"""Verify native Guard restart, stopped entity NBT, NPC YAML and release provenance."""
from datetime import datetime
from pathlib import Path
import gzip
import hashlib
import io
import json
import struct
import subprocess
import uuid
import zipfile
import zlib
import yaml

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = (ROOT / path).resolve()
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file()]


def nbt(data):
    """Decode the standard named-tag format in the stopped native entity region."""
    stream = io.BytesIO(data)

    def exact(size):
        assert size >= 0
        value = stream.read(size)
        assert len(value) == size
        return value

    def number(fmt):
        return struct.unpack(">" + fmt, exact(struct.calcsize(">" + fmt)))[0]

    def string():
        return exact(number("H")).decode("utf-8")

    def payload(kind):
        if kind in range(1, 7):
            return number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return exact(number("i"))
        if kind == 8:
            return string()
        if kind == 9:
            child, size = number("B"), number("i")
            assert size >= 0 and (child != 0 or size == 0)
            return [payload(child) for _ in range(size)]
        if kind == 10:
            result = {}
            while True:
                child = number("B")
                if child == 0:
                    return result
                name = string()
                assert name not in result
                result[name] = payload(child)
        if kind in [11, 12]:
            size = number("i")
            assert size >= 0
            return [number("i" if kind == 11 else "q") for _ in range(size)]
        raise AssertionError(f"Unknown NBT tag {kind}")

    root = number("B")
    assert root == 10
    string()
    value = payload(root)
    assert not stream.read(1)
    return value


def entity_regions(world):
    entities = []
    for region in sorted((ROOT / world / "entities").glob("*.mca")):
        data = region.read_bytes()
        if not data:
            continue  # Minecraft creates empty region placeholders for chunks with no entities.
        assert len(data) >= 8192
        for index in range(1024):
            location = int.from_bytes(data[index*4:index*4+4], "big")
            if not location:
                continue
            offset, sectors = location >> 8, location & 255
            assert offset >= 2 and sectors > 0
            start = offset*4096
            size = int.from_bytes(data[start:start+4], "big")
            assert 1 < size <= sectors*4096-4 and start+size+4 <= len(data)
            compression = data[start+4]
            payload = data[start+5:start+4+size]
            if compression == 1:
                payload = gzip.decompress(payload)
            elif compression == 2:
                payload = zlib.decompress(payload)
            else:
                assert compression == 3, f"Unsupported region compression {compression}"
            entities.extend(nbt(payload)["Entities"])
    return entities


def native_uuid(value):
    assert len(value) == 4
    return str(uuid.UUID(bytes=b"".join(struct.pack(">I", n & 0xffffffff) for n in value)))


def guard_from_disk(entity):
    # Guard writes empty compounds for empty slots and explicit indices for populated slots.
    slots = [{} for _ in range(6)]
    assert len(entity["Inventory"]) == len(slots)
    for item in entity["Inventory"]:
        if not item:
            continue
        item = dict(item)
        slot = item.pop("Slot")
        assert 0 <= slot < len(slots) and not slots[slot]
        slots[slot] = item
    anchor_keys = ["PatrolPosX", "PatrolPosY", "PatrolPosZ"]
    assert all(k in entity for k in anchor_keys) or not any(k in entity for k in anchor_keys)
    return dict(uuid=native_uuid(entity["UUID"]), owner=native_uuid(entity["Owner"]), name=json.loads(entity["CustomName"]),
                following=bool(entity["Following"]), patrolling=bool(entity["Patrolling"]), inventory=slots,
                anchor=[entity[k] for k in anchor_keys] if anchor_keys[0] in entity else None)


def validate_npc_file(path, state):
    content = yaml.safe_load((ROOT / path).read_text(encoding="utf-8-sig"))
    records = content["npc"]
    assert len(records) == len(state["npcs"]) == 5
    for saved in state["npcs"]:
        record = records[saved["id"]]
        assert record["uuid"] == saved["uuid"]
        assert record["metadata"]["minecraft-ai"] == saved["nativeAI"]
        assert record["traits"]["type"] == saved["type"]
        assert ("packet" in record["traitnames"].split(",")) == saved["packet"]
        if "profession" in saved:
            assert record["traits"]["profession"] == saved["profession"]
    return evidence(path)


