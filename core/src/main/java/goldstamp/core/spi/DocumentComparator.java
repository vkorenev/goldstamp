package goldstamp.core.spi;

/**
 * Compares the parsed approved snapshot with the transformed actual document. The actual document
 * is not serialized first, so implementations must tolerate representation changes that
 * serialization causes (e.g. numeric node types), or approving the received file will not produce a
 * match. Must not mutate either operand.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentComparator<T> {
  /**
   * Compares the two documents.
   *
   * @param approved the approved file's text, parsed by {@link SnapshotFormat#deserialize}
   * @param actual the transformed incoming document
   */
  ComparisonResult compare(T approved, T actual);
}
