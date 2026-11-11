# Spotless 8.11.0 and google-java-format 2.0.0

## What it is

A **formatter** rewrites source code into one fixed layout: where the spaces go, where lines break, how imports are ordered. It changes no behavior. **google-java-format** is a formatter for Java with one fixed style and almost no options. **Spotless** is a Gradle plugin that runs formatters over the files of a project. It can only check ("does any file differ from the formatted version?") or apply ("rewrite them").

Two tools, two jobs: Spotless is the Gradle plugin in version 8.11.0. google-java-format is the formatter it runs, in version 2.0.0, downloaded as a library when needed.

## Why this repository uses it

With one enforced layout nobody argues about style, reviews show only real changes, and nobody formats by hand. The README lists Spotless in its table of tools, and `./gradlew check` runs `spotlessCheck`, so an unformatted file stops the gate. This repository has 40 main and 21 test Java files, written in parts by several authors and tools, so one enforced layout is what keeps them looking like one code base.

## Where it appears

| Path | What it does with Spotless |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`alias(libs.plugins.spotless)`) and configures it (`spotless { java { ... } }`). |
| `gradle/libs.versions.toml` | Holds the two versions: `spotless = "8.11.0"` and `googleJavaFormat = "2.0.0"`. |
| `src/main/java/**`, `src/test/java/**` | The files it checks (every Java source file of the project, 40 main files and 21 test files). |
| `.editorconfig` | Declares two-space indentation for Java, the same as the formatter (`editorconfig.md`). |

## How it works underneath

1. `./gradlew spotlessCheck` runs the task `spotlessJava`, which finds the Java source files and feeds each one through the formatter steps in the order they are listed: google-java-format, then removal of unused imports, then the end-of-file rule.
2. The result is compared with the file on disk. If they are identical, the task passes. If not, it fails and prints a **diff** (a list of the lines that would change; in the diff a middle dot stands for a space) and the sentence `Run './gradlew spotlessApply' to fix all violations.`
3. `./gradlew spotlessApply` writes the formatted result back into the files.
4. Spotless remembers what it checked, so unchanged files are skipped. A second run prints `UP-TO-DATE`.

google-java-format's rules, as they show in this repository's code: two spaces of indentation, four spaces for a continued line, a hard limit of 100 columns, one statement per line, braces always, a blank line between methods, and imports sorted in ASCII order with all `import static` lines first. Three lines of this repository are longer than 100 columns (101, 101 and 111), and each one is a single string literal (a piece of text in double quotes) that the formatter cannot break without changing the text: expected text in `RollingDeployTest.java` (line 79), `RuntimeApiTest.java` (line 75) and `RunReportTest.java` (line 162).

## Walkthrough of this repository's use

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}
```

- `spotless { ... }` is the extension block of the plugin.
- `java { ... }` configures the Java formatting target.
- `googleJavaFormat(libs.versions.googleJavaFormat.get())` adds the google-java-format step. The version comes from the catalog (`toml-version-catalog.md`), so changing the formatter means editing one line. The step also pins the style, because the formatter has only one.
- `removeUnusedImports()` deletes `import` lines whose names are never used. Spotless does this with its own step; the formatter alone does not.
- `endWithNewline()` makes sure the last character of each file is a line break.

The plugin itself is switched on in line 15 of the build file with `alias(libs.plugins.spotless)` (`gradle.md`).

### An experiment in a scratch copy

I copied the repository (without `build/`, `.gradle/`) to a scratch folder, so the real files stayed untouched. In `LifecycleState.java` of the `seatwave` package I changed the line `  READY,` into `  READY   ,` (extra spaces) and ran `./gradlew spotlessCheck`. It failed:

```
> Task :spotlessJava
> Task :spotlessJavaCheck FAILED
> The following files had format violations:
      src/main/java/io/plainround/seatwave/balancer/seatwave/LifecycleState.java
          @@ -11,7 +11,7 @@
           ···*/
           ··STARTING,
           ··/**·The·instance·admits·new·requests·and·its·readiness·check·can·pass.·*/
          -··READY···,
          +··READY,
           ··/**·Shutdown·has·begun:·no·new·request·is·admitted,·the·ones·in·flight·are·finishing.·*/
           ··DRAINING,
           ··/**·In-flight·requests·reached·zero,·or·the·drain·deadline·passed;·the·listeners·are·closing.·*/
  Run './gradlew spotlessApply' to fix all violations.
