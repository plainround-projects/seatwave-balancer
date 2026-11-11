# SQL and `db/schema.sql`, from zero

## What it is

**SQL** (Structured Query Language) is the language you use to talk to a relational database. A **relational database** keeps its data in **tables**. A table is a grid: each **column** has a name and a kind of value, and each **row** is one record. `db/schema.sql` is a text file of SQL statements. A **schema** is the shape of a database: which tables exist, which columns they have, and which rules the data must obey. This file creates the three tables of SeatWave and fills two of them.

Terms you need.

- A **statement** is one complete instruction in SQL. It ends with a semicolon. `CREATE TABLE` makes a table. `INSERT` adds rows. `SELECT` reads rows. `UPDATE` changes rows.
- A **data type** says what kind of value a column holds. `BIGINT` is a whole number of 64 bits (up to about 9 quintillion). `VARCHAR(120)` is text of at most 120 characters. `CHAR(64)` is text of exactly 64 characters (shorter text is padded with spaces). `TIMESTAMP WITH TIME ZONE` is a date and time that stands for one exact moment, whatever zone you are in.
- **`NOT NULL`** says the column must always have a value. `NULL` is SQL's word for "no value". A column without `NOT NULL` may be empty.
- A **constraint** is a rule the database enforces on every change. A statement that breaks a rule fails, and nothing is changed. The rules here are: primary key, foreign key, check and unique.
- A **primary key** is a column (or group of columns) whose value identifies a row and is never repeated and never `NULL`.
- A **foreign key** says "this value must exist in another table's key". It stops a row from pointing at something that is not there.
- A **check** constraint says "this expression must be true for every row".
- A **unique** constraint says "no two rows may have the same value here".
- An **index** is a lookup structure the database keeps beside a table so it can find a row without reading every row. A primary key and a unique constraint each get an index automatically.
- A **comment** in SQL starts with two hyphens, `--`, and runs to the end of the line. The database ignores it.

## Why this repository uses it

Module 10 puts three SeatWave instances behind a balancer. Before, each instance would have kept the holds in its own memory, and a seat held through instance 1 would have been free on instance 2. So every hold now lives in one shared database. The tables are the shared truth:

