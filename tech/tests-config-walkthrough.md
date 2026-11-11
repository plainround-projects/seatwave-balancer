# The `config` tests, line by line

## What it is

Three test classes in the package `config`, 17 tests in all.

- `SettingsReaderTest` (6 tests): the typed reads of `SettingsReader`, the list of problems, file paths, and the two ways a settings file can fail to load.
- `SettingsFilesTest` (4 tests): what `SettingsFiles.load` prints and returns for a bad file, a missing file and a broken escape, and that a bug in the caller's own code is not hidden.
- `InfrastructureRulesTest` (7 tests): despite its package, it tests files that are not Java: the balancer's configuration `haproxy/haproxy.cfg`, the stack file `compose.yaml`, and the two Dockerfiles. It reads them as text and checks the rules that the module's measurements and its security depend on. It runs in every build, without Docker.

Terms you need (the test vocabulary, JUnit, assertions and temporary folders, is in `tests-seatwave-walkthrough-1.md`).

- A **balancer** (here HAProxy, see `haproxy.md`) receives each request and passes it to one of several servers. A **backend** is a named group of servers in its configuration. This project has five backends: `seatwave`, `seatwave_active_only`, `seatwave_leastconn`, `seatwave_source`, `seatwave_weighted`.
- A **health check** asks a server "are you able to serve?" at intervals. An **active** check sends its own probe. A **passive** check watches real traffic and marks a server bad when real requests fail.
- A **retry** sends a failed request to a server again. **Redispatch** means the retry goes to a different server.
- A **published port** is a container port made reachable from the host machine at a host address and port. In Compose a published port is written `"127.0.0.1:21000:8080"`: host address, host port, container port.
- A **digest** (`@sha256:...`) pins a container image to exact content. A tag such as `3.4.7` can be moved by whoever owns the image; a digest cannot.
- A **regular expression** (a `Pattern`) describes allowed text; `\s` means a space, `$` the end of the text.
- A **mutation** is a deliberate one-line change that breaks the code. The review of version 1.0.0 found tests that still passed after such a change. Version 1.1.0 added or strengthened tests until each listed mutation makes a test fail. The file `CHANGELOG.md` lists what changed; this note says, for each test, which defect it now catches.

## Why this repository uses it

The module's numbers come from specific lines in `haproxy.cfg`. In the 1.1.0 runs a killed instance behind active checks alone cost 7,653 failed requests and was taken out after 10.87 s; behind the production backend the same kill cost 0 and took 52.7 ms. If someone changes `inter 2s` to `inter 20s`, or adds a retry rule that retries a request the instance already served, those numbers become false and nothing would say so. These tests turn the important lines into checks that fail the build. The ARCHITECTURE table points to them as the enforcement of the rule "the balancer retries only what SeatWave did not serve" and of "no instance or database is reachable from outside the lab network".

The two settings tests keep the reading of settings files honest: every problem reported together, a missing file named, a broken escape turned into a message, and a real bug left visible.

## Where it appears

| File | Tests | What it checks |
|---|---|---|
| `config/SettingsReaderTest.java` | 6 | Problems collected in order; valid values; a missing file; a long file path; a control character; a broken escape. |
| `config/SettingsFilesTest.java` | 4 | The printed report, the missing-file message, the malformed escape, a bug in the reading function. |
| `config/InfrastructureRulesTest.java` | 7 | Rules of `haproxy.cfg`, `compose.yaml`, `Dockerfile` and `keepalived/Dockerfile`. |

## How it works underneath

**Settings tests.** They build a `Properties` object in memory, or write a small file in a temporary folder, and call the code under test. They compare the list of problem sentences or the text printed to a captured error stream.

**Infrastructure rules.** The Gradle build gives the test JVM two system properties holding the absolute paths of `db/schema.sql` and `haproxy/haproxy.cfg`. The test reads `haproxy.cfg` as one string, finds the block that starts with `backend <name>` and ends at the first blank line, and checks that block with plain text matches. For `compose.yaml` and the Dockerfiles it reads the files by their relative paths (the working folder of a Gradle test is the project folder). No HAProxy or Docker process is started. This is a **text-level** check: it proves the file says the right thing, not that HAProxy behaves that way. The behavior is measured in the labs.

**Inputs of the test task.** Gradle skips a task whose inputs have not changed since its last run; it prints `UP-TO-DATE`. In version 1.0.0 only `db/schema.sql` and `haproxy/haproxy.cfg` were declared as inputs of the `test` task, so a changed `compose.yaml` could pass `./gradlew check` without any test running. Version 1.1.0 declares every file a test reads (`build.gradle.kts`, the lines `inputs.file("compose.yaml")`, `inputs.file("Dockerfile")`, `inputs.dir("keepalived")` and `inputs.dir("config")`), so a change to any of them runs the tests again. "Try it yourself" step 3 shows it.

