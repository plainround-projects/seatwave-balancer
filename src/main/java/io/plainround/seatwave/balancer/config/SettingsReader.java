package io.plainround.seatwave.balancer.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a learner sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  // A path as a file system can hold it: any character but a control character (a line break or a
  // NUL cannot be part of a real file name), up to 4,096 characters, Linux's PATH_MAX. Spaces, "+"
  // and deep folders are allowed, because a reader's checkout may sit in any of them.
  private static final Pattern FILE_PATH = Pattern.compile("[^\\p{Cntrl}]{1,4096}");

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();

  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file does not exist, cannot be read or is not a properties file; the
   *     message says which
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (NoSuchFileException missing) {
      throw new IOException("the settings file " + path + " does not exist", missing);
    } catch (IllegalArgumentException malformed) {
      // Properties.load refuses a broken escape (a backslash and a u with fewer than four hex
      // digits) with this unchecked exception; a typo in a settings file is an expected failure,
      // so it is reported like an unreadable file.
      throw new IOException(
          "the settings file "
              + path
              + " is not a valid properties file: "
              + malformed.getMessage(),
          malformed);
    }
    return properties;
  }

  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }

  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code Millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }

  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @return the trimmed value, or an empty string if the key is missing or does not match
   */
  public String textMatching(String key, Pattern allowed) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return text;
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must match " + allowed.pattern() + ", but was " + text);
      return "";
    }
    return text;
  }

  /**
   * Reads a file path.
   *
   * @param key the key
   * @return the trimmed path, or an empty string if the key is missing or holds a control character
   */
  public String filePath(String key) {
    return textMatching(key, FILE_PATH);
  }

  /**
   * Reads a comma-separated list of text items, each matching a pattern.
   *
   * @param key the key
   * @param allowed the pattern every item must match
   * @return the trimmed items in file order, or an empty list if the key is missing or invalid
   */
  public List<String> listMatching(String key, Pattern allowed) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",", -1)) {
      String trimmed = item.strip();
      if (!allowed.matcher(trimmed).matches()) {
        problems.add(
            key + " has the item '" + trimmed + "', which must match " + allowed.pattern());
        return List.of();
      }
      items.add(trimmed);
    }
    return List.copyOf(items);
  }

  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }

  /**
   * Records a problem that involves more than one key, so it is reported with all the others.
   *
   * @param problem the sentence that names the keys and what is wrong
   */
  public void addProblem(String problem) {
    problems.add(Objects.requireNonNull(problem, "problem"));
  }

  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
}
