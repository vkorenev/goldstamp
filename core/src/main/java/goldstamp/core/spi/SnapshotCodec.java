package goldstamp.core.spi;

import java.io.IOException;

/**
 * Converts documents of type {@code T} to and from snapshot text.
 *
 * <p>When invoked by {@code SnapshotVerifier}, an {@link IOException} from either method is
 * reported as a {@code SnapshotException}. Runtime exceptions from {@link #serialize} propagate
 * unchanged; those from {@link #deserialize} are wrapped with the approved file's path.
 *
 * @param <T> the document type
 */
public interface SnapshotCodec<T> {
  /** Snapshot file suffix; letters and digits only, e.g. "json". */
  String extension();

  /** Serializes a document to the text stored in the snapshot file. Never returns null. */
  String serialize(T document) throws IOException;

  /**
   * Deserializes snapshot text. Must accept this codec's own output, and must also accept
   * equivalent text a human edited by hand during approval. Never returns null.
   */
  T deserialize(String snapshot) throws IOException;
}
