# Measured local load results

<!--
Maintenance: Records measured load runs and their limits; scripts and thresholds alone are not
benchmark evidence.
-->

Executed on 2026-10-17 on this ARM64 Mac using Java 25.0.4.1, Boot 4.1.1, k6 2.3.0, real PostgreSQL
18.6, Kafka 4.2.1, Redis 8.10.2 and Elasticsearch 9.4.5. Seven applications and the test broker
share one Java process in the ephemeral browser harness; Keycloak and Elasticsearch use separate
JVMs. These results are not container/Kubernetes capacity figures.

Each sequential run used 10 virtual users for 15 seconds, three public HTTP reads per iteration (catalog list, cached
detail, Elasticsearch search), and a 100 ms think time. Six active synthetic
products were seeded. The setup call is included in request count. No payment transactions or
authentication overhead are represented in this catalog throughput number.

| Server threads | Requests | Requests/s | HTTP p95 ms | HTTP p99 ms | Failed requests |
|----------------|---------:|-----------:|------------:|------------:|----------------:|
| Virtual        |     4051 |     268.00 |        6.60 |       11.01 |           0.00% |
| Platform       |     4081 |     270.03 |        6.19 |        9.20 |           0.00% |

The runs provide short smoke baselines. They include neither sustained saturation nor statistical
repetitions, so they do not establish which thread model is faster. Native harness/shared-JVM
overhead, warmup, GC and background monitoring differ from independently deployed services. No
production SLA or peak-memory conclusion is claimed.

## Reproduction

Build the project/test classpath and native tools as documented in the README. Start the seeded
browser stack with `VIRTUAL_THREADS=true bash scripts/browser-stack.sh`, then:

```bash
k6 run --summary-export .cache/verification/k6-virtual.json load-tests/catalog.js
SERVICE_CLIENT_SECRET=local-test-service-only python3 scripts/metrics-snapshot.py
```

Stop that stack fully, restart with `VIRTUAL_THREADS=false`, and execute the same workload writing
`k6-platform.json`. Keep the same dataset and machine conditions; add warmup, longer durations and
repeated trials before interpreting differences. Override `BASE_URL`, `VUS` and `DURATION` to change
the catalog experiment. `load-tests/checkout.js` separately exercises authenticated asynchronous
checkout using a local customer's token and sufficiently stocked `PRODUCT_ID`; it is not included in
this throughput table.

## Recorded JVM and pool samples

Snapshots were taken at the end of the workload and do not measure peaks. JVM heap/thread values
cover the shared native test process, not one independently deployed service. The JVM thread gauge
counts live platform threads. HikariCP active/idle values refer to catalog's pool at scrape time;
zero active after load is not evidence that no connections were used during requests.

| Model    | JVM heap MiB | Live platform threads | Catalog active / idle connections |
|----------|-------------:|----------------------:|----------------------------------:|
| Virtual  |       177.33 |                   342 |                            0 / 10 |
| Platform |       318.32 |                   386 |                            0 / 10 |

Raw k6 JSON and the metric sample timestamps are retained in [load-results](load-results). Complete
authenticated scrape snapshots remain in ignored `.cache/verification/metrics-*`. They also contain
cache hits/misses, search query timing, Kafka processing duration/lag, outbox and business outcomes;
the live dashboard queries were checked through Grafana. Peak/under-saturation memory, pool
occupancy and virtual-thread population profiling remain future performance work.

The catalog samples measured 1,351 search queries averaging 3.14 ms in virtual mode and 1,360
averaging 2.91 ms in platform mode. Cache counters were 1,345 hits / 5 misses and 1,355 hits / 5
misses respectively. These are cumulative application-side observations including setup, distinct
from end-to-end k6 HTTP percentiles.

## Authenticated checkout smoke

After the browser demonstration, the real k6 checkout script ran two users and four iterations per
scenario against synthetic stock using a PKCE-issued local administrator token (administrators also
have checkout permission). All eight acceptance/outcome checks per scenario passed, with no failed
HTTP requests. Four approvals committed four units; four declines returned stock without leaving
reservations. The approved and declined runs each made 12 HTTP requests, including status polling.
The short runs check behavior; they do not estimate throughput. Raw summaries are
`checkout-approve.json` and `checkout-decline.json` in `load-results`.

For reproduction, obtain a fresh local CUSTOMER or ADMIN access token through the documented PKCE
login and provide sufficient seeded stock:

```bash
ACCESS_TOKEN='<local access token>' PRODUCT_ID='<stocked product UUID>' VUS=2 ITERATIONS=4 SCENARIO=APPROVE k6 run load-tests/checkout.js
ACCESS_TOKEN='<local access token>' PRODUCT_ID='<stocked product UUID>' VUS=2 ITERATIONS=4 SCENARIO=DECLINE k6 run load-tests/checkout.js
```

Never commit tokens or put real customer data in load fixtures. In a separate cumulative native
sample after browser/demo and checkout workloads, Kafka handler averages were 1.29 ms for Order (48
events), 1.70 ms for Inventory (32), 1.93 ms for Payment (16) and 0.75 ms for Notification (48).
These application timers exclude queueing/broker transit and do not represent end-to-end saga
latency. Exact counts, durations and the capture timestamp are in `metrics-summary.json`.
