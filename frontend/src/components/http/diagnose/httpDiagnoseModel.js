/**
 * HTTP uçtan uca tanılama MODELİ — saf fonksiyonlar, React yok (2026-10-02).
 *
 * <p>Backend `POST /api/monitoring/http/{id}/diagnose` her yolu (izlemenin kendi yolu + vekil tanımlıysa öteki yol) ham
 * soket ölçümüyle adım adım dener ve snake_case bir sonuç döner (sözleşme: paths[0] daima `monitor`, paths[1] varsa
 * `alternate`; bulgu kodları + ADLI parametreler). Burada bu ham sonuç okunur parçalara çevrilir: hüküm metni (adlı yer
 * tutucular doldurulur, yol değerleri yerelleşir), adım hattı, zamanlama şelalesi, döküm süzgeci ve paylaşılabilir rapor
 * (Markdown / düz metin).
 *
 * <p>Neden ayrı modül: metin/sıralama mantığı i18n'siz test edilebilsin; pencere bileşeni yalnız çizsin. `t` gereken
 * yerde parametre olarak alınır, sözlük burada import EDİLMEZ (bağlantı tanılamasının diagModel.js deseni).
 */

/** Adım sırası — sözleşmedeki `steps[].key` sırası. Bilinmeyen anahtar sona eklenir (yeni adım düşmesin). */
export const STEP_ORDER = ['dns', 'proxy_connect', 'tcp', 'proxy_tunnel', 'tls', 'request', 'response', 'body']

/** Zamanlama şelalesinin bölümleri (`timeline.<key>_ms`) — sırayla, üst üste değil ARD ARDA. */
export const TIMING_KEYS = ['dns', 'connect', 'proxy', 'tls', 'ttfb', 'download']

/** Sözleşmenin bulgu kodları (backend ile AYNI liste; i18n `httpdx.finding.<CODE>.title|body`). */
export const FINDING_CODES = [
  'OK', 'SLOW', 'DNS_FAIL', 'TCP_REFUSED', 'TCP_TIMEOUT', 'PROXY_CONNECT_FAIL', 'PROXY_AUTH_REQUIRED',
  'PROXY_TUNNEL_REFUSED', 'TLS_HANDSHAKE_FAIL', 'TLS_UNTRUSTED', 'TLS_HOSTNAME_MISMATCH', 'TLS_EXPIRED',
  'RESPONSE_TIMEOUT', 'BODY_TIMEOUT', 'STATUS_MISMATCH', 'AUTH_REQUIRED', 'REDIRECT_LOOP', 'SSRF_BLOCKED',
  'JSON_ASSERTION_FAIL', 'PATH_DIFFERS', 'BOTH_PATHS_FAIL', 'CLIENT_MISMATCH',
]

/** Değeri yol adı (proxy|direct) olan parametreler — metinde "Vekil"/"Doğrudan" diye yerelleşir. */
const ROUTE_PARAMS = new Set(['route', 'failing_route', 'working_route'])
/** Değeri adım anahtarı olan parametreler — metinde adımın adı yazılır. */
const STEP_PARAMS = new Set(['failed_step'])

/** Sunucunun izin verdiği zaman aşımı aralığı (sözleşme: izlemenin timeout_ms'i 1000..30000'e kısılır). */
export const TIMEOUT_MIN = 1000
export const TIMEOUT_MAX = 30000
/** Tüm çalıştırmanın üst sınırı (sunucu) — istemci bundan biraz uzun bekler (ağ payı). */
export const RUN_LIMIT_MS = 60000

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const arr = (v) => (Array.isArray(v) ? v : [])

/** Yol başına süre sınırı (ms): izlemenin zaman aşımı sözleşmedeki aralığa kısılır; yoksa 10 sn. */
export function clampTimeout(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return 10000
  return Math.min(TIMEOUT_MAX, Math.max(TIMEOUT_MIN, Math.round(n)))
}

