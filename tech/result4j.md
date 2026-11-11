# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (the README's dependency table says so).

## Why this repository uses it

A settings file, a request body, a list of balancer addresses and a rolling deploy that can stop half way are all things that can fail for ordinary reasons. A person can write `load.ratePerSecond=0`. A client can send `{"seat": }`. The deploy can find that an instance never came back up. A seat can be taken by someone else. These are **expected failures**: the program should list them, count them and answer them, not die with a stack trace. One run of a settings reader must report every problem, not stop at the first.

Exceptions are kept for programmer errors: a null argument, a record built with a value its constructor forbids. The line is: if a person, a client, a database or another customer can cause it, it is a `Result`; if only a bug can cause it, it is an exception. One failure of the first kind is still an exception here, on purpose: when the database does not answer, `JdbcHoldStore.hold` throws `StoreUnavailableException`, because no business rule can answer a question the database cannot hear. The result's error side is for refusals that are part of the business: `SeatTaken`, `NoSuchSeat` and `KeyReused`.

The dependency is declared in `gradle/libs.versions.toml`, used in `build.gradle.kts` and pinned in `gradle.lockfile`:

```toml
result4j = "1.2.0"
```

```toml
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
```

```kotlin
    implementation(libs.result4j)
```

```text
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths.

## Where it appears

| File (under `src/main/java/io/plainround/seatwave/balancer/`) | What it does with `Result` |
|---|---|
| `config/SettingsFiles.java` | `load` takes a function that returns `Result<T, List<String>>` and switches over it: on an error it prints every problem. (`config/SettingsReader.java` does **not** use `Result`: it collects problem lines in a list.) |
| `seatwave/ServiceSettings.java` | `from` returns `Result<ServiceSettings, List<String>>`. |
| `harness/HarnessSettings.java` | `from` returns `Result<HarnessSettings, List<String>>`. |
| `deploy/DeploySettings.java` | `from` returns `Result<DeploySettings, List<String>>`. |
| `haproxy/NamedBalancer.java` | `parse` returns `Result<List<NamedBalancer>, String>`: the balancers, or why an entry is not `name=address:port`. |
| `json/JsonCodec.java` | `read` returns `Result<T, BindingProblem>`: the request record, or which field is wrong and why. |
| `seatwave/HoldStore.java` | The interface: `hold` returns `Result<GrantedHold, HoldRefusal>`. |
| `seatwave/JdbcHoldStore.java` | Builds that result: a granted hold, a replayed hold, or one of three refusals. |
| `seatwave/HoldRoutes.java` | Switches over `json.read` and over the store's result to build the HTTP reply. |
| `deploy/RollingDeploy.java` | `run` and `restart` return `Result<Integer, String>`: the number of restarted instances, or what stopped the deploy. |
| `deploy/DeployCli.java` | Switches over the deploy's result to print and set the exit status. |
| `harness/LoadRun.java` | Uses a record pattern in an `instanceof` test on `json.read`. |

Twelve main files import the library, and eight test files mention it (`SettingsFilesTest`, `RollingDeployTest`, `HarnessCliTest`, `RunReportTest`, `ServiceSettingsTest`, `JdbcHoldStoreTest`, `RuntimeApiTest` and `ScriptedHoldStore`). I counted them with the commands in "Try it yourself".

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the 1.2.0 jar and confirmed it: `Result$Success` is a `final` class that extends `java.lang.Record` and implements `Result<R, E>`, and `Result$Error` is the same. A **sealed** interface lists every type allowed to implement it. That is what lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=7]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=[q must be a whole number]]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

Because both cases are records, `equals` compares by content. `Result.error(new HoldRefusal.SeatTaken())` equals another `Result.error(new HoldRefusal.SeatTaken())`, because `SeatTaken` is a record with no components and so any two are equal. The tests rely on this.

**The calls this repository uses:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch`, or an `instanceof`, with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`.
- `Result.Success.class`, `Result.Error.class` and `Result.Success<?, ?>` in tests, to ask which case a value is.

The library offers more. I listed its public methods with `javap -public` on the jar: `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, `fromOptional`, `fromOptionalResult`, and the function builders `mapping`, `flatMapping` and `errorMapping`. Neither the main code nor the tests of this repository call any of them. I searched `src` for each name and found no call.

