# Jackson 2.23.0 (JSON in and out)

## What it is

**JSON** (JavaScript Object Notation) is a text format for data. An **object** is a set of named values between braces, `{"eventId":1,"seat":"A-1"}`. A name is a **field**. A value is a number, a piece of text in double quotes, `true`, `false`, `null`, another object, or a list in square brackets. Programs on different machines and in different languages all read it, which is why web services use it.

**Jackson** is the Java library that turns JSON text into Java values and back. Reading JSON into a Java value is called **binding** or **deserializing**. Writing a Java value as JSON is **serializing**. Jackson is made of three parts, each one a jar:

| Jar | What it holds |
| --- | --- |
| `jackson-core` | The low-level reader and writer of JSON text, one token at a time (`JsonParser`, `JsonToken`). |
| `jackson-annotations` | Labels such as `@JsonProperty` that a class can carry. This repository uses none, but `jackson-databind` needs the jar. |
| `jackson-databind` | The part that binds JSON to Java classes and records. Its main class is `ObjectMapper`. |

The repository asks only for `jackson-databind`; Gradle brings the other two (`dependency-locking.md`). All three are version 2.23.0, except the annotations jar, which is `2.23` (Jackson publishes it with no patch number).

## Why this repository uses it

SeatWave's API takes a JSON body to hold a seat and answers in JSON. The instance runs on the JDK's own HTTP server, which has no JSON support, so a library is needed. The module puts three instances behind a balancer, and a retried request with the same `Idempotency-Key` must mean exactly the same hold on any of them. If an instance accepted a body loosely, a typo, a wrong type or a field the contract does not list could pass unnoticed, and two instances could disagree about what a request meant. So `JsonCodec` is strict: a body either matches a record exactly, or it is refused with a reason that names the field. The load harness uses the same codec to read the hold it gets back.

## Where it appears

| Path | What it does with Jackson |
| --- | --- |
| `gradle/libs.versions.toml` | `jackson = "2.23.0"`, the `jackson-bom` and `jackson-databind` entries (`toml-version-catalog.md`). |
| `build.gradle.kts` | `implementation(platform(libs.jackson.bom))` and `implementation(libs.jackson.databind)` (`gradle.md`). |
| `gradle.lockfile` | Locks the four Jackson lines on all four classpaths (`dependency-locking.md`). |
| `src/main/java/.../json/JsonCodec.java` | The only class that imports Jackson: it builds the `ObjectMapper`, binds a body (`read`) and writes a value (`write`). |
| `src/main/java/.../seatwave/HoldRoutes.java`, `Problems.java` | Call `json.read` and `json.write`; they never import Jackson (`java-config-json-walkthrough.md`). |
| `src/main/java/.../harness/LoadRun.java` | Calls `json.read` on the body of a granted hold to remember its id. |

I searched `src` for `com.fasterxml`: only `JsonCodec.java` imports it, with 16 import lines. No test imports Jackson, and no test file is named for the codec: the tests reach it through the routes and the load run (the coverage report shows that the refusal branches of `read` are never run, `jacoco.md`).

## How it works underneath

1. `JsonMapper.builder()...build()` makes an `ObjectMapper`, the object that holds the settings. Making one is costly, so the codec makes exactly one, when the codec is created, and only reads it afterwards. Jackson documents a configured `ObjectMapper` as safe for many threads at once, which the class comment of `JsonCodec` relies on.
2. To read, the mapper asks a `JsonParser` for tokens (`START_OBJECT`, a field name, a number, `END_OBJECT`). It looks at the target type, here a Java **record**, finds the record's constructor, and calls it with the values it read. A record needs no annotations: its component names are the field names.
3. To write, the mapper looks at a record or a list or a map, and writes its components in the order the record declares them. `Problems.ProblemBody` says so in a comment: "record components are written in this order".
4. Everything is in memory. Jackson makes no network call and reads no file.
5. Every failure is an exception. The codec catches the ones a client can cause and turns each into a `Result.error` (`result4j.md`). The base class of all of them is `JacksonException`; `JsonMappingException` is the one for "the JSON is fine but does not fit the type", `MismatchedInputException` is a wrong type, and `UnrecognizedPropertyException` is a field the type does not have.

**What Jackson does by default.** A default `ObjectMapper` is forgiving. I ran a tiny program with the 2.23.0 jars. A record `Hold(long eventId, String seat)` was written as `{"eventId":1,"seat":"A-1"}` and read back as `Hold[eventId=7, seat=B-2]`. The text `"7"` in place of the number 7 was **coerced** (silently converted) to 7. A field `typo` that the record does not have raised `UnrecognizedPropertyException`, because refusing unknown fields is on by default. And `"eventId":null` for the primitive `long` was read as `0`, with no error, until `FAIL_ON_NULL_FOR_PRIMITIVES` was switched on, which made it raise `MismatchedInputException`. Those are exactly the soft spots that `JsonCodec` closes.

