import { useCallback, useMemo } from 'react'

/**
 * İzleme formunda takım seçimi (2026-09-18, kullanıcı isteği): birden fazla takımı olan kullanıcı
 * yeni izlemeyi HANGİ takımına ekleyeceğini seçebilmeli.
 *
 * <p>Eskiden takım kutusu yalnız global admin için açıktı; diğer herkes birincil takımına kilitliydi
 * ve ikincil takımının izlemesini düzenleyemiyordu (`isOwnTeam` yalnız birincil takıma bakıyordu).
 * Sunucu tarafı da aynı anda gevşetildi: USER rolü artık ÜYESİ olduğu her takım için iş görür
 * (`MonitoringController.canOperateTeam` → memberTeamIds).
 *
 * <p>Dokuz izleme sayfası aynı üçlüyü kullanır — kopya kod olmasın diye tek hook:
 * <ul>
 *   <li>{@code canPickTeam}: kutu açılır mı (admin ya da 2+ takım),</li>
 *   <li>{@code pickTeams}: kutuda listelenecek takımlar — admin için sunucudan gelen tam liste,
 *       diğerlerinde yalnız ÜYESİ olduğu takımlar (admin ucu çağrılmaz),</li>
 *   <li>{@code isOwnTeam(m)}: satır kullanıcının herhangi bir takımına mı ait (düzenle/kontrol kapısı).</li>
 * </ul>
 *
 * @param {object} p
 * @param {boolean} p.isAdmin          global admin (tam takım listesi + takımsız seçeneği)
 * @param {Array<{id:number,name:string}>} p.adminTeams  sunucudan gelen tam liste (yalnız admin dolu)
 * @param {Array<{id:number,name:string}>} p.myTeams     kullanıcının üyesi olduğu takımlar (/me team_ids × team_names)
 * @param {number|null} p.teamId         birincil takım (varsayılan seçim)
 * @param {string|null} [p.teamName]     birincil takımın adı (oturumdan)
 *
 * <p><b>Varsayılan takım (2026-09-26, "USER rolü izleme ekleyebilmeli").</b> Sayfalar yeni izleme formunu yalnız
 * oturumun `team_id`'siyle açıyordu; tek takımlı ama oturum takımı boş (null) gelen üye kilitli "Takımsız" alan ve
 * gri Kaydet görüyordu — sunucu isteği kabul ettiği hâlde. Artık:
 * <ul>
 *   <li>{@code defaultTeamId}: `teamId ?? (tek takımı varsa o takım)`; iki+ takımlıda null (kullanıcı seçer),</li>
 *   <li>{@code defaultTeamName}: aynı takımın adı (kilitli alanda gösterilir),</li>
 *   <li>{@code teamless}: admin olmayan ve HİÇBİR takımı olmayan kullanıcı — form açık bir uyarı gösterir.</li>
 * </ul>
 */
export function useMonitorTeamPick({ isAdmin, adminTeams = [], myTeams = [], teamId, teamName = null }) {
  const myTeamIdSet = useMemo(() => {
    const s = new Set(myTeams.map((tm) => String(tm.id)))
    if (teamId != null) s.add(String(teamId))
    return s
  }, [myTeams, teamId])

  const canPickTeam = isAdmin || myTeams.length > 1
  // Yönetici rolünde sunucu listesi (/admin/teams = görüş kapsamı) erişim paylaşımıyla düzenlenen takımları içermez
  // (2026-10-12): kapsamlı müdür / TEAM_ADMIN paylaşan takıma da izleme ekleyebilsin diye eksik olanlar eklenir.
  const pickTeams = useMemo(() => (isAdmin ? mergeWriteTeams(adminTeams, myTeams, { flagExisting: true }) : myTeams), [isAdmin, adminTeams, myTeams])

  const isOwnTeam = useCallback((m) => m?.team_id != null && myTeamIdSet.has(String(m.team_id)), [myTeamIdSet])

  const only = myTeams.length === 1 ? myTeams[0] : null
  const defaultTeamId = teamId ?? only?.id ?? null
  const defaultTeamName = teamId != null
    ? (teamName ?? myTeams.find((tm) => String(tm.id) === String(teamId))?.name ?? null)
    : (only?.name ?? null)
  const teamless = !isAdmin && teamId == null && myTeams.length === 0

  return { canPickTeam, pickTeams, isOwnTeam, myTeamIdSet, defaultTeamId, defaultTeamName, teamless }
}

/**
 * Takım erişim paylaşımıyla YAZILABİLEN takımlar (2026-10-12): /me `access_grants` içinde düzeyi EDIT ya da DELETE olanlar
 * `[{id, name, grant: true}]`. Üyelik DEĞİLDİR — yalnız izleme / envanter ekleme takım seçicisine eklenir (Nav ve yönetim
 * üyelikle kalır). `grant` işareti düzenleme formunun takım seçicisinde paylaşımla gelen takımı ayırmak içindir
 * ({@link formTeamSelect}).
 */
