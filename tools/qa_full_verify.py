"""Validate the current full-pack fixture's boot and live world route blocks."""
from __future__ import annotations
import json
from pathlib import Path
import time
import traceback
from collections import defaultdict
import numpy as np
from qa_rcon import Rcon, ROOT


def main():
    home=ROOT/'build/qa-server'
    run=json.loads((home/'run.json').read_text(encoding='utf-8'))
    if not run.get('fullPack'):raise ValueError('full Better MC fixture was not selected')
    report={'passed':False,'jarSha256':run['jarSha256'],'copiedServerMods':run['copiedServerMods'],
            'runtime':'Minecraft 1.21.1 / NeoForge 21.1.250, complete current server mod set',
            'loopbackOnly':True,'originalWorldUsed':False,'checkedRouteBlocks':0,'failures':[],
            'boundary':'Full-pack startup and live native block checks; player flow is covered separately by the protocol-player suite.'}
    started=time.monotonic();r=None
    try:
        deadline=time.monotonic()+360
        while time.monotonic()<deadline:
            try:r=Rcon();break
            except (OSError,ConnectionError):time.sleep(1)
        if r is None:raise TimeoutError('full server did not become ready')
        maps=r.command('muxioutbreak list')
        if 'lostschool' not in maps:raise AssertionError('Outbreak did not register its campaign: '+maps)
        report['mapList']=maps.strip()
        r.command('muxioutbreak prepare lostschool')
        deadline=time.monotonic()+360
        while time.monotonic()<deadline:
            state=json.loads(r.command('muxioutbreak inspect lostschool').strip())
            if state['geometryReady']:break
            if '失败' in state['geometry']:raise AssertionError(state)
            time.sleep(1)
        else:raise TimeoutError('full-pack geometry installation did not finish')
        report['geometry']=state
        print('FULL_PACK_READY',run['copiedServerMods'],'server mods + Outbreak',flush=True)
        r.command('gamerule doMobSpawning false')
        unique=set()
        for name in ('lost','lostschool_2','lostschool_3'):
            data=np.load(ROOT/f'build/{name}-voxels.npz');grid=data['grid'];origin=data['origin'];route=data['route']
            palette=json.loads(str(data['palette']))
            expected={}
            for x,y,z in route:
                for dy in (-1,0,1):
                    p=(int(x),int(y+dy),int(z));i=tuple(np.asarray(p)-origin)
                    block=palette[int(grid[i])]['Name']
                    expected[p]=block
            bad=[]
            chunks=defaultdict(list)
            for p,block in expected.items():chunks[(p[0]//16,p[2]//16)].append((p,block))
            for (cx,cz),entries in chunks.items():
                r.command(f'execute in muxi_outbreak:campaign run forceload add {cx*16} {cz*16}')
                try:
                    deadline=time.monotonic()+30
                    p=entries[0][0]
                    while time.monotonic()<deadline:
                        loaded=r.command(f'execute in muxi_outbreak:campaign if loaded {p[0]} {p[1]} {p[2]}')
                        if 'Test passed' in loaded:break
                        time.sleep(.1)
                    else:raise TimeoutError(f'QA chunk {(cx,cz)} not loaded: {loaded}')
                    for p,block in entries:
                        if p in unique:continue
                        unique.add(p)
                        result=r.command(f'execute in muxi_outbreak:campaign if block {p[0]} {p[1]} {p[2]} {block}')
                        report['checkedRouteBlocks']+=1
                        if 'Test passed' not in result:
                            bad.append({'pos':p,'expected':block,'response':result.strip()})
                finally:
                    r.command(f'execute in muxi_outbreak:campaign run forceload remove {cx*16} {cz*16}')
            print('FULL_ROUTE_BLOCKS',name,len(expected),'mismatches',len(bad),flush=True)
            report['failures'].extend(bad)
        report['bootDone']='Done (' in (home/'console.log').read_text(encoding='utf-8',errors='replace')
        report['passed']=report['bootDone'] and not report['failures']
    except Exception:
        report['error']=traceback.format_exc();print(report['error'],flush=True)
    finally:
        if r:r.close()
        report['seconds']=round(time.monotonic()-started,2)
        (ROOT/'build/full-server-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('FULL_PACK_VALIDATION',report['passed'],'blocks',report['checkedRouteBlocks'],'failures',len(report['failures']),flush=True)
    raise SystemExit(0 if report['passed'] else 1)


if __name__=='__main__':main()
