package goldstamp.core;

import goldstamp.core.spi.ComparisonResult;
import goldstamp.core.spi.TextComparator;
import java.util.Objects;

/**
 * The default {@link TextComparator}: reports the first differing line with a little surrounding
 * context. Line endings ({@code \n} or {@code \r\n}) are not significant, so approved files survive
 * a CRLF checkout. For rich diffs, diff the received file with a dedicated tool or plug in a
 * comparator.
 */
public final class LineComparator implements TextComparator {
  private static final int CONTEXT = 2;

  @Override
  public ComparisonResult compare(String approved, String actual) {
    Objects.requireNonNull(approved, "approved");
    Objects.requireNonNull(actual, "actual");
    if (approved.equals(actual)) {
      return ComparisonResult.matching();
    }
    String[] approvedLines = approved.split("\r?\n", -1);
    String[] actualLines = actual.split("\r?\n", -1);
    int firstDifference = 0;
    while (firstDifference < approvedLines.length
        && firstDifference < actualLines.length
        && approvedLines[firstDifference].equals(actualLines[firstDifference])) {
      firstDifference++;
    }
    // The texts differ only in line endings (e.g. CRLF vs LF).
    if (firstDifference == approvedLines.length && firstDifference == actualLines.length) {
      return ComparisonResult.matching();
    }
    // Only a trailing newline separates the texts when the longer one has a single empty line
    // left and the shorter one does not itself end with a newline.
    boolean approvedLonger = approvedLines.length > actualLines.length;
    String[] longer = approvedLonger ? approvedLines : actualLines;
    String[] shorter = approvedLonger ? actualLines : approvedLines;
    boolean trailingNewlineOnly =
        longer.length == shorter.length + 1
            && firstDifference == shorter.length
            && longer[firstDifference].isEmpty()
            && firstDifference > 0
            && !shorter[firstDifference - 1].isEmpty();
    String end = trailingNewlineOnly ? "<no trailing newline>" : "<end of snapshot>";
    StringBuilder message =
        new StringBuilder("First difference at line " + (firstDifference + 1) + ":\n");
    for (int i = Math.max(0, firstDifference - CONTEXT); i < firstDifference; i++) {
      message.append("  ").append(visible(approvedLines[i])).append('\n');
    }
    message.append("- ").append(lineAt(approvedLines, firstDifference, end));
    message.append("\n+ ").append(lineAt(actualLines, firstDifference, end));
    return ComparisonResult.different(message.toString());
  }

  private static String lineAt(String[] lines, int index, String end) {
    return index < lines.length ? visible(lines[index]) : end;
  }

  /** Makes invisible differences (trailing spaces, tabs, stray carriage returns) readable. */
  private static String visible(String line) {
    String shown = line.replace("\t", "\\t").replace("\r", "\\r");
    return shown.endsWith(" ") ? shown + " <trailing whitespace>" : shown;
  }
}
