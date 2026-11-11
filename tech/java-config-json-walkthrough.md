# The `config` and `json` packages, line by line

## What it is

Two small packages that every other part of the project stands on.

- `config` (`SettingsReader.java`, `SettingsFiles.java`): reads a settings file and reports **every** mistake in it in one run. There are no defaults.
- `json` (`JsonCodec.java`): turns JSON text into a Java record, and a Java value into JSON text. It reads strictly: a body either matches the record exactly or is refused with a reason that names the field.

Terms you need.

- A **settings file** is a text file of `key=value` lines. Java calls the format a `.properties` file and reads it with `java.util.Properties` (see `java-properties.md`). The program reads it once, when it starts.
- **JSON** is a plain-text way to write data with named fields: `{"seat":"A-7"}`. To **bind** JSON to a record means to fill the record's fields from it. **Jackson** is the library that does it (see `jackson.md`).
- A **JSON pointer** (RFC 6901) names a place inside a JSON document. `/eventId` is the field `eventId` at the top. The empty pointer is the whole document.
- A **regular expression** (a `Pattern` in Java) describes which texts are allowed. `[a-z]+` means "one or more lowercase letters".
- **`Result<T, E>`** (see `result4j.md`) is a box that holds either a success value or an error value.
- A **checked exception** must be announced with `throws` and handled by the caller. An **unchecked** exception (a `RuntimeException`) need not be.
- **`Optional<T>`** holds one value or nothing.

## Why this repository uses it

- **`config`**: the course rule is that no number lives in code. Every timeout, port and pool size comes from a file, and a missing key is an error, not a silent default. Reporting all problems in one run saves a reader from fixing one error per attempt. The three programs of this project (an instance, the load harness and the deploy tool) all read their settings through these two classes.
- **`json`**: a request body is input from outside. Module 7's contract says unknown fields, wrong types and numbers out of range are errors. A strict codec makes the contract real, and stops "mass assignment", where a client sets a field it should not be able to set. The same codec also reads SeatWave's replies in the load harness.

Both packages were copied from the Module 9 project, `seatwave-proxy`, as of its first commit. That project fixed several things in its copies afterwards. The differences are listed below, in the walkthrough, at the places where they show.

## Where it appears

| File | What it does |
|---|---|
| `config/SettingsReader.java` | Typed reads from a loaded `Properties`; collects problem sentences. |
| `config/SettingsFiles.java` | Loads a file, applies a function that builds the settings, prints the problems. |
| `json/JsonCodec.java` | Strict `read` into a record, and `write` to text. |

Used by: `seatwave/ServiceSettings.java` (reads with `SettingsReader`), `seatwave/SeatWaveCli.java`, `harness/HarnessCli.java` and `deploy/DeployCli.java` (load with `SettingsFiles`), `seatwave/HoldRoutes.java` and `seatwave/Problems.java` (use `JsonCodec`), `harness/LoadRun.java` (reads SeatWave's replies with `JsonCodec`), and the tests.

## How it works underneath

**Settings.** The file is read into a `java.util.Properties` object: a table from key text to value text. `SettingsReader` wraps it and offers typed reads (`intBetween`, `millisBetween`, `textMatching`, `listMatching`, `text`). When a key is missing or wrong, the method writes one sentence into an internal list of problems and returns a harmless stand-in value, so the next read can run. After all reads, the caller looks at the list: empty means every value is trustworthy. `SettingsFiles.load` wraps the whole sequence for a command-line program: read the file, run the function, and either return the settings or print every problem.

**JSON.** Jackson normally reads leniently. It ignores unknown fields, turns the text `"1"` into the number 1, cuts `1.5` to 1 for an integer, and lets a repeated field overwrite the first. `JsonCodec` switches each of those off, one by one. It also reads the first token by hand, so an empty body, a body that is not an object and data after the object each get their own message.

## Walkthrough of this repository's use

### `config/SettingsReader.java`

```java
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
```

The package line and the imports. `Reader` reads text; `StandardCharsets` names character encodings; `Files` and `Path` work with files; `NoSuchFileException` is the error for a missing file; `Duration` is a length of time; `ArrayList` and `List` are lists; `Locale` fixes number formatting; `Objects` has `requireNonNull`; `Properties` is the key-value table; `Pattern` is a regular expression.

```java
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
```

The Javadoc states the contract.

- **No defaults**: "a key that is absent is a problem, never a silent fallback".
- When a key is invalid, the method records the problem and returns a harmless stand-in. "The caller must check `problems()` before using any value." The stand-in is never meant to be used.
- **Not thread-safe**: one thread builds one settings record with one reader. Nothing else shares it.

`public final class` means: visible everywhere, and no one can extend it.

```java
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
```

The reader keeps the `Properties` and an `ArrayList` of problem sentences. The constructor refuses `null`.

`Objects.requireNonNull(properties, "properties")` returns its first argument if it is not null and otherwise throws a `NullPointerException` whose message is the second argument, so a mistake names the argument.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file does not exist or cannot be read; the message says which
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (NoSuchFileException missing) {
      throw new IOException("the settings file " + path + " does not exist", missing);
    }
    return properties;
  }
