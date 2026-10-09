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

  /** The approved snapshot file; it may not exist (see {@link Missing}). */
  Path approvedFile();

  /** A human-readable summary suitable for a test failure message. */
  String diagnostic();

  /** Whether the document matched its approved snapshot. */
  default boolean matches() {
    return this instanceof Matching;
  }

  /** The document matches the approved snapshot; any stale received file was deleted. */
  record Matching(String snapshotId, Path approvedFile) implements VerificationResult {
    public Matching {
      validate(snapshotId, approvedFile);
    }

    @Override
    public String diagnostic() {
      return "Snapshot '" + snapshotId + "' matches.\nApproved: " + approvedFile;
    }
  }

  /** No approved snapshot exists; the received file was written for review. */
  record Missing(String snapshotId, Path approvedFile, Path receivedFile)
      implements VerificationResult {
    public Missing {
      validate(snapshotId, approvedFile);
      Objects.requireNonNull(receivedFile, "receivedFile");
    }

    @Override
    public String diagnostic() {
      return "Missing approved snapshot '"
          + snapshotId
          + "'. Review and copy received to approved.\n"
          + locations(approvedFile, receivedFile);
    }
  }

  /**
   * The document differs from the approved snapshot; the received file was written for review.
   * {@code differences} is the comparator's explanation of the mismatch.
   */
  record Different(String snapshotId, Path approvedFile, Path receivedFile, String differences)
      implements VerificationResult {
    public Different {
      validate(snapshotId, approvedFile);
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
          + locations(approvedFile, receivedFile);
    }
  }

  private static void validate(String snapshotId, Path approvedFile) {
    Objects.requireNonNull(snapshotId, "snapshotId");
    Objects.requireNonNull(approvedFile, "approvedFile");
  }

  private static String locations(Path approvedFile, Path receivedFile) {
    return "Approved: " + approvedFile + "\nReceived: " + receivedFile;
  }
}
