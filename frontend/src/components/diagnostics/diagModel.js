/**
 * Bağlantı tanılama MODELİ — saf fonksiyonlar, React yok (2026-09-26 yeniden tasarım).
 *
 * <p>Backend (`ConnectionDiagnosticsService.diagnose`) her yolu ({direct, proxy} × {browser, default} TLS modu)
 * ayrı sondalar ve her sonda için ULAŞILAN AŞAMAYI döner: doğrudan yol `tcp-connect → tls-handshake → cert-ok`,
 * vekil yolu `proxy-connect → tls-handshake → cert-ok`; pod'un kendi DNS çözümlemesi (`dns`) ayrı ölçülür.
 * `step_reached` = HATANIN OLDUĞU aşama (başarılı sondada `cert-ok`). Burada bu ham matris, kullanıcıya
 * okunur bir "adım hattına" (DNS → TCP → Proxy → TLS → Sertifika → HTTP), tek cümlelik bir hükme
 * (en olası neden + sonraki adım), zamanlama şeritlerine ve düz metin rapora çevrilir.
 *
 * <p>Neden ayrı modül: hüküm mantığı i18n'siz test edilebilsin; pencere bileşeni yalnız çizsin.
 * `t` gereken yerlerde parametre olarak alınır (anahtar → metin), sözlük burada import EDİLMEZ.
 */
import { OWNER_KEY, personalKey } from '../../utils/personalStorage.js'

/** Adım hattı sırası — çizim de bu sırayı kullanır. */
export const STEP_KEYS = ['dns', 'tcp', 'proxy', 'tls', 'cert', 'http']

/** Backend aşama adı → sıra (bir sonda daha ileri bir aşamada düştüyse önceki aşamaları geçmiştir). */
const STAGE_RANK = { dns: 0, 'tcp-connect': 1, 'proxy-connect': 1, 'tls-handshake': 2, 'cert-ok': 3 }

/** Aşama → adım (hüküm metinlerinde "hangi adımda düştü" için). */
export const STAGE_STEP = { dns: 'dns', 'tcp-connect': 'tcp', 'proxy-connect': 'proxy', 'tls-handshake': 'tls', 'cert-ok': 'cert', unknown: null }

const rank = (stage) => STAGE_RANK[stage] ?? -1
const isOk = (c) => c?.status === 'ok'
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/** Sonda verilen aşamayı geçti mi? true / false / null (aşaması bilinmiyor — zaman aşımı sentetik kaydı). */
export function comboPassed(combo, stage) {
  if (isOk(combo)) return true
  const r = rank(combo?.step_reached)
  if (r < 0) return null
  return r > rank(stage)
}

/** Sonda tam bu aşamada mı düştü? */
export function comboFailedAt(combo, stage) {
  return !isOk(combo) && combo?.step_reached === stage
}

/** Sondaların bir adım için toplu durumu: ok | warning (karışık) | failed | skipped (hiçbiri ulaşmadı). */
function aggregate(combos, stage) {
  let passed = 0, failed = 0
  for (const c of combos) {
    if (comboFailedAt(c, stage)) failed++
    else if (comboPassed(c, stage) === true) passed++
  }
  if (failed > 0 && passed === 0) return 'failed'
  if (failed > 0) return 'warning'
  if (passed > 0) return 'ok'
  return 'skipped'
}

const minOf = (arr) => (arr.length ? Math.min(...arr) : null)
const maxOf = (arr) => (arr.length ? Math.max(...arr) : null)

/** Adım süresi: başarılıysa en hızlı tam yol, düştüyse en uzun düşme süresi (bekleyip düşen sonda). */
function stepDuration(combos, stage, status) {
  if (status === 'failed' || status === 'warning') {
    const ms = maxOf(combos.filter((c) => comboFailedAt(c, stage)).map((c) => num(c.elapsed_ms)).filter((v) => v != null))
    if (ms != null) return { ms, kind: 'fail' }
  }
  if (status === 'ok' || status === 'warning') {
    const ms = minOf(combos.filter(isOk).map((c) => num(c.elapsed_ms)).filter((v) => v != null))
    if (ms != null) return { ms, kind: 'path' }
  }
  return { ms: null, kind: null }
}

