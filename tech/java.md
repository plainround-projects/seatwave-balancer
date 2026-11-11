# Java 25 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows PC and a Linux server, so the same bytecode runs on all of them.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. A mistake such as passing text where a number is expected stops the build instead of crashing later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration` and `HttpClient`. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2, and `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`.

## Why this repository uses it

The repository is the hands-on project of Module 10 of a system design course. It builds a small ticket-booking service (SeatWave) that runs on the JDK's own web server, three copies of it behind a pair of balancers, and the tools that measure the balancers: a load harness, a client for the balancer's runtime API and a rolling deploy. Java 25 gives what that work needs, with six small libraries and no framework:

- **A web server and an HTTP client in the JDK** (`com.sun.net.httpserver`, `java.net.http`), and **JDBC** (`java.sql`) for the database.
- **Threads and the concurrency tools** (`ThreadPoolExecutor`, a lock with a condition, atomic counters, a semaphore, virtual threads) for the drain rule and for a load that sends thousands of requests per second.
- **Records, enums and sealed interfaces**: small immutable data types in a few lines, and `switch` statements that the compiler proves complete.
- **A standard library that covers the rest**, so the project has six runtime libraries only: result4j, Jackson, SLF4J, Logback, HikariCP and the PostgreSQL driver (`result4j.md`, `jackson.md`, `logging-slf4j-logback.md`, `hikaricp.md`, `postgresql-and-h2.md`).

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/balancer/seatwave/` | 18 files: one SeatWave instance. See `java-seatwave-walkthrough-1.md`, `java-seatwave-walkthrough-2.md` and `java-seatwave-walkthrough-3.md`. |
| `.../config/`, `.../json/` | 2 and 1 files: the settings reader and the JSON codec. See `java-config-json-walkthrough.md`. |
| `.../harness/` | 7 files: the load harness. See `java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`. |
| `.../deploy/` | 7 files: the rolling deploy. See `java-deploy-walkthrough.md`. |
| `.../haproxy/` | 5 files: the client of HAProxy's runtime API. See `java-haproxy-client-walkthrough.md`. |
| `src/test/java/io/plainround/seatwave/balancer/` | 21 files, also Java (`tests-seatwave-walkthrough-1.md` and `tests-seatwave-walkthrough-2.md`, `tests-config-walkthrough.md`, `tests-tools-walkthrough-1.md` and `tests-tools-walkthrough-2.md`, `tests-stack-walkthrough.md`). |
| `build.gradle.kts` | Tells Gradle to compile with Java 25 and to treat every warning as an error (`gradle.md`). |

In all there are 40 source files under `src/main/java` (the counts above add up to 18 + 2 + 1 + 7 + 7 + 5 = 40). I ran `./gradlew -q compileJava` (it printed nothing, which means success) and found 59 class files under `build/classes/java/main/`. That is more than 40 because a nested class, a record or an enum inside a file gets its own class file.

## How it works underneath

**From text to a running program.** You write `Hold.java`. The compiler reads it and writes `Hold.class`. Gradle does this for you. To start a program, a JVM process starts, loads the class you name, and calls its `main` method. This repository has three, `SeatWaveCli`, `HarnessCli` and `DeployCli`. The last two have a Gradle task each (`load` and `deploy`); `SeatWaveCli` has none, because an instance needs the shared database and so runs only in the Compose stack, as the command of its container.

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns and no other thread is running, the process ends with **exit status** 0, which means success. A program may call `System.exit(n)` to end at once with another number. By convention a non-zero status means failure. This repository uses 2 for "the command was used incorrectly" (`EXIT_BAD_USAGE`, in all three entry points) and 1 for a run that failed.

**Memory.** Two areas matter. The **stack** holds the method calls in progress with their local variables: each call adds a frame and each return removes one. Every thread has its own stack. The **heap** holds objects: `new Hold(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. The JVM frees objects that no variable can reach any more. This is **garbage collection**. You never free memory by hand.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often each method runs, and the **JIT compiler** (just-in-time compiler) turns the busy methods into native machine code while the program runs. A method is slow in its first runs and fast after. That is why the harness sends a warm-up and throws it away (`load.warmupMillis=5000` in `config/kill-production.properties`, `java-properties.md`).

**Threads.** A **thread** is one line of execution inside a process. A program may have several, and they run at the same time on different processor cores. A **blocking** call, such as reading from a socket, makes its thread wait until the data arrives. A **platform thread** is one that the operating system schedules; each costs memory and there are only so many. A **virtual thread** (Java 21) is a cheap thread managed by the JDK. This repository uses **platform threads on purpose** for SeatWave's 64 workers, a fixed pool whose size and queue are part of what the lab measures, and **virtual threads** for the harness, which starts one thread per request so thousands of requests can wait for their answers at once.

**The class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task; inside the image, the command line gives `-cp /app/lib/*` (`docker.md`).

**Compile flags.** `build.gradle.kts` sets `options.release = 25` (compile for Java 25 and use only its library) and `-Xlint:all -Werror` (turn on every warning, and make any warning fail the build). So a warning cannot stay.

## Walkthrough of this repository's use

### The basic words

**Class.** A class is a blueprint for objects. It has **fields** (the data) and **methods** (the code). Everything in Java lives in a class. Records, enums and interfaces are special kinds of class.

**Package and import.** A **package** is a named group of classes, written with dots: `io.plainround.seatwave.balancer.seatwave`. The name matches the folder path under `src/main/java`. The first line of a file is `package ...;`. After it come the **imports**: each `import java.util.Objects;` lets the file write `Objects` instead of the full name `java.util.Objects`. Classes of the same package need no import.

```java
package io.plainround.seatwave.balancer.seatwave;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
```

**Visibility and `static`, `final`.** `public` means any class may use it. `private` means only this class. A member with none of them is **package-private**: only classes of the same package may use it. `static` means it belongs to the class, not to an object: you call it as `Reply.json(...)`, with no object. `final` on a field or variable means it can be assigned once. `final` on a class means no one can extend it. A `static final` field is a **constant**.

```java
public final class SeatWaveServer {

  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveServer.class);
  private static final int OK = 200;
  private static final int NOT_FOUND = 404;
  private static final int UNAVAILABLE = 503;
  // 0: the in-flight wait is the Lifecycle's job, done before the listeners are stopped.
  private static final int STOP_DELAY_SECONDS = 0;
  // After the drain, the longest wait for worker threads to end: a request past the drain
  // deadline gets this long, then is interrupted. The worst-case stop is therefore the drain
  // deadline plus this wait (ServiceSettingsTest checks compose.yaml's grace period covers it).
  static final Duration WORKER_STOP_WAIT = Duration.ofSeconds(5);
  // HEAD is answered like GET without a body (RFC 9110, Section 9.3.2); -1 tells the JDK server so.
  private static final long NO_BODY = -1;
```

- `public final class SeatWaveServer` is a public class that nobody can extend.
- `private static final Logger LOG = ...` is a constant that only this class sees (`logging-slf4j-logback.md`).
- `private static final int OK = 200;`, `NOT_FOUND` and `UNAVAILABLE` name HTTP status numbers so the code never has a bare `503` whose meaning must be guessed. The comment above `STOP_DELAY_SECONDS` gives the reason for its `0`.
- `static final Duration WORKER_STOP_WAIT = Duration.ofSeconds(5);` has no `public` or `private`: it is package-private, so a test in the same package can read it. A `Duration` (feature 13 below) is an amount of time that carries its unit.
- `long` is a whole number of 64 bits, `int` one of 32 bits, `double` a number with a fraction. `5` is an `int`; `5L` would be a `long`. `NO_BODY = -1` is a `long` because the method it feeds (`sendResponseHeaders`) takes the body length as one; `-1` is an `int` literal, and Java widens it to `long` without a cast, because every `int` fits in a `long`.

```java
public final class SeatWaveCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  static final int EXIT_BAD_USAGE = 2;
  // The name shows up in X-Served-By, in the balancer's server list and in log lines.
  private static final Pattern INSTANCE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

  private SeatWaveCli() {}
```

- `static final int EXIT_BAD_USAGE = 2;` has no `private`, so it is package-private: the test of the same package reads it.
- `Pattern.compile("[a-z][a-z0-9-]{0,31}")` builds a **regular expression**, a pattern for text: here, a lower-case letter, then 0 to 31 lower-case letters, digits or hyphens (an instance name such as `seatwave-1`). It is made once, in a constant, because compiling a pattern costs time.
- `private SeatWaveCli() {}` is a **private constructor** with an empty body. Nobody can make an object of this class, which is right for a class that only holds `main` and static methods.

**Constructors, `this` and `new`.** A **constructor** has the class name and no return type. It runs when you write `new`. `this` is the object itself, as in `this.store = store`. The constructor of `SeatWaveServer` is `private`, so the only way to make one is the `public static SeatWaveServer start(...)` method (lines 115 to 123 of the file), which first checks every argument with `Objects.requireNonNull(x, "x")`. That method throws a `NullPointerException` with the name `x` if `x` is `null`.

**Comments and Javadoc.** `//` starts a comment to the end of the line. `/** ... */` is a **Javadoc comment**: a documentation comment placed before a class or a method. Tags inside it begin with `@`: `@param` describes a parameter, `@return` the result, `@throws` an exception. Tools turn them into web pages. Here they are the main place a reader finds out what a class is for.

### The features this code uses

#### 1. Records

A **record** is a class for plain data. You list the components once, and Java writes the constructor, the accessors (methods named like the components), `equals`, `hashCode` and `toString`. A record is immutable: its fields cannot change after it is made.

```java
/**
 * One seat held by one customer until a moment, as the shared database recorded it.
 *
 * @param holdId the hold's identifier, a random UUID
 * @param eventId the event, Module 1's event 1
 * @param seat the seat label, for example {@code A-7}
 * @param customer the customer's identifier as the client sent it
 * @param expiresAt when the hold ends, Module 1's 300 seconds after it began
 */
public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt) {

  /** Checks that no field is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * Says whether the hold still blocks its seat.
   *
   * @param now the current time
   * @return true until the expiry moment
   */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
```

- `public record Hold(String holdId, long eventId, String seat, String customer, Instant expiresAt)` declares five components. I ran `javap -p` on the compiled class: it is `public final class ... Hold extends java.lang.Record` with five `private final` fields, a constructor with the five parameters, the accessors `holdId()`, `eventId()`, `seat()`, `customer()` and `expiresAt()`, and `toString`, `hashCode` and `equals`.
- `public Hold { ... }` with no parameter list is the **compact constructor**: code that runs first, with the parameters in scope, before the fields are assigned. Here it checks that nothing is `null`. (`eventId` is a `long`, which cannot be `null`.)
- `isActiveAt` is an ordinary method added to the record. `now.isBefore(expiresAt)` compares two `Instant` values.

A compact constructor can also refuse a value:

```java
  /**
   * How a drain ended.
   *
   * @param inFlightLeft requests still running when the wait ended: zero after a clean drain, more
   *     when the deadline passed first
   * @param waited how long the wait took
   */
  public record DrainResult(long inFlightLeft, Duration waited) {

    /** Checks the fields. */
    public DrainResult {
      if (inFlightLeft < 0) {
        throw new IllegalArgumentException("inFlightLeft cannot be negative: " + inFlightLeft);
      }
      Objects.requireNonNull(waited, "waited");
    }

    /**
     * Says whether every admitted request finished before the deadline.
     *
     * @return true when nothing was left in flight
     */
    public boolean isClean() {
      return inFlightLeft == 0;
    }

    /**
     * Returns the wait in whole milliseconds, for a log line.
     *
     * @return the wait, rounded down
     */
    public long waitedMillis() {
      return TimeUnit.NANOSECONDS.toMillis(waited.toNanos());
    }
  }
```

`throw new IllegalArgumentException(...)` stops the construction with an exception, so a `DrainResult` with a negative count can never exist. The record also carries two methods of its own, `isClean` and `waitedMillis`.

A record can be nested, as `DrainResult` is inside `Lifecycle`, and it can be package-private and tiny: `record Routed(String route, Reply reply) {}` in `HoldRoutes`, and `record Running(SeatWaveServer server, HikariDataSource pool, ServiceSettings settings, String instanceName) {}` in `SeatWaveCli`.

#### 2. Enums

An **enum** is a type with a fixed list of values.

```java
/**
 * Where one SeatWave instance is in its life, in the only order it can move: {@code STARTING} to
 * {@code READY} to {@code DRAINING} to {@code STOPPED}. A shutdown signal during start goes from
 * {@code STARTING} straight to {@code DRAINING}.
 */
public enum LifecycleState {
  /**
   * The listeners are open, but the instance has not said it can serve; every request is refused.
   */
  STARTING,
  /** The instance admits new requests and its readiness check can pass. */
  READY,
  /** Shutdown has begun: no new request is admitted, the ones in flight are finishing. */
  DRAINING,
  /** In-flight requests reached zero, or the drain deadline passed; the listeners are closing. */
  STOPPED
}
```

Each name (`STARTING`, `READY`, `DRAINING`, `STOPPED`) is the only instance of its kind. Compare enum values with `==` or `switch`; they are never created with `new`. `state.name().toLowerCase(Locale.ROOT)` turns a value into its text, as `HoldRoutes` does for the "the instance is draining; nothing was done" answer. `Outcome.Kind` in the harness is a nested enum with three values (`ANSWERED`, `FAILED`, `NOT_SENT`).

#### 3. Sealed interfaces

An **interface** names behavior without code. A **sealed** interface lists every type that may implement it. Then the compiler knows all the cases.

```java
/** Why a hold was not granted: the expected business failures of {@code POST /v1/holds}. */
public sealed interface HoldRefusal {

  /** Another active hold or a booking owns the seat. */
  record SeatTaken() implements HoldRefusal {}

  /** The event or the seat does not exist in the database. */
  record NoSuchSeat() implements HoldRefusal {}

  /** The Idempotency-Key was already used with a different request (Module 7). */
  record KeyReused() implements HoldRefusal {}
}
```

- `public sealed interface HoldRefusal` allows only the types declared inside it (a sealed interface may omit `permits` when the implementations sit in the same file). Each is a record with no components, so `new HoldRefusal.SeatTaken()` is a value that stands for one reason.
- `record SeatTaken() implements HoldRefusal {}` is a record nested in the interface, and it implements the interface.
- I read the class file with `javap -v`: it lists `PermittedSubclasses: HoldRefusal$SeatTaken, HoldRefusal$NoSuchSeat, HoldRefusal$KeyReused`. That list is what makes a `switch` over a `HoldRefusal` complete.

An interface can also be a plain contract with one method: `Containers` in the deploy package (`stop` and `start`) lets a test give a recorder instead of the real Docker call. `HoldStore` is the contract for the hold store, with `JdbcHoldStore` as the real implementation and `ScriptedHoldStore` as the test's.

#### 4. `switch`: expressions, strings and patterns

An old `switch` was a statement with `case X:` and `break`. The modern forms use an arrow `->`, need no `break`, and can **produce a value**. A `switch` can also look at the **type** of a value, and take a record apart:

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

  private Reply refused(Request request, HoldRefusal refusal) {
    return switch (refusal) {
      case HoldRefusal.SeatTaken taken ->
          problems.of(
              CONFLICT,
              "seat-unavailable",
              "The seat is held or sold",
              "the seat is not available",
              request.correlationId());
      case HoldRefusal.NoSuchSeat missing -> notFound(request);
      case HoldRefusal.KeyReused reused ->
          problems.of(
              UNPROCESSABLE,
              "idempotency-key-reused",
              "This Idempotency-Key was used with another body",
              "use a new key for a new request",
              request.correlationId());
    };
  }
```

- `return switch (outcome) { ... };` returns the value the `switch` produced.
- `case Result.Success<GrantedHold, HoldRefusal>(GrantedHold granted) ->` is a **record pattern**: it checks the type and takes the record apart into its component `granted` in one step (`result4j.md`).
- A `switch` arm may be a block `{ ... }`. The keyword `yield` gives the value of the whole expression from inside the block: `yield Reply.json(CREATED, ...)`.
- `case HoldRefusal.SeatTaken taken -> problems.of(...)` is a **type pattern**: it matches when `refusal` is a `SeatTaken`, and binds it to the name `taken` (unused here). Because `HoldRefusal` is sealed with exactly three records, the compiler checks that all three are handled and no `default` is needed. If someone adds a fourth record, this `switch` stops compiling.

```java
  public static void main(String[] arguments) throws InterruptedException {
    Optional<Running> running = start(arguments, System.out, System.err);
    if (running.isEmpty()) {
      System.exit(EXIT_BAD_USAGE);
      return;
    }
    CountDownLatch stopped = new CountDownLatch(1);
    // The JVM runs this hook on SIGTERM and waits for it, so the drain finishes before the process
    // exits; docker stop's grace period (compose.yaml) is longer than the drain deadline.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              stop(running.get(), System.out);
              stopped.countDown();
            }));
    stopped.await();
  }
