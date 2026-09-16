# metadq: product and technical design

Last reviewed: 2026-09-15

## 1. Decision

Build `metadq` as a focused, read-only Java CLI that reports what current
Apache Iceberg metadata can establish and evaluates four conservative checks.
The product promise is **zero data-row scans**, not zero I/O: metadata JSON,
manifest lists, and manifests still need to be read.

The project, distribution, and CLI are all named `metadq`. An `oh-my-*` name
would imply a broad toolbox or plugin ecosystem and does not fit this product.

## 2. Product hypothesis

### Problem

Data engineers often launch Spark or SQL for basic questions such as “does the
table have rows?” or “are null metrics complete?”. Iceberg already stores useful
evidence, but interpreting it correctly requires knowledge of partial metrics,
delete files, snapshots, format versions, and writer limitations.

### Differentiation

`metadq` is not another observability platform. It has two small workflows:

1. `doctor` makes the hidden evidence visible and explains gaps.
2. `check` converts supported evidence into deterministic CI results.

Existing data-quality frameworks are broader and often execute data queries;
Iceberg explorers are usually interactive services. The useful niche is a
single runnable JAR, a five-minute demo, no persistent service, and conservative
evidence semantics.

### Honest value claim

The four v0.1 rules cover row-count, null-count, schema, and commit-recency use
cases. They must not be advertised as “60% of DQ checks”: the published
zero-scan study's larger coverage figure includes additional rule families and
cross-snapshot checks that v0.1 will not implement.

## 3. Target user and workflows

**Primary user:** a data engineer operating Iceberg tables through a REST
catalog.

**Discovery job:** understand which checks are possible before writing a rules
file.

**Automation job:** fail CI or orchestration when a metadata-supported invariant
is violated.

```text
                  +------------------+
--demo ---------->|                  |----> doctor report
                  | normalized       |
REST table ------>| TableEvidence    |----> 4 rule evaluators ----> results
                  | (one scan plan)  |
                  +------------------+
```

One command processes one table. Batch scheduling belongs to Airflow, Dagster,
CI, or shell tooling.

## 4. Trust contract

### 4.1 Evidence wording

Results mean “according to the current Iceberg metadata,” not “the physical
rows were independently verified.” Writers and source libraries can emit
incorrect metrics that are internally plausible. `metadq` detects missing and
some contradictory counts, but cannot prove that every source metric is true.

### 4.2 Rule states

Every rule returns exactly one state:

| State | Meaning |
|---|---|
| `PASS` | Available metadata establishes that the rule is satisfied. |
| `FAIL` | Available metadata establishes that the rule is violated. |
| `UNKNOWN` | The request is valid, but evidence cannot establish either outcome. |
| `ERROR` | Configuration, access, compatibility, or execution prevented evaluation. |

Each result includes `rule_id`, `rule_type`, `status`, `observed`, `expected`,
`snapshot_id`, and `evaluated_at`. `reason_code` is emitted when a stable
diagnostic applies; text may abbreviate these fields. Configuration, access,
compatibility, and execution failures stop the command before per-rule output
and return exit code `2` with a concise `ERROR` message.

Stable v0.1 reason codes:

- `ACTIVE_DELETE_FILES`
- `EMPTY_TABLE`
- `MISSING_COLUMN`
- `MISSING_METRICS`
- `INCONSISTENT_METRICS`
- `NO_CURRENT_SNAPSHOT`
- `TYPE_MISMATCH`
- `UNSUPPORTED_COLUMN_TYPE`
- `UNSUPPORTED_FORMAT_VERSION`
- `INVALID_RULE`
- `CATALOG_ERROR`

### 4.3 No-data-file invariant

Both commands may read Iceberg metadata JSON, manifest lists, and manifest
files. They must not open Parquet, ORC, or Avro content data files or execute a
SQL query. Public wording uses “zero data-row scans” or “no content data files
opened,” never “zero I/O,” “free,” or “the data is proven correct.”

Production dependencies must not include Iceberg data readers such as
`iceberg-data`, `iceberg-parquet`, or `iceberg-orc`, nor Spark or Flink.

### 4.4 Delete and metric semantics

- Any applicable positional delete, equality delete, or deletion vector makes
  logical row count and null ratio `UNKNOWN` in v0.1.
- Delete files exposed by multiple scan tasks are deduplicated by content type
  and path before reporting.
- Missing per-file metrics are never filled with zero.
- Negative record/metric counts, overflowing aggregates, or a null-plus-NaN
  count greater than a known value count become `INCONSISTENT_METRICS` rather
  than a false result.
- An optional column's null ratio requires complete per-file null counts.
- A required top-level primitive column may infer zero nulls from the schema
  only when row count is positive and no applicable deletes exist. An empty
  denominator is still `UNKNOWN`.
