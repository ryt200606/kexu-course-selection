-- Candidates measured in isolation; excluded from the shipping schema.
CREATE INDEX cs3_section_roster ON cs2_enrollment(section_id,state,student_id);
CREATE INDEX cs3_offering_state ON cs2_enrollment(offering_id,state);
