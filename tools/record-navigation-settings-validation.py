"""Verify stopped navigation settings seed/restart fixtures and ordinary release provenance."""
from datetime import datetime
from pathlib import Path
import hashlib
import importlib.util
import json
import math
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
    records = yaml.safe_load((ROOT / path).read_text(encoding="utf-8-sig"))["npc"]
    return list(records.values()) if isinstance(records, dict) else records


def validate():
    with socket.socket() as connection:
        assert connection.connect_ex(("127.0.0.1", 25646)) != 0, "Fixtures must be stopped"
    spec = importlib.util.spec_from_file_location("navigation_settings_nbt", ROOT / "tools/record-guard-restart-validation.py")
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)
    control_path = "artifacts/navigation-settings-native-before.log"
    control = (ROOT / control_path).read_text(encoding="utf-8-sig")
    assert "AssertionError: ordinary_snapshot_contains_attack_settings" in control
    assert "BUILD SUCCESSFUL" in control and "[NAVSETTINGSAUDIT] COMPLETE" not in control
    before_paths = sorted((ROOT / "artifacts/navigation-settings-before-test-results").glob("TEST-*.xml"))
    before = [ET.parse(path).getroot() for path in before_paths]
    assert sum(int(root.get("tests", 0)) for root in before) == 4
    assert sum(int(root.get("failures", 0)) for root in before) == 3

    targets = []
    for version, output, unitlog in [("233", "unit", "final"), ("248", "selected", "selected-final")]:
        ordinary = ROOT / f"artifacts/navigation-settings-{output}-build"
        native = ROOT / f"artifacts/navigation-settings-native-final-{version}-build"
        fixture = Path(f"artifacts/navigation-settings-final-{version}-server")
        reports = sorted((ordinary / "test-results/test").glob("TEST-*.xml"))
        roots = [ET.parse(path).getroot() for path in reports]
        counts = {key: sum(int(root.get(key, 0)) for root in roots) for key in ["tests", "failures", "errors", "skipped"]}
        assert len(roots) == 118 and counts == dict(tests=847, failures=0, errors=0, skipped=0)
        focused = next(root for root in roots if root.get("name", "").endswith("NavigationSettingsPersistenceTest"))
        assert int(focused.get("tests")) == 5
        buildlog = ROOT / f"artifacts/navigation-settings-unit-{unitlog}.log"
        assert "BUILD SUCCESSFUL" in buildlog.read_text(encoding="utf-8-sig")
        config = yaml.safe_load((ROOT / fixture / "config/citizens/config.yml").read_text(encoding="utf-8-sig"))
        path_config = config["npc"]["pathfinding"]
        phases = []
        identities = []
        for phase, checks in [("seed", 84), ("recover", 38)]:
            log = ROOT / f"artifacts/navigation-settings-native-final-{version}-{phase}.log"
            text = log.read_text(encoding="utf-8-sig")
            assert f"[NAVSETTINGSAUDIT] COMPLETE {phase} {checks} checks" in text
            assert "[NAVSETTINGSAUDIT] FAILED" not in text and "BUILD SUCCESSFUL" in text
            assert "All dimensions are saved" in text and "Citizens disabled." in text
            done_path = fixture / (phase + ".complete.json")
            done = json.loads((ROOT / done_path).read_text(encoding="utf-8"))
            assert done["phase"] == phase and done["checks"] == len(done["passed"]) == len(set(done["passed"])) == checks
            saves = fixture / ("evidence/seed/saves.yml" if phase == "seed" else "config/citizens/saves.yml")
            records = npc_records(saves)
            assert len(records) == 2
            identities.append({record["uuid"] for record in records})
            states = []
            for record in records:
                live = next(state for state in done["npcs"] if state["snapshot"]["uuid"] == record["uuid"])
                assert record == live["snapshot"], (version, phase, record["name"])
                packet = record["name"] == "packetSettings"
                assert record["name"] in ["ordinarySettings", "packetSettings"]
                assert ("packet" in record["traitnames"].split(",")) == packet
                assert record["traits"]["type"] == "minecraft:pig"
                # PersistenceLoader may omit shouldSpawn=true after a load; the Spawned trait constructor owns that default.
                assert "spawned" in record["traitnames"].split(",") and record["traits"].get("spawned", True) is True
                assert record["metadata"]["disable-default-stuck-action"] is True
                assert record["metadata"]["pathfinder-open-doors"] is True
                nav = record["navigator"]
                assert nav["pathfindingrange"] == 41 and nav["stationaryticks"] == 80 and nav["speedmodifier"] == 1.25
                assert nav["distancemargin"] == 0.3 and nav["destinationteleportmargin"] == 0 and nav["falldistance"] == 4
                assert nav["avoidwater"] is True and nav["usedefaultstuckaction"] is False and nav["pathfindertype"] == "CITIZENS"
                if phase == "seed":
                    assert nav["attackrange"] == live["attackRange"] == 8.75
                    assert nav["attackdelayticks"] == live["attackDelay"] == -2
                    assert nav["pathdistancemargin"] == live["pathDistanceMargin"] == 0.375
                else:
                    assert not set(nav) & {"attackrange", "attackdelayticks", "pathdistancemargin"}
                    assert live["attackRange"] == path_config["attack-range"]
                    assert live["pathDistanceMargin"] == path_config["default-path-distance-margin"]
                    assert live["attackDelay"] == 20  # Public NavigatorParameters default, pinned in source evidence.
                location = record["traits"]["location"]
                assert location["world"] == "minecraft:overworld"
                assert all(math.isfinite(location[key]) for key in ["x", "y", "z", "yaw", "pitch", "bodyYaw"])
                destination = [10 if phase == "seed" else 4, -60, 7 if packet else 4]
                distance = math.dist([location[key] for key in ["x", "y", "z"]], destination)
                assert distance <= 1
                states.append(dict(uuid=record["uuid"], name=record["name"], location=location, distanceToGoal=distance))
            phases.append(dict(phase=phase, checks=checks, log=evidence(log), complete=evidence(done_path),
                               saves=evidence(saves), finalStates=states))
        assert identities[0] == identities[1]
        expected = json.loads((ROOT / fixture / "expected.json").read_text())
        seed = json.loads((ROOT / fixture / "seed.complete.json").read_text())
        assert expected == seed
        entities = decoder.entity_regions(fixture / "navigation-settings-world")
        assert not any(decoder.native_uuid(entity["UUID"]) in identities[0] for entity in entities if "UUID" in entity)
        classes = sorted((ordinary / "classes/java/main").rglob("*.class"))
        assert classes
        for path in classes:
            relative = path.relative_to(ordinary / "classes/java/main")
            assert path.read_bytes() == (native / "classes/java/main" / relative).read_bytes(), relative
        packages = []
        for path in sorted((ordinary / "libs").glob("*.jar")):
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                assert not any("/audit/" in name or "NavigationSettingsPersistenceTest" in name for name in names)
                if path.name.startswith("yuuniverse-interactions"):
                    assert not any(name.startswith("net/citizensnpcs/") for name in names)
                elif not path.name.endswith("-sources.jar"):
                    assert not any(name.startswith("net/yuuniverse/interactions/") for name in names)
                    name = "net/citizensnpcs/npc/ai/CitizensNavigator.class"
                    assert archive.read(name) == (ordinary / "classes/java/main" / name).read_bytes()
            packages.append(evidence(path))
        assert len(packages) == 3
        targets.append(dict(neoForge="21.1." + version, tests=counts, suites=len(roots),
                            unitLog=evidence(buildlog), reports=[evidence(path) for path in reports], native=phases,
                            config=evidence(fixture / "config/citizens/config.yml"), expected=evidence(fixture / "expected.json"),
                            productionClassesMatchNative=len(classes), packages=packages))
    sources = ["neoforge/src/main/java/net/citizensnpcs/npc/ai/CitizensNavigator.java",
               "neoforge/src/main/java/net/citizensnpcs/api/ai/NavigatorParameters.java",
               "neoforge/src/main/java/net/citizensnpcs/api/trait/trait/Spawned.java",
               "neoforge/src/main/java/net/citizensnpcs/api/persistence/PersistenceLoader.java",
               "neoforge/src/test/java/net/citizensnpcs/npc/ai/NavigationSettingsPersistenceTest.java",
               "tools/navigation-settings-runtime-audit/net/citizensnpcs/audit/NavigationSettingsRuntimeAudit.java",
               "tools/prepare-navigation-settings-audit.ps1", "tools/navigation-settings-runtime-audit.gradle",
               "tools/navigation-command-runtime-audit.gradle",
               "tools/record-navigation-settings-validation.py", "tools/record-guard-restart-validation.py"]
    regression_paths = sorted((ROOT / "artifacts/navigation-settings-default-restore-before-test-results").glob("TEST-*.xml"))
    regression = [ET.parse(path).getroot() for path in regression_paths]
    assert sum(int(root.get("tests", 0)) for root in regression) == 1
    assert sum(int(root.get("failures", 0)) for root in regression) == 1
    superseded_path = "artifacts/navigation-settings-native-233-seed.log"
    superseded = (ROOT / superseded_path).read_text(encoding="utf-8-sig")
    assert "[NAVSETTINGSAUDIT] COMPLETE seed 62 checks" in superseded and "BUILD SUCCESSFUL" in superseded
    protected_path = "artifacts/navigation-settings-guard-fixed-before.json"
    protected = json.loads((ROOT / protected_path).read_text())
    for path, digest in protected.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest, path
    guard_path = "artifacts/navigation-settings-replay-guard.log"
    guard = (ROOT / guard_path).read_text(encoding="utf-8-sig")
    assert "Execution failed for task ':runServer'" in guard
    assert "Invalid or attempted phase" in guard and "[NAVSETTINGSAUDIT]" not in guard
    exit_path = "artifacts/navigation-settings-guard-exit.json"
    assert json.loads((ROOT / exit_path).read_text(encoding="utf-8-sig"))["exitCode"] != 0
    command_before = "artifacts/navigation-command-guard-fixed-before.json"
    command_protected = json.loads((ROOT / command_before).read_text())
    for path, digest in command_protected.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest, path
    command_log = "artifacts/navigation-command-guard-fixed.log"
    command_text = (ROOT / command_log).read_text(encoding="utf-8-sig")
    assert "Execution failed for task ':runServer'" in command_text
    assert "Prepare a fresh isolated fixture" in command_text and "[NAVCOMMANDAUDIT]" not in command_text
    command_exit = "artifacts/navigation-command-guard-fixed-exit.json"
    assert json.loads((ROOT / command_exit).read_text(encoding="utf-8-sig"))["exitCode"] != 0
    result = dict(recordedAt=datetime.now().astimezone().isoformat(), totalNativeChecks=244, targets=targets,
                  negativeControl=evidence(control_path), originalCommit="fbc5862",
                  originalUnitResults=[evidence(path) for path in before_paths], sources=[evidence(path) for path in sources],
                  defaultRestoreFailure=[evidence(path) for path in regression_paths],
                  supersededSeed=evidence(superseded_path),
                  retainedRecorderFailure=evidence("artifacts/navigation-settings-independent-initial.log"),
                  replayGuard=dict(protectedFiles=len(protected), before=evidence(protected_path),
                                   log=evidence(guard_path), process=evidence(exit_path)),
                  commandReplayGuard=dict(protectedFiles=len(command_protected), before=evidence(command_before),
                                          log=evidence(command_log), process=evidence(command_exit)),
                  rejectedStartup=dict(log=evidence("artifacts/navigation-settings-replay-guard-initial.log"),
                                       serverLog=evidence("artifacts/navigation-settings-guard-initial-server.log"),
                                       changedFiles=evidence("artifacts/navigation-settings-guard-initial-changes.json"),
                                       before=evidence("artifacts/navigation-settings-guard-before.json")),
                  limits=["Native synthetic fixture; physical ordinary-client acceptance remains deferred",
                          "Existing callback/strategy runtime objects are not serialized",
                          "The initial cached-preparation guard test started a baseline JVM; audit admission rejected before actions. NPC snapshot semantics remain exact; world/log files changed",
                          "No production or provider activation or release publication"])
    (ROOT / "docs/navigation-settings-validation-2026-10-24.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 847 tests per target, 244 native seed/restart checks, exact saved settings and ordinary packages")


if __name__ == "__main__":
    validate()
