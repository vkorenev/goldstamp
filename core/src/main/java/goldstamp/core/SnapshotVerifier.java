package goldstamp.core;

import goldstamp.core.spi.ComparisonResult;
import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.SnapshotFormat;
import goldstamp.core.spi.TextComparator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Verifies documents against approved snapshot files; created by {@link Verifier.Builder#build()}.
 * It compares documents and never modifies them, so {@link #verifyOwned} is the same as {@link
 * #verify}. Each verification writes a {@code *.received.<ext>} file when the snapshot is missing
 * or different, and deletes a stale one when it matches. Approved files are never written.
 *
 * @param <T> the document type
 */
final class SnapshotVerifier<T> implements Verifier<T> {
  /** Compares text or documents without round-tripping either operand through the format. */
  @FunctionalInterface
  interface Comparison<T> {
    ComparisonResult compare(
        Path approved, String approvedText, T actual, Supplier<String> actualText);
  }

  private static final Pattern SNAPSHOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /**
   * Anything but path separators, control characters and characters Windows forbids in names; no
   * leading dot, since the verifier adds the separating one, and no trailing space.
   */
  private static final Pattern EXTENSION =
      Pattern.compile("(?!\\.)[^\\p{Cntrl}/\\\\:*?\"<>|]*[^\\p{Cntrl}/\\\\:*?\"<>| ]");

  private final Path approvedDirectory;
  private final Path receivedDirectory;
  private final SnapshotFormat<T> format;
  private final Comparison<T> comparison;
  private final String extension;

  SnapshotVerifier(
      Path approvedDirectory,
      Path receivedDirectory,
      SnapshotFormat<T> format,
      Comparison<T> comparison) {
    this.approvedDirectory = approvedDirectory;
    this.receivedDirectory = receivedDirectory;
    this.format = format;
    this.comparison = comparison;
    this.extension = requireValidExtension(format.extension());
  }

  @Override
  public VerificationResult verify(String snapshotId, T actual) {
    return verifyDocument(snapshotId, actual);
  }

  @Override
  public VerificationResult verifyOwned(String snapshotId, T actual) {
    return verifyDocument(snapshotId, actual);
  }

  private VerificationResult verifyDocument(String snapshotId, T actual) {
    Objects.requireNonNull(actual, "actual");
    if (snapshotId == null || !SNAPSHOT_ID.matcher(snapshotId).matches()) {
      throw new IllegalArgumentException(
          "Invalid snapshot ID '"
              + snapshotId
              + "': must be a single file name starting with a letter or digit");
    }
    Path approved = approvedDirectory.resolve(snapshotId + ".approved." + extension);
    Path received = receivedDirectory.resolve(snapshotId + ".received." + extension);
    Supplier<String> actualText = memoize(() -> serialize(actual));
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
            comparison.compare(approved, approvedText, actual, actualText), "Comparison result");
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

  private static String requireValidExtension(String extension) {
    Objects.requireNonNull(extension, "extension");
    if (!EXTENSION.matcher(extension).matches()) {
      throw new IllegalArgumentException(
          "Invalid format extension '"
              + extension
              + "': must be non-empty, not start with a dot or end with a space, and contain no path separators,"
              + " control characters or any of : * ? \" < > |");
    }
    return extension;
  }

  static <T> Comparison<T> textComparison(TextComparator comparator) {
    return (approved, approvedText, actual, actualText) ->
        comparator.compare(approvedText, actualText.get());
  }

  static <T> Comparison<T> documentComparison(
      SnapshotFormat<T> format, DocumentComparator<? super T> comparator) {
    return (approved, approvedText, actual, actualText) ->
        comparator.compare(parseApproved(format, approved, approvedText), actual);
  }

  private static <T> T parseApproved(SnapshotFormat<T> format, Path approved, String approvedText) {
    try {
      return Objects.requireNonNull(format.deserialize(approvedText), "Approved document");
    } catch (RuntimeException failure) {
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
    return Objects.requireNonNull(format.serialize(document), "Snapshot text");
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
