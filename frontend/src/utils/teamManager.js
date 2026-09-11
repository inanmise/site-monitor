/**
 * Takımın müdürü — TEK kişi.
 *
 * <p><b>Neden var (2026-09-10, kullanıcı bildirimi).</b> Takım listesindeki "Takım Müdürü" sütunu
 * üyelerin bağlı olduğu müdürlerin BİRLEŞİMİNİ yazıyordu: takım liderine (PO) bağlı üyeler
 * PO'yu, PO'nun kendisi de asıl müdürü getirince iki ad çıkıyordu; müdürün kendisi takım üyesi
 * olunca onun üstü (bölüm başkanı) da listeye giriyordu. Oysa bir takımın bir müdürü olur:
 * takımın bağlı olduğu İLK yönetici.
 *
 * <p>Kural:
 * <ol>
 *   <li>Adaylar = takım üyelerinin bağlı olduğu kişiler (manager_id, çözülemiyorsa manager_sicil).</li>
 *   <li>Takım lideri ve yönetici olmayan roller (PO, TECH) aday değildir — takımın içindedirler.</li>
 *   <li>Başka bir adayın yönetim zincirinde ÜSTÜ olan aday düşer (bölüm başkanı, müdürün üstü).</li>
 *   <li>Kalanlar arasında en yakın kademe (MANAGER < bilinmeyen < BOLUM_BASKANI < CLEVEL), sonra
 *       takımda en çok doğrudan bağlısı olan, sonra ad sırası.</li>
 *   <li>Hiç aday kalmazsa elenen (PO/lider) adayların bir üst yöneticisiyle aynı kural yeniden denenir.</li>
 * </ol>
 * Saf fonksiyon: ağ yok, DOM yok — kapı `teamManager.test.js`.
 */

const NON_MANAGER_ROLES = new Set(['PO', 'TECH'])
const RANK = { MANAGER: 0, BOLUM_BASKANI: 2, CLEVEL: 3 }
const MAX_CHAIN = 8

function rankOf(user) {
  if (!user) return 1
  return RANK[user.org_role] ?? 1
}

function keyOf(u) {
  if (u?.manager_id != null && u.manager_id !== '') return `id:${u.manager_id}`
  if (u?.manager_sicil) return `sicil:${u.manager_sicil}`
  return null
}

/** Bir kullanıcının yönetim zincirindeki üst anahtarları (kendisi hariç). */
function ancestorKeys(user, usersById) {
  const out = new Set()
  let cur = user
  for (let i = 0; i < MAX_CHAIN && cur; i++) {
    const k = keyOf(cur)
    if (!k || out.has(k)) break
    out.add(k)
    cur = cur.manager_id != null ? usersById[cur.manager_id] : null
  }
  return out
}

function pick(candidates, usersById, leaderId) {
  const excluded = []
  let remaining = candidates.filter(c => {
    const isLeader = leaderId != null && c.user && String(c.user.id) === String(leaderId)
    const nonManager = c.user && NON_MANAGER_ROLES.has(c.user.org_role)
    if (isLeader || nonManager) { excluded.push(c); return false }
    return true
  })
  // Başka bir adayın üstü olan aday düşer (ilk yönetici = zincirde en yakın olan).
  const above = new Set()
  remaining.forEach(c => { if (c.user) ancestorKeys(c.user, usersById).forEach(k => above.add(k)) })
  remaining = remaining.filter(c => !above.has(c.key))
  remaining.sort((a, b) => rankOf(a.user) - rankOf(b.user) || b.count - a.count
    || a.label.localeCompare(b.label, 'tr'))
  return { chosen: remaining[0] ?? null, excluded }
}

/**
 * @param {Array} members takımın kullanıcıları
 * @param {Object} usersById id → kullanıcı (tüm aktif kullanıcılar)
 * @param {Function} labelFor kullanıcıdan müdür etiketi (ad ya da sicil) — null olabilir
 * @param {*} leaderId takım lideri id'si
 * @returns {string|null} tek müdür etiketi
 */
export function resolveTeamManager(members, usersById, labelFor, leaderId) {
  const collect = (people) => {
    const map = new Map()
    people.forEach(u => {
      const key = keyOf(u)
      if (!key) return
      const label = labelFor(u)
      if (!label) return
      const cur = map.get(key) || { key, label, count: 0, user: u.manager_id != null ? usersById[u.manager_id] : null }
      cur.count++
      map.set(key, cur)
    })
    return [...map.values()]
  }
  let { chosen, excluded } = pick(collect(members), usersById, leaderId)
  // Yalnız PO/lider adaylar kaldıysa bir kademe yukarı çık (lider takım üyesi değilse bu gerekir).
  if (!chosen && excluded.length) {
    const ups = excluded.map(c => c.user).filter(Boolean)
    chosen = pick(collect(ups), usersById, leaderId).chosen
  }
  return chosen ? chosen.label : null
}