```

`load` is a **static** method (it belongs to the class, not to an object) that reads a file into a `Properties`.

- `Files.newBufferedReader(path, StandardCharsets.UTF_8)` opens the file as UTF-8 text. UTF-8 is the usual way to write every character as bytes.
- `try (Reader reader = ...)` is **try-with-resources**: Java closes the reader when the block ends, whether it ends normally or with an error.
- `properties.load(reader)` parses the `key=value` lines. Lines that start with `#` are comments.
- `NoSuchFileException` (a kind of `IOException`) is caught and replaced with a new `IOException` whose message is `the settings file <path> does not exist`. The original is passed as the **cause**, so a stack trace still shows what really happened.

One difference from the Module 9 copy. The later `seatwave-proxy` version also catches `IllegalArgumentException` here. `Properties.load` throws that unchecked error when a file holds a broken escape (`\u` followed by fewer than four hexadecimal digits; in a properties file `\u0041` means `A`). Here `load` lets it fly out, and the catch lives one level up, in `SettingsFiles`. A caller that uses `SettingsReader.load` directly, such as the test of the shipped settings, would get the raw error. All three programs go through `SettingsFiles`, so users see the friendly message (below).

```java
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
```

`intBetween` reads a whole number inside a range.

1. `text(key)` gets the trimmed text (and records "is missing" if there is none). An empty result means the key was missing: the method returns the minimum without a second problem.
2. `Integer.parseInt(text)` turns text into a number. If the text is not a number, `NumberFormatException` is thrown and the `catch` records `key must be a whole number, but was text` and returns the minimum. The comment says why this is a problem line and not a crash: "a typo is an expected failure of a settings file".
3. A number outside the range records `key must be from 1 to 10, but was 99`. `String.format(Locale.ROOT, ...)` builds the text; `%s` is a text, `%d` a whole number, and `Locale.ROOT` makes the formatting the same on every computer.

Because the minimum is returned in every failure, the caller's next line still works.

```java
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
```

`millisBetween` reads a number of milliseconds and wraps it in a `Duration`. By the project's rule, a key that holds milliseconds ends in `Millis`.

```java
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
```

`textMatching` reads a text that must match a pattern.

- Missing: `text` already recorded it; return the empty string.
- `allowed.matcher(text).matches()` is true only if the **whole** text fits the pattern. A text that merely contains a match does not count.
- No match: record `key must match <pattern>, but was <text>` and return an empty string.

Notice that the message shows the pattern's source text, so a user sees the rule that broke. A rule such as `[a-z][a-z0-9-]{0,31}` is readable enough.

```java
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
```

`listMatching` reads a comma-separated list, for example `seatwave-1, seatwave-2`.

- `text.split(",", -1)` cuts the text at every comma. The `-1` keeps empty items at the end: `a,` gives two items, `a` and an empty one, and the empty one then fails the pattern. Without the `-1`, a trailing comma would vanish silently.
- `item.strip()` removes spaces around an item.
- The first bad item records `key has the item 'X', which must match <pattern>` and returns an empty list. Reporting only the first bad item keeps the line short.
- `List.copyOf(items)` returns an unmodifiable copy.

