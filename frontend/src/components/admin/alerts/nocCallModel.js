import {
  CheckCircle2, PhoneMissed, Voicemail, PhoneOff, Ban, PhoneForwarded, Phone, MessageSquareText, MessagesSquare, Mail,
} from 'lucide-react'
import { toUtc } from '../../../utils/localDay.js'

/**
 * 7/24 ARAMA KAYDI — saf veri modeli (2026-09-27; `.migration/noc/CONTRACT.md` "Arama kaydı"). Bileşenler yalnız okur.
 * Sunucu sözleşmesi: `GET/POST /api/alerts/{id}/noc-calls`, `DELETE …/noc-calls/{callId}`, `GET …/noc-contacts`;
 * yanıtlar snake_case, gövde camelCase; zamanlar UTC ISO (`yyyy-MM-dd'T'HH:mm:ss`, ek yok). Telefon HİÇ gelmez.
 */

/** Sunucuyla AYNI eşikler (NocCallLogService) — arayüz anında söyler, garanti sunucuda. */
export const NOTE_MAX = 1000
export const NAME_MAX = 200
/** Serbest ad alanında bu kadar ya da daha çok rakam = telefon numarası (yazılmaz). */
export const PHONE_DIGITS = 7
/** Aramanın uyarı açılışından önce olabileceği pay (dk) — sunucu BEFORE_OPEN_TOLERANCE. */
export const BEFORE_OPEN_TOLERANCE_MIN = 10

/**
 * Sonuçlar — sıra formdaki çip sırası (en sık önce). `chip` seçili çipin tonu, `badge` zaman çizelgesi rozeti.
 * Renk tek başına anlam taşımaz: her rozet ikon + metin. Koyu tema karşılıkları `dark:` ile aynı satırda.
 */
export const OUTCOMES = [
  { value: 'REACHED', Icon: CheckCircle2, labelKey: 'nocCall.outcome.REACHED', ink: 'text-success',
    badge: 'border-success/30 bg-success/10 text-success',
    chip: 'data-[state=on]:border-success data-[state=on]:bg-success/10 data-[state=on]:text-success' },
  { value: 'NO_ANSWER', Icon: PhoneMissed, labelKey: 'nocCall.outcome.NO_ANSWER', ink: 'text-amber-700 dark:text-amber-300',
    badge: 'border-amber-500/30 bg-amber-500/10 text-amber-800 dark:text-amber-300',
    chip: 'data-[state=on]:border-amber-500 data-[state=on]:bg-amber-500/10 data-[state=on]:text-amber-800 dark:data-[state=on]:text-amber-300' },
  { value: 'VOICEMAIL', Icon: Voicemail, labelKey: 'nocCall.outcome.VOICEMAIL', ink: 'text-sky-700 dark:text-sky-300',
    badge: 'border-sky-500/30 bg-sky-500/10 text-sky-800 dark:text-sky-300',
    chip: 'data-[state=on]:border-sky-500 data-[state=on]:bg-sky-500/10 data-[state=on]:text-sky-800 dark:data-[state=on]:text-sky-300' },
  { value: 'BUSY', Icon: PhoneOff, labelKey: 'nocCall.outcome.BUSY', ink: 'text-orange-700 dark:text-orange-300',
    badge: 'border-orange-500/30 bg-orange-500/10 text-orange-800 dark:text-orange-300',
    chip: 'data-[state=on]:border-orange-500 data-[state=on]:bg-orange-500/10 data-[state=on]:text-orange-800 dark:data-[state=on]:text-orange-300' },
  { value: 'WRONG_NUMBER', Icon: Ban, labelKey: 'nocCall.outcome.WRONG_NUMBER', ink: 'text-destructive',
    badge: 'border-destructive/30 bg-destructive/10 text-destructive',
    chip: 'data-[state=on]:border-destructive data-[state=on]:bg-destructive/10 data-[state=on]:text-destructive' },
  { value: 'ESCALATED', Icon: PhoneForwarded, labelKey: 'nocCall.outcome.ESCALATED', ink: 'text-violet-700 dark:text-violet-300',
    badge: 'border-violet-500/30 bg-violet-500/10 text-violet-800 dark:text-violet-300',
    chip: 'data-[state=on]:border-violet-500 data-[state=on]:bg-violet-500/10 data-[state=on]:text-violet-800 dark:data-[state=on]:text-violet-300' },
]
export const outcomeMeta = (v) => OUTCOMES.find((o) => o.value === v) ?? null

