from pathlib import Path
import argparse,os,sys,json,shutil,zipfile,subprocess,time,socket,hashlib
O=Path(__file__).resolve().parents[1]
W=O/'tests/recovery'
parser=argparse.ArgumentParser(description='QA-only hidden real network clients; never deploys or changes an existing world')
for flag in ['home','server','game','framework','jdk','artifact','world']:
    parser.add_argument('--'+flag,type=Path,required=True)
parser.add_argument('--lr',type=Path,required=True)
parser.add_argument('--port',type=int,required=True)
args=parser.parse_args();home=args.home.resolve();port=args.port
S=args.server.resolve();G=args.game.resolve();F=args.framework.resolve();J=args.jdk.resolve();A=args.artifact.resolve()
sys.path.insert(0,str(O));import build
if O not in home.parents:raise SystemExit('Owned project QA directory required')
if home.exists():raise SystemExit('New isolated home required')
try:
    c=socket.create_connection(('127.0.0.1',port),timeout=1)
except OSError:pass
else:c.close();raise SystemExit('Owned QA port occupied')
home.mkdir(parents=True);coordinator=home/'coordinator';coordinator.mkdir();serverhome=home/'server';serverhome.mkdir();mods=serverhome/'mods';mods.mkdir()
common=[A,F,*list((S/'mods').glob('tacz-neoforge-*.jar')),args.lr.resolve(),*list((S/'mods').glob('*champions-neoforge*.jar')),*list((S/'mods').glob('*architectury*.jar'))]
for f in common:shutil.copy2(f,mods/f.name)
cp=build.server_classpath(S,'21.1.250')+common
classes=home/'server-classes';build.compile_java(J/'bin/javac.exe',[W/'EquipmentServerQA.java',W/'EquipmentRestoreReceiptQAMixin.java',O/'tests/recovery/HardwareWmiTimeoutQAMixin.java'],classes,os.pathsep.join(map(str,cp)),home/'server-compile.args')
def package_qa(destination,classes,modid,mixins):
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as z:
        toml=f'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="{modid}"\nversion="0.0.1"\ndisplayName="Outbreak equipment QA ONLY"\n'
        for name,package,key,entries in mixins:
            config='equipment-'+name+'.mixins.json';toml+='[[mixins]]\nconfig="'+config+'"\n';z.writestr(config,json.dumps({'required':True,'minVersion':'0.8','package':package,'compatibilityLevel':'JAVA_21',key:entries,'injectors':{'defaultRequire':1}}))
        z.writestr('META-INF/neoforge.mods.toml',toml)
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
package_qa(mods/'outbreak-equipment-server-QA-ONLY.jar',classes,'outbreak_repro_qa',[('wmi','net.muxigame.terminal.qa.mixin','mixins',['HardwareWmiTimeoutQAMixin']),('restore','net.muxigame.outbreak.equipmentqa.mixin','mixins',['EquipmentRestoreReceiptQAMixin'])])
subprocess.run(['cmd','/c','mklink','/J',str(serverhome/'libraries'),str(S/'libraries')],check=True,capture_output=True)
world=args.world.resolve();shutil.copytree(world,serverhome/'qa-world')
(serverhome/'eula.txt').write_text('eula=true\n')
(serverhome/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=qa-world\ngamemode=survival\ndifficulty=hard\nview-distance=3\nsimulation-distance=3\nmax-tick-time=120000\nspawn-protection=0\nallow-flight=true\n')
def config(lab,client=False):
    (lab/'config').mkdir(exist_ok=True);(lab/'config/muxi-game-core.json').write_text(json.dumps({'schema':1,'features':{'identity':{'enabled':False},'login':{'enabled':False}}}))
    for f in (S/'config').glob('champions*'):
        if f.is_file():shutil.copy2(f,lab/'config'/f.name)
    shutil.copytree(S/'tacz',lab/'tacz')
    if client:
        (lab/'config/fml.toml').write_text('earlyWindowControl = false\nearlyWindowProvider = ""\nversionCheck = false\n')
        (lab/'options.txt').write_text('lang:zh_cn\nmaxFps:30\nenableVsync:false\nonboardAccessibility:false\nsoundCategory_master:0.0\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\ngraphicsMode:0\n')
config(serverhome)
def allowed(rules):
    if not rules:return True
    result=False
    for rule in rules:
        platform=rule.get('os',{})
        if platform.get('name','windows')!='windows' or platform.get('arch','x86_64') not in ('x86_64','amd64'):continue
        if any((key=='has_custom_resolution')!=value for key,value in rule.get('features',{}).items()):continue
        result=rule.get('action')=='allow'
    return result
meta=json.loads((G/'versions/BatterMC5Remake/BatterMC5Remake.json').read_text(encoding='utf-8'))
libs=list(dict.fromkeys([G/'libraries'/x['downloads']['artifact']['path'] for x in meta['libraries'] if allowed(x.get('rules')) and x.get('downloads',{}).get('artifact')]+[G/'versions/BatterMC5Remake/BatterMC5Remake.jar']))
clientcp=[G/'libraries/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-client.jar',G/'libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar',*libs,*cp]
classes=home/'client-classes';build.compile_java(J/'bin/javac.exe',[W/'EquipmentClientQA.java',O/'tests/recovery/HiddenWindowMixin.java',O/'tests/recovery/HardwareWmiTimeoutQAMixin.java'],classes,os.pathsep.join(map(str,clientcp)),home/'client-compile.args')
clientjar=home/'outbreak-equipment-client-QA-ONLY.jar';package_qa(clientjar,classes,'outbreak_equipment_client_qa',[('window','net.muxigame.terminal.smoke.mixin','client',['HiddenWindowMixin']),('wmi','net.muxigame.terminal.qa.mixin','client',['HardwareWmiTimeoutQAMixin'])])
clients={}
for role,name in [('host','OutbreakHostQA'),('guest','OutbreakGuestQA')]:
    lab=home/role;lab.mkdir();(lab/'mods').mkdir();config(lab,True)
    for f in common+[clientjar]:shutil.copy2(f,lab/'mods'/f.name)
    shutil.copytree(G/'versions/BatterMC5Remake/BatterMC5Remake-natives',lab/'natives')
    subs={'auth_player_name':name,'auth_uuid':hashlib.md5(('OfflinePlayer:'+name).encode()).hexdigest(),'auth_access_token':'0','version_name':'BatterMC5Remake','game_directory':str(lab),'assets_root':str(G/'assets'),'assets_index_name':meta['assetIndex']['id'],'clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','resolution_width':'960','resolution_height':'540','natives_directory':str(lab/'natives'),'launcher_name':'outbreak-hidden-network-qa','launcher_version':'1','classpath':os.pathsep.join(map(str,libs)),'library_directory':str(G/'libraries'),'classpath_separator':os.pathsep}
    def expand(items):
        result=[]
        for item in items:
            if isinstance(item,dict):
                if not allowed(item.get('rules')):continue
                vals=item['value'] if isinstance(item['value'],list) else [item['value']]
            else:vals=[item]
            for value in vals:
                for key,replacement in subs.items():value=value.replace('${'+key+'}',replacement)
                if '${' in value:raise ValueError(value)
                result.append(value)
        return result
    jvm=['-Xms256M','-Xmx2300M','-XX:ActiveProcessorCount=3','-Dfile.encoding=UTF-8',f'-Dqa.outbreak.role={role}',f'-Dqa.outbreak.port={port}',f'-Dqa.outbreak.coordinator={coordinator}','-Dhttp.proxyHost=127.0.0.1','-Dhttp.proxyPort=9','-Dhttps.proxyHost=127.0.0.1','-Dhttps.proxyPort=9',*expand(meta['arguments']['jvm']),meta['mainClass'],*expand(meta['arguments']['game'])]
    (lab/'launch.args').write_text('\n'.join('"'+str(x).replace('\\','/').replace('"','\\"')+'"' for x in jvm),encoding='utf-8');clients[role]=lab
processes={};logs={};result={'actualNetworkClients':2,'fakePlayers':False,'hidden':True,'productionOperations':False,'port':port,'artifactSha256':hashlib.sha256(A.read_bytes()).hexdigest(),'frameworkSha256':hashlib.sha256(F.read_bytes()).hexdigest()}
try:
    logs['server']=(serverhome/'boot.log').open('w',encoding='utf-8')
    serverargs=[str(J/'bin/java.exe'),'-Xms256M','-Xmx2300M','-XX:ActiveProcessorCount=3','-Dmuxi.outbreak.recovery.qa=true',f'-Dqa.outbreak.coordinator={coordinator}','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','@libraries/net/neoforged/neoforge/21.1.250/win_args.txt','nogui']
    processes['server']=subprocess.Popen(serverargs,cwd=serverhome,stdin=subprocess.PIPE,stdout=logs['server'],stderr=subprocess.STDOUT,text=True)
    ready_deadline=time.monotonic()+180
    while True:
        if processes['server'].poll() is not None:raise RuntimeError('Owned server exited during boot')
        try:
            probe=socket.create_connection(('127.0.0.1',port),timeout=1);probe.close();break
        except OSError:
            if time.monotonic()>ready_deadline:raise TimeoutError('Owned server did not open its port')
            time.sleep(.2)
    for role,lab in clients.items():
        logs[role]=(lab/'boot.log').open('w',encoding='utf-8');processes[role]=subprocess.Popen([str(J/'bin/java.exe'),'@'+str(lab/'launch.args')],cwd=lab,stdin=subprocess.DEVNULL,stdout=logs[role],stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    (home/'run.json').write_text(json.dumps({'pids':{r:p.pid for r,p in processes.items()},**result},indent=2));print('EQUIPMENT_NETWORK_QA '+json.dumps({r:p.pid for r,p in processes.items()}),flush=True)
    limit=time.monotonic()+1100;last=0
    while processes['server'].poll() is None:
        if time.monotonic()>limit:raise TimeoutError('Owned equipment QA exceeded 1100 seconds')
        if time.monotonic()-last>15:
            progress=serverhome/'equipment-progress.json'
            try:print('EQUIPMENT_PROGRESS '+progress.read_text(),flush=True)
            except (OSError,json.JSONDecodeError):pass
            last=time.monotonic()
        for r,p in processes.items():
            if r!='server' and p.poll() is not None and not (serverhome/'equipment-result.json').exists():raise RuntimeError('Real client exited early: '+r)
        time.sleep(.5)
    result['passed']=(serverhome/'equipment-result.json').exists() and not (serverhome/'failure.txt').exists()
except Exception as e:result['passed']=False;result['error']=str(e);print('EQUIPMENT_QA_FAILED '+str(e),flush=True)
finally:
    for role in clients:
        p=coordinator/f'command-{role}.json';tmp=p.with_suffix('.tmp');tmp.write_text(json.dumps({'id':1000000,'type':'stop'}));os.replace(tmp,p)
    for r,p in processes.items():
        if r!='server':
            try:p.wait(timeout=180)
            except subprocess.TimeoutExpired:result.setdefault('normalStopBlocked',[]).append({'role':r,'pid':p.pid})
    server=processes.get('server')
    if server and server.poll() is None:
        try:server.stdin.write('stop\n');server.stdin.flush()
        except OSError:pass
    if server:
        try:server.wait(timeout=180)
        except subprocess.TimeoutExpired:result.setdefault('normalStopBlocked',[]).append({'role':'server','pid':server.pid})
    result['exitCodes']={r:p.poll() for r,p in processes.items()};result['normalExit']=all(code==0 for code in result['exitCodes'].values())
    for log in logs.values():log.close()
    (home/'run-result.json').write_text(json.dumps(result,indent=2));print('EQUIPMENT_NETWORK_RESULT '+json.dumps(result),flush=True)
if not result.get('passed') or not result.get('normalExit'):raise SystemExit(1)
