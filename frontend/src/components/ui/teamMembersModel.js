import { resolveTeamManagerEntry } from '../../utils/teamManager.js'

/**
 * Takım üyeleri penceresinin SAF modeli (2026-09-28 yeniden tasarım) — ağ yok, DOM yok.
 *
 * <p>Veri sözleşmesi: kurum-geneli `/api/teams/{id}/members` BEYAZ-LİSTELİ projeksiyon döner (ad, unvan, birim,
 * müdürlük, org rolü, kademe, e-posta, müdür adı). Telefon, sicil, sistem rolü ve fotoğraf DÖNMEZ ve bu pencere
 * onları yönetim yükleyicisi (tam entity) verse bile ÇİZMEZ — aşağıdaki arama metni de yalnız beyaz-liste alanlarını
 * okur. Kapı: `TeamMembersModal.test.jsx` "gizlilik".
 *
 * <p>Takım Müdürü TEK kişidir ve üye listesine ASLA eklenmez (prod hatası 2026-09-26): {@link resolveModalManager}
 * yalnız başlık çipi için kişiyi bulur; müdür gerçekten üyeyse listede zaten vardır (rozetle işaretlenir).
 */

/** Varsayılan sıralama: MANAGER → PO → diğer; sonra kademe (büyükten küçüğe, sayısal); sonra ad (tr). */
export function sortMembers(members) {
  const rankOf = (m) => (m.org_role === 'MANAGER' ? 0 : (m.org_role === 'PO' ? 1 : 2))
  return [...members].sort((a, b) => {
    if (rankOf(a) !== rankOf(b)) return rankOf(a) - rankOf(b)
    const la = (a.company_level || '').toLowerCase()
    const lb = (b.company_level || '').toLowerCase()
    if (la !== lb) return lb.localeCompare(la, 'tr', { numeric: true })
    return nameOf(a).localeCompare(nameOf(b), 'tr')
  })
}

export const MEMBER_SORTS = Object.freeze(['role', 'name', 'title'])

export const nameOf = (m) => String(m?.display_name || m?.username || '')

/** Sıralama: `role` (varsayılan, {@link sortMembers}), `name` (A–Z), `title` (unvan A–Z, unvansızlar sonda; eşitlikte ad). */
export function sortMembersBy(members, sort) {
  if (sort === 'name') return [...members].sort((a, b) => nameOf(a).localeCompare(nameOf(b), 'tr'))
  if (sort === 'title') {
    return [...members].sort((a, b) => {
      const ta = String(a.title || '').trim()
      const tb = String(b.title || '').trim()
      if (!ta !== !tb) return ta ? -1 : 1
      return ta.localeCompare(tb, 'tr') || nameOf(a).localeCompare(nameOf(b), 'tr')
    })
  }
  return sortMembers(members)
}

/**
 * Aramada harf/aksan duyarsız karşılaştırma: Türkçe küçültme + birleşik işaretleri at + ı→i
 * ("ozgur" → "Özgür", "IŞIK" → "ışık"). Boşluklar tekilleşir.
 */
export function foldText(s) {
  return String(s ?? '')
    .toLocaleLowerCase('tr-TR')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/ı/g, 'i')
    .replace(/\s+/g, ' ')
    .trim()
}

/** Aranan metin — YALNIZ beyaz-liste alanları (ad, kullanıcı adı, unvan, birim, müdürlük, e-posta). */
export function memberSearchText(m) {
  return foldText([m.display_name, m.first_name, m.last_name, m.username, m.title, m.department, m.mudurluk_name, m.email]
    .filter(Boolean).join(' '))
}

/** Ek üyelik mi: birincil takımı BAŞKA (yalnız `team_id` taşıyan yönetim verisinde bilinir). */
export function isSecondaryMember(m, teamId) {
  if (!m || teamId == null || m.team_id == null || m.team_id === '') return false
  return String(m.team_id) !== String(teamId)
}

