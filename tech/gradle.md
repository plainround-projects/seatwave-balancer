# Gradle 9.9.1 (build tool) and the Kotlin DSL

## What it is

Source code is text. A computer cannot run text until other programs have turned it into something runnable. A **build tool** is the program that does all those steps in the right order, from one command: it fetches the libraries the code needs, compiles the Java source files into **class files** (the form the Java Virtual Machine, the JVM, can run), runs the tests, checks the code style and measures the test coverage. **Gradle** is a build tool for Java. You tell it what you want (for example `check`) and it works out which smaller steps are needed.

A Gradle build is described in files written in a language. This repository uses the **Kotlin DSL**: the build file is a Kotlin script. "DSL" means domain-specific language, a small language made for one job. A file that ends in `.kts` is a Kotlin script, a Kotlin program that is run directly instead of being compiled first.

A **terminal** is a window where you type commands. The word after the program name, such as `check` in `./gradlew check`, is the name of a **task**. A task is one unit of work Gradle knows how to do.

## Why this repository uses it

The hands-on project of Module 10 must build and test with one command and the same result on every machine, and it must not need Docker for that. Gradle gives that: `./gradlew check` checks the formatting of the code, compiles it with every warning turned into an error, runs 98 tests and fails if the line coverage is below 80 percent. The build file also gives each of the two Java tools (the load harness and the rolling deploy) its own task, so a learner types one short command instead of assembling a long Java command line. It keeps the tests that need the running Compose stack out of `check` (they have their own task, `stackTest`), so the gate takes seconds and needs no Docker. And one task prepares the Docker side without Docker: `stackFiles` collects the jars the container image copies. SeatWave itself has no task, because an instance needs the shared database and so runs only inside the Compose stack.

## Where it appears

