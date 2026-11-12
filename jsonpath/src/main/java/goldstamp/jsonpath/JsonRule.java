package goldstamp.jsonpath;

import com.jayway.jsonpath.InvalidModificationException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JsonProvider;
import goldstamp.core.SnapshotException;
import goldstamp.core.spi.DocumentTransformer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * An immutable transformer applying one action to every value a JsonPath selector matches. Create
 * rules with a {@link JsonTreeFormat}; an invalid selector or sort key, a function path or removing
 * the root throws {@link IllegalArgumentException} at creation. The selector must match at least
 * one value unless the rule is {@link #optional()}; a rule that cannot apply throws {@link
 * SnapshotException} naming the rule.
 *
 * <p>{@link #apply(Object, boolean) apply(tree, true)} mutates the given tree; with {@code false}
 * it copies the tree first, so a verifier never modifies the caller's tree.
 *
 * @param <T> the tree-node type of the format
 */
public final class JsonRule<T> implements DocumentTransformer<T> {
  private final JsonTreeFormat<T> format;
  private final JsonPath selector;
  private final Action<T> action;
  private final boolean optional;

  JsonRule(JsonTreeFormat<T> format, String selector, Action<T> action) {
    this(format, compile(selector, "selector"), action, false);
    if (action instanceof Remove && selectsRoot()) {
      throw new IllegalArgumentException("Cannot remove the document root");
    }
  }

  private JsonRule(
      JsonTreeFormat<T> format, JsonPath selector, Action<T> action, boolean optional) {
    this.format = format;
    this.selector = selector;
    this.action = action;
    this.optional = optional;
  }

  /** The same rule, allowing the selector to match nothing; invalid matched values still fail. */
  public JsonRule<T> optional() {
    return new JsonRule<>(format, selector, action, true);
  }

  @Override
  public Sharing resultSharing() {
    return Sharing.UNSHARED;
  }

  @Override
  public T apply(T document, boolean canModifySource) {
    Objects.requireNonNull(document, "document");
    T target = canModifySource ? document : format.toTree(document);
    try {
      return applyAction(target);
    } catch (InvalidModificationException failure) {
      // E.g. a filter or deep scan that also selects the root, which JsonPath cannot modify.
      throw new SnapshotException("Rule " + this + " cannot modify a matched value", failure);
    }
  }

  private T applyAction(T document) {
    if (action instanceof Remove) {
      try {
        return selector.delete(document, format.configuration());
      } catch (PathNotFoundException missing) {
        return noMatch(document, missing);
      }
    }
    if (action instanceof Replace<T> replace) {
      return map(document, ignored -> format.toTree(replace.value()));
    }
    if (action instanceof Transform<T> transform) {
      return map(
          document,
          value ->
              Objects.requireNonNull(
                  transform.function().apply(value), () -> "Rule " + this + " returned null"));
    }
    if (action instanceof Sort<T> sort) {
      return map(document, value -> sortArray(value, sort));
    }
    throw new AssertionError("Unknown action " + action);
  }

  @Override
  public String toString() {
    return action.name()
        + "("
        + selector.getPath()
        + action.detail()
        + ")"
        + (optional ? ".optional()" : "");
  }

  private T map(T root, Function<T, T> transform) {
    // JsonPath cannot replace the root through map.
    if (selectsRoot()) {
      return transform.apply(root);
    }
    boolean[] callbackStarted = {false};
    try {
      return selector.map(
          root,
          (value, config) -> {
            callbackStarted[0] = true;
            return transform.apply(cast(value));
          },
          format.configuration());
    } catch (PathNotFoundException missing) {
      // An optional selector must not hide a failure inside a matched value's transformation.
      if (callbackStarted[0]) {
        throw missing;
      }
      return noMatch(root, missing);
    }
  }

  private boolean selectsRoot() {
    return selector.getPath().equals("$");
  }

  private T noMatch(T root, PathNotFoundException missing) {
    if (optional) {
      return root;
    }
    throw new SnapshotException("Rule " + this + " matched no values", missing);
  }

  private record SortEntry<T>(T element, List<T> key) {}

  /** Sorts an array by keys extracted once per element; ties fall back to {@link JsonOrdering}. */
  private T sortArray(T target, Sort<T> sort) {
    JsonProvider provider = format.configuration().jsonProvider();
    if (!provider.isArray(target)) {
      throw new SnapshotException("Rule " + this + " expected an array, got " + target);
    }
    int length = provider.length(target);
    List<SortEntry<T>> entries = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      T element = cast(provider.getArrayIndex(target, i));
      entries.add(new SortEntry<>(element, sortKeys(element, sort.keys())));
    }
    JsonOrdering<T> ordering = format.ordering();
    entries.sort(
        (left, right) -> {
          for (int i = 0; i < left.key().size(); i++) {
            int result = sort.keyOrder().compare(left.key().get(i), right.key().get(i));
            if (result != 0) {
              return result;
            }
          }
          return ordering.compare(left.element(), right.element());
        });
    for (int i = 0; i < entries.size(); i++) {
      provider.setArrayIndex(target, i, entries.get(i).element());
    }
    return target;
  }

  /** With no key paths the element is its own key. */
  private List<T> sortKeys(T element, List<JsonPath> keys) {
    if (keys.isEmpty()) {
      return List.of(element);
    }
    List<T> values = new ArrayList<>(keys.size());
    for (JsonPath key : keys) {
      try {
        values.add(cast(key.read(element, format.configuration())));
      } catch (PathNotFoundException missing) {
        throw new SnapshotException(
            "Rule " + this + " found no sort key " + key.getPath() + " in " + element, missing);
      }
    }
    return values;
  }

  private static JsonPath compile(String path, String kind) {
    Objects.requireNonNull(path, kind);
    JsonPath compiled;
    try {
      compiled = JsonPath.compile(path);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(
          "Invalid JsonPath " + kind + " '" + path + "': " + failure.getMessage(), failure);
    }
    // Function paths such as $.a.length() compute a value rather than select a location. The
    // check is textual: the pinned JsonPath version does not expose isFunctionPath().
    if (compiled.getPath().endsWith(")")) {
      throw new IllegalArgumentException(
          "JsonPath " + kind + " must not be a function: " + compiled.getPath());
    }
    return compiled;
  }

  @SuppressWarnings("unchecked")
  private static <T> T cast(Object node) {
    return (T) node;
  }

  /** What a rule does to each matched value. */
  sealed interface Action<T> permits Remove, Replace, Transform, Sort {
    default String name() {
      return getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    default String detail() {
      return "";
    }
  }

  record Remove<T>() implements Action<T> {}

  /** {@code value} is a tree owned by the rule; it is copied at every match and never exposed. */
  record Replace<T>(T value) implements Action<T> {
    @Override
    public String detail() {
      return ", " + value;
    }
  }

  record Transform<T>(UnaryOperator<T> function) implements Action<T> {}

  record Sort<T>(List<JsonPath> keys, Comparator<? super T> keyOrder) implements Action<T> {
    static <T> Sort<T> of(String[] keys, Comparator<? super T> keyOrder) {
      List<JsonPath> compiled = new ArrayList<>(keys.length);
      for (String key : keys) {
        JsonPath path = compile(key, "sort key");
        if (!path.isDefinite()) {
          throw new IllegalArgumentException(
              "JsonPath sort key must be a definite path: " + path.getPath());
        }
        compiled.add(path);
      }
      return new Sort<>(List.copyOf(compiled), keyOrder);
    }

    @Override
    public String detail() {
      return keys.isEmpty() ? "" : " by " + keys.stream().map(JsonPath::getPath).toList();
    }
  }
}
