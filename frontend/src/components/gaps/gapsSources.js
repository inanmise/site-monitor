// Eksikler penceresi — KAYNAK yükleyici (2026-11-15 zenginleştirme). React yok; `api` çağıranın verdiği nesne (testte sahte).
//
// Kurallar (kullanıcı kuralı: "zaman aşımsız istek / sonsuz döngü yok"):
//   • her istek bir üst süreyle biter (`withDeadline`, LOAD_DEADLINE_MS) — gövde takılsa da okuma "okunamadı" sayılır,
//   • aynı anda en çok POOL_SIZE istek gider (tek pencere açılışı sunucuyu boğmasın), havuz döngüsü görev sayısıyla sınırlı,
//   • yetkisi olmayan denetim HİÇ istenmez (`denied`), yetkiler henüz bilinmiyorsa istenmez (`off`),
//   • okunamayan kaynak "eksik yok" değildir (`failed` — pencere "okunamadı" der).

import { errorInfoOf } from '../../utils/errorMessages.js'
import { DEADLINE, LOAD_DEADLINE_MS, MAX_LOADED_TEAMS, TEAM_FETCH_TIMEOUT_MS, ownTeamIds, withDeadline } from './gapsModel.js'
import { CONTACT_CODES, makeContext, profileGaps } from './gapsProfile.js'
import { onCallMembership } from './gapsExtra.js'

/** Aynı anda en çok giden istek. */
export const POOL_SIZE = 4

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)

/**
 * Görevleri en çok `limit` eşzamanlı çalıştırır (sıra korunur). Her görev bir söz döndüren fonksiyondur ve kendi içinde
 * süre sınırlıdır; havuz döngüsü görev sayısıyla sınırlıdır.
 */
export async function runPool(tasks, limit = POOL_SIZE) {
  const list = Array.isArray(tasks) ? tasks : []
  const out = new Array(list.length)
  let next = 0
  const worker = async () => {
    while (next < list.length) {
      const k = next++
      try { out[k] = await list[k]() } catch { out[k] = { status: 'failed' } }
    }
  }
  const n = Math.max(1, Math.min(limit, list.length))
  await Promise.all(Array.from({ length: n }, worker))
  return out
}

/** Sunucu yanıtını kaynak durumuna çevirir: 403 → denied, 404 / 405 → unsupported (ya da `notFound` verilmişse o), diğer → failed. */
export function classify(res, { notFound = 'unsupported', pick = (r) => r.data } = {}) {
  if (res === DEADLINE || res == null) return { status: 'failed' }
  if (isObj(res) && res.success !== false && !res.error) return { status: 'ok', data: pick(res) }
  const st = errorInfoOf(res)?.status ?? (Number.isFinite(Number(res?.status)) ? Number(res.status) : null)
  if (st === 403) return { status: 'denied' }
  if (st === 404) return { status: notFound }
  if (st === 405) return { status: 'unsupported' }
  return { status: 'failed' }
}

/** Tek istek: üst süre + hata → `failed`. `fn` yoksa (eski istemci / sahte api) `failed`. */
async function call(fn, opts) {
  if (typeof fn !== 'function') return { status: 'failed' }
  try {
    return classify(await withDeadline(fn(), LOAD_DEADLINE_MS), opts)
  } catch {
    return { status: 'failed' }
  }
}

/** Yetki üç değerli: 'yes' (iste), 'no' (denied — istenmez), diğer (yetkiler bilinmiyor — istenmez). */
const gate = (can, task) => (can === 'yes' ? task : async () => ({ status: can === 'no' ? 'denied' : 'off' }))

/**
 * Bir takımın veri kalitesi + nöbet çizelgesi görevleri. 404 = takım pasif / artık görünmüyor → uygulanamaz (`skipped`).
 */
function teamTasks(api, id, can) {
  return [
    gate(can.dq, () => call(() => api.dataQuality?.team?.(id, false, { timeoutMs: TEAM_FETCH_TIMEOUT_MS }), { notFound: 'skipped' })),
    gate(can.oncall, () => call(() => api.oncall?.get?.(id), { notFound: 'skipped' })),
  ]
}

/**
 * `/me` okuması (profil + takımlar). Okunamazsa `null` — çağıran uygulamanın bildiği takımları kullanır, profil "okunamadı".
 */
export async function loadMe(api) {
  try {
    const res = await withDeadline(api.getMe?.({ timeoutMs: TEAM_FETCH_TIMEOUT_MS }), LOAD_DEADLINE_MS)
    return res && res !== DEADLINE && isObj(res) && !res.error && res.success !== false ? res : null
  } catch {
    return null
  }
}

/**
 * Bütün kaynaklar. `can` = {dq, groups, oncall, forwards, channels, actions} ('yes' | 'no' | 'unknown'); `myTeamIds` /
 * `manageTeamIds` uygulamanın bildiği takımlar (taze `/me` okunamazsa); `teamIds` verilirse YALNIZ o takımlar (pencerede
 * "sonraki takımlar"); `isCurrent()` false dönerse yarıda bırakılır (oturum değişti / yeni okuma başladı).
 */