## Walkthrough of this repository's use

### `config/SettingsReaderTest.java`

```java
package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final Pattern WORD = Pattern.compile("[a-z]+");
```

The imports and the pattern `WORD`, one or more lowercase letters, used as an allowed shape in the tests. `StandardCharsets` and `Files` are new in 1.1.0: the last test writes a small file.

```java
  @Test
  void collectsOneProblemPerMissingOrInvalidKey() {
    Properties properties = new Properties();
    properties.setProperty("count", "seven");
    properties.setProperty("range", "99");
    properties.setProperty("word", "Upper");
    properties.setProperty("list", "one, Two");
    SettingsReader reader = new SettingsReader(properties);

    reader.intBetween("count", 1, 10);
    reader.intBetween("range", 1, 10);
    reader.textMatching("word", WORD);
    reader.listMatching("list", WORD);
    reader.text("absent");
    reader.addProblem("a and b must differ");

    assertThat(reader.problems())
        .containsExactly(
            "count must be a whole number, but was seven",
            "range must be from 1 to 10, but was 99",
            "word must match [a-z]+, but was Upper",
            "list has the item 'Two', which must match [a-z]+",
            "absent is missing",
            "a and b must differ");
  }
```

**`collectsOneProblemPerMissingOrInvalidKey`.** Four keys are set: `count=seven`, `range=99`, `word=Upper`, `list=one, Two`. Then one read of each kind is made, plus a missing key and a manual problem:

1. `intBetween("count", 1, 10)`: `seven` is not a number.
2. `intBetween("range", 1, 10)`: 99 is out of range.
3. `textMatching("word", WORD)`: `Upper` has a capital.
4. `listMatching("list", WORD)`: the second item, `Two`, has a capital.
5. `text("absent")`: no such key.
6. `addProblem("a and b must differ")`.

`containsExactly(...)` requires exactly these six sentences in this order. That pins every message and the order. The messages are `count must be a whole number, but was seven`, `range must be from 1 to 10, but was 99`, `word must match [a-z]+, but was Upper`, `list has the item 'Two', which must match [a-z]+`, `absent is missing` and `a and b must differ`.

```java
  @Test
  void readsValidValues() {
    Properties properties = new Properties();
    properties.setProperty("waitMillis", "250");
    properties.setProperty("names", "alpha, beta");
    SettingsReader reader = new SettingsReader(properties);

    assertThat(reader.millisBetween("waitMillis", 1, 1_000)).isEqualTo(Duration.ofMillis(250));
    assertThat(reader.listMatching("names", WORD)).containsExactly("alpha", "beta");
    assertThat(reader.problems()).isEmpty();
  }
```

**`readsValidValues`.** `waitMillis=250` read with `millisBetween("waitMillis", 1, 1000)` equals `Duration.ofMillis(250)`. `names=alpha, beta` read as a list gives `alpha` then `beta` (spaces trimmed). The list of problems stays empty.

```java
  @Test
  void saysThatAMissingFileDoesNotExist(@TempDir Path folder) {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> SettingsReader.load(missing))
        .isInstanceOf(IOException.class)
        .hasMessage("the settings file " + missing + " does not exist");
  }
```

**`saysThatAMissingFileDoesNotExist`.** `@TempDir Path folder` is a parameter here: JUnit passes a fresh temporary folder to the test method. `SettingsReader.load` on a file that is not there must throw an `IOException` whose message is exactly `the settings file <path> does not exist`.

```java
  @Test
  void acceptsAFilePathInADeepFolderWithSpacesAndPlusSigns() {
    String deepPath = "/checkouts/My Projects+Labs/" + "nested-folder/".repeat(20) + "ca.pem";
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", deepPath);
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(deepPath).hasSizeGreaterThan(220);
    assertThat(path).isEqualTo(deepPath);
    assertThat(reader.problems()).isEmpty();
  }
```

**`acceptsAFilePathInADeepFolderWithSpacesAndPlusSigns`.** `filePath(key)` reads a value that names a file. Its only rule is the pattern `[^\p{Cntrl}]{1,4096}`: 1 to 4,096 characters, none of them a **control character** (an invisible character such as a tab or a line break). The test builds a path the way a real checkout can look: a folder name with a space (`My Projects+Labs`), a plus sign, and the text `nested-folder/` repeated 20 times by `String.repeat(20)`, then `ca.pem`.

- `assertThat(deepPath).hasSizeGreaterThan(220)` first checks the test itself: the path really is long (more than 220 characters), so an older, shorter limit would have refused it.
- `assertThat(path).isEqualTo(deepPath)`: the value comes back unchanged.
- `reader.problems()` stays empty.

This guards against a pattern that is too strict for real folder names.

