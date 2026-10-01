"""Small deterministic writer for vanilla Java Edition structure NBT."""
from __future__ import annotations
import gzip
import struct
from pathlib import Path


def string(value: str) -> bytes:
    data = value.encode('utf-8')
    return struct.pack('>H', len(data)) + data


def tag(kind: int, name: str, payload: bytes) -> bytes:
    return bytes([kind]) + string(name) + payload


def integer(name: str, value: int) -> bytes:
    return tag(3, name, struct.pack('>i', int(value)))


def integers(name: str, values) -> bytes:
    return tag(9, name, b'\x03' + struct.pack('>i', len(values))
               + b''.join(struct.pack('>i', int(v)) for v in values))


def write(path: Path, palette: list[dict], rows, size=(32, 32, 32)) -> int:
    rows = list(rows)
    states = []
    for state in palette:
        data = tag(8, 'Name', string(state['Name']))
        if state.get('Properties'):
            properties = b''.join(tag(8, k, string(v)) for k, v in sorted(state['Properties'].items()))
            data += tag(10, 'Properties', properties + b'\x00')
        states.append(data + b'\x00')
    blocks = b''.join(integers('pos', pos) + integer('state', state) + b'\x00' for pos, state in rows)
    root = (integer('DataVersion', 3955) + integers('size', size)
            + tag(9, 'palette', b'\x0a' + struct.pack('>i', len(states)) + b''.join(states))
            + tag(9, 'blocks', b'\x0a' + struct.pack('>i', len(rows)) + blocks)
            + tag(9, 'entities', b'\x0a\x00\x00\x00\x00') + b'\x00')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(tag(10, '', root), mtime=0))
    return len(rows)
