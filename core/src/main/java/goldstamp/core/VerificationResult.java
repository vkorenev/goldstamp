package goldstamp.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The outcome of verification. Diagnostics are derived from the outcome and its data. Only outcomes
 * that wrote received output ({@link Missing}, {@link Different}) have a received file.
 */
public sealed interface VerificationResult {
  /** The ID passed to {@code verify}. */
  String snapshotId();

  /** Where the approved snapshot is; it may not exist (see {@link Missing}). */
  ApprovedLocation approvedLocation();

  /** A human-readable summary suitable for a test failure message. */
  String diagnostic();

  /** Whether the document matched its approved snapshot. */
  default boolean matches() {
    return this instanceof Matching;
  }

  /**
   * Does nothing if the document matched its approved snapshot.
   *
   * @throws AssertionError carrying {@link #diagnostic()} if the snapshot is missing or different
   */
  default void assertMatches() {
    if (!matches()) {
      throw new AssertionError(diagnostic());
    }
  }

  /** The document matches the approved snapshot; any stale received file was deleted. */
  record Matching(String snapshotId, ApprovedLocation approvedLocation)
      implements VerificationResult {
    public Matching {
      validate(snapshotId, approvedLocation);
    }

    @Override
    public String diagnostic() {
      return "Snapshot '" + snapshotId + "' matches.\nApproved: " + approvedLocation;
    }
  }

  /** No approved snapshot exists; the received file was written for review. */
  record Missing(String snapshotId, ApprovedLocation approvedLocation, Path receivedFile)
      implements VerificationResult {
    public Missing {
      validate(snapshotId, approvedLocation);
      Objects.requireNonNull(receivedFile, "receivedFile");
    }

    @Override
    public String diagnostic() {
      return "Missing approved snapshot '"
          + snapshotId
          + "'. Review and copy received to approved.\n"
          + locations(approvedLocation, receivedFile);
    }
  }

  /**
   * The document differs from the approved snapshot; the received file was written for review.
   * {@code differences} is the comparator's explanation of the mismatch.
   */
  record Different(
      String snapshotId, ApprovedLocation approvedLocation, Path receivedFile, String differences)
      implements VerificationResult {
    public Different {
      validate(snapshotId, approvedLocation);
      Objects.requireNonNull(receivedFile, "receivedFile");
      Objects.requireNonNull(differences, "differences");
    }

    @Override
    public String diagnostic() {
      return "Snapshot '"
          + snapshotId
          + "' differs: "
          + differences
          + "\n"
          + locations(approvedLocation, receivedFile);
    }
  }

  private static void validate(String snapshotId, ApprovedLocation approvedLocation) {
    Objects.requireNonNull(snapshotId, "snapshotId");
    Objects.requireNonNull(approvedLocation, "approvedLocation");
  }

  private static String locations(ApprovedLocation approvedLocation, Path receivedFile) {
    return "Approved: " + approvedLocation + "\nReceived: " + receivedFile;
  }
}