```java
  @Test
  void refusesAFilePathWithAControlCharacter() {
    Properties properties = new Properties();
    properties.setProperty("tls.authorityPem", "build/tls/ca\t.pem");
    SettingsReader reader = new SettingsReader(properties);

    String path = reader.filePath("tls.authorityPem");

    assertThat(path).isEmpty();
    assertThat(reader.problems())
        .singleElement()
        .asString()
        .startsWith("tls.authorityPem must match");
  }
```

**`refusesAFilePathWithAControlCharacter`.** The value `build/tls/ca\t.pem` holds a tab (`\t` in Java text is one tab character) in the middle. A tab in a file name is almost always a copy-and-paste mistake.

- `filePath` returns the empty string, the reader's sign for "no usable value".
- `singleElement()` requires that the list of problems holds exactly one item and moves the check to that item. `asString()` treats it as text, so `startsWith("tls.authorityPem must match")` can check how the sentence begins. The rest of the sentence (the pattern and the value) is not pinned, so the test does not break when the wording after the key changes.

```java
  @Test
  void reportsABrokenEscapeAsAnUnreadableSettingsFile(@TempDir Path folder) throws IOException {
    Path broken =
        Files.writeString(
            folder.resolve("broken.properties"),
            "http.bindAddress=\\u12\n",
            StandardCharsets.UTF_8);

    assertThatThrownBy(() -> SettingsReader.load(broken))
        .isInstanceOf(IOException.class)
        .hasMessage(
            "the settings file "
                + broken
                + " is not a valid properties file: Malformed \\uxxxx encoding.");
  }
}
```

**`reportsABrokenEscapeAsAnUnreadableSettingsFile`.** A properties file may write any character as `\u` and four hexadecimal digits (`\u0041` is `A`). The file here holds `http.bindAddress=\u12`: only two digits. (In Java source the backslash must be doubled, so the test writes `"http.bindAddress=\\u12\n"`.) The Java library's `Properties.load` refuses it with an `IllegalArgumentException`, an **unchecked** exception: Java does not force any caller to handle it, so without care it would end the program with a stack trace.

`SettingsReader.load` catches that exception and throws an `IOException` instead, the same kind it throws for a missing file. The test pins the whole message: `the settings file <path> is not a valid properties file: Malformed \uxxxx encoding.` The last part, `Malformed \uxxxx encoding.`, is the Java library's own message, copied in. So a broken file now gives one clear sentence that names the file.

### `config/SettingsFilesTest.java`

```java
package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFilesTest {

  @TempDir Path folder;
```

The imports and a `@TempDir` field. `SettingsFiles` prints to a `PrintStream`; the tests pass one that writes into a `ByteArrayOutputStream`, then read it back as text. `assertThatThrownBy` is new in 1.1.0, for the last test.

```java
  @Test
  void printsEveryProblemOfTheFile() throws Exception {
    Path file =
        Files.writeString(folder.resolve("bad.properties"), "a=1\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            file,
            reader -> Result.error(List.of("one", "two")),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("2 problem(s)", "  one", "  two");
  }
```

**`printsEveryProblemOfTheFile`.** A valid file (`a=1`) is written. The reading function ignores it and returns `Result.error(List.of("one", "two"))`. The result must be empty, and the printed text must contain `2 problem(s)`, `  one` and `  two`. The two spaces before each word show that each problem is indented under the heading.

```java
  @Test
  void saysWhichFileIsMissing() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            folder.resolve("absent.properties"),
            reader -> Result.success("unused"),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("absent.properties does not exist");
  }
```

**`saysWhichFileIsMissing`.** A path in the temporary folder that does not exist. The result is empty and the printed text contains `absent.properties does not exist`.

```java
  @Test
  void reportsAMalformedEscapeAsAProblemNotACrash() throws Exception {
    Path file =
        Files.writeString(
            folder.resolve("escape.properties"), "a=\\uZZZZ\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    Optional<String> loaded =
        SettingsFiles.load(
            file,
            reader -> Result.success("unused"),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(loaded).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("escape.properties is not a valid properties file");
  }
```

**`reportsAMalformedEscapeAsAProblemNotACrash`.** The file holds `a=\uZZZZ` (written in Java as `"a=\\uZZZZ\n"`): a unicode escape with letters that are not hexadecimal. `SettingsReader.load` turns the library's `IllegalArgumentException` into an `IOException` (the test above), and `SettingsFiles.load` prints that message. The result must be empty and the text must contain `escape.properties is not a valid properties file`. In version 1.0.0 the message said `cannot be read`, which wrongly suggested a permission problem; the new words say what is wrong with the file.

