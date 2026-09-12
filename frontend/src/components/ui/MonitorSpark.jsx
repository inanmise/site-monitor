import Sparkline from './Sparkline.jsx'
import { useT } from '../../i18n/index.jsx'

/**
 * Kart içi mini trend + son 5 kontrol noktası (2026-09-12, zenginleştirme #4 ve #14).
 * `spark` = useSparklines haritasındaki tek monitörün kaydı; yoksa hiçbir şey çizilmez (kart değişmez).
 * Çizgi: saatlik ortalama süre (ms). Kırmızı nokta = hatalı kontrol. Sağda 24 sa erişilebilirlik yüzdesi.
 */
export default function MonitorSpark({ spark, unit = 'ms', sla = null, slaTarget = null, slaDays = 30 }) {
  const t = useT()
  if ((!spark || !spark.n) && !(sla && sla.n)) return null
  if (!spark || !spark.n) return <SlaLine sla={sla} target={slaTarget} days={slaDays} />
  const series = (spark.buckets || []).map((b) => (b.ms == null ? 0 : b.ms))
  const last = spark.last || []
  const up = spark.up_pct
  const upCls = up == null ? '' : up >= 99.9 ? ' is-ok' : up >= 99 ? ' is-warn' : ' is-bad'
  const lastMs = series.length ? series[series.length - 1] : null
  return (
    <div className="mspark" onClick={(e) => e.stopPropagation()}
      title={t('spark.title', spark.n, spark.fail)}>
      <div className="mspark-line">
        {series.length >= 2
          ? <Sparkline data={series} width={120} height={22} color={spark.fail > 0 ? 'var(--danger)' : 'var(--primary)'} label={t('spark.aria')} />
          : <span className="mspark-flat" aria-hidden="true" />}
        {lastMs != null && <span className="mspark-ms">{lastMs}{unit}</span>}
      </div>
      <div className="mspark-foot">
        <span className="mspark-dots" role="img" aria-label={t('spark.lastAria', last.length)}>
          {last.map((c, i) => (
            <span key={i} className={`mspark-dot${c.ok ? ' is-ok' : ' is-fail'}`}
              title={`${c.at ? c.at.replace('T', ' ') : ''}${c.ms != null ? ` · ${c.ms}${unit}` : ''}${c.ok ? '' : ` · ${t('spark.fail')}`}`} />
          ))}
        </span>
        {up != null && <span className={`mspark-up${upCls}`}>{t('spark.up', up)}</span>}
      </div>
      <SlaLine sla={sla} target={slaTarget} days={slaDays} />
    </div>
  )
}

/** 30 günlük kullanılabilirlik / hedef satırı (2026-09-12, #11): hedef altındaysa kırmızı ok, üstündeyse yeşil. */
function SlaLine({ sla, target, days }) {
  const t = useT()
  if (!sla || !sla.n || sla.up_pct == null) return null
  const below = target != null && sla.up_pct < target
  return (
    <div className={`mspark-sla${below ? ' is-below' : ' is-met'}`} title={t('spark.slaTip', sla.n, sla.fail, sla.bad_hours ?? 0)}>
      <span className="mspark-sla-val">{t('spark.sla', days, sla.up_pct.toFixed(2))}</span>
      {target != null && <span className="mspark-sla-target">{t('spark.slaTarget', target)} {below ? '↓' : '✓'}</span>}
      {(sla.bad_hours ?? 0) > 0 && <span className="mspark-sla-bad">{t('spark.slaBadHours', sla.bad_hours)}</span>}
    </div>
  )
}
