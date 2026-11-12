package goldstamp.core.spi;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Converts an {@code S} into a {@code T} and reports who may modify what. It is the step {@code
 * Verifier.prependTransformation} puts in front of a verifier. A mapping from a type to itself,
 * such as a JSON rule, is a transformation that cleans up an input.
 *
 * <p>The caller says whether it hands over the source ({@code canModifySource}); the mapping says
 * how its result is {@linkplain Sharing shared} ({@link #resultSharing()}). A mapping that would
 * modify the source must copy it first unless it may. Subsequent mappings copy a result before
 * modifying it unless it is unshared, or shared only with a source that was handed over, so a chain
 * of mappings copies only when something might be referenced elsewhere. Neither claim is checked: a
 * false one lets a shared object or the caller's own object be modified without any error.
 *
 * @param <S> the source type
 * @param <T> the result type
 */
@FunctionalInterface
public interface Mapping<S, T> {
  /**
   * Who else may reference mutable state in a result or its descendants. Immutable values may be
   * shared.
   */
  enum Sharing {
    /**
     * Mutable state in the result or its descendants may be referenced elsewhere, such as by a
     * cache or another object. It must be copied before it is modified.
     */
    SHARED,
    /**
     * The result shares mutable state only with the source. It may be modified exactly when the
     * source was handed over.
     */
    WITH_SOURCE,
    /**
     * No mutable state in the result or its descendants is referenced outside this verification,
     * whatever {@code canModifySource} was. That state may be modified.
     */
    UNSHARED
  }

  /**
   * Returns a non-null result.
   *
   * @param canModifySource whether the caller hands over {@code source}. When {@code false}, the
   *     mapping must leave {@code source} unchanged, copying it before any modification. When
   *     {@code true}, an exception may leave {@code source} partly modified.
   */
  T apply(S source, boolean canModifySource);

  /**
   * How the result is shared. Returns {@link Sharing#SHARED} unless overridden, which is always
   * safe: the result is then copied before anything modifies it.
   */
  default Sharing resultSharing() {
    return Sharing.SHARED;
  }

  /**
   * A mapping that never modifies its source. {@code function} must return a non-null value, which
   * may be shared, so it is treated as shared and copied before anything modifies it.
   */
  static <S, T> Mapping<S, T> shared(Function<? super S, ? extends T> function) {
    return of(function, Sharing.SHARED);
  }

  /**
   * A mapping that never modifies its source and returns the source or a value built from parts of
   * it, such as a wrapper. The result and its descendants must share mutable state only with the
   * source; immutable values may be shared freely. {@code function} must return a non-null value.
   */
  static <S, T> Mapping<S, T> withSource(Function<? super S, ? extends T> function) {
    return of(function, Sharing.WITH_SOURCE);
  }

  /**
   * A mapping that never modifies its source and returns a non-null value whose mutable state,
   * including that of its descendants, is shared with neither the source nor anything else.
   * Immutable values may be shared. The function must not retain mutable parts of the result for
   * use outside the verification.
   */
  static <S, T> Mapping<S, T> unshared(Function<? super S, ? extends T> function) {
    return of(function, Sharing.UNSHARED);
  }

  /**
   * A transformation that may modify its argument: {@code function} receives the argument itself,
   * or {@code copier}'s copy when the caller did not hand the argument over. Both {@code function}
   * and {@code copier} must return non-null values. The copier must isolate mutable state from the
   * source. The function must not introduce externally shared mutable state or retain mutable parts
   * of its argument or result for use outside the verification. Immutable values may be shared. Its
   * result is {@link Sharing#UNSHARED}.
   */
  static <T> Mapping<T, T> inPlace(UnaryOperator<T> function, UnaryOperator<T> copier) {
    Objects.requireNonNull(function, "function");
    Objects.requireNonNull(copier, "copier");
    return new Mapping<>() {
      @Override
      public T apply(T source, boolean canModifySource) {
        return function.apply(
            canModifySource
                ? source
                : Objects.requireNonNull(copier.apply(source), "Copier returned null"));
      }

      @Override
      public Sharing resultSharing() {
        return Sharing.UNSHARED;
      }
    };
  }

  private static <S, T> Mapping<S, T> of(
      Function<? super S, ? extends T> function, Sharing sharing) {
    Objects.requireNonNull(function, "function");
    return new Mapping<>() {
      @Override
      public T apply(S source, boolean canModifySource) {
        return function.apply(source);
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
