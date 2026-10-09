package goldstamp.core.spi;

import java.util.Objects;

/** The outcome of comparing an approved snapshot with an actual document. */
public sealed interface ComparisonResult {
  /** The operands are equivalent. */
  record Matching() implements ComparisonResult {}

  /** The operands differ; {@code differences} explains how and must not be blank. */
  record Different(String differences) implements ComparisonResult {
    public Different {
      Objects.requireNonNull(differences, "differences");
      if (differences.isBlank()) {
        throw new IllegalArgumentException("A different comparison must explain the difference");
      }
    }
  }

  /** A result for equivalent operands. */
  static Matching matching() {
    return new Matching();
  }

  /** A result for differing operands, explained by {@code differences}. */
  static Different different(String differences) {
    return new Different(differences);
  }
}
