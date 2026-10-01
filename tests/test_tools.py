from __future__ import annotations

import json
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import nbt_reader


class NbtReaderTest(unittest.TestCase):
    def test_reads_compound_and_list(self):
        raw = (
            b"\x0a" + struct.pack(">H", 4) + b"root"
            + b"\x08" + struct.pack(">H", 1) + b"a" + struct.pack(">H", 2) + b"ok"
            + b"\x09" + struct.pack(">H", 4) + b"nums" + b"\x03" + struct.pack(">i", 2)
            + struct.pack(">i", 3) + struct.pack(">i", 7)
            + b"\x00"
        )
        name, root = nbt_reader.loads(raw)
        self.assertEqual("root", name)
        self.assertEqual("ok", root["a"])
        self.assertEqual([3, 7], root["nums"])


class MapResourceTest(unittest.TestCase):
    def test_builtin_maps_have_required_shape(self):
        paths = list((ROOT / "src/main/resources/data/muxi_outbreak/outbreak_maps").glob("*.json"))
        self.assertTrue(paths)
        ids = set()
        for path in paths:
            data = json.loads(path.read_text(encoding="utf-8"))
            self.assertIn(data["mode"], {"campaign", "survival"})
            self.assertEqual(3, len(data["start"]))
            self.assertNotIn(data["id"], ids)
            ids.add(data["id"])
            for key in ("commonSpawns", "hordeSpawns", "bossSpawns", "itemSpawns"):
                self.assertIsInstance(data[key], list)


if __name__ == "__main__":
    unittest.main()
