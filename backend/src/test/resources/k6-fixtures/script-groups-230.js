import http from 'k6/http';
import { check, group } from 'k6';
export const options = { thresholds: { http_req_duration: ['p(95)<5000', 'avg<1'], checks: ['rate>0.5'] } };
export default function () {
  group('Giriş', function () {
    const r = http.get('http://127.0.0.1:8080/health');
    check(r, { 'durum 200': (x) => x.status === 200 });
    group('Alt adım', function () {
      const r2 = http.get('http://127.0.0.1:8080/health');
      check(r2, { 'gövde UP': (x) => x.body.indexOf('UP') >= 0 });
    });
  });
}
