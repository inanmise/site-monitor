import { useCallback, useEffect, useState } from 'react'
import { formatPercent } from '../../i18n/dateLocale.js'
import { ChevronDown, Activity, Flame, Lightbulb, Timer, MoonStar, CheckCircle2, AlertOctagon } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'

/**
 * Alarm gürültü analizi (2026-09-12, zenginleştirme #18): en çok alarm üreten 10 hedef, gün × saat
 * ısı haritası (İstanbul), "flap" adayları + eşik/onay sayısı önerisi. Alarm Geçmişi'nin üstünde,
 * katlanır; 7 / 30 gün seçimi. Veri /api/admin/alerts/noise.
 */
const DAYS = [7, 30]

/** KPI kutucuğu — sayı büyük, etiket küçük, ipucu başlıkta (2026-09-16 zenginleştirme). */
function Kpi({ icon: Icon, label, value, sub, tone, title }) {
  return (
    <div className={`noise-kpi${tone ? ` noise-kpi--${tone}` : ''}`} title={title}>
      <div className="noise-kpi-top">{Icon && <Icon size={13} aria-hidden="true" />}<span>{label}</span></div>
      <div className="noise-kpi-val">{value}</div>
      {sub && <div className="noise-kpi-sub">{sub}</div>}
    </div>
  )
}

/** Günlük seri — kıvılcım çubukları; en yoğun gün vurgulu. */
function Trend({ series, t }) {
  const max = Math.max(1, ...series.map((p) => p.count))
  const peak = series.reduce((m, p) => (p.count > (m?.count ?? -1) ? p : m), null)
  return (
    <div className="noise-trend">
      <div className="noise-trend-bars" role="img" aria-label={t('noise.trendAria')}>
        {series.map((p) => (
          <span key={p.date} className={`noise-trend-bar${peak && p.date === peak.date && p.count > 0 ? ' is-peak' : ''}`}
            style={{ '--h': `${Math.max(3, Math.round(100 * p.count / max))}%` }}
            title={`${p.date} · ${p.count}`} />
        ))}
      </div>
      <div className="noise-trend-axis">
        <span>{series[0]?.date?.slice(5)}</span>
        {peak && peak.count > 0 && <span className="noise-trend-peak">{t('noise.trendPeak', peak.date.slice(5), peak.count)}</span>}
        <span>{series[series.length - 1]?.date?.slice(5)}</span>
      </div>
    </div>
  )
}

