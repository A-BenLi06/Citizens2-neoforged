"""Independently verify stopped movement fixtures, unit results and ordinary packages."""
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


def successful_log(path, marker=None, count=None):
    log = (ROOT / path).read_text(encoding="utf-8-sig")
    assert "BUILD SUCCESSFUL" in log and "BUILD FAILED" not in log, path
    if marker:
        assert f"[{marker}] COMPLETE {count} checks" in log, path
        assert f"[{marker}] FAILED" not in log, path
        assert "All dimensions are saved" in log and "Citizens disabled." in log, path
    return evidence(path)


def classes_match(ordinary, native):
    classes = sorted((ROOT / ordinary / "classes/java/main").rglob("*.class"))
    assert classes
    for path in classes:
        relative = path.relative_to(ROOT / ordinary / "classes/java/main")
        assert path.read_bytes() == (ROOT / native / "classes/java/main" / relative).read_bytes(), relative
    return len(classes)


def validate():
    with socket.socket() as connection:
        assert connection.connect_ex(("127.0.0.1", 25645)) != 0, "Fixture must be stopped"
    decoder_path = ROOT / "tools/record-guard-restart-validation.py"
    spec = importlib.util.spec_from_file_location("navigation_nbt", decoder_path)
    decoder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(decoder)

    control = (ROOT / "artifacts/navigation-command-native-before.log").read_text(encoding="utf-8-sig")
    assert "AssertionError: ordinary_rejected_path_edit_preserves_all_state" in control
    assert "BUILD SUCCESSFUL" in control and "[NAVCOMMANDAUDIT] COMPLETE" not in control
    before_reports = list((ROOT / "artifacts/navigation-command-before-test-results").rglob("TEST-*.xml"))
    before_roots = [ET.parse(path).getroot() for path in before_reports]
    assert sum(int(root.get("tests", 0)) for root in before_roots) == 6
    assert sum(int(root.get("failures", 0)) for root in before_roots) == 6

    excluded = []
    for attempt in ["233", "233-retry"]:
        log = ROOT / f"artifacts/navigation-command-native-{attempt}.log"
        text = log.read_text(encoding="utf-8-sig")
        assert "AssertionError: fixture_timeout" in text and "BUILD FAILED" in text
        assert "All dimensions are saved" in text
        excluded.append(evidence(log))
    targets = []
    for version, build, label in [("233", "core", "core"), ("248", "selected", "selected")]:
        ordinary = Path(f"artifacts/navigation-command-{build}-build")
        native = Path(f"artifacts/navigation-command-native-final-{version}-build")
        fixture = Path(f"artifacts/navigation-command-final-{version}-server")
        reports = sorted((ROOT / ordinary / "test-results/test").glob("TEST-*.xml"))
        roots = [ET.parse(path).getroot() for path in reports]
        counts = {key: sum(int(root.get(key, 0)) for root in roots) for key in ["tests", "failures", "errors", "skipped"]}
        assert len(roots) == 117 and counts == dict(tests=842, failures=0, errors=0, skipped=0)
        focused = next(root for root in roots if root.get("name", "").endswith("NavigationCommandValidationTest"))
        assert int(focused.get("tests")) == 6
        result = json.loads((ROOT / fixture / "complete.json").read_text(encoding="utf-8"))
        assert result["checks"] == len(result["passed"]) == len(set(result["passed"])) == 229
        for prefix in ["ordinary", "packet"]:
            for pathfinder in ["citizens", "minecraft"]:
                assert f"{prefix}_{pathfinder}_valid_commands_drive_real_navigation_after_rejections" in result["passed"]
        for check in ["paused_packet_navigation_does_not_simulate", "active_packet_navigation_receives_native_ticks",
                      "world_passenger_of_navigating_packet_root_ticks_exactly_once",
                      "packet_root_never_receives_duplicate_native_ticks", "idle_packet_root_returns_to_decorative_state"]:
            assert check in result["passed"]
        npcs = yaml.safe_load((ROOT / fixture / "config/citizens/saves.yml").read_text(encoding="utf-8-sig"))["npc"]
        if isinstance(npcs, dict):
            npcs = list(npcs.values())
        assert len(npcs) == 2
        final = []
        for record in npcs:
            live = next(state for state in result["npcs"] if state["snapshot"]["uuid"] == record["uuid"])
            assert record == live["snapshot"], record["name"]
            assert record["metadata"] == {"disable-default-stuck-action": True, "pathfinder-open-doors": False}
            assert record["traits"]["type"] == "minecraft:pig" and record["traits"]["spawned"] is True
            packet = record["name"] == "packetNavigation"
            assert record["name"] in ["ordinaryNavigation", "packetNavigation"]
            assert ("packet" in record["traitnames"].split(",")) == packet
            location = record["traits"]["location"]
            assert location["world"] == "minecraft:overworld"
            assert all(math.isfinite(location[key]) for key in ["x", "y", "z", "yaw", "pitch", "bodyYaw"])
            distance = math.dist([location[key] for key in ["x", "y", "z"]], [4, -60, 7 if packet else 4])
            assert distance <= 1, location
            assert all(math.isfinite(value) for value in live["motion"])
            assert live["range"] == 64 and live["speed"] == 1.5 and live["stationary"] == 80
            assert live["pathDistance"] == 0.25 and live["attackRange"] == 3 and live["attackDelay"] == 3
            assert live["falling"] == -1 and live["pathfinder"] == "MINECRAFT"
            final.append(dict(uuid=record["uuid"], name=record["name"], location=location, distanceToGoal=distance))
        entities = decoder.entity_regions(fixture / "navigation-command-world")
        npc_ids = {record["uuid"] for record in npcs}
        assert not any(decoder.native_uuid(entity["UUID"]) in npc_ids for entity in entities if "UUID" in entity)
        packages = []
        for path in sorted((ROOT / ordinary / "libs").glob("*.jar")):
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                assert not any("/audit/" in name or "NavigationCommandValidationTest" in name for name in names)
                if path.name.startswith("yuuniverse-interactions"):
                    assert not any(name.startswith("net/citizensnpcs/") for name in names)
                elif not path.name.endswith("-sources.jar"):
                    assert not any(name.startswith("net/yuuniverse/interactions/") for name in names)
                    for entry in ["commands/NPCCommands", "trait/PacketNPC", "mixin/ServerLevelPassengerMixin", "npc/ai/MCNavigationStrategy"]:
                        name = "net/citizensnpcs/" + entry + ".class"
                        assert archive.read(name) == (ROOT / ordinary / "classes/java/main" / name).read_bytes()
            packages.append(evidence(path))
        assert len(packages) == 3
        targets.append(dict(neoForge="21.1." + version, tests=counts, suites=len(roots),
                            build=successful_log(f"artifacts/navigation-command-accepted-{label}.log"),
                            reports=[evidence(path) for path in reports], nativeChecks=result["checks"],
                            nativeLog=successful_log(f"artifacts/navigation-command-native-final-{version}.log", "NAVCOMMANDAUDIT", 229),
                            completion=evidence(fixture / "complete.json"), saves=evidence(fixture / "config/citizens/saves.yml"),
                            entityRegions=[evidence(path) for path in sorted((ROOT / fixture / "navigation-command-world/entities").glob("*.mca"))],
                            finalStates=final, productionClassesMatchNative=classes_match(ordinary, native), packages=packages))

    regressions = []
    for name, marker, checks in [("interaction-label", "INTERACTIONLABELAUDIT", 393), ("packet-hologram", "PACKETHOLOGRAMAUDIT", 170)]:
        regressions.append(dict(name=name, checks=checks,
                                log=successful_log(f"artifacts/navigation-{name}-regression.log", marker, checks),
                                productionClassesMatchNative=classes_match(Path("artifacts/navigation-command-selected-build"),
                                                                          Path(f"artifacts/navigation-{name}-regression-build"))))
    protected_path = "artifacts/navigation-command-replay-guard-before.json"
    protected = json.loads((ROOT / protected_path).read_text(encoding="utf-8"))
    for path, digest in protected.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest, path
    guard = (ROOT / "artifacts/navigation-command-replay-guard.log").read_text(encoding="utf-8-sig")
    assert "Execution failed for task ':prepareServerRun'" in guard
    assert "Prepare a fresh isolated fixture" in guard and "> Task :runServer" not in guard
    guard_exit = json.loads((ROOT / "artifacts/navigation-command-replay-guard-exit.json").read_text(encoding="utf-8-sig"))
    assert guard_exit["exitCode"] != 0
    source_paths = ["neoforge/src/main/java/net/citizensnpcs/" + path for path in ["commands/NPCCommands.java",
                    "trait/PacketNPC.java", "mixin/ServerLevelPassengerMixin.java", "npc/ai/MCNavigationStrategy.java"]]
    source_paths += ["neoforge/src/test/java/net/citizensnpcs/commands/NavigationCommandValidationTest.java",
                     "tools/prepare-navigation-command-audit.ps1", "tools/navigation-command-runtime-audit.gradle",
                     "tools/navigation-command-runtime-audit/net/citizensnpcs/audit/NavigationCommandRuntimeAudit.java",
                     "tools/record-navigation-command-validation.py", "tools/record-guard-restart-validation.py",
                     "tools/interaction-label-runtime-audit/net/citizensnpcs/audit/InteractionLabelRuntimeAudit.java",
                     "tools/packet-hologram-runtime-audit/net/citizensnpcs/audit/PacketHologramRuntimeAudit.java"]
    result = dict(recordedAt=datetime.now().astimezone().isoformat(), targets=targets, regressions=regressions,
                  totalNativeChecks=1021, negativeControl=evidence("artifacts/navigation-command-native-before.log"),
                  originalUnitFailures=[evidence(path) for path in before_reports], excludedNativeAttempts=excluded,
                  replayGuard=dict(protectedFiles=len(protected), before=evidence(protected_path),
                                   log=evidence("artifacts/navigation-command-replay-guard.log"),
                                   process=evidence("artifacts/navigation-command-replay-guard-exit.json")),
                  sources=[evidence(path) for path in source_paths],
                  limits=["Native synthetic actors; ordinary physical-client test remains deferred",
                          "Packet entity simulation is limited to active unpaused navigation on unmounted virtual roots",
                          "No production restart, provider activation or release publication"])
    destination = ROOT / "docs/navigation-command-validation-2026-10-24.json"
    destination.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 842 tests per target; 458 navigation +563 label/mount checks; stopped YAML/NBT and packages verified")


if __name__ == "__main__":
    validate()
