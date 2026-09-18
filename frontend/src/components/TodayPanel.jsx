import { useCallback, useState } from 'react'
import { CalendarClock, Siren, ClipboardCheck, CalendarDays, ArrowRight, ChevronDown, Sparkles } from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import TeamBadge from './ui/TeamBadge.jsx'
import TodayListModal from './TodayListModal.jsx'

/**
 * "Sizin için — bugün" (2026-09-12, zenginleştirme #3): dashboard'un üstünde dört kart —
 * 30 gün altı sertifika · açık alarm · süresi dolan istisna · bu haftanın raporu. Her kart doğru
 * sayfaya süzülmüş bağlantı. Hepsi sıfırsa tek satır yeşil "bugün ilgilenilecek bir şey yok".
 * 2 dk'da bir görünürken tazelenir; VARSAYILAN KAPALI, açık/kapalı tercihi localStorage'da.
 */
export default function TodayPanel({ onOpenDomain }) {
  const t = useT()
  const [data, setData] = useState(null)
  // "Tümünü gör" pop-up'ı (2026-09-18): { section, title, icon, onOpen, onGo }
  const [listModal, setListModal] = useState(null)
  // Varsayılan KAPALI (2026-09-12, kullanıcı: "otomatik kapalı olsun"); açan kullanıcı tercihi saklanır.
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('today-panel-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.me.today(); if (r?.success && r.data) setData(r.data) } catch { /* panel süs */ }
  }, [])
  useVisibleInterval(load, 120_000, true)

  if (!data) return null
  const certs = data.certs || {}, alerts = data.alerts || {}, exc = data.exceptions || {}, weekly = data.weekly || {}
  const total = (certs.count || 0) + (alerts.count || 0) + (exc.count || 0) + (weekly.missing || 0)

  function toggle() {
    setOpen((o) => { try { localStorage.setItem('today-panel-open', String(!o)) } catch { /* yoksay */ } return !o })
  }

  const Card = ({ icon: Icon, tone, title, count, sub, onGo, section, onOpenItem, children }) => (
    <div className={`today-card today-card--${tone}`}>
      <div className="today-card-head">
        <Icon size={16} aria-hidden="true" />
        <span className="today-card-title">{title}</span>
        <b className="today-card-count">{count}</b>
      </div>
      {sub && <div className="today-card-sub">{sub}</div>}
      {children}
      {/* "Tümünü gör" → sayfaya gitmek yerine POP-UP: listenin tamamı sayfalı (2026-09-18); "Sayfaya git" pop-up'ın altında */}
      {onGo && (
        <button type="button" className="today-card-go"
          onClick={() => setListModal({ section, title, icon: Icon, onOpen: onOpenItem, onGo })}>{t('today.go')} <ArrowRight size={12} aria-hidden="true" /></button>
      )}
    </div>
  )

  return (
    <section className={`today${open ? ' is-open' : ''}${total === 0 ? ' today--clear' : ''}`} aria-label={t('today.title')}>
      <button type="button" className="today-head" aria-expanded={open} onClick={toggle}>
        <Sparkles size={16} aria-hidden="true" />
        <span className="today-title">{t('today.title')}</span>
        <span className="today-summary">{total === 0 ? t('today.clear') : t('today.summary', total)}</span>
        <ChevronDown size={16} className={`today-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && total > 0 && (
        <div className="today-grid">
          <Card icon={CalendarClock} tone={certs.expired > 0 ? 'bad' : certs.count > 0 ? 'warn' : 'ok'} title={t('today.certs')} count={certs.count || 0}
            sub={certs.expired > 0 ? t('today.certsExpired', certs.expired) : t('today.certsSub')}
            onGo={certs.count ? () => navigateTo('renewal') : null} section="certs" onOpenItem={(c) => onOpenDomain?.(c.domain)}>
            <ul className="today-list">
              {(certs.items || []).map((c) => (
                <li key={c.domain}>
                  <button type="button" className="today-link" onClick={() => onOpenDomain?.(c.domain)}>{c.domain}</button>
                  <span className={`today-days${c.days < 0 ? ' is-bad' : c.days <= 7 ? ' is-warn' : ''}`}>{c.days < 0 ? t('today.daysPast', -c.days) : t('today.daysLeft', c.days)}</span>
                  {c.team_name && <TeamBadge teamId={c.team_id} teamName={c.team_name} />}
                </li>
              ))}
            </ul>
          </Card>

          <Card icon={Siren} tone={alerts.critical > 0 ? 'bad' : alerts.count > 0 ? 'warn' : 'ok'} title={t('today.alerts')} count={alerts.count || 0}
            sub={alerts.critical > 0 ? t('today.alertsCritical', alerts.critical) : t('today.alertsSub')}
            onGo={alerts.count ? () => navigateTo('warnings') : null} section="alerts" onOpenItem={(a) => navigateTo('alerthistory', { incident: a.id })}>
            <ul className="today-list">
              {(alerts.items || []).map((a) => (
                <li key={a.id}>
                  <span className={`today-level today-level--${(a.level || '').toLowerCase()}`}>{a.level}</span>
                  <button type="button" className="today-link" onClick={() => navigateTo('alerthistory', { incident: a.id })}>{a.domain || a.type}</button>
                  <span className="today-muted">{a.type}{a.acknowledged ? ` · ${t('today.acked')}` : ''}</span>
                </li>
              ))}
            </ul>
          </Card>

          <Card icon={ClipboardCheck} tone={exc.expired > 0 ? 'bad' : exc.count > 0 ? 'warn' : 'ok'} title={t('today.exceptions')} count={exc.count || 0}
            sub={exc.expired > 0 ? t('today.exceptionsExpired', exc.expired) : t('today.exceptionsSub')}
            onGo={exc.count ? () => navigateTo('weakalgo') : null} section="exceptions" onOpenItem={() => navigateTo('weakalgo')}>
            <ul className="today-list">
              {(exc.items || []).map((e) => (
                <li key={e.domain}>
                  <button type="button" className="today-link" onClick={() => navigateTo('weakalgo')}>{e.domain}</button>
                  <span className={`today-days${e.days < 0 ? ' is-bad' : ' is-warn'}`}>{e.days < 0 ? t('today.expiredOn', formatDate(e.until)) : t('today.untilIn', e.days)}</span>
                </li>
              ))}
            </ul>
          </Card>

          {/* Haftalık rapor kartı YALNIZ modülü açık takımlarda (2026-09-16): sunucu kapalı takımı hiç
              saymaz → count 0 gelir; sayfayı görmeyen takıma "raporun eksik" demenin karşılığı yok. */}
          {weekly.count > 0 && (
          <Card icon={CalendarDays} tone={weekly.missing > 0 ? 'warn' : 'ok'} title={t('today.weekly', weekly.week)} count={weekly.missing || 0}
            sub={weekly.count === 0 ? t('today.weeklyNoTeam') : weekly.missing > 0 ? t('today.weeklyMissing') : t('today.weeklyDone')}
            onGo={weekly.count ? () => navigateTo('weeklyreports') : null} section="weekly" onOpenItem={() => navigateTo('weeklyreports')}>
            <ul className="today-list">
              {(weekly.items || []).map((w) => (
                <li key={w.team_id}>
                  {w.team_name ? <TeamBadge teamId={w.team_id} teamName={w.team_name} /> : <span>#{w.team_id}</span>}
                  <span className={`today-status today-status--${(w.status || '').toLowerCase()}`}>{t(`today.wr.${w.status}`)}</span>
                </li>
              ))}
            </ul>
          </Card>
          )}
        </div>
      )}
      {listModal && (
        <TodayListModal section={listModal.section} title={listModal.title} icon={listModal.icon}
          onClose={() => setListModal(null)}
          onOpen={(x) => { setListModal(null); listModal.onOpen?.(x) }}
          onGo={listModal.onGo} />
      )}
    </section>
  )
}
