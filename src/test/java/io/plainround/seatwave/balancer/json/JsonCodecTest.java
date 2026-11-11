package io.plainround.seatwave.balancer.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

  private final JsonCodec json = new JsonCodec();

  @Test
  void bindsABodyThatMatchesTheRecordExactly() {
    Result<Ticket, JsonCodec.BindingProblem> read =
        json.read("{\"count\":2,\"name\":\"arena\"}", Ticket.class);

    assertThat(read.discardError()).contains(new Ticket(2, "arena"));
  }

  @Test
  void refusesAnEmptyBodyAndABodyThatIsNotAnObject() {
    assertThat(problem("")).isEqualTo(whole("the body is empty; send a JSON object"));
    assertThat(problem("[1]")).isEqualTo(whole("the body must be a JSON object"));
  }

  @Test
  void refusesDataAfterTheObjectWhetherOrNotItIsJson() {
    assertThat(problem("{\"count\":2,\"name\":\"a\"} {}"))
        .isEqualTo(whole("the body has data after the JSON object"));
    assertThat(problem("{\"count\":2,\"name\":\"a\"} x"))
        .isEqualTo(whole("the body has data after the JSON object"));
  }

  @Test
  void namesAFieldThatAppearsTwice() {
    assertThat(problem("{\"count\":1,\"count\":2,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "appears more than once"));
  }

  @Test
  void namesAnUnknownFieldAWrongTypeANullNumberAndANumberOutOfRange() {
    assertThat(problem("{\"count\":1,\"name\":\"a\",\"a/b\":1}"))
        .isEqualTo(new JsonCodec.BindingProblem("/a~1b", "is not a field of this request"));
    assertThat(problem("{\"count\":\"1\",\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":1,\"name\":5}"))
        .isEqualTo(new JsonCodec.BindingProblem("/name", "has the wrong type"));
    assertThat(problem("{\"count\":null,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "has the wrong type"));
    assertThat(problem("{\"count\":99999999999999999999,\"name\":\"a\"}"))
        .isEqualTo(new JsonCodec.BindingProblem("/count", "is out of range for this field"));
  }

  @Test
  void callsMalformedTextInvalidJson() {
    assertThat(problem("{\"count\":")).isEqualTo(whole("the body is not valid JSON"));
    assertThat(problem("{count:1}")).isEqualTo(whole("the body is not valid JSON"));
  }

  @Test
  void writesARecordAndRefusesAValueThatIsNotWritable() {
    assertThat(json.write(new Ticket(3, "x"))).isEqualTo("{\"count\":3,\"name\":\"x\"}");
    assertThatThrownBy(() -> json.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not writable as JSON");
  }

  private JsonCodec.BindingProblem problem(String body) {
    return switch (json.read(body, Ticket.class)) {
      case Result.Success<Ticket, JsonCodec.BindingProblem>(Ticket bound) ->
          throw new AssertionError("bound " + bound + " from " + body);
      case Result.Error<Ticket, JsonCodec.BindingProblem>(JsonCodec.BindingProblem refused) ->
          refused;
    };
  }

  private static JsonCodec.BindingProblem whole(String detail) {
    return new JsonCodec.BindingProblem("", detail);
  }

  /** A record with one primitive and one text field, the two shapes the codec checks. */
  record Ticket(int count, String name) {}
}
