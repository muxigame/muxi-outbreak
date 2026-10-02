import collections, json, struct, sys, tempfile, unittest
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import source_details as d

class DetailsTest(unittest.TestCase):
    def grid(self):
        g=d.Grid.__new__(d.Grid);g.origin=np.zeros(3,dtype=int);g.grid=np.zeros((12,12,12),dtype=np.uint8)
        g.palette=[{'Name':'minecraft:air'}];g.ids={g.key(g.palette[0]):0};g.protected=np.zeros_like(g.grid,dtype=bool)
        g.grid[:,2,:]=g.state('minecraft:stone');g.base=g.grid.copy();return g
    def test_fail_closed_worldlight_header(self):
        with tempfile.TemporaryDirectory() as f:
            p=Path(f)/'bad.bsp';raw=bytearray(1036);raw[:4]=b'VBSP';struct.pack_into('<I',raw,4,21)
            struct.pack_into('<4I',raw,8+15*16,1,1036,99,0);p.write_bytes(raw)
            with self.assertRaises(ValueError):d.compiled_lights(p)
    def test_atomic_protected_placement_and_original_states(self):
        g=self.grid();g.reserve([5,3,5],[5,5,5]);state=g.state('minecraft:spruce_planks')
        self.assertEqual(g.place([([4,3,5],state),([5,3,5],state)]),'protected_navigation_or_gameplay')
        self.assertEqual(g.get([4,3,5]),0)
        self.assertEqual(g.place([([4,2,5],state)]),'existing_geometry_or_prior_prop')
        self.assertIsNone(g.place([([4,3,5],state)]));self.assertEqual(g.verify()['originalBlocksPreserved'],144)
    def test_supported_bed_pair_and_cardinal_yaw(self):
        g=self.grid();p=dict(id='test',model='models/props/de_inferno/bed.mdl',origin=[120, -120, -1848],angles=[0,0,0],scale=1)
        row=d.furniture(g,p,0);self.assertEqual(row['status'],'placed');self.assertEqual(row['nativeAnchor'],[5,3,5])
        self.assertEqual([c['state']['Properties']['part'] for c in row['cells']],['foot','head'])
        self.assertEqual(row['cells'][1]['pos'],[6,3,5]);g.verify()
    def test_off_lamp_is_not_an_emitter(self):
        g=self.grid();p=dict(id='test',model='models/props/lamppost03a_off.mdl',origin=[120,-120,-1848],angles=[0,0,0],scale=1)
        row=d.furniture(g,p,0);self.assertEqual(row['status'],'placed')
        self.assertEqual(row['cells'][0]['state']['Name'],'minecraft:iron_bars')
    def test_unmapped_tilted_and_scaled_are_explicit(self):
        g=self.grid();p=dict(id='test',model='models/unknown.mdl',origin=[120,-120,-1848],angles=[0,0,0],scale=1)
        self.assertEqual(d.furniture(g,p,0)['status'],'unmapped')
        p['model']='models/props/de_inferno/bed.mdl';p['scale']=2
        self.assertEqual(d.furniture(g,p,0)['reason'],'tilted_or_scaled_model')

class CoverageTest(unittest.TestCase):
    def test_complete_details_and_preserved_gameplay(self):
        resource=Path(__file__).resolve().parents[1]/'src/main/resources/data/muxi_outbreak'
        path=resource/'outbreak_details/lostschool.json'
        if not path.exists():self.skipTest('source details not generated')
        r=json.loads(path.read_text(encoding='utf-8'));m=json.loads((resource/'outbreak_maps/lostschool.json').read_text(encoding='utf-8'))
        self.assertTrue(r['layoutPreserved']);self.assertFalse(r['sourceAssetsBundled'])
        self.assertEqual(sum(c['originalBlocksPreserved'] for c in r['chapters']),1999012)
        self.assertEqual(sum(len(c['props']) for c in r['chapters']),1644)
        self.assertEqual(sum(sum(l['type']==1 for l in c['lights']) for c in r['chapters']),66)
        ids=[p['id'] for c in r['chapters'] for p in c['props']];self.assertEqual(len(set(ids)),len(ids))
        self.assertEqual(len(m['supplies']),133)
        self.assertEqual([c['start'] for c in m['chapters']],[[-7,83,7],[2011,77,-15],[4136,81,13]])
        self.assertEqual([c['end'] for c in m['chapters']],[[67,53,-17],[2010,57,-16],[4159,92,224]])

if __name__=='__main__':unittest.main()
