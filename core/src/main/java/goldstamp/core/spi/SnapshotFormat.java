package goldstamp.core.spi;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Objects;

/**
 * Everything that depends on a document model: the snapshot file extension and conversion between
 * documents and snapshot content.
 *
 * <p>Only {@link #serialize} and {@link #deserialize} are required. The streaming {@link #write}
 * and {@link #read} default to them, encoding text as UTF-8. Overrides must write the UTF-8
 * encoding of what {@link #serialize} returns and read whatever {@link #deserialize} accepts. The
 * caller owns every stream: implementations must not close it.
 *
 * <p>When invoked by {@code SnapshotVerifier}, exceptions from {@link #deserialize} and {@code
 * read} are reported as a {@code SnapshotException} naming the approved file, and an {@link
 * IOException} from {@code write} as one naming the received file; other exceptions propagate
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

  /**
   * Writes a document as snapshot bytes, by default the strict UTF-8 encoding of {@link
   * #serialize}. Does not close {@code out}.
   *
   * @throws NullPointerException if {@link #serialize} returns null
   * @throws IOException if writing fails or the text is not valid UTF-16
   */
  default void write(T document, OutputStream out) throws IOException {
    String text = Objects.requireNonNull(serialize(document), "Snapshot text");
    ByteBuffer bytes = UTF_8.newEncoder().encode(CharBuffer.wrap(text));
    out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
  }

  /**
   * Reads a document from snapshot bytes, by default decoding all of them as strict UTF-8 for
   * {@link #deserialize}. Does not close {@code in}. Never returns null.
   *
   * @throws IOException if reading fails or the bytes are not valid UTF-8
   */
  default T read(InputStream in) throws IOException {
    return deserialize(UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readAllBytes())).toString());
  }
}
