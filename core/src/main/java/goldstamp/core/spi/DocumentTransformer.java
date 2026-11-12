package goldstamp.core.spi;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Transforms an incoming document. Never applied to approved snapshots.
 *
 * <p>A transformer is a {@link Mapping} from a document to a document. Given {@code canModifySource
 * == false} it must leave its argument unchanged, copying it first if it needs to modify it; given
 * {@code true} it may modify the argument and return it or a replacement root. It must ensure any
 * nodes it introduces are safe for subsequent transformers to mutate, and reports how its result is
 * shared ({@link #resultSharing()}) truthfully: a result that may reference a constant or a cache
 * is {@link Mapping.Sharing#SHARED}.
 *
 * <p>Create one from a function with {@link #inPlace}, {@link #pure}, {@link #derived} or {@link
 * #fresh}; implement the interface directly to treat both cases yourself, as JSON rules do.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentTransformer<T> extends Mapping<T, T> {
  /**
   * A transformer that never modifies its argument. {@code function} must return a non-null
   * document, which may share nodes with its argument or other objects, so the result is treated as
   * shared and copied before anything modifies it.
   */
  static <T> DocumentTransformer<T> pure(UnaryOperator<T> function) {
    return of(function, Sharing.SHARED);
  }

  /**
   * A transformer that never modifies its argument and returns it, or a document built from its
   * nodes, such as a wrapper, referenced by nothing else. The result may then be modified exactly
   * when the argument may. If {@code function} returns an object that something else references,
   * that object will be modified later.
   */
  static <T> DocumentTransformer<T> derived(UnaryOperator<T> function) {
    return of(function, Sharing.WITH_SOURCE);
  }

  /**
   * A transformer that never modifies its argument and returns a new object that shares nothing
   * with its argument or anything else, so that the result need not be copied. If {@code function}
   * returns a shared or cached object, that object will be modified later.
   */
  static <T> DocumentTransformer<T> fresh(UnaryOperator<T> function) {
    return of(function, Sharing.UNSHARED);
  }

  /**
   * A transformer that may modify its argument: {@code function} receives the argument itself, or
   * {@code copier}'s copy when the caller did not give the argument up. Both {@code function} and
   * {@code copier} must return non-null documents, and {@code function}'s result must be safe to
   * mutate: if it returns a shared object, that object will be modified later.
   */
  static <T> DocumentTransformer<T> inPlace(UnaryOperator<T> function, UnaryOperator<T> copier) {
    Objects.requireNonNull(function, "function");
    Objects.requireNonNull(copier, "copier");
    return new DocumentTransformer<>() {
      @Override
      public T apply(T document, boolean canModifySource) {
        return function.apply(
            canModifySource
                ? document
                : Objects.requireNonNull(copier.apply(document), "Copier returned null"));
      }

      @Override
      public Sharing resultSharing() {
        return Sharing.UNSHARED;
      }
    };
  }

  private static <T> DocumentTransformer<T> of(UnaryOperator<T> function, Sharing sharing) {
    Objects.requireNonNull(function, "function");
    return new DocumentTransformer<>() {
      @Override
      public T apply(T document, boolean canModifySource) {
        return function.apply(document);
      }

      @Override
      public Sharing resultSharing() {
        return sharing;
      }

      @Override
      public String toString() {
        return function.toString();
      }
    };
  }
}