export function grantWriteTeamsFromMe(res) {
  const list = Array.isArray(res?.access_grants) ? res.access_grants : []
  const out = list
    .filter((g) => g && g.team_id != null && (g.level === 'EDIT' || g.level === 'DELETE'))
    .map((g) => ({ id: g.team_id, name: g.team_name ?? String(g.team_id), grant: true }))
  // Kendi kapsamıyla GÖRÜLEN ama yalnız paylaşımla YAZILAN takımlar (inceleme 6, sunucu `grant_write_teams`): müdürün görüş
  // alanındaki takım / AUDIT — `access_grants` bunları düşürür; yeni kaydın kilidi için `grant` işaretiyle eklenir.
  const seen = new Set(out.map((tm) => String(tm.id)))
  for (const g of Array.isArray(res?.grant_write_teams) ? res.grant_write_teams : []) {
    if (!g || g.team_id == null || seen.has(String(g.team_id))) continue
    seen.add(String(g.team_id))
    out.push({ id: g.team_id, name: g.team_name ?? String(g.team_id), grant: true })
  }
  return out
}

/**
 * Bildirim YÖNLENDİRMESİNİN yeni kayıt varsayılanı (inceleme 5) — sunucunun `guardSharedNotificationFields` kuralında
 * "yeni varlık" değerleri. Paylaşan takıma eklenen kayıtta bu alanlar varsayılandan farklıysa sunucu 403 döner.
 */
export const ROUTING_DEFAULTS = Object.freeze({
  notifyEmail: true, notifyWebhook: true, alertLevel: 'WARNING', notificationGroupId: '', nocNotify: false, nocGroupIds: Object.freeze([]),
})

/** Envanter formunun (snake_case) karşılığı — kademe de yönlendirmedir (sertifika alarmının seviyesi). */
export const INVENTORY_ROUTING_DEFAULTS = Object.freeze({
  tier: null, notification_group_id: '', noc_notify: false, noc_group_ids: Object.freeze([]),
})

/** Değer, sunucunun eşdeğer saydığı varsayılan mı (null e-posta = açık, '' grup = yok, boş 7/24 grubu = varsayılan …). */
function isRoutingDefault(value, def) {
  if (def === true) return value !== false
  if (def === false) return value !== true
  if (Array.isArray(def)) return value == null || value === '' || (Array.isArray(value) && value.length === 0)
  if (def === 'WARNING') return value == null || value === '' || String(value).toUpperCase() === 'WARNING'
  return value == null || value === ''
}

/**
 * Formdaki yönlendirme alanlarını varsayılana çeker; zaten varsayılansa AYNI nesne döner (setState döngüsü olmaz).
 * Yalnız formda bulunan anahtarlara dokunulur (her izleme türü her alanı taşımaz).
 */
export function withRoutingDefaults(form, defaults = ROUTING_DEFAULTS) {
  if (!form || typeof form !== 'object') return form
  let out = form
  for (const [k, def] of Object.entries(defaults)) {
    if (!(k in form) || isRoutingDefault(form[k], def)) continue
    if (out === form) out = { ...form }
    out[k] = Array.isArray(def) ? [] : def
  }
  return out
}

/** Satır bildirim yönlendirmesi açısından kilitli mi: yalnız paylaşımla görülen ya da yalnız paylaşımla yazılabilen. */
export function isRoutingLockedRow(row) {
  return !!row && typeof row === 'object' && (row.routing_locked === true || row.shared_access === true)
}

/** Takım yalnız erişim paylaşımıyla yazılabilen bir takım mı (`pickTeams` içinde `grant: true`). */
export function isGrantTeam(teamId, pickTeams) {
  if (teamId == null || teamId === '') return false
  const key = String(teamId)
  return (Array.isArray(pickTeams) ? pickTeams : []).some((tm) => tm?.grant === true && String(tm.id) === key)
}

/**
 * Formda bildirim yönlendirmesi kilitli mi (inceleme 5): düzenlemede satırın `routing_locked` / `shared_access`
 * bayrağı (sunucu kararı), yeni kayıtta seçili takımın yalnız paylaşımla yazılabilmesi.
 */
export function routingLockOf(modal, teamId, pickTeams) {
  if (modal === 'new') return isGrantTeam(teamId, pickTeams)
  return isRoutingLockedRow(modal)
}

/** Kilitli seçicide kayıtlı takım seçeneklerde yoksa adıyla eklenir (çıplak kimlik görünmesin). */
function withCurrentTeamOption(options, modal) {
  const cur = modal?.team_id
  if (cur == null || options.some((o) => String(o.value) === String(cur))) return options
  return [...options, { value: String(cur), label: modal.team_name || String(cur) }]
}

