import { errorInfoOf } from '../../utils/errorMessages.js'
import { familyLabel } from '../../utils/pushPrefs.js'

/**
 * Alarm iletimi — izleme / sertifika penceresi rozeti, yeniden gönderim penceresi, bildirim geçmişi ve "Kim
 * bilgilendirilir?" yüzeylerinin SAF modeli (2026-11-05). Karar SUNUCUNUNDUR (`GET /api/alert-forwards/for-monitor`,
 * önizleme satırlarının `kind: "FORWARD"`'u, simülasyonun `forwards` listesi); burada yalnız ekranın biçimi kurulur.
 *
 * <p>Kural: okunamayan iletim bilgisi "iletim yok" DEĞİLDİR — ekran ya hiçbir şey çizmez (gizli / eski sunucu / izleme
 * yok) ya da küçük bir "okunamadı" gösterir. Sunucu satırlarındaki durum kodları ve kapsam türleri ham basılmaz.
 */

/** Sunucunun iletim satırı durumları (sözleşme §3 öncelik sırası). Bilinmeyen durum "bilinmiyor" okunur, asla etkin değil. */
export const FORWARD_STATES = ['active', 'paused', 'expired', 'recipient_inactive', 'no_targets', 'no_recipients']

/** İletim günlüğü / önizleme satırının alıcı rolü (sunucu `recipient_role` / `kind`). */
export const FORWARD_ROLE = 'FORWARD'

/**
 * 7/24 tür anahtarından (MonitorDetailModal `noc.type`: HTTP, PING, … ; sertifika SSL) iletim tür anahtarına
 * (`MonitorTypeCatalog.ORDER`: http, ping, … ; sertifika "cert"). Boş → null.
 */
export function forwardTypeKey(nocType) {
  const s = String(nocType ?? '').trim()
  if (!s) return null
  return s.toUpperCase() === 'SSL' ? 'cert' : s.toLowerCase()
}

/** Düz nesne mi (dizi / null değil). */
const isObj = (v) => v != null && typeof v === 'object' && !Array.isArray(v)

/**
 * `forMonitor` yanıtı → rozet durumu.
 * - `none`  : hiçbir şey çizilmez — görüntüleyici sahibi görmüyor (`hidden`), izleme yok / dizinde yok (404), izin yok
 *             (403), bilinmeyen tür (400 field `type`), eski sunucu (404 / 405), oturum bitti (null) ya da yanıt beklenen
 *             biçimde değil;
 * - `error` : okunamadı (ağ, zaman aşımı, 5xx) — küçük "okunamadı" + tekrar dene;
 * - `ok`    : `items` (+ `canAdd`, `ownerTeams`, `monitor`).
 */
export function readForMonitor(res) {
  if (res == null || typeof res !== 'object') return { status: 'none' }
  if (res.success === false) {
    const st = errorInfoOf(res)?.status
    // 400 = sunucu türü tanımıyor (field `type`) — rozet çizilmez, "okunamadı" da denmez
    if (st === 400 || st === 401 || st === 403 || st === 404 || st === 405) return { status: 'none' }
    return { status: 'error' }
  }
  const data = res.data
  if (!isObj(data) || data.hidden === true || !Array.isArray(data.items)) return { status: 'none' }
  return {
    status: 'ok',
    items: data.items.filter(isObj),
    canAdd: data.can_add === true,
    ownerTeams: Array.isArray(data.owner_teams) ? data.owner_teams.filter((o) => isObj(o) && o.id != null) : [],
    monitor: isObj(data.monitor) ? data.monitor : null,
  }
}

/** Satırın durum anahtarı; bilinmeyen / boş → 'unknown'. */
export function forwardState(item) {
  const s = String(item?.state ?? '').trim().toLowerCase()
  return FORWARD_STATES.includes(s) ? s : 'unknown'
}

/** Gerçekten iletilen (etkin) satır sayısı. */
export const activeCount = (items) => (Array.isArray(items) ? items.filter((i) => forwardState(i) === 'active').length : 0)

