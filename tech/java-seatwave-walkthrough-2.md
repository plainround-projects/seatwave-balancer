# The `seatwave` package, part 2: replies, requests, the server and the routes (line by line)

This is part 2 of 3. Part 1 (`java-seatwave-walkthrough-1.md`) reads `ServiceSettings`, `Hold`, `GrantedHold`, `HoldRefusal`, `HoldCommand`, `HoldStore`, `StoreUnavailableException`, `LifecycleState` and `Lifecycle`. This part reads `Reply`, `Request`, `Problems`, `SeatWaveServer` and `HoldRoutes`. Part 3 (`java-seatwave-walkthrough-3.md`) reads `JdbcHoldStore`, `DatabasePool`, `ServiceMetrics` and `SeatWaveCli`.

## What it is

These five files are the part of an instance that talks HTTP.

- `Reply.java`: what a route answers: a status number, a media type, the body bytes and some headers. It cannot be changed after it is built.
- `Request.java`: one request as the routes see it, copied out of the web server's own objects.
- `Problems.java`: builds the one error shape of SeatWave, a JSON body called "problem details".
- `SeatWaveServer.java`: the web server. It opens two ports, runs requests on a fixed pool of threads, asks `Lifecycle` for permission before it reads a request, and drains on stop.
- `HoldRoutes.java`: the two routes, `POST /v1/holds` and `GET /v1/holds/{holdId}`, and every rule that turns a request into a reply.

Terms you need.

- **HTTP** is the text conversation between a client and a server. A **request** has a **method** (`GET` reads, `POST` creates), a **path** (`/v1/holds`), **headers** (lines of `Name: value`) and sometimes a **body**. A **reply** has a **status** (a number such as 201 or 503), headers and a body.
- A **status code** in the 400s says the client's request was wrong. In the 500s it says the server could not do it now. `503 Service Unavailable` means "try again, maybe somewhere else".
- A **header** is one line of metadata. `Content-Type` says what the body is. `Idempotency-Key` says "this is the same attempt as before". `Retry-After: 1` says "wait one second". `Connection: close` says "close this connection after this reply".
- **JSON** is a text format for data with named fields. **Problem details** (RFC 9457) is a standard JSON shape for errors: `type`, `title`, `status`, `detail`, `instance`.
- A **port** is a numbered door on a machine. A **listener** is a server waiting at one port. This instance has two listeners.
- A **thread pool** is a fixed group of threads that take jobs from a **queue**, a waiting line. A **bounded** queue has a size limit.
- An **executor** is the Java object that runs jobs on threads. `ThreadPoolExecutor` is the pool kind used here.
- A **handler** is the method the web server calls once for every request.
- **MDC** (mapped diagnostic context) is a small per-thread notebook that the logging library reads. The code writes the request's id into it, and every log line printed by that thread then carries the id (see `logging-slf4j-logback.md`).
- A **correlation id** is a random label given to one request so that all its log lines and its error body can be matched.
- A **SHA-256 hash** is a fixed-length fingerprint of any text; the same text always gives the same 64 hexadecimal characters.

## Why this repository uses it

- **One gate.** The server registers exactly one handler for the whole request port. That handler asks `Lifecycle.tryAdmit` before it reads a byte. No other path reaches the routes. So the rule "no new request after draining starts" holds for every path, including paths that do not exist.
- **Two ports.** The balancer's health check goes to a second port with its own thread. A busy instance still answers it, so a busy instance is not mistaken for a dead one. Part of the module's story is the difference between `/health` ("is the process alive?") and `/ready` ("should requests come here?").
- **A refusal the balancer understands.** A draining or starting instance answers `503` with `Connection: close` and a body that says "nothing was done". The balancer's configuration retries exactly that case on another instance (`retry-on conn-failure empty-response 503`, see `haproxy-config-walkthrough.md`), so the fan never sees it.
- **Same answers on every instance.** Every rule is in `HoldRoutes`, and every hold is in the database, so a request gets the same answer from instance 1, 2 or 3.
- **Honest errors.** An invalid request is `400`, an unknown path `404`, a taken seat `409`, a reused key `422`, a broken database `503`. Clients act on the status and the `type`, never on the wording.

## Where it appears

| File | What it does |
|---|---|
| `seatwave/Reply.java` | Immutable reply. Built by `HoldRoutes` and `Problems`, written by `SeatWaveServer.write`. |
| `seatwave/Request.java` | Immutable request. Built by `SeatWaveServer.request`, read by `HoldRoutes`. |
| `seatwave/Problems.java` | Builds `application/problem+json` replies. Used only by `HoldRoutes`. |
| `seatwave/SeatWaveServer.java` | Two listeners, the worker pool, the gate, drain and stop. Started by `SeatWaveCli`; started by the tests too. |
| `seatwave/HoldRoutes.java` | The routes. Called by `SeatWaveServer.handle`; tested directly by `HoldRoutesTest`. |

## How it works underneath

**Starting.** `SeatWaveServer.start` builds a thread pool of `workerThreads` threads with a queue of `queueCapacity` places, creates two JDK `HttpServer` objects (one for requests, one for checks), gives each its executor and its single handler, and starts them. From now on the operating system hands every connection to the server, and the server hands every request to an executor thread.

**A request, step by step.**

1. A client connects to the request port. The JDK server reads the request line and headers and submits a job to the worker pool.
2. A worker thread runs `handle`. `handle` makes a random correlation id, then calls `lifecycle.tryAdmit()`.
3. If the instance is not ready, `handle` reads and throws away the body, writes the `503` and returns. Nothing else is done.
4. If admitted, `handle` puts the id in the MDC, copies the request into a `Request`, calls `routes.route`, writes the `Reply`, counts the request in the metrics, and in a `finally` block closes the admission and clears the MDC.
5. The close is after the response is written. The drain waits for that count, so a request that is still writing its answer still counts as running.

**When the queue is full.** The pool has a fixed number of threads and a bounded queue. When all threads are busy and the queue is full, the next job is rejected. The code counts the rejection and throws `RejectedExecutionException`; the JDK server then closes that client's connection without a reply. The balancer sees a connection that ended with no bytes and counts a failed attempt. I ran this with one worker and a queue of one, and six requests at once: two were served, four connections were closed without a reply, and the metric `seatwave_rejected_queue_full_total` showed 4. Notice also what the in-flight count does: a request waiting in the queue is not in flight yet, because admission happens inside `handle`, which a worker has not started. During a drain, such a queued request is answered `503` "not admitted", which the balancer retries elsewhere.

**Stopping.** `drainAndStop` flips the lifecycle to draining, waits for in-flight requests (up to the deadline), then closes the two ports and the pool. The wait comes first on purpose: closing the port first would cut the requests that are still being answered.

## Walkthrough of this repository's use

### `seatwave/Reply.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
```

Imports. `ArrayList` is a growable list; `List` is the list interface; `Map` is a table of keys and values (here only its `Map.Entry`, one key-value pair).

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
```

The Javadoc says "Immutable: `withHeader` returns a new reply". **Immutable** means that once built, the object never changes. That matters when many threads share data. The record has four components: `status`, `contentType`, `body` (a `byte[]`, an array of bytes) and `headers` (a list of name-value pairs, in order).

