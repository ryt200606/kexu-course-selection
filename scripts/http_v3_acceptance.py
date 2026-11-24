"""Run three-role V3 acceptance through the deployed Nginx origin, with new fictional fixtures."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import http.server
import json
from pathlib import Path
import secrets
import threading
import time
import urllib.parse
import urllib.request
import uuid

from http_acceptance import Client, check

class Session(Client):
    def __init__(self, base, log):
        super().__init__(base)
        self.origin=base.rstrip("/")
        self.records=log

    def call(self, method, path, data=None, expected=(200,201), key=None):
        self.base=self.origin+"/api/v3"
        try: return self.request(method,path,data,expected,key)
        finally: self.base=self.origin+"/api/v2"

def dropped_response(actor, path, payload, key):
    target=urllib.parse.urlsplit(actor.origin)
    request=urllib.request.Request(actor.origin+path)
    actor.cookies.add_cookie_header(request)
    observed={}
    class DropRelay(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            upstream=http.client.HTTPConnection(target.hostname,target.port,timeout=10)
            try:
                body=self.rfile.read(int(self.headers["Content-Length"]))
                # Lose the response after the actual service has completed the request.
                upstream.request("POST",self.path,body=body,headers={k:self.headers[k] for k in
                    ("Content-Type","Cookie","X-CSRF-TOKEN","Idempotency-Key")})
                response=upstream.getresponse()
                observed.update(status=response.status,requestId=response.getheader("X-Request-ID"))
                self.close_connection=True
            finally: upstream.close()
    relay=http.server.HTTPServer(("127.0.0.1",0),DropRelay)
    runner=threading.Thread(target=relay.handle_request,daemon=True)
    runner.start()
    connection=http.client.HTTPConnection("127.0.0.1",relay.server_port,timeout=12)
    try:
        connection.request("POST",path,body=json.dumps(payload).encode(),headers={
            "Content-Type":"application/json","Cookie":request.get_header("Cookie"),
            "X-CSRF-TOKEN":actor.csrf,"Idempotency-Key":key})
        try: connection.getresponse()
        except http.client.RemoteDisconnected: pass
        else: raise AssertionError("downstream unexpectedly received an acknowledgement")
    finally:
        connection.close(); runner.join(timeout=12); relay.server_close()
    check(observed.get("status") in (200,201),"upstream transaction failed: "+str(observed.get("status")))
    return observed

def public_state(value): return {k:value[k] for k in ("id","state","version")}

def run(base, private, out):
    out.mkdir(parents=True,exist_ok=False)
    checks=[]; requests=[]; facts={}
    result={"checks":checks,"requests":requests,"observations":facts}
    admin=Session(base,requests)
    create=lambda path,body:admin.request("POST","/admin/"+path,body,expected=(200,201))
    prefix="Z3"+uuid.uuid4().hex[:9]
    term=prefix+"TERM"; instant_round=prefix+"RI"; pref=prefix+"RP"
    pw="Ac3#"+secrets.token_urlsafe(18)
    def one(role,name,suffix):
        c=Session(base,requests)
        if role=="TEACHER":
            create("teachers",{"id":suffix,"name":"复核教师","department":prefix})
            binding={"teacherId":suffix}
        else:
            create("students",{"id":suffix,"name":"试排学生","department":prefix,"state":"ACTIVE"})
            binding={"studentId":suffix}
        create("users",{"username":name,"displayName":name,"role":role,"password":pw,**binding})
        check(c.login(name,pw)["role"]==role,"wrong role"); return c
    try:
        h=admin.request("GET","/health")
        check(h["sourceVersion"]=="3.0.0" and not h["surrogate"] and "6.0.2" in h["serverVersion"],"native V3 identity")
        admin.login("admin",(private/"bootstrap_admin_password").read_text().strip())
        facts["health"]=h; facts["termId"]=term
        now=datetime.now(timezone.utc)
        iso=lambda d:d.isoformat(timespec="seconds").replace("+00:00","Z")
        create("terms",{"id":term,"name":"V3集成 "+prefix,"state":"ACTIVE"})
        def round_body(id,state): return {"id":id,"termId":term,"name":id,"state":state,
            "startsAt":iso(now-timedelta(hours=1)),"endsAt":iso(now+timedelta(hours=1)),
            "withdrawalEndsAt":iso(now+timedelta(hours=3)),"creditLimit":18}
        create("rounds",round_body(instant_round,"OPEN"))
        teacher_id=prefix+"T"
        teacher=one("TEACHER",prefix+"T",teacher_id); sid=prefix+"S"
        student=one("STUDENT",prefix+"S",sid)
        other=one("STUDENT",prefix+"B",prefix+"B")
        slots=lambda day,room:{"weekday":day,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16,"room":room}
        def section(suffix,day,state="PUBLISHED",capacity=4):
            cid=prefix+"C"+suffix; oid=prefix+"O"+suffix; sec=prefix+"X"+suffix
            create("courses",{"id":cid,"title":"教学课程 "+suffix,"department":prefix,"description":"虚构集成数据"})
            create("offerings",{"id":oid,"termId":term,"courseId":cid,"credits":3,"state":"PUBLISHED","prerequisites":[]})
            create("sections",{"id":sec,"offeringId":oid,"teacherId":teacher_id,"capacity":capacity,"state":state,
                "meetings":[slots(day,prefix+"R"+suffix)]})
            return cid,sec
        cid,graded=section("G",1);_,second=section("O",2);_,third=section("P",3,capacity=1)
        cur=admin.call("POST","/admin/curricula",{"cohort":prefix,"name":"修读试验","modules":[{"name":"核心","requiredCredits":3,"courseIds":[cid]}]})
        admin.call("POST","/admin/curricula/"+cur["id"]+"/assign",{"studentIds":[sid]})
        plans={"left":[graded],"right":[second]}
        saved=student.call("PUT","/student/plans?termId="+term,{**plans,"version":0})
        check(saved["version"]==1 and student.call("GET","/student/plans?termId="+term)["right"]==[second],"saved plans")
        sim=student.call("POST","/student/plans/compare",{"termId":term,**plans})
        check(sim["left"]["feasible"] and sim["right"]["feasible"],"simulated both plans")
        check(student.request("GET","/student/enrollments?termId="+term)["total"]==0,"preview took a seat")
        checks.append("TWO_STORED_PLANS_ROUNDTRIP_AND_ZERO_SEAT_SIDE_EFFECT")
        key=uuid.uuid4().hex
        intercepted=dropped_response(student,"/api/v2/student/enrollments",{"sectionId":graded},key)
        enrolled=[]
        for _ in range(50):
            enrolled=student.request("GET","/student/enrollments?termId="+term)["items"]
            if enrolled: break
            time.sleep(.1)
        check(len(enrolled)==1 and enrolled[0]["sectionId"]==graded,"commit after connection loss")
        replay=student.request("POST","/student/enrollments",{"sectionId":graded},expected=(200,201),key=key)
        check(replay["id"]==enrolled[0]["id"],"lost acknowledgment did not replay")
        facts["lostResponse"]={"upstream":intercepted,"downstreamAcknowledgementReceived":False,"id":replay["id"],"observedBeforeRetry":True,"retrySameId":True}
        checks.append("REAL_CONNECTION_CLOSED_BEFORE_REPLY_COMMIT_FOUND_BEFORE_REPLAY")
        sheet=teacher.request("GET","/teacher/sections/"+graded+"/grades")
        row=sheet["items"][0]
        sheet=teacher.request("PUT","/teacher/sections/"+graded+"/grades",{"items":[{"enrollmentId":row["enrollmentId"],"grade":59,"version":row["version"]}]})
        teacher.request("POST","/teacher/sections/"+graded+"/publish-grades",{"version":sheet["version"]})
        def official(): return next(i["publishedGrade"] for i in student.request("GET","/student/enrollments?termId="+term)["items"] if i["id"]==replay["id"])
        review=student.call("POST","/student/grade-reviews",{"enrollmentId":replay["id"],"reason":"复核评分"})
        rid=review["id"]; states=[public_state(review)]; official_grades=[official()]
        hidden=other.call("GET","/grade-reviews/"+rid,expected=(404,)); check(hidden["code"]=="NOT_FOUND","private scope")
        rec=teacher.call("POST","/teacher/grade-reviews/"+rid+"/recommend",{"version":0,"proposedGrade":64,"reason":"核对原始评分明细"})
        states.append(public_state(rec)); check(rec["crossesPassThreshold"],"pass threshold")
        dec=admin.call("POST","/admin/grade-reviews/"+rid+"/decide",{"version":1,"reason":"批准复核","approve":True})
        states.append(public_state(dec)); official_grades.append(official())
        check(student.call("GET","/student/curriculum")["earnedCredits"]==0,"draft changed credits")
        pub=teacher.call("POST","/teacher/grade-reviews/"+rid+"/publish",{"version":2})
        states.append(public_state(pub)); official_grades.append(official())
        check(official_grades==[59,59,64] and len(pub["history"])==4,"grade flow")
        check(student.call("GET","/student/curriculum")["earnedCredits"]==3,"published grade omitted")
        facts["gradeReview"]={"states":states,"publishedGrades":official_grades,"afterEarnedCredits":3}
        checks.append("FOUR_STAGE_GRADE_REVIEW_59_TO_64_RECALCULATES_CREDITS_ONLY_AFTER_PUBLICATION")
        _,d1=section("D1",5,"DRAFT");_,d2=section("D2",6,"DRAFT"); room=prefix+"LAB"
        admin.call("PUT","/admin/planning/resources/"+term,{"version":0,"rooms":[{"id":room,"capacity":15}],"unavailable":[]})
        task=lambda sec,d:{"sectionId":sec,"slots":[slots(x,room) for x in d]}
        preview=lambda resources:admin.call("POST","/admin/planning/schedules/preview",{"termId":term,"rooms":[{"id":room,"capacity":15}],"sections":resources})
        bad=preview([task(d1,[5]),task(d2,[5])])
        rejected=admin.call("POST","/admin/planning/runs/"+bad["id"]+"/publish",{"snapshotHash":bad["snapshotHash"]},expected=(409,))
        check(rejected["code"]=="PARTIAL_SCHEDULE","conflicting schedule accepted")
        good=preview([task(d1,[5]),task(d2,[5,6])])
        deployed=admin.call("POST","/admin/planning/runs/"+good["id"]+"/publish",{"snapshotHash":good["snapshotHash"]})
        check(deployed["state"]=="PUBLISHED","CP-SAT schedule publish")
        facts["schedule"]={"failedReason":rejected["code"],"publishedRun":good["id"]}
        checks.append("REAL_LINUX_CP_SAT_CONFLICT_REJECTED_THEN_FULL_SCHEDULE_PUBLISHED")
        # A real waitlist promotion gives WAIT_TIME a positive denominator.
        other.request("POST","/student/enrollments",{"sectionId":third},expected=(200,201))
        student.request("POST","/student/waitlist",{"sectionId":third},expected=(200,201))
        other.request("DELETE","/student/enrollments/"+third)
        wait=admin.call("GET","/admin/analytics/settlement?termId="+term+"&metric=WAIT_TIME")
        check(wait["denominator"]==1 and sum(r["denominator"] for r in wait["items"])==1,"wait drill")
        original=admin.request("GET","/admin/rounds/"+instant_round)
        admin.request("PUT","/admin/rounds/"+instant_round,{**round_body(instant_round,"CLOSED"),"version":original["version"]})
        create("rounds",round_body(pref,"DRAFT"))
        committed=admin.call("POST","/admin/planning/rounds/"+pref+"/commit",{})
        pr=admin.request("GET","/admin/rounds/"+pref)
        admin.request("PUT","/admin/rounds/"+pref,{**round_body(pref,"OPEN"),"version":pr["version"]})
        for person in [student,other]:
            person.call("PUT","/student/preferences/"+pref,{"sectionIds":[second],"version":0})
        pr=admin.request("GET","/admin/rounds/"+pref)
        admin.request("PUT","/admin/rounds/"+pref,{**round_body(pref,"CLOSED"),"version":pr["version"]})
        allocation=admin.call("POST","/admin/planning/allocations/preview",{"roundId":pref})
        check(hashlib.sha256(allocation["seedReveal"].encode()).hexdigest()==committed["seedCommitment"],"commitment mismatch")
        published=admin.call("POST","/admin/planning/runs/"+allocation["id"]+"/publish",{"snapshotHash":allocation["snapshotHash"]})
        check(published["state"]=="PUBLISHED","assignment publish")
        checks.append("COMMIT_OPEN_PREFERENCES_CLOSE_PREVIEW_PUBLISH_SEED_VERIFIED")
        facts["settlement"]={}
        for metric in ("UNASSIGNED","PREFERENCE","WAIT_TIME"):
            data=admin.call("GET","/admin/analytics/settlement?termId="+term+"&metric="+metric)
            assert sum(i["numerator"] for i in data["items"])==data["numerator"]
            assert sum(i["denominator"] for i in data["items"])==data["denominator"] and data["denominator"]>0
            facts["settlement"][metric]={k:data[k] for k in ["numerator","denominator","value","total"]}
        checks.append("THREE_SETTLEMENT_METRICS_RECONCILE_TO_DETAIL")
        result["status"]="PASS"
    except Exception as error:
        result["status"]="FAIL"; result["error"]=str(error)
        raise
    finally:
        (out/"result.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf8")
        print(json.dumps({"status":result.get("status"),"checks":checks,"requests":len(requests),"out":str(out)},ensure_ascii=False))
if __name__=="__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--origin",choices=["http://192.168.189.56:18096","http://127.0.0.1:18096"],required=True)
    cli.add_argument("--private-dir",type=Path,required=True)
    cli.add_argument("--out",type=Path,required=True)
    args=cli.parse_args();run(args.origin,args.private_dir,args.out)
