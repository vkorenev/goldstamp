package goldstamp.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.*;

import goldstamp.core.spi.ComparisonResult;
import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.SnapshotCodec;
import goldstamp.core.text.TextSnapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotVerifierTest {
  @TempDir Path directory;

  /** Pass-through text builder over {@link #directory}; tests reconfigure it as needed. */
  private SnapshotVerifier.Builder<String> builder;

  /** The default verifier: pass-through text codec, line-by-line text comparison. */
  private SnapshotVerifier<String> verifier;

  @BeforeEach
  void createVerifier() {
    builder = TextSnapshots.builder().approvedDirectory(directory).receivedDirectory(directory);
    verifier = builder.build();
  }

  private static final DocumentComparator<String> EQUAL_DOCUMENTS =
      (expected, actual) ->
          expected.equals(actual)
              ? ComparisonResult.matching()
              : ComparisonResult.different("documents differ");

  /** Approves the received output, as a developer would after reviewing it. */
  private static void approve(VerificationResult.Missing missing) throws IOException {
    Files.copy(missing.receivedFile(), missing.approvedFile());
  }

  /** Builder with the {@link NormalizingTextCodec} over {@link #directory}. */
  private SnapshotVerifier.Builder<String> normalizingBuilder() {
    return SnapshotVerifier.builder(new NormalizingTextCodec())
        .approvedDirectory(directory)
        .receivedDirectory(directory);
  }

  /**
   * Documents are strings; the snapshot is the lower-cased, stripped text plus a newline, and
   * snapshots containing "invalid" cannot be read.
   */
  private static class NormalizingTextCodec implements SnapshotCodec<String> {
    @Override
    public String extension() {
      return "txt";
    }

    @Override
    public String serialize(String document) {
      return document.strip().toLowerCase() + "\n";
    }

    @Override
    public String deserialize(String snapshot) throws IOException {
      if (snapshot.contains("invalid")) throw new IOException("invalid snapshot");
      return snapshot.strip().toLowerCase();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingApproveMatchDifferentWorkflowWithRulesAppliedOnlyToIncomingDocuments(
      boolean compareDocuments) throws IOException {
    if (compareDocuments) {
      builder.documentComparator(EQUAL_DOCUMENTS);
    }
    var transforming = builder.build().appendTransformer(text -> text.toLowerCase() + "!");

    var missing =
        assertInstanceOf(
            VerificationResult.Missing.class, transforming.verify("greeting", "Hello"));
    assertEquals("hello!", Files.readString(missing.receivedFile()));
    assertFalse(Files.exists(missing.approvedFile()));

    // Approve the received output. The transformer ran on the incoming document only, so a
    // matching result also proves it was not applied again to the approved text.
    approve(missing);
    assertInstanceOf(VerificationResult.Matching.class, transforming.verify("greeting", "Hello"));
    assertFalse(Files.exists(missing.receivedFile()));

    assertInstanceOf(
        VerificationResult.Different.class, transforming.verify("greeting", "goodbye"));
    assertEquals("goodbye!", Files.readString(missing.receivedFile()));
    assertEquals("hello!", Files.readString(missing.approvedFile()));
  }

  @Test
  void assertMatchesThrowsWithTheDiagnosticUnlessTheSnapshotMatches() throws IOException {
    var missing = assertThrows(AssertionError.class, () -> verifier.assertMatches("am", "x"));
    assertThat(missing.getMessage(), containsString("Missing approved snapshot 'am'"));

    Files.copy(directory.resolve("am.received.txt"), directory.resolve("am.approved.txt"));
    verifier.assertMatches("am", "x");

    var different = assertThrows(AssertionError.class, () -> verifier.assertMatches("am", "y"));
    assertThat(different.getMessage(), containsString("- x"));
    assertThat(different.getMessage(), containsString("+ y"));
  }

  @Test
  void onlyAMissingTrailingNewlineIsNamedInTheDiagnostic() throws IOException {
    Files.writeString(directory.resolve("nl.approved.txt"), "a\n");
    var missingNewline =
        assertInstanceOf(VerificationResult.Different.class, verifier.verify("nl", "a"));
    assertThat(missingNewline.differences(), containsString("<no trailing newline>"));
    var extraBlankLine =
        assertInstanceOf(VerificationResult.Different.class, verifier.verify("nl", "a\n\n"));
    assertThat(extraBlankLine.differences(), not(containsString("<no trailing newline>")));
  }

  @Test
  void appendedTransformersRunInOrderAndLeaveTheOriginalVerifierUnchanged() throws IOException {
    var chained =
        verifier.appendTransformer(text -> text + "-a").appendTransformer(text -> text + "-b");

    var missing = assertInstanceOf(VerificationResult.Missing.class, chained.verify("chain", "x"));
    assertEquals("x-a-b", Files.readString(missing.receivedFile()));
    approve(missing);
    assertInstanceOf(VerificationResult.Matching.class, chained.verify("chain", "x"));
    // The original verifier is unchanged.
    assertInstanceOf(VerificationResult.Different.class, verifier.verify("chain", "x"));
  }

  @Test
  void approvedFileWithCrlfLineEndingsStillMatches() throws IOException {
    Files.writeString(directory.resolve("crlf.approved.txt"), "line one\r\nline two\r\n");
    assertInstanceOf(
        VerificationResult.Matching.class, verifier.verify("crlf", "line one\nline two\n"));
    var different = verifier.verify("crlf", "line one\nline 2\n");
    assertInstanceOf(VerificationResult.Different.class, different);
    assertThat(different.diagnostic(), containsString("line two"));
  }

  @Test
  void textComparisonPreservesApprovalEditsWhileDocumentComparisonUsesActualDirectly()
      throws IOException {
    // A hand-edited approval: the codec would serialize "hello\n" for the same document.
    Path approved = directory.resolve("edit.approved.txt");
    Files.writeString(approved, "  HELLO  \n\n\n");
    var codecBuilder = normalizingBuilder();

    // Text comparison uses the approved file verbatim, so the edits are significant.
    var textComparing = codecBuilder.build();
    assertInstanceOf(VerificationResult.Different.class, textComparing.verify("edit", "hello"));

    // Document comparison parses the approval ("hello") and compares it with the actual document.
    var documentComparing = codecBuilder.documentComparator(EQUAL_DOCUMENTS).build();
    assertInstanceOf(VerificationResult.Matching.class, documentComparing.verify("edit", "hello"));
    // The actual document is not normalized by a codec round trip, despite identical output text.
    assertInstanceOf(VerificationResult.Different.class, documentComparing.verify("edit", "HELLO"));
    assertEquals("hello\n", Files.readString(directory.resolve("edit.received.txt")));
    assertInstanceOf(VerificationResult.Matching.class, documentComparing.verify("edit", "hello"));
    assertFalse(Files.exists(directory.resolve("edit.received.txt")));
    assertEquals("  HELLO  \n\n\n", Files.readString(approved));
    assertInstanceOf(VerificationResult.Different.class, textComparing.verify("edit", "hello"));
  }

  @Test
  void comparatorDeterminesTheVerdictAndDiagnostic() throws IOException {
    Files.writeString(directory.resolve("cmp.approved.txt"), "alpha\nbeta\n");
    var different =
        assertInstanceOf(
            VerificationResult.Different.class, verifier.verify("cmp", "alpha\ngamma\n"));
    assertThat(different.differences(), stringContainsInOrder("line 2", "- beta", "+ gamma"));
    assertEquals("alpha\ngamma\n", Files.readString(different.receivedFile()));

    var alwaysMatching =
        builder.textComparator((approved, actual) -> ComparisonResult.matching()).build();
    assertInstanceOf(
        VerificationResult.Matching.class, alwaysMatching.verify("cmp", "alpha\ngamma\n"));
    assertFalse(Files.exists(different.receivedFile())); // Stale received output is removed.

    DocumentComparator<Object> comparator =
        (approved, actual) -> ComparisonResult.different("custom: nope");
    var alwaysDifferent = builder.documentComparator(comparator).build();
    // Reconfiguring the builder after build() must not affect the verifier already built.
    builder.textComparator((approved, actual) -> ComparisonResult.matching());
    var customResult =
        assertInstanceOf(
            VerificationResult.Different.class, alwaysDifferent.verify("cmp", "alpha\nbeta\n"));
    assertThat(customResult.diagnostic(), containsString("custom: nope"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "..", "../escape", "/tmp/escape", "a/b", "a\\b", "C:\\escape", "."})
  void rejectsUnsafeSnapshotIds(String id) throws IOException {
    assertThrows(IllegalArgumentException.class, () -> verifier.verify(id, "x"));
    // Nothing may be written, in particular not outside the snapshot directory.
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void ruleFailurePropagatesUnchanged() {
    var originalFailure = new IllegalArgumentException("bad custom rule");
    var failing =
        verifier.appendTransformer(
            text -> {
              throw originalFailure;
            });

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> failing.verify("bad", "hello"));
    assertSame(originalFailure, failure);
    assertFalse(Files.exists(directory.resolve("bad.received.txt")));
  }

  @Test
  void nullTransformerResultIsRejected() {
    NullPointerException failure =
        assertThrows(
            NullPointerException.class,
            () -> verifier.appendTransformer(text -> null).verify("bad", "hello"));
    assertThat(failure.getMessage(), containsString("Transformed document"));
  }

  @Test
  void unparseableApprovalIsReportedWithSnapshotContextAndLeftUntouched() throws IOException {
    Files.writeString(directory.resolve("bad.approved.txt"), "invalid");
    var documentComparing =
        normalizingBuilder()
            .documentComparator((expected, actual) -> ComparisonResult.matching())
            .build();

    SnapshotException failure =
        assertThrows(SnapshotException.class, () -> documentComparing.verify("bad", "hello"));
    assertThat(
        failure.getMessage(), containsString(directory.resolve("bad.approved.txt").toString()));
    assertThat(failure.getCause().getMessage(), containsString("invalid snapshot"));
    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals("invalid", Files.readString(directory.resolve("bad.approved.txt")));
  }

  @Test
  void receivedFileWriteFailureIsReportedWithSnapshotContext() throws IOException {
    Path regularFile = directory.resolve("not-a-directory");
    Files.writeString(regularFile, "x");
    var unwritable = builder.receivedDirectory(regularFile).build();

    SnapshotException failure =
        assertThrows(SnapshotException.class, () -> unwritable.verify("missing", "x"));
    assertThat(failure.getMessage(), containsString("received snapshot"));
    assertInstanceOf(IOException.class, failure.getCause());
  }

  @Test
  void approvedFileReadFailureIsReportedWithSnapshotContext() throws IOException {
    Files.createDirectories(
        directory.resolve("unreadable.approved.txt")); // A directory, not a file.

    SnapshotException failure =
        assertThrows(SnapshotException.class, () -> verifier.verify("unreadable", "x"));
    assertThat(failure.getMessage(), containsString("approved snapshot"));
    assertInstanceOf(IOException.class, failure.getCause());
  }

  @Test
  void staleReceivedFileCleanupFailureIsReportedWithSnapshotContext() throws IOException {
    // A non-empty directory in place of the stale received file cannot be deleted.
    Path staleReceived = directory.resolve("stale.received.txt");
    Files.createDirectories(staleReceived);
    Files.writeString(staleReceived.resolve("child"), "preserve");
    Files.writeString(directory.resolve("stale.approved.txt"), "x");

    SnapshotException failure =
        assertThrows(SnapshotException.class, () -> verifier.verify("stale", "x"));
    assertThat(failure.getMessage(), containsString("stale received snapshot"));
    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals("preserve", Files.readString(staleReceived.resolve("child")));
  }
}