```java
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
```

`text` is the lowest-level read, used by all the others. `properties.getProperty(key)` returns `null` for a missing key. A value that is only spaces counts as missing (`isBlank`). Otherwise the value is trimmed with `strip()`.

The key is checked for `null` first: a null key is a programming mistake, not a settings mistake.

```java
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
```

- `addProblem` lets the caller record a problem that involves more than one key, for example "these two ports must differ", in the same list and order as the others.
- `problems()` returns `List.copyOf(problems)`, a snapshot. A caller cannot change the reader's list.

### `config/SettingsFiles.java`

```java
package io.plainround.seatwave.balancer.config;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
```

The imports. `PrintStream` is a place to print text (such as the error output). `Function` is a Java type for "a piece of code that takes an argument and returns a result".

```java
/**
 * Loads one settings file into a checked settings record for a command-line entry point, and prints
 * every problem when it cannot. Thread-safe: it holds no state.
 */
public final class SettingsFiles {

  private SettingsFiles() {}
```

The Javadoc: "Loads one settings file into a checked settings record for a command-line entry point, and prints every problem when it cannot. Thread-safe: it holds no state." The constructor is private and empty: no one makes an object of this class; it is a holder of one static method.

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
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(reading, "reading");
    Objects.requireNonNull(err, "err");
```

The signature has a **type parameter** `<T>`: the method works for any settings type, and the caller says which. The three arguments are the file, `reading` (a function that takes a `SettingsReader` and returns a `Result` of either the settings or a list of problems) and the stream for messages. The method returns an `Optional<T>`: the settings, or empty after printing why not.

The three `Objects.requireNonNull` lines reject null arguments.

```java
    try {
      return switch (reading.apply(new SettingsReader(SettingsReader.load(file)))) {
        case Result.Success<T, List<String>>(T checked) -> Optional.of(checked);
        case Result.Error<T, List<String>>(List<String> problems) -> {
          err.println("the settings in " + file + " have " + problems.size() + " problem(s):");
          problems.forEach(problem -> err.println("  " + problem));
          yield Optional.empty();
        }
      };
```

The center of the method.

1. `SettingsReader.load(file)` reads the file into `Properties`.
2. `new SettingsReader(...)` wraps them, and `reading.apply(reader)` runs the settings class's own `from` method. (`SeatWaveCli` passes `ServiceSettings::from`, a method reference.)
3. A `switch` over the `Result` with **record patterns**: `case Result.Success<T, List<String>>(T checked)` matches a success and names its value `checked`; `case Result.Error<...>(List<String> problems)` matches an error and names the list.
4. Success: `Optional.of(checked)`.
5. Error: print `the settings in <file> have N problem(s):`, then one indented line for each problem, and `yield Optional.empty()`. `yield` is how a `switch` branch with a block gives its value.

The `switch` is exhaustive: `Result` has exactly two cases, so no `default` is needed.

```java
    } catch (IOException unreadable) {
      err.println(unreadable.getMessage());
      return Optional.empty();
    } catch (IllegalArgumentException malformed) {
      // Properties.load refuses a malformed \\u escape this way; it is a mistake in the file.
      err.println("the settings file " + file + " cannot be read: " + malformed.getMessage());
      return Optional.empty();
    }
  }
}
```

Two failure paths around the whole thing.

- `IOException`: the message was written for a human by `SettingsReader.load` (`the settings file ... does not exist`). It is printed as it is.
- `IllegalArgumentException`: `Properties.load` refuses a broken escape this way, and it is a mistake in the file. The message printed is `the settings file <file> cannot be read: Malformed \uxxxx encoding.` `SettingsFilesTest.reportsAMalformedEscapeAsAProblemNotACrash` tests it, and I ran the program on such a file: it exited with status 2.

A careful reader should know the catch is wide: it also catches an `IllegalArgumentException` thrown by `reading.apply`. If a settings class had a bug that threw one, the user would be told the file "cannot be read". `ServiceSettings.from` avoids this by recording problems rather than throwing.

### `json/JsonCodec.java`

```java
package io.plainround.seatwave.balancer.json;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.stream.Collectors;
```

The imports are mostly Jackson (see `jackson.md`). The names to know: `ObjectMapper` is the object that converts. `JsonMapper.builder()` makes one with chosen settings. `JsonParser` and `JsonToken` read JSON piece by piece. `DeserializationFeature`, `MapperFeature`, `StreamReadFeature` and the `Coercion` classes are switches. The exception classes name the kinds of failure. `Collectors` gathers the pieces of a stream into one result.

```java
/**
 * JSON in and out, bound only to known records. Unknown fields, nulls for numbers, a value of the
 * wrong type, a number out of range, trailing data and duplicate fields are refused, so a body
 * either matches a record exactly or is rejected with a reason that names the field.
 *
 * <p>Thread-safe: the mapper is configured once in the constructor and only read afterwards, which
 * is the use Jackson documents as safe across threads.
 */