| Path | What it does with Gradle |
| --- | --- |
| `settings.gradle.kts` | Names the project and says where libraries may be downloaded from. |
| `build.gradle.kts` | The build itself: plugins, Java version, libraries, compiler flags, formatter, coverage, the test tasks, the stack task and the two program tasks. |
| `gradle/libs.versions.toml` | The version catalog: one place for every version (`toml-version-catalog.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | Pinned versions of every library, including the ones libraries bring along (`dependency-locking.md`). |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | The wrapper that downloads and starts the right Gradle (`gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`). |
| `scripts/stack.sh`, `scripts/lab.sh` | Call `./gradlew -q stackFiles`, `./gradlew stackTest` and `./gradlew -q deploy` (`shell-scripts-walkthrough.md`). |
| `Dockerfile` | Its comment says to build the jars first with `./gradlew stackFiles` (`docker.md`). |
| `build/` and `.gradle/` | Folders Gradle creates while it works. Git ignores them (`git.md`). |

## How it works underneath

1. You run `./gradlew check`. The wrapper starts a **Gradle process**, a Java program that stays alive in the background for a while (the **daemon**) so the next command starts quickly.
2. Gradle reads `settings.gradle.kts` first. That file decides which projects are part of the build. Here there is one project.
3. Gradle then reads `build.gradle.kts`. Running the file does not build anything. It only **configures**: it creates tasks and tells each one what it depends on.
4. Gradle looks at the task you asked for and at everything it depends on, and builds a **task graph**: a list of tasks in a legal order, each after the tasks it needs.
5. It runs the tasks. For each task it checks whether the inputs (source files, settings) and outputs (class files, reports) are the same as last time. If yes, the task is marked `UP-TO-DATE` and skipped. This is why a second `./gradlew stackFiles` took well under a second on this machine: it said `UP-TO-DATE` for the tasks it had already done.
6. Everything it makes goes into the `build/` folder. Downloaded libraries are kept in a cache folder in your home directory (`~/.gradle`), shared by all projects.

A **plugin** is a package of tasks and settings that you switch on in one line. The `java` plugin adds the tasks that compile and test Java. A **dependency** is a library your code needs. A **configuration** is a named list of dependencies for one purpose, such as "what the tests need at run time". A **repository** is a server that stores libraries; Maven Central is the big public one.

## Walkthrough of this repository's use

### `settings.gradle.kts`

```kotlin
// One place decides where dependencies come from; a project that adds its own repository fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "seatwave-balancer"
```

- Line 1 is a comment: `//` starts a comment in Kotlin. It says that one place decides where libraries come from.
- `dependencyResolutionManagement { ... }` is a block. A block is a name followed by braces; everything inside configures that one topic. This block configures how Gradle **resolves** dependencies, which means finding the exact file for each library.
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS` makes the build fail if `build.gradle.kts` ever adds a repository of its own. All repositories must be declared here. This stops a project from quietly pulling libraries from an unreviewed server.
- `repositories { mavenCentral() }` lists the only allowed repository: Maven Central.
- `rootProject.name = "seatwave-balancer"` names the project. The name appears in reports, for example "Rule violated for bundle seatwave-balancer".

### The plugins block

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}
```

- `plugins { ... }` lists the plugins to switch on.
- `java` adds the Java tasks: `compileJava`, `test`, `jar` and the `check` task.
- `jacoco` adds the coverage tool (`jacoco.md`).
- `alias(libs.plugins.spotless)` switches on the Spotless formatter plugin. `alias` means "use the plugin described in the version catalog". `libs.plugins.spotless` points at the `spotless` line in the `[plugins]` section of `gradle/libs.versions.toml`, which holds the plugin id and its version (`toml-version-catalog.md`).

### Project coordinates

```kotlin
group = "io.plainround"
version = "1.0.0"
```

`group` and `version` are the project's own identity in the Maven style: a group, a name (from `settings.gradle.kts`) and a version. They matter in one visible place: `version = "1.0.0"` is part of the name of the jar, `seatwave-balancer-1.0.0.jar`, that `stackFiles` collects. The `Dockerfile` names that jar and `compose.yaml` names the image `seatwave-balancer-seatwave:1.0.0`, so the three change together.

### The Java toolchain

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A **toolchain** is the exact JDK (Java Development Kit) that builds the code. `languageVersion = JavaLanguageVersion.of(25)` says "use Java 25". Gradle looks for an installed JDK 25 on the machine and uses it for compiling and for running tests, even if the JDK that started Gradle is different. This build does not configure an automatic download of a JDK, so a JDK 25 must be installed (the README names Eclipse Temurin 25.0.2). The `java { toolchain { ... } }` nesting is a block inside a block: `java` is the extension that the `java` plugin added, and `toolchain` is one of its parts.

### Dependencies and configurations

```kotlin
dependencies {
    implementation(libs.result4j)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    // H2 in its PostgreSQL mode runs the same db/schema.sql as the real database, so the hold rule
    // is tested in ./gradlew check without Docker; the stack tests run it on PostgreSQL itself.
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

```

- A name like `implementation`, `runtimeOnly`, `testImplementation` or `testRuntimeOnly` is a **configuration**. The `test` prefix means "only for the test code". `Implementation` means "needed to compile and run". `RuntimeOnly` means "needed only when running, not when compiling".
- `implementation(libs.result4j)` puts the result4j library on the **classpath** of the main code, both for compiling and for running. A classpath is the list of jar files and folders the JVM searches for classes. `libs.result4j` is a catalog entry: it expands to `com.github.sviperll:result4j:1.2.0` (`result4j.md`).
- `implementation(platform(libs.jackson.bom))` brings in a **BOM**, a bill of materials. A BOM is a small published file that lists the matching versions of many libraries of one family. `platform(...)` tells Gradle "use this list to decide versions". Jackson has several libraries (`jackson-databind`, `jackson-core`, `jackson-annotations`); the BOM says all of them are 2.23, so the catalog does not need a version for each.
- `implementation(libs.jackson.databind)` adds the JSON library (`jackson.md`). It has no version in the catalog; the BOM above supplies it.
- `implementation(libs.slf4j.api)` adds the logging front door that the code calls (`logging-slf4j-logback.md`).
- `implementation(libs.hikari)` adds HikariCP, the pool of database connections (`hikaricp.md`). The code names its classes, so it is `implementation`.
- `runtimeOnly(libs.postgresql)` adds the PostgreSQL JDBC driver (`postgresql-and-h2.md`). It is `runtimeOnly` on purpose: the code talks to the database through the JDK's `java.sql` interfaces and never names a driver class. The driver is found when the program starts.
- `runtimeOnly(libs.logback.classic)` adds the logging engine. It is `runtimeOnly` on the same grounds: no code names a Logback class, and SLF4J finds it when the program starts.
- `testImplementation(platform(libs.junit.bom))` loads the JUnit BOM for the tests, the same idea as the Jackson one.
- `testImplementation(libs.junit.jupiter)` adds the JUnit Jupiter bundle: the annotations and the engine that finds and runs the tests (`junit.md`). The BOM supplies 6.1.3.
- `testImplementation(libs.assertj.core)` adds AssertJ, the readable assertions (`assertj.md`).
- `testImplementation(libs.awaitility)` adds Awaitility, which waits until a condition becomes true (`awaitility.md`).
- The two comment lines above `testImplementation(libs.h2)` give the reason for H2: it runs the same `db/schema.sql` as the real database, in its PostgreSQL mode, so the hold rule is tested by `./gradlew check` without Docker, and the stack tests run the same rule on PostgreSQL itself (`postgresql-and-h2.md`). It is `testImplementation`, so it never reaches the container image.
- `testRuntimeOnly(libs.junit.platform.launcher)` adds the JUnit Platform launcher, the piece Gradle calls to start the tests. Gradle 9 expects the project to declare it, and it is needed only when tests run, so it is `testRuntimeOnly`. Its version also comes from the BOM.

The servers, the HTTP client and the load generator are all the JDK's own classes (`java.net.http`, `com.sun.net.httpserver`), so the main code needs only these six libraries: result4j, Jackson, SLF4J, HikariCP, the PostgreSQL driver and Logback.

### Dependency locking

```kotlin
// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}
```

Libraries bring other libraries (their **transitive dependencies**). `lockAllConfigurations()` makes Gradle write the exact version of every library of every configuration into `gradle.lockfile`, and read that file on every later build. If the resolved versions would differ from the lock, the build fails. The whole story is in `dependency-locking.md`.

### The Java compiler flags

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
```

- `tasks.withType<JavaCompile>().configureEach { ... }` means: for every task of the type `JavaCompile` (there is one for main code and one for test code), run this block. `<JavaCompile>` is a Kotlin type argument, the type to select. `configureEach` applies the block to each one lazily, only when needed.
- `options.encoding = "UTF-8"` makes the compiler read source files as UTF-8 text, so the result does not depend on the machine's default.
- `options.release = 25` makes the compiler accept only Java 25 language features and use only Java 25 library classes.
- The comment on line 43 gives the reason for the next line: "Every warning is an error: a warning that is allowed to stay is a defect nobody owns."
- `options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))` passes two flags to `javac`, the Java compiler. `-Xlint:all` turns on every optional warning (raw types, unchecked casts, an auto-closeable resource that is never used, missing serial ids and more). `-Werror` turns each warning into an error, so the build stops. The flag also applies to the test code.
- I ran an experiment in a scratch copy: a new class with the raw types `java.util.List` and `java.util.ArrayList` made `compileJava` fail. The output had `warning: [rawtypes] found raw type: List`, then `error: warnings found and -Werror specified`, `1 error` and `2 warnings`.

### Spotless and the formatter

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

Spotless is a Gradle plugin that checks and rewrites source files to one layout (`spotless-google-java-format.md`).
- `spotless { java { ... } }` configures Spotless for Java files.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` chooses the formatter. `libs.versions.googleJavaFormat` is the version entry `googleJavaFormat = "2.0.0"` from the catalog, and `.get()` turns it into the text `2.0.0`.
- `removeUnusedImports()` deletes `import` lines the file never uses.
- `endWithNewline()` makes every file end with one line break.
- Spotless adds the tasks `spotlessCheck` (fails if a file would change) and `spotlessApply` (changes the files). The `java` plugin's `check` task depends on `spotlessCheck`, which is how formatting becomes part of the gate.