const modeKey = (c) => (c?.via === 'proxy' ? 'proxy' : 'direct') + ' · ' + (c?.tls_mode || '—')

/**
 * Adım hattı. `extras`: { hsts?, net? } — isteğe bağlı derin analiz sonuçları; HTTP adımı bunlardan dolar
 * (temel koşu HTTP isteği yapmaz — API boşluğu, aşağıda "pending").
 * @returns {Array<{key, status, duration:{ms,kind}, combos, facts:object, raw:object}>}
 */
export function buildSteps(data, extras = {}) {
  const d = data || {}
  const combos = Array.isArray(d.combos) ? d.combos : []
  const direct = combos.filter((c) => c.via !== 'proxy')
  const proxy = combos.filter((c) => c.via === 'proxy')
  const dns = d.dns || {}
  const ips = Array.isArray(dns.ips) ? dns.ips : []
  const steps = []

  // DNS — pod çözümlemesi
  const dnsFailed = Boolean(dns.error)
  steps.push({
    key: 'dns',
    status: dnsFailed ? 'failed' : (ips.length ? 'ok' : 'skipped'),
    duration: { ms: num(dns.elapsed_ms), kind: 'dns' },
    combos: [],
    facts: {
      ips,
      ipv4: ips.filter((ip) => !ip.includes(':')).length,
      ipv6: ips.filter((ip) => ip.includes(':')).length,
      error: dns.error || null,
    },
    raw: dns,
  })

  // TCP — yalnız doğrudan sondalar (vekil yolunda TCP proxy'ye kurulur, "proxy" adımında)
  const tcpStatus = aggregate(direct, 'tcp-connect')
  steps.push({
    key: 'tcp',
    status: tcpStatus,
    duration: stepDuration(direct, 'tcp-connect', tcpStatus),
    combos: direct,
    facts: {
      peers: uniq(direct.filter((c) => c.peer_ip).map((c) => `${c.peer_ip}:${c.peer_port ?? d.port ?? ''}`)),
      sources: uniq(direct.filter((c) => c.source_ip).map((c) => `${c.source_ip}:${c.source_port ?? ''}`)),
      failures: direct.filter((c) => comboFailedAt(c, 'tcp-connect')).map(failure),
      noDirect: direct.length === 0,
    },
    raw: direct,
  })

  // Proxy CONNECT — vekil yapılandırılmadıysa atlanır
  const proxyConfigured = Boolean(d.proxy_configured)
  const proxyStatus = proxyConfigured ? aggregate(proxy, 'proxy-connect') : 'skipped'
  steps.push({
    key: 'proxy',
    status: proxyStatus,
    duration: proxyConfigured ? stepDuration(proxy, 'proxy-connect', proxyStatus) : { ms: null, kind: null },
    combos: proxy,
    facts: {
      configured: proxyConfigured,
      address: d.proxy_address || null,
      peers: uniq(proxy.filter((c) => c.peer_ip).map((c) => `${c.peer_ip}:${c.peer_port ?? ''}`)),
      failures: proxy.filter((c) => comboFailedAt(c, 'proxy-connect')).map(failure),
    },
    raw: proxy,
  })

  // TLS — bağlantı kuran her sonda
  const reachedTls = combos.filter((c) => comboPassed(c, c.via === 'proxy' ? 'proxy-connect' : 'tcp-connect') === true)
  const tlsStatus = aggregate(reachedTls, 'tls-handshake')
  steps.push({
    key: 'tls',
    status: tlsStatus,
    duration: stepDuration(reachedTls, 'tls-handshake', tlsStatus),
    combos: reachedTls,
    facts: {
      negotiated: reachedTls.filter(isOk).map((c) => ({
        mode: modeKey(c), version: c.tls_version || null, cipher: c.cipher_suite || null, alpn: c.alpn || null,
      })),
      failures: reachedTls.filter((c) => comboFailedAt(c, 'tls-handshake')).map(failure),
    },
    raw: reachedTls,
  })

  // Sertifika — el sıkışmayı tamamlayan sondalar
  const handshook = combos.filter((c) => comboPassed(c, 'tls-handshake') === true)
  const certStatus = aggregate(handshook, 'cert-ok')
  const okCombos = handshook.filter(isOk)
  const days = minOf(okCombos.map((c) => num(c.days_remaining)).filter((v) => v != null))
  const ossl = extras.ossl?.certificate || null
  steps.push({
    key: 'cert',
    status: certStatus,
    duration: stepDuration(handshook, 'cert-ok', certStatus),
    combos: handshook,
    facts: {
      subject: okCombos.find((c) => c.subject)?.subject || null,
      cn: shortCn(okCombos.find((c) => c.subject)?.subject),
      days,
      issuer: ossl?.issuer || null,
      notAfter: ossl?.not_after || null,
      keySig: ossl ? [ossl.key_bits ? `${ossl.key_bits} bit` : null, ossl.signature_algorithm || null].filter(Boolean).join(' · ') || null : null,
      flags: Array.isArray(extras.ossl?.flags) ? extras.ossl.flags : [],
      failures: handshook.filter((c) => comboFailedAt(c, 'cert-ok')).map(failure),
    },
    raw: handshook,
  })

  // HTTP — temel koşuda yok; HSTS analizi (durum satırı + yönlendirme) ya da ağ analizinin curl izi doldurur
  steps.push(httpStep(extras))

  return steps
}

