package goldstamp.core.text;

import goldstamp.core.SnapshotVerifier;

/**
 * The shortest path to a plain-text {@link SnapshotVerifier}. Each factory returns the plain core
 * builder, so directories and comparators are configured on it directly.
 *
 * <pre>{@code
 * var snapshots = TextSnapshots.builder()
 *     .approvedDirectory(Path.of("src/test/snapshots"))
 *     .build();
 * var result = snapshots.verify("report", reportText);
 * }</pre>
 */
public final class TextSnapshots {
  private TextSnapshots() {}

  /** A builder for {@code .txt} snapshots. */
  public static SnapshotVerifier.Builder<String> builder() {
    return SnapshotVerifier.builder(new TextSnapshotCodec());
  }

  /** A builder for snapshots with the given file extension, e.g. {@code "md"} or {@code "log"}. */
  public static SnapshotVerifier.Builder<String> builder(String extension) {
    return SnapshotVerifier.builder(new TextSnapshotCodec(extension));
  }
}
