import { PAGINATION_PRESETS } from '../../hooks/paginationPresets.js'

/**
 * Gönderim penceresi + kanal gönderim günlüğü modeli (R18, 2026-10-18 — kullanıcı onayı).
 *
 * <p><b>Ek Bilgi (CC), C13:</b> sunucu kuralının (`TechErrorReportSender.extraCc`) istemci aynası — istemcinin bilebildiği
 * kısmı: biçim (aynı desen, ≤ 254), en çok {@link MAX_EXTRA_CC} farklı adres, giriş ≤ {@link MAX_EXTRA_CC_INPUT}. Bilgi
 * toplamının 20'yi aşması sunucuda denetlenir (takım Bilgi listesini sunucu bilir) ve alan altında gösterilir.
 *
 * <p><b>Gönderim günlüğü, E22:</b> tür / durum süzgeçleri, etiket anahtarları ve adres anahtarları (`te_tab=sendlog`,
 * `te_slp` / `te_slps` sayfa, `te_slk` tür, `te_sls` durum).
 */

/** Bir gönderime eklenebilecek en çok ek Bilgi adresi — `TechErrorReportSender.MAX_EXTRA_CC`. */
export const MAX_EXTRA_CC = 10
/** Ek Bilgi girişinin üst uzunluğu — `TechErrorReportSender.MAX_EXTRA_CC_INPUT`. */
export const MAX_EXTRA_CC_INPUT = 4000
/** Sunucunun teslim edilebilir adres deseni (`TechErrorReportSender.DELIVERABLE`), tam eşleşme. */
const DELIVERABLE = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]{2,}$/
const SPLIT = /[,;\s]+/

/** Tek adres teslim edilebilir mi (boş değil, ≤ 254 — uzunluk desenden ÖNCE)? */
export function isDeliverable(e) {
  const v = String(e ?? '').trim()
  return v.length > 0 && v.length <= 254 && DELIVERABLE.test(v)
}

/**
 * Ek Bilgi girişini çözer. `{ addresses, error }`: `addresses` = farklı adresler (büyük / küçük harf tekrarı tek), `error`
 * = `null` ya da `{ key, params }` (i18n). Boş giriş = ek yok (hata değil).
 */
export function parseExtraCc(raw) {
  const text = String(raw ?? '')
  if (!text.trim()) return { addresses: [], error: null }
  if (text.length > MAX_EXTRA_CC_INPUT) return { addresses: [], error: { key: 'te.send.ccTooLong', params: [MAX_EXTRA_CC_INPUT, MAX_EXTRA_CC] } }
  const seen = new Set()
  const out = []
  for (const part of text.split(SPLIT)) {
    const e = part.trim()
    if (!e) continue
    if (!isDeliverable(e)) {
      const shown = e.length > 80 ? `${e.slice(0, 80)}…` : e
      return { addresses: [], error: { key: 'te.send.ccInvalid', params: [shown] } }
    }
    const k = e.toLowerCase()
    if (!seen.has(k)) { seen.add(k); out.push(e) }
  }
  if (out.length > MAX_EXTRA_CC) return { addresses: [], error: { key: 'te.send.ccTooMany', params: [MAX_EXTRA_CC, out.length] } }
  return { addresses: out, error: null }
}

// ── Gönderim günlüğü ────────────────────────────────────────────────────────────────────────────────

export const TAB_SENDLOG = 'sendlog'
export const TE_SLP = 'te_slp'
export const TE_SLPS = 'te_slps'
export const TE_SLK = 'te_slk'
export const TE_SLS = 'te_sls'
/** Kanal görünümünden çıkınca silinen günlük adres anahtarları (başka kanala eski süzgeç taşınmasın). */
export const SENDLOG_KEYS = Object.freeze([TE_SLP, TE_SLPS, TE_SLK, TE_SLS])

export const SENDLOG_KINDS = Object.freeze(['all', 'report', 'test', 'approval', 'reminder'])
export const SENDLOG_STATUSES = Object.freeze(['all', 'sent', 'queued', 'skipped', 'failed'])
/** Sunucu sayfası en çok 100 satır — `panel` ön ayarının 100'e kadar olan boyutları (boyut listesi kaynakta yazılmaz). */
export const SENDLOG_SIZE_OPTIONS = Object.freeze(PAGINATION_PRESETS.panel.sizeOptions.filter((n) => n <= 100))

export function normalizeSendLogKind(v) {
  return SENDLOG_KINDS.includes(v) ? v : 'all'
}

export function normalizeSendLogStatus(v) {
  return SENDLOG_STATUSES.includes(v) ? v : 'all'
}

/** Tür etiketleri (tam anahtar — dinamik anahtar yok). */
export const SENDLOG_KIND_KEYS = Object.freeze({
  all: 'te.slog.kind.all', report: 'te.slog.kind.report', test: 'te.slog.kind.test', approval: 'te.slog.kind.approval',
  reminder: 'te.slog.kind.reminder',
})

/** Durum sınıfı etiketleri ("gönderilemedi" anahtarı "undelivered": anahtar adında failed geçmez — ileti değil etiket). */
export const SENDLOG_STATUS_KEYS = Object.freeze({
  all: 'te.slog.st.all', sent: 'te.slog.st.sent', queued: 'te.slog.st.queued', skipped: 'te.slog.st.skipped',
  failed: 'te.slog.st.undelivered',
})

/** Rol etiketleri; bilinmeyen rol "diğer" (ham değer gösterilmez). */
export const SENDLOG_ROLE_KEYS = Object.freeze({
  TEAM: 'te.slog.role.team', EXECUTIVE: 'te.slog.role.executive', SELF: 'te.slog.role.self', APPROVER: 'te.slog.role.approver',
  APPROVER_INFO: 'te.slog.role.approverInfo', REQUESTER: 'te.slog.role.requester', OTHER: 'te.slog.role.other',
})

/** Durum rozetinin tonu (shadcn Badge sınıfı; durum metniyle birlikte — renk tek başına anlam taşımaz). */
export const SENDLOG_STATUS_TONE = Object.freeze({
  sent: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  queued: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  skipped: 'bg-muted text-muted-foreground',
  failed: 'bg-destructive/10 text-destructive',
})

export function sendLogStatusOf(row) {
  return SENDLOG_STATUS_TONE[row?.status] ? row.status : 'failed'
}

export function sendLogKindOf(row) {
  return SENDLOG_KIND_KEYS[row?.kind] && row.kind !== 'all' ? row.kind : 'report'
}

export function sendLogRoleKey(row) {
  return SENDLOG_ROLE_KEYS[row?.role] || SENDLOG_ROLE_KEYS.OTHER
}
