/**
 * Kripto envanteri / PQC hazırlık — saf model (2026-10-10). Sınıflandırma ve öncelik puanı SUNUCUDADIR
 * (`CryptoClassifier`, `PqcMigrationPriority` — tek kaynak); burada yalnız süzme, sıralama, takım seçenekleri ve
 * dışa aktarım tablosu var. Kod sabitleri sunucunun enum sırasıyla aynıdır (çeviri anahtarları `cinv.*.<KOD>`).
 */

export const CATEGORIES = ['BROKEN', 'LEGACY', 'MODERN', 'PQC_READY', 'UNKNOWN']
export const PQC_STATES = ['VULNERABLE', 'HYBRID', 'PQC', 'UNKNOWN']
export const BANDS = ['P1', 'P2', 'P3', 'P4', 'DONE']
export const BUCKETS = ['RSA_1024', 'RSA_LT2048', 'RSA_2048', 'RSA_3072', 'RSA_4096', 'RSA_OTHER', 'EC_P256', 'EC_P384',
  'EC_P521', 'EC_OTHER', 'ED25519', 'ED448', 'DSA', 'PQC', 'HYBRID', 'OTHER', 'UNKNOWN']
export const SIG_HASHES = ['MD5', 'SHA1', 'SHA224', 'SHA256', 'SHA384', 'SHA512', 'EDDSA', 'PQC', 'HYBRID', 'OTHER', 'UNKNOWN']
export const REMNANTS = ['MD5_LEAF', 'SHA1_LEAF', 'MD5_INTERMEDIATE', 'SHA1_INTERMEDIATE']
export const SOURCES = ['NETWORK', 'MANUAL']
export const TIERS = ['1', '2', '3', '4', 'none']
export const SORTS = ['priority', 'expiry', 'domain', 'team', 'tier', 'key']
export const ACTIONS = ['replace', 'reissue', 'renew', 'pqc_plan', 'collect', 'none']

/** Kova → bugünkü güç tonu (çubuk rengi): zayıf / 2030 altı / güçlü / PQC / bilinmiyor. */
export const BUCKET_TONE = {
  RSA_1024: 'bad', RSA_LT2048: 'bad', RSA_2048: 'warn', RSA_3072: 'ok', RSA_4096: 'ok', RSA_OTHER: 'muted',
  EC_P256: 'ok', EC_P384: 'ok', EC_P521: 'ok', EC_OTHER: 'muted', ED25519: 'ok', ED448: 'ok', DSA: 'warn',
  PQC: 'pqc', HYBRID: 'pqc', OTHER: 'muted', UNKNOWN: 'muted',
}
export const HASH_TONE = {
  MD5: 'bad', SHA1: 'bad', SHA224: 'ok', SHA256: 'ok', SHA384: 'ok', SHA512: 'ok', EDDSA: 'ok', PQC: 'pqc', HYBRID: 'pqc',
  OTHER: 'muted', UNKNOWN: 'muted',
}
export const CATEGORY_TONE = { BROKEN: 'bad', LEGACY: 'warn', MODERN: 'ok', PQC_READY: 'pqc', UNKNOWN: 'muted' }
export const BAND_TONE = { P1: 'bad', P2: 'warn', P3: 'info', P4: 'muted', DONE: 'pqc' }
export const PQC_TONE = { VULNERABLE: 'warn', HYBRID: 'pqc', PQC: 'pqc', UNKNOWN: 'muted' }

/** Süzgeçlerin varsayılanı — URL'de `ci_*` anahtarlarıyla taşınır (varsayılan yazılmaz). */
export const DEFAULT_FILTERS = Object.freeze({
  q: '', team: '', category: '', pqc: '', tier: '', source: '', band: '', bucket: '', hash: '', remnant: false, due: false,
  sort: 'priority',
})

/** Süzgeç anahtarı ↔ URL parametresi. */
export const URL_KEYS = Object.freeze({
  q: 'ci_q', team: 'ci_team', category: 'ci_cat', pqc: 'ci_pqc', tier: 'ci_tier', source: 'ci_src', band: 'ci_band',
  bucket: 'ci_key', hash: 'ci_hash', remnant: 'ci_rem', due: 'ci_due', sort: 'ci_sort',
})

