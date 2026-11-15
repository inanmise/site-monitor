/**
 * Alan Adı listesi ek süzgeçleri (2026-11-15, C1) — registrar (veriden, Türkçe katlanmış), veri kaynağı (RDAP / WHOIS /
 * bilinmiyor) ve süre durumu (dolmuş / dolmamış / bilinmiyor). Saf yardımcılar (React yok): sayfa ve testler paylaşır.
 *
 * <p><b>Sayımlar dürüst:</b> her süzgecin seçenek sayısı, sayfanın öteki süzgeçleri ve DİĞER iki ek süzgeç uygulanmış liste
 * üzerinden sayılır (kendi seçimi hariç — klasik "facet" sayımı). Bilinmeyen (registrar yok, kaynak yok, bitiş yok) ayrı bir
 * seçenektir — hiçbir zaman "0 dolmuş" / "temiz" diye sayılmaz.
 *
 * <p>URL anahtarları sayfanın `d` önekiyle (`dq` gibi): `dr` registrar (katlanmış anahtar), `ds` kaynak, `dx` süre durumu.
 */
import { foldSearchText } from '../../utils/monitorFilters.js'
import { liveDays, sourceFamily } from './domainCardModel.js'

export const NO_REGISTRAR = '__none__'
export const SOURCE_FILTERS = Object.freeze(['all', 'rdap', 'whois', 'unknown'])
export const EXPIRY_FILTERS = Object.freeze(['all', 'expired', 'valid', 'unknown'])
/** Sayfanın URL anahtarları (kayıtlı görünümler de bunları saklar — hooks/userPrefsModel VIEW_SPECS.monitor). */
export const DOMAIN_FILTER_KEYS = Object.freeze({ registrar: 'dr', source: 'ds', expiry: 'dx' })
export const DEFAULT_DOMAIN_FILTERS = Object.freeze({ registrar: 'all', source: 'all', expiry: 'all' })

const COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Registrar anahtarı: Türkçe katlanmış (büyük/küçük harf, İ/ı, aksan) — "GoDaddy.com, LLC" = "GODADDY.COM, LLC". */
export function registrarKey(m) {
  const r = String(m?.registrar ?? '').trim()
  return r ? foldSearchText(r).replace(/\s+/g, ' ') : NO_REGISTRAR
}

/** Kaynak türü: rdap (RDAP / RDAP_REGISTRY / RDAP_ORG) | whois | unknown (kaynak yok / NONE). */
export function sourceKind(m) {
  return sourceFamily(m?.source) || 'unknown'
}

/** Süre durumu CANLI kalan günden (A1): expired (< 0) | valid (≥ 0) | unknown (bitiş bilinmiyor). */
export function expiryKind(m, now = Date.now()) {
  const d = liveDays(m, now)
  if (d == null) return 'unknown'
  return d < 0 ? 'expired' : 'valid'
}

/** URL / kayıtlı görünüm değerlerini doğrular — bilinmeyen değer varsayılana döner. */
export function normalizeDomainFilters(raw = {}) {
  const registrar = typeof raw.registrar === 'string' && raw.registrar.trim() ? raw.registrar : 'all'
  const source = SOURCE_FILTERS.includes(raw.source) ? raw.source : 'all'
  const expiry = EXPIRY_FILTERS.includes(raw.expiry) ? raw.expiry : 'all'
  return { registrar, source, expiry }
}

/** Satır seçili ek süzgeçlerden geçiyor mu; `skip` = sayım için atlanacak süzgeç adı. */
export function matchesDomainFilters(m, filters, skip = null, now = Date.now()) {
  const f = filters || DEFAULT_DOMAIN_FILTERS
  if (skip !== 'registrar' && f.registrar !== 'all' && registrarKey(m) !== f.registrar) return false
  if (skip !== 'source' && f.source !== 'all' && sourceKind(m) !== f.source) return false
  if (skip !== 'expiry' && f.expiry !== 'all' && expiryKind(m, now) !== f.expiry) return false
  return true
}

/** En az bir ek süzgeç seçili mi. */
export function hasDomainFilters(filters) {
  const f = filters || DEFAULT_DOMAIN_FILTERS
  return f.registrar !== 'all' || f.source !== 'all' || f.expiry !== 'all'
}

/**
 * Seçenek sayımları — `list` sayfanın öteki süzgeçleri uygulanmış liste. Döner:
 * `{ registrars: [{ key, label, count }] (A→Z, bilinmeyen sonda), source: { all, rdap, whois, unknown }, expiry: { … } }`.
 * Seçili registrar listede artık yoksa 0 sayımla yine döner (seçim görünmez kalmasın).
 */
export function domainFacets(list, filters, now = Date.now()) {
  const f = filters || DEFAULT_DOMAIN_FILTERS
  const reg = new Map()
  let regAll = 0
  const source = { all: 0, rdap: 0, whois: 0, unknown: 0 }
  const expiry = { all: 0, expired: 0, valid: 0, unknown: 0 }
  for (const m of Array.isArray(list) ? list : []) {
    if (matchesDomainFilters(m, f, 'registrar', now)) {
      regAll++
      const k = registrarKey(m)
      const cur = reg.get(k)
      if (cur) cur.count++
      else reg.set(k, { key: k, label: k === NO_REGISTRAR ? null : String(m.registrar).trim(), count: 1 })
    }
    if (matchesDomainFilters(m, f, 'source', now)) { source.all++; source[sourceKind(m)]++ }
    if (matchesDomainFilters(m, f, 'expiry', now)) { expiry.all++; expiry[expiryKind(m, now)]++ }
  }
  if (f.registrar !== 'all' && !reg.has(f.registrar)) {
    reg.set(f.registrar, { key: f.registrar, label: f.registrar === NO_REGISTRAR ? null : f.registrar, count: 0 })
  }
  const registrars = [...reg.values()].sort((a, b) => {
    if (a.key === NO_REGISTRAR) return 1
    if (b.key === NO_REGISTRAR) return -1
    return COLLATOR.compare(a.label || '', b.label || '')
  })
  return { registrars, registrarAll: regAll, source, expiry }
}
