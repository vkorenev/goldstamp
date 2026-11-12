package goldstamp.core.spi;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Transforms an incoming document. Never applied to approved snapshots.
 *
 * <p>In a verifier, transformers run in order, each receiving the preceding result. Before the
 * first transformer that {@link #inPlace() works in place}, the verifier replaces the document with
 * {@link SnapshotFormat#copy its own copy}, so the caller's object is never modified. A transformer
 * may mutate its input or return a replacement root, and must ensure any nodes it introduces are
 * safe for subsequent transformers to mutate. Outside a verifier, callers must copy first if their
 * document needs to stay unchanged.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentTransformer<T> {
  /** Returns a non-null document, which may be a replacement root. */
  T apply(T document);

  /**
   * Whether {@link #apply} may mutate its argument. Returns {@code true} unless overridden, which
   * is always safe; a transformer that never mutates its argument returns {@code false} so that the
   * verifier can skip copying the document. See {@link #pure}.
   */
  default boolean inPlace() {
    return true;
  }

  /** A transformer applying this one, then {@code next}, to the result. */
  default DocumentTransformer<T> andThen(DocumentTransformer<T> next) {
    Objects.requireNonNull(next, "next");
    DocumentTransformer<T> first = this;
    boolean inPlace = inPlace() || next.inPlace();
    return new DocumentTransformer<>() {
      @Override
      public T apply(T document) {
        return next.apply(Objects.requireNonNull(first.apply(document), "Transformed document"));
      }

      @Override
      public boolean inPlace() {
        return inPlace;
      }
    };
  }

  /**
   * A transformer that never mutates its argument, so the verifier need not copy the document for
   * it. {@code function} must return a non-null document, which may share nodes with its argument.
   */
  static <T> DocumentTransformer<T> pure(UnaryOperator<T> function) {
    Objects.requireNonNull(function, "function");
    return new DocumentTransformer<>() {
      @Override
      public T apply(T document) {
        return function.apply(document);
      }

      @Override
      public boolean inPlace() {
        return false;
      }
    };
  }
}
