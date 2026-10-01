"""Offline build for muxi Outbreak.

Requires Python 3.11+, JDK 21+, and the already-installed neighbouring
Minecraft 1.21.1 / NeoForge 21.1.250 server. The build performs no downloads.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent


def java_tools(home: Path | None) -> tuple[Path, Path]:
    homes: list[Path] = []
    if home:
        homes.append(home)
    elif os.getenv("JAVA_HOME"):
        homes.append(Path(os.environ["JAVA_HOME"]))
    for base in (
        Path("C:/Program Files/Java"),
        Path("C:/Program Files/Eclipse Adoptium"),
        Path("C:/Program Files/Microsoft"),
    ):
        if base.exists():
            homes.extend(sorted(base.iterdir(), reverse=True))
    if shutil.which("javac"):
        homes.append(Path(shutil.which("javac")).resolve().parent.parent)
    suffix = ".exe" if os.name == "nt" else ""
    for candidate in homes:
        javac = candidate / f"bin/javac{suffix}"
        java = candidate / f"bin/java{suffix}"
        if not javac.is_file() or not java.is_file():
            continue
        try:
            output = subprocess.run(
                [str(javac), "-version"], capture_output=True, text=True, timeout=10, check=True
            )
            match = re.search(r"javac (\d+)", output.stdout + output.stderr)
            if match and int(match.group(1)) >= 21:
                return javac, java
        except (OSError, subprocess.SubprocessError):
            pass
    raise ValueError("JDK 21+ not found; use --java-home.")


def server_classpath(server: Path, neo_version: str) -> list[Path]:
    all_jars = sorted((server / "libraries").rglob("*.jar"))
    mapped = [p for p in all_jars if p.name == "server-1.21.1-20240808.144430-srg.jar"]
    neo_dir = server / f"libraries/net/neoforged/neoforge/{neo_version}"
    neo_server = neo_dir / f"neoforge-{neo_version}-server.jar"
    neo_universal = neo_dir / f"neoforge-{neo_version}-universal.jar"
    if len(mapped) != 1 or not neo_server.is_file() or not neo_universal.is_file():
        raise ValueError("Installed Minecraft 1.21.1 / NeoForge 21.1.250 server libraries are required.")
    result = [neo_server, mapped[0]]
    result.extend(p for p in all_jars if p not in result and "/net/minecraft/" not in p.as_posix())
    result.append(neo_universal)
    return result


def compile_java(javac: Path, sources: list[Path], output: Path, classpath: str, argfile: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    args = [
        "--release", "21", "-encoding", "UTF-8", "-proc:none",
        "-classpath", classpath, "-d", str(output), *map(str, sources)
    ]
    argfile.write_text(
        "\n".join('"' + value.replace("\\", "/").replace('"', '\\"') + '"' for value in args),
        encoding="utf-8",
    )
    subprocess.run([str(javac), "@" + str(argfile)], check=True)


def build(server: Path, java_home: Path | None = None) -> Path:
    meta = json.loads((ROOT / "mod.json").read_text(encoding="utf-8"))
    javac, _ = java_tools(java_home)
    jars = server_classpath(server, meta["neoforge"])
    output = ROOT / "build"
    (output / "libs").mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    if not sources:
        raise ValueError("No Java sources found.")
    with tempfile.TemporaryDirectory(dir=output, prefix="compile-") as raw:
        temp = Path(raw)
        classes = temp / "classes"
        compile_java(javac, sources, classes, os.pathsep.join(map(str, jars)), temp / "main.args")
        target = output / "libs" / f"muxi-outbreak-{meta['version']}.jar"
        entries: dict[str, bytes] = {"META-INF/LICENSE": (ROOT / "LICENSE").read_bytes()}
        for base in (classes, ROOT / "src/main/resources"):
            for file in base.rglob("*"):
                if not file.is_file():
                    continue
                data = file.read_bytes()
                if file.suffix == ".toml":
                    data = data.replace(b"$" b"{mod_version}", meta["version"].encode("ascii"))
                entries[file.relative_to(base).as_posix()] = data
        staged = temp / target.name
        with zipfile.ZipFile(staged, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        os.replace(staged, target)
        release = {
            "id": meta["id"],
            "name": meta["name"],
            "version": meta["version"],
            "artifact": target.name,
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "size": target.stat().st_size,
            "minecraft": meta["minecraft"],
            "neoforge": meta["neoforge"],
            "java": 21,
        }
        (output / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(release))
        return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, default=ROOT.parent / "bmc5server")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--test", action="store_true", help="run Python and executable Java regression tests before building")
    args = parser.parse_args()
    try:
        if args.test:
            subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(ROOT / "tests"), "-v"], check=True, cwd=ROOT)
            javac, java = java_tools(args.java_home)
            with tempfile.TemporaryDirectory(prefix="outbreak-tests-") as raw:
                test_dir = Path(raw)
                compile_java(javac, [ROOT / "src/main/java/net/muxigame/outbreak/director/Director.java",
                                    ROOT / "tests/java/DirectorTest.java"], test_dir, str(test_dir), test_dir / "test.args")
                subprocess.run([str(java), "-ea", "-cp", str(test_dir), "DirectorTest"], check=True)
        build(args.server.resolve(), args.java_home)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
