# The `config` tests, line by line

## What it is

Three test classes in the package `config`, 12 tests in all.

- `SettingsReaderTest` (3 tests): the typed reads of `SettingsReader` and the list of problems.
- `SettingsFilesTest` (3 tests): what `SettingsFiles.load` prints and returns for a bad file, a missing file and a broken escape.
- `InfrastructureRulesTest` (6 tests): despite its package, it tests two files that are not Java: the balancer's configuration `haproxy/haproxy.cfg` and the stack file `compose.yaml`, plus two Dockerfiles. It reads them as text and checks the rules that the module's measurements depend on. It runs in every build, without Docker.

Terms you need (the test vocabulary, JUnit, assertions and temporary folders, is in `tests-seatwave-walkthrough-1.md`).

- A **balancer** (here HAProxy, see `haproxy.md`) receives each request and passes it to one of several servers. A **backend** is a named group of servers in its configuration. This project has five backends: `seatwave`, `seatwave_active_only`, `seatwave_leastconn`, `seatwave_source`, `seatwave_weighted`.
- A **health check** asks a server "are you able to serve?" at intervals. An **active** check sends its own probe. A **passive** check watches real traffic and marks a server bad when real requests fail.
- A **retry** sends a failed request to a server again. **Redispatch** means the retry goes to a different server.
- A **published port** is a container port made reachable from the host machine at a host address and port. In Compose a published port is written `"127.0.0.1:21000:8080"`: host address, host port, container port.
- A **digest** (`@sha256:...`) pins a container image to exact content. A tag such as `3.4.7` can be moved by whoever owns the image; a digest cannot.
- A **regular expression** (a `Pattern`) describes allowed text; `\s` means a space, `$` the end of the text.

## Why this repository uses it

The module's numbers (11,777 failed requests behind active checks alone, 0 behind the production backend, detection in 13.4 ms) come from specific lines in `haproxy.cfg`. If someone changes `inter 2s` to `inter 20s`, or adds a retry rule that retries a request the instance already served, the numbers in the README become false and nothing would say so. These tests turn the important lines into checks that fail the build. The ARCHITECTURE table points to them as the enforcement of the rule "the balancer retries only what SeatWave did not serve" and of "no instance or database is reachable from outside the lab network".

The two settings tests keep the reading of settings files honest: every problem reported together, a missing file named, and a broken escape turned into a message.

## Where it appears

| File | Tests | What it checks |
|---|---|---|
| `config/SettingsReaderTest.java` | 3 | Problems collected in order; valid values; a missing file. |
| `config/SettingsFilesTest.java` | 3 | The printed report, the missing-file message, the malformed escape. |
| `config/InfrastructureRulesTest.java` | 6 | Rules of `haproxy.cfg`, `compose.yaml`, `Dockerfile` and `keepalived/Dockerfile`. |

## How it works underneath

**Settings tests.** They build a `Properties` object in memory, or write a small file in a temporary folder, and call the code under test. They compare the list of problem sentences or the text printed to a captured error stream.

**Infrastructure rules.** The Gradle build gives the test JVM two system properties holding the absolute paths of `db/schema.sql` and `haproxy/haproxy.cfg`. The test reads `haproxy.cfg` as one string, finds the block that starts with `backend <name>` and ends at the first blank line, and checks that block with plain text matches. For `compose.yaml` and the Dockerfiles it reads the files by their relative paths (the working folder of a Gradle test is the project folder). No HAProxy or Docker process is started. This is a **text-level** check: it proves the file says the right thing, not that HAProxy behaves that way. The behavior is measured in the labs.

## Walkthrough of this repository's use

### `config/SettingsReaderTest.java`

```java
package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final Pattern WORD = Pattern.compile("[a-z]+");
```

The imports and the pattern `WORD`, one or more lowercase letters, used as an allowed shape in the tests.

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
}
```

**`saysThatAMissingFileDoesNotExist`.** `@TempDir Path folder` is a parameter here: JUnit passes a fresh temporary folder to the test method. `SettingsReader.load` on a file that is not there must throw an `IOException` whose message is exactly `the settings file <path> does not exist`.

What these tests leave out: `text` with a blank value, a list with a trailing comma, an empty list value, `intBetween` at the exact limits, and a null argument. JaCoCo marks two lines of `SettingsReader` as never run (the `text.isEmpty()` return in `intBetween` and the empty-list return in `listMatching`).

### `config/SettingsFilesTest.java`

```java
package io.plainround.seatwave.balancer.config;

