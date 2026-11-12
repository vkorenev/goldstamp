package goldstamp.jackson3.json;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jayway.jsonpath.PathNotFoundException;
import goldstamp.core.SnapshotException;
import goldstamp.core.VerificationResult;
import goldstamp.core.Verifier;
import goldstamp.core.spi.DocumentTransformer;
import goldstamp.core.spi.Mapping;
import goldstamp.jsonpath.JsonRule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.util.MinimalPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;

class SnapshotWorkflowTest {
  @TempDir Path directory;

  private static final JacksonJson JSON = JacksonJson.defaults();

  private Verifier<JsonNode> baseVerifier;

  @BeforeEach
  void createVerifier() {
    baseVerifier = verifier(JSON);
  }

  private Verifier<JsonNode> verifier(JacksonJson format) {
    return format
        .verifier()
        .approvedDirectory(directory.resolve("approved"))
        .receivedDirectory(directory.resolve("received"))
        .build();
  }

  private static JsonNode json(String text) {
    return JSON.parse(text);
  }

  private static VerificationResult.Missing missing(VerificationResult result) {
    return assertInstanceOf(VerificationResult.Missing.class, result);
  }

  /** Approves the received output, as a developer would after reviewing it. */
  private static void approve(VerificationResult.Missing missing) throws IOException {
    Files.createDirectories(missing.approvedFile().getParent());
    Files.copy(missing.receivedFile(), missing.approvedFile());
  }

  /** Asserts that the snapshot written for a missing approval holds {@code expected}. */
  private static void assertReceived(String expected, VerificationResult result)
      throws IOException {
    assertEquals(json(expected), json(Files.readString(missing(result).receivedFile())));
  }