/** Rol süzgeci sırası: yönetim kademesi önce, uzman sonra, rolü olmayan en sonda. */
const ROLE_ORDER = ['MANAGER', 'BOLUM_BASKANI', 'CLEVEL', 'PO', 'TECH']
export const NO_ROLE = 'none'
export const FACET_ALL = 'all'
export const FACET_SECONDARY = 'secondary'

/**
 * Hızlı süzgeç çipleri veriden türer: `[{ value, count, role? }]` — `all` hep ilk; her mevcut org rolü; rolsüz
 * üyeler `none`; yönetim verisinde ek üyelik varsa `secondary`. Tek bir rol varsa (hepsi uzman) süzgeç anlamsızdır →
 * çağıran `facets.length > 2` ile gösterir.
 */
export function memberFacets(members, teamId) {
  const counts = new Map()
  let secondary = 0
  for (const m of members) {
    const role = m.org_role || NO_ROLE
    counts.set(role, (counts.get(role) || 0) + 1)
    if (isSecondaryMember(m, teamId)) secondary++
  }
  const rank = (r) => { const i = ROLE_ORDER.indexOf(r); return i === -1 ? (r === NO_ROLE ? 99 : 50) : i }
  const roles = [...counts.entries()].sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
    .map(([role, count]) => ({ value: `role:${role}`, role, count }))
  const out = [{ value: FACET_ALL, count: members.length }, ...roles]
  if (secondary > 0) out.push({ value: FACET_SECONDARY, count: secondary })
  return out
}

/** Arama + çip süzgeci. `index` = {@link buildSearchIndex} (her çizimde metni yeniden katlamamak için). */
export function filterMembers(members, { query = '', facet = FACET_ALL, teamId = null, index = null } = {}) {
  const words = foldText(query).split(' ').filter(Boolean)
  return members.filter((m) => {
    if (facet === FACET_SECONDARY && !isSecondaryMember(m, teamId)) return false
    if (facet.startsWith('role:') && (m.org_role || NO_ROLE) !== facet.slice(5)) return false
    if (!words.length) return true
    const text = index?.get(m) ?? memberSearchText(m)
    return words.every((w) => text.includes(w))
  })
}

export function buildSearchIndex(members) {
  const idx = new Map()
  for (const m of members) idx.set(m, memberSearchText(m))
  return idx
}

/**
 * Üyelerin müdürlüğü TEK ise onu döner (başlıkta takımın bağlı olduğu birim). Karışıksa null — çoğunluğu takımın
 * birimi diye sunmak yanıltır.
 */
export function commonUnit(members) {
  let unit = null
  for (const m of members) {
    const u = String(m.mudurluk_name || '').trim()
    if (!u) continue
    if (unit == null) unit = u
    else if (unit !== u) return null
  }
  return unit
}

const sameId = (a, b) => a != null && b != null && a !== '' && b !== '' && String(a) === String(b)

/**
 * Takım Müdürü (TEK kişi) — başlık çipi için. Öncelik:
 *  1. `teamManager` verildiyse (yönetim ekranı: tüm kullanıcılarla türetilmiş, "Takım Müdürü" sütunuyla AYNI kayıt)
 *     o; `null` verildiyse "müdür yok" (türetme YAPILMAZ — sütunla çelişmesin).
 *  2. Elle atanmış `managerId` (takım rehberi): üyeyse onun adı, değilse ona bağlı bir üyenin `manager_display_name`'i.
 *  3. Üyelerin yönetim zincirinden türetme (`utils/teamManager.js` kuralı; kurum-geneli veride yalnız üyeler bilinir).
 *
 * @returns {{label: string, userId: *, manual: boolean, member: object|null}|null}
 */