/** Hüküm durumu → ton (AlertBanner/ToneBadge dili). */
export function verdictTone(status) {
  if (status === 'ok') return 'success'
  if (status === 'warn') return 'warning'
  if (status === 'fail') return 'danger'
  return 'muted'
}

/** Bulgu önemi → ton. OK (info) yeşil; beklenen 401 gibi bilgi bulguları mavi. */
export function severityTone(severity, code) {
  if (severity === 'fail') return 'danger'
  if (severity === 'warn') return 'warning'
  return code === 'OK' ? 'success' : 'info'
}

/** Yol sonucu → ton. */
export function outcomeTone(outcome) {
  if (outcome === 'ok') return 'success'
  if (outcome === 'warn') return 'warning'
  if (outcome === 'fail') return 'danger'
  return 'muted'
}

/** Adım durumu sözlüğü: ok | fail | warn | skip | pending (backend `skip`; eksik/bilinmeyen = çalıştırılmadı). */
export function stepState(status) {
  switch (String(status ?? '').toLowerCase()) {
    case 'ok': return 'ok'
    case 'fail': case 'failed': case 'error': return 'fail'
    case 'warn': case 'warning': return 'warn'
    case 'skip': case 'skipped': return 'skip'
    default: return 'pending'
  }
}

/** Yol değeri → yerel ad ("Vekil" / "Doğrudan"); bilinmeyen değer olduğu gibi. */
export function routeText(route, t) {
  if (route === 'proxy' || route === 'direct') return t(`httpdx.route.${route}`)
  return route == null || route === '' ? '—' : String(route)
}

/** Vekil adresi "host:port" (yoksa null). */
export function proxyAddress(proxy) {
  if (!proxy || !proxy.host) return null
  return proxy.port != null ? `${proxy.host}:${proxy.port}` : String(proxy.host)
}

/** Yolun tam etiketi: "Vekil · dmzproxy:8080" / "Doğrudan". Vekil adresi önce yolun kendi hop'undan, yoksa üst bilgiden. */
export function routeLabel(path, data, t) {
  const r = routeText(path?.route, t)
  if (path?.route !== 'proxy') return r
  const hopProxy = arr(path?.hops).find((h) => h?.proxy?.host)?.proxy
  const addr = proxyAddress(hopProxy) || proxyAddress(data?.proxy)
  return addr ? `${r} · ${addr}` : r
}

/** Yol anahtarı → başlık ("İzlemenin yolu" / "Öteki yol"). */
export function pathTitle(key, t) {
  return t(key === 'alternate' ? 'httpdx.path.alternate' : 'httpdx.path.monitor')
}

/**
 * ADLI yer tutucuları doldurur: `{status}`, `{ms}` … Değer yoksa "—" (metin yarım kalmasın).
 * İşlev biçimli replace: değer `$&` gibi özel diziler içerse de bozulmaz (i18n `t`'nin split/join gerekçesi).
 */
export function interpolate(template, values = {}) {
  return String(template ?? '').replace(/\{([a-z][a-z0-9_]*)\}/gi, (_, name) => {
    const v = values[name]
    return v == null || v === '' ? '—' : String(v)
  })
}

/** Bulgu parametrelerini metne hazırlar: yol/adım değerleri yerelleşir, dizi birleşir, boş "—". */
export function localizeParams(params, t) {
  const out = {}
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null || v === '') { out[k] = '—'; continue }
    if (ROUTE_PARAMS.has(k)) out[k] = routeText(v, t)
    else if (STEP_PARAMS.has(k)) out[k] = STEP_ORDER.includes(v) ? t(`httpdx.step.${v}`) : String(v)
    else if (Array.isArray(v)) out[k] = v.join(', ')
    else if (typeof v === 'object') out[k] = JSON.stringify(v)
    else out[k] = String(v)
  }
  return out
}

/** Sözlükte gerçekten tanımlı mı? (`t` eksik anahtarda anahtarın kendisini döner.) */
const has = (t, key) => t(key) !== key