### JaCoCo, the coverage tool

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`jacoco { toolVersion = ... }` fixes the JaCoCo version to the catalog entry `jacoco = "0.8.16"`. Without it, Gradle would pick a default version. `jacoco.md` explains what the tool measures.

### The test task

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

- The two comment lines say why the tagged tests are separate: "The tests tagged "stack" need the running Compose stack; ./gradlew check never runs them, so a reader without Docker can still build and test everything else."
- `tasks.test { ... }` configures the existing task named `test`.
- `useJUnitPlatform { excludeTags("stack") }` tells it to run tests through the JUnit Platform, and to skip every test marked `@Tag("stack")`. Without `useJUnitPlatform` Gradle would look for older JUnit 4 tests and find none.
- `systemProperty("seatwave.schema", file("db/schema.sql").absolutePath)` is how the tests find the database schema. A **system property** is a named text value that Gradle gives to the test JVM, like a variable that every class can read. The value is an absolute path to `db/schema.sql` of the checkout, so it does not matter which folder the test JVM starts in. `systemProperty("seatwave.haproxy", file("haproxy/haproxy.cfg").absolutePath)` does the same for the balancer's configuration file, which a test reads and checks (`tests-stack-walkthrough.md`).
- `inputs.file("db/schema.sql")` and `inputs.file("haproxy/haproxy.cfg")` tell Gradle that these two files are inputs of the task: if either changes, `test` is not up to date and runs again.
- `testLogging { events("passed", "failed", "skipped") }` prints one line per test result while the task runs, for example `ServiceSettingsTest > refusesAHostNameAsTheBindAddress() PASSED`. Without it, only failures show.
- `finalizedBy(tasks.jacocoTestReport)` means: after `test` finishes, always run `jacocoTestReport`, even if tests failed. That creates the coverage report in `build/reports/jacoco/test/html`.