- `events` and `seats` are Module 1's arena on sale: one event with 20,000 seats.
- `seats.status` is "the single source of truth for a seat" (the schema's own words). Java code asks the database; it never decides alone.
- `holds` records every hold that was granted, with the `Idempotency-Key` of the request that made it. A unique rule on that key is what makes a retried request find the hold it already made, on whichever instance the retry lands.

## Where it appears

| File | What it does with SQL |
|---|---|
| `db/schema.sql` | Creates the three tables and fills `events` and `seats`. |
| `compose.yaml` | Mounts the file into PostgreSQL's start-up folder; PostgreSQL runs it once, when its data volume is new. |
| `seatwave/JdbcHoldStore.java` | Runs `SELECT`, `UPDATE` and `INSERT` against these tables (see `java-seatwave-walkthrough-3.md`). |
| `seatwave/TestDatabase.java` | Runs the same file on H2 before each test (see `tests-seatwave-walkthrough-1.md`). |
| `scripts/common.sh` | `give_seats_back` runs one `UPDATE` on `seats` through `psql` before and after every lab run: every `HELD` seat becomes `AVAILABLE` again with its hold columns emptied (see `shell-scripts-walkthrough.md`). |

## How it works underneath

**Running the file.** PostgreSQL's image has a folder, `/docker-entrypoint-initdb.d`. On the first start of an empty data volume, it runs every `.sql` file in that folder, in name order, once. After that, the data lives in the volume and the file is not run again. So to change the schema in a running stack you must remove the volume (`docker compose down -v`), or the old tables stay. The tests do the opposite: each test makes a fresh in-memory H2 database and runs the same file on it (see `postgresql-and-h2.md`).

**Order matters.** `seats` points at `events` with a foreign key, so `events` must exist first. The rows follow the tables: the event first, then the seats that point at it.

**One statement, many rows.** The last statement makes 20,000 rows with a single `INSERT ... SELECT`. Inside, `generate_series(1, 20000)` produces the numbers 1 to 20,000 as if they were a table, and the `SELECT` turns each number into a seat.

**Rules at the door.** A `CHECK`, a `FOREIGN KEY` and a `UNIQUE` are checked by the database on every `INSERT` and `UPDATE`, whatever program sent them. If a bug in the Java code ever tried to write a seat with a status of `BROKEN`, the database would refuse it.

## Walkthrough of this repository's use

### The header

```sql
-- SeatWave's shared database (Module 10): every instance reads and writes here, so no instance
-- keeps a hold in memory. PostgreSQL runs this once, when its data volume is new
-- (/docker-entrypoint-initdb.d); the tests run the same file on H2 in its PostgreSQL mode.
```

Three comment lines. They say what the file is for and how it runs: every instance reads and writes here, so no instance keeps a hold in memory. PostgreSQL runs the file once, "when its data volume is new", from `/docker-entrypoint-initdb.d`; the tests run "the same file on H2 in its PostgreSQL mode". Keeping one file for both is how the project makes sure the tests run on the schema that production uses.

### The `events` table

```sql
-- Module 1's tables, with times as timestamps instead of epoch milliseconds.
CREATE TABLE events (
  event_id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  starts_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

- The comment: "Module 1's tables, with times as timestamps instead of epoch milliseconds." Module 1 stored times as a number of milliseconds since 1970; here a real date-time type is used.
- `CREATE TABLE events ( ... );` makes a table called `events` with three columns.
- `event_id BIGINT PRIMARY KEY`: the event's number, a whole number, and the primary key: unique and never empty. Module 1's one event is 1.
- `name VARCHAR(120) NOT NULL`: the event's name, up to 120 characters, always present.
- `starts_at TIMESTAMP WITH TIME ZONE NOT NULL`: when the event starts. With a time zone, PostgreSQL stores one exact moment, so a reader in any zone sees the same instant.

Each column definition ends with a comma, except the last. The closing bracket and the semicolon end the statement.

### The `seats` table

```sql
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
```

- The comment is the key design line: "The status column is the single source of truth for a seat. Java never decides alone." A program may cache what it reads, but the seat's state is whatever the row says now.
- `event_id BIGINT NOT NULL REFERENCES events (event_id)`: the event this seat belongs to. `REFERENCES events (event_id)` is a **foreign key**: the value must exist in `events.event_id`. A seat for event 2 cannot be added while event 2 does not exist. My check: inserting a seat for event 2 failed with `Referential integrity constraint violation`.
- `seat_label VARCHAR(16) NOT NULL`: the seat's label, such as `A-7`.
- `section VARCHAR(4) NOT NULL`: the section letter, `A` to `T`.
- `status VARCHAR(12) NOT NULL`: one of three words, enforced below.
- `hold_id VARCHAR(40)`, `hold_customer VARCHAR(64)`, `hold_expires_at TIMESTAMP WITH TIME ZONE`: who holds the seat and until when. They may be empty (`NULL`), because a free seat has no hold. A UUID is 36 characters, so 40 leaves room.
- `PRIMARY KEY (event_id, seat_label)`: a **composite** primary key. The pair of values identifies the seat: seat `A-7` of event 1. The same label may appear for another event. The database builds an index on the pair, so `WHERE event_id = ? AND seat_label = ?` is a direct lookup, which the Java `UPDATE` relies on.
- `CONSTRAINT seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED'))`: a named **check**. `IN (...)` means "equal to one of these". A status such as `BROKEN` is refused. The name `seat_status` appears in the error message, which makes failures easy to read. In my run, `INSERT ... 'BROKEN'` failed with `Check constraint violation: "seat_status"`.

The three statuses are the whole life of a seat: `AVAILABLE` (free), `HELD` (kept for a customer until `hold_expires_at`), `CONFIRMED` (sold). This project only moves a seat from `AVAILABLE` to `HELD`; confirming a purchase is not built here.

### The `holds` table

```sql
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
```

- The comment explains the purpose of the unique key: it "makes a retried POST (Module 7's Idempotency-Key) find the hold it already made, on whichever instance the retry lands".
- `hold_id VARCHAR(40) PRIMARY KEY`: the hold's UUID.
- `idempotency_key VARCHAR(66) NOT NULL`: the client's key. It is stored with its quotes (an RFC 9651 string), so 64 characters plus two quotes is 66.
- `body_sha256 CHAR(64) NOT NULL`: the SHA-256 fingerprint of the request, as 64 hexadecimal characters. If a retry arrives with the same key and a different fingerprint, the store knows the key was reused for another request. `CHAR(64)` is the right type for a value that is always exactly 64 characters.
- `event_id`, `seat_label`, `customer`: what was held, for whom.
- `expires_at TIMESTAMP WITH TIME ZONE NOT NULL`: when the hold ends.
- `CONSTRAINT one_hold_per_key UNIQUE (idempotency_key)`: the rule that matters most here. Two rows can never carry the same key. If two instances try to insert a hold for one key at the same moment, the database lets one succeed and refuses the other with the standard error code `23505`. The Java code catches that code and answers with the first hold. In my run, a second `INSERT` with the same key failed with `Unique index or primary key violation: "public.one_hold_per_key ..."`.

`holds` has no foreign key to `seats`, and `event_id` and `seat_label` here are plain copies. The hold row is a record of the grant; the seat row is the current state. They are updated in the same transaction by `JdbcHoldStore`.

### The data

```sql
-- Module 1's arena on-sale: event 1 with 20,000 seats in 20 sections (A to T) of 1,000. The start
-- time is the one Module 1 printed and Modules 7 and 9 wrote in ISO-8601.
INSERT INTO events (event_id, name, starts_at) VALUES (1, 'Arena Night', TIMESTAMP WITH TIME ZONE '2026-12-08 20:20:51.783+00');
```

- The comment: "Module 1's arena on-sale: event 1 with 20,000 seats in 20 sections (A to T) of 1,000." The start time is "the one Module 1 printed and Modules 7 and 9 wrote in ISO-8601".
- `INSERT INTO events (event_id, name, starts_at) VALUES (...)` adds one row. The first bracket lists the columns, the second lists the values in the same order.
- `1` is the event id; `'Arena Night'` is the name (single quotes mark text in SQL); `TIMESTAMP WITH TIME ZONE '2026-12-08 20:20:51.783+00'` is a date-time **literal**: the type name followed by the text. `+00` means offset zero from UTC.

```sql
INSERT INTO seats (event_id, seat_label, section, status)
SELECT 1, chr(65 + (n - 1) / 1000) || '-' || (((n - 1) % 1000) + 1), chr(65 + (n - 1) / 1000), 'AVAILABLE'
FROM generate_series(1, 20000) AS g(n);
```

This statement adds 20,000 rows by running a `SELECT` and inserting each row it returns.

```text
INSERT INTO seats (event_id, seat_label, section, status)
SELECT 1,
       chr(65 + (n - 1) / 1000) || '-' || (((n - 1) % 1000) + 1),
       chr(65 + (n - 1) / 1000),
       'AVAILABLE'
FROM generate_series(1, 20000) AS g(n);
```

(That block is the statement laid out over several lines so it can be read; the file has it on three lines, quoted above.)

- `FROM generate_series(1, 20000) AS g(n)`: `generate_series` is a function that returns the numbers 1 to 20,000, one row each. `AS g(n)` names this little table `g` and its one column `n`.
- `SELECT 1, ... ` makes four values for each `n`, matching the four columns in the first line: the event id `1`, the label, the section and the status `'AVAILABLE'`.
- `chr(65 + (n - 1) / 1000)`: `chr(65)` is the character with code 65, which is `A`. `(n - 1) / 1000` is whole-number division, so it is 0 for n from 1 to 1,000, 1 for 1,001 to 2,000, up to 19 for 19,001 to 20,000. So the section letter runs from `A` (65) to `T` (84), 1,000 seats each.
- `||` joins texts. The label is the section letter, a hyphen, and `((n - 1) % 1000) + 1`. `%` is the remainder after division, so the number inside the section runs 1 to 1000.
- Worked examples: n = 1 gives `A-1`; n = 1000 gives `A-1000`; n = 1001 gives `B-1`; n = 20000 gives `T-1000`. I checked these on a real database (see below).

The result matches the `SEAT` pattern in `HoldCommand`: a letter A to T, a hyphen, a number 1 to 1000.

## Try it yourself

All commands run in the project folder. These use H2, the in-memory-style database that the tests use, from the copy Gradle has already downloaded; there is no PostgreSQL on the machine used for these notes, so none of these steps ran the file on PostgreSQL. The `find` command locates H2's jar in Gradle's cache; run `./gradlew --offline test` once first if it finds nothing. Timings differ on every run.

1. Load the schema into a file database, then count what it made.

```bash
mkdir -p build/h2
H2=$(find ~/.gradle -name 'h2-2.5.252.jar' | head -1)
URL="jdbc:h2:./build/h2/demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
java -cp "$H2" org.h2.tools.RunScript -url "$URL" -script db/schema.sql
java -cp "$H2" org.h2.tools.Shell -url "$URL" -sql "SELECT count(*) AS seats, count(DISTINCT section) AS sections, min(seat_label) AS first_label FROM seats"
```

```text
seats | sections | first_label
20000 | 20       | A-1
(1 row, 38 ms)
```

2. Take a seat the way `JdbcHoldStore` does, with one conditional `UPDATE`. The first call takes the free seat. The second, a second later, finds it held and changes nothing. The third, after the first hold expired, takes it again. (This block repeats the statement with different values; the three `sed` calls only fill in the values.)

```bash
h2sql() { java -cp "$H2" org.h2.tools.Shell -url "$URL" -sql "$1" 2>&1 | head -4; }
TAKE="UPDATE seats SET status = 'HELD', hold_id = 'HID', hold_customer = 'CUST', hold_expires_at = TIMESTAMP WITH TIME ZONE 'EXP' WHERE event_id = 1 AND seat_label = 'A-7' AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= TIMESTAMP WITH TIME ZONE 'NOW'))"
h2sql "$(echo "$TAKE" | sed -e 's/HID/h1/;s/CUST/fan-1/;s/EXP/2026-11-11 09:05:00+00/;s/NOW/2026-11-11 09:00:00+00/')"
h2sql "$(echo "$TAKE" | sed -e 's/HID/h2/;s/CUST/fan-2/;s/EXP/2026-11-11 09:05:01+00/;s/NOW/2026-11-11 09:00:01+00/')"
h2sql "$(echo "$TAKE" | sed -e 's/HID/h3/;s/CUST/fan-3/;s/EXP/2026-11-11 09:11:00+00/;s/NOW/2026-11-11 09:06:00+00/')"
h2sql "SELECT seat_label, status, hold_id, hold_customer FROM seats WHERE seat_label = 'A-7'"
```

```text
(Update count: 1, 4 ms)
(Update count: 0, 3 ms)
(Update count: 1, 4 ms)
seat_label | status | hold_id | hold_customer
A-7        | HELD   | h3      | fan-3
(1 row, 32 ms)
```

`Update count: 1` means one row changed. The 0 is the whole point: the database said "not yours" without any Java code deciding it.

3. Break the rules on purpose. Each statement is refused, and nothing changes.

```bash
h2sql "INSERT INTO holds VALUES ('h1', '\"k-1\"', repeat('a', 64), 1, 'A-7', 'fan-1', TIMESTAMP WITH TIME ZONE '2026-11-11 09:05:00+00')"
h2sql "INSERT INTO holds VALUES ('h9', '\"k-1\"', repeat('b', 64), 1, 'A-8', 'fan-9', TIMESTAMP WITH TIME ZONE '2026-11-11 09:05:00+00')"
h2sql "INSERT INTO seats (event_id, seat_label, section, status) VALUES (1, 'Z-1', 'Z', 'BROKEN')"
h2sql "INSERT INTO seats (event_id, seat_label, section, status) VALUES (2, 'A-1', 'A', 'AVAILABLE')"
```

The first succeeds (`Update count: 1`). The second fails with `Unique index or primary key violation: "public.one_hold_per_key ..."` and the code `[23505-252]`. The third fails with `Check constraint violation: "seat_status: "`. The fourth fails with `Referential integrity constraint violation`. The number 252 at the end of each code is H2's own build number; the five digits before it are the standard SQLState.

4. Remove the demo files.

```bash
rm -rf build/h2
```

## Mistakes people make

- **Reading a row, deciding in code, then writing.** Two programs can both read "free". The rule here lives in the `WHERE` of one `UPDATE`.
- **Forgetting that `NULL` is not a value.** A comparison with `NULL` is never true. The `seats` hold columns are `NULL` for a free seat, and the rule tests `status`, which is never `NULL`.
- **Counting on a script to run again.** PostgreSQL runs `schema.sql` only on a new volume. Change the file and the old volume keeps the old tables.
- **Using the wrong text type.** `CHAR(64)` pads, `VARCHAR(64)` does not. A fingerprint is always 64 characters, so `CHAR` is safe; a name of varying length would be padded with spaces and surprise a comparison.
- **Storing local times.** `TIMESTAMP WITH TIME ZONE` stores one moment. Stored without a zone, a time shifts when the machine's zone changes.
- **Splitting a script on semicolons.** `TestDatabase` does it: it removes comment lines and cuts at `;`. It works because this file has no semicolon inside a text value. A semicolon inside `'...'` would break it.
- **Assuming the foreign key covers everything.** `holds` has no key to `seats`; the project relies on one transaction to update both.

## Check yourself

1. Why does `seats` have a composite primary key of `(event_id, seat_label)`?
   Answer: a seat is identified by the pair; the same label exists in every event.
2. What does `UNIQUE (idempotency_key)` give the project that the Java code cannot give alone?
   Answer: a rule that holds across all instances at once. The database refuses a second row with the same key even when two instances insert at the same moment.
3. What label does `generate_series` number 1001 produce, and why?
   Answer: `B-1`. `(1001 - 1) / 1000` is 1, so `chr(66)` is `B`; `(1000 % 1000) + 1` is 1.
4. What happens if a program inserts a seat with status `SOLD`?
   Answer: the `seat_status` check refuses it and nothing is stored.
5. When does PostgreSQL run `schema.sql`, and what must you do to run it again?
   Answer: once, when the data volume is new. Remove the volume (`docker compose down -v`) and start again.

## Further reading

- PostgreSQL 18 documentation, `CREATE TABLE`: https://www.postgresql.org/docs/18/sql-createtable.html
- PostgreSQL 18 documentation, constraints (primary key, foreign key, check, unique): https://www.postgresql.org/docs/18/ddl-constraints.html
- PostgreSQL 18 documentation, data types (`bigint`, `character varying`, `character`, `timestamp with time zone`): https://www.postgresql.org/docs/18/datatype.html
- PostgreSQL 18 documentation, `INSERT` and `UPDATE`: https://www.postgresql.org/docs/18/sql-insert.html and https://www.postgresql.org/docs/18/sql-update.html
- PostgreSQL 18 documentation, set-returning functions (`generate_series`): https://www.postgresql.org/docs/18/functions-srf.html
- PostgreSQL 18 documentation, string functions and operators (`chr`, `||`): https://www.postgresql.org/docs/18/functions-string.html
- The PostgreSQL image on Docker Hub (the initialization scripts folder): https://hub.docker.com/_/postgres
- H2 2.5 documentation, PostgreSQL compatibility mode: https://h2database.com/html/features.html#compatibility
