"""Campaign import pipeline entry point.

This tool intentionally only handles metadata conversion. Geometry conversion
is separated so the map source format can change without affecting gameplay.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


REQUIRED = ["id", "start"]


def validate(data: dict) -> None:
    missing = [key for key in REQUIRED if key not in data]
    if missing:
        raise ValueError("missing required fields: " + ", ".join(missing))
    for key in ["safeRooms", "commonSpawns", "hordeSpawns", "bossSpawns", "itemSpawns"]:
        if key in data and not isinstance(data[key], list):
            raise ValueError(key + " must be an array")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="exported campaign metadata")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = json.loads(args.source.read_text(encoding="utf-8"))
    validate(source)
    result = {
        "id": source["id"],
        "title": source.get("title", source["id"]),
        "mode": "campaign",
        "dimension": "muxi_game_core:adventure",
        "start": source["start"],
        "finish": source.get("finish", source["start"]),
        "safeRooms": source.get("safeRooms", []),
        "commonSpawns": source.get("commonSpawns", []),
        "hordeSpawns": source.get("hordeSpawns", []),
        "bossSpawns": source.get("bossSpawns", []),
        "itemSpawns": source.get("itemSpawns", []),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
