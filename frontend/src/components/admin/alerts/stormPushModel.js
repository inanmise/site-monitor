// Fırtına push'u ↔ alarm bağı (2026-10-04, kullanıcı isteği) — alarm detayının "Fırtına push'u" bölümü ve zaman çizelgesi
// için saf yardımcılar. Kaynak: GET /api/admin/alerts/{id}/storm-push (AdminController → StormPushCoverageService).
import { pushReasonLabel } from '../../../utils/pushPrefs.js'

export const SP_TRIGGERS = ['INITIAL', 'DAILY_REALERT', 'RESOLVE']

const EMPTY = Object.freeze({ items: [], pending: [], storms: [], handedOver: false, pushIndividual: false })

/** Uç yanıtı → güvenli biçim (eski sunucu / hata / dizi dönen taklit → boş bölüm). */
export function normalizeStormPush(res) {
  const d = res?.success && res.data && !Array.isArray(res.data) && typeof res.data === 'object' ? res.data : null
  if (!d) return EMPTY
  return {
    items: Array.isArray(d.items) ? d.items : [],
    pending: Array.isArray(d.pending) ? d.pending : [],
    storms: Array.isArray(d.storms) ? d.storms : [],
    handedOver: d.handed_over === true,
    pushIndividual: d.push_individual === true,
  }
}

/** Bildirimin zaman çizelgesindeki anı: ilk iletim, yoksa ilk satır, yoksa kayıt anı. */
export function stormPushAt(item) {
  return item?.first_sent_at || item?.first_created_at || item?.covered_at || null
}

/** Zone'suz damga = UTC (sunucu ISO biçimi). */
function ms(iso) {
  if (!iso) return null
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  const v = Date.parse(s)
  return Number.isNaN(v) ? null : v
}

/** Zaman çizelgesi olayları — bildirim başına bir olay (kind `stormPush`). */
export function stormPushTimelineItems(data) {
  const out = []
  for (const it of data?.items ?? []) {
    if (!it?.push_key) continue
    out.push({ id: `sp-${it.push_key}|${it.team_id ?? ''}`, kind: 'stormPush', at: ms(stormPushAt(it)), when: stormPushAt(it), item: it })
  }
  return out
}

/** Bildirimin sonucu (sunucu `outcome`; eski yanıtta sayılardan). */
export function stormPushOutcome(item) {
  if (item?.outcome) return item.outcome
  if (Number(item?.sent) > 0) return 'sent'
  if (Number(item?.pending) > 0) return 'pending'
  if (Number(item?.failed) > 0) return 'failed'
  return item?.decision ? 'skipped' : 'none'
}

/** Sonuç → rozet tonu. */
export const OUTCOME_TONE = { sent: 'success', pending: 'info', failed: 'danger', skipped: 'warning', none: 'muted' }

/** Başlık: "Fırtına #12 push'u iletildi · 12 kişi" / düzelme / kuyrukta / gönderilmedi. */
export function stormPushHeadline(t, item) {
  const id = item?.storm_id ?? '?'
  const resolve = item?.trigger === 'RESOLVE'
  switch (stormPushOutcome(item)) {
    case 'sent': return t(resolve ? 'alh.sp.resolvedSent' : 'alh.sp.sent', id, Number(item.sent) || 0)
    case 'pending': return t(resolve ? 'alh.sp.resolvedQueued' : 'alh.sp.queued', id)
    // Teslimat satırı hiç yok (saklama süresiyle silinmiş olabilir): "gönderilmedi" demek yanlış olurdu.
    case 'none': return t('alh.sp.noRecord', id)
    default: return t(resolve ? 'alh.sp.resolvedNotSent' : 'alh.sp.notSent', id)
  }
}

/** Gönderilmeyen bildirimin nedeni (kanal kararı → başarısız → en sık kişi durumu → kayıt yok); gönderildiyse null. */
export function stormPushReason(t, item) {
  const outcome = stormPushOutcome(item)
  if (outcome === 'sent' || outcome === 'pending') return null
  if (item?.decision) return pushReasonLabel(item.decision, t)
  if (outcome === 'failed') return t('alh.sp.reason.failed')
  const counts = Object.entries(item?.counts ?? {}).filter(([k]) => k !== 'SENT' && k !== 'PENDING').sort((a, b) => b[1] - a[1])
  if (counts.length) return pushReasonLabel(counts[0][0], t)
  return t('alh.sp.reason.none')
}

/** Tetik etiketi anahtarı (bilinmeyen tetik açılış sayılır). */
export function stormPushTriggerKey(trigger) {
  return `alh.sp.trigger.${SP_TRIGGERS.includes(trigger) ? trigger : 'INITIAL'}`
}

/** Kişi satırı durumu → rozet tonu. */
export function recipientTone(status) {
  if (status === 'SENT') return 'success'
  if (status === 'FAILED' || status === 'CIRCUIT_OPEN') return 'danger'
  if (status === 'PENDING') return 'info'
  if (status === 'RATE_LIMITED') return 'warning'
  return 'muted'
}

/** Bölüm gösterilsin mi: alarm bir fırtınaya bağlı ya da bağ / bekleyen / devir izi var. */
export function showStormPushSection(alert, data) {
  if (!data) return alert?.storm_id != null
  return alert?.storm_id != null || data.items.length > 0 || data.pending.length > 0 || data.handedOver || data.storms.length > 0
}
