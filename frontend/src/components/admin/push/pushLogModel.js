// Webhook Push Gönderim Logu — sabitler ve saf yardımcılar (2026-10-01 yeniden tasarımında PushLogView'dan ayrıldı).
import { CheckCircle, XCircle, MinusCircle, Clock, Ban, HelpCircle } from 'lucide-react'
import { readUrlParam } from '../../../hooks/useUrlQuerySync.js'

export const RANGES = ['24h', '7d', '30d']
export const STATUSES = ['SENT', 'FAILED', 'PENDING', 'BLOCKED', 'SKIPPED']
/** Push tetikleyici sözlüğü (UserPushService): mevcut `userpush.trigger.*` etiketleri; bilinmeyen ham gösterilir. */
export const TRIGGERS = ['OPEN', 'ESCALATION', 'RE_ALERT', 'RESOLVE', 'RESEND', 'TEST', 'WEAK_ALGO', 'WEEKLY_REPORT']
export const LEVELS = ['CRITICAL', 'HIGH', 'WARNING', 'INFO']
export const ERROR_CLASSES = ['AUTH', 'NOT_FOUND', 'RATE', 'SERVER', 'CLIENT', 'CONFIG', 'TIMEOUT', 'CONNECT', 'OTHER']
/** Sunucunun kabul ettiği sıralama alanları (PushLogController). */
export const SORT_FIELDS = ['at', 'team', 'user', 'monitor', 'level', 'trigger', 'status']
export const REFRESH_MS = 60_000
export const DEFAULT_SORT = 'at,desc'

export const STATUS_META = {
  SENT:    { Icon: CheckCircle, tone: 'success', key: 'health.statusSent' },
  FAILED:  { Icon: XCircle,     tone: 'danger',  key: 'health.statusFailed' },
  PENDING: { Icon: Clock,       tone: 'muted',   key: 'pl.statusPending' },
  BLOCKED: { Icon: Ban,         tone: 'danger',  key: 'pl.statusBlocked' },
  SKIPPED: { Icon: MinusCircle, tone: 'muted',   key: 'health.statusSkipped' },
  UNKNOWN: { Icon: HelpCircle,  tone: 'muted',   key: 'health.statusUnknown' },
}
/** Grafik renkleri (gönderildi / başarısız / kuyrukta / atlandı). */
export const SERIES_COLORS = { sent: '#059669', failed: '#dc2626', pending: '#d97706', skipped: '#a1a1aa' }

export function triggerLabel(trigger, t) {
  if (!trigger) return '—'
  const k = `userpush.trigger.${trigger}`
  const v = t(k)
  return v === k ? trigger : v
}

/** Durum etiketi — anahtarlar AÇIKÇA yazılı (kullanılmayan anahtar kapısı dinamik anahtarı göremez). */
export function statusLabel(kind, t) {
  return {
    SENT: t('health.statusSent'), FAILED: t('health.statusFailed'), PENDING: t('pl.statusPending'),
    BLOCKED: t('pl.statusBlocked'), SKIPPED: t('health.statusSkipped'),
  }[kind] ?? t('health.statusUnknown')
}

export function toIso(d) { return d instanceof Date && !isNaN(d) ? d.toISOString().slice(0, 19) : null }
export function fromIso(s) {
  if (!s) return null
  const str = String(s)
  const d = new Date(/[zZ]|[+-]\d\d:?\d\d$/.test(str) ? str : str + 'Z')   // sunucu UTC'yi "Z"siz yazar
  return isNaN(d) ? null : d
}
export function rangeFrom(range, now = new Date()) {
  const ms = range === '24h' ? 24 * 3600e3 : range === '30d' ? 30 * 86400e3 : 7 * 86400e3
  return new Date(now.getTime() - ms)
}

export const EMPTY_FILTERS = {
  range: '7d', from: null, to: null, status: '', trigger: '', teamId: '', errorClass: '', level: '',
  username: '', monitorType: '', q: '', sort: DEFAULT_SORT,
}

/** Başlangıç süzgeçleri: `initial` (kart / derin bağlantı) ya da URL (`p_*`). */
export function readInitial(initial) {
  const p = (k, fb = '') => initial?.[k] ?? readUrlParam('p_' + k, fb)
  const range = p('range', '') || (p('from') ? 'custom' : '7d')
  return {
    range,
    from: fromIso(p('from')) ?? (range === 'custom' ? rangeFrom('7d') : null),
    to: fromIso(p('to')),
    status: p('status'), trigger: p('trigger'), teamId: p('team'), errorClass: p('cls'), level: p('level'),
    username: p('user'), monitorType: p('mtype'), q: p('q'),
    sort: p('sort', DEFAULT_SORT),
  }
}

/** Yeniden kuyruğa alınabilir mi (yalnız başarısız / engellenmiş). */
export const retryable = (x) => x?.kind === 'FAILED' || x?.kind === 'BLOCKED'

/** Teslimat sağlığı tonu (başarı oranı): ≥ 99 iyi, ≥ 90 uyarı, altı kötü; veri yoksa nötr. */
export function rateTone(rate) {
  if (rate == null || rate === '') return 'neutral'
  const r = Number(rate)
  return r >= 99 ? 'ok' : r >= 90 ? 'warn' : 'bad'
}

/** İki UTC zaman arası süre (ms); biri yoksa null. */
export function gapMs(fromS, toS) {
  const a = fromIso(fromS), b = fromIso(toS)
  return a && b ? Math.max(0, b.getTime() - a.getTime()) : null
}