/** Yenileme penceresi süzgecinin gün sınırı (KPI "≤ 90 gün yenilenecek" ile aynı — sunucu `vulnerable_expiring_90d`). */
export const DUE_DAYS = 90

const ALLOWED = {
  category: CATEGORIES, pqc: PQC_STATES, band: BANDS, bucket: BUCKETS, hash: SIG_HASHES, source: SOURCES, tier: TIERS,
  sort: SORTS,
}

/** URL'den okunmuş ham değerleri doğrular: tanınmayan kod varsayılana düşer (paylaşılan bağlantı bozuk olsa da çalışır). */
export function sanitizeFilters(raw = {}) {
  const f = { ...DEFAULT_FILTERS }
  for (const k of Object.keys(DEFAULT_FILTERS)) {
    const v = raw[k]
    if (v == null || v === '') continue
    if (k === 'remnant' || k === 'due') f[k] = v === true || v === '1' || v === 'true'
    else if (ALLOWED[k]) f[k] = ALLOWED[k].includes(String(v)) ? String(v) : DEFAULT_FILTERS[k]
    else f[k] = String(v)
  }
  return f
}

/** Süzgeç → URL eşlemesi (useUrlQuerySync için; varsayılan null = yazılmaz). */
export function filtersToUrl(f) {
  const out = {}
  for (const [k, key] of Object.entries(URL_KEYS)) {
    const v = f[k]
    out[key] = v === DEFAULT_FILTERS[k] || v === '' || v === false ? null : v === true ? '1' : String(v)
  }
  return out
}

/** Sıralama dışında etkin süzgeç var mı. */
export function isFiltered(f) {
  return Object.keys(DEFAULT_FILTERS).some((k) => k !== 'sort' && f[k] !== DEFAULT_FILTERS[k])
}

const COLLATOR = typeof Intl !== 'undefined' ? new Intl.Collator('tr', { sensitivity: 'base', numeric: true }) : null
const cmpText = (a, b) => (COLLATOR ? COLLATOR.compare(String(a ?? ''), String(b ?? '')) : String(a ?? '').localeCompare(String(b ?? '')))
const lower = (s) => String(s ?? '').toLocaleLowerCase('tr')

/** Satır süzgeci — arama alan adı, takım, sahip, grup, algoritma adlarında (Türkçe büyük/küçük harf duyarsız). */
export function filterRows(rows, f) {
  const q = lower(f.q).trim()
  return (rows || []).filter((r) => {
    if (f.team) {
      if (f.team === 'none' ? r.team_id != null : String(r.team_id) !== f.team) return false
    }
    if (f.category && r.category !== f.category) return false
    if (f.pqc && r.pqc !== f.pqc) return false
    if (f.band && r.priority?.band !== f.band) return false
    if (f.source && r.source !== f.source) return false
    if (f.bucket && r.key_bucket !== f.bucket) return false
    if (f.hash && r.sig_hash !== f.hash) return false
    if (f.tier) {
      if (f.tier === 'none' ? r.tier != null : String(r.tier) !== f.tier) return false
    }
    if (f.remnant && !(r.remnants || []).length) return false
    if (f.due && !(r.pqc === 'VULNERABLE' && r.days_remaining != null && r.days_remaining <= DUE_DAYS)) return false
    if (q) {
      const hay = [r.domain, r.team_name, r.ug_team_name, r.owner, r.group_name, r.key_algorithm, r.signature_algorithm]
      if (!hay.some((h) => lower(h).includes(q))) return false
    }
    return true
  })
}

const nullsLast = (a, b) => {
  if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1
  return a - b
}
const byRank = (a, b) => (a.rank ?? 0) - (b.rank ?? 0)

