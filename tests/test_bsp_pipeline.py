from __future__ import annotations
import gzip
import os
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import nbt_reader
import source_nav
import structure_writer


class StructureWriterTest(unittest.TestCase):
    def test_native_structure_roundtrip(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.nbt'
            palette=[{'Name':'minecraft:stone'},{'Name':'minecraft:ladder','Properties':{'facing':'north','waterlogged':'false'}}]
            structure_writer.write(path,palette,[([0,0,0],0),([1,1,1],1)])
            _,root=nbt_reader.loads(gzip.decompress(path.read_bytes()))
            self.assertEqual(root['DataVersion'],3955)
            self.assertEqual(root['palette'],palette)
            self.assertEqual(root['blocks'][1]['pos'],[1,1,1])
            before=path.read_bytes()
            structure_writer.write(path,palette,[([0,0,0],0),([1,1,1],1)])
            self.assertEqual(before,path.read_bytes())

    def test_reject_truncated_nav(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'bad.nav';path.write_bytes(b'\x00'*12)
            with self.assertRaises(ValueError):source_nav.load(path)


class CampaignConversionTest(unittest.TestCase):
    def test_all_geometry_resources_and_hashes(self):
        base=ROOT/'src/main/resources/data/muxi_outbreak'
        manifest=json.loads((base/'outbreak_geometry/lostschool.json').read_text(encoding='utf-8'))
        count=0
        for piece in manifest['structures']:
            path=base/piece['resource'].split(':',1)[1]
            data=path.read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(),piece['sha256'],str(path))
            _,root=nbt_reader.loads(gzip.decompress(data))
            self.assertEqual(len(root['blocks']),piece['blocks'])
            self.assertTrue(all(p['Name'].startswith('minecraft:') for p in root['palette']))
            for block in root['blocks']:
                self.assertTrue(0<=block['state']<len(root['palette']))
                self.assertTrue(all(0<=v<size for v,size in zip(block['pos'],root['size'])))
            count+=len(root['blocks'])
        self.assertEqual(count,manifest['blocks'])
        self.assertGreater(count,1_000_000,'reject a metadata-only placeholder')

    def test_campaign_is_three_chapter_native_map(self):
        data=json.loads((ROOT/'src/main/resources/data/muxi_outbreak/outbreak_maps/lostschool.json').read_text(encoding='utf-8'))
        self.assertEqual(len(data['chapters']),3)
        self.assertEqual(data['dimension'],'muxi_outbreak:campaign')
        self.assertEqual([r['nextSection'] for r in data['safeRooms']],[1,2])
        self.assertEqual(data['finale']['waves'],3)
        self.assertEqual(data['start'],data['chapters'][0]['start'])
        self.assertEqual(data['finish'],data['chapters'][-1]['end'])
        self.assertNotEqual(data['start'],[0,72,0])

    def test_source_nav_graph_routes(self):
        source=Path(os.environ.get('OUTBREAK_SOURCE_DIR',str(ROOT/'maps/workshop/lostschool_extracted/maps')))
        if not source.exists():self.skipTest('private source VPK is not installed')
        campaign=json.loads((ROOT/'src/main/resources/data/muxi_outbreak/outbreak_maps/lostschool.json').read_text(encoding='utf-8'))
        for chapter in campaign['chapters']:
            graph=source_nav.load(source/(chapter['id']+'.nav'))
            route=chapter['navAreaIds']
            self.assertTrue(all(b in graph[a].links for a,b in zip(route,route[1:])))

    def test_converted_route_clearance_and_support(self):
        try:import numpy as np
        except ImportError:self.skipTest('numpy is required for independent voxel validation')
        for name in ('lost','lostschool_2','lostschool_3'):
            path=ROOT/f'build/{name}-voxels.npz'
            if not path.exists():self.skipTest('run converter to create independent voxel fixtures')
            data=np.load(path);grid=data['grid'];origin=data['origin'];route=data['route'];palette=json.loads(str(data['palette']))
            climb={i for i,p in enumerate(palette) if p['Name'] in {'minecraft:ladder','minecraft:scaffolding'}}
            clear={i for i,p in enumerate(palette) if p['Name'] in {'minecraft:air','minecraft:light'}}|climb
            def get(p):
                p=np.asarray(p)-origin
                return int(grid[tuple(p)]) if np.all(p>=0) and np.all(p<grid.shape) else -1
            for p in route:
                x,y,z=map(int,p)
                self.assertIn(get((x,y,z)),clear,(name,p,'feet'))
                self.assertIn(get((x,y+1,z)),clear,(name,p,'head'))
                self.assertTrue(get((x,y-1,z)) not in clear|{-1} or get((x,y,z)) in climb,(name,p,'support'))
            for a,b in zip(route,route[1:]):
                delta=np.abs(b-a)
                self.assertLessEqual(delta[0]+delta[2],1,(name,a,b,'horizontal discontinuity'))
                self.assertLessEqual(delta[1],1,(name,a,b,'vertical discontinuity'))


if __name__=='__main__':unittest.main()