```

Then `./gradlew spotlessApply` rewrote the file (a `diff` against the saved original printed nothing, so the file was exactly as before), and a second `./gradlew spotlessCheck` passed with `BUILD SUCCESSFUL`. Lines that begin with a space in the diff are unchanged context, the line with `-` is what is on disk, and the line with `+` is what the formatter wants. A middle dot stands for a space.

## Try it yourself

All commands ran in a scratch copy of the repository on 11 November 2026.

1. Check the real repository (it must already be formatted):

```
./gradlew --offline spotlessCheck
```

   Expected: `> Task :spotlessJavaCheck` and `BUILD SUCCESSFUL`. On a repeat run each task says `UP-TO-DATE`.

2. List the Spotless tasks:

```
./gradlew --offline tasks --all | grep -i spotless
```

   You see `spotlessInstallGitPrePushHook`, `spotlessApply`, `spotlessCheck`, `spotlessDiagnose`, `spotlessJava`, `spotlessJavaApply`, `spotlessJavaCheck` and `spotlessJavaDiagnose`. `spotlessInstallGitPrePushHook` would write a Git hook; do not run it unless you want one.

3. Look for lines longer than 100 columns in the Java files (these are the three string literals named above):

```
awk 'length>100{print FILENAME": "FNR}' $(git ls-files 'src/*.java')
```

   Expected: three lines, in `RollingDeployTest.java` (79), `RuntimeApiTest.java` (75) and `RunReportTest.java` (162). Every other Java line is at most 100 columns.

4. Try the failure yourself, in a copy so the repository stays clean:

```
mkdir ../spotless-demo && rsync -a --exclude build --exclude .gradle --exclude .git --exclude tech ./ ../spotless-demo/ && cd ../spotless-demo
```

   Open `src/main/java/io/plainround/seatwave/balancer/seatwave/LifecycleState.java` there, change `  READY,` into `  READY   ,`, then run `./gradlew spotlessCheck` (expect `FAILED` and the diff) and `./gradlew spotlessApply` (expect `BUILD SUCCESSFUL` and the line back as it was). Delete the folder afterwards.

## Mistakes people make

- **Fixing style by hand.** Run `./gradlew spotlessApply`; it is faster and always right.
- **Committing without running `spotlessApply`.** The next `check` fails.
- **Expecting the formatter to fix everything.** It never renames a variable or deletes dead code. It only changes layout.
- **Formatting the Kotlin build files with it.** This build configures Spotless for Java only; `build.gradle.kts` is not formatted by it.
- **Using different formatter versions on different machines.** The version is pinned in the catalog, so everyone gets 2.0.0.
- **Hand-wrapping long strings.** The formatter does not break a string literal, so a long expected line stays long.

## Check yourself

1. *What is the difference between `spotlessCheck` and `spotlessApply`?* The first fails if any file would change. The second changes the files.
2. *Which version of the formatter does the build use, and where is it set?* 2.0.0, in `gradle/libs.versions.toml` (`googleJavaFormat`).
3. *Why can an unformatted file stop the build?* `check` depends on `spotlessCheck`.
4. *What does `removeUnusedImports()` do?* Deletes `import` lines that nothing uses.
5. *What indentation does the style use?* Two spaces, with four for a continued line.

## Further reading

- Spotless Gradle plugin documentation (8.x): <https://github.com/diffplug/spotless/tree/main/plugin-gradle>
- google-java-format project (2.0.0): <https://github.com/google/google-java-format>
