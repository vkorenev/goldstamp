package goldstamp.core;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.*;

import goldstamp.core.spi.ComparisonResult;
import goldstamp.core.spi.DocumentComparator;
import goldstamp.core.spi.Mapping;
import goldstamp.core.spi.SnapshotFormat;
import goldstamp.core.text.TextFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotVerifierTest {
  @TempDir Path directory;

  /** Plain-text builder over {@link #directory}; tests reconfigure it as needed. */
  private Verifier.Builder<String> builder;

  /** The default verifier: plain-text format, line-by-line text comparison. */
  private Verifier<String> verifier;

  @BeforeEach
  void createVerifier() {
    builder = new TextFormat().verifier().approvedDirectory(directory).receivedDirectory(directory);
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

  private final NormalizingTextFormat normalizing = new NormalizingTextFormat();

  /** Builder with the {@link NormalizingTextFormat} over {@link #directory}. */
  private Verifier.Builder<String> normalizingBuilder() {
    return Verifier.builder(normalizing).approvedDirectory(directory).receivedDirectory(directory);
  }

  /**
   * Documents are strings; the snapshot is the lower-cased, stripped text plus a newline, and
   * snapshots containing "invalid" cannot be read. Counts copies.
   */
  private static class NormalizingTextFormat implements SnapshotFormat<String> {
    final AtomicInteger copies = new AtomicInteger();

    @Override
    public String extension() {
      return "txt";
    }

    String copy(String document) {
      copies.incrementAndGet();
      return document;
    }

    @Override
    public String serialize(String document) {
      return document.strip().toLowerCase() + "\n";
    }

    @Override
    public String deserialize(String snapshot) {
      if (snapshot.contains("invalid")) throw new IllegalStateException("invalid snapshot");
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
    var transforming = builder.with(Mapping.unshared(text -> text.toLowerCase() + "!")).build();

    var missing =
        assertInstanceOf(
            VerificationResult.Missing.class, transforming.verify("greeting", "Hello"));
    assertEquals("hello!", Files.readString(missing.receivedFile()));
    assertFalse(Files.exists(missing.approvedFile()));

    // Approve the received output. The transformation ran on the incoming document only, so a
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
  void prependedTransformationsRunBeforeTheVerifiersOwnAndLeaveTheOriginalUnchanged()
      throws IOException {
    var baseBuilder = normalizingBuilder().with(Mapping.unshared(text -> text + "-a"));
    var base = baseBuilder.build();
    baseBuilder.with(
        Mapping.unshared(text -> text + "-ignored")); // Built verifiers keep their chain.
    var chained =
        base.prependTransformations(
            List.of(
                Mapping.<String, String>unshared(text -> text + "-b"),
                Mapping.<String, String>unshared(text -> text + "-c"),
                Mapping.<String, String>unshared(text -> text + "-d")));

    var missing = assertInstanceOf(VerificationResult.Missing.class, chained.verify("chain", "x"));
    assertEquals("x-b-c-d-a\n", Files.readString(missing.receivedFile()));
    approve(missing);
    assertInstanceOf(VerificationResult.Matching.class, chained.verify("chain", "x"));
    // The original verifier is unchanged.
    assertInstanceOf(VerificationResult.Different.class, base.verify("chain", "x"));
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
    // A hand-edited approval: the format would serialize "hello\n" for the same document.
    Path approved = directory.resolve("edit.approved.txt");
    Files.writeString(approved, "  HELLO  \n\n\n");
    var formatBuilder = normalizingBuilder();

    // Text comparison uses the approved file verbatim, so the edits are significant.
    var textComparing = formatBuilder.build();
    assertInstanceOf(VerificationResult.Different.class, textComparing.verify("edit", "hello"));

    // Document comparison parses the approval ("hello") and compares it with the actual document.
    var documentComparing = formatBuilder.documentComparator(EQUAL_DOCUMENTS).build();
    assertInstanceOf(VerificationResult.Matching.class, documentComparing.verify("edit", "hello"));
    // The actual document is not normalized by a format round trip, despite identical output text.
    assertInstanceOf(VerificationResult.Different.class, documentComparing.verify("edit", "HELLO"));
    assertEquals("hello\n", Files.readString(directory.resolve("edit.received.txt")));
    assertInstanceOf(VerificationResult.Matching.class, documentComparing.verify("edit", "hello"));
    assertFalse(Files.exists(directory.resolve("edit.received.txt")));
    assertEquals("  HELLO  \n\n\n", Files.readString(approved));
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
  @NullSource
  @ValueSource(strings = {"", "..", "../escape", "/tmp/escape", "a/b", "a\\b", "C:\\escape", "."})
  void rejectsUnsafeSnapshotIds(String id) throws IOException {
    assertThrows(IllegalArgumentException.class, () -> verifier.verify(id, "x"));
    // Nothing may be written, in particular not outside the snapshot directory.
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ".json", "json ", "../x", "a/b", "a\\b", "a:b", "a|b", "a\nb"})
  void rejectsFormatExtensionsThatBreakFileNames(String extension) {
    var format = new TextFormat(extension);
    assertThrows(IllegalArgumentException.class, () -> format.verifier().build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"json", "tar.gz", "c++", "x_y-z", "a b", "é"})
  void acceptsAnyFormatExtensionUsableInAFileName(String extension) {
    new TextFormat(extension).verifier().build();
  }

  /** Copies made while verifying "x" with a verifier built from the given transformations. */
  private int copies(boolean handedOver, Mapping<String, String>... transformations) {
    normalizing.copies.set(0);
    var chain = normalizingBuilder().with(transformations).build();
    if (handedOver) {
      chain.verifyOwned("copies", "x");
    } else {
      chain.verify("copies", "x");
    }
    return normalizing.copies.get();
  }

  @Test
  @SuppressWarnings("unchecked")
  void copiesOnlyWhenAnInPlaceTransformationReceivesADocumentThatMayBeShared() {
    var shared = Mapping.<String, String>shared(text -> text + "!");
    var withSource = Mapping.<String, String>withSource(text -> text + "#");
    var unshared = Mapping.<String, String>unshared(text -> text + "~");
    var inPlace = Mapping.inPlace(text -> text + "?", normalizing::copy);

    assertEquals(0, copies(false));
    assertEquals(0, copies(false, unshared, inPlace));
    assertEquals(1, copies(false, shared, inPlace, inPlace));
    assertEquals(0, copies(true, inPlace, inPlace)); // The caller gave the document up.
    assertEquals(1, copies(true, shared, inPlace)); // ...but a shared result may be shared.
    assertEquals(0, copies(true, withSource, inPlace)); // A withSource one is built from it only.
    assertEquals(1, copies(false, withSource, inPlace)); // ...so it is the caller's own document.
    assertEquals(2, copies(false, inPlace, shared, inPlace));
    assertEquals(1, copies(false, inPlace, withSource, inPlace));
    assertEquals(1, copies(false, inPlace, unshared, inPlace));
  }

  @Test
  void sharingFlowsThroughPrependTransformation() {
    var rules =
        normalizingBuilder().with(Mapping.inPlace(text -> text + "?", normalizing::copy)).build();

    normalizing.copies.set(0);
    rules
        .prependTransformation(Mapping.<Integer, String>unshared(n -> "N=" + n))
        .verify("fresh", 1);
    assertEquals(0, normalizing.copies.get()); // A fresh result is not referenced elsewhere.

    rules.prependTransformation(Mapping.<Integer, String>shared(n -> "N=" + n)).verify("shared", 1);
    assertEquals(1, normalizing.copies.get());
  }

  @Test
  void resultAssertMatchesThrowsUnlessTheSnapshotMatches() throws IOException {
    var missing = verifier.verifyOwned("res", "x");
    var failure = assertThrows(AssertionError.class, missing::assertMatches);
    assertEquals(missing.diagnostic(), failure.getMessage());

    approve(assertInstanceOf(VerificationResult.Missing.class, missing));
    verifier.verifyOwned("res", "x").assertMatches();
  }

  @Test
  void prependTransformationAdaptsTheInputType() throws IOException {
    Verifier<Integer> numbers =
        verifier.prependTransformation(Mapping.<Integer, String>shared(n -> "N=" + n));
    Verifier<String> digits =
        numbers.prependTransformation(Mapping.<String, Integer>shared(Integer::valueOf));

    var missing = assertInstanceOf(VerificationResult.Missing.class, digits.verify("num", "42"));
    assertEquals("N=42", Files.readString(missing.receivedFile()));
    approve(missing);
    numbers.assertMatches("num", 42);
    assertThrows(AssertionError.class, () -> numbers.assertMatches("num", 43));

    var failure =
        assertThrows(
            NullPointerException.class,
            () ->
                verifier
                    .prependTransformation(Mapping.<Integer, String>shared(n -> null))
                    .verify("bad", 1));
    assertThat(failure.getMessage(), containsString("returned null for snapshot 'bad'"));
  }

  @Test
  void ruleFailurePropagatesUnchanged() {
    var originalFailure = new IllegalArgumentException("bad custom rule");
    var failing =
        verifier.prependTransformation(
            Mapping.<String, String>shared(
                text -> {
                  throw originalFailure;
                }));

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> failing.verify("bad", "hello"));
    assertSame(originalFailure, failure);
    assertFalse(Files.exists(directory.resolve("bad.received.txt")));
  }

  @Test
  void nullTransformationResultNamesTheTransformationAndSnapshotWhereverItIsInTheChain() {
    var nullReturning = Mapping.<String, String>shared(text -> null);
    var other = Mapping.<String, String>shared(text -> text + "!");

    for (var chain :
        List.of(
            List.of(nullReturning), List.of(nullReturning, other), List.of(other, nullReturning))) {
      NullPointerException failure =
          assertThrows(
              NullPointerException.class,
              () -> verifier.prependTransformations(chain).verify("bad", "hello"));
      assertThat(failure.getMessage(), containsString("returned null for snapshot 'bad'"));
      assertThat(failure.getMessage(), containsString(nullReturning.toString()));
    }
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
    assertInstanceOf(IllegalStateException.class, failure.getCause());
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
