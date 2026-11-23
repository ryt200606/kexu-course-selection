#!/usr/bin/env bash
# Derived from course34 final pg-entrypoint-r2.sh; deliberately no V1 DDL/seed.
set -Eeuo pipefail
umask 077
fail() { printf 'course34-v2: %s\n' "$1" >&2; exit 1; }
[[ ${COURSE34_V2_INSTANCE_ID:-} =~ ^[a-f0-9]{32}$ ]] || fail 'Missing V2 instance ID'
[[ -d /var/lib/course && ! -L /var/lib/course ]] || fail 'Dedicated volume missing'
[[ -d /run/course-private && ! -L /run/course-private ]] || fail 'Private runtime directory missing'
marker() { printf 'course34-v2:opengauss:6.0.2\n%s\n' "$COURSE34_V2_INSTANCE_ID"; }
secret() {
  local value
  [[ -f $1 && ! -L $1 ]] || fail 'Secret file missing'
  value=$(cat "$1")
  [[ $value =~ ^[A-Za-z0-9_#]{32}$ && $value == *[A-Z]* && $value == *[a-z]* && $value == *[0-9]* && $value == *'#'* ]] || fail 'Secret must be exactly 32 policy-compliant ASCII bytes'
  printf '%s' "$value"
}
if [[ $(id -u) == 0 ]]; then
  if [[ -e /var/lib/course/.course34-v2-owned ]]; then
    [[ -f /var/lib/course/.course34-v2-owned && ! -L /var/lib/course/.course34-v2-owned ]] || fail 'Invalid marker'
    cmp -s <(marker) /var/lib/course/.course34-v2-owned || fail 'Foreign V2 volume'
  else
    [[ -z $(find /var/lib/course -mindepth 1 -maxdepth 1 -print -quit) ]] || fail 'Unmarked/nonempty volume; never adopt or erase it automatically'
  fi
  for name in admin owner runtime; do
    secret "/run/secrets/${name}_password" > "/run/course-private/${name}_password"
    chmod 600 "/run/course-private/${name}_password"
  done
  mkdir -p /var/lib/course/data /run/course-db
  chown omm /var/lib/course /var/lib/course/data /run/course-db /run/course-private /run/course-private/*_password
  chmod 700 /var/lib/course/data /run/course-db /run/course-private
  exec gosu omm /opt/course/bin/pg-entrypoint-v2.sh
fi
[[ $(id -un) == omm ]] || fail 'Expected omm database owner'
export PGDATA=/var/lib/course/data
[[ $(sha256sum /opt/course/bin/mot-container-r2.conf | cut -d' ' -f1) == 3dc91facc92b6c14cc68d401e2486da6d2f47613e6eb2ad9010ca0224e4b6799 ]] || fail 'Final MOT profile changed'
profile=(-c max_process_memory=2GB -c shared_buffers=256MB -c cstore_buffers=16MB -c wal_buffers=16MB -c mot_config_file=/opt/course/bin/mot-container-r2.conf)
if [[ ! -f /var/lib/course/.course34-v2-owned ]]; then
  [[ -z $(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit) ]] || fail 'Partial initialization retained for inspection'
  gs_initdb -D "$PGDATA" --nodename=course34v2 --encoding=UTF8 --locale=C --pwfile=/run/course-private/admin_password > /run/course-private/init.log 2>&1
  {
    printf 'local all omm trust\n'
    for database in course_app_v2 course_app_v2_test course_app_v2_perf_test; do
      for role in course_v2_owner course_v2_app; do
        printf 'host %s %s 172.30.134.3/32 sha256\n' "$database" "$role"
      done
    done
    printf 'host all all 0.0.0.0/0 reject\nhost all all ::0/0 reject\n'
  } > "$PGDATA/pg_hba.conf"
  printf "\nlisten_addresses='*'\nport=5432\nunix_socket_directory='/run/course-db'\nmax_connections=64\npassword_encryption_type=2\ntimezone='UTC'\n" >> "$PGDATA/postgresql.conf"
  trap 'gs_ctl -D "$PGDATA" -m fast -w -t 120 stop >>/run/course-private/init-stop.log 2>&1 || true' EXIT
  gs_ctl -D "$PGDATA" -Z single_node -w -t 120 -o "-c listen_addresses='127.0.0.1' ${profile[*]}" start > /run/course-private/init-start.log 2>&1
  owner_password=$(secret /run/course-private/owner_password)
  runtime_password=$(secret /run/course-private/runtime_password)
  gsql -X -h /run/course-db -p 5432 -U omm -d postgres -v ON_ERROR_STOP=1 > /run/course-private/roles.log 2>&1 <<SQL
CREATE USER course_v2_owner WITH PASSWORD '$owner_password' NOCREATEDB NOCREATEROLE;
CREATE USER course_v2_app WITH PASSWORD '$runtime_password' NOCREATEDB NOCREATEROLE;
SQL
  for database in course_app_v2 course_app_v2_test course_app_v2_perf_test; do
    gsql -X -h /run/course-db -p 5432 -U omm -d postgres -v ON_ERROR_STOP=1 >> /run/course-private/databases.log 2>&1 <<SQL
CREATE DATABASE $database OWNER course_v2_owner ENCODING 'UTF8';
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO course_v2_app;
SQL
    gsql -X -h /run/course-db -p 5432 -U omm -d "$database" -v ON_ERROR_STOP=1 >> /run/course-private/schema-access.log 2>&1 <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO course_v2_owner;
GRANT USAGE ON SCHEMA public TO course_v2_app;
SQL
  done
  unset owner_password runtime_password
  gs_ctl -D "$PGDATA" -m fast -w -t 120 stop > /run/course-private/init-stop.log 2>&1
  trap - EXIT
  marker > /var/lib/course/.course34-v2-owned
  sync
fi
[[ -s $PGDATA/PG_VERSION ]] || fail 'Owned version file missing'
exec gaussdb -D "$PGDATA" --single_node "${profile[@]}"
