-- SeatWave's shared database (Module 10): every instance reads and writes here, so no instance
-- keeps a hold in memory. PostgreSQL runs this once, when its data volume is new
-- (/docker-entrypoint-initdb.d); the tests run the same file on H2 in its PostgreSQL mode.

-- Module 1's tables, with times as timestamps instead of epoch milliseconds.
CREATE TABLE events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The status column is the single source of truth for a seat. Java never decides alone.
CREATE TABLE seats (
  event_id BIGINT NOT NULL REFERENCES events (event_id),
  seat_label VARCHAR(16) NOT NULL,
  section VARCHAR(4) NOT NULL,
  status VARCHAR(12) NOT NULL,
  hold_id VARCHAR(40),
  hold_customer VARCHAR(64),
  hold_expires_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (event_id, seat_label),
  CONSTRAINT seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED'))
);

-- One row per hold that was granted. The unique key makes a retried POST (Module 7's
-- Idempotency-Key) find the hold it already made, on whichever instance the retry lands.
CREATE TABLE holds (
  hold_id VARCHAR(40) PRIMARY KEY,
  idempotency_key VARCHAR(66) NOT NULL,
  body_sha256 CHAR(64) NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT one_hold_per_key UNIQUE (idempotency_key)
);

-- Module 1's arena on-sale: event 1 with 20,000 seats in 20 sections (A to T) of 1,000. The start
-- time is the one Module 1 printed and Modules 7 and 9 wrote in ISO-8601.
INSERT INTO events (event_id, name, starts_at) VALUES (1, 'Arena Night', TIMESTAMP WITH TIME ZONE '2026-12-08 20:20:51.783+00');

INSERT INTO seats (event_id, seat_label, section, status)
SELECT 1, chr(65 + (n - 1) / 1000) || '-' || (((n - 1) % 1000) + 1), chr(65 + (n - 1) / 1000), 'AVAILABLE'
FROM generate_series(1, 20000) AS g(n);