```java
  @Test
  void letsABugInTheReadingFunctionThrough() throws Exception {
    Path file =
        Files.writeString(folder.resolve("good.properties"), "a=1\n", StandardCharsets.UTF_8);
    ByteArrayOutputStream err = new ByteArrayOutputStream();

    assertThatThrownBy(() ->
            SettingsFiles.load(
                file,
                reader -> {
                  throw new IllegalArgumentException("a bug in the settings record");
                },
                new PrintStream(err, true, StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a bug in the settings record");
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
  }
}
```

**`letsABugInTheReadingFunctionThrough`.** The other side of the same rule. The file is fine (`a=1`), but the reading function itself throws `IllegalArgumentException("a bug in the settings record")`. The lambda `reader -> { throw ...; }` is a function whose body only throws.

- `assertThatThrownBy(() -> SettingsFiles.load(...))` runs the call and expects it to throw. `isInstanceOf(IllegalArgumentException.class)` and `hasMessage(...)` require the very exception the function threw.
- The captured error text must be empty: nothing was printed.

Why it matters: in version 1.0.0, `SettingsFiles.load` caught every `IllegalArgumentException`, so a programming mistake inside a settings record was printed as if the file were broken. Now only file problems are caught (`IOException`), and a bug stays a bug, with its stack trace.

Neither class checks the exit status of a program that uses `SettingsFiles`; they test the method only.

### `config/InfrastructureRulesTest.java`

```java
package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
```

The imports. `Matcher` runs a pattern over text and finds every match.

```java
/**
 * The rules of haproxy.cfg and compose.yaml that the module's numbers depend on, checked on every
 * build without Docker. Each rule reads one backend's own block, so a value moved to another
 * backend or written in another unit fails it.
 */
class InfrastructureRulesTest {

  private static final Pattern PUBLISHED = Pattern.compile("- \"([0-9.]+):([0-9]+):([0-9]+)\"");
  // FROM name:tag@sha256:<64 hex digits>, with an optional stage name.
  private static final Pattern PINNED_FROM =
      Pattern.compile("FROM [a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}( AS [a-z]+)?");
  // An image: line (not one that refers to an anchor), or the anchor that names the balancers'.
  private static final Pattern IMAGE =
      Pattern.compile("(?m)^ *(?:image:|x-haproxy-image: &haproxy-image) ([^*\\s]\\S*)$");
  private static final int FIRST_PORT = 21000;
  private static final int LAST_PORT = 21099;

```

The class comment says what the class is for: "The rules of haproxy.cfg and compose.yaml that the module's numbers depend on, checked on every build without Docker. Each rule reads one backend's own block, so a value moved to another backend or written in another unit fails it."

- `PUBLISHED` is a pattern for a Compose port line: `- "` then an address of digits and dots, a colon, a host port, a colon, a container port, and `"`. Each pair of brackets is a **capture group**, so a match can give back the three parts.
- `PINNED_FROM` is the shape of a pinned `FROM` line of a Dockerfile. Piece by piece: `FROM `; an image name of lowercase letters, digits, dots, slashes and hyphens (`[a-z0-9./-]+`); a colon and a tag (`[A-Za-z0-9._-]+`, for example `25.0.5_8-jdk-noble`); `@sha256:` and exactly 64 hexadecimal digits (`[0-9a-f]{64}`); then, optionally, ` AS ` and a stage name (`( AS [a-z]+)?`; the `?` makes the bracketed part optional). The comment above it says the same in one line.
- `IMAGE` finds every image a Compose file names. `(?m)` turns on **multi-line mode**: `^` and `$` then match at the start and end of every line, not only of the whole text. `^ *` allows any indentation. `(?:image:|x-haproxy-image: &haproxy-image)` is a group that does not capture (`?:`) and accepts either an `image:` key or the line `x-haproxy-image: &haproxy-image`, where `compose.yaml` defines the balancers' image once as an **anchor** (a named value that other places reuse with `*haproxy-image`). Then a space and the captured name, `([^*\s]\S*)`: the first character may be neither `*` nor a space, so a line such as `image: *haproxy-image`, which only refers to the anchor, is skipped and the image is not counted twice. `\S*` is the rest of the name up to the end of the line.
- `FIRST_PORT` 21000 and `LAST_PORT` 21099 are the module's range of host ports.

```java
  @Test
  void checksReadinessEveryTwoSecondsAndNeedsThreeFailures() throws IOException {
    for (String backend : List.of("seatwave", "seatwave_active_only")) {
      String block = backend(backend);

      assertThat(block).contains("http-check send meth GET uri /ready\n");
      assertThat(block)
          .containsPattern("default-server check port 8081 inter 2s fall 3 rise 2(\\s|$)");
    }
  }
```

**`checksReadinessEveryTwoSecondsAndNeedsThreeFailures`.** For both backends that have their own checks, `seatwave` and `seatwave_active_only`:

