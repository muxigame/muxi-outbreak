"""Build a separate TEST-ONLY mod, excluded from the shipped Outbreak JAR."""
from pathlib import Path
import os
import sys
import zipfile
import tempfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import build


def build_harness(home: Path):
    if home.resolve()!=ROOT/'build/qa-server':raise ValueError('test-only destination required')
    import json
    artifact=ROOT/'build/libs'/json.loads((ROOT/'build/release.json').read_text(encoding='utf-8'))['artifact']
    javac,_=build.java_tools(None)
    cp=build.server_classpath(ROOT.parent/'bmc5server','21.1.250')+[artifact]
    with tempfile.TemporaryDirectory(dir=ROOT/'build',prefix='engine-harness-') as raw:
        temp=Path(raw);classes=temp/'classes'
        build.compile_java(javac,[ROOT/'tests/integration/EquipmentEngineHarness.java'],classes,os.pathsep.join(map(str,cp)),temp/'harness.args')
        target=home/'mods/muxi-outbreak-QA-ONLY.jar'
        with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as jar:
            jar.writestr('META-INF/neoforge.mods.toml','''modLoader="javafml"
loaderVersion="[4,)"
license="MIT"
[[mods]]
modId="muxi_outbreak_qa"
version="0.0.1"
displayName="Outbreak QA fixture - DO NOT DEPLOY"
[[dependencies.muxi_outbreak_qa]]
modId="muxi_outbreak"
type="required"
versionRange="[0.3.0,)"
ordering="AFTER"
side="SERVER"
''')
            for path in classes.rglob('*.class'):jar.write(path,path.relative_to(classes).as_posix())
        return target


if __name__=='__main__':print(build_harness(ROOT/'build/qa-server'))
