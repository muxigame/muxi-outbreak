"""Offline regression for the production Outbreak entity admission guard."""
from pathlib import Path
import argparse
import subprocess
import tempfile

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java-home",required=True,type=Path)
args=parser.parse_args()
with tempfile.TemporaryDirectory(prefix="outbreak-admission-") as raw:
    output=Path(raw)
    sources=[root/"src/main/java/net/muxigame/outbreak/equipment/OutbreakEntityAdmission.java",root/"tests/java/EntityAdmissionTest.java"]
    subprocess.run([str(args.java_home/"bin/javac.exe"),"--release","21","-encoding","UTF-8","-d",str(output),*map(str,sources)],check=True)
    subprocess.run([str(args.java_home/"bin/java.exe"),"-ea","-cp",str(output),"EntityAdmissionTest"],check=True)
source=(root/"src/main/java/net/muxigame/outbreak/OutbreakGame.java").read_text(encoding="utf-8")
join=source.split("private void entityJoin(EntityJoinLevelEvent event) {",1)[1].split("private void eliminate(",1)[0]
assert "OutbreakEntityAdmission.rejectForeignSession(entity.getPersistentData()::getString," in join
assert "CampaignThrowables.TAG" not in join
assert "event.setCanceled(true);entity.discard();return;" in join
assert 'entity.level().dimension().equals(s.map.dimension())' in join
assert 's.equipmentEntities.add(entity.getUUID())' in join
assert 's.id.toString().equals(tag)&&s.infected.contains(entity.getUUID())' in join
assert 'event.getEntity() instanceof Mob' in join
print("ENTITY_ADMISSION_WIRING_PASS preserved private-session cancellation, owner/dimension tagging and infected guards")
