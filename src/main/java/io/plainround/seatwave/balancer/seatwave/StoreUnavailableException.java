package io.plainround.seatwave.balancer.seatwave;

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
