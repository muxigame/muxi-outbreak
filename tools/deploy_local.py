"""Install the verified artifact locally; never starts/stops servers or publishes releases."""
from __future__ import annotations
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT.parent


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    release=json.loads((ROOT/'build/release.json').read_text(encoding='utf-8'))
    artifact=ROOT/'build/libs'/release['artifact']
    if release['version']!='0.3.0' or artifact.name!='muxi-outbreak-0.3.0.jar':
        raise ValueError('review deployment targets before using this script for a new version')
    expected=release['sha256']
    if sha(artifact)!=expected:raise ValueError('artifact checksum differs from release metadata')
    evidence={}
    for name in ('equipment-engine-report.json','full-server-report.json','graphical-client-report.json'):
        report=json.loads((ROOT/'build'/name).read_text(encoding='utf-8'))
        if not report.get('passed') or report.get('jarSha256')!=expected:
            raise ValueError('final artifact does not match successful evidence: '+name)
        evidence[name]={'passed':True,'jarSha256':report['jarSha256']}
    dependency=json.loads((ROOT/'build/equipment-research/dependency-lock.json').read_text(encoding='utf-8'))
    addon=ROOT/'build/equipment-research'/dependency['file']['filename']
    if sha(addon)!=dependency['sha256']:raise ValueError('LR dependency checksum mismatch')
    targets=[
        BASE/'bmc5server/mods',
        BASE/'bmc5client-repo/game/mods',
        BASE/'better-mc-remake/pack/source/Better MC Remake [FORGE]/mods',
    ]
    inventory={}
    for target in targets:
        if not target.is_dir():raise ValueError('missing designated target: '+str(target))
        old=list(target.glob('muxi-outbreak-*.jar'))
        for file in old:
            if file.name not in ('muxi-outbreak-0.1.0.jar','muxi-outbreak-0.2.0.jar',artifact.name):
                raise ValueError('unexpected concurrent/newer Outbreak version: '+str(file))
            if file.name==artifact.name and sha(file)!=expected:
                raise ValueError('different 0.3.0 exists; refusing to overwrite concurrent work: '+str(file))
        if len(list(target.glob('tacz-neoforge-*.jar')))!=1:
            raise ValueError('exactly one existing TaCZ runtime required at '+str(target))
        for existing in target.glob('LesRaisins-Tactical-Equipements-*.jar'):
            if existing.name!=addon.name or sha(existing)!=dependency['sha256']:
                raise ValueError('different LR version already installed; refusing unreviewed dependency replacement')
        inventory[str(target)]=[(str(p),sha(p)) for p in old]
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup=ROOT/'build/deployment-backups'/stamp
    result={'artifact':artifact.name,'size':artifact.stat().st_size,'sha256':expected,
            'evidence':evidence,'targets':[], 'serverLifecycleCommandsExecuted':False,
            'remotePublishingPerformed':False,'activation':'next normal server/client restart',
            'dependency':{'file':addon.name,'sha256':dependency['sha256'],'version':dependency['version']},
            'dependencyCaution':'Unofficial early NeoForge port; important world backup is required before activation. This operation backs up JARs only, not a consistent live-world snapshot.',
            'excludedTarget':'bmc5client-repo/pack is the text-content source; no binary JAR was added there.'}
    for index,target in enumerate(targets):
        entry={'directory':str(target),'backups':[]}
        stage=target/(artifact.name+'.outbreak-stage')
        moved=[]
        addon_installed_here=False
        try:
            shutil.copy2(artifact,stage)
            if sha(stage)!=expected:raise ValueError('staging checksum mismatch')
            addon_target=target/addon.name
            if not addon_target.exists():
                addon_stage=target/(addon.name+'.outbreak-stage')
                shutil.copy2(addon,addon_stage)
                if sha(addon_stage)!=dependency['sha256']:raise ValueError('LR staging checksum mismatch')
                os.replace(addon_stage,addon_target);addon_installed_here=True
            current=[(str(p),sha(p)) for p in target.glob('muxi-outbreak-*.jar')]
            if sorted(current)!=sorted(inventory[str(target)]):
                raise ValueError('Outbreak files changed during deployment; refusing race')
            for oldname,oldsha in inventory[str(target)]:
                old=Path(oldname)
                if old.name==artifact.name:continue
                dest=backup/str(index)/old.name
                dest.parent.mkdir(parents=True,exist_ok=True)
                # Same-volume rename cannot leave two mod versions in the scanned directory.
                os.replace(old,dest)
                moved.append((old,dest));entry['backups'].append({'path':str(dest),'sha256':oldsha})
            os.replace(stage,target/artifact.name)
            actual=sha(target/artifact.name)
            if actual!=expected:raise ValueError('installed checksum mismatch')
            entry.update(status='installed',artifact=str(target/artifact.name),sha256=actual,
                         dependency=str(addon_target),dependencySha256=sha(addon_target))
        except Exception as error:
            if stage.exists():stage.unlink()
            if addon_installed_here and addon_target.exists():addon_target.unlink()
            for old,dest in reversed(moved):
                if dest.exists() and not old.exists():os.replace(dest,old)
            entry.update(status='failed',error=str(error))
            result['targets'].append(entry)
            (ROOT/'build/deployment-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            raise
        result['targets'].append(entry)
        print('INSTALLED',target/artifact.name,actual,flush=True)
    result['passed']=all(row['status']=='installed' for row in result['targets'])
    (ROOT/'build/deployment-report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('LOCAL_DEPLOYMENT_COMPLETE; production process was not restarted',flush=True)


if __name__=='__main__':main()