I ran a small program with the 1.2.0 jar on the class path (it is `R4j.java`, listed in "Try it yourself"). It parses `42` and `x` and returns a `Result<Integer, List<String>>`:

```text
got 42
problems [q must be a whole number]
Success[result=7] | Error[error=[q must be a whole number]]
true
Success[result=14]
true
```

The first two lines come from the two `switch` statements: the good value, then the list of problems. The next line shows the two `toString` forms. The last three lines: `equals` compares by content (a parsed 7 equals `Result.success(7)`), `map` applies a function to a success (7 became 14), and `isError()` is true for the bad one.

**Java version.** The class files in the 1.2.0 jar have major version 65, which is Java 21 (`javap -v` printed `major version: 65`). So the library needs Java 21 or newer, and this repository's `switch` with record patterns also needs Java 21. The project builds with `--release 25`.

## Walkthrough of this repository's use

### Building a result from a list of problems: `ServiceSettings.from`

```java
  /**
   * Reads the settings, reporting every missing or invalid key and every broken rule between keys
   * together.
   *
   * @param reader the settings reader
   * @return the settings, or one line per problem
   */
  public static Result<ServiceSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String bind = reader.textMatching("http.bindAddress", ADDRESS);
    int port = reader.intBetween("http.port", 0, HIGHEST_PORT);
    int adminPort = reader.intBetween("http.adminPort", 0, HIGHEST_PORT);
    int backlog = reader.intBetween("http.backlog", 1, MAX_BACKLOG);
    int workers = reader.intBetween("http.workerThreads", 1, MAX_WORKERS);
    int queue = reader.intBetween("http.queueCapacity", 1, MAX_QUEUE);
    String url = reader.textMatching("db.url", JDBC_URL);
    String user = reader.textMatching("db.user", USER);
    String passwordFile = reader.textMatching("db.passwordFile", FILE);
    int poolSize = reader.intBetween("db.poolSize", 1, MAX_POOL);
    Duration connect = reader.millisBetween("db.connectTimeoutMillis", 1, MAX_WAIT_MILLIS);
    Duration check =
        reader.millisBetween("readiness.checkTimeoutMillis", MIN_CHECK_MILLIS, MAX_WAIT_MILLIS);
    Duration drain = reader.millisBetween("drain.deadlineMillis", 0, MAX_WAIT_MILLIS);
    if (port != 0 && port == adminPort) {
      reader.addProblem("http.port and http.adminPort must differ (0 means any free port)");
    }
    if (poolSize > workers) {
      reader.addProblem(
          "db.poolSize must not be larger than http.workerThreads: a connection no worker can use"
              + " is held open at the database for nothing");
    }
    Optional<InetAddress> address = bind.isEmpty() ? Optional.empty() : literal(reader, bind);
    if (!reader.problems().isEmpty() || address.isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ServiceSettings(
            address.get(),
            port,
            adminPort,
            backlog,
            workers,
            queue,
            new DatabaseSettings(url, user, Path.of(passwordFile), poolSize, connect, check),
            drain));
  }
```

The result type here is `Result<ServiceSettings, List<String>>`. The method reads every key through the `SettingsReader`, which never stops: a bad key adds a problem line and returns a harmless stand-in value (the minimum of the range, or an empty text). After all keys, and after the two rules that link keys (the two ports must differ, and the pool cannot be larger than the worker count), the method looks at the problem list. If it is not empty, `Result.error(reader.problems())` returns the lines. Otherwise `Result.success(...)` returns the finished record. The stand-in values are harmless because the record is never built from them. `HarnessSettings.from` and `DeploySettings.from` have the same shape, each with its own `if (!reader.problems().isEmpty() || balancers.isEmpty())` test before the last `return Result.success(`.

### Building a result from one reason: `NamedBalancer.parse`