```java
  /** Copies the body and the headers. */
  public Reply {
    Objects.requireNonNull(contentType, "contentType");
    body = Objects.requireNonNull(body, "body").clone();
    headers = List.copyOf(headers);
  }
```

The compact constructor makes the object safe.

- `contentType` must not be null.
- `body = Objects.requireNonNull(body, "body").clone()` checks for null and then stores a **copy** of the array. The caller's array can be changed later without changing the reply. This is a **defensive copy**.
- `headers = List.copyOf(headers)` stores an unmodifiable copy of the list. It also refuses a null list or a null element.

```java
  /**
   * Returns a JSON reply.
   *
   * @param status the HTTP status
   * @param json the body
   * @return the reply, with {@code application/json}
   */
  public static Reply json(int status, String json) {
    return new Reply(status, "application/json", json.getBytes(StandardCharsets.UTF_8), List.of());
  }
```

`json(status, json)` is a shortcut that builds a reply with the media type `application/json`, the text turned into bytes in UTF-8 (`getBytes(StandardCharsets.UTF_8)`), and no extra headers. UTF-8 is the usual way to write any character as bytes. `List.of()` is an empty immutable list.

```java
  @Override
  public byte[] body() {
    return body.clone();
  }
```

A record writes its own accessor `body()` that returns the array itself. The code **overrides** it (`@Override`) to return a copy, so a caller cannot change the stored bytes through the accessor either. The copy on the way in and the copy on the way out together make the array safe.

```java
  /**
   * Returns the body as text, for tests and logs.
   *
   * @return the body decoded as UTF-8
   */
  public String bodyText() {
    return new String(body, StandardCharsets.UTF_8);
  }
```

`bodyText` decodes the bytes back to text. The Javadoc says "for tests and logs". The tests use it to check what a reply says.

```java
  /**
   * Returns the first value of a header.
   *
   * @param name the header name, matched without regard to case
   * @return the value, or an empty string when the reply has no such header
   */
  public String header(String name) {
    return headers.stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }
```

`header(name)` finds the first header with that name. It reads the list as a **stream** (a pipeline of steps): `filter` keeps the entries whose name equals the wanted one ignoring upper and lower case (`equalsIgnoreCase`, because HTTP header names ignore case), `map(Map.Entry::getValue)` takes the value of each (`Map.Entry::getValue` is a **method reference**, a short way to write a function), `findFirst` takes the first one as an `Optional`, and `orElse("")` gives an empty string if there is none. An empty string and not null: callers never need a null check.

```java
  /**
   * Adds one header.
   *
   * @param name the header name
   * @param value the header value
   * @return a new reply with the header last
   */
  public Reply withHeader(String name, String value) {
    List<Map.Entry<String, String>> more = new ArrayList<>(headers);
    more.add(Map.entry(name, value));
    return new Reply(status, contentType, body, more);
  }
}
```

`withHeader` returns a **new** reply. It copies the list into an `ArrayList`, adds the new `Map.entry(name, value)` at the end, and calls the constructor again (which copies again). The old reply is untouched. Replies are built by chaining this: `Reply.json(...).withHeader("Location", ...).withHeader("Cache-Control", "no-store")`.

### `seatwave/Request.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import java.util.Objects;
```

The imports are short: only `Objects`.

```java
/**
 * One request as the routes see it: the parts of the HTTP exchange they need, already copied out of
 * the JDK's server types, so no route can touch the connection.
 *
 * @param method the HTTP method
 * @param path the raw path, without the query
 * @param rawQuery the raw query, or an empty string
 * @param contentType the {@code Content-Type} header, or an empty string
 * @param idempotencyKey the {@code Idempotency-Key} header, or an empty string
 * @param body the body, at most {@link HoldRoutes#MAX_BODY_BYTES} bytes, copied on the way in and
 *     out
 * @param isBodyTooLarge true when the client sent more than the limit; the body is then empty
 * @param correlationId the id written in every log line of this request
 */
public record Request(
    String method,
    String path,
    String rawQuery,
    String contentType,
    String idempotencyKey,
    byte[] body,
    boolean isBodyTooLarge,
    String correlationId) {
```

The Javadoc says why this type exists: "already copied out of the JDK's server types, so no route can touch the connection". A route works on plain data and cannot, by mistake, write to the connection or read more of the body. It also makes the routes easy to test: a test builds a `Request` with `new Request(...)` and needs no server. The components:

- `method`, `path`, `rawQuery`: what the client asked. `rawQuery` is the part after `?`, or an empty string.
- `contentType`, `idempotencyKey`: the two headers a route reads. An empty string means the header was absent.
- `body`: at most 1,024 bytes (`HoldRoutes.MAX_BODY_BYTES`).
- `isBodyTooLarge`: true when the client sent more than that. The body is then empty.
- `correlationId`: the id for log lines and the error body.

```java
  /** Checks and copies the fields. */
  public Request {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rawQuery, "rawQuery");
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    body = Objects.requireNonNull(body, "body").clone();
    Objects.requireNonNull(correlationId, "correlationId");
  }
```

The compact constructor refuses a null in every object field and clones the body array.

```java
  @Override
  public byte[] body() {
    return body.clone();
  }
}
```

The accessor for the body is overridden, as in `Reply`, to return a copy.

### `seatwave/Problems.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one error shape of SeatWave version 1: problem details (RFC 9457), with the {@code type} URIs
 * of Module 7, so a client acts on the type and the status and never on the wording. Thread-safe:
 * the codec is.
 */
final class Problems {
```

`Problems` is package-private (no `public`): only this package uses it. The comment names the standard: RFC 9457, "problem details". The `type` URIs are those of Module 7, so a client acts on the `type` and the status, never on the human wording.

```java
  // Documentation names under the reserved .example domain (RFC 2606), as Module 7 uses them.
  private static final String TYPE_BASE = "https://seatwave.example/problems/";

  private final JsonCodec json;