```

This is `main`. `Optional<Running> running = start(...)`; if it is empty the program exits with status 2 and `return`s (the `return` is never reached after `System.exit`, but the compiler does not know that). `Runtime.getRuntime().addShutdownHook(new Thread(() -> { ... }))` registers a thread that the JVM starts when the process is asked to end (Ctrl+C, `docker stop`'s SIGTERM). The thread drains the server and counts down a latch, which lets `main` finish.

#### 5. Generics

**Generics** let one class or method work for many types while the compiler still checks them. `List<String>` is a list that holds only text. The type in angle brackets is a **type argument**.

```java
  /**
   * Loads and checks a settings file.
   *
   * @param file the file
   * @param reading turns a reader into the settings, or into its problems
   * @param err where problems are printed
   * @param <T> the settings type
   * @return the settings, or empty after printing why the file cannot be used
   */
  public static <T> Optional<T> load(
      Path file, Function<SettingsReader, Result<T, List<String>>> reading, PrintStream err) {
```

The method is generic: `public static <T> Optional<T> load(Path file, Function<SettingsReader, Result<T, List<String>>> reading, PrintStream err)`. `<T>` before the return type declares a **type parameter**: `T` stands for "the settings type" and is decided by the caller. The same method loads a `ServiceSettings`, a `HarnessSettings` or a `DeploySettings`, with no cast. `Function<SettingsReader, Result<T, List<String>>>` is the type of a function that takes a reader and returns a result.

`JsonCodec.read` is another: `public <T> Result<T, BindingProblem> read(String json, Class<T> type)`. A `Class<T>` is a handle for a type, passed as `HoldRequestBody.class`; the compiler then knows that the result is a `HoldRequestBody`. In `DeployCli`, `settings.balancers().stream().<BalancerControl>map(RuntimeBalancer::new).toList()` writes the type argument out by hand (`<BalancerControl>` before `map`), because the compiler would otherwise infer `RuntimeBalancer` and the list would have the wrong element type.

#### 6. Lambdas and method references

A **lambda** is a short anonymous function: `(parameters) -> body`. A **method reference** such as `Math::max` or `this::handle` is a lambda that only calls a method.

```java
    this.adminThread =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "seatwave-admin"));
    admin.setExecutor(adminThread);
    admin.createContext("/", this::handleAdmin);
    requests.setExecutor(workers);
    requests.createContext("/", this::handle);
    requests.start();
    admin.start();
