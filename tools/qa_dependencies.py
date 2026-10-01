"""Copy pinned runtime dependencies into an isolated QA directory, never production."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT=Path(__file__).resolve().parents[1]


def install(home: Path):
    if ROOT/'build' not in home.resolve().parents:
        raise ValueError('QA dependencies must stay below the project build directory')
    (home/'mods').mkdir(exist_ok=True)
    server=ROOT.parent/'bmc5server'
    source=next((server/'mods').glob('tacz-neoforge-*.jar'))
    lock=json.loads((ROOT/'build/equipment-research/dependency-lock.json').read_text(encoding='utf-8'))
    addon=ROOT/'build/equipment-research'/lock['file']['filename']
    if hashlib.sha256(addon.read_bytes()).hexdigest()!=lock['sha256']:
        raise ValueError('unverified LR dependency')
    for pattern,file in [('tacz-neoforge-*.jar',source),('LesRaisins-Tactical-Equipements-*.jar',addon)]:
        for old in (home/'mods').glob(pattern):old.unlink()
        shutil.copy2(file,home/'mods'/file.name)
    if (server/'tacz').is_dir():shutil.copytree(server/'tacz',home/'tacz',dirs_exist_ok=True)
    (home/'config').mkdir(exist_ok=True)
    for path in (server/'config').glob('tacz*.toml'):shutil.copy2(path,home/'config'/path.name)
    return [source.name,addon.name]
