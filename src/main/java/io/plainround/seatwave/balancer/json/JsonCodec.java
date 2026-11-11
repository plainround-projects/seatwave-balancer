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
