CREATE NAMESPACE IF NOT EXISTS analytics;
DROP TABLE IF EXISTS analytics.events;
CREATE TABLE analytics.events (
  event_id STRING NOT NULL,
  user_id BIGINT,
  score DOUBLE
) USING iceberg
TBLPROPERTIES (
  'format-version' = '2',
  'write.metadata.metrics.default' = 'full'
);
INSERT INTO analytics.events VALUES
  ('event-1', 1, 10.0),
  ('event-2', 2, 20.0),
  ('event-3', NULL, 30.0),
  ('event-4', 4, 40.0);
SELECT COUNT(*) AS row_count FROM analytics.events;
