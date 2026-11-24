"""Generate bounded synthetic data and real-plan SQL for a NEW dedicated performance DB.

Never connects to a DB. Output cannot overwrite an existing directory. All SQL
refuses the application database and refuses a nonempty public schema.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
DATABASE = 'course_app_v3_perf_test'
COUNT = {'students': 10000, 'courses': 200, 'sections': 600, 'enrollments': 100000}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path)
    p.add_argument('--execute', action='store_true', help='Write files only; never runs SQL')
    p.add_argument('--password-hash-file', type=Path, help='Private bcrypt hash of the synthetic benchmark account password')
    a = p.parse_args()
    if not a.execute:
        print(json.dumps({'status': 'PLAN_ONLY', 'database': DATABASE, 'rows': COUNT, 'database_actions': False}))
        return
    if a.output is None or a.output.exists() or a.output.is_symlink():
        raise ValueError('Use a new output directory')
    schema = ROOT / 'backend/src/main/resources/db/schema.sql'
    if not schema.is_file():
        raise ValueError('Backend schema required')
    schema_text = schema.read_text(encoding='utf-8')
    index_statement = re.search(r'CREATE\s+INDEX\s+cs2_enrollment_section_idx\s+ON\s+cs2_enrollment\s*\(\s*section_id\s*,\s*state\s*\)\s*;', schema_text, re.I)
    if not index_statement:
        raise ValueError('Expected nonconstraint section/state index must be present in the exact backend schema')
    if not a.password_hash_file:
        raise ValueError('A private bcrypt hash file is required to provision benchmark logins')
    password_hash = a.password_hash_file.read_text(encoding='ascii').strip()
    if not re.fullmatch(r'\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}', password_hash):
        raise ValueError('Not a bcrypt digest')
    a.output.mkdir(parents=True, exist_ok=False)
    target = a.output / 'load.sql'
    with target.open('x', encoding='utf-8', newline='\n') as f:
        f.write("\\set ON_ERROR_STOP on\nSET TIME ZONE 'UTC';\nSELECT 1/CASE WHEN current_database()='" + DATABASE + "' THEN 1 ELSE 0 END;\n")
        f.write("SELECT 1/CASE WHEN (SELECT count(*) FROM pg_tables WHERE schemaname='public')=0 THEN 1 ELSE 0 END;\nBEGIN;\n")
        f.write(schema_text + '\n')
        fingerprint = hashlib.sha256(schema.read_bytes()).hexdigest()
        f.write("INSERT INTO cs2_schema_version VALUES(1,'2.0.0','" + fingerprint + "',CURRENT_TIMESTAMP);\n")
        def copy(table, columns, rows):
            f.write('COPY ' + table + '(' + columns + ") FROM STDIN WITH (FORMAT CSV);\n")
            for row in rows:
                f.write(','.join(str(x) for x in row) + '\n')
            f.write('\\.\n')
        copy('cs2_student', 'id,name,department,state', ((f'P{i:05}', f'合成学生{i:05}', '性能测试系', 'ACTIVE') for i in range(10000)))
        copy('cs2_user', 'id,username,password_hash,display_name,role,student_id',
             ((f'PU{i:05}', f'perf{i:05}', password_hash, f'合成学生{i:05}', 'STUDENT', f'P{i:05}') for i in range(10000)))
        copy('cs2_teacher', 'id,name,department', ((f'PT{i:03}', f'合成教师{i:03}', '性能测试系') for i in range(60)))
        copy('cs2_term', 'id,name,state', [('PERF2026', '独立性能合成学期', 'ACTIVE')])
        f.write("INSERT INTO cs2_round(id,term_id,name,state,starts_at,ends_at,withdrawal_ends_at,credit_limit)"
                " VALUES('PERFROUND','PERF2026','合成性能轮','OPEN','2020-01-01','2099-01-01','2099-02-01',60);\n")
        copy('cs2_course', 'id,title,department,description', ((f'PC{i:03}', f'合成课程{i:03}', '性能测试系', '固定随机无关的性能测试数据') for i in range(200)))
        copy('cs2_offering', 'id,course_id,term_id,credits,state', ((f'PO{i:03}', f'PC{i:03}', 'PERF2026', 3, 'PUBLISHED') for i in range(200)))
        counts = [0] * 600
        for i in range(10000):
            for k in range(10):
                counts[((i % 20) + 20 * k) * 3 + i % 3] += 1
        copy('cs2_section', 'id,offering_id,teacher_id,capacity,enrolled_count,state', ((f'PS{s:03}', f'PO{s//3:03}', f'PT{s%60:03}', 1000, counts[s], 'PUBLISHED') for s in range(600)))
        copy('cs2_meeting', 'id,section_id,weekday,start_period,end_period,start_week,end_week,room', ((f'PM{s:03}', f'PS{s:03}', (s//60)%5+1, (s//60)//5*2+1, (s//60)//5*2+2, 1, 16, f'合成教室{s:03}') for s in range(600)))
        copy('cs2_student_term', 'student_id,term_id,selected_credits,credit_limit', ((f'P{i:05}', 'PERF2026', 30, 60) for i in range(10000)))
        copy('cs2_enrollment', 'id,student_id,offering_id,section_id,state,grade,created_at,updated_at',
            ((f'PE{i:05}_{k:02}', f'P{i:05}', f'PO{(i%20)+20*k:03}', f'PS{((i%20)+20*k)*3+i%3:03}', 'ENROLLED',
              f'{50+(i+k)%51}.00', '2026-09-01 00:00:00', '2026-09-01 00:00:00') for i in range(10000) for k in range(10)))
        f.write('COMMIT;\nANALYZE;\n')
        f.write("SELECT 'rows|students|'||count(*) FROM cs2_student;\nSELECT 'rows|sections|'||count(*) FROM cs2_section;\nSELECT 'rows|enrollments|'||count(*) FROM cs2_enrollment;\n")
        f.write("SELECT 'count_mismatches|'||count(*) FROM cs2_section s WHERE enrolled_count<>(SELECT count(*) FROM cs2_enrollment e WHERE e.section_id=s.id AND e.state='ENROLLED');\n")
    query = """SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id"""
    with (a.output / 'measure.sql').open('x', encoding='utf-8', newline='\n') as f:
        f.write("\\set ON_ERROR_STOP on\nSET TIME ZONE 'UTC';\nSELECT 1/CASE WHEN current_database()='" + DATABASE + "' THEN 1 ELSE 0 END;\n")
        f.write("SELECT version();\nSELECT 'database|'||current_database();\nSELECT 'MEASUREMENT_BEGIN';\n")
        f.write("SELECT 'planner_defaults|'||current_setting('enable_indexscan')||'|'||current_setting('enable_bitmapscan');\n")
        f.write("SELECT 1/CASE WHEN current_setting('enable_indexscan')='on' AND current_setting('enable_bitmapscan')='on' THEN 1 ELSE 0 END;\n")
        f.write("ANALYZE cs2_enrollment;\nBEGIN;\nSELECT 1/CASE WHEN (SELECT count(*) FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace ns ON ns.oid=idx.relnamespace JOIN pg_class t ON t.oid=i.indrelid WHERE ns.nspname='public' AND idx.relname='cs2_enrollment_section_idx' AND t.relname='cs2_enrollment' AND NOT i.indisunique AND NOT i.indisprimary AND i.indisvalid AND NOT EXISTS(SELECT 1 FROM pg_constraint c WHERE c.conindid=i.indexrelid))=1 THEN 1 ELSE 0 END;\n")
        f.write("DROP INDEX public.cs2_enrollment_section_idx;\nSELECT 'INDEX_DROP_COMPLETED';\n")
        for label in ('without_section_index', 'with_section_index'):
            if label == 'with_section_index':
                f.write(index_statement.group(0) + "\nSELECT 'INDEX_CREATE_COMPLETED';\n")
            f.write("SELECT 'index_catalog|" + label + "|'||count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';\n")
            f.write("SELECT 'result_rows|" + label + "|'||count(*) FROM (" + query + ') measured_result;\n')
            f.write("SELECT 'warmup|" + label + "';\nEXPLAIN ANALYZE " + query + ';\n')
            for n in range(7):
                f.write("SELECT 'measurement|" + label + '|' + str(n+1) + "';\nEXPLAIN ANALYZE " + query + ';\n')
        f.write("COMMIT;\nSELECT 'index_catalog|restored|'||count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';\n")
        f.write("SELECT 'index_definition|restored|'||indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';\nSELECT 'MEASUREMENT_END';\n")
    files = {x.name: {'bytes': x.stat().st_size, 'sha256': hashlib.sha256(x.read_bytes()).hexdigest()}
             for x in a.output.iterdir() if x.is_file()}
    if sum(r['bytes'] for r in files.values()) > 64 * 1024 * 1024:
        raise ValueError('Unexpectedly large generated dataset; files retained for inspection')
    manifest = {'status': 'GENERATED_NOT_EXECUTED', 'database': DATABASE, 'rows': COUNT,
                'schema_sha256': hashlib.sha256(schema.read_bytes()).hexdigest(), 'files': files,
                'measurement': {'warmup': 1, 'measured_per_scenario': 7, 'concurrency': 1,
                                'scenarios': ['without_section_index', 'with_section_index'],
                                'expected_result_rows': counts[123], 'index_create_sql': index_statement.group(0),
                                'comparison': 'Natural planner before/after real DROP/CREATE of only the nonconstraint section/state index in the dedicated performance DB',
                                'restoration': 'One DDL transaction: success recreates original definition; failed client transaction rolls back DROP'},
                'limits': 'Synthetic bounded dataset; not a whole application workload or production capacity claim'}
    (a.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': manifest['status'], 'output': str(a.output), 'rows': COUNT, 'bytes': sum(r['bytes'] for r in files.values())}))


if __name__ == '__main__':
    main()
