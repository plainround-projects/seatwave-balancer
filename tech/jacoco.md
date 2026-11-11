# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "Line coverage: 0.893 (1,433 of 1,604 lines)" for 98 tests, and I measured the same numbers on 11 November 2026.

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

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="171" covered="1433"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

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

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-balancer: lines covered ratio is 0.89, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 98 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 740 | 8,164 | 90 percent (7,424 covered) |
| Branches | 110 | 499 | 77 percent (389 covered) |
| Lines | 171 | 1,604 | 1,433 lines, 89.3 percent |
| Methods | 18 | 290 | 272 |
| Classes | 1 | 54 | 53 |
| Complexity | 116 | 540 | 424 |

The rule needs 0.80 of 1,604 lines, which is 1,283.2, so at least 1,284 covered lines. The build has 1,433. **The margin is 149 lines.**

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `deploy` | 59 | 225 | `DockerCompose` 27, `DeployCli` 21, `DeploySettings` 5, `RollingDeploy` 5, `RuntimeBalancer` 1. |
| `harness` | 45 | 488 | `RunReport` 17 (and 1 in its nested `KillAnalysis`), `LoadRun` 10, `HarnessCli` 8, `BalancerWatch` 7, `Outcome` 2. |
| `seatwave` | 41 | 653 | `SeatWaveCli` 17, `JdbcHoldStore` 9, `SeatWaveServer` 8, `HoldCommand` 2, `HoldRoutes` 2, `ServiceMetrics` 2, and 1 in the nested `Lifecycle.DrainResult`. |
| `json` | 22 | 54 | `JsonCodec` 22: the refusal branches of `read` (see below). |
| `config` | 2 | 71 | `SettingsReader` 2. |
| `haproxy` | 2 | 113 | `RuntimeApi` 1 and `StatRow` 1. |

The three classes that hold a `main` method are `SeatWaveCli`, `HarnessCli` and `DeployCli`. Their missed lines add up to 46 of the 171 (17 + 8 + 21), and the build file's comment on the rule names them: "the command-line entry points are the untested remainder". For `DeployCli` the missed lines are the one-line `main` (lines 38 to 41 and 43), the bad-usage and bad-settings returns (64, 65 and 75), the branch where the deploy stops with a reason or cannot write its audit file (116 to 128) and the unreadable-terminal branch (156 and 157). For `SeatWaveCli` they are the body of `main` (lines 37 to 52, which need a live process and a signal), the failed settings branch (70) and the branch where the listeners cannot start (96 to 99).

`DockerCompose` is the one class that never ran at all (the `Classes` row shows 1 missed of 54). It starts the `docker` program to stop and start containers, and the tests replace its `Containers` interface with a fake (`RollingDeployTest` and `DeployCliTest`), so it runs only against a real Compose stack. Its 27 missed lines are 27 of the 59 missed lines of the `deploy` package.

`JsonCodec` is different. Its 22 missed lines (lines 74 to 103 and 117 to 129 of the source) are the branches that refuse a bad request body: an empty body, a body that is not an object, data after the object, an unknown field, a wrong type, a number out of range and malformed JSON. I read the XML and the source to confirm it: the missed lines are 74, 78, 82, 90 to 92, 94 to 96, 98 to 101, 103, 117, 118 and 124 to 129, and the one success path of `read` ran. The other missed lines are mostly error paths too; I did not read all of them one by one.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

All commands ran in a scratch copy on 11 November 2026. Timings differ on every run.

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 98 `PASSED` lines (count them with `--console=plain`), `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 15s` (or about that) and `10 actionable tasks: 10 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  740 of 8,164  90%  110 of 499  77%  116  540  171  1,604  18  290  1  54`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="171" covered="1433"/>`.

4. Print the totals of the `harness` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.balancer.harness/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  222 of 2,755  91%  37 of 170  78%  38  175  45  488  3  90  0  11`. The `harness` package has 488 lines, 45 of them missed, and no missed class.

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
- **Forgetting the margin.** There are 149 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.
- **Reading a high total as proof that every rule is tested.** A whole package can hide behind it: here the refusal branches of `JsonCodec` are red while the project total is 89.3 percent.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 89.3 percent and not 100?* The command-line entry points are not fully tested (46 of the 171 missed lines), `DockerCompose` needs a real stack (27), and the rest are error paths that a test would have to break something on purpose to reach.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 149: 1,433 covered against 1,284 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
