/**
 * CertMonitor — k6 smoke load script.
 *
 * Not wired into CI (k6 needs to be installed separately on the runner). Run
 * locally or from a release runbook:
 *
 *   k6 run -e BASE_URL=http://localhost:8080 perf/k6-smoke.js
 *
 * Tests the public-facing surface (no auth required) under modest concurrency
 * so we can spot regressions in /health and the network-status endpoint.
 */
import http from 'k6/http'
import { check, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'

export const options = {
  vus: 50,
  duration: '30s',
  thresholds: {
    http_req_failed:   ['rate<0.01'],   // < 1% errors
    http_req_duration: ['p(95)<500'],   // 95th percentile under 500 ms
    checks:            ['rate>0.99'],
  },
}

export default function () {
  const health = http.get(`${BASE_URL}/health`, { tags: { name: 'health' } })
  check(health, {
    'health 200':        (r) => r.status === 200,
    'health fast':       (r) => r.timings.duration < 500,
    'health body has UP': (r) => (r.body || '').includes('UP'),
  })

  // /api/system/network-status requires auth, so a 401 is the "healthy" answer
  // for an anonymous probe -- we just want to know the route is alive.
  const net = http.get(`${BASE_URL}/api/system/network-status`, { tags: { name: 'network-status' } })
  check(net, {
    'network-status auth-gated': (r) => r.status === 401 || r.status === 200,
  })

  sleep(0.5)
}