There is no `maxParallelForks` and no `forkEvery`, so Gradle runs all test classes in one test JVM, one test after another.

### The stack test task

```kotlin
tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the running stack; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
```

- `tasks.register<Test>("stackTest") { ... }` creates a second task of the type `Test`, so it must be told what a `test` task is told by the java plugin: `testClassesDirs = sourceSets.test.get().output.classesDirs` (where the compiled test classes are) and `classpath = sourceSets.test.get().runtimeClasspath` (the libraries they need). A **source set** is a named group of source folders; `test` is `src/test/java`.
- `useJUnitPlatform { includeTags("stack") }` is the mirror image of `excludeTags("stack")`: only the tests marked `@Tag("stack")` run.
- The comment explains `outputs.upToDateWhen { false }`: "The stack is outside Gradle's view, so these tests are never up to date." Gradle cannot see a running container, so it must never skip the task.
- `testLogging { ... }` is the same as above.
- The `group` and `description` lines file the task under `Verification tasks` with the text that tells how to use it: `scripts/stack.sh test` starts the stack, runs this task and stops the stack. This note did not run it, because it needs Docker.

### The XML coverage report

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

`tasks.jacocoTestReport { ... }` configures the task that writes the coverage report. `reports { xml.required = true }` switches on the **XML report**. By default the task writes only the HTML report, which people read in a browser. XML is a text format made of tags such as `<counter type="LINE" missed="171" covered="1433"/>`; a program can read it, so the exact numbers can be copied into the README without counting by eye. The file lands in `build/reports/jacoco/test/jacocoTestReport.xml`.

### The coverage rule wired into `check`

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

