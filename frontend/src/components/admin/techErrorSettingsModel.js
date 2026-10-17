import { matchesSearchText } from '../../utils/monitorFilters.js'
import { channelPeriodStatus } from '../techerror/channelListModel.js'

/**
 * Ayarlar → Platform → Teknik hata raporu — kanal özetinin SAF modeli (2026-10-17, yeniden tasarım: kullanıcı isteği
 * "shadcn ile yeniden tasarla, mweb duyarlı, mevcut işlevler korunsun"). Sunucu sözleşmesi değişmedi
 * (`GET /api/admin/tech-error-reports/settings` → `channels[]`: id, name, active, team_id, team_name, team_visible,
 * executive_count, approver_count, team_email_set, period_report).
 *
 * Tek süzgeç anahtarı (`filter`): dönem durumu (sent / open / missing / inactive — `channelPeriodStatus` ile kanal
 * listesi sayfasıyla AYNI kural) ya da yapılandırma bayrağı (exec / noEmail / hidden). KPI kartları ve araç çubuğundaki
 * seçim kutusu aynı durumu sürer; kartlar birbirini dışlar ("Kanal" kartı süzgeci kaldırır).
 *
 * Bilinmeyen ≠ eksik: `team_email_set` ve `team_visible` yalnız AÇIKÇA `false` ise eksik / kapalı sayılır; alan yoksa
 * (eski sunucu, kısmi veri) sayılmaz ve arayüz "bilinmiyor" yazar — hiçbir sayım bilinmeyenden "0 — her şey yolunda"
 * üretmez.
 */
export const TES_FILTERS = Object.freeze(['all', 'sent', 'open', 'missing', 'inactive', 'exec', 'noEmail', 'hidden'])
/** Dönem durumu süzgeçleri (seçim kutusunun ilk grubu). */
export const TES_PERIOD_FILTERS = Object.freeze(['sent', 'open', 'missing', 'inactive'])
/** Yapılandırma bayrağı süzgeçleri (ikinci grup). */
export const TES_FLAG_FILTERS = Object.freeze(['exec', 'noEmail', 'hidden'])
export const TES_SORTS = Object.freeze(['name', 'team', 'status'])
/**
 * Kanal özeti bu kap genişliğinden itibaren tablo, altında kart. Eşik kanal listesi sayfasından (900) ÇOK düşük, çünkü
 * Ayarlar içeriği dar (Playwright ölçümü 2026-10-17, uygulama kenar çubuğu açık): liste kabı 768 px tablette 351 px (kenar
 * çubuğu 768'den itibaren sabit sütun), 1280 px dizüstünde 575 px (dikey kaydırma çubuğuyla ~560), 1440'ta 735 px. Tablo
 * dört DAR sütunla (kanal · takım durumu · onay akışı · dönem raporu; rozetler sarar) 540 px'te okunur kalır; tablet ve
 * telefon kart alır. Kenar çubuğu daraltılınca aynı sayfa daha geniş kapta tabloya geçer — karar görünüm alanına değil KABA göre.
 */
export const TES_TABLE_MIN_PX = 540

const STATUS_RANK = { missing: 0, open: 1, sent: 2, inactive: 3 }
const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

export { channelPeriodStatus }

export function normalizeFilter(v) {
  return TES_FILTERS.includes(v) ? v : 'all'
}

export function normalizeSort(v) {
  return TES_SORTS.includes(v) ? v : 'name'
}

/** Yönetici alıcısı tanımlı mı (onay akışı var). */
export function hasExecutives(ch) {
  return (Number(ch?.executive_count) || 0) > 0
}

/** Takım e-postası: `true` tanımlı, `false` eksik, `null` bilinmiyor (alan yok). */
export function teamEmailState(ch) {
  if (ch?.team_email_set === true) return true
  if (ch?.team_email_set === false) return false
  return null
}

/** Modül bu takıma kapalı mı: yalnız AÇIKÇA `false`. */
export function isTeamHidden(ch) {
  return ch?.team_visible === false
}

