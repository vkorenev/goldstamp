package goldstamp.core.spi;

/**
 * Everything that depends on a document model: the snapshot file extension and conversion between
 * documents and snapshot text.
 *
 * <p>When invoked by {@code SnapshotVerifier}, exceptions from {@link #deserialize} are reported as
 * a {@code SnapshotException} naming the approved file; exceptions from the other methods propagate
 * unchanged.
 *
 * @param <T> the document type
 */
public interface SnapshotFormat<T> {
  /**
   * Snapshot file suffix, e.g. "json", "tar.gz" or "c++". Must be usable in a file name: non-empty,
   * not starting with a dot (write "json", not ".json") or ending with a space, with no path
   * separators, control characters or any of {@code : * ? " < > |}. Checked when a verifier is
   * built with the format.
   */
  String extension();

  /** Serializes a document to the text stored in the snapshot file. Never returns null. */
  String serialize(T document);

  /**
   * Deserializes snapshot text. Must accept this format's own output, and must also accept
   * equivalent text a human edited by hand during approval. Never returns null.
   */
  T deserialize(String snapshot);
}
