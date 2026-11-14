package goldstamp.jsonpath;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import goldstamp.core.Verifier;
import goldstamp.core.spi.Mapping;
import goldstamp.core.spi.SnapshotFormat;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A snapshot format for a JSON tree model that also creates JsonPath {@link JsonRule}s over it. Use
 * rules only with the format that created them.
 *
 * <p>To support a JSON library, subclass it with the library's JsonPath {@link JsonProvider} and
 * {@link MappingProvider}, and implement {@link #toTree}, {@link #extension}, {@link #serialize}
 * and {@link #deserialize}. To verify POJOs or other values, map them with {@code
 * verifier.prependTransformation(format.treeMapping())}.
 *
 * @param <T> the tree-node type of the provider
 */
public abstract class JsonTreeFormat<T> implements SnapshotFormat<T> {
  private final Configuration configuration;
  private final JsonOrdering<T> ordering;

  protected JsonTreeFormat(JsonProvider jsonProvider, MappingProvider mappingProvider) {
    this.configuration =
        Configuration.builder()
            .jsonProvider(Objects.requireNonNull(jsonProvider, "jsonProvider"))
            .mappingProvider(Objects.requireNonNull(mappingProvider, "mappingProvider"))
            .build();
    this.ordering = new JsonOrdering<>(jsonProvider);
  }

  /**
   * Converts any Java value into a fresh tree that is safe to mutate: a tree node is deep-copied;
   * POJOs, maps, collections, strings, numbers and booleans become the equivalent JSON; {@code
   * null} becomes JSON null. Strings are JSON string values, never parsed; to verify JSON text,
   * read it with {@link #parse}.
   */
  public abstract T toTree(Object value);

  /**
   * A mapping from any Java value to a tree, as {@link #toTree}. Its result is unshared, so a
   * verifier can modify it without copying: {@code
   * snapshots.prependTransformation(json.treeMapping())}.
   */
  public final Mapping<Object, T> treeMapping() {
    return Mapping.unshared(this::toTree);
  }

  /** Parses JSON text into a tree, as {@link #deserialize} does. */
  public final T parse(String json) {
    return deserialize(Objects.requireNonNull(json, "json"));
  }

  /** Starts a verifier for this format. */
  public final Verifier.Builder<T> verifier() {
    return Verifier.builder(this);
  }

  /** Removes the selected values. The selector may not be the root ({@code $}). */
  public final JsonRule<T> remove(String selector) {
    return new JsonRule<>(this, selector, new JsonRule.Remove<>());
  }

  /**
   * Replaces each selected value with {@code value}, converted with {@link #toTree} now and copied
   * again at every match: {@code replace("$.id", "<id>")} sets a JSON string, {@code null} sets
   * JSON null, and a map or POJO sets the equivalent object.
   */
  public final JsonRule<T> replace(String selector, Object value) {
    return new JsonRule<>(this, selector, new JsonRule.Replace<>(toTree(value)));
  }

  /**
   * Replaces each selected value with the function's non-null result. The function receives the
   * live node and may mutate and return it, or return a new node, e.g. from {@link #toTree}.
   *
   * <p>Subsequent transformations may mutate the result or its descendants. The function must not
   * introduce externally shared mutable state or retain mutable nodes for use outside this
   * verification. Immutable values may be shared.
   */
  public final JsonRule<T> transform(String selector, UnaryOperator<T> function) {
    return new JsonRule<>(
        this, selector, new JsonRule.Transform<>(Objects.requireNonNull(function, "function")));
  }

  /**
   * Sorts the selected arrays by the values at {@code keys} (or by the elements, with no keys),
   * compared with {@link JsonOrdering}. Ties are broken by {@link JsonOrdering} on the whole
   * element, so the result never depends on input order.
   *
   * @param keys definite JsonPaths relative to each element ({@code "id"}, {@code "$.id"} and
   *     {@code "@.id"} are equivalent); every element must have every key
   */
  public final JsonRule<T> sort(String selector, String... keys) {
    return new JsonRule<>(this, selector, JsonRule.Sort.of(keys, ordering));
  }

  /**
   * As {@link #sort(String, String...)}, but compares the keys with {@code keyOrder}. Exceptions it
   * throws propagate unchanged.
   */
  public final JsonRule<T> sort(String selector, Comparator<? super T> keyOrder, String... keys) {
    return new JsonRule<>(
        this, selector, JsonRule.Sort.of(keys, Objects.requireNonNull(keyOrder, "keyOrder")));
  }

  final Configuration configuration() {
    return configuration;
  }

  /**
   * The order {@link #sort} uses by default and for tie-breaks, e.g. {@code sort("$.items",
   * ordering().reversed(), "id")}.
   */
  public final JsonOrdering<T> ordering() {
    return ordering;
  }
}