- the block must contain `http-check send meth GET uri /ready` followed by a line break: the health check is a `GET` of `/ready`, not of `/health`;
- the block must match the pattern `default-server check port 8081 inter 2s fall 3 rise 2` followed by a space or the end of the text.

What those words mean in HAProxy: `check` turns on the active check; `port 8081` sends it to the instance's admin port; `inter 2s` is one probe every two seconds; `fall 3` is three failed probes in a row before the server is marked down; `rise 2` is two good probes before it is marked up again. The `(\s|$)` ending stops a longer number: `rise 20` would not match. The pattern allows more words after `rise 2` (for example `slowstart`), because the production backend has them. On the backend with only this check, the 1.1.0 kill run measured 10.87 s from the last answer of a killed instance to its removal.

The loop goes over two backends. If one is missing the helper `backend(...)` fails with a message that names it. The mutation check confirms the test catches two quiet edits: `inter 2s` written as `inter 2000` (HAProxy reads a bare number as milliseconds, so the meaning stays but the line no longer says what the module documents), and `fall 3` changed to `fall 2` on the active-only backend.

```java
  @Test
  void retriesOnlyWhatIsSafeToRepeat() throws IOException {
    String production = backend("seatwave");

    assertThat(production).contains("\n  retry-on conn-failure empty-response 503\n");
    assertThat(production).doesNotContain("response-timeout").doesNotContain("junk-response");
    assertThat(production)
        .contains("slowstart 10s")
        .contains("observe layer4 error-limit 3 on-error mark-down");
  }
```

**`retriesOnlyWhatIsSafeToRepeat`.** (Renamed in 1.1.0 from `retriesOnlyWhatSeatWaveDidNotServe`: a `503` from SeatWave is an answer the instance served, but one it promises did nothing, so the new name is the accurate one.) On the production backend `seatwave`:

- the block must contain a line that is exactly `  retry-on conn-failure empty-response 503`. The balancer retries only when it could not connect (`conn-failure`), when the connection ended before any byte of an answer (`empty-response`), or when the instance itself said `503`, which `HoldRoutes.notAdmitted` uses to mean "nothing was done". The leading spaces and the line break make the match whole-line.
- it must not contain `response-timeout` (retry after a slow answer) nor `junk-response` (retry after a garbage answer): retrying those could repeat work the instance had already started. The mutation check adds `response-timeout` to the line, and this test fails.
- it must contain `slowstart 10s` and `observe layer4 error-limit 3 on-error mark-down`. `slowstart 10s` ramps a returning server from a small share to its full share over ten seconds. `observe layer4 error-limit 3 on-error mark-down` is the passive check: after three connection errors in a row on real traffic, mark the server down at once. This is what made the production backend take a killed instance out 52.7 ms after its last answer in the 1.1.0 run.

```java
  @Test
  void keepsTheActiveOnlyBackendFreeOfRetriesAndPassiveChecks() throws IOException {
    String simple = backend("seatwave_active_only");

    assertThat(simple).contains("\n  retries 0\n").contains("\n  retry-on none\n");
    assertThat(simple)
        .doesNotContain("redispatch")
        .doesNotContain("observe")
        .doesNotContain("slowstart");
  }
```

**`keepsTheActiveOnlyBackendFreeOfRetriesAndPassiveChecks`.** `seatwave_active_only` is the lab's "what active checks alone cost" backend. Its block must contain the lines `retries 0` and `retry-on none` and must not contain `redispatch`, `observe` or `slowstart`. A "no retry, no passive check" backend is what makes the 7,653 failed requests of the active-only kill run happen; if someone added a retry, the lab would show a better number for the wrong reason. The mutation check changes `retries 0` to `retries 1`, and this test fails.

```java
  @Test
  void letsTheAlgorithmBackendsFollowProductionsChecks() throws IOException {
    for (String backend : List.of("seatwave_leastconn", "seatwave_source", "seatwave_weighted")) {
      assertThat(backend(backend))
          .contains("track seatwave/seatwave-1")
          .contains("track seatwave/seatwave-2")
          .contains("track seatwave/seatwave-3")
          .doesNotContain(" check");
    }
  }
```

**`letsTheAlgorithmBackendsFollowProductionsChecks`.** The three backends that only show an algorithm (`seatwave_leastconn`, `seatwave_source`, `seatwave_weighted`) must contain `track seatwave/seatwave-1`, `-2` and `-3`, and must not contain the text ` check`. `track seatwave/seatwave-1` means: this server has no checks of its own; use the state of `seatwave-1` in backend `seatwave`. That keeps the comparison between algorithms fair, since all five backends see the same health. The string ` check` begins with a space so that it does not match words such as `checks` in a comment. The mutation check makes the least-connections backend track `seatwave_active_only/seatwave-2` instead, and this test fails.