def validate():
    stopped = read("artifacts/guard-restart-stopped.json")
    assert stopped["exclusiveStoppedWorldOwnership"] and len(stopped["worlds"]) == 5
    for world in stopped["worlds"]:
        assert evidence(world["lock"]["path"]) == world["lock"]
    client_snapshot = "artifacts/logging-reader-pinned-client-before.json"
    for item in read(client_snapshot)["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    expected_provider = read("artifacts/guard-coexistence-validation-2026-10-18.json")["provider"]["sha256"]
    snapshots = []
    for target in ["248", "233"]:
        fixture = Path(f"artifacts/guard-restart-{target}-server")
        state = read(fixture / "guard-restart-state.json")
        recovered = read(fixture / "guard-restart-recovery.json")
        assert state["seedChecks"] == 12 and recovered["recoveryChecks"] == 31
        assert all(recovered[k] == state[k] for k in state)
        assert recovered["restoredFollower"] == state["follower"] and recovered["restoredPatroller"] == state["patroller"]
        assert state["follower"]["following"] and not state["follower"]["patrolling"] and state["follower"]["anchor"] is None
        assert not state["patroller"]["following"] and state["patroller"]["patrolling"] and state["patroller"]["anchor"] == [11,-60,8]
        for role in ["Follower", "Patroller"]:
            original, final = state[role.lower()], recovered["final" + role]
            assert {k:v for k,v in original.items() if k not in ["following", "patrolling", "anchor"]} == {k:v for k,v in final.items() if k not in ["following", "patrolling", "anchor"]}
            assert not final["following"] and not final["patrolling"] and final["anchor"] is None
            assert len(original["inventory"]) == 6 and original["owner"] == state["actor"]
            assert original["inventory"][1]["components"]["minecraft:damage"] == 9
            assert original["inventory"][5] == state["weapon"]
        assert len({n["uuid"] for n in state["npcs"]}) == 5
        assert [n["nativeAI"] for n in state["npcs"]] == [False,True,False,False,True]
        assert [n["packet"] for n in state["npcs"]] == [False,False,True,False,False]
        logs = [f"artifacts/guard-restart-{target}-{'seed' if target=='248' else 'restart-seed'}.log",
                f"artifacts/guard-restart-{target}-{'recover' if target=='248' else 'restart-recover'}.log"]
        for path, checks, phase in zip(logs, [12,31], ["restart-seed","restart-recover"]):
            raw = (ROOT / path).read_text(encoding="utf-8-sig")
            assert f"[GUARDRESTART] COMPLETE {checks} checks phase={phase}" in raw and "BUILD SUCCESSFUL" in raw and "[GUARDAUDIT] FAILED" not in raw
            assert f"NeoForge 21.1.{target}" in raw and "Guard Villagers 2.4.12" in raw
        provider = evidence(fixture / "mods/guardvillagers.jar")
        assert provider["sha256"] == expected_provider
        actual = entity_regions(fixture / "guard-world")
        guards = [guard_from_disk(e) for e in actual if e["id"] == "guardvillagers:guard"]
        assert len(guards) == 2
        for guard in guards:
            expected = next(recovered["final"+role] for role in ["Follower","Patroller"] if recovered["final"+role]["uuid"] == guard["uuid"])
            assert guard == {k:expected[k] for k in guard}
        snapshot = dict(target="21.1."+target, seedChecks=12, recoveryChecks=31, state=state, recovered=recovered,
                        stateEvidence=evidence(fixture / "guard-restart-state.json"), recoveryEvidence=evidence(fixture / "guard-restart-recovery.json"),
                        logs=[evidence(p) for p in logs], provider=provider, nativeGuards=guards,
                        nativeEntityRegions=files(fixture / "guard-world/entities"), nativePlayerFiles=files(fixture / "guard-world/playerdata"),
                        npcFile=validate_npc_file(fixture / "config/citizens/saves.yml",state))
        if target == "233":
            seed_world = Path("artifacts/guard-restart-233-stopped-seed-world")
            seed_guards = [guard_from_disk(e) for e in entity_regions(seed_world) if e["id"] == "guardvillagers:guard"]
            assert len(seed_guards) == 2
            for guard in seed_guards:
                expected = next(state[role] for role in ["follower","patroller"] if state[role]["uuid"] == guard["uuid"])
                assert guard == {k:expected[k] for k in guard}
            snapshot["stoppedSeed"] = dict(guards=seed_guards, files=files(seed_world), npcFile=validate_npc_file("artifacts/guard-restart-233-stopped-seed-npcs.yml",state))
        snapshots.append(snapshot)
    regression = []
    for target in ["248","233"]:
        log = f"artifacts/guard-restart-regression-{target}-coexist.log"
        raw = (ROOT / log).read_text(encoding="utf-8-sig")
        assert "[GUARDAUDIT] COMPLETE 41 checks" in raw and "BUILD SUCCESSFUL" in raw and "[GUARDAUDIT] FAILED" not in raw
        regression.append(dict(target="21.1."+target, checks=41, log=evidence(log)))
    guards = read("artifacts/guard-restart-launch-rejections.json")
    assert guards["unchanged"] and len(guards["checks"]) == 4 and guards["before"] == guards["after"]
    for path, before in zip(guards["protected"],guards["before"]):
        assert evidence(path)["sha256"].upper() == before
    for check in guards["checks"]:
        raw = (ROOT / check["log"]).read_text(encoding="utf-8-sig")
        assert check["exitCode"] != 0 and check["expected"] in raw and "Starting minecraft server" not in raw
    preparations = read("artifacts/guard-restart-prepare-rejections.json")
    assert len(preparations) == 2 and all(p["rejected"] for p in preparations)
    previous = read("docs/craft-binding-validation-2026-10-22.json")
    assert previous["productionSources"] == files("neoforge/src/main")
    subprocess.run(["git","diff","--exit-code","1add232","--","neoforge/src/main","neoforge/src/dialogue","neoforge/src/test","neoforge/build.gradle","tools/audit-tests","tools/audit-tests.gradle"],cwd=ROOT,check=True)
    packages = []
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k:package[k] for k in ["path","bytes","sha256"]}
        if package["path"].endswith("-sources.jar"):
            continue
        target = "248" if "21.1.248-build" in package["path"] else "233"
        source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
        with zipfile.ZipFile(ROOT / package["path"]) as archive:
            names = [n for n in archive.namelist() if n.endswith(".class")]
            for name in names:
                for build in [f"guard-restart-{target}",f"guard-restart-regression-{target}"]:
                    assert archive.read(name) == (ROOT / f"artifacts/{build}-build/classes/java/{source_set}" / name).read_bytes(),name
        packages.append(dict(**evidence(package["path"]),matchingProductionClasses=len(names)))
    for test in previous["ordinary"]:
        assert test["results"] == dict(tests=825,failures=0,errors=0,skipped=0)
        for item in [test["log"],*test["reports"]]:
            assert evidence(item["path"]) == item
    assert previous["interactions"]["results"] == dict(tests=100,failures=0,errors=0,skipped=0)
    assert evidence(previous["interactions"]["log"]["path"]) == previous["interactions"]["log"]
    for path in files("tools/guard-runtime-audit"):
        if not path["path"].endswith(".java"):
            continue
        relative = Path(path["path"]).relative_to("tools/guard-runtime-audit")
        assert evidence(Path("artifacts/guard-restart-references/sources") / relative)["sha256"] == path["sha256"]
    for name in ["guard-runtime-audit.gradle","prepare-guard-audit.ps1"]:
        assert evidence("tools/"+name)["sha256"] == evidence("artifacts/guard-restart-references/"+name)["sha256"]
    audit_binaries=[]
    binary_sets=[]
    for build in ["guard-restart-248","guard-restart-233","guard-restart-regression-248","guard-restart-regression-233"]:
        directory=ROOT / f"artifacts/{build}-build/classes/java/main/net/citizensnpcs/audit"
        values={p.name:p.read_bytes() for p in directory.glob("Guard*.class")}
        assert values
        binary_sets.append(values)
        audit_binaries.extend(evidence(directory / name) for name in sorted(values))
    assert all(values == binary_sets[0] for values in binary_sets)
    result = dict(createdAt=datetime.now().astimezone().isoformat(),timezone="UTC+08:00",baseRevision="1add232",cases=snapshots,regression=regression,
                  nativeChecks=168,guards=guards,guardEvidence=evidence("artifacts/guard-restart-launch-rejections.json"),
                  guardLogs=[evidence(c["log"]) for c in guards["checks"]],
                  prepareRejections=preparations,prepareRejectionEvidence=evidence("artifacts/guard-restart-prepare-rejections.json"),
                  stopped=stopped,stoppedEvidence=evidence("artifacts/guard-restart-stopped.json"),
                  stoppedVerifier=evidence("artifacts/confirm-guard-restart-stopped.ps1"),
                  unchangedPinnedClient=evidence(client_snapshot),
                  sources=files("tools/guard-runtime-audit")+[evidence(p) for p in ["tools/guard-runtime-audit.gradle","tools/prepare-guard-audit.ps1","tools/record-guard-restart-validation.py"]],
                  auditBinaries=audit_binaries,
                  packages=packages,reusedOrdinaryTestsPerTarget=825,reusedInteractionsTests=100,reusedEvidence=evidence("docs/craft-binding-validation-2026-10-22.json"),
                  limits=["Clean native world/player and Citizens store restart on both targets", "Native synthetic player reconnect/payloads, not physical client input/rendering", "Combined pack, arbitrary AI overrides, unflushed kill and integrated migration/release gates remain", "No production or deferred client changes"])
    (ROOT / "docs/guard-restart-validation-2026-10-23.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print("PASS 86 native seed/restart and 82 coexistence checks, stopped native guard inventories/NPC identities and unchanged production packages")


if __name__ == "__main__":
    validate()