```

- `task -> new Thread(task, "seatwave-admin")` is a lambda with one parameter. `Executors.newSingleThreadExecutor(...)` asks for a **thread factory**, a function that makes a thread for a task; the lambda is that function, and it gives the thread a name.
- `this::handleAdmin` is the same idea as `exchange -> handleAdmin(exchange)`. The JDK server calls it for every request to the operations listener, and `requests.createContext("/", this::handle)` does the same for the request listener.
- A lambda with no parameters, `() -> { stop(...); stopped.countDown(); }`, is the body of the shutdown thread in `main`.
- A functional interface is an interface with one method, so a lambda can stand for it. `DeployCli.ContainersFactory` carries `@FunctionalInterface`, which makes the compiler check that there is exactly one method; `DeployCli::run` takes `DockerCompose::new` (a **constructor reference**) for it.

#### 7. `Optional` and streams

An `Optional<T>` is a box that holds a value or nothing. It makes "there may be no result" visible in the type, where `null` hides it.

```java
  /**
   * Admits one request if the instance is ready.
   *
   * @return an admission to close when the response has been written, or empty when the instance is
   *     starting, draining or stopped; the caller must then refuse the request without doing any of
   *     its work
   */
  public Optional<Admission> tryAdmit() {
    lock.lock();
    try {
      if (state != LifecycleState.READY) {
        return Optional.empty();
      }
      inFlight++;
      return Optional.of(new Admission(this));
    } finally {
      lock.unlock();
    }
  }
