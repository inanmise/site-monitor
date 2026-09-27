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
 *   <li>Adaylar = takım ÜYELERİNİN (birincil takım VEYA ek üyelik — {@link isTeamMember}) bağlı olduğu
 *       kişiler (manager_id; çözülemiyorsa manager_sicil → sicili tekil eşleşen kullanıcı → yoksa sicil).</li>
 *   <li>Takım lideri ve yönetici olmayan roller (PO, TECH) aday değildir — takımın içindedirler.</li>
 *   <li>Başka bir adayın yönetim zincirinde ÜSTÜ olan aday düşer (bölüm başkanı, müdürün üstü).</li>
 *   <li>Kalanlar arasında en yakın kademe (MANAGER < bilinmeyen < BOLUM_BASKANI < CLEVEL), sonra
 *       takımda en çok doğrudan bağlısı olan, sonra ad sırası.</li>
 *   <li>Hiç aday kalmazsa elenen (PO/lider) adayların bir üst yöneticisiyle aynı kural yeniden denenir.</li>
 * </ol>
 *
 * <p><b>Türetilen müdür takımın ÜYESİ DEĞİLDİR</b> (prod hatası 2026-09-26): ekranda ayrı etiketle
 * ("Takım Müdürü") durur, üye listesine/sayısına asla eklenmez. Üye kartları eskiden üyelerden
 * yukarı 2 kademe zincir yürüyüp müdürü ve onun müdürünü üye ızgarasına koyuyordu; takımda olmayan
 * kişi "takımın içinde" görünüyordu.
 *
 * <p>2026-09-26 düzeltmeleri: (a) üyelik yalnız birincil {@code team_id} değil {@code team_ids}'ten de
 * okunur (ek üyelikle bağlı çoğunluk sayılmıyordu); (b) aynı müdür bir üyede {@code manager_id},
 * ötekinde yalnız {@code manager_sicil} ile gelince İKİ aday sayılıp oyu bölünüyordu — sicil tekil
 * eşleşen kullanıcıya çözülür; (c) seçilen müdür KİMLİĞİYLE döner ({@link resolveTeamManagerEntry}) —
 * görünen ada göre geri eşlemek aynı adlı iki kişiyi karıştırıyordu.
 *
 * Saf fonksiyon: ağ yok, DOM yok — kapılar `teamManager.test.js`, `teamManagerDerivation.test.js`.
 */

const NON_MANAGER_ROLES = new Set(['PO', 'TECH'])
const RANK = { MANAGER: 0, BOLUM_BASKANI: 2, CLEVEL: 3 }
const MAX_CHAIN = 8

/** Kullanıcı bu takımın GERÇEK üyesi mi — birincil takım ya da çoklu üyelik (app_user_teams). */
export function isTeamMember(user, teamId) {
  if (!user || teamId == null) return false
  const tid = String(teamId)
  if (user.team_id != null && String(user.team_id) === tid) return true
  const ids = user.team_ids ?? user.teamIds
  return Array.isArray(ids) && ids.some((x) => x != null && String(x) === tid)
}

function rankOf(user) {
  if (!user) return 1
  return RANK[user.org_role] ?? 1
}

const normSicil = (s) => (s == null ? '' : String(s).trim().toUpperCase())

/** employee_id → kullanıcı; aynı sicili taşıyan BİRDEN FAZLA kullanıcı varsa o sicil çözülmez (yanlış kişiye bağlamasın). */
function sicilIndexOf(usersById) {
  const idx = new Map()
  const dup = new Set()
  Object.values(usersById || {}).forEach((u) => {
    const s = normSicil(u?.employee_id)
    if (!s) return
    if (idx.has(s)) dup.add(s)
    else idx.set(s, u)
  })
  dup.forEach((s) => idx.delete(s))
  return idx
}

/** Bir kullanıcının müdürü (kullanıcı nesnesi) — manager_id, yoksa tekil eşleşen sicil. */
function managerUserOf(u, usersById, sicilIdx) {
  if (u?.manager_id != null && u.manager_id !== '') return usersById[u.manager_id] || null
  const s = normSicil(u?.manager_sicil)
  return s ? (sicilIdx.get(s) || null) : null
}

