"""Tiny dependency-free NBT and legacy Anvil reader used by the importer.

It intentionally supports only reading. It does not modify source worlds.
"""
from __future__ import annotations

from dataclasses import dataclass
import gzip
import io
from pathlib import Path
import struct
import zlib
from typing import Any, Iterator


class NbtError(ValueError):
    pass


def _read_exact(stream: io.BufferedIOBase | io.BytesIO, count: int) -> bytes:
    data = stream.read(count)
    if len(data) != count:
        raise NbtError(f"unexpected EOF: wanted {count}, got {len(data)}")
    return data


def _unpack(stream: io.BufferedIOBase | io.BytesIO, fmt: str):
    size = struct.calcsize(fmt)
    return struct.unpack(fmt, _read_exact(stream, size))[0]


def _string(stream: io.BufferedIOBase | io.BytesIO) -> str:
    size = _unpack(stream, ">H")
    return _read_exact(stream, size).decode("utf-8", errors="replace")


def _payload(stream: io.BufferedIOBase | io.BytesIO, tag_type: int) -> Any:
    if tag_type == 0:
        return None
    if tag_type == 1:
        return _unpack(stream, ">b")
    if tag_type == 2:
        return _unpack(stream, ">h")
    if tag_type == 3:
        return _unpack(stream, ">i")
    if tag_type == 4:
        return _unpack(stream, ">q")
    if tag_type == 5:
        return _unpack(stream, ">f")
    if tag_type == 6:
        return _unpack(stream, ">d")
    if tag_type == 7:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative byte array length")
        return _read_exact(stream, size)
    if tag_type == 8:
        return _string(stream)
    if tag_type == 9:
        child_type = _unpack(stream, ">B")
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative list length")
        return [_payload(stream, child_type) for _ in range(size)]
    if tag_type == 10:
        result: dict[str, Any] = {}
        while True:
            child_type = _unpack(stream, ">B")
            if child_type == 0:
                return result
            name = _string(stream)
            result[name] = _payload(stream, child_type)
    if tag_type == 11:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative int array length")
        return [_unpack(stream, ">i") for _ in range(size)]
    if tag_type == 12:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative long array length")
        return [_unpack(stream, ">q") for _ in range(size)]
    raise NbtError(f"unsupported NBT tag type {tag_type}")


def _skip_payload(stream: io.BufferedIOBase | io.BytesIO, tag_type: int) -> None:
    if tag_type == 0:
        return
    fixed = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
    if tag_type in fixed:
        stream.seek(fixed[tag_type], io.SEEK_CUR)
        return
    if tag_type == 7:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative byte array length")
        stream.seek(size, io.SEEK_CUR)
        return
    if tag_type == 8:
        size = _unpack(stream, ">H")
        stream.seek(size, io.SEEK_CUR)
        return
    if tag_type == 9:
        child_type = _unpack(stream, ">B")
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative list length")
        for _ in range(size):
            _skip_payload(stream, child_type)
        return
    if tag_type == 10:
        while True:
            child_type = _unpack(stream, ">B")
            if child_type == 0:
                return
            _ = _string(stream)
            _skip_payload(stream, child_type)
    if tag_type == 11:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative int array length")
        stream.seek(size * 4, io.SEEK_CUR)
        return
    if tag_type == 12:
        size = _unpack(stream, ">i")
        if size < 0:
            raise NbtError("negative long array length")
        stream.seek(size * 8, io.SEEK_CUR)
        return
    raise NbtError(f"unsupported NBT tag type {tag_type}")


def _section_compound(stream: io.BytesIO) -> dict[str, Any]:
    result: dict[str, Any] = {}
    wanted = {"Y", "Blocks", "Add"}
    while True:
        tag_type = _unpack(stream, ">B")
        if tag_type == 0:
            return result
        name = _string(stream)
        if name in wanted:
            result[name] = _payload(stream, tag_type)
        else:
            _skip_payload(stream, tag_type)


