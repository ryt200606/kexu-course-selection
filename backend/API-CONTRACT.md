> 归档约定：下列是 V2 初版接口。V3 保留业务路径；数据库命名、迁移、软件版本以新版部署说明为准。

# 课序 V2 API contract (2026-11-01)

Base `/api/v2`; UTF-8 JSON; IDs are case-sensitive strings. Dates use ISO-8601 UTC (`2026-11-01T08:00:00Z`). Roles are `ADMIN`, `TEACHER`, `STUDENT`. Authentication is server-side session; fetch `/auth/csrf` before login and after login/logout. Send its token in `X-CSRF-TOKEN` on every write, with same-origin cookies. No credentials in browser storage.

All lists return `{items:[],page:0,size:20,total:0}`; query `page`, `size` (1..100), `q`, optional `termId`, `state`. All errors: `{code,message,fieldErrors:{},requestId}`. Each response has `X-Request-ID`. Writes return resulting resource or `{status:"OK",...}`. Successful business POST/PUT enrollment operations require `Idempotency-Key` (8..100 characters); reuse with a different payload returns `IDEMPOTENCY_CONFLICT`.

## Session and common

- `GET /auth/csrf` public → `{token,headerName:"X-CSRF-TOKEN"}`.
- `POST /auth/login` public + CSRF, `{username,password}` → user.
- `GET /auth/me` → `{id,username,displayName,role,studentId,teacherId}` (nullable IDs).
- `POST /auth/logout` + CSRF → `{status:"OK"}`.
- `GET /health` public → `{status,database,serverVersion,schemaVersion,surrogate}` (H2 always surrogate).
- `GET /terms` any role → page of `{id,name,state}`.
- `GET /rounds?termId=` any → page of `{id,termId,name,state,startsAt,endsAt,withdrawalEndsAt,creditLimit}`.
- `GET /catalog?termId=&q=&department=&availableOnly=` any → page of sections below, including meetings and prerequisite labels.
- `GET /sections/{id}` any → section + meetings + prerequisites.
- `GET /dashboard?termId=` → role-scoped `{students,courses,sections,enrollments,waiting,selectedCredits,creditLimit,averageGrade,passRate,gradeDistribution:[{label,count}],capacity:[{sectionId,title,enrolledCount,capacity}]}`.
- `GET /notifications` → page of `{id,title,message,createdAt,readAt}`; `PUT /notifications/{id}/read` marks own message read.

## Student workbench (`STUDENT`; admin uses explicitly named admin operations)

- `GET /student/summary?termId=` → `{studentId,name,department,termId,selectedCredits,creditLimit,earnedCredits,waitingCount}`.
- `GET /student/enrollments?termId=` → page of enrollment rows.
- `GET /student/timetable?termId=` → `{termId,items:[{sectionId,courseTitle,teacherName,room,weekday,startPeriod,endPeriod,startWeek,endWeek}]}`.
- `GET /student/waitlist?termId=` → page `{id,sectionId,courseTitle,roundId,queueNo,position,state,reason,createdAt}`.
- `GET /student/sections/{id}/eligibility` → `{canEnroll,eligible:canEnroll,canWaitlist,reasons:[{code,message,relatedSectionIds:[]}]}`. Live advisory only; all checks run again inside the write transaction.
- `POST /student/enrollments` `{sectionId}` → enrollment. Fails if a live queue exists; does not automatically join it.
- `DELETE /student/enrollments/{sectionId}` → `{status:"WITHDRAWN",promotionPending:boolean}`.
- `POST /student/swaps` `{sourceSectionId,targetSectionId}` → `{status:"SWAPPED",sourceSectionId,targetSectionId}`; target failure keeps original enrollment; never implicitly joins a queue.
- `POST /student/waitlist` `{sectionId}` → waitlist row; join only when full or queue pending.
- `DELETE /student/waitlist/{id}` → `{status:"CANCELLED"}`.

Enrollment row: `{id,studentId,studentName,sectionId,courseId,courseTitle,termId,termName,credits,teacherName,state,grade,publishedGrade,gradeVersion,gradeState,meetings:[]}`. State is `ENROLLED` or `WITHDRAWN`; lists normally contain only ENROLLED. Students see `grade:null` before first publication. During a correction, they continue to see the previous `publishedGrade`; passed credits change only on republication. Cumulative earnedCredits count each course once, taking its maximum credits among published passing attempts. The same student cannot hold two active sections of the same course in one term.

## Teacher (`TEACHER`, ownership enforced; `ADMIN` may inspect)

- `GET /teacher/sections?termId=` → section page.
- `GET /teacher/sections/{id}/grades` → `{sectionId,gradeState,gradeRevision,version,items:[{enrollmentId,studentId,studentName,grade,publishedGrade,version}]}`.
- `PUT /teacher/sections/{id}/grades` `{items:[{enrollmentId,grade:number|null,version:number}]}` → current grade sheet; all rows validate and commit together; 1..1000 rows. Draft only; 0..100, at most 2 decimals.
- `POST /teacher/sections/{id}/publish-grades` `{version}` → grade sheet. Every active enrollment must have a grade; publish freezes student-visible results.
- `POST /admin/sections/{id}/reopen-grades` `{reason,version}` → section; admin only, audited correction workflow.

## Admin (`ADMIN` only)

