package goldstamp.core.spi;

/**
 * Compares the parsed approved snapshot with the transformed actual document, without serializing
 * and reloading the actual operand. Must not mutate its operands or apply the transformer.
 * Implementations must account for representation changes caused by the codec if approval is to
 * produce a match.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentComparator<T> {
  /**
   * Compares the two documents.
   *
   * @param approved the approved file's text, parsed by {@link SnapshotCodec#deserialize}
   * @param actual the transformed incoming document
   */
  ComparisonResult compare(T approved, T actual);
}
