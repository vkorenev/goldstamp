package goldstamp.core.text;

import goldstamp.core.Verifier;
import goldstamp.core.spi.SnapshotFormat;
import java.util.Objects;

/**
 * Plain-text snapshots: the snapshot file holds the document text exactly as given, and reading
 * returns the file text unchanged. Other inputs, such as a {@link CharSequence}, are converted with
 * {@code prependTransformation(Mapping.shared(Object::toString))}.
 *
 * <pre>{@code
 * var snapshots = new TextFormat("log").verifier().build();
 * snapshots.assertMatches("report", reportText);
 * }</pre>
 */
public final class TextFormat implements SnapshotFormat<String> {
  private final String extension;

  /** A format writing {@code .txt} snapshot files. */
  public TextFormat() {
    this("txt");
  }

  /**
   * A format writing snapshot files with the given extension, e.g. {@code "md"} or {@code "log"};
   * see {@link SnapshotFormat#extension()} for what is valid. This is checked when a verifier is
   * built with the format.
   */
  public TextFormat(String extension) {
    this.extension = Objects.requireNonNull(extension, "extension");
  }

  /** Starts a verifier for this format. */
  public Verifier.Builder<String> verifier() {
    return Verifier.builder(this);
  }

  @Override
  public String extension() {
    return extension;
  }

  @Override
  public String serialize(String document) {
    return document;
  }

  @Override
  public String deserialize(String snapshot) {
    return snapshot;
  }
}
