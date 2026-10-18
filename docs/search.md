# Search consistency and recovery

PostgreSQL owns catalog data. Kafka ProductChanged events build the Elasticsearch projection. `cf-products-read` and `cf-products-write` resolve to versioned indices; explicit mappings live in `catalog-service/src/main/resources/search/product-index.json`.

Price filters use integer minor units and require a currency (USD by default). Names/descriptions use full-text relevance with a name boost. Category/currency/active fields are keywords/booleans. Results support price/date/relevance sorting, bounded pages of at most 100 and a 10,000-result window. Catalog detail reads cache data; checkout explicitly sends `Cache-Control: no-cache` to obtain authoritative current prices.

Elasticsearch external versions equal PostgreSQL product version + 1. Equal/older updates cannot overwrite newer data. Deactivated products remain indexed as inactive tombstones, so delayed events cannot resurrect them. Search filters inactive products. Search is eventually consistent with Kafka delivery plus the index refresh interval; it never controls checkout prices or stock.

`POST /api/v1/admin/search/rebuild` requires ADMIN. A rebuild walks PostgreSQL in bounded pages into a new index, refreshes it and swaps both aliases atomically. A PostgreSQL advisory lock serializes rebuilds with index consumers across catalog instances. Product writes continue; their durable events apply to the newly selected alias after the lock is released. Prior indices remain for explicit recovery/cleanup. Inspect alias targets before removing old indices. An Elasticsearch alias swap cannot be atomic with a PostgreSQL transaction; the new index remains valid even if the application crashes immediately after the swap, and a repeat rebuild is safe.

If Elasticsearch is unavailable, search returns `503 SEARCH_UNAVAILABLE` and catalog browsing/order pricing remain PostgreSQL-backed. Kafka failures retry and eventually dead-letter as documented in [recovery.md](recovery.md); restore search and replay dead letters or rebuild. No search results are fabricated from an in-memory fallback.