def _sections_list(stream: io.BytesIO) -> list[dict[str, Any]]:
    child_type = _unpack(stream, ">B")
    size = _unpack(stream, ">i")
    if size < 0:
        raise NbtError("negative section list length")
    if child_type != 10:
        result = []
        for _ in range(size):
            _skip_payload(stream, child_type)
        return result
    return [_section_compound(stream) for _ in range(size)]


def _level_interest(stream: io.BytesIO) -> dict[str, Any]:
    result: dict[str, Any] = {}
    while True:
        tag_type = _unpack(stream, ">B")
        if tag_type == 0:
            return result
        name = _string(stream)
        if name == "Sections" and tag_type == 9:
            result[name] = _sections_list(stream)
        elif name == "TileEntities" and tag_type == 9:
            result[name] = _payload(stream, tag_type)
        else:
            _skip_payload(stream, tag_type)


def loads_chunk_interest(raw: bytes) -> dict[str, Any]:
    """Decode only legacy Section block arrays and TileEntities."""
    stream = io.BytesIO(raw)
    root_type = _unpack(stream, ">B")
    if root_type != 10:
        raise NbtError(f"legacy chunk root is not compound: {root_type}")
    _ = _string(stream)
    root: dict[str, Any] = {}
    while True:
        tag_type = _unpack(stream, ">B")
        if tag_type == 0:
            return root
        name = _string(stream)
        if name == "Level" and tag_type == 10:
            root["Level"] = _level_interest(stream)
        else:
            _skip_payload(stream, tag_type)


def loads(raw: bytes) -> tuple[str, Any]:
    stream = io.BytesIO(raw)
    tag_type = _unpack(stream, ">B")
    if tag_type == 0:
        return "", None
    name = _string(stream)
    return name, _payload(stream, tag_type)


def load(path: Path) -> tuple[str, Any]:
    raw = path.read_bytes()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return loads(raw)


def walk(value: Any, path: tuple[str, ...] = ()) -> Iterator[tuple[tuple[str, ...], Any]]:
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, (*path, key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, (*path, str(index)))


@dataclass(frozen=True)
class Chunk:
    region_x: int
    region_z: int
    local_x: int
    local_z: int
    root: dict[str, Any]

    @property
    def chunk_x(self) -> int:
        return self.region_x * 32 + self.local_x

    @property
    def chunk_z(self) -> int:
        return self.region_z * 32 + self.local_z