- `tasks.jacocoTestCoverageVerification` is the task that checks coverage against a rule. `dependsOn(tasks.test)` makes sure the tests have run, so there is coverage data to read.
- `violationRules { rule { limit { ... } } }` is the rule. A **limit** names a counter, here `counter = "LINE"`, and a bound. `minimum = "0.80".toBigDecimal()` means the covered share of lines must be at least 0.80. `.toBigDecimal()` turns the text into an exact decimal number, which the plugin requires. The comment explains that the untested remainder is the command-line entry points.
- `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` adds the coverage check to `check`. By default `check` does not run it, so this line is what makes the floor a real gate. I proved it in a scratch copy: with the minimum raised to 0.95 the build failed with `Rule violated for bundle seatwave-balancer: lines covered ratio is 0.89, but expected minimum is 0.95`.
- `check` is the standard "is this project healthy" task. It runs the tests, `spotlessCheck` and the coverage verification. Neither program task of the next sections is a dependency of it.

### The stack task

```kotlin
// The jars the container image copies: this project's jar and its runtime libraries, in one folder.
tasks.register<Sync>("stackFiles") {
    group = "build"
    description = "Collects the jar and its runtime libraries into build/stack/lib for the Dockerfile."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("stack/lib"))
}
```

- The comment says what `stackFiles` is for: "The jars the container image copies: this project's jar and its runtime libraries, in one folder."
- `tasks.register<Sync>("stackFiles")` creates a task of the type `Sync`. A `Sync` task copies files into a folder and **deletes** anything else in that folder, so the folder always matches the sources exactly.
- `from(tasks.jar)` adds the project's own jar, built by the `jar` task, and `from(configurations.runtimeClasspath)` adds every library that the main code needs at run time. `into(layout.buildDirectory.dir("stack/lib"))` is the target, `build/stack/lib`. I ran it and the folder held eleven jars: `HikariCP-7.1.0.jar`, `checker-qual-3.55.1.jar`, `jackson-annotations-2.23.jar`, `jackson-core-2.23.0.jar`, `jackson-databind-2.23.0.jar`, `logback-classic-1.6.6.jar`, `logback-core-1.6.6.jar`, `postgresql-42.7.14.jar`, `result4j-1.2.0.jar`, `seatwave-balancer-1.0.0.jar` and `slf4j-api-2.0.20.jar`. The Dockerfile copies every jar in that folder. The test-only libraries (H2, JUnit and the rest) are not in it, because `runtimeClasspath` does not hold them.
- `group = "build"` and `description = ...` file the task in the `build` group of `./gradlew tasks` with its explanatory text. `scripts/stack.sh up` calls it before `docker compose up`.

### The two program tasks

```kotlin
// One command per example: each entry point gets its own task, so a reader never assembles a classpath.
// SeatWave itself has none: it needs the shared database, so it runs only in the Compose stack.
val entryPoints = mapOf(
    "load" to "io.plainround.seatwave.balancer.harness.HarnessCli",
    "deploy" to "io.plainround.seatwave.balancer.deploy.DeployCli",
)

entryPoints.forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "application"
        description = "Runs $entryPoint."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = entryPoint
        // Paths in --args are read from the project folder, wherever Gradle was started.
        workingDir = rootDir
        // The deploy reads its confirmation from the terminal.
        standardInput = System.`in`
    }
}
```

