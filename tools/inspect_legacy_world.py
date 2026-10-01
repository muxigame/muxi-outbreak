#!/usr/bin/env python3
"""Inspect a Minecraft 1.12 Forge world without changing it."""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from nbt_reader import iter_region, load, tile_entities, walk


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("world", type=Path)
    parser.add_argument("--needle", default="left2mine")
    args = parser.parse_args()
    world = args.world
    needle = args.needle.lower()

    _, level = load(world / "level.dat")
    print("== Left 2 Mine block registry ==")
    try:
        registry = level["FML"]["Registries"]["minecraft:blocks"]["ids"]
        for row in registry:
            if isinstance(row, dict) and str(row.get("K", "")).startswith("left2mine:"):
                print(row.get("V"), row.get("K"))
    except (KeyError, TypeError):
        print("(Forge block registry not found)")
    print()
    print("== level.dat matches ==")
    matches = 0
    for path, value in walk(level):
        text = value if isinstance(value, str) else ""
        path_text = "/".join(path)
        if needle in path_text.lower() or needle in text.lower():
            print(path_text, "=", repr(value)[:500])
            matches += 1
    print("matches:", matches)

    print("\n== tile entity ids ==")
    ids: Counter[str] = Counter()
    interesting: list[tuple[str, dict]] = []
    for region in sorted((world / "region").glob("r.*.*.mca")):
        for chunk in iter_region(region):
            for tile in tile_entities(chunk):
                tile_id = str(tile.get("id", ""))
                ids[tile_id] += 1
                if needle in tile_id.lower() or any(needle in str(v).lower() for v in tile.values()):
                    interesting.append((f"{tile.get('x')},{tile.get('y')},{tile.get('z')}", tile))
    for name, count in ids.most_common(50):
        print(count, name)
    print("\n== interesting tile entities ==")
    for pos, tile in interesting[:300]:
        print(pos, tile)


if __name__ == "__main__":
    main()
