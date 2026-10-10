/**
 * Takım veri kalitesi sayfasının SAF modeli (2026-10-10) — React yok; birim testte doğrudan sınanır.
 *
 * Sunucu sözleşmesi: `GET /api/data-quality` → `{ org, teams[], unassigned?, notes[], catalog[], config }`,
 * `GET /api/data-quality/teams/{id|unassigned}` → `{ team, rules[{ code, items[] }], notes[] }`. Puan formülü ve kural
 * kataloğu backend'de (`DataQualityScore`, `DataQualityRule`); burada yalnız gösterim, süzme, sıralama ve DERİN
 * BAĞLANTI kuralı var.
 */
import { DATA_QUALITY_BANDS, DATA_QUALITY_RULES } from './dataQualityCodes.js'
import { openTargetOf } from '../noc/nocModel.js'
import { inventoryDeepLinkParams } from '../inventory/inventoryDetailModel.js'
import { DEEP_OPEN } from '../../utils/monitorDeepLink.js'

/** Sayfanın URL anahtarları (`dq_` öneki useUrlQuerySync.PAGE_STATE_PREFIXES'te: sekme değişince temizlenir). */
export const DQ_PARAMS = Object.freeze({ q: 'dq_q', band: 'dq_band', sort: 'dq_sort', team: 'dq_team' })

/** Takım sıralamaları — varsayılan: en düşük puan üstte (aksiyon önce). */
export const SORTS = ['score_asc', 'score_desc', 'name', 'findings']
export const DEFAULT_SORT = 'score_asc'

/** Bant → ton (Badge / StatusBlock tonları) ve halka rengi (yalnız tanımlı CSS jetonları — cssTokens kapısı). */
const BAND_TONE = { EXCELLENT: 'success', GOOD: 'info', NEEDS_ATTENTION: 'warning', POOR: 'danger', NO_DATA: 'neutral' }
const BAND_COLOR = {
  EXCELLENT: 'var(--success)', GOOD: 'var(--primary)', NEEDS_ATTENTION: 'var(--warning)', POOR: 'var(--destructive)',
  NO_DATA: 'var(--muted-foreground)',
}

export function bandOf(band) {
  return DATA_QUALITY_BANDS.includes(band) ? band : 'NO_DATA'
}
export function bandTone(band) { return BAND_TONE[bandOf(band)] }
export function bandColor(band) { return BAND_COLOR[bandOf(band)] }

/** Badge varyantı (proje varyantları: success yok → outline + metin tonu çağıranda). */
export function bandBadgeVariant(band) {
  switch (bandOf(band)) {
    case 'POOR': return 'destructive'
    case 'NEEDS_ATTENTION': return 'warning'
    case 'NO_DATA': return 'outline'
    default: return 'secondary'
  }
}

/** Puan sayısı mı (0..100 tam sayı)? */
export function hasScore(v) {
  return typeof v === 'number' && Number.isFinite(v)
}

/** "+4" / "−3" / "0" — 7 günlük fark; yoksa null. Eksi işareti tipografik (U+2212). */
export function formatDelta(d) {
  if (!hasScore(d)) return null
  if (d > 0) return `+${d}`
  if (d < 0) return `−${Math.abs(d)}`
  return '0'
}

/** Fark tonu: artış iyi, düşüş kötü. */
export function deltaTone(d) {
  if (!hasScore(d) || d === 0) return 'neutral'
  return d > 0 ? 'up' : 'down'
}

/** Yanıt güvenli mi (varsayılan `data: []` mock'u ya da bozuk gövde boş sayılır). */
export function normalizeSummary(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return null
  const teams = Array.isArray(data.teams) ? data.teams.filter((x) => x && x.id != null) : []
  return {
    generatedAt: data.generated_at ?? null,
    org: data.org && typeof data.org === 'object' ? data.org : null,
    teams,
    unassigned: data.unassigned && typeof data.unassigned === 'object' ? data.unassigned : null,
    notes: Array.isArray(data.notes) ? data.notes : [],
    config: data.config && typeof data.config === 'object' ? data.config : {},
  }
}

/** Bant sayaçları (süzgeç kartları) — görünür takımlar üzerinden. */
export function bandCounts(teams) {
  const out = Object.fromEntries(DATA_QUALITY_BANDS.map((b) => [b, 0]))
  for (const t of teams || []) out[bandOf(t.band)]++
  return out
}

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Arama (takım adı, Türkçe büyük/küçük harf duyarsız) + bant süzgeci. */
export function filterTeams(teams, { q = '', band = '' } = {}) {
  const needle = String(q || '').trim().toLocaleLowerCase('tr')
  return (teams || []).filter((t) => {
    if (band && bandOf(t.band) !== band) return false
    if (needle && !String(t.name || '').toLocaleLowerCase('tr').includes(needle)) return false
    return true
  })
}

