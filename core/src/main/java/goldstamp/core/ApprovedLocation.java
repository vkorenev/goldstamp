package goldstamp.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Where an approved snapshot is: a file, or a classpath resource. It may not exist. */
public sealed interface ApprovedLocation {
  /**
   * An approved file.
   *
   * @param path the file's path
   */
  record File(Path path) implements ApprovedLocation {
    /**
     * Creates the location.
     *
     * @throws NullPointerException if {@code path} is null
     */
    public File {
      Objects.requireNonNull(path, "path");
    }

    /** Returns the path. */
    @Override
    public String toString() {
      return path.toString();
    }
  }

  /**
   * An approved classpath resource.
   *
   * @param name the resource name, such as {@code com/example/order.approved.json}
   * @param source the URI it was read from, or null if it was not found or not read
   */
  record Resource(String name, URI source) implements ApprovedLocation {
    /**
     * Creates the location.
     *
     * @throws NullPointerException if {@code name} is null
     */
    public Resource {
      Objects.requireNonNull(name, "name");
    }

    /** Returns {@code classpath:<name>}, followed by the source in parentheses if known. */
    @Override
    public String toString() {
      return "classpath:" + name + (source == null ? "" : " (" + source + ")");
    }
  }
}