/**
 * Rozetin görünümü:
 * - `active`   : en az bir etkin iletim → "İletim: N" (bilgi tonu);
 * - `inactive` : iletim var ama hiçbiri etkin değil (duraklatılmış / süresi dolmuş / alıcısız) → sessiz "etkin değil";
 * - `add`      : iletim yok, görüntüleyici ekleyebilir → sessiz "İletim ekle";
 * - null       : çizilmez (iletim yok ve eklenemez).
 */
export function pillKind(view) {
  if (view?.status !== 'ok') return null
  if (activeCount(view.items) > 0) return 'active'
  if (view.items.length > 0) return 'inactive'
  return view.canAdd ? 'add' : null
}

/** Durum rozeti tonu (bilgi amaçlı — renkli alarm DEĞİL). */
export const STATE_TONE = {
  active: 'info',
  paused: 'muted',
  expired: 'muted',
  recipient_inactive: 'warning',
  no_targets: 'warning',
  no_recipients: 'warning',
  unknown: 'muted',
}

/** Durum metni anahtarı (i18n). Anahtarlar AÇIK yazılı — kullanılmayan anahtar kapısı dinamik anahtarı göremez. */
export const STATE_KEY = {
  active: 'fwdx.state.active',
  paused: 'fwdx.state.paused',
  expired: 'fwdx.state.expired',
  recipient_inactive: 'fwdx.state.recipientInactive',
  no_targets: 'fwdx.state.noTargets',
  no_recipients: 'fwdx.state.noRecipients',
  unknown: 'fwdx.state.unknown',
}

/** Alıcı özeti: "Tüm takım" ya da "N üye". */
export function recipientText(t, item) {
  if (String(item?.recipient_kind ?? '').toUpperCase() === 'MEMBERS') {
    const n = Array.isArray(item?.recipient_members) ? item.recipient_members.length : 0
    return n === 1 ? t('fwdx.recipient.members.one') : t('fwdx.recipient.members', n)
  }
  return t('fwdx.recipient.team')
}

/**
 * Kapsam türünün adı: push tür etiketleriyle aynı kısa adlar (`userpush.type.*`); yalnız "cert" iletimde envanter kaydının
 * TÜM alarmlarıdır (sertifika + erişilebilirlik) — yönetim ekranındaki adıyla (`fwd.type.cert`), "Sertifika" denmez.
 */
export function scopeTypeName(type, t) {
  return String(type ?? '') === 'cert' ? t('fwd.type.cert') : familyLabel(type, t)
}

/**
 * Kapsam etiketi: "Tüm izlemeler" / "Türler: HTTP, Ping" / "Seçilen N izleme". Tür adları {@link scopeTypeName}.
 */
export function scopeText(t, kind, types, monitorsCount) {
  const k = String(kind ?? '').toUpperCase()
  if (k === 'TYPES') {
    const names = (Array.isArray(types) ? types : []).map((x) => scopeTypeName(x, t)).filter(Boolean)
    return names.length ? t('fwdx.scope.types', names.join(', ')) : t('fwdx.scope.typesNone')
  }
  if (k === 'MONITORS') {
    const n = Number(monitorsCount) || 0
    return n === 1 ? t('fwdx.scope.monitors.one') : t('fwdx.scope.monitors', n)
  }
  return t('fwdx.scope.all')
}

/**
 * "Kim bilgilendirilir?" iletim satırının kapsam etiketi (sunucu `applies`): `all` — seçilen türe uyan (ALL ya da TYPES ∋
 * tür); `types_only` — yalnız SEÇİLEN türler (senaryo genel, tür belirsiz) → tür adlarıyla; `monitors_only` — yalnız seçilen
 * izlemeler. Bilinmeyen değer "tümü" sayılmaz: tür listesi varsa türlerle, yoksa izleme kapsamı gibi temkinli okunur.
 */
