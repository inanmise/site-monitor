/**
 * Rapor e-postası gönderim sonucu — Envanter raporu "Şimdi çalıştır" / test gönderimi ve Haftalık erişilebilirlik test
 * gönderimi için TEK sınıflandırma (20.166 bugfix, UIA-C2 / K9). Eskiden ekran `success:true` gördü mü "Rapor gönderildi"
 * yazıyor, durum alanını ham basıyordu: SMTP bozukken yeşil bantta "durum: FAILED: Connection refused", e-posta kanalı
 * kapalıyken (SKIPPED_DISABLED) "test gönderildi". Kural CLAUDE.md checklist #8: SKIPPED ≠ FAILED ≠ SENT; kuyruk
 * (QUEUED) teslim değildir.
 *
 * Sunucu durum biçimleri: `SENT`, `QUEUED_RETRY…`, `SKIPPED_DISABLED` / `SKIPPED: …`, `NO_RECIPIENT`, `FAILED: …`.
 */
export const SEND_CLASSES = ['sent', 'queued', 'skipped', 'noRecipient', 'failed', 'unknown']

export function sendStatusClass(status) {
  const s = String(status ?? '').trim().toUpperCase()
  if (!s) return 'unknown'
  if (s === 'SENT' || s.startsWith('SENT ') || s.startsWith('SENT:')) return 'sent'
  if (s.startsWith('QUEUED')) return 'queued'
  if (s.startsWith('NO_RECIPIENT')) return 'noRecipient'
  if (s.startsWith('SKIPPED') || s === 'DISABLED' || s === 'ALREADY_SENT') return 'skipped'
  return 'failed'
}

/** Sınıf → bant / bildirim tonu. */
export const SEND_TONE = { sent: 'success', queued: 'info', skipped: 'warning', noRecipient: 'warning', failed: 'danger', unknown: 'warning' }

/** Sınıf → açıklayıcı cümle (anahtarlar AÇIKÇA yazılı — kullanılmayan anahtar kapısı dinamik anahtarı görmez). */
export function sendStatusText(cls, t) {
  switch (cls) {
    case 'sent': return t('rsend.status.sent')
    case 'queued': return t('rsend.status.queued')
    case 'skipped': return t('rsend.status.skipped')
    case 'noRecipient': return t('rsend.status.noRecipient')
    case 'failed': return t('rsend.status.failed')
    default: return t('rsend.status.unknown')
  }
}

/** Arşiv satırı durum rozeti: kısa etiket + ton (SENT / QUEUED / SKIPPED / NO_RECIPIENT / FAILED). */
export function historyBadge(status, t) {
  const cls = sendStatusClass(status)
  const label = {
    sent: t('rsend.badge.sent'), queued: t('rsend.badge.queued'), skipped: t('rsend.badge.skipped'),
    noRecipient: t('rsend.badge.noRecipient'), failed: t('rsend.badge.undelivered'),
  }[cls] ?? (status || '—')
  const tone = { sent: 'success', queued: 'info', skipped: 'muted', noRecipient: 'warning', failed: 'danger' }[cls] ?? 'muted'
  return { cls, label, tone }
}

/**
 * Bir gönderim yanıtının sonucu: önce durum alanı (sunucu `success`'i eski sürümde SKIPPED'ı da başarı sayıyordu), durum
 * yoksa `success`. Dönen `{ cls, text, tone }` ekranın doğrudan basacağı şey; ham durum `raw`'da (teknik ayrıntı).
 */
export function sendOutcome(res, t, statusOf = (r) => r?.data?.status ?? r?.message) {
  const raw = statusOf(res)
  // K9: "Şimdi çalıştır" yanıtı sınıflanmış `data.outcome` (SENT | QUEUED | SKIPPED | FAILED | NO_RECIPIENT) taşır — varsa o
  // kazanır, yoksa (eski sunucu) ham durumdan türetilir.
  const outcome = typeof res?.data?.outcome === 'string' ? res.data.outcome : null
  let cls = sendStatusClass(outcome || raw)
  if (cls === 'unknown') cls = res?.success ? 'sent' : 'failed'
  const text = cls === 'failed' && !raw && res?.error ? res.error : sendStatusText(cls, t)
  return { cls, text, tone: SEND_TONE[cls], raw: raw || null }
}