/**
 * Bulgunun yerel başlık + gövdesi. Başlıklar yer tutucusuz (geçmiş listesi parametresiz koddan da başlık çizebilsin);
 * gövde adlı parametrelerle dolar. Bilinmeyen kod (sözleşmeye sonradan eklenmiş) genel metinle düşer — ham anahtar yok.
 */
export function findingText(finding, t) {
  const code = finding?.code || 'UNKNOWN'
  const base = `httpdx.finding.${code}`
  const known = has(t, `${base}.title`)
  const p = localizeParams(finding?.params, t)
  if (!known) {
    return {
      known: false,
      title: interpolate(t('httpdx.finding.UNKNOWN.title'), { code }),
      body: interpolate(t('httpdx.finding.UNKNOWN.body'), { code }),
    }
  }
  // Gerekçe varyantı (2026-10-02): sunucu "yanıt yok"un alt nedenini `params.reason` ile verir (RESPONSE_TIMEOUT:
  // closed = bağlantı yanıtsız kapandı, error = yanıt okunurken hata; BODY_TIMEOUT: error). Varyant metni varsa o
  // kullanılır — yoksa genel metin. Sıfırlanan bağlantıya "N ms içinde yanıt gelmedi" demek yanlış teşhis olurdu.
  const reason = finding?.params?.reason
  const pick = (part) => {
    const v = reason ? `${base}.${part}.${reason}` : null
    return v && has(t, v) ? t(v) : t(`${base}.${part}`)
  }
  return { known: true, title: pick('title'), body: interpolate(pick('body'), p) }
}

/** Yalnız koddan başlık (geçmiş satırı — parametre yok). */
export function findingTitle(code, t) {
  return findingText({ code, params: {} }, t).title
}

/** Bulgu → çizime hazır kayıt. */
function decorate(f, t) {
  const { title, body } = findingText(f, t)
  return { code: f?.code, severity: f?.severity || 'info', path: f?.path || null, params: f?.params || {},
    title, body, tone: severityTone(f?.severity, f?.code) }
}

/**
 * Hüküm şeridi: en önemli bulgu (sunucunun `verdict`'i) + kalan bulgular. Hükümle AYNI kod+yol taşıyan bulgu listede
 * tekrar edilmez. Sunucu `verdict` göndermezse ilk bulgu hüküm sayılır (savunmacı).
 */
export function buildVerdict(data, t) {
  const v = data?.verdict || null
  const findings = arr(data?.findings)
  const head = v ? { code: v.code, severity: v.status === 'ok' ? 'info' : v.status, params: v.params, path: v.path }
    : findings[0] || { code: 'UNKNOWN', severity: 'warn', params: {} }
  const status = v?.status || (head.severity === 'fail' ? 'fail' : head.severity === 'warn' ? 'warn' : 'ok')
  const { title, body } = findingText(head, t)
  const same = (f) => f?.code === head.code && (f?.path || null) === (head.path || null)
  let skipped = false
  const others = []
  for (const f of findings) {
    if (!skipped && same(f)) { skipped = true; continue }
    others.push(decorate(f, t))
  }
  return {
    status, tone: verdictTone(status), code: head.code, title, body,
    failedStep: v?.failed_step || null, path: v?.path || head.path || null, others,
  }
}

/** Adımlar sözleşme sırasına dizilir; durum normalleşir. */
export function normalizeSteps(steps) {
  const list = arr(steps).filter((s) => s && s.key)
  const rank = (k) => { const i = STEP_ORDER.indexOf(k); return i < 0 ? STEP_ORDER.length : i }
  return list
    .map((s, i) => ({ s, i }))
    .sort((a, b) => rank(a.s.key) - rank(b.s.key) || a.i - b.i)
    .map(({ s }) => ({ key: s.key, state: stepState(s.status), ms: isNum(s.ms) ? s.ms : null }))
}

/** Yolun "takıldığı" (ya da son) hop'u — kart özetindeki adım hattı bu hop'tan çizilir. */
export function focusHopIndex(path) {
  const hops = arr(path?.hops)
  if (!hops.length) return -1
  const failing = hops.findIndex((h) => arr(h?.steps).some((s) => stepState(s?.status) === 'fail'))
  return failing >= 0 ? failing : hops.length - 1
}

