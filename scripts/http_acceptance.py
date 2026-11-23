"""Bounded real HTTP / roles / enrollment / waitlist / grade acceptance.

Credentials stay in memory. Test fixtures use a unique prefix and are retained
with their audit trail. No destructive cleanup or database initialization.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import json
from pathlib import Path
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/') + '/api/v2'
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None
        self.records = []

    def request(self, method, path, data=None, expected=(200,), key=None, csrf=True):
        headers = {'Accept': 'application/json'}
        if method not in ('GET', 'HEAD'):
            headers['Content-Type'] = 'application/json'
            if csrf and self.csrf:
                headers['X-CSRF-TOKEN'] = self.csrf
            headers['Idempotency-Key'] = key or uuid.uuid4().hex
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode('utf-8')
        begin = time.monotonic()
        started_at = datetime.now(timezone.utc).isoformat()
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read(2 * 1024 * 1024)
        status = response.code
        value = json.loads(raw.decode('utf-8')) if raw else {}
        record = {'method': method, 'path': path, 'status': status,
                  'started_at': started_at, 'completed_at': datetime.now(timezone.utc).isoformat(),
                  'code': value.get('code') if isinstance(value, dict) else None,
                  'request_id': response.headers.get('X-Request-ID'),
                  'elapsed_ms': round((time.monotonic() - begin) * 1000, 3)}
        self.records.append(record)
        if status not in expected:
            raise AssertionError('Unexpected HTTP result: ' + json.dumps(record, ensure_ascii=False))
        return value

    def token(self):
        self.csrf = self.request('GET', '/auth/csrf')['token']

    def login(self, username, password):
        self.token()
        actor = self.request('POST', '/auth/login', {'username': username, 'password': password})
        self.token()
        return actor


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def items(response):
    return response['items']


def expect_code(client, method, path, data, code, key=None):
    result = client.request(method, path, data, expected=(400, 401, 403, 404, 409), key=key)
    check(result.get('code') == code, 'Expected domain code ' + code + ', got ' + str(result.get('code')))


def fixture_snapshot(admin, fixture):
    term = urllib.parse.quote(fixture['term'], safe='')
    sections = items(admin.request('GET', '/admin/sections?termId=' + term + '&size=100'))
    enrollments = items(admin.request('GET', '/admin/enrollments?termId=' + term + '&size=100'))
    # Volatile request/session metadata is deliberately excluded; all business fields remain.
    consistency = admin.request('GET', '/admin/consistency?termId=' + term)
    consistency.pop('snapshotAt', None)
    return {'term': fixture['term'], 'sections': sorted(sections, key=lambda r: r['id']),
            'enrollments': sorted(enrollments, key=lambda r: r['id']),
            'students': items(admin.request('GET', '/admin/students?q=' + fixture['prefix'] + '&size=100')),
            'teachers': items(admin.request('GET', '/admin/teachers?q=' + fixture['prefix'] + '&size=100')),
            'courses': items(admin.request('GET', '/admin/courses?q=' + fixture['prefix'] + '&size=100')),
            'offerings': items(admin.request('GET', '/admin/offerings?termId=' + term + '&size=100')),
            'termRecord': admin.request('GET', '/admin/terms/' + fixture['term']),
            'roundRecord': admin.request('GET', '/admin/rounds/' + fixture['round']),
            'consistency': consistency}


def full(admin, base, checks, result):
    prefix = 'QA' + uuid.uuid4().hex[:10]
    fixture = {'prefix': prefix, 'term': prefix + 'TERM', 'round': prefix + 'ROUND'}
    result['fixture'] = fixture
    now = datetime.now(timezone.utc)
    iso = lambda date: date.isoformat(timespec='seconds').replace('+00:00', 'Z')
    admin.request('POST', '/admin/terms', {'id': fixture['term'], 'name': '验收独立学期 ' + prefix, 'state': 'ACTIVE'}, expected=(200, 201))
    admin.request('POST', '/admin/rounds', {'id': fixture['round'], 'termId': fixture['term'], 'name': '有限验收轮次', 'state': 'OPEN',
        'startsAt': iso(now - timedelta(hours=1)), 'endsAt': iso(now + timedelta(days=1)),
        'withdrawalEndsAt': iso(now + timedelta(days=2)), 'creditLimit': 18}, expected=(200, 201))
    teacher_id = prefix + 'T'
    admin.request('POST', '/admin/teachers', {'id': teacher_id, 'name': '验收教师', 'department': '验收独立系'}, expected=(200, 201))
    clash_teacher_id = prefix + 'T2'
    admin.request('POST', '/admin/teachers', {'id': clash_teacher_id, 'name': '冲突课程教师', 'department': '验收独立系'}, expected=(200, 201))
    password = 'Qa#' + secrets.token_urlsafe(20)
    teacher_name = prefix + '_teacher'
    admin.request('POST', '/admin/users', {'username': teacher_name, 'displayName': '验收教师', 'role': 'TEACHER',
                  'teacherId': teacher_id, 'password': password}, expected=(200, 201))
    teacher = Client(base)
    teacher.records = result['requests']
    teacher.login(teacher_name, password)
    other_teacher_name = prefix + '_other_teacher'
    admin.request('POST', '/admin/users', {'username': other_teacher_name, 'displayName': '另一验收教师', 'role': 'TEACHER',
                  'teacherId': clash_teacher_id, 'password': password}, expected=(200, 201))
    other_teacher = Client(base)
    other_teacher.records = result['requests']
    other_teacher.login(other_teacher_name, password)
    students = []
    for i in range(3):
        student_id = prefix + 'S' + str(i)
        username = prefix + '_s' + str(i)
        admin.request('POST', '/admin/students', {'id': student_id, 'name': '验收学生' + str(i), 'department': '验收独立系', 'state': 'ACTIVE'}, expected=(200, 201))
        admin.request('POST', '/admin/users', {'username': username, 'displayName': '验收学生' + str(i), 'role': 'STUDENT',
                      'studentId': student_id, 'password': password}, expected=(200, 201))
        client = Client(base)
        client.records = result['requests']
        actor = client.login(username, password)
        check(actor['role'] == 'STUDENT' and actor['studentId'] == student_id, 'Session identity binding mismatch')
        students.append(client)
    a_second_session = Client(base)
    a_second_session.records = result['requests']
    a_second_session.login(prefix + '_s0', password)
    del password
    a, b, c = students
    expect_code(a, 'GET', '/admin/students', None, 'FORBIDDEN')
    expect_code(teacher, 'GET', '/admin/users', None, 'FORBIDDEN')
    checks.append('ROLE_AND_OBJECT_BINDINGS')

    def make_course(suffix, prerequisites=None, capacity=1, weekday=1, start=1, credits=3, teacher_override=None):
        course = prefix + 'C' + suffix
        offering = prefix + 'O' + suffix
        section = prefix + 'X' + suffix
        admin.request('POST', '/admin/courses', {'id': course, 'title': '验收课程' + suffix, 'department': '验收独立系', 'description': '独立验收fixture；保留审计'}, expected=(200, 201))
        admin.request('POST', '/admin/offerings', {'id': offering, 'courseId': course, 'termId': fixture['term'], 'credits': credits,
                      'state': 'PUBLISHED', 'prerequisites': prerequisites or []}, expected=(200, 201))
        admin.request('POST', '/admin/sections', {'id': section, 'offeringId': offering, 'teacherId': teacher_override or teacher_id, 'capacity': capacity,
                      'state': 'PUBLISHED', 'meetings': [{'weekday': weekday, 'startPeriod': start, 'endPeriod': start+1,
                                                        'startWeek': 1, 'endWeek': 16, 'room': '验收教室' + suffix}]}, expected=(200, 201))
        return course, offering, section

    course, offering, main = make_course('MAIN')
    _, _, conflict = make_course('CLASH', capacity=3, teacher_override=clash_teacher_id)
    _, _, prerequisite = make_course('PRE', [{'courseId': course, 'minGrade': 60}], capacity=3, weekday=3)
    _, _, race = make_course('RACE', weekday=4)
    expect_code(other_teacher, 'GET', '/teacher/sections/' + main + '/grades', None, 'FORBIDDEN')
    checks.append('TEACHER_OBJECT_OWNERSHIP')
    fixture['main_section'] = main
    same_key = uuid.uuid4().hex
    admitted = a.request('POST', '/student/enrollments', {'sectionId': main}, key=same_key, expected=(200, 201))
    replay = a.request('POST', '/student/enrollments', {'sectionId': main}, key=same_key, expected=(200, 201))
    check(admitted['id'] == replay['id'], 'Idempotency replay returned a different enrollment')
    expect_code(a, 'POST', '/student/enrollments', {'sectionId': conflict}, 'IDEMPOTENCY_CONFLICT', key=same_key)
    expect_code(a, 'POST', '/student/enrollments', {'sectionId': conflict}, 'TIMETABLE_CONFLICT')
    expect_code(c, 'POST', '/student/enrollments', {'sectionId': prerequisite}, 'PREREQUISITE_REQUIRED')
    expect_code(b, 'POST', '/student/enrollments', {'sectionId': main}, 'SECTION_FULL')
    checks.extend(['IDEMPOTENCY_SAME_RESPONSE', 'IDEMPOTENCY_PAYLOAD_CONFLICT', 'TIMETABLE_RULE', 'PREREQUISITE_RULE', 'CAPACITY_RULE'])
    wait_b = b.request('POST', '/student/waitlist', {'sectionId': main}, expected=(200, 201))
    expect_code(c, 'POST', '/student/enrollments', {'sectionId': main}, 'WAITLIST_FIRST')
    wait_c = c.request('POST', '/student/waitlist', {'sectionId': main}, expected=(200, 201))
    check(wait_b['queueNo'] < wait_c['queueNo'], 'FIFO queue numbers not increasing')
    a.request('DELETE', '/student/enrollments/' + main)
    admin.request('POST', '/admin/promotions/run', {'sectionId': main})
    deadline = time.monotonic() + 25
    promoted = []
    while time.monotonic() < deadline:
        promoted = [r for r in items(b.request('GET', '/student/enrollments?termId=' + fixture['term'])) if r['sectionId'] == main and r['state'] == 'ENROLLED']
        if promoted:
            break
        time.sleep(0.5)
    check(len(promoted) == 1, 'First waitlisted student not promoted within 25 seconds')
    remaining = [r for r in items(c.request('GET', '/student/waitlist?termId=' + fixture['term'])) if r['id'] == wait_c['id']]
    check(remaining and remaining[0]['state'] == 'WAITING', 'Second candidate jumped the queue')
    c.request('DELETE', '/student/waitlist/' + wait_c['id'])
    checks.extend(['WAITLIST_FIFO', 'NO_QUEUE_JUMP', 'PROMOTION_AND_CANCEL'])

    barrier = threading.Barrier(2, timeout=5)
    def compete(client):
        barrier.wait()
        result = client.request('POST', '/student/enrollments', {'sectionId': race}, expected=(200, 201, 409))
        return result.get('code', 'ENROLLED')
    with ThreadPoolExecutor(max_workers=2) as pool:
        one = pool.submit(compete, a)
        two = pool.submit(compete, c)
        outcomes = [one.result(timeout=20), two.result(timeout=20)]
    check(sorted(outcomes) == ['ENROLLED', 'SECTION_FULL'], 'Concurrent final-seat invariant failed: ' + repr(outcomes))
    checks.append('TWO_SESSIONS_LAST_SEAT_EXACTLY_ONE_COMMIT')
    # Failed target admission must retain the source selection.
    expect_code(b, 'POST', '/student/swaps', {'sourceSectionId': main, 'targetSectionId': race}, 'SECTION_FULL')
    original = [r for r in items(b.request('GET', '/student/enrollments?termId=' + fixture['term'])) if r['sectionId'] == main and r['state'] == 'ENROLLED']
    check(len(original) == 1, 'Failed swap removed original enrollment')
    checks.append('SWAP_FAILURE_IS_ATOMIC')
    _, _, credit_ten = make_course('CREDIT10', capacity=3, weekday=5, credits=10)
    _, _, credit_nine = make_course('CREDIT9', capacity=3, weekday=6, credits=9)
    a.request('POST', '/student/enrollments', {'sectionId': credit_ten}, expected=(200, 201))
    expect_code(a, 'POST', '/student/enrollments', {'sectionId': credit_nine}, 'CREDIT_LIMIT')
    frozen = admin.request('GET', '/admin/offerings/' + offering)
    expect_code(admin, 'PUT', '/admin/offerings/' + offering,
                {'courseId': course, 'termId': fixture['term'], 'credits': 4, 'state': 'PUBLISHED',
                 'prerequisites': [], 'version': frozen['version']}, 'FROZEN_RESOURCE')
    checks.extend(['PER_TERM_CREDIT_LIMIT', 'PUBLISHED_OFFERING_FROZEN'])
    _, _, keyed = make_course('KEYED', weekday=7)
    same_key_parallel = uuid.uuid4().hex
    same_key_barrier = threading.Barrier(2, timeout=5)
    def repeat_same_key(client):
        same_key_barrier.wait()
        return client.request('POST', '/student/enrollments', {'sectionId': keyed},
                              key=same_key_parallel, expected=(200, 201))
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(repeat_same_key, a)
        second = pool.submit(repeat_same_key, a_second_session)
        check(first.result(timeout=20)['id'] == second.result(timeout=20)['id'], 'Concurrent same-key requests did not return the same enrollment')
    keyed_rows = items(admin.request('GET', '/admin/enrollments?termId=' + fixture['term'] + '&sectionId=' + keyed))
    check(len(keyed_rows) == 1, 'Concurrent same key committed duplicate business results')
    checks.append('DOUBLE_SESSION_SAME_KEY_EXACTLY_ONE_BUSINESS_RESULT')

    sheet = teacher.request('GET', '/teacher/sections/' + main + '/grades')
    row = next(r for r in sheet['items'] if r['studentId'] == original[0]['studentId'])
    sheet = teacher.request('PUT', '/teacher/sections/' + main + '/grades', {'items': [{'enrollmentId': row['enrollmentId'], 'grade': 92.5, 'version': row['version']}]})
    visible_draft = [r for r in items(b.request('GET', '/student/enrollments?termId=' + fixture['term'])) if r['sectionId'] == main][0]
    check(visible_draft['grade'] is None, 'Draft grade leaked to student')
    expect_code(teacher, 'PUT', '/teacher/sections/' + main + '/grades', {'items': [{'enrollmentId': row['enrollmentId'], 'grade': 90, 'version': row['version']}]}, 'VERSION_CONFLICT')
    teacher.request('POST', '/teacher/sections/' + main + '/publish-grades', {'version': sheet['version']})
    visible = [r for r in items(b.request('GET', '/student/enrollments?termId=' + fixture['term'])) if r['sectionId'] == main][0]
    check(float(visible['grade']) == 92.5 and visible['gradeState'] == 'PUBLISHED', 'Published grade not visible')
    checks.extend(['DRAFT_GRADE_HIDDEN', 'GRADE_OPTIMISTIC_LOCK', 'PUBLISHED_GRADE_VISIBLE'])
    consistency = admin.request('GET', '/admin/consistency?termId=' + fixture['term'])
    check(consistency.get('status') in ('OK', 'PASS') and consistency.get('mismatches') == [], 'Database aggregate invariants mismatch')
    audit = admin.request('GET', '/admin/audit?termId=' + fixture['term'] + '&size=100')
    check(audit['total'] > 0, 'Fixture operations were not audited')
    checks.extend(['DATABASE_AGGREGATE_INVARIANTS', 'AUDIT_REAL_ROWS'])
    check(fixture['term'] == prefix + 'TERM' and fixture['round'] == prefix + 'ROUND', 'Only this newly created QA fixture may be closed')
    current_round = admin.request('GET', '/admin/rounds/' + fixture['round'])
    round_update = {key: current_round[key] for key in ('termId', 'name', 'startsAt', 'endsAt', 'withdrawalEndsAt', 'creditLimit', 'version')}
    round_update['state'] = 'CLOSED'
    admin.request('PUT', '/admin/rounds/' + fixture['round'], round_update)
    current_term = admin.request('GET', '/admin/terms/' + fixture['term'])
    admin.request('PUT', '/admin/terms/' + fixture['term'],
                  {'name': current_term['name'], 'state': 'ARCHIVED', 'version': current_term['version']})
    check(admin.request('GET', '/admin/rounds/' + fixture['round'])['state'] == 'CLOSED', 'QA round did not close')
    check(admin.request('GET', '/admin/terms/' + fixture['term'])['state'] == 'ARCHIVED', 'QA term did not archive')
    checks.append('FIXTURE_CLOSED_AND_ARCHIVED')
    fixture['snapshot'] = fixture_snapshot(admin, fixture)
    return fixture


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', default='http://127.0.0.1:18095')
    p.add_argument('--private-dir', type=Path, help='Prepared private dir; bootstrap_admin_password is read without printing')
    p.add_argument('--phase', choices=['smoke', 'before', 'after'], default='smoke')
    p.add_argument('--snapshot', type=Path, help='Public fixture snapshot written by before; read by after')
    p.add_argument('--output', type=Path)
    p.add_argument('--execute', action='store_true')
    args = p.parse_args()
    if not args.execute:
        print(json.dumps({'status': 'PLAN_ONLY', 'phase': args.phase, 'maximum_workers': 2,
                          'http_timeout_seconds': 15, 'promotion_timeout_seconds': 25, 'deletes_existing_data': False}))
        return
    if args.private_dir is None or args.output is None or args.output.exists():
        raise ValueError('Explicit private dir and a new result path required')
    target = urllib.parse.urlsplit(args.base_url)
    if (target.scheme != 'http' or target.hostname not in ('127.0.0.1', 'localhost', '192.168.189.56')
            or target.username is not None or target.password is not None
            or target.path not in ('', '/') or target.query or target.fragment):
        raise ValueError('Use an explicit local/course Host-only HTTP origin')
    if args.phase in ('before', 'after') and args.snapshot is None:
        raise ValueError('A snapshot path is required for persistence phases')
    if args.phase == 'before' and args.snapshot.exists():
        raise ValueError('Will not overwrite a prior fixture snapshot')
    result = {'status': 'IN_PROGRESS', 'phase': args.phase, 'started_at': datetime.now(timezone.utc).isoformat(),
              'checks': [], 'requests': [], 'credentials_recorded': False}
    admin = Client(args.base_url)
    try:
        health = admin.request('GET', '/health')
        check(health['database'] == 'opengauss' and health['surrogate'] is False and '6.0.2' in health['serverVersion'], 'Actual openGauss6.0.2 required')
        result['health'] = health
        result['checks'].append('ACTUAL_OPENGAUSS_6_0_2_IDENTITY')
        expect_code(admin, 'GET', '/admin/students', None, 'UNAUTHENTICATED')
        admin.request('POST', '/auth/login', {'username': 'invalid', 'password': 'invalid'}, expected=(403,), csrf=False)
        password = (args.private_dir / 'bootstrap_admin_password').read_text(encoding='ascii')
        actor = admin.login('admin', password)
        del password
        check(actor['role'] == 'ADMIN', 'Admin session role mismatch')
        result['checks'].extend(['ANONYMOUS_DENIED', 'CSRF_ENFORCED', 'SESSION_LOGIN'])
        if args.phase == 'before':
            fixture = full(admin, args.base_url, result['checks'], result)
            args.snapshot.parent.mkdir(parents=True, exist_ok=True)
            with args.snapshot.open('x', encoding='utf-8') as stream:
                json.dump(fixture, stream, ensure_ascii=False, indent=2)
            result['snapshot'] = {'path': str(args.snapshot), 'sha256': hashlib.sha256(args.snapshot.read_bytes()).hexdigest()}
        elif args.phase == 'after':
            fixture = json.loads(args.snapshot.read_text(encoding='utf-8'))
            actual = fixture_snapshot(admin, fixture)
            check(actual == fixture['snapshot'], 'Business fields changed after normal restart')
            result['checks'].append('SAME_FIXTURE_FULL_BUSINESS_FIELDS_PERSISTED')
            result['snapshot_sha256'] = hashlib.sha256(args.snapshot.read_bytes()).hexdigest()
        else:
            admin.request('GET', '/terms')
            admin.request('GET', '/dashboard')
            result['checks'].append('AUTHENTICATED_READS')
        result['status'] = 'PASS'
    except Exception as error:
        result['status'] = 'FAIL'
        result['error_type'] = type(error).__name__
        result['error'] = str(error)
    finally:
        result['requests'].extend(admin.records)
        result['finished_at'] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x', encoding='utf-8') as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'status': result['status'], 'phase': args.phase, 'checks': len(result['checks']), 'output': str(args.output)}))
    if result['status'] != 'PASS':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
