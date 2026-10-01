import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]


class SourceSuppliesTest(unittest.TestCase):
    def setUp(self):
        self.data=json.loads((ROOT/'src/main/resources/data/muxi_outbreak/outbreak_maps/lostschool.json').read_text(encoding='utf-8'))

    def test_no_per_player_gift_points(self):
        self.assertEqual(self.data['itemSpawns'],[])
        self.assertEqual(len(self.data['supplies']),133)
        self.assertTrue(self.data['supplyPolicy']['sharedStock'])

    def test_source_identity_and_shared_counts(self):
        seen=set()
        for row in self.data['supplies']:
            self.assertNotIn(row['id'],seen);seen.add(row['id'])
            self.assertIn(row['section'],(0,1,2));self.assertTrue(row['sourceHammerId'].isdigit())
            self.assertEqual(len(row['sourceOrigin']),3);self.assertGreater(row['count'],0)
            self.assertTrue(row['choices']);self.assertNotIn('arrow',','.join(row['choices']))
        self.assertEqual(sum(r['choices']==['ammo'] for r in self.data['supplies']),14)
        self.assertTrue(all(r['infinite'] for r in self.data['supplies'] if r['choices']==['ammo']))

    def test_same_default_firearms_as_zombie_challenge(self):
        source=(ROOT/'src/main/java/net/muxigame/outbreak/equipment/CampaignInventory.java').read_text(encoding='utf-8')
        self.assertIn('tacz:hk_mp5a5',source);self.assertIn('tacz:glock_17',source)
        self.assertNotIn('Items.BOW',source);self.assertNotIn('Items.ARROW',source)
        snapshot=(ROOT/'src/main/java/net/muxigame/outbreak/PlayerSnapshot.java').read_text(encoding='utf-8')
        self.assertNotIn('Items.BOW',snapshot);self.assertNotIn('Items.GOLDEN_APPLE',snapshot)

    def test_icons_and_models_exist_for_all_registered_items(self):
        for name in ('medkit','pills','adrenaline','defibrillator','explosive_ammo_pack','pipe_bomb','bile_bomb'):
            base=ROOT/'src/main/resources/assets/muxi_outbreak'
            self.assertTrue((base/f'textures/item/{name}.png').is_file())
            self.assertTrue((base/f'models/item/{name}.json').is_file())


if __name__=='__main__':unittest.main()