function httpStep(extras) {
  const hsts = extras.hsts
  if (hsts && typeof hsts === 'object') {
    const failed = hsts.status === 'error' || hsts.verdict === 'CONNECT_FAILED'
    return {
      key: 'http', status: failed ? 'failed' : 'ok', duration: { ms: num(hsts.elapsed_ms), kind: 'path' }, combos: [],
      facts: {
        source: 'hsts', statusLine: hsts.status_line || null, url: hsts.url || null,
        redirect: hsts.http_redirects_to_https ?? null, sts: hsts.raw_value || null, verdict: hsts.verdict || null,
        error: hsts.error || null, proxyUsed: hsts.proxy_used ?? null,
      },
      raw: hsts,
    }
  }
  const curl = Array.isArray(extras.net?.checks) ? extras.net.checks.find((c) => c.key === 'curl') : null
  if (curl) {
    const map = { ok: 'ok', warn: 'warning', fail: 'failed', na: 'skipped' }
    return {
      key: 'http', status: map[curl.status] || 'warning', duration: { ms: null, kind: null }, combos: [],
      facts: { source: 'curl', summary: curl.summary || null, command: curl.command || null, statusLine: curlStatusLine(curl.output) },
      raw: curl,
    }
  }
  return { key: 'http', status: 'pending', duration: { ms: null, kind: null }, combos: [], facts: { source: null }, raw: null }
}

/** curl -v çıktısından ilk yanıt durum satırı ("< HTTP/2 200"). */
export function curlStatusLine(output) {
  if (!output) return null
  const m = String(output).match(/^<\s*(HTTP\/[\d.]+\s+\d{3}[^\n]*)/m)
  return m ? m[1].trim() : null
}

function failure(c) {
  return { mode: modeKey(c), errorClass: c.error_class || 'UNKNOWN', error: c.error || null, elapsedMs: num(c.elapsed_ms) }
}

function uniq(arr) { return [...new Set(arr)] }

/** "CN=www.example.com, O=…" → "www.example.com". */
export function shortCn(dn) {
  if (!dn) return null
  const m = /CN=([^,]+)/i.exec(dn)
  return m ? m[1].trim() : dn
}

/** Kalan güne göre rozet tonu (proje eşikleri: 7 / 30). */
export function daysTone(days) {
  if (days == null) return 'muted'
  if (days <= 0) return 'danger'
  if (days <= 7) return 'danger'
  if (days <= 30) return 'warning'
  return 'success'
}