/** Kart özeti: odak hop'unun adımları (yoksa boş). */
export function pathSummarySteps(path) {
  const i = focusHopIndex(path)
  return i < 0 ? [] : normalizeSteps(path.hops[i]?.steps)
}

/**
 * Zamanlama şelalesi — bölümler ARD ARDA (dns → connect → proxy → tls → ttfb → download); ölçek toplam süredir.
 * Yanıt hiç gelmediyse (ttfb yok) kalan süre "yanıt beklendi" bölümü olur — RESPONSE_TIMEOUT'ta 10 sn'lik çubuk
 * tam da bunu gösterir; gövde bitmediyse "gövde beklendi"; başarılı koşudaki küçük artık "diğer" (yönlendirme vb.).
 *
 * @returns {{ segments: Array<{key, ms, start, startPct, widthPct, kind}>, total: number|null }}
 */
export function timingSegments(timeline) {
  const tl = timeline || {}
  const known = TIMING_KEYS.map((k) => ({ key: k, ms: isNum(tl[`${k}_ms`]) ? tl[`${k}_ms`] : null }))
  const sum = known.reduce((s, x) => s + (x.ms ?? 0), 0)
  const total = isNum(tl.total_ms) ? tl.total_ms : (sum > 0 ? sum : null)
  const segs = known.filter((x) => x.ms != null).map((x) => ({ ...x, kind: 'phase' }))
  const rest = total != null ? total - sum : 0
  if (rest > 0) {
    const ttfbMissing = tl.ttfb_ms == null
    const downloadMissing = tl.download_ms == null
    const key = ttfbMissing ? 'waitResponse' : downloadMissing ? 'waitBody' : 'other'
    segs.push({ key, ms: rest, kind: key === 'other' ? 'other' : 'stalled' })
  }
  const scale = Math.max(total ?? 0, sum, 1)
  let start = 0
  const segments = segs.map((s) => {
    const startPct = Math.min(100, (start / scale) * 100)
    const raw = (s.ms / scale) * 100
    const widthPct = Math.min(100 - startPct, s.ms > 0 ? Math.max(raw, 0.75) : 0)
    const out = { ...s, start, startPct: round2(startPct), widthPct: round2(widthPct) }
    start += s.ms
    return out
  })
  return { segments, total }
}

const round2 = (n) => Math.round(n * 100) / 100

/** Döküm satırı türü: `*` bilgi, `>` gönderilen, `<` alınan (curl -v dili). */
export function classifyLine(line) {
  const s = String(line ?? '')
  if (s.startsWith('>')) return 'sent'
  if (s.startsWith('<')) return 'recv'
  if (s.startsWith('*')) return 'info'
  return 'other'
}

/** Döküm süzgeci: all | sent | recv | info. "Bilgi" süzgeci önek taşımayan satırları da gösterir. */
export function filterTranscript(lines, filter = 'all') {
  const rows = arr(lines).map((text, i) => ({ i, text: String(text ?? ''), kind: classifyLine(text) }))
  if (filter === 'all') return rows
  if (filter === 'info') return rows.filter((r) => r.kind === 'info' || r.kind === 'other')
  return rows.filter((r) => r.kind === filter)
}

/** Gizli değerin yerine yazılan işaret (sunucunun maskesiyle aynı). */
export const MASK = '••••'

/**
 * Başlık listesi — çizime güvenli satırlar (ad/değer dize, `masked` bayrağı, `display` = gösterilecek değer).
 * SAVUNMA: sunucu `masked: true` dediği hâlde değerde maske izi yoksa (beklenmeyen — sunucu maskesi atlanmış) değer
 * ekrana da rapora da HİÇ yazılmaz; Set-Cookie gibi kısmi maskeli değer ("ad=••••; path=/") olduğu gibi kalır.
 */
