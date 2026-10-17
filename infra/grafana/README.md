# Grafana provisioning

Mounted into the Grafana container at `/etc/grafana/provisioning` by
docker-compose. Log in with admin/admin at http://localhost:3001.

- `datasources/prometheus.yml` — Prometheus datasource (http://prometheus:9090)
- `dashboards/dashboards.yml` — file provider that loads every dashboard JSON
  in the same folder
- `dashboards/shopflow.json` — NFR-20 dashboard: request rate, error rate,
  response time, services up, Kafka listener throughput

Still TODO for NFR-20: an "orders per status" panel needs a custom gauge
exported from order-service (e.g. a Micrometer `MultiGauge` over
`select status, count(*)`), and p95 panels need histogram buckets
(`management.metrics.distribution.percentiles-histogram.http.server.requests=true`).