export const CHANNELS = [
  { value: 'PHONE', Icon: Phone, labelKey: 'nocCall.channel.PHONE' },
  { value: 'SMS', Icon: MessageSquareText, labelKey: 'nocCall.channel.SMS' },
  { value: 'TEAMS', Icon: MessagesSquare, labelKey: 'nocCall.channel.TEAMS' },
  { value: 'EMAIL', Icon: Mail, labelKey: 'nocCall.channel.EMAIL' },
]
export const DEFAULT_CHANNEL = 'PHONE'
export const channelMeta = (v) => CHANNELS.find((c) => c.value === v) ?? CHANNELS[0]

/** Hızlı zaman çipleri — "kaç dakika önce". `custom` tarih-saat seçicisini açar. */
export const QUICK_TIMES = [
  { key: 'now', minutes: 0, labelKey: 'nocCall.time.now' },
  { key: 'm5', minutes: 5, labelKey: 'nocCall.time.m5' },
  { key: 'm15', minutes: 15, labelKey: 'nocCall.time.m15' },
]

const pad = (n) => String(n).padStart(2, '0')

/** Epoch ms → proje UTC ISO (saniye sıfır DEĞİL — "şimdi" tam an). */
export function toIso(ms) {
  const d = new Date(ms)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`
}

/** Proje damgası → epoch ms (UTC kabulü); çözülemezse NaN. */
export function parseMs(iso) {
  if (!iso) return NaN
  return Date.parse(toUtc(String(iso)))
}

/**
 * "Şimdi" seçili mi? (Bilinmeyen kip de `contactedAtMs`'te "şimdi"ye düşer.) Bu kipte istemci saati HİÇ gönderilmez:
 * sunucu kendi saatini kullanır — saati kaymış bir cihaz ne "gelecek" ne "uyarı açılışından önce" 400'ü üretir.
 */
export function isNowTime(time) {
  if (time?.mode === 'custom') return false
  const q = QUICK_TIMES.find((x) => x.key === time?.mode) ?? QUICK_TIMES[0]
  return q.minutes === 0
}

/** Formun zaman seçiminden gönderilecek an (ms). */
export function contactedAtMs(time, nowMs) {
  if (time?.mode === 'custom') return parseMs(time.custom)
  const q = QUICK_TIMES.find((x) => x.key === time?.mode) ?? QUICK_TIMES[0]
  return nowMs - q.minutes * 60_000
}

/** "12 dk önce" — kısa göreli zaman. */
export function relTime(iso, nowMs, t) {
  const ms = parseMs(iso)
  if (Number.isNaN(ms)) return '—'
  const sec = Math.max(0, Math.round((nowMs - ms) / 1000))
  if (sec < 45) return t('nocCall.rel.now')
  const min = Math.round(sec / 60)
  if (min < 60) return t('nocCall.rel.min', min)
  const h = Math.floor(min / 60)
  if (h < 24) return t('nocCall.rel.hour', h)
  const d = Math.floor(h / 24)
  return d === 1 ? t('nocCall.rel.dayOne') : t('nocCall.rel.day', d)
}

/** Saat:dakika (yerel) — telefonda da görünen kesin saat. */
export function clockTime(iso, locale) {
  const ms = parseMs(iso)
  if (Number.isNaN(ms)) return ''
  try { return new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }) } catch { return '' }
}

/** Tam tarih-saat (ipucu). */
export function fullTime(iso, locale) {
  const ms = parseMs(iso)
  if (Number.isNaN(ms)) return ''
  try {
    return new Date(ms).toLocaleString(locale, { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return String(iso) }
}

/** Silme düğmesi şu an gösterilsin mi — sunucunun `can_delete`'i + (kendi kaydında) süre. */
export function canDeleteNow(row, nowMs) {
  if (!row?.can_delete) return false
  if (!row.delete_until) return true   // global yönetici: süresiz
  const until = parseMs(row.delete_until)
  return Number.isNaN(until) ? false : nowMs < until
}

/** Kişi seçicisi grupları: arama listesi (sıralı) · Takım Müdürü · diğer üyeler. */
export function groupContacts(contacts) {
  const list = Array.isArray(contacts) ? contacts : []
  return {
    callList: list.filter((c) => c.source === 'CALL_LIST'),
    manager: list.filter((c) => c.source === 'MANAGER'),
    members: list.filter((c) => c.source === 'MEMBER'),
  }
}

/** Önseçili kişi: arama listesinin ilki; liste yoksa müdür; o da yoksa seçim yok. */
export function defaultPerson(contacts) {
  const g = groupContacts(contacts)
  const first = g.callList[0] ?? g.manager[0] ?? null
  return first ? { kind: 'user', userId: first.user_id, name: first.display_name } : null
}

export function looksLikePhone(s) {
  return (String(s ?? '').match(/[0-9]/g) || []).length >= PHONE_DIGITS
}

/**
 * Form doğrulaması (sunucuyla aynı kurallar) → `{ alan: i18n anahtarı }`; boş nesne = geçerli.
 * form: `{ person, outcome, channel, time, note }`.
 */
export function validateCall(form, { alertCreatedAt, nowMs }) {
  const e = {}
  if (!form?.outcome) e.outcome = 'nocCall.err.outcome'
  const p = form?.person
  if (!p) e.person = 'nocCall.err.person'
  else if (p.kind === 'other') {
    const name = String(p.name ?? '').trim()
    if (!name) e.person = 'nocCall.err.name'
    else if (name.length > NAME_MAX) e.person = 'nocCall.err.nameLong'
    else if (looksLikePhone(name)) e.person = 'nocCall.err.namePhone'
  }
  // "Şimdi": an sunucuda belirlenir (gövdede yok) → istemci saatine dayalı gelecek/açılış kontrolleri onu ENGELLEMEZ
  // (saati geri kalmış cihazda "şimdi" uyarı açılışından önce görünürdü; sunucu kendi saatiyle doğru kaydeder).
  if (!isNowTime(form?.time)) {
    const at = contactedAtMs(form?.time, nowMs)
    if (Number.isNaN(at)) e.time = 'nocCall.err.time'
    else if (at > nowMs + 2 * 60_000) e.time = 'nocCall.err.future'
    else {
      const opened = parseMs(alertCreatedAt)
      if (!Number.isNaN(opened) && at < opened - BEFORE_OPEN_TOLERANCE_MIN * 60_000) e.time = 'nocCall.err.beforeOpen'
    }
  }
  if (String(form?.note ?? '').length > NOTE_MAX) e.note = 'nocCall.err.noteLong'
  return e
}

/** İstek gövdesi (camelCase). "Şimdi" kipinde `contactedAt` YOK — sunucu kendi saatini yazar (bkz. isNowTime). */
export function buildBody(form, nowMs) {
  const p = form.person
  const body = {
    outcome: form.outcome,
    channel: form.channel || DEFAULT_CHANNEL,
  }
  if (!isNowTime(form.time)) body.contactedAt = toIso(Math.min(contactedAtMs(form.time, nowMs), nowMs))
  if (p?.kind === 'user') body.contactedUserId = p.userId
  else body.contactedName = String(p?.name ?? '').trim()
  const note = String(form.note ?? '').trim()
  if (note) body.note = note
  return body
}

/** Liste göstergesi için özet: `{ count, last }` (kayıtlar en yeni önce sıralı gelir). */
export function summarizeCalls(calls) {
  const list = Array.isArray(calls) ? calls : []
  const last = list[0]
  return {
    noc_call_count: list.length,
    noc_last_call: last ? { contacted_name: last.contacted_name, outcome: last.outcome, contacted_at: last.contacted_at } : null,
  }
}