export function headerRows(headers) {
  return arr(headers).filter(Boolean).map((h, i) => {
    const value = h.value == null ? '' : String(h.value)
    const masked = h.masked === true
    return { i, name: String(h.name ?? ''), value, masked, display: masked && !value.includes('•') ? MASK : value }
  })
}

/** Bayt → okunur boyut (1 KB = 1024 B). */
export function formatBytes(n) {
  if (!isNum(n) || n < 0) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

/** Sertifikanın kalan günü → ton. */
export function daysTone(days) {
  if (!isNum(days)) return 'muted'
  if (days <= 0) return 'danger'
  if (days <= 14) return 'danger'
  if (days <= 30) return 'warning'
  return 'success'
}

/** İzleme istemcisi (client_check) ham ölçümle aynı sonucu mu verdi? true / false / null (karşılaştırılamaz). */
export function clientAgrees(path) {
  const c = path?.client_check
  if (!c || typeof c.ok !== 'boolean' || !path?.outcome) return null
  return c.ok === (path.outcome === 'ok')
}

/** Sunucu yanıtı hız sınırı mı (429)? Gövdede durum yoksa son başarısız çağrı halkasına bakılır. */
export function isRateLimited(res, recentFailures = []) {
  if (res && res.status === 429) return true
  for (let i = recentFailures.length - 1; i >= 0; i--) {
    const f = recentFailures[i]
    if (/\/monitoring\/http\/\d+\/diagnose/.test(String(f?.path || ''))) return f.status === 429
  }
  return false
}

/**
 * Başarısız yanıtın sınıfı — pencere hangi şeridi çizeceğini buna göre seçer.
 * @returns {'rateLimited'|'forbidden'|'notFound'|'network'|'other'}
 */
export function failureKind(res, recentFailures = []) {
  if (isRateLimited(res, recentFailures)) return 'rateLimited'
  if (res?.status === 403) return 'forbidden'
  if (res?.status === 404) return 'notFound'
  if (res == null || res.status === 0) return 'network'
  return 'other'
}

/** Geçmiş yanıtı: dizi ya da `{ items }` zarfı. */
export function historyItems(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.items)) return data.items
  return []
}

/** JSON dosya adı: http-diagnose-<izleme>-run<no>.json (no yoksa zaman damgası). */
export function reportFileName(data, now = new Date()) {
  const id = data?.monitor?.id ?? 'x'
  if (data?.run_id != null) return `http-diagnose-${id}-run${data.run_id}.json`
  const p = (n) => String(n).padStart(2, '0')
  return `http-diagnose-${id}-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}.json`
}

/** Tarayıcıda JSON indir (Blob) — jsdom'da sessizce çıkar (utils/csvExport.downloadCsv deseni). */
export function downloadJson(filename, obj) {
  try {
    const blob = new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => { try { URL.revokeObjectURL(url) } catch { /* indirme ortamı yok */ } }, 1000)
    return true
  } catch {
    return false
  }
}

// ── Rapor ────────────────────────────────────────────────────────────────────

/** Rapora konan gövde önizlemesi üst sınırı (karakter) — tam gövde JSON indirmede. */
export const REPORT_BODY_MAX = 2000

/** Biçim yazıcısı: Markdown ya da düz metin — aynı içerik, farklı işaretleme. */
function writer(format) {
  const md = format !== 'text'
  const L = []
  return {
    lines: L,
    h1: (s) => { L.push(md ? `# ${s}` : s); if (!md) L.push('='.repeat(Math.min(72, s.length))); L.push('') },
    h2: (s) => { if (L.length && L[L.length - 1] !== '') L.push(''); L.push(md ? `## ${s}` : s.toUpperCase()); if (!md) L.push('-'.repeat(Math.min(72, s.length))) },
    h3: (s) => { if (L.length && L[L.length - 1] !== '') L.push(''); L.push(md ? `### ${s}` : `[ ${s} ]`) },
    li: (s) => L.push(`- ${s}`),
    p: (s) => L.push(s),
    kv: (k, v) => L.push(md ? `- **${k}:** ${v}` : `- ${k}: ${v}`),
    code: (lines, lang = 'text') => {
      const body = arr(lines).map(String)
      if (!body.length) return
      if (md) { L.push('```' + lang); L.push(...body); L.push('```') } else body.forEach((x) => L.push(`    ${x}`))
    },
    blank: () => { if (L[L.length - 1] !== '') L.push('') },
  }
}