```java
  public static Result<List<NamedBalancer>, String> parse(List<String> entries, Duration timeout) {
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(timeout, "timeout");
    List<NamedBalancer> balancers = new ArrayList<>();
    for (String entry : entries) {
      Matcher matcher = ENTRY.matcher(entry);
      if (!matcher.matches() || Integer.parseInt(matcher.group(3)) > HIGHEST_PORT) {
        return Result.error(
            "'" + entry + "' is not name=address:port, such as lb1=10.210.10.21:9999");
      }
      InetAddress address;
      try {
        address = InetAddress.ofLiteral(matcher.group(2));
      } catch (IllegalArgumentException notAnAddress) {
        return Result.error("'" + entry + "' does not hold an IP address");
      }
      balancers.add(
          new NamedBalancer(
              matcher.group(1),
              new RuntimeApi(
                  new InetSocketAddress(address, Integer.parseInt(matcher.group(3))), timeout)));
    }
    return Result.success(List.copyOf(balancers));
  }
```

Here the error type is a single `String`: the first entry that is wrong, with the reason. The method returns at the first wrong entry, because the settings classes add it to their problem list as one line (`reader.addProblem("watch.balancers: " + reason)`). A success holds an immutable copy of the list, made with `List.copyOf`.

### A result whose error is a sealed type: `JdbcHoldStore`

```java
      return Result.error(
          seatExists(connection, command)
              ? new HoldRefusal.SeatTaken()
              : new HoldRefusal.NoSuchSeat());
    }
    recordHold(connection, hold, command);
    return Result.success(new GrantedHold(hold, false));
```

```java
  private static Result<GrantedHold, HoldRefusal> replay(StoredHold stored, HoldCommand command) {
    // Module 7: the same key with a different request is refused, never answered with the other
    // request's hold.
    return stored.fingerprint().equals(command.fingerprint())
        ? Result.success(new GrantedHold(stored.hold(), true))
        : Result.error(new HoldRefusal.KeyReused());
  }
```

Here the error is not text but a value of the sealed interface `HoldRefusal` (`java.md`): `SeatTaken`, `NoSuchSeat` or `KeyReused`. The first block runs when the single conditional `UPDATE` that takes the seat changed no row: if the seat exists, someone else holds it, and if not, there is no such seat. In the second, a request that arrives with an `Idempotency-Key` seen before gets the stored hold back as a success marked as a replay, unless the key was used with a different request, which is `KeyReused`. Because `HoldRefusal` is sealed, a `switch` over it knows all three cases.

### Building a result with several error exits: `RollingDeploy.restart`

```java
  private Result<Integer, String> restart(String instance) throws InterruptedException {
    try {
      setEverywhere(instance, AdminState.DRAIN);
      say(instance + ": drain set on " + balancers.size() + " balancer(s)");
      if (!waitFor(instance, row -> row.currentSessions() == 0, settings.drainTimeout())) {
        return Result.error(instance + " still had sessions after " + settings.drainTimeout());
      }
      say(instance + ": no session left on any balancer");
      containers.stop(instance);
      say(instance + ": stopped (SIGTERM; the instance drained itself)");
      // Wait until every balancer's checks have seen it down. Started sooner, a quick restart can
      // go unnoticed: the balancer would then send full traffic at once to a cold instance, with no
      // slow start, possibly before it is ready.
      if (!waitFor(instance, row -> row.status().startsWith("DOWN"), settings.upTimeout())) {
        return Result.error(instance + " was never seen down within " + settings.upTimeout());
      }
      say(instance + ": seen down by every balancer's checks");
      containers.start(instance);
      say(instance + ": started");
      setEverywhere(instance, AdminState.READY);
      say(instance + ": ready set; the checks decide when it gets traffic again");
      if (!waitFor(instance, StatRow::isUp, settings.upTimeout())) {
        return Result.error(instance + " did not pass its checks within " + settings.upTimeout());
      }
      say(instance + ": up on every balancer; slow start ramps its weight");
      Thread.sleep(settings.settle());
      return Result.success(1);
    } catch (IOException failure) {
      return Result.error(instance + ": " + failure.getMessage());
    }
```

