package goldstamp.core.spi;

import java.util.Objects;

/**
 * Transforms an incoming document. Never applied to approved snapshots.
 *
 * <p>The first transformer receives the document passed to {@code verify}; each subsequent
 * transformer receives the preceding result. A transformer that mutates its input must copy it
 * first. GoldStamp's own engines copy.
 *
 * @param <T> the document type
 */
@FunctionalInterface
public interface DocumentTransformer<T> {
  /** Returns a non-null document, which may be a replacement root. */
  T apply(T document);

  /** A transformer applying this one, then {@code next}, to the result. */
  default DocumentTransformer<T> andThen(DocumentTransformer<T> next) {
    Objects.requireNonNull(next, "next");
    return document -> next.apply(Objects.requireNonNull(apply(document), "Transformed document"));
  }
}
