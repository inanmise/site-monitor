import { useCallback, useState } from 'react'
import { HeartPulse, CalendarClock, Siren, Gauge, TrendingUp, TrendingDown, Minus } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import TeamBadge from './ui/TeamBadge.jsx'
import { ProgressBar } from './ui/Progress.jsx'

/**
 * İstatistik sayfası yönetici özeti (2026-09-12, zenginleştirme #20): dört KPI + takım karşılaştırma
 * çubuğu (sağlık % — en kötü üstte) + "son 30 gün / önceki 30 gün" alarm deltası. Veri /api/stats/executive.
 */
export default function ExecutiveSummary({ onOpenTeam }) {
  const t = useT()
  const [data, setData] = useState(null)
  const load = useCallback(async () => {
    try { const r = await api.getExecutiveStats(); if (r?.success && r.data) setData(r.data) } catch { /* özet süs */ }
  }, [])
  useVisibleInterval(load, 300_000, true)
  if (!data) return null

  const c = data.certs || {}, a = data.alerts || {}, s = data.sla || {}
  const teams = data.teams || []
  const health = c.health_pct
  const tone = (v, warn, bad, invert = false) => v == null ? '' : invert
    ? (v >= warn ? ' is-ok' : v >= bad ? ' is-warn' : ' is-bad')
    : (v === 0 ? ' is-ok' : v <= warn ? ' is-warn' : ' is-bad')
  const Delta = ({ n }) => n == null ? null : (
    <span className={`exs-delta${n > 0 ? ' is-bad' : n < 0 ? ' is-ok' : ''}`} title={t('exs.deltaTip', a.opened_last30 ?? 0, a.opened_prev30 ?? 0)}>
      {n > 0 ? <TrendingUp size={12} /> : n < 0 ? <TrendingDown size={12} /> : <Minus size={12} />} {n > 0 ? `+${n}` : n} {t('exs.vsPrev')}
    </span>
  )

  return (
    <section className="exs" aria-label={t('exs.title')}>
      <div className="exs-kpis">
        <button type="button" className={`exs-kpi${tone(health, 95, 90, true)}`} onClick={() => navigateTo('dashboard')}>
          <HeartPulse size={18} aria-hidden="true" />
          <span className="exs-kpi-label">{t('exs.health')}</span>
          <b className="exs-kpi-value">{health == null ? '—' : `%${health}`}</b>
          <span className="exs-kpi-sub">{t('exs.healthSub', c.ok ?? 0, c.total ?? 0)}</span>
        </button>
        <button type="button" className={`exs-kpi${tone(c.under30 ?? 0, 5, 15)}`} onClick={() => navigateTo('renewal')}>
          <CalendarClock size={18} aria-hidden="true" />
          <span className="exs-kpi-label">{t('exs.under30')}</span>
          <b className="exs-kpi-value">{c.under30 ?? 0}</b>
          <span className="exs-kpi-sub">{t('exs.under30Sub', c.under7 ?? 0, c.expired ?? 0)}</span>
        </button>
        <button type="button" className={`exs-kpi${tone(a.open ?? 0, 3, 10)}`} onClick={() => navigateTo('warnings')}>
          <Siren size={18} aria-hidden="true" />
          <span className="exs-kpi-label">{t('exs.alerts')}</span>
          <b className="exs-kpi-value">{a.open ?? 0}</b>
          <span className="exs-kpi-sub">{t('exs.alertsSub', a.critical ?? 0)} <Delta n={a.delta} /></span>
        </button>
        <button type="button" className={`exs-kpi${tone(s.breaches ?? 0, 2, 5)}`} onClick={() => navigateTo('uptime')}>
          <Gauge size={18} aria-hidden="true" />
          <span className="exs-kpi-label">{t('exs.sla', s.target_pct ?? 99.9)}</span>
          <b className="exs-kpi-value">{s.breaches ?? 0}</b>
          <span className="exs-kpi-sub">{t('exs.slaSub', s.monitors ?? 0, data.window_days ?? 30)}</span>
        </button>
      </div>

      {teams.length > 1 && (
        <div className="exs-teams">
          <div className="exs-teams-title">{t('exs.teams')}</div>
          <div className="exs-teams-grid">
            {teams.map((tm) => {
              const h = tm.health_pct
              return (
                <button type="button" key={tm.team_id ?? 'none'} className="exs-team-row" onClick={() => tm.team_id != null && onOpenTeam?.(tm.team_id)}
                  title={t('exs.teamTip', tm.ok, tm.under30, tm.expired, tm.error, tm.open_alerts)}>
                  <span className="exs-team-name">{tm.team_name ? <TeamBadge teamId={tm.team_id} teamName={tm.team_name} /> : <em>{t('exs.noTeam')}</em>}</span>
                  <ProgressBar value={h ?? 0} max={100} size="sm" decorative className={`exs-team-bar${h == null ? '' : h >= 95 ? ' is-ok' : h >= 90 ? ' is-warn' : ' is-bad'}`} />
                  <span className="exs-team-pct">{h == null ? '—' : `%${h}`}</span>
                  <span className="exs-team-meta">{tm.total} · {tm.under30 > 0 && <b className="is-warn">{tm.under30}↓30g</b>} {tm.open_alerts > 0 && <b className="is-bad">{tm.open_alerts}⚠</b>}</span>
                </button>
              )
            })}
          </div>
        </div>
      )}
    </section>
  )
}
