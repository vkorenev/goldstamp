package goldstamp.core;

import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.Mapping;
import goldstamp.core.spi.SnapshotFormat;
import goldstamp.core.spi.TextComparator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Verifies inputs of type {@code I} against approved snapshots. Start with {@link #builder}, then
 * derive others by prepending transformations: {@link #prependTransformation} adapts another input
 * type, and {@link #prependTransformations} cleans up inputs of the same type. Derived verifiers
 * leave this one unchanged. Concurrent verifications require distinct snapshot IDs and thread-safe
 * formats, transformations and comparators.
 *
 * <p>Every verification writes a {@code *.received.<ext>} file when the snapshot is missing or
 * different, and deletes a stale one when it matches. Approved files are never written.
 *
 * <pre>{@code
 * Verifier<Doc> docs = Verifier.builder(format).with(transformation).build();
 * docs.assertMatches("orders", document);
 * Verifier<Order> orders = docs.prependTransformation(Mapping.unshared(Order::toDocument));
 * orders.assertMatches("order-1", order);
 * }</pre>
 *
 * @param <I> the input type
 */
public interface Verifier<I> {
  /**
   * Starts a verifier for documents of {@code format}. Defaults: approved files in {@code
   * src/test/snapshots/approved}, received files in {@code build/snapshots/received} (both relative
   * to the working directory), no transformations, and {@link LineComparator} text comparison.
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
   * @throws NullPointerException if {@code actual} is null, or a mapping, format, transformation or
   *     comparator returns null
   * @throws SnapshotException if a snapshot file cannot be read, written, deleted or parsed
   * @throws RuntimeException whatever a mapping, format, transformation or comparator throws,
   *     unchanged
   */
  VerificationResult verify(String snapshotId, I actual);

  /**
   * As {@link #verify}, but the caller hands over {@code actual}, which transformations may modify
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
    verify(snapshotId, actual).assertMatches();
  }

  /**
   * Returns a verifier that runs {@code transformation} on each input before this verifier sees it.
   * The transformation may change the input type, such as converting a POJO to a tree. This
   * verifier may modify the result instead of copying it when the transformation's {@linkplain
   * Mapping#resultSharing() result sharing} allows: it is unshared, or shared only with a source
   * that {@link #verifyOwned} handed over. Later calls run first. The transformation must return a
   * non-null value; a null names the transformation and snapshot ID in a {@link
   * NullPointerException}. Create one from a function with {@link Mapping#shared}, {@link
   * Mapping#withSource} or {@link Mapping#unshared}, by how its result is shared.
   */
  default <U> Verifier<U> prependTransformation(Mapping<? super U, ? extends I> transformation) {
    Objects.requireNonNull(transformation, "transformation");
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
                transformation.apply(Objects.requireNonNull(actual, "actual"), handedOver),
                () ->
                    "Transformation "
                        + transformation
                        + " returned null for snapshot '"
                        + snapshotId
                        + "'");
        Mapping.Sharing sharing = transformation.resultSharing();
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
   * Returns a verifier that applies {@code transformations} in order to each input before this
   * verifier sees it. Later calls run first: {@code v.prependTransformations(List.of(a))
   * .prependTransformations(List.of(b))} applies b, then a, then {@code v}'s own steps. Each
   * transformation copies the document before modifying it, unless the caller handed it over and no
   * earlier step may have shared it. A transformation that returns null is named in a {@link
   * NullPointerException} with the snapshot ID.
   */
  default Verifier<I> prependTransformations(List<? extends Mapping<I, I>> transformations) {
    List<? extends Mapping<I, I>> chain = List.copyOf(transformations);
    Verifier<I> result = this;
    for (int i = chain.size() - 1; i >= 0; i--) {
      result = result.prependTransformation(chain.get(i));
    }
    return result;
  }

  /**
   * Configures a {@link Verifier} of documents. Not thread-safe; {@link #build()} snapshots it.
   *
   * @param <T> the document type
   */
  final class Builder<T> {
    private final SnapshotFormat<T> format;
    private ApprovedSnapshots approvedSnapshots =
        new ApprovedSnapshots.Directory(Path.of("src/test/snapshots/approved"));
    private Path receivedDirectory = Path.of("build/snapshots/received");
    private List<Mapping<T, T>> transformations = List.of();
    private SnapshotVerifier.Comparison<T> comparison =
        SnapshotVerifier.textComparison(new LineComparator());

    private Builder(SnapshotFormat<T> format) {
      this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * Where manually approved snapshots live. GoldStamp never writes here. Replaces any earlier
     * approved location.
     */
    public Builder<T> approvedDirectory(Path directory) {
      this.approvedSnapshots = new ApprovedSnapshots.Directory(directory);
      return this;
    }

    /**
     * Reads approved snapshots as classpath resources in {@code anchor}'s package, through its
     * class loader. As {@link #approvedResources(ClassLoader, String)} with the package path as the
     * directory.
     *
     * @throws IllegalArgumentException if {@code anchor} has no class loader
     */
    public Builder<T> approvedResources(Class<?> anchor) {
      ClassLoader loader = Objects.requireNonNull(anchor, "anchor").getClassLoader();
      if (loader == null) {
        throw new IllegalArgumentException("Class " + anchor.getName() + " has no class loader");
      }
      return approvedResources(loader, anchor.getPackageName().replace('.', '/'));
    }

    /**
     * Reads approved snapshots as resources of {@code loader} under {@code directory}, a
     * slash-separated resource path such as {@code "com/example"}; empty means the root, and a
     * trailing slash is ignored. Received files mirror the resource path under the received
     * directory. Replaces any earlier approved location.
     *
     * @throws IllegalArgumentException if {@code directory} starts with {@code /}, has an empty,
     *     {@code .} or {@code ..} segment, a segment ending in a dot or space, or a backslash,
     *     control character or any of {@code : * ? " < > |}
     */
    public Builder<T> approvedResources(ClassLoader loader, String directory) {
      this.approvedSnapshots = new ApprovedSnapshots.Resources(loader, directory);
      return this;
    }

    /** Where {@code *.received.<ext>} files are written on a missing or different snapshot. */
    public Builder<T> receivedDirectory(Path directory) {
      this.receivedDirectory = Objects.requireNonNull(directory, "directory");
      return this;
    }

    /**
     * Appends transformations, applied in order to each incoming document; never to approved
     * snapshots. Each transformation copies the document before modifying it, unless the caller
     * handed it over and no earlier step may have shared it.
     */
    @SafeVarargs
    public final Builder<T> with(Mapping<T, T>... more) {
      var chain = new ArrayList<>(transformations);
      chain.addAll(Arrays.asList(more));
      this.transformations = List.copyOf(chain); // Rejects null elements.
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
     * Creates the verifier, which applies the transformations in order to each input before
     * comparing it.
     */
    public Verifier<T> build() {
      return new SnapshotVerifier<>(
              approvedSnapshots, receivedDirectory.toAbsolutePath().normalize(), format, comparison)
          .prependTransformations(transformations);
    }
  }
}
