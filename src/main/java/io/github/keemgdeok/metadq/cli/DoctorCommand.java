package io.github.keemgdeok.metadq.cli;

import io.github.keemgdeok.metadq.core.ReasonCode;
import io.github.keemgdeok.metadq.core.TableEvidence;
import io.github.keemgdeok.metadq.iceberg.CatalogLoader;
import io.github.keemgdeok.metadq.iceberg.DemoTableFactory;
import io.github.keemgdeok.metadq.iceberg.EvidenceCollector;
import io.github.keemgdeok.metadq.iceberg.UnsupportedFormatVersionException;
import io.github.keemgdeok.metadq.output.DoctorRenderer;
import io.github.keemgdeok.metadq.output.ErrorRenderer;
import io.github.keemgdeok.metadq.output.OutputFormat;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "doctor",
    description = "Explain the data-quality evidence available in Iceberg metadata.",
    mixinStandardHelpOptions = true)
final class DoctorCommand implements Callable<Integer> {
  @Option(names = "--demo", description = "Run a zero-configuration in-memory demo.")
  private boolean demo;

  @Option(names = "--catalog-properties", paramLabel = "FILE")
  private Path catalogProperties;

  @Option(names = "--table", paramLabel = "IDENTIFIER")
  private String tableIdentifier;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output: ${COMPLETION-CANDIDATES}.")
  private OutputFormat format;

  @Spec private CommandSpec spec;

  @Override
  public Integer call() {
    try {
      validateSource();
    } catch (IllegalArgumentException exception) {
      return error(ReasonCode.INVALID_ARGUMENT, exception);
    }

    TableEvidence evidence;
    try {
      evidence = collectEvidence();
    } catch (UnsupportedFormatVersionException exception) {
      return error(ReasonCode.UNSUPPORTED_FORMAT_VERSION, exception);
    } catch (Exception exception) {
      return error(ReasonCode.CATALOG_ERROR, exception);
    }
    spec.commandLine().getOut().println(new DoctorRenderer().render(evidence, format));
    return 0;
  }

  private void validateSource() {
    boolean catalogMode = catalogProperties != null || tableIdentifier != null;
    if (demo && catalogMode) {
      throw new IllegalArgumentException(
          "Choose exactly one source: --demo, or --catalog-properties with --table");
    }
    if (!demo && (catalogProperties == null || tableIdentifier == null)) {
      throw new IllegalArgumentException(
          "Choose exactly one source: --demo, or --catalog-properties with --table");
    }
  }

  private TableEvidence collectEvidence() throws Exception {
    EvidenceCollector collector = new EvidenceCollector();
    if (demo) {
      try (DemoTableFactory.DemoTable demoTable = DemoTableFactory.create()) {
        return collector.collect("demo.events", demoTable.table());
      }
    }
    try (CatalogLoader.CatalogTable catalogTable =
        CatalogLoader.load(catalogProperties, tableIdentifier)) {
      return collector.collect(tableIdentifier, catalogTable.table());
    }
  }

  private int error(ReasonCode reasonCode, Exception exception) {
    spec.commandLine().getErr().println(new ErrorRenderer().render(reasonCode, exception, format));
    return 2;
  }
}
