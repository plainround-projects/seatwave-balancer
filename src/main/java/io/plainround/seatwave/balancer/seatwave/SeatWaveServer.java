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

  private static void drainBody(HttpExchange exchange) throws IOException {
    // The request body is read and thrown away, so the 503 is not written while the client is
    // still sending, which some clients report as a reset instead of a response.
    try (InputStream in = exchange.getRequestBody()) {
      in.skip(HoldRoutes.MAX_BODY_BYTES + 1L);
    }
  }

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