## Walkthrough of this repository's use

### The imports

```java
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
```

- The first group (`com.fasterxml.jackson.core.*`) is `jackson-core`: `JacksonException` (the base of every Jackson failure), `JsonParser` and `JsonToken` (reading token by token), `JsonProcessingException` (thrown when writing fails), `StreamReadFeature` (switches of the parser), and `InputCoercionException` (a number that does not fit its type).
- The second group (`com.fasterxml.jackson.databind.*`) is `jackson-databind`: `DeserializationFeature` and `MapperFeature` (switches of the mapper), `JsonMappingException`, `MismatchedInputException` and `UnrecognizedPropertyException` (the exception family), `ObjectMapper` and `JsonMapper` (the mapper and its builder), and `CoercionAction`, `CoercionInputShape` and `LogicalType` (the vocabulary of the coercion rules).
- `com.github.sviperll.result4j.Result` is the result type (`result4j.md`). The rest are JDK classes: `IOException` and `UncheckedIOException` for the one failure that cannot be the client's fault, `Objects` for the null checks and `Collectors` for the pointer helper below.

### The class comment and the strict mapper

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

  // RFC 6901: the empty pointer is the whole document.
  private static final String WHOLE_BODY = "";
```

The class comment lists what is refused: "Unknown fields, nulls for numbers, a value of the wrong type, a number out of range, trailing data and duplicate fields". It promises a reason that names the field. The notes on `read` below show that two of the refusals, trailing text that is not JSON and a repeated field, give the generic reason `the body is not valid JSON` and do not name the field. `WHOLE_BODY` is the empty JSON pointer, which RFC 6901 defines as the whole document. The comment on the `final` class says it is thread-safe: the mapper is configured once and only read afterwards.

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

- `private final ObjectMapper mapper = JsonMapper.builder() ... .build();` makes the one mapper, as a field of the codec. `final` means it is assigned once. A **builder** collects settings and then builds an object that cannot be changed.
- `.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` refuses a field that the record does not list. The comment explains why even though it is on by default: "ignoring it would let a typo pass silently, and is how mass assignment starts." **Mass assignment** is an attack in which a client sends extra fields (such as `"admin":true`) and a loose binder stores them. Writing the line down also protects against a future default change.
- `.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)` refuses `null` where the record has a primitive number. I showed above that without it `null` becomes `0` silently.
- `.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)` refuses `"1"` where a number is wanted. A **scalar** is a single value (a number, a text, a boolean). The comment says `"1" and 1.5 are not the integer the contract asks for: no silent conversion.`
- `.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)` refuses `1.5` where an integer is wanted instead of cutting it to `1`.
- `.withCoercionConfig(LogicalType.Textual, textual -> textual.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)...)` is the reverse direction. A **lambda** is a short anonymous function: `textual -> ...` takes the settings object for text fields and changes it. For a field that expects **text**, an integer, a float and a boolean are each set to `CoercionAction.Fail`. The comment says: "The switch above covers numbers and booleans only; 5 or true is not a string either." So `{"seat": 5}` is refused. I fed that body to the codec and got `/seat has the wrong type`.
- `.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)` makes the parser refuse an object with the same field twice. By default the last one wins, which lets a client hide a value from a check that reads the first. I tested it: `{"eventId":1,"eventId":2,...}` was refused, but with the reason "the body is not valid JSON" and not one that names `eventId`.

### `read`: one success, eight refusals

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

- `public <T> Result<T, BindingProblem> read(String json, Class<T> type)` is **generic**: `<T>` stands for any record type the caller names with `type`. It returns a `Result` (`result4j.md`): the record, or a `BindingProblem` with a `pointer` and a `detail`.
- `Objects.requireNonNull(...)` twice refuse a `null` argument. That is a programming error, so it throws.
- The comment says the parser is driven by hand: "an empty body, a body that is not an object and data after the object each get their own message instead of one generic type error." `try (JsonParser parser = mapper.createParser(json))` opens a parser on the text; the **try-with-resources** closes it at the end.
- `JsonToken first = parser.nextToken();` reads the first token. `null` means the text was empty. Anything but `START_OBJECT` means the body is not an object, for example `[1]`.
- `T value = mapper.readValue(parser, type);` binds the object to the record. The next line, `parser.nextToken() != null`, asks whether anything follows the closing brace. If a token follows, the answer is "the body has data after the JSON object". If the extra text is not even a valid token (`x`), `nextToken` itself throws, and the `catch (JacksonException malformed)` block turns it into "the body is not valid JSON". That is the first case where the reason is less exact than the class comment suggests.
- The `catch` blocks go from the most specific exception to the most general, because Java takes the first block that fits. `UnrecognizedPropertyException` becomes the pointer `"/" + escaped(unknownField.getPropertyName())` with "is not a field of this request". `MismatchedInputException` becomes "has the wrong type". Another `JsonMappingException` is checked for a cause of `InputCoercionException`, the comment's example being `99999999999999999999`, "valid JSON; it only does not fit the field's type", and becomes "is out of range for this field". Any other `JacksonException` becomes "the body is not valid JSON": a body that is cut short, and also the repeated field, which Jackson reports as a syntax error. The `IOException` block is for a failure that cannot happen when reading a `String`, and so it throws.

I fed ten bodies to `JsonCodec.read` with a three-field record (`Long eventId`, `String seat`, `String customer`) and read the `detail`:

| Body | Answer |
| --- | --- |
| (empty) | `the body is empty; send a JSON object` |
| `[1]` | `the body must be a JSON object` |
| `{"eventId":1,"seat":"A-3","customer":"Sam"} x` | `the body is not valid JSON` |
| `{"eventId":1,"seat":"A-3","customer":"Sam"} {}` | `the body has data after the JSON object` |
| `{"eventId":"1","seat":"A-3","customer":"Sam"}` | `/eventId has the wrong type` |
| `{"eventId":99999999999999999999,...}` | `/eventId is out of range for this field` |
| `{"eventId":1,"seat":"A-3",` | `the body is not valid JSON` |
| `{"eventId":1,"eventId":2,...}` | `the body is not valid JSON` |
| `{"eventId":null,...}` | a success: `eventId` is `null` |
| `{"eventId":1,"seat":"A-2","bogus":1}` | `/bogus is not a field of this request` |

The `null` row binds, because the record uses `Long`, a type that may be `null`; the route then refuses it with its own rule (`/eventId must be 1 or more`, in `HoldRoutes.checked`). The two rows `x` and the repeated field are what a reader should know about: they are refused, which is the safe outcome, but the reason does not name the field.

### `write` and the pointer helpers

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

- `write` calls `mapper.writeValueAsString(value)`. Writing can fail only if the value cannot be written, which is a bug, so the `JsonProcessingException` is turned into an `IllegalArgumentException`.
- `pointerOf` builds a **JSON pointer** (RFC 6901), a path such as `/eventId` that names one place in a JSON document. Jackson's exception carries the path it was reading; `getPath().stream().map(...).collect(Collectors.joining("/", "/", ""))` writes each step (a field name, or a list index) and joins them with `/` after a leading `/`. A stream is a pipeline of steps over a list; `joining` glues the strings with a separator, a prefix and a suffix.
- `escaped` writes a `~` as `~0` and a `/` as `~1`, in that order, as RFC 6901 requires, so a field name that contains a slash cannot be mistaken for two steps.

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
```

`BindingProblem` is a **record** (`java.md`) with two text components. Its compact constructor refuses `null`.

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. See who imports Jackson:

```
grep -rl "com.fasterxml" src
```

   Expected: `src/main/java/io/plainround/seatwave/balancer/json/JsonCodec.java` and nothing else.

2. See the jars Gradle chose:

```
./gradlew --offline -q dependencies --configuration runtimeClasspath | grep jackson | head -8
```

   Expected: the BOM `com.fasterxml.jackson:jackson-bom:2.23.0`, then `jackson-databind -> 2.23.0` with `jackson-annotations:2.23` and `jackson-core:2.23.0` below it.

3. Run the default mapper and see what it forgives. Save this as `J.java` in an empty folder:

```
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class J {
  record Hold(long eventId, String seat) {}

  public static void main(String[] args) throws Exception {
    ObjectMapper lenient = JsonMapper.builder().build();
    ObjectMapper strict =
        JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    System.out.println(lenient.writeValueAsString(new Hold(1, "A-1")));
    Hold read = lenient.readValue("{\"eventId\":7,\"seat\":\"B-2\"}", Hold.class);
    System.out.println(read);
    System.out.println(lenient.readValue("{\"eventId\":\"7\",\"seat\":\"B-2\"}", Hold.class));
    try {
      lenient.readValue("{\"eventId\":7,\"seat\":\"B-2\",\"typo\":1}", Hold.class);
    } catch (Exception e) {
      System.out.println(e.getClass().getSimpleName());
    }
    try {
      strict.readValue("{\"eventId\":null,\"seat\":\"B-2\"}", Hold.class);
    } catch (Exception e) {
      System.out.println(e.getClass().getSimpleName());
    }
    System.out.println(lenient.readValue("{\"eventId\":null,\"seat\":\"B-2\"}", Hold.class));
  }
}
```

   Run it with the three jars from Gradle's cache on the class path:

```
CP=$(find ~/.gradle -name 'jackson-databind-2.23.0.jar'|head -1):$(find ~/.gradle -name 'jackson-core-2.23.0.jar'|head -1):$(find ~/.gradle -name 'jackson-annotations-2.23.jar'|head -1)
java -cp "$CP" J.java
```

   Expected, six lines: `{"eventId":1,"seat":"A-1"}`, `Hold[eventId=7, seat=B-2]`, `Hold[eventId=7, seat=B-2]`, `UnrecognizedPropertyException`, `MismatchedInputException` and `Hold[eventId=0, seat=B-2]`.

4. Send bad bodies to the real codec. The compiled classes are in `build/classes/java/main` after a build. Save this as `Probe2.java` in an empty folder:

```
import io.plainround.seatwave.balancer.json.JsonCodec;

public class Probe2 {
  record Body(Long eventId, String seat, String customer) {}

  public static void main(String[] a) {
    JsonCodec c = new JsonCodec();
    String[] bodies = {"", "[1]", "{\"eventId\":1,\"seat\":\"A-3\",\"customer\":\"Sam\"} x", "{\"eventId\":1,\"eventId\":2,\"seat\":\"A-3\",\"customer\":\"Sam\"}", "{\"eventId\":1,\"seat\":\"A-2\",\"bogus\":1}"};
    for (String b : bodies) System.out.println(b + "  ->  " + c.read(b, Body.class));
    System.out.println(c.write(new Body(1L, "A-1", "Sam")));
  }
}
```

   Run it with the classes folder, the three Jackson jars and the result4j jar on the class path (the path to the classes is relative to the folder of your project, so adjust it):

```
CP=<project>/build/classes/java/main:$(find ~/.gradle -name 'jackson-databind-2.23.0.jar'|head -1):$(find ~/.gradle -name 'jackson-core-2.23.0.jar'|head -1):$(find ~/.gradle -name 'jackson-annotations-2.23.jar'|head -1):$(find ~/.gradle -name 'result4j-1.2.0.jar'|head -1)
java -cp "$CP" Probe2.java
```

   Expected, six lines (the first body is the empty text, so its line starts with spaces), for example `[1]  ->  Error[error=BindingProblem[pointer=, detail=the body must be a JSON object]]`, then the `x` line and the repeated-field line with `detail=the body is not valid JSON`, then `/bogus is not a field of this request`, and last `{"eventId":1,"seat":"A-1","customer":"Sam"}`, which is `write` of a record with its components in declaration order. (Each `Error[...]` line shows the record `BindingProblem` as the `toString` of a record prints it.)

## Mistakes people make

- **Keeping Jackson's defaults for an API that must be strict.** They convert `"7"` to 7 and `null` to 0 without a word.
- **Making a new `ObjectMapper` for every request.** It is costly. One mapper, configured once, is the documented safe pattern.
- **Ignoring unknown fields to be "tolerant".** A typo then passes, and extra fields reach the code. This repository refuses them.
- **Letting Jackson exceptions reach the client.** Their messages can quote the input and the internal class names. The codec maps each one to a short, safe sentence.
- **Trusting the number of tokens.** An object followed by more data is a different failure from an object with a typo; the code looks for it on purpose.
- **Using a mutable class for a request.** A record cannot change after it is built, so a checked request stays checked.

## Check yourself

1. *What are the three Jackson jars, and which one does the build file name?* `jackson-core`, `jackson-annotations` and `jackson-databind`; only `jackson-databind`, and Gradle brings the others.
2. *Why is `FAIL_ON_NULL_FOR_PRIMITIVES` switched on?* Without it a `null` for a primitive number becomes `0` silently.
3. *What does `STRICT_DUPLICATE_DETECTION` stop?* An object that names the same field twice, where the last value would otherwise win.
4. *What is a JSON pointer?* A path such as `/eventId` that names one place in a document; the codec uses it to name the field at fault.
5. *Why does `read` return a `Result` and not throw?* A client can cause every one of these failures, so each is a value the caller must handle; only a bug is an exception.

## Further reading

- Jackson databind (2.x) project and wiki: <https://github.com/FasterXML/jackson-databind>
- Jackson core streaming API (`JsonParser`, `StreamReadFeature`): <https://github.com/FasterXML/jackson-core>
- JSON (ECMA-404): <https://ecma-international.org/publications-and-standards/standards/ecma-404/>
- JSON (RFC 8259): <https://www.rfc-editor.org/rfc/rfc8259>
- JSON Pointer (RFC 6901): <https://www.rfc-editor.org/rfc/rfc6901>
