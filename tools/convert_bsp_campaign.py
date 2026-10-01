"""Convert the supplied Lost School BSP/NAV campaign to native MC structures.

No Source binary is executed. All source files are read-only. Solid convex
brushes are voxelized against their actual planes, displacement triangles are
sampled, and native NAV connectivity determines the playable route. Thin
walls/doorways and ladders receive an explicit, audited Minecraft adaptation.
The output contains vanilla blocks, not Source textures, music or models.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import itertools
import json
import math
from pathlib import Path
import shutil
import time

import numpy as np
import bsp_tool
from bsp_tool.branches.valve import left4dead2
import source_nav
import structure_writer

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / 'src/main/resources/data/muxi_outbreak'
SCALE = 24.0
CHAPTERS = [('lost', '9号宿舍'), ('lostschool_2', '8号宿舍'), ('lostschool_3', '大操场')]


def json_write(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def source_pos(entity):
    return tuple(map(float, entity.get('origin', '0 0 0').split()))


def mapped(v):
    return np.asarray([v[0], v[2], -v[1]], dtype=float) / SCALE


def model_brushes(bsp, index):
    todo = [bsp.MODELS[index].node]
    visited, result = set(), set()
    while todo:
        node = todo.pop()
        if node in visited:
            continue
        visited.add(node)
        if node >= 0:
            todo.extend(bsp.NODES[node].children)
        else:
            leaf = bsp.LEAVES[-node - 1]
            result.update(bsp.LEAF_BRUSHES[leaf.first_leaf_brush:leaf.first_leaf_brush + leaf.num_leaf_brushes])
    return result


def material(name: str) -> str | None:
    n = name.lower()
    if any(s in n for s in ('toolssky', 'toolstrigger', 'toolsclip', 'toolshint', 'toolsskip', 'climb_')):
        return None
    for words, block in [
        (('glass', 'window'), 'light_gray_stained_glass'),
        (('grass', 'leaves'), 'grass_block'),
        (('mud', 'dirt'), 'coarse_dirt'),
        (('road', 'asphalt'), 'gray_concrete'),
        (('brick',), 'bricks'),
        (('wood', 'plank'), 'spruce_planks'),
        (('metal', 'locker'), 'iron_block'),
        (('tile', 'ceiling_white'), 'smooth_quartz'),
        (('plaster',), 'white_concrete'),
        (('rock', 'stone'), 'stone'),
    ]:
        if any(w in n for w in words):
            return 'minecraft:' + block
    return 'minecraft:light_gray_concrete'


class VoxelMap:
    def __init__(self, bsp, index: int):
        self.bsp = bsp
        self.offset = np.asarray([index * 2048, 80, 0], dtype=int)
        a, b = map(mapped, (bsp.MODELS[0].bounds.mins, bsp.MODELS[0].bounds.maxs))
        self.origin = np.floor(np.minimum(a, b)).astype(int) - 4 + self.offset
        upper = np.ceil(np.maximum(a, b)).astype(int) + 5 + self.offset
        shape = tuple((upper - self.origin).tolist())
        if np.prod(shape) > 160_000_000:
            raise ValueError(f'unsafe voxel volume {shape}')
        self.grid = np.zeros(shape, dtype=np.uint8)
        self.palette = [{'Name': 'minecraft:air'}]
        self.palette_ids = {'minecraft:air': 0}
        self.stats = Counter()

    def state(self, name: str, **properties):
        key = name + json.dumps(properties, sort_keys=True)
        if not properties and name == 'minecraft:air':
            return 0
        if key not in self.palette_ids:
            if len(self.palette) >= 256:
                raise ValueError('palette exceeds uint8')
            self.palette_ids[key] = len(self.palette)
            self.palette.append({'Name': name, **({'Properties': properties} if properties else {})})
        return self.palette_ids[key]

    def transform(self, v, rounded=False):
        p = mapped(v) + self.offset
        return tuple(np.rint(p).astype(int).tolist()) if rounded else p

    def get(self, pos):
        p = np.asarray(pos, dtype=int) - self.origin
        if np.any(p < 0) or np.any(p >= self.grid.shape):
            return 0
        return int(self.grid[tuple(p)])

    def put(self, pos, state):
        p = np.asarray(pos, dtype=int) - self.origin
        if np.any(p < 0) or np.any(p >= self.grid.shape):
            raise ValueError(f'point outside map: {pos}')
        self.grid[tuple(p)] = state

    def brush(self, index, origin=(0., 0., 0.)):
        b = self.bsp.BRUSHES[index]
        if not int(b.contents) & (1 | 2 | 8):
            self.stats['non_solid_brushes_skipped'] += 1
            return
        sides = self.bsp.BRUSH_SIDES[b.first_side:b.first_side + b.num_sides]
        textures = []
        planes = []
        for s in sides:
            plane = self.bsp.PLANES[s.plane]
            normal = np.asarray(list(plane.normal), dtype=float)
            # Entity submodels are local; apply the entity's source-space origin.
            distance = plane.distance + np.dot(normal, origin)
            n = normal[[0, 2, 1]] * [1, 1, -1]
            d = distance / SCALE + np.dot(n, self.offset)
            planes.append((n, d))
            if s.texture_info >= 0:
                info = self.bsp.TEXTURE_INFO[s.texture_info]
                tex = self.bsp.TEXTURE_DATA[info.texture_data]
                textures.append(self.bsp.TEXTURE_DATA_STRING_DATA[tex.name_index])
        if any('toolssky' in t.lower() for t in textures):
            self.stats['sky_brushes_skipped'] += 1
            return
        visible = [t for t in textures if not t.lower().startswith('tools/') and material(t)]
        if not visible:
            self.stats['invisible_brushes_skipped'] += 1
            return
        block = self.state(material(Counter(visible).most_common(1)[0][0]))
        low, high = np.full(3, -np.inf), np.full(3, np.inf)
        for n, d in planes:
            nonzero = np.flatnonzero(np.abs(n) > 1e-6)
            if len(nonzero) == 1:
                axis = nonzero[0]
                if n[axis] > 0:
                    high[axis] = min(high[axis], d / n[axis])
                else:
                    low[axis] = max(low[axis], d / n[axis])
        if not np.all(np.isfinite(np.r_[low, high])):
            # Non-axial hull fallback: actual intersections, not its guessed box.
            pts = []
            for triple in itertools.combinations(planes, 3):
                mat = np.asarray([p[0] for p in triple])
                if abs(np.linalg.det(mat)) < 1e-8:
                    continue
                p = np.linalg.solve(mat, [t[1] for t in triple])
                if all(np.dot(n, p) <= d + 1e-4 for n, d in planes):
                    pts.append(p)
            if not pts:
                raise ValueError(f'cannot resolve convex brush {index}')
            low, high = np.min(pts, axis=0), np.max(pts, axis=0)
        lo = np.maximum(np.floor(low - .1).astype(int), self.origin)
        hi = np.minimum(np.floor(high + .1).astype(int) + 1, self.origin + self.grid.shape)
        if np.any(hi <= lo):
            return
        # Chunk the candidate box to bound peak working memory.
        for x in range(int(lo[0]), int(hi[0]), 16):
            xr = np.arange(x, min(x + 16, hi[0]), dtype=float)[:, None, None] + .5
            yr = np.arange(lo[1], hi[1], dtype=float)[None, :, None] + .5
            zr = np.arange(lo[2], hi[2], dtype=float)[None, None, :] + .5
            mask = np.ones((len(xr), yr.shape[1], zr.shape[2]), dtype=bool)
            for n, d in planes:
                mask &= n[0] * xr + n[1] * yr + n[2] * zr <= d + .36
            view = self.grid[x-self.origin[0]:min(x+16,hi[0])-self.origin[0],
                             lo[1]-self.origin[1]:hi[1]-self.origin[1],
                             lo[2]-self.origin[2]:hi[2]-self.origin[2]]
            view[mask] = block
        self.stats['solid_brushes_voxelized'] += 1

    def triangle(self, a, b, c, state):
        a, b, c = [self.transform(v) for v in (a, b, c)]
        divisions = max(1, math.ceil(max(np.linalg.norm(b-a), np.linalg.norm(c-a), np.linalg.norm(c-b)) * 2))
        if divisions > 4096:
            raise ValueError('unbounded triangle')
        for j in range(divisions + 1):
            u = j / divisions
            v = np.arange(divisions - j + 1) / divisions
            pts = np.floor(a + (b-a) * u + (c-a) * v[:, None]).astype(int) - self.origin
            valid = np.all((pts >= 0) & (pts < self.grid.shape), axis=1)
            pts = pts[valid]
            self.grid[pts[:, 0], pts[:, 1], pts[:, 2]] = state

    def geometry(self):
        for index in sorted(model_brushes(self.bsp, 0)):
            self.brush(index)
        for e in self.bsp.ENTITIES:
            if e.get('classname') in {'func_breakable', 'func_brush', 'func_wall', 'func_detail'} and e.get('model','').startswith('*'):
                for index in sorted(model_brushes(self.bsp, int(e['model'][1:]))):
                    self.brush(index, source_pos(e))
        for face_index in range(self.bsp.MODELS[0].num_faces):
            face = self.bsp.FACES[face_index]
            if face.displacement_info < 0:
                continue
            mesh = self.bsp.displacement_mesh(face_index)
            block = material(mesh.material.name)
            if not block:
                continue
            state = self.state(block)
            for polygon in mesh.polygons:
                for j in range(1, len(polygon.vertices)-1):
                    self.triangle(polygon.vertices[0].position, polygon.vertices[j].position, polygon.vertices[j+1].position, state)
                    self.stats['displacement_triangles'] += 1
        # Source light positions become vanilla fixtures; no dependency on L4D2 assets.
        for e in self.bsp.ENTITIES:
            if e.get('classname') == 'light':
                self.put(self.transform(source_pos(e), True), self.state('minecraft:sea_lantern'))
        self.stats['static_props_not_mesh_converted'] = len(self.bsp.GAME_LUMP.sprp.props)
        self.stats['dynamic_props_not_mesh_converted'] = sum(e.get('classname','').startswith('prop_') for e in self.bsp.ENTITIES)

    def adapt_route(self, points):
        cells = []
        for a, b in zip(points, points[1:]):
            a, b = self.transform(a), self.transform(b)
            count = max(1, int(np.ceil(np.max(np.abs(b-a)) * 3)))
            for t in np.linspace(0, 1, count + 1):
                p = tuple(np.rint(a + (b-a)*t).astype(int).tolist())
                if not cells or cells[-1] != p:
                    cells.append(p)
        # Include supercover corner cells so two diagonal walls cannot pinch players.
        route = []
        for p in cells:
            if route and p[0] != route[-1][0] and p[2] != route[-1][2]:
                route.append((p[0], max(p[1], route[-1][1]), route[-1][2]))
            route.append(p)
        floor = self.state('minecraft:stone_bricks')
        ladder = self.state('minecraft:ladder', facing='east', waterlogged='false')
        vertical = set()
        for a, b in zip(route, route[1:]):
            if a[0] == b[0] and a[2] == b[2] and a[1] != b[1]:
                for y in range(min(a[1], b[1]), max(a[1], b[1])+1):
                    vertical.add((a[0], y, a[2]))
        # Only restore the NAV main route, not a synthetic corridor across the map.
        for x, y, z in route:
            for yy in (y, y+1):
                if self.get((x, yy, z)):
                    self.stats['route_clearance_blocks_removed'] += 1
                self.put((x, yy, z), 0)
            if not self.get((x, y-1, z)) and (x, y-1, z) not in vertical:
                self.put((x, y-1, z), floor)
                self.stats['route_support_blocks_added'] += 1
        for p in sorted(vertical):
            x,y,z = p
            self.put((x-1,y,z), floor)
            self.put(p, ladder)
            self.put((x,y+1,z), ladder)
        self.stats['route_ladder_cells'] = len(vertical)
        # Spawn/trigger points stay on an actual route cell and get a clear 3x3 pad.
        return route

    def pad(self, point):
        x,y,z = point
        for dx in (-1,0,1):
            for dz in (-1,0,1):
                self.put((x+dx,y-1,z+dz), self.state('minecraft:stone_bricks'))
                self.put((x+dx,y,z+dz),0)
                self.put((x+dx,y+1,z+dz),0)

    def finalize_route(self, route):
        """Resolve *all* clearances first; later ladder supports cannot block another stair."""
        clearance = {(x,y+d,z) for x,y,z in route for d in (0,1,2)}
        support = {(x,y-1,z) for x,y,z in route} - clearance
        floor = self.state('minecraft:stone_bricks')
        for p in support:
            if self.get(p) == 0:
                self.put(p,floor)
        for p in clearance:
            self.put(p,0)
        for x,y,z in route:
            if (x,y-1,z) not in clearance:
                continue
            for dx,dz,facing in [(-1,0,'east'),(1,0,'west'),(0,-1,'south'),(0,1,'north')]:
                backing=(x+dx,y,z+dz)
                if backing not in clearance:
                    self.put(backing,floor)
                    self.put((x,y,z),self.state('minecraft:ladder',facing=facing,waterlogged='false'))
                    break
            else:
                self.put((x,y,z),self.state('minecraft:scaffolding',distance='0',bottom='false',waterlogged='false'))
        self.stats['validated_route_cells'] = len(route)
        passable = {i for i,p in enumerate(self.palette) if p['Name'] in {
            'minecraft:air','minecraft:ladder','minecraft:scaffolding'}}
        for x,y,z in route:
            if self.get((x,y,z)) not in passable or self.get((x,y+1,z)) not in passable:
                raise ValueError(f'blocked route after adaptation: {(x,y,z)}')
            if self.get((x,y-1,z)) == 0 and self.get((x,y,z)) == 0:
                raise ValueError(f'unsupported route: {(x,y,z)}')

    def export(self, name, staging: Path):
        entries = []
        for x in range(0, self.grid.shape[0],32):
            for y in range(0, self.grid.shape[1],32):
                for z in range(0, self.grid.shape[2],32):
                    tile = self.grid[x:x+32,y:y+32,z:z+32]
                    coords = np.argwhere(tile != 0)
                    if not len(coords):
                        continue
                    used = sorted(set(tile[tuple(coords.T)].tolist()))
                    remap = {v:i for i,v in enumerate(used)}
                    state_palette = [self.palette[v] for v in used]
                    rel = f'lostschool/{name}/{x}_{y}_{z}.nbt'
                    path = staging / 'structure' / rel
                    count = structure_writer.write(path, state_palette,
                        [(p.tolist(),remap[int(tile[tuple(p)])]) for p in coords], tile.shape)
                    entries.append({'resource':f'muxi_outbreak:structure/{rel}',
                                    'origin':(self.origin + [x,y,z]).tolist(), 'blocks':count,
                                    'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
        self.stats['blocks'] = int(np.count_nonzero(self.grid))
        self.stats['structures'] = len(entries)
        return entries


def route_points(areas, start, end):
    result = [source_nav.project(areas[0], start), areas[0].center]
    for a,b in zip(areas, areas[1:]):
        # Add the real shared-edge portal before turning to the next area center.
        lo = np.maximum(a.corners[:2],b.corners[:2])
        hi = np.minimum(a.corners[3:5],b.corners[3:5])
        if np.all(hi >= lo - 1):
            mid = (lo+hi)/2
            result.append((float(mid[0]),float(mid[1]),(a.center[2]+b.center[2])/2))
        result.append(b.center)
    result.append(source_nav.project(areas[-1], end))
    return result


def resolved_start(bsp, start):
    """Resolve unconditional spawn-room teleport volumes, as Source does at entry."""
    visited=[]
    for _ in range(8):
        for e in bsp.ENTITIES:
            if e['classname']!='trigger_teleport' or e.get('StartDisabled','0')!='0' or not e.get('model','').startswith('*'):
                continue
            origin=np.asarray(source_pos(e))
            model=bsp.MODELS[int(e['model'][1:])]
            lo=np.asarray(list(model.bounds.mins))+origin
            hi=np.asarray(list(model.bounds.maxs))+origin
            if np.all(np.asarray(start)>=lo) and np.all(np.asarray(start)<=hi):
                destination=next((d for d in bsp.ENTITIES if d.get('targetname')==e.get('target') and d['classname']=='info_teleport_destination'),None)
                if destination is not None:
                    if e['hammerid'] in visited:raise ValueError('spawn teleport loop')
                    visited.append(e['hammerid']);start=source_pos(destination)
                    break
        else:return start,visited
    raise ValueError('too many spawn teleports')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT/'maps/workshop/lostschool_extracted/maps')
    parser.add_argument('--output', type=Path, default=RESOURCE)
    args = parser.parse_args()
    staging = ROOT/'build/bsp-conversion-staging'
    staging.mkdir(parents=True,exist_ok=True)
    output = {'id':'lostschool','title':'逃离学院','mode':'campaign',
              'dimension':'muxi_outbreak:campaign','geometry':'muxi_outbreak:outbreak_geometry/lostschool.json',
              'safeRooms':[],'commonSpawns':[],'hordeSpawns':[],'bossSpawns':[],
              'itemSpawns':[],'chapters':[],'panicEvents':[], 'source':{
              'workshopId':'795271842','author':'未名','scaleSourceUnitsPerBlock':SCALE,
              'conversion':'BSP brushes + displacement surfaces + directed NAV route; props and Source scripts are not one-to-one ports'}}
    manifest = {'version':1,'id':'lostschool','structures':[]}
    reports = []
    for index,(name,title) in enumerate(CHAPTERS):
        start_time=time.monotonic()
        source = args.source/f'{name}.bsp'
        bsp=bsp_tool.ValveBsp.from_file(left4dead2,str(source))
        if bsp.loading_errors:
            raise ValueError(f'BSP decoding failed: {bsp.loading_errors}')
        nav=source_nav.load(source.with_suffix('.nav'))
        start=next(source_pos(e) for e in bsp.ENTITIES if e['classname']=='info_player_start')
        if index<2:
            change=next(e for e in bsp.ENTITIES if e['classname']=='info_changelevel')
            end=next(source_pos(e) for e in bsp.ENTITIES if e.get('targetname')==change['landmark'])
        else:
            end=next(source_pos(e) for e in bsp.ENTITIES if e['classname']=='trigger_finale')
        gameplay_start,spawn_teleports=resolved_start(bsp,start)
        nav_route=source_nav.route(nav,gameplay_start,end)
        vox=VoxelMap(bsp,index)
        print(f'{name}: parsing {len(bsp.BRUSHES)} brushes, NAV {len(nav)} areas; voxelizing...',flush=True)
        vox.geometry()
        cells=vox.adapt_route(route_points(nav_route,gameplay_start,end))
        spawn,finish=cells[0],cells[-1]
        vox.pad(spawn);vox.pad(finish)
        # Important route points are generated from source NAV, not handwritten coordinates.
        route_sample=[list(cells[i]) for i in range(0,len(cells),max(1,len(cells)//40))]
        route_sample.append(list(finish))
        output['chapters'].append({'id':name,'title':title,'start':list(spawn),'end':list(finish),
                                   'route':route_sample,'navAreaIds':[a.id for a in nav_route]})
        if index==0:output['start']=list(spawn)
        if index<2:
            output['safeRooms'].append({'id':name+'_checkpoint','min':[finish[0]-2,finish[1]-1,finish[2]-2],
                'max':[finish[0]+2,finish[1]+3,finish[2]+2],'nextSection':index+1})
        else:
            output['finish']=list(finish)
            output['finale']={'pos':list(finish),'holdSeconds':60,'radius':7.0,'waves':3}
        points=[]
        for a in nav_route[2:-2]:
            target=vox.transform(a.center)
            p=min(cells,key=lambda c:math.dist(c,target))
            if all(math.dist(p,q)>=7 for q in points) and math.dist(p,spawn)>7 and math.dist(p,finish)>4:
                points.append(p)
        if not points:raise ValueError(f'no spawn points on {name}')
        for p in points:
            output['commonSpawns'].append({'section':index,'pos':list(p)})
        for p in points[::max(1,len(points)//8)]:
            output['hordeSpawns'].append({'section':index,'pos':list(p)})
            output['bossSpawns'].append({'section':index,'pos':list(p)})
        for p in (spawn,points[len(points)//2]):
            output['itemSpawns'].append({'section':index,'pos':list(p),'preset':'medical'})
        if index<2:
            output['panicEvents'].append({'section':index,'pos':list(cells[len(cells)//2]),'waves':2})
        vox.finalize_route(cells)
        entries=vox.export(name,staging)
        manifest['structures'].extend(entries)
        # Cache the actual voxel grid for independent structural/path tests and previews.
        np.savez_compressed(ROOT/f'build/{name}-voxels.npz',grid=vox.grid,origin=vox.origin,
                            palette=json.dumps(vox.palette),route=np.asarray(cells))
        report={'chapter':name,'bspSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                'navSha256':hashlib.sha256(source.with_suffix('.nav').read_bytes()).hexdigest(),
                'entities':len(bsp.ENTITIES),'navAreas':len(nav),'routeAreas':len(nav_route),
                'startSource':start,'resolvedStartSource':gameplay_start,'spawnTeleportHammerIds':spawn_teleports,
                'endSource':end,'startMinecraft':spawn,'endMinecraft':finish,
                'stats':dict(vox.stats),'seconds':round(time.monotonic()-start_time,2)}
        reports.append(report)
        print(json.dumps(report,ensure_ascii=False),flush=True)
        del vox
    manifest['blocks']=sum(e['blocks'] for e in manifest['structures'])
    manifest['sha256']=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
    json_write(staging/'outbreak_geometry/lostschool.json',manifest)
    json_write(staging/'outbreak_maps/lostschool.json',output)
    # Publish only after all three chapters successfully convert.
    destination=args.output/'structure/lostschool'
    if destination.exists():shutil.rmtree(destination)
    shutil.copytree(staging/'structure/lostschool',destination)
    for rel in ('outbreak_geometry/lostschool.json','outbreak_maps/lostschool.json'):
        (args.output/rel).parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(staging/rel,args.output/rel)
    json_write(ROOT/'build/conversion-report.json',{'chapters':reports,'totalBlocks':manifest['blocks'],
        'structures':len(manifest['structures']),'geometrySha256':manifest['sha256'],
        'dependencies':{p:importlib.metadata.version(p) for p in ('bsp_tool','numpy')},
        'limitations':['Static/dynamic MDL props are not mesh-converted; L4D2 base-game assets are absent.',
                       'Original Source puzzle scripts/cinematics are replaced by native Outbreak checkpoint/panic/finale rules.']})
    print('CONVERSION_COMPLETE',manifest['blocks'],len(manifest['structures']),manifest['sha256'],flush=True)
    if args.output.resolve()==RESOURCE.resolve():
        from import_source_supplies import generate
        generate()


if __name__=='__main__':main()
