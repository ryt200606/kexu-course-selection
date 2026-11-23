\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';
SELECT 1/CASE WHEN current_database()='course_app_v2_perf_test' THEN 1 ELSE 0 END;
SELECT version();
SELECT 'database|'||current_database();
SELECT 'MEASUREMENT_BEGIN';
SELECT 'planner_defaults|'||current_setting('enable_indexscan')||'|'||current_setting('enable_bitmapscan');
SELECT 1/CASE WHEN current_setting('enable_indexscan')='on' AND current_setting('enable_bitmapscan')='on' THEN 1 ELSE 0 END;
ANALYZE cs2_enrollment;
BEGIN;
SELECT 1/CASE WHEN (SELECT count(*) FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace ns ON ns.oid=idx.relnamespace JOIN pg_class t ON t.oid=i.indrelid WHERE ns.nspname='public' AND idx.relname='cs2_enrollment_section_idx' AND t.relname='cs2_enrollment' AND NOT i.indisunique AND NOT i.indisprimary AND i.indisvalid AND NOT EXISTS(SELECT 1 FROM pg_constraint c WHERE c.conindid=i.indexrelid))=1 THEN 1 ELSE 0 END;
DROP INDEX public.cs2_enrollment_section_idx;
SELECT 'INDEX_DROP_COMPLETED';
SELECT 'index_catalog|without_section_index|'||count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';
SELECT 'result_rows|without_section_index|'||count(*) FROM (SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id) measured_result;
SELECT 'warmup|without_section_index';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|1';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|2';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|3';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|4';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|5';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|6';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|without_section_index|7';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
CREATE INDEX cs2_enrollment_section_idx ON cs2_enrollment(section_id,state);
SELECT 'INDEX_CREATE_COMPLETED';
SELECT 'index_catalog|with_section_index|'||count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';
SELECT 'result_rows|with_section_index|'||count(*) FROM (SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id) measured_result;
SELECT 'warmup|with_section_index';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|1';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|2';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|3';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|4';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|5';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|6';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
SELECT 'measurement|with_section_index|7';
EXPLAIN ANALYZE SELECT e.id,e.state,c.title,o.credits,s.name AS student_name,t.name AS teacher_name
FROM cs2_enrollment e JOIN cs2_student s ON s.id=e.student_id
JOIN cs2_section sec ON sec.id=e.section_id JOIN cs2_offering o ON o.id=sec.offering_id
JOIN cs2_course c ON c.id=o.course_id JOIN cs2_teacher t ON t.id=sec.teacher_id
WHERE e.section_id='PS123' AND e.state='ENROLLED' ORDER BY e.id;
COMMIT;
SELECT 'index_catalog|restored|'||count(*) FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';
SELECT 'index_definition|restored|'||indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='cs2_enrollment' AND indexname='cs2_enrollment_section_idx';
SELECT 'MEASUREMENT_END';
