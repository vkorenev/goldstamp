package goldstamp.core.text;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.*;

import goldstamp.core.ApprovedLocation;
import goldstamp.core.VerificationResult;
import goldstamp.core.spi.Mapping;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextFormatTest {
  @TempDir Path directory;

  @Test
  void missingApproveMatchDifferentWorkflowKeepsTextVerbatim() throws IOException {
    var verifier =
        new TextFormat("log")
            .verifier()
            .approvedDirectory(directory)
            .receivedDirectory(directory)
            .build();

    var missing =
        assertInstanceOf(
            VerificationResult.Missing.class,
            verifier
                .prependTransformation(Mapping.<CharSequence, String>shared(CharSequence::toString))
                .verify("run", new StringBuilder("Hello\n  World")));
    assertThat(missing.receivedFile().getFileName().toString(), endsWith(".received.log"));
    assertEquals("Hello\n  World", Files.readString(missing.receivedFile()));

    Files.copy(
        missing.receivedFile(),
        assertInstanceOf(ApprovedLocation.File.class, missing.approvedLocation()).path());
    assertInstanceOf(VerificationResult.Matching.class, verifier.verify("run", "Hello\n  World"));
    assertFalse(Files.exists(missing.receivedFile()));

    assertInstanceOf(VerificationResult.Different.class, verifier.verify("run", "hello\n  World"));
    assertEquals("hello\n  World", Files.readString(missing.receivedFile()));
  }
}
