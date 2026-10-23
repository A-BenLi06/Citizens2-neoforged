"""Build a local owner-class repair while proving all other provider entries unchanged."""
from datetime import datetime
from pathlib import Path
import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
OWNER = "eu/avalanche7/paradigm/modules/holograms/HologramStore"


def evidence(path):
    path = Path(path).resolve()
    return dict(path=path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path),
                bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def execute(arguments, log):
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True)
    log.write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError("Owner candidate command failed; preserve " + str(log))
    return result.stdout.decode("utf-8").replace("\r\n", "\n")


def methods(text):
    parts = re.split(r"(?m)^(  [^\n]+\([^\n]*\);)\n", text)
    return {parts[i]: re.sub(r"#\d+", "#CP", parts[i+1].rstrip().removesuffix("}").rstrip()) for i in range(1, len(parts), 2)}


def build(args):
    output = Path(args.output).resolve()
    if not output.is_relative_to(ROOT / "artifacts") or output == ROOT / "artifacts":
        raise ValueError("Use a fresh output inside task artifacts")
    output.mkdir(exist_ok=False)
    provider, baseline, fixed = [Path(value).resolve() for value in [args.provider, args.baseline_source, args.fixed_source]]
    dependencies = [Path(value).resolve() for value in args.dependency]
    java = Path(args.java_home).resolve() / "bin"
    classpath = os.pathsep.join(str(path) for path in [provider, *dependencies])
    with zipfile.ZipFile(provider) as original:
        assert len(original.namelist()) == len(set(original.namelist()))
        assert not any(name.upper().endswith((".RSA", ".DSA", ".SF")) for name in original.namelist())
        major = int.from_bytes(original.read(OWNER + ".class")[6:8], "big")
        release = major - 44
        installed_code = execute([java / "javap.exe", "-c", "-p", "-classpath", provider, OWNER.replace("/", ".")], output / "installed-bytecode.txt")
        installed_api = execute([java / "javap.exe", "-p", "-s", "-classpath", provider, OWNER.replace("/", ".")], output / "installed-descriptors.txt")
        for name, source in [("baseline", baseline), ("fixed", fixed)]:
            target = output / (name + "-source") / (OWNER + ".java")
            target.parent.mkdir(parents=True)
            shutil.copyfile(source, target)
            classes = output / (name + "-classes")
            classes.mkdir()
            execute([java / "javac.exe", "--release", release, "-encoding", "UTF-8", "-cp", classpath, "-d", classes, target], output / (name + "-compile.log"))
            code = execute([java / "javap.exe", "-c", "-p", "-classpath", classes, OWNER.replace("/", ".")], output / (name + "-bytecode.txt"))
            api = execute([java / "javap.exe", "-p", "-s", "-classpath", classes, OWNER.replace("/", ".")], output / (name + "-descriptors.txt"))
            assert api == installed_api, "Provider descriptors changed"
            assert [p.relative_to(classes).as_posix() for p in classes.rglob("*.class")] == [OWNER + ".class"]
            if name == "baseline":
                assert code == installed_code, "Baseline source is not the installed owner implementation"
            else:
                before, after = methods(installed_code), methods(code)
                assert before.keys() == after.keys()
                changed = [key for key in before if before[key] != after[key]]
                assert changed == ["  private eu.avalanche7.paradigm.modules.holograms.HologramService$Config load();"], changed
        candidate = output / "Paradigm-neoforge-1.21.1-2.5.0-hologram-private.jar"
        with zipfile.ZipFile(candidate, "x") as repaired:
            for entry in original.infolist():
                payload = (output / "fixed-classes" / (OWNER + ".class")).read_bytes() if entry.filename == OWNER + ".class" else original.read(entry.filename)
                repaired.writestr(copy.copy(entry), payload)
        with zipfile.ZipFile(candidate) as repaired:
            assert original.namelist() == repaired.namelist()
            changes = [name for name in original.namelist() if original.read(name) != repaired.read(name)]
            assert changes == [OWNER + ".class"]
    record = dict(createdAt=datetime.now().astimezone().isoformat(), privateLocalCandidate=True,
                  baseline=evidence(baseline), fixedSource=evidence(fixed), provider=evidence(provider), candidate=evidence(candidate),
                  dependencies=[evidence(path) for path in dependencies], javaRelease=release, changedEntries=changes,
                  changedMethods=changed, unchangedDescriptors=True, baselineAllMethodsMatch=True,
                  buildFiles=[evidence(path) for path in sorted(output.rglob("*")) if path.is_file()])
    (output / "candidate.json").write_text(json.dumps(record, indent=2)+"\n", encoding="utf-8")
    print("PASS private owner candidate: baseline matches installed methods; only load() and one JAR entry change")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ["provider", "baseline-source", "fixed-source", "java-home", "output"]:
        parser.add_argument("--"+key, required=True)
    parser.add_argument("--dependency", action="append", required=True)
    build(parser.parse_args())
