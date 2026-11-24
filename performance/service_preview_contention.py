"""Eight foreground clients while the actual Spring API builds bounded allocation previews in the same JVM."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import statistics
import sys
import threading
import time
import urllib.request
from urllib.parse import urlsplit
import uuid

from solver_contention import DockerHost, Session, run_round, dump
APP=Path(__file__).resolve().parents[1]
API="course34-v3-api-1"
sys.path.insert(0,str(APP/"scripts"))
from http_acceptance import Client

IMAGE="sha256:c520d77243f09e25fd183d258109650832e271889d712f8dd0804a10d8f8a96d"
DATABASE="course_app_v3_perf_test"
GSQL=" exec -i course34-v3-database-1 gosu omm gsql -XqAt -v ON_ERROR_STOP=1 -h /run/course-db -p 5432 -U omm -d "+DATABASE

def clock(): return datetime.now(timezone.utc).isoformat()

def statement(vm,sql):
    return vm.run("perf-only-preview-fixture",vm.docker+GSQL,stdin=sql+"\n",timeout=65).strip()

def fixture(vm,tag,pw,origin):
    if not re.fullmatch(r"VJ[0-9a-f]{8}",tag):raise ValueError("Invalid generated ID")
    admin_name=tag.lower()+"admin";term=tag+"TERM";rid=tag+"ROUND"
    def table(name,cols,rows):return f"INSERT INTO {name}({cols}) "+rows+";\n"
    sql=table("cs2_user","id,username,password_hash,display_name,role",
        f"SELECT '{tag}A','{admin_name}',password_hash,'性能教学管理员','ADMIN' FROM cs2_user WHERE username='perf00000'")
    sql+=table("cs2_term","id,name,state",f"VALUES('{term}','{term}','ACTIVE')")
    sql+=table("cs2_round","id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit",
        f"VALUES('{rid}','{term}','{rid}','DRAFT',CURRENT_TIMESTAMP-INTERVAL '2 hour',CURRENT_TIMESTAMP-INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '1 day',3)")
    sql+=table("cs2_course","id,title,department,description",
        f"SELECT '{tag}C'||i,'测量课程'||i,'模拟系','独立规划测量' FROM generate_series(0,63) i")
    sql+=table("cs2_offering","id,course_id,term_id,credits,state",
        f"SELECT '{tag}O'||i,'{tag}C'||i,'{term}',3,'PUBLISHED' FROM generate_series(0,63) i")
    sql+=table("cs2_section","id,offering_id,teacher_id,capacity,state",
        f"SELECT '{tag}X'||i,'{tag}O'||i,'PT000',12,'PUBLISHED' FROM generate_series(0,63) i")
    sql+=table("cs2_meeting","id,section_id,weekday,start_period,end_period,start_week,end_week,room",
        f"SELECT '{tag}M'||i,'{tag}X'||i,1+i%5,1+2*(i%4),2+2*(i%4),1,16,'预览教室'||i FROM generate_series(0,63) i")
    statement(vm,"BEGIN;\n"+sql+"COMMIT;")
    admin=Client(origin)
    role=admin.login(admin_name,pw);assert role["role"]=="ADMIN"
    admin.base=origin+"/api/v3"
    commit=admin.request("POST","/admin/planning/rounds/"+rid+"/commit",{},(200,201))
    assert len(commit["seedCommitment"])==64
    sql=table("cs3_preference_form","round_id,student_id,version",
        f"SELECT '{rid}','P'||lpad((5000+i)::text,5,'0'),1 FROM generate_series(0,319) i")
    sql+=table("cs3_preference_choice","round_id,student_id,section_id,choice_rank",
        f"SELECT '{rid}','P'||lpad((5000+i)::text,5,'0'),'{tag}X'||((i*7+k*13)%64),k+1 FROM generate_series(0,319) i CROSS JOIN generate_series(0,4) k")
    statement(vm,"BEGIN;\n"+sql+f"UPDATE cs2_round SET state='CLOSED' WHERE id='{rid}';\nCOMMIT;")
    counts=statement(vm,f"SELECT (SELECT COUNT(*) FROM cs3_preference_form WHERE round_id='{rid}'),(SELECT COUNT(*) FROM cs3_preference_choice WHERE round_id='{rid}');")
    assert counts=="320|1600"
    return admin,rid,term

def preview(admin,rid):
    request=urllib.request.Request(admin.base+"/admin/planning/allocations/preview",
        data=json.dumps({"roundId":rid}).encode(),method="POST",
        headers={"Accept":"application/json","Content-Type":"application/json","X-CSRF-TOKEN":admin.csrf,"Idempotency-Key":uuid.uuid4().hex})
    started=time.monotonic();utc=clock()
    with admin.opener.open(request,timeout=55) as response:
        result=json.load(response);status=response.status
    ended=time.monotonic()
    assert status in (200,201) and result["state"]=="PREVIEW" and result["kind"]=="ALLOCATION" and result["roundId"]==rid
    return {"id":result["id"],"state":result["state"],"status":result["solverStatus"],"httpStatus":status,"utc":utc,
            "start":started,"end":ended,"elapsedSeconds":round(ended-started,3),
            "nativeStageMs":sum(s["elapsedMs"] for s in result["stages"]),"stages":result["stages"],
            "assigned":len(result["assignments"]),"snapshotHash":result["snapshotHash"]}

def lower_bound(preview_record,start,end):
    a,b=preview_record["start"],preview_record["end"]
    outside=max(0,min(start,b)-a)+max(0,b-max(end,a))
    return max(0.0,preview_record["nativeStageMs"]/1000-outside)

def reconcile(vm):
    values=statement(vm,(APP/"performance/reconcile.sql").read_text(encoding="utf8")).split("|")
    if values!=["0","0","0"]: raise RuntimeError("Reconciliation failed: "+str(values))
    return dict(zip(("seatErrors","duplicateCourses","creditErrors"),map(int,values)))

def run(args, connect, docker="docker"):
    tag="VJ"+uuid.uuid4().hex[:8];out=args.evidence_root/datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out.mkdir(parents=True,exist_ok=False)
    doc={"status":"RUNNING","createdUtc":clock(),"clients":8,"pairs":args.pairs,"seconds":args.seconds,
         "fixture":{"students":320,"choices":1600,"sections":64,"seed":"precommitted by API","published":False},
         "sourceImage":args.expected_image,"database":DATABASE,"mode":"actual API JVM via POST /api/v3/admin/planning/allocations/preview",
         "nativeOverlapDefinition":"Sum of per-preview time spent in CP-SAT engine.solve reported by server, subtracting all request time outside foreground interval; conservative lower bound.",
         "windows":[]}
    control=connect();monitor=connect();control.docker=monitor.docker=docker;sessions=[]
    try:
        assert control.run("active-db",docker+" exec "+API+" printenv DB_URL").strip()=="jdbc:opengauss://database:5432/"+DATABASE
        assert control.run("active-image",docker+" inspect "+API+" --format '{{.Image}}'").strip()==args.expected_image
        assert statement(control,"SELECT current_database(),(SELECT COUNT(*) FROM cs2_student), (SELECT MAX(version) FROM cs2_schema_version);")==DATABASE+"|10000|3"
        pw=args.password_file.read_text(encoding="ascii").strip()
        admin,rid,term=fixture(control,tag,pw,args.origin)
        doc["fixture"].update(roundId=rid,term=term)
        with ThreadPoolExecutor(4) as pool:sessions=list(pool.map(lambda i:Session(urlsplit(args.origin),(i*71+29)%10000,pw),range(8)))
        original=[s.held for s in sessions]
        def normalize():
            for s,wanted in zip(sessions,original):
                if s.held!=wanted:
                    if wanted: code,_=s.request("POST","/student/enrollments",{"sectionId":s.section})
                    else: code,_=s.request("DELETE","/student/enrollments/"+s.section)
                    assert 200<=code<300;s.held=wanted
        warm=preview(admin,rid);doc["warmPreview"]=warm;run_round(sessions,2)
        def sample(stop):
            result=[]
            cmd=docker+" exec "+API+" sh -c "+"""'cat /proc/1/comm; grep "^VmRSS:" /proc/1/status; cat /proc/1/stat'"""
            while not stop.is_set():
                raw=monitor.run("same-jvm-sample",cmd,timeout=8).strip().splitlines()
                assert raw[0]=="java"
                fields=raw[2].split(") ",1)[1].split()
                result.append({"utc":clock(),"mono":time.monotonic(),"rssKiB":int(raw[1].split()[1]),"jvmCpuTicks":int(fields[11])+int(fields[12])})
                stop.wait(.6)
            return result
        for i in range(args.pairs):
            for condition in (("alone","preview") if i%2==0 else ("preview","alone")):
                normalize();stop=threading.Event()
                def previews():
                    rows=[]
                    for _ in range(30):
                        rows.append(preview(admin,rid))
                        if stop.is_set():return rows
                    raise RuntimeError("Preview work count bound reached")
                with ThreadPoolExecutor(2) as workers:
                    telemetry=workers.submit(sample,stop);p=None
                    start=time.monotonic();utc=clock()
                    if condition=="preview":p=workers.submit(previews)
                    try: load=run_round(sessions,args.seconds)
                    finally: end=time.monotonic();stop.set()
                    rows=p.result(timeout=62) if p else [];metrics=telemetry.result(timeout=10)
                window={"pair":i+1,"condition":condition,"utc":utc,"start":start,"end":end,"http":load,
                        "serverJvmSamples":metrics,"previews":rows,"nativeOverlapAtLeastSeconds":round(sum(lower_bound(r,start,end) for r in rows),4)}
                assert len(metrics)>=2
                if rows: assert window["nativeOverlapAtLeastSeconds"]>0
                window["reconciliation"]=reconcile(control);doc["windows"].append(window);dump(out/"result.json",doc)
        normalize()
        doc["finalReconciliation"]=reconcile(control)
        persisted=statement(control,f"SELECT id,state,snapshot_hash FROM cs3_planning_run WHERE round_id='{rid}' ORDER BY id;").splitlines()
        objs=[warm]+[r for w in doc["windows"] for r in w["previews"]]
        assert sorted(x["id"]+"|PREVIEW|"+x["snapshotHash"] for x in objs)==persisted
        final=statement(control,f"SELECT (SELECT COUNT(*) FROM cs3_allocation_award a JOIN cs3_planning_run r ON r.id=a.run_id WHERE r.round_id='{rid}'),(SELECT COUNT(*) FROM cs2_enrollment e JOIN cs2_offering o ON o.id=e.offering_id WHERE o.term_id='{term}');")
        assert final=="0|0"
        doc["persistedPreviews"]=len(persisted);doc["publishedAwards"]=doc["enrollmentsInSyntheticTerm"]=0
        doc["medianByCondition"]={}
        for mode in ("alone","preview"):
            records=[w["http"] for w in doc["windows"] if w["condition"]==mode]
            doc["medianByCondition"][mode]={"requestsPerSecond":statistics.median(x["requestsPerSecond"] for x in records),
                "readP95Ms":statistics.median(x["read"]["p95Ms"] for x in records),"writeP95Ms":statistics.median(x["write"]["p95Ms"] for x in records),
                "technicalErrorRate":statistics.median(x["technicalErrorRate"] for x in records)}
        doc["status"]="MEASURED"
        if args.output:dump(args.output,doc)
    except Exception as e:
        doc.update(status="FAIL",error=type(e).__name__+": "+str(e));raise
    finally:
        for s in sessions:s.conn.close()
        control.close();monitor.close();dump(out/"result.json",doc)
        print(json.dumps({"status":doc["status"],"evidence":str(out),"summary":doc.get("medianByCondition")}),flush=True)

def options():
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--execute",action="store_true")
    cli.add_argument("--pairs",type=int,choices=(2,3),default=2)
    cli.add_argument("--seconds",type=int,choices=range(5,13),default=7)
    cli.add_argument("--origin",default="http://127.0.0.1:18096",help="Trusted Nginx origin for the isolated benchmark instance.")
    cli.add_argument("--password-file",type=Path,help="Private password file for the generated perf accounts.")
    cli.add_argument("--expected-image",default=IMAGE,help="Expected immutable sha256: API image from docker inspect.")
    cli.add_argument("--ssh-target",help="Optional trusted Docker host; uses an existing SSH identity and strict known_hosts.")
    cli.add_argument("--evidence-root",type=Path,default=Path("service-preview-measurements"))
    cli.add_argument("--output",type=Path,help="Optional copy of the completed JSON evidence.")
    return cli

if __name__=="__main__":
    args=options().parse_args()
    if args.execute:
        if not args.password_file or not args.password_file.is_file():raise SystemExit("Supply --password-file for the perf accounts")
        if not re.fullmatch(r"sha256:[a-f0-9]{64}",args.expected_image):raise SystemExit("Expected an immutable image ID")
        run(args,lambda:DockerHost(args.ssh_target))
    else:print("PLAN: isolated V3 performance database, 8 front-end users, 320 applicants, 1600 choices, real API JVM previews; no published allocation.")
