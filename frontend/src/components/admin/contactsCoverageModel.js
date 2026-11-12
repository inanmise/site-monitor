// Kişiler — takım × seviye eskalasyon KAPSAM matrisi (2026-11-12) — saf, test edilebilir yardımcılar.
// Kaynak: /admin/contacts/all (kapsam-filtreli kişiler) + /admin/users (pasif tespiti) + /admin/teams/stats (aktif varlık
// sayısı) — hepsi zaten EscalationContacts'ta yüklü. Yeni uç yok; sunucu kuralıyla hizalı (EscalationContactScope):
// bir kişi seviyeyi KARŞILAR ⇔ eşiği ≤ seviye. WARNING eşiği HER seviyeyi, CRITICAL eşiği YALNIZ kritiği karşılar.
// Dolayısıyla CRITICAL kapsamı = tüm aktif kişiler; 0 ise hiç eskalasyon yok (en sert alarm takım-maili kalır) = boşluk.

export const COVERAGE_LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
export const COVERAGE_TABLE_MIN_PX = 640
const RANK = { WARNING: 0, HIGH: 1, CRITICAL: 2 }

/** Kişi bu seviyeyi KARŞILAR mı: eşik ≤ seviye. Bilinmeyen/boş eşik WARNING (en geniş kapsam) sayılır. */
export function coversLevel(minLevel, level) {
  return (RANK[minLevel] ?? 0) <= (RANK[level] ?? 0)
}

/** Kişi GERÇEKTEN bildirim gönderir mi: aktif VE pasif kullanıcıya bağlı değil. Webhook-yalnız kişi (user_id yok) her
 *  zaman sayılır — pasif KULLANICI süzgeci onu düşürmez (InactiveRecipientGuard yalnız kullanıcı-bağlı adresi düşürür). */
export function contactNotifies(c, inactiveIds) {
  if (!c || c.active === false) return false
  const uid = c.user_id != null && c.user_id !== '' ? String(c.user_id) : null
  if (uid && inactiveIds && typeof inactiveIds.has === 'function' && inactiveIds.has(uid)) return false
  return true
}

/**
 * Takım × seviye kapsamı. Her takım için seviyede BİLDİRİM GÖNDERECEK etkin kişi sayısı + dürüst ton.
 *
 * Ton (yanıltmaz):
 *  - `noAssets` (bilgi, boşluk DEĞİL): takımın aktif izlenen varlığı yok → alarm üretmez, eksik demek değil.
 *  - `gap` (kırmızı): varlık var ama CRITICAL kapsamı 0 → en sert alarmda bile hiç eskalasyon yok (yalnız takım-maili).
 *  - `partial` (amber): CRITICAL ≥ 1 ama HIGH kapsamı 0 → kritik tırmanır, yüksek tırmanmaz (tüm kişiler CRITICAL eşikli).
 *  - `ok` (yeşil): varlık var, HIGH ve CRITICAL kapsanıyor.
 *
 * WARNING sütunu BİLGİ amaçlıdır, boşluk olarak işaretlenmez: standalone UYARI alarmı tasarımca takım-maili (kişi
 * eklenmez); sertifika uyarısı ise kişi kullanır. Sunucu tarafı eşleştirme AdminController + EscalationContactScope.
 */
export function buildCoverage(contacts = [], teams = [], inactiveIds = new Set(), stats = {}) {
  // Varlık sayıları hiç yüklenmediyse (teamStats okunamadı) takımı YANLIŞÇA "varlık yok" göstermeyiz — bilinmeyen ≠ eksik.
  const haveStats = stats && typeof stats === 'object' && Object.keys(stats).length > 0
  const byTeam = new Map()
  for (const c of contacts || []) {
    if (!contactNotifies(c, inactiveIds)) continue
    if (c.team_id == null || c.team_id === '') continue
    const k = String(c.team_id)
    if (!byTeam.has(k)) byTeam.set(k, [])
    byTeam.get(k).push(c)
  }
  const statAt = (id) => stats?.[String(id)] || stats?.[id] || {}
  const rows = (teams || []).map((team) => {
    const list = byTeam.get(String(team.id)) || []
    const counts = { WARNING: 0, HIGH: 0, CRITICAL: 0 }
    for (const c of list) for (const lvl of COVERAGE_LEVELS) if (coversLevel(c.min_alert_level, lvl)) counts[lvl]++
    const s = statAt(team.id)
    const assets = haveStats ? (Number(s.monitors) || 0) + (Number(s.domains) || 0) : null
    let tone
    const gapLevels = []
    if (!haveStats) tone = 'unknown'                                    // varlık bilinmiyor → boşluk/temiz iddia edilmez
    else if (assets <= 0) tone = 'noAssets'
    else if (counts.CRITICAL === 0) { tone = 'gap'; gapLevels.push('CRITICAL'); if (counts.HIGH === 0) gapLevels.push('HIGH') }
    else if (counts.HIGH === 0) { tone = 'partial'; gapLevels.push('HIGH') }
    else tone = 'ok'
    return { teamId: team.id, teamName: team.name || '', assets, counts, tone, gapLevels, total: list.length }
  })
  const summary = { teams: rows.length, withAssets: 0, ok: 0, partial: 0, gap: 0, noAssets: 0, unknown: 0, statsKnown: haveStats }
  for (const r of rows) { summary[r.tone] = (summary[r.tone] || 0) + 1; if (r.assets > 0) summary.withAssets++ }
  return { rows, summary }
}

export const COVERAGE_FILTERS = ['all', 'gap', 'partial', 'ok', 'noAssets', 'unknown']

/** Matris süzgeci (KPI kartından). 'all' → hepsi; aksi → o tonlu satırlar. */
export function filterCoverage(rows = [], filter) {
  return !filter || filter === 'all' ? rows : rows.filter((r) => r.tone === filter)
}

// Sıralama: en kötü önce (boşluk → kısmi → tam → varlıksız), sonra varlık çok → az, sonra ad (Türkçe harmanlama).
const TONE_ORDER = { gap: 0, partial: 1, ok: 2, noAssets: 3, unknown: 4 }
const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
export function sortCoverage(rows = []) {
  return [...rows].sort((a, b) =>
    (TONE_ORDER[a.tone] - TONE_ORDER[b.tone]) || (b.assets - a.assets) || coll.compare(a.teamName, b.teamName))
}

/** Hücre vurgusu: varlık varken 0 kapsam → CRITICAL kırmızı, HIGH amber, WARNING nötr (bilgi). Aksi nötr/yeşil. */
export function cellTone(count, level, assets) {
  if (count > 0) return 'covered'
  if (assets <= 0) return 'muted'
  if (level === 'CRITICAL') return 'danger'
  if (level === 'HIGH') return 'warning'
  return 'muted'   // WARNING boşluğu bilgi
}

/** Boşluğu kapatacak en geniş eşik: CRITICAL dâhil her boşluğu WARNING eşikli tek kişi kapatır. */
export const GAP_FIX_LEVEL = 'WARNING'
