"""Compare plain openGauss logical dumps offline without printing data rows or credentials."""
import argparse
import hashlib
import json
import re
from pathlib import Path

def sha(payload):
    return hashlib.sha256(payload).hexdigest()

def ddl_line(line):
    # openGauss may move a text[] cast from the literal array to each member after restore.
    # Preserve field names, allowed literal values, delimiters and all other constraints.
    if b" CHECK " in line and b" = ANY (" in line:
        outer=rb"\(ARRAY\[('[A-Z_]+'::character varying(?:, '[A-Z_]+'::character varying)*)\]\)::text\[\]"
        line=re.sub(outer,lambda m:b"ARRAY["+m.group(1)+b"]",line)
        line=re.sub(rb"\(('[A-Z_]+'::character varying)\)::text",rb"\1",line)
    return line

def fingerprint(file):
    rows={}; schema=hashlib.sha256(); raw=hashlib.sha256()
    row_hashes=None; table=None; complete=False
    with file.open("rb") as lines:
        for original in lines:
            raw.update(original)
            line=original.rstrip(b"\r\n")
            if row_hashes is not None:
                if line==b"\\.":
                    signature=hashlib.sha256()
                    for value in sorted(row_hashes): signature.update(value)
                    rows[table]={"rows":len(row_hashes),"sha256":signature.hexdigest()}
                    row_hashes=None
                else:
                    row_hashes.append(hashlib.sha256(line).digest())
                continue
            if line.startswith(b"COPY public."):
                match=re.match(rb"^COPY public\.(cs[23]_[a-z0-9_]+) \(",line)
                if match is None: raise ValueError("Unexpected table in a course database dump")
                table=match.group(1).decode("ascii")
                if table in rows: raise ValueError("Duplicate COPY for "+table)
                row_hashes=[]
            if line.startswith(b"-- openGauss database dump complete"): complete=True
            if line.strip() and not line.lstrip().startswith(b"--"):
                schema.update(ddl_line(line)+b"\n")
    if row_hashes is not None or not complete or not rows:
        raise ValueError("Incomplete or unsupported plain openGauss dump")
    return rows,schema.hexdigest(),raw.hexdigest()

def compare(source, restored):
    a,sa,da=fingerprint(source); b,sb,db=fingerprint(restored)
    differing=sorted(t for t in a.keys()|b.keys() if a.get(t)!=b.get(t))
    return {"status":"PASS" if not differing and sa==sb else "FAIL","tables":len(a),
            "totalRows":sum(v["rows"] for v in a.values()),"perTable":a,"differentTables":differing,
            "schemaEqual":sa==sb,"schemaDefinitionsSha256":sa,"restoredSchemaDefinitionsSha256":sb,
            "sourceDumpSha256":da,"recoveredDumpSha256":db}

if __name__=="__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source",type=Path);cli.add_argument("restored",type=Path)
    cli.add_argument("--output",type=Path)
    args=cli.parse_args(); data=compare(args.source,args.restored)
    if args.output:
        with args.output.open("x",encoding="utf8") as out: json.dump(data,out,indent=2)
    print(json.dumps({k:v for k,v in data.items() if k!="perTable"}))
    raise SystemExit(0 if data["status"]=="PASS" else 1)
