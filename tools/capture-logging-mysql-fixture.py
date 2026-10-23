"""Read only a named isolated logging fixture; retain canonical rows and a SQL dump."""
from datetime import datetime
from pathlib import Path
import argparse
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def capture(container, database, output, identity_file=None):
    output = (ROOT / output).resolve()
    if not output.is_relative_to(ROOT / "artifacts") or output.exists():
        raise ValueError("A fresh output directory inside task artifacts is required")
    if not re.fullmatch(r"loggingaudit[a-z0-9_]*", database):
        raise ValueError("Only isolated loggingaudit databases may be captured")
    if identity_file:
        selected = (ROOT / identity_file).resolve()
        if not selected.is_relative_to(ROOT / "artifacts"):
            raise ValueError("Fixture identity must be saved inside task artifacts")
        fixture_identity = json.loads(selected.read_text(encoding="utf-8-sig"))
    else:
        fixture_identity = dict(containerId=(ROOT / "artifacts/logging-mysql84-environment/container-id.txt").read_text().strip(), label="grieflogger-mysql84-20261023")
    expected = fixture_identity["containerId"]
    identity = json.loads(subprocess.check_output(["docker", "inspect", container, "--format", "{{json .}}"], text=True))
    if identity["Id"] != expected or identity["Config"]["Labels"].get("citizens.audit") != fixture_identity["label"] or not identity["State"]["Running"] or ("image" in fixture_identity and identity["Image"] != fixture_identity["image"]):
        raise ValueError("Container is not this task's running isolated MySQL fixture")
    # Do not serialize container environment variables or credentials.
    client = ["docker", "exec", "-i", container, "sh", "-c",
              'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --raw --default-character-set=utf8mb4 --database="$1"', "fixture", database]

    def query(sql):
        result = subprocess.run(client, input=sql, text=True, encoding="utf-8", capture_output=True, check=True)
        return [json.loads(line) for line in result.stdout.splitlines() if line]

    columns = query("""SELECT JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,'ordinal',ORDINAL_POSITION,
        'type',DATA_TYPE,'columnType',COLUMN_TYPE,'charset',CHARACTER_SET_NAME,'collation',COLLATION_NAME,
        'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT,'extra',EXTRA,'comment',COLUMN_COMMENT,
        'generation',GENERATION_EXPRESSION)
        FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION;""")
    tables = {}
    for column in columns:
        if not all(re.fullmatch(r"[a-zA-Z0-9_]+", column[key]) for key in ["table", "name"]):
            raise ValueError("Unexpected fixture identifier")
        tables.setdefault(column["table"], dict(columns=[], rows=[]))["columns"].append(column)
    for name, table in tables.items():
        expressions = [f"HEX(`{column['name']}`)" if column["type"].endswith("blob") or column["type"] in ["binary", "varbinary"] else f"`{column['name']}`" for column in table["columns"]]
        table["rows"] = sorted(query(f"SELECT JSON_ARRAY({','.join(expressions)}) FROM `{name}`;"), key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True))
    indices = query("""SELECT JSON_OBJECT('table',TABLE_NAME,'name',INDEX_NAME,'sequence',SEQ_IN_INDEX,
        'column',COLUMN_NAME,'nonUnique',NON_UNIQUE) FROM information_schema.statistics
        WHERE table_schema=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;""")
    references = query("""SELECT JSON_OBJECT('table',k.TABLE_NAME,'name',k.CONSTRAINT_NAME,'column',k.COLUMN_NAME,
        'targetSchema',k.REFERENCED_TABLE_SCHEMA,'targetTable',k.REFERENCED_TABLE_NAME,'targetColumn',k.REFERENCED_COLUMN_NAME,
        'update',r.UPDATE_RULE,'delete',r.DELETE_RULE)
        FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r
        ON k.CONSTRAINT_SCHEMA=r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=r.CONSTRAINT_NAME
        WHERE k.TABLE_SCHEMA=DATABASE() ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION;""")
    triggers = query("""SELECT JSON_OBJECT('name',TRIGGER_NAME,'table',EVENT_OBJECT_TABLE,'event',EVENT_MANIPULATION,
        'timing',ACTION_TIMING,'statement',ACTION_STATEMENT,'definer',DEFINER)
        FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY TRIGGER_NAME;""")
    attributes = query("""SELECT JSON_OBJECT('name',TABLE_NAME,'engine',ENGINE,'collation',TABLE_COLLATION,
        'rowFormat',ROW_FORMAT,'comment',TABLE_COMMENT) FROM information_schema.tables
        WHERE table_schema=DATABASE() ORDER BY TABLE_NAME;""")
    server = query("SELECT JSON_OBJECT('version',VERSION(),'sqlMode',@@global.sql_mode,'charset',@@character_set_server,'lowerCaseTableNames',@@lower_case_table_names);")[0]
    output.mkdir(parents=True)
    snapshot = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", containerId=expected,
                    image=identity["Image"], database=database, server=server, tables=tables, indices=indices,
                    references=references, triggers=triggers, attributes=attributes)
    (output / "snapshot.json").write_text(json.dumps(snapshot, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    with (output / "database.sql").open("xb") as target:
        subprocess.run(["docker", "exec", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF "$1"', "fixture", database], stdout=target, check=True)
    print(f"PASS isolated MySQL snapshot: {database}, {len(tables)} tables, {sum(len(table['rows']) for table in tables.values())} exact rows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--identity", help="Artifact-confined fixture identity with containerId, label and optional image")
    args = parser.parse_args()
    capture(args.container, args.database, args.output, args.identity)
