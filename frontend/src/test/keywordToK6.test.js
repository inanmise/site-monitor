import { describe, it, expect } from 'vitest'
import {
  BROWSER_USER_AGENT, K6_INTERVAL_MIN, absoluteUrl, jsString, jsonPathSegments, keywordToK6, normUrl, portableRegex, statusRanges,
  validEnvName,
} from '../components/keyword/k6/keywordToK6.js'
import { readJavaMain } from './helpers/javaTable.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

const tOf = (dict) => (k, ...a) => { let s = dict[k] ?? k; a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v ?? '')) }); return s }
const t = tOf(TR)

const base = {
  id: 7, name: 'Kurumsal site', url: 'https://www.example.com/', keyword: 'Hoş geldiniz', operator: 'GTE', match_count: 1,
  case_sensitive: false, team_id: 3, group_name: 'Kurumsal Web', tags: 'prod,web', notify_email: true, notify_webhook: false,
  alert_level: 'HIGH', notification_group_id: 12, noc_notify: true, noc_group_ids: [4, 5], interval_seconds: 300, timeout_ms: 8000,
  confirm_attempts: 2, confirm_interval_seconds: 20, recovery_checks: 4, recovery_interval_seconds: 40, use_proxy: 'ON',
  slow_response_enabled: true, slow_threshold_ms: 2500,
}
const gen = (over = {}, opts = {}) => keywordToK6({ ...base, ...over }, { t, ...opts })

// ── k6 çalışma zamanı taklidi: betik GERÇEKTEN koşturulur (import'lar enjekte edilir) ─────────────────────────────────
function wrapSel(nodes) {
  return {
    size: () => nodes.length,
    text: () => nodes.map((n) => n.textContent).join(''),
    toArray: () => nodes.map((n) => wrapSel([n])),
    find: (sel) => wrapSel(nodes.flatMap((n) => [...n.querySelectorAll(sel)])),
  }
}
function parseHTML(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return { ...wrapSel([doc.documentElement]), find: (sel) => wrapSel([...doc.querySelectorAll(sel)]) }
}
function run(script, { body = '', status = 200, url = 'https://www.example.com/', env = {} } = {}) {
  const code = script.replace(/^import .*$/gm, '').replace('export default function () {', 'return function () {')
  const checks = {}
  const calls = []
  const http = { request: (method, u, b, params) => { calls.push({ method, url: u, body: b, params }); return { status, body, url } } }
  const check = (res, sets) => { for (const [k, fn] of Object.entries(sets)) checks[k] = !!fn(res); return true }
  const encoding = { b64encode: (s) => Buffer.from(s, 'utf8').toString('base64') }
  const fn = new Function('http', 'check', 'encoding', 'parseHTML', '__ENV', code)(http, check, encoding, parseHTML, env)
  fn()
  return { checks, calls, values: Object.values(checks) }
}

