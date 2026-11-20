/**
 * Onay sonrası yöneticilere gönderimin sonucu → cümle (UIA-E1, 2026-10-22). Eskiden ham kod basılıyordu ("…gönderilemedi
 * (APPROVAL_STALE). Yöneticilere gönder ile yeniden deneyin") — onaydan sonra değişen raporda o düğme yine 409 alır, gönderim
 * sürerken ikinci gönderime çağırıyordu. Anlamlar sunucunun push cümleleriyle aynı (TechErrorPushService.approvedParts).
 * SENT + boş `sent_to` (yöneticilerin hepsi pasif, posta yalnız takım CC'sine gitti) "yalnız takım kopyası" der.
 *
 * Ayrı modül (2026-11-20, A2): onay kartı ve ondan çıkarılan karar penceresi (ApprovalDecideDialog) ikisi de kullanır —
 * ApprovalCard.jsx eski içe aktarmalar için yeniden dışa aktarır.
 * @returns {{ tone: 'success'|'info'|'error', text: string }}
 */
export function approvalOutcome(d, t) {
  const code = d?.send_state || d?.send_error || 'FAILED'
  if (code === 'SENT') {
    const to = Array.isArray(d?.sent_to) ? d.sent_to.filter(Boolean) : []
    return to.length ? { tone: 'success', text: t('te.appr.approved', to.join(', ')) } : { tone: 'success', text: t('te.appr.outcome.ccOnly') }
  }
  if (code === 'QUEUED') return { tone: 'info', text: t('te.appr.approvedQueued') }
  if (code === 'SKIPPED') return { tone: 'info', text: t('te.appr.outcome.SKIPPED') }
  if (code === 'APPROVAL_STALE') return { tone: 'error', text: t('te.appr.outcome.APPROVAL_STALE') }
  if (code === 'SEND_IN_PROGRESS') return { tone: 'info', text: t('te.appr.outcome.SEND_IN_PROGRESS') }
  // Çok pod R2a (MP-41): raporun önceki gönderimi 421 kuyruğunda — ikinci kopya gitmesin diye yöneticilere gönderilmedi
  if (code === 'SEND_QUEUED') return { tone: 'info', text: t('te.appr.outcome.SEND_QUEUED') }
  if (code === 'PDF_BUSY') return { tone: 'error', text: t('te.appr.outcome.PDF_BUSY') }
  if (code === 'PDF_TOO_LARGE') return { tone: 'error', text: t('te.appr.outcome.PDF_TOO_LARGE') }
  return { tone: 'error', text: t('te.appr.outcome.other') }
}
