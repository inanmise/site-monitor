/**
 * Sistem Bakımı (Ayarlar → Platform) — SAF yardımcılar (2026-10-02, kullanıcı kararı). React yok: form varsayılanları,
 * İstanbul yerel tarih/saat dönüşümü, istemci doğrulaması (sunucu aynı kuralları ayrıca uygular — garanti sunucuda),
 * gönderim gövdesi ve durum → ton eşlemesi.
 *
 * Saat dilimi: form değerleri İstanbul YEREL saatidir ("yyyy-MM-dd" + "HH:mm"), sunucuya `start_local` / `end_local`
 * olarak gider; tarayıcının kendi saat dilimi KULLANILMAZ (yurt dışından bakan yönetici de İstanbul saatini girer).
 * Türkiye 2016'dan beri sabit UTC+3 (yaz saati yok) — dönüşüm sabit ofsetle.
 */

const OFFSET = '+03:00'
const ZONE = 'Europe/Istanbul'

/** Sunucu seçenekleri gelmeden önceki yedek (SystemMaintenanceService ile aynı). */
export const DEFAULT_OPTIONS = Object.freeze({
  warn_minutes: [5, 10, 15, 30],
  announce_hours: [0, 1, 6, 24, 48],
  countdown_minutes: [0, 1, 2, 5, 10, 15, 30],
  duration_minutes: [15, 30, 60, 90, 120, 240],
  extend_minutes: [15, 30, 60],
  default_warn_minutes: 10,
  default_announce_hours: 24,
  max_duration_hours: 72,
})

/** Durum → ton (Badge) + i18n anahtarı. `none` = bakım yok. */
export const PHASE_META = Object.freeze({
  none: { tone: 'outline', key: 'sysmaint.phase.none' },
  planned: { tone: 'secondary', key: 'sysmaint.phase.planned' },
  announced: { tone: 'secondary', key: 'sysmaint.phase.announced' },
  warning: { tone: 'warning', key: 'sysmaint.phase.warning' },
  active: { tone: 'destructive', key: 'sysmaint.phase.active' },
  ended: { tone: 'outline', key: 'sysmaint.phase.ended' },
  cancelled: { tone: 'outline', key: 'sysmaint.phase.cancelled' },
})

export function phaseMeta(phase) {
  return PHASE_META[phase] || PHASE_META.none
}

