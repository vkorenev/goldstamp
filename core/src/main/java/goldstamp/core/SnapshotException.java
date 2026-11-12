package goldstamp.core;

/**
 * A snapshot could not be processed: a snapshot file could not be read, written, deleted or parsed
 * (the message names the file), or a GoldStamp rule could not apply to a document (the message
 * names the rule). Never thrown for a mismatch.
 */
public class SnapshotException extends RuntimeException {
  public SnapshotException(String message) {
    super(message);
  }

  public SnapshotException(String message, Throwable cause) {
    super(message, cause);
  }
}