/**
 * Hüküm: ton + başlık + en olası neden + sonraki adım (+ sertifika notları). Metinler `t` ile çözülür.
 * @param {object} data   API sonucu
 * @param {Array}  steps  buildSteps çıktısı
 * @param {Function} t    i18n
 */
export function buildVerdict(data, steps, t) {
  const d = data || {}
  const combos = Array.isArray(d.combos) ? d.combos : []
  const total = combos.length
  const okCombos = combos.filter(isOk)
  const ok = okCombos.length
  const known = combos.filter((c) => isOk(c) || rank(c.step_reached) >= 0)
  const stepLabel = (stage) => t(STEP_LABEL_KEY[STAGE_STEP[stage]] || 'inv.diagColStep')
  const target = `${d.domain || '—'}:${d.port ?? 443}`
  const sourceIp = d.source?.ips?.[0] || d.source?.pod_ip || '—'

  let v
  if (total === 0 || known.length === 0) {
    v = { tone: 'warning', stage: null, title: t('diag.verdict.unknown'), cause: t('diag.verdict.unknownCause'), next: t('diag.verdict.unknownNext') }
  } else if (ok === total) {
    v = { tone: 'success', stage: null, title: t('diag.verdict.reachable', ok, total), cause: t(d.proxy_configured ? 'diag.verdict.reachableCause' : 'diag.verdict.reachableDirectCause'), next: null }
  } else {
    const direct = combos.filter((c) => c.via !== 'proxy')
    const proxy = combos.filter((c) => c.via === 'proxy')
    const allFail = (arr) => arr.length > 0 && arr.every((c) => !isOk(c))
    const allOk = (arr) => arr.length > 0 && arr.every(isOk)
    const dominant = dominantStage(combos.filter((c) => !isOk(c)))
    if (ok === 0) {
      if (d.dns?.error || dominant === 'dns') {
        v = { tone: 'danger', stage: 'dns', title: t('diag.verdict.dns', d.dns?.error || t('diag.status.failed')), cause: t('diag.verdict.dnsCause'), next: t('diag.verdict.dnsNext') }
      } else if (dominant === 'tcp-connect') {
        v = { tone: 'danger', stage: 'tcp', title: t('diag.verdict.tcp'), cause: t('diag.verdict.tcpCause'), next: t('diag.verdict.tcpNext', sourceIp, target) }
      } else if (dominant === 'proxy-connect') {
        v = { tone: 'danger', stage: 'proxy', title: t('diag.verdict.proxy'), cause: t('diag.verdict.proxyCause'), next: t('diag.verdict.proxyNext', target) }
      } else if (dominant === 'tls-handshake') {
        v = { tone: 'danger', stage: 'tls', title: t('diag.verdict.tls'), cause: t('diag.verdict.tlsCause'), next: t('diag.verdict.tlsNext') }
      } else if (dominant === 'cert-ok') {
        v = { tone: 'danger', stage: 'cert', title: t('diag.verdict.cert'), cause: t('diag.verdict.certCause'), next: t('diag.verdict.certNext') }
      } else {
        v = { tone: 'warning', stage: null, title: t('diag.verdict.unknown'), cause: t('diag.verdict.unknownCause'), next: t('diag.verdict.unknownNext') }
      }
    } else if (allFail(direct) && allOk(proxy)) {
      const st = dominantStage(direct)
      v = d.dns?.error
        ? { tone: 'warning', stage: 'dns', title: t('diag.verdict.splitDns'), cause: t('diag.verdict.splitDnsCause'), next: t('diag.verdict.proxyOnlyNext') }
        : { tone: 'warning', stage: STAGE_STEP[st] || null, title: t('diag.verdict.proxyOnly'), cause: t('diag.verdict.proxyOnlyCause', stepLabel(st)), next: t('diag.verdict.proxyOnlyNext') }
    } else if (allFail(proxy) && allOk(direct)) {
      const st = dominantStage(proxy)
      v = { tone: 'warning', stage: STAGE_STEP[st] || null, title: t('diag.verdict.directOnly'), cause: t('diag.verdict.directOnlyCause', stepLabel(st)), next: t('diag.verdict.directOnlyNext') }
    } else {
      const fp = fingerprintSplit(direct) || fingerprintSplit(proxy)
      if (fp) {
        v = { tone: 'warning', stage: 'tls', title: t('diag.verdict.fingerprint'), cause: t('diag.verdict.fingerprintCause', t(fp === 'browser' ? 'inv.diagTlsBrowserMode' : 'inv.diagTlsDefaultMode')), next: t('diag.verdict.fingerprintNext') }
      } else {
        v = { tone: 'warning', stage: STAGE_STEP[dominant] || null, title: t('diag.verdict.partial', ok, total), cause: t('diag.verdict.partialCause', stepLabel(dominant)), next: t('diag.verdict.partialNext') }
      }
    }
  }

  // Sertifika notları — erişilebilir olsa da süresi dolmuş/dolmak üzere sertifika hükmü sertleştirir
  const notes = []
  const days = minOf(okCombos.map((c) => num(c.days_remaining)).filter((x) => x != null))
  if (days != null) {
    if (days <= 0) { notes.push({ tone: 'danger', text: t('diag.verdict.certExpired') }); if (v.tone === 'success') v = { ...v, tone: 'danger' } }
    else if (days <= 14) { notes.push({ tone: 'warning', text: t('diag.verdict.certExpiring', days) }); if (v.tone === 'success') v = { ...v, tone: 'warning' } }
  }
  const unknowns = combos.filter((c) => !isOk(c) && rank(c.step_reached) < 0)
  if (unknowns.length && ok > 0) notes.push({ tone: 'warning', text: t('diag.verdict.unknownNote', unknowns.length) })
  return { ...v, ok, total, notes }
}