/** ms → İstanbul yerel {date: 'yyyy-MM-dd', time: 'HH:mm'}. */
export function toIstanbulParts(msOrIso) {
  const t = typeof msOrIso === 'number' ? msOrIso : Date.parse(msOrIso || '')
  if (!Number.isFinite(t)) return { date: '', time: '' }
  const f = new Intl.DateTimeFormat('en-GB', {
    timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  })
  const p = Object.fromEntries(f.formatToParts(new Date(t)).map((x) => [x.type, x.value]))
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}` }
}

/** İstanbul yerel tarih + saat → ms (geçersizse null). */
export function istanbulToMs(date, time) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '') || !/^\d{2}:\d{2}$/.test(time || '')) return null
  const t = Date.parse(`${date}T${time}:00${OFFSET}`)
  return Number.isFinite(t) ? t : null
}

/** Yeni plan formu: sunucu saatinden 1 saat sonra, bir sonraki yarım saate yuvarlanmış başlangıç; süre 1 saat. */
export function defaultPlanForm(serverNowMs, options = DEFAULT_OPTIONS) {
  const base = (Number.isFinite(serverNowMs) ? serverNowMs : Date.now()) + 60 * 60_000
  const half = 30 * 60_000
  const start = Math.ceil(base / half) * half
  const s = toIstanbulParts(start), e = toIstanbulParts(start + 60 * 60_000)
  return {
    startDate: s.date, startTime: s.time, endDate: e.date, endTime: e.time,
    warnMinutes: options.default_warn_minutes ?? 10,
    announceHours: options.default_announce_hours ?? 24,
    mute: false,
    messageTr: '', messageEn: '', contact: '',
    emailAllUsers: false, emailTeamIds: [], emailCorrections: true, emailOnEnd: true,
  }
}

/** E-posta alıcısı seçildi mi (tüm aktif kullanıcılar ya da en az bir takım) — "bitince e-posta" seçeneği buna bağlı. */
export function hasRecipients(form) {
  return !!form?.emailAllUsers || (form?.emailTeamIds || []).length > 0
}

/** Var olan pencereden düzenleme formu. */
export function formFromWindow(w) {
  const s = toIstanbulParts(w?.start_at), e = toIstanbulParts(w?.end_at)
  return {
    startDate: s.date, startTime: s.time, endDate: e.date, endTime: e.time,
    warnMinutes: w?.warn_minutes ?? 10,
    announceHours: w?.announce_hours ?? 24,
    mute: !!w?.mute_notifications,
    messageTr: w?.message_tr || '', messageEn: w?.message_en || '', contact: w?.contact || '',
    emailAllUsers: !!w?.email_all_users,
    emailTeamIds: Array.isArray(w?.email_team_ids) ? w.email_team_ids : [],
    emailCorrections: w?.email_corrections !== false,
    emailOnEnd: w?.email_on_end !== false,
  }
}

/**
 * "Hemen bakıma al" formu. E-posta alıcıları burada YALNIZ bakım bitince giden "tamamlandı" e-postası içindir (hemen
 * bakımda duyuru/düzeltme e-postası yok — 2026-10-02).
 */
export function defaultStartNowForm() {
  return { countdownMinutes: 5, durationMinutes: 60, mute: false, messageTr: '', messageEn: '', contact: '',
    emailAllUsers: false, emailTeamIds: [], emailOnEnd: true }
}

/**
 * İstemci doğrulaması — alan → i18n anahtarı (ya da false). Sunucu aynı kuralları uygular (alan yanında 400 + field).
 * `existing` = çakışma denetimi için öteki açık pencereler (kendisi hariç).
 */
export function validatePlan(form, serverNowMs, existing = [], selfId = null, maxHours = 72) {
  const start = istanbulToMs(form.startDate, form.startTime)
  const end = istanbulToMs(form.endDate, form.endTime)
  const errs = {}
  if (start == null) errs.start_local = 'sysmaint.err.startRequired'
  else if (start < serverNowMs + 60_000) errs.start_local = 'sysmaint.err.startPast'
  if (end == null) errs.end_local = 'sysmaint.err.endRequired'
  else if (start != null && end <= start) errs.end_local = 'sysmaint.err.endBeforeStart'
  else if (start != null && end - start < 5 * 60_000) errs.end_local = 'sysmaint.err.tooShort'
  else if (start != null && end - start > maxHours * 3_600_000) errs.end_local = 'sysmaint.err.tooLong'
  if (!errs.start_local && !errs.end_local) {
    for (const w of existing) {
      if (selfId != null && w.id === selfId) continue
      const ws = Date.parse(w.start_at || ''), we = Date.parse(w.end_at || '')
      if (Number.isFinite(ws) && Number.isFinite(we) && start < we && ws < end) {
        errs.start_local = 'sysmaint.err.overlap'
        break
      }
    }
  }
  if ((form.messageTr || '').length > 1000) errs.message_tr = 'sysmaint.err.tooLongText'
  if ((form.messageEn || '').length > 1000) errs.message_en = 'sysmaint.err.tooLongText'
  if ((form.contact || '').length > 300) errs.contact = 'sysmaint.err.tooLongText'
  return errs
}

/** Plan / düzenle gövdesi (sunucu sözleşmesi). */
export function planPayload(form) {
  return {
    start_local: `${form.startDate}T${form.startTime}`,
    end_local: `${form.endDate}T${form.endTime}`,
    warn_minutes: Number(form.warnMinutes),
    announce_hours: Number(form.announceHours),
    mute_notifications: !!form.mute,
    message_tr: form.messageTr || '',
    message_en: form.messageEn || '',
    contact: form.contact || '',
    email_all_users: !!form.emailAllUsers,
    email_team_ids: (form.emailTeamIds || []).map(Number),
    email_corrections: !!form.emailCorrections,
    email_on_end: !!form.emailOnEnd,
  }
}

export function startNowPayload(form) {
  return {
    countdown_minutes: Number(form.countdownMinutes),
    duration_minutes: Number(form.durationMinutes),
    mute_notifications: !!form.mute,
    message_tr: form.messageTr || '',
    message_en: form.messageEn || '',
    contact: form.contact || '',
    email_all_users: !!form.emailAllUsers,
    email_team_ids: (form.emailTeamIds || []).map(Number),
    email_on_end: !!form.emailOnEnd,
  }
}

/** Sunucunun alan adı → formdaki hata anahtarı (400 + field). */
export function fieldOf(serverField) {
  if (serverField === 'start_local' || serverField === 'start_at') return 'start_local'
  if (serverField === 'end_local' || serverField === 'end_at' || serverField === 'end') return 'end_local'
  return serverField || null
}

/** Süre (ms) → {h, m} — "X sa Y dk" metinleri için. */
export function hm(msLeft) {
  const min = Math.max(0, Math.round((msLeft || 0) / 60_000))
  return { h: Math.floor(min / 60), m: min % 60 }
}
