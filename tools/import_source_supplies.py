"""Import real BSP supply entities. Does not invent route reward checkpoints.

All source coordinates, class names, counts and flags remain auditable. Unknown
objects are explicitly reported; they are never silently turned into medkits.
"""
from __future__ import annotations
from collections import Counter
import json
import math
from pathlib import Path
import numpy as np
import bsp_tool
from bsp_tool.branches.valve import left4dead2

ROOT=Path(__file__).resolve().parents[1]
GUNS={
 'smg':'hk_mp5a5','smg_silenced':'ump45','smg_mp5':'hk_mp5a5',
 'pistol':'glock_17','pistol_magnum':'deagle','rifle':'m4a1','rifle_ak47':'ak47',
 'rifle_desert':'scar_h','rifle_sg552':'aug','hunting_rifle':'m700','sniper_military':'ai_awp',
 'sniper_awp':'ai_awp','sniper_scout':'kar98','pumpshotgun':'m870','shotgun_chrome':'m870',
 'autoshotgun':'m1014','shotgun_spas':'m1014','grenade_launcher':'rpg7','rifle_m60':'m249',
}
FIXED={'first_aid_kit':'medkit','pain_pills':'pills','adrenaline':'adrenaline','defibrillator':'defib',
       'pipe_bomb':'pipe_bomb','molotov':'lr:lrtactical:molotov','vomitjar':'bile_bomb',
       'ammo':'ammo','upgradepack_explosive':'explosive_pack','melee':'melee'}
ITEMS={1:'ammo',2:'medkit',3:'lr:lrtactical:molotov',4:'pills',5:'pipe_bomb',
       11:'adrenaline',12:'defib',13:'bile_bomb',17:'gun:tacz:rpg7',18:'gun:tacz:m249'}
SELECTIONS={'any_primary':['gun:tacz:'+g for g in ('hk_mp5a5','m870','ak47','m4a1','m1014','m700')],
            'any_pistol':['gun:tacz:glock_17','gun:tacz:deagle'],
            'tier1_any':['gun:tacz:hk_mp5a5','gun:tacz:m870'],
            'tier2_any':['gun:tacz:ak47','gun:tacz:m4a1','gun:tacz:m1014','gun:tacz:m700']}


def generate():
    path=ROOT/'src/main/resources/data/muxi_outbreak/outbreak_maps/lostschool.json'
    campaign=json.loads(path.read_text(encoding='utf-8'))
    imported=[];skipped=[];relocated=[]
    for section,chapter in enumerate(campaign['chapters']):
        name=chapter['id'];bsp_path=ROOT/'maps/workshop/lostschool_extracted/maps'/f'{name}.bsp'
        bsp=bsp_tool.ValveBsp.from_file(left4dead2,str(bsp_path))
        cache=np.load(ROOT/f'build/{name}-voxels.npz');grid=cache['grid'];origin=cache['origin'];palette=json.loads(str(cache['palette']))
        passable={i for i,p in enumerate(palette) if p['Name'] in {'minecraft:air','minecraft:ladder','minecraft:scaffolding'}}
        def get(p):
            q=np.asarray(p)-origin
            return int(grid[tuple(q)]) if np.all(q>=0) and np.all(q<grid.shape) else 0
        def supported(p):
            return get(p) in passable and get((p[0],p[1]+1,p[2])) in passable and get((p[0],p[1]-1,p[2])) not in passable
        for entity in bsp.ENTITIES:
            classname=entity.get('classname','')
            if not classname.startswith('weapon_'):continue
            base=classname.removeprefix('weapon_').removesuffix('_spawn')
            random_node=False;unsupported=[]
            if base=='item':
                choices=[value for key,value in ITEMS.items() if entity.get('item'+str(key),'0')=='1'];random_node=True
                unsupported=[key for key in entity if key.startswith('item') and entity[key]=='1' and key[4:].isdigit() and int(key[4:]) not in ITEMS]
            elif base=='spawn':
                selection=entity.get('weapon_selection','');choices=SELECTIONS.get(selection,[]);random_node=True
            elif base in GUNS:choices=['gun:tacz:'+GUNS[base]]
            elif base in FIXED:choices=[FIXED[base]]
            else:choices=[]
            ident=name+'_'+entity['hammerid']
            if not choices:
                skipped.append({'id':ident,'source':dict(entity),'reason':'No implemented mapping; kept out of play rather than silently substituted'});continue
            xyz=list(map(float,entity['origin'].split()))
            raw=[xyz[0]/24+section*2048,xyz[2]/24+80,-xyz[1]/24]
            rounded=tuple(round(v) for v in raw)
            pos=rounded
            if not supported(pos):
                options=[]
                for dx in range(-3,4):
                    for dy in range(-5,6):
                        for dz in range(-3,4):
                            q=(rounded[0]+dx,rounded[1]+dy,rounded[2]+dz)
                            if supported(q):options.append(q)
                if not options:
                    skipped.append({'id':ident,'source':dict(entity),'reason':'No clear supported pickup surface within 3 horizontal/5 vertical blocks','projected':raw});continue
                pos=min(options,key=lambda q:math.dist(q,raw))
                relocated.append({'id':ident,'projected':raw,'position':pos,'distance':round(math.dist(pos,raw),3)})
            flags=int(entity.get('spawnflags','0'))
            imported.append({'id':ident,'section':section,'pos':list(pos),'choices':choices,
                'count':max(1,int(entity.get('count','1'))),'infinite':bool(flags&8) or choices==['ammo'],
                'mustExist':bool(flags&2) or not classname.endswith('_spawn'),
                'directorChoice':random_node,'sourceClass':classname,'sourceHammerId':entity['hammerid'],
                'sourceOrigin':xyz,'sourceFlags':flags,'unsupportedSourceChoices':unsupported})
    campaign['supplies']=imported
    campaign['itemSpawns']=[]
    campaign['supplyPolicy']={'sharedStock':True,'perPlayerGiftPoints':False,'ammoPile':'unlimited reserve-only refill; no medical/grenade restock',
        'medicalSlots':'one medkit/defibrillator/upgrade pack AND one pills/adrenaline','throwableSlot':'one total throwable',
        'director':'one seeded decision per unseen source node per round; fixed supplies are not overwritten; depleted supplies never regrow',
        'notExact':'Valve item-density and Director RNG are not reproduced bit-for-bit; undocumented selection weights are an explicit adaptation.'}
    path.write_text(json.dumps(campaign,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    report={'imported':len(imported),'perChapter':dict(Counter(p['section'] for p in imported)),
        'bySourceClass':dict(Counter(p['sourceClass'] for p in imported)),'relocated':relocated,'skipped':skipped,
        'gunMapping':GUNS,'otherMapping':FIXED}
    dest=ROOT/'build/source-supply-report.json';dest.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('SOURCE_SUPPLIES_IMPORTED',len(imported),'relocated',len(relocated),'skipped',len(skipped),report['bySourceClass'])
    return report


if __name__=='__main__':generate()
