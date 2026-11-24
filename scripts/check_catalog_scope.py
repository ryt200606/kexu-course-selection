"""Check exact course navigation, the older substring filter, and the four session states at one Nginx origin."""
import argparse
import json
from pathlib import Path
import uuid
from http_v3_acceptance import Session


def run(origin, private, output):
    records=[]
    sessions={role:Session(origin,records) for role in ("anonymous","student","teacher","admin")}
    for role in ("student","teacher","admin"):
        password=(private/("bootstrap_"+role+"_password")).read_text().strip()
        assert sessions[role].login(role,password)["role"]==role.upper()
    admin=sessions["admin"]
    token="XC"+uuid.uuid4().hex[:8]
    term=token+"TM"; course=token+"C"
    def create(kind,body): return admin.request("POST","/admin/"+kind,body,expected=(200,201))
    create("terms",{"id":term,"name":"精确检索 "+token,"state":"ACTIVE"})
    for i,suffix in enumerate(("", "2"),1):
        cid=course+suffix;off=token+"O"+str(i);sec=token+"X"+str(i)
        create("courses",{"id":cid,"title":"检索课程"+str(i),"department":token,"description":"虚构验收用课"})
        create("offerings",{"id":off,"termId":term,"courseId":cid,"credits":2,"state":"PUBLISHED","prerequisites":[]})
        create("sections",{"id":sec,"offeringId":off,"teacherId":"T1001","capacity":10,"state":"PUBLISHED",
               "meetings":[{"weekday":i,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":8,"room":token}]})
    matrix={}
    urls=["/catalog?termId="+term,"/student/curriculum","/teacher/sections?termId="+term,"/admin/curricula"]
    for role,expectations in {"anonymous":[401]*4,"student":[200,200,403,403],"teacher":[200,403,200,403],"admin":[200,403,200,200]}.items():
        actor=sessions[role];matrix[role]={}
        for i,(path,status) in enumerate(zip(urls,expectations)):
            if i in (0,2):actor.request("GET",path,expected=(status,))
            else:actor.call("GET",path,expected=(status,))
            matrix[role][path.split("?")[0]]=records[-1]["status"]
    base=urls[0]; student=sessions["student"]
    def ids(params):
        result=student.request("GET",base+params)
        return sorted({row["courseId"] for row in result["items"]})
    checks={
        "oldSubstring":ids("&q="+course),"exact":ids("&courseId="+course),
        "secondExact":ids("&courseId="+course+"2"),"combinedFilters":ids("&q="+course+"&courseId="+course),
        "oldNoFilter":ids(""),"unknown":ids("&courseId="+course+"MISSING"),
    }
    assert checks=={"oldSubstring":[course,course+"2"],"exact":[course],"secondExact":[course+"2"],
        "combinedFilters":[course],"oldNoFilter":[course,course+"2"],"unknown":[]}
    proof={"status":"PASS","fixtureTerm":term,"checks":checks,"roleMatrix":matrix,"httpRequestCount":len(records)}
    with output.open("x",encoding="utf8") as stream: json.dump(proof,stream,indent=2,ensure_ascii=False)
    print(json.dumps(proof,ensure_ascii=False))


if __name__=="__main__":
    cli=argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--origin",choices=["http://127.0.0.1:18096","http://192.168.189.56:18096"],required=True)
    cli.add_argument("--private-dir",type=Path,required=True)
    cli.add_argument("--output",type=Path,required=True)
    options=cli.parse_args()
    run(options.origin,options.private_dir,options.output)
