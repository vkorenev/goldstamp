package goldstamp.core;

import goldstamp.core.spi.ComparisonResult;
import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.DocumentTransformer;
import goldstamp.core.spi.SnapshotCodec;
import goldstamp.core.spi.TextComparator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Verifies documents against approved snapshot files. Create one with {@link #builder}, which
 * builds a verifier without transformers; {@link #appendTransformer} derives a new verifier with
 * one more. Configuration is fixed after construction. Concurrent verifications require distinct
 * snapshot IDs and thread-safe codecs, transformers, and comparators. Snapshot IDs are single file
 * names.
 *
 * <p>Each {@link #verify} call writes a {@code *.received.<ext>} file when the snapshot is missing
 * or different, and deletes a stale one when it matches. Approved files are never written.
 *
 * <pre>{@code
 * var base = SnapshotVerifier.builder(codec).build();
 * var snapshots = base.appendTransformer(transformer);
 * snapshots.assertMatches("orders", document);
 * }</pre>
 *
 * @param <T> the document type
 */
public final class SnapshotVerifier<T> {
  /** Compares text or documents without round-tripping either operand through the codec. */
  @FunctionalInterface
  private interface Comparison<T> {
    ComparisonResult compare(
        Path approved, String approvedText, T actual, Supplier<String> actualText);
  }

  private static final Pattern SNAPSHOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final Path approvedDirectory;
  private final Path receivedDirectory;
  private final SnapshotCodec<T> codec;
  private final List<DocumentTransformer<T>> transformers;
  private final Comparison<T> comparison;
  private final String extension;

  private SnapshotVerifier(
      Path approvedDirectory,
      Path receivedDirectory,
      SnapshotCodec<T> codec,
      List<DocumentTransformer<T>> transformers,
      Comparison<T> comparison) {
    this.approvedDirectory = approvedDirectory;
    this.receivedDirectory = receivedDirectory;
    this.codec = codec;
    this.transformers = transformers;
    this.comparison = comparison;
    this.extension = Objects.requireNonNull(codec.extension(), "extension");
  }

  private SnapshotVerifier(Builder<T> builder) {
    this(
        builder.approvedDirectory.toAbsolutePath().normalize(),
        builder.receivedDirectory.toAbsolutePath().normalize(),
        builder.codec,
        List.of(),
        builder.comparison);
  }

  /**
   * Returns a new verifier that applies {@code next} after this verifier's transformers. This
   * verifier is unchanged.
   */
  public SnapshotVerifier<T> appendTransformer(DocumentTransformer<T> next) {
    Objects.requireNonNull(next, "next");
    var chain = new ArrayList<>(transformers);
    chain.add(next);
    return new SnapshotVerifier<>(
        approvedDirectory, receivedDirectory, codec, List.copyOf(chain), comparison);
  }

  /**
   * Starts a verifier for documents persisted by {@code codec}. Defaults: approved files in {@code
   * src/test/snapshots/approved}, received files in {@code build/snapshots/received} (both relative
   * to the working directory, matching the Gradle project layout), no transformers, and {@link
   * LineComparator} text comparison.
   */
  public static <T> Builder<T> builder(SnapshotCodec<T> codec) {
    return new Builder<>(codec);
  }

  /**
   * Configures a {@link SnapshotVerifier}. Not thread-safe; {@link #build()} snapshots it.
   *
   * @param <T> the document type
   */
  public static final class Builder<T> {
    private final SnapshotCodec<T> codec;
    private Path approvedDirectory = Path.of("src/test/snapshots/approved");
    private Path receivedDirectory = Path.of("build/snapshots/received");
    private Comparison<T> comparison = textComparison(new LineComparator());

    private Builder(SnapshotCodec<T> codec) {
      this.codec = Objects.requireNonNull(codec, "codec");
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
     * Compares the parsed approval with the transformed actual document directly. The comparator
     * must account for any representation changes caused by serialization. Replaces any earlier
     * comparator. If the codec cannot read an approved file, {@link #verify} throws {@link
     * SnapshotException} naming that file.
     */
    public Builder<T> documentComparator(DocumentComparator<? super T> comparator) {
      this.comparison = documentComparison(codec, Objects.requireNonNull(comparator, "comparator"));
      return this;
    }

    /**
     * Compares approved file text with serialized actual text (the default strategy). Replaces any
     * earlier comparator.
     */
    public Builder<T> textComparator(TextComparator comparator) {
      this.comparison = textComparison(Objects.requireNonNull(comparator, "comparator"));
      return this;
    }

    /** Creates the verifier. */
    public SnapshotVerifier<T> build() {
      return new SnapshotVerifier<>(this);
    }
  }

  /**
   * Verifies a document and throws if it does not match its approved snapshot. A missing or
   * different snapshot fails with an {@link AssertionError} carrying {@link
   * VerificationResult#diagnostic()}; use {@link #verify} to inspect the result instead.
   *
   * @throws AssertionError if the snapshot is missing or different
   * @see #verify for the other exceptions
   */
  public void assertMatches(String snapshotId, T actual) {
    VerificationResult result = verify(snapshotId, actual);
    if (!result.matches()) {
      throw new AssertionError(result.diagnostic());
    }
  }

  /**
   * Verifies a document. Transformers run on the incoming document only; approved files are never
   * modified. GoldStamp's own rule engines do not mutate {@code actual}.
   *
   * <p>A mismatch or missing approval is reported through the returned {@link VerificationResult},
   * not an exception.
   *
   * @param snapshotId the snapshot's file name stem; letters, digits, {@code .}, {@code _} and
   *     {@code -}, starting with a letter or digit
   * @param actual the document to verify; not modified
   * @throws IllegalArgumentException if {@code snapshotId} is not a valid ID
   * @throws SnapshotException if a snapshot file cannot be read, written, deleted or parsed, or the
   *     codec cannot serialize the document
   * @throws RuntimeException whatever a configured transformer or comparator throws, unchanged
   */
  public VerificationResult verify(String snapshotId, T actual) {
    Objects.requireNonNull(actual, "actual");
    if (snapshotId == null || !SNAPSHOT_ID.matcher(snapshotId).matches()) {
      throw new IllegalArgumentException(
          "Invalid snapshot ID '"
              + snapshotId
              + "': must be a single file name starting with a letter or digit");
    }
    Path approved = approvedDirectory.resolve(snapshotId + ".approved." + extension);
    Path received = receivedDirectory.resolve(snapshotId + ".received." + extension);
    T current = actual;
    for (DocumentTransformer<T> transformer : transformers) {
      current = Objects.requireNonNull(transformer.apply(current), "Transformed document");
    }
    T transformedDocument = current;
    Supplier<String> actualText = memoize(() -> serialize(transformedDocument));
    String approvedText;
    try {
      approvedText = Files.readString(approved);
    } catch (NoSuchFileException missing) {
      writeReceived(received, actualText.get());
      return new VerificationResult.Missing(snapshotId, approved, received);
    } catch (IOException failure) {
      throw new SnapshotException("Cannot read approved snapshot " + approved, failure);
    }
    ComparisonResult result =
        Objects.requireNonNull(
            comparison.compare(approved, approvedText, transformedDocument, actualText),
            "Comparison result");
    if (result instanceof ComparisonResult.Different different) {
      writeReceived(received, actualText.get());
      return new VerificationResult.Different(
          snapshotId, approved, received, different.differences());
    }
    try {
      Files.deleteIfExists(received);
    } catch (IOException failure) {
      throw new SnapshotException("Cannot delete stale received snapshot " + received, failure);
    }
    return new VerificationResult.Matching(snapshotId, approved);
  }

  private static <T> Comparison<T> textComparison(TextComparator comparator) {
    return (approved, approvedText, actual, actualText) ->
        comparator.compare(approvedText, actualText.get());
  }

  private static <T> Comparison<T> documentComparison(
      SnapshotCodec<T> codec, DocumentComparator<? super T> comparator) {
    return (approved, approvedText, actual, actualText) ->
        comparator.compare(parseApproved(codec, approved, approvedText), actual);
  }

  private static <T> T parseApproved(SnapshotCodec<T> codec, Path approved, String approvedText) {
    try {
      return Objects.requireNonNull(codec.deserialize(approvedText), "Approved document");
    } catch (IOException | RuntimeException failure) {
      throw new SnapshotException("Cannot parse approved snapshot " + approved, failure);
    }
  }

  /** Computes the value on first use and reuses it. */
  private static <V> Supplier<V> memoize(Supplier<V> supplier) {
    return new Supplier<>() {
      private V value;

      @Override
      public V get() {
        if (value == null) {
          value = supplier.get();
        }
        return value;
      }
    };
  }

  private String serialize(T document) {
    try {
      return Objects.requireNonNull(codec.serialize(document), "Snapshot text");
    } catch (IOException failure) {
      throw new SnapshotException("Cannot serialize document", failure);
    }
  }

  private static void writeReceived(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          text,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
    } catch (IOException failure) {
      throw new SnapshotException("Cannot write received snapshot " + file, failure);
    }
  }
}