```java
  @Test
  void publishesOnlyBalancerPortsOnLoopbackInsideTheModulesRange() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    Matcher published = PUBLISHED.matcher(compose);
    int count = 0;
    while (published.find()) {
      count++;
      assertThat(published.group(1)).isEqualTo("127.0.0.1");
      assertThat(Integer.parseInt(published.group(2))).isBetween(FIRST_PORT, LAST_PORT);
      // The container side is a balancer port: requests 8080 to 8083, status 8404, runtime 9999.
      assertThat(published.group(3)).isIn("8080", "8081", "8082", "8083", "8404", "9999");
    }
    assertThat(count).isEqualTo(12);
    for (String service : List.of("postgres", "seatwave-1", "seatwave-2", "seatwave-3")) {
      assertThat(serviceBlock(compose, service)).doesNotContain("ports:");
    }
  }
```

**`publishesOnlyBalancerPortsOnLoopbackInsideTheModulesRange`.** The security rule of the stack, as a test.

1. Read `compose.yaml` as text and find every `- "a:b:c"` line.
2. For each: the host address must be `127.0.0.1` (only this machine can reach it); the host port must be between 21000 and 21099 (`isBetween` includes both ends); the container port must be one of `8080`, `8081`, `8082`, `8083`, `8404` or `9999`. The comment names them: requests 8080 to 8083, status 8404, runtime API 9999. In other words, only balancer ports.
3. The number of published ports must be exactly 12: six for each of the two balancers.
4. For `postgres`, `seatwave-1`, `seatwave-2` and `seatwave-3`, the service's block in the file must not contain the word `ports:`. No instance and not the database is reachable from outside the lab network.

The mutation check confirms each part: a balancer port published on `0.0.0.0` (every address of the machine), one published port deleted (the count drops to 11), a host port of `22003` (outside the range), and a `ports:` list added to `seatwave-1` each make this test fail. The count of 12 is a hard-coded number: adding a thirteenth legitimate port means changing the test on purpose.

```java
  @Test
  void pinsEveryImageByTagAndDigest() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);

    // Every FROM line of both Dockerfiles, the second stage's included.
    assertThat(fromLines("Dockerfile"))
        .hasSize(2)
        .allMatch(line -> PINNED_FROM.matcher(line).matches(), "pinned by tag and digest");
    assertThat(fromLines("keepalived/Dockerfile"))
        .hasSize(1)
        .allMatch(line -> PINNED_FROM.matcher(line).matches(), "pinned by tag and digest");
    // Every image compose.yaml pulls; the ones this project builds itself carry its own name.
    Matcher image = IMAGE.matcher(compose);
    int pulled = 0;
    while (image.find()) {
      String name = image.group(1);
      if (!name.startsWith("seatwave-balancer-")) {
        pulled++;
        assertThat(name).matches("[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}");
      }
    }
    assertThat(pulled).isEqualTo(2);
    assertThat(compose)
        .doesNotContain("privileged: true")
        .doesNotContain("network_mode: host")
        .doesNotContain("docker.sock");
  }
```

**`pinsEveryImageByTagAndDigest`.** In version 1.0.0 this test checked two named images in `compose.yaml` and one `FROM` line in each Dockerfile, and it missed the second stage of `Dockerfile` (`FROM ubuntu:noble@sha256:...`): unpinning that line passed. Version 1.1.0 checks every line, whatever its name.

1. `fromLines("Dockerfile")` (a helper below) returns every line of the file that starts with `FROM `. `hasSize(2)` requires both stages: the build stage (`eclipse-temurin`, named `AS runtime`) and the final stage (`ubuntu:noble`). `allMatch(predicate, "pinned by tag and digest")` requires that every line matches `PINNED_FROM`; the second argument is the description AssertJ prints when a line does not. `matcher(line).matches()` demands that the whole line fits the pattern, not only a part of it.
2. The same for `keepalived/Dockerfile`, which has one `FROM` line (`alpine:3.24.4`).
3. The loop over `IMAGE` visits every image `compose.yaml` names. A name that starts with `seatwave-balancer-` is an image this project builds itself (`seatwave-balancer-seatwave:1.1.0`, `seatwave-balancer-keepalived:1.1.0`); it has no digest, because it exists only on this machine. Every other name is pulled from a registry, so it must match name, colon, tag, `@sha256:` and 64 hexadecimal digits. `pulled` counts them and must end at exactly 2: the balancers' HAProxy image (through the anchor line) and `postgres:18.6`. The count stops the loop from passing with zero matches, which would happen if the pattern stopped finding anything.
4. `compose.yaml` contains none of `privileged: true`, `network_mode: host` or `docker.sock`: no full-power container, no sharing of the host's network, no access to the Docker control socket.

The mutation check removes the digest of the `ubuntu`, the `alpine` and the `postgres` lines, one at a time; each makes this test fail.

