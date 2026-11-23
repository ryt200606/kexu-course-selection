-- Version 1. Dedicated course_app_v2 only. Executed by the owner CLI, never by serve.
CREATE TABLE cs2_schema_version (
    version INTEGER PRIMARY KEY,
    source_version VARCHAR(40) NOT NULL,
    schema_sha256 VARCHAR(64) NOT NULL,
    installed_at TIMESTAMP NOT NULL
);

CREATE TABLE cs2_student (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(80) NOT NULL,
    state VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_student_state CHECK(state IN ('ACTIVE','SUSPENDED'))
);

CREATE TABLE cs2_teacher (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(80) NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE cs2_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(80) UNIQUE NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    student_id VARCHAR(32) UNIQUE REFERENCES cs2_student(id),
    teacher_id VARCHAR(32) UNIQUE REFERENCES cs2_teacher(id),
    enabled INTEGER DEFAULT 1 NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_user_role CHECK(role IN ('ADMIN','TEACHER','STUDENT')),
    CONSTRAINT cs2_user_binding CHECK((role='STUDENT' AND student_id IS NOT NULL AND teacher_id IS NULL) OR (role='TEACHER' AND teacher_id IS NOT NULL AND student_id IS NULL) OR (role='ADMIN' AND student_id IS NULL AND teacher_id IS NULL))
);

CREATE TABLE cs2_term (
    id VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    state VARCHAR(20) DEFAULT 'PLANNING' NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_term_state CHECK(state IN ('PLANNING','ACTIVE','ARCHIVED'))
);

CREATE TABLE cs2_round (
    id VARCHAR(32) PRIMARY KEY,
    term_id VARCHAR(32) NOT NULL REFERENCES cs2_term(id),
    name VARCHAR(100) NOT NULL,
    state VARCHAR(16) DEFAULT 'DRAFT' NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    withdrawal_ends_at TIMESTAMP NOT NULL,
    credit_limit INTEGER NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_round_state CHECK(state IN ('DRAFT','OPEN','CLOSED')),
    CONSTRAINT cs2_round_dates CHECK(starts_at<ends_at AND ends_at<=withdrawal_ends_at),
    CONSTRAINT cs2_round_credits CHECK(credit_limit BETWEEN 1 AND 60)
);

CREATE TABLE cs2_course (
    id VARCHAR(32) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    department VARCHAR(80) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL
);

CREATE TABLE cs2_offering (
    id VARCHAR(32) PRIMARY KEY,
    course_id VARCHAR(32) NOT NULL REFERENCES cs2_course(id),
    term_id VARCHAR(32) NOT NULL REFERENCES cs2_term(id),
    credits INTEGER NOT NULL,
    state VARCHAR(20) DEFAULT 'DRAFT' NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_offering_course_term UNIQUE(course_id,term_id),
    CONSTRAINT cs2_offering_credits CHECK(credits BETWEEN 1 AND 10),
    CONSTRAINT cs2_offering_state CHECK(state IN ('DRAFT','PUBLISHED'))
);

CREATE TABLE cs2_prerequisite (
    offering_id VARCHAR(32) NOT NULL REFERENCES cs2_offering(id),
    course_id VARCHAR(32) NOT NULL REFERENCES cs2_course(id),
    min_grade DECIMAL(5,2) DEFAULT 60 NOT NULL,
    PRIMARY KEY(offering_id,course_id),
    CONSTRAINT cs2_prereq_grade CHECK(min_grade BETWEEN 0 AND 100)
);

