from pathlib import Path
import sys,json,unittest,math,tempfile,hashlib
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import source_detail_models as m
import source_details as d

class PoseTest(unittest.TestCase):
    def test_source_axis_conversion(self):
        np.testing.assert_allclose(m.rotate(m.rotation([0,90,0]),[1,0,0]),[0,0,-1],atol=1e-6)
        np.testing.assert_allclose(m.rotate(m.rotation([90,0,0]),[1,0,0]),[0,-1,0],atol=1e-6)
        np.testing.assert_allclose(m.rotate(m.rotation([0,0,90]),[0,0,-1]),[0,1,0],atol=1e-6)
        self.assertAlmostEqual(float(np.linalg.norm(m.rotation([60,90,180]))),1)
    def test_off_lamp_geometry_has_no_emitting_block(self):
        parts=m.boxes('fixture','models/props_c17/lamppost03a_off.mdl')
        self.assertGreater(len(parts),2)
        self.assertFalse(any(block in {'minecraft:light','minecraft:sea_lantern','minecraft:glowstone','minecraft:end_rod'} for block,_,_ in parts))
    def test_native_model_parts_are_positive_and_inventory_free(self):
        for kind in ('bed','chair','table','cabinet','crate','fixture','shelf','toolchest','medical_cart','medical_gurney','medical_pole','sleeping_bag','curtain','container_box','shipping_container','bathroom_sink','toilet','showerhead'):
            for block,pos,size in m.boxes(kind,'models/props/de_nuke/wall_light.mdl'):
                self.assertTrue(np.all(size>0));self.assertTrue(np.all(np.isfinite(pos)))
                self.assertNotIn(block,{'minecraft:chest','minecraft:barrel','minecraft:shulker_box'})

class CompleteCoverageTest(unittest.TestCase):
    def test_requested_furniture_and_lamps_have_explicit_complete_mapping(self):
        root=Path(__file__).resolve().parents[1]/'src/main/resources/data/muxi_outbreak'
        report=json.loads((root/'outbreak_details/lostschool.json').read_text(encoding='utf-8'))
        rows=[p for c in report['chapters'] for p in c['props']]
        expected={'bed':126,'table':31,'cabinet':21,'chair':152,'fixture':74,'shelf':7,'toolchest':4,'medical_cart':8,'medical_gurney':7,'medical_pole':5,'sleeping_bag':7,'curtain':1,'container_box':23,'shipping_container':4,'bathroom_sink':28,'toilet':27,'showerhead':25}
        for kind,total in expected.items():
            props=[p for p in rows if p['mapping']==kind];self.assertEqual(len(props),total)
            self.assertTrue(all(p['status'] in {'placed','placed_display'} for p in props),kind)
        manifest=json.loads((root/'outbreak_geometry/lostschool.json').read_text(encoding='utf-8'))
        parts=manifest['decorations'];self.assertEqual(len({p['uuid'] for p in parts}),len(parts))
        self.assertEqual({p['id'] for p in parts},{part['id'] for p in rows for part in p.get('descriptors',[])})
        for part in parts:self.assertAlmostEqual(sum(v*v for v in part['rotation']),1,places=5)
        for chapter in report['chapters']:
            for light in chapter['nativeRouteSafetyLights']:
                self.assertEqual(light['level'],4);self.assertFalse(light['sourceEmitter'])
                self.assertTrue(light['combat'] or light['verticalTransition'] or light['endApproach'])

class BaselineSafetyTest(unittest.TestCase):
    def test_reconversion_uses_identical_immutable_geometry(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);original=root/'original';first=root/'first';second=root/'second';p=original/'structure/lostschool/lost/0_0_0.nbt';p.parent.mkdir(parents=True);p.write_bytes(b'original native structure fixture')
            manifest={'version':1,'id':'lostschool','sha256':'a'*64,'structures':[{'resource':'muxi_outbreak:structure/lostschool/lost/0_0_0.nbt','origin':[0,0,0],'blocks':1,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}]}
            j=original/'outbreak_geometry/lostschool.json';j.parent.mkdir(parents=True);j.write_text(json.dumps(manifest))
            a=d.freeze_base(original,first);b=d.freeze_base(first,second);self.assertEqual(a,b)
            rel=a['structures'][0]['resource'].split(':')[1];self.assertEqual((first/rel).read_bytes(),(second/rel).read_bytes())
            self.assertIn('/_baseline/',rel)
    def test_detailed_input_without_base_cannot_duplicate_furniture(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);p=root/'outbreak_details/lostschool.json';p.parent.mkdir(parents=True);p.write_text('{}')
            with self.assertRaisesRegex(ValueError,'immutable base'):d.freeze_base(root,root/'output')

if __name__=='__main__':unittest.main()
