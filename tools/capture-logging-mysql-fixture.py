"""Read only a named isolated logging fixture; retain canonical rows and a SQL dump."""
from datetime import datetime
from pathlib import Path
import argparse
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def capture(container, database, output):
    output = (ROOT / output).resolve()
    if not output.is_relative_to(ROOT / "artifacts") or output.exists():
        raise ValueError("A fresh output directory inside task artifacts is required")
    if not re.fullmatch(r"loggingaudit[a-z0-9_]*", database):
        raise ValueError("Only isolated loggingaudit databases may be captured")
    expected = (ROOT / "artifacts/logging-mysql84-environment/container-id.txt").read_text().strip()
    identity = json.loads(subprocess.check_output(["docker", "inspect", container, "--format", "{{json .}}"], text=True))
    if identity["Id"] != expected or identity["Config"]["Labels"].get("citizens.audit") != "grieflogger-mysql84-20261023" or not identity["State"]["Running"]:
        raise ValueError("Container is not this task's running isolated MySQL fixture")
    # Do not serialize container environment variables or credentials.
    client = ["docker", "exec", "-i", container, "sh", "-c",
              'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --raw --default-character-set=utf8mb4 --database="$1"', "fixture", database]

    def query(sql):
        result = subprocess.run(client, input=sql, text=True, encoding="utf-8", capture_output=True, check=True)
        return [json.loads(line) for line in result.stdout.splitlines() if line]

    columns = query("""SELECT JSON_OBJECT('table',TABLE_NAME,'name',COLUMN_NAME,'ordinal',ORDINAL_POSITION,
        'type',DATA_TYPE,'columnType',COLUMN_TYPE,'charset',CHARACTER_SET_NAME,'collation',COLLATION_NAME,
        'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT,'extra',EXTRA,'comment',COLUMN_COMMENT)
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
    server = query("SELECT JSON_OBJECT('version',VERSION(),'sqlMode',@@global.sql_mode,'charset',@@character_set_server,'lowerCaseTableNames',@@lower_case_table_names);")[0]
    output.mkdir(parents=True)
    snapshot = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", containerId=expected,
                    image=identity["Image"], database=database, server=server, tables=tables, indices=indices)
    (output / "snapshot.json").write_text(json.dumps(snapshot, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    with (output / "database.sql").open("xb") as target:
        subprocess.run(["docker", "exec", container, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF "$1"', "fixture", database], stdout=target, check=True)
    print(f"PASS isolated MySQL snapshot: {database}, {len(tables)} tables, {sum(len(table['rows']) for table in tables.values())} exact rows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    capture(args.container, args.database, args.output)