/** Modül bu takıma görünür mü: `true` görünür, `false` kapalı, `null` bilinmiyor. */
export function teamVisibleState(ch) {
  if (ch?.team_visible === true) return true
  if (ch?.team_visible === false) return false
  return null
}

/** Kanal verilen süzgeçle eşleşir mi. */
export function matchesFilter(ch, filter) {
  const f = normalizeFilter(filter)
  if (f === 'all') return true
  if (f === 'exec') return hasExecutives(ch)
  if (f === 'noEmail') return teamEmailState(ch) === false
  if (f === 'hidden') return isTeamHidden(ch)
  return channelPeriodStatus(ch) === f
}

/**
 * Sayımlar: `total` listedeki her kanal; `sent` / `open` / `missing` yalnız AKTİF kanallar (pasif kanalın dönem raporu
 * beklenmez); `inactive` pasifler; `exec` / `noEmail` / `hidden` tüm kanallar üzerinden.
 */
export function countChannels(channels) {
  const out = { total: 0, sent: 0, open: 0, missing: 0, inactive: 0, exec: 0, noEmail: 0, hidden: 0 }
  for (const c of channels || []) {
    out.total++
    out[channelPeriodStatus(c)]++
    if (hasExecutives(c)) out.exec++
    if (teamEmailState(c) === false) out.noEmail++
    if (isTeamHidden(c)) out.hidden++
  }
  return out
}

/** Arama (kanal adı + takım adı, Türkçe katlama) ve takım süzgeci — KPI sayımları bunun ÜZERİNDEN alınır. */
export function searchChannels(channels, { q = '', team = 'all' } = {}) {
  const teamId = team == null || team === 'all' || team === '' ? null : String(team)
  return (channels || []).filter((c) => {
    if (teamId != null && String(c?.team_id) !== teamId) return false
    return matchesSearchText(q, [c?.name, c?.team_name])
  })
}

/** Tam süzgeç: arama + takım + durum / bayrak. */
export function filterChannels(channels, { q = '', team = 'all', filter = 'all' } = {}) {
  return searchChannels(channels, { q, team }).filter((c) => matchesFilter(c, filter))
}

function byName(a, b) {
  return collator.compare(a?.name || '', b?.name || '') || (Number(a?.id) || 0) - (Number(b?.id) || 0)
}

/**
 * Sıralama (kararlı, girdi değişmez): `name` aktifler önce, ad A→Z; `team` takım adı A→Z, aynı takımda aktifler önce,
 * ad A→Z; `status` ilgi isteyen önce (eksik → açık → gönderildi → pasif), aynı durumda takım e-postası eksik olan önce,
 * sonra ad.
 */
export function sortChannels(list, sort = 'name') {
  const s = normalizeSort(sort)
  const out = [...(list || [])]
  const activeFirst = (a, b) => (a?.active === b?.active ? 0 : a?.active ? -1 : 1)
  if (s === 'status') {
    out.sort((a, b) => STATUS_RANK[channelPeriodStatus(a)] - STATUS_RANK[channelPeriodStatus(b)]
      || Number(teamEmailState(b) === false) - Number(teamEmailState(a) === false) || byName(a, b))
  } else if (s === 'team') {
    out.sort((a, b) => collator.compare(a?.team_name || '', b?.team_name || '') || activeFirst(a, b) || byName(a, b))
  } else {
    out.sort((a, b) => activeFirst(a, b) || byName(a, b))
  }
  return out
}

/** Kanalların takımları (tekil, ada göre Türkçe sıralı) — takım seçim kutusu için. */
export function teamsOf(channels) {
  const map = new Map()
  for (const c of channels || []) {
    const id = c?.team_id
    if (id == null) continue
    const key = String(id)
    if (!map.has(key)) map.set(key, { id: key, name: c?.team_name || `#${key}` })
  }
  return [...map.values()].sort((a, b) => collator.compare(a.name, b.name))
}

/** Herhangi bir süzgeç etkin mi (temizle düğmesi). */
export function filtersActive({ q = '', team = 'all', filter = 'all' } = {}) {
  return Boolean(String(q).trim()) || (team != null && team !== 'all' && team !== '') || normalizeFilter(filter) !== 'all'
}
