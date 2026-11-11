package io.plainround.seatwave.balancer.harness;

import java.util.Objects;

/**
 * What happened to one request of a load run, with times in nanoseconds since the run began.
 *
 * @param sentNanos when the request was started
 * @param doneNanos when its answer or its failure arrived; equal to {@code sentNanos} when it was
 *     never sent
 * @param kind whether it was answered, failed or never sent
 * @param status the HTTP status, or 0 when there was none
 * @param servedBy the {@code X-Served-By} header: which SeatWave instance answered, or empty
 * @param balancer the {@code X-Balancer} header: which balancer passed it on, or empty
 * @param failure why it failed: {@code HTTP 503} or the client's exception, or empty
 */
public record Outcome(
    long sentNanos,
    long doneNanos,
    Kind kind,
    int status,
    String servedBy,
    String balancer,
    String failure) {

  // Every status from 500 up is a failure the fan sees; 4xx answers (409 seat taken) are answers.
  private static final int FIRST_SERVER_ERROR = 500;

  /** Checks the fields. */
  public Outcome {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(servedBy, "servedBy");
    Objects.requireNonNull(balancer, "balancer");
    Objects.requireNonNull(failure, "failure");
    if (doneNanos < sentNanos) {
      throw new IllegalArgumentException("a request cannot end before it starts");
    }
  }

  /**
   * Classifies a response.
   *
   * @param sentNanos when the request started
   * @param doneNanos when the response arrived
   * @param status its status
   * @param servedBy its {@code X-Served-By}
   * @param balancer its {@code X-Balancer}
   * @return answered below 500, failed from 500 up
   */
  static Outcome response(
      long sentNanos, long doneNanos, int status, String servedBy, String balancer) {
    boolean isFailure = status >= FIRST_SERVER_ERROR;
    return new Outcome(
        sentNanos,
        doneNanos,
        isFailure ? Kind.FAILED : Kind.ANSWERED,
        status,
        servedBy,
        balancer,
        isFailure ? "HTTP " + status : "");
  }

  /**
   * Records a request that got no response.
   *
   * @param sentNanos when it started
   * @param doneNanos when the client gave up
   * @param failure why
   * @return a failed outcome
   */
  static Outcome failure(long sentNanos, long doneNanos, String failure) {
    return new Outcome(sentNanos, doneNanos, Kind.FAILED, 0, "", "", failure);
  }

  /**
   * Records a request that was due but not sent, because the in-flight limit was reached.
   *
   * @param dueNanos when it was due
   * @return a not-sent outcome
   */
  static Outcome notSent(long dueNanos) {
    return new Outcome(dueNanos, dueNanos, Kind.NOT_SENT, 0, "", "", "");
  }

  /**
   * Returns the request's latency.
   *
   * @return done minus sent, in nanoseconds
   */
  long latencyNanos() {
    return doneNanos - sentNanos;
  }

  /** The three things that can happen to a request. */
  public enum Kind {
    /** A response below 500. */
    ANSWERED,
    /** A response from 500 up, a timeout, or a broken connection. */
    FAILED,
    /** Due, but the in-flight limit was reached. */
    NOT_SENT
  }
}
