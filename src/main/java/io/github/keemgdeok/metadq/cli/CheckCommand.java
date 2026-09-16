package io.github.keemgdeok.metadq.cli;

import io.github.keemgdeok.metadq.config.RuleConfigParser;
import io.github.keemgdeok.metadq.core.ReasonCode;
import io.github.keemgdeok.metadq.core.RuleEvaluator;
import io.github.keemgdeok.metadq.core.RuleResult;
import io.github.keemgdeok.metadq.core.RuleSpec;
import io.github.keemgdeok.metadq.core.RuleSpec.NullRatioRule;
import io.github.keemgdeok.metadq.core.TableEvidence;
import io.github.keemgdeok.metadq.iceberg.CatalogLoader;
import io.github.keemgdeok.metadq.iceberg.EvidenceCollector;
import io.github.keemgdeok.metadq.iceberg.UnsupportedFormatVersionException;
import io.github.keemgdeok.metadq.output.CheckRenderer;
import io.github.keemgdeok.metadq.output.ErrorRenderer;
import io.github.keemgdeok.metadq.output.OutputFormat;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    List<RuleSpec> rules;
    try {
      rules = new RuleConfigParser().parse(rulesPath);
    } catch (Exception exception) {
      return error(ReasonCode.INVALID_RULE, exception);
    }

    TableEvidence evidence;
    try (CatalogLoader.CatalogTable catalogTable =
        CatalogLoader.load(catalogProperties, tableIdentifier)) {
      evidence =
          new EvidenceCollector()
              .collect(tableIdentifier, catalogTable.table(), requestedColumns(rules));
    } catch (UnsupportedFormatVersionException exception) {
      return error(ReasonCode.UNSUPPORTED_FORMAT_VERSION, exception);
    } catch (Exception exception) {
      return error(ReasonCode.CATALOG_ERROR, exception);
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
  }

  private int error(ReasonCode reasonCode, Exception exception) {
    spec.commandLine().getErr().println(new ErrorRenderer().render(reasonCode, exception, format));
    return 2;
  }

  private static Set<String> requestedColumns(List<RuleSpec> rules) {
    Set<String> columns = new LinkedHashSet<>();
    for (RuleSpec rule : rules) {
      if (rule instanceof NullRatioRule nullRatioRule) {
        columns.add(nullRatioRule.column());
      }
    }
    return Set.copyOf(columns);
  }
}
