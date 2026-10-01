#!/usr/bin/env python3
"""Extract Left 2 Mine gameplay metadata from a Minecraft 1.12 Forge world.

The source world is read-only. Geometry is intentionally left to Minecraft's
version conversion / Amulet; this tool converts the L2M-specific semantic
markers that generic world converters cannot understand.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
import json
import math
from pathlib import Path
from typing import Any

from nbt_reader import iter_matching_legacy_blocks, iter_region_interest, load, tile_entities


RELEVANT = {
    "left2mine:playerspawn",
    "left2mine:survivalspawn",
    "left2mine:saferoomnode",
    "left2mine:saferoomdoornode",
    "left2mine:zombiespawn",
    "left2mine:hordespawn",
    "left2mine:bossspawn",
    "left2mine:itemspawn",
    "left2mine:ammopile",
    "left2mine:gamedoorsaferoom",
    "left2mine:saferoomdoor",
    "left2mine:proximitysensor",
}


@dataclass(frozen=True)
class Marker:
    name: str
    pos: tuple[int, int, int]
    tile: dict[str, Any] | None


def distance(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def pos(tile: dict[str, Any]) -> tuple[int, int, int] | None:
    try:
        return int(tile["x"]), int(tile["y"]), int(tile["z"])
    except (KeyError, TypeError, ValueError):
        return None


def registry(world: Path) -> dict[int, str]:
    _, root = load(world / "level.dat")
    try:
        rows = root["FML"]["Registries"]["minecraft:blocks"]["ids"]
    except (KeyError, TypeError) as error:
        raise ValueError("Forge 1.12 block registry is missing from level.dat") from error
    result: dict[int, str] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        name = str(row.get("K", ""))
        value = row.get("V")
        if name.startswith("left2mine:") and isinstance(value, int):
            result[value] = name
    if not result:
        raise ValueError("No Left 2 Mine blocks found in the Forge registry")
    return result


def scan(world: Path) -> tuple[list[Marker], list[dict[str, Any]]]:
    ids = registry(world)
    interesting_ids = {block_id for block_id, name in ids.items() if name in RELEVANT}
    tile_by_pos: dict[tuple[int, int, int], dict[str, Any]] = {}
    commands: list[dict[str, Any]] = []
    markers: list[Marker] = []
    regions = sorted((world / "region").glob("r.*.*.mca"))
    print(f"Scanning {len(regions)} region files...", flush=True)
    for region_index, region in enumerate(regions, start=1):
        print(f"  region {region_index}/{len(regions)} {region.name}", flush=True)
        for chunk in iter_region_interest(region):
            chunk_tiles: dict[tuple[int, int, int], dict[str, Any]] = {}
            for tile in tile_entities(chunk):
                tile_pos = pos(tile)
                if tile_pos is not None:
                    tile_by_pos[tile_pos] = tile
                    chunk_tiles[tile_pos] = tile
                command = str(tile.get("Command", "")).strip()
                if command.lstrip("/").lower().startswith("left2mine "):
                    commands.append({
                        "pos": list(tile_pos) if tile_pos else None,
                        "command": command,
                    })
            for x, y, z, block_id in iter_matching_legacy_blocks(chunk, interesting_ids):
                marker_pos = (x, y, z)
                markers.append(Marker(ids[block_id], marker_pos, chunk_tiles.get(marker_pos)))
    return markers, commands


def parent_coord(tile: dict[str, Any] | None) -> tuple[int, int, int] | None:
    if not tile:
        return None
    try:
        return int(tile["nodeX"]), int(tile["nodeY"]), int(tile["nodeZ"])
    except (KeyError, TypeError, ValueError):
        return None


def item_preset(tile: dict[str, Any] | None) -> str:
    if not tile:
        return "legacy:random"
    custom = tile.get("customSpawn")
    if isinstance(custom, dict):
        item = str(custom.get("id", "minecraft:air"))
        if item != "minecraft:air":
            return "legacy:" + item
    item_type = tile.get("type")
    return f"legacy:type_{item_type}" if isinstance(item_type, int) else "legacy:random"


def infer_sections(markers: list[Marker], mode: str) -> tuple[dict[tuple[int, int, int], int], list[Marker]]:
    parent_kinds = {"left2mine:playerspawn", "left2mine:survivalspawn", "left2mine:saferoomnode"}
    parents = [marker for marker in markers if marker.name in parent_kinds]
    if not parents:
        return {}, []
    start_kind = "left2mine:survivalspawn" if mode == "survival" else "left2mine:playerspawn"
    start = next((marker for marker in parents if marker.name == start_kind), parents[0])
    safe_parents = [marker for marker in parents if marker.name == "left2mine:saferoomnode"]

    children_by_parent: dict[tuple[int, int, int], list[Marker]] = defaultdict(list)
    for marker in markers:
        parent = parent_coord(marker.tile)
        if parent is not None:
            children_by_parent[parent].append(marker)

    edges: dict[tuple[int, int, int], tuple[int, int, int]] = {}
    for parent, children in children_by_parent.items():
        door_nodes = [child for child in children if child.name == "left2mine:saferoomdoornode"]
        if not door_nodes:
            continue
        candidate_pairs = [
            (distance(door.pos, safe.pos), safe.pos)
            for door in door_nodes
            for safe in safe_parents
            if safe.pos != parent
        ]
        if candidate_pairs:
            nearest_distance, nearest = min(candidate_pairs)
            if nearest_distance <= 48:
                edges[parent] = nearest

    sections: dict[tuple[int, int, int], int] = {start.pos: 0}
    ordered_safe: list[Marker] = []
    current = start.pos
    seen = {current}
    while current in edges:
        next_parent = edges[current]
        if next_parent in seen:
            break
        seen.add(next_parent)
        sections[next_parent] = len(sections)
        safe = next((marker for marker in safe_parents if marker.pos == next_parent), None)
        if safe:
            ordered_safe.append(safe)
        current = next_parent

    missing = [safe for safe in safe_parents if safe.pos not in sections]
    missing.sort(key=lambda marker: distance(start.pos, marker.pos))
    for safe in missing:
        sections[safe.pos] = len(sections)
        ordered_safe.append(safe)
    return sections, ordered_safe


def convert(world: Path, map_id: str, title: str, dimension: str, forced_mode: str | None) -> dict[str, Any]:
    markers, commands = scan(world)
    player_spawn = next((m for m in markers if m.name == "left2mine:playerspawn"), None)
    survival_spawn = next((m for m in markers if m.name == "left2mine:survivalspawn"), None)
    mode = forced_mode or ("campaign" if player_spawn else "survival" if survival_spawn else "campaign")
    start_marker = survival_spawn if mode == "survival" else player_spawn
    if start_marker is None:
        start_marker = survival_spawn or player_spawn
    if start_marker is None:
        raise ValueError("No Left 2 Mine player/survival spawn marker found")

    sections, ordered_safe = infer_sections(markers, mode)

    def section_for(marker: Marker) -> int:
        parent = parent_coord(marker.tile)
        return sections.get(parent, 0)

    def point(marker: Marker, y_offset: int = 1) -> list[int]:
        return [marker.pos[0], marker.pos[1] + y_offset, marker.pos[2]]

    win_commands = [
        row for row in commands
        if row["command"].lstrip("/").lower().split()[:2] == ["left2mine", "win"]
    ]
    finish = (
        [int(v) for v in win_commands[0]["pos"]]
        if win_commands and win_commands[0]["pos"]
        else point(ordered_safe[-1] if ordered_safe else start_marker)
    )

    safe_rooms = []
    # Some L2M maps store safe rooms only as doors/sensors instead of a
    # saferoomnode marker. Preserve the chapter flow instead of silently
    # dropping the checkpoint.
    safe_anchors = ordered_safe
    if not safe_anchors:
        safe_anchors = [m for m in markers if m.name == "left2mine:gamedoorsaferoom"]
    for index, safe in enumerate(safe_anchors, start=1):
        x, y, z = safe.pos
        safe_rooms.append({
            "id": f"legacy_safe_{index}",
            "min": [x - 5, y - 2, z - 5],
            "max": [x + 5, y + 4, z + 5],
            "nextSection": sections.get(safe.pos, index),
            "legacyAnchor": [x, y, z],
            "estimatedBounds": True,
        })

    def spawn_rows(name: str) -> list[dict[str, Any]]:
        return [
            {"section": section_for(marker), "pos": point(marker)}
            for marker in markers if marker.name == name
        ]

    items = []
    for marker in markers:
        if marker.name not in {"left2mine:itemspawn", "left2mine:ammopile"}:
            continue
        items.append({
            "section": section_for(marker),
            "pos": point(marker),
            "preset": "ammo" if marker.name == "left2mine:ammopile" else item_preset(marker.tile),
        })

    parent_report = []
    for marker in markers:
        if marker.name not in {"left2mine:playerspawn", "left2mine:survivalspawn", "left2mine:saferoomnode"}:
            continue
        parent_report.append({
            "kind": marker.name,
            "pos": list(marker.pos),
            "section": sections.get(marker.pos),
            "colour": {
                "r": marker.tile.get("redColour") if marker.tile else None,
                "g": marker.tile.get("greenColour") if marker.tile else None,
                "b": marker.tile.get("blueColour") if marker.tile else None,
            },
        })

    return {
        "id": map_id,
        "title": title,
        "mode": mode,
        "dimension": dimension,
        "start": point(start_marker),
        "finish": finish,
        "safeRooms": safe_rooms,
        "commonSpawns": spawn_rows("left2mine:zombiespawn"),
        "hordeSpawns": spawn_rows("left2mine:hordespawn"),
        "bossSpawns": spawn_rows("left2mine:bossspawn"),
        "itemSpawns": items,
        "legacy": {
            "format": "left2mine-1.12.2",
            "sourceWorld": world.name,
            "parents": parent_report,
            "commands": commands,
            "markerCounts": {
                name: sum(1 for marker in markers if marker.name == name)
                for name in sorted({marker.name for marker in markers})
            },
            "notes": [
                "Geometry must be upgraded separately; this file preserves Left 2 Mine gameplay semantics.",
                "Safe-room bounds are estimated around saferoom-node anchors and should be audited before publishing.",
                "Section links use node parent coordinates and saferoom-door proximity, with distance fallback.",
            ],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--title")
    parser.add_argument("--dimension", default="muxi_game_core:adventure")
    parser.add_argument("--mode", choices=("campaign", "survival"))
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()
    converted = convert(
        args.world,
        args.id,
        args.title or args.id.replace("_", " ").title(),
        args.dimension,
        args.mode,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(converted, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "id": converted["id"],
        "mode": converted["mode"],
        "common": len(converted["commonSpawns"]),
        "horde": len(converted["hordeSpawns"]),
        "boss": len(converted["bossSpawns"]),
        "items": len(converted["itemSpawns"]),
        "safeRooms": len(converted["safeRooms"]),
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
