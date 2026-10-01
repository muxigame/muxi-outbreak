"""Scripted two-player integration acceptance against the real isolated NeoForge server.

The harness uses admin teleports between validated route points to test triggers;
it is not a claim that a graphical client/human has walked every room or fought
the campaign. No forced-win command, fake game clock or session-field mutation.
"""
from __future__ import annotations
import json
from pathlib import Path
import re
import time
import traceback

from qa_rcon import Rcon, ROOT

HOME=ROOT/'build/qa-server'
PLAYERS=('OutbreakQA','OutbreakQB')
checks=[]
commands=[]
started=time.time()
rcon=Rcon()
campaign=json.loads((ROOT/'src/main/resources/data/muxi_outbreak/outbreak_maps/lostschool.json').read_text(encoding='utf-8'))


def command(text):
    result=rcon.command(text)
    commands.append({'command':text,'result':result,'time':round(time.time()-started,3)})
    return result


def inspect():return json.loads(command('muxioutbreak inspect lostschool').strip())


def check(name,condition,details=None):
    entry={'name':name,'passed':bool(condition),'details':details,'seconds':round(time.time()-started,3)}
    checks.append(entry)
    print(json.dumps(entry,ensure_ascii=False),flush=True)
    if not condition:raise AssertionError(name+': '+str(details))


def until(predicate,seconds=30):
    deadline=time.monotonic()+seconds
    last=None
    while time.monotonic()<deadline:
        last=predicate()
        if last:return last
        time.sleep(.25)
    raise AssertionError('timed out waiting for '+str(predicate)+' last='+repr(last))


def action(name,act,**data):
    with (HOME/'bot-actions.jsonl').open('a',encoding='utf-8') as out:
        out.write(json.dumps({'name':name,'action':act,**data})+'\n')


def as_player(name,text):return command(f'execute as {name} at @s run {text}')


def tp(name,p,dimension='muxi_outbreak:campaign'):
    x,y,z=p
    result=command(f'execute in {dimension} run tp {name} {x+.5} {y} {z+.5}')
    if 'No entity' in result or 'Incorrect' in result:raise AssertionError(result)


def protect():
    for name in PLAYERS:command(f'effect give {name} minecraft:resistance 1200 255 true')


def clear_infected():
    command('execute in muxi_outbreak:campaign run kill @e[tag=muxi_outbreak]')
    time.sleep(.15)


def start_party():
    as_player(PLAYERS[0],'muxioutbreak start lostschool')
    state=inspect()
    check('host_creation',state.get('phase')=='COUNTDOWN',state)
    as_player(PLAYERS[1],'muxioutbreak join '+state['session'])
    state=inspect()
    check('two_player_join',state.get('players')==2 and state.get('phase')=='COUNTDOWN',state)
    until(lambda:inspect().get('phase')=='RUNNING',20)
    return state['session']


def assert_restored(label):
    for name in PLAYERS:
        dim=command(f'data get entity {name} Dimension')
        inv=command(f'data get entity {name} Inventory')
        mode=command(f'data get entity {name} playerGameType')
        recovery=command(f'data get entity {name} NeoForgeData.PlayerPersisted.muxi_outbreak_return_v1')
        check(label+'_'+name,'minecraft:overworld' in dim and 'minecraft:diamond' in inv and
              'minecraft:iron_sword' not in inv and re.search(r'\b0\b',mode) is not None,
              {'dimension':dim,'inventory':inv,'mode':mode,'recoveryProbe':recovery})