- The comment on line 123 says what the block is for: "One command per example: each entry point gets its own task, so a reader never assembles a classpath." The second comment line says what is missing: "SeatWave itself has none: it needs the shared database, so it runs only in the Compose stack."
- `val entryPoints = mapOf(...)` creates a read-only map in Kotlin: each pair is a task name and the full name of a class that has a `main` method (an **entry point**, the place where a Java program starts). `"a" to "b"` builds one pair. The two pairs are `load` (`HarnessCli`, the load harness) and `deploy` (`DeployCli`, the rolling deploy). The trailing comma after the last pair is allowed in Kotlin.
- `entryPoints.forEach { (taskName, entryPoint) -> ... }` runs the block once per pair. The round brackets `(taskName, entryPoint)` take the pair apart into its two parts.
- `tasks.register<JavaExec>(taskName) { ... }` creates a new task of the type `JavaExec`, which runs a Java program. `register` creates it lazily.
- `group = "application"` files the task in the `application` group in the `./gradlew tasks` list. `description = "Runs $entryPoint."` is the text shown next to it; `$entryPoint` inserts the variable into the text.
- `classpath = sourceSets.main.get().runtimeClasspath` gives the program the compiled main classes plus the libraries it needs. `main` is `src/main/java`. Because `JavaExec` depends on the classpath, Gradle compiles the code first.
- `mainClass = entryPoint` names the class to start.
- The comment "Paths in --args are read from the project folder, wherever Gradle was started." and `workingDir = rootDir`: a **working directory** is the folder a program treats as "here": a relative path such as `config/deploy.properties` is looked for from it. `rootDir` is the folder that holds `settings.gradle.kts`.
- `standardInput = System.`in`` connects the terminal's keyboard to the program. **Standard input** is the stream a program reads typed text from; Gradle does not connect it to a task unless told to. The comment says why: the deploy reads its confirmation from the terminal (`java-deploy-walkthrough.md`). In Kotlin, `in` is a reserved word, so a name that equals it is written between backticks.

**Why the measuring runs are their own tasks and not part of `check`.** A load run takes tens of seconds to minutes and its numbers depend on the machine and on a running stack, so a test could fail on a busy laptop without any bug. A table of failures and gaps is not a pass-or-fail check anyway: a person reads it. So the tests check that the machinery is right (the parsers, the settings, the report arithmetic, the server, the deploy sequence against a fake) and finish in seconds, and the two tasks make the real runs when asked. The cost: the `main` methods are not fully covered by tests, which is why the coverage floor is 0.80 and not higher (`jacoco.md`).

### The commands you type

- `./gradlew check` runs the full gate. `./` means "the file `gradlew` in this folder".
- `./gradlew spotlessApply` rewrites source files into the formatter's layout. Run it before `check` if `spotlessCheck` complains.
- `-q` (`--quiet`) hides Gradle's own messages, so only the program's output shows.
- `--args='...'` passes the text after it to the program's `main` method as arguments. Gradle splits the text at spaces into separate arguments. `./gradlew -q deploy --args='config/deploy.properties'` therefore gives `DeployCli` one argument: the settings file. With `--run --yes` added inside the quotes it gives three, which is how `scripts/lab.sh` calls it.
- `--offline` makes Gradle use only what is in its cache and touch no network. I used it for every command in these notes.
- When a program calls `System.exit` with a non-zero value, Gradle reports `Execution failed for task ':load'` (the name of the task) and exits with 1 itself.

## Try it yourself

All commands below ran in a scratch copy of this repository on 11 November 2026 (Temurin 25.0.2, Gradle 9.9.1). Timings differ on every run.

1. List the tasks that run the programs:

```
./gradlew --offline -q tasks --group application
```

   The list starts with `Application tasks` and has these two lines: `deploy - Runs io.plainround.seatwave.balancer.deploy.DeployCli.` and `load - Runs io.plainround.seatwave.balancer.harness.HarnessCli.`

2. Run the whole gate:

```
./gradlew --offline clean check
```

   It prints one `PASSED` line per test (98 in all, with `--console=plain`), then `> Task :jacocoTestCoverageVerification`, `> Task :spotlessJavaCheck` and ends with `BUILD SUCCESSFUL in 15s` and `10 actionable tasks: 10 executed`.

3. Collect the jars for the container image:

```
./gradlew --offline stackFiles
ls build/stack/lib
```

   Expected: `> Task :stackFiles`, `BUILD SUCCESSFUL`, and the eleven jars listed above.

4. See the deploy's plan without changing anything. The tool is a dry run unless you add `--run`:

```
./gradlew --offline -q deploy --args='config/deploy.properties'
```

   Expected: two lines that start with `plan: restart [seatwave-1, seatwave-2, seatwave-3] of project seatwave-balancer one at a time` and `  each: drain, wait for 0 sessions, stop (SIGTERM), wait until seen down, start, ready, wait until up, settle 10000 ms`, then `dry run: nothing changed. Add --run to do it.` The exit status is 0.

