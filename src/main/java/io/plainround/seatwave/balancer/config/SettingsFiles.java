package io.plainround.seatwave.balancer.config;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Loads one settings file into a checked settings record for a command-line entry point, and prints
 * every problem when it cannot. Thread-safe: it holds no state.
 */
public final class SettingsFiles {

  private SettingsFiles() {}

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
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(reading, "reading");
    Objects.requireNonNull(err, "err");
    try {
      return switch (reading.apply(new SettingsReader(SettingsReader.load(file)))) {
        case Result.Success<T, List<String>>(T checked) -> Optional.of(checked);
        case Result.Error<T, List<String>>(List<String> problems) -> {
          err.println("the settings in " + file + " have " + problems.size() + " problem(s):");
          problems.forEach(problem -> err.println("  " + problem));
          yield Optional.empty();
        }
      };
    } catch (IOException unreadable) {
      // SettingsReader.load also turns a broken escape into this, so only the file's own
      // problems land here; an exception from the reading function is a bug and is not caught.
      err.println(unreadable.getMessage());
      return Optional.empty();
    }
  }
}
