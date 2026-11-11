# Technology notes for seatwave-balancer

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal, a load balancer, Docker or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, try it yourself, mistakes people make, check yourself, and further reading. This index is the one file without them.

The repository is the hands-on project of Module 10, "Spreading the load", of a system design course. It serves a ticket-booking case called SeatWave. It runs SeatWave on three stateless instances that keep every hold in a shared PostgreSQL database, behind a redundant pair of HAProxy 3.4.7 balancers that share a floating address moved by keepalived, and it measures what that buys: how many requests fail when an instance is killed, how long the balancer takes to notice, whether a rolling deploy costs any request, and how long the service is unreachable when the active balancer dies. The stack runs in Docker Compose with every port on 127.0.0.1; `./gradlew check` needs no Docker. The code is Java 25 with Gradle, three shell scripts, SQL and the HAProxy and keepalived configuration.

## The files

**The language and the libraries**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, record, enum, sealed interface, `switch` with patterns, generics, lambdas, streams, `Optional`, exceptions, try-with-resources, `Locale`, `Duration` and `Clock`, regular expressions, locks, threads and virtual threads, Javadoc, and the minimum Java version of each feature. |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, every place it is used, and why expected failures are values here and exceptions are kept for bugs. |
| `jackson.md` | Jackson 2.23.0: JSON text into records and back, and the strict settings of `JsonCodec` that refuse a typo, a wrong type and a null number (and the two refusals whose reason is not exact). |
| `logging-slf4j-logback.md` | SLF4J and Logback: `logback.xml` line by line, the pattern, and the correlation id in the MDC that ties a log line to a request. |
| `hikaricp.md` | HikariCP: connection pools from zero, and how this repository sizes and uses one. |

**The data formats and the store**

| File | Role |
|---|---|
| `java-properties.md` | The `.properties` format and all thirteen settings files under `config/`, key by key. |
| `sql-schema.md` | SQL from zero and every line of `db/schema.sql`. |
| `postgresql-and-h2.md` | PostgreSQL as the shared store, why holds moved out of memory, and how the tests run the same schema on H2. |

**The balancer and the stack**

| File | Role |
|---|---|
| `load-balancing-concepts.md` | What a load balancer does: layer 4 and layer 7, the algorithms, active and passive health checks, retries, draining, slow start and the redundant pair. |
| `haproxy.md` | HAProxy: what it is and how its configuration language works, from zero. |
| `haproxy-config-walkthrough.md` | `haproxy/haproxy.cfg`, every line. |
| `keepalived-vrrp.md` | The floating address, VRRP, both keepalived files and the keepalived image. |
| `docker.md` | Docker: the `Dockerfile`, `keepalived/Dockerfile` and `.dockerignore`, line by line. |
| `docker-compose.md` | `compose.yaml`, key by key. |
| `shell-scripts-walkthrough.md` | The scripts under `scripts/`: `common.sh`, `stack.sh` and `lab.sh`. |
| `measuring-a-balancer.md` | How the module measures a balancer: open-loop load, failed requests during a kill, detection time, the drain test, the failover gap and CPU per thousand requests, tied to the README's captured numbers. |

**The code walkthroughs**

| File | Role |
|---|---|
| `java-config-json-walkthrough.md` | The `config` and `json` packages: the settings reader with no defaults, and the strict JSON codec. |
| `java-seatwave-walkthrough-1.md` | The `seatwave` package, part 1: settings, small types and the lifecycle (line by line). |
| `java-seatwave-walkthrough-2.md` | The `seatwave` package, part 2: replies, requests, the server and the routes (line by line). |
| `java-seatwave-walkthrough-3.md` | The `seatwave` package, part 3: the database store, the pool, the metrics and the command line (line by line). |
| `java-haproxy-client-walkthrough.md` | The `haproxy` package: the client of HAProxy's runtime API and the parsed stat table. |
| `java-harness-walkthrough-1.md` | The `harness` package, part 1: the settings, the two value types and the balancer watch. |
| `java-harness-walkthrough-2.md` | The `harness` package, part 2: sending the load, building the report, starting a run. |
| `java-deploy-walkthrough.md` | The `deploy` package: the rolling deploy. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle and `build.gradle.kts`: the plugins, the compiler flags, the test and stack tasks, the coverage rule and the two program tasks (`load`, `deploy`). |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions of 34 jars. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 80 percent floor; the real coverage is 89.3 percent. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit: how the test engine finds and runs the tests, and the `stack` tag that keeps Docker out of `check`. |
| `assertj.md` | AssertJ: the fluent assertions used in the tests. |
| `awaitility.md` | Awaitility: waiting for a condition, used twice, to test the drain rule. |
| `tests-seatwave-walkthrough-1.md` | The `seatwave` tests, part 1: the helpers, the lifecycle, the settings and the command line (line by line). |
| `tests-seatwave-walkthrough-2.md` | The `seatwave` tests, part 2: the store, the server and the routes (line by line). |
| `tests-config-walkthrough.md` | The tests of the `config` package and the rules test that reads `haproxy.cfg` and `compose.yaml`. |
| `tests-tools-walkthrough-1.md` | The tests of the tools, part 1: the runtime API client and the rolling deploy. |
| `tests-tools-walkthrough-2.md` | The tests of the tools, part 2: the harness and its report. |
| `tests-stack-walkthrough.md` | The tagged tests that run against the live stack. |

