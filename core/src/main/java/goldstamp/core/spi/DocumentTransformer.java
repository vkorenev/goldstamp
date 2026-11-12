package goldstamp.core.spi;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Transforms an incoming document. Never applied to approved snapshots.
 *
 * <p>In a verifier, transformers run in order, each receiving the preceding result. Before a
 * transformer whose {@link #effect()} is {@link Effect#IN_PLACE} receives a document that the
 * verifier does not own, the verifier replaces it with {@link SnapshotFormat#copy its own copy}, so
 * the caller's objects are never modified. A transformer may return a replacement root, and must
 * ensure any nodes it introduces are safe for subsequent transformers to mutate. Outside a
 * verifier, callers must copy first if their document needs to stay unchanged.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentTransformer<T> {
  /** What a transformer does to its argument, and what it promises about its result. */
  enum Effect {
    /**
     * May mutate its argument. The result is the argument or a replacement built from private
     * nodes.
     */
    IN_PLACE,
    /** Never mutates its argument. The result may be the argument or share nodes with anything. */
    PURE,
    /**
     * Never mutates its argument. The result is a new object: modifying it changes neither the
     * argument nor anything else.
     */
    PURE_FRESH
  }

  /** Returns a non-null document, which may be a replacement root. */
  T apply(T document);

  /**
   * How {@link #apply} treats its argument and result. Returns {@link Effect#IN_PLACE} unless
   * overridden, which is always safe; the other values let a verifier skip copies. See {@link
   * #pure} and {@link #fresh}.
   */
  default Effect effect() {
    return Effect.IN_PLACE;
  }

  /**
   * A transformer that never mutates its argument, so the verifier need not copy the document for
   * it. {@code function} must return a non-null document, which may share nodes with its argument
   * or with other objects; the verifier copies it again before any in-place transformer.
   */
  static <T> DocumentTransformer<T> pure(UnaryOperator<T> function) {
    return of(function, Effect.PURE);
  }

  /**
   * As {@link #pure}, but {@code function} must return a new object that shares no nodes with its
   * argument or with anything else, so the verifier need not copy the result either.
   */
  static <T> DocumentTransformer<T> fresh(UnaryOperator<T> function) {
    return of(function, Effect.PURE_FRESH);
  }

  private static <T> DocumentTransformer<T> of(UnaryOperator<T> function, Effect effect) {
    Objects.requireNonNull(function, "function");
    return new DocumentTransformer<>() {
      @Override
      public T apply(T document) {
        return function.apply(document);
      }

      @Override
      public Effect effect() {
        return effect;
      }
    };
  }
}