export function simAppliesText(t, row) {
  const applies = String(row?.applies ?? '').toLowerCase()
  const scope = String(row?.scope_kind ?? '').toUpperCase()
  const types = Array.isArray(row?.scope_types) ? row.scope_types : []
  if (applies === 'monitors_only') return t('fwdx.sim.appliesMonitors')
  if (applies === 'types_only' || (applies !== 'all' && types.length > 0)) {
    const names = types.map((x) => scopeTypeName(x, t)).filter(Boolean)
    return names.length ? t('fwdx.sim.appliesTypes', names.join(', ')) : t('fwdx.scope.typesNone')
  }
  if (applies !== 'all') return t('fwdx.sim.appliesMonitors')
  return scope === 'TYPES' ? t('fwdx.sim.appliesType') : t('fwdx.scope.all')
}

/** Satır "yalnız bir bölüm için" mi (`types_only` / `monitors_only`) — not ve uyarı tonu. */
export const simAppliesPartly = (row) => ['types_only', 'monitors_only'].includes(String(row?.applies ?? '').toLowerCase())

/** Satırın kapsam etiketi. */
export const itemScopeText = (t, item) => scopeText(t, item?.scope_kind, item?.scope_types,
  Array.isArray(item?.scope_monitors) ? item.scope_monitors.length : 0)

/** "yyyy-MM-dd" → yerel gün ("12 Kas 2026"); biçimsiz değer → olduğu gibi. */
export function formatDay(ymd, locale) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymd ?? ''))
  if (!m) return ymd ? String(ymd) : ''
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  try { return d.toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric' }) } catch { return String(ymd) }
}

/**
 * Yönetim → Alarm iletimi bağlantısı. `add` verilirse pencere bu izleme için açılır (`g_fnew`): "<tür>:<id>" — kimlik
 * (sertifikada envanter kaydının kimliği, `for-monitor` yanıtının `monitor.id`'si) biliniyorsa onunla; sertifikada kimlik
 * yoksa alan adıyla "cert:<alanadı>".
 *
 * <p>Sahip takım `g_fteam` (sunucu `owner_teams` = yalnız görüntüleyicinin KENDİ kapsamında gördüğü sahipler, her biri
 * `can_manage` taşır): ekleme bağlantısı YÖNETEBİLDİĞİ ilk sahibi seçer (sertifikada UG yönetilip SY yönetilmiyorsa UG);
 * hiçbirini yönetemiyorsa `g_fteam` yok (pencere kendi seçer). "Tümünü yönet" görülen ilk sahiple süzer (yoksa süzgeç yok).
 * Eski sunucu `can_manage` göndermez: o zaman ilk sahip (önceki davranış).
 */
export function manageTarget({ type, id, domain, ownerTeams, add = false }) {
  const params = { g_tab: 'forwards' }
  const owners = (Array.isArray(ownerTeams) ? ownerTeams : []).filter((o) => o && o.id != null)
  const knowsManage = owners.some((o) => typeof o.can_manage === 'boolean')
  const owner = add && knowsManage ? owners.find((o) => o.can_manage === true) : owners[0]
  if (owner?.id != null) params.g_fteam = String(owner.id)
  if (add) {
    const key = id != null && String(id) !== '' ? String(id) : (type === 'cert' && domain ? String(domain) : '')
    if (type && key) params.g_fnew = `${type}:${key}`
  }
  return { tab: 'admin', params }
}

/** Alıcı rolü etiketi — `FORWARD` → "İletim"; diğer roller olduğu gibi (SMTP günlüğü ayrıntısı). */
export function recipientRoleText(t, role) {
  if (!role) return ''
  return String(role).toUpperCase() === FORWARD_ROLE ? t('fwdx.role') : String(role)
}

/** Yeniden gönderim önizlemesi (e-posta / push) satırı iletim mi (`kind: "FORWARD"`). */
export const isForwardRow = (r) => String(r?.kind ?? '').toUpperCase() === FORWARD_ROLE
/** Bildirim günlüğü (`notification_logs`) satırı iletim mi (`recipient_role: "FORWARD"`). */
export const isForwardLog = (l) => String(l?.recipient_role ?? '').toUpperCase() === FORWARD_ROLE