/** Sıralama — puanı olmayan (NO_DATA) her sıralamada SONDA; eşitlikte ad. */
export function sortTeams(teams, sort = DEFAULT_SORT) {
  const list = [...(teams || [])]
  const byName = (a, b) => collator.compare(String(a.name || ''), String(b.name || ''))
  const noScoreLast = (a, b) => (hasScore(a.score) ? 0 : 1) - (hasScore(b.score) ? 0 : 1)
  list.sort((a, b) => {
    const ns = noScoreLast(a, b)
    if (ns !== 0 && sort !== 'name') return ns
    switch (sort) {
      case 'name': return byName(a, b)
      case 'score_desc': return (b.score ?? -1) - (a.score ?? -1) || byName(a, b)
      case 'findings': return (b.findings ?? 0) - (a.findings ?? 0) || byName(a, b)
      default: return (a.score ?? 101) - (b.score ?? 101) || byName(a, b)
    }
  })
  return list
}

/** Kural grubu: yalnız kusuru olanlar, katalog sırasıyla (sunucu sırası da odur; savunmacı). */
export function failingRules(rules) {
  const order = (code) => {
    const i = DATA_QUALITY_RULES.indexOf(code)
    return i < 0 ? DATA_QUALITY_RULES.length : i
  }
  return (rules || []).filter((r) => (r?.failing ?? 0) > 0).sort((a, b) => order(a.code) - order(b.code))
}

/**
 * Düzeltme listesi: önem süzgeci + metin araması (kalem adı / hedef) — kalemi kalmayan kural düşer. Sıra ETKİYE göre:
 * en çok puan kaybettiren kural üstte (önce onu düzeltmek puanı en çok artırır), eşitlikte katalog sırası.
 */
export function filterRules(rules, { severity = '', q = '' } = {}) {
  const needle = String(q || '').trim().toLocaleLowerCase('tr')
  const out = []
  const byImpact = failingRules(rules).map((r, i) => ({ r, i }))
    .sort((a, b) => (Number(b.r.points) || 0) - (Number(a.r.points) || 0) || a.i - b.i)
    .map((x) => x.r)
  for (const r of byImpact) {
    if (severity && r.severity !== severity) continue
    const items = (r.items || []).filter((it) => !needle
      || String(it.name || '').toLocaleLowerCase('tr').includes(needle)
      || String(it.target || '').toLocaleLowerCase('tr').includes(needle))
    if (needle && items.length === 0) continue
    out.push({ ...r, items })
  }
  return out
}

/** Sağlık oranı → yüzde (0..100, tam sayı). */
export function healthPercent(rule) {
  const e = Number(rule?.eligible ?? 0)
  const f = Number(rule?.failing ?? 0)
  if (!(e > 0)) return 100
  return Math.round((100 * (e - f)) / e)
}

/** İzleme türü → kenar çubuğu etiket anahtarı (kalem satırındaki tür rozeti). */
export const TYPE_LABEL_KEY = {
  SSL: 'dq.type.SSL', HTTP: 'nav.http', PING: 'nav.ping', PORT: 'nav.port', DNS: 'nav.dns', DOMAIN: 'nav.domainmon',
  KEYWORD: 'nav.keyword', PAGE: 'nav.page', PAGESPEED: 'nav.pagespeed', SCRIPTED: 'nav.scripted', TEAM: 'dq.type.TEAM',
}

/** Kalemin envanter kaydı için derin bağlantısı: Envanter çekmecesi (başkasının kaydında "tüm takımlar" kapsamı). */
function inventoryLink(item) {
  return { tab: 'domains', params: inventoryDeepLinkParams({ domain: item.name, can_manage: item.can_edit !== false }) }
}

/** Takım ayarı bağlantıları — Yönetim Paneli alt sekmeleri (`g_tab` + süzgeç). */
function teamLink(rule, item) {
  switch (rule) {
    case 'TEAM_NO_ESCALATION':
      return { tab: 'admin', params: { g_tab: 'contacts', g_team: String(item.id) } }
    default:
      return { tab: 'admin', params: { g_tab: 'teams', g_q: String(item.name || '') } }
  }
}

