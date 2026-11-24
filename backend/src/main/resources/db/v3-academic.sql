-- Published curriculum revisions are append-only. A student binds to exactly one revision.
CREATE TABLE cs3_curriculum_gate (id INTEGER PRIMARY KEY);
INSERT INTO cs3_curriculum_gate(id) VALUES(1);
CREATE TABLE cs3_curriculum (
    id VARCHAR(36) PRIMARY KEY, cohort VARCHAR(80) NOT NULL, name VARCHAR(100) NOT NULL,
    revision INTEGER NOT NULL CHECK(revision>0), UNIQUE(cohort,revision)
);
CREATE TABLE cs3_module (
    curriculum_id VARCHAR(36) NOT NULL REFERENCES cs3_curriculum(id),
    module_no INTEGER NOT NULL, name VARCHAR(100) NOT NULL,
    required_credits INTEGER NOT NULL CHECK(required_credits BETWEEN 1 AND 300),
    PRIMARY KEY(curriculum_id,module_no), UNIQUE(curriculum_id,name)
);
CREATE TABLE cs3_module_course (
    curriculum_id VARCHAR(36) NOT NULL, module_no INTEGER NOT NULL,
    course_id VARCHAR(32) NOT NULL REFERENCES cs2_course(id),
    PRIMARY KEY(curriculum_id,course_id),
    FOREIGN KEY(curriculum_id,module_no) REFERENCES cs3_module(curriculum_id,module_no)
);
CREATE TABLE cs3_curriculum_binding (
    student_id VARCHAR(32) PRIMARY KEY REFERENCES cs2_student(id),
    curriculum_id VARCHAR(36) NOT NULL REFERENCES cs3_curriculum(id)
);
CREATE TABLE cs3_plan (
    student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id), term_id VARCHAR(32) NOT NULL REFERENCES cs2_term(id),
    revision BIGINT NOT NULL CHECK(revision>0), PRIMARY KEY(student_id,term_id)
);
CREATE TABLE cs3_plan_section (
    student_id VARCHAR(32) NOT NULL, term_id VARCHAR(32) NOT NULL, side CHAR(1) NOT NULL CHECK(side IN ('A','B')),
    position_no INTEGER NOT NULL, section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
    PRIMARY KEY(student_id,term_id,side,position_no), UNIQUE(student_id,term_id,side,section_id),
    FOREIGN KEY(student_id,term_id) REFERENCES cs3_plan(student_id,term_id)
);
CREATE TABLE cs3_grade_review (
    id VARCHAR(36) PRIMARY KEY, enrollment_id VARCHAR(36) NOT NULL REFERENCES cs2_enrollment(id),
    original_grade DECIMAL(5,2) NOT NULL CHECK(original_grade BETWEEN 0 AND 100), original_version BIGINT NOT NULL,
    proposed_grade DECIMAL(5,2) CHECK(proposed_grade BETWEEN 0 AND 100), student_reason VARCHAR(500) NOT NULL,
    teacher_reason VARCHAR(500), admin_reason VARCHAR(500), state VARCHAR(18) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT cs3_review_state CHECK(state IN ('REQUESTED','RECOMMENDED','APPROVED','REJECTED','PUBLISHED','STALE'))
);
CREATE INDEX cs3_review_enrollment ON cs3_grade_review(enrollment_id,state);
CREATE INDEX cs3_review_state ON cs3_grade_review(state,created_at);