/** En çok sondanın düştüğü aşama (eşitlikte en erken aşama). */
function dominantStage(failed) {
  const counts = new Map()
  for (const c of failed) {
    const s = rank(c.step_reached) >= 0 ? c.step_reached : 'unknown'
    counts.set(s, (counts.get(s) || 0) + 1)
  }
  let best = null, bestN = -1
  for (const [s, n] of counts) {
    if (n > bestN || (n === bestN && rank(s) < rank(best))) { best = s; bestN = n }
  }
  return best
}

/** Aynı yolda yalnız bir TLS modu el sıkışmada düşüyorsa o modu döner (WAF parmak izi bulgusu). */
function fingerprintSplit(sameVia) {
  if (sameVia.length < 2) return null
  const failed = sameVia.filter((c) => comboFailedAt(c, 'tls-handshake'))
  const ok = sameVia.filter(isOk)
  if (failed.length === 1 && ok.length >= 1) return failed[0].tls_mode || null
  return null
}

/** Adım → etiket anahtarı (mevcut `inv.diagStep*` sözlüğü + yeni sertifika/HTTP anahtarları). */
export const STEP_LABEL_KEY = {
  dns: 'inv.diagStepDns', tcp: 'inv.diagStepTcp', proxy: 'inv.diagStepProxyConnect',
  tls: 'inv.diagStepTls', cert: 'diag.step.cert', http: 'diag.step.http',
}

/** Zamanlama şeritleri: DNS + her sonda (paralel koşarlar; toplam `elapsed_ms` ayrı gösterilir). */
export function buildLanes(data) {
  const d = data || {}
  const lanes = []
  if (d.dns) lanes.push({ key: 'dns', kind: 'dns', label: null, ms: num(d.dns.elapsed_ms), status: d.dns.error ? 'failed' : 'ok' })
  for (const c of Array.isArray(d.combos) ? d.combos : []) {
    lanes.push({
      key: c.id || modeKey(c), kind: 'combo', via: c.via, mode: c.tls_mode, ms: num(c.elapsed_ms),
      status: isOk(c) ? 'ok' : 'failed', stage: isOk(c) ? 'cert-ok' : c.step_reached || 'unknown',
    })
  }
  const max = Math.max(num(d.elapsed_ms) ?? 0, ...lanes.map((l) => l.ms ?? 0), 1)
  return { lanes, max, total: num(d.elapsed_ms) }
}

