package io.github.keemgdeok.metadq.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.keemgdeok.metadq.core.RuleSpec.LastCommitAgeRule;
import io.github.keemgdeok.metadq.core.RuleSpec.NullRatioRule;
import io.github.keemgdeok.metadq.core.RuleSpec.RowCountRule;
import io.github.keemgdeok.metadq.core.RuleSpec.SchemaColumnRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleConfigParserTest {
  @TempDir Path directory;
  private int sequence;

  @Test
  void parsesAllFourRules() throws IOException {
    Path rules =
        write(
            """
            version: 1
            rules:
              - id: rows
                type: table.row_count
                min: 1
                max: 10
              - id: nulls
                type: column.null_ratio
                column: user_id
                max: 0.1
              - id: recent
                type: table.last_commit_age
                max: 2h
              - id: schema
                type: schema.column
                column: event_id
                data_type: string
                nullable: false
            """);

    var parsed = new RuleConfigParser().parse(rules);

    assertEquals(4, parsed.size());
    assertInstanceOf(RowCountRule.class, parsed.get(0));
    assertInstanceOf(NullRatioRule.class, parsed.get(1));
    assertInstanceOf(LastCommitAgeRule.class, parsed.get(2));
    assertInstanceOf(SchemaColumnRule.class, parsed.get(3));
  }

  @Test
  void rejectsUnknownPropertiesWithPath() throws IOException {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RuleConfigParser()
                    .parse(
                        write(
                            """
                            version: 1
                            rules:
                              - id: rows
                                type: table.row_count
                                min: 1
                                surprise: true
                            """)));

    assertTrue(error.getMessage().contains("rules[0].surprise"));
  }

  @Test
  void rejectsDuplicateIdsAndInvalidBounds() throws IOException {
    Path duplicate =
        write(
            """
            version: 1
            rules:
              - id: same
                type: table.row_count
                min: 1
              - id: same
                type: table.row_count
                max: 2
            """);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new RuleConfigParser().parse(duplicate))
            .getMessage()
            .contains("duplicate rule id"));

    Path bounds =
        write(
            """
            version: 1
            rules:
              - id: rows
                type: table.row_count
                min: 5
                max: 2
            """);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new RuleConfigParser().parse(bounds))
            .getMessage()
            .contains("min must not exceed max"));
  }

  @Test
  void rejectsInvalidDurationAndDuplicateYamlKeys() throws IOException {
    Path duration =
        write(
            """
            version: 1
            rules:
              - id: recent
                type: table.last_commit_age
                max: PT2H
            """);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new RuleConfigParser().parse(duration))
            .getMessage()
            .contains("positive integer"));

    Path duplicateKey =
        write(
            """
            version: 1
            version: 1
            rules: []
            """);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> new RuleConfigParser().parse(duplicateKey))
            .getMessage()
            .contains("Duplicate field"));
  }

  @Test
  void rejectsOutOfRangeNumbersAndInvalidRuleIds() throws IOException {
    Path negativeRows =
        write(
            """
            version: 1
            rules:
              - id: rows
                type: table.row_count
                min: -1
            """);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> new RuleConfigParser().parse(negativeRows))
            .getMessage()
            .contains("non-negative"));

    Path invalidRatio =
        write(
            """
            version: 1
            rules:
              - id: nulls
                type: column.null_ratio
                column: user_id
                max: 1.01
            """);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> new RuleConfigParser().parse(invalidRatio))
            .getMessage()
            .contains("between 0 and 1"));

    Path invalidId =
        write(
            """
            version: 1
            rules:
              - id: "bad id"
                type: table.row_count
                min: 1
            """);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new RuleConfigParser().parse(invalidId))
            .getMessage()
            .contains("must match"));
  }

  private Path write(String content) throws IOException {
    Path file = directory.resolve("rules-" + sequence++ + ".yml");
    return Files.writeString(file, content);
  }
}
