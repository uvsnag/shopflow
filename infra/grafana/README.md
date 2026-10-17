# Grafana provisioning

TODO (Phase 7): add provisioning files here:

- `datasources/prometheus.yml` — point at http://prometheus:9090
- `dashboards/shopflow.json` — request rate, error rate, response time,
  orders per status (NFR-20)

The folder is mounted into the Grafana container by docker-compose.