import static org.assertj.core.api.Assertions.assertThat;

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

The imports and a `@TempDir` field. `SettingsFiles` prints to a `PrintStream`; the tests pass one that writes into a `ByteArrayOutputStream`, then read it back as text.

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
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("escape.properties cannot be read");
  }
}
```

**`reportsAMalformedEscapeAsAProblemNotACrash`.** The file holds `a=\uZZZZ` (written in Java as `"a=\\uZZZZ\n"`): a unicode escape with letters that are not hexadecimal. `Properties.load` throws `IllegalArgumentException`. The result must be empty and the text must contain `escape.properties cannot be read`. This pins the catch that the Module 9 review asked for.

Neither test checks that the program using `SettingsFiles` exits with status 2; they test the method only. I ran the real program on a broken file and it exited with status 2.

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
  private static final int FIRST_PORT = 21000;
  private static final int LAST_PORT = 21099;

  @Test
```

The class comment says what the class is for: "The rules of haproxy.cfg and compose.yaml that the module's numbers depend on, checked on every build without Docker. Each rule reads one backend's own block, so a value moved to another backend or written in another unit fails it."

- `PUBLISHED` is a pattern for a Compose port line: `- "` then an address of digits and dots, a colon, a host port, a colon, a container port, and `"`. Each pair of brackets is a **capture group**, so a match can give back the three parts.
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

What those words mean in HAProxy: `check` turns on the active check; `port 8081` sends it to the instance's admin port; `inter 2s` is one probe every two seconds; `fall 3` is three failed probes in a row before the server is marked down; `rise 2` is two good probes before it is marked up again. The `(\s|$)` ending stops a longer number: `rise 20` would not match. The pattern allows more words after `rise 2` (for example `slowstart 10s`), because the production backend has them. The README measured 11.8 s from the last answer of a killed instance to its removal on the backend with only this check.

The loop goes over two backends. If one is missing the helper `backend(...)` fails with a message that names it.

```java
  @Test
  void retriesOnlyWhatSeatWaveDidNotServe() throws IOException {
    String production = backend("seatwave");

    assertThat(production).contains("\n  retry-on conn-failure empty-response 503\n");
    assertThat(production).doesNotContain("response-timeout").doesNotContain("junk-response");
    assertThat(production)
        .contains("slowstart 10s")
        .contains("observe layer4 error-limit 3 on-error mark-down");
  }
```

**`retriesOnlyWhatSeatWaveDidNotServe`.** On the production backend `seatwave`:

- the block must contain a line that is exactly `  retry-on conn-failure empty-response 503`. The balancer retries only when it could not connect (`conn-failure`), when the connection ended before any byte of an answer (`empty-response`), or when the instance itself said `503`, which `HoldRoutes.notAdmitted` uses to mean "nothing was done". The leading spaces and the line break make the match whole-line.
- it must not contain `response-timeout` (retry after a slow answer) nor `junk-response` (retry after a garbage answer): retrying those could repeat work the instance had already started.
- it must contain `slowstart 10s` and `observe layer4 error-limit 3 on-error mark-down`. `slowstart 10s` ramps a returning server from a small share to its full share over ten seconds. `observe layer4 error-limit 3 on-error mark-down` is the passive check: after three connection errors in a row on real traffic, mark the server down at once. This is what made the production backend notice a killed instance in 13.4 ms in the README.

I changed `retry-on conn-failure empty-response 503` to `... 503 504` in a scratch copy and this test failed, as it should.

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

**`keepsTheActiveOnlyBackendFreeOfRetriesAndPassiveChecks`.** `seatwave_active_only` is the lab's "what active checks alone cost" backend. Its block must contain the lines `retries 0` and `retry-on none` and must not contain `redispatch`, `observe` or `slowstart`. A "no retry, no passive check" backend is what makes the 11,777 failed requests of the first lab happen; if someone added a retry, the lab would show a better number for the wrong reason.

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

