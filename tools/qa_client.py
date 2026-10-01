"""Launch a clean graphical NeoForge client using cached local launcher libraries.

Only Outbreak is installed here. This is explicitly not a full Better MC client
load test. Login fields are replaced with a dedicated offline QA profile.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT=Path(__file__).resolve().parents[1]


def main():
    home=ROOT/'build/qa-client'
    home.mkdir(parents=True,exist_ok=True)
    (home/'mods').mkdir(exist_ok=True)
    (home/'tmp').mkdir(exist_ok=True)
    template=ROOT.parent/'_crawl_test'
    java=Path((template/'java.txt').read_text(encoding='utf-8-sig').strip())
    args=[json.loads(line) for line in (template/'args.txt').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    username='OutbreakVisual'
    ident=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+username).encode()).digest(),version=3))
    def option(name,value):
        if name in args:args[args.index(name)+1]=str(value)
        else:args.extend([name,str(value)])
    for name in ('--quickPlaySingleplayer','--quickPlayRealms','--server','--port'):
        if name in args:
            i=args.index(name);del args[i:i+2]
    if '--fullscreen' in args:args.remove('--fullscreen')
    for name,value in {'--username':username,'--uuid':ident,'--accessToken':'0','--userType':'legacy',
                       '--gameDir':home,'--width':1024,'--height':640,
                       '--quickPlayMultiplayer':'127.0.0.1:25683'}.items():option(name,value)
    for name,value in {'--clientId':'','--xuid':'0','--userProperties':'{}','--profileProperties':'{}'}.items():
        if name in args:option(name,value)
    for i,value in enumerate(args):
        if value.startswith('-Xmx'):args[i]='-Xmx1536M'
        elif value.startswith('-Xms'):args[i]='-Xms256M'
        elif value.startswith('-XX:ErrorFile='):args[i]='-XX:ErrorFile='+str(home/'hs_err_pid%p.log')
        elif value.startswith('-XX:HeapDumpPath='):args[i]='-XX:HeapDumpPath='+str(home/'heapdump.hprof')
        elif value.startswith('-Djava.io.tmpdir='):args[i]='-Djava.io.tmpdir='+str(home/'tmp')
        elif value.startswith('-Djdk.net.unixdomain.tmpdir='):args[i]='-Djdk.net.unixdomain.tmpdir='+str(home/'tmp')
    args[:0]=['-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']
    # Abort rather than accidentally carry an unknown authentication option out of the template.
    for value in args:
        if value.startswith(('--','-D')) and any(s in value.lower() for s in ('password','secret','token')) and value!='--accessToken':
            raise ValueError('unrecognized credential-bearing launcher option')
    jar=ROOT/'build/libs/muxi-outbreak-0.2.0.jar'
    shutil.copy2(jar,home/'mods'/jar.name)
    (home/'options.txt').write_text('\n'.join([
        'version:3955','lang:zh_cn','renderDistance:6','simulationDistance:2','maxFps:30',
        'graphicsMode:0','mipmapLevels:0','enableVsync:false','fullscreen:false','pauseOnLostFocus:false',
        'resourcePacks:[]','incompatibleResourcePacks:[]','soundCategory_master:0.0','tutorialStep:none',
        'narrator:0','skipMultiplayerWarning:true','joinedFirstServer:true','onboardAccessibility:false',
    ])+'\n',encoding='utf-8')
    argfile=home/'client.args'
    argfile.write_text('\n'.join(json.dumps(arg,ensure_ascii=False) for arg in args),encoding='utf-8')
    with (home/'console.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen([str(java),'@'+str(argfile)],cwd=home,stdout=log,stderr=subprocess.STDOUT)
        (home/'run.json').write_text(json.dumps({'pid':process.pid,'java':str(java),'username':username,'uuid':ident,
            'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'fullBetterMCClient':False,
            'gameDirectory':str(home),'connection':'127.0.0.1:25683','heapMB':1536},indent=2),encoding='utf-8')
        print('QA_GRAPHICAL_CLIENT',process.pid,str(home/'console.log'),flush=True)
        code=process.wait();print('QA_GRAPHICAL_CLIENT_EXIT',code,flush=True)
        raise SystemExit(code)


if __name__=='__main__':main()