`restart` runs one instance's whole sequence: drain, wait, stop, wait until seen down, start, ready, wait until up, settle. Every step that can time out returns `Result.error(...)` with a sentence that names the instance and the limit, and the last line of the `try` returns `Result.success(1)`. `run` (lines 54 to 69 of the file) calls `restart` once per instance and returns the first error it meets, so a failed deploy stops at the instance that failed and leaves it drained or stopped, never sent traffic it cannot serve. An `IOException` from the Docker call is turned into an error too, so the caller has one kind of failure to handle.

### Examining a result: `switch` with record patterns

```java
    try {
      return switch (reading.apply(new SettingsReader(SettingsReader.load(file)))) {
        case Result.Success<T, List<String>>(T checked) -> Optional.of(checked);
        case Result.Error<T, List<String>>(List<String> problems) -> {
          err.println("the settings in " + file + " have " + problems.size() + " problem(s):");
          problems.forEach(problem -> err.println("  " + problem));
          yield Optional.empty();
        }
      };
```

This `switch` is an **expression**: it produces the value that `return` hands back. Each case unpacks the record: `Result.Success<T, List<String>>(T checked)` binds the good value, and `Result.Error<T, List<String>>(List<String> problems)` binds the list. A success becomes `Optional.of(checked)`. An error prints every problem and uses `yield Optional.empty();` to give the value of the block. The compiler checks that both cases are present. If a later version of the library added a third case, this `switch` would stop compiling, and the author would have to handle it.

```java
    return switch (json.read(body, HoldRequestBody.class)) {
      case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
          checked(request, parsed);
      case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
              JsonCodec.BindingProblem problem) ->
          invalid(request, List.of(problem.pointer() + " " + problem.detail()));
    };
```

The same idea in the route: a success goes on to check the fields, and an error becomes an invalid-request answer that joins the field's JSON pointer and the detail, such as `/eventId has the wrong type`.

```java
  private Reply answer(Request request, Result<GrantedHold, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) -> {
        // A business event, logged once per hold with the request's correlation id.
        LOG.debug(
            "held {} until {}{}",
            granted.hold().seat(),
            granted.hold().expiresAt(),
            granted.isReplay() ? " (replayed for a retried request)" : "");
        yield Reply.json(CREATED, json.write(HoldView.of(granted.hold(), clock)))
            .withHeader("Location", HOLDS + "/" + granted.hold().holdId())
            .withHeader("Cache-Control", "no-store");
      }
      case Result.Error<GrantedHold, HoldRefusal>(HoldRefusal refusal) -> refused(request, refusal);
    };
  }
```

The store's result is examined in the same way. A success logs the business event and builds the `201 Created` reply (`yield` gives the block's value). An error goes to `refused`, which has its own `switch` over the three refusals (`SeatTaken` becomes `409`, `NoSuchSeat` becomes `404`, `KeyReused` becomes `422`).

```java
  private void remember(String body) {
    if (json.read(body, HoldReply.class)
        instanceof Result.Success<HoldReply, JsonCodec.BindingProblem>(HoldReply hold)) {
      long slot = holdsMade.getAndIncrement();
      recentHolds.set((int) Math.floorMod(slot, (long) RECENT_HOLDS), hold.holdId());
    }
  }
```

`instanceof` accepts a record pattern too. If the result of reading the body is a `Success`, the pattern binds its hold to the name `hold` for the lines inside the `if`. For an error nothing happens, because the load run does not need to know why a body could not be read.

```java
    for (String instance : settings.instances()) {
      Result<Integer, String> step = restart(instance);
      if (step instanceof Result.Error<Integer, String> failed) {
        return failed;
      }
    }
```

The same test with a type pattern: `step instanceof Result.Error<Integer, String> failed` binds the whole `Result.Error` to `failed`, so the method can return it unchanged.

### A result with many error exits: `JsonCodec.read`

```java
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
```

