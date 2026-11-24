-- Idempotent curriculum for the fictional autumn cohort. No matching course fixture means no seed.
INSERT INTO cs3_curriculum(id,cohort,name,revision)
SELECT 'DEMO-CS-2026-R1','2026级演示班','计算机科学与技术（演示培养方案）',1
WHERE (SELECT COUNT(*) FROM cs2_course WHERE id IN ('C001','C002','C003','C005','C006','C007'))=6
AND NOT EXISTS (SELECT 1 FROM cs3_curriculum WHERE cohort='2026级演示班' AND revision=1);
INSERT INTO cs3_module(curriculum_id,module_no,name,required_credits)
SELECT id,1,'专业基础',7 FROM cs3_curriculum p WHERE p.id='DEMO-CS-2026-R1'
AND NOT EXISTS (SELECT 1 FROM cs3_module m WHERE m.curriculum_id=p.id AND m.module_no=1);
INSERT INTO cs3_module(curriculum_id,module_no,name,required_credits)
SELECT id,2,'系统与工程',5 FROM cs3_curriculum p WHERE p.id='DEMO-CS-2026-R1'
AND NOT EXISTS (SELECT 1 FROM cs3_module m WHERE m.curriculum_id=p.id AND m.module_no=2);
INSERT INTO cs3_module_course(curriculum_id,module_no,course_id)
SELECT p.id,CASE WHEN c.id IN ('C002','C005','C006') THEN 1 ELSE 2 END,c.id FROM cs3_curriculum p
CROSS JOIN cs2_course c WHERE p.id='DEMO-CS-2026-R1' AND c.id IN ('C001','C002','C003','C005','C006','C007')
AND NOT EXISTS (SELECT 1 FROM cs3_module_course mc WHERE mc.curriculum_id=p.id AND mc.course_id=c.id);
INSERT INTO cs3_curriculum_binding(student_id,curriculum_id)
SELECT s.id,p.id FROM cs2_student s CROSS JOIN cs3_curriculum p WHERE p.id='DEMO-CS-2026-R1'
AND s.name LIKE '%（演示）' AND s.id BETWEEN 'S1001' AND 'S1120'
AND NOT EXISTS (SELECT 1 FROM cs3_curriculum_binding b WHERE b.student_id=s.id);
