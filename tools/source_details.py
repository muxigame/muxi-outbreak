"""Add audited Source props and light fields without changing existing geometry.

Private BSP/NAV inputs stay outside the distributable. Model mappings are native
approximations, not recovered meshes. Every instance receives a coverage record.
"""
from __future__ import annotations
import argparse, collections, gzip, hashlib, json, math, shutil, struct
from pathlib import Path
import numpy as np
import nbt_reader, source_nav, structure_writer

NAMES = ('lost', 'lostschool_2', 'lostschool_3')
WORLD_LIGHT = struct.Struct('<12f3i7f3i')

def transform(p, chapter):
    return np.asarray([p[0] / 24 + chapter * 2048, p[2] / 24 + 80, -p[1] / 24])

def compiled_lights(path):
    raw = path.read_bytes()
    if raw[:4] != b'VBSP' or struct.unpack_from('<I', raw, 4)[0] != 21:
        raise ValueError('only L4D2 VBSP21 is supported')
    result = {}
    # L4D2 uses version/offset/length/fourCC header order; v21 adds shadow offset.
    for number, label in ((15, 'LDR'), (54, 'HDR')):
        version, offset, length, compressed = struct.unpack_from('<4I', raw, 8 + number * 16)
        if version != 1 or compressed or length % WORLD_LIGHT.size or offset + length > len(raw):
            raise ValueError('unsupported/truncated WORLDLIGHTS')
        records = []
        for ordinal, fields in enumerate(WORLD_LIGHT.iter_unpack(raw[offset:offset + length])):
            if not all(math.isfinite(float(x)) for x in fields) or fields[13] not in range(6):
                raise ValueError('invalid WORLDLIGHTS record')
            records.append(dict(index=ordinal, origin=list(fields[:3]), intensity=list(fields[3:6]),
                normal=list(fields[6:9]), shadowOffset=list(fields[9:12]), cluster=fields[12],
                type=fields[13], style=fields[14], stopdot=fields[15], stopdot2=fields[16],
                exponent=fields[17], radius=fields[18], constant=fields[19], linear=fields[20],
                quadratic=fields[21], flags=fields[22], texinfo=fields[23], owner=fields[24]))
        result[label] = records
    return result

def model_rule(model):
    stem = Path(model).stem.lower()
    if model.lower() == 'models/props/de_inferno/bed.mdl': return 'bed', 0
    if stem == 'hospital_bed': return 'bed', 0
    if 'cabinet' in stem: return 'cabinet', 0
    if 'table' in stem or 'desk' in stem: return 'table', 0
    if ('chair' in stem or 'stool' in stem) and 'dead_' not in stem: return 'chair', 0
    if stem in {'light_inset', 'wall_light', 'light_ceiling', 'light_shop2', 'lamppost03a_off', 'surgery_lamp', 'floodlight'}:
        return 'fixture', 0
    if stem in {'static_crate_40', 'crate_40', 'wood_crate', 'boxes_garage_lower', 'boxes_frontroom'}: return 'crate', 0
    return None, 0

