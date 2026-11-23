"""Prepare V2 Compose files and secrets, without starting Docker or a database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import string
import sys
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
PINNED = {
    'mot-container-r2.conf': '3dc91facc92b6c14cc68d401e2486da6d2f47613e6eb2ad9010ca0224e4b6799',
    'seccomp-mbind-mode3-flags32zero.json': 'd6f65ba8e95decbdf9e3b9f28577dd61ae0de2a1b339cd876bbbb6558d049127',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def model(project, output, images, instance, web_bind_address='127.0.0.1'):
    """Pure Compose model; exposed HTTP is loopback only; the DB has no host port."""
    bind = lambda source, target: {'type': 'bind', 'source': str(source), 'target': target,
                                  'read_only': True, 'bind': {'create_host_path': False}}
    secret = lambda name, target: {'source': name, 'target': target}
    api = dict(image=images['api'], pull_policy='never', restart='no', read_only=True,
        mem_limit='1g', cpus=2, cap_drop=['ALL'], security_opt=['no-new-privileges:true'],
        tmpfs=['/tmp:rw,nosuid,nodev,size=64m'], stop_grace_period='60s',
        environment=dict(DB_TYPE='opengauss', DB_URL='jdbc:opengauss://database:5432/course_app_v2',
                         DB_USER='course_v2_app', APP_MODE='serve', BIND_ADDRESS='0.0.0.0',
                         PORT='8080', JAVA_TOOL_OPTIONS='-Xms128m -Xmx640m'),
        secrets=[secret('runtime_password', 'db_password')],
        networks={'backend': {'ipv4_address': '172.30.134.3'}, 'frontend': {}},
        depends_on={'database': {'condition': 'service_healthy'}})
    migration = {**api, 'profiles': ['init'], 'networks': {'backend': {'ipv4_address': '172.30.134.3'}},
        'environment': {**api['environment'], 'DB_USER': 'course_v2_owner', 'APP_MODE': 'init',
                        'APP_RUNTIME_ROLE': 'course_v2_app'},
        'secrets': [secret('owner_password', 'db_password')] + [secret('bootstrap_' + n + '_password', 'bootstrap_' + n + '_password')
                                                                  for n in ('admin', 'teacher', 'student')]}
    database = dict(image=images['database'], pull_policy='never', restart='no',
        entrypoint=['bash', '/opt/course/bin/pg-entrypoint-v2.sh'],
        environment={'COURSE34_V2_INSTANCE_ID': instance}, mem_limit='4g', shm_size='1g',
        security_opt=['seccomp=' + str(output / 'runtime/seccomp-mbind-mode3-flags32zero.json')],
        volumes=[{'type': 'volume', 'source': 'data', 'target': '/var/lib/course'},
                 bind(output / 'runtime/pg-entrypoint-v2.sh', '/opt/course/bin/pg-entrypoint-v2.sh'),
                 bind(output / 'runtime/pg-health-v2.sh', '/opt/course/bin/pg-health-v2.sh'),
                 bind(output / 'runtime/mot-container-r2.conf', '/opt/course/bin/mot-container-r2.conf')],
        tmpfs=['/run/course-db:rw,nosuid,nodev,size=16m,mode=0700'],
        secrets=[secret(n + '_password', n + '_password') for n in ('admin', 'owner', 'runtime')],
        networks={'backend': {'ipv4_address': '172.30.134.2', 'aliases': ['database']}},
        healthcheck=dict(test=['CMD', 'bash', '/opt/course/bin/pg-health-v2.sh'], interval='5s', timeout='15s', retries=60, start_period='45s'),
        stop_signal='SIGINT', stop_grace_period='180s', ulimits={'nofile': {'soft': 65536, 'hard': 65536}, 'core': 0})
    database['volumes'].append({'type': 'bind', 'source': str(output / 'db-private'), 'target': '/run/course-private',
                                'bind': {'create_host_path': False}})
    frontend = dict(image=images['frontend'], pull_policy='never', restart='no', mem_limit='128m',
        ports=[web_bind_address + ':18095:80'], networks=['frontend'], stop_grace_period='30s',
        volumes=[bind(project / 'deploy/nginx.conf', '/etc/nginx/conf.d/default.conf'),
                 bind(project / 'frontend/dist', '/usr/share/nginx/html')], depends_on=['api'])
    return dict(name='course34-v2', services={'database': database, 'migration': migration, 'api': api, 'frontend': frontend},
        networks={'backend': {'internal': True, 'ipam': {'config': [{'subnet': '172.30.134.0/28'}]}},
                  'frontend': {'ipam': {'config': [{'subnet': '172.30.135.0/28'}]}}},
        volumes={'data': {'name': 'course34-v2-data-' + instance, 'labels': {'org.ustb.course34.v2.instance': instance}}},
        secrets={n + '_password': {'file': str(output / 'private' / (n + '_password'))}
                 for n in ('admin', 'owner', 'runtime', 'bootstrap_admin', 'bootstrap_teacher', 'bootstrap_student')})


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--execute', action='store_true')
    p.add_argument('--output', type=Path)
    p.add_argument('--images', type=Path, help='JSON database/api/frontend immutable image IDs from actual docker inspect')
    p.add_argument('--final-runtime', type=Path, help='Directory holding the pinned final MOT and seccomp files')
    p.add_argument('--network-reviewed', action='store_true')
    p.add_argument('--web-bind-address', choices=['127.0.0.1', '192.168.189.56'], default=os.environ.get('WEB_BIND_ADDRESS', '127.0.0.1'))
    args = p.parse_args()
    if not args.execute:
        print(json.dumps({'status': 'PLAN_ONLY', 'database': 'course_app_v2', 'port': '127.0.0.1:18095',
                          'budget': 'VM8GiB/DB4GiB/API1GiB/Nginx128MiB', 'starts_services': False}))
        return
    require(sys.platform == 'linux' and os.geteuid() == 0, 'Prepare as Linux root to set private file ownership')
    require(args.output and args.images and args.final_runtime and args.network_reviewed, 'Explicit output, images, final runtime and reviewed networks required')
    out = args.output
    require(out.is_absolute() and out.resolve() == out and not out.exists() and out.parent.is_dir(), 'Use a new absolute output directory')
    require(not out.is_relative_to(ROOT), 'Keep generated secrets outside the public project')
    images = json.loads(args.images.read_text(encoding='utf-8'))
    require(set(images) == {'database', 'api', 'frontend'}, 'Exactly three inspected images required')
    for image in images.values():
        require(re.fullmatch(r'(?:sha256:|[A-Za-z0-9_./:-]+@sha256:)[a-f0-9]{64}', image), 'Floating image tags are not accepted')
    for name, digest in PINNED.items():
        src = args.final_runtime / name
        require(src.is_file() and not src.is_symlink() and sha(src) == digest, 'Final runtime source hash mismatch: ' + name)
    require((ROOT / 'frontend/dist/index.html').is_file(), 'Build the frontend first')
    instance = uuid.uuid4().hex
    require(args.web_bind_address in ('127.0.0.1', '192.168.189.56'), 'Only loopback or the reviewed course VM Host-only address is allowed')
    compose = model(ROOT, out, images, instance, args.web_bind_address)
    out.mkdir(mode=0o700)
    (out / 'private').mkdir(mode=0o700)
    (out / 'db-private').mkdir(mode=0o700)
    (out / 'runtime').mkdir(mode=0o755)
    for name in ('admin', 'owner', 'runtime', 'bootstrap_admin', 'bootstrap_teacher', 'bootstrap_student'):
        value = 'Db34#' + ''.join(secrets.choice(string.ascii_letters + string.digits) for _ in range(27))
        target = out / 'private' / (name + '_password')
        with target.open('x', encoding='ascii') as stream:
            stream.write(value)
        target.chmod(0o400)
        if name != 'admin':
            os.chown(target, 10001, 10001)
    for name in PINNED:
        shutil.copyfile(args.final_runtime / name, out / 'runtime' / name)
        (out / 'runtime' / name).chmod(0o644)
    for name in ('pg-entrypoint-v2.sh', 'pg-health-v2.sh'):
        shutil.copyfile(ROOT / 'deploy' / name, out / 'runtime' / name)
        target = out / 'runtime' / name
        target.chmod(0o755)
        require(target.stat().st_mode & 0o777 == 0o755, 'Runtime shell entrypoints must remain executable')
        require(target.read_bytes().startswith(b'#!/usr/bin/env bash\n'), 'Runtime shell entrypoint must have an LF Bash shebang')
    for name in PINNED:
        require((out / 'runtime' / name).stat().st_mode & 0o777 == 0o644, 'Final MOT/seccomp data files must stay 0644')
    (out / 'compose.json').write_text(json.dumps(compose, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (out / 'instance.json').write_text(json.dumps({'instance': instance, 'images': images,
        'status': 'PREPARED_NOT_STARTED', 'project': 'course34-v2', 'database': 'course_app_v2',
        'source_files': {str(x.relative_to(ROOT)): sha(x) for x in (ROOT / 'deploy').glob('*') if x.is_file()}}
        , ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'PREPARED_NOT_STARTED', 'output': str(out), 'instance': instance, 'credentials_printed': False}))


if __name__ == '__main__':
    main()
