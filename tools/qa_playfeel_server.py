from pathlib import Path
import sys,os,shutil,json,zipfile,subprocess
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
FRAMEWORK=args.framework.resolve()
JDK=args.jdk.resolve()
sys.path.insert(0,str(ROOT));import build
home=args.home.resolve();home.mkdir(parents=True,exist_ok=True)
if ROOT not in home.parents:raise SystemExit("QA home must be inside this project; use a new build/recovery directory")
if (home/"run.json").exists() or (home/"inputs.json").exists():raise SystemExit("Use a new QA home; prior process ownership must be checked before reuse")
if (home/'failure.txt').exists():(home/'failure.txt').unlink()
import socket
try:
    probe=socket.create_connection(('127.0.0.1',25817),timeout=1)
except OSError:pass
else:
    probe.close();raise SystemExit('Recovery QA port 25817 is occupied; stop only its owner normally first')
mods=home/'mods';mods.mkdir(exist_ok=True)
artifact=ROOT/'build/libs'/json.loads((ROOT/'build/release.json').read_text())['artifact']
for file in [artifact,FRAMEWORK,*list((SERVER/'mods').glob('tacz-neoforge-*.jar')),*list((ROOT/'build/equipment-research').glob('LesRaisins*.jar'))]:shutil.copy2(file,mods/file.name)
classes=home/'qa-classes'
cp=build.server_classpath(SERVER,'21.1.250')+[artifact,FRAMEWORK]
build.compile_java(JDK/'bin/javac.exe',[ROOT/'tests/recovery/PlayfeelServerQA.java',ROOT/'tests/recovery/HardwareWmiTimeoutQAMixin.java'],classes,os.pathsep.join(map(str,cp)),home/'compile.args')
with zipfile.ZipFile(mods/'outbreak-repro-QA-ONLY.jar','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="outbreak_repro_qa"\nversion="0.0.1"\ndisplayName="Outbreak isolated reproduction QA ONLY"\n[[mixins]]\nconfig="outbreak-repro.mixins.json"\n')
    z.writestr('outbreak-repro.mixins.json',json.dumps({'required':True,'minVersion':'0.8','package':'net.muxigame.terminal.qa.mixin','compatibilityLevel':'JAVA_21','mixins':['HardwareWmiTimeoutQAMixin'],'injectors':{'defaultRequire':1}}))
    for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
if not (home/'libraries').exists():subprocess.run(['cmd','/c','mklink','/J',str(home/'libraries'),str(SERVER/'libraries')],check=True,capture_output=True)
(home/'eula.txt').write_text('eula=true\n')
(home/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25817\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=qa-world\ngamemode=survival\ndifficulty=normal\nview-distance=3\nsimulation-distance=3\nmax-tick-time=120000\nspawn-protection=0\nallow-flight=true\nlevel-type=minecraft:flat\ngenerate-structures=false\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}\n')
(home/'config').mkdir(exist_ok=True)
(home/'config/muxi-game-core.json').write_text(json.dumps({'schema':1,'features':{'identity':{'enabled':False},'login':{'enabled':False}}}))
for d in ('tacz',):
    if (SERVER/d).is_dir() and not (home/d).exists():shutil.copytree(SERVER/d,home/d)
args=[str(JDK/'bin/java.exe'),'-Xms256M','-Xmx1800M','-XX:ActiveProcessorCount=3','-Dmuxi.outbreak.recovery.qa=true','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','@libraries/net/neoforged/neoforge/21.1.250/win_args.txt','nogui']
with (home/'boot.log').open('w',encoding='utf-8') as out:
    p=subprocess.Popen(args,cwd=home,stdin=subprocess.PIPE,stdout=out,stderr=subprocess.STDOUT,text=True)
    (home/'run.json').write_text(json.dumps({'pid':p.pid,'port':25817,'artifact':str(artifact),'networkClients':False,'qaOnly':True},indent=2))
    print('QA_REPRO',p.pid,flush=True)
    result=p.wait();print('QA_EXIT',result,flush=True)
    if result or (home/'failure.txt').exists():raise SystemExit(1)