```

`tryAdmit` returns `Optional.empty()` when the instance is not ready, and `Optional.of(new Admission(this))` when it is. The caller checks with `isPresent()` / `isEmpty()`, or takes the value out with `get()` after checking, `orElseThrow()`, or `orElse(...)`. `Optional.of(x)` wraps a value; `Optional.ofNullable(x)` wraps a value that may be `null`. In the tests, AssertJ's `assertThat(optional).isPresent()` checks one (`assertj.md`). The `try { ... } finally { lock.unlock(); }` is explained under feature 9.

A **stream** is a pipeline of steps over a collection: make a stream, change or filter its items, then collect the result.

```java
  public List<StatRow> servers(String backend) {
    return rows.stream()
        .filter(row -> row.proxy().equals(backend))
        .filter(row -> !row.server().equals("BACKEND") && !row.server().equals("FRONTEND"))
        .toList();
  }

  /**
   * Returns one server's row.
   *
   * @param backend the backend
   * @param server the server
   * @return the row, or empty if there is no such server
   */
  public Optional<StatRow> server(String backend, String server) {
    return servers(backend).stream().filter(row -> row.server().equals(server)).findFirst();
  }
```

- `rows.stream()` starts a pipeline over a list of rows.
- `.filter(row -> row.proxy().equals(backend))` keeps only the items for which the test is true.
- `.toList()` ends the pipeline and returns an unchangeable list.
- `.findFirst()` ends it with an `Optional` of the first item, empty if there is none.
- A lambda such as `row -> !row.server().equals("BACKEND") && !row.server().equals("FRONTEND")` is the test inside a `filter`. `&&` means "and", `!` means "not".

Other streams in the code use `allMatch` (does every item fit?), `map` (turn each item into another value), `sorted`, `mapToLong(...).max()` and `Collectors.groupingBy`, which groups items by a key and counts them (`RunReport`). `JsonCodec.pointerOf` ends with `Collectors.joining`, which joins text pieces:

```java
  private static String pointerOf(JsonMappingException failure) {
    // RFC 6901 pointer: "/field" for each step of the path Jackson was reading.
    return failure.getPath().stream()
        .map(step ->
            step.getFieldName() == null
                ? String.valueOf(step.getIndex())
                : escaped(step.getFieldName()))
        .collect(Collectors.joining("/", "/", ""));
  }
```

`failure.getPath().stream()` starts the pipeline over the path steps. `.map(step -> ...)` turns each step into text; the `? :` is the **conditional operator**, "if the field name is null then the index else the escaped name". `.collect(Collectors.joining("/", "/", ""))` joins the pieces with a `/` between them, a `/` before and nothing after. For `eventId` it makes `/eventId`.

#### 8. Immutable collections

`List.of("a", "b")`, `Map.of(...)` and `Set.of(...)` make collections that cannot be changed. `List.copyOf(x)` makes an unchangeable copy. Several classes use them to stay immutable:

```java
/**
 * What a route answers. Immutable: {@link #withHeader} returns a new reply.
 *
 * @param status the HTTP status
 * @param contentType the media type of the body
 * @param body the bytes, copied on the way in and out
 * @param headers further headers, in order
 */
