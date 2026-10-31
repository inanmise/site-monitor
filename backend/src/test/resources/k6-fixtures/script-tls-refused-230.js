import http from 'k6/http';
import { check, group } from 'k6';
export const options = { insecureSkipTLSVerify: true, thresholds: { http_req_duration: ['p(95)<5000'], 'http_req_duration{name:a}': ['avg<1'] } };
export default function () {
  group('Giriş', () => { const r = http.get('https://127.0.0.1:18732/mod.js', {timeout:'5s', tags: {name:'a'}}); check(r, {'ok': (x) => x.status === 200}); });
  http.get('http://127.0.0.1:18799/', {timeout:'2s'});
}
