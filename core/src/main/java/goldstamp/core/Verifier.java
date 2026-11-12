package goldstamp.core;

import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.DocumentTransformer;
import goldstamp.core.spi.Mapping;
import goldstamp.core.spi.SnapshotFormat;
import goldstamp.core.spi.TextComparator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Verifies inputs of type {@code I} against approved snapshot files. Start with {@link #builder},
 * then derive others: {@link #contramap} for another input type and {@link #prependTransformations}
 * for cleanup before verification. Derived verifiers leave this one unchanged. Concurrent
 * verifications require distinct snapshot IDs and thread-safe formats, transformers and
 * comparators.
 *
 * <p>Every verification writes a {@code *.received.<ext>} file when the snapshot is missing or
 * different, and deletes a stale one when it matches. Approved files are never written.
 *
 * <pre>{@code
 * Verifier<Doc> docs = Verifier.builder(format).with(transformer).build();
 * docs.assertMatches("orders", document);
 * Verifier<Order> orders = docs.contramap(Mapping.fresh(Order::toDocument));
 * orders.assertMatches("order-1", order);
 * }</pre>
 *
 * @param <I> the input type
 */
public interface Verifier<I> {
  /**
   * Starts a verifier for documents of {@code format}. Defaults: approved files in {@code
   * src/test/snapshots/approved}, received files in {@code build/snapshots/received} (both relative
   * to the working directory), no transformers, and {@link LineComparator} text comparison.
   */
  static <T> Builder<T> builder(SnapshotFormat<T> format) {
    return new Builder<>(format);
  }

  /**
   * Verifies {@code actual} without modifying it. A mismatch or missing approval is reported
   * through the returned {@link VerificationResult}, not an exception.
   *
   * @param snapshotId the snapshot's file name stem; letters, digits, {@code .}, {@code _} and
   *     {@code -}, starting with a letter or digit
   * @throws IllegalArgumentException if {@code snapshotId} is not a valid ID
   * @throws NullPointerException if {@code actual} is null, or a mapping, format, transformer or
   *     comparator returns null
   * @throws SnapshotException if a snapshot file cannot be read, written, deleted or parsed
   * @throws RuntimeException whatever a mapping, format, transformer or comparator throws,
   *     unchanged
   */
  VerificationResult verify(String snapshotId, I actual);

  /**
   * As {@link #verify}, but the caller hands over {@code actual}, which transformers may modify
   * instead of copying. Use it for objects built just for the verification. If the verification
   * throws, {@code actual} may be left partly modified.
   */
  VerificationResult verifyOwned(String snapshotId, I actual);

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
   * Returns a verifier of {@code U} that applies {@code mapping} to each input and verifies the
   * result with this verifier, which may modify the result instead of copying it when the mapping's
   * {@linkplain Mapping#resultSharing() result sharing} allows: it is unshared, or shared only with
   * a source that {@link #verifyOwned} handed over. The mapping must return a non-null value; a
   * null names the mapping and snapshot ID in a {@link NullPointerException}. Use {@link
   * Mapping#pure}, {@link Mapping#derived} or {@link Mapping#fresh} for a plain function, by how
   * its result is shared.
   */
  default <U> Verifier<U> contramap(Mapping<? super U, ? extends I> mapping) {
    Objects.requireNonNull(mapping, "mapping");
    Verifier<I> target = this;
    return new Verifier<>() {
      @Override
      public VerificationResult verify(String snapshotId, U actual) {
        return run(snapshotId, actual, false);
      }

      @Override
      public VerificationResult verifyOwned(String snapshotId, U actual) {
        return run(snapshotId, actual, true);
      }

      private VerificationResult run(String snapshotId, U actual, boolean handedOver) {
        I mapped =
            Objects.requireNonNull(
                mapping.apply(Objects.requireNonNull(actual, "actual"), handedOver),
                () -> "Mapping " + mapping + " returned null for snapshot '" + snapshotId + "'");
        Mapping.Sharing sharing = mapping.resultSharing();
        boolean mappedHandedOver =
            sharing == Mapping.Sharing.UNSHARED
                || (sharing == Mapping.Sharing.WITH_SOURCE && handedOver);
        return mappedHandedOver
            ? target.verifyOwned(snapshotId, mapped)
            : target.verify(snapshotId, mapped);
      }
    };
  }

  /**
   * Returns a verifier that applies {@code transformers} in order to each input before this
   * verifier sees it. Later calls run first: {@code v.prependTransformations(a)
   * .prependTransformations(b)} applies b, then a, then {@code v}'s own steps. Each transformer
   * copies the document before modifying it, unless the caller handed it over and no earlier step
   * may have shared it. A transformer that returns null is named in a {@link NullPointerException}
   * with the snapshot ID.
   */
  default Verifier<I> prependTransformations(List<? extends DocumentTransformer<I>> transformers) {
    List<? extends DocumentTransformer<I>> chain = List.copyOf(transformers);
    Verifier<I> result = this;
    for (int i = chain.size() - 1; i >= 0; i--) {
      result = result.contramap(chain.get(i));
    }
    return result;
  }

  /** As {@link #prependTransformations(List)} for a single transformer. */
  default Verifier<I> prependTransformations(DocumentTransformer<I> transformer) {
    return prependTransformations(List.of(transformer));
  }

  /**
   * Configures a {@link Verifier} of documents. Not thread-safe; {@link #build()} snapshots it.
   *
   * @param <T> the document type
   */
  final class Builder<T> {
    private final SnapshotFormat<T> format;
    private Path approvedDirectory = Path.of("src/test/snapshots/approved");
    private Path receivedDirectory = Path.of("build/snapshots/received");
    private List<DocumentTransformer<T>> transformers = List.of();
    private SnapshotVerifier.Comparison<T> comparison =
        SnapshotVerifier.textComparison(new LineComparator());

    private Builder(SnapshotFormat<T> format) {
      this.format = Objects.requireNonNull(format, "format");
    }

    /** Where manually approved snapshots live. GoldStamp never writes here. */
    public Builder<T> approvedDirectory(Path directory) {
      this.approvedDirectory = Objects.requireNonNull(directory, "directory");
      return this;
    }

    /** Where {@code *.received.<ext>} files are written on a missing or different snapshot. */
    public Builder<T> receivedDirectory(Path directory) {
      this.receivedDirectory = Objects.requireNonNull(directory, "directory");
      return this;
    }

    /**
     * Appends transformers, applied in order to each incoming document; never to approved
     * snapshots. Each transformer copies the document before modifying it, unless the caller handed
     * it over and no earlier step may have shared it.
     */
    @SafeVarargs
    public final Builder<T> with(DocumentTransformer<T>... more) {
      var chain = new ArrayList<>(transformers);
      chain.addAll(Arrays.asList(more));
      this.transformers = List.copyOf(chain); // Rejects null elements.
      return this;
    }

    /**
     * Compares the parsed approval with the transformed actual document directly. The comparator
     * must account for any representation changes caused by serialization. Replaces any earlier
     * comparator. If the format cannot read an approved file, verifying throws {@link
     * SnapshotException} naming that file.
     */
    public Builder<T> documentComparator(DocumentComparator<? super T> comparator) {
      this.comparison =
          SnapshotVerifier.documentComparison(
              format, Objects.requireNonNull(comparator, "comparator"));
      return this;
    }

    /**
     * Compares approved file text with serialized actual text (the default strategy). Replaces any
     * earlier comparator.
     */
    public Builder<T> textComparator(TextComparator comparator) {
      this.comparison =
          SnapshotVerifier.textComparison(Objects.requireNonNull(comparator, "comparator"));
      return this;
    }

    /**
     * Creates the verifier, which applies the transformers in order to each input before comparing
     * it.
     */
    public Verifier<T> build() {
      return new SnapshotVerifier<>(
              approvedDirectory.toAbsolutePath().normalize(),
              receivedDirectory.toAbsolutePath().normalize(),
              format,
              comparison)
          .prependTransformations(transformers);
    }
  }
}
