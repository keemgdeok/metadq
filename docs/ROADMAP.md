# Roadmap

`metadq` is pre-release. The code, runnable demo, four rules, guarded metadata
path, and executable JAR are implemented.

## Before 0.1.0

- Keep the documented MinIO and Iceberg REST Catalog smoke test passing.
- Publish the release JAR only after its checksum and demo pass on Linux and
  macOS with Java 17.
- Keep known limitations in the README and release notes.
- If external Iceberg-user validation remains limited, state that explicitly in
  the release notes.
- Tag `v0.1.0` and attach the runnable JAR and its checksum.

## After 0.1.0

- Validate the demo with Iceberg users and record concrete tables or workflows
  where they would try it.
- Add cold-process REST/MinIO request and transfer measurements to the published
  1k, 10k, and 100k collector benchmark.

## Later, only from measured demand

- Partition-level checks.
- Snapshot-to-snapshot drift.
- JUnit or SARIF output.
- Direct metadata-location input.

Row scans, a server, a scheduler, a plugin framework, and automatic table
changes remain out of scope.
