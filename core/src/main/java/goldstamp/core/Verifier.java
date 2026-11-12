package goldstamp.core;

import java.util.Objects;
import java.util.function.Function;

/**
 * Verifies inputs of type {@code I} against approved snapshot files. {@link SnapshotVerifier}
 * verifies documents; {@link #contramap} derives a verifier for another input type.
 *
 * <pre>{@code
 * Verifier<Order> orders = snapshots.contramap(json::toTree);
 * orders.assertMatches("order-1", order);
 * }</pre>
 *
 * @param <I> the input type
 */
public interface Verifier<I> {
  /**
   * Verifies {@code actual}. A mismatch or missing approval is reported through the returned {@link
   * VerificationResult}, not an exception.
   *
   * @param snapshotId the snapshot's file name stem; letters, digits, {@code .}, {@code _} and
   *     {@code -}, starting with a letter or digit
   * @throws IllegalArgumentException if {@code snapshotId} is not a valid ID
   * @throws NullPointerException if {@code actual} is null, or a mapper, format, transformer or
   *     comparator returns null
   * @throws SnapshotException if a snapshot file cannot be read, written, deleted or parsed
   * @throws RuntimeException whatever a mapper, format, transformer or comparator throws, unchanged
   */
  VerificationResult verify(String snapshotId, I actual);

  /**
   * As {@link #verify}, but throws if the snapshot is missing or different.
   *
   * @throws AssertionError carrying {@link VerificationResult#diagnostic()}
   */
  default void assertMatches(String snapshotId, I actual) {
    VerificationResult result = verify(snapshotId, actual);
    if (!result.matches()) {
      throw new AssertionError(result.diagnostic());
    }
  }

  /**
   * Returns a verifier of {@code U} that applies {@code mapper} to each input and then verifies the
   * result with this verifier. This verifier is unchanged. The mapper must return a non-null value;
   * it may return a shared object, which is copied before anything modifies it.
   */
  default <U> Verifier<U> contramap(Function<? super U, ? extends I> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    Verifier<I> target = this;
    return (snapshotId, actual) ->
        target.verify(
            snapshotId,
            Objects.requireNonNull(
                mapper.apply(Objects.requireNonNull(actual, "actual")),
                () -> "Mapper " + mapper + " returned null for snapshot '" + snapshotId + "'"));
  }
}
