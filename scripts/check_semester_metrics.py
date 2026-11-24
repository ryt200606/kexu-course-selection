"""Compare six analytics API measures to independent gsql output for one frozen term."""
import argparse
from decimal import Decimal
import json
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from http_acceptance import Client


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--origin",required=True)
    p.add_argument("--term",required=True)
    p.add_argument("--sql-output",required=True,type=Path,help="gsql -XqAt output of performance/semester-metrics.sql")
    p.add_argument("--admin-password-file",required=True,type=Path)
    p.add_argument("--username",default="admin")
    p.add_argument("--output",required=True,type=Path)
    a=p.parse_args()
    if a.output.exists() or urlsplit(a.origin).scheme not in ("http","https"):
        raise ValueError("New output and a valid isolated API origin required")
    client=Client(a.origin)
    client.login(a.username,a.admin_password_file.read_text().strip())
    client.base=a.origin.rstrip("/")+"/api/v3"
    truth={}
    for line in a.sql_output.read_text(encoding="utf-8-sig").splitlines():
        metric,n,d=line.split("|")
        if metric in truth: raise ValueError("Duplicate independent metric")
        truth[metric]=[Decimal(n),Decimal(d)]
    if set(truth)!={"UNASSIGNED","PREFERENCE","WAIT_TIME","CAPACITY","WAITLIST","GRADES"}:
        raise ValueError("Expected one independent result per metric")
    result={}
    for metric,expected in truth.items():
        params={"termId":a.term,"metric":metric,"size":100,"page":0}
        if metric in ("UNASSIGNED","PREFERENCE","WAIT_TIME"):
            r=client.request("GET","/admin/analytics/settlement?"+urlencode(params))
            api=[Decimal(str(r[k])) for k in ("numerator","denominator")]
        else:
            rows=[]
            while True:
                r=client.request("GET","/admin/analytics/drill?"+urlencode(params))
                rows+=r["items"]
                if len(rows)>=r["total"]: break
                if not r["items"]: raise AssertionError("Inconsistent pagination")
                params["page"]+=1
            api=[sum((Decimal(str(x[k])) for x in rows),Decimal(0)) for k in ("numerator","denominator")]
        result[metric]={"sql":[str(x) for x in expected],"api":[str(x) for x in api],"match":api==expected}
    passed=all(m["match"] for m in result.values())
    a.output.write_text(json.dumps({"term":a.term,"status":"PASS" if passed else "FAIL","measures":result},indent=2)+"\n",encoding="utf-8")
    if not passed: raise SystemExit("One or more measures disagree")


if __name__=="__main__":
    main()

