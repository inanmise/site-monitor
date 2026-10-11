/**
 * Takım eksik bilgi modeli (2026-10-11, kullanıcı isteği: "e-posta adresi olmayan takım, takım lideri olmayan takım gibi
 * takımda eksik olanların filtrelenebileceği bir süzgeç mekanizması yok"). Saf — React yok; birim testleri sınar.
 *
 * Her kural takım kaydından (`/admin/teams`) ve satır sayaçlarından (`/admin/teams/stats`) türetilir; arka uç değişmedi.
 * Sayaç gerektiren kural, sayaç henüz gelmediyse "bilinmiyor" sayılır (ne eksik ne tam — sayıma ve süzgece girmez).
 */

/**
 * Kural kataloğu — sıra = ekran sırası. `severity`: high (bildirim / sahiplik kırılır), medium (yönetim eksik), low
 * (bilgi). `stats`: kural satır sayaçlarına ihtiyaç duyar mı.
 */
export const TEAM_GAPS = Object.freeze([
  { key: 'email', severity: 'high', stats: false },
  { key: 'leader', severity: 'high', stats: false },
  { key: 'members', severity: 'high', stats: true },
  { key: 'contacts', severity: 'high', stats: true },
  { key: 'manager', severity: 'medium', stats: false },
  { key: 'inactive_members', severity: 'medium', stats: true },
  { key: 'groups', severity: 'low', stats: true },
  { key: 'description', severity: 'low', stats: false },
  { key: 'assets', severity: 'low', stats: true },
])

export const GAP_KEYS = TEAM_GAPS.map((g) => g.key)
const BY_KEY = Object.fromEntries(TEAM_GAPS.map((g) => [g.key, g]))
const SEVERITY_RANK = { high: 3, medium: 2, low: 1 }

/** "Eksik bilgisi olan takım" kartının kapsadığı kurallar — yüksek ve orta önem (düşük önem bilgi amaçlı). */
export const CORE_GAPS = Object.freeze(TEAM_GAPS.filter((g) => g.severity !== 'low').map((g) => g.key))

export function gapSeverity(key) {
  return BY_KEY[key]?.severity ?? 'low'
}

/** Sayaç değeri (yoksa null — bilinmiyor). */
function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * Takımın eksikleri — katalog sırasıyla anahtarlar.
 *
 * @param team  takım kaydı (snake_case; camelCase savunma amaçlı okunur)
 * @param ctx   `{ stats, hasLeader, hasManager }` — stats: o takımın sayaçları (yoksa null); hasLeader / hasManager:
 *              çağıranın çözümü (lider AKTİF kullanıcı mı; müdür elle atanmış ya da AD zincirinden türetilmiş mi).
 *              null = bilinmiyor (kullanıcı listesi henüz gelmedi) → o kural atlanır, "eksik" sayılmaz.
 */
export function teamGaps(team, { stats = null, hasLeader = null, hasManager = null } = {}) {
  if (!team) return []
  const s = stats || null
  const out = []
  for (const g of TEAM_GAPS) {
    if (g.stats && !s) continue
    if ((g.key === 'leader' && hasLeader == null) || (g.key === 'manager' && hasManager == null)) continue
    let missing = false
    switch (g.key) {
      case 'email': missing = !String(team.email ?? '').trim(); break
      case 'leader': missing = !hasLeader; break
      case 'manager': missing = !hasManager; break
      case 'description': missing = !String(team.description ?? '').trim(); break
      case 'members': {
        const total = num(s.members)
        const inactive = num(s.members_inactive) ?? 0
        missing = total != null && total - inactive <= 0
        break
      }
      case 'inactive_members': missing = (num(s.members_inactive) ?? 0) > 0; break
      case 'contacts': missing = num(s.contacts) === 0; break
      case 'groups': missing = num(s.groups) === 0; break
      case 'assets': missing = num(s.domains) === 0 && num(s.monitors) === 0; break
      default: missing = false
    }
    if (missing) out.push(g.key)
  }
  return out
}

/** En ağır eksik önemi (eksik yoksa null). */
export function worstSeverity(gaps) {
  let best = null
  for (const k of gaps || []) {
    const s = gapSeverity(k)
    if (!best || SEVERITY_RANK[s] > SEVERITY_RANK[best]) best = s
  }
  return best
}

/** Çekirdek (yüksek + orta) eksik var mı? */
export function hasCoreGap(gaps) {
  return (gaps || []).some((k) => CORE_GAPS.includes(k))
}

/** Eksik anahtarı başına takım sayısı. `gapsOf(team)` her takımın eksik listesini döndürür. */
export function gapCounts(teams, gapsOf) {
  const out = Object.fromEntries(GAP_KEYS.map((k) => [k, 0]))
  for (const tm of teams || []) for (const k of gapsOf(tm)) out[k] = (out[k] ?? 0) + 1
  return out
}

/**
 * Eksik süzgeci: seçim boşsa her takım geçer; `any` = seçilenlerden EN AZ biri eksik, `all` = HEPSİ eksik.
 */
export function matchesGaps(gaps, selected, mode = 'any') {
  const sel = selected || []
  if (sel.length === 0) return true
  const set = new Set(gaps || [])
  return mode === 'all' ? sel.every((k) => set.has(k)) : sel.some((k) => set.has(k))
}

/** URL değeri (`g_miss=email,leader`) → geçerli, tekil, katalog sıralı anahtarlar. */
export function parseGapParam(raw) {
  const want = new Set(String(raw || '').split(',').map((x) => x.trim()).filter(Boolean))
  return GAP_KEYS.filter((k) => want.has(k))
}

export function gapParam(selected) {
  const list = parseGapParam((selected || []).join(','))
  return list.length ? list.join(',') : null
}

/** Sıralamalar (URL `g_sort`; varsayılan ad, yazılmaz). */
export const TEAM_SORTS = Object.freeze(['name', 'gaps', 'alerts', 'members'])
export const DEFAULT_TEAM_SORT = 'name'

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/**
 * Sıralama. `ctx`: `{ gapsOf, statsOf }`. Eşitlikte ad (Türkçe, büyük/küçük harf duyarsız). Girdi değişmez.
 * `name` (varsayılan) listeyi YENİDEN SIRALAMAZ: sunucu zaten ada göre döndürür (`UserService.listTeams`, LOWER(name)) —
 * sayfanın bugünkü sırası birebir korunur.
 */
export function sortTeams(teams, sort, { gapsOf = () => [], statsOf = () => null } = {}) {
  const list = [...(teams || [])]
  const byName = (a, b) => collator.compare(String(a?.name ?? ''), String(b?.name ?? ''))
  const weight = (tm) => gapsOf(tm).reduce((sum, k) => sum + SEVERITY_RANK[gapSeverity(k)] * 10, 0) + gapsOf(tm).length
  list.sort((a, b) => {
    switch (sort) {
      case 'gaps': return weight(b) - weight(a) || byName(a, b)
      case 'alerts': return (num(statsOf(b)?.open_alerts) ?? -1) - (num(statsOf(a)?.open_alerts) ?? -1) || byName(a, b)
      case 'members': return (num(statsOf(b)?.members) ?? -1) - (num(statsOf(a)?.members) ?? -1) || byName(a, b)
      default: return 0
    }
  })
  return list
}