**Everything else**

| File | Role |
|---|---|
| `editorconfig.md` | `.editorconfig`, the editor settings. |
| `git.md` | Git: `.gitignore` and `.gitattributes`. |
| `gitleaks.md` | Gitleaks: the secret scanner that stands behind `.gitignore`; no file of the repository runs it. |

Four long walkthroughs are split into numbered files, and no file has the bare name: the `seatwave` code (`java-seatwave-walkthrough-1.md`, `java-seatwave-walkthrough-2.md` and `java-seatwave-walkthrough-3.md`); the `harness` code (`java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`); the `seatwave` tests (`tests-seatwave-walkthrough-1.md` and `tests-seatwave-walkthrough-2.md`); the tests of the tools (`tests-tools-walkthrough-1.md` and `tests-tools-walkthrough-2.md`). A note that speaks of "the walkthrough" of one of them means all its parts.

## Reading order

1. **The language first.** `java.md`. Do not skip it: every walkthrough relies on it. Then `result4j.md`, because many methods return a `Result`.
2. **The idea.** `load-balancing-concepts.md`, then `measuring-a-balancer.md`.
3. **The data.** `java-properties.md`, `sql-schema.md`, `postgresql-and-h2.md`, `hikaricp.md`, `jackson.md`, `logging-slf4j-logback.md`.
4. **The balancer and the stack.** `haproxy.md`, `haproxy-config-walkthrough.md`, `keepalived-vrrp.md`, `docker.md`, `docker-compose.md`, `shell-scripts-walkthrough.md`.
5. **The code walkthroughs, in the order the data travels.** `java-config-json-walkthrough.md`, then the `seatwave` package (`java-seatwave-walkthrough-1.md`, `java-seatwave-walkthrough-2.md`, `java-seatwave-walkthrough-3.md`), `java-haproxy-client-walkthrough.md`, the `harness` package (`java-harness-walkthrough-1.md`, `java-harness-walkthrough-2.md`) and `java-deploy-walkthrough.md`.
6. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
7. **The tests.** `junit.md`, `assertj.md`, `awaitility.md`, then the tests walkthroughs: the `seatwave` tests (`tests-seatwave-walkthrough-1.md`, `tests-seatwave-walkthrough-2.md`), `tests-config-walkthrough.md`, the tools tests (`tests-tools-walkthrough-1.md`, `tests-tools-walkthrough-2.md`) and `tests-stack-walkthrough.md`.
8. **The rest.** `editorconfig.md`, `git.md`, `gitleaks.md`.

## If you have ten minutes

1. `load-balancing-concepts.md`: read "How it works underneath", for what each balancing algorithm, health check and retry rule does and what it costs.
2. `measuring-a-balancer.md`: read "How it works underneath", for how the module turns a run into a number you can trust.
3. `java.md`: read "How it works underneath" and the sections on records, sealed interfaces and locks, so that the code walkthroughs make sense.

## How to run what the notes describe

The notes of the build files and the libraries were checked on 11 November 2026 with Java 25.0.2 and Gradle through `./gradlew`, on a Mac with an ARM processor (`aarch64`), in a copy of the repository. Timings, ports chosen by the system and random values differ on every run and every machine: compare the shape, not the digits. The commands that need no Docker are:

```text
./gradlew check
./gradlew -q stackFiles
./gradlew -q deploy --args='config/deploy.properties'
```

`check` runs 98 tests with a line coverage of 89.3 percent (1,433 of 1,604 lines). `stackFiles` collects the eleven jars that the container image copies into `build/stack/lib`. The `deploy` task, without `--run`, only prints the plan of the rolling deploy and says `dry run: nothing changed`. The stack, the load harness inside the lab network and the four tests tagged `stack` need Docker; the notes that describe them say whether their commands were run.

One warning: never give `--run` to the `deploy` task while someone else's stack is up on this machine. The tool talks to the balancers on `127.0.0.1` ports 21005 and 21015 and restarts real containers of the Compose project `seatwave-balancer`.

## How these notes were checked

Each walkthrough quotes the source line by line, copied by a script and not retyped. The command `python3 tools/techcov.py <report.json> seatwave-balancer`, run from the folder that holds all the repositories, counts the non-blank, non-comment lines of every source, script and configuration file and checks that each appears in these notes. The notes of the build files, the thirteen settings files and `logback.xml` quote every counted line of those files. Everything stated about Gradle, JUnit, AssertJ, Awaitility, Jackson, SLF4J, Logback, result4j and Gitleaks was checked on this machine, by running the command, by running a small program against the real jar, by reading a class file with `javap`, or by reading the jar's own files. HikariCP, the PostgreSQL driver and H2 are described in `hikaricp.md` and `postgresql-and-h2.md`.