export function resolveModalManager({ teamManager, managerId = null, leaderId = null, members = [], labelFor = null }) {
  const memberById = (id) => members.find((m) => sameId(m.id, id)) || null
  if (teamManager !== undefined) {
    if (!teamManager || !teamManager.label) return null
    return { label: teamManager.label, userId: teamManager.userId ?? null, manual: !!teamManager.manual, member: memberById(teamManager.userId) }
  }
  if (managerId != null && managerId !== '') {
    const member = memberById(managerId)
    if (member) return { label: nameOf(member), userId: member.id, manual: true, member }
    const via = members.find((m) => sameId(m.manager_id, managerId) && m.manager_display_name)
    if (via) return { label: via.manager_display_name, userId: managerId, manual: true, member: null }
  }
  const byId = {}
  for (const m of members) if (m.id != null) byId[m.id] = m
  const label = (u) => (labelFor ? labelFor(u) : null) || u?.manager_display_name || null
  const e = resolveTeamManagerEntry(members, byId, label, leaderId)
  if (!e) return null
  return { label: e.label, userId: e.userId, manual: false, member: e.userId != null ? (byId[e.userId] || null) : null }
}

/** Takım Lideri: kimlik + görünen ad (üyeyse unvanı da). */
export function resolveModalLeader({ leaderId = null, leaderName = null, members = [] }) {
  if (leaderId == null || leaderId === '') return leaderName ? { label: leaderName, userId: null, member: null } : null
  const member = members.find((m) => sameId(m.id, leaderId)) || null
  const label = leaderName || (member ? nameOf(member) : null)
  return label ? { label, userId: leaderId, member } : null
}

/** Eskalasyon seviyeleri — kritik önce (en dar kapsam). */
export const ESC_LEVELS = Object.freeze(['CRITICAL', 'HIGH', 'WARNING'])

/** Kişileri asgari seviyeye göre gruplar (boş grup yok; seviyesi bilinmeyen WARNING sayılır — sunucu varsayılanı). */
export function groupContactsByLevel(contacts) {
  const groups = new Map(ESC_LEVELS.map((l) => [l, []]))
  for (const c of contacts || []) {
    const level = ESC_LEVELS.includes(c?.min_alert_level) ? c.min_alert_level : 'WARNING'
    groups.get(level).push(c)
  }
  return ESC_LEVELS
    .map((level) => ({ level, items: groups.get(level).sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'tr')) }))
    .filter((g) => g.items.length > 0)
}

/** Görünen addan baş harfler (ilk + son sözcük); tek sözcükte ilk iki harf. Türkçe büyütme. */
export function initialsOf(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (parts.length === 1) return parts[0].slice(0, 2).toLocaleUpperCase('tr-TR')
  return (parts[0][0] + parts[parts.length - 1][0]).toLocaleUpperCase('tr-TR')
}

/** Kararlı karma → avatar yuvası (0..n-1). `TeamMemberCards.avatarStyleFor` ile AYNI karma: kişi her yüzeyde aynı ton ailesinde. */
export function avatarSlot(seed, n) {
  const s = String(seed || '')
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = ((hash << 5) - hash + s.charCodeAt(i)) | 0
  return Math.abs(hash) % n
}

/**
 * Baş harf avatarının JETON tonları (ham hex yok): açık temada yumuşak zemin + koyu mürekkep, koyu temada tersi.
 * Yuva sırası `avatarStyleFor` gradyanlarının ton aileleriyle eşleşir (indigo · gök · zümrüt · turuncu · pembe).
 */
export const AVATAR_TONES = Object.freeze([
  'bg-indigo-500/15 text-indigo-700 dark:bg-indigo-400/20 dark:text-indigo-200',
  'bg-sky-500/15 text-sky-700 dark:bg-sky-400/20 dark:text-sky-200',
  'bg-emerald-500/15 text-emerald-700 dark:bg-emerald-400/20 dark:text-emerald-200',
  'bg-orange-500/15 text-orange-700 dark:bg-orange-400/20 dark:text-orange-200',
  'bg-pink-500/15 text-pink-700 dark:bg-pink-400/20 dark:text-pink-200',
])

export function avatarToneFor(seed) {
  return AVATAR_TONES[avatarSlot(seed, AVATAR_TONES.length)]
}

/** Kişinin avatar tohumu — pencerenin üç sekmesinde aynı kişi aynı rengi alsın diye tek kaynak. */
export const memberSeed = (m) => String(m?.username || m?.display_name || m?.id || '')
