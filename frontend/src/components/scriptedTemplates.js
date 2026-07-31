// Senaryo İzleme hazır k6 şablonları — form'daki "Şablon" seçicisinden yüklenir.
// Her şablon açıklamalı ve credential'lar __ENV üzerinden gelir (script gövdesine sabit-kod YAZILMAZ).
// name/desc TR + EN.

export const SCRIPTED_TEMPLATES = [
  {
    id: 'smoke-health',
    name: { tr: 'Sistem sağlık kontrolü (smoke)', en: 'System health check (smoke)' },
    desc: {
      tr: 'k6 / Senaryo İzleme uçtan uca çalışıyor mu — env GEREKTİRMEZ; public k6 test sitesine GET atar. "Test Çalıştır" ile sistemin çalıştığını hızlıca gözlemlemek için.',
      en: 'Is k6 / Scripted Check working end to end — NO env needed; GETs the public k6 test site. Use "Test Run" to quickly observe the system is working.',
    },
    env: [],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Env gerektirmez — k6/Senaryo İzleme'nin uçtan uca çalıştığını doğrulayan basit smoke testi.
export default function () {
  const r = http.get('https://test.k6.io');
  check(r, {
    'status 200': (res) => res.status === 200,
    'gövde var': (res) => (res.body || '').length > 0,
  });
}
`,
  },
  {
    id: 'oidc-keycloak',
    name: { tr: 'OIDC / Keycloak login akışı', en: 'OIDC / Keycloak login flow' },
    desc: {
      tr: 'Authorization-code akışı: auth endpoint → login formu submit → redirect/code → token exchange → claim doğrulama. Gerekli env: BASE_URL, REALM, CLIENT_ID, CLIENT_SECRET (secret), REDIRECT_URI, USERNAME, PASSWORD (secret).',
      en: 'Authorization-code flow: auth endpoint → submit login form → redirect/code → token exchange → claim check. Env: BASE_URL, REALM, CLIENT_ID, CLIENT_SECRET (secret), REDIRECT_URI, USERNAME, PASSWORD (secret).',
    },
    env: [
      { name: 'BASE_URL', secret: false }, { name: 'REALM', secret: false }, { name: 'CLIENT_ID', secret: false },
      { name: 'CLIENT_SECRET', secret: true }, { name: 'REDIRECT_URI', secret: false },
      { name: 'USERNAME', secret: false }, { name: 'PASSWORD', secret: true },
    ],
    script: `import http from 'k6/http';
import { check } from 'k6';

// İZLEME İÇİN AYRI BİR SERVİS HESABI KULLANIN — gerçek kullanıcı hesabıyla izleme yapmayın.
// Gerekli __ENV: BASE_URL, REALM, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, USERNAME, PASSWORD
export default function () {
  const base = __ENV.BASE_URL;
  const realm = __ENV.REALM || 'master';
  const authUrl = base + '/realms/' + realm + '/protocol/openid-connect/auth'
    + '?client_id=' + __ENV.CLIENT_ID
    + '&redirect_uri=' + encodeURIComponent(__ENV.REDIRECT_URI)
    + '&response_type=code&scope=openid';

  // 1) Login sayfasını al (form + cookie'ler)
  const loginPage = http.get(authUrl);
  check(loginPage, { 'login sayfası 200': (r) => r.status === 200 });

  // 2) Keycloak login formunu doldur + submit; redirect'i yakala
  const afterLogin = loginPage.submitForm({
    formSelector: '#kc-form-login',
    fields: { username: __ENV.USERNAME, password: __ENV.PASSWORD },
    params: { redirects: 0 },
  });
  check(afterLogin, {
    'redirect (302/303)': (r) => r.status === 302 || r.status === 303,
    'authorization code var': (r) => (r.headers['Location'] || '').indexOf('code=') >= 0,
  });

  // 3) code'u çıkar → token exchange
  const loc = afterLogin.headers['Location'] || '';
  const m = loc.match(/[?&]code=([^&]+)/);
  const code = m ? m[1] : null;
  if (code) {
    const tok = http.post(base + '/realms/' + realm + '/protocol/openid-connect/token', {
      grant_type: 'authorization_code', code: code,
      client_id: __ENV.CLIENT_ID, redirect_uri: __ENV.REDIRECT_URI,
      client_secret: __ENV.CLIENT_SECRET || '',
    });
    check(tok, { 'token exchange 200': (r) => r.status === 200 });
    const b = tok.json();
    check(b, { 'access_token mevcut': (x) => !!x.access_token, 'id_token mevcut': (x) => !!x.id_token });
  }
}
`,
  },
  {
    id: 'api-chain',
    name: { tr: 'Basit API zinciri (POST → GET)', en: 'Simple API chain (POST → GET)' },
    desc: {
      tr: 'POST ile kayıt oluşturur, dönen ID ile GET yapar ve alan doğrular. Env: BASE_URL, TOKEN (secret, opsiyonel).',
      en: 'Creates a record via POST, GETs it by returned ID and validates a field. Env: BASE_URL, TOKEN (secret, optional).',
    },
    env: [{ name: 'BASE_URL', secret: false }, { name: 'TOKEN', secret: true }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: BASE_URL  (opsiyonel: TOKEN)
export default function () {
  const base = __ENV.BASE_URL;
  const headers = { 'Content-Type': 'application/json' };
  if (__ENV.TOKEN) headers.Authorization = 'Bearer ' + __ENV.TOKEN;

  // 1) POST — kayıt oluştur
  const create = http.post(base + '/api/items', JSON.stringify({ name: 'monitor-probe', kind: 'test' }), { headers: headers });
  check(create, { 'POST 200/201': (r) => r.status === 200 || r.status === 201 });
  const id = create.json('id');
  check(create, { 'id döndü': () => !!id });

  // 2) GET — dönen ID ile oku + alan doğrula
  if (id) {
    const get = http.get(base + '/api/items/' + id, { headers: headers });
    check(get, { 'GET 200': (r) => r.status === 200, 'name doğru': (r) => r.json('name') === 'monitor-probe' });
  }
}
`,
  },
  {
    id: 'form-login',
    name: { tr: 'Form login + oturum sayfası', en: 'Form login + session page' },
    desc: {
      tr: 'Login formunu doldurur ve oturum-korumalı bir sayfanın erişilebilir olduğunu doğrular. Env: BASE_URL, USERNAME, PASSWORD (secret).',
      en: 'Submits a login form and verifies a session-protected page is reachable. Env: BASE_URL, USERNAME, PASSWORD (secret).',
    },
    env: [{ name: 'BASE_URL', secret: false }, { name: 'USERNAME', secret: false }, { name: 'PASSWORD', secret: true }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// İZLEME İÇİN AYRI SERVİS HESABI KULLANIN. Gerekli __ENV: BASE_URL, USERNAME, PASSWORD
export default function () {
  const base = __ENV.BASE_URL;

  // 1) Login sayfası
  const page = http.get(base + '/login');
  check(page, { 'login sayfası 200': (r) => r.status === 200 });

  // 2) Form login
  const res = page.submitForm({ fields: { username: __ENV.USERNAME, password: __ENV.PASSWORD } });
  check(res, { 'login sonrası 200': (r) => r.status === 200 });

  // 3) Oturum-korumalı sayfa erişilebilir mi
  const dash = http.get(base + '/dashboard');
  check(dash, {
    'dashboard 200': (r) => r.status === 200,
    'oturum açık (login formuna düşmedi)': (r) => (r.body || '').indexOf('name="password"') < 0,
  });
}
`,
  },
]
