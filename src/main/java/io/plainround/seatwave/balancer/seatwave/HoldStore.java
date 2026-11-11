package io.plainround.seatwave.balancer.seatwave;

import com.github.sviperll.result4j.Result;
import java.util.Optional;

/**
 * Where holds live: outside every instance, so any instance can answer for any hold. The JDBC store
 * is the real one; the tests also use a store that can hold a request open on purpose, to test
 * draining. Implementations are thread-safe.
 */
public interface HoldStore {

  /**
   * Holds a seat, or replays the hold this request's Idempotency-Key already made.
   *
   * @param command the checked request
   * @return the granted hold, or why it was refused
   * @throws StoreUnavailableException if the database cannot be used
   */
  Result<GrantedHold, HoldRefusal> hold(HoldCommand command) throws StoreUnavailableException;

  /**
   * Finds a hold by its identifier.
   *
   * @param holdId the hold's UUID
   * @return the hold, or empty if no hold has this identifier
   * @throws StoreUnavailableException if the database cannot be used
   */
  Optional<Hold> find(String holdId) throws StoreUnavailableException;

  /**
   * Says whether the database answers now, for the readiness check.
   *
   * @return true if a connection was obtained and answered within the store's timeout
   */
  boolean isReachable();
}
