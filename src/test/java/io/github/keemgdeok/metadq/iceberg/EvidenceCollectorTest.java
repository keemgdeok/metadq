package io.github.keemgdeok.metadq.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.junit.jupiter.api.Test;

class EvidenceCollectorTest {
  @Test
  void collectsDemoEvidenceWithoutOpeningContentFiles() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      InMemoryFileIO io = (InMemoryFileIO) demo.table().io();
      for (String contentPath : demo.contentPaths()) {
        assertFalse(io.fileExists(contentPath));
      }

      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertEquals(2, evidence.formatVersion());
      assertEquals(3, evidence.dataFileCount());
      assertEquals(6_144, evidence.referencedDataBytes());
      assertEquals(1_200, evidence.metadataRecordCount());
      assertFalse(evidence.hasApplicableDeletes());
      assertFalse(evidence.hasAnyInconsistentMetrics());
      assertEquals(2, evidence.columns().get("user_id").coverage().nullCountFiles());
      assertFalse(evidence.columns().get("user_id").nullCountsCompleteForRows());

      for (String contentPath : demo.contentPaths()) {
        assertThrows(NotFoundException.class, () -> io.newInputFile(contentPath));
      }
    }
  }

  @Test
  void deduplicatesOneDeleteFileAppliedToMultipleDataFiles() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      var deleteFile =
          FileMetadata.deleteFileBuilder(demo.table().spec())
              .ofPositionDeletes()
              .withPath("memory://metadq/content/delete-positions.parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(128)
              .withRecordCount(1)
              .build();
      demo.table().newRowDelta().addDeletes(deleteFile).commit();

      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertEquals(1L, evidence.deleteFileCounts().get("position_deletes"));
      assertEquals(3, evidence.dataFileCount());
    }
  }

  @Test
  void supportsIcebergFormatV1() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create(1)) {
      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertEquals(1, evidence.formatVersion());
      assertEquals(1_200, evidence.metadataRecordCount());
    }
  }

  @Test
  void handlesTableWithoutSnapshotAsEmpty() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.createEmpty()) {
      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertFalse(evidence.hasCurrentSnapshot());
      assertEquals(0, evidence.dataFileCount());
      assertEquals(0, evidence.metadataRecordCount());
    }
  }

  @Test
  void rejectsUntestedFormatVersions() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.createEmpty(3)) {
      assertThrows(
          UnsupportedFormatVersionException.class,
          () -> new EvidenceCollector().collect("demo.events", demo.table()));
    }
  }

  @Test
  void marksContradictoryCountsAsInconsistent() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      var contradictory =
          DataFiles.builder(demo.table().spec())
              .withPath("memory://metadq/content/contradictory.parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(128)
              .withRecordCount(10)
              .withMetrics(new Metrics(10L, null, Map.of(2, 10L), Map.of(2, 11L), null))
              .build();
      demo.table().newAppend().appendFile(contradictory).commit();

      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertFalse(evidence.recordCountsInconsistent());
      assertTrue(evidence.hasAnyInconsistentMetrics());
      assertTrue(evidence.columns().get("user_id").inconsistentMetrics());
    }
  }

  @Test
  void marksPositiveNullCountForRequiredColumnAsInconsistent() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      var contradictory =
          DataFiles.builder(demo.table().spec())
              .withPath("memory://metadq/content/required-null.parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(128)
              .withRecordCount(10)
              .withMetrics(new Metrics(10L, null, Map.of(1, 10L), Map.of(1, 1L), null))
              .build();
      demo.table().newAppend().appendFile(contradictory).commit();

      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertTrue(evidence.columns().get("event_id").inconsistentMetrics());
      assertFalse(evidence.recordCountsInconsistent());
    }
  }

  @Test
  void collectsStatisticsOnlyForRequestedColumns() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      var evidence =
          new EvidenceCollector().collect("demo.events", demo.table(), Set.of("user_id"));

      assertEquals(2, evidence.columns().get("user_id").coverage().nullCountFiles());
      assertEquals(0, evidence.columns().get("event_id").coverage().nullCountFiles());
      assertEquals(1_200, evidence.metadataRecordCount());
    }
  }

  @Test
  void marksAggregateOverflowAsInconsistent() throws Exception {
    try (DemoTableFactory.DemoTable demo = DemoTableFactory.create()) {
      var oversized =
          DataFiles.builder(demo.table().spec())
              .withPath("memory://metadq/content/oversized.parquet")
              .withFormat(FileFormat.PARQUET)
              .withFileSizeInBytes(Long.MAX_VALUE)
              .withRecordCount(Long.MAX_VALUE)
              .withMetrics(
                  new Metrics(
                      Long.MAX_VALUE,
                      null,
                      Map.of(2, Long.MAX_VALUE),
                      Map.of(2, Long.MAX_VALUE),
                      null))
              .build();
      demo.table().newAppend().appendFile(oversized).commit();

      var evidence = new EvidenceCollector().collect("demo.events", demo.table());

      assertTrue(evidence.recordCountsInconsistent());
      assertTrue(evidence.referencedDataBytesInconsistent());
      assertEquals(Long.MAX_VALUE, evidence.referencedDataBytes());
      assertEquals(Long.MAX_VALUE, evidence.metadataRecordCount());
      assertEquals(Long.MAX_VALUE, evidence.columns().get("user_id").nullCount());
      assertTrue(evidence.columns().get("user_id").inconsistentMetrics());
    }
  }
}
