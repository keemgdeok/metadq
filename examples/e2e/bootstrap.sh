#!/usr/bin/env bash
set -euo pipefail

until (echo > /dev/tcp/rest/8181) >/dev/null 2>&1; do
  sleep 1
done

/opt/spark/bin/spark-sql \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.lakehouse=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.lakehouse.type=rest \
  --conf spark.sql.catalog.lakehouse.uri=http://rest:8181 \
  --conf spark.sql.catalog.lakehouse.warehouse=s3://warehouse/ \
  --conf spark.sql.catalog.lakehouse.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
  --conf spark.sql.catalog.lakehouse.s3.endpoint=http://minio:9000 \
  --conf spark.sql.catalog.lakehouse.s3.path-style-access=true \
  --conf spark.sql.defaultCatalog=lakehouse \
  -f /e2e/bootstrap.sql
