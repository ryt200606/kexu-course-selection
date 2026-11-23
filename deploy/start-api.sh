#!/usr/bin/env sh
set -eu
export DB_PASSWORD="$(cat /run/secrets/db_password)"
if [ "${APP_MODE:-serve}" = init ]; then
  INIT_CONFIRM="${INIT_CONFIRM:-course_app_v2}"
  case "$INIT_CONFIRM" in course_app_v2|course_app_v2_test) ;; *) echo 'Explicit main/test init confirmation required' >&2; exit 1;; esac
  export BOOTSTRAP_ADMIN_PASSWORD="$(cat /run/secrets/bootstrap_admin_password)"
  export BOOTSTRAP_TEACHER_PASSWORD="$(cat /run/secrets/bootstrap_teacher_password)"
  export BOOTSTRAP_STUDENT_PASSWORD="$(cat /run/secrets/bootstrap_student_password)"
  exec java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -jar /app/application.jar --spring.main.web-application-type=none --app.mode=init "--app.init.confirm=$INIT_CONFIRM"
fi
if [ "${APP_MODE:-serve}" = verify ]; then
  exec java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -jar /app/application.jar --spring.main.web-application-type=none --app.mode=verify
fi
exec java -Dfile.encoding=UTF-8 -Duser.timezone=UTC -jar /app/application.jar