`read` has one success line, `return Result.success(value);`, and eight error exits, each with a `BindingProblem` that says which field and what is wrong: an empty body, a body that is not an object, data after the object, an unknown field, a wrong type, a number out of range and two kinds of malformed JSON. Only a failure that cannot be the client's fault (`IOException` while reading a `String`) is thrown, as an `UncheckedIOException`. This is the line the repository draws between a `Result` and an exception (`jackson.md` explains the Jackson side).

### How the tests use a result

```java
  @Test
  void refusesASeatAnotherActiveHoldOwns() throws Exception {
    store.hold(command("\"k-1\"", SHA_A, "A-7"));

    Result<GrantedHold, HoldRefusal> second = store.hold(command("\"k-2\"", SHA_B, "A-7"));

    assertThat(second).isEqualTo(Result.error(new HoldRefusal.SeatTaken()));
  }
```

A refusal is a value, so the test compares it with `isEqualTo(Result.error(new HoldRefusal.SeatTaken()))`. This works because both the `Result` and the refusal are records, and records compare by content.

```java
  @Test
  void letsExactlyOneOfManyRacersHoldOneSeat() throws Exception {
    List<Result<GrantedHold, HoldRefusal>> outcomes =
        race(racer -> command("\"race-" + racer + "\"", SHA_A, "B-1"));

    long winners =
        outcomes.stream().filter(outcome -> outcome instanceof Result.Success<?, ?>).count();
    assertThat(winners).isEqualTo(1);
    assertThat(outcomes)
        .filteredOn(outcome -> outcome instanceof Result.Error<?, ?>)
        .allMatch(outcome -> outcome.equals(Result.error(new HoldRefusal.SeatTaken())));
  }
```

A race of many threads for one seat: the test counts how many outcomes are a `Success` (the pattern `Result.Success<?, ?>` has wildcards because the test does not care about the types) and checks that every other outcome is the same `SeatTaken` error.

```java
  private static GrantedHold granted(Result<GrantedHold, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) -> granted;
      case Result.Error<GrantedHold, HoldRefusal>(HoldRefusal refusal) ->
          throw new AssertionError("expected a granted hold, got " + refusal);
    };
  }
```

A helper turns the success into its value and throws an `AssertionError` for an error, so a test about something else fails with a message that names the refusal.

```java
  @Test
  void readsTheContainerSettingsShippedWithTheProject() throws IOException {
    Result<ServiceSettings, List<String>> read =
        ServiceSettings.from(
            new SettingsReader(
                SettingsReader.load(Path.of("config/seatwave-container.properties"))));

    ServiceSettings settings =
        switch (read) {
          case Result.Success<ServiceSettings, List<String>>(ServiceSettings checked) -> checked;
          case Result.Error<ServiceSettings, List<String>>(List<String> problems) ->
              throw new AssertionError("the shipped settings have problems: " + problems);
        };
    assertThat(settings.database().poolSize()).isEqualTo(16);
    assertThat(settings.drainDeadline()).isEqualTo(Duration.ofSeconds(20));
  }
```

The same helper idea for the settings: the shipped file must be valid, and if it is not the test fails with every problem named.

## Try it yourself

All commands ran in a scratch copy of the repository on 11 November 2026.

**1. See that the library is a runtime dependency of the main code.**

```text
./gradlew --offline -q dependencies --configuration runtimeClasspath | grep result4j
```

```text
+--- com.github.sviperll:result4j:1.2.0
+--- com.github.sviperll:result4j:{strictly 1.2.0} -> 1.2.0 (c)
```

The `strictly` line is the lock file's constraint: the version may not change.

**2. List the methods of the type, from the jar.** Find the jar in Gradle's cache and run `javap`:

```text
javap -public -cp "$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)" com.github.sviperll.result4j.Result | head -12
```

You should see `errorMapping`, `mapping`, `flatMapping`, `success`, `error`, `fromOptionalResult`, `fromOptional`, `isError`, `andThen` and `map` listed.

**3. Watch an expected failure come back as a value, not a crash.** Break a settings file as in `java-properties.md`, and run:

```text
./gradlew --offline -q load --args='../demo/bad.properties'
```

