import { useEffect, useState } from 'react'
import { CalendarClock, BellOff } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'

/**
 * Hatırlatma görünürlüğü (2026-09-13, ikinci tur): "Hatırlatma e-postalarını şimdi gönder" düğmesinin yanında sonraki
 * otomatik koşu + alıcı özeti. Yöneticinin "mail gitti mi, kime gidecek?" sorusuna düğmeye basmadan yanıt verir.
 * Sunucu yalnız admin/AUDIT'e döner; `nonce` elle gönderim sonrası tazeler. Küresel anahtar
 * (`reminder-enabled=false`) kapalıysa uyarır.
 *
 * 2026-09-27: legacy `.wr-rem-status` sınıfları yerine Tailwind; "Bu hafta" bölümünün alt satırında çizilir.
 * Test kancaları: role="note", data-slot="wr-reminder-status", data-state="on|off".
 */
export default function WeeklyReminderStatus({ nonce = 0, className }) {
  const t = useT()
  const [st, setSt] = useState(null)

  useEffect(() => {
    let alive = true
    ;(async () => {
      try { const r = await api.weeklyReports.remindersStatus(); if (alive && r?.success && r.data) setSt(r.data) }
      catch { /* süs */ }
    })()
    return () => { alive = false }
  }, [nonce])

  if (!st) return null
  if (st.enabled === false) {
    return (
      <p role="note" data-slot="wr-reminder-status" data-state="off"
        className={cn('flex min-w-0 items-start gap-1.5 text-xs font-medium text-amber-700 dark:text-amber-400', className)}>
        <BellOff aria-hidden="true" className="mt-px size-3.5 shrink-0" /> <span className="min-w-0">{t('wr.rem.off')}</span>
      </p>
    )
  }
  const parts = [
    t('wr.rem.willSend', st.will_send ?? 0),
    t('wr.rem.done', st.already_done ?? 0),
    t('wr.rem.noEmail', st.no_email ?? 0),
    t('wr.rem.optIn', st.opt_in_teams ?? 0),
  ]
  return (
    <p role="note" data-slot="wr-reminder-status" data-state="on" title={t('wr.rem.title')}
      className={cn('flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground', className)}>
      <CalendarClock aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="min-w-0">
        {t('wr.rem.next')}: <b className="font-semibold text-foreground">{st.next_run_at ? formatDate(st.next_run_at) : '—'}</b>
        {st.run_week ? ` (${st.run_week})` : ''}
        <span aria-hidden="true"> · </span>{parts.join(' · ')}
      </span>
    </p>
  )
}
