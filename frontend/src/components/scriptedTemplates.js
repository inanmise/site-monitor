// Sentetik İzleme hazır k6 şablonları — form'daki "Şablon" seçicisinden yüklenir.
// Her şablon açıklamalı ve credential'lar __ENV üzerinden gelir (script gövdesine sabit-kod YAZILMAZ).
// name/desc/when TR + EN. `when` = kullanım senaryosu; seçici altında gösterilir, kullanıcı hangi
// şablonun kendi işine uyduğunu şablonu yüklemeden önce anlar.
//
// ŞABLON YAZIM KURALLARI (testle sabitlenmiştir — scriptedTemplates.test.js)
//
// 1) HER isteğe AÇIK `timeout` verilir ve süreç zaman aşımından KISA olur. k6'nın kendi varsayılan
//    istek timeout'u 60 sn, monitörün varsayılan süreç timeout'u da 60 sn: ikisi eşit olunca istek
//    kendi kendine düşemeden süreci öldürüyoruz ve k6 sebebi yazamıyor — ekranda sebepsiz
//    "Süre aşımı" kalıyor. Açık timeout, aynı arızayı sebebiyle birlikte FAIL'e çevirir.
// 2) Ortamdaki k6 (v0.49, gömülü Babel 6) `?.`, `??` ve `{...nesne}` sözdizimini AYRIŞTIRAMAZ.
//    Klasik `||` / `&&` ve `Object.assign` kullanılır.
// 3) `vus`/`iterations`/`stages` TANIMLANMAZ: motor her kontrolü `--vus 1 --iterations 1` ile koşar
//    ve bu ayarları ezer. `thresholds` ise geçerlidir (düşerse k6 99 ile çıkar → kontrol FAIL olur).