/** Düz metin rapor (panoya kopyalanır; ekip/kayıt paylaşımı için). */
export function buildReport({ data, steps, verdict, t, formatDate, now = new Date() }) {
  const d = data || {}
  const L = []
  L.push(`SiteMonitor — ${t('inv.diagTitle', d.domain || '—')}`)
  L.push(`${t('diag.report.target')}: ${d.domain || '—'}:${d.port ?? 443}`)
  const src = d.source || {}
  const srcBits = [src.hostname, (src.ips || []).join(', '), src.node_name ? `node ${src.node_name}` : null].filter(Boolean).join(' · ')
  L.push(`${t('diag.report.when')}: ${formatDate ? formatDate(now.toISOString().slice(0, 19)) : now.toISOString()}${srcBits ? ` (${srcBits})` : ''}`)
  L.push('')
  L.push(`${t('diag.report.verdict')}: ${verdict.title}`)
  if (verdict.cause) L.push(`${t('diag.report.cause')}: ${verdict.cause}`)
  if (verdict.next) L.push(verdict.next)
  for (const n of verdict.notes || []) L.push(`! ${n.text}`)
  L.push('')
  L.push(`${t('diag.pipeline')}:`)
  for (const s of steps) {
    const st = t('diag.status.' + s.status)
    let extra = ''
    if (s.key === 'dns') extra = s.facts.error ? s.facts.error : s.facts.ips.join(', ')
    else if (s.key === 'tls') extra = s.facts.negotiated.map((n) => `${n.mode}: ${[n.version, n.cipher, n.alpn ? 'ALPN ' + n.alpn : null].filter(Boolean).join(' / ')}`).join('; ')
    else if (s.key === 'cert') extra = [s.facts.subject, s.facts.days != null ? t('diag.daysLeft', s.facts.days) : null].filter(Boolean).join(' · ')
    else if (s.key === 'proxy') extra = s.facts.address || ''
    else if (s.key === 'http') extra = s.facts.statusLine || s.facts.summary || ''
    const fails = (s.facts.failures || []).map((f) => `${f.mode}: ${f.errorClass}${f.error ? ' — ' + f.error : ''}`).join('; ')
    const ms = s.duration.ms != null ? ` (${s.duration.ms} ms)` : ''
    L.push(`  ${t(STEP_LABEL_KEY[s.key])}: ${st}${ms}${extra ? ' — ' + extra : ''}${fails ? ' — ' + fails : ''}`)
  }
  L.push('')
  L.push(`${t('inv.diagMatrix')}:`)
  for (const c of Array.isArray(d.combos) ? d.combos : []) {
    const head = `  ${modeKey(c)}: ${isOk(c) ? t('inv.diagOk') : (c.error_class || 'ERROR')} · ${c.step_reached || '—'} · ${c.elapsed_ms ?? '—'} ms`
    const tail = isOk(c)
      ? [c.tls_version, c.cipher_suite, c.alpn ? 'ALPN ' + c.alpn : null, c.days_remaining != null ? `${c.days_remaining}d` : null].filter(Boolean).join(' · ')
      : (c.error || '')
    L.push(tail ? `${head} · ${tail}` : head)
  }
  return L.join('\n')
}

/**
 * İki bağlantı koşusu arasındaki fark satırları (DiffTable: [anahtar, alan, önceki, şimdi]).
 * Yalnız DEĞİŞEN alanlar döner; boş dizi = aynı sonuç.
 */
