"""Preserve the audited native checkpoint adaptation when regenerating this map.

Anchors and doorway floor/clearance must still match the converted geometry.
This does not claim to import omitted Source MDL door models or puzzle scripts.
"""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parents[1]

def apply(output, voxels=None):
    adaptation=json.loads((ROOT/'maps/workshop/lostschool/checkpoint_rooms.json').read_text(encoding='utf-8'))
    expected=adaptation['chapterAnchors']
    actual=[{'id':c['id'],'start':c['start'],'end':c['end']} for c in output['chapters']]
    if actual!=expected:
        raise ValueError('Converted chapter anchors changed: checkpoint openings need a fresh geometry audit')
    output['startRooms']=adaptation['startRooms']
    output['safeRooms']=adaptation['safeRooms']
    output['checkpointPolicy']=adaptation['checkpointPolicy']
    return output
