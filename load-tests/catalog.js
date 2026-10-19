// Separates search and cached-detail latency in k6; use synthetic local fixtures and retain
// measured output before claiming results.
import http from 'k6/http';
import {check, sleep} from 'k6';
import {Trend} from 'k6/metrics';

const searchLatency = new Trend('search_latency', true);
const detailLatency = new Trend('cached_detail_latency', true);
const base = __ENV.BASE_URL || 'http://localhost:8080';
export const options = {
    vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '15s',
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
    thresholds: {http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000']},
};

export function setup() {
    const response = http.get(`${base}/api/v1/products?size=1`);
    if (response.status !== 200 || !response.json('items').length) throw new Error('Seed at least one active USD product before benchmarking.');
    // Select a real seeded product once so detail timings do not mix missing-product failures with
    // cache behavior.
    return {productId: response.json('items.0.id')};
}

export default function (data) {
    const list = http.get(`${base}/api/v1/products?size=20`, {tags: {operation: 'catalog-list'}});
    check(list, {'catalog available': r => r.status === 200});
    const detail = http.get(`${base}/api/v1/products/${data.productId}`, {tags: {operation: 'cached-detail'}});
    // Record detail separately from search so one operation does not hide the latency of the other.
    detailLatency.add(detail.timings.duration);
    check(detail, {'product available': r => r.status === 200});
    const search = http.get(`${base}/api/v1/products/search?q=keyboard&currency=USD&size=10`, {tags: {operation: 'search'}});
    searchLatency.add(search.timings.duration);
    check(search, {'search available': r => r.status === 200});
    sleep(0.1);
}
