/**
 * Nöbet çizelgesi — saf model (2026-10-11). Sunucu görünümü (`/api/oncall/teams/{id}`) ↔ form; kaydetme gövdesi;
 * istemci doğrulaması (sunucu aynı kuralları uygular); İstanbul yerel devir anı ↔ UTC ISO (DateTimeField UTC tutar).
 * İstanbul'da yaz saati yok: UTC+3 sabit.
 */

const TR_OFFSET_MS = 3 * 3600 * 1000
const pad = (n) => String(n).padStart(2, '0')

/** "yyyy-MM-ddTHH:mm" (İstanbul) → "yyyy-MM-ddTHH:mm:00" (UTC) — DateTimeField değeri. */
export function anchorToUtc(anchor) {
  if (!anchor || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(anchor)) return ''
  const ms = Date.parse(anchor.slice(0, 16) + ':00Z') - TR_OFFSET_MS
  if (!Number.isFinite(ms)) return ''
  return new Date(ms).toISOString().slice(0, 19)
}

/** UTC ISO → İstanbul yerel "yyyy-MM-ddTHH:mm" (sunucu `anchor` biçimi). */
export function utcToAnchor(utc) {
  if (!utc) return ''
  const ms = Date.parse(String(utc).slice(0, 19) + 'Z') + TR_OFFSET_MS
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}

