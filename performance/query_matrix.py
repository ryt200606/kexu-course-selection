"""Generate/parse the V3 plan matrix. Run only on the isolated 100k-row database."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics

QUERIES = {
    'catalog': """SELECT s.id,c.title,s.capacity-s.enrolled_count AS seats
        FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id
        JOIN cs2_course c ON c.id=o.course_id WHERE o.term_id='PERF2026'
        AND s.state='PUBLISHED' AND s.enrolled_count<s.capacity
        ORDER BY c.title,s.id LIMIT 20""",
    'eligibility': """SELECT e.section_id,m.weekday,m.start_week,m.end_week,m.start_period,m.end_period
        FROM cs2_enrollment e JOIN cs2_meeting m ON m.section_id=e.section_id
        WHERE e.student_id='P00120' AND e.state='ENROLLED' ORDER BY e.section_id,m.id""",
    'roster': """SELECT e.student_id,s.name FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
        WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.student_id LIMIT 100""",
    'admin_offerings': """SELECT o.id,c.title,p.course_id,p.min_grade FROM
        (SELECT id,course_id FROM cs2_offering WHERE term_id='PERF2026' ORDER BY id LIMIT 100) o
        JOIN cs2_course c ON c.id=o.course_id LEFT JOIN cs2_prerequisite p ON p.offering_id=o.id
        ORDER BY o.id,p.course_id""",
    'term_stats': """SELECT o.id,COUNT(e.id) AS enrolled FROM cs2_offering o
        LEFT JOIN cs2_enrollment e ON e.offering_id=o.id AND e.state='ENROLLED'
        WHERE o.term_id='PERF2026' GROUP BY o.id ORDER BY o.id""",
}
WRITE = """UPDATE cs2_enrollment SET state='WITHDRAWN' WHERE id IN
    (SELECT id FROM cs2_enrollment WHERE state='ENROLLED' ORDER BY id LIMIT 1000)"""
CANDIDATES = Path(__file__).with_name('candidate-indexes.sql')

def generate():
    indexes = re.findall(r'^CREATE INDEX (cs3_\w+) (ON [^;]+);', CANDIDATES.read_text('utf-8'), re.M)
    assert len(indexes) == 2
    print(r'\set ON_ERROR_STOP on')
    print("SELECT 1/CASE WHEN current_database()='course_app_v3_perf_test' AND "
          "(SELECT count(*) FROM cs2_enrollment)=100000 THEN 1 ELSE 0 END;")
    print("ANALYZE cs2_enrollment; ANALYZE cs2_section; BEGIN;")
    for name, _ in indexes:
        print(f"DROP INDEX IF EXISTS {name};")
    for phase in ('base', 'v3'):
        if phase == 'v3':
            for name, definition in indexes:
                print(f"CREATE INDEX {name} {definition};")
                print(f"SELECT 'index_bytes|{name}|'||pg_relation_size('{name}');")
        for label, query in QUERIES.items():
            print(f"SELECT 'ROWS|{phase}|{label}';\n{query};\nSELECT 'ENDROWS';")
        for label, query in {**QUERIES, 'write_1000': WRITE}.items():
            for repetition in range(8):
                print(f"SELECT 'PLAN|{phase}|{label}|{repetition}';")
                if label == 'write_1000':
                    print("SAVEPOINT write_cost;")
                print("EXPLAIN ANALYZE " + query + ";")
                if label == 'write_1000':
                    print("ROLLBACK TO SAVEPOINT write_cost; RELEASE SAVEPOINT write_cost;")
    print("ROLLBACK; SELECT 'FINISHED';")

def parse(raw):
    phases = {k:{} for k in ['base','v3']}
    rows = {}
    current = None
    block = None
    sizes = {}
    for line in raw.splitlines():
        if line.startswith('ROWS|'):
            _,phase,label=line.split('|')
            block=[]
        elif line == 'ENDROWS':
            rows[phase,label] = (len(block), hashlib.sha256('\n'.join(block).encode()).hexdigest())
            block=None
        elif block is not None:
            block.append(line)
        elif line.startswith('PLAN|'):
            _,phase,label,run=line.split('|')
            current=phases[phase].setdefault(label,[])
        elif line.startswith('index_bytes|'):
            _,name,size=line.split('|'); sizes[name]=int(size)
        elif current is not None:
            match=re.search(r'(?:Total runtime|Execution time|Execution Time):\s*([\d.]+)\s*ms',line,re.I)
            if match:
                current.append(float(match[1])); current=None
    assert 'FINISHED' in raw and len(sizes)==2
    for label in QUERIES:
        assert rows['base',label] == rows['v3',label], label
    for phase, items in phases.items():
        assert len(items)==len(QUERIES)+1
        for label, values in list(items.items()):
            assert len(values)==8,(phase,label,len(values))
            items[label]={'warmupMs':values[0], 'samplesMs':values[1:], 'medianMs':statistics.median(values[1:])}
    return {'dataset':{'students':10000,'sections':600,'enrollments':100000},
            'resultEquality':{k:{'rows':rows['v3',k][0],'sha256':rows['v3',k][1]} for k in QUERIES},
            'extraIndexBytes':sizes, 'phases':phases, 'labels':{'base':'existing production indexes','v3':'two evaluated candidate indexes, not deployed'}, 'writeCost':'1000 state changes rolled back after each run'}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parse',type=Path)
    args=parser.parse_args()
    if args.parse:
        print(json.dumps(parse(args.parse.read_text('utf-8')),ensure_ascii=False,indent=2))
    else: generate()
