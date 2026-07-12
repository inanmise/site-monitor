import { ShieldCheck, Globe, Activity, Radio, Network, Server, Search } from 'lucide-react'

/** Tür → {ikon, etiket anahtarı, türe-özgü ekstra metrik anahtarı + birimi}. */
const TYPE_META = {
  cert:    { icon: ShieldCheck, labelKey: 'wr.monTypeCert',    extraKey: 'wr.monExpiring30',  extraUnit: 'count' },
  domain:  { icon: Globe,       labelKey: 'wr.monTypeDomain',  extraKey: 'wr.monExpiring30',  extraUnit: 'count' },
  http:    { icon: Activity,    labelKey: 'wr.monTypeHttp',    extraKey: 'wr.monAvgResp',     extraUnit: 'ms' },
  ping:    { icon: Radio,       labelKey: 'wr.monTypePing',    extraKey: 'wr.monAvgResp',     extraUnit: 'ms' },
  port:    { icon: Network,     labelKey: 'wr.monTypePort',    extraKey: 'wr.monClosedPorts', extraUnit: 'count' },
  dns:     { icon: Server,      labelKey: 'wr.monTypeDns',     extraKey: 'wr.monChanges',     extraUnit: 'count' },
  keyword: { icon: Search,      labelKey: 'wr.monTypeKeyword', extraKey: null,                extraUnit: null },
}
const ORDER = ['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword']

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

/**
 * İzleme Göstergeleri (04 · İzleme) — izleme türü bazında haftalık kart grid'i.
 * `monitoringStats.types` snake_case okur; veri yoksa (kart) "İzleme yok"; stats null → hiç render etmez.
 */
export default function WeeklyMonitoringStrip({ stats, loading, t }) {
  if (loading && !stats) return <div className="wr-mon-strip wr-mon-strip--loading">…</div>
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
          return (
            <div key={k} className="wr-mon-card">
              <div className="wr-mon-card-top"><Icon size={15} className="wr-mon-icon" /><span className="wr-mon-card-name">{t(meta.labelKey)}</span></div>
              {empty ? (
                <div className="wr-mon-empty">{t('wr.monNoMonitors')}</div>
              ) : (
                <>
                  <div className={`wr-mon-rate wr-mon-rate--${rateBand(s.rate)}`}>
                    {s.rate != null ? `${s.rate.toFixed(1)}%` : '—'}
                    {s.rateDelta != null && s.rateDelta !== 0 && (
                      <span className={`wr-mon-delta ${s.rateDelta > 0 ? 'up' : 'down'}`}>{s.rateDelta > 0 ? '▲' : '▼'} {Math.abs(s.rateDelta).toFixed(1)}</span>
                    )}
                  </div>
                  <div className="wr-mon-lines">
                    <span>{t('wr.monActive', s.active)}</span>
                    <span>{t('wr.monChecks', s.checks)}</span>
                    <span>{t('wr.monAlarms', s.opened, s.resolved, s.open)}</span>
                    {meta.extraKey && s.extra != null && (
                      <span>{t(meta.extraKey, meta.extraUnit === 'ms' ? Math.round(s.extra) : Math.round(s.extra))}</span>
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