/** Varsayılan ilk devir: önümüzdeki pazartesi 09:00 İstanbul. */
export function defaultAnchor(now = Date.now()) {
  const local = new Date(now + TR_OFFSET_MS)
  const dow = local.getUTCDay()   // 0 = pazar
  const add = ((8 - dow) % 7) || 7
  const d = new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() + add, 9, 0))
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T09:00`
}

/** Sunucu görünümü → form. Üye kimlikleri olduğu gibi (opak ya da sayısal) — istemci sayıya çevirmez. */
export function toForm(view) {
  return {
    enabled: !!view?.enabled,
    rotation: view?.rotation === 'DAILY' ? 'DAILY' : 'WEEKLY',
    anchor: view?.anchor || defaultAnchor(),
    notify: !!view?.notify_oncall,
    members: Array.isArray(view?.members) ? view.members.map((m) => ({ id: m.user_id, name: m.name || m.username || '—', active: m.active !== false, missing: !!m.missing })) : [],
    notice: noticeForm(view?.weekly_notice),
  }
}

// ── Haftalık nöbet bildirimi (2026-11-15) ────────────────────────────────────────────────

export const NOTICE_DEFAULT_DAY = 1
export const NOTICE_DEFAULT_TIME = '08:30'
export const NOTICE_DAYS = [1, 2, 3, 4, 5, 6, 7]
/** Sunucuyla aynı: "HH:mm", 00:00–23:59 (OnCallWeeklyNoticeSlots.validTime). */
export const NOTICE_TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

/**
 * Sunucunun `weekly_notice` nesnesi → form parçası. Alan yoksa (eski sunucu) `supported: false` — pencere bölümü
 * çizmez ve kaydetme gövdesine `weekly_notice` girmez.
 */
export function noticeForm(wn) {
  if (!wn || typeof wn !== 'object') {
    return { supported: false, enabled: false, mail: true, push: true, day: NOTICE_DEFAULT_DAY, time: NOTICE_DEFAULT_TIME }
  }
  const day = Number.isInteger(wn.day) && wn.day >= 1 && wn.day <= 7 ? wn.day : NOTICE_DEFAULT_DAY
  const time = typeof wn.time === 'string' && NOTICE_TIME_RE.test(wn.time) ? wn.time : NOTICE_DEFAULT_TIME
  return { supported: true, enabled: !!wn.enabled, mail: wn.mail !== false, push: wn.push !== false, day, time }
}

const NOTICE_KEYS = ['enabled', 'mail', 'push', 'day', 'time']

/** Bildirim ayarında YALNIZ değişen alanlar (sunucu yalnız gönderileni yazar). Desteklenmiyorsa boş. */
export function noticeChanges(form, base) {
  const out = {}
  if (!form?.notice?.supported || !base?.notice) return out
  for (const k of NOTICE_KEYS) if (form.notice[k] !== base.notice[k]) out[k] = form.notice[k]
  return out
}

/** Çizelge (bildirim hariç) değişti mi. */
export function scheduleDirty(form, base) {
  const shape = (f) => ({ ...buildPayload(f), members: f.members.map((m) => (m.missing ? '∅' : String(m.id))) })
  return JSON.stringify(shape(form)) !== JSON.stringify(shape(base))
}

/**
 * Kaydetme gövdesi: çizelge değiştiyse (ya da çizelge hiç kaydedilmemişse) tam çizelge + değişen bildirim alanları;
 * yalnız bildirim değiştiyse YALNIZ `weekly_notice` (sunucu çizelgeye dokunmaz — eşzamanlı çizelge kaydı ezilmez).
 */
export function buildSaveBody(form, base, { configured = true } = {}) {
  const wn = noticeChanges(form, base)
  const hasNotice = Object.keys(wn).length > 0
  if (!scheduleDirty(form, base) && configured && hasNotice) return { weekly_notice: wn }
  const body = buildPayload(form)
  if (hasNotice) body.weekly_notice = wn
  return body
}

/** Bildirim açılabilir mi: çizelge açık ve en az bir (silinmemiş) nöbetçi var. */
export function noticeUsable(form) {
  return !!form?.enabled && form.members.some((m) => !m.missing)
}

/** Geçmişteki "sıradaki gönderim" = telafi penceresi açık, az sonra gönderilecek. */
export function noticeNextState(next, now = Date.now()) {
  if (!next) return null
  const ms = Date.parse(String(next).slice(0, 19) + 'Z')
  if (!Number.isFinite(ms)) return null
  return ms <= now ? 'soon' : 'scheduled'
}

/** Son sonuç rozet tonu. Bilinmeyen / gönderilmedi / kuyrukta asla yeşil değil. */
export function noticeStatusTone(status) {
  switch (status) {
    case 'SENT': return 'success'
    case 'PARTIAL': return 'warning'
    case 'FAILED': return 'danger'
    case 'UNKNOWN': return 'warning'
    case 'SENDING': return 'info'
    case 'QUEUED': return 'info'
    default: return 'muted'
  }
}

const ISTANBUL_OFFSET_MS = 3 * 3600_000   // Türkiye saati sabit UTC+3 (sunucu OnCallRotation.TZ ile aynı)
const pad2 = (n) => String(n).padStart(2, '0')

/**
 * Sunucunun UTC anı ("yyyy-MM-ddTHH:mm:ss", Z'siz) → Türkiye saati parçaları (tarayıcının saat diliminden bağımsız;
 * ayar "Türkiye saati" dediği için gösterim de öyle). Gün 1 (Pazartesi) … 7 (Pazar). Okunamazsa null.
 */
export function istanbulParts(iso) {
  if (!iso) return null
  const ms = Date.parse(String(iso).slice(0, 19) + 'Z')
  if (!Number.isFinite(ms)) return null
  const d = new Date(ms + ISTANBUL_OFFSET_MS)
  const dow = d.getUTCDay() === 0 ? 7 : d.getUTCDay()
  return { dow, date: `${pad2(d.getUTCDate())}.${pad2(d.getUTCMonth() + 1)}.${d.getUTCFullYear()}`,
    time: `${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}` }
}

/** Hafta anahtarı "2026-11-09" → "09.11.2026" (okunamazsa null). */
export function weekKeyText(key) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key || ''))
  return m ? `${m[3]}.${m[2]}.${m[1]}` : null
}

/**
 * Son sonucun ekran görünümü (sunucu alanlarından; eski sunucunun eksik alanı "bilinmiyor", asla yanlış iddia değil):
 * bayat SENDING → UNKNOWN (+ INTERRUPTED nedeni); e-postası kuyrukta olan SENT → QUEUED ("gönderildi" demez);
 * `retry` = pending (aynı gün yeniden denenecek) | done (yeniden denenmeyecek) | null (uygulanmaz / eski sunucu);
 * `push.kind` = off | people (+ arg adet) | notSent | none.
 */
export function noticeLastView(last) {
  if (!last) return null
  let status = NOTICE_STATUSES.includes(last.status) ? last.status : null
  let reason = NOTICE_REASONS.includes(last.reason) ? last.reason : null
  if (status === 'SENDING' && last.stale === true) {
    status = 'UNKNOWN'
    reason = reason || 'INTERRUPTED'
  }
  if (status === 'SENT' && last.mail === 'queued') status = 'QUEUED'
  const retriable = status === 'FAILED' || (status === 'PARTIAL' && last.mail === 'failed')
  const retry = !retriable || typeof last.retry !== 'boolean' ? null : last.retry ? 'pending' : 'done'
  const count = Number.isFinite(last.push_count) ? last.push_count : null
  let push = null
  if (last.push === 'off') push = { kind: 'off' }
  else if (last.push === 'failed') push = { kind: 'notSent' }
  else if (last.push === 'skipped' || (last.push === 'delivered' && count === 0)) push = { kind: 'none' }
  else if (count != null && (last.push === 'delivered' || last.push == null)) push = { kind: 'people', arg: String(count) }
  return { status, reason, retry, push, attempts: last.attempts, maxAttempts: last.max_attempts }
}

/**
 * Bildirim değişikliği gönderildi ama sunucu tanımadı (eski pod, dağıtım sürüyor): başarı yanıtında `weekly_notice`
 * yok, ya da YALNIZ-bildirim gövdesine bildirim dışı bir alan hatası geldi (eski pod çizelge kaydı sanıp `anchor` ister).
 * O zaman sessiz kayıp / anlamsız "ilk devir" hatası yerine "bu sürüm desteklemiyor" denir.
 */
export function noticeNotSupported(body, res) {
  if (!body || typeof body !== 'object' || !('weekly_notice' in body)) return false
  if (res?.success) return !res.data || res.data.weekly_notice == null || typeof res.data.weekly_notice !== 'object'
  const noticeOnly = Object.keys(body).length === 1
  return noticeOnly && !!res?.field && !String(res.field).startsWith('weekly_notice')
}

export const NOTICE_STATUSES = ['SENT', 'PARTIAL', 'SKIPPED', 'FAILED', 'UNKNOWN', 'SENDING']
export const NOTICE_REASONS = ['NO_ONCALL', 'MAINTENANCE', 'CHANNELS_OFF', 'NO_TEAM', 'TEAM_INACTIVE', 'MISSED', 'NO_RECIPIENT', 'INTERRUPTED', 'ERROR']

/**
 * Kaydetme gövdesi (PUT). SİLİNMİŞ hesaplar (`missing`) GİTMEZ (20.165.1, UIA-A5 / K11): silinen bir kişi hiç nöbetçi
 * olamaz; eski sunucu bilinmeyen kimliği reddediyordu ("listedeki bir kişi bulunamadı — sayfayı yenileyin", yenilemek de
 * bir şey değiştirmiyordu), yenisi sessizce düşürür. Kapsamlı görüntüleyicide silinmiş kişinin kimliği zaten null'dır.
 */
export function buildPayload(form) {
  return {
    enabled: !!form.enabled,
    rotation: form.rotation === 'DAILY' ? 'DAILY' : 'WEEKLY',
    anchor: form.anchor,
    notify_oncall: !!form.notify,
    members: form.members.filter((m) => !m.missing && m.id != null).map((m) => m.id),
  }
}

/**
 * Form değişti mi (kaydet düğmesi). Silinmiş satırı listeden çıkarmak da değişikliktir (kaydedince sunucudan düşer).
 * Haftalık bildirim ayarındaki değişiklik de sayılır (2026-11-15).
 */
export function isDirty(form, base) {
  return scheduleDirty(form, base) || Object.keys(noticeChanges(form, base)).length > 0
}

/** Listede silinmiş hesap var mı (pencerede uyarı bandı). */
export function hasMissingMembers(form) {
  return !!form?.members?.some((m) => m.missing)
}

export const MAX_MEMBERS = 50

/** İstemci doğrulaması → { alan: i18n anahtarı } (boş = geçerli). */
export function validate(form) {
  const errs = {}
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(form.anchor || '')) errs.anchor = 'oncall.err.anchor'
  // Silinmiş hesaplar sayılmaz: gövdeden düşer, açık çizelge yalnız onlarla kalırsa sunucu 400 verir (K11)
  if (form.enabled && form.members.filter((m) => !m.missing).length === 0) errs.members = 'oncall.err.noMembers'
  if (form.members.length > MAX_MEMBERS) errs.members = 'oncall.err.tooMany'
  const n = form.notice
  if (n?.supported) {
    if (!NOTICE_TIME_RE.test(n.time || '')) errs['weekly_notice.time'] = 'oncall.wn.err.time'
    if (!NOTICE_DAYS.includes(n.day)) errs['weekly_notice.day'] = 'oncall.wn.err.day'
    if (n.enabled && !n.mail && !n.push) errs['weekly_notice.mail'] = 'oncall.wn.err.channel'
  }
  return errs
}

/** Sıradaki üyeyi bir yukarı / aşağı taşı (yeni dizi). */
export function move(list, index, delta) {
  const j = index + delta
  if (index < 0 || index >= list.length || j < 0 || j >= list.length) return list
  const out = list.slice()
  const [x] = out.splice(index, 1)
  out.splice(j, 0, x)
  return out
}

/** Aynı kişiyi iki kez ekleme (kimlik karşılaştırması string — opak / sayısal fark etmez). */
export function addMember(list, person) {
  if (!person || list.some((m) => String(m.id) === String(person.id))) return list
  return [...list, person]
}

/** Yer değiştirme formu doğrulaması. */
export function validateOverride(o) {
  const errs = {}
  if (o.userId == null || o.userId === '') errs.user = 'oncall.err.ovrUser'
  if (!o.start) errs.start = 'oncall.err.ovrStart'
  if (!o.end) errs.end = 'oncall.err.ovrEnd'
  else if (o.start && Date.parse(o.end + 'Z') <= Date.parse(o.start + 'Z')) errs.end = 'oncall.err.ovrOrder'
  return errs
}
