import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// --- Custom metrics ---
// Track allowed vs. rate-limited responses separately for clear reporting
const allowedRequests     = new Counter('requests_allowed');
const rateLimitedRequests = new Counter('requests_rate_limited');
const rateLimitedRate     = new Rate('rate_limited_rate');

export const options = {
    stages: [
        { duration: '30s', target: 100 },  // Ramp-up: 0 → 100 VUs over 30 s
        { duration: '1m',  target: 100 },  // Sustained load: 100 VUs for 1 min
        { duration: '15s', target: 200 },  // Burst: spike to 200 VUs to stress the rate limiter
        { duration: '10s', target: 0   },  // Ramp-down: drain back to 0
    ],
    thresholds: {
        // 95th-percentile response time must stay under 200 ms overall
        http_req_duration: ['p(95)<200'],
        // Every response must be either 200 (allowed) or 429 (rate-limited) — never a 5xx
        'checks':          ['rate==1.0'],
        // Under sustained load, at least 5% of requests should be rate-limited,
        // confirming the token bucket is actually enforcing limits
        'rate_limited_rate': ['rate>0.05'],
    },
};

export default function () {
    const res = http.get('http://localhost:8080/api/products');

    const isAllowed      = res.status === 200;
    const isRateLimited  = res.status === 429;

    // Verify the response is one of the two expected status codes
    check(res, {
        'status is 200 (allowed) or 429 (rate-limited)': (r) => isAllowed || isRateLimited,
        'no unexpected server errors (5xx)':             (r) => r.status < 500,
    });

    // Update custom metrics
    if (isAllowed)     allowedRequests.add(1);
    if (isRateLimited) rateLimitedRequests.add(1);
    rateLimitedRate.add(isRateLimited);

    // Brief pause between requests — each VU sends ~1 req/s
    sleep(1);
}