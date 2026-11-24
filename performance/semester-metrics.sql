-- Run in gsql with -v term_id=... ; a row is returned for each metric even when its denominator is zero.
WITH sections AS (
  SELECT s.id,s.capacity FROM cs2_section s JOIN cs2_offering o ON o.id=s.offering_id
  WHERE o.term_id=:'term_id'
), enrolled AS (
  SELECT e.student_id,e.section_id,e.published_grade FROM cs2_enrollment e
  JOIN sections s ON s.id=e.section_id WHERE e.state='ENROLLED'
), published AS (
  SELECT r.id,r.round_id FROM cs3_planning_run r WHERE r.term_id=:'term_id'
    AND r.kind='ALLOCATION' AND r.state='PUBLISHED'
), ballots AS (
  SELECT p.id,c.student_id,c.section_id FROM published p JOIN cs3_preference_choice c ON c.round_id=p.round_id
), participants AS (
  SELECT DISTINCT id,student_id FROM ballots
), awards AS (
  SELECT a.run_id,a.student_id,a.section_id FROM cs3_allocation_award a JOIN published p ON p.id=a.run_id
), completed_queue AS (
  SELECT EXTRACT(EPOCH FROM (w.updated_at-w.created_at)) AS seconds
  FROM cs2_waitlist w JOIN sections s ON s.id=w.section_id WHERE w.state='ENROLLED'
), current_queue AS (
  SELECT w.student_id FROM cs2_waitlist w JOIN sections s ON s.id=w.section_id JOIN cs2_round r ON r.id=w.round_id
  WHERE w.state='WAITING' AND r.state='OPEN' AND r.starts_at<=CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP<r.ends_at
)
SELECT 'CAPACITY' AS metric,(SELECT COUNT(*) FROM enrolled)::numeric AS numerator,
       (SELECT COALESCE(SUM(capacity),0) FROM sections)::numeric AS denominator
UNION ALL SELECT 'WAITLIST',(SELECT COUNT(*) FROM current_queue),(SELECT COALESCE(SUM(capacity),0) FROM sections)
UNION ALL SELECT 'GRADES',(SELECT COUNT(*) FROM enrolled WHERE published_grade<60),
                          (SELECT COUNT(published_grade) FROM enrolled)
UNION ALL SELECT 'UNASSIGNED',(SELECT COUNT(*) FROM participants b WHERE NOT EXISTS
  (SELECT 1 FROM awards a WHERE a.run_id=b.id AND a.student_id=b.student_id)),(SELECT COUNT(*) FROM participants)
UNION ALL SELECT 'PREFERENCE',(SELECT COUNT(*) FROM ballots b WHERE EXISTS
  (SELECT 1 FROM awards a WHERE a.run_id=b.id AND a.student_id=b.student_id AND a.section_id=b.section_id)),
  (SELECT COUNT(*) FROM ballots)
UNION ALL SELECT 'WAIT_TIME',(SELECT COALESCE(SUM(seconds),0) FROM completed_queue),(SELECT COUNT(*) FROM completed_queue)
ORDER BY 1;