CREATE TABLE cs2_section (
    id VARCHAR(32) PRIMARY KEY,
    offering_id VARCHAR(32) NOT NULL REFERENCES cs2_offering(id),
    teacher_id VARCHAR(32) NOT NULL REFERENCES cs2_teacher(id),
    capacity INTEGER NOT NULL,
    enrolled_count INTEGER DEFAULT 0 NOT NULL,
    state VARCHAR(20) DEFAULT 'DRAFT' NOT NULL,
    grade_state VARCHAR(16) DEFAULT 'DRAFT' NOT NULL,
    grade_revision INTEGER DEFAULT 0 NOT NULL,
    last_published_at TIMESTAMP,
    promotion_pending INTEGER DEFAULT 0 NOT NULL,
    next_queue_no BIGINT DEFAULT 1 NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT cs2_section_capacity CHECK(capacity BETWEEN 1 AND 1000),
    CONSTRAINT cs2_section_count CHECK(enrolled_count BETWEEN 0 AND capacity),
    CONSTRAINT cs2_section_offering_pair UNIQUE(id,offering_id),
    CONSTRAINT cs2_section_state CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT cs2_grade_state CHECK(grade_state IN ('DRAFT','PUBLISHED'))
);

CREATE TABLE cs2_meeting (
    id VARCHAR(36) PRIMARY KEY,
    section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
    weekday INTEGER NOT NULL,
    start_period INTEGER NOT NULL,
    end_period INTEGER NOT NULL,
    start_week INTEGER NOT NULL,
    end_week INTEGER NOT NULL,
    room VARCHAR(80) NOT NULL,
    CONSTRAINT cs2_meeting_day CHECK(weekday BETWEEN 1 AND 7),
    CONSTRAINT cs2_meeting_period CHECK(start_period BETWEEN 1 AND 14 AND end_period BETWEEN start_period AND 14),
    CONSTRAINT cs2_meeting_week CHECK(start_week BETWEEN 1 AND 30 AND end_week BETWEEN start_week AND 30)
);

CREATE TABLE cs2_student_term (
    student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id),
    term_id VARCHAR(32) NOT NULL REFERENCES cs2_term(id),
    selected_credits INTEGER DEFAULT 0 NOT NULL,
    credit_limit INTEGER NOT NULL,
    PRIMARY KEY(student_id,term_id),
    CONSTRAINT cs2_st_credits CHECK(selected_credits>=0),
    CONSTRAINT cs2_st_limit CHECK(credit_limit BETWEEN 1 AND 60)
);

CREATE TABLE cs2_enrollment (
    id VARCHAR(36) PRIMARY KEY,
    student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id),
    offering_id VARCHAR(32) NOT NULL REFERENCES cs2_offering(id),
    section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
    state VARCHAR(20) DEFAULT 'ENROLLED' NOT NULL,
    grade DECIMAL(5,2),
    published_grade DECIMAL(5,2),
    grade_version BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT cs2_enrollment_course UNIQUE(student_id,offering_id),
    CONSTRAINT cs2_enrollment_section_offering FOREIGN KEY(section_id,offering_id) REFERENCES cs2_section(id,offering_id),
    CONSTRAINT cs2_enrollment_state CHECK(state IN ('ENROLLED','WITHDRAWN')),
    CONSTRAINT cs2_enrollment_grade CHECK(grade IS NULL OR grade BETWEEN 0 AND 100),
    CONSTRAINT cs2_published_grade CHECK(published_grade IS NULL OR published_grade BETWEEN 0 AND 100)
);

CREATE TABLE cs2_waitlist (
    id VARCHAR(36) PRIMARY KEY,
    student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id),
    section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
    round_id VARCHAR(32) NOT NULL REFERENCES cs2_round(id),
    queue_no BIGINT NOT NULL,
    state VARCHAR(16) DEFAULT 'WAITING' NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT cs2_waitlist_queue UNIQUE(section_id,round_id,queue_no),
    CONSTRAINT cs2_waitlist_state CHECK(state IN ('WAITING','ENROLLED','CANCELLED','SKIPPED','EXPIRED'))
);