const ms = (v) => (isNum(v) ? `${v} ms` : '—')

/**
 * Paylaşılabilir rapor (bilet/sohbet). Ekranda ne varsa yazar: hüküm, bulgular, her yolun sonucu + adımlar +
 * zamanlama + izleme istemcisi + hata, her isteğin istek/yanıt satırı ve başlıkları (MASKELİ hâliyle), TLS özeti,
 * gövde önizlemesinin başı ve döküm. Gizli değerler sunucuda maskelendiği için rapor da maskelidir.
 *
 * @param {{data, t, formatDate?, format?: 'markdown'|'text'}} o
 */
export function buildReport({ data, t, formatDate, format = 'markdown' }) {
  const d = data || {}
  const w = writer(format)
  const fd = (iso) => (iso ? (formatDate ? formatDate(iso) : String(iso)) : '—')
  const mon = d.monitor || {}
  w.h1(t('httpdx.report.title'))
  w.kv(t('httpdx.report.monitor'), `${mon.name || mon.url || '—'}${mon.id != null ? ` (#${mon.id})` : ''}`)
  w.kv(t('httpdx.report.target'), `${mon.method || 'GET'} ${mon.url || '—'}`)
  if (mon.expected_status) w.kv(t('httpdx.report.expected'), mon.expected_status)
  w.kv(t('httpdx.report.run'), [d.run_id != null ? `#${d.run_id}` : null, fd(d.started_at), ms(d.duration_ms)].filter(Boolean).join(' · '))
  const src = d.source || {}
  w.kv(t('httpdx.report.source'), [src.pod && `pod ${src.pod}`, src.node && `node ${src.node}`, src.pod_ip && `IP ${src.pod_ip}`].filter(Boolean).join(' · ') || '—')
  if (d.proxy?.configured) {
    w.kv(t('httpdx.report.proxy'), [proxyAddress(d.proxy), d.proxy.no_proxy ? `NO_PROXY: ${d.proxy.no_proxy}` : null].filter(Boolean).join(' · '))
  }

  const v = buildVerdict(d, t)
  w.h2(`${t('httpdx.report.verdict')}: ${t(`httpdx.status.${v.status}`)} — ${v.title}`)
  w.p(v.body)
  if (v.failedStep) {
    w.p(`${t('httpdx.verdict.failedStep')}: ${t(`httpdx.step.${v.failedStep}`)}${v.path ? ` (${pathTitle(v.path, t)})` : ''}`)
  }
  if (v.others.length) {
    w.h3(t('httpdx.verdict.others'))
    for (const f of v.others) {
      w.li(`[${t(`httpdx.sev.${f.severity}`)}] ${f.title}${f.path ? ` (${pathTitle(f.path, t)})` : ''} — ${f.body}`)
    }
  }
  if (d.comparison?.available) {
    w.blank()
    w.p(t(d.comparison.differs ? 'httpdx.compare.differs' : 'httpdx.compare.same'))
  }

  for (const path of arr(d.paths)) {
    w.h2(`${pathTitle(path.key, t)} — ${routeLabel(path, d, t)}`)
    w.kv(t('httpdx.report.outcome'), [
      t(`httpdx.outcome.${path.outcome || 'fail'}`),
      path.http_status != null ? `HTTP ${path.http_status}` : t('httpdx.path.noResponse'),
      ms(path.total_ms),
      path.failed_step ? `${t('httpdx.verdict.failedStep')}: ${t(`httpdx.step.${path.failed_step}`)}` : null,
    ].filter(Boolean).join(' · '))
    const steps = pathSummarySteps(path)
    if (steps.length) {
      w.kv(t('httpdx.steps.label'), steps.map((s) => `${t(`httpdx.step.${s.key}`)} ${t(`httpdx.stepState.${s.state}`)}${s.ms != null ? ` ${s.ms} ms` : ''}`).join(' → '))
    }
    const tm = timingSegments(path.timeline)
    if (tm.segments.length) {
      w.kv(t('httpdx.timing.title'), tm.segments.map((s) => `${t(`httpdx.timing.${s.key}`)} ${s.ms} ms`).join(' · '))
    }
    const c = path.client_check
    if (c) {
      w.kv(t('httpdx.client.title'), [t(c.ok ? 'httpdx.client.ok' : 'httpdx.client.fail'),
        c.http_status != null ? `HTTP ${c.http_status}` : null, ms(c.response_ms), c.http_version, c.error].filter(Boolean).join(' · '))
    }
    if (path.error) w.kv(t('httpdx.error.title'), [path.error.class, path.error.message].filter(Boolean).join(': '))

    arr(path.hops).forEach((h, i) => {
      w.h3(`${t('httpdx.hop.label', i + 1)}: ${h.method || 'GET'} ${h.url || ''}`)
      if (h.dns) w.kv('DNS', `${h.dns.host || ''} → ${arr(h.dns.addresses).join(', ') || h.dns.error || '—'}`)
      if (h.tcp) w.kv('TCP', [h.tcp.local, h.tcp.remote].filter(Boolean).join(' → ') || '—')
      if (h.proxy) w.kv(t('httpdx.sec.proxy'), [proxyAddress(h.proxy), h.proxy.mode, h.proxy.connect_response?.status_line].filter(Boolean).join(' · '))
      if (h.tls) {
        w.kv('TLS', [h.tls.protocol, h.tls.cipher, h.tls.alpn && `ALPN ${h.tls.alpn}`, h.tls.sni && `SNI ${h.tls.sni}`,
          h.tls.trusted === true ? t('httpdx.tls.trusted') : h.tls.trusted === false ? t('httpdx.tls.untrusted') : null,
          h.tls.hostname_match === false ? t('httpdx.tls.mismatch') : null].filter(Boolean).join(' · '))
        const leaf = arr(h.tls.chain)[0]
        if (leaf) w.kv(t('httpdx.tls.chain'), `${leaf.subject || '—'} · ${leaf.not_after || '—'}${isNum(leaf.days_left) ? ` (${leaf.days_left})` : ''}`)
      }
      if (h.request) {
        w.p(`${t('httpdx.sec.request')}:`)
        w.code([h.request.line, ...headerRows(h.request.headers).map((r) => `${r.name}: ${r.display}`)].filter(Boolean), 'http')
      }
      if (h.response) {
        w.p(`${t('httpdx.sec.response')}:`)
        w.code([h.response.status_line, ...headerRows(h.response.headers).map((r) => `${r.name}: ${r.display}`)].filter(Boolean), 'http')
        const b = h.response.body
        if (b?.text && b.preview) {
          const cut = b.preview.length > REPORT_BODY_MAX
          w.p(`${t('httpdx.body.title')} (${b.content_type || '—'}, ${formatBytes(b.bytes)}${cut ? `, ${t('httpdx.report.bodyCut', REPORT_BODY_MAX)}` : ''}):`)
          w.code(b.preview.slice(0, REPORT_BODY_MAX).split('\n'), 'text')
        }
      } else {
        w.p(`${t('httpdx.sec.response')}: ${t('httpdx.res.none')}`)
      }
      if (h.redirect) w.kv(t('httpdx.sec.redirect'), `${h.redirect.status ?? ''} → ${h.redirect.next_url || h.redirect.location || '—'}`)
    })

    if (arr(path.transcript).length) {
      w.h3(t('httpdx.transcript.title'))
      w.code(path.transcript, 'text')
    }
  }
  w.blank()
  w.p(t('httpdx.source.egress'))
  return w.lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}
