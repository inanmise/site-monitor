// Haftalık erişilebilirlik e-postası gönderim logu — saf model (2026-10-01 yeniden tasarım). Sunucu satırı:
// { id, sent_at (UTC, "Z"siz), team, to, cc, subject, status, trigger }. `status`: SENT · "FAILED: <neden>" ·
// NO_RECIPIENT (ve benzeri atlamalar). `trigger`: WEEKLY_AVAILABILITY (zamanlanmış) · WEEKLY_AVAILABILITY_TEST.
import { parseUtc } from '../../../utils/incidentMeta.js'
import { toCsv } from '../../../utils/csvExport.js'

export const WA_TEST = 'WEEKLY_AVAILABILITY_TEST'
/** Sunucudan istenebilecek kayıt sayıları (sunucu tavanı 500). */
export const WA_LIMITS = [100, 250, 500]
export const WA_KINDS = ['SENT', 'FAILED', 'SKIPPED']
const IST_OFFSET_MS = 3 * 3600e3   // Europe/Istanbul UTC+3, yaz saati yok

/** Gönderim durumu → SENT | FAILED | SKIPPED | UNKNOWN. */
export function waKind(status) {
  if (!status) return 'UNKNOWN'
  if (status === 'SENT') return 'SENT'
  if (String(status).startsWith('FAILED')) return 'FAILED'
  return 'SKIPPED'   // NO_RECIPIENT vb.
}
export const isTest = (row) => row?.trigger === WA_TEST

/** Başarısız gönderimin nedeni ("FAILED: relay denied" → "relay denied"); başarısız değilse null. */
export function waError(row) {
  const s = String(row?.status || '')
  if (!s.startsWith('FAILED')) return null
  return s.replace(/^FAILED:?\s*/, '') || s
}

/** Virgül / noktalı virgülle ayrılmış adres listesi → dizi (boşlar atılır). */
export function splitAddrs(s) {
  return String(s || '').split(/[,;]/).map((x) => x.trim()).filter(Boolean)
}

/** UTC zamanın İstanbul takvim günü (YYYY-MM-DD) — gruplama anahtarı. */
export function istDay(sentAt) {
  const d = parseUtc(sentAt)
  return d ? new Date(d.getTime() + IST_OFFSET_MS).toISOString().slice(0, 10) : ''
}

/**
 * Süzgeç (saf): kind (SENT|FAILED|SKIPPED|''), type ('scheduled'|'test'|''), team (ad, ''=hepsi), q (takım / alıcı /
 * CC / konu içinde, büyük-küçük harf ve Türkçe I/İ duyarsız).
 */
export function filterWa(rows, { kind = '', type = '', team = '', q = '' } = {}) {
  const needle = String(q || '').trim().toLocaleLowerCase('tr')
  return (rows || []).filter((r) => {
    if (kind && waKind(r.status) !== kind) return false
    if (type === 'test' && !isTest(r)) return false
    if (type === 'scheduled' && isTest(r)) return false
    if (team && (r.team || '') !== team) return false
    if (needle) {
      const hay = [r.team, r.to, r.cc, r.subject].filter(Boolean).join(' ').toLocaleLowerCase('tr')
      if (!hay.includes(needle)) return false
    }
    return true
  })
}

/** Sayımlar + başarı oranı (gönderildi / (gönderildi + başarısız); atlananlar orana girmez). */
export function waCounts(rows) {
  const c = { total: 0, SENT: 0, FAILED: 0, SKIPPED: 0, UNKNOWN: 0, test: 0 }
  for (const r of rows || []) {
    c.total += 1
    c[waKind(r.status)] += 1
    if (isTest(r)) c.test += 1
  }
  const attempted = c.SENT + c.FAILED
  c.rate = attempted ? Math.round((c.SENT * 1000) / attempted) / 10 : null
  return c
}

/** Satırları İstanbul gününe göre grupla (girdi sırası korunur — sunucu en yeniyi üstte verir). */
export function groupByDay(rows) {
  const out = []
  const byKey = new Map()
  for (const r of rows || []) {
    const key = istDay(r.sent_at)
    let g = byKey.get(key)
    if (!g) { g = { key, rows: [] }; byKey.set(key, g); out.push(g) }
    g.rows.push(r)
  }
  return out.map((g) => ({ ...g, counts: waCounts(g.rows) }))
}

/**
 * Son ZAMANLANMIŞ çalışma: en yeni zamanlanmış gönderimin günündeki zamanlanmış satırlar (test hariç) — "kaç takıma
 * gitti, kaçı düştü" özeti. Zamanlanmış gönderim yoksa null.
 */
export function lastRun(rows) {
  const scheduled = (rows || []).filter((r) => !isTest(r))
  if (!scheduled.length) return null
  const day = istDay(scheduled[0].sent_at)
  const same = scheduled.filter((r) => istDay(r.sent_at) === day)
  return { day, at: scheduled[0].sent_at, rows: same, counts: waCounts(same) }
}

/** Takım seçenekleri (alfabetik, Türkçe). */
export function teamsOf(rows) {
  return [...new Set((rows || []).map((r) => r.team).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'tr'))
}

/** CSV — görünen (süzülmüş) satırların tamamı. */
export function waCsv(rows, { t, fmt }) {
  const head = [t('health.smtpLogDate'), t('waLogs.colTeam'), t('waLogs.colRecipients'), 'CC', t('health.smtpLogSubject'),
    t('waLogs.colType'), t('health.smtpLogStatus'), t('waLogs.csv.error')]
  const kindLabel = { SENT: t('health.statusSent'), FAILED: t('health.statusFailed'), SKIPPED: t('waLogs.kind.SKIPPED'), UNKNOWN: t('health.statusUnknown') }
  const body = (rows || []).map((r) => [fmt(r.sent_at), r.team || '', r.to || '', r.cc || '', r.subject || '',
    isTest(r) ? t('waLogs.test') : t('waLogs.scheduled'), kindLabel[waKind(r.status)], waError(r) || (waKind(r.status) === 'SKIPPED' ? r.status : '')])
  return toCsv(head, body)
}