public final class JsonCodec {
```

The Javadoc lists what is refused: "Unknown fields, nulls for numbers, a value of the wrong type, a number out of range, trailing data and duplicate fields". The promise at the end, "a reason that names the field", is the contract. The class is thread-safe, because the mapper "is configured once in the constructor and only read afterwards".

Two of the promises are not fully kept in this copy. Trailing text that is not JSON, and a repeated field, both give the whole-body message "the body is not valid JSON", not a message that names the field. The walkthrough of `read` shows where.

```java
  // RFC 6901: the empty pointer is the whole document.
  private static final String WHOLE_BODY = "";
```

`WHOLE_BODY` is the empty string, the JSON pointer of the whole document (RFC 6901).

```java
  private final ObjectMapper mapper =
      JsonMapper.builder()
          // A field the contract does not list is an error (additionalProperties: false), never
          // ignored: ignoring it would let a typo pass silently, and is how mass assignment starts.
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          // "1" and 1.5 are not the integer the contract asks for: no silent conversion.
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          // The switch above covers numbers and booleans only; 5 or true is not a string either.
          .withCoercionConfig(
              LogicalType.Textual,
              textual ->
                  textual
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
```

The mapper is made once and switched to strict.

- `FAIL_ON_UNKNOWN_PROPERTIES`: a field the contract does not list is an error. The comment explains: ignoring it "would let a typo pass silently, and is how mass assignment starts".
- `FAIL_ON_NULL_FOR_PRIMITIVES`: `null` for a field of type `long` or `int` is an error, not a silent zero.
- Disabling `ALLOW_COERCION_OF_SCALARS` and `ACCEPT_FLOAT_AS_INT`: `"1"` and `1.5` are not the whole number the contract asks for. No silent conversion.
- `withCoercionConfig(LogicalType.Textual, ...)`: the switch above covers numbers and booleans but not text fields. This block says that for a text field an integer, a float or a boolean input is a failure. So `"seat":5` does not become `"5"`.
- `STRICT_DUPLICATE_DETECTION`: a repeated field name is an error.
- `.build()` makes the finished mapper.

```java
  /**
   * Reads a body into a record.
   *
   * @param json the body
   * @param type the record it must match
   * @param <T> the record type
   * @return the record, or a problem that names the JSON pointer of the field at fault
   */
  public <T> Result<T, BindingProblem> read(String json, Class<T> type) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(type, "type");
```

`read` has two type parameters: `<T>` is the record type, and `Class<T> type` is the record's class object, which tells Jackson what to build. The result is a `Result<T, BindingProblem>`. Both arguments are checked for null.

```java
    // The parser is driven by hand, so an empty body, a body that is not an object and data after
    // the object each get their own message instead of one generic type error.
    try (JsonParser parser = mapper.createParser(json)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body is empty; send a JSON object"));
      }
      if (first != JsonToken.START_OBJECT) {
        return Result.error(new BindingProblem(WHOLE_BODY, "the body must be a JSON object"));
      }
      T value = mapper.readValue(parser, type);
      if (parser.nextToken() != null) {
        return Result.error(
            new BindingProblem(WHOLE_BODY, "the body has data after the JSON object"));
      }
      return Result.success(value);
```

The body of `read`. The comment says the parser is driven by hand so that each kind of bad body "gets its own message instead of one generic type error".

1. `mapper.createParser(json)` makes a parser over the text; the `try (...)` closes it.
2. `parser.nextToken()` returns the first piece. `null` means there was nothing: "the body is empty; send a JSON object".
3. A first token other than `START_OBJECT` (the `{`) means the body is a list, a number or a text: "the body must be a JSON object".
4. `mapper.readValue(parser, type)` reads one object into the record.
5. `parser.nextToken() != null` after the object means more text follows: "the body has data after the JSON object".
6. Otherwise `Result.success(value)`.

```java
    } catch (UnrecognizedPropertyException unknownField) {
      return Result.error(
          new BindingProblem(
              "/" + escaped(unknownField.getPropertyName()), "is not a field of this request"));
    } catch (MismatchedInputException wrongType) {
      return Result.error(new BindingProblem(pointerOf(wrongType), "has the wrong type"));
    } catch (JsonMappingException unbound) {
      // 99999999999999999999 is valid JSON; it only does not fit the field's type.
      if (unbound.getCause() instanceof InputCoercionException) {
        return Result.error(
            new BindingProblem(pointerOf(unbound), "is out of range for this field"));
      }
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (JacksonException malformed) {
      return Result.error(new BindingProblem(WHOLE_BODY, "the body is not valid JSON"));
    } catch (IOException notReadable) {
      // Every parse failure is a JacksonException; reading from a string has no other I/O to fail.
      throw new UncheckedIOException("reading a string failed", notReadable);
    }
  }
```

The `catch` blocks, from the most specific to the most general. Java uses the first one that fits, so the order matters.

- `UnrecognizedPropertyException`: pointer `/` plus the field name, detail "is not a field of this request".
- `MismatchedInputException`: a value of the wrong type (a text for a number, a null for a `long`, a float for an integer, a number for a text). Pointer from `pointerOf`, detail "has the wrong type".
- `JsonMappingException`: other binding failures. The comment: `99999999999999999999` is valid JSON that does not fit the field's type. If the cause is an `InputCoercionException`, the detail is "is out of range for this field". Otherwise: "the body is not valid JSON".
- `JacksonException`: the general parse failure: "the body is not valid JSON".
- `IOException`: reading from a string has no real input or output to fail, so this cannot happen. The comment says so; the code turns it into an unchecked error to keep the signature clean.

Two flaws follow from these blocks. A repeated field (`{"eventId":1,"eventId":2}`) is found by `STRICT_DUPLICATE_DETECTION`, which throws a parse error, and the parse error lands in the `JacksonException` block. The answer is "the body is not valid JSON", although the Javadoc promises a message that names the field. And `{"eventId":1} x` makes `parser.nextToken()` on line 81 throw instead of returning a token, so it also lands in that block, not in "the body has data after the JSON object". I ran both through the codec; the Try-it below shows the first. The later `seatwave-proxy` code fixed both: it reports `appears more than once` with the field's pointer, and treats any text after the object as trailing data.

```java
  /**
   * Writes a value as JSON.
   *
   * @param value a record, a list or a map of them
   * @return the JSON text
   * @throws IllegalArgumentException if the value cannot be written, which is a programming error
   */
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException notWritable) {
      throw new IllegalArgumentException("not writable as JSON", notWritable);
    }
  }
