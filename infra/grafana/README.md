# Grafana provisioning

Mounted into the Grafana container at `/etc/grafana/provisioning` by
docker-compose. Log in with admin/admin at http://localhost:3001.

- `datasources/prometheus.yml` — Prometheus datasource (http://prometheus:9090)
- `dashboards/dashboards.yml` — file provider that loads every dashboard JSON
  in the same folder
- `dashboards/shopflow.json` — NFR-20 dashboard: request rate, error rate,
  mean and p95 response time, services up, Kafka listener throughput, orders
  per status, provider circuit-breaker state, dead-letter traffic

Where the numbers come from:

- `http_server_requests_seconds_*` — Spring Boot Actuator + Micrometer in every
  service; p95 needs the histogram buckets enabled in `shopflow-defaults.yml`
  (`management.metrics.distribution.percentiles-histogram.http.server.requests`).
- `shopflow_orders{status=...}` — a Micrometer `MultiGauge` in order-service
  (`OrderMetrics`) refreshed from `select status, count(*)` every 15 s.
- `resilience4j_circuitbreaker_state{name="provider"}` — payment-service's
  circuit breaker towards the fake bank (NFR-11).
- `spring_kafka_template_seconds_count{topic=~".*\.DLT"}` — messages parked on
  a dead letter topic after 3 failed attempts.