/** Sıralama — varsayılan öncelik (sunucunun `rank` sırası); diğerleri eşitlikte önceliğe düşer. */
export function sortRows(rows, sort = 'priority') {
  const list = [...(rows || [])]
  switch (sort) {
    case 'expiry': return list.sort((a, b) => nullsLast(a.days_remaining, b.days_remaining) || byRank(a, b))
    case 'domain': return list.sort((a, b) => cmpText(a.domain, b.domain) || byRank(a, b))
    case 'team': return list.sort((a, b) => {
      if ((a.team_name == null) !== (b.team_name == null)) return a.team_name == null ? 1 : -1
      return cmpText(a.team_name, b.team_name) || byRank(a, b)
    })
    case 'tier': return list.sort((a, b) => nullsLast(a.tier, b.tier) || byRank(a, b))
    case 'key': return list.sort((a, b) => BUCKETS.indexOf(a.key_bucket) - BUCKETS.indexOf(b.key_bucket) || byRank(a, b))
    default: return list.sort(byRank)
  }
}

/** Takım seçenekleri — satırlardan (birincil takım), A→Z Türkçe; takımsız satır varsa en sonda `none`. */
export function teamOptions(rows) {
  const map = new Map()
  let none = false
  for (const r of rows || []) {
    if (r.team_id == null) { none = true; continue }
    if (!map.has(String(r.team_id))) map.set(String(r.team_id), r.team_name || `#${r.team_id}`)
  }
  const opts = [...map.entries()].map(([value, label]) => ({ value, label })).sort((a, b) => cmpText(a.label, b.label))
  if (none) opts.push({ value: 'none', label: null })
  return opts
}

/** PQC hazır payı (hibrit + PQC) ve kuantuma açık payı — yüzde, bir ondalık. */
export function readiness(summary) {
  const total = Number(summary?.total) || 0
  const by = summary?.by_pqc || {}
  const pct = (n) => (total ? Math.round((1000 * (Number(n) || 0)) / total) / 10 : 0)
  return {
    total,
    vulnerable: Number(by.VULNERABLE) || 0,
    ready: (Number(by.HYBRID) || 0) + (Number(by.PQC) || 0),
    unknown: Number(by.UNKNOWN) || 0,
    vulnerablePct: pct(by.VULNERABLE),
    readyPct: pct((Number(by.HYBRID) || 0) + (Number(by.PQC) || 0)),
  }
}

/** Kova etiketi — çeviri anahtarı yerine teknik ad (dile göre değişmez; "Diğer/Bilinmiyor" çevrilir). */
export function bucketLabel(bucket, t) {
  const fixed = {
    RSA_1024: 'RSA ≤1024', RSA_LT2048: 'RSA 1025–2047', RSA_2048: 'RSA 2048', RSA_3072: 'RSA 3072', RSA_4096: 'RSA 4096+',
    EC_P256: 'ECDSA P-256', EC_P384: 'ECDSA P-384', EC_P521: 'ECDSA P-521', ED25519: 'Ed25519', ED448: 'Ed448', DSA: 'DSA',
  }
  return fixed[bucket] || t(`cinv.bucket.${bucket}`)
}

/** İmza özeti etiketi. */
export function hashLabel(hash, t) {
  const fixed = { MD5: 'MD5', SHA1: 'SHA-1', SHA224: 'SHA-224', SHA256: 'SHA-256', SHA384: 'SHA-384', SHA512: 'SHA-512', EDDSA: 'EdDSA' }
  return fixed[hash] || t(`cinv.hash.${hash}`)
}

/** Satırın anahtar metni: "RSA 2048" / "EC 256" / "—". */
export function keyText(r) {
  if (!r?.key_algorithm) return '—'
  return r.key_size ? `${r.key_algorithm} ${r.key_size}` : r.key_algorithm
}

/** Kalan gün metni. */
export function daysText(days, t) {
  if (days == null) return '—'
  return days < 0 ? t('cinv.daysPast', Math.abs(days)) : t('cinv.daysLeft', days)
}

