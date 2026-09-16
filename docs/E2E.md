# Local end-to-end environment

This environment starts MinIO, the Apache Iceberg REST fixture, and Spark.
Spark creates a real Iceberg v2 table with Parquet data in MinIO. `metadq`
then runs `doctor` and all four rule families against that table.

From the repository root:

```console
cd examples/e2e
docker compose up --build
```

The run is successful when the `check` container exits with code `0` and its
four rules report `PASS`. MinIO and the REST catalog remain running for manual
checks. Stop and remove the local data with:

```console
docker compose down --volumes
```

Spark is used only to create the fixture and query its row count. It is not a
runtime dependency of `metadq`.
