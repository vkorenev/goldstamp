package goldstamp.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Where approved snapshots are read from: a directory or the classpath. */
sealed interface ApprovedSnapshots {
  /** One approved snapshot, which may not exist. Used for a single verification. */
  interface Approved {
    /** Where the snapshot is; for a resource, it includes the source once opened. */
    ApprovedLocation location();

    /**
     * Opens the snapshot for reading.
     *
     * @throws NoSuchFileException if it does not exist
     * @throws IOException if it cannot be opened
     */
    InputStream open() throws IOException;
  }

  /** The path of {@code fileName} relative to the received directory. */
  String relativeName(String fileName);

  /** The approved snapshot named {@code fileName}. */
  Approved locate(String fileName);

  /** Approved files in a directory, made absolute and normalized; received files are flat. */
  record Directory(Path directory) implements ApprovedSnapshots {
    public Directory {
      directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    @Override
    public String relativeName(String fileName) {
      return fileName;
    }

    @Override
    public Approved locate(String fileName) {
      var location = new ApprovedLocation.File(directory.resolve(fileName));
      return new Approved() {
        @Override
        public ApprovedLocation location() {
          return location;
        }

        @Override
        public InputStream open() throws IOException {
          return Files.newInputStream(location.path());
        }
      };
    }
  }

  /**
   * Approved resources of {@code loader} under {@code directory}, a slash-separated resource path
   * without a trailing slash; empty means the root. Received files mirror the resource path, so
   * copying one back to the resource tree approves it.
   */
  record Resources(ClassLoader loader, String directory) implements ApprovedSnapshots {
    /**
     * A segment that names the same thing as a resource name and as a file name: no separators,
     * control characters or characters Windows forbids, and no trailing dot or space, which Windows
     * strips. This also rules out empty, {@code .} and {@code ..} segments. Windows reserved device
     * names such as {@code con} are not checked.
     */
    private static final Pattern SEGMENT =
        Pattern.compile(SnapshotVerifier.FILE_NAME_CHAR + "+(?<![. ])");

    /**
     * Validates {@code directory} so that it maps identically to a resource name and to a path
     * relative to the received directory, and strips one trailing slash.
     *
     * @throws IllegalArgumentException if it does not map identically
     */
    public Resources {
      Objects.requireNonNull(loader, "loader");
      Objects.requireNonNull(directory, "directory");
      String raw = directory;
      if (directory.length() > 1 && directory.endsWith("/")) {
        directory = directory.substring(0, directory.length() - 1);
      }
      if (!directory.isEmpty()) {
        for (String segment : directory.split("/", -1)) {
          if (!SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                "Invalid resource directory '"
                    + raw
                    + "': must be relative, with no empty, '.' or '..' segments, no segment"
                    + " ending in a dot or space, and no backslashes, control characters or any of"
                    + " : * ? \" < > |");
          }
        }
      }
    }

    @Override
    public String relativeName(String fileName) {
      return directory.isEmpty() ? fileName : directory + "/" + fileName;
    }

    @Override
    public Approved locate(String fileName) {
      String name = relativeName(fileName);
      return new Approved() {
        private ApprovedLocation location = new ApprovedLocation.Resource(name, null);

        @Override
        public ApprovedLocation location() {
          return location;
        }

        @Override
        public InputStream open() throws IOException {
          // Not getResourceAsStream: it returns null on an I/O error, which would read as missing.
          URL url = loader.getResource(name);
          if (url == null) {
            throw new NoSuchFileException(location.toString());
          }
          URI source = toUri(url);
          location = new ApprovedLocation.Resource(name, source);
          // A file: URL to a directory streams a listing of its entries, not an error.
          if (source != null
              && "file".equals(source.getScheme())
              && Files.isDirectory(Path.of(source))) {
            throw new FileSystemException(location.toString(), null, "Is a directory");
          }
          return url.openStream();
        }
      };
    }

    /** The URL as a URI, or null if it is not a valid one; it is used only in diagnostics. */
    private static URI toUri(URL url) {
      try {
        return url.toURI();
      } catch (URISyntaxException e) {
        return null;
      }
    }
  }
}