/**
 * İzleme formunun takım seçicisi (2026-10-12): takım DEĞİŞİMİ erişim paylaşımıyla yapılamaz (sunucu `resolveTeamChange`
 * 403 döner). `modal` = sayfanın düzenleme durumu ('new' | satır | null); dönen `{options, disabled?}` seçiciye yayılır.
 *  - Yalnız paylaşımla görülen ya da yalnız paylaşımla düzenlenebilen kayıt (`shared_access` / `routing_locked`):
 *    seçici KİLİTLİ, kayıtlı takım adıyla görünür.
 *  - Kendi kaydı: paylaşımla yazılan takımlar seçenekten düşer (kayıtlı değer hariç) — oraya taşıma reddedilirdi.
 *  - Yeni kayıt: tüm seçenekler (paylaşan takıma izleme eklemek serbest).
 */
export function formTeamSelect(modal, options, pickTeams) {
  if (!modal || typeof modal !== 'object') return { options }
  if (isRoutingLockedRow(modal)) return { options: withCurrentTeamOption(options, modal), disabled: true }
  const grantIds = new Set((Array.isArray(pickTeams) ? pickTeams : []).filter((tm) => tm?.grant === true).map((tm) => String(tm.id)))
  if (!grantIds.size) return { options }
  const current = modal.team_id != null ? String(modal.team_id) : null
  return { options: options.filter((o) => !grantIds.has(String(o.value)) || String(o.value) === current) }
}

/** /me `access_grants` → `{ [teamId]: level }` (paylaşımla erişilen takımlar ve en yüksek düzey). */
export function grantLevelsFromMe(res) {
  const out = {}
  for (const g of Array.isArray(res?.access_grants) ? res.access_grants : []) {
    if (g && g.team_id != null && typeof g.level === 'string') out[String(g.team_id)] = g.level
  }
  return out
}

const LEVEL_RANK = { VIEW: 1, EXECUTE: 2, EDIT: 3, DELETE: 4 }

/**
 * Yalnız erişim paylaşımıyla görülen satırın eylemleri (2026-10-12). Satırın takımı kullanıcının üyesi olduğu bir takım
 * değilse ve paylaşım varsa `{ check, edit, delete }` (düzeye göre); aksi hâlde `null` — çağıran mevcut rol kuralını
 * uygular (paylaşımı olmayan kullanıcıda davranış aynı). Global görüntüleyicide her zaman `null`.
 */
export function rowGrantAccess(teamId, { memberTeamIds = [], grantLevels = {}, globalViewer = false } = {}) {
  if (globalViewer || teamId == null) return null
  const key = String(teamId)
  if (memberTeamIds.some((id) => String(id) === key)) return null
  const rank = LEVEL_RANK[grantLevels[key]] || 0
  if (!rank) return null
  return { check: rank >= LEVEL_RANK.EXECUTE, edit: rank >= LEVEL_RANK.EDIT, delete: rank >= LEVEL_RANK.DELETE }
}

/**
 * Üyelik + paylaşımla yazılabilen takımlar — kimlik başına bir kez, üyelik önce.
 *
 * `flagExisting` (inceleme 6): taban liste yöneticinin `/admin/teams` GÖRÜŞ listesiyse, orada zaten olan ama yalnız
 * paylaşımla yazılabilen takım `grant` işaretini alır — yoksa yeni kaydın bildirim yönlendirmesi kilitlenmez ve sunucu 403
 * verir. Taban ÜYELİK listesiyse kapalı kalır (üyelik kendi kapsamıdır; bayat bir /me yanıtı kendi takımını kilitlemesin).
 */
export function mergeWriteTeams(myTeams, grantTeams, { flagExisting = false } = {}) {
  const base = Array.isArray(myTeams) ? myTeams : []
  const grants = Array.isArray(grantTeams) ? grantTeams : []
  const flagged = new Set(flagExisting ? grants.filter((tm) => tm?.grant === true).map((tm) => String(tm.id)) : [])
  let merged = base
  if (flagged.size && base.some((tm) => tm && tm.grant !== true && flagged.has(String(tm.id)))) {
    merged = base.map((tm) => (tm && tm.grant !== true && flagged.has(String(tm.id)) ? { ...tm, grant: true } : tm))
  }
  const seen = new Set(base.map((tm) => String(tm.id)))
  const extra = []
  for (const tm of grants) {
    if (tm && !seen.has(String(tm.id))) { seen.add(String(tm.id)); extra.push(tm) }
  }
  // Eklenecek / işaretlenecek takım yoksa AYNI dizi döner (paylaşımı olmayan kullanıcıda kimlik ve memo bağımlılıkları değişmez)
  return extra.length ? [...merged, ...extra] : merged
}

/** /me yanıtındaki paralel dizileri ({team_ids, team_names}) takım nesnelerine çevirir. */
export function teamsFromMe(res) {
  const ids = Array.isArray(res?.team_ids) ? res.team_ids : []
  const names = Array.isArray(res?.team_names) ? res.team_names : []
  return ids.map((id, i) => ({ id, name: names[i] ?? String(id) })).filter((tm) => tm.id != null)
}
