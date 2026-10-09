package goldstamp.core.spi;

/**
 * Compares approved file text verbatim with serialized actual text. The approved text is neither
 * parsed nor normalized by the codec. This is the default comparison strategy, with {@code
 * LineComparator} as the default implementation.
 */
@FunctionalInterface
public interface TextComparator {
  /**
   * Compares the two texts.
   *
   * @param approved the approved file's text, exactly as stored
   * @param actual the transformed incoming document, serialized by {@link SnapshotCodec#serialize}
   */
  ComparisonResult compare(String approved, String actual);
}