- Bounds are reported by `doctor` as availability only. Truncated bounds are
  not sufficient for a new v0.1 quality rule.

## 5. `doctor` contract

### Demo mode

```console
java -jar metadq.jar doctor --demo [--format text|json]
```

`--demo` constructs a deterministic Iceberg table using `InMemoryCatalog` and
`InMemoryFileIO`. It stores metadata descriptors and sentinel content-file
paths, but creates no row-bearing data files.

A checked-in v1–v3 metadata directory is deliberately avoided: those format
versions persist fully qualified locations, so copying the tree to another
machine is not a portable demo.

### Real-table mode

```console
java -jar metadq.jar doctor \
  --catalog-properties catalog.properties \
  --table analytics.events \
  [--format text|json]
```

Exactly one source mode is valid: `--demo`, or the pair
`--catalog-properties` and `--table`.

### Minimum report

- table name and format version
- current snapshot ID, timestamp, and operation, or “no current snapshot”
- live data-file count and referenced data bytes
- sum of data-file record counts, labeled as a pre-delete metadata count
- distinct applicable delete-file counts by content type
- for each top-level primitive column, numerator/denominator and percentage of
  live data files containing value, null, NaN, and bounds metrics
- readiness notes for row-count and null-ratio rules
- the explicit statement “No content data files opened.”

`doctor` returns `0` when it can generate a report, even if evidence is
incomplete. Invalid arguments, catalog/access failures, and unsupported formats
return `2`.

## 6. `check` contract

```console
java -jar metadq.jar check \
  --catalog-properties catalog.properties \
  --table analytics.events \
  --rules metadq.yml \
  [--format text|json]
```

| Rule | Inputs | PASS / FAIL evidence | UNKNOWN conditions |
|---|---|---|---|
| `table.row_count` | optional `min`, optional `max` | Sum of live data-file record counts is within/outside inclusive bounds. No current snapshot means exact zero. | Applicable delete content or inconsistent counts. |
| `column.null_ratio` | `column`, `max` in `[0,1]` | Complete null evidence divided by positive row count is at/below or above `max`. Missing requested column is `FAIL`. | Missing/inconsistent metrics, empty table, nested/unsupported type, or applicable delete content. |
| `table.last_commit_age` | `max` duration | Current snapshot commit age is at/below or above `max`. | No current snapshot. This is commit recency, not event-time freshness. |
| `schema.column` | `column`; optional `data_type`, optional `nullable` | Current top-level schema matches or violates the contract. Missing requested column is `FAIL`. | Unsupported rule syntax is `ERROR`, not `UNKNOWN`. |

Validation remains deliberately small:

- `table.row_count` requires at least one of `min` or `max`; `min <= max`.
- Durations are a positive integer followed by `s`, `m`, `h`, or `d`.
- Column names are case-sensitive.
- `column.null_ratio` supports top-level primitive columns only.
- Rule IDs are unique and match `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`.
- Unknown keys and rule types are configuration errors.

### Exit codes

| Code | Overall result |
|---:|---|
| `0` | All rules are `PASS`. |
| `1` | At least one `FAIL`, and no `ERROR`. |
| `2` | At least one `ERROR`. |
| `3` | No `FAIL`/`ERROR`, but at least one `UNKNOWN`. |

Overall precedence is `ERROR`, `FAIL`, `UNKNOWN`, `PASS`.

After command arguments are parsed, `--format json` also renders rule,
catalog, and unsupported-format failures as a versioned error document on
standard error. Command-line syntax errors raised by Picocli before command
execution remain text diagnostics.

## 7. Configuration

`--catalog-properties` is a Java properties file passed to Iceberg's catalog
loader. v0.1 bundles REST-catalog support only. Credentials, REST behavior, and
object-store access remain Iceberg concerns; `metadq` does not add a credential
system.

Minimal rule file:

```yaml
version: 1
rules:
  - id: at-least-one-row
    type: table.row_count
    min: 1
  - id: no-null-customer-id
    type: column.null_ratio
    column: customer_id
    max: 0
  - id: committed-recently
    type: table.last_commit_age
    max: 2h
  - id: customer-id-contract
    type: schema.column
    column: customer_id
    data_type: long
    nullable: false
```

## 8. Minimal implementation design

Use Java 17 and Apache Iceberg 1.11.x, Picocli, Jackson databind/YAML, and JUnit.
Bundle Iceberg's AWS module for the single v0.1 storage profile: REST catalog
with S3 or S3-compatible storage. Use Gradle's `java` and `application` plugins
plus one conventional fat-JAR packaging plugin. Do not add other cloud SDKs,
Spring, Lombok, dependency injection, native image, or a plugin system.

