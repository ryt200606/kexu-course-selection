"""Closed-loop HTTP capacity on an isolated three-container / PERF2026 installation."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import http.client
from http.cookies import SimpleCookie
import json
import math
from pathlib import Path
import subprocess
import threading
import time
from urllib.parse import urlsplit
import uuid
from datetime import datetime, timezone

class Session:
    def __init__(self, origin, student, password):
        self.origin = origin
        self.student = student
        self.section = 'PS%03d' % ((student % 20) * 3 + student % 3)
        self.cookies = {}
        self.token = ''
        self.conn = http.client.HTTPConnection(origin.hostname, origin.port, timeout=12)
        self.token = self.request('GET','/auth/csrf')[1]['token']
        self.request('POST','/auth/login',{'username':'perf%05d'%student,'password':password},expect=200)
        self.token = self.request('GET','/auth/csrf')[1]['token']
        enrolled = self.request('GET','/student/enrollments?termId=PERF2026&size=20',expect=200)[1]['items']
        self.held = any(e['sectionId']==self.section for e in enrolled)

    def request(self, method, path, body=None, expect=None):
        headers={'Cookie':'; '.join(f'{k}={v}' for k,v in self.cookies.items()), 'Accept':'application/json'}
        if method!='GET':
            headers.update({'X-CSRF-TOKEN':self.token,'Idempotency-Key':uuid.uuid4().hex,'Content-Type':'application/json'})
        self.conn.request(method,'/api/v2'+path,json.dumps(body) if body is not None else None,headers)
        response=self.conn.getresponse()
        for name,value in response.getheaders():
            if name.lower()=='set-cookie':
                cookie=SimpleCookie();cookie.load(value)
                self.cookies.update({k:v.value for k,v in cookie.items()})
        status=response.status
        raw=response.read()
        payload=json.loads(raw) if raw else {}
        if expect is not None and status!=expect:
            raise RuntimeError(f'{method} {path}: HTTP {status} {payload.get("code","")}')
        return status,payload

    def cycle(self, i):
        if i % 4 == 3:
            if self.held: method,path,body='DELETE','/student/enrollments/'+self.section,None
            else: method,path,body='POST','/student/enrollments',{'sectionId':self.section}
            status,result=self.request(method,path,body)
            if 200<=status<300: self.held=not self.held
            return 'write',status,result
        routes=['/catalog?termId=PERF2026&size=20','/student/summary?termId=PERF2026',
                '/student/sections/'+self.section+'/eligibility']
        status,result=self.request('GET',routes[i%4])
        return 'read',status,result

BUSINESS={'DUPLICATE_ENROLLMENT','SECTION_FULL','WAITLIST_FIRST','TIMETABLE_CONFLICT','CREDIT_LIMIT',
          'PREREQUISITE_REQUIRED','NOT_ENROLLED'}
def histogram(samples):
    if not samples: return {}
    values=sorted(samples)
    return {f'p{q}Ms':round(values[math.ceil(len(values)*q/100)-1],3) for q in (50,95,99)}

def run_round(sessions,seconds):
    start=threading.Barrier(len(sessions)+1)
    def work(item):
        index,sess=item
        start.wait()
        until=time.perf_counter()+seconds
        logs=[]
        while time.perf_counter()<until:
            i=index+len(logs)
            kind='write' if i%4==3 else 'read'
            tick=time.perf_counter()
            try:
                kind,status,payload=sess.cycle(i)
                code=payload.get('code','') if isinstance(payload,dict) else ''
                if not (200<=status<300 or (status==409 and code in BUSINESS)):
                    code='TECH:'+str(status)+':'+code
            except (OSError,ValueError,http.client.HTTPException) as error:
                sess.conn.close()
                code='TECH:'+type(error).__name__; status=0
            logs.append((kind,(time.perf_counter()-tick)*1000,status,code))
        return logs
    with ThreadPoolExecutor(max_workers=len(sessions)) as pool:
        tasks=[pool.submit(work,i) for i in enumerate(sessions)]
        begin=time.perf_counter();start.wait()
        events=[e for t in tasks for e in t.result()]
        elapsed=time.perf_counter()-begin
    codes=Counter(e[3] for e in events if e[3])
    tech=sum(v for k,v in codes.items() if k.startswith('TECH:'))
    business=sum(v for k,v in codes.items() if k in BUSINESS)
    read=[e[1] for e in events if e[0]=='read'];write=[e[1] for e in events if e[0]=='write']
    # Include slow failures in percentiles and denominator, retain outcomes separately.
    return {'endUtc':datetime.now(timezone.utc).isoformat(),'seconds':round(elapsed,3),'requests':len(events),'reads':len(read),'writes':len(write),
            'requestsPerSecond':round(len(events)/elapsed,2),'read':histogram(read),'write':histogram(write),
            'technicalErrors':tech,'businessRefusals':business,'outcomes':dict(codes),
            'technicalErrorRate':tech/max(1,len(events))}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--origin',required=True)
    p.add_argument('--password-file',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--audit-command',nargs='+',required=True,help='Command returning independent SQL discrepancy counts as JSON')
    p.add_argument('--seconds',default=12,type=int)
    p.add_argument('--max-concurrency',default=256,type=int)
    p.add_argument('--levels',default='1,8,16,32,64')
    p.add_argument('--profile',choices=('uniform','hot'),required=True)
    a=p.parse_args()
    origin=urlsplit(a.origin)
    if origin.hostname not in {'127.0.0.1','localhost','192.168.189.56'} or origin.scheme!='http' or origin.port==18095:
        raise ValueError('Require an isolated local/host-only performance API')
    if a.output.exists() or a.seconds<3 or not 1<=a.max_concurrency<=4096: raise ValueError('Invalid bounds or output already exists')
    password=a.password_file.read_text(encoding='ascii').strip()
    levels=list(map(int,a.levels.split(',')))
    while levels[-1]<a.max_concurrency: levels.append(levels[-1]*2)
    levels=[i for i in levels if i<=a.max_concurrency]
    sessions=[]
    doc={'profile':a.profile,'mode':'closed-loop','mix':'three reads per alternating withdraw/enroll write',
         'seed':20261124,'minimumStageSeconds':a.seconds,'goals':{'readP95Ms':500,'writeP95Ms':1000,'technicalErrorRateExclusive':0.001},
         'highestPassing':None,'firstNonPassing':None,'stages':[],'status':'RUNNING'}
    a.output.parent.mkdir(parents=True,exist_ok=True)
    def save():
        tmp=a.output.with_suffix('.part');tmp.write_text(json.dumps(doc,indent=2),encoding='utf-8');tmp.replace(a.output)
    def index_id(i):
        if a.profile=='uniform': return (i*71+29)%10000
        # First 167 writers select PS000; subsequent users spill into the next hot section.
        return (i%167)*60+i//167
    def sign_in(i): return Session(origin,index_id(i),password)
    try:
        for n in levels:
            with ThreadPoolExecutor(max_workers=min(4,n)) as pool:
                sessions.extend(pool.map(sign_in,range(len(sessions),n)))
            run_round(sessions,3)
            stage={'clients':n,'rounds':[]}
            doc['stages'].append(stage)
            for _ in range(3):
                r=run_round(sessions,a.seconds)
                raw=subprocess.check_output(a.audit_command,timeout=60,encoding='utf8')
                arows=json.loads(raw)
                if not arows or any(isinstance(v,bool) or not isinstance(v,int) for v in arows.values()):
                    raise ValueError('Invalid independent audit')
                r['mismatches']=arows
                r['passed']=(not any(arows.values()) and r['technicalErrorRate']<.001
                             and r['read'].get('p95Ms',math.inf)<=500 and r['write'].get('p95Ms',math.inf)<=1000)
                stage['rounds'].append(r);save()
            stage['passed']=all(r['passed'] for r in stage['rounds'])
            print(a.profile,n,stage['passed'],[r['requestsPerSecond'] for r in stage['rounds']],flush=True)
            if not stage['passed']:
                doc['firstNonPassing']=n;break
            doc['highestPassing']=n
        doc['status']='MEASURED';save()
    finally:
        for s in sessions:s.conn.close()
if __name__=='__main__': main()
