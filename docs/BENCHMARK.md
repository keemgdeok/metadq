# Metadata scalability benchmark

The benchmark creates Iceberg metadata for 1k, 10k, and 100k live data-file
entries. The content files are sentinels and are never opened. It measures one
metadata collection pass for three rule shapes after a small JVM warm-up. Each
reported runtime is the median of three collection passes:

- `row_count`: no column statistics requested
- `one_column`: one null-ratio column requested
- `all_columns`: statistics for all ten primitive columns requested

Run it on Java 17 with:

```console
./gradlew metadataBenchmark
```

The Gradle task caps the benchmark process heap at 2 GiB.

Record the Git revision, hardware, OS, JVM, Iceberg version, heap settings, and
raw CSV output. Do not compare these numbers directly with a full row scan:
the benchmark evaluates Iceberg metadata and does not validate content rows.

The local benchmark does not report object-store request counts or transferred
bytes. Those require request instrumentation against the end-to-end MinIO
environment and must be published separately.

## Reference run

Measured on September 16, 2026:

- Apple M4, 16 GiB RAM, arm64
- macOS 26.2 host and Docker 29.4.0
- Eclipse Temurin 17.0.20
- Apache Iceberg 1.11.0
- 2 GiB maximum benchmark heap
- Source revision `2413de1`

| Live files | Mode | Median runtime | Peak heap |
| ---: | --- | ---: | ---: |
| 1,000 | row count | 6 ms | 10 MiB |
| 1,000 | one column | 4 ms | 11 MiB |
| 1,000 | all columns | 3 ms | 12 MiB |
| 10,000 | row count | 6 ms | 16 MiB |
| 10,000 | one column | 9 ms | 24 MiB |
| 10,000 | all columns | 11 ms | 26 MiB |
| 100,000 | row count | 75 ms | 71 MiB |
| 100,000 | one column | 81 ms | 128 MiB |
| 100,000 | all columns | 127 ms | 247 MiB |

Sub-10 ms results are sensitive to timer and runtime noise. The 100k-file rows
are the useful comparison for column-statistics selection.