/** Etkin süzgeçlerin okunur özeti (dışa aktarım başlığı) — süzgeç yoksa "Süzgeç yok". */
export function filterSummary(f, t, teamName) {
  const parts = []
  if (f.q) parts.push(`${t('cinv.f.search')}: "${f.q}"`)
  if (f.team) parts.push(`${t('cinv.f.team')}: ${f.team === 'none' ? t('cinv.noTeam') : (teamName?.(f.team) || f.team)}`)
  if (f.category) parts.push(`${t('cinv.f.category')}: ${t(`cinv.cat.${f.category}`)}`)
  if (f.pqc) parts.push(`${t('cinv.f.pqc')}: ${t(`cinv.pqc.${f.pqc}`)}`)
  if (f.band) parts.push(`${t('cinv.f.band')}: ${t(`cinv.band.${f.band}`)}`)
  if (f.tier) parts.push(`${t('cinv.f.tier')}: ${f.tier === 'none' ? t('cinv.tierNone') : `T${f.tier}`}`)
  if (f.source) parts.push(`${t('cinv.f.source')}: ${t(`cinv.src.${f.source}`)}`)
  if (f.bucket) parts.push(`${t('cinv.f.key')}: ${bucketLabel(f.bucket, t)}`)
  if (f.hash) parts.push(`${t('cinv.f.hash')}: ${hashLabel(f.hash, t)}`)
  if (f.remnant) parts.push(t('cinv.f.remnantOn'))
  if (f.due) parts.push(t('cinv.f.dueOn', DUE_DAYS))
  return parts.length ? parts.join(' · ') : t('cinv.f.none')
}

/** Kapsam metni: "Tüm takımlar" ya da "Takımlar: A, B". */
export function scopeText(scope, t) {
  if (!scope || scope.all) return t('cinv.scopeAll')
  const names = (scope.teams || []).map((x) => x.name || `#${x.id}`)
  return names.length ? t('cinv.scopeTeams', names.join(', ')) : t('cinv.scopeNone')
}

/** Dışa aktarım sütunları (XLSX / CSV / PDF ortak; PDF bir alt küme seçer). */
export function exportColumns(t) {
  return [
    t('cinv.col.rank'), t('cinv.col.score'), t('cinv.col.band'), t('cinv.col.domain'), t('cinv.col.source'), t('cinv.col.team'),
    t('cinv.col.ugTeam'), t('cinv.col.owner'), t('cinv.col.tier'), t('cinv.col.keyAlg'), t('cinv.col.keySize'),
    t('cinv.col.keyClass'), t('cinv.col.sigAlg'), t('cinv.col.hash'), t('cinv.col.remnants'), t('cinv.col.pqc'),
    t('cinv.col.category'), t('cinv.col.target'), t('cinv.col.migrateBy'), t('cinv.col.notAfter'), t('cinv.col.days'),
    t('cinv.col.tls'), t('cinv.col.pfs'), t('cinv.col.checkedAt'), t('cinv.col.factors'),
  ]
}

/** Tek satır → hücreler (sayılar sayı olarak kalır: Excel süzgeci/sıralaması çalışsın). */
export function exportRow(r, t, fmt = {}) {
  const fmtDate = fmt.fmtDate || ((v) => v || '')
  const p = r.priority || {}
  const yesNo = (v) => (v == null ? '' : v ? t('cinv.yes') : t('cinv.no'))
  return [
    r.rank ?? '', p.score ?? '', t(`cinv.band.${p.band || 'P4'}`), r.domain || '',
    t(`cinv.src.${r.source || 'NETWORK'}`) + (r.source === 'MANUAL' && r.manual_version ? ` v${r.manual_version}` : ''),
    r.team_name || t('cinv.noTeam'), r.ug_team_name || '', r.owner || '', r.tier ? `T${r.tier}` : t('cinv.tierNone'),
    r.key_algorithm || '', r.key_size ?? '', bucketLabel(r.key_bucket, t), r.signature_algorithm || '', hashLabel(r.sig_hash, t),
    (r.remnants || []).map((x) => t(`cinv.rem.${x}`)).join(', '), t(`cinv.pqc.${r.pqc}`), t(`cinv.cat.${r.category}`),
    t(`cinv.target.${r.category}`), r.migrate_by ? fmtDate(r.migrate_by) : '', r.not_after ? fmtDate(r.not_after) : '',
    r.days_remaining ?? '', r.tls_version || '', yesNo(r.pfs), r.checked_at ? fmtDate(r.checked_at) : '',
    t('cinv.factorsText', p.exposure ?? 0, p.strength ?? 0, p.renewal ?? 0, p.hndl ?? 0),
  ]
}

