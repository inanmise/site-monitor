import { ShieldCheck, Globe, Activity, Radio, Network, Server, Search, ScanSearch } from 'lucide-react'

/** Tür → {ikon, etiket anahtarı, türe-özgü ekstra metrik etiketi + birimi}. */
const TYPE_META = {
  cert:    { icon: ShieldCheck, labelKey: 'wr.monTypeCert',    extraLabel: 'wr.monExtraExpiring', extraUnit: 'count' },
  domain:  { icon: Globe,       labelKey: 'wr.monTypeDomain',  extraLabel: 'wr.monExtraExpiring', extraUnit: 'count' },
  http:    { icon: Activity,    labelKey: 'wr.monTypeHttp',    extraLabel: 'wr.monExtraResp',     extraUnit: 'ms' },
  ping:    { icon: Radio,       labelKey: 'wr.monTypePing',    extraLabel: 'wr.monExtraResp',     extraUnit: 'ms' },
  port:    { icon: Network,     labelKey: 'wr.monTypePort',    extraLabel: 'wr.monExtraClosed',   extraUnit: 'count' },
  dns:     { icon: Server,      labelKey: 'wr.monTypeDns',     extraLabel: 'wr.monExtraChanges',  extraUnit: 'count' },
  keyword: { icon: Search,      labelKey: 'wr.monTypeKeyword', extraLabel: null,                  extraUnit: null },
  page:    { icon: ScanSearch,  labelKey: 'wr.monTypePage',    extraLabel: null,                  extraUnit: null },
}
const ORDER = ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page']

/** Backend snake_case (SNAKE_CASE) → normalize; camelCase fallback güvenlik için. */
function norm(x) {
  return {
    type: x.type,
    active: x.active_monitors ?? x.activeMonitors ?? 0,
    checks: x.total_checks ?? x.totalChecks ?? 0,
    rate: x.success_rate ?? x.successRate,
    opened: x.alarms_opened ?? x.alarmsOpened ?? 0,
    resolved: x.alarms_resolved ?? x.alarmsResolved ?? 0,
    open: x.alarms_open ?? x.alarmsOpen ?? 0,
    rateDelta: x.success_rate_delta ?? x.successRateDelta,
    extra: x.extra,
    top3: (x.top3 || []).map((tt) => ({ name: tt.name, rate: tt.success_rate ?? tt.successRate, alarms: tt.alarms ?? 0 })),
  }
}

/** Erişim oranı bandı — ≥99.5 yeşil, ≥97 amber, altı kırmızı; null → nötr. */
function rateBand(r) { return r == null ? 'na' : r >= 99.5 ? 'good' : r >= 97 ? 'amber' : 'bad' }

function Metric({ label, value, danger }) {
  return (
    <div className="wr-mon-metric">
      <span className="wr-mon-metric-lbl">{label}</span>
      <span className={`wr-mon-metric-val${danger ? ' danger' : ''}`}>{value}</span>
    </div>
  )
}

/**
 * İzleme Göstergeleri (04 · İzleme) — izleme türü bazında haftalık kart grid'i; KPI şeridiyle uyumlu executive görünüm
 * (dikişsiz grid, uppercase etiket, büyük hafif erişim oranı, hizalı etiket/değer satırları). Kart-başına "İzleme yok".
 */
export default function WeeklyMonitoringStrip({ stats, loading, t }) {
  if (loading && !stats) return <div className="wr-mon wr-mon--loading">…</div>
  if (!stats?.types) return null
  const byType = {}
  stats.types.forEach((x) => { byType[x.type] = norm(x) })

  return (
    <div className="wr-mon">
      <div className="wr-mon-title">{t('wr.monTitle')}</div>
      <div className="wr-mon-grid">
        {ORDER.filter((k) => byType[k]).map((k) => {
          const s = byType[k]
          const meta = TYPE_META[k]
          const Icon = meta.icon
          const empty = s.active === 0 && s.checks === 0
          const band = rateBand(s.rate)
          return (
            <div key={k} className="wr-mon-card" data-band={band}>
              <div className="wr-mon-card-top"><Icon size={14} className="wr-mon-icon" /><span className="wr-mon-card-name">{t(meta.labelKey)}</span></div>
              {empty ? (
                <div className="wr-mon-empty">{t('wr.monNoMonitors')}</div>
              ) : (
                <>
                  <div className={`wr-mon-rate wr-mon-rate--${band}`}>
                    <span className="wr-mon-rate-num">{s.rate != null ? s.rate.toFixed(1) : '—'}</span>
                    {s.rate != null && <span className="wr-mon-rate-pct">%</span>}
                    {s.rateDelta != null && s.rateDelta !== 0 && (
                      <span className={`wr-mon-delta ${s.rateDelta > 0 ? 'up' : 'down'}`}>{s.rateDelta > 0 ? '▲' : '▼'} {Math.abs(s.rateDelta).toFixed(1)}</span>
                    )}
                  </div>
                  <div className="wr-mon-metrics">
                    <Metric label={t('wr.monLblActive')} value={s.active} />
                    <Metric label={t('wr.monLblChecks')} value={s.checks} />
                    <Metric label={t('wr.monLblOpened')} value={s.opened} />
                    <Metric label={t('wr.monLblResolved')} value={s.resolved} />
                    <Metric label={t('wr.monLblOpen')} value={s.open} danger={s.open > 0} />
                    {meta.extraLabel && s.extra != null && (
                      <Metric label={t(meta.extraLabel)} value={meta.extraUnit === 'ms' ? `${Math.round(s.extra)} ms` : Math.round(s.extra)} />
                    )}
                  </div>
                  {s.top3.length > 0 && (
                    <div className="wr-mon-top">
                      <div className="wr-mon-top-hdr">{t('wr.monTop3')}</div>
                      {s.top3.map((tt, i) => (
                        <div key={`${tt.name}#${i}`} className="wr-mon-top-row">
                          <span className="wr-mon-top-name" title={tt.name}>{tt.name}</span>
                          <span className="wr-mon-top-val">{tt.rate != null ? `${tt.rate.toFixed(1)}%` : (tt.alarms > 0 ? t('wr.monAlarmsShort', tt.alarms) : '—')}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