// ── Sentetik kaydın kaynak taraması (ScriptedCheckerService / ScriptedSafetyRules) — üretilen betik REDDEDİLMEMELİ ─────
const stripComments = (s) => s.replace(/(^|[^:\\])\/\/[^\n]*/g, '$1')
const maskStrings = (s) => s.replace(/"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'/g, '""')
const SCAN = {
  open: /(?<![\w$.])open\s*(?:\?\.\s*)?\(/,
  openRef: /(?<![\w$.])open(?![\w$])(?!\s*[(:])(?!\s*\?\.)/,
  fs: /['"`]k6\/(?:experimental\/)?fs['"`]/,
  localImport: /(?:\bfrom|\brequire\s*\(|\bimport)\s*['"`](?:\/|\.{1,2}\/|file:)/,
  systemPath: /['"`]\/(?:proc|etc|sys|root|home|var\/run\/secrets|var\/log|var\/lib|run\/secrets|tmp|app|dev)(?:\/|['"`])/,
  handleSummary: /(?<![\w$])handleSummary(?![\w$])/,
  remoteImport: /(?:\bfrom|\brequire\s*\(|\bimport\s*\(?)\s*['"`](https?:\/\/)/,
  // ScriptedSafetyRules.EXTENSION_IMPORT birebir: yalnız from / import / require ardındaki dize (düz dize sayılmaz)
  extension: /(?:\bfrom|\bimport(?:\s*\()?|\brequire\s*\()\s*['"`]k6\/x\//,
  useK6: /^[ \t]*['"]use k6\b/m,
  infinite: /(?:while\s*\(\s*(?:true|1)\s*\))|(?:for\s*\(\s*;\s*;\s*\))/,
}
function scanProblems(script) {
  const code = stripComments(script)
  const bare = maskStrings(code)
  const out = []
  if (SCAN.open.test(bare) || SCAN.openRef.test(bare)) out.push('open')
  for (const k of ['fs', 'localImport', 'systemPath', 'remoteImport', 'extension', 'useK6', 'infinite']) if (SCAN[k].test(code)) out.push(k)
  if (SCAN.handleSummary.test(bare)) out.push('handleSummary')
  const httpCalls = (code.match(/\bhttp\s*\.\s*(get|post|put|del|patch|head|options|request|batch|asyncRequest)\s*\(/g) || []).length
  if (httpCalls !== 1) out.push(`httpCalls=${httpCalls}`)
  return out
}

describe('keywordToK6 — yardımcılar', () => {
  it('jsString: geri çözülünce aynı metin; tırnak / parantez / baştaki "/" / denetim karakteri kaçışlı', () => {
    for (const s of ['Hoş geldiniz', '/etc/passwd', 'a"b\'c`d', 'x\\y', 'while(true)', 'satır\nsonu\u2028', '', 'İ ı Ş']) {
      const lit = jsString(s)
      expect(new Function(`return ${lit}`)()).toBe(s)
      expect(lit.slice(1, -1)).not.toMatch(/["'`()\n]/)
      expect(lit.startsWith('"/')).toBe(false)
    }
    expect(jsString('https://a.example/x')).toBe('"https://a.example/x"')
  })

  it('durum aralıkları (sunucu dilbilgisi), JSON yolu bölümleri, URL biçimi', () => {
    expect(statusRanges('2xx, 301,400-404 , 999, abc')).toEqual([[200, 299], [301, 301], [400, 404]])
    expect(jsonPathSegments('$.items[0][\'a b\'].id')).toEqual(['items', 0, 'a b', 'id'])
    expect(jsonPathSegments('status')).toEqual(['status'])
    expect(jsonPathSegments('$..x')).toBeNull()
    expect(normUrl('HTTPS://WWW.Example.com:443/Yol?a=1#frag')).toBe('https://www.example.com/Yol?a=1')
    expect(absoluteUrl('/app/ok', 'https://x.example:8443/a/b')).toBe('https://x.example:8443/app/ok')
    expect(absoluteUrl('https://y.example/', 'https://x.example')).toBe('https://y.example/')
  })

  it('düzenli ifade taşınabilirliği: baştaki bayrak JS bayrağı, RE2’ye özgü sözdizimi null, duyarsızda İ/ı katlanır', () => {
    expect(portableRegex('Sipariş no: \\d{6}', true)).toEqual({ source: 'Sipariş no: \\d{6}', flags: 'g' })
    expect(portableRegex('(?is)İptal.*ok', false)).toEqual({ source: 'iptal.*ok', flags: 'isg' })
    for (const p of ['\\p{Lu}+', '\\Qa.b\\E', '(?P<ad>x)', 'a(?i)b', '[[:alpha:]]+', '\\x{41}', '(?U)a+', '\\Aabc']) expect(portableRegex(p), p).toBeNull()
    expect(portableRegex('(?:ab)+[İI]', false).source).toBe('(?:ab)+[ii]')
  })

  it('env adı politikası (ScriptedEnvPolicy aynası)', () => {
    expect(validEnvName('HDR_X_API_KEY')).toBe(true)
    for (const bad of ['HDR_PROXY_AUTHORIZATION', 'K6_OUT', 'GOMAXPROCS', 'PATH', '1ABC', 'a-b']) expect(validEnvName(bad), bad).toBe(false)
  })

  it('tarayıcı UA sabiti sunucunun KeywordRequestRules.BROWSER_USER_AGENT değeriyle aynı (arka uç birleşince denetlenir)', () => {
    let src = null
    try { src = readJavaMain('service/keyword/KeywordRequestRules.java') } catch { src = null }
    if (!src) return
    const m = /BROWSER_USER_AGENT\s*=\s*"([^"]+)"/.exec(src)
    expect(m, 'BROWSER_USER_AGENT Java kaynağında').not.toBeNull()
    expect(BROWSER_USER_AGENT).toBe(m[1])
  })
})

describe('keywordToK6 — üretilen betik k6 taklidinde koşar', () => {
  it('eski tek kural (içermeli, harf duyarsız): Türkçe katlamayla bulur; yoksa denetim düşer; GET + UA + zaman aşımı', () => {
    const { script, notes } = gen()
    expect(notes).toEqual([])
    const ok = run(script, { body: '<h1>HOŞ GELDİNİZ</h1>' })
    expect(ok.values).toEqual([true])
    expect(Object.keys(ok.checks)[0]).toContain('Kural 1: « Hoş geldiniz » sayfada en az bir kez geçmeli.')
    expect(ok.calls[0]).toMatchObject({ method: 'GET', body: null, params: { redirects: 5, timeout: '8000ms' } })
    expect(ok.calls[0].params.headers['User-Agent']).toBe('SiteMonitor-KeywordMonitor/1.0')
    expect(run(script, { body: '<h1>Merhaba</h1>' }).values).toEqual([false])
  })

  it('İSTANBUL ↔ istanbul (B3) ve harf duyarlı kural birebir', () => {
    expect(run(gen({ keyword: 'istanbul' }).script, { body: 'İSTANBUL' }).values).toEqual([true])
    expect(run(gen({ keyword: 'istanbul', case_sensitive: true }).script, { body: 'İSTANBUL' }).values).toEqual([false])
  })

  it('içermemeli ve adet kuralları; {timestamp} çalışma anında doldurulur', () => {
    const absent = gen({ keyword: 'Hata', operator: 'LTE', match_count: 0, url: 'https://www.example.com/?t={timestamp}' })
    const r = run(absent.script, { body: 'Bir hata oluştu' })
    expect(r.values).toEqual([false])
    expect(r.calls[0].url).toMatch(/^https:\/\/www\.example\.com\/\?t=\d+$/)
    expect(run(absent.script, { body: 'temiz' }).values).toEqual([true])
    const atLeast3 = gen({ keyword: 'ürün', operator: 'GTE', match_count: 3 })
    expect(run(atLeast3.script, { body: 'ürün ürün' }).values).toEqual([false])
    expect(run(atLeast3.script, { body: 'ürün ürün ÜRÜN' }).values).toEqual([true])
  })

  const multi = (logic) => ({
    rule_logic: logic,
    rules: [
      { id: 'r1', kind: 'TEXT', pattern: 'Fiyat', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null },
      { id: 'r2', kind: 'TEXT', pattern: 'Stokta yok', case_sensitive: false, operator: 'LTE', count: 0, critical: null, scope: null, label: 'Stok' },
    ],
  })

  it('VE: kural başına bir denetim; VEYA: tek birleşik denetim', () => {
    const and = run(gen(multi('AND')).script, { body: 'Fiyat: 10 TL — Stokta yok' })
    expect(and.values).toEqual([true, false])
    expect(Object.keys(and.checks)[1]).toContain('Stok:')
    const or = run(gen(multi('OR')).script, { body: 'Fiyat: 10 TL — Stokta yok' })
    expect(Object.keys(or.checks)).toHaveLength(1)
    expect(or.values).toEqual([true])
    expect(run(gen(multi('OR')).script, { body: 'Stokta yok' }).values).toEqual([false])
  })

  it('düzenli ifade (taşınabilir) sayar; taşınamayan desen yorum + not, denetim yoksa "yanıt verdi" denetimi', () => {
    const re = gen({ rules: [{ id: 'r1', kind: 'REGEX', pattern: 'Sipariş no: \\d{6}', case_sensitive: false, operator: 'GTE', count: 1 }] })
    expect(run(re.script, { body: 'SİPARİŞ NO: 123456' }).values).toEqual([true])
    expect(run(re.script, { body: 'Sipariş no: 12' }).values).toEqual([false])
    const np = gen({ rules: [{ id: 'r1', kind: 'REGEX', pattern: '\\p{Lu}+', case_sensitive: true, operator: 'GTE', count: 1, label: 'Büyük harf' }] })
    expect(np.notes).toContainEqual({ code: 'REGEX_NOT_PORTABLE', params: ['Büyük harf'] })
    expect(np.script).toMatch(/\/\/ Büyük harf: düzenli ifade JavaScript’e çevrilemedi/)
    const r = run(np.script, { body: 'X' })
    expect(Object.keys(r.checks)).toEqual(['Sayfa yanıt verdi'])
  })

  it('CSS ve JSON kapsamı; bölge yoksa kural sağlanmaz', () => {
    const css = gen({ rules: [{ id: 'r1', kind: 'TEXT', pattern: 'UP', operator: 'GTE', count: 1, scope: { type: 'CSS', value: '#durum' } }] })
    expect(run(css.script, { body: '<p>UP</p><div id="durum">  up </div>' }).values).toEqual([true])
    expect(run(css.script, { body: '<p>UP</p>' }).values).toEqual([false])
    const json = gen({ rules: [{ id: 'r1', kind: 'TEXT', pattern: 'ok', operator: 'GTE', count: 1, scope: { type: 'JSON', value: '$.data[0].status' } }] })
    expect(run(json.script, { body: '{"data":[{"status":"OK"}]}' }).values).toEqual([true])
    expect(run(json.script, { body: '{"data":[]}' }).values).toEqual([false])
    expect(run(json.script, { body: '<html>' }).values).toEqual([false])
  })

  it('POST + gövde + içerik türü; Basic auth (kullanıcı düz, parola GİZLİ ve boş); özel başlık adları gizli + boş env', () => {
    const g = gen({ method: 'POST', request_body: '{"q":"/etc/x"}', request_content_type: 'application/json', basic_auth_user: 'izleme',
      has_basic_auth_pass: true, has_custom_headers: true, custom_header_names: ['X-Api-Key', 'Proxy-Authorization'] })
    expect(g.env).toEqual([
      { name: 'BASIC_USER', secret: false, value: 'izleme' },
      { name: 'BASIC_PASS', secret: true, value: '' },
      { name: 'HDR_X_API_KEY', secret: true, value: '' },
      { name: 'HDR_2', secret: true, value: '' },
    ])
    const r = run(g.script, { body: 'Hoş geldiniz', env: { BASIC_USER: 'izleme', BASIC_PASS: 'p', HDR_X_API_KEY: 'k', HDR_2: 'v' } })
    expect(r.calls[0]).toMatchObject({ method: 'POST', body: '{"q":"/etc/x"}' })
    expect(r.calls[0].params.headers).toMatchObject({ 'Content-Type': 'application/json', Authorization: `Basic ${Buffer.from('izleme:p').toString('base64')}`,
      'X-Api-Key': 'k', 'Proxy-Authorization': 'v' })
    // Değer verilmezse başlık hiç gönderilmez
    expect(run(g.script, { body: 'Hoş geldiniz' }).calls[0].params.headers).not.toHaveProperty('X-Api-Key')
  })

  it('adları görünmeyen özel başlıklar taşınmaz (not)', () => {
    expect(gen({ has_custom_headers: true, custom_header_names: [] }).notes).toContainEqual({ code: 'HEADERS_NOT_CARRIED', params: [] })
  })

  it('kabul edilen durum kodları, son URL (göreli → mutlak), yönlendirme takibi kapalı, UA kipleri', () => {
    const g = gen({ expected_status: '2xx', expected_final_url: '/hesabim', follow_redirects: false, user_agent_mode: 'BROWSER' })
    const ok = run(g.script, { body: 'Hoş geldiniz', status: 200, url: 'https://WWW.example.com:443/hesabim?x=1' })
    expect(ok.values).toEqual([true, true, true])
    expect(ok.calls[0].params.redirects).toBe(0)
    expect(ok.calls[0].params.headers['User-Agent']).toBe(BROWSER_USER_AGENT)
    const bad = run(g.script, { body: 'Hoş geldiniz', status: 503, url: 'https://www.example.com/login' })
    expect(bad.values).toEqual([false, false, true])
    expect(run(gen({ user_agent_mode: 'CUSTOM', user_agent: 'Kurum-İzleme/2' }).script, { body: 'Hoş geldiniz' }).calls[0].params.headers['User-Agent']).toBe('Kurum-İzleme/2')
  })

  it('boyut (karakter — not), kritik eşik / değişim / görünür metin / kısa aralık notları', () => {
    const g = gen({ min_bytes: 5, max_bytes: 20, change_detect: true, match_scope: 'VISIBLE', interval_seconds: 30,
      rules: [{ id: 'r1', kind: 'TEXT', pattern: 'hata', operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 } }] })
    expect(g.notes.map((n) => n.code).sort()).toEqual(['CHANGE_NOT_CARRIED', 'INTERVAL_RAISED', 'LEVELS_NOT_CARRIED', 'SCOPE_APPROXIMATE', 'SIZE_APPROXIMATE'])
    expect(g.form.intervalSeconds).toBe(K6_INTERVAL_MIN)
    const r = run(g.script, { body: '<html><body><script>hata hata hata hata hata</script><p>tamam</p></body></html>' })
    expect(r.values[0]).toBe(false)   // boyut > 20 karakter
  })
})

describe('keywordToK6 — güvenlik: sır yok, kaynak taraması temiz', () => {
  const hostile = {
    keyword: 'open(\'/etc/passwd\')', url: 'https://www.example.com/dev/x',
    method: 'POST', request_body: '/tmp/x while(true) { http.get("u") } handleSummary import "./a" from "/b"',
    basic_auth_user: 'u', has_basic_auth_pass: true, basic_auth_pass: 'S3CR3T-PAROLA', custom_headers: 'X-Token: GIZLI-DEGER',
    has_custom_headers: true, custom_header_names: ['X-Token'],
    rules: [
      { id: 'r1', kind: 'TEXT', pattern: '"use k6"', operator: 'GTE', count: 1 },
      { id: 'r2', kind: 'TEXT', pattern: 'k6/x/sql', operator: 'LTE', count: 0, scope: { type: 'CSS', value: '/dev/null' } },
      { id: 'r3', kind: 'REGEX', pattern: 'https?://[a-z]+', operator: 'GTE', count: 1, label: 'from \'/etc\'' },
    ],
  }

  it('sır değeri (parola, başlık değeri) betikte ve taslakta YOK; gizli env değerleri boş', () => {
    const g = gen(hostile)
    const all = `${g.script}\n${JSON.stringify(g.form)}`
    expect(all).not.toContain('S3CR3T-PAROLA')
    expect(all).not.toContain('GIZLI-DEGER')
    for (const e of g.form.env.filter((x) => x.secret)) expect(e.value).toBe('')
  })

  it('sentetik kaydın taramasına takılmaz (open / handleSummary / yerel-uzak import / sistem yolu / k6/x / use k6 / sonsuz döngü / tek istek)', () => {
    expect(scanProblems(gen(hostile).script)).toEqual([])
    for (const over of [{}, { rules: [{ id: 'r1', kind: 'TEXT', pattern: 'x', operator: 'GTE', count: 1, scope: { type: 'JSON', value: '$.a' } }] }]) {
      expect(scanProblems(gen(over).script)).toEqual([])
    }
  })

  it('düşmanca verili betik de doğru çalışır (veri koda karışmaz)', () => {
    const r = run(gen(hostile).script, { body: '<p>"use k6" https://abc</p>', env: {} })
    expect(r.values.length).toBe(3)
    expect(r.calls[0].body).toBe(hostile.request_body)
  })
})

describe('keywordToK6 — sentetik form taslağı', () => {
  it('takım / grup / etiket / bildirim / aralık / zaman aşımı / teyit / vekil / yavaşlık eşlenir; sınırlar sentetik formunki', () => {
    const { form } = gen()
    expect(form).toMatchObject({
      name: 'Kurumsal site (sentetik)', teamId: '3', groupName: 'Kurumsal Web', tags: 'prod,web', notifyEmail: true, notifyWebhook: false,
      alertLevel: 'HIGH', notificationGroupId: '12', nocNotify: true, nocGroupIds: ['4', '5'], intervalSeconds: 300, timeoutSeconds: 30,
      confirmAttempts: 2, confirmIntervalSeconds: 20, recoveryChecks: 4, recoveryIntervalSeconds: 40, slowResponseEnabled: true,
      slowThresholdMs: 2500, useProxy: 'ON', active: true, template: '',
    })
    expect(form.description).toContain('https://www.example.com/')
    expect(gen({ timeout_ms: 60000, slow_threshold_ms: 100, recovery_checks: 50 }).form).toMatchObject({ timeoutSeconds: 80, slowThresholdMs: 500, recoveryChecks: 10 })
  })

  it('İngilizce arayüzde denetim adları ve notlar İngilizce', () => {
    const g = keywordToK6({ ...base, expected_status: '200' }, { t: tOf(EN) })
    expect(g.form.name).toBe('Kurumsal site (synthetic)')
    expect(g.script).toContain('HTTP status is accepted \\u0028200\\u0029')
  })
})
