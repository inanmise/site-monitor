import { useEffect, useState } from 'react'
import { CalendarClock, BellOff } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

/**
 * Hatırlatma görünürlüğü (2026-09-13, ikinci tur): "Hatırlatma maillerini şimdi gönder"
 * düğmesinin yanında sonraki otomatik koşu + alıcı özeti. Yöneticinin "mail gitti mi, kime
 * gidecek?" sorusuna düğmeye basmadan yanıt verir. Sunucu yalnız admin/AUDIT'e döner; `nonce`
 * elle gönderim sonrası tazeler. Küresel anahtar (`reminder-enabled=false`) kapalıysa uyarır.
 */
export default function WeeklyReminderStatus({ nonce = 0 }) {
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
      <span className="wr-rem-status is-off" role="note">
        <BellOff size={13} aria-hidden="true" /> {t('wr.rem.off')}
      </span>
    )
  }
  const parts = [
    t('wr.rem.willSend', st.will_send ?? 0),
    t('wr.rem.done', st.already_done ?? 0),
    t('wr.rem.noEmail', st.no_email ?? 0),
    t('wr.rem.optIn', st.opt_in_teams ?? 0),
  ]
  return (
    <span className="wr-rem-status" role="note" title={t('wr.rem.title')}>
      <CalendarClock size={13} aria-hidden="true" />
      <span>{t('wr.rem.next')}: <b>{st.next_run_at ? formatDate(st.next_run_at) : '—'}</b></span>
      <span className="wr-rem-sep" aria-hidden="true">·</span>
      <span>{parts.join(' · ')}</span>
    </span>
  )
}