export const SCRIPTED_TEMPLATES = [
  {
    id: 'smoke-health',
    name: { tr: 'Sistem sağlık kontrolü (smoke)', en: 'System health check (smoke)' },
    desc: {
      tr: 'k6 / Sentetik İzleme uçtan uca çalışıyor mu — env GEREKTİRMEZ; www.akbank.com adresine GET atıp içeriği doğrular. İsteğe açık 20 sn timeout verilmiştir: hedefe çıkış kapalıysa koşum sebepsiz zaman aşımına düşmez, gerçek sebebi yazar.',
      en: 'Is k6 / Synthetic Monitoring working end to end — NO env needed; GETs www.akbank.com and validates its content. The request carries an explicit 20s timeout, so if egress is blocked the run reports the real cause instead of a silent timeout.',
    },
    when: {
      tr: 'Sentetik İzleme\'yi ilk kez kurarken veya "acaba k6 mı bozuldu?" şüphesinde ilk çalıştırılacak şablon. Yeni bir pod/ortam sonrası DNS + TLS + HTTP zincirinin ayakta olduğunu tek tıkla gösterir.',
      en: 'The first template to run when setting up Synthetic Monitoring, or when you suspect k6 itself is broken. After a new pod/environment it proves the DNS + TLS + HTTP chain is alive in one click.',
    },
    env: [],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Env gerektirmez — k6/Sentetik İzleme'nin uçtan uca çalıştığını doğrulayan basit smoke testi.
// Hedef kurumsal sitemiz: DNS + TLS + HTTP zinciri pod'dan GERÇEK bir hedefle sınanır.
// Başka bir hedefi denemek için URL'i değiştirmeniz yeterli.
//
// timeout AÇIK ve süreç zaman aşımından KISA verilmiştir (bilerek): k6'nın kendi varsayılan
// istek timeout'u 60 sn, monitörün varsayılan süreç timeout'u da 60 sn. İkisi eşit olunca istek
// kendi kendine düşemeden süreci öldürüyoruz ve k6 başarısızlığın sebebini (bağlantı reddi, DNS,
// TLS, istek zaman aşımı) yazmaya fırsat bulamıyor — ekranda sebepsiz "Süre aşımı" kalıyor.
export default function () {
  const r = http.get('https://www.akbank.com', { timeout: '20s' });
  check(r, {
    'status 200': (res) => res.status === 200,
    // "gövde var" YETMEZ: engelleyen vekil/captive portal sayfasının da gövdesi vardır.
    // İçerik kontrolü, gerçekten hedefe ulaşıldığını doğrular.
    'içerik Akbank sayfası': (res) => (res.body || '').indexOf('Akbank') >= 0,
  });
}
`,
  },
  {
    id: 'json-health',
    name: { tr: 'JSON sağlık ucu (Actuator / mikroservis)', en: 'JSON health endpoint (Actuator / microservice)' },
    desc: {
      tr: 'Servisin /health ucunu çağırır ve gövdeyi metin olarak değil ALAN olarak doğrular: `status` alanı UP mı, yanıt süresi eşiğin altında mı. Env: BASE_URL.',
      en: 'Calls the service /health endpoint and validates the body as a FIELD, not text: is `status` UP, is the response under the latency bar. Env: BASE_URL.',
    },
    when: {
      tr: 'Spring Boot Actuator veya benzeri bir JSON sağlık ucu yayınlayan mikroservisler için. HTTP izleme yalnız 200 görür; bu şablon "200 dönüyor ama status: DOWN" durumunu yakalar — en sık gözden kaçan arıza budur.',
      en: 'For microservices exposing a Spring Boot Actuator-style JSON health endpoint. HTTP monitoring only sees 200; this template catches "returns 200 but status: DOWN" — the most commonly missed failure.',
    },
    env: [{ name: 'BASE_URL', secret: false }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: BASE_URL   (örn. https://servis.akbank.com)
export default function () {
  const r = http.get(__ENV.BASE_URL + '/health', { timeout: '15s' });

  // json('status') gövde JSON değilse istisna atar → kontrol ERROR olur ve sebebi ekrana yazılır.
  // Bu bilinçli: HTML dönen bir "sağlık ucu" sessizce PASS geçmemeli.
  check(r, {
    'status 200': (res) => res.status === 200,
    'health UP': (res) => res.json('status') === 'UP',
    'yanıt 2 sn altında': (res) => res.timings.duration < 2000,
  });
}
`,
  },
  {
    id: 'oauth2-client-credentials',
    name: { tr: 'OAuth2 client_credentials → korumalı API', en: 'OAuth2 client_credentials → protected API' },
    desc: {
      tr: 'Önce token endpoint\'inden client_credentials ile access_token alır, sonra korumalı API\'yi Bearer ile çağırır ve 401/403 dönmediğini doğrular. Env: TOKEN_URL, CLIENT_ID, CLIENT_SECRET (secret), API_URL, SCOPE (opsiyonel).',
      en: 'Gets an access_token from the token endpoint via client_credentials, then calls the protected API with Bearer and verifies it is not 401/403. Env: TOKEN_URL, CLIENT_ID, CLIENT_SECRET (secret), API_URL, SCOPE (optional).',
    },
    when: {
      tr: 'Servisten servise (makine-makine) entegrasyonlar: API gateway arkasındaki uçlar, açık bankacılık/partner API\'leri. Token üretiminin kendisi de izlenmiş olur — süresi dolan sertifika/secret veya yetki kaybı, API çağrısından ÖNCE görünür.',
      en: 'Service-to-service (machine-to-machine) integrations: endpoints behind an API gateway, open-banking/partner APIs. Token issuance itself is monitored too — an expired secret or lost scope shows up BEFORE the API call.',
    },
    env: [
      { name: 'TOKEN_URL', secret: false }, { name: 'CLIENT_ID', secret: false },
      { name: 'CLIENT_SECRET', secret: true }, { name: 'API_URL', secret: false },
      { name: 'SCOPE', secret: false },
    ],
    script: `import http from 'k6/http';
import { check } from 'k6';

// İZLEME İÇİN AYRI BİR SERVİS HESABI/İSTEMCİSİ KULLANIN.
// Gerekli __ENV: TOKEN_URL, CLIENT_ID, CLIENT_SECRET, API_URL   (opsiyonel: SCOPE)
export default function () {
  // 1) Token al
  const tok = http.post(__ENV.TOKEN_URL, {
    grant_type: 'client_credentials',
    client_id: __ENV.CLIENT_ID,
    client_secret: __ENV.CLIENT_SECRET,
    scope: __ENV.SCOPE || '',
  }, { timeout: '15s' });

  check(tok, {
    'token 200': (r) => r.status === 200,
    'access_token döndü': (r) => r.status === 200 && !!r.json('access_token'),
  });

  const token = tok.status === 200 ? tok.json('access_token') : null;
  if (!token) return;   // token yoksa korumalı uca gitmek anlamsız — sebep zaten yukarıda raporlandı

  // 2) Korumalı API'yi çağır
  const api = http.get(__ENV.API_URL, {
    timeout: '15s',
    headers: { Authorization: 'Bearer ' + token },
  });

  check(api, {
    'API 200': (r) => r.status === 200,
    'yetki kabul edildi (401/403 değil)': (r) => r.status !== 401 && r.status !== 403,
  });
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
    when: {
      tr: 'Tek bir uç değil, YAZ-OKU turunun bütünlüğü önemliyse: kayıt oluşturulabiliyor ve hemen ardından okunabiliyor mu. Yazma tarafı bozulduğunda ya da okuma replikası geride kaldığında ortaya çıkar.',
      en: 'When the write-then-read round trip matters, not a single endpoint: can a record be created and immediately read back. Surfaces broken writes and lagging read replicas.',
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
  const create = http.post(base + '/api/items', JSON.stringify({ name: 'monitor-probe', kind: 'test' }), { timeout: '15s', headers: headers });
  check(create, { 'POST 200/201': (r) => r.status === 200 || r.status === 201 });
  const id = create.json('id');
  check(create, { 'id döndü': () => !!id });

  // 2) GET — dönen ID ile oku + alan doğrula
  if (id) {
    const get = http.get(base + '/api/items/' + id, { timeout: '15s', headers: headers });
    check(get, { 'GET 200': (r) => r.status === 200, 'name doğru': (r) => r.json('name') === 'monitor-probe' });
  }
}
`,
  },
  {
    id: 'multi-step-journey',
    name: { tr: 'Çok adımlı kullanıcı yolculuğu (group)', en: 'Multi-step user journey (group)' },
    desc: {
      tr: 'Kritik iş akışını adım adım koşar (ana sayfa → arama → detay) ve her adımı `group()` ile ayırır; çıktıda hangi ADIMIN düştüğü net görünür. Env: BASE_URL.',
      en: 'Runs a critical flow step by step (home → search → detail), separating each with `group()`; the output shows exactly WHICH step failed. Env: BASE_URL.',
    },
    when: {
      tr: 'Tek bir URL\'in ayakta olması yetmiyorsa: uçtan uca bir iş akışının (arama, sorgulama, form doldurma) çalıştığını kanıtlamak için. Tüm uçlar tek tek 200 dönerken akışın ortasında kopan entegrasyonları yakalar.',
      en: 'When a single URL being up is not enough: to prove an end-to-end flow (search, query, form) works. Catches integrations that break mid-flow while every endpoint individually returns 200.',
    },
    env: [{ name: 'BASE_URL', secret: false }],
    script: `import http from 'k6/http';
import { check, group } from 'k6';

// Gerekli __ENV: BASE_URL
// group() adımları çıktıda ayrı başlıklar hâlinde görünür → "hangi adım düştü" sorusu tek bakışta cevaplanır.
export default function () {
  let itemId = null;

  group('1) ana sayfa', function () {
    const r = http.get(__ENV.BASE_URL + '/', { timeout: '15s' });
    check(r, { 'ana sayfa 200': (res) => res.status === 200 });
  });

  group('2) arama', function () {
    const r = http.get(__ENV.BASE_URL + '/api/search?q=test', { timeout: '15s' });
    check(r, { 'arama 200': (res) => res.status === 200 });
    if (r.status === 200) itemId = r.json('items.0.id') || null;
    check(r, { 'en az bir sonuç döndü': () => !!itemId });
  });

  group('3) detay', function () {
    if (!itemId) return;   // arama düştüyse detay adımını koşmak yanıltıcı olur
    const r = http.get(__ENV.BASE_URL + '/api/items/' + itemId, { timeout: '15s' });
    check(r, { 'detay 200': (res) => res.status === 200 });
  });
}
`,
  },
  {
    id: 'sla-threshold',
    name: { tr: 'Yanıt süresi SLA (threshold)', en: 'Response-time SLA (threshold)' },
    desc: {
      tr: '`options.thresholds` ile süre ve hata oranı eşiği tanımlar. Eşik düşerse k6 99 ile çıkar ve kontrol FAIL olur — yani "çalışıyor ama yavaş" da arıza sayılır. Env: TARGET_URL.',
      en: 'Defines duration and error-rate bars via `options.thresholds`. If a bar is missed k6 exits 99 and the check FAILs — so "up but slow" counts as a failure too. Env: TARGET_URL.',
    },
    when: {
      tr: 'Yanıt süresi taahhüdü (SLA) olan uçlar için: ödeme, sorgulama, giriş. Ayakta ama yavaşlayan bir servisi, kullanıcı şikâyeti gelmeden alarma dönüştürür.',
      en: 'For endpoints with a latency commitment (SLA): payments, queries, login. Turns a service that is up but slowing down into an alert before users complain.',
    },
    env: [{ name: 'TARGET_URL', secret: false }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: TARGET_URL
//
// Eşikler her koşumda TEK istek üzerinden değerlendirilir (motor --vus 1 --iterations 1 ile koşar),
// yani p(95) pratikte o tek isteğin süresidir. Yük testi değil, SLA nöbetçisi olarak düşünün.
//
// EŞİĞİ KENDİ UCUNUZA GÖRE AYARLAYIN: aşağıdaki 1,5 sn bir API ucu içindir. Ağır bir HTML sayfası
// (birkaç yüz KB) ilk isteğin TLS + indirme maliyetiyle bunu rahatlıkla aşar ve monitör boş yere
// FAIL verir. Gerçek ölçümünüzü "Test Çalıştır" ile alıp üstüne makul bir pay ekleyin.
export const options = {
  thresholds: {
    'http_req_duration': ['p(95)<1500'],   // 1,5 sn üstü = SLA ihlali
    'http_req_failed': ['rate<0.01'],      // isteğin kendisi düşerse
  },
};

export default function () {
  const r = http.get(__ENV.TARGET_URL, { timeout: '20s' });
  check(r, { 'status 200': (res) => res.status === 200 });
}
`,
  },
  {
    id: 'soap-xml',
    name: { tr: 'SOAP / XML servis çağrısı', en: 'SOAP / XML service call' },
    desc: {
      tr: 'SOAP zarfı gönderir; HTTP 200\'ün yanında gövdede `Fault` OLMADIĞINI doğrular. Env: SOAP_URL, SOAP_ACTION.',
      en: 'Posts a SOAP envelope and verifies there is NO `Fault` in the body, not just HTTP 200. Env: SOAP_URL, SOAP_ACTION.',
    },
    when: {
      tr: 'Çekirdek bankacılık ve eski entegrasyon servisleri için. SOAP\'ta asıl arıza HTTP kodunda değil zarfın içindedir: servis 200 dönerken içeride `faultstring` gönderir — düz HTTP izleme bunu sağlıklı sanır.',
      en: 'For core-banking and legacy integration services. In SOAP the real failure is inside the envelope, not the HTTP code: the service returns 200 while carrying a `faultstring` — plain HTTP monitoring reads that as healthy.',
    },
    env: [{ name: 'SOAP_URL', secret: false }, { name: 'SOAP_ACTION', secret: false }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: SOAP_URL, SOAP_ACTION
// Zarfı kendi servisinizin beklediği istek gövdesiyle değiştirin (aşağıdaki basit bir Ping örneğidir).
export default function () {
  const envelope = '<?xml version="1.0" encoding="UTF-8"?>'
    + '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">'
    + '<soapenv:Header/><soapenv:Body><Ping/></soapenv:Body>'
    + '</soapenv:Envelope>';

  const r = http.post(__ENV.SOAP_URL, envelope, {
    timeout: '20s',
    headers: { 'Content-Type': 'text/xml; charset=utf-8', 'SOAPAction': __ENV.SOAP_ACTION },
  });

  const body = r.body || '';
  check(r, {
    'HTTP 200': (res) => res.status === 200,
    'SOAP zarfı döndü': () => body.indexOf('Envelope') >= 0,
    // SOAP'ta 200 + Fault normaldir; arıza tam olarak BUDUR.
    'SOAP Fault yok': () => body.indexOf('faultstring') < 0 && body.indexOf('Fault>') < 0,
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
    when: {
      tr: 'Kimlik sağlayıcısının (Keycloak/OIDC) kendisi kritikse: SSO bozulduğunda arkasındaki tüm uygulamalar aynı anda erişilemez olur. Sertifika yenileme, realm/istemci ayarı değişikliği ve süresi dolan client secret bu şablonla dakikalar içinde görünür.',
      en: 'When the identity provider itself is critical: if SSO breaks, every application behind it becomes unreachable at once. Certificate renewals, realm/client config changes and expired client secrets surface within minutes.',
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
  const loginPage = http.get(authUrl, { timeout: '15s' });
  check(loginPage, { 'login sayfası 200': (r) => r.status === 200 });

  // 2) Keycloak login formunu doldur + submit; redirect'i yakala
  const afterLogin = loginPage.submitForm({
    formSelector: '#kc-form-login',
    fields: { username: __ENV.USERNAME, password: __ENV.PASSWORD },
    params: { redirects: 0, timeout: '15s' },
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
    }, { timeout: '15s' });
    check(tok, { 'token exchange 200': (r) => r.status === 200 });
    const b = tok.json();
    check(b, { 'access_token mevcut': (x) => !!x.access_token, 'id_token mevcut': (x) => !!x.id_token });
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
    when: {
      tr: 'Klasik (OIDC olmayan) form login kullanan iç uygulamalar için. Giriş sayfası açılıyor diye uygulama sağlıklı sayılmaz; bu şablon oturumun gerçekten AÇILDIĞINI kanıtlar — LDAP/DB bağlantısı kopunca giriş sayfası hâlâ 200 döner.',
      en: 'For internal apps using classic (non-OIDC) form login. A reachable login page does not mean the app is healthy; this template proves a session actually opens — when LDAP/DB connectivity breaks, the login page still returns 200.',
    },
    env: [{ name: 'BASE_URL', secret: false }, { name: 'USERNAME', secret: false }, { name: 'PASSWORD', secret: true }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// İZLEME İÇİN AYRI SERVİS HESABI KULLANIN. Gerekli __ENV: BASE_URL, USERNAME, PASSWORD
export default function () {
  const base = __ENV.BASE_URL;

  // 1) Login sayfası
  const page = http.get(base + '/login', { timeout: '15s' });
  check(page, { 'login sayfası 200': (r) => r.status === 200 });

  // 2) Form login
  const res = page.submitForm({
    fields: { username: __ENV.USERNAME, password: __ENV.PASSWORD },
    params: { timeout: '15s' },
  });
  check(res, { 'login sonrası 200': (r) => r.status === 200 });

  // 3) Oturum-korumalı sayfa erişilebilir mi
  const dash = http.get(base + '/dashboard', { timeout: '15s' });
  check(dash, {
    'dashboard 200': (r) => r.status === 200,
    'oturum açık (login formuna düşmedi)': (r) => (r.body || '').indexOf('name="password"') < 0,
  });
}
`,
  },
  {
    id: 'mtls-client-cert',
    name: { tr: 'mTLS — istemci sertifikasıyla API', en: 'mTLS — API with client certificate' },
    desc: {
      tr: 'İstemci sertifikası (mTLS) isteyen bir ucu çağırır; sertifika ve anahtar PEM olarak env\'den gelir. Env: BASE_URL, CERT_DOMAIN, CLIENT_CERT, CLIENT_KEY (secret).',
      en: 'Calls an endpoint requiring a client certificate (mTLS); cert and key come from env as PEM. Env: BASE_URL, CERT_DOMAIN, CLIENT_CERT, CLIENT_KEY (secret).',
    },
    when: {
      tr: 'Karşılıklı TLS ile korunan partner/entegrasyon uçları için. Sunucu sertifikasının süresini Port(443) izlemesi zaten takip eder; bu şablon ise BİZİM istemci sertifikamızın hâlâ kabul edildiğini doğrular — süresi dolan ya da yeni CA\'ya geçilen istemci sertifikası ancak burada görünür.',
      en: 'For partner/integration endpoints protected by mutual TLS. Port(443) monitoring already tracks the server certificate; this template proves OUR client certificate is still accepted — an expired or re-issued client cert shows up only here.',
    },
    env: [
      { name: 'BASE_URL', secret: false }, { name: 'CERT_DOMAIN', secret: false },
      { name: 'CLIENT_CERT', secret: false }, { name: 'CLIENT_KEY', secret: true },
    ],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: BASE_URL, CERT_DOMAIN, CLIENT_CERT, CLIENT_KEY
// CLIENT_CERT/CLIENT_KEY tam PEM içeriğidir ("-----BEGIN ..." dahil). ANAHTARI "secret" İŞARETLEYİN:
// secret değerler şifreli saklanır ve çıktıda maskelenir. Script gövdesine ASLA gömmeyin.
//
// tlsAuth KOŞULLU tanımlanır (bilerek): kaydetme sırasındaki sözdizimi doğrulaması script'i env
// VERMEDEN derler — secret'lar o yola hiç girmez. Koşulsuz yazılsaydı doğrulama boş PEM'de
// "failed to find any PEM data" ile düşer ve kullanıcı, script'i sapasağlamken uyarı görürdü.
export const options = __ENV.CLIENT_CERT
  ? {
      tlsAuth: [{
        domains: [__ENV.CERT_DOMAIN || ''],
        cert: __ENV.CLIENT_CERT,
        key: __ENV.CLIENT_KEY,
      }],
    }
  : {};

export default function () {
  const r = http.get(__ENV.BASE_URL, { timeout: '20s' });

  // mTLS reddi genelde TLS el sıkışmasında düşer: istek hiç tamamlanmaz ve sebep
  // "remote error: tls: ..." olarak hata satırına yazılır.
  check(r, {
    'status 200': (res) => res.status === 200,
    'yetki kabul edildi (401/403 değil)': (res) => res.status !== 401 && res.status !== 403,
  });
}
`,
  },
  {
    id: 'graphql',
    name: { tr: 'GraphQL sorgusu', en: 'GraphQL query' },
    desc: {
      tr: 'GraphQL ucuna sorgu gönderir; HTTP 200\'ün yanında yanıtta `errors` alanı OLMADIĞINI doğrular. Env: GRAPHQL_URL, TOKEN (secret, opsiyonel).',
      en: 'Posts a query to a GraphQL endpoint and verifies the response has NO `errors` field, not just HTTP 200. Env: GRAPHQL_URL, TOKEN (secret, optional).',
    },
    when: {
      tr: 'GraphQL API\'leri için. GraphQL hatayı HTTP koduyla değil gövdedeki `errors` dizisiyle bildirir: çözümleyici (resolver) patlasa bile yanıt 200\'dür — düz HTTP izleme bu arızayı hiç görmez.',
      en: 'For GraphQL APIs. GraphQL reports failures in the body `errors` array, not the HTTP code: even when a resolver blows up the response is 200 — plain HTTP monitoring never sees it.',
    },
    env: [{ name: 'GRAPHQL_URL', secret: false }, { name: 'TOKEN', secret: true }],
    script: `import http from 'k6/http';
import { check } from 'k6';

// Gerekli __ENV: GRAPHQL_URL   (opsiyonel: TOKEN)
// Sorguyu kendi şemanızdan gerçek bir alanla değiştirin; __typename her şemada çalışan güvenli bir başlangıçtır.
export default function () {
  const query = JSON.stringify({ query: '{ __typename }' });
  const headers = { 'Content-Type': 'application/json' };
  if (__ENV.TOKEN) headers.Authorization = 'Bearer ' + __ENV.TOKEN;

  const r = http.post(__ENV.GRAPHQL_URL, query, { timeout: '15s', headers: headers });
  const body = r.body || '';

  check(r, {
    'status 200': (res) => res.status === 200,
    'data alanı var': () => body.indexOf('"data"') >= 0,
    // GraphQL hatayı 200 ile döndürür; arıza tam olarak BURADADIR.
    'errors alanı yok': () => body.indexOf('"errors"') < 0,
  });
}
`,
  },
]