```

`write` turns a record, list or map into JSON text with `mapper.writeValueAsString`. A failure is a programming mistake (a type Jackson cannot write), so it is wrapped in an unchecked `IllegalArgumentException`. Record fields come out in the order they are declared.

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

  private static String escaped(String fieldName) {
    // RFC 6901: "~" and "/" inside a name are written "~0" and "~1", in this order.
    return fieldName.replace("~", "~0").replace("/", "~1");
  }
```

Two helpers that build the JSON pointer.

- `pointerOf`: Jackson remembers the **path** it was reading when it failed, as a list of steps. Each step has a field name or a list index. The stream maps each step to its text (the index as a number, the name escaped) and joins them with `/`, starting with `/`.
- `escaped`: RFC 6901 says a `~` in a name is written `~0` and a `/` is written `~1`, in this order. The order matters: replacing `/` first would then have its `~` changed again.

One edge: if the path were empty, `pointerOf` would return `/` and not the empty pointer. That cannot happen here because the first token is always `{`, so a failing path always has at least one field.

```java
  /**
   * Why a body did not bind.
   *
   * @param pointer the JSON pointer of the field at fault, or an empty string for the whole body
   * @param detail what is wrong, in words a client developer can act on
   */
  public record BindingProblem(String pointer, String detail) {

    /** Checks the fields. */
    public BindingProblem {
      Objects.requireNonNull(pointer, "pointer");
      Objects.requireNonNull(detail, "detail");
    }
  }
}
```