  Problems(JsonCodec json) {
    this.json = Objects.requireNonNull(json, "json");
  }
```

`TYPE_BASE` is the start of every `type` address. The comment explains that `.example` is a domain reserved by RFC 2606 for documentation, so the address can never lead to a real website. The class keeps a `JsonCodec` and receives it in its constructor.

```java
  Reply of(int status, String slug, String title, String detail, String correlationId) {
    ProblemBody problem =
        new ProblemBody(
            TYPE_BASE + slug, title, status, detail, "urn:seatwave:request:" + correlationId);
    return new Reply(
        status,
        "application/problem+json",
        json.write(problem).getBytes(StandardCharsets.UTF_8),
        List.of(Map.entry("Cache-Control", "no-store")));
  }
```

`of` builds one error reply.

- A `ProblemBody` is made from the `type` (the base plus a short slug such as `seat-unavailable`), the `title`, the numeric `status`, the `detail`, and `instance`. `instance` is `urn:seatwave:request:` plus the correlation id. A client that reports a problem can quote that id and the operator can find the log lines with the same id.
- The reply has the status, the media type `application/problem+json`, the JSON as UTF-8 bytes, and one header: `Cache-Control: no-store`, which tells every cache to keep no copy of an error.

```java
  /** The JSON body; record components are written in this order. */
  record ProblemBody(String type, String title, int status, String detail, String instance) {}
}
```

`ProblemBody` is a record nested in the class. The Javadoc says "record components are written in this order": Jackson writes the fields in the order the components are declared, so the JSON always starts with `type`.

### `seatwave/SeatWaveServer.java`

The imports.

```java
package io.plainround.seatwave.balancer.seatwave;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.plainround.seatwave.balancer.json.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
```

- `com.sun.net.httpserver` is the small web server that comes with the JDK. `HttpServer` is the server, `HttpExchange` is one request and its reply together.
- `Clock` is a source of the current time that a test can replace.
- `ArrayBlockingQueue`, `ExecutorService`, `Executors`, `RejectedExecutionException`, `ThreadPoolExecutor`, `TimeUnit` and `AtomicInteger` are the tools for the worker pool.
- `Logger`, `LoggerFactory` and `MDC` are from SLF4J, the logging front (see `logging-slf4j-logback.md`).

```java
/**
 * One SeatWave instance on the JDK's own HTTP server: a request listener for the balancer and an
 * operations listener for the balancer's checks.
 *
 * <p>The request listener has one context, {@code /}, and one handler, {@link #handle}, which asks
 * the {@link Lifecycle} for an admission before it reads the request. That is the only way to the
 * routes, so the invariant holds on every path: once draining starts, a request is answered {@code
 * 503} with {@code Connection: close} and nothing is done for it.
 *
 * <p>Two checks, two questions. {@code /health} asks "is the process alive?" and answers {@code
 * 200} until it stops. {@code /ready} asks "should the balancer send requests here?" and answers
 * {@code 200} only while the instance is ready and its database answers. The balancer checks {@code
 * /ready}. Thread-safe: handlers share only thread-safe collaborators.
 */
public final class SeatWaveServer {
```

The class comment is the best summary of the file.

- Two listeners: one for the balancer's requests, one for the balancer's checks.
- One context `/` and one handler `handle` that asks the lifecycle first.
- Two questions: `/health` ("is the process alive?", `200` until it stops) and `/ready` ("should the balancer send requests here?", `200` only while the instance is ready and its database answers). The balancer checks `/ready`.

```java
public final class SeatWaveServer {

  private static final Logger LOG = LoggerFactory.getLogger(SeatWaveServer.class);
  private static final int OK = 200;
  private static final int NOT_FOUND = 404;
  private static final int UNAVAILABLE = 503;
  // 0: the in-flight wait is the Lifecycle's job, done before the listeners are stopped.
  private static final int STOP_DELAY_SECONDS = 0;
  private static final long WORKER_STOP_SECONDS = 5;

