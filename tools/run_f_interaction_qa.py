"""Real-client native F regression; private fixtures, no OS input or production writes."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time
import uuid
import zipfile

def main():
    parser=argparse.ArgumentParser()
    for key in ('outbreak','project','server','game','java-home','core','framework','artifact','lr','world','instance-root'):
        parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--port',type=int,required=True)
    parser.add_argument('--unix-temp',type=Path,required=True)
    parser.add_argument('--legacy-repro',action='store_true')
    a=parser.parse_args();sys.path.insert(0,str(a.project/'scripts'))
    import local_mc_debug as debug
    import local_mc_runtime as rt
    if hashlib.sha1(a.core.read_bytes()).hexdigest()!='e7ddcadd791168ab73403f590feeec7b603c6e7f':raise ValueError('Core must match published 1.4.31 input')
    work=a.outbreak/'build'/('f-interaction-compile-'+uuid.uuid4().hex[:8]);work.mkdir()
    sources=a.outbreak/'tests/interaction'
    metadata=rt.client_metadata(a.game,'BatterMC5Remake');libraries=rt.client_libraries(a.game,'BatterMC5Remake',metadata)
    dependencies=[a.core,a.framework,a.artifact,a.lr]
    for pattern in ('tacz-neoforge-*.jar','architectury-*.jar','waystones-*.jar','balm-*.jar'):
        rows=list((a.server/'mods').glob(pattern));assert len(rows)==1,(pattern,rows);dependencies.extend(rows)
    cp=rt.javac_classpath(a.server,a.game,'21.1.250',libraries)+os.pathsep+os.pathsep.join(map(str,dependencies))
    classes=work/'classes';classes.mkdir();args=work/'javac.args'
    rt.argfile(args,['--release','21','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',classes,*sorted(sources.glob('*.java'))])
    with (work/'compile.log').open('w',encoding='utf-8') as log:
        subprocess.run([str(a.java_home/'bin/javac.exe'),'@'+str(args)],stdout=log,stderr=subprocess.STDOUT,check=True)
    agent=work/'outbreak-F-interaction-QA-ONLY.jar'
    with zipfile.ZipFile(agent,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="outbreak_interaction_qa"\nversion="0.0.1"\ndisplayName="Outbreak F interaction QA ONLY"\n[[mixins]]\nconfig="outbreak_interaction_qa.mixins.json"\n')
        z.writestr('outbreak_interaction_qa.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'net.muxigame.outbreak.interactionqa.mixin','compatibilityLevel':'JAVA_21','mixins':['InteractionRequestQAMixin'],'injectors':{'defaultRequire':1}}))
        for file in classes.rglob('*.class'):z.write(file,file.relative_to(classes).as_posix())
    command=[sys.executable,'-B',str(a.project/'scripts/local_mc_debug.py'),'run','--project-root',str(a.project),'--instance-root',str(a.instance_root),'--server-runtime',str(a.server),'--client-game',str(a.game),'--java-home',str(a.java_home),'--port',str(a.port),'--clients','2','--mode','hold','--hold-seconds','900','--boot-timeout','240','--accept-eula','--unix-temp',str(a.unix_temp),'--world',str(a.world),'--data-dir',str(a.server/'tacz')]
    for jar in [*dependencies,agent]:command.extend(['--mod',str(jar)])
    log=(work/'runner.log').open('w',encoding='utf-8');runner=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
    report={'passed':False,'legacyRepro':a.legacy_repro,'artifactSha256':rt.sha256(a.artifact),'coreSha256':rt.sha256(a.core),'frameworkSha256':rt.sha256(a.framework),'nativeKeyboardHandler':True,'physicalOSInput':False,'visualAcceptance':False,'SSOAcceptance':False,'assistedMapFixtures':True,'longCampaignAcceptance':False,'evidence':[]}
    ids={};root=a.instance_root;marker=None
    def wait(label,test,seconds=240):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            found=test()
            if found:return found
            if runner.poll() is not None:raise RuntimeError('Runner exited before '+label+'; see '+str(work/'runner.log'))
            time.sleep(.2)
        raise TimeoutError(label)
    def check(label,condition,**data):
        row={'check':label,'passed':bool(condition),**data};report['evidence'].append(row)
        print(json.dumps(row),flush=True)
        rt.write_json(work/'f-interaction-result.json',report)
        if not condition:raise AssertionError(label)
    def cmd(role,payload):return debug.command(root,role,payload,40)
    def qa(role,typ,**fields):
        number=ids.get(role,0)+1;ids[role]=number
        rt.write_json(root/'coordinator'/f'interaction-command-{role}.json',{'runId':marker['runId'],'id':number,'type':typ,**fields})
        result=wait('QA callback '+role+str(number),lambda:rt.read_json(root/'coordinator'/f'interaction-result-{role}-{number}.json'),40)
        if not result.get('ok'):raise RuntimeError(str(result))
        return result
    def observe():return qa('server','observe')['status']
    def aim(status):
        cmd('host',{'type':'aim','x':status['aimX'],'y':status['aimY'],'z':status['aimZ']})
        time.sleep(.3)
    def fixture(kind):
        result=qa('server','fixture',kind=kind)['status'];time.sleep(.8);aim(result);return observe()
    def press(**fields):
        before=observe();native=qa('host','f',**fields);time.sleep(.5);after=observe()
        check('F_claim_no_tacz_click',native['serverIssuedFContext'] and native['taczClicks']==0 and not native['taczDown'],native=native)
        check('one_F_request_despite_repeat',after['packets']==before['packets']+1,before=before['packets'],after=after['packets'])
        return after
    try:
        marker=wait('owner marker',lambda:rt.read_json(root/'local-mc-owner.json'))
        wait('two real clients',lambda:(rt.read_json(root/'coordinator/status-server.json') or {}).get('players')==2 and all((rt.read_json(root/'coordinator'/f'status-{role}.json') or {}).get('connected') for role in ('host','guest')))
        outside=cmd('server',{'type':'observe'})['status']['byName']['DebugHost']['dimension']
        cmd('host',{'type':'game-action','game':'outbreak','action':'createConfigured','value':'{"map":"lostschool","mode":"CAMPAIGN","difficulty":1}'})
        snapshot=cmd('server',{'type':'game-snapshot','player':'DebugHost','game':'outbreak'})['snapshot']
        # Runtime snapshots can wrap per-game data depending on the framework generation.
        def rooms(data):
            if isinstance(data,dict):
                if 'rooms' in data:return data['rooms']
                for value in data.values():
                    found=rooms(value)
                    if found:return found
            elif isinstance(data,list):
                for value in data:
                    found=rooms(value)
                    if found:return found
            return []
        rows=rooms(snapshot);assert len(rows)==1,snapshot
        cmd('guest',{'type':'game-action','game':'outbreak','action':'join','value':rows[0]['id']})
        wait('prepared source map',lambda:rooms(cmd('server',{'type':'game-snapshot','player':'DebugHost','game':'outbreak'})['snapshot'])[0]['mapReady'])
        cmd('host',{'type':'game-action','game':'outbreak','action':'start','value':''})
        wait('native start room',lambda:observe()['phase']=='START_ROOM')
        wait('server-issued F context',lambda:qa('host','observe')['serverIssuedFContext'],30)
        cmd('server',{'type':'inventory-set','player':'DebugHost','slot':0,'item':'minecraft:air','count':0})
        before=fixture('checkpoint-start');after=press(repeats=5)
        if a.legacy_repro:
            check('reproduced_1_4_31_F_does_not_open_checkpoint',before['open']==after['open']==False)
            before=fixture('wood');after=press()
            check('reproduced_1_4_31_F_does_not_open_ordinary_door',before['open']==after['open']==False)
        else:
            check('F_opens_start_checkpoint_group',not before['open'] and after['open'])
            aim(after);after=press();check('F_closes_start_checkpoint_group',not after['open'])
            for kind in ('checkpoint-safe','checkpoint-future','wood','iron','trapdoor','gate','button','lever','chest','bed'):
                before=fixture(kind);after=press(repeats=3)
                if kind=='checkpoint-future':check('future_checkpoint_stays_locked',before['open']==after['open'])
                elif kind=='bed':check('scenery_bed_not_exploded',after['block']=='minecraft:red_bed')
                elif kind=='chest':check('campaign_external_container_guard_preserved',after['menu']=='InventoryMenu');qa('host','close')
                elif kind in ('button','lever'):check('F_activates_'+kind,after['powered'])
                else:check('F_toggles_'+kind,before['open']!=after['open'])
            for kind in ('gun:tacz:hk_mp5a5','melee','medkit','pills','adrenaline','defib','explosive_pack','grenade','pipe_bomb','bile_bomb','ammo','upgrade_station'):
                before=fixture('supply-'+kind);after=press(repeats=5)
                if kind.startswith('gun:'):
                    check('F_swaps_primary_and_preserves_old_stock',after['inventory']['0']['gun']=='tacz:hk_mp5a5' and after['nodeRemaining']==1 and after['supplyNodes']==before['supplyNodes']+1)
                    before=fixture('wood');after=press();check('F_opens_door_with_gun_without_changing_gun',after['open'] and after['inventory']['0']['components']==before['inventory']['0']['components'])
                elif kind=='ammo':check('F_refills_ammunition',after['looseAmmo']>before['looseAmmo'])
                elif kind=='upgrade_station':check('F_deploys_explosive_ammo_once',after['explosiveRounds']>0 and after['nodeRemaining']==1)
                else:
                    slot='1' if kind=='melee' else '4' if kind in ('pills','adrenaline') else '3' if kind in ('medkit','defib','explosive_pack') else '2';check('F_takes_'+kind+'_once',after['inventory'][slot]['count']==1 and after['nodeRemaining']==1)
            before=fixture('wood');after=qa('server','duplicate-direct')['status'];check('duplicate_same_tick_toggles_only_once',not before['open'] and after['open'])
            for condition in ('far','wall','downed'):
                before=fixture('wood');qa('server','conditions',condition=condition);after=press();check('F_rejects_'+condition,not after['open'])
            before=fixture('supply-medkit');qa('server','conditions',condition='wall');after=press();check('F_cannot_pick_up_through_wall',after['nodeRemaining']==2)
            before=fixture('supply-medkit');qa('server','conditions',condition='door');after=press();check('nearer_door_wins_and_supply_not_taken',after['occludingDoorOpen'] and after['nodeRemaining']==2);after=press();check('next_F_takes_supply_after_door_open',after['nodeRemaining']==1)
            fixture('supply-medkit');after=press();after=press();check('finite_medkit_merge_two',after['inventory']['3']['count']==2 and after['nodeRemaining']==0)
            cmd('host',{'type':'select','slot':3});cmd('host',{'type':'drop','all':False});after=observe();check('native_Q_still_drops_exactly_one',after['inventory']['3']['count']==1)
            cmd('host',{'type':'drop','all':True});after=observe();check('native_CtrlQ_still_drops_remainder','3' not in after['inventory'])
        cmd('host',{'type':'game-action','game':'outbreak','action':'leave','value':''})
        time.sleep(1);check('leave_restores_outside_dimension',cmd('server',{'type':'observe'})['status']['byName']['DebugHost']['dimension']==outside)
        report['passed']=True
    except Exception as error:
        report['error']=repr(error);print(json.dumps({'error':repr(error),'work':str(work)}),flush=True)
    finally:
        if marker:debug.stop_files(root,marker)
        try:runner.wait(timeout=150)
        except subprocess.TimeoutExpired:report['normalStopBlocked']=True
        log.close();report['runnerExitCode']=runner.poll();report['runResult']=rt.read_json(root/'run-result.json')
        report['passed']=report['passed'] and report['runnerExitCode']==0 and (report['runResult'] or {}).get('normalExit',False)
        rt.write_json(work/'f-interaction-result.json',report)
        if root.exists():rt.write_json(root/'f-interaction-result.json',report)
        print(json.dumps({'passed':report['passed'],'work':str(work),'lab':str(root),'exitCodes':(report['runResult'] or {}).get('exitCodes')}),flush=True)
    return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