CREATE TABLE cs2_audit (
    id VARCHAR(36) PRIMARY KEY,
    actor_id VARCHAR(36),
    actor_name VARCHAR(100) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_id VARCHAR(100) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    term_id VARCHAR(32),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE cs2_notification (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES cs2_user(id),
    title VARCHAR(120) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);

CREATE TABLE cs2_idempotency (
    user_id VARCHAR(36) NOT NULL REFERENCES cs2_user(id),
    operation VARCHAR(100) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(user_id,operation,request_key)
);

CREATE INDEX cs2_round_term_idx ON cs2_round(term_id,state);
CREATE INDEX cs2_section_offering_idx ON cs2_section(offering_id);
CREATE INDEX cs2_meeting_section_idx ON cs2_meeting(section_id);
CREATE INDEX cs2_enrollment_section_idx ON cs2_enrollment(section_id,state);
CREATE INDEX cs2_enrollment_student_idx ON cs2_enrollment(student_id,state);
CREATE INDEX cs2_waitlist_fifo_idx ON cs2_waitlist(section_id,state,round_id,queue_no);
CREATE INDEX cs2_waitlist_student_idx ON cs2_waitlist(student_id,state);
CREATE INDEX cs2_audit_time_idx ON cs2_audit(created_at);
CREATE INDEX cs2_notify_user_idx ON cs2_notification(user_id,created_at);

-- Read-only relational projections. API callers still supply their own identity/term filters.
CREATE VIEW cs2_v_student_timetable AS
SELECT e.student_id,s.id AS section_id,o.course_id,c.title AS course_title,
       o.term_id,t.name AS teacher_name,m.room,m.weekday,m.start_period,
       m.end_period,m.start_week,m.end_week
FROM cs2_enrollment e
JOIN cs2_section s ON s.id=e.section_id
JOIN cs2_offering o ON o.id=e.offering_id
JOIN cs2_course c ON c.id=o.course_id
JOIN cs2_teacher t ON t.id=s.teacher_id
JOIN cs2_meeting m ON m.section_id=s.id
WHERE e.state='ENROLLED';

-- A correction draft does not replace the previous formally published grade.
CREATE VIEW cs2_v_transcript AS
SELECT e.id AS enrollment_id,e.student_id,st.name AS student_name,e.section_id,
       o.course_id,c.title AS course_title,o.term_id,o.credits,e.published_grade,
       s.grade_revision,s.last_published_at
FROM cs2_enrollment e
JOIN cs2_student st ON st.id=e.student_id
JOIN cs2_offering o ON o.id=e.offering_id
JOIN cs2_course c ON c.id=o.course_id
JOIN cs2_section s ON s.id=e.section_id
WHERE e.state='ENROLLED' AND e.published_grade IS NOT NULL;

-- Correlated aggregates avoid multiplying enrollment counts by meeting or queue rows.
CREATE VIEW cs2_v_term_summary AS
SELECT tm.id AS term_id,tm.name AS term_name,
       (SELECT COUNT(DISTINCT o.course_id) FROM cs2_section s
        JOIN cs2_offering o ON o.id=s.offering_id
        WHERE o.term_id=tm.id) AS course_count,
       (SELECT COUNT(*) FROM cs2_section s
        JOIN cs2_offering o ON o.id=s.offering_id
        WHERE o.term_id=tm.id) AS section_count,
       (SELECT COALESCE(SUM(s.capacity),0) FROM cs2_section s
        JOIN cs2_offering o ON o.id=s.offering_id
        WHERE o.term_id=tm.id) AS total_capacity,
       (SELECT COALESCE(SUM(s.enrolled_count),0) FROM cs2_section s
        JOIN cs2_offering o ON o.id=s.offering_id
        WHERE o.term_id=tm.id) AS occupied_seats,
       (SELECT COUNT(*) FROM cs2_enrollment e
        JOIN cs2_offering o ON o.id=e.offering_id
        WHERE o.term_id=tm.id AND e.state='ENROLLED') AS enrollment_count,
       (SELECT COUNT(*) FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id
        WHERE r.term_id=tm.id AND w.state='WAITING' AND r.state='OPEN'
          AND r.starts_at<=CURRENT_TIMESTAMP AND r.ends_at>CURRENT_TIMESTAMP) AS waiting_count
FROM cs2_term tm;