```java
  @Test
  void limitsHowLongAClientMaySendItsRequestHead() throws IOException {
    String config =
        Files.readString(Path.of(System.getProperty("seatwave.haproxy")), StandardCharsets.UTF_8);
    String defaults =
        config.substring(config.indexOf("\ndefaults\n"), config.indexOf("\nfrontend "));

    // In the defaults, so every frontend has it, and shorter than the 30 s client timeout.
    assertThat(defaults).containsPattern("\n  timeout http-request 5s\n");
  }
```

**`limitsHowLongAClientMaySendItsRequestHead`.** New in 1.1.0. The **request head** is the first part of an HTTP request: the request line and the headers, before the body. `timeout http-request 5s` tells HAProxy that the whole head must arrive within 5 s. Without it, a client could send one byte every few seconds and keep a connection open for as long as it likes, because each byte would restart the 30 s client timeout. That is called a **slow-headers attack**.

- The test reads `haproxy.cfg` and cuts out the `defaults` section: from the line `defaults` to the first line that starts with `frontend `. Settings in `defaults` apply to every frontend that follows.
- `containsPattern("\n  timeout http-request 5s\n")` requires the whole line, with its two-space indentation, inside that section. The comment states the second half of the rule: 5 s is shorter than the 30 s client timeout.

The mutation check deletes the line, and this test fails.

```java
  private static List<String> fromLines(String dockerfile) throws IOException {
    return Files.readAllLines(Path.of(dockerfile), StandardCharsets.UTF_8).stream()
        .filter(line -> line.startsWith("FROM "))
        .toList();
  }
```

`fromLines(dockerfile)` reads a file as a list of lines (`Files.readAllLines`), turns the list into a **stream** (a pipeline that hands each item to the next step), keeps the lines that start with `FROM ` (`filter`), and collects the kept lines into a new list (`toList()`).

```java
  private static String backend(String name) throws IOException {
    String config =
        Files.readString(Path.of(System.getProperty("seatwave.haproxy")), StandardCharsets.UTF_8);
    int start = config.indexOf("\nbackend " + name + "\n");
    assertThat(start).as("backend %s in haproxy.cfg", name).isNotNegative();
    int end = config.indexOf("\n\n", start + 1);
    return config.substring(start, end < 0 ? config.length() : end) + "\n";
  }
```

`backend(name)` is the helper.

1. Read the whole `haproxy.cfg`, whose path comes from the system property `seatwave.haproxy`.
2. `config.indexOf("\nbackend " + name + "\n")` finds the start of the block. The line breaks on both sides make `seatwave` not match `seatwave_active_only`.
3. `assertThat(start).as("backend %s in haproxy.cfg", name).isNotNegative()` fails with a readable message if the block is missing (`indexOf` returns -1 when not found). `as(...)` names the check in the failure message.
4. The block ends at the next blank line (`indexOf("\n\n", start + 1)`), or at the end of the file. It adds a final line break so that the last line also ends with `\n`.

A blank line inside a backend block would cut the block short, and the rules after it would be checked against less text. The file has no blank lines inside its backends today.

```java
  private static String serviceBlock(String compose, String service) {
    int start = compose.indexOf("\n  " + service + ":\n");
    assertThat(start).as("service %s in compose.yaml", service).isNotNegative();
    int end = compose.indexOf("\n\n", start + 1);
    return compose.substring(start, end);
  }
}
```

`serviceBlock(compose, service)` does the same for a Compose service: it finds `"\n  " + service + ":\n"` (two spaces and the name, a colon) and cuts at the next blank line.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the 17 tests of this package.

