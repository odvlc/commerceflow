import http from 'k6/http';
import {check, sleep} from 'k6';

const base = __ENV.BASE_URL || 'http://localhost:8080';
export const options = {
    vus: Number(__ENV.VUS || 2),
    iterations: Number(__ENV.ITERATIONS || 10),
    thresholds: {checks: ['rate==1']}
};

export function setup() {
    if (!__ENV.ACCESS_TOKEN || !__ENV.PRODUCT_ID) throw new Error('Provide ACCESS_TOKEN for a local CUSTOMER and PRODUCT_ID with sufficient seeded stock.');
}

export default function () {
    const headers = {
        Authorization: `Bearer ${__ENV.ACCESS_TOKEN}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `k6-${Date.now()}-${__VU}-${__ITER}`
    };
    const scenario = __ENV.SCENARIO || 'APPROVE';
    const result = http.post(`${base}/api/v1/orders`, JSON.stringify({
        items: [{
            productId: __ENV.PRODUCT_ID,
            quantity: 1
        }], paymentScenario: scenario
    }), {headers});
    if (!check(result, {'order accepted': r => r.status === 201})) return;
    const id = result.json('id');
    let status = result.json('status');
    for (let attempt = 0; attempt < 40 && status !== 'CONFIRMED' && status !== 'CANCELLED'; attempt++) {
        sleep(0.5);
        const response = http.get(`${base}/api/v1/orders/${id}`, {headers});
        if (response.status === 200) status = response.json('status');
    }
    check(status, {'expected terminal outcome': value => value === (scenario === 'APPROVE' ? 'CONFIRMED' : 'CANCELLED')});
}
