package goldstamp.core;

/**
 * Thrown by {@link SnapshotVerifier#verify} when a snapshot file could not be read, written,
 * deleted, or parsed, or the codec could not serialize a document; not for a snapshot mismatch. The
 * message names the file where relevant and the cause is kept. Failures in user-supplied
 * transformers and comparators propagate unwrapped.
 */
public class SnapshotException extends RuntimeException {
  public SnapshotException(String message, Throwable cause) {
    super(message, cause);
  }
}
