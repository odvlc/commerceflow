# Observability

<!--
Maintenance: Explains authenticated metric collection and dashboards without putting caller
identities or secrets into metric labels.
-->

All applications expose health/liveness/readiness and authenticated Prometheus metrics. Metric scraping requires the
service-account `commerceflow.internal` scope. Prometheus obtains and refreshes a Keycloak client-credentials token; it
does not scrape with a customer token. `scripts/render-monitoring.py` keeps the ignored scrape configuration consistent
with local secret overrides.

Boot/Micrometer provide HTTP count/errors/latency histograms, JVM/GC/thread and HikariCP metrics. Kafka client listeners
provide consumer lag; consumers record processing time and replay counts. Outbox gauges report pending rows, with
delivered/failure counters. Committed order outcomes/saga duration, inventory reservation results and payment outcomes
are recorded only after their business transactions commit. Catalog instruments cache hits/misses/failures and search
latency/failures.

Provisioned Grafana dashboards are **Service Health** and **Order Processing**. Alerts cover HTTP error ratio, p95
latency, pending outbox backlog and Kafka lag. Idle Kafka rolling-window gauges may report NaN before/after samples; an
absent/idle series is not fabricated as a successful measurement. JSON logs carry correlation IDs from HTTP to outbox
envelopes and consumer handlers. IDs are not metric labels.

Native verification executed Prometheus 3.15.0, Grafana 13.2.4 and authenticated scrapes of all seven application ports.
Grafana loaded both dashboards, its datasource health was OK, and its datasource proxy returned two real order-outcome
series after the browser purchase/decline tests. Four alert rules and the rendered configuration passed `promtool`.

For native reproduction after starting the browser stack, install the named official native distributions under
`.cache/tools`, then:

```bash
SERVICE_CLIENT_SECRET=local-test-service-only python3 scripts/render-monitoring.py native
.cache/tools/prometheus-3.15.0.darwin-arm64/prometheus --config.file=.cache/monitoring/prometheus.yaml --storage.tsdb.path=.cache/monitoring/prometheus-data --web.listen-address=127.0.0.1:9090
# In another terminal; use an absolute homepath for archive assets:
.cache/tools/grafana-13.2.4/bin/grafana server --homepath="$PWD/.cache/tools/grafana-13.2.4" --config="$PWD/.cache/monitoring/grafana.ini"
SERVICE_CLIENT_SECRET=local-test-service-only python3 scripts/metrics-snapshot.py
```

Native credentials are synthetic test fixtures. Compose uses `infra/.env` and its own documented local defaults instead.
Generated monitoring files, scrape snapshots and test logs remain ignored. Containerized and Kubernetes monitoring still
require deployment verification.
