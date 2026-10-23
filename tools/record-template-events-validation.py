"""Verify stopped core template fixtures, saved NPC/scoreboard state and ordinary packages."""
from datetime import datetime
from pathlib import Path
import gzip
import hashlib
import importlib.util
import json
import socket
import xml.etree.ElementTree as ET
import zipfile

import yaml

ROOT = Path(__file__).resolve().parent.parent


def evidence(path):
    path = ROOT / path
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def npc_records(path):
    records = yaml.safe_load(path.read_text(encoding="utf-8-sig"))["npc"]
    # Native YAML emits a list when NPC IDs happen to be contiguous from zero.
    return dict(enumerate(records)) if isinstance(records, list) else records


def validate():
    # Reuse the existing read-only standard NBT decoder; importing does not run its provider audit.
    decoder_path = ROOT / "tools/record-guard-restart-validation.py"
    spec = importlib.util.spec_from_file_location("template_events_nbt", decoder_path)
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
    with socket.socket() as connection:
        assert connection.connect_ex(("127.0.0.1", 25644)) != 0, "Fixture must be stopped"

    control_log = ROOT / "artifacts/template-events-native-before-retry.log"
    control = control_log.read_text(encoding="utf-8-sig")
    assert "AssertionError: ordinary_snapshot_contains_applied_event_commands" in control
    assert "BUILD SUCCESSFUL" in control and "[TEMPLATEEVENTAUDIT] COMPLETE" not in control
    control_npcs = npc_records(ROOT / "artifacts/template-events-before-server/config/citizens/saves.yml")
    assert len(control_npcs) == 1 and all("template-events" not in record for record in control_npcs.values())

    targets = []
    for version, ordinary in [("233", "template-events-core-build"), ("248", "template-events-selected-build")]:
        fixture = ROOT / f"artifacts/template-events-fixed-{version}-server"
        native = ROOT / f"artifacts/template-events-native-{version}-build"
        output = ROOT / "artifacts" / ordinary
        reports = sorted((output / "test-results/test").glob("TEST-*.xml"))
        roots = [ET.parse(path).getroot() for path in reports]
        counts = {key: sum(int(root.get(key, 0)) for root in roots) for key in ["tests", "failures", "errors", "skipped"]}
        assert counts == dict(tests=836, failures=0, errors=0, skipped=0)
        focused = next(root for root in roots if root.get("name", "").endswith("TemplateEventPersistenceTest"))
        assert int(focused.get("tests")) == 8

        phases = []
        states = {}
        for phase, checks in [("seed", 42), ("recover", 21)]:
            log = ROOT / f"artifacts/template-events-native-{version}-{phase}.log"
            text = log.read_text(encoding="utf-8-sig")
            assert f"[TEMPLATEEVENTAUDIT] COMPLETE {phase} {checks} checks" in text
            assert "[TEMPLATEEVENTAUDIT] FAILED" not in text and "BUILD SUCCESSFUL" in text
            assert "All dimensions are saved" in text and "Citizens disabled." in text
            assert (fixture / f"{phase}.complete").read_text().strip() == str(checks)
            data = fixture / "evidence" / phase
            npcs = npc_records(data / "saves.yml")
            scores = decoder.nbt(gzip.decompress((data / "scoreboard.dat").read_bytes()))["data"]["PlayerScores"]
            scores = {row["Name"]: row["Score"] for row in scores if row["Objective"] == "template_events"}
            assert len(npcs) == 2 and len(scores) == 12
            assert scores["ordinary_apply"] == 2 and scores["packet_apply"] == 1
            for prefix, amount in [("ordinary", 10), ("packet", 1)]:
                expected_name = "ordinary_latest_runtime" if prefix == "ordinary" else "packet_runtime"
                record = next(record for record in npcs.values() if record["name"] == expected_name)
                bindings = record["template-events"]
                assert bindings["version"] == 1
                assert record["traits"]["type"] == "minecraft:pig"
                assert ("packet" in record["traitnames"].split(",")) == (prefix == "packet")
                if phase == "seed":
                    assert len(bindings["templates"]) == 1
                    template = bindings["templates"][0]
                    assert template["key"] == "audit:" + prefix
                    commands = template["commands"]
                    assert set(commands) == {"on_" + name for name in ["spawn", "death", "damage", "left_click", "right_click"]}
                    for event, lines in commands.items():
                        assert lines == [f"scoreboard players add {prefix}_{event[3:]} template_events {amount}"]
                else:
                    assert bindings["templates"] == []
                for event in ["spawn", "death", "damage", "left_click", "right_click"]:
                    before = 13 if prefix == "ordinary" and event == "spawn" else 3 if event == "spawn" else 2
                    assert scores[prefix + "_" + event] == before + (amount if phase == "recover" else 0)
            states[phase] = npcs
            phases.append(dict(phase=phase, checks=checks, log=evidence(log),
                               stoppedState=[evidence(data / name) for name in ["saves.yml", "scoreboard.dat"]]))
        assert {record["uuid"] for record in states["seed"].values()} == {record["uuid"] for record in states["recover"].values()}

        classes = sorted((output / "classes/java/main").rglob("*.class"))
        for path in classes:
            relative = path.relative_to(output / "classes/java/main")
            assert path.read_bytes() == (native / "classes/java/main" / relative).read_bytes(), relative
        packages = []
        for path in sorted((output / "libs").glob("*.jar")):
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                assert not any("/audit/" in name or "TemplateEventPersistenceTest" in name for name in names)
                if path.name.startswith("yuuniverse-interactions"):
                    assert not any(name.startswith("net/citizensnpcs/") for name in names)
                elif not path.name.endswith("-sources.jar"):
                    assert not any(name.startswith("net/yuuniverse/interactions/") for name in names)
                    assert "net/citizensnpcs/api/npc/templates/TemplateEventBindings.class" in names
            packages.append(evidence(path))
        targets.append(dict(neoForge="21.1." + version, tests=counts, suites=len(roots), focusedTests=8,
                            reports=[evidence(path) for path in reports], native=phases,
                            productionClassesMatchNative=len(classes), packages=packages))

    sources = [ROOT / path for path in [
        "neoforge/src/main/java/net/citizensnpcs/Citizens.java",
        "neoforge/src/main/java/net/citizensnpcs/api/npc/AbstractNPC.java",
        "neoforge/src/main/java/net/citizensnpcs/api/npc/templates/CommandEventAction.java",
        "neoforge/src/main/java/net/citizensnpcs/api/npc/templates/CommandListExecutor.java",
        "neoforge/src/main/java/net/citizensnpcs/api/npc/templates/Template.java",
        "neoforge/src/main/java/net/citizensnpcs/api/npc/templates/TemplateEventBindings.java",
        "neoforge/src/test/java/net/citizensnpcs/api/npc/templates/TemplateEventPersistenceTest.java",
        "tools/template-events-runtime-audit.gradle", "tools/prepare-template-events-audit.ps1",
        "tools/record-template-events-validation.py", "tools/record-guard-restart-validation.py"]]
    sources += sorted((ROOT / "tools/template-events-runtime-audit").rglob("*.*"))
    result = dict(recordedAt=datetime.now().astimezone().isoformat(), minecraft="1.21.1", targets=targets,
                  totalNativeChecks=126, negativeControl=evidence(control_log), sources=[evidence(path) for path in sources],
                  limits=["Native synthetic actors/events; ordinary physical client test remains deferred",
                          "Legacy saves have no historical application provenance; explicitly reapply templates to record hooks",
                          "Runtime extension callbacks are not serialized", "No production or provider activation"])
    destination = ROOT / "docs/template-event-persistence-validation-2026-10-24.json"
    destination.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 836 unit tests per target, 126 native checks, stopped YAML/NBT and ordinary package verification")


if __name__ == "__main__":
    validate()