public record Reply(
    int status, String contentType, byte[] body, List<Map.Entry<String, String>> headers) {

  /** Copies the body and the headers. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    headers = List.copyOf(headers);
  }
```

The compact constructor does `headers = List.copyOf(headers);` so a caller cannot change the list after handing it over, and `body = Objects.requireNonNull(body, "body").clone();` copies the byte array for the same reason (an array is always changeable). The accessor `body()` is written by hand to return a clone too. `Map.Entry<String, String>` is one pair of a map; the headers are a list of pairs because the order matters. `Map.entry(name, value)` makes one. The **diamond** `<>`, as in `new ArrayList<>(headers)` in `withHeader`, lets the compiler fill in the type argument from the left-hand side. `StatTable` and `DeploySettings` copy their lists in the same way.

#### 9. Exceptions

An **exception** is an object that is thrown when something goes wrong. Throwing stops the current method and unwinds the stack until some `catch` block handles it. There are two families. A **checked** exception such as `IOException` must be declared with `throws` in the method signature or caught; the compiler enforces it. An **unchecked** exception (`IllegalArgumentException`, `NullPointerException`, `UncheckedIOException`) needs no declaration.

This repository draws a line (`result4j.md`): a **bug** (a null argument, a record built with a forbidden value) throws an unchecked exception; a **failure a person or a client can cause** (a bad settings file, a bad request body, a seat that is taken) is a `Result`. A third kind sits between: the database cannot be reached. That is not a bug and not a business answer, so it has an exception of its own, which the routes turn into `503`:

```java
/**
 * The shared database could not be used: an infrastructure failure, not a business answer. The
 * routes turn it into {@code 503} with {@code Retry-After}, never into a guess.
 */
public final class StoreUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Wraps the cause.
   *
   * @param message what was being done
   * @param cause the database's own exception
   */
  public StoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

`extends Exception` makes it a **checked** exception, so `HoldStore.hold` must say `throws StoreUnavailableException` and every caller must handle it. `serialVersionUID` is a number the Java serialization format wants for exceptions; nothing here serializes. The constructor passes the message and the original exception (the **cause**) up to `super`, so the log shows both.

```java
  @Override
  public Result<GrantedHold, HoldRefusal> hold(HoldCommand command)
      throws StoreUnavailableException {
    Objects.requireNonNull(command, "command");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Result<GrantedHold, HoldRefusal> outcome = holdInTransaction(connection, command);
        connection.commit();
        return outcome;
      } catch (SQLException failure) {
        connection.rollback();
        if (!UNIQUE_VIOLATION.equals(failure.getSQLState())) {
          throw failure;
        }
        // Another request with the same key committed first: this is a retry that raced its
        // original, so answer what the original made.
        StoredHold original =
            findByKey(connection, command.idempotencyKey())
                .orElseThrow(() -> new SQLException("a duplicate key without a hold", failure));
        connection.commit();
        return replay(original, command);
      }
    } catch (SQLException failure) {
      throw new StoreUnavailableException("holding seat " + command.seat() + " failed", failure);
    }
  }
```

- `throws StoreUnavailableException` in the signature tells callers they must handle it.
- `try (Connection connection = dataSource.getConnection()) { ... } catch (SQLException failure) { throw new StoreUnavailableException(...); }` turns the database's own checked exception into this repository's. The inner `try` has its own `catch (SQLException failure)`: it rolls back, and if the failure is a **unique violation** (the SQL state `UNIQUE_VIOLATION`, meaning another request with the same `Idempotency-Key` committed first) it answers with that request's hold, and otherwise it re-throws with `throw failure;`.
- The two `catch` blocks use the same variable name `failure`, which is allowed because the inner one is out of scope when the outer one starts.

`finally` is a block that runs after the `try` block whether it succeeded or threw. `Lifecycle.tryAdmit` (feature 7) uses it to release the lock on every path, and the request handler in `SeatWaveServer` uses it to count a finished request and to clear the logging context.

#### 10. try-with-resources

A **resource** is something that must be closed: a file, a network connection, a thread pool. The `try (...)` form closes it for you at the end of the block, even when an exception is thrown. Any class that implements `AutoCloseable` can be used this way.

```java
  @Override
  public Optional<Hold> find(String holdId) throws StoreUnavailableException {
    Objects.requireNonNull(holdId, "holdId");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(FIND_BY_ID)) {
      statement.setString(1, holdId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(holdFrom(rows)) : Optional.empty();
      }
    } catch (SQLException failure) {
      throw new StoreUnavailableException("reading hold " + holdId + " failed", failure);
    }
  }
```

Two resources, a `Connection` and a `PreparedStatement`, are opened in one `try ( ... ; ... )` and closed in reverse order. The inner `try (ResultSet rows = statement.executeQuery())` adds a third. Closing a connection obtained from a pool does not close the database connection; it gives it back to the pool (`hikaricp.md`). The class `Lifecycle.Admission` implements `AutoCloseable` too: closing it ends one request's part of the in-flight count, and closing it twice counts once (`AtomicBoolean.compareAndSet(false, true)`). `LoadRun` writes `try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor())`: closing an executor waits for its tasks to finish, which is exactly what the load run needs before it reads its results.

#### 11. Strings and formatting

A `String` is immutable text. `+` joins strings: `"the settings in " + file + " have " + problems.size() + " problem(s):"`. Useful methods in this code: `strip()` removes surrounding spaces, `startsWith`, `contains`, `toLowerCase(Locale.ROOT)`, `isBlank()`, `replace`, `split`. `String.format` fills a template: `%.1f` prints a number with one decimal.

```java
  private static String millis(long nanos) {
    return String.format(Locale.ROOT, "%.1f ms", nanos / NANOS_PER_MILLI);
  }

  private static String at(long nanos) {
    return String.format(Locale.ROOT, "%+.3f s", nanos / NANOS_PER_SECOND);
  }
```

`Locale.ROOT` is passed on purpose. Without it, `String.format` uses the computer's language settings, and on a machine set to German the decimal point would print as a comma. `Locale.ROOT` fixes the format on every machine, which matters for a report whose numbers are copied into a document. `%+.3f` prints a number with a sign and three decimals (`+13.598`); `%d` prints a whole number. The same `String.format(Locale.ROOT, "%s must be from %d to %d, but was %d", ...)` is in `SettingsReader.intBetween`.

#### 12. Numbers

Whole numbers: `int` (32 bits, up to about 2.1 billion), `long` (64 bits). Fractions: `double`. Division of two whole numbers cuts the fraction: `7 / 2` is `3`. Arithmetic on `int` that gets too large **wraps around** silently, so the code uses `long` for nanoseconds and counts, and `Math.multiplyExact` where an overflow would be a bug:

```java
  public long plannedRequests() {
    return Math.multiplyExact((long) ratePerSecond, warmup.plus(duration).toMillis())
        / Duration.ofSeconds(1).toMillis();
  }
```

`Math.multiplyExact(a, b)` multiplies two `long` values and throws `ArithmeticException` instead of wrapping around. The method computes the planned number of requests as the rate times the milliseconds of warm-up plus duration, divided by 1,000 (the milliseconds in one second). With 3,000 requests per second, 5,000 ms of warm-up and 30,000 ms of duration that is 3,000 times 35,000 divided by 1,000, which is 105,000: the README's "105000 requests passed". `Math.floorMod` (used in `LoadRun`) is a remainder that is never negative. The `LongAdder` and `AtomicInteger` classes (feature 17) are counters that many threads can bump at once.

#### 13. Time: `Duration`, `Instant`, `Clock` and `System.nanoTime`

A `Duration` is a length of time (`Duration.ofSeconds(20)`). An `Instant` is a point on the time line, in UTC. A `Clock` produces the current `Instant` (`clock.instant()`). `JdbcHoldStore` receives a `Clock` in its constructor, so a test can give it a clock it moves by hand and make a hold expire without waiting 300 seconds:

```java
/** A clock the tests move by hand, so expiry is tested without waiting. Thread-safe. */
final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  void advance(Duration step) {
    now.updateAndGet(instant -> instant.plus(step));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the tests use UTC only");
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
```

`MutableClock extends Clock` and overrides the three methods a `Clock` needs. `AtomicReference<Instant>` holds the current time so another thread may read it while the test moves it with `advance`. `System.nanoTime()` returns a number of nanoseconds from an unspecified start; it is only good for **measuring a span** (subtract two readings) and never for the time of day. `Lifecycle.awaitDrained` and the handler in `SeatWaveServer` use it to measure how long a drain or a request took. The load harness counts every time in nanoseconds from the run's start and formats it as milliseconds or seconds only for the report.

#### 14. Regular expressions

A `Pattern` is a compiled regular expression. `pattern.matcher(text).matches()` is true if the **whole** text fits. The command classes use patterns to say what a value may contain:

```java
  // Module 7: an Idempotency-Key is an RFC 9651 String, a quoted value of printable characters.
  static final Pattern IDEMPOTENCY_KEY = Pattern.compile("\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"");
  // Module 1's seat labels: A-1 to T-1000.
  static final Pattern SEAT = Pattern.compile("[A-T]-([1-9][0-9]{0,2}|1000)");
  static final Pattern CUSTOMER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
```

- `[A-T]-([1-9][0-9]{0,2}|1000)` is a seat label: one letter A to T, a hyphen, then a number from 1 to 999 (a digit 1 to 9 followed by 0 to 2 more digits) or exactly `1000`. `[abc]` means one of these characters; `{0,2}` means 0 to 2 times; `|` means "or"; `( )` groups.
- `\"[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}\"` is the Idempotency-Key: a quote, 1 to 64 printable characters other than a quote or a backslash (written as the ranges `x21`, `x23` to `x5B`, `x5D` to `x7E`), and a quote. In a Java string `\\` is one backslash and `\"` is a quote.
- The compact constructor of `HoldCommand` checks each field against its pattern and throws `IllegalArgumentException` if one does not fit: the routes check first, so a failure there is a bug.

#### 15. Files and paths

A `Path` names a file or folder. `Path.of("config/seatwave-container.properties")` is relative to the folder the program starts in. `Files` reads and writes: `Files.newBufferedReader(path, StandardCharsets.UTF_8)` in `SettingsReader.load`, `Files.writeString(..., StandardOpenOption.CREATE, StandardOpenOption.APPEND)` in the deploy's audit line. Every read names the character set, so the result does not depend on the machine.

#### 16. Anonymous classes and `@Override`

An **anonymous class** defines and instantiates a class in one place, without a name.

```java
    Containers containers() {
      return new Containers() {
        @Override
        public void stop(String service) {
          calls.add("stop " + service);
          if (readsUntilSeenDown == 0) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "DOWN"));
          } else {
            readsLeft.put(service, readsUntilSeenDown);
          }
        }

        @Override
        public void start(String service) {
          calls.add("start " + service);
          calls.add("start " + service + " while " + status.get("seatwave/" + service));
          if (!staysDownAfterStart) {
            BACKENDS.forEach(backend -> status.put(backend + "/" + service, "UP"));
          }
        }
      };
    }
```

`new Containers() { ... }` makes a class on the spot that implements the interface `Containers` and gives its two methods a body: the test's stand-in records the call and changes what the fake balancer will report. `stop` marks the instance `DOWN` at once, or, when the test asked for it (`readsUntilSeenDown` above zero), only after that many reads of the balancer's table, as real checks take time. `start` records a second line with the status the instance had at that moment, so a test can prove the deploy waited until the instance was seen down before starting it. `@Override` asks the compiler to check that the method really replaces one of the type it implements. `BACKENDS.forEach(backend -> status.put(...))` is a lambda passed to `forEach`, which runs it for every item.

#### 17. Threads, executors and the small concurrency tools

A **thread pool** (an `ExecutorService`) keeps a fixed number of threads and a queue of tasks.

```java
    AtomicInteger workerNumber = new AtomicInteger();
    this.workers =
        new ThreadPoolExecutor(
            settings.workerThreads(),
            settings.workerThreads(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(settings.queueCapacity()),
            task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet()),
            (task, pool) -> {
              // A full queue is refused, never grown: the JDK server then closes that connection,
              // and the balancer counts it as a failed attempt.
              metrics.rejectedByQueue();
              throw new RejectedExecutionException("the request queue is full");
            });
```

- `ThreadPoolExecutor(core, max, keepAlive, unit, queue, factory, handler)` takes seven arguments. Here the core and the maximum are both `settings.workerThreads()` (64), so the pool never grows or shrinks. `0, TimeUnit.MILLISECONDS` is the keep-alive for extra threads, which do not exist.
- `new ArrayBlockingQueue<>(settings.queueCapacity())` is the **bounded queue** in front of the workers: when all 64 workers are busy, requests wait in it, and when it is full the next one is refused.
- `task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet())` names each thread `seatwave-worker-1`, `seatwave-worker-2` and then the next numbers. `AtomicInteger` is an integer that can be changed by several threads without a lock: `incrementAndGet()` adds one and returns the new value as one indivisible step.
- The last argument is the **rejection handler**, a lambda with two parameters. When the queue is full it counts the refusal in the metrics and throws `RejectedExecutionException`, so the JDK server closes that connection (the comment adds that the balancer counts it as a failed attempt). A full queue is refused and never grown.

**A lock and a condition.** `Lifecycle` holds the module's central rule, and it does so with one lock:

```java
public final class Lifecycle {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition idle = lock.newCondition();
  private LifecycleState state = LifecycleState.STARTING;
  private long inFlight;
```
```java
  public DrainResult awaitDrained(Duration deadline) throws InterruptedException {
    Objects.requireNonNull(deadline, "deadline");
    if (deadline.isNegative()) {
      throw new IllegalArgumentException("the drain deadline cannot be negative: " + deadline);
    }
    long startNanos = System.nanoTime();
    long remainingNanos = deadline.toNanos();
    lock.lock();
    try {
      if (state == LifecycleState.STARTING || state == LifecycleState.READY) {
        state = LifecycleState.DRAINING;
      }
      // A loop, because a condition can wake up without a signal; awaitNanos returns what is left.
      while (inFlight > 0 && remainingNanos > 0) {
        remainingNanos = idle.awaitNanos(remainingNanos);
      }
      return new DrainResult(inFlight, Duration.ofNanos(System.nanoTime() - startNanos));
    } finally {
      state = LifecycleState.STOPPED;
      lock.unlock();
    }
  }
```

A **lock** (`ReentrantLock`) lets one thread at a time run the code between `lock.lock()` and `lock.unlock()`. The state and the in-flight count change under the same lock, so "is it still ready?" and "count this request" are one step. A **condition** (`idle`) lets a thread wait inside the lock until another thread signals it: `idle.awaitNanos(remainingNanos)` gives up the lock and sleeps until `idle.signalAll()` (in `release`, when the count reaches zero) or until the time is up, and returns how much time is left. The `while` loop is there because a wait can wake up without a signal. The `finally` sets the state to `STOPPED` and unlocks on every path, including the interruption that `awaitNanos` can throw. `volatile` and `synchronized` are not used anywhere in `src/main`: the lock covers every shared field. (One test stand-in, in `RollingDeployTest`, marks a field `volatile`, which makes every thread see its latest value.)

**Counters and maps shared by threads.** `LongAdder` (in `ServiceMetrics`) is a counter built for many writers: `increment()`, `add(n)`, `sum()`. `ConcurrentHashMap` is a map that many threads may use at once; `requests.computeIfAbsent(route + "|" + status, key -> new LongAdder()).increment()` makes the counter for a route and status on first use and bumps it. The database, not a Java map, now decides who owns a seat: the rule moved out of memory so that three instances can share it.

**Virtual threads, a semaphore and a queue** in the load run:

```java
  List<Outcome> run() throws InterruptedException {
    Queue<Outcome> outcomes = new ConcurrentLinkedQueue<>();
    Semaphore slots = new Semaphore(settings.maxInFlight());
    long planned = settings.plannedRequests();
    long firstNanos = startNanos - settings.warmup().toNanos();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long requestIndex = 0; requestIndex < planned; requestIndex++) {
        // From the index, never from a rounded interval: at 3,000 a second the interval is
        // 333,333.3 ns, and adding a rounded 333,333 ns would start request 15,000 (the first of
        // the measured part) 5 microseconds before time zero, among the warm-up.
        long dueNanos =
            firstNanos
                + Math.multiplyExact(requestIndex, NANOS_PER_SECOND) / settings.ratePerSecond();
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos > 0) {
          LockSupport.parkNanos(waitNanos);
        }
        if (Thread.interrupted()) {
          throw new InterruptedException("the load run was interrupted");
        }
        long dueAt = dueNanos - startNanos;
        boolean isHold = isHold(requestIndex);
        if (dueAt >= 0) {
          (isHold ? measuredHolds : measuredReads).incrementAndGet();
        }
        if (!slots.tryAcquire()) {
          outcomes.add(Outcome.notSent(dueAt));
          continue;
        }
        long index = requestIndex;
        senders.submit(() -> {
          try {
            outcomes.add(sendSafely(index, dueAt, isHold));
          } finally {
            slots.release();
          }
        });
      }
    }
    if (outcomes.size() != planned) {
      throw new IllegalStateException(
          "the run planned " + planned + " requests but has " + outcomes.size() + " outcomes");
    }
    return new ArrayList<>(outcomes);
  }
```

- `Queue<Outcome> outcomes = new ConcurrentLinkedQueue<>();` is a queue many threads may add to at once; each sender thread adds its result.
- `Semaphore slots = new Semaphore(settings.maxInFlight())` holds 4,000 permits. `slots.tryAcquire()` takes one without waiting and returns `false` if none is left, in which case the request is recorded as "not sent" instead of piling up. `slots.release()` in the `finally` gives the permit back.
- `dueNanos` is computed from the request's index every time, `firstNanos + Math.multiplyExact(requestIndex, NANOS_PER_SECOND) / settings.ratePerSecond()`, and not by adding a rounded interval again and again. The comment gives the numbers: at 3,000 requests a second the interval is 333,333.3 ns, and adding a rounded 333,333 ns would let the error grow until request 15,000 started 5 microseconds too early. Multiplying first and dividing last keeps the error under one nanosecond. `Math.multiplyExact` throws instead of silently wrapping around if the product were too large for a `long`.
- `(isHold ? measuredHolds : measuredReads).incrementAndGet()` picks one of two counters with a conditional expression and adds one to it, for requests due at or after time zero.
- After the loop, the `try` block has closed the executor, which waits for every task. Then the code checks that there is one outcome per planned request and throws an `IllegalStateException` if not: a lost outcome would be a bug in the harness, not a result.
- `Executors.newVirtualThreadPerTaskExecutor()` makes a new **virtual thread** for each submitted task (Java 21). Thousands may wait for an answer at once at almost no cost, which a pool of 64 platform threads could not do.
- `LockSupport.parkNanos(waitNanos)` sleeps this thread until the next request is due, so the loop sends at a steady rate (`java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`). `Thread.interrupted()` tests and clears the interrupt flag, and the code turns it into an `InterruptedException` so the run can be stopped.
- `long index = requestIndex;` copies the loop counter into a variable that is never changed: a lambda may use only variables that are not changed after they are set. `dueAt` and `isHold` are such variables already. The task calls `sendSafely`, which turns an unexpected error in the harness's own code into an outcome instead of losing it (`java-harness-walkthrough-1.md`).

**Starting and stopping.** The main thread of an instance waits for a signal. `CountDownLatch stopped = new CountDownLatch(1);` is a gate with a count of 1. `stopped.await()` blocks the main thread until the count reaches 0; the shutdown hook (feature 4) calls `stopped.countDown()` when the drain is over. `throws InterruptedException` appears because `await()` can be interrupted. Code that catches it should restore the flag with `Thread.currentThread().interrupt()`, as `SeatWaveServer.drainAndStop` and `DockerCompose.run` do.

**A child process.** The rolling deploy runs the `docker` program with a fixed argument list and a time limit:

```java
  private void run(List<String> action, Duration limit) throws IOException {
    List<String> command = new ArrayList<>(program);
    command.addAll(List.of("-p", project));
    command.addAll(action);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    // Read while the child runs: a child whose output fills the pipe would otherwise wait for a
    // reader forever, and the wait below would end only at its time limit.
    FutureTask<String> output =
        new FutureTask<>(() -> {
          try (InputStream stream = process.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          }
        });
    Thread.ofVirtual().name("docker-compose-output").start(output);
    try {
      if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException(String.join(" ", command) + " did not finish within " + limit);
      }
      // Bounded too: a grandchild that kept the pipe open would otherwise hold the read forever.
      String text = output.get(limit.toMillis(), TimeUnit.MILLISECONDS);
      if (process.exitValue() != 0) {
        throw new IOException(
            String.join(" ", command) + " exited " + process.exitValue() + ": " + text.strip());
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(String.join(" ", command) + " was interrupted", interrupted);
    } catch (TimeoutException stillOpen) {
      process.destroyForcibly();
      throw new IOException(
          "the output of " + String.join(" ", command) + " did not end within " + limit, stillOpen);
    } catch (ExecutionException unreadable) {
      throw new IOException(
          "the output of " + String.join(" ", command) + " cannot be read", unreadable.getCause());
    }
  }
```

`command` starts as a copy of `program` (`docker compose` in real use; a test passes another program) and gets `-p <project>` and the action added. `new ProcessBuilder(command).redirectErrorStream(true).start()` starts the process and merges its error output into its normal output. The output is read at the same time by a `FutureTask`, a task whose result can be fetched later with `get()`, on a virtual thread of its own (`Thread.ofVirtual().name(...).start(output)`). The comment gives the reason: a child process writes into a pipe of limited size, and if nobody reads it, a child with a lot of output blocks forever, so the wait would end only at its time limit. `process.waitFor(limit, unit)` waits for the process to end and returns `false` if the limit passes, in which case `destroyForcibly()` kills it. `output.get(limit, unit)` then fetches the text, waiting at most the same limit (a grandchild process that kept the pipe open would otherwise hold the read forever); if the wait runs out it throws a `TimeoutException`, and if reading failed an `ExecutionException`, which the two last `catch` blocks turn into an `IOException` with the real cause. The list form (never one string passed to a shell) means a service name cannot turn into a second command. This note did not run `docker`; `DockerComposeTest` runs the same code with a stand-in program.

#### 18. Javadoc

Almost every class and method in `src/main` has a Javadoc comment with `@param`, `@return` and `@throws`, and a `@param` line for each component of a record. They are the first place to read what a class is for and which rules it keeps. The comment of `Hold` quoted above is a typical one.

#### 19. The static helpers used everywhere

- `Objects.requireNonNull(x, "x")`: throws if `x` is `null`.
- `List.of(...)`, `Map.of(...)`, `List.copyOf(...)`: immutable collections.
- `Optional.of`, `Optional.empty`, `Optional.ofNullable`.
- `Duration.ofMillis`, `Duration.ofSeconds`; `Instant.now`; `Clock.systemUTC()`.
- `StandardCharsets.UTF_8`: the character set named in every conversion between text and bytes.
- `UUID.randomUUID()`: a random identifier, used for hold ids and for the correlation id of each request.

### The minimum Java version of each feature

| Feature | First in Java |
|---|---|
| `List.of`, `Map.of`, `Set.of`, `List.copyOf` | 9 / 10 |
| Lambdas, `Optional`, streams, `java.time` | 8 |
| `try-with-resources` on an existing variable (`try (exchange)` in `SeatWaveServer`) | 9 |
| `switch` expressions with `->` and `yield` | 14 |
| Records | 16 |
| `instanceof` patterns (`step instanceof Result.Error<Integer, String> failed` in `RollingDeploy`) | 16 |
| `Stream.toList()` | 16 |
| Sealed interfaces | 17 |
| Pattern matching for `switch`, record patterns | 21 |
| Virtual threads (`LoadRun`) | 21 |
| `List.getFirst()` (`StatTable.parse`) | 21 |
| `InetAddress.ofLiteral` (`ServiceSettings`, `NamedBalancer`) | 22 |

The project compiles with `--release 25`, so all of these are available.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026 (Temurin 25.0.2). Timings differ on every run.

1. See the Java version and compile the main code:

```
java -version
./gradlew --offline -q compileJava
```

   Expected: `openjdk version "25.0.2" 2026-01-20 LTS` on the first line, and no output from Gradle.

2. Count the class files and the source files:

```
find build/classes/java/main -name '*.class' | wc -l
git ls-files 'src/main/java/*.java' | wc -l
```

   Expected: `59` and `40`.

3. Look inside a record:

```
javap -p -cp build/classes/java/main io.plainround.seatwave.balancer.seatwave.Hold
```

   Expected: `public final class ...Hold extends java.lang.Record`, five `private final` fields, the constructor, `isActiveAt`, `toString`, `hashCode`, `equals` and five accessors.

4. See the class-file version:

```
javap -v -cp build/classes/java/main io.plainround.seatwave.balancer.seatwave.Hold | grep 'major version'
```

   Expected: `major version: 69`, which is Java 25.

5. See that a sealed interface lists its subtypes:

```
javap -v -cp build/classes/java/main io.plainround.seatwave.balancer.seatwave.HoldRefusal | grep -A3 PermittedSubclasses
```

   Expected: `PermittedSubclasses:` followed by `io/plainround/seatwave/balancer/seatwave/HoldRefusal$SeatTaken`, `...$NoSuchSeat` and `...$KeyReused`.

## Mistakes people make

- **Comparing text with `==`.** It compares references, not letters. Use `.equals(...)`; the code does (`row.server().equals("BACKEND")`).
- **Forgetting that `int` wraps.** Use `long` for nanoseconds and counts, and `Math.multiplyExact` where wrapping would be a bug.
- **Catching `Exception` or ignoring it.** The code catches the specific type it can handle.
- **Using `null` for "no result".** `Optional` or a `Result` says it in the type.
- **A mutable object shared between threads without protection.** Records and `List.copyOf` copy; the lock in `Lifecycle`, `LongAdder` and the concurrent collections are the tools that are safe.
- **Leaving a resource open.** try-with-resources closes it.
- **Using the default `Locale` in output.** `Locale.ROOT` keeps a report the same on every machine.
- **Assuming virtual threads are always better.** The instance uses a fixed pool of platform threads on purpose, so its size and queue are what the lab measures; the harness uses virtual threads because it only waits.
- **Swallowing `InterruptedException`.** Restore the flag with `Thread.currentThread().interrupt()` before leaving, as the code does.

## Check yourself

1. *What does the compact constructor of a record do?* It runs first, with the parameters in scope, to check or adjust the values before the fields are assigned.
2. *Why does a `switch` over a sealed interface need no `default`?* The compiler knows every permitted type, so it can check that all are handled.
3. *What is the difference between a checked and an unchecked exception?* A checked one (`IOException`, `StoreUnavailableException`) must be declared with `throws` or caught; an unchecked one (`IllegalArgumentException`) need not be.
4. *Why does `Lifecycle` use one lock for both the state and the in-flight count?* "Is it still ready?" and "count this request" must be one step, so no request is counted after draining began.
5. *Why are the workers platform threads and the harness's senders virtual threads?* The workers are a fixed, measured resource; the senders only wait for answers, so thousands of cheap threads fit.

## Further reading

- The Java Language Specification, Java SE 25: <https://docs.oracle.com/javase/specs/jls/se25/html/index.html>
- JDK 25 API documentation: <https://docs.oracle.com/en/java/javase/25/docs/api/index.html>
- Records (JEP 395): <https://openjdk.org/jeps/395>
- Sealed classes (JEP 409): <https://openjdk.org/jeps/409>
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): <https://openjdk.org/jeps/441>, <https://openjdk.org/jeps/440>
- `ThreadPoolExecutor` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html>
- Virtual threads (JEP 444): <https://openjdk.org/jeps/444>