def iter_region(path: Path) -> Iterator[Chunk]:
    stem = path.stem.split(".")
    if len(stem) != 3 or stem[0] != "r":
        raise NbtError(f"not a region file name: {path.name}")
    region_x, region_z = int(stem[1]), int(stem[2])
    with path.open("rb") as handle:
        locations = _read_exact(handle, 4096)
        for index in range(1024):
            entry = locations[index * 4 : index * 4 + 4]
            sector = int.from_bytes(entry[:3], "big")
            count = entry[3]
            if sector == 0 or count == 0:
                continue
            handle.seek(sector * 4096)
            length = _unpack(handle, ">I")
            if length <= 1 or length > count * 4096:
                raise NbtError(f"invalid chunk length {length} in {path}")
            compression = _unpack(handle, ">B")
            compressed = _read_exact(handle, length - 1)
            if compression == 1:
                raw = gzip.decompress(compressed)
            elif compression == 2:
                raw = zlib.decompress(compressed)
            elif compression == 3:
                raw = compressed
            else:
                raise NbtError(f"unsupported chunk compression {compression}")
            _, root = loads(raw)
            if not isinstance(root, dict):
                continue
            yield Chunk(region_x, region_z, index % 32, index // 32, root)


def iter_region_interest(path: Path) -> Iterator[Chunk]:
    """Read 1.12 Anvil chunks while skipping all data unrelated to conversion."""
    stem = path.stem.split(".")
    if len(stem) != 3 or stem[0] != "r":
        raise NbtError(f"not a region file name: {path.name}")
    region_x, region_z = int(stem[1]), int(stem[2])
    with path.open("rb") as handle:
        locations = _read_exact(handle, 4096)
        for index in range(1024):
            entry = locations[index * 4 : index * 4 + 4]
            sector = int.from_bytes(entry[:3], "big")
            count = entry[3]
            if sector == 0 or count == 0:
                continue
            handle.seek(sector * 4096)
            length = _unpack(handle, ">I")
            if length <= 1 or length > count * 4096:
                raise NbtError(f"invalid chunk length {length} in {path}")
            compression = _unpack(handle, ">B")
            compressed = _read_exact(handle, length - 1)
            if compression == 1:
                raw = gzip.decompress(compressed)
            elif compression == 2:
                raw = zlib.decompress(compressed)
            elif compression == 3:
                raw = compressed
            else:
                raise NbtError(f"unsupported chunk compression {compression}")
            root = loads_chunk_interest(raw)
            yield Chunk(region_x, region_z, index % 32, index // 32, root)


def block_id_at(section: dict[str, Any], index: int) -> int:
    blocks = section.get("Blocks")
    if not isinstance(blocks, (bytes, bytearray)) or index >= len(blocks):
        return -1
    base = blocks[index]
    add = section.get("Add")
    if isinstance(add, (bytes, bytearray)) and index // 2 < len(add):
        packed = add[index // 2]
        high = (packed & 0x0F) if index % 2 == 0 else ((packed >> 4) & 0x0F)
        base |= high << 8
    return base


def iter_legacy_blocks(chunk: Chunk) -> Iterator[tuple[int, int, int, int]]:
    level = chunk.root.get("Level", chunk.root)
    sections = level.get("Sections", []) if isinstance(level, dict) else []
    for section in sections:
        if not isinstance(section, dict):
            continue
        sy = int(section.get("Y", 0))
        blocks = section.get("Blocks")
        if not isinstance(blocks, (bytes, bytearray)):
            continue
        for index in range(min(4096, len(blocks))):
            block_id = block_id_at(section, index)
            if block_id == 0:
                continue
            lx = index & 15
            lz = (index >> 4) & 15
            ly = (index >> 8) & 15
            yield chunk.chunk_x * 16 + lx, sy * 16 + ly, chunk.chunk_z * 16 + lz, block_id


def iter_matching_legacy_blocks(
    chunk: Chunk, wanted_ids: set[int]
) -> Iterator[tuple[int, int, int, int]]:
    """Yield only selected legacy numeric IDs using bytes.find in C.

    Modded 1.12 worlds may contain tens of millions of non-air blocks. The
    importer normally wants only a few marker IDs, so scanning every block in
    Python is unnecessarily expensive.
    """
    if not wanted_ids:
        return
    level = chunk.root.get("Level", chunk.root)
    sections = level.get("Sections", []) if isinstance(level, dict) else []
    by_low: dict[int, list[int]] = {}
    for block_id in wanted_ids:
        by_low.setdefault(block_id & 0xFF, []).append(block_id)
    for section in sections:
        if not isinstance(section, dict):
            continue
        sy = int(section.get("Y", 0))
        blocks = section.get("Blocks")
        if not isinstance(blocks, (bytes, bytearray)):
            continue
        blocks = bytes(blocks)
        add = section.get("Add")
        add_bytes = bytes(add) if isinstance(add, (bytes, bytearray)) else b""
        for low, candidates in by_low.items():
            start = 0
            needle = bytes((low,))
            while True:
                index = blocks.find(needle, start)
                if index < 0:
                    break
                start = index + 1
                packed = add_bytes[index // 2] if index // 2 < len(add_bytes) else 0
                high = (packed & 0x0F) if index % 2 == 0 else ((packed >> 4) & 0x0F)
                full_id = low | (high << 8)
                if full_id not in candidates:
                    continue
                lx = index & 15
                lz = (index >> 4) & 15
                ly = (index >> 8) & 15
                yield chunk.chunk_x * 16 + lx, sy * 16 + ly, chunk.chunk_z * 16 + lz, full_id


def tile_entities(chunk: Chunk) -> list[dict[str, Any]]:
    level = chunk.root.get("Level", chunk.root)
    if not isinstance(level, dict):
        return []
    result = level.get("TileEntities", [])
    return [item for item in result if isinstance(item, dict)] if isinstance(result, list) else []