```text
the settings in ../demo/bad.properties have 3 problem(s):
  load.ratePerSecond must be from 1 to 20000, but was 0
  report.victim is missing
  load.requestTimeoutMillis must be longer than load.connectTimeoutMillis, or no request that needs a new connection can finish
```

No stack trace from the program: it took the `Error` arm of the `switch` in `SettingsFiles.load` and exited with status 2. Gradle then adds its own failure report.

**4. Run the demo program.** Save this as `R4j.java`:

```java
import com.github.sviperll.result4j.Result;
import java.util.List;

public class R4j {
  static Result<Integer, List<String>> parse(String key, String text) {
    try {
      return Result.success(Integer.parseInt(text));
    } catch (NumberFormatException notANumber) {
      return Result.error(List.of(key + " must be a whole number"));
    }
  }

  public static void main(String[] args) {
    Result<Integer, List<String>> good = parse("p", "42");
    Result<Integer, List<String>> bad = parse("q", "x");
    switch (good) {
      case Result.Success<Integer, List<String>>(Integer value) -> System.out.println("got " + value);
      case Result.Error<Integer, List<String>>(List<String> problems) -> System.out.println("problems " + problems);
    }
    switch (bad) {
      case Result.Success<Integer, List<String>>(Integer value) -> System.out.println("got " + value);
      case Result.Error<Integer, List<String>>(List<String> problems) -> System.out.println("problems " + problems);
    }
    System.out.println(parse("r", "7") + " | " + parse("q", "x"));
    System.out.println(parse("r", "7").equals(Result.success(7)));
    System.out.println(parse("r", "7").map(n -> n * 2));
    System.out.println(bad.isError());
  }
}
```

Then run it with the jar from Gradle's cache on the class path. It prints the six lines shown in "How it works underneath".

```text
java -cp "$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)" R4j.java
```

**5. Count the files that import the library:**

```text
grep -rl "import com.github.sviperll.result4j.Result;" src/main | wc -l
grep -rl "result4j" src/test | wc -l
```

Expected: `12` and `8`.

## Mistakes people make

- **Using a `Result` for a bug.** A null argument or a record built with a forbidden value is a programmer error. `Objects.requireNonNull` and the constructors throw, as they should.
- **Using an exception for a typo in a settings file.** The program would stop at the first bad key. Here, every bad key is listed in one run.
- **Reading the value of an error.** The caller must look at the case first. The sealed `switch` forces it.
- **Using the stand-in values.** `SettingsReader` returns a stand-in for a bad key. The code returns `Result.error(reader.problems())` before it builds anything from them.
- **A `default` arm that hides a new case.** None of the switches here has a `default`, so a new case stops the build.
- **Mixing up the two type parameters.** The pattern needs both: `Result.Success<R, E>(R value)`.
- **Turning a business refusal into an exception.** A seat that is taken is not an error of the program. It is an answer, and the route turns it into `409`.

## Check yourself

1. What are the two cases of a `Result`, and what does each hold? (`Success` holds the value `R`; `Error` holds the reason `E`.)
2. Why do the `switch`es over a `Result` in this repository have no `default` arm? (The interface is sealed with two permitted records, so the compiler knows both cases are handled. A new case would stop the build.)
3. When does this repository throw an exception instead of returning an error? (For programmer errors: a null argument, or a record built with a value its constructor forbids; and for a failure no business rule can answer, such as a database that does not respond, which is `StoreUnavailableException`.)
4. In `ServiceSettings.from`, why is `Result.error(reader.problems())` returned before the record is built? (The stand-in values of bad keys are harmless only because the record is never made from them.)
5. Why can a test write `isEqualTo(Result.error(new HoldRefusal.SeatTaken()))`? (Both `Result.Error` and `SeatTaken` are records, and records compare by content.)

## Further reading

Official documentation:

- The result4j project and its README, at the pinned version 1.2.0: https://github.com/sviperll/result4j
- Maven Central page of the artifact: https://central.sonatype.com/artifact/com.github.sviperll/result4j/1.2.0
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- Records (JEP 395): https://openjdk.org/jeps/395
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440
