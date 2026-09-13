import { useEffect, useState } from 'react'
import { CalendarClock, Plus, ArrowRight, AlertTriangle, CheckCircle2, ChevronDown } from 'lucide-react'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { formatWeekRange } from '../../utils/isoWeek'
import { countdown } from './weeklyModel.js'

/**
 * "Bu hafta" şeridi (2026-09-13): kapsamdaki her takım için bu ISO haftanın durumu + son giriş anına geri
 * sayım + tek tık eylem (Oluştur / Devam et / Aç). Geçmişse kırmızı "gecikti". Sayaç dakikada bir tazelenir.
 */
export default function WeeklyThisWeekStrip({ data, onOpen, onCreate, canCreate, loading }) {
  const t = useT()
  const { lang } = useLanguage()
  const [now, setNow] = useState(() => Date.now())
  // Varsayılan KAPALI (kullanıcı kararı 2026-09-13): başlıkta özet (eksik sayısı + son giriş) görünür, açan kişinin tercihi kalır
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('wr-thisweek-open') === 'true' } catch { return false } })
  const toggle = () => setOpen((o) => { try { localStorage.setItem('wr-thisweek-open', String(!o)) } catch { /* yoksay */ } return !o })
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 60_000); return () => clearInterval(id) }, [])
  if (loading || !data) return null
  const cd = countdown(data.due_at, now)
  const teams = data.teams || []
  const missing = teams.filter((x) => x.status === 'MISSING' || x.status === 'DRAFT' || x.status === 'REJECTED').length
  const dueText = cd ? (cd.past ? t('wr.tw.overdueBy', cd.d, cd.h) : t('wr.tw.dueIn', cd.d, cd.h, cd.m)) : ''
  const tone = cd?.past && missing > 0 ? 'late' : missing > 0 ? 'open' : 'ok'
  return (
    <section className={`wr-tw wr-tw--${tone}`} aria-label={t('wr.tw.title')} data-tour="wr-thisweek">
      <button type="button" className="wr-tw-head" aria-expanded={open} onClick={toggle}>
        <CalendarClock size={16} />
        <strong>{t('wr.tw.title')}</strong>
        <span className="wr-tw-week">{formatWeekRange(data.year, data.week, lang)}</span>
        {!open && <span className={`wr-tw-sum${missing > 0 ? ' is-open' : ''}`}>{missing > 0 ? t('wr.tw.sumMissing', missing) : t('wr.tw.sumOk')}</span>}
        <span className="wr-spacer" />
        <span className={`wr-tw-due${cd?.past ? ' is-past' : ''}`} title={t('wr.tw.deadlineHint', data.deadline_day, data.deadline_time)}>
          {cd?.past ? <AlertTriangle size={13} /> : null} {dueText}
        </span>
        <ChevronDown size={15} className={`wr-tw-chev${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && <ul className="wr-tw-list">
        {teams.length === 0 && <li className="wr-tw-none">{t('wr.tw.noTeam')}</li>}
        {teams.map((x) => {
          const st = x.status
          const label = st === 'APPROVED' && x.sent_at ? t('wr.statusSent') : t(st === 'MISSING' ? 'wr.tw.stMissing' : `wr.status${st === 'PENDING_APPROVAL' ? 'Pending' : st.charAt(0) + st.slice(1).toLowerCase()}`)
          const action = st === 'MISSING'
            ? (canCreate ? <button type="button" className="btn btn-sm btn-success" onClick={() => onCreate(x.team_id, data.year, data.week)}><Plus size={13} /> {t('wr.tw.create')}</button> : null)
            : <button type="button" className="btn btn-sm btn-secondary" onClick={() => onOpen(x.report_id)}>{st === 'DRAFT' || st === 'REJECTED' ? t('wr.tw.continue') : t('wr.open')} <ArrowRight size={13} /></button>
          const done = st === 'APPROVED' || st === 'PENDING_APPROVAL'
          return (
            <li key={x.team_id} className={`wr-tw-item wr-tw-item--${st.toLowerCase()}`}>
              {done ? <CheckCircle2 size={14} className="wr-tw-ok" /> : <span className="wr-tw-dot" aria-hidden="true" />}
              <span className="wr-tw-team">{x.team_name}</span>
              <span className={`wr-status-badge wr-status--${st === 'MISSING' ? 'MISSING' : st}`}>{label}</span>
              <span className="wr-spacer" />
              {action}
            </li>
          )
        })}
      </ul>}
    </section>
  )
}