**`letsTheAlgorithmBackendsFollowProductionsChecks`.** The three backends that only show an algorithm (`seatwave_leastconn`, `seatwave_source`, `seatwave_weighted`) must contain `track seatwave/seatwave-1`, `-2` and `-3`, and must not contain the text ` check`. `track seatwave/seatwave-1` means: this server has no checks of its own; use the state of `seatwave-1` in backend `seatwave`. That keeps the comparison between algorithms fair, since all five backends see the same health. The string ` check` begins with a space so that it does not match words such as `checks` in a comment. I added `check` to one of these lines in a scratch copy and the test failed.

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

I changed one line to `0.0.0.0:21003:8083` in a scratch copy and the test failed. The count of 12 is a hard-coded number: adding a thirteenth legitimate port means changing the test on purpose.

```java
  @Test
  void pinsEveryImageByTagAndDigest() throws IOException {
    String compose = Files.readString(Path.of("compose.yaml"), StandardCharsets.UTF_8);
    String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
    String keepalived = Files.readString(Path.of("keepalived/Dockerfile"), StandardCharsets.UTF_8);

    assertThat(compose).contains("haproxy:3.4.7@sha256:").contains("postgres:18.6@sha256:");
    assertThat(dockerfile)
        .containsPattern("FROM eclipse-temurin:25\\.0\\.5_8-jdk-noble@sha256:[0-9a-f]{64}");
    assertThat(keepalived).containsPattern("FROM alpine:3\\.24\\.4@sha256:[0-9a-f]{64}");
    assertThat(compose)
        .doesNotContain("privileged: true")
        .doesNotContain("network_mode: host")
        .doesNotContain("docker.sock");
  }
```

**`pinsEveryImageByTagAndDigest`.** Reads `compose.yaml`, `Dockerfile` and `keepalived/Dockerfile`. It checks:

- the compose text contains `haproxy:3.4.7@sha256:` and `postgres:18.6@sha256:`;
- the `Dockerfile` has a line that matches `FROM eclipse-temurin:25\.0\.5_8-jdk-noble@sha256:` and 64 hexadecimal characters (the pattern escapes the dots with `\.`);
- the keepalived `Dockerfile` matches `FROM alpine:3\.24\.4@sha256:` and 64 hexadecimal characters;
- `compose.yaml` contains none of `privileged: true`, `network_mode: host` or `docker.sock`: no full-power container, no sharing of the host's network, no access to the Docker control socket.

Despite the word "every" in the name, one pinned image is not checked. The `Dockerfile` has a second stage, `FROM ubuntu:noble@sha256:...`. I replaced it with `FROM ubuntu:noble` in a scratch copy and the test still passed. (The images that Compose builds, `seatwave-balancer-seatwave` and `seatwave-balancer-keepalived`, are local names and are not pinned by digest, by design.)

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

A build-system gap that affects this class. The Gradle `test` task declares only `db/schema.sql` and `haproxy/haproxy.cfg` as inputs (`build.gradle.kts`, the two `inputs.file` lines). `compose.yaml`, `Dockerfile`, `keepalived/Dockerfile` and everything under `config/` are read by the tests but are not inputs. After you change one of them, `./gradlew test` (and so `./gradlew check`) can report `:test UP-TO-DATE` and `BUILD SUCCESSFUL` without running a single test. I confirmed it twice in scratch copies: a port edited to `0.0.0.0` in `compose.yaml` passed until `cleanTest` forced a rerun; and `db.poolSize=0` in `config/seatwave-container.properties` passed the same way, then failed `ServiceSettingsTest.readsTheContainerSettingsShippedWithTheProject` after `cleanTest`. Declaring those files as inputs in `build.gradle.kts` would close the gap.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the twelve tests of this package.