export function diffRuns(now, prev, t) {
  const rows = []
  const push = (key, label, a, b) => { if (String(a ?? '—') !== String(b ?? '—')) rows.push([key, label, a ?? '—', b ?? '—']) }
  const okCount = (r) => (Array.isArray(r?.combos) ? r.combos.filter(isOk).length : 0)
  const total = (r) => (Array.isArray(r?.combos) ? r.combos.length : 0)
  push('ok', t('diag.compare.paths'), `${okCount(prev)}/${total(prev)}`, `${okCount(now)}/${total(now)}`)
  push('dns', t('inv.diagDnsIps'), (prev?.dns?.ips || []).join(', ') || prev?.dns?.error, (now?.dns?.ips || []).join(', ') || now?.dns?.error)
  const ids = uniq([...(prev?.combos || []), ...(now?.combos || [])].map((c) => c.id || modeKey(c)))
  for (const id of ids) {
    const a = (prev?.combos || []).find((c) => (c.id || modeKey(c)) === id)
    const b = (now?.combos || []).find((c) => (c.id || modeKey(c)) === id)
    const res = (c) => (c ? (isOk(c) ? t('inv.diagOk') : `${c.error_class || 'ERROR'} @ ${c.step_reached || '—'}`) : null)
    push(`${id}:status`, `${id} · ${t('inv.diagColResult')}`, res(a), res(b))
    push(`${id}:tls`, `${id} · TLS`, a && [a.tls_version, a.cipher_suite].filter(Boolean).join(' / '), b && [b.tls_version, b.cipher_suite].filter(Boolean).join(' / '))
    push(`${id}:days`, `${id} · ${t('diag.compare.days')}`, a?.days_remaining, b?.days_remaining)
  }
  return rows
}

/**
 * 429 tespiti. Sunucu hız sınırında `{success:false, error}` döner ve durum kodu gövdede YOKTUR (API boşluğu);
 * istemcinin başarısız-istek halkası (`getRecentFailures`) yol + durum kodunu tutar — o yola ait EN SON kayıt
 * 429 ise sınır aşılmıştır. Gövde `status` taşıyorsa (JSON olmayan yanıt) o da kabul edilir.
 */
export function wasRateLimited(res, pathSuffix, recentFailures) {
  if (res && res.status === 429) return true
  const list = Array.isArray(recentFailures) ? recentFailures : []
  for (let i = list.length - 1; i >= 0; i--) {
    if (String(list[i]?.path || '').endsWith(pathSuffix)) return list[i].status === 429
  }
  return false
}

/** Kullanıcı girdisini sunucu adına indirger: şema, yol, kimlik, port ve son nokta atılır; küçük harf. */
export function normaliseDomainInput(raw) {
  let s = String(raw ?? '').trim().toLowerCase()
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  s = s.split(/[/?#]/)[0]
  s = s.replace(/^[^@]*@/, '')
  s = s.replace(/:\d+$/, '')
  s = s.replace(/\.+$/, '')
  return s
}

const LABEL_RE = /^(?!-)[\p{L}\p{N}-]{1,63}(?<!-)$/u

/** Geçerli bir sunucu adı mı (en az iki etiket, TLD rakam değil, etiket kuralları)? */
export function isValidDomain(host) {
  if (!host || host.length > 253) return false
  const parts = host.split('.')
  if (parts.length < 2) return false
  if (!parts.every((p) => LABEL_RE.test(p))) return false
  return !/^\d+$/.test(parts[parts.length - 1])
}

/**
 * Son sorgular (localStorage) — en yeni başta, tekrar yok, en fazla `max`. Anahtar KULLANICIYA göre ayrılır ve çıkışta
 * silinir (başka takımların alan adları paylaşılan makinede sonraki kişiye kalmasın) — utils/personalStorage.js, B9.
 */
export const RECENT_KEY = 'sm.dexp.recent'
function recentKey(storage) {
  let owner = null
  try { owner = storage?.getItem(OWNER_KEY) || null } catch { /* depolama kapalı */ }
  return personalKey(RECENT_KEY, owner)
}
export function readRecent(storage = globalThis.localStorage) {
  try {
    const v = JSON.parse(storage?.getItem(recentKey(storage)) || '[]')
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string').slice(0, 8) : []
  } catch { return [] }
}
export function pushRecent(domain, storage = globalThis.localStorage, max = 8) {
  const next = [domain, ...readRecent(storage).filter((x) => x !== domain)].slice(0, max)
  try { storage?.setItem(recentKey(storage), JSON.stringify(next)) } catch { /* özel pencere / kota */ }
  return next
}
export function clearRecent(storage = globalThis.localStorage) {
  try { storage?.removeItem(recentKey(storage)) } catch { /* yoksay */ }
  return []
}
