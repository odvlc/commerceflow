# HTTP contracts

All paths are relative to `/api/v1`. OpenAPI for each application is generated at `/v3/api-docs`; Swagger UI is `/docs`
on catalog and the other configured service UIs. Customer identity comes from the JWT UUID subject. Pagination is
zero-based, `size` 1–100. Errors use `application/problem+json` with `code` and correlation ID; clients may provide a
validated `X-Correlation-ID`.

| Endpoint                             | Authorization / request                                                                                                                                                           |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /products`                      | Public, `page,size,category`; active products only.                                                                                                                               |
| `GET /products/{id}`                 | Public; `Cache-Control: no-cache` requests current PostgreSQL data. Inactive/missing → 404.                                                                                       |
| `GET /products/search`               | Public; `q,category,minPrice,maxPrice,currency,page,size,sort`. Sort: relevance/priceAsc/priceDesc/newest; currency defaults USD; 10,000-result window. Search unavailable → 503. |
| `GET /admin/products`                | ADMIN; includes inactive products for management.                                                                                                                                 |
| `POST /admin/products`               | ADMIN; `Idempotency-Key` required. SKU/name/description/category/price/currency/active. Response 201 with Location and ETag.                                                      |
| `PUT /admin/products/{id}`           | ADMIN; same product fields, quoted `If-Match` version required. Stale versions → 409.                                                                                             |
| `POST /admin/search/rebuild`         | ADMIN; builds a new versioned search index and swaps aliases.                                                                                                                     |
| `GET /cart`                          | CUSTOMER/ADMIN; user-scoped items/version/TTL.                                                                                                                                    |
| `POST /cart/items`                   | CUSTOMER/ADMIN; `{productId,quantity}` adds units; required request idempotency key.                                                                                              |
| `PATCH /cart/items/{id}`             | CUSTOMER/ADMIN; `{quantity}` sets units; required request idempotency key.                                                                                                        |
| `DELETE /cart/items/{id}`            | CUSTOMER/ADMIN; required request idempotency key.                                                                                                                                 |
| `POST /orders`                       | CUSTOMER/ADMIN; required idempotency key; `{items:[{productId,quantity}],paymentScenario}`. Trusted server prices only. Same customer/key with changed contents → 409.            |
| `GET /orders` and `GET /orders/{id}` | CUSTOMER/ADMIN; always restricted to caller ownership. Different owner → 404.                                                                                                     |
| `POST /orders/{id}/cancel`           | Owner; idempotent cancellation before commitment/fulfillment. Confirmed/committing orders → 409; refunds remain an internal simulated operation.                                  |
| `GET /admin/orders`                  | ADMIN/SUPPORT; paginated cross-customer monitoring.                                                                                                                               |
| `GET/PUT /admin/inventory/{id}`      | ADMIN; PUT `{availableQuantity,version?}`. Omit version only for initial creation; existing updates use current version.                                                          |
| `GET /admin/payments/{orderId}`      | ADMIN; payment and authorization/refund attempts.                                                                                                                                 |
| `GET /notifications`                 | Authenticated customer subject; paginated owned local messages.                                                                                                                   |

Order scenarios are APPROVE, DECLINE, TIMEOUT, DELAY; no real payment provider or card data is involved. Order creation
validates nonempty unique items, maximum 100 products and quantities 1–1000, then obtains current catalog prices before
committing the order/outbox.

Example product body:

```json
{
  "sku": "KEYBOARD-01",
  "name": "Studio keyboard",
  "description": "Quiet mechanical keys",
  "category": "Workspace",
  "price": "129.00",
  "currency": "USD",
  "active": true
}
```

Internal endpoints require `commerceflow.internal` scope and are never gateway routes: reserve/commit/release under
`/internal/v1/reservations/{orderId}` and authorize/cancel under `/internal/v1/payments/{orderId}`. Business IDs and
request hashes prevent repeated stock/payment effects. Routine workflows use Kafka rather than direct HTTP commands.
