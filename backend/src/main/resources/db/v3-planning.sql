-- Owner-applied migration. Bootstrap grants only the operational privileges required for these tables.
CREATE TABLE cs3_preference_round (
  round_id VARCHAR(32) PRIMARY KEY REFERENCES cs2_round(id),
  seed_secret VARCHAR(64) NOT NULL,
  seed_commitment VARCHAR(64) NOT NULL,
  phase VARCHAR(16) NOT NULL CHECK (phase IN ('COLLECTING','FROZEN','PUBLISHED')),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE cs3_preference_form (
  round_id VARCHAR(32) NOT NULL REFERENCES cs3_preference_round(round_id),
  student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id),
  version BIGINT NOT NULL CHECK (version > 0),
  PRIMARY KEY(round_id,student_id)
);
CREATE TABLE cs3_preference_choice (
  round_id VARCHAR(32) NOT NULL,
  student_id VARCHAR(32) NOT NULL,
  section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
  choice_rank INTEGER NOT NULL CHECK (choice_rank BETWEEN 1 AND 8),
  PRIMARY KEY(round_id,student_id,choice_rank),
  UNIQUE(round_id,student_id,section_id),
  FOREIGN KEY(round_id,student_id) REFERENCES cs3_preference_form(round_id,student_id)
);
CREATE TABLE cs3_planning_run (
  id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('ALLOCATION','SCHEDULE')),
  term_id VARCHAR(32) NOT NULL REFERENCES cs2_term(id),
  round_id VARCHAR(32) REFERENCES cs3_preference_round(round_id),
  state VARCHAR(16) NOT NULL CHECK (state IN ('PREVIEW','PUBLISHED')),
  snapshot_hash VARCHAR(64) NOT NULL,
  input_json TEXT NOT NULL,
  result_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMP
);
CREATE INDEX cs3_planning_run_term ON cs3_planning_run(term_id,created_at);
CREATE TABLE cs3_allocation_award (
  run_id VARCHAR(36) NOT NULL REFERENCES cs3_planning_run(id),
  student_id VARCHAR(32) NOT NULL REFERENCES cs2_student(id),
  section_id VARCHAR(32) NOT NULL REFERENCES cs2_section(id),
  PRIMARY KEY(run_id,student_id,section_id)
);
CREATE TABLE cs3_resource_version (
  term_id VARCHAR(32) PRIMARY KEY REFERENCES cs2_term(id),
  version BIGINT NOT NULL CHECK(version>0)
);
CREATE TABLE cs3_room (
  term_id VARCHAR(32) NOT NULL REFERENCES cs3_resource_version(term_id),
  room VARCHAR(80) NOT NULL, capacity INTEGER NOT NULL CHECK(capacity>0),
  PRIMARY KEY(term_id,room)
);
CREATE TABLE cs3_teacher_block (
  term_id VARCHAR(32) NOT NULL REFERENCES cs3_resource_version(term_id),
  teacher_id VARCHAR(32) NOT NULL REFERENCES cs2_teacher(id),
  weekday INTEGER NOT NULL CHECK(weekday BETWEEN 1 AND 7),
  start_week INTEGER NOT NULL CHECK(start_week BETWEEN 1 AND 30),
  end_week INTEGER NOT NULL CHECK(end_week BETWEEN start_week AND 30),
  start_period INTEGER NOT NULL CHECK(start_period BETWEEN 1 AND 14),
  end_period INTEGER NOT NULL CHECK(end_period BETWEEN start_period AND 14),
  PRIMARY KEY(term_id,teacher_id,weekday,start_week,end_week,start_period,end_period)
);