def main():
    state=until(lambda:(s if (s:=inspect()).get('geometryReady') else None),300)
    check('native_geometry_ready',state['geometryReady'],state)
    online=command('list')
    for name in PLAYERS:
        if name not in online:action(name,'reconnect')
    online=until(lambda:(value if all(p in (value:=command('list')) for p in PLAYERS) else None),30)
    check('protocol_players_logged_in',all(name in online for name in PLAYERS),online)
    # These settings affect ONLY the loopback fixture, not the live server.
    command('gamerule doMobSpawning false')
    command('gamerule doDaylightCycle false')
    command('gamerule doWeatherCycle false')
    old=inspect()
    if old.get('session'):as_player(PLAYERS[0],'muxioutbreak stop')
    for i,name in enumerate(PLAYERS):
        command('op '+name)
        command('gamemode survival '+name)
        command('clear '+name)
        command(f'give {name} minecraft:diamond 3')
        tp(name,[i*2,-60,0],'minecraft:overworld')
    winning_session=start_party()
    for name in PLAYERS:
        dim=command(f'data get entity {name} Dimension')
        inv=command(f'data get entity {name} Inventory')
        check('native_entry_and_temporary_kit_'+name,'muxi_outbreak:campaign' in dim and 'minecraft:iron_sword' in inv and 'minecraft:diamond' not in inv,{'dimension':dim,'inventory':inv})
    protect()
    action('OutbreakQA','drop')
    time.sleep(.4)
    sword=command('data get entity OutbreakQA Inventory[{id:"minecraft:iron_sword"}].count')
    dropped=command('execute in muxi_outbreak:campaign if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:iron_sword"}}]')
    check('temporary_kit_cannot_be_dropped',bool(re.search(r'\b1\b',sword)) and 'Test failed' in dropped,
          {'inventorySwordCount':sword,'droppedSwordEntities':dropped})
    clear_infected()
    as_player(PLAYERS[0],'muxioutbreak spawn horde')
    state=inspect()
    check('horde_spawned_in_real_world',state['infected']['COMMON']>=4,state)
    # Clear between tests so the mob cap and mutual collision cannot hide failures.
    for kind in ('hunter','smoker','boomer','spitter','jockey','tank','witch','charger'):
        clear_infected()
        as_player(PLAYERS[0],'muxioutbreak spawn '+kind)
        state=inspect()
        check('special_spawn_'+kind,state['infected'][kind.upper()]>=1,state)
    clear_infected()
    as_player(PLAYERS[0],'muxioutbreak director disable')
    command('effect clear OutbreakQA minecraft:resistance')
    command('damage OutbreakQA 100 minecraft:generic')
    until(lambda:inspect().get('downed')==1)
    check('lethal_damage_becomes_incapacitation',inspect()['downed']==1)
    action('OutbreakQB','sneak',value=True)
    until(lambda:inspect().get('downed')==0,12)
    action('OutbreakQB','sneak',value=False)
    health=command('data get entity OutbreakQA Health')
    check('three_second_teammate_revive','7.0f' in health or '7.0' in health,health)
    protect()
    as_player(PLAYERS[0],'muxioutbreak director enable')
    for section in range(2):
        event=campaign['panicEvents'][section]
        for name in PLAYERS:tp(name,event['pos'])
        until(lambda:inspect().get('panicEvents',0)>=section+1)
        check('source_route_panic_'+str(section),True,inspect())
        clear_infected()
        end=campaign['chapters'][section]['end']
        tp(PLAYERS[0],end)
        time.sleep(.4)
        check('safe_room_waits_for_whole_team_'+str(section),inspect().get('phase')=='RUNNING',inspect())
        tp(PLAYERS[1],end)
        until(lambda:inspect().get('phase')=='SAFE_ROOM',8)
        state=inspect()
        check('safe_room_advance_'+str(section),state['section']==section+1 and sum(state['infected'].values())==0,state)
        until(lambda:inspect().get('phase')=='RUNNING',15)
        for name in PLAYERS:
            pos=command(f'data get entity {name} Pos')
            expected=campaign['chapters'][section+1]['start']
            check('chapter_spawn_'+str(section+1)+'_'+name,str(expected[0]+.5) in pos and str(expected[1])+'.0' in pos,pos)
    # Final extraction uses the unmodified 60-second countdown and the actual Tank lifecycle.
    protect();clear_infected()
    for name in PLAYERS:tp(name,campaign['finish'])
    until(lambda:inspect().get('finaleWaves',0)>=1)
    begin=time.monotonic();tank_seen=False;last=inspect()
    deadline=time.monotonic()+100
    while time.monotonic()<deadline:
        state=inspect()
        if not state.get('session'):break
        last=state
        if state.get('finaleTankSpawned') and state.get('infected',{}).get('TANK',0)>0:
            tank_seen=True
        clear_infected()
        time.sleep(.8)
    check('finale_tank_really_spawned',tank_seen,last)
    check('no_premature_extraction',time.monotonic()-begin>=58,round(time.monotonic()-begin,2))
    check('natural_campaign_completion',not inspect().get('session'),last)
    log=(HOME/'console.log').read_text(encoding='utf-8',errors='replace')
    check('server_recorded_victory',f'session={winning_session} win=true section=2' in log)
    assert_restored('victory_restored')
    # Second game: wipe/cleanup and state restoration, not just the happy path.
    start_party();clear_infected()
    as_player(PLAYERS[0],'muxioutbreak director disable')
    for name in PLAYERS:
        command('effect clear '+name)
        command(f'damage {name} 100 minecraft:generic')
    until(lambda:not inspect().get('session'),10)
    check('team_wipe_ends_session',not inspect().get('session'))
    assert_restored('wipe_restored')
    # Third game: disconnect must not strand an adventure-mode player or temporary kit.
    start_party();protect();clear_infected()
    action('OutbreakQB','disconnect')
    until(lambda:inspect().get('players')==1)
    action('OutbreakQB','reconnect')
    until(lambda:'OutbreakQB' in command('list'),20)
    dim=command('data get entity OutbreakQB Dimension')
    inv=command('data get entity OutbreakQB Inventory')
    check('logout_restores_before_reconnect','minecraft:overworld' in dim and 'minecraft:diamond' in inv,{'dimension':dim,'inventory':inv})
    as_player('OutbreakQA','muxioutbreak leave')
    check('explicit_leave_cleans_session',not inspect().get('session'))
    assert_restored('leave_restored')


if __name__=='__main__':
    passed=False
    failure=None
    try:
        main();passed=True
    except Exception:
        failure=traceback.format_exc();print(failure,flush=True)
    finally:
        report={'passed':passed,'kind':'two scripted protocol players on real NeoForge 21.1.250 / MC 1.21.1',
            'jarSha256':json.loads((HOME/'run.json').read_text(encoding='utf-8'))['jarSha256'],
            'checks':checks,'commands':commands,'failure':failure,'durationSeconds':round(time.time()-started,2),
            'boundaries':['Fixture has NeoForge and Outbreak only, not the complete Better MC mod set.',
                'Trigger travel uses admin teleports to real converted route points; no graphical client/manual traversal.',
                'Finale enemies are killed by test commands; this validates lifecycle, not human combat balance.']}
        (ROOT/'build/e2e-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        rcon.close()
    raise SystemExit(0 if passed else 1)