```bash
./gradlew --offline cleanTest test --tests '*SettingsReaderTest' --tests '*SettingsFilesTest' --tests '*InfrastructureRulesTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Twelve lines end in `PASSED` and the last line is `BUILD SUCCESSFUL`.

2. Prove a rule is enforced: break `haproxy.cfg` in a copy and watch a test fail.

```bash
rm -rf build/cfg-check && mkdir build/cfg-check
rsync -a --exclude build --exclude .gradle ./ build/cfg-check/
cd build/cfg-check
sed -i.bak 's/ slowstart 10s observe/ observe/' haproxy/haproxy.cfg
./gradlew --offline cleanTest test --tests '*InfrastructureRulesTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/cfg-check
```

The test `retriesOnlyWhatSeatWaveDidNotServe` fails, because `slowstart 10s` is gone from the production backend.

3. See the up-to-date gap. Edit a file the tests read but Gradle does not know about, and run the normal command.

```bash
rm -rf build/gap-check && mkdir build/gap-check
rsync -a --exclude build --exclude .gradle ./ build/gap-check/
cd build/gap-check
./gradlew --offline test --tests '*ServiceSettingsTest' 2>&1 | grep -E "BUILD"
sed -i.bak 's/^db.poolSize=16/db.poolSize=0/' config/seatwave-container.properties
./gradlew --offline test --tests '*ServiceSettingsTest' 2>&1 | grep -E ":test|BUILD"
./gradlew --offline cleanTest test --tests '*ServiceSettingsTest' 2>&1 | grep -E "FAILED|BUILD"
cd ../.. && rm -rf build/gap-check
```

The first run passes. The second prints `> Task :test UP-TO-DATE` and `BUILD SUCCESSFUL` although the shipped settings are now invalid. The third, after `cleanTest`, shows `readsTheContainerSettingsShippedWithTheProject() FAILED`.

4. Read the rules the tests protect. These commands print the lines the tests look at.

```bash
grep -n "retry-on\|default-server\|retries" haproxy/haproxy.cfg
grep -c '^      - "127.0.0.1:' compose.yaml
```

The first lists the retry and check lines of all backends. The second prints `12`, the published port lines the test counts.

## Mistakes people make

- **Trusting a text check as if it were a behavior check.** The file can say `inter 2s` and HAProxy can still be slow. The labs measure the behavior; these tests only keep the file honest.
- **Matching a word inside a longer word.** `rise 2` also starts `rise 20`. The `(\s|$)` ending and the leading spaces in the whole-line checks prevent that.
- **Checking the wrong block.** A rule that reads the whole file would pass when the value sits in another backend. Each rule here reads one backend's block.
- **Hard-coded counts.** `12` published ports is exact. That is the point, but it must change on purpose.
- **Inputs not declared to the build.** The up-to-date gap above.
- **Claiming "every" and checking some.** `pinsEveryImageByTagAndDigest` skips the `ubuntu:noble` stage.
- **A test that cannot fail.** Break the thing in a copy and check that the test fails, as steps 2 and 3 do.

## Check yourself

1. Why does `backend(name)` search for `"\nbackend " + name + "\n"` with line breaks on both sides?
   Answer: so `seatwave` does not match the start of `seatwave_active_only`.
2. What does `observe layer4 error-limit 3 on-error mark-down` do, and which test protects it?
   Answer: after three connection errors in a row on real traffic, the server is marked down at once (a passive check). `retriesOnlyWhatSeatWaveDidNotServe` requires the text on the production backend.
3. Why must the active-only backend contain `retries 0` and `retry-on none`?
   Answer: the lab compares it with the production backend to show what retries and passive checks buy; any retry there would hide the cost.
4. Which container ports may be published, and on which host address?
   Answer: container ports 8080, 8081, 8082, 8083, 8404 and 9999 (six numbers), only on `127.0.0.1`, with host ports 21000 to 21099.
5. What can make `./gradlew check` pass without running the infrastructure tests?
   Answer: Gradle considers the `test` task up to date, because `compose.yaml`, the Dockerfiles and `config/` are not declared as its inputs.

## Further reading

- HAProxy 3.4 configuration manual (`check`, `inter`, `fall`, `rise`, `slowstart`, `observe`, `retry-on`, `option redispatch`, `track`): https://docs.haproxy.org/3.4/configuration.html
- Docker Compose file reference, `ports`: https://docs.docker.com/reference/compose-file/services/#ports
- Docker documentation, pin base images by digest: https://docs.docker.com/build/building/best-practices/#pin-base-image-versions
- Gradle 9.9.1 user guide, up-to-date checks and declaring task inputs: https://docs.gradle.org/9.9.1/userguide/incremental_build.html
- JUnit 6 user guide: https://docs.junit.org/current/user-guide/
- AssertJ core documentation: https://assertj.github.io/doc/