export default function AlertNoisePanel({ onPickDomain }) {
  const t = useT()
  const [days, setDays] = useState(7)
  const [data, setData] = useState(null)
  // Varsayilan KAPALI, tercih OTURUMLUK (2026-09-17 kullanici karari) - takim kirilimiyla ayni kural.
  const [open, setOpen] = useState(() => { try { return sessionStorage.getItem('alh-noise-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.admin.getAlertNoise(days); if (r?.success && r.data) setData(r.data) } catch { /* panel süs */ }
  }, [days])
  useEffect(() => { if (open) load() }, [open, load])

  const toggle = () => setOpen((o) => { try { sessionStorage.setItem('alh-noise-open', String(!o)) } catch { /* yoksay */ } return !o })
  const dayNames = [t('cal.mon'), t('cal.tue'), t('cal.wed'), t('cal.thu'), t('cal.fri'), t('cal.sat'), t('cal.sun')]
  const heat = data?.heat
  const peak = Math.max(1, heat?.peak || 0)

  return (
    <section className="noise" aria-label={t('noise.title')}>
      <button type="button" className="noise-head" aria-expanded={open} onClick={toggle}>
        <Activity size={16} aria-hidden="true" />
        <span className="noise-title">{t('noise.title')}</span>
        {data && open && <span className="noise-sum">{t('noise.summary', data.total, data.distinct_targets, (data.flapping || []).length)}</span>}
        <ChevronDown size={16} className={`noise-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && (
        <div className="noise-body">
          <div className="noise-toolbar">
            {DAYS.map((d) => (
              <button type="button" key={d} className={`btn btn-sm ${days === d ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setDays(d)} aria-pressed={days === d}>{t('noise.days', d)}</button>
            ))}
          </div>
          {!data && <div className="noise-empty">{t('noise.loading')}</div>}
          {data && data.total === 0 && <div className="noise-empty">{t('noise.none', data.days)}</div>}
          {data && data.total > 0 && (
            <>
            {/* KPI şeridi (2026-09-16): sayfa açılır açılmaz "ne kadar gürültü, ne kadarı kritik,
                ne kadarı mesai dışı, ortalama kapanma ne kadar sürüyor" görünsün. */}
            <div className="noise-kpis">
              <Kpi icon={Activity} label={t('noise.kpiTotal')} value={data.total}
                sub={t('noise.kpiPerDay', data.per_day_avg ?? 0)} title={t('noise.kpiTotalTip', data.days)} />
              <Kpi icon={AlertOctagon} label={t('noise.kpiCritical')} value={data.critical} tone={data.critical > 0 ? 'bad' : null}
                sub={t('noise.kpiOpen', data.still_open ?? 0)} />
              <Kpi icon={CheckCircle2} label={t('noise.kpiResolved')} value={formatPercent(data.resolved_pct ?? 0)}
                sub={t('noise.kpiResolvedSub', data.resolved_total ?? 0)} tone={(data.resolved_pct ?? 0) >= 80 ? 'ok' : null} />
              <Kpi icon={Timer} label={t('noise.kpiMttr')} value={data.mttr_minutes == null ? '—' : t('noise.minutes', data.mttr_minutes)}
                sub={t('noise.kpiMttrSub')} title={t('noise.kpiMttrTip')} />
              <Kpi icon={MoonStar} label={t('noise.kpiOffHours')} value={formatPercent(data.off_hours_pct ?? 0)}
                sub={t('noise.kpiOffHoursSub', data.off_hours ?? 0)} tone={(data.off_hours_pct ?? 0) >= 40 ? 'warn' : null}
                title={t('noise.kpiOffHoursTip')} />
            </div>

            {(data.series || []).length > 1 && (
              <div className="noise-block noise-block--wide">
                <div className="noise-block-title">{t('noise.trend')}</div>
                <Trend series={data.series} t={t} />
              </div>
            )}

            <div className="noise-grid">
              <div className="noise-block">
                <div className="noise-block-title">{t('noise.top')}</div>
                <table className="noise-table">
                  <thead><tr><th>{t('noise.colTarget')}</th><th>{t('noise.colType')}</th><th>{t('noise.colCount')}</th><th>{t('noise.colShare')}</th><th>{t('noise.colAvg')}</th></tr></thead>
                  <tbody>
                    {(data.top || []).map((r, i) => (
                      <tr key={i}>
                        <td><button type="button" className="noise-link" onClick={() => onPickDomain?.(r.domain)}>{r.domain}</button></td>
                        <td className="noise-mono">{r.type}</td>
                        <td><b>{r.count}</b></td>
                        <td><span className="noise-bar" style={{ '--w': `${Math.min(100, r.share_pct)}%` }}>{formatPercent(r.share_pct)}</span></td>
                        <td>{r.avg_minutes == null ? '—' : t('noise.minutes', r.avg_minutes)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="noise-block">
                <div className="noise-block-title">{t('noise.heat')} <small>{heat && heat.peak > 0 ? t('noise.peak', dayNames[heat.peak_day] ?? '', heat.peak_hour, heat.peak) : ''}</small></div>
                <div className="noise-heat" role="img" aria-label={t('noise.heatAria')}>
                  <div className="noise-heat-corner" />
                  {Array.from({ length: 24 }, (_, h) => <div key={`h${h}`} className="noise-heat-hour">{h % 3 === 0 ? h : ''}</div>)}
                  {(heat?.rows || []).map((row, dow) => (
                    <div key={dow} className="noise-heat-row">
                      <div className="noise-heat-day">{dayNames[dow]}</div>
                      {row.map((v, h) => (
                        <div key={h} className="noise-heat-cell" style={{ '--a': v ? Math.max(0.15, v / peak) : 0 }} title={`${dayNames[dow]} ${String(h).padStart(2, '0')}:00 · ${v}`} />
                      ))}
                    </div>
                  ))}
                </div>
              </div>

              <div className="noise-block noise-block--wide">
                <div className="noise-block-title"><Flame size={14} aria-hidden="true" /> {t('noise.flapping')}</div>
                {(data.flapping || []).length === 0 ? <div className="noise-empty">{t('noise.noFlap')}</div> : (
                  <ul className="noise-flap-list">
                    {data.flapping.map((f, i) => (
                      <li key={i}>
                        <button type="button" className="noise-link" onClick={() => onPickDomain?.(f.domain)}>{f.domain}</button>
                        <span className="noise-mono"> · {f.type}</span> — {t('noise.flapLine', f.count, f.avg_minutes)}
                        <span className="noise-tip"><Lightbulb size={12} aria-hidden="true" /> {t('noise.flapTip')} <button type="button" className="noise-link" onClick={() => navigateTo('settings')}>{t('noise.flapGo')}</button></span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              {(data.by_type || []).length > 0 && (
                <div className="noise-block">
                  <div className="noise-block-title">{t('noise.byType')}</div>
                  <ul className="noise-type-list">
                    {data.by_type.map((r) => (
                      <li key={r.type}>
                        <span className="noise-type-name">{r.type}</span>
                        <span className="noise-type-bar" style={{ '--w': `${Math.min(100, r.share_pct)}%` }} aria-hidden="true" />
                        <span className="noise-type-num"><b>{r.count}</b>{r.critical > 0 && <em className="noise-type-crit" title={t('noise.kpiCritical')}> · {r.critical}</em>}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
            </>
          )}
        </div>
      )}
    </section>
  )
}
