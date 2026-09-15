package io.github.keemgdeok.metadq.cli;

import io.github.keemgdeok.metadq.config.RuleConfigParser;
import io.github.keemgdeok.metadq.core.RuleEvaluator;
import io.github.keemgdeok.metadq.core.RuleResult;
import io.github.keemgdeok.metadq.core.RuleSpec;
import io.github.keemgdeok.metadq.core.TableEvidence;
import io.github.keemgdeok.metadq.iceberg.CatalogLoader;
import io.github.keemgdeok.metadq.iceberg.EvidenceCollector;
import io.github.keemgdeok.metadq.output.CheckRenderer;
import io.github.keemgdeok.metadq.output.OutputFormat;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "check",
    description = "Evaluate rules against one Iceberg table's metadata.",
    mixinStandardHelpOptions = true)
final class CheckCommand implements Callable<Integer> {
  @Option(names = "--catalog-properties", required = true, paramLabel = "FILE")
  private Path catalogProperties;

  @Option(names = "--table", required = true, paramLabel = "IDENTIFIER")
  private String tableIdentifier;

  @Option(names = "--rules", required = true, paramLabel = "FILE")
  private Path rulesPath;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output: ${COMPLETION-CANDIDATES}.")
  private OutputFormat format;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    try {
      List<RuleSpec> rules = new RuleConfigParser().parse(rulesPath);
      TableEvidence evidence;
      try (CatalogLoader.CatalogTable catalogTable =
          CatalogLoader.load(catalogProperties, tableIdentifier)) {
        evidence = new EvidenceCollector().collect(tableIdentifier, catalogTable.table());
      }

      Instant evaluatedAt = Instant.now();
      Clock clock = Clock.fixed(evaluatedAt, ZoneOffset.UTC);
      List<RuleResult> results = new ArrayList<>();
      for (RuleSpec rule : rules) {
        results.add(RuleEvaluator.evaluate(rule, evidence, clock));
      }
      spec.commandLine()
          .getOut()
          .println(
              new CheckRenderer()
                  .render(
                      evidence.tableName(),
                      evidence.snapshotId(),
                      evaluatedAt,
                      List.copyOf(results),
                      format));
      return RuleEvaluator.exitCode(results);
    } catch (Exception exception) {
      spec.commandLine().getErr().println("ERROR: " + errorMessage(exception));
      return 2;
    }
  }

  private static String errorMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }
}