```bash
./gradlew --offline test --tests '*SettingsReaderTest' --tests '*SettingsFilesTest' --tests '*InfrastructureRulesTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Seventeen lines end in `PASSED` and the last line is `BUILD SUCCESSFUL`. Add `cleanTest` before `test` if Gradle says the task is up to date.

2. Prove a rule is enforced: break `haproxy.cfg` in a copy and watch a test fail.

```bash
rm -rf build/cfg-check && mkdir build/cfg-check
rsync -a --exclude build --exclude .gradle ./ build/cfg-check/
cd build/cfg-check
sed -i.bak 's/ slowstart 10s observe/ observe/' haproxy/haproxy.cfg
./gradlew --offline test --tests '*InfrastructureRulesTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/cfg-check
```

The test `retriesOnlyWhatIsSafeToRepeat` fails, because `slowstart 10s` is gone from the production backend. Deleting the line `  timeout http-request 5s` instead makes `limitsHowLongAClientMaySendItsRequestHead` fail.

3. See that a change to a file the tests read runs them again. Edit a settings file in a copy and run the normal command, with no `cleanTest`.

```bash
rm -rf build/input-check && mkdir build/input-check
rsync -a --exclude build --exclude .gradle ./ build/input-check/
cd build/input-check
./gradlew --offline test --tests '*ServiceSettingsTest' 2>&1 | grep -E "BUILD"
sed -i.bak 's/^db.poolSize=16/db.poolSize=0/' config/seatwave-container.properties
./gradlew --offline test --tests '*ServiceSettingsTest' 2>&1 | grep -E ":test|FAILED|BUILD"
cd ../.. && rm -rf build/input-check
```

The first run passes. The second, because `config/` is now an input of the test task, runs the tests again and prints `readsTheContainerSettingsShippedWithTheProject() FAILED`, `givesDockerStopMoreTimeThanTheWorstCaseShutdown() FAILED` and `BUILD FAILED`. (In version 1.0.0 it printed `> Task :test UP-TO-DATE` and `BUILD SUCCESSFUL`.)

4. Read the rules the tests protect. These commands print the lines the tests look at.

```bash
grep -n "retry-on\|default-server\|retries\|http-request" haproxy/haproxy.cfg
grep -c '^      - "127.0.0.1:' compose.yaml
grep -n '^FROM' Dockerfile keepalived/Dockerfile
```

The first lists the retry and check lines of all backends and the `timeout http-request 5s` line. The second prints `12`, the published port lines the test counts. The third prints the three `FROM` lines, each with `@sha256:` and 64 hexadecimal digits.

## Mistakes people make

- **Trusting a text check as if it were a behavior check.** The file can say `inter 2s` and HAProxy can still be slow. The labs measure the behavior; these tests only keep the file honest.
- **Matching a word inside a longer word.** `rise 2` also starts `rise 20`. The `(\s|$)` ending and the leading spaces in the whole-line checks prevent that.
- **Checking the wrong block.** A rule that reads the whole file would pass when the value sits in another backend. Each rule here reads one backend's block, and the request-head rule reads the `defaults` section.
- **Hard-coded counts.** `12` published ports, `2` `FROM` lines and `2` pulled images are exact. That is the point, but they must change on purpose.
- **Inputs not declared to the build.** A test that reads a file Gradle does not know about can be skipped as up to date. Version 1.1.0 declares them all.
- **Claiming "every" and checking some.** Version 1.0.0's `pinsEveryImageByTagAndDigest` skipped the `ubuntu:noble` stage. Version 1.1.0 checks every `FROM` line and every pulled image, and counts them.
- **Catching too much.** Turning every `IllegalArgumentException` into "the file is broken" hides bugs. `letsABugInTheReadingFunctionThrough` keeps the catch narrow.
- **A test that cannot fail.** Break the thing in a copy and check that the test fails, as steps 2 and 3 do and as the module's mutation check does for 45 one-line changes.

## Check yourself

1. Why does `backend(name)` search for `"\nbackend " + name + "\n"` with line breaks on both sides?
   Answer: so `seatwave` does not match the start of `seatwave_active_only`.
2. What does `observe layer4 error-limit 3 on-error mark-down` do, and which test protects it?
   Answer: after three connection errors in a row on real traffic, the server is marked down at once (a passive check). `retriesOnlyWhatIsSafeToRepeat` requires the text on the production backend.
3. Why does the `IMAGE` pattern refuse a name that starts with `*`?
   Answer: `image: *haproxy-image` only refers to the anchor defined on the `x-haproxy-image` line; counting it would count the HAProxy image twice.
4. What is a slow-headers attack, and which line stops it?
   Answer: a client that sends its request head a byte at a time to hold a connection open; `timeout http-request 5s` in the `defaults` section ends it after 5 s.
5. Why must `letsABugInTheReadingFunctionThrough` see an exception and an empty error text?
   Answer: a bug in the code that reads settings must not be printed as a problem of the file; only file problems (`IOException`) are caught and printed.

## Further reading

- HAProxy 3.4 configuration manual (`check`, `inter`, `fall`, `rise`, `slowstart`, `observe`, `retry-on`, `option redispatch`, `track`, `timeout http-request`): https://docs.haproxy.org/3.4/configuration.html
- Docker Compose file reference, `ports`: https://docs.docker.com/reference/compose-file/services/#ports
- Docker documentation, pin base images by digest: https://docs.docker.com/build/building/best-practices/#pin-base-image-versions
- Java 25 API, `java.util.Properties.load` (escapes): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- Java 25 API, `java.util.regex.Pattern` (`(?m)`, `\p{Cntrl}`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- Gradle 9.9.1 user guide, up-to-date checks and declaring task inputs: https://docs.gradle.org/9.9.1/userguide/incremental_build.html
- JUnit 6 user guide: https://docs.junit.org/current/user-guide/
- AssertJ core documentation: https://assertj.github.io/doc/
