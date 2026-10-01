"""Strict reader for the L4D2 v16/sub14 NAVs supplied with Lost School.

Preserves the directed area graph and ladders. Other formats fail closed; they
must never silently generate a straight-line substitute for a real route.
"""
from __future__ import annotations

from dataclasses import dataclass
import heapq
import math
from pathlib import Path
import struct


@dataclass
class Area:
    id: int
    flags: int
    corners: tuple[float, ...]
    links: list[int]
    spawn_flags: int
    ladders: list[int]

    @property
    def center(self) -> tuple[float, float, float]:
        a = self.corners
        return ((a[0] + a[3]) / 2, (a[1] + a[4]) / 2,
                (a[2] + a[5] + a[6] + a[7]) / 4)


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, fmt: str):
        size = struct.calcsize('<' + fmt)
        if self.offset + size > len(self.data):
            raise ValueError(f'truncated NAV at {self.offset}')
        values = struct.unpack_from('<' + fmt, self.data, self.offset)
        self.offset += size
        return values[0] if len(values) == 1 else values

    def skip(self, size: int):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError(f'invalid NAV span {self.offset}+{size}')
        self.offset += size

    def count(self) -> int:
        count = self.take('I')
        if count > 100000:
            raise ValueError(f'invalid NAV count {count} at {self.offset - 4}')
        return count


def load(path: Path) -> dict[int, Area]:
    r = Reader(path.read_bytes())
    magic, version, subversion, _bsp_size = r.take('4I')
    if (magic, version, subversion) != (0xFEEDFACE, 16, 14):
        raise ValueError(f'unsupported NAV {version}/{subversion}')
    r.take('B')  # analyzed
    for _ in range(r.take('H')):
        r.skip(r.take('H'))
    r.take('B')  # unnamed areas
    # L4D2's sub14 mesh data: population set name followed by two flags.
    while r.take('B') != 0:
        pass
    r.take('2B')
    areas: dict[int, Area] = {}
    for _ in range(r.count()):
        ident, flags = r.take('2I')
        corners = r.take('8f')
        if not all(math.isfinite(v) and abs(v) < 1e6 for v in corners):
            raise ValueError('invalid NAV coordinates')
        links = []
        for _direction in range(4):
            links.extend(r.take('I') for _ in range(r.count()))
        r.skip(r.take('B') * 17)  # hiding spots
        for _encounter in range(r.count()):
            encounter = r.take('IBIBB')
            r.skip(encounter[-1] * 5)
        r.take('H')  # place
        ladders = []
        for _direction in range(2):
            ladders.extend(r.take('I') for _ in range(r.count()))
        r.take('6f')  # occupation times and corner lighting
        spawn_flags, _unknown = r.take('IH')
        r.skip(r.count() * 5)  # visible areas and visibility flags
        r.take('I')  # inherited visibility
        if ident in areas:
            raise ValueError(f'duplicate NAV area {ident}')
        areas[ident] = Area(ident, flags, corners, links, spawn_flags, ladders)
    ladder_count = r.count()
    for _ in range(ladder_count):
        ident = r.take('I')
        r.take('8f')  # width, top xyz, bottom xyz, length
        r.take('I')  # facing
        neighbors = [v for v in r.take('5I') if v in areas]
        users = [a.id for a in areas.values() if ident in a.ladders]
        # A ladder supplies explicit vertical connectivity, not a guessed bridge.
        for a in set(neighbors + users):
            areas[a].links.extend(b for b in set(neighbors + users) if b != a)
    # Four-byte L4D2 mesh trailer is present in these exported maps.
    r.take('I')
    if r.offset != len(r.data):
        raise ValueError(f'unconsumed NAV bytes: {len(r.data) - r.offset}')
    if any(b not in areas for a in areas.values() for b in a.links):
        raise ValueError('NAV references missing areas')
    return areas


def project(area: Area, point) -> tuple[float, float, float]:
    c = area.corners
    x = max(c[0], min(c[3], point[0]))
    y = max(c[1], min(c[4], point[1]))
    u = (x-c[0]) / max(.001, c[3]-c[0])
    v = (y-c[1]) / max(.001, c[4]-c[1])
    z = c[2]*(1-u)*(1-v) + c[6]*u*(1-v) + c[7]*(1-u)*v + c[5]*u*v
    return x, y, z


def nearest(areas: dict[int, Area], point) -> int:
    return min(areas, key=lambda k: math.dist(project(areas[k], point), point))


def route(areas: dict[int, Area], start, end) -> list[Area]:
    first, last = nearest(areas, start), nearest(areas, end)
    queue = [(0., first)]
    cost = {first: 0.}
    parent = {}
    while queue:
        distance, ident = heapq.heappop(queue)
        if ident == last:
            ids = [last]
            while ids[-1] != first:
                ids.append(parent[ids[-1]])
            return [areas[i] for i in reversed(ids)]
        if distance != cost[ident]:
            continue
        for neighbor in areas[ident].links:
            proposed = distance + math.dist(areas[ident].center, areas[neighbor].center)
            if proposed < cost.get(neighbor, math.inf):
                cost[neighbor] = proposed
                parent[neighbor] = ident
                heapq.heappush(queue, (proposed, neighbor))
    raise ValueError(f'no directed NAV route from area {first} to {last}')
