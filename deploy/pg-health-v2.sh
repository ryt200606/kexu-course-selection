#!/usr/bin/env bash
set -euo pipefail
[[ -s /var/lib/course/.course34-v3-owned ]] || exit 1
gosu omm timeout 8 gsql -X -h /run/course-db -p 5432 -U omm -d course_app_v3 -At -v ON_ERROR_STOP=1 -c 'SELECT version()' | grep -F '6.0.2' >/dev/null