  private final HttpServer requests;
  private final HttpServer admin;
  private final ExecutorService adminThread;
  private final ThreadPoolExecutor workers;
  private final Lifecycle lifecycle = new Lifecycle();
  private final ServiceMetrics metrics = new ServiceMetrics(lifecycle);
  private final HoldRoutes routes;
  private final HoldStore store;
  private final String instanceName;
```

- `LOG` is this class's logger.
- `OK`, `NOT_FOUND`, `UNAVAILABLE` are named status numbers. A name beats a bare 503.
- `STOP_DELAY_SECONDS = 0`. The comment explains: the JDK server's `stop(delay)` waits up to `delay` seconds for running requests. The code already waited in the lifecycle, before it closes anything, so it asks for no further wait.
- `WORKER_STOP_SECONDS = 5` is how long the pool gets to end its threads.
- The fields: the two servers, the admin thread, the worker pool, one `Lifecycle`, the `ServiceMetrics` that watches the lifecycle, the routes, the store, and the instance's name (`seatwave-1`).

```java
  private SeatWaveServer(
      ServiceSettings settings, String instanceName, HoldStore store, Clock clock)
      throws IOException {
    this.instanceName = instanceName;
    this.store = store;
    this.routes = new HoldRoutes(store, new JsonCodec(), clock);
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

The constructor is private: callers use `start`.

- `this.routes = new HoldRoutes(store, new JsonCodec(), clock)` builds the routes with the store and a JSON codec.
- `AtomicInteger workerNumber` hands out numbers 1, 2, 3 for thread names.
- `new ThreadPoolExecutor(core, max, keepAlive, unit, queue, threadFactory, rejectionHandler)` has seven arguments:
  - core and maximum thread count are both `workerThreads`: a **fixed** pool. It never grows.
  - `0, TimeUnit.MILLISECONDS` is the keep-alive time for threads above the core; there are none.
  - `new ArrayBlockingQueue<>(settings.queueCapacity())` is the bounded waiting line.
  - The thread factory `task -> new Thread(task, "seatwave-worker-" + workerNumber.incrementAndGet())` makes platform threads with readable names, so a thread dump shows `seatwave-worker-17`.
  - The rejection handler `(task, pool) -> { ... }` runs when the queue is full. It counts `metrics.rejectedByQueue()` and throws. The comment says what follows: the JDK server then closes that connection, and the balancer counts a failed attempt. The queue is never grown.

```java
    this.requests =
        HttpServer.create(
            new InetSocketAddress(settings.bindAddress(), settings.port()), settings.backlog());
    this.admin =
        HttpServer.create(
            new InetSocketAddress(settings.bindAddress(), settings.adminPort()),
            settings.backlog());
    // One thread of its own: the checks are answered even when every worker is busy, so a busy
    // instance is not taken for a dead one.
    this.adminThread =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "seatwave-admin"));
    admin.setExecutor(adminThread);
    admin.createContext("/", this::handleAdmin);
    requests.setExecutor(workers);
    requests.createContext("/", this::handle);
    requests.start();
    admin.start();
  }
```

- `HttpServer.create(new InetSocketAddress(address, port), backlog)` makes a server bound to an address and port. `backlog` is the queue of connections the operating system holds for it. Port 0 means "any free port".
- `this.adminThread = Executors.newSingleThreadExecutor(...)` is a pool of exactly one thread named `seatwave-admin`. The comment gives the reason: the checks must be answered even when every worker is busy.
- `admin.setExecutor(adminThread)` and `requests.setExecutor(workers)` give each server its own threads. `createContext("/", handler)` says "every path starting with `/` goes to this handler". `this::handleAdmin` and `this::handle` are method references.
- `requests.start()` and `admin.start()` open the ports. They are the last lines, so nothing receives a request before the object is complete. The constructor declares `throws IOException` because binding a port can fail, for example when it is already taken.

```java
  /**
   * Opens both listeners. The instance starts in {@link LifecycleState#STARTING} and refuses every
   * request until {@link #markReady} is called.
   *
   * @param settings the checked settings
   * @param instanceName the instance's name, sent in {@code X-Served-By} on every reply
   * @param store the shared hold store
   * @param clock the clock holds expire by
   * @return the running server
   * @throws IOException if a port cannot be bound
   */
  public static SeatWaveServer start(
      ServiceSettings settings, String instanceName, HoldStore store, Clock clock)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(instanceName, "instanceName");
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(clock, "clock");
    return new SeatWaveServer(settings, instanceName, store, clock);
  }
```

`start` is the public way in. It refuses a null for each argument, then builds the server. The Javadoc states the starting state: the instance is in `STARTING` and refuses every request until `markReady` is called.

```java
  /**
   * Lets requests in.
   *
   * @return true if the instance is now ready; false if shutdown began first
   */
  public boolean markReady() {
    boolean isReady = lifecycle.markReady();
    LOG.info("{} is {}", instanceName, lifecycle.state());
    return isReady;
  }
```

`markReady` asks the lifecycle to become ready, logs the state (`{}` in the message is a placeholder SLF4J fills with the argument), and returns whether it worked.

```java
  /**
   * Drains and stops: no new request from now on, waits for in-flight requests up to the deadline,
   * then closes both listeners and the workers. Safe to call more than once.
   *
   * @param deadline the longest wait for in-flight requests
   * @return how the drain ended
   */
  public Lifecycle.DrainResult drainAndStop(Duration deadline) {
    lifecycle.startDraining();
    LOG.info("{} is draining with {} in flight", instanceName, lifecycle.inFlight());
    Lifecycle.DrainResult result;
    try {
      result = lifecycle.awaitDrained(deadline);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      result = new Lifecycle.DrainResult(lifecycle.inFlight(), Duration.ZERO);
    }
    requests.stop(STOP_DELAY_SECONDS);
    admin.stop(STOP_DELAY_SECONDS);
    adminThread.shutdownNow();
    workers.shutdown();
    try {
      if (!workers.awaitTermination(WORKER_STOP_SECONDS, TimeUnit.SECONDS)) {
        workers.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
    return result;
  }
```

`drainAndStop` is the other half of the invariant, in four moves.

1. `lifecycle.startDraining()` closes the gate: from now on every request is refused. The log line says how many are in flight.
2. `lifecycle.awaitDrained(deadline)` waits for the in-flight count to reach zero or for the deadline. If the waiting thread is interrupted, the code sets the thread's interrupt flag again (`Thread.currentThread().interrupt()`, so the caller can still see it) and builds a result from what it knows.
3. Only now do the ports close: `requests.stop(0)` and `admin.stop(0)`, then `adminThread.shutdownNow()`. Then `workers.shutdown()` stops accepting jobs and lets running ones end, and `awaitTermination(5, SECONDS)` waits for the threads. If they do not end in 5 seconds, `shutdownNow()` interrupts them.
4. The result is returned, so `SeatWaveCli` can print it.

"Safe to call more than once", says the Javadoc: the lifecycle calls tolerate repetition, and the tests call it in `@AfterEach` even after a test already called it.

```java
  /**
   * Returns the request listener's port.
   *
   * @return the port, useful when the settings asked for any free port
   */
  public int port() {
    return requests.getAddress().getPort();
  }

  /**
   * Returns the operations listener's port.
   *
   * @return the port
   */
  public int adminPort() {
    return admin.getAddress().getPort();
  }

  /**
   * Returns the lifecycle state.
   *
   * @return the state now
   */
  public LifecycleState state() {
    return lifecycle.state();
  }

  /**
   * Returns the number of requests this instance refused because it was not ready.
   *
   * @return the count since start
   */
  public long notAdmittedCount() {
    return metrics.notAdmittedCount();
  }
```

Four small getters. `port()` and `adminPort()` ask the server for the address it really bound, which matters when the settings said port 0. `notAdmittedCount()` is the number of requests refused because the instance was not ready; `SeatWaveCli` prints it when the instance stops.

```java
  private void handle(HttpExchange exchange) throws IOException {
    long startNanos = System.nanoTime();
    String correlationId = UUID.randomUUID().toString().replace("-", "");
    try (exchange) {
      // The invariant's gate: asked before a byte of the request is read.
      Optional<Lifecycle.Admission> admission = lifecycle.tryAdmit();
      if (admission.isEmpty()) {
        metrics.notAdmitted();
        drainBody(exchange);
        write(exchange, routes.notAdmitted(lifecycle.state(), correlationId));
        metrics.finished("not admitted", UNAVAILABLE, System.nanoTime() - startNanos);
        return;
      }
```

`handle` is the one request handler, and everything is in its first lines.

- `startNanos` is for the timing metric. `correlationId` is a random UUID with the hyphens removed, 32 characters.
- `try (exchange)` is **try-with-resources** on the exchange: whatever happens, `exchange.close()` runs at the end, which finishes the reply.
- `lifecycle.tryAdmit()` is the gate, asked "before a byte of the request is read", says the comment.
- When the admission is empty: `metrics.notAdmitted()` counts it, `drainBody` reads and throws away the body, `routes.notAdmitted(state, id)` builds the `503` reply, `write` sends it, and `metrics.finished("not admitted", 503, ...)` records the time. Then `return`: the routes are never reached.

```java
      Lifecycle.Admission admitted = admission.get();
      try {
        MDC.put("correlationId", correlationId);
        HoldRoutes.Routed routed = routes.route(request(exchange, correlationId));
        write(exchange, routed.reply());
        metrics.finished(routed.route(), routed.reply().status(), System.nanoTime() - startNanos);
      } finally {
        // Released after the response is written, on every path: the in-flight count is what the
        // drain waits for, so a request still writing its answer must still count.
        admitted.close();
        MDC.clear();
      }
    }
  }
```

When the admission is present:

- `MDC.put("correlationId", correlationId)` sets the log label for this thread.
- `routes.route(request(exchange, correlationId))` copies the request and runs the route. `write` sends the reply. `metrics.finished` counts it under the route's name, the status and the elapsed time.
- `finally` closes the admission and clears the MDC. The comment states the rule: the admission is released "after the response is written, on every path", because the drain waits for this count. `MDC.clear()` matters because worker threads are reused; without it the next request on this thread would log the previous request's id.

```java
  private void handleAdmin(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getRawPath();
      boolean isGet = exchange.getRequestMethod().equals("GET");
      Reply reply;
      if (isGet && path.equals("/health")) {
        // Liveness: the process answers. Never asks the database, because restarting an instance
        // does not fix a database that is down.
        reply = Reply.json(OK, stateJson("up"));
      } else if (isGet && path.equals("/ready")) {
        reply = readiness();
      } else if (isGet && path.equals("/metrics")) {
        reply =
            new Reply(
                OK,
                "text/plain; version=0.0.4; charset=utf-8",
                metrics.render().getBytes(StandardCharsets.UTF_8),
                List.of());
      } else {
        reply = Reply.json(NOT_FOUND, "{\"detail\":\"only GET /health, /ready and /metrics\"}");
      }
      write(exchange, reply.withHeader("Cache-Control", "no-store"));
    }
  }
```

`handleAdmin` serves the second port. It never touches the lifecycle's gate: the checks must answer in every state.

- `getRequestURI().getRawPath()` is the path as sent; `getRequestMethod().equals("GET")` checks the method.
- `GET /health`: `200` with `{"status":"up",...}`. The comment says why it never asks the database: restarting an instance does not repair a database that is down, so the liveness answer must not depend on it.
- `GET /ready`: calls `readiness()`.
- `GET /metrics`: the Prometheus text from `ServiceMetrics.render()`, with the media type `text/plain; version=0.0.4; charset=utf-8` that Prometheus expects.
- Anything else, including `HEAD` and `POST`: `404` with a small JSON detail. A `HEAD /health` therefore answers `404`, which a reader should know when probing by hand.
- Every admin reply gets `Cache-Control: no-store`, so no cache keeps a stale health answer.

```java
  private Reply readiness() {
    if (lifecycle.state() != LifecycleState.READY) {
      return Reply.json(UNAVAILABLE, stateJson("not-ready"));
    }
    // Readiness asks about the database because no request can be served without it; Section 9
    // of the module weighs what that costs when the database itself fails.
    return store.isReachable()
        ? Reply.json(OK, stateJson("ready"))
        : Reply.json(UNAVAILABLE, stateJson("database-unreachable"));
  }

  private String stateJson(String status) {
    return String.format(
        Locale.ROOT,
        "{\"status\":\"%s\",\"state\":\"%s\",\"instance\":\"%s\"}",
        status,
        lifecycle.state().name().toLowerCase(Locale.ROOT),
        instanceName);
  }
```

- `readiness()`: if the state is not `READY`, `503` with `not-ready`. Otherwise it asks the store: `store.isReachable()` true gives `200` `ready`, false gives `503` `database-unreachable`. The comment states the trade: "Readiness asks about the database because no request can be served without it", and the module weighs what that costs when the database itself fails (all three instances go not-ready together).
- `stateJson` builds the small JSON by hand with `String.format`. `Locale.ROOT` fixes the formatting rules so a computer set to another language still writes the same text. `%s` marks a place for a text. `lifecycle.state().name().toLowerCase(Locale.ROOT)` turns `READY` into `ready`.

```java
  private static Request request(HttpExchange exchange, String correlationId) throws IOException {
    byte[] body;
    try (InputStream in = exchange.getRequestBody()) {
      // One byte more than the limit is read, so a body over the limit is noticed without reading
      // all of it.
      body = in.readNBytes(HoldRoutes.MAX_BODY_BYTES + 1);
    }
    boolean isTooLarge = body.length > HoldRoutes.MAX_BODY_BYTES;
    URI uri = exchange.getRequestURI();
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
    return new Request(
        exchange.getRequestMethod(),
        uri.getRawPath(),
        uri.getRawQuery() == null ? "" : uri.getRawQuery(),
        contentType == null ? "" : contentType,
        idempotencyKey == null ? "" : idempotencyKey,
        isTooLarge ? new byte[0] : body,
        isTooLarge,
        correlationId);
  }
```

`request` copies the JDK's exchange into a `Request`.

- `in.readNBytes(HoldRoutes.MAX_BODY_BYTES + 1)` reads at most 1,025 bytes. Reading one byte more than the limit is how the code notices a body over the limit "without reading all of it". Try-with-resources closes the stream.
- `isTooLarge` is true if more than 1,024 bytes came. Then the body is replaced by an empty array.
- `getFirst("Content-Type")` returns the first value of a header, or null. The code turns null into an empty string, so `Request` never holds a null.
- `getRawQuery()` is null when there is no `?`, turned into an empty string too.

```java
  private static void drainBody(HttpExchange exchange) throws IOException {
    // The request body is read and thrown away, so the 503 is not written while the client is
    // still sending, which some clients report as a reset instead of a response.
    try (InputStream in = exchange.getRequestBody()) {
      in.skip(HoldRoutes.MAX_BODY_BYTES + 1L);
    }
  }
```

`drainBody` reads up to 1,025 bytes of the body and drops them (`skip`). The comment gives the reason: if the server writes the `503` while the client is still sending, some clients report a connection reset and never read the `503`. One caution for a careful reader: `InputStream.skip` may skip fewer bytes than asked, and the code does not check how many it skipped, so a large body can still be partly unread. The JDK server drains a limited amount more when it closes the exchange, and closes the connection otherwise.

```java
  private void write(HttpExchange exchange, Reply reply) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", reply.contentType());
    reply
        .headers()
        .forEach(header -> exchange.getResponseHeaders().add(header.getKey(), header.getValue()));
    // Which instance answered: the lab counts traffic share from it. Section 10 of the module says
    // why a public balancer would remove it before the response leaves.
    exchange.getResponseHeaders().set("X-Served-By", instanceName);
    byte[] body = reply.body();
    exchange.sendResponseHeaders(reply.status(), body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
```

`write` sends a reply.

- `getResponseHeaders().set("Content-Type", ...)` sets the type. Each reply header is added with `add`.
- `X-Served-By` is set to the instance name on every reply. The lab counts traffic share from it. The comment points to Section 10 of the module, which says a public balancer would remove it.
- `sendResponseHeaders(status, body.length)` writes the status line and headers. The second argument is the exact body length; it must be right. A length of zero would mean "chunked", and -1 "no body", neither of which occurs here because every reply has a body.
- The body is written in a try-with-resources block, so the stream closes and the reply is complete.

A detail visible on the wire: the JDK server rewrites header names so only the first letter is a capital. `X-Served-By` appears as `X-served-by` in the output of `curl`. HTTP says header names ignore case, so clients must not care.

### `seatwave/HoldRoutes.java`

```java
package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

The imports. `MessageDigest` computes hashes. `HexFormat` writes bytes as hexadecimal text. `Matcher` and `Pattern` are the regular-expression tools. `Result` is the success-or-error box.

```java
/**
 * SeatWave's routes behind the balancer: Module 7's {@code POST /v1/holds} with an {@code
 * Idempotency-Key} and {@code GET /v1/holds/{holdId}}, in Module 7's shapes. Every hold lives in
 * the shared store, so a hold made through one instance is read through any other.
 *
 * <p>Thread-safe: the store and the codec are, and nothing else changes after construction.
 */
final class HoldRoutes {

  /** Module 7's contract limit on a request body. */
  static final int MAX_BODY_BYTES = 1_024;
```

The comment says the routes are "in Module 7's shapes", the contract of an earlier module. `MAX_BODY_BYTES` is 1,024. It is package-visible because `SeatWaveServer` needs the same number.

```java
  private static final Logger LOG = LoggerFactory.getLogger(HoldRoutes.class);
  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int METHOD_NOT_ALLOWED = 405;
  private static final int CONFLICT = 409;
  private static final int TOO_LARGE = 413;
  private static final int UNSUPPORTED_MEDIA_TYPE = 415;
  private static final int UNPROCESSABLE = 422;
  private static final int UNAVAILABLE = 503;
  // Seconds a client waits before retrying a 503, as Module 7's problem bodies say.
  private static final String RETRY_AFTER_SECONDS = "1";
  private static final String HOLDS = "/v1/holds";
  private static final Pattern HOLD_PATH =
      Pattern.compile("/v1/holds/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
```

- Status numbers have names: `OK` 200, `CREATED` 201, `BAD_REQUEST` 400, `NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `CONFLICT` 409, `TOO_LARGE` 413, `UNSUPPORTED_MEDIA_TYPE` 415, `UNPROCESSABLE` 422, `UNAVAILABLE` 503.
- `RETRY_AFTER_SECONDS` is the text "1" for the `Retry-After` header.
- `HOLDS` is the path `/v1/holds`. `HOLD_PATH` is a pattern for `/v1/holds/` followed by a UUID in lowercase hexadecimal groups of 8-4-4-4-12. The brackets `( ... )` make a **capture group**: the matcher can hand back the part inside, the hold id.

```java
  private final HoldStore store;
  private final JsonCodec json;
  private final Problems problems;
  private final Clock clock;

  HoldRoutes(HoldStore store, JsonCodec json, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.problems = new Problems(json);
  }
```

The constructor stores the store, the codec and the clock, and builds a `Problems`. The class is thread-safe, says its comment, because the store and the codec are and nothing else changes.

```java
  /**
   * Answers one admitted request.
   *
   * @param request the request
   * @return the route's template, for the metrics, and the reply
   */
  Routed route(Request request) {
    String path = request.path();
    if (path.equals(HOLDS)) {
      return request.method().equals("POST")
          ? new Routed("POST /v1/holds", hold(request))
          : new Routed("other", notAllowed(request, "POST"));
    }
    Matcher holdPath = HOLD_PATH.matcher(path);
    if (holdPath.matches()) {
      return request.method().equals("GET")
          ? new Routed("GET /v1/holds/{holdId}", readHold(request, holdPath.group(1)))
          : new Routed("other", notAllowed(request, "GET"));
    }
    return new Routed("other", notFound(request));
  }
```

`route` is the traffic officer.

- The path `/v1/holds` with method `POST` goes to `hold`; with any other method it is `405` with `Allow: POST`.
- A path that matches `HOLD_PATH` with method `GET` goes to `readHold`, passing the captured id (`holdPath.group(1)`); with any other method it is `405` with `Allow: GET`.
- Everything else is `404`.
- The first value of each `Routed` is the **route template**: `POST /v1/holds`, `GET /v1/holds/{holdId}` or `other`. The metrics use the template, never the raw path, so there is a handful of labels and not one per hold id.

```java
  /**
   * The answer of an instance that is not admitting requests: {@code 503} with {@code Connection:
   * close}, so the balancer moves to another instance, and a body that says nothing was done.
   *
   * @param state the instance's state
   * @param correlationId the request's id
   * @return the reply
   */
  Reply notAdmitted(LifecycleState state, String correlationId) {
    return problems
        .of(
            UNAVAILABLE,
            "instance-not-ready",
            "This SeatWave instance is not taking requests",
            "the instance is " + state.name().toLowerCase(Locale.ROOT) + "; nothing was done",
            correlationId)
        .withHeader("Retry-After", RETRY_AFTER_SECONDS)
        .withHeader("Connection", "close");
  }
```

`notAdmitted` builds the answer of an instance that is not taking requests.

- Status `503`, slug `instance-not-ready`, and a detail that names the state: "the instance is draining; nothing was done". The state name is lowercased for reading.
- `Retry-After: 1`, and `Connection: close` so the client does not reuse this connection and the balancer moves to another instance.
- "Nothing was done" is the promise the balancer's retry relies on: retrying a request is safe when no work happened. `SeatWaveServerTest.finishesTheRequestInFlightAndRefusesNewOnesWhileDraining` checks the text.

```java
  private Reply hold(Request request) {
    if (request.isBodyTooLarge()) {
      return problems.of(
          TOO_LARGE,
          "body-too-large",
          "The body is over 1,024 bytes",
          "send at most " + MAX_BODY_BYTES + " bytes",
          request.correlationId());
    }
    if (!isJson(request.contentType())) {
      return problems.of(
          UNSUPPORTED_MEDIA_TYPE,
          "unsupported-media-type",
          "The body must be application/json",
          "send Content-Type: application/json",
          request.correlationId());
    }
    if (!HoldCommand.IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
      return invalid(
          request, List.of("Idempotency-Key must be one quoted string of 1 to 64 characters"));
    }
    String body = new String(request.body(), StandardCharsets.UTF_8);
    return switch (json.read(body, HoldRequestBody.class)) {
      case Result.Success<HoldRequestBody, JsonCodec.BindingProblem>(HoldRequestBody parsed) ->
          checked(request, parsed);
      case Result.Error<HoldRequestBody, JsonCodec.BindingProblem>(
              JsonCodec.BindingProblem problem) ->
          invalid(request, List.of(problem.pointer() + " " + problem.detail()));
    };
  }
```

`hold` handles `POST /v1/holds`. It checks in a fixed order, and the first failure answers.

1. **Too large**: `413`, if the server saw more than 1,024 bytes.
2. **Media type**: `415` unless `isJson(contentType)`.
3. **Idempotency-Key**: `400` unless the header matches `HoldCommand.IDEMPOTENCY_KEY`. The same pattern the command uses.
4. **The body**: bytes to text, then `json.read(body, HoldRequestBody.class)` in a `switch` over the `Result`.
   - `case Result.Success<...>(HoldRequestBody parsed) -> checked(request, parsed)`: a **record pattern**. It both tests that the result is a success and takes its value out into the variable `parsed`.
   - `case Result.Error<...>(JsonCodec.BindingProblem problem)`: `400` with the sentence `pointer + " " + detail`, for example `/x is not a field of this request`.

`switch` here is an expression (it returns a value), and because `Result` is sealed, it needs no `default`.

A flaw a careful reader sees: for a problem about the whole body, the pointer is an empty string, so the detail starts with a space. I sent an empty body to a running instance and the `detail` was `" the body is empty; send a JSON object"` with a leading space. It is harmless to a program and untidy for a person.

```java
  private Reply checked(Request request, HoldRequestBody body) {
    List<String> errors = new ArrayList<>();
    if (body.eventId() == null || body.eventId() < 1) {
      errors.add("/eventId must be 1 or more");
    }
    if (body.seat() == null || !HoldCommand.SEAT.matcher(body.seat()).matches()) {
      errors.add("/seat must be a section letter A to T, a hyphen and a number 1 to 1000");
    }
    if (body.customer() == null || !HoldCommand.CUSTOMER.matcher(body.customer()).matches()) {
      errors.add("/customer must be 1 to 64 letters, digits, - or _");
    }
    if (!errors.isEmpty()) {
      return invalid(request, errors);
    }
    HoldCommand command =
        new HoldCommand(
            request.idempotencyKey(),
            fingerprint(body.eventId() + "|" + body.seat() + "|" + body.customer()),
            body.eventId(),
            body.seat(),
            body.customer());
    try {
      return answer(request, store.hold(command));
    } catch (StoreUnavailableException failure) {
      return storeDown(request, failure);
    }
  }
```

`checked` validates the fields that the codec could not.

- `HoldRequestBody` has boxed fields (`Long`, `String`), so a missing field arrives as `null` and reaches this method instead of failing inside the codec.
- Each failed rule adds a sentence with a JSON pointer: `/eventId must be 1 or more`, `/seat must be a section letter A to T, a hyphen and a number 1 to 1000`, `/customer must be 1 to 64 letters, digits, - or _`. All problems are collected, so the client fixes everything in one go. `HoldRoutesTest.namesEveryInvalidFieldTogether` proves that.
- When all is well, a `HoldCommand` is built. The fingerprint is the SHA-256 of `eventId|seat|customer`, for example the text `1|A-7|fan-1`.
- `store.hold(command)` is the only call that touches the database. `StoreUnavailableException` is caught here and turned into `storeDown`.

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

`answer` turns the store's `Result` into a reply.

- On success, it logs at debug level a business line, with the hold's seat and expiry and a marker if the hold is a replay. Then `201 Created` with the hold as JSON, a `Location` header pointing to the new hold's address, and `Cache-Control: no-store`.
- A replay also answers `201` with the same hold, so a retry looks exactly like the first answer. That is what an idempotent `POST` means.
- On error, `refused` decides.

```java
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

`refused` has one case for each of the three refusals: `409` `seat-unavailable`, `404` for a missing seat (it reuses `notFound`), `422` `idempotency-key-reused`. The variable names `taken`, `missing`, `reused` are required by the syntax of a type pattern but are not used.

```java
  private Reply readHold(Request request, String holdId) {
    if (!request.rawQuery().isEmpty()) {
      return invalid(request, List.of("this path takes no query parameters"));
    }
    try {
      Optional<Hold> hold = store.find(holdId);
      return hold.map(found ->
              Reply.json(OK, json.write(HoldView.of(found, clock)))
                  // private: one fan's hold, never kept by a shared cache (RFC 9111).
                  .withHeader("Cache-Control", "private, max-age=60"))
          .orElseGet(() -> notFound(request));
    } catch (StoreUnavailableException failure) {
      return storeDown(request, failure);
    }
  }
```

`readHold` handles `GET /v1/holds/{id}`.

- A query string (`?x=1`) is refused with `400`, because this path takes none.
- `store.find(holdId)` returns an `Optional<Hold>`. `hold.map(found -> Reply.json(...))` builds a `200` if present, with `Cache-Control: private, max-age=60`; `orElseGet(() -> notFound(request))` builds a `404` if absent. The comment explains `private`: this is one fan's hold, and a shared cache must never keep it (RFC 9111).
- `StoreUnavailableException` becomes `503` again.

One consequence to notice: a client may keep a `held` answer for 60 seconds, so for up to a minute after the expiry it may still see `held`. `max-age=60` is shorter than a hold's 300 seconds, but not shorter than the gap.

```java
  private Reply storeDown(Request request, StoreUnavailableException failure) {
    // Fail safe: without the database nobody can say who owns a seat, so refuse with 503.
    LOG.warn("the shared database is unavailable: {}", failure.getMessage());
    return problems
        .of(
            UNAVAILABLE,
            "service-busy",
            "SeatWave cannot reach its database; retry after Retry-After",
            "the shared database did not answer",
            request.correlationId())
        .withHeader("Retry-After", RETRY_AFTER_SECONDS);
  }
```

`storeDown` is "fail safe": without the database nobody can say who owns a seat, so the answer is `503`, never a guess. It logs a warning with the exception's message (not the whole stack trace), then returns `503` with `Retry-After: 1`.

```java
  private Reply invalid(Request request, List<String> errors) {
    return problems.of(
        BAD_REQUEST,
        "invalid-request",
        "The request does not match the contract",
        String.join("; ", errors),
        request.correlationId());
  }

  private Reply notFound(Request request) {
    return problems.of(
        NOT_FOUND, "not-found", "No such resource", "no such resource", request.correlationId());
  }

  private Reply notAllowed(Request request, String allowed) {
    return problems
        .of(
            METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "This path does not take this method",
            "use " + allowed,
            request.correlationId())
        .withHeader("Allow", allowed);
  }
```

Three helpers, one per error type.

- `invalid` is `400` with all the sentences joined by `"; "`.
- `notFound` is `404`.
- `notAllowed` is `405` with an `Allow` header naming the one method the path takes.

```java
  private static boolean isJson(String contentType) {
    // The media type is the part before any parameter (RFC 9110, Section 8.3.1), compared without
    // regard to case: "application/json; charset=utf-8" is JSON, "application/jsonx" is not.
    int semicolon = contentType.indexOf(';');
    String mediaType = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
    return mediaType.strip().equalsIgnoreCase("application/json");
  }
```

`isJson` compares the media type, which is the part of `Content-Type` before any `;`. The comment cites RFC 9110, Section 8.3.1. `application/json; charset=utf-8` is JSON. `application/jsonx` is not, which `HoldRoutesTest.refusesAMediaTypeThatOnlyStartsLikeJson` checks (an earlier project used `startsWith` and accepted it). `strip()` removes spaces and `equalsIgnoreCase` ignores case.

```java
  private static String fingerprint(String meaning) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(meaning.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must provide SHA-256, so this is a broken runtime, not bad input.
      throw new IllegalStateException("the JDK has no SHA-256", missing);
    }
  }
```

`fingerprint` computes the SHA-256 of a text and writes it as 64 lowercase hexadecimal characters. `MessageDigest.getInstance("SHA-256")` can in theory throw `NoSuchAlgorithmException`, but every Java platform must have SHA-256, so the code turns it into an `IllegalStateException` ("a broken runtime, not bad input").

```java
  /** The reply of one route, with the route template the metrics count it under. */
  record Routed(String route, Reply reply) {}

  /** The body of POST /v1/holds as sent; boxed, so a missing field reaches the validation step. */
  record HoldRequestBody(Long eventId, String seat, String customer) {}

  /** Module 7's hold as JSON. */
  record HoldView(String holdId, long eventId, String seat, String state, String expiresAt) {
    static HoldView of(Hold hold, Clock clock) {
      return new HoldView(
          hold.holdId(),
          hold.eventId(),
          hold.seat(),
          hold.isActiveAt(clock.instant()) ? "held" : "expired",
          hold.expiresAt().toString());
    }
  }
}
```

Three nested records.

- `Routed(route, reply)` carries the route template with the reply.
- `HoldRequestBody(Long eventId, String seat, String customer)` is what the codec fills from the JSON. The Javadoc says "boxed, so a missing field reaches the validation step".
- `HoldView(holdId, eventId, seat, state, expiresAt)` is the JSON shape of a hold: `state` is `held` while `isActiveAt(now)` and `expired` afterwards, and `expiresAt` is the instant as ISO-8601 text (`Instant.toString()` gives `2026-11-11T06:55:57.681Z`).

## Try it yourself

All commands run in the project folder. Timings and ids differ on every run. These ran on one machine with no Docker, and the database address in step 1 points at a port where nothing listens, on purpose. An instance runs for real, against PostgreSQL, only in Compose; these notes did not run the Compose stack.

1. Start one instance on its own loopback ports. It has no database, and it starts anyway. The password file holds any text.

```bash
./gradlew --offline -q stackFiles
mkdir -p build/try && echo lab-only > build/try/db.password
sed -e 's#^http.bindAddress=.*#http.bindAddress=127.0.0.1#' -e 's#^http.port=.*#http.port=22080#' -e 's#^http.adminPort=.*#http.adminPort=22081#' -e 's#^db.url=.*#db.url=jdbc:postgresql://127.0.0.1:5999/seatwave#' -e 's#^db.passwordFile=.*#db.passwordFile=build/try/db.password#' -e 's#^drain.deadlineMillis=.*#drain.deadlineMillis=3000#' config/seatwave-container.properties > build/try/local.properties
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli build/try/local.properties seatwave-local > build/try/out.txt 2>&1 &
PID=$!
sleep 3
```

2. Ask the two questions. `/health` says the process is alive. `/ready` says the database is unreachable.

```bash
curl -s -i http://127.0.0.1:22081/health | grep -v -i '^date'
curl -s -i http://127.0.0.1:22081/ready | grep -v -i '^date'
```

```text
HTTP/1.1 200 OK
X-served-by: seatwave-local
Content-type: application/json
Content-length: 59
Cache-control: no-store

{"status":"up","state":"ready","instance":"seatwave-local"}
HTTP/1.1 503 Service Unavailable
X-served-by: seatwave-local
Content-type: application/json
Content-length: 77
Cache-control: no-store

{"status":"database-unreachable","state":"ready","instance":"seatwave-local"}
```

Notice the header names: the JDK server writes `X-served-by`, with only the first letter in capitals.

3. Send a request with two bad fields. Both are named in one answer. The `instance` value holds this request's id and differs on every run.

```bash
curl -s -i -H 'Content-Type: application/json' -H 'Idempotency-Key: "k1"' --data '{"eventId":1,"seat":"Z-9","customer":"a b"}' http://127.0.0.1:22080/v1/holds | grep -v -i '^date'
```

```text
HTTP/1.1 400 Bad Request
X-served-by: seatwave-local
Content-type: application/problem+json
Content-length: 323
Cache-control: no-store

{"type":"https://seatwave.example/problems/invalid-request","title":"The request does not match the contract","status":400,"detail":"/seat must be a section letter A to T, a hyphen and a number 1 to 1000; /customer must be 1 to 64 letters, digits, - or _","instance":"urn:seatwave:request:e5f47bad3cc94845b67fe96a7430a7f2"}
```

4. Send a correct request. The route reaches the store, the store cannot get a connection, and the answer is `503` with `Retry-After`. Then ask for the wrong method on the same path, and read the counters.

```bash
curl -s -i -H 'Content-Type: application/json' -H 'Idempotency-Key: "k1"' --data '{"eventId":1,"seat":"A-7","customer":"fan-1"}' http://127.0.0.1:22080/v1/holds | grep -v -i '^date'
curl -s -i http://127.0.0.1:22080/v1/holds | grep -v -i '^date'
curl -s http://127.0.0.1:22081/metrics | grep -E 'requests_total|in_flight'
```

```text
HTTP/1.1 503 Service Unavailable
X-served-by: seatwave-local
Content-type: application/problem+json
Retry-after: 1
Content-length: 253
Cache-control: no-store

{"type":"https://seatwave.example/problems/service-busy","title":"SeatWave cannot reach its database; retry after Retry-After","status":503,"detail":"the shared database did not answer","instance":"urn:seatwave:request:52c68a0cc64c4da68e12888d653102d9"}
HTTP/1.1 405 Method Not Allowed
X-served-by: seatwave-local
Allow: POST
Content-type: application/problem+json
Content-length: 209
Cache-control: no-store

{"type":"https://seatwave.example/problems/method-not-allowed","title":"This path does not take this method","status":405,"detail":"use POST","instance":"urn:seatwave:request:40a4ee1a92e2440d9117a2bbc6fccaa0"}
# TYPE seatwave_requests_total counter
seatwave_requests_total{route="POST /v1/holds",status="400"} 1
seatwave_requests_total{route="POST /v1/holds",status="503"} 1
seatwave_requests_total{route="other",status="405"} 1
# TYPE seatwave_in_flight gauge
seatwave_in_flight 0
```

5. Stop the instance with the same signal Docker sends, and read what it printed. The exit status 143 is 128 plus 15, the number of SIGTERM.

```bash
kill -TERM $PID; wait $PID; echo "exit $?"
grep -v DEBUG build/try/out.txt | cut -c1-200
```

```text
exit 143
```

The log shows the lines `seatwave-local is READY`, a `WARN` line for the request that failed (it carries the request's id in square brackets), `seatwave-local is draining with 0 in flight`, and last `seatwave-local drained cleanly: 0 in flight left after 0 ms; 0 requests refused while not ready; stopped`.

6. Run the tests of this part. `HoldRoutesTest` has sixteen tests and `SeatWaveServerTest` six.

```bash
./gradlew --offline cleanTest test --tests '*HoldRoutesTest' --tests '*SeatWaveServerTest' 2>&1 | grep -E "FAILED|BUILD"
```

The only line printed is `BUILD SUCCESSFUL`, because the grep hides the `PASSED` lines.

## Mistakes people make

- **Reading the request before asking permission.** If the handler parsed the body first, a draining instance would do work for a request it should have refused. Here `tryAdmit` comes first.
- **Releasing the in-flight count before the response is written.** The drain could then end while a reply is still being sent. `handle` releases in `finally`, after `write`.
- **Closing the ports before waiting.** `drainAndStop` waits first, then closes.
- **Forgetting `MDC.clear()` on a reused thread.** The next request would log the old id.
- **An unbounded queue.** A queue that grows without limit turns overload into slow death by memory. This queue is bounded, and a full queue refuses and counts.
- **Trusting `startsWith("application/json")`.** It accepts `application/jsonx`. `isJson` compares the whole media type.
- **Answering readiness from the process alone.** An instance that cannot reach the database would receive traffic it must fail. `/ready` asks the store.
- **Letting the liveness check depend on the database.** `/health` never asks it, so a database outage does not make a restart loop.
- **Believing a `HEAD` request works on the checks port.** `HEAD /health` gets `404`, and the JDK server logs a warning "being invoked with a content length for a HEAD request". The balancer uses `GET`.

## Check yourself

1. What is the first thing `handle` does with a request, and why before anything else?
   Answer: it asks `lifecycle.tryAdmit()`. Admission is the single gate for the drain rule; it must come before any work or the instance could start work for a request it is about to abandon.
2. Why does `Reply` copy its byte array on the way in and on the way out?
   Answer: so no caller can change a stored reply by keeping a reference to the array. A record's array component is otherwise shared.
3. What does a client see when the worker queue is full, and what does the instance record?
   Answer: the connection is closed with no reply; the instance counts it in `seatwave_rejected_queue_full_total`.
4. Which statuses do `SeatTaken`, `NoSuchSeat` and `KeyReused` become?
   Answer: 409, 404 and 422.
5. Why does `/ready` ask the store while `/health` does not?
   Answer: readiness decides whether to send traffic, and traffic needs the database; liveness decides whether to restart the process, and a restart does not fix a database.

## Further reading

- JDK 25 API, package `com.sun.net.httpserver` (`HttpServer`, `HttpExchange`, `Headers`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- JDK 25 API, `ThreadPoolExecutor`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- RFC 9110, HTTP Semantics (methods, status codes, media types): https://www.rfc-editor.org/rfc/rfc9110
- RFC 9111, HTTP Caching (`private`, `no-store`, `max-age`): https://www.rfc-editor.org/rfc/rfc9111
- RFC 9457, Problem Details for HTTP APIs: https://www.rfc-editor.org/rfc/rfc9457
- RFC 6901, JSON Pointer: https://www.rfc-editor.org/rfc/rfc6901
- SLF4J manual, the MDC (version 2.0): https://www.slf4j.org/manual.html
- Prometheus, exposition formats: https://prometheus.io/docs/instrumenting/exposition_formats/
