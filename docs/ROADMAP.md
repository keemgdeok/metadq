# Roadmap

`metadq` is pre-release. The code, runnable demo, four rules, guarded metadata
path, and executable JAR are implemented.

## Before 0.1.0

- Validate the demo with three Iceberg users. At least two should understand
  `UNKNOWN` and identify a real table or workflow where they would try it.
- Keep the documented MinIO and Iceberg REST Catalog smoke test passing.
- Add cold-process REST/MinIO request and transfer measurements to the published
  local 1k, 10k, and 100k collector benchmark.
- Verify the release JAR and README commands on clean macOS and Linux Java 17
  environments.
- Tag `0.1.0` and attach the runnable JAR and its checksum.

## Later, only from measured demand

- Partition-level checks.
- Snapshot-to-snapshot drift.
- JUnit or SARIF output.
- Direct metadata-location input.

Row scans, a server, a scheduler, a plugin framework, and automatic table
changes remain out of scope.