export async function loadGapSources({ api, can = {}, myTeamIds, manageTeamIds, globalAdmin = false, isCurrent = () => true } = {}) {
  const me = await loadMe(api)
  if (!isCurrent()) return null
  const own = ownTeamIds(me && Array.isArray(me.team_ids) ? me.team_ids : myTeamIds,
    me && Array.isArray(me.manage_team_ids) ? me.manage_team_ids : manageTeamIds)
  const ids = own.ids
  const contactGaps = profileGaps(me).some((g) => CONTACT_CODES.includes(g.code))
  const needDirectory = (!globalAdmin && contactGaps) || own.rest.length > 0

  const shared = [
    () => call(() => api.getLoginMethods?.(), { pick: (r) => r }),
    () => call(() => api.me?.getPushPreferences?.(), { pick: (r) => r }),
    gate(can.groups, () => call(() => api.notificationGroups?.list?.(), { pick: (r) => r.data?.groups ?? r.groups ?? [] })),
    gate(can.forwards, () => call(() => api.alertForwards?.list?.(), { pick: (r) => r.data?.items ?? r.items ?? [] })),
    gate(can.channels, () => call(() => api.techErrors?.overview?.(), { pick: (r) => r.data ?? r })),
    gate(can.actions, () => call(() => api.incidents?.actions?.({ status: 'open', mine: true, overdue: true, size: 5 }),
      { pick: (r) => r.data ?? r })),
    needDirectory ? () => call(() => api.teams?.directory?.(), { pick: (r) => (Array.isArray(r.data) ? r.data : []) }) : async () => ({ status: 'off' }),
  ]
  const perTeam = ids.flatMap((id) => teamTasks(api, id, can))
  const results = await runPool([...shared, ...perTeam])
  if (!isCurrent()) return null
  const [login, prefs, groups, forwards, channels, actions, directory] = results
  const teams = ids.map((id, i) => ({ id, name: teamNameOf(me, id, directory), dq: results[shared.length + 2 * i], oncall: results[shared.length + 2 * i + 1] }))
  return {
    me, login, prefs, groups, forwards, channels, actions, directory, teams,
    autoTeamIds: ids, restTeamIds: own.rest.slice(0, MAX_LOADED_TEAMS - ids.length), moreTeams: own.more,
  }
}

/**
 * Ek takımlar (pencerede "sonraki takımlar" / seçiciden): yalnız takım başına kaynaklar okunur, ortak kaynaklar (gruplar,
 * iletimler, kanallar) ilk okumadan kalır. Toplam takım sayısı MAX_LOADED_TEAMS ile sınırlı (çağıran keser).
 */
export async function loadMoreTeams({ api, can = {}, teamIds = [], me = null, directory = null, isCurrent = () => true } = {}) {
  const ids = (Array.isArray(teamIds) ? teamIds : []).slice(0, MAX_LOADED_TEAMS)
  if (ids.length === 0) return []
  const results = await runPool(ids.flatMap((id) => teamTasks(api, id, can)))
  if (!isCurrent()) return null
  return ids.map((id, i) => ({ id, name: teamNameOf(me, id, directory), dq: results[2 * i], oncall: results[2 * i + 1] }))
}

/** Takım adı: `/me` (`team_ids` ↔ `team_names` aynı sıra) ya da takım dizini. Bilinmiyorsa null. */
export function teamNameOf(me, id, directory) {
  if (isObj(me) && Array.isArray(me.team_ids) && Array.isArray(me.team_names)) {
    const i = me.team_ids.findIndex((x) => String(x) === String(id))
    if (i >= 0 && typeof me.team_names[i] === 'string' && me.team_names[i]) return me.team_names[i]
  }
  const dir = directory?.status === 'ok' && Array.isArray(directory.data) ? directory.data : []
  const row = dir.find((t) => isObj(t) && String(t.id) === String(id))
  return row && typeof row.name === 'string' && row.name ? row.name : null
}

/**
 * Önem bağlamı: nöbet üyeliği (okunan çizelgelerden), 7/24 ekibi (`/me` `noc_operator`), kodla girişte telefon / e-posta
 * zorunluluğu (genel giriş yöntemleri). Okunamayan bilgi `null` (bilinmiyor) kalır.
 */
export function contextOf(src, username) {
  const login = src?.login?.status === 'ok' && isObj(src.login.data) ? src.login.data : null
  const me = isObj(src?.me) ? src.me : null
  const onCall = onCallMembership((src?.teams || []).map((t) => t.oncall).filter(Boolean), username)
  return makeContext({
    onCall,
    noc: me && typeof me.noc_operator === 'boolean' ? me.noc_operator : null,
    otpPhone: login ? (login.otp_push === true && login.push_requires_phone === true) : null,
    otpEmail: login ? (login.otp_email === true && login.email_requires_email === true) : null,
  })
}
