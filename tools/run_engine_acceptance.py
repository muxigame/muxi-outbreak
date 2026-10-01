"""Run the real server-engine suite and bind its result to the exact loaded JAR.

Requires the separate QA-only addon. This never substitutes an engine fixture
for a network client; the graphical client has a separate acceptance record.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import time
from qa_rcon import Rcon, ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full',action='store_true')
    args=parser.parse_args()
    home=ROOT/'build/qa-server';result=ROOT/'build/equipment-engine-report.json'
    deadline=time.monotonic()+360
    while time.monotonic()<deadline:
        try:
            r=Rcon();break
        except (ConnectionError,OSError):time.sleep(1)
    else:raise SystemExit('QA instance never opened its loopback RCON')
    run=json.loads((home/'run.json').read_text(encoding='utf-8'))
    if args.full and not run['fullPack']:
        r.close();raise SystemExit('refusing to label a minimal fixture as full-pack testing')
    old_timestamp=result.stat().st_mtime_ns if result.exists() else 0
    try:
        response=r.command('outbreakqa run')
        if 'integration suite started' not in response:raise RuntimeError(response)
        print('ENGINE_ACCEPTANCE_STARTED fullPack='+str(run['fullPack']),flush=True)
    finally:r.close()
    deadline=time.monotonic()+600
    while time.monotonic()<deadline:
        if result.exists() and result.stat().st_mtime_ns!=old_timestamp:
            try:report=json.loads(result.read_text(encoding='utf-8'))
            except json.JSONDecodeError:time.sleep(.2);continue
            if report.get('running'):time.sleep(.2);continue
            if not report.get('passed'):
                print('ENGINE_ACCEPTANCE_FAILED',report.get('error'),flush=True);raise SystemExit(1)
            artifact=ROOT/'build/libs'/json.loads((ROOT/'build/release.json').read_text(encoding='utf-8'))['artifact']
            loaded=home/'mods'/artifact.name
            digest=hashlib.sha256(loaded.read_bytes()).hexdigest()
            if digest!=run['jarSha256'] or digest!=hashlib.sha256(artifact.read_bytes()).hexdigest():
                raise SystemExit('running fixture/artifact hash mismatch')
            report.update(jarSha256=digest,fullPack=run['fullPack'],copiedServerMods=run['copiedServerMods'],
                          integrationDependencies=run['integrationDependencies'],networkClients=False,
                          fixtureEntitiesTickedByRealServer=True,
                          fixtureChunkTickets='Explicit temporary force-load tickets; all removed during harness cleanup')
            result.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            print('ENGINE_ACCEPTANCE_PASS',len(report['checks']),'assertions',digest,flush=True)
            return
        time.sleep(1)
    raise SystemExit('engine test timed out; do not deploy this artifact')


if __name__=='__main__':main()
