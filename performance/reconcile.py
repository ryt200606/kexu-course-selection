"""Independent post-load audit for a local Docker Compose host."""
import json
import os
from pathlib import Path
import subprocess

database=os.environ.get('KEXU_MEASURE_DB','course_app_v3_perf_test')
if database not in ('course_app_v2_perf_test','course_app_v3_perf_test'):
    raise ValueError('An isolated performance DB is required')
query=Path(__file__).with_suffix('.sql').read_text(encoding='utf-8')
command=['docker','compose','-f',os.environ['KEXU_COMPOSE'],'exec','-T','database','gosu','omm',
         'gsql','-XqAt','-v','ON_ERROR_STOP=1','-h','/run/course-db','-p','5432','-U','omm','-d',database,'-c',query]
result=subprocess.check_output(command,timeout=30,text=True).strip().split('|')
if len(result)!=3:raise ValueError('Unexpected audit result')
print(json.dumps(dict(zip(['seatErrors','duplicateCourses','creditErrors'],map(int,result)))))
