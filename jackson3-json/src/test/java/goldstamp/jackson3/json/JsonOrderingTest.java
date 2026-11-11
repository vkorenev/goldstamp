package goldstamp.jackson3.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import goldstamp.jsonpath.JsonOrdering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonOrderingTest {
  /** Parses {@code NaN} and {@code Infinity} too, which plain JSON cannot express. */
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

  private final JsonOrdering<JsonNode> ordering = JacksonJson.of(MAPPER).ordering();

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          types      | [{"a": 1}, [1], "s", 2.5, true, null, 1, false] | [null, false, true, 1, 2.5, "s", [1], {"a": 1}]
          numbers    | [10, 2.5, -7, 3000000000, 1.0, 1, 0.5]          | [-7, 0.5, 1, 1.0, 2.5, 10, 3000000000]
          big ints   | [99999999999999999999, 5, -99999999999999999999] | [-99999999999999999999, 5, 99999999999999999999]
          non-finite | [NaN, Infinity, 0, -Infinity, 1e300]            | [-Infinity, 0, 1e300, Infinity, NaN]
          strings    | ["b", "B", "", "ab", "a"]                       | ["", "B", "a", "ab", "b"]
          arrays     | [[2], [1, 2], [], [1], [[1]]]                   | [[], [1], [1, 2], [2], [[1]]]
          objects    | [{"b": 1}, {"a": 2}, {"a": 1, "b": 0}, {"a": 1}] | [{"a": 1}, {"a": 1, "b": 0}, {"a": 2}, {"b": 1}]
          """)
  void sortsValues(String name, String unsorted, String expected) {
    List<JsonNode> actual = new ArrayList<>(MAPPER.readTree(unsorted).values());
    actual.sort(ordering);
    assertEquals(MAPPER.readTree(expected), MAPPER.createArrayNode().addAll(actual));
  }
}
