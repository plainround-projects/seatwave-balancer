# TOML and the Gradle version catalog

## What it is

**TOML** (Tom's Obvious Minimal Language) is a plain-text file format for settings. It is built to be read by people first. A TOML file is a list of `key = value` pairs, grouped under headings written in square brackets. Gradle uses one TOML file, `gradle/libs.versions.toml`, as a **version catalog**: a single list of every library, plugin and version the build uses, each with a short name. The build file refers to entries by name, so the version numbers live in exactly one place.

## Why this repository uses it

The README says every version is exact and lists them in one table. One file means one place to read, one place to change and one place to check against the vulnerability database. The build file then contains no version number at all. This repository has thirteen libraries and one plugin, so a single list pays off: a reader who wants to know "which Jackson?" or "which connection pool?" opens one file of 33 lines.

## Where it appears

| Path | What it does with TOML |
| --- | --- |
| `gradle/libs.versions.toml` | The catalog: 13 versions, 13 libraries, 1 plugin. |
| `build.gradle.kts` | Reads it through `libs.xxx` names (`gradle.md`). |
| `gradle.lockfile`, `settings-gradle.lockfile` | The locked, resolved result of what the catalog asks for (`dependency-locking.md`). |
| `.editorconfig` | Gives `*.toml` files a two-space indent (`editorconfig.md`). |

## How it works underneath

1. Gradle looks for `gradle/libs.versions.toml` automatically. The file name decides the catalog name: `libs`.
2. It parses the three tables and, for each entry, creates a typed accessor that the Kotlin build file can call.
3. Nothing is downloaded when the catalog is read. It is only a list of names. Downloads start when a task needs a classpath.
4. `settings-gradle.lockfile` has a line for the internal configuration that Gradle uses to read the catalog (`dependency-locking.md`).

**TOML syntax in six rules.**
- `#` starts a comment that runs to the end of the line.
- `[name]` on its own line starts a **table** (a named group). Everything below it belongs to it, until the next table heading.
- `key = "text"`: a key, an equals sign and a value. Text is always in double quotes.
- `{ a = "x", b = "y" }` is an **inline table**: a small group written on one line.
- `version.ref = "junit"` is a **dotted key**: the key `version` holds a table whose key `ref` is `"junit"`. It is the same as `version = { ref = "junit" }`.
- Keys are case sensitive. Blank lines do not matter.

## Walkthrough of this repository's use

### The whole file

```toml
# Every version is exact. Each was checked on Maven Central or the Gradle Plugin Portal on 11 November 2026.
[versions]
result4j = "1.2.0"
jackson = "2.23.0"
slf4j = "2.0.20"
logback = "1.6.6"
hikari = "7.1.0"
postgresql = "42.7.14"
h2 = "2.5.252"
junit = "6.1.3"
assertj = "3.27.7"
awaitility = "4.3.0"
spotless = "8.11.0"
googleJavaFormat = "2.0.0"
jacoco = "0.8.16"

[libraries]
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
jackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
awaitility = { module = "org.awaitility:awaitility", version.ref = "awaitility" }

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

### Line 1: the comment

The `#` line says every version is exact and was checked on 11 November 2026. Exact means there are no ranges such as `3.+`: a range would let a new release appear without any change to the repository.

### Lines 2 to 15: `[versions]`

A table of named versions. The name is yours to choose; the value is a string.
- `result4j = "1.2.0"` the result library (`result4j.md`).
- `jackson = "2.23.0"` the version of the whole Jackson family. It is used by the BOM entry below (`jackson.md`).
- `slf4j = "2.0.20"` the logging front door, and `logback = "1.6.6"` the logging engine behind it (`logging-slf4j-logback.md`).
- `hikari = "7.1.0"` the database connection pool (`hikaricp.md`), `postgresql = "42.7.14"` the driver that talks to PostgreSQL, and `h2 = "2.5.252"` an in-memory database that only the tests use (`postgresql-and-h2.md`).
- `junit = "6.1.3"` the version of the whole JUnit family. It is used by the BOM entry below (`junit.md`).
- `assertj = "3.27.7"` the assertion library (`assertj.md`).
- `awaitility = "4.3.0"` the library that waits for a condition in a test (`awaitility.md`).
- `spotless = "8.11.0"` the version of the Spotless Gradle plugin.
- `googleJavaFormat = "2.0.0"` the version of the formatter that Spotless runs. It is read directly by the build file (`spotless-google-java-format.md`).
- `jacoco = "0.8.16"` the coverage tool version. Also read directly by the build file (`jacoco.md`).

### Lines 17 to 30: `[libraries]`

Each line is an alias, an equals sign and an inline table.
- `module = "group:name"` is the library's address on Maven Central: the group, a colon and the artifact name. For example `com.github.sviperll:result4j`.
- `version.ref = "result4j"` says "use the entry called `result4j` from `[versions]`". The reference is by name, not by number.
- `junit-jupiter`, `junit-platform-launcher` and `jackson-databind` have **no version**. That is intentional. The build file brings in a **BOM** (bill of materials, a published list of matching versions) first, `platform(libs.junit.bom)` or `platform(libs.jackson.bom)`, and the BOM's list supplies the version of every module of its family. So `junit-bom` and `jackson-bom` are the only entries of those families that carry `version.ref`.
- `result4j`, `slf4j-api`, `logback-classic`, `hikari`, `postgresql`, `h2`, `assertj-core` and `awaitility` have no BOM, so each has its own `version.ref`.

### Lines 32 and 33: `[plugins]`

`spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }` names a Gradle plugin. `id` is the plugin's identifier and `version.ref` points at the version `8.11.0`. In the build file it is switched on with `alias(libs.plugins.spotless)`.

### How a catalog name becomes `libs.something`

The part before the first dot is the catalog name (`libs`). After it comes the table. Dashes, underscores and dots in an alias all become dots in the accessor.

| Catalog line | In `build.gradle.kts` | Meaning |
| --- | --- | --- |
| `result4j = { module = ... }` in `[libraries]` | `libs.result4j` | The library `com.github.sviperll:result4j:1.2.0`. |
| `jackson-bom = { ... }` | `libs.jackson.bom` | The Jackson bill of materials 2.23.0. |
| `jackson-databind = { ... }` | `libs.jackson.databind` | The JSON library, version from the BOM. |
| `slf4j-api = { ... }` | `libs.slf4j.api` | SLF4J 2.0.20. |
| `logback-classic = { ... }` | `libs.logback.classic` | Logback 1.6.6. |
| `hikari = { ... }` | `libs.hikari` | HikariCP 7.1.0. |
| `postgresql = { ... }` | `libs.postgresql` | The PostgreSQL JDBC driver 42.7.14. |
| `h2 = { ... }` | `libs.h2` | The H2 database 2.5.252, for tests. |
| `junit-bom = { ... }` | `libs.junit.bom` | The JUnit bill of materials 6.1.3. |
| `junit-jupiter = { ... }` | `libs.junit.jupiter` | The Jupiter bundle, version from the BOM. |
| `junit-platform-launcher = { ... }` | `libs.junit.platform.launcher` | The launcher, version from the BOM. |
| `assertj-core = { ... }` | `libs.assertj.core` | AssertJ 3.27.7. |
| `awaitility = { ... }` | `libs.awaitility` | Awaitility 4.3.0. |
| `spotless = { id = ... }` in `[plugins]` | `libs.plugins.spotless` | The Spotless plugin 8.11.0. |
| `googleJavaFormat = "2.0.0"` in `[versions]` | `libs.versions.googleJavaFormat.get()` | The text `2.0.0`. |
| `jacoco = "0.8.16"` in `[versions]` | `libs.versions.jacoco.get()` | The text `0.8.16`. |

A library entry used as a dependency is turned into a full address with a version. A version entry used as a value has to be read with `.get()`, which returns its text.

## Try it yourself

All commands ran in a scratch copy of this repository on 11 November 2026 (Gradle 9.9.1, Temurin 25.0.2).

1. See which catalog names the build uses:

```
grep -n "libs\." build.gradle.kts
```

   Expected: 16 lines, at line numbers 4, 17 to 23, 25 to 28, 31, 32, 49 and 56: `libs.plugins.spotless`, thirteen dependency lines (the `libs.h2` line is the one after the two-line comment), `libs.versions.googleJavaFormat.get()` and `libs.versions.jacoco.get()`.

2. Ask Gradle which version Jackson resolved to and why:

```
./gradlew --offline -q dependencyInsight --dependency jackson-databind --configuration runtimeClasspath
```

   The output starts with `com.fasterxml.jackson.core:jackson-databind:2.23.0` and includes `Selection reasons:` followed by `By constraint: Dependency version enforced by Dependency Locking` and `By ancestor`.

3. See the BOMs at work:

```
./gradlew --offline -q dependencies --configuration testRuntimeClasspath | grep "junit-jupiter \|jackson-databind"
```

   Expected lines include `org.junit.jupiter:junit-jupiter -> 6.1.3` and `com.fasterxml.jackson.core:jackson-databind -> 2.23.0`. The versions came from the BOMs, not from the catalog entries.

4. Count the entries:

```
grep -c " = " gradle/libs.versions.toml
```

   Expected: `27` (13 versions, 13 libraries, 1 plugin).

## Mistakes people make

- **Single quotes or no quotes around text.** TOML text uses double quotes (single quotes are literal strings, rarely needed).
- **Writing a version where `version.ref` is meant.** `version = "1.2.0"` inside a library entry works but defeats the one-place rule.
- **Using a range** such as `"3.+"`. This repository pins exact versions on purpose.
- **Mixing up the dash and the dot.** The alias `junit-jupiter` is spelled `libs.junit.jupiter` in Kotlin.
- **Forgetting `.get()`** on a `libs.versions...` value.
- **Giving the BOM members their own versions.** That would fight the BOM. They are left without one here.

## Check yourself

1. *What are the three tables in this file?* `[versions]`, `[libraries]` and `[plugins]`.
2. *What does `version.ref = "assertj"` mean?* "Use the version named `assertj` from the `[versions]` table."
3. *Why does `jackson-databind` have no version?* The Jackson BOM (`jackson-bom`) provides it.
4. *How is `junit-platform-launcher` written in the build file?* `libs.junit.platform.launcher`.
5. *Why exact versions?* So no new release can change the build without a visible change to this file.

## Further reading

- Version catalogs in Gradle 9.9.1: <https://docs.gradle.org/9.9.1/userguide/version_catalogs.html>
- The TOML 1.0.0 specification: <https://toml.io/en/v1.0.0>
- Platforms and BOMs in Gradle: <https://docs.gradle.org/9.9.1/userguide/platforms.html>
