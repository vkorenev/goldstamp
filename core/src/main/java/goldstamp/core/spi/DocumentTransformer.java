package goldstamp.core.spi;

import java.util.Objects;

/**
 * Transforms an incoming document. Never applied to approved snapshots.
 *
 * <p>In a verifier, the first transformer receives the document produced by {@link
 * SnapshotFormat#toDocument}, which is the verifier's own copy; each subsequent transformer
 * receives the preceding result. Transformers may mutate their input or return a replacement root,
 * and must ensure any nodes they introduce are safe for subsequent transformers to mutate. Outside
 * a verifier, callers must copy first if their document needs to stay unchanged.
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