class Grid:
    def __init__(self, resource, manifest, chapter):
        pieces = [p for p in manifest['structures'] if '/' + chapter + '/' in p['resource']]
        self.origin = np.min([p['origin'] for p in pieces], axis=0)
        high = np.max([np.asarray(p['origin']) + 32 for p in pieces], axis=0)
        self.grid = np.zeros(tuple(high - self.origin), dtype=np.uint8)
        self.palette = [{'Name': 'minecraft:air'}]; self.ids = {self.key(self.palette[0]): 0}
        for p in pieces:
            raw = (resource / p['resource'].split(':')[1]).read_bytes()
            if hashlib.sha256(raw).hexdigest() != p['sha256']: raise ValueError('input structure hash differs')
            nbt = nbt_reader.loads(gzip.decompress(raw))[1]
            ids = [self.state(s['Name'], **s.get('Properties', {})) for s in nbt['palette']]
            positions = np.asarray([b['pos'] for b in nbt['blocks']]) + p['origin'] - self.origin
            self.grid[tuple(positions.T)] = [ids[b['state']] for b in nbt['blocks']]
        self.base = self.grid.copy(); self.protected = np.zeros_like(self.grid, dtype=bool)
    @staticmethod
    def key(s): return json.dumps(s, sort_keys=True)
    def state(self, name, **properties):
        state = {'Name': name, **({'Properties': properties} if properties else {})}
        key = self.key(state)
        if key not in self.ids:
            if len(self.palette) >= 256: raise ValueError('palette overflow')
            self.ids[key] = len(self.palette); self.palette.append(state)
        return self.ids[key]
    def at(self, p):
        q = np.asarray(p, dtype=int) - self.origin
        return tuple(q) if np.all(q >= 0) and np.all(q < self.grid.shape) else None
    def get(self, p):
        q = self.at(p); return int(self.grid[q]) if q is not None else 0
    def reserve(self, lo, hi):
        lo = np.maximum(np.asarray(lo, dtype=int) - self.origin, 0)
        hi = np.minimum(np.asarray(hi, dtype=int) - self.origin + 1, self.grid.shape)
        if np.all(hi > lo): self.protected[tuple(slice(a,b) for a,b in zip(lo,hi))] = True
    def solid(self, p):
        return self.palette[self.get(p)]['Name'] not in {'minecraft:air','minecraft:light','minecraft:ladder','minecraft:scaffolding'}
    def place(self, cells):
        coords = [self.at(p) for p, state in cells]
        if any(p is None for p in coords): return 'outside_native_extent'
        if any(self.protected[p] for p in coords): return 'protected_navigation_or_gameplay'
        if any(self.grid[p] != 0 for p in coords): return 'existing_geometry_or_prior_prop'
        for p, (_, state) in zip(coords, cells): self.grid[p] = state
        return None
    def verify(self):
        occupied = self.base != 0
        if not np.array_equal(self.grid[occupied], self.base[occupied]): raise ValueError('original geometry changed')
        added = (self.base == 0) & (self.grid != 0)
        solids = np.asarray([s['Name'] != 'minecraft:light' for s in self.palette])[self.grid]
        if np.any(added & solids & self.protected): raise ValueError('protected route obstructed')
        return {'originalBlocksPreserved': int(occupied.sum()), 'addedBlocks': int(added.sum())}
    def export(self, name, out):
        entries = []
        for x in range(0,self.grid.shape[0],32):
            for y in range(0,self.grid.shape[1],32):
                for z in range(0,self.grid.shape[2],32):
                    tile=self.grid[x:x+32,y:y+32,z:z+32]; positions=np.argwhere(tile)
                    if not len(positions): continue
                    used=sorted(set(tile[tuple(positions.T)].tolist())); ids={v:i for i,v in enumerate(used)}
                    rel=f'structure/lostschool/{name}/{x}_{y}_{z}.nbt'; path=out/rel
                    count=structure_writer.write(path,[self.palette[v] for v in used],[(p.tolist(),ids[int(tile[tuple(p)])]) for p in positions],tile.shape)
                    entries.append(dict(resource='muxi_outbreak:'+rel,origin=(self.origin+[x,y,z]).tolist(),blocks=count,sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        return entries

def protect(g, nav, campaign, index):
    # Reserve actual NAV rectangles, not a straight-line substitute for their graph.
    for area in nav.values():
        a=area.corners; lo=transform((min(a[0],a[3]),max(a[1],a[4]),min(a[2],a[5],a[6],a[7])),index)
        hi=transform((max(a[0],a[3]),min(a[1],a[4]),max(a[2],a[5],a[6],a[7])),index)
        g.reserve(np.floor(lo),np.ceil(hi)+[0,2,0])
    chapter=campaign['chapters'][index]
    for p in chapter['route']:
        g.reserve(np.asarray(p)-[1,0,1],np.asarray(p)+[1,2,1])
    for key in ('commonSpawns','hordeSpawns','bossSpawns','itemSpawns','supplies'):
        for s in campaign.get(key,[]):
            if s.get('section',index)==index and s.get('pos'):
                p=np.rint(s['pos']).astype(int);g.reserve(p-[1,0,1],p+[1,2,1])
    for room in campaign.get('startRooms',[])+campaign.get('safeRooms',[]):
        if 'min' in room: g.reserve(room['min'],room['max'])
        for d in room.get('doors',[]):
            p=np.asarray(d);g.reserve(p-[1,0,1],p+[1,2,1])

def furniture(g, prop, index):
    kind,pivot=model_rule(prop['model']); row=dict(prop, mapping=kind, modelMeshRecovered=False)
    if not kind: return dict(row,status='unmapped',reason='no_reviewed_native_model_rule')
    angles=prop['angles']; origin=prop['origin']
    if abs(angles[0])>5 or abs(angles[2])>5 or prop.get('scale',1)!=1:
        return dict(row,status='manual_review',reason='tilted_or_scaled_model')
    pos=np.rint(transform(origin,index)).astype(int); pos[1]+=pivot
    direction=np.asarray([math.cos(math.radians(angles[1])),0,-math.sin(math.radians(angles[1]))])
    axis=int(np.argmax(np.abs(direction))); step=np.zeros(3,dtype=int);step[axis]=1 if direction[axis]>=0 else -1
    facing={(1,0,0):'east',(-1,0,0):'west',(0,0,1):'south',(0,0,-1):'north'}[tuple(step)]
    cells=[]
    if kind!='fixture':
        # Source model pivots vary; anchor vertically at the same source X/Z only.
        candidates=[int(pos[1])+d for d in (0,-1,1,-2,2)]
        y=next((y for y in candidates if g.solid((pos[0],y-1,pos[2])) and not g.solid((pos[0],y,pos[2]))),None)
        if y is None:return dict(row,status='manual_review',reason='no_support_at_source_xz')
        pos[1]=y
    if kind=='bed':
        head=pos+step
        if not g.solid(head-[0,1,0]): return dict(row,status='manual_review',reason='bed_head_unsupported')
        for p,part in ((pos,'foot'),(head,'head')):cells.append((p,g.state('minecraft:white_bed',facing=facing,part=part,occupied='false')))
    elif kind=='chair':cells=[(pos,g.state('minecraft:spruce_stairs',facing=facing,half='bottom',shape='straight',waterlogged='false'))]
    elif kind=='table':
        cells=[(pos,g.state('minecraft:spruce_fence',east='false',west='false',north='false',south='false',waterlogged='false')),
               (pos+[0,1,0],g.state('minecraft:spruce_slab',type='bottom',waterlogged='false'))]
    elif kind=='cabinet':
        cells=[(pos,g.state('minecraft:bookshelf')),(pos+[0,1,0],g.state('minecraft:spruce_planks'))]
    elif kind=='crate':cells=[(pos,g.state('minecraft:spruce_planks'))]
    elif kind=='fixture':
        # Source fixture models are not emitters, including explicitly off poles.
        cells=[(pos,g.state('minecraft:iron_bars',east='false',west='false',north='false',south='false',waterlogged='false'))]
    reason=g.place(cells)
    return dict(row,status='skipped' if reason else 'placed',reason=reason, nativeAnchor=pos.tolist(),nativeFacing=facing,
                pivotOffsetBlocks=pivot, approximation='vanilla geometry; cardinal yaw; bounded vertical support; original X/Z unchanged',
                cells=[{'pos':p.tolist(),'state':g.palette[s]} for p,s in cells] if not reason else [])

def light_field(g, lights, index):
    # Native monochrome approximation of Source intensity/attenuation, ray occluded.
    # Sampling every 3 cells allows Minecraft's own light engine to interpolate.
    for level in range(1,16):g.state('minecraft:light',level=str(level),waterlogged='false')
    opaque=np.asarray([s['Name'] not in {'minecraft:air','minecraft:light','minecraft:ladder','minecraft:scaffolding','minecraft:light_gray_stained_glass','minecraft:sea_lantern','minecraft:iron_bars'} for s in g.palette])
    rows=[]
    for light in lights:
        row=dict(light)
        if light['type']!=1:
            rows.append(dict(row,status='environment_preserved',approximation='native existing skylight; Source sky RGB/direction not reproduced'));continue
        src=transform(light['origin'],index); maximum=36
        lo=np.maximum(np.floor(src-maximum).astype(int),g.origin);hi=np.minimum(np.ceil(src+maximum).astype(int),g.origin+g.grid.shape-1)
        axes=[np.arange(a,b+1,3) for a,b in zip(lo,hi)]
        pts=np.asarray(np.meshgrid(*axes,indexing='ij')).reshape(3,-1).T
        q=pts-g.origin
        free=np.asarray([s['Name'] in {'minecraft:air','minecraft:light'} for s in g.palette])[g.grid[tuple(q.T)]]
        pts=pts[free]
        delta=pts+.5-src; dist=np.linalg.norm(delta,axis=1)
        dsource=np.maximum(dist*24,24); denominator=np.maximum(light['constant']+light['linear']*dsource+light['quadratic']*dsource*dsource,1)
        energy=max(light['intensity'])/denominator
        # Explicit exposure mapping, no client gamma / world ambient modification.
        levels=np.clip(np.rint(15*np.sqrt(energy/(energy+.018))),0,15).astype(int)
        keep=(dist<=maximum)&(levels>=5);pts=pts[keep];delta=delta[keep];levels=levels[keep]
        visible=np.ones(len(pts),dtype=bool)
        if len(pts):
            steps=max(2,int(np.ceil(np.max(np.linalg.norm(delta,axis=1))*2)))
            for t in np.linspace(0,1,steps)[2:-1]:
                ray=np.floor(src+delta*t).astype(int)-g.origin
                valid=np.all(ray>=0,axis=1)&np.all(ray<g.grid.shape,axis=1)
                blocked=np.ones(len(ray),dtype=bool);blocked[valid]=opaque[g.grid[tuple(ray[valid].T)]]
                visible &= ~blocked
            pts=pts[visible];levels=levels[visible]
        installed=0
        for p,level in zip(pts,levels):
            at=g.at(p);old=g.palette[int(g.grid[at])]
            if old['Name'] not in {'minecraft:air','minecraft:light'}:continue
            if old['Name']=='minecraft:light' and int(old['Properties']['level'])>=level:continue
            g.grid[at]=g.state('minecraft:light',level=str(level),waterlogged='false');installed+=1
        rows.append(dict(row,status='mapped',nativeOrigin=src.tolist(),fieldWrites=installed,maxRadiusBlocks=maximum,sourceColorPreservedInMetadata=True,nativeMonochromeApproximation=True))
    return rows

def read_props(bsp,name):
    result=[]
    for i,p in enumerate(bsp.GAME_LUMP.sprp.props):
        result.append(dict(id=f'{name}:staticprop:{i}',model=bsp.GAME_LUMP.sprp.model_names[p.name_index],origin=list(p.origin),angles=list(p.angles),scale=1))
    for i,e in enumerate(bsp.ENTITIES):
        if not e.get('classname','').startswith('prop_'):continue
        if not e.get('origin') or not e.get('model'):continue
        result.append(dict(id=f'{name}:entity:{e.get("hammerid",i)}',model=e['model'],origin=list(map(float,e['origin'].split())),angles=list(map(float,e.get('angles','0 0 0').split())),scale=float(e.get('modelscale','1')),sourceClass=e['classname']))
    return result

def generate(source, resource, output):
    import bsp_tool
    from bsp_tool.branches.valve import left4dead2
    campaign=json.loads((resource/'outbreak_maps/lostschool.json').read_text(encoding='utf-8'))
    original=json.loads((resource/'outbreak_geometry/lostschool.json').read_text(encoding='utf-8'))
    report=dict(version=1,sourceScale=24,layoutPreserved=True,baseGeometrySha256=original['sha256'],sourceAssetsBundled=False,
                lightingPolicy='HDR once, v21 100-byte records; source attenuation + occluded 3-cell native light samples; radius36; monochrome',chapters=[])
    manifest=dict(version=1,id='lostschool',structures=[])
    for index,name in enumerate(NAMES):
        bsp_path=source/f'{name}.bsp';bsp=bsp_tool.ValveBsp.from_file(left4dead2,str(bsp_path))
        if bsp.loading_errors:raise ValueError(str(bsp.loading_errors))
        nav=source_nav.load(bsp_path.with_suffix('.nav'));g=Grid(resource,original,name);protect(g,nav,campaign,index)
        props=[furniture(g,p,index) for p in read_props(bsp,name)]
        compiled=compiled_lights(bsp_path);chosen=compiled['HDR'] or compiled['LDR']
        entity_points=[np.asarray(list(map(float,e['origin'].split()))) for e in bsp.ENTITIES if e['classname']=='light']
        points=[l for l in chosen if l['type']==1]
        if len(points)!=len(entity_points) or any(not any(np.linalg.norm(np.asarray(l['origin'])-p)<.01 for p in entity_points) for l in points):raise ValueError('compiled/entity point-light mismatch')
        lights=light_field(g,chosen,index);verified=g.verify();entries=g.export(name,output);manifest['structures'].extend(entries)
        row=dict(chapter=name,bspSha256=hashlib.sha256(bsp_path.read_bytes()).hexdigest(),navSha256=hashlib.sha256(bsp_path.with_suffix('.nav').read_bytes()).hexdigest(),
            props=props,lights=lights,propCounts=dict(collections.Counter(p['status'] for p in props)),**verified)
        report['chapters'].append(row);print(name,row['propCounts'],verified,flush=True)
    manifest['blocks']=sum(p['blocks'] for p in manifest['structures']);manifest['sha256']=hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest()
    campaign['sourceDetails']='muxi_outbreak:outbreak_details/lostschool.json'
    for rel,value in [('outbreak_geometry/lostschool.json',manifest),('outbreak_maps/lostschool.json',campaign),('outbreak_details/lostschool.json',report)]:
        path=output/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--resource',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.resolve()==a.resource.resolve():raise ValueError('output must be staged separately; validate before publication')
    generate(a.source,a.resource,a.output)
