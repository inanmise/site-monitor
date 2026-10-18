import { matchesSearchText } from '../../utils/monitorFilters.js'
import { channelPeriodStatus } from '../techerror/channelListModel.js'
import { lastAttemptMeta, statusMeta } from '../techerror/techErrorModel.js'

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
// 1280'de Ayarlar içerik sütunu ~560–575 px (kenar çubuğu açık); 520 eşiği kaydırma çubuğu / yazı tipi farkına pay bırakır
export const TES_TABLE_MIN_PX = 520

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

/**
 * DIŞA AKTARMA (2026-10-17, kullanıcı onayı E22): kanal özetinin CSV / Excel karşılığı — SÜZÜLMÜŞ + SIRALANMIŞ listenin TÜMÜ
 * (yalnız görünen sayfa değil). Değerler ekranın söylediği gibi; bilinmeyen alan "Bilinmiyor" yazar — boş hücre "sorun yok"
 * diye okunmasın. Sayılar (yönetici alıcı / PO onaylayıcı) Excel'de sayı hücresi.
 */
export const TES_EXPORT_COLUMNS = Object.freeze(['channel', 'team', 'active', 'visible', 'email', 'exec', 'approvers', 'report', 'approval'])
const APPROVAL_STATES = new Set(['none', 'pending', 'approved', 'rejected', 'stale'])

export function exportHeader(t) {
  return TES_EXPORT_COLUMNS.map((c) => t(`te.set.export.col.${c}`))
}

/** Sayım: sonlu, negatif olmayan sayı → sayı; alan yok / okunamaz → null (bilinmiyor ≠ 0 — inceleme 2026-10-18). */
export function countOrNull(v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/**
 * Takım rehberinde olmayan SEÇİLİ kimliğin etiketi (inceleme 2026-10-18): rehber okunduysa takım silinmiştir; okunamadıysa
 * adı bilinmiyor — asla çıplak "#12".
 */
export function missingTeamText(id, directoryLoaded, t, ordinal = 1, count = 1) {
  // Birden fazla eksik takım aynı etiketle görünmesin (inceleme 2026-10-18): "Silinmiş takım 1", "Silinmiş takım 2" — sıra
  // kimliğe göre artan (`teamOptions`), kimlik gösterilmez
  if (count > 1) return t(directoryLoaded ? 'te.set.deletedTeamN' : 'te.set.unknownTeamN', ordinal)
  return directoryLoaded ? t('te.set.deletedTeam') : t('te.set.unknownTeam')
}

/**
 * Kayıtlı görünürlük listesi YALNIZ silinmiş takımlardan mı oluşuyor (inceleme 2026-10-18): sunucu böyle bir listeyi "herkes"e
 * çevirmez (kısıt gevşemez) — modülü şu an yalnız kurum geneli görüntüleyiciler görür; sayfa bunu söyler. Rehber okunmadıysa
 * (boş) bilinmez → false (bilinmeyen ≠ silinmiş).
 */
export function onlyDeletedTeams(ids, directory) {
  const list = Array.isArray(ids) ? ids : []
  if (list.length === 0 || !Array.isArray(directory) || directory.length === 0) return false
  const known = new Set(directory.map((tm) => Number(tm?.id)).filter(Number.isFinite))
  return list.every((id) => !known.has(Number(id)))
}

/** Sayım alanı: sonlu sayı → sayı; alan yok / okunamaz → "Bilinmiyor". */
function countCell(v, unknown) {
  if (v == null || v === '') return unknown
  const n = Number(v)
  return Number.isFinite(n) ? n : unknown
}

function yesNo(v, t, unknown) {
  if (v === true) return t('te.yes')
  if (v === false) return t('te.no')
  return unknown
}

/** Dönem raporunun durumu — `StatusBadge` ile aynı metin (+ son denemenin sonucu); rapor yoksa "Dönem raporu yok". */
export function exportReportStatus(ch, t) {
  const pr = ch?.period_report
  if (!pr) return t('te.set.noReport')
  const meta = pr.status ? statusMeta(pr.status) : { key: 'te.status.notStarted' }
  const attempt = lastAttemptMeta(pr.last_send_status)
  return attempt ? `${t(meta.key)} · ${t(attempt.key)}` : t(meta.key)
}

/**
 * Dönem raporunun onay durumu — ekrandaki rozetle aynı (inceleme 2026-10-17):
 *  - rapor yok → "—" (uygulanmaz);
 *  - süren / sonuçlanmış bir onay (`pending` / `approved` / `rejected` / `stale`) → onun metni (ekran rozeti de gösterir);
 *  - onay AKIŞI YOK (yönetici alıcısı 0 — sunucu o zaman da `state:"none"` yollar, ekran rozet çizmez) → "—", "Onaya
 *    gönderilmedi" DEĞİL;
 *  - alıcı sayısı bilinmiyor (alan yok / okunamaz) ya da durum okunamaz → "Bilinmiyor";
 *  - akış var, henüz gönderilmedi → "Onaya gönderilmedi".
 */
export function exportApprovalStatus(ch, t) {
  const pr = ch?.period_report
  if (!pr) return '—'
  const st = pr.approval?.state
  if (APPROVAL_STATES.has(st) && st !== 'none') return t(`te.appr.state.${st}`)
  const raw = ch?.executive_count
  const count = raw == null || raw === '' ? NaN : Number(raw)
  if (count === 0) return '—'
  if (!Number.isFinite(count)) return t('te.set.export.unknown')
  return st === 'none' ? t('te.appr.state.none') : t('te.set.export.unknown')
}

/** Bir kanalın dışa aktarma satırı (sütun sırası {@link TES_EXPORT_COLUMNS}). */
export function exportRow(ch, t) {
  const unknown = t('te.set.export.unknown')
  const email = teamEmailState(ch)
  return [
    ch?.name || '—',
    ch?.team_name || (ch?.team_id != null ? `#${ch.team_id}` : '—'),
    yesNo(typeof ch?.active === 'boolean' ? ch.active : null, t, unknown),
    yesNo(teamVisibleState(ch), t, unknown),
    email === true ? t('te.set.emailSet') : email === false ? t('te.set.emailMissing') : unknown,
    countCell(ch?.executive_count, unknown),
    countCell(ch?.approver_count, unknown),
    exportReportStatus(ch, t),
    exportApprovalStatus(ch, t),
  ]
}

/** Başlık + satırlar (verilen sırayla — çağıran süzülmüş ve sıralanmış TAM listeyi verir). */
export function exportTable(channels, t) {
  return { header: exportHeader(t), rows: (channels || []).map((c) => exportRow(c, t)) }
}

/** Dosya adı: `teknik-hata-raporu-kanallar-<yyyy-mm>.<uzantı>` (dönem yoksa bugünün tarihi). */
export function exportFileName(period, ext, now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0')
  const stamp = period?.year && period?.month
    ? `${period.year}-${pad(period.month)}`
    : `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  return `teknik-hata-raporu-kanallar-${stamp}.${ext}`
}
