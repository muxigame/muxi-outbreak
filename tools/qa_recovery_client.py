from pathlib import Path
import sys,os,shutil,json,zipfile,subprocess,hashlib,time
HERE=Path(__file__).resolve().parent
import argparse
parser=argparse.ArgumentParser(description="Isolated Outbreak recovery QA; never a production launcher")
parser.add_argument("--server",type=Path,required=True)
parser.add_argument("--framework",type=Path,required=True)
parser.add_argument("--jdk",type=Path,required=True)
parser.add_argument("--home",type=Path,required=True)
parser.add_argument("--game",type=Path)
args=parser.parse_args()
ROOT=Path(__file__).resolve().parents[1]
SERVER=args.server.resolve()
GAME=args.game.resolve() if args.game else None
FRAMEWORK=args.framework.resolve()
JDK=args.jdk.resolve()
sys.path.insert(0,str(ROOT));import build
def allowed(rules):
    if not rules:return True
    result=False
    for rule in rules:
        platform=rule.get("os",{})
        if platform.get("name","windows")!="windows":continue
        if platform.get("arch","x86_64") not in ("x86_64","amd64"):continue
        if any((key=="has_custom_resolution")!=value for key,value in rule.get("features",{}).items()):continue
        result=rule.get("action")=="allow"
    return result
def argfile(path,args):path.write_text("\n".join(chr(34)+str(a).replace("\\","/").replace(chr(34),"\\"+chr(34))+chr(34) for a in args),encoding="utf-8")
def configure_file(path):path.write_text("earlyWindowControl = false\nearlyWindowProvider = \"\"\nversionCheck = false\n",encoding="utf-8")
home=args.home.resolve();home.mkdir(parents=True,exist_ok=True)
if ROOT not in home.parents:raise SystemExit("QA home must be inside this project; use a new build/recovery directory")
if (home/"run.json").exists() or (home/"inputs.json").exists():raise SystemExit("Use a new QA home; prior process ownership must be checked before reuse")
for name in ['native-result.json','native-evidence.json','native-progress.json']:
    if (home/name).exists():(home/name).unlink()
mods=home/'mods';mods.mkdir(exist_ok=True)
artifact=ROOT/'build/libs'/json.loads((ROOT/'build/release.json').read_text())['artifact']
for f in [artifact,FRAMEWORK,*list((SERVER/'mods').glob('tacz-neoforge-*.jar')),*list((ROOT/'build/equipment-research').glob('LesRaisins*.jar'))]:shutil.copy2(f,mods/f.name)
meta=json.loads((GAME/'versions/BatterMC5Remake/BatterMC5Remake.json').read_text(encoding='utf-8'))
libs=[GAME/'libraries'/x['downloads']['artifact']['path'] for x in meta['libraries'] if allowed(x.get('rules')) and x.get('downloads',{}).get('artifact')]
libs=list(dict.fromkeys(libs+[GAME/'versions/BatterMC5Remake/BatterMC5Remake.jar']))
missing=[str(f) for f in libs if not f.is_file()]
if missing:raise SystemExit('Missing libraries: '+json.dumps(missing[:10]))
classes=home/'qa-classes'
cp=[GAME/'libraries/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-client.jar',GAME/'libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar',*libs,*build.server_classpath(SERVER,'21.1.250'),artifact,FRAMEWORK]
build.compile_java(JDK/'bin/javac.exe',[ROOT/'tests/recovery/NativeClientQA.java',ROOT/'tests/recovery/HiddenWindowMixin.java',ROOT/'tests/recovery/HardwareWmiTimeoutQAMixin.java'],classes,os.pathsep.join(map(str,cp)),home/'compile.args')
with zipfile.ZipFile(mods/'outbreak-native-QA-ONLY.jar','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="outbreak_native_qa"\nversion="0.0.1"\ndisplayName="Outbreak isolated real client QA ONLY"\n[[mixins]]\nconfig="outbreak-native-window.mixins.json"\n[[mixins]]\nconfig="outbreak-native-wmi.mixins.json"\n')
    for name,package,mixins in [('window','net.muxigame.terminal.smoke.mixin',['HiddenWindowMixin']),('wmi','net.muxigame.terminal.qa.mixin',['HardwareWmiTimeoutQAMixin'])]:z.writestr('outbreak-native-'+name+'.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':package,'compatibilityLevel':'JAVA_21','client':mixins,'injectors':{'defaultRequire':1}}))
    for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
(home/'config').mkdir(exist_ok=True)
(home/'config/muxi-game-core.json').write_text(json.dumps({'schema':1,'features':{'identity':{'enabled':False},'login':{'enabled':False}}}))
configure_file(home/'config/fml.toml')
(home/'options.txt').write_text('lang:zh_cn\nguiScale:2\nmaxFps:30\nenableVsync:false\nonboardAccessibility:false\nsoundCategory_master:0.0\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\ngraphicsMode:0\n')
if not (home/'natives').exists():shutil.copytree(GAME/'versions/BatterMC5Remake/BatterMC5Remake-natives',home/'natives')
if (SERVER/'tacz').exists() and not (home/'tacz').exists():shutil.copytree(SERVER/'tacz',home/'tacz')
subs={'auth_player_name':'OutbreakNativeQA','auth_uuid':'b37642a237df4f66abe03e682b35bcae','auth_access_token':'0','version_name':'BatterMC5Remake','game_directory':str(home),'assets_root':str(GAME/'assets'),'assets_index_name':meta['assetIndex']['id'],'clientid':'','auth_xuid':'','user_type':'legacy','version_type':'release','resolution_width':'1280','resolution_height':'720','natives_directory':str(home/'natives'),'launcher_name':'outbreak-isolated-hidden-qa','launcher_version':'1','classpath':os.pathsep.join(map(str,libs)),'library_directory':str(GAME/'libraries'),'classpath_separator':os.pathsep}
def expand(items):
    result=[]
    for item in items:
        if isinstance(item,dict):
            if not allowed(item.get('rules')):continue
            vals=item['value'] if isinstance(item['value'],list) else [item['value']]
        else:vals=[item]
        for value in vals:
            for key,replacement in subs.items():value=value.replace('${'+key+'}',replacement)
            if '${' in value:raise ValueError('Unresolved launch substitution '+value)
            result.append(value)
    return result
args=['-Xms256M','-Xmx2300M','-XX:ActiveProcessorCount=3','-Dfile.encoding=UTF-8','-Dhttp.proxyHost=127.0.0.1','-Dhttp.proxyPort=9','-Dhttps.proxyHost=127.0.0.1','-Dhttps.proxyPort=9',*expand(meta['arguments']['jvm']),meta['mainClass'],*expand(meta['arguments']['game'])]
argfile(home/'launch.args',args)
provenance={'qaOnly':True,'visible':False,'focusRequested':False,'realMinecraftClient':True,'originalWorldUsed':False,'outbreakSha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'mods':[{'file':f.name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in mods.glob('*.jar')]}
with (home/'boot.log').open('w',encoding='utf-8') as out:
    process=subprocess.Popen([str(JDK/'bin/java.exe'),'@'+str(home/'launch.args')],cwd=home,stdin=subprocess.DEVNULL,stdout=out,stderr=subprocess.STDOUT)
    provenance['pid']=process.pid;(home/'inputs.json').write_text(json.dumps(provenance,indent=2));print('NATIVE_QA_CLIENT',process.pid,flush=True)
    result=process.wait();(home/'exit.json').write_text(json.dumps({'exitCode':result,'normalExit':result==0}));print('NATIVE_EXIT',result,flush=True)
