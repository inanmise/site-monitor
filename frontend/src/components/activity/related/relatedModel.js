/**
 * "Aynı anda ne oldu?" (kök neden ipuçları, 2026-11-05, F) — saf model. Sunucu (`GET /api/activity/{id}/related`,
 * be2 `ActivityRelatedService`) pencere içindeki hatalı kontrolleri, neden / host / takım dağılımını ve KOD olarak
 * şüpheli neden + olgu listesini verir; metin burada kullanıcının dilinde kurulur:
 *   - `act.rel.suspect.<KOD>` (+ `.hint`) — şüpheli neden kartı
 *   - `act.rel.fact.<KOD>` — olgu satırı
 * Kod listeleri sunucunun `SUSPECT_CODES` / `FACT_CODES` sabitleriyle AYNI (kapı: activityRelatedCodes.test.js).
 */

export const SUSPECT_CODES = ['DNS_WIDE', 'NETWORK_WIDE', 'TLS_WIDE', 'PROXY_WIDE', 'SAME_HOST', 'SAME_TEAM', 'SINGLE']
export const FACT_CODES = ['FIRST_FAILURE_IN_DAYS', 'FAILURES_24H', 'TIMEOUTS_24H', 'LAST_SUCCESS', 'RECOVERED_AFTER', 'STILL_FAILING']

/** Pencere seçenekleri (sn): ±1 / ±2 / ±5 / ±15 dk — sunucu 30–900 kabul eder. */
export const WINDOWS = [60, 120, 300, 900]
export const DEFAULT_WINDOW = 120

/** Geniş çaplı (altyapı) şüpheler uyarı tonunda; aynı host / takım bilgi; tek izleme nötr. */
export function suspectTone(code) {
  if (/_WIDE$/.test(String(code || ''))) return 'warning'
  if (code === 'SINGLE') return 'neutral'
  return 'info'
}

/**
 * Yanıtın gövdesi: sunucu alanları ya kök düzeyde (`{ success, anchor, … }`) ya da `data` altında gelebilir — ikisi de
 * okunur. Gövde tanınmıyorsa null (okunamadı sayılır, "ilişkili hata yok" DEĞİL).
 */
export function unwrapRelated(res) {
  if (!res || typeof res !== 'object') return null
  const body = res.data && typeof res.data === 'object' && !Array.isArray(res.data) && ('items' in res.data || 'anchor' in res.data)
    ? res.data : res
  if (!Array.isArray(body.items) && !body.anchor) return null
  return {
    anchor: body.anchor || null,
    window_s: Number(body.window_s) || null,
    total: Number.isFinite(Number(body.total)) ? Number(body.total) : (Array.isArray(body.items) ? body.items.length : 0),
    by_reason: Array.isArray(body.by_reason) ? body.by_reason : [],
    by_host: Array.isArray(body.by_host) ? body.by_host : [],
    by_team: Array.isArray(body.by_team) ? body.by_team : [],
    items: Array.isArray(body.items) ? body.items : [],
    suspects: Array.isArray(body.suspects) ? body.suspects.filter((s) => SUSPECT_CODES.includes(s?.code)) : [],
    facts: Array.isArray(body.facts) ? body.facts.filter((f) => FACT_CODES.includes(f?.code)) : [],
  }
}

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/**
 * Şüphe parametreleri — sunucunun adlandırması kesinleşmeden (paralel geliştirme) eş adlar da okunur:
 * sayı `count | monitors | n`, oran `pct | percent | ratio (0–1)`, host, takım adı `team_name | team`.
 */
export function suspectParams(params = {}) {
  const p = params || {}
  const ratio = num(p.ratio)
  const pct = num(p.pct) ?? num(p.percent) ?? (ratio != null ? (ratio <= 1 ? ratio * 100 : ratio) : null)
  return {
    count: num(p.count) ?? num(p.monitors) ?? num(p.n),
    pct: pct != null ? Math.round(pct) : null,
    host: p.host ?? null,
    team: p.team_name ?? p.team ?? null,
    reason: p.reason ?? null,
  }
}

/** Adlı yer tutucuları doldurur; değeri olmayan "…" olur. */
function fill(template, params) {
  return String(template ?? '').replace(/\{([a-z][a-z0-9_]*)\}/gi, (_, name) => {
    const v = params[name]
    return v == null || v === '' ? '…' : String(v)
  })
}

/** Şüphe kartının başlık + ipucu metni (kullanıcının dilinde). */
export function suspectText(s, t) {
  const p = suspectParams(s?.params)
  return {
    title: fill(t(`act.rel.suspect.${s.code}`), p),
    hint: fill(t(`act.rel.suspect.${s.code}.hint`), p),
  }
}

/** Saniye → "45 sn" / "12 dk" / "3 sa 5 dk" / "2 g 4 sa" (birimler i18n, `incov.unit.*`). */
export function durationText(seconds, t) {
  const s = Math.max(0, Math.floor(Number(seconds) || 0))
  if (s < 60) return `${s} ${t('incov.unit.sec')}`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} ${t('incov.unit.min')}`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} ${t('incov.unit.hour')}${m % 60 ? ` ${m % 60} ${t('incov.unit.min')}` : ''}`
  const d = Math.floor(h / 24)
  return `${d} ${t('incov.unit.day')}${h % 24 ? ` ${h % 24} ${t('incov.unit.hour')}` : ''}`
}

/** Olgu satırı metni. `fmtTime(iso)` zamanı biçimler (İstanbul). Bilinmeyen değer "…" — uydurulmaz. */
export function factText(f, t, fmtTime) {
  const p = f?.params || {}
  const vals = {
    days: num(p.days),
    n: num(p.n) ?? num(p.count),
    at: p.at ? (fmtTime ? fmtTime(p.at) : String(p.at)) : null,
    duration: num(p.seconds) != null ? durationText(p.seconds, t) : null,
  }
  return fill(t(`act.rel.fact.${f.code}`), vals)
}

/** ISO (Z'siz = UTC) → ms; geçersiz → NaN. */
export function parseTime(iso) {
  if (!iso) return NaN
  const s = String(iso)
  return Date.parse(s.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(s) ? s : s + 'Z')
}

/** Satırın çapaya göre zaman farkı: "+45 sn" / "−2 dk" / "aynı anda". Okunamazsa ''. */
export function offsetText(anchorIso, rowIso, t) {
  const a = parseTime(anchorIso), b = parseTime(rowIso)
  if (!Number.isFinite(a) || !Number.isFinite(b)) return ''
  const diff = Math.round((b - a) / 1000)
  if (diff === 0) return t('act.rel.sameTime')
  const sign = diff > 0 ? '+' : '−'
  return `${sign}${durationText(Math.abs(diff), t)}`
}

/** Dağılım listesinin en büyük değeri (çubuk genişliği için; boşsa 1 — sıfıra bölme yok). */
export function maxCount(list) {
  return Math.max(1, ...(list || []).map((x) => Number(x?.count) || 0))
}
