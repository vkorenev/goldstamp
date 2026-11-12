package goldstamp.jackson3.json;

import com.jayway.jsonpath.spi.json.Jackson3JsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.Jackson3MappingProvider;
import goldstamp.jsonpath.JsonTreeFormat;
import java.util.Objects;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * JSON snapshots with Jackson 3. One {@link JsonMapper} drives everything: converting values to
 * trees ({@link #toTree}: Jackson trees are deep-copied; POJOs, records, maps and other values are
 * converted with {@link JsonMapper#valueToTree}, honoring Jackson annotations), writing and reading
 * snapshot text, and the JsonPath rules this format creates.
 *
 * <pre>{@code
 * var json = JacksonJson.defaults();
 * var snapshots = json.verifier()
 *     .with(json.remove("$.generatedAt"), json.sort("$.items", "id"))
 *     .build();
 * snapshots.contramap(json::toTree).assertMatches("orders", orderResponse);
 * }</pre>
 *
 * <p>Snapshots are pretty-printed with the mapper's default pretty printer and end with a newline.
 */
public final class JacksonJson extends JsonTreeFormat<JsonNode> {
  private static final JsonMapper DEFAULT_MAPPER = createDefaultMapper();
  private static final JacksonJson DEFAULTS = new JacksonJson(DEFAULT_MAPPER);

  private final JsonMapper mapper;

  private JacksonJson(JsonMapper mapper) {
    super(new Jackson3JsonNodeJsonProvider(mapper), new Jackson3MappingProvider(mapper));
    this.mapper = mapper;
  }

  /** The format using {@link #defaultMapper()}. */
  public static JacksonJson defaults() {
    return DEFAULTS;
  }

  /** A format using {@code mapper} unchanged; it decides the snapshot text and POJO conversion. */
  public static JacksonJson of(JsonMapper mapper) {
    return new JacksonJson(Objects.requireNonNull(mapper, "mapper"));
  }

  /**
   * GoldStamp's default mapper: object keys written sorted, trailing content after the JSON value
   * rejected, and two-space indentation, LF newlines and {@code "name": value} spacing. Customize
   * it with {@code rebuild()}, e.g. {@code JacksonJson.of(defaultMapper().rebuild()...build())}.
   */
  public static JsonMapper defaultMapper() {
    return DEFAULT_MAPPER;
  }

  @Override
  public String extension() {
    return "json";
  }

  @Override
  public JsonNode toTree(Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    if (value instanceof JsonNode node) {
      return node.deepCopy();
    }
    return mapper.valueToTree(value);
  }

  @Override
  public JsonNode copy(JsonNode document) {
    return document.deepCopy();
  }

  @Override
  public String serialize(JsonNode document) {
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(requireValue(document))
        + "\n";
  }

  @Override
  public JsonNode deserialize(String snapshot) {
    JsonNode node = mapper.readTree(snapshot);
    if (node == null || node.isMissingNode()) {
      throw new IllegalArgumentException("Expected a JSON value, but the text is empty");
    }
    return node;
  }

  private static JsonNode requireValue(JsonNode node) {
    if (node.isMissingNode()) {
      throw new IllegalArgumentException("Cannot serialize a missing node");
    }
    return node;
  }

  private static JsonMapper createDefaultMapper() {
    DefaultIndenter indent = new DefaultIndenter("  ", "\n");
    Separators separators =
        Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER);
    return JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .defaultPrettyPrinter(
            new DefaultPrettyPrinter(separators)
                .withObjectIndenter(indent)
                .withArrayIndenter(indent))
        .build();
  }
}