function keyOf(u, usersById, sicilIdx) {
  if (u?.manager_id != null && u.manager_id !== '') return `id:${u.manager_id}`
  const s = normSicil(u?.manager_sicil)
  if (!s) return null
  const m = sicilIdx.get(s)
  return m ? `id:${m.id}` : `sicil:${s}`
}

/** Bir kullanıcının yönetim zincirindeki üst anahtarları (kendisi hariç). */
function ancestorKeys(user, usersById, sicilIdx) {
  const out = new Set()
  let cur = user
  for (let i = 0; i < MAX_CHAIN && cur; i++) {
    const k = keyOf(cur, usersById, sicilIdx)
    if (!k || out.has(k)) break
    out.add(k)
    cur = managerUserOf(cur, usersById, sicilIdx)
  }
  return out
}

function pick(candidates, usersById, sicilIdx, leaderId) {
  const excluded = []
  let remaining = candidates.filter(c => {
    const isLeader = leaderId != null && c.user && String(c.user.id) === String(leaderId)
    const nonManager = c.user && NON_MANAGER_ROLES.has(c.user.org_role)
    if (isLeader || nonManager) { excluded.push(c); return false }
    return true
  })
  // Başka bir adayın üstü olan aday düşer (ilk yönetici = zincirde en yakın olan).
  const above = new Set()
  remaining.forEach(c => { if (c.user) ancestorKeys(c.user, usersById, sicilIdx).forEach(k => above.add(k)) })
  remaining = remaining.filter(c => !above.has(c.key))
  remaining.sort((a, b) => rankOf(a.user) - rankOf(b.user) || b.count - a.count
    || a.label.localeCompare(b.label, 'tr'))
  return { chosen: remaining[0] ?? null, excluded }
}

/**
 * @param {Array} members takımın ÜYELERİ (birincil ya da ek üyelik)
 * @param {Object} usersById id → kullanıcı (tüm aktif kullanıcılar)
 * @param {Function} labelFor kullanıcıdan müdür etiketi (ad ya da sicil) — null olabilir
 * @param {*} leaderId takım lideri id'si
 * @returns {{label: string, userId: (number|string|null)}|null} seçilen müdür; kullanıcıya
 *          çözülemediyse (yalnız sicil) userId null
 */
export function resolveTeamManagerEntry(members, usersById, labelFor, leaderId) {
  const byId = usersById || {}
  const sicilIdx = sicilIndexOf(byId)
  const collect = (people) => {
    const map = new Map()
    people.forEach(u => {
      const key = keyOf(u, byId, sicilIdx)
      if (!key) return
      const mgr = managerUserOf(u, byId, sicilIdx)
      // Sicil bir kullanıcıya çözüldüyse etiket de o kullanıcının adı olsun (id ile gelenle aynı aday).
      const label = (mgr && (mgr.display_name || mgr.username)) || labelFor(u)
      if (!label) return
      const cur = map.get(key) || { key, label, count: 0, user: mgr }
      cur.count++
      map.set(key, cur)
    })
    return [...map.values()]
  }
  let { chosen, excluded } = pick(collect(members), byId, sicilIdx, leaderId)
  // Yalnız PO/lider adaylar kaldıysa bir kademe yukarı çık (lider takım üyesi değilse bu gerekir).
  if (!chosen && excluded.length) {
    const ups = excluded.map(c => c.user).filter(Boolean)
    chosen = pick(collect(ups), byId, sicilIdx, leaderId).chosen
  }
  if (!chosen) return null
  return { label: chosen.label, userId: chosen.user ? chosen.user.id : null }
}

/** Geriye-uyumlu kısa yol: yalnız etiket (ad ya da sicil) — null olabilir. */
export function resolveTeamManager(members, usersById, labelFor, leaderId) {
  return resolveTeamManagerEntry(members, usersById, labelFor, leaderId)?.label ?? null
}
