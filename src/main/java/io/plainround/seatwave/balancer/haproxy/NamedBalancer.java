package io.plainround.seatwave.balancer.haproxy;

import com.github.sviperll.result4j.Result;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A balancer by its lab name, with the address of its runtime API, as a settings file writes it:
 * {@code lb1=10.210.10.21:9999}. Immutable.
 *
 * @param name the balancer's name, the same as its {@code X-Balancer} header
 * @param api the client for its runtime API
 */
public record NamedBalancer(String name, RuntimeApi api) {

  // A port from 1 up, without a leading zero; the address is checked once it is parsed.
  private static final Pattern ENTRY =
      Pattern.compile("([a-z][a-z0-9-]{0,31})=([0-9.]{7,15}):([1-9][0-9]{0,4})");
  private static final int HIGHEST_PORT = 65_535;

  /** Checks the fields. */
  public NamedBalancer {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(api, "api");
  }

  /**
   * Reads a list of {@code name=address:port} entries.
   *
   * @param entries the items of a settings key
   * @param timeout the runtime API timeout for every balancer
   * @return the balancers in order, or the first entry that is not valid, with the reason
   */
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
      // ofLiteral also reads short and padded forms (127.0.1, 010.0.0.1); only the dotted form
      // that prints back the same is the address the reader meant.
      if (!address.getHostAddress().equals(matcher.group(2))) {
        return Result.error(
            "'" + entry + "' does not hold a dotted IPv4 address with four parts and no padding");
      }
      balancers.add(
          new NamedBalancer(
              matcher.group(1),
              new RuntimeApi(
                  new InetSocketAddress(address, Integer.parseInt(matcher.group(3))), timeout)));
    }
    return Result.success(List.copyOf(balancers));
  }
}
