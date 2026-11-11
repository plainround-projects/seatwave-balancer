# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. For version 1.1.0 the line coverage is 0.934, 93.4 percent (1,610 of 1,723 lines), for 152 tests, and I measured the same numbers in a scratch copy on 11 November 2026.

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not. The tests tagged `stack` do not run in `./gradlew check`, so they add nothing to these numbers either.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
// The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a
// reader without Docker can still build and test everything else.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    systemProperty("seatwave.schema", file("db/schema.sql").absolutePath)
    systemProperty("seatwave.haproxy", file("haproxy/haproxy.cfg").absolutePath)
    inputs.file("db/schema.sql")
    inputs.file("haproxy/haproxy.cfg")
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run. The other lines of this block are explained in `gradle.md`.

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="113" covered="1610"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-balancer: lines covered ratio is 0.93, but expected minimum is 0.95`. (The message cuts the ratio to two decimals: 1,610 of 1,719 is 0.9366.)

### The real numbers (Temurin 25.0.2, 152 tests, version 1.1.0)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 442 | 8,689 | 95 percent (8,247 covered) |
| Branches | 89 | 553 | 83 percent (464 covered) |
| Lines | 113 | 1,723 | 1,610 lines, 93.4 percent |
| Methods | 12 | 306 | 294 |
| Classes | 0 | 55 | 55 |
| Complexity | 98 | 583 | 485 |

The rule needs 0.80 of 1,723 lines, which is 1,378.4, so at least 1,379 covered lines. The build has 1,610. **The margin is 231 lines.**

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `deploy` | 42 | 254 | `DeployCli` 18, `DockerCompose` 15, `RollingDeploy` 5, `DeploySettings` 3, `RuntimeBalancer` 1. |
| `seatwave` | 33 | 674 | `SeatWaveCli` 17, `SeatWaveServer` 8, `HoldCommand` 2, `HoldRoutes` 2, `ServiceMetrics` 2, `JdbcHoldStore` 1, and 1 in the nested `Lifecycle.DrainResult`. |
| `harness` | 28 | 536 | `LoadRun` 8, `HarnessCli` 8, `BalancerWatch` 7, `Outcome` 2, `HarnessSettings` 2, `RunReport` 1. |
| `json` | 6 | 63 | `JsonCodec` 6: the fallback branches of `read` (see below). |
| `haproxy` | 3 | 123 | `StatRow` 2 and `RuntimeApi` 1. |
| `config` | 1 | 73 | `SettingsReader` 1. |

The three classes that hold a `main` method are `SeatWaveCli`, `HarnessCli` and `DeployCli`. Their missed lines add up to 43 of the 113 (17 + 8 + 18), and the build file's comment on the rule names them: "the command-line entry points are the untested remainder". For `DeployCli` the missed lines are the body of `main` (lines 39 to 42 and 44), the second bad-usage return (65 and 66), the bad-settings return (76), the branch where the deploy is interrupted (120 to 123), the branch where the deploy stops with a reason (135 to 138) and the unreadable-terminal branch (180 and 181). For `SeatWaveCli` they are the body of `main` (lines 37 to 52, which need a live process and a signal), the failed settings branch (70) and the branch where the listeners cannot start (96 to 99).

`DockerCompose` was the one class that never ran in version 1.0.0. It starts the `docker` program to stop and start containers. Version 1.1.0 added `DockerComposeTest`, which uses the class's second constructor to run a small stand-in program in place of `docker compose`, so the class now runs in `./gradlew check` and the `Classes` row shows 0 missed of 55. Its 15 missed lines are the public constructor that names the real `docker compose`, the branch that kills a child that runs past its time limit, the branch for an output that does not end within the limit (a grandchild that keeps the pipe open), and the two branches for an interrupted wait and an unreadable output.

`JsonCodec` had 22 missed lines in version 1.0.0, the branches that refuse a bad request body. Version 1.1.0 added `JsonCodecTest`, and 6 lines are left: line 101 (a mapping failure that is not a number out of range), lines 110 to 112 and 114 (a Jackson failure of no more specific kind, and an `IOException` that reading from a string cannot really raise) and line 148 (in `pointerOf`, the step of a path that is an array index, which no test reaches). I read the XML report and the source to confirm the line numbers. The other missed lines are mostly error paths too; I did not read all of them one by one.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

All commands ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 152 `PASSED` lines (count them with `--console=plain`), `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 12s` (or about that) and `10 actionable tasks: 10 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  442 of 8,689  95%  89 of 553  83%  98  583  113  1,723  12  306  0  55`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="113" covered="1610"/>`.

4. Print the totals of the `harness` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.balancer.harness/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  111 of 2,999  96%  31 of 192  83%  32  192  28  536  2  96  0  12`. The `harness` package has 536 lines, 28 of them missed, and no missed class.

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-sessions.html`, `jacoco-resources` and one folder per package (`io.plainround.seatwave.balancer.config`, `.deploy`, `.haproxy`, `.harness`, `.json` and `.seatwave`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead.
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 231 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.
- **Reading a high total as proof that every rule is tested.** A whole package can hide behind it: in version 1.0.0 the refusal branches of `JsonCodec` were red while the project total was 89.3 percent. Version 1.1.0 added the tests that run them.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 93.4 percent and not 100?* The command-line entry points are not fully tested (43 of the 113 missed lines), and the rest are error paths that a test would have to break something on purpose to reach, such as `DockerCompose`'s time limit and interruption branches (11).
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 231: 1,610 covered against 1,379 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
