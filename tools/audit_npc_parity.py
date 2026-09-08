"""Read-only inventory for Citizens/Interactions migration audits.

Requires Python 3.11+ and PyYAML. Output is evidence, not a behavioral parity
certificate. Source presence and command annotations do not prove execution.
No server commands are executed and input directories are never modified.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime

import yaml


def read(path):
    return path.read_text(encoding="utf-8-sig")


def documents(folder):
    for path in sorted(folder.glob("*.yml")):
        yield path, yaml.safe_load(read(path)) or {}


def walk(value, path=()):
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, (*path, str(index)))


def commands(sources):
    result = {}
    for path, text in sources.items():
        # Command annotations have no nested parentheses in aliases/modifiers.
        for match in re.finditer(r"@Command\s*\((.*?)\)\s*(?=@|public|protected)", text, re.S):
            block = match.group(1)
            def strings(field):
                found = re.search(field + r"\s*=\s*\{(.*?)\}", block, re.S)
                return re.findall(r'"([^"]*)"', found.group(1)) if found else []
            for alias in strings("aliases"):
                for modifier in strings("modifiers"):
                    result[(alias + " " + modifier).strip()] = path
    return result


def sha(path):
    return hashlib.file_digest(path.open("rb"), "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--plugins", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--upstream-ref", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    # Do not allow evidence generation to overwrite any inspected input tree.
    for protected in (args.plugins.resolve(), args.server.resolve()):
        if out == protected or protected in out.parents:
            parser.error("--out must be outside the inspected server/plugin trees")
    out.mkdir(parents=True, exist_ok=True)

    def git(*arguments):
        return subprocess.check_output(["git", "-C", str(args.repo), *arguments]).decode("utf-8")

    upstream_commit = git("rev-parse", args.upstream_ref).strip()
    paths = git("ls-tree", "-r", "--name-only", upstream_commit, "main/src/main/java").splitlines()
    upstream = {p: git("show", f"{upstream_commit}:{p}") for p in paths if p.endswith(".java")}
    port = {p.relative_to(args.repo).as_posix(): read(p)
            for p in (args.repo / "neoforge/src/main/java").rglob("*.java")}
    uc, pc = commands(upstream), commands(port)
    rows = [{"command": key, "upstream": uc.get(key, ""), "port": pc.get(key, ""),
             "status": "declaration present; behavior unverified" if key in uc and key in pc
             else "missing declaration" if key in uc else "port-only declaration"}
            for key in sorted(uc.keys() | pc.keys())]
    with (out / "commands.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    port_basenames = {Path(p).name: p for p in port}
    classes = [{"upstream": p, "port_same_basename": port_basenames.get(Path(p).name, ""),
                "status": "source counterpart; behavior unverified" if Path(p).name in port_basenames
                else "no same-basename source; inspect platform replacement/version applicability"}
               for p in upstream]
    with (out / "classes.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=classes[0].keys())
        writer.writeheader()
        writer.writerows(classes)

    verbs, heads, placeholders, fields = (collections.Counter() for _ in range(4))
    locations = collections.defaultdict(list)
    conversations = []
    referenced_items = set()
    sounds = collections.Counter()
    for path, doc in documents(args.plugins / "Interactions/conversations"):
        local_heads, local_placeholders = collections.Counter(), collections.Counter()
        feature_values = {}
        for keypath, value in walk(doc):
            if keypath and isinstance(value, (bool, int, float)):
                fields[(keypath[-1], str(value))] += 1
                if len(keypath) == 1:
                    feature_values[keypath[0]] = value
            if not isinstance(value, str):
                continue
            for placeholder in re.findall(r"%([^%\n]+)%", value):
                family = placeholder.split(":")[0]
                placeholders[family] += 1
                local_placeholders[family] += 1
                if not family.startswith("checkitem") and family not in ("player", "player_name"):
                    locations["placeholder:" + family].append(f"{path.name}:{'.'.join(keypath)}")
            if len(keypath) < 2 or keypath[-2] not in ("actions", "last_actions"):
                continue
            verb, separator, body = value.partition(":")
            if not separator:
                continue
            verb, body = verb.strip(), body.strip()
            verbs[verb] += 1
            if verb == "playsound":
                sounds[body.split(";")[0]] += 1
            if verb in ("console_command", "player_command_as_op"):
                parts = body.lstrip("/").split()
                if parts:
                    heads[parts[0]] += 1
                    local_heads[parts[0]] += 1
                    locations["command:" + parts[0]].append(f"{path.name}:{'.'.join(keypath)}")
                    if len(parts) >= 3 and parts[:2] == ["si", "give"]:
                        referenced_items.add(parts[2])
        new = args.server / "config/interactions/conversations" / path.name
        conversations.append({"file": path.name, "triggers": doc.get("starts_with", []),
                              "features": feature_values, "commands": dict(local_heads),
                              "placeholders": dict(local_placeholders),
                              "deployed_identical": new.is_file() and sha(path) == sha(new)})

    old_npcs = yaml.safe_load(read(args.plugins / "Citizens/saves.yml"))["npc"]
    new_npcs = yaml.safe_load(read(args.server / "config/citizens/saves.yml"))["npc"]
    def npcmap(raw):
        return {str(i): v for i, v in (enumerate(raw) if isinstance(raw, list) else raw.items()) if v}
    old_npcs, new_npcs = npcmap(old_npcs), npcmap(new_npcs)
    traits = collections.Counter(t for npc in old_npcs.values() for t in npc.get("traits", {}))
    trait_sources = {}
    for source, text in port.items():
        match = re.search(r'@TraitName\("([^"]+)"\)', text)
        if match:
            trait_sources[match.group(1)] = source
    factory = read(args.repo / "neoforge/src/main/java/net/citizensnpcs/npc/CitizensTraitFactory.java")
    registered = set(re.findall(r"registerTrait\(TraitInfo.create\((\w+)\.class", factory))
    trait_rows = [{"saved_trait": name, "old_npc_count": count,
                   "port_source": trait_sources.get(name, ""),
                   "registered": Path(trait_sources.get(name, "")).stem in registered}
                  for name, count in sorted(traits.items())]
    with (out / "old-traits.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=trait_rows[0].keys())
        writer.writeheader()
        writer.writerows(trait_rows)
    sentinels = {i: npc["traits"]["sentinel"] for i, npc in old_npcs.items()
                 if "sentinel" in npc.get("traits", {})}
    items = yaml.safe_load(read(args.plugins / "ItemEdit/database/server-database.yml"))
    item_meta = collections.Counter()
    item_rows = []
    for key, value in items.items():
        item = value.get("item", {})
        meta = item.get("meta", {})
        item_meta.update(meta.keys())
        item_rows.append({"id": str(key), "type": item.get("type"),
                          "referenced": str(key) in referenced_items, "metadata_keys": list(meta)})
    mods = [{"file": p.name, "sha256": sha(p)} for p in sorted((args.server / "mods").glob("*.jar"))]
    aliases_file = args.server / "config/interactions/command-aliases.yml"
    aliases = yaml.safe_load(read(aliases_file)) if aliases_file.exists() else {}
    summary = {
        "timestamp": datetime.now().astimezone().isoformat(), "upstream_commit": upstream_commit,
        "local_head": git("rev-parse", "HEAD").strip(),
        "inputs": {"plugins": str(args.plugins), "server": str(args.server)},
        "command_counts": {"upstream": len(uc), "port": len(pc)},
        "missing_commands": sorted(uc.keys() - pc.keys()),
        "upstream_npc_subcommands": len([c for c in uc if c.startswith("npc ")]),
        "port_npc_subcommands": len([c for c in pc if c.startswith("npc ")]),
        "conversations": len(conversations), "unchanged_deployed_conversations": sum(c["deployed_identical"] for c in conversations),
        "action_verbs": dict(verbs), "command_heads": dict(heads), "placeholders": dict(placeholders),
        "sound_actions": dict(sounds), "field_values": {f"{k}={v}": n for (k, v), n in fields.items()},
        "old_npcs": len(old_npcs), "deployed_npcs": len(new_npcs),
        "missing_npc_ids": sorted(old_npcs.keys() - new_npcs.keys()),
        "changed_npc_identity": [i for i in old_npcs.keys() & new_npcs.keys()
                                 if old_npcs[i].get("uuid") != new_npcs[i].get("uuid")],
        "old_traits": dict(traits), "sentinel_count": len(sentinels),
        "registered_trait_classes": len(registered),
        "items": len(items), "referenced_items": len(referenced_items),
        "missing_item_definitions": sorted(referenced_items - {str(k) for k in items}),
        "item_metadata": dict(item_meta), "deployed_aliases": aliases,
        "dropped_command_occurrences": {h: n for h, n in heads.items() if h in aliases and not aliases[h]},
        "plugins": sorted(p.name for p in args.plugins.glob("*.jar")),
        "note": "Static evidence only. Matching files/declarations do not establish functional equivalence.",
    }
    for name, value in (("summary", summary), ("conversations", conversations), ("locations", locations),
                        ("items", item_rows), ("mods", mods), ("sentinels", sentinels)):
        (out / f"{name}.json").write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