```text
src/main/java/io/github/keemgdeok/metadq/
  cli/            # arguments, orchestration, exit code
  config/         # strict YAML parsing and validation
  core/           # evidence/result records and four pure evaluators
  iceberg/        # catalog loading and one metadata collection pass
  output/         # deterministic text and JSON
```

Both commands share one immutable `TableEvidence`. The collector starts with
`table.newScan().includeColumnStats(requestedColumns).planFiles()` in a
try-with-resources block, using `FileScanTask.file()` and
`FileScanTask.deletes()`. Do not write a custom manifest parser unless a
published benchmark identifies this API as the bottleneck.

## 9. Compatibility boundary

- Test format v1 and v2 before claiming support.
- Add v3 only after a deletion-vector fixture passes.
- Reject untested newer versions with a clear error.
- Production input is REST catalog with S3/S3-compatible `S3FileIO`; tests and
  `doctor --demo` use in-memory Iceberg APIs. Do not imply that every object
  store is bundled.
- Direct metadata URLs, Glue, Hive, Hadoop, Spark, Delta Lake, and Hudi are out
  of v0.1.
- Read-only behavior is absolute: no table property changes, rewrites,
  compaction, snapshot expiration, or other commits.

## 10. Verification

The highest-value test wraps Java `FileIO`, allows only known metadata paths,
and fails immediately if a content-file sentinel is opened. Run both commands
and all four rule families behind this guard.

Required fixture states:

1. Healthy table with complete metrics.
2. Rule-violating table.
3. Partial/missing metrics.
4. Empty table and no current snapshot.
5. Applicable positional/equality deletes; deletion vector when v3 is added.
6. Schema mismatch.
7. Internally contradictory counts.

Record cold-process wall time and peak memory for metadata representing 1k,
10k, and 100k live file tasks. Include hardware, OS, JVM, and Iceberg version.
This is a manual release benchmark, not a brittle CI threshold.

## 11. Release gates

Before implementing all rules, a two-day vertical slice must show that:

- `java -jar metadq.jar doctor --demo` works without services or credentials;
- the report makes evidence gaps understandable in under five minutes;
- the no-content-file guard passes; and
- at least two of three Iceberg users say they would try it on a real table or
  identify a concrete workflow where it would help.

If this validation fails, revise positioning/output before adding features.

v0.1 is complete when the demo and real REST workflows work from a clean Java
17 machine, four rule states and exit codes match this contract, text/JSON are
deterministic, the guard and fixtures pass, measured benchmarks are published,
and a tagged release contains one runnable fat JAR.

GitHub stars are not an engineering acceptance metric. They are a distribution
outcome; reproducible value and honest limitations are the release criteria.

## 12. Explicit non-goals

Not in v0.1:

- value min/max rules
- historical drift or anomaly detection
- distinct count, uniqueness, percentile, regex, enum, or referential checks
- partition-level rules or multi-table configuration
- direct metadata-location input or row-scan fallback
- scheduler, daemon, API server, UI, database, telemetry, or alerts
- plugins, JUnit/SARIF integrations, or automatic remediation
- native image, Docker image, Homebrew, or Maven Central publication

## 13. Risks and stop conditions

| Risk | v0.1 response |
|---|---|
| Metrics are absent or partial | Report coverage; return `UNKNOWN` for affected checks. |
| Metrics are internally contradictory | Return `INCONSISTENT_METRICS`. |
| Writer emitted plausible but wrong metrics | State the source-metric limitation; do not claim row verification. |
| Deletes produce false certainty | Mark row/null checks `UNKNOWN`. |
| Metadata planning is slow at scale | Measure; optimize only after profiling. |
| Maintenance commits look like fresh data | Call the rule `last_commit_age` and document its meaning. |
| Scope becomes an observability platform | Keep two commands, four rules, and no persistent service. |

If the no-content-file guard cannot be made reliable, or a result cannot
explain its evidence, the affected capability does not ship.

## 14. Sources checked

- [Apache Iceberg Java API](https://iceberg.apache.org/docs/latest/api/)
- [Apache Iceberg TableScan API](https://iceberg.apache.org/javadoc/latest/org/apache/iceberg/TableScan.html)
- [Apache Iceberg 1.11.0 release](https://iceberg.apache.org/releases/)
- [Apache Iceberg configuration](https://iceberg.apache.org/docs/nightly/configuration/)
- [Apache Iceberg specification](https://iceberg.apache.org/spec/)
- [Iceberg issue #17558: incorrect null counting with missing Parquet stats](https://github.com/apache/iceberg/issues/17558)
- [Zero-Scan Data Quality paper](https://arxiv.org/html/2605.30308)
- [Nimtable](https://github.com/nimtable/nimtable) and
  [TableSleuth](https://github.com/jamesbconner/TableSleuth) for adjacent-tool
  comparison

These sources establish feasibility and caveats. Remaining release work is in
[`ROADMAP.md`](ROADMAP.md).
