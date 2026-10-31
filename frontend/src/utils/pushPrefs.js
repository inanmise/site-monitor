// Kişisel push tercihleri + push geçmişi — saf yardımcılar (2026-10-04, onaylı öneriler 3/4/5).
// Sunucu sözleşmesi: GET/PUT /api/me/push-preferences, POST /api/me/push-snooze, GET /api/me/push-history (MyPushController).
import { toUtc, localDayKey } from './localDay.js'

/** İzleme aileleri — backend MonitorTypeCatalog.ORDER ile aynı sıra (sunucu `available_families` verirse o kazanır). */
export const PUSH_FAMILIES = ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted', 'dbquery']
/** En düşük seviye seçenekleri — 'ALL' = tercih yok (sunucuda null). */
export const PUSH_LEVELS = ['ALL', 'HIGH', 'CRITICAL']
export const PUSH_LANGS = ['tr', 'en']
export const SNOOZE_PRESETS = ['1h', '4h', 'tomorrow']

/** Sunucu yanıtı → form değeri. Aile listesi null = hepsi (formda hepsi seçili). */
export function prefsToForm(p, families = PUSH_FAMILIES) {
  const avail = Array.isArray(p?.available_families) && p.available_families.length ? p.available_families : families
  return {
    level: p?.min_level === 'HIGH' || p?.min_level === 'CRITICAL' ? p.min_level : 'ALL',
    families: Array.isArray(p?.families) && p.families.length ? avail.filter((f) => p.families.includes(f)) : [...avail],
    lang: p?.lang === 'en' ? 'en' : 'tr',
  }
}

/** Form → PUT gövdesi. Bütün aileler seçiliyse null (= tercih yok; sunucu da böyle normalize eder). */
export function formToBody(form, families = PUSH_FAMILIES) {
  const all = families.every((f) => form.families.includes(f))
  return {
    min_level: form.level === 'ALL' ? '' : form.level,
    families: all ? null : families.filter((f) => form.families.includes(f)),
    lang: form.lang,
  }
}

export function formEqual(a, b) {
  if (!a || !b) return a === b
  return a.level === b.level && a.lang === b.lang
    && a.families.length === b.families.length && a.families.every((f) => b.families.includes(f))
}

/** Tercih yok mu (tümü varsayılan)? Kart başlığı özeti için. */
export function isDefaultForm(form, families = PUSH_FAMILIES) {
  return form.level === 'ALL' && form.lang === 'tr' && families.every((f) => form.families.includes(f))
}

/** Push gönderilmeme nedeni — `push.reason.<KOD>`; bilinmeyen kod HAM gösterilir (t() ham anahtar basmasın). */
export function pushReasonLabel(code, t) {
  if (!code) return ''
  const key = `push.reason.${code}`
  const v = t(key)
  return v === key ? code : v
}

/**
 * Aynı kişi satırının hesap notu (2026-10-21): SKIPPED_SAME_PERSON → kararın verildiği hesap; SKIPPED_SAME_PERSON_EXCLUDED →
 * bildirimden çıkarılan hesap. Metin gönderildiğini iddia etmez (inceleme): karar o hesap için verildi.
 */
export function samePersonText(t, status, username) {
  if (!username) return ''
  return status === 'SKIPPED_SAME_PERSON_EXCLUDED' ? t('push.samePersonExcludedOf', username) : t('push.samePersonOf', username)
}

/** Push tetiği etiketi — `userpush.trigger.<T>`; bilinmeyen ham. */
export function pushTriggerLabel(trigger, t) {
  if (!trigger) return '—'
  const key = `userpush.trigger.${trigger}`
  const v = t(key)
  return v === key ? trigger : v
}

/**
 * Aile olmayan push türleri → etiket anahtarı (2026-10-18, inceleme: "Push bildirimlerim"de ham "TECH_ERROR_REPORT" rozeti).
 * Anahtar adları "error" İÇERMEZ — `errorMessageQuality` kapısı adı error'la eşleşen her anahtarı hata iletisi sayar.
 * CERTIFICATE: takım bildirimlerinin (zayıf algoritma) ve varsayılan satırın türü = sertifika ailesi.
 */
export const PUSH_TYPE_ALIASES = {
  certificate: 'cert',
  tech_error_report: 'channel_reports',
  weekly_report: 'weekly_report',
  storm: 'storm',
}

/**
 * Push türü etiketi — izleme ailesi (`userpush.type.<aile>`) ya da aile olmayan bildirim türü (`PUSH_TYPE_ALIASES`).
 * Bilinmeyen tür ASLA ham gösterilmez: "Diğer bildirim" (`userpush.type.other`). "Push bildirimlerim" ve yönetici push
 * günlüğü (TypeBadge) aynı etiketi kullanır.
 */
export function familyLabel(type, t) {
  if (!type) return ''
  const low = String(type).trim().toLowerCase()
  const key = `userpush.type.${PUSH_TYPE_ALIASES[low] || low}`
  const v = t(key)
  return v === key || !v ? t('userpush.type.other') : v
}

/** UTC damga → yerel "HH:mm" (bugün değilse tarih de ayrı döner). */
export function snoozeParts(until, locale, now = new Date()) {
  if (!until) return null
  const d = new Date(toUtc(until))
  if (Number.isNaN(d.getTime())) return null
  const time = d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
  const sameDay = localDayKey(d.toISOString()) === localDayKey(now.toISOString())
  const date = sameDay ? null : d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })
  return { time, date }
}

/** Satırın görünen zamanı (gönderildiyse gönderim, yoksa oluşturma). */
export function rowTime(row) {
  return row?.at || row?.sent_at || row?.created_at || null
}

/**
 * Geçmiş satırlarını YEREL güne göre gruplar (sıra korunur: sunucu yeni → eski verir).
 * @returns {{ day: string, rows: object[] }[]}
 */
export function groupByDay(rows) {
  const out = []
  let cur = null
  for (const r of rows || []) {
    const day = localDayKey(rowTime(r)) || '—'
    if (!cur || cur.day !== day) { cur = { day, rows: [] }; out.push(cur) }
    cur.rows.push(r)
  }
  return out
}

/** Satırın kullanıcıya gösterilen durumu: geldi / kuyrukta / özetlendi / gelmedi. */
export function rowStatus(row) {
  if (row?.kind === 'sent') return 'sent'
  if (row?.kind === 'pending') return 'pending'
  if (row?.reason === 'RATE_LIMITED' && row?.summarized_into) return 'summarized'
  return 'not_sent'
}
