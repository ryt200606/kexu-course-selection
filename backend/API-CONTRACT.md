# 课序 3.0 接口

新增资源的前缀为 `/api/v3`。先在 `/api/v2/auth/csrf` 取令牌，通过 `/api/v2/auth/login` 登录并再取令牌。同源会话 Cookie 识别身份，所有写请求携带 `X-CSRF-TOKEN`。服务端从会话确定学生、教师和教务权限。列表以零为起始页，常规分页结果含 `items/page/size/total`。

## 培养方案、草案与成绩复核

| 角色和方法 | 路径（相对 `/api/v3`） | 请求与返回 |
|---|---|---|
| 教务 GET / POST | `/admin/curricula` | 查询版本目录或发布新版本。创建字段为 `cohort,name,modules:[{name,requiredCredits,courseIds}]`。模块间课程唯一。 |
| 教务 POST | `/admin/curricula/{id}/assign` | `{studentIds:[...]}`，只绑定尚未分配方案的学生，冲突时整批回滚。 |
| 教务 GET | `/admin/curricula/{id}/progress` | 分页查看已绑定此版本的学生、已计入学分及剩余学分。 |
| 学生 GET | `/student/curriculum` | 本人绑定版本、各模块要求、已通过课程和剩余学分。按课程去重，取历次及格成绩所对应的最大学分；计入某模块的学分上限为该模块要求。 |
| 学生 GET / PUT | `/student/plans?termId=...` | 两套草案分别以 `left,right` 保存有序教学班编号；更新提供 `version`，旧版本返回 409。每套至多 20 个班。 |
| 学生 POST | `/student/plans/compare` | `{termId,left,right}`。对每套方案返回学分、可行性、逐班原因与可替代同课程教学班。试排不预占席位。 |
| 学生 POST | `/student/grade-reviews` | `{enrollmentId,reason}` 为本人已发布成绩申请复核，初始状态 `REQUESTED`。 |
| 授课教师 POST | `/teacher/grade-reviews/{id}/recommend` | `{version,proposedGrade,reason}` 提交建议，进入 `RECOMMENDED`。建议跨越 60 分及格线时返回 `crossesPassThreshold:true`。 |
| 教务 POST | `/admin/grade-reviews/{id}/decide` | `{version,approve,reason}` 记录核准或驳回，状态为 `APPROVED` 或 `REJECTED`。正式成绩此时仍为原值。 |
| 授课教师 POST | `/teacher/grade-reviews/{id}/publish` | `{version}` 对核准建议重新发布。写入正式分数、修订号、审计与通知后进入 `PUBLISHED`。源成绩已变化时拒绝旧申请。 |
| 三角色 GET | `/grade-reviews`、`/grade-reviews/{id}` | 学生只见本人，教师只见所属班级，教务按权限可见全体。列表可筛 `termId,state`；详情含按复核步骤排列的 `history`。 |

## 志愿、排课与结算

| 角色和方法 | 路径（相对 `/api/v3`） | 业务含义 |
|---|---|---|
| 教务 POST | `/admin/planning/rounds/{roundId}/commit` | 在草稿轮次公布随机种子的 SHA-256 承诺。 |
| 学生 GET / PUT | `/student/preferences/{roundId}` | 开放收集时写入 `{version,sectionIds:[...]}`（顺序即志愿顺位）。本人可查询自己的志愿和分配。 |
| 教务 POST | `/admin/planning/allocations/preview` | `{roundId}`，封存收集后生成分配预览、快照摘要和可核验的已揭示种子。 |
| 教务 GET / PUT | `/admin/planning/resources/{termId}` | `{version,rooms:[{id,capacity}],unavailable:[{teacherId,weekday,startPeriod,endPeriod,startWeek,endWeek}]}`，维护有版本的正式教室和教师禁排时段。 |
| 教务 POST | `/admin/planning/schedules/preview` | 输入学期、教学班与备选时段。返回排班结果和求解状态。即使有部分可行解也不能发布不完整批次。 |
| 教务 GET / POST | `/admin/planning/runs`、`/admin/planning/runs/{id}`、`/admin/planning/runs/{id}/publish` | 查询历史与详情；发布使用 `{snapshotHash}` 及 `Idempotency-Key`。过期快照拒绝发布。 |
| 教务 GET | `/admin/analytics/drill?termId=...&metric=...` | `CAPACITY` 为已选/容量，`WAITLIST` 为有效队列人数/容量，`GRADES` 为未及格人数/已有正式成绩人数；每行对应一个教学班。 |
| 教务 GET | `/admin/analytics/settlement?termId=...&metric=...` | `UNASSIGNED` 按轮次计算未获教学班的人次，`PREFERENCE` 按顺位计算命中比例，`WAIT_TIME` 计算成功转正记录的平均等待秒数。响应包含分子、分母、单位、定义和分页明细。 |

可在独立验收学期复现上述链条。参见 [三角色 HTTP 验收脚本](../scripts/http_v3_acceptance.py) 和 [V3 部署说明](../DEPLOYMENT.md)。

> 下文保留 V2 兼容接口。数据库名称、版本、部署要求以 V3 部署说明为准。

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
- `GET /catalog?termId=&q=&courseId=&department=&availableOnly=` returns a page of published sections with meetings and prerequisites. The optional `courseId` is exact and applied before counting or paging. A curriculum link for course `C` excludes `C2`. The existing `q` filter is a substring search and can be combined with `courseId`. A term without a matching offering returns an empty page.
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