`BindingProblem` is a record: a `pointer` and a `detail`. The Javadoc says the pointer is "an empty string for the whole body". The compact constructor refuses null for both.

## Try it yourself

All commands run in the project folder. Timings differ on every run.

1. Run the tests of the `config` package. Three classes test the readers; `InfrastructureRulesTest` is explained in `tests-config-walkthrough.md`.

```bash
./gradlew --offline cleanTest test --tests '*SettingsReaderTest' --tests '*SettingsFilesTest' 2>&1 | grep -E "PASSED|FAILED|BUILD"
```

Six tests pass, then `BUILD SUCCESSFUL`.

2. Ask the settings reader a few questions in JShell, Java's interactive console. First build the libraries folder with `./gradlew --offline -q stackFiles`. The `grep` keeps only the answers.

```bash
cat > build/settings-try.jsh <<'EOF'
import io.plainround.seatwave.balancer.config.SettingsReader;
var properties = new java.util.Properties();
properties.putAll(java.util.Map.of("count", "seven", "range", "99", "size", " 8 "));
var reader = new SettingsReader(properties);
reader.intBetween("count", 1, 10)
reader.intBetween("range", 1, 10)
reader.intBetween("size", 1, 10)
reader.text("absent")
reader.problems()
/exit
EOF
jshell --class-path 'build/stack/lib/*' < build/settings-try.jsh 2>&1 | grep -o '\$[0-9]* ==> .*'
```

```text
$5 ==> 1
$6 ==> 1
$7 ==> 8
$8 ==> ""
$9 ==> [count must be a whole number, but was seven, range must be from 1 to 10, but was 99, absent is missing]
```

Bad values give the minimum (1) and a problem sentence. The value `" 8 "` is trimmed and read as 8. A missing key gives an empty string. The numbers `$5` to `$9` are JShell's own labels.

3. Feed the JSON codec good and bad bodies. The record `Hold` here belongs to the experiment, not to the project.

```bash
cat > build/json-try.jsh <<'EOF'
import io.plainround.seatwave.balancer.json.JsonCodec;
record Hold(long eventId, String seat) {}
var codec = new JsonCodec();
codec.read("{\"eventId\":1,\"seat\":\"A-7\"}", Hold.class)
codec.read("{\"eventId\":\"1\",\"seat\":\"A-7\"}", Hold.class)
codec.read("{\"eventId\":1,\"seat\":\"A-7\",\"x\":1}", Hold.class)
codec.read("{\"eventId\":99999999999999999999,\"seat\":\"A-7\"}", Hold.class)
codec.read("", Hold.class)
codec.read("[1]", Hold.class)
codec.read("{\"eventId\":1,\"seat\":\"A-7\"} {}", Hold.class)
codec.read("{\"eventId\":1,\"eventId\":2,\"seat\":\"A-7\"}", Hold.class)
codec.write(new Hold(1, "A-7"))
/exit
EOF
jshell --class-path 'build/stack/lib/*' < build/json-try.jsh 2>&1 | grep -o '\$[0-9]* ==> .*'
```

```text
$4 ==> Success[result=Hold[eventId=1, seat=A-7]]
$5 ==> Error[error=BindingProblem[pointer=/eventId, detail=has the wrong type]]
$6 ==> Error[error=BindingProblem[pointer=/x, detail=is not a field of this request]]
$7 ==> Error[error=BindingProblem[pointer=/eventId, detail=is out of range for this field]]
$8 ==> Error[error=BindingProblem[pointer=, detail=the body is empty; send a JSON object]]
$9 ==> Error[error=BindingProblem[pointer=, detail=the body must be a JSON object]]
$10 ==> Error[error=BindingProblem[pointer=, detail=the body has data after the JSON object]]
$11 ==> Error[error=BindingProblem[pointer=, detail=the body is not valid JSON]]
$12 ==> "{\"eventId\":1,\"seat\":\"A-7\"}"
```