/**
 * Düzeltme kaleminin AÇILACAĞI YER — `{ tab, params }` (App `navigateTo` ile gider) ya da `null`.
 *  - envanter kaydı: Envanter çekmecesi (tier / sorumlu / takım / duraklatma / hiç kontrol edilmemiş / bayat — çekmecede
 *    Düzenle ve Şimdi kontrol et var); tekrarlayan hata: Pano'daki sertifika penceresi (sonuç + tanılama);
 *    7/24'e bildirilmeyen kritik kayıt: sertifika penceresinin 7/24 alanı (`open=noc`, 7/24 Kapsamı ile aynı hedef);
 *  - izleme: türün sayfasında detay penceresi (`?tab=<tür>&monitor=<id>`, 7/24 Kapsamı'nın `openTargetOf`'u);
 *  - takım: Yönetim → Takımlar (ad süzgeçli) ya da Eskalasyon Kişileri (takım süzgeçli).
 */
export function linkFor(rule, item) {
  if (!item) return null
  if (item.kind === 'team') return item.id == null ? null : teamLink(rule, item)
  if (item.kind === 'inventory') {
    if (!item.name) return null
    if (rule === 'NOC_CRITICAL_UNCOVERED') return openTargetOf({ type: 'SSL', name: item.name }, { action: DEEP_OPEN.NOC })
    if (rule === 'INV_CHECK_FAILING') return openTargetOf({ type: 'SSL', name: item.name })
    return inventoryLink(item)
  }
  if (item.kind === 'monitor') return openTargetOf({ type: item.type, id: item.id })
  return null
}

/** 7/24 kurum notunun düzeltme yeri (yalnız global yönetici değiştirir). */
export const NOC_SETTINGS_LINK = Object.freeze({ tab: 'settings', params: { sec: 'noc' } })

/**
 * Kalemin kısa "neden" metni için i18n anahtarı + argümanlar (dil bağımsız `facts` → arayüz dili). Yoksa null.
 * @returns {{ key: string, args: any[] } | null}
 */
export function factText(rule, facts = {}) {
  const f = facts || {}
  switch (rule) {
    case 'INV_NO_TEAM':
    case 'MON_NO_TEAM':
      return f.reason ? { key: `dq.owner.${f.reason}`, args: [] } : null
    case 'INV_TIER_SUSPECT':
      return f.reason ? { key: `dq.tier.${f.reason}`, args: [f.tier ?? '', f.token ?? ''] } : null
    case 'INV_CHECK_FAILING':
      return hasScore(f.errors_7d) ? { key: 'dq.fact.errors7d', args: [f.errors_7d] } : null
    case 'NOC_CRITICAL_UNCOVERED':
      return f.tier != null ? { key: 'dq.fact.tier', args: [f.tier] } : null
    case 'MON_PAUSED_LONG':
      return hasScore(f.days) ? { key: f.exact ? 'dq.fact.pausedExact' : 'dq.fact.pausedApprox', args: [f.days] } : null
    case 'MON_DUPLICATE':
      return f.duplicate_of_name ? { key: 'dq.fact.duplicateOf', args: [f.duplicate_of_name] } : null
    case 'TEAM_NO_MANAGER':
      if (!f.leader && !f.manager) return null
      return { key: f.leader === 'INACTIVE' || f.manager === 'INACTIVE' ? 'dq.fact.lead.INACTIVE' : 'dq.fact.lead.NONE', args: [] }
    case 'TEAM_NO_ESCALATION':
      return Array.isArray(f.missing) && f.missing.length
        ? { key: f.missing.length > 1 ? 'dq.fact.escalationBoth' : 'dq.fact.escalationHigh', args: [] } : null
    default:
      return null
  }
}

/** Düzeltme listesi CSV satırları (başlık + satırlar) — çağıran `t` ile kural adını çevirir. */
export function csvRowsOf(rules, t) {
  const rows = [[t('dq.csv.rule'), t('dq.csv.severity'), t('dq.csv.type'), t('dq.csv.name'), t('dq.csv.target'), t('dq.csv.detail')]]
  for (const r of failingRules(rules)) {
    for (const it of r.items || []) {
      const ft = factText(r.code, it.facts)
      rows.push([
        t(`dq.rule.${r.code}.title`), t(`dq.severity.${r.severity}`), t(TYPE_LABEL_KEY[it.type] || 'dq.type.TEAM'),
        it.name ?? '', it.target ?? '', ft ? t(ft.key, ...ft.args) : '',
      ])
    }
  }
  return rows
}

/** Eğilim noktaları → grafik satırları (geçersiz noktalar düşer — grafik bozuk veride çökmez). */
export function trendRows(trend) {
  return (Array.isArray(trend) ? trend : [])
    .filter((p) => p && typeof p.day === 'string' && hasScore(p.score))
    .map((p) => ({ day: p.day, label: `${p.day.slice(8, 10)}.${p.day.slice(5, 7)}`, score: p.score }))
}