5. Call the load task with no argument, to see the usage message and how Gradle reports a failing program:

```
./gradlew --offline -q load
```

   Expected: `usage: load <harness.properties>`, then `Execution failed for task ':load'` and `finished with non-zero exit value 2`. Do not give it a real settings file: the harness sends thousands of requests per second to the lab network, which exists only inside the Compose stack.

6. Break the coverage floor on purpose, in a scratch copy only. Change `"0.80"` to `"0.95"` in `build.gradle.kts` and run `./gradlew --offline check -q`. The output contains `Rule violated for bundle seatwave-balancer: lines covered ratio is 0.89, but expected minimum is 0.95` and `Execution failed for task ':jacocoTestCoverageVerification'`, and `BUILD FAILED`.

## Mistakes people make

- **Running `gradle` instead of `./gradlew`.** The installed Gradle may be a different version. The wrapper always starts 9.9.1 (`gradle-wrapper.md`).
- **Editing `gradle.lockfile` by hand.** The file says manual edits can break the build. Regenerate it (`dependency-locking.md`).
- **Adding a repository in `build.gradle.kts`.** `FAIL_ON_PROJECT_REPOS` in the settings file stops it on purpose.
- **Ignoring a compiler warning.** `-Werror` makes that impossible here.
- **Thinking `test` is enough.** `./gradlew test` skips the formatter check and the coverage floor. Only `check` runs all three.
- **Expecting `check` to prove the balancer works.** The tests tagged `stack` are left out of `check`. They run only through `stackTest` against a running stack.
- **Running a test class from an editor without Gradle.** The tests need the system properties `seatwave.schema` and `seatwave.haproxy`; only the Gradle `test` task sets them.
- **Looking for the XML report in the wrong place.** It is `build/reports/jacoco/test/jacocoTestReport.xml`, written only because of the `xml.required = true` line.
- **Running the deploy with `--run` on a machine where someone else's stack is up.** The tool talks to the balancers on `127.0.0.1` and restarts real containers. Without `--run` it only prints the plan. (I read in `RollingDeploy.run` that it checks every instance first and stops with a message that starts `check before:` if something is not up; I did not run it with `--run`, because that would change containers if a stack were running.)
- **Forgetting that the build file runs.** It is a program. Typing mistakes show up as Kotlin errors, not as friendly messages.

## Check yourself

1. *What is a task?* One named unit of work, such as `compileJava`, `test` or `check`. Gradle runs a task only after the tasks it depends on.
2. *What does `testImplementation(platform(libs.junit.bom))` do?* It loads a list of matching versions for the JUnit family, so the individual JUnit libraries need no version of their own.
3. *Why does `-Werror` matter?* It turns every compiler warning into an error, so no warning can stay unnoticed.
4. *Which line makes the coverage floor part of `check`?* `dependsOn(tasks.jacocoTestCoverageVerification)` inside `tasks.check`.
5. *Why is there both `excludeTags("stack")` and `includeTags("stack")`?* `test` (part of `check`) skips the tests that need the running stack, and `stackTest` runs only those.

## Further reading

- Gradle 9.9.1 user guide: <https://docs.gradle.org/9.9.1/userguide/userguide.html>
- Build file basics: <https://docs.gradle.org/9.9.1/userguide/part1_gradle_init.html>
- Java toolchains: <https://docs.gradle.org/9.9.1/userguide/toolchains.html>
- Kotlin DSL primer: <https://docs.gradle.org/9.9.1/userguide/kotlin_dsl.html>
- Custom task classes and up-to-date checks: <https://docs.gradle.org/9.9.1/userguide/custom_tasks.html>
- The Java plugin and its `check` task: <https://docs.gradle.org/9.9.1/userguide/java_plugin.html>
- The JaCoCo plugin and its reports: <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
