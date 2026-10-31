import http from 'k6/http';
import { check, group } from 'k6';
export const options = { insecureSkipTLSVerify: true };
export default function () {
  group('Güvenli', () => { const r = http.get('https://localhost:18733/mod.js', {timeout:'5s'}); check(r, {'ok': (x) => x.status === 200}); });
  group('Eksik', () => { const r = http.get('https://localhost:18733/yok.js', {timeout:'5s'}); check(r, {'bulundu': (x) => x.status === 200}); });
}
