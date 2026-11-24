"""Paired HTTP latency at eight clients with/without bounded CP-SAT jobs on the perf-only V3 deployment."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit
import uuid

from http_capacity import Session, run_round

APP = Path(__file__).resolve().parents[1]
OUT = Path.cwd() / "solver-measurements"
D = "docker"
Q = shlex.quote
API = "course34-v3-api-1"
ORIGIN = "http://127.0.0.1:18096"

class DockerHost:
    """Run on the measured Linux host or use an existing SSH identity and known_hosts."""
    def __init__(self, host):
        self.host = host
    def run(self, label, command, timeout=45):
        args = (["ssh","-o","BatchMode=yes","-o","StrictHostKeyChecking=yes",self.host,command]
                if self.host else ["sh","-ec",command])
        return subprocess.check_output(args,text=True,timeout=timeout).strip()
    def close(self):
        pass

def dump(path, data):
    temp = path.with_suffix(".part")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf8")
    temp.replace(path)

def iso():
    return datetime.now(timezone.utc).isoformat()

def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()

def audit(control):
    sql=(APP/"performance/reconcile.sql").read_text(encoding="utf8")
    response=control.run("reconcile", D+" exec course34-v3-database-1 gosu omm gsql -XqAt -v ON_ERROR_STOP=1 "
        +"-h /run/course-db -p 5432 -U omm -d course_app_v3_perf_test -c "+Q(sql))
    numbers=response.strip().split("|")
    if len(numbers)!=3: raise ValueError("Expected three reconciliation counts")
    result=dict(zip(("seatErrors","duplicateCourses","creditErrors"),map(int,numbers)))
    if any(result.values()):raise RuntimeError("Reconciliation failed: "+str(result))
    return result

class Solver:
    def __init__(self, vm, mode, image, tag):
        self.vm, self.mode, self.image, self.tag = vm, mode, image, tag
        self.directory = "/tmp/kexu-verified-solver-"+tag
        vm.run("prepare-solver-results", "install -d -m 700 "+Q(self.directory))
        # Same-container execution shares the API memory/CPU cgroup, but runs in a separate JVM.
        self.marker = f"/tmp/kexu-work-{tag}"
        self.counter = 0

    def once(self, label):
        self.counter += 1
        name=f"curve-{self.counter:02d}.json"
        target = ("/tmp/"+self.tag+"-"+name) if self.mode=="api-container-second-jvm" else ("/evidence/"+name)
        marker = self.marker if self.mode=="api-container-second-jvm" else "/evidence/started"
        java = ["java","-Xms32m","-Xmx224m","-XX:MaxDirectMemorySize=48m","-XX:ActiveProcessorCount=1",
                "-Dloader.main=edu.ustb.coursev2.PlanningBenchmark","-cp","/app/application.jar",
                "org.springframework.boot.loader.launch.PropertiesLauncher",target]
        worker = "set -eu; touch "+Q(marker)+"; "+shlex.join(java)+" >/dev/null; cat "+Q(target)
        if self.mode=="api-container-second-jvm":
            cmd=D+" exec -e JAVA_TOOL_OPTIONS= "+API+" sh -c "+Q(worker+"; rm -f "+Q(target))
        else:
            # Namespace owner 10001 writes to a private tmpfs; return only the measured JSON.
            permission=D+" run --rm --network none --user 0 --entrypoint sh -v "+Q(self.directory+":/evidence")+" "+Q(self.image)+" -c "+Q("chown 10001:10001 /evidence")
            self.vm.run("assign-benchmark-output",permission)
            cmd=(D+" run --rm --pull=never --network none --cpus 1 --memory 490m --pids-limit 128"
                 +" --read-only --tmpfs /tmp:rw,exec,nosuid,size=160m -e JAVA_TOOL_OPTIONS= -v "
                 +Q(self.directory+":/evidence")+" --entrypoint sh "+Q(self.image)+" -c "+Q(worker))
        data=json.loads(self.vm.run(label,cmd,timeout=120))
        if len(data["allocation"])!=3 or len(data["scheduling"])!=3 or "Linux" not in data["os"]:
            raise RuntimeError("Missing Linux native solver measurements")
        return data

    def wait_started(self, control, future):
        if self.mode=="api-container-second-jvm": command=D+" exec "+API+" test -f "+Q(self.marker)
        else: command=D+" run --rm --network none --entrypoint sh -v "+Q(self.directory+":/evidence:ro")+" "+Q(self.image)+" -c "+Q("test -f /evidence/started")
        until=time.monotonic()+20
        while time.monotonic()<until:
            if future.done(): future.result()
            if control.run("wait-start-batch","if "+command+"; then echo yes; else echo no; fi").strip()=="yes": return
            time.sleep(.12)
        raise TimeoutError("Solver did not start")

    def reset_marker(self, control):
        if self.mode=="api-container-second-jvm": command=D+" exec "+API+" rm -f "+Q(self.marker)
        else: command=D+" run --rm --network none --user 0 --entrypoint sh -v "+Q(self.directory+":/evidence")+" "+Q(self.image)+" -c "+Q("rm -f /evidence/started")
        control.run("reset-private-solver-marker",command)

def main():
    cli=argparse.ArgumentParser(description=__doc__)
    global ORIGIN
    cli.add_argument("--origin",default=ORIGIN)
    cli.add_argument("--password-file",type=Path)
    cli.add_argument("--ssh-host",help="Optional configured SSH hostname of the Docker host")
    cli.add_argument("--execute",action="store_true")
    cli.add_argument("--seconds",type=int,default=6,choices=range(3,13))
    cli.add_argument("--pairs",type=int,default=3,choices=(2,3,4))
    cli.add_argument("--output-dir",type=Path)
    args=cli.parse_args()
    if not args.execute:
        print("PLAN: paired uniform eight-client rounds; dedicated V3 perf database only.");return
    ORIGIN=args.origin
    if urlsplit(ORIGIN).hostname not in ("localhost","127.0.0.1","192.168.189.56") or not args.password_file:
        raise ValueError("Use an isolated course endpoint and your local performance password file")
    if args.ssh_host and not re.fullmatch(r"[\w.@-]+",args.ssh_host):raise ValueError("Invalid SSH host")
    tag="kx"+uuid.uuid4().hex[:12]
    out=args.output_dir or OUT/datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    out.mkdir(parents=True,exist_ok=False)
    control=DockerHost(args.ssh_host)
    solver_vm=DockerHost(args.ssh_host)
    sessions=[]
    doc={"status":"RUNNING","clients":8,"profile":"uniform","mix":"3 reads / 1 alternating write",
         "secondsPerRound":args.seconds,"pairs":args.pairs,"rounds":[],"origin":ORIGIN,"database":"course_app_v3_perf_test"}
    try:
        database=control.run("active-url",D+" exec "+API+" printenv DB_URL").strip()
        if database!="jdbc:opengauss://database:5432/course_app_v3_perf_test":
            raise RuntimeError("Activate the isolated V3 performance database first")
        image=control.run("active-image",D+" inspect "+API+" --format '{{.Image}}'").strip()
        if not re.fullmatch("sha256:[0-9a-f]{64}",image): raise ValueError("Unexpected active image")
        # Actual cgroup + tmpfs headroom determine the scope of the contention experiment.
        raw=control.run("memory-headroom",D+" exec "+API+" sh -c "+Q(
            'cat /sys/fs/cgroup/memory.current 2>/dev/null || cat /sys/fs/cgroup/memory/memory.usage_in_bytes; df -Pk /tmp | tail -1')).splitlines()
        used=int(raw[0]);tmpfree=int(raw[1].split()[3])*1024
        limit=int(control.run("cgroup-limit",D+" inspect "+API+" --format '{{.HostConfig.Memory}}'"))
        remain=int(control.run("guest-headroom","awk '/MemAvailable:/{print $2}' /proc/meminfo"))*1024
        if limit-used>=475*1024**2 and tmpfree>=85*1024**2: mode="api-container-second-jvm"
        elif remain>=820*1024**2: mode="same-vm-bounded-sidecar"
        else: raise RuntimeError("Insufficient headroom for a bounded secondary JVM")
        solver=Solver(solver_vm,mode,image,tag)
        host_before=time.time()
        guest_clock=float(control.run("guest-clock","date +%s.%N"))
        host_after=time.time()
        skew=guest_clock-(host_before+host_after)/2
        doc.update(mode=mode,sharedProcess=False,image=image,headroom={"containerBytes":limit-used,"tmpFreeBytes":tmpfree,
            "guestAvailableBytes":remain},clock={"guestMinusHostSeconds":skew,"clockUncertaintySeconds":(host_after-host_before)/2})
        curve=solver.once("standalone-six-tier-cp-sat")
        dump(out/"planning-linux.json",curve)
        pw=args.password_file.read_text(encoding="ascii").strip()
        origin=urlsplit(ORIGIN)
        with ThreadPoolExecutor(max_workers=4) as pool:
            sessions=list(pool.map(lambda i:Session(origin,(i*71+29)%10000,pw),range(8)))
        held=[s.held for s in sessions]
        run_round(sessions,3)
        def normalize():
            for s,baseline in zip(sessions,held):
                if s.held==baseline:continue
                if baseline: code,_=s.request("POST","/student/enrollments",{"sectionId":s.section})
                else: code,_=s.request("DELETE","/student/enrollments/"+s.section)
                if not 200<=code<300: raise RuntimeError("Unable to restore initial per-client course selection")
                s.held=baseline
        for pair in range(args.pairs):
            for condition in (("alone","solver") if pair%2==0 else ("solver","alone")):
                normalize()
                stage={"pair":pair+1,"condition":condition}
                if condition=="alone":
                    stage["startedAt"]=iso()
                    stage["http"]=run_round(sessions,args.seconds)
                    stage["finishedAt"]=iso()
                else:
                    solver.reset_marker(control)
                    done=threading.Event()
                    def batch():
                        curves=[]
                        for n in range(8):
                            curve=solver.once(f"load-{pair+1}-{n+1}")
                            curves.append(curve)
                            if done.is_set():return curves
                        raise RuntimeError("Bounded solver jobs ended before HTTP interval finished")
                    with ThreadPoolExecutor(max_workers=1) as pool:
                        pending=pool.submit(batch)
                        try:
                            solver.wait_started(control,pending)
                            stage["startedAt"]=iso()
                            stage["http"]=run_round(sessions,args.seconds)
                            stage["finishedAt"]=iso()
                        finally: done.set()
                        curves=pending.result(timeout=145)
                    begin=timestamp(stage["startedAt"])+skew
                    end=timestamp(stage["finishedAt"])+skew
                    overlap=sum(max(0,min(end,timestamp(c["finishedAt"]))-max(begin,timestamp(c["startedAt"]))) for c in curves)
                    stage["solverOverlapSeconds"]=round(overlap,3);stage["solverRuns"]=len(curves)
                    stage["overlapFraction"]=round(overlap/(end-begin),3)
                    dump(out/f"loaded-pair-{pair+1}.json",curves)
                    if overlap<=0: raise RuntimeError("No measured overlap of HTTP and solver")
                stage["mismatches"]=audit(control)
                doc["rounds"].append(stage);dump(out/"solver-contention.json",doc)
        normalize();doc["finalReconciliation"]=audit(control)
        doc["medianByCondition"]={}
        for condition in ("alone","solver"):
            windows=[x["http"] for x in doc["rounds"] if x["condition"]==condition]
            doc["medianByCondition"][condition]={k:statistics.median(f(w) for w in windows) for k,f in
                (("requestsPerSecond",lambda r:r["requestsPerSecond"]),("readP95Ms",lambda r:r["read"]["p95Ms"]),
                 ("writeP95Ms",lambda r:r["write"]["p95Ms"]),("technicalErrorRate",lambda r:r["technicalErrorRate"]))}
        doc["status"]="MEASURED"
    except Exception as e:
        doc.update(status="FAIL",error=str(e));raise
    finally:
        for s in sessions:s.conn.close()
        control.close();solver_vm.close()
        dump(out/"solver-contention.json",doc)
        print(json.dumps({"status":doc["status"],"directory":str(out)},ensure_ascii=False))
if __name__=="__main__":main()
