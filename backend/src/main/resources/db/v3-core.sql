-- Shared scan position; a failing section cannot monopolise the first batch.
CREATE TABLE cs3_promotion_cursor (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_section VARCHAR(32)
);
INSERT INTO cs3_promotion_cursor(id,last_section) VALUES(1,NULL);
