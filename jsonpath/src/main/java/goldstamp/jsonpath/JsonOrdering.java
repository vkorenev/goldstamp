package goldstamp.jsonpath;

import com.jayway.jsonpath.spi.json.JsonProvider;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A total order over JSON values of a {@link JsonProvider}'s tree model, independent of how the
 * values are serialized or in which order object properties were inserted.
 *
 * <p>Values are ordered by type first: {@code null}, {@code false}, {@code true}, numbers, strings,
 * arrays, objects. Within a type:
 *
 * <ul>
 *   <li>numbers compare numerically (so {@code 1} and {@code 1.0} are adjacent); numbers equal in
 *       value are ordered by their text, and non-finite values order as {@code -Infinity}, finite
 *       numbers, {@code +Infinity}, {@code NaN};
 *   <li>strings compare by UTF-16 code units, as {@link String#compareTo};
 *   <li>arrays compare element by element, then by length;
 *   <li>objects compare their properties sorted by name, name first and then value, then by size.
 * </ul>
 *
 * Obtain one from {@link JsonTreeFormat#ordering()}.
 */
public final class JsonOrdering<T> implements Comparator<T> {
  private final JsonProvider provider;

  JsonOrdering(JsonProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider");
  }

  @Override
  public int compare(T left, T right) {
    return compareNodes(left, right);
  }

  private int compareNodes(Object left, Object right) {
    int leftRank = rank(left);
    int rightRank = rank(right);
    if (leftRank != rightRank) {
      return Integer.compare(leftRank, rightRank);
    }
    return switch (leftRank) {
      case ARRAY -> compareArrays(left, right);
      case OBJECT -> compareObjects(left, right);
      default -> compareScalars(provider.unwrap(left), provider.unwrap(right));
    };
  }

  private static final int NULL = 0;
  private static final int FALSE = 1;
  private static final int TRUE = 2;
  private static final int NUMBER = 3;
  private static final int STRING = 4;
  private static final int ARRAY = 5;
  private static final int OBJECT = 6;

  private int rank(Object node) {
    if (provider.isArray(node)) {
      return ARRAY;
    }
    if (provider.isMap(node)) {
      return OBJECT;
    }
    Object value = provider.unwrap(node);
    if (value == null) {
      return NULL;
    }
    if (value instanceof Boolean flag) {
      return flag ? TRUE : FALSE;
    }
    return value instanceof Number ? NUMBER : STRING;
  }

  private int compareArrays(Object left, Object right) {
    int leftLength = provider.length(left);
    int rightLength = provider.length(right);
    for (int i = 0; i < Math.min(leftLength, rightLength); i++) {
      int result = compareNodes(provider.getArrayIndex(left, i), provider.getArrayIndex(right, i));
      if (result != 0) {
        return result;
      }
    }
    return Integer.compare(leftLength, rightLength);
  }

  private int compareObjects(Object left, Object right) {
    List<String> leftKeys = sortedKeys(left);
    List<String> rightKeys = sortedKeys(right);
    for (int i = 0; i < Math.min(leftKeys.size(), rightKeys.size()); i++) {
      int result = leftKeys.get(i).compareTo(rightKeys.get(i));
      if (result == 0) {
        result =
            compareNodes(
                provider.getMapValue(left, leftKeys.get(i)),
                provider.getMapValue(right, rightKeys.get(i)));
      }
      if (result != 0) {
        return result;
      }
    }
    return Integer.compare(leftKeys.size(), rightKeys.size());
  }

  private List<String> sortedKeys(Object object) {
    Collection<String> keys = provider.getPropertyKeys(object);
    List<String> sorted = new ArrayList<>(keys);
    sorted.sort(null);
    return sorted;
  }

  /** Only called for two values of the same scalar rank. */
  private static int compareScalars(Object left, Object right) {
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      return compareNumbers(leftNumber, rightNumber);
    }
    if (left == null || left instanceof Boolean) {
      return 0;
    }
    return left.toString().compareTo(right.toString());
  }

  private static int compareNumbers(Number left, Number right) {
    if (left.getClass() == right.getClass()) {
      if (left instanceof Double l) {
        return Double.compare(l, (Double) right);
      }
      if (left instanceof Float l) {
        return Float.compare(l, (Float) right);
      }
      if (left instanceof BigInteger l) {
        return l.compareTo((BigInteger) right);
      }
    }
    if (isIntegral(left) && isIntegral(right)) {
      return Long.compare(left.longValue(), right.longValue());
    }
    int leftClass = valueClass(left);
    int rightClass = valueClass(right);
    if (leftClass != FINITE || rightClass != FINITE) {
      return Integer.compare(leftClass, rightClass);
    }
    BigDecimal leftDecimal = decimal(left);
    BigDecimal rightDecimal = decimal(right);
    int result =
        leftDecimal != null && rightDecimal != null
            ? leftDecimal.compareTo(rightDecimal)
            : Double.compare(left.doubleValue(), right.doubleValue());
    return result != 0 ? result : left.toString().compareTo(right.toString());
  }

  private static final int NEGATIVE_INFINITY = 0;
  private static final int FINITE = 1;
  private static final int POSITIVE_INFINITY = 2;
  private static final int NAN = 3;

  /** Orders non-finite values around the finite ones: -Infinity, finite, +Infinity, NaN. */
  private static int valueClass(Number number) {
    double value;
    if (number instanceof Double d) {
      value = d;
    } else if (number instanceof Float f) {
      value = f;
    } else {
      return FINITE;
    }
    if (Double.isNaN(value)) {
      return NAN;
    }
    if (Double.isInfinite(value)) {
      return value > 0 ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
    }
    return FINITE;
  }

  private static boolean isIntegral(Number number) {
    return number instanceof Integer
        || number instanceof Long
        || number instanceof Short
        || number instanceof Byte;
  }

  private static BigDecimal decimal(Number number) {
    if (number instanceof BigDecimal decimal) {
      return decimal;
    }
    if (number instanceof BigInteger integer) {
      return new BigDecimal(integer);
    }
    if (isIntegral(number)) {
      return BigDecimal.valueOf(number.longValue());
    }
    try {
      return new BigDecimal(number.toString());
    } catch (NumberFormatException notDecimal) {
      return null;
    }
  }
}