  @Test
  void missingReceivedManualApprovalMatchingAndDifferentWorkflow() throws IOException {
    var ordersVerifier =
        baseVerifier.prependTransformations(
            List.of(JSON.remove("$.timestamp"), JSON.sort("$.items", "id")));
    String input =
        """
        {"timestamp": 123, "items": [{"id": 2}, {"id": 1}]}""";
    var missing = missing(ordersVerifier.verify("orders", json(input)));
    assertEquals(directory.resolve("approved/orders.approved.json"), missing.approvedFile());
    assertEquals(directory.resolve("received/orders.received.json"), missing.receivedFile());
    assertFalse(Files.exists(missing.approvedFile()));
    assertThat(Files.readString(missing.receivedFile()), not(containsString("timestamp")));
    AssertionError failure =
        assertThrows(
            AssertionError.class, () -> ordersVerifier.assertMatches("orders", json(input)));
    assertThat(failure.getMessage(), containsString(missing.approvedFile().toString()));
    assertThat(failure.getMessage(), containsString(missing.receivedFile().toString()));

    approve(missing);
    String approved = Files.readString(missing.approvedFile());
    assertInstanceOf(
        VerificationResult.Matching.class, ordersVerifier.verify("orders", json(input)));
    assertFalse(Files.exists(missing.receivedFile()));

    String changed =
        """
        {"timestamp": 456, "items": [{"id": 3}, {"id": 1}]}""";
    var different =
        assertInstanceOf(
            VerificationResult.Different.class, ordersVerifier.verify("orders", json(changed)));
    assertThat(different.differences(), containsString("\"id\": 3"));
    assertTrue(Files.exists(different.receivedFile()));
    assertEquals(approved, Files.readString(missing.approvedFile()));
    ordersVerifier.assertMatches("orders", json(input));
    assertFalse(Files.exists(missing.receivedFile()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void customTransformersAndRulesComposeWithoutMutatingTheCallersDocument(boolean customMapper)
      throws IOException {
    var format = customMapper ? JacksonJson.of(new JsonMapper()) : JSON;
    var snapshots =
        format
            .verifier()
            .approvedDirectory(directory)
            .receivedDirectory(directory)
            .with(
                DocumentTransformer.inPlace(
                    document -> {
                      ((ObjectNode) document).remove("secret");
                      return document;
                    },
                    format::toTree),
                format.remove("$.timestamp"))
            .build()
            .prependTransformations(format.sort("$.items"));
    JsonNode input =
        json(
            """
            {"secret": "hidden", "timestamp": 123, "items": [2, 1]}""");
    JsonNode original = input.deepCopy();
    var missing = missing(snapshots.verify("chain", input));
    assertReceived(
        """
        {"items": [1, 2]}""",
        missing);
    approve(missing);
    snapshots.assertMatches("chain", input);
    assertEquals(original, input);
  }

  record Item(@JsonProperty("item_id") int id, String displayName, @JsonIgnore boolean temporary) {}

  record Order(List<Item> items, String requestId) {}

  @Test
  void pojosAreConvertedWithTheFormatsMapperAndItsAnnotations() throws IOException {
    // The same mapper converts the POJO and parses rule paths, so rules see snake_case names.
    var mapper =
        JacksonJson.defaultMapper()
            .rebuild()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    var format = JacksonJson.of(mapper);
    var trees =
        verifier(format)
            .prependTransformations(
                List.of(
                    format.replace("$.request_id", "<request-id>"),
                    format.sort("$.items", "item_id")));
    Verifier<Order> snapshots = trees.contramap(format.mapping());
    var order = new Order(List.of(new Item(2, "Two", true), new Item(1, "One", false)), "req-7f3a");

    var missing = missing(snapshots.verify("pojo", order));
    assertReceived(
        """
        {"items": [{"item_id": 1, "display_name": "One"}, {"item_id": 2, "display_name": "Two"}],
         "request_id": "<request-id>"}""",
        missing);
    approve(missing);
    snapshots.assertMatches("pojo", new Order(order.items(), "req-other"));
    // A JsonNode with the same content matches too.
    trees.assertMatches("pojo", format.toTree(order));
    assertInstanceOf(
        VerificationResult.Different.class,
        snapshots.verify("pojo", new Order(List.of(new Item(3, "Three", false)), "req-7f3a")));
  }

  @Test
  void inlineFiltersNestedSortKeysAndInferredComparatorsWorkAsDocumented() throws IOException {
    var snapshots =
        baseVerifier.prependTransformations(
            List.of(
                JSON.remove("$..items[?(@.temporary == true)]"),
                JSON.sort("$.items", Comparator.comparing(n -> n.get("name").asString())),
                JSON.sort("$.people", "name.last", "name.first")));
    assertReceived(
        """
        {"items": [{"name": "a", "temporary": false}, {"name": "b"}],
         "people": [{"name": {"first": "Bob", "last": "Ash"}},
                    {"name": {"first": "Amy", "last": "Poe"}}]}""",
        snapshots.verify(
            "filters",
            json(
                """
                {"items": [{"name": "b"}, {"name": "x", "temporary": true},
                           {"name": "a", "temporary": false}],
                 "people": [{"name": {"first": "Amy", "last": "Poe"}},
                            {"name": {"first": "Bob", "last": "Ash"}}]}""")));
  }

  @Test
  void parseReadsJsonTextAndRejectsInvalidText() throws IOException {
    String body =
        """
        {"a": 1}""";
    assertReceived(body, baseVerifier.verify("t", JSON.parse(body)));
    assertThrows(StreamReadException.class, () -> JSON.parse("oops"));
    assertThrows(IllegalArgumentException.class, () -> JSON.parse(" "));
    assertThrows(JacksonException.class, () -> JSON.parse("{} trailing"));
  }

  @Test
  void verifyOwnedLetsRulesModifyTheTreeThatWasHandedOver() throws IOException {
    var removing = baseVerifier.prependTransformations(JSON.remove("$.secret"));
    JsonNode kept =
        json(
            """
        {"a": 1, "secret": 2}""");
    JsonNode handedOver = kept.deepCopy();

    assertReceived(
        """
        {"a": 1}""",
        removing.verify("kept", kept));
    assertEquals(
        json(
            """
        {"a": 1, "secret": 2}"""),
        kept);
    assertReceived(
        """
        {"a": 1}""",
        removing.verifyOwned("owned", handedOver));
    assertEquals(
        json(
            """
        {"a": 1}"""),
        handedOver);
  }

  @Test
  void jsonTextIsVerifiedWithAParsingMapping() throws IOException {
    Verifier<String> bodies = baseVerifier.contramap(Mapping.fresh(JSON::parse));
    assertReceived(
        """
        {"a": 1}""",
        bodies.verify(
            "text",
            """
        {"a":1}"""));
  }

  private static JsonRule<JsonNode> rule(String operation, String selector) {
    return switch (operation) {
      case "remove" -> JSON.remove(selector);
      case "replace" -> JSON.replace(selector, null);
      case "transform" -> JSON.transform(selector, node -> node);
      case "sort" -> JSON.sort(selector);
      default -> throw new IllegalArgumentException(operation);
    };
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
      remove,    $.absent
      remove,    $..absent
      replace,   $.absent
      replace,   $..absent
      transform, $.absent
      transform, $..absent
      sort,      $.absent
      sort,      $..absent
      """)
  void unmatchedRulesFailUnlessOptional(String operation, String selector) throws IOException {
    String input =
        """
        {"items": [{}, {}]}""";
    var rule = rule(operation, selector);
    var failure =
        assertThrows(
            SnapshotException.class,
            () -> baseVerifier.prependTransformations(rule).verify("required", json(input)));
    assertEquals("Rule " + rule + " matched no values", failure.getMessage());
    assertInstanceOf(PathNotFoundException.class, failure.getCause());
    assertFalse(Files.exists(directory.resolve("received/required.received.json")));

    assertReceived(
        input,
        baseVerifier.prependTransformations(rule.optional()).verify("optional", json(input)));
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
      remove,    $.items.length()
      replace,   $.items.length()
      transform, $.items.length()
      sort,      $.length()
      remove,    $
      remove,    $[
      """)
  void invalidSelectorsAreRejectedWhenTheRuleIsCreated(String operation, String selector) {
    assertThrows(IllegalArgumentException.class, () -> rule(operation, selector));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
      sort    | $.a           | {"a": 1}  | expected an array
      remove  | $..[?(@.id)]  | {"id": 1} | cannot modify
      replace | $..[?(@.id)]  | {"id": 1} | cannot modify
      """)
  void rulesThatCannotApplyFailNamingTheRuleEvenWhenOptional(
      String operation, String selector, String input, String reason) {
    // The filter rows also select the root, which JsonPath cannot modify.
    var rule = rule(operation, selector).optional();
    var failure = assertThrows(SnapshotException.class, () -> rule.apply(json(input), true));
    assertThat(failure.getMessage(), stringContainsInOrder("Rule " + rule, reason));
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
      $.items, false
      $,       false
      $.items, true
      $,       true
      """)
  void optionalTransformsPropagateFailures(String selector, boolean pathNotFound) {
    RuntimeException expected =
        pathNotFound
            ? new PathNotFoundException("inside transform")
            : new IllegalStateException("inside transform");
    var failing =
        baseVerifier.prependTransformations(
            JSON.transform(
                    selector,
                    node -> {
                      throw expected;
                    })
                .optional());
    var failure =
        assertThrows(
            expected.getClass(),
            () ->
                failing.verify(
                    "transform",
                    json(
                        """
                        {"items": [1]}""")));
    assertSame(expected, failure);
  }

  @Test
  void removingMultipleIndexesKeepsTheRemainingElementsInOrder() throws IOException {
    assertReceived(
        """
        {"items": [1, 4]}""",
        baseVerifier
            .prependTransformations(JSON.remove("$.items[0,2,3]"))
            .verify(
                "indexes",
                json(
                    """
                    {"items": [0, 1, 2, 3, 4]}""")));
  }

  @Test
  void transformRulesReplaceMatchedValuesWithoutMutatingTheCallersDocument() throws IOException {
    var uppercasing =
        JSON.transform("$.items[*].name", node -> JSON.toTree(node.asString().toUpperCase()));
    JsonNode input =
        json(
            """
            {"items": [{"name": "a"}, {"name": "b"}]}""");
    JsonNode original = input.deepCopy();
    assertReceived(
        """
        {"items": [{"name": "A"}, {"name": "B"}]}""",
        baseVerifier.prependTransformations(uppercasing).verify("mapped", input));
    assertEquals(original, input);

    var nullReturning = JSON.transform("$.items", node -> null);
    var failure =
        assertThrows(
            NullPointerException.class,
            () -> baseVerifier.prependTransformations(nullReturning).verify("bad", input));
    assertEquals("Rule " + nullReturning + " returned null", failure.getMessage());
  }

  @Test
  void replacementValuesAreConvertedOnceAndCopiedPerMatch() throws IOException {
    var meta = new HashMap<String, Object>(Map.of("n", "x"));
    var price = DecimalNode.valueOf(new BigDecimal("1.10"));
    var snapshots =
        baseVerifier.prependTransformations(
            List.of(
                JSON.replace("$.items[*].meta", meta),
                JSON.replace("$.price", price),
                JSON.replace("$.token", null),
                JSON.transform(
                    "$.items[0].meta",
                    node -> {
                      ((ObjectNode) node).put("n", node.get("n").asString() + "!");
                      return node;
                    })));
    meta.put("n", "changed"); // Values are converted when the rule is created.
    JsonNode input =
        json(
            """
            {"price": 0, "token": "abc", "items": [{"meta": 1}, {"meta": 2}]}""");
    for (String id : List.of("first", "second")) { // Reused rules must not alter their value.
      var result = missing(snapshots.verify(id, input));
      assertThat(Files.readString(result.receivedFile()), containsString("\"price\": 1.10"));
      assertReceived(
          """
          {"price": 1.10, "token": null, "items": [{"meta": {"n": "x!"}}, {"meta": {"n": "x"}}]}""",
          result);
    }
  }

  @Test
  void writesSortedKeysWithGoldStampsLayoutAndCustomizedDefaultMappersKeepTheirOtherDefaults()
      throws IOException {
    JsonNode document =
        json(
            """
            {"z": 1, "items": [{"id": 2}, {"id": 1}]}""");
    assertEquals(
        """
        {
          "items": [
            {
              "id": 2
            },
            {
              "id": 1
            }
          ],
          "z": 1
        }
        """,
        JSON.serialize(document));

    var compact =
        JacksonJson.of(
            JacksonJson.defaultMapper()
                .rebuild()
                .defaultPrettyPrinter(new MinimalPrettyPrinter())
                .build());
    var result = missing(verifier(compact).verify("compact", document));
    assertEquals(
        """
        {"items":[{"id":2},{"id":1}],"z":1}
        """,
        Files.readString(result.receivedFile()));
  }

  @Test
  void sortByKeyBreaksTiesOnTheWholeElementWhateverTheInputOrder() throws IOException {
    var sorting = baseVerifier.prependTransformations(JSON.sort("$.items", "id"));
    var expected =
        """
        {"items": [{"id": 0}, {"id": 1, "n": "a"}, {"id": 1, "n": "b"}]}""";
    assertReceived(
        expected,
        sorting.verify(
            "first",
            json(
                """
                {"items": [{"id": 1, "n": "b"}, {"id": 0}, {"id": 1, "n": "a"}]}""")));
    assertReceived(
        expected,
        sorting.verify(
            "second",
            json(
                """
                {"items": [{"n": "a", "id": 1}, {"id": 1, "n": "b"}, {"id": 0}]}""")));
  }

  @Test
  void sortKeysUseNestedAndMultipleKeyPathsRelativeToEachElement() throws IOException {
    var sorting =
        baseVerifier.prependTransformations(
            JSON.sort("$.people", "$.name.last", "@.name.first", "age"));
    assertReceived(
        """
        {"people": [
          {"name": {"first": "Bob", "last": "Ash"}, "age": 5},
          {"name": {"first": "Amy", "last": "Poe"}, "age": 3},
          {"name": {"first": "Amy", "last": "Poe"}, "age": 9},
          {"name": {"first": "Zed", "last": "Poe"}, "age": 2}]}""",
        sorting.verify(
            "people",
            json(
                """
                {"people": [
                  {"name": {"first": "Zed", "last": "Poe"}, "age": 2},
                  {"name": {"first": "Amy", "last": "Poe"}, "age": 9},
                  {"name": {"first": "Amy", "last": "Poe"}, "age": 3},
                  {"name": {"first": "Bob", "last": "Ash"}, "age": 5}]}""")));
  }

  @Test
  void sortKeysMustExistAndBeSingleValuedPaths() {
    var sorting = baseVerifier.prependTransformations(JSON.sort("$.people", "name").optional());
    SnapshotException missingKey =
        assertThrows(
            SnapshotException.class,
            () ->
                sorting.verify(
                    "bad",
                    json(
                        """
                        {"people": [{"age": 1}, {"age": 2}]}""")));
    assertThat(missingKey.getMessage(), containsString("sort key $['name']"));
    assertThrows(IllegalArgumentException.class, () -> JSON.sort("$.people", "$.tags[*]"));
    assertThrows(IllegalArgumentException.class, () -> JSON.sort("$.people", "$.tags.length()"));
  }

  @Test
  void sortWithComparatorReplacesTheDefaultKeyOrderAndTiesFallBackToIt() throws IOException {
    var caseInsensitive =
        baseVerifier.prependTransformations(
            JSON.sort("$.names", Comparator.comparing((JsonNode n) -> n.asString().toLowerCase())));
    assertReceived(
        """
        {"names": ["Amy", "amy", "bob", "Carl"]}""",
        caseInsensitive.verify(
            "folded",
            json(
                """
                {"names": ["bob", "amy", "Carl", "Amy"]}""")));

    var expected = new IllegalStateException("inside comparator");
    var failing =
        baseVerifier.prependTransformations(
            JSON.sort(
                "$.names",
                (a, b) -> {
                  throw expected;
                }));
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                failing.verify(
                    "bad",
                    json(
                        """
                        {"names": [1, 2]}""")));
    assertSame(expected, failure);
  }
}
