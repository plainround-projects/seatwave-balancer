package io.plainround.seatwave.balancer.seatwave;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * One instance's counters, written in the Prometheus text format at {@code GET /metrics}: requests
 * by route and status, a duration histogram, requests refused because the instance was not ready,
 * requests refused by a full queue, and the lifecycle's state and in-flight count.
 *
 * <p>Every line ends in {@code \n}, never the platform's line separator: the text format is the
 * same on every machine.
 *
 * <p>The labels are the route template and the status, never a raw path or a client address, so the
 * number of series stays small (the guidelines' rule 68). Thread-safe: every field is a concurrent
 * counter.
 */
final class ServiceMetrics {

  // Upper bounds of the duration histogram, in seconds: Module 2's targets fall between them.
  private static final List<Double> BUCKET_SECONDS =
      List.of(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5);
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  private final ConcurrentMap<String, LongAdder> requests = new ConcurrentHashMap<>();
  private final LongAdder[] bucketCounts = new LongAdder[BUCKET_SECONDS.size()];
  private final LongAdder durationCount = new LongAdder();
  private final LongAdder durationNanos = new LongAdder();
  private final LongAdder notAdmitted = new LongAdder();
  private final LongAdder rejectedByQueue = new LongAdder();
  private final Lifecycle lifecycle;

  ServiceMetrics(Lifecycle lifecycle) {
    this.lifecycle = lifecycle;
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      bucketCounts[bucketIndex] = new LongAdder();
    }
  }

  void finished(String route, int status, long elapsedNanos) {
    requests.computeIfAbsent(route + "|" + status, key -> new LongAdder()).increment();
    durationCount.increment();
    durationNanos.add(elapsedNanos);
    double seconds = elapsedNanos / NANOS_PER_SECOND;
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      if (seconds <= BUCKET_SECONDS.get(bucketIndex)) {
        bucketCounts[bucketIndex].increment();
      }
    }
  }

  void notAdmitted() {
    notAdmitted.increment();
  }

  void rejectedByQueue() {
    rejectedByQueue.increment();
  }

  long notAdmittedCount() {
    return notAdmitted.sum();
  }

  String render() {
    StringBuilder text = new StringBuilder();
    text.append("# TYPE seatwave_requests_total counter\n");
    Map<String, Long> sorted = new TreeMap<>();
    requests.forEach((key, count) -> sorted.put(key, count.sum()));
    sorted.forEach((key, count) -> {
      int bar = key.lastIndexOf('|');
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_requests_total{route=\"%s\",status=\"%s\"} %d\n",
              key.substring(0, bar),
              key.substring(bar + 1),
              count));
    });
    text.append("# TYPE seatwave_request_seconds histogram\n");
    for (int bucketIndex = 0; bucketIndex < bucketCounts.length; bucketIndex++) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_request_seconds_bucket{le=\"%s\"} %d\n",
              BUCKET_SECONDS.get(bucketIndex),
              bucketCounts[bucketIndex].sum()));
    }
    long count = durationCount.sum();
    text.append(
        String.format(Locale.ROOT, "seatwave_request_seconds_bucket{le=\"+Inf\"} %d\n", count));
    text.append(
        String.format(
            Locale.ROOT,
            "seatwave_request_seconds_sum %.6f\n",
            durationNanos.sum() / NANOS_PER_SECOND));
    text.append(String.format(Locale.ROOT, "seatwave_request_seconds_count %d\n", count));
    line(text, "counter", "seatwave_not_admitted_total", notAdmitted.sum());
    line(text, "counter", "seatwave_rejected_queue_full_total", rejectedByQueue.sum());
    line(text, "gauge", "seatwave_in_flight", lifecycle.inFlight());
    text.append("# TYPE seatwave_state gauge\n");
    LifecycleState current = lifecycle.state();
    for (LifecycleState state : LifecycleState.values()) {
      text.append(
          String.format(
              Locale.ROOT,
              "seatwave_state{state=\"%s\"} %d\n",
              state.name().toLowerCase(Locale.ROOT),
              state == current ? 1 : 0));
    }
    return text.toString();
  }

  private static void line(StringBuilder text, String type, String name, long value) {
    text.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    text.append(name).append(' ').append(value).append('\n');
  }
}
