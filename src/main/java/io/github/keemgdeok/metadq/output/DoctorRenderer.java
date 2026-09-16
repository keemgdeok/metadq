package io.github.keemgdeok.metadq.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.keemgdeok.metadq.core.TableEvidence;
import io.github.keemgdeok.metadq.core.TableEvidence.ColumnEvidence;
import io.github.keemgdeok.metadq.core.TableEvidence.MetricCoverage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DoctorRenderer {
  public String render(TableEvidence evidence, OutputFormat format) {
    if (format == OutputFormat.JSON) {
      try {
        return JsonSupport.mapper()
            .writeValueAsString(new DoctorDocument(1, evidence, "No content data files opened."));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Could not render doctor JSON", exception);
      }
    }
    return renderText(evidence);
  }

  private String renderText(TableEvidence evidence) {
    StringBuilder output = new StringBuilder();
    output.append(String.format(Locale.ROOT, "%-10s %s%n", "TABLE", evidence.tableName()));
    output.append(String.format(Locale.ROOT, "%-10s v%d%n", "FORMAT", evidence.formatVersion()));
    if (evidence.hasCurrentSnapshot()) {
      output.append(
          String.format(
              Locale.ROOT,
              "%-10s %d at %s (%s)%n",
              "SNAPSHOT",
              evidence.snapshotId(),
              evidence.snapshotTimestamp(),
              evidence.snapshotOperation()));
    } else {
      output.append(String.format(Locale.ROOT, "%-10s none%n", "SNAPSHOT"));
    }

    long deleteFiles =
        evidence.deleteFileCounts().values().stream().mapToLong(Long::longValue).sum();
    output.append(
        String.format(
            Locale.ROOT,
            "%-10s %d data, %d delete, %d bytes referenced%n",
            "FILES",
            evidence.dataFileCount(),
            deleteFiles,
            evidence.referencedDataBytes()));
    output.append(
        String.format(
            Locale.ROOT,
            "%-10s %d before deletes (according to Iceberg metadata)%n",
            "ROWS",
            evidence.metadataRecordCount()));
    for (Map.Entry<String, Long> entry : evidence.deleteFileCounts().entrySet()) {
      output.append(
          String.format(Locale.ROOT, "%-10s %s=%d%n", "DELETE", entry.getKey(), entry.getValue()));
    }

    output.append(System.lineSeparator());
    output.append(
        String.format(
            Locale.ROOT,
            "%-24s %-16s %-16s %-16s %-16s%n",
            "COLUMN",
            "VALUE",
            "NULL",
            "NAN",
            "BOUNDS"));
    for (ColumnEvidence column : evidence.columns().values()) {
      MetricCoverage coverage = column.coverage();
      if (!column.primitive()) {
        output.append(
            String.format(
                Locale.ROOT,
                "%-24s %-16s %-16s %-16s %-16s%n",
                column.name(),
                "unsupported",
                "unsupported",
                "unsupported",
                "unsupported"));
        continue;
      }
      output.append(
          String.format(
              Locale.ROOT,
              "%-24s %-16s %-16s %-16s %-16s%n",
              column.name(),
              coverage(coverage.valueCountFiles(), coverage.totalDataFiles()),
              coverage(coverage.nullCountFiles(), coverage.totalDataFiles()),
              column.nanApplicable()
                  ? coverage(coverage.nanCountFiles(), coverage.totalDataFiles())
                  : "n/a",
              coverage(coverage.boundsFiles(), coverage.totalDataFiles())));
    }

    for (String note : notes(evidence)) {
      output.append(String.format(Locale.ROOT, "%-10s %s%n", "NOTE", note));
    }
    output.append(String.format(Locale.ROOT, "%-10s No content data files opened.%n", "GUARD"));
    return output.toString().stripTrailing();
  }

  private static List<String> notes(TableEvidence evidence) {
    List<String> notes = new ArrayList<>();
    if (evidence.recordCountsInconsistent()) {
      notes.add("row/null checks are not ready: inconsistent metrics");
    } else if (evidence.hasApplicableDeletes()) {
      notes.add("row/null checks are not ready: applicable delete files exist");
    } else {
      notes.add("table.row_count is ready");
    }
    if (evidence.referencedDataBytesInconsistent()) {
      notes.add("referenced byte total is inconsistent");
    }
    for (ColumnEvidence column : evidence.columns().values()) {
      if (column.inconsistentMetrics()) {
        notes.add("column.null_ratio(" + column.name() + ") is not ready: inconsistent metrics");
      } else if (column.primitive() && !column.required() && !column.nullCountsCompleteForRows()) {
        notes.add(
            "column.null_ratio("
                + column.name()
                + ") is not ready: null counts cover "
                + column.coverage().nullCountFiles()
                + "/"
                + column.coverage().totalDataFiles()
                + " files");
      }
    }
    return notes;
  }

  private static String coverage(long count, long total) {
    if (total == 0) {
      return "0/0 (n/a)";
    }
    long percent = Math.round(100.0 * count / total);
    return count + "/" + total + " (" + percent + "%)";
  }

  private record DoctorDocument(int version, TableEvidence table, String guard) {}
}