Resource lists `/admin/students`, `/admin/teachers`, `/admin/courses`, `/admin/terms`, `/admin/rounds`, `/admin/offerings`, `/admin/sections`, `/admin/users` use standard page. POST creates; PUT `/{id}` edits; DELETE `/{id}` rejects referenced/history-bearing rows. PUT payloads include current `version` where returned. No hard-delete accounts: `PUT /admin/users/{id}` changes enabled state.

`GET /admin/{resource}/{id}` returns the latest editable row, including offering prerequisites and section meetings. Disabling an account or changing its version invalidates existing sessions. Role/binding changes require a new account; they are not accepted through the update endpoint.

- Student `{id,name,department,state:"ACTIVE"|"SUSPENDED"}`.
- Teacher `{id,name,department}`.
- Course `{id,title,department,description}`.
- Term `{id,name,state:"PLANNING"|"ACTIVE"|"ARCHIVED"}`.
- Round `{id,termId,name,state:"DRAFT"|"OPEN"|"CLOSED",startsAt,endsAt,withdrawalEndsAt,creditLimit}`. One open round per term; closing stops new admissions, already admitted transactions may finish. Expired queues are not promoted. All rounds in one term use the same frozen credit limit. Editing a non-draft round preserves exact original timestamp values.
- Offering `{id,courseId,termId,credits,state:"DRAFT"|"PUBLISHED",prerequisites:[{courseId,minGrade:60}]}`. Credits/prerequisites freeze on publication, never rewrite historical terms.
- Section `{id,offeringId,teacherId,capacity,state:"DRAFT"|"PUBLISHED"|"ARCHIVED",meetings:[{weekday:1,startPeriod:1,endPeriod:2,startWeek:1,endWeek:16,room:"教101"}]}`. At least one meeting before publication; published teacher/timetable immutable; capacity may be changed above actual occupancy. Derived fields `{courseId,courseTitle,termId,termName,credits,department,teacherName,enrolledCount,waitingCount,promotionPending,gradeState,version}`.
- User create `{username,displayName,role,studentId?,teacherId?,password}`; update `{displayName,enabled,password?}`. Password never returned.
- `GET /admin/enrollments?termId=&sectionId=&studentId=` → enrollment page.
- `GET /admin/audit?q=&termId=` → page `{id,actorId,actorName,action,entityId,message,requestId,createdAt}`.
- `GET /admin/consistency?termId=` → `{status,checkedSections,checkedStudents,mismatches:[]}` from real aggregate comparisons (read-only).
- `POST /admin/promotions/run` `{sectionId}` → `{status:"OK",processed,promotionPending}`; same bounded worker logic; useful for demo, no synthetic results.

## Errors and stable domain codes

401 `UNAUTHENTICATED`/`BAD_CREDENTIALS`; 403 `FORBIDDEN`/`CSRF_INVALID`; 404 `NOT_FOUND`; 400 `INVALID_INPUT`; 409 `SECTION_FULL`, `DUPLICATE_ENROLLMENT`, `WAITLIST_FIRST`, `WINDOW_CLOSED`, `CREDIT_LIMIT`, `TIMETABLE_CONFLICT`, `PREREQUISITE_REQUIRED`, `STUDENT_INACTIVE`, `VERSION_CONFLICT`, `FROZEN_RESOURCE`, `GRADES_PUBLISHED`, `GRADE_INCOMPLETE`, `CONSTRAINT_VIOLATION`, `IDEMPOTENCY_CONFLICT`; 503 `DATABASE_UNAVAILABLE`/`BUSY_RETRY`.

Waitlist states: `WAITING`, `ENROLLED`, `CANCELLED`, `SKIPPED`, `EXPIRED`. Invalid candidates become SKIPPED with reason and notification; rejoin gets a new queue number. Process at most 20 candidates / approximately 2 seconds per transaction, then persist pending; worker resumes after restart. No direct applicant may jump a live queue.

## Runtime/init contract

Java17 / Boot3.5.16. `mvn verify` → `target/course-selection-v2-2.0.0.jar`. Serve default `APP_MODE=serve`: no automatic DDL/seed. `DB_TYPE=opengauss` (default), `DB_URL=jdbc:opengauss://.../course_app_v2`, `DB_USER`, `DB_PASSWORD`; refuses actual non-openGauss6.0.2 or wrong schema/source SHA. Explicit CLI `java -jar ... --spring.main.web-application-type=none --app.mode=init --app.init.confirm=course_app_v2` with owner credentials creates only an empty dedicated database schema. Confirmation must equal the actual database name; explicit allowed names are course_app_v2, course_app_v2_test, course_app_v2_perf, course_app_v2_perf_test. `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_TEACHER_PASSWORD`, `BOOTSTRAP_STUDENT_PASSWORD` required (10..128 chars); usernames admin, teacher/teacher02..12 and student/student02..120; student identities S1001..S1120 and teachers T1001..T1012. Seed includes 120 students, 12 teachers, 36 courses, 60 current sections and 1 historical section, all fictitious. Initialization does not create database/SQL roles. Optional `APP_RUNTIME_ROLE=course_v2_app` revokes public schema CREATE, grants USAGE, DML on the fixed 16 business tables and SELECT only on cs2_schema_version and views cs2_v_student_timetable/cs2_v_transcript/cs2_v_term_summary; no sequences exist. `APP_MODE=verify` is read-only schema/identity check and exits. Owner/init account is separate. H2 `DB_TYPE=h2` is explicitly marked surrogate only. All native connections SET TIME ZONE UTC and JVM runs UTC; API dates are ISO UTC. No passwords are compiled into the application or included in the public package.
