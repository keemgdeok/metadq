package io.github.keemgdeok.metadq.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class MetadqCliTest {
  @TempDir Path directory;

  @Test
  void doctorDemoProducesEvidenceReport() {
    Execution execution = execute("doctor", "--demo");

    assertEquals(0, execution.exitCode());
    assertTrue(execution.out().contains("TABLE      demo.events"));
    assertTrue(execution.out().contains("user_id"));
    assertTrue(execution.out().contains("2/3 (67%)"));
    assertTrue(execution.out().contains("No content data files opened."));
  }

  @Test
  void doctorDemoProducesVersionedJson() {
    Execution execution = execute("doctor", "--demo", "--format", "json");

    assertEquals(0, execution.exitCode());
    assertTrue(execution.out().contains("\"version\" : 1"));
    assertTrue(execution.out().contains("\"format_version\" : 2"));
    assertTrue(execution.out().contains("\"null_counts_complete_for_rows\" : false"));
  }

  @Test
  void doctorRejectsAmbiguousOrMissingSource() {
    Execution missing = execute("doctor");
    assertEquals(2, missing.exitCode());
    assertTrue(missing.err().contains("Choose exactly one source"));

    Execution ambiguous =
        execute(
            "doctor", "--demo", "--catalog-properties", "catalog.properties", "--table", "events");
    assertEquals(2, ambiguous.exitCode());
    assertTrue(ambiguous.err().contains("Choose exactly one source"));
  }

  @Test
  void doctorRendersCommandErrorsAsJson() {
    Execution execution = execute("doctor", "--format", "json");

    assertEquals(2, execution.exitCode());
    assertTrue(execution.err().contains("\"status\" : \"ERROR\""));
    assertTrue(execution.err().contains("\"reason_code\" : \"INVALID_ARGUMENT\""));
  }

  @Test
  void checkRejectsRulesBeforeConnectingToCatalog() throws Exception {
    Path invalidRules = directory.resolve("invalid.yml");
    Files.writeString(
        invalidRules,
        """
        version: 1
        rules:
          - id: rows
            type: table.row_count
            min: 1
            unknown: true
        """);

    Execution execution =
        execute(
            "check",
            "--catalog-properties",
            directory.resolve("missing.properties").toString(),
            "--table",
            "analytics.events",
            "--rules",
            invalidRules.toString());

    assertEquals(2, execution.exitCode());
    assertTrue(execution.err().contains("rules[0].unknown"));
    assertTrue(!execution.err().contains("Catalog properties file not found"));
  }

  @Test
  void checkRendersRuleAndCatalogErrorsAsJson() throws Exception {
    Path invalidRules = directory.resolve("invalid-json.yml");
    Files.writeString(invalidRules, "version: 1\nrules: []\n");

    Execution invalidRule =
        execute(
            "check",
            "--catalog-properties",
            directory.resolve("missing.properties").toString(),
            "--table",
            "analytics.events",
            "--rules",
            invalidRules.toString(),
            "--format",
            "json");
    assertEquals(2, invalidRule.exitCode());
    assertTrue(invalidRule.err().contains("\"reason_code\" : \"INVALID_RULE\""));

    Path validRules = directory.resolve("valid.yml");
    Files.writeString(
        validRules,
        """
        version: 1
        rules:
          - id: rows
            type: table.row_count
            min: 1
        """);
    Execution catalogError =
        execute(
            "check",
            "--catalog-properties",
            directory.resolve("missing.properties").toString(),
            "--table",
            "analytics.events",
            "--rules",
            validRules.toString(),
            "--format",
            "json");
    assertEquals(2, catalogError.exitCode());
    assertTrue(catalogError.err().contains("\"reason_code\" : \"CATALOG_ERROR\""));
  }

  private static Execution execute(String... arguments) {
    CommandLine commandLine = Metadq.createCommandLine();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(arguments);
    return new Execution(
        exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private record Execution(int exitCode, String out, String err) {}
}