Line `$11` is the repeated `eventId`. It should name `/eventId`; it does not (see the flaw above).

4. Give the program a settings file with a broken escape. It prints one friendly line and exits with status 2.

```bash
printf 'http.bindAddress=\u12\n' > build/esc.properties
java -cp 'build/stack/lib/*' io.plainround.seatwave.balancer.seatwave.SeatWaveCli build/esc.properties seatwave-1; echo "exit $?"
```

```text
the settings file build/esc.properties cannot be read: Malformed \uxxxx encoding.
exit 2
```

## Mistakes people make

- **Defaults hidden in code.** `if (port == null) port = 8080` makes a program that runs with settings no one wrote down. Here a missing key is a problem sentence.
- **Stopping at the first bad key.** A reader fixes one error, runs again, finds the next. This reader collects all.
- **Using `find()` or `contains` where the whole value must fit.** `matches()` is used, so `12abc` is not a number-like value.
- **Trusting JSON to match the contract by default.** Jackson is lenient unless told otherwise. Each strict switch in `JsonCodec` closes one real hole: the typo field, the silent conversion, the null that becomes zero, the second value of a repeated field.
- **Returning a stand-in and using it.** The stand-ins (the minimum, the empty string) are only there to keep reading. `ServiceSettings.from` checks `problems()` before it builds anything.
- **Escaping a JSON pointer in the wrong order.** Replace `~` first, then `/`.
- **Catching a general error and mislabeling it.** `SettingsFiles` catches `IllegalArgumentException` around a call that includes the caller's own code; a bug in that code would be reported as an unreadable file.
- **Copying code and trusting that it is current.** This copy lacks fixes that the Module 9 project made afterwards (the duplicate-field message and the trailing-data message). The project's own README says the code is "unchanged except the package", and no test file for `JsonCodec` exists, so nothing noticed. JaCoCo shows 22 of the 50 lines of `JsonCodec` never run in the build.

## Check yourself

1. What does `SettingsReader.intBetween("poolSize", 1, 100)` return, and what does it record, when the file says `poolSize=abc`?
   Answer: it returns 1, the minimum, and records `poolSize must be a whole number, but was abc`.
2. Why does `listMatching` split with `split(",", -1)`?
   Answer: the `-1` keeps empty items at the end, so `a,` gives an empty second item that then fails the pattern; without it the trailing comma would vanish.
3. Why is the order of the `catch` blocks in `JsonCodec.read` important?
   Answer: Java uses the first block that fits. `UnrecognizedPropertyException` and `MismatchedInputException` are kinds of `JsonMappingException`, which is a kind of `JacksonException`; the most specific must come first or the specific messages would never be reached.
4. Which settings in the mapper stop `{"seat":5}` from becoming the text `"5"`?
   Answer: the coercion configuration for textual types that sets integer, float and boolean input to `Fail`.
5. What does `SettingsFiles.load` return when the file has two bad keys?
   Answer: an empty `Optional`, after printing `the settings in <file> have 2 problem(s):` and one indented line for each.

## Further reading

- Java SE 25 API, `java.util.Properties` (the file format and `load`): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- Java SE 25 API, `java.util.regex.Pattern`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html
- Jackson 2.23 databind, `DeserializationFeature` and `MapperFeature`: https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.23.0/index.html
- Jackson core 2.23, `StreamReadFeature.STRICT_DUPLICATE_DETECTION`: https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-core/2.23.0/index.html
- RFC 6901, JavaScript Object Notation (JSON) Pointer: https://www.rfc-editor.org/rfc/rfc6901
- RFC 8259, The JSON Data Interchange Format: https://www.rfc-editor.org/rfc/rfc8259
- result4j: https://github.com/sviperll/result4j
- JShell, the Java interactive console (JDK 25 tool reference): https://docs.oracle.com/en/java/javase/25/docs/specs/man/jshell.html
