package goldstamp.core.text;

import goldstamp.core.spi.SnapshotCodec;
import java.util.Objects;

/**
 * A pass-through codec for plain text documents: the snapshot file holds the document text exactly
 * as given, and reading returns the file text unchanged.
 */
public final class TextSnapshotCodec implements SnapshotCodec<String> {
  private final String extension;

  /** A codec writing {@code .txt} snapshot files. */
  public TextSnapshotCodec() {
    this("txt");
  }

  /**
   * A codec writing snapshot files with the given extension, which must be letters and digits only;
   * this is checked when a verifier is built with the codec.
   */
  public TextSnapshotCodec(String extension) {
    this.extension = Objects.requireNonNull(extension, "extension");
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