/** Takım tablosu (dışa aktarım + ekran ortak sütun sırası). */
export function teamExportRows(teams, unowned, t) {
  const head = [t('cinv.col.team'), t('cinv.col.total'), ...CATEGORIES.map((c) => t(`cinv.cat.${c}`)),
    ...BANDS.slice(0, 4).map((b) => t(`cinv.band.${b}`)), t('cinv.col.remnantsCount'), t('cinv.col.topScore'), t('cinv.col.nextMigrate')]
  const row = (x, name) => [name, x.total ?? 0, ...CATEGORIES.map((c) => x.by_category?.[c] ?? 0),
    ...BANDS.slice(0, 4).map((b) => x.by_band?.[b] ?? 0), x.remnants ?? 0, x.top_score ?? 0, x.next_migrate_by || '']
  const body = (teams || []).map((x) => row(x, x.team_name || `#${x.team_id}`))
  if ((unowned?.total ?? 0) > 0) body.push(row(unowned, t('cinv.noTeam')))
  return [head, ...body]
}

/** Özet sayfası satırları ([etiket, değer]) — XLSX "Özet" ve PDF özet tablosu ortak. */
export function summaryRows(data, f, t, fmt = {}, teamName) {
  const fmtDateSec = fmt.fmtDateSec || ((v) => v || '')
  const s = data?.summary || {}
  const rem = s.remnants || {}
  const r = readiness(s)
  return [
    [t('cinv.xl.preparedAt'), fmtDateSec(fmt.now || new Date().toISOString())],
    [t('cinv.xl.generatedAt'), data?.generated_at ? fmtDateSec(data.generated_at) : '—'],
    [t('cinv.xl.dataAsOf'), data?.data_as_of ? fmtDateSec(data.data_as_of) : '—'],
    [t('cinv.xl.oldestCheck'), data?.oldest_check ? fmtDateSec(data.oldest_check) : '—'],
    [t('cinv.xl.scope'), scopeText(data?.scope, t)],
    [t('cinv.xl.filters'), filterSummary(f, t, teamName)],
    [t('cinv.kpi.total'), s.total ?? 0],
    [t('cinv.xl.checked'), s.checked ?? 0],
    [t('cinv.xl.network'), s.network ?? 0],
    [t('cinv.xl.manual'), s.manual ?? 0],
    ...PQC_STATES.map((k) => [t(`cinv.pqc.${k}`), s.by_pqc?.[k] ?? 0]),
    [t('cinv.xl.vulnerablePct'), `${r.vulnerablePct}%`],
    ...CATEGORIES.map((k) => [t(`cinv.cat.${k}`), s.by_category?.[k] ?? 0]),
    ...BANDS.map((k) => [t(`cinv.band.${k}`), s.by_band?.[k] ?? 0]),
    [t('cinv.rem.SHA1_LEAF'), rem.sha1_leaf ?? 0],
    [t('cinv.rem.MD5_LEAF'), rem.md5_leaf ?? 0],
    [t('cinv.rem.SHA1_INTERMEDIATE'), rem.sha1_intermediate ?? 0],
    [t('cinv.rem.MD5_INTERMEDIATE'), rem.md5_intermediate ?? 0],
    [t('cinv.rem.sha1Root'), rem.sha1_root ?? 0],
    [t('cinv.kpi.due', DUE_DAYS), s.vulnerable_expiring_90d ?? 0],
    [t('cinv.xl.kexNote'), t('cinv.kexNote')],
  ]
}

/** Dosya adı: sitemonitor-crypto-inventory-YYYYMMDD-HHmm.<uzantı> */
export function exportFileName(ext, now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `sitemonitor-crypto-inventory-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}.${ext}`
}
