import Sparkline from './Sparkline.jsx'
import { useT } from '../../i18n/index.jsx'

/**
 * Kart içi mini trend + son 5 kontrol noktası (2026-09-12, zenginleştirme #4 ve #14).
 * `spark` = useSparklines haritasındaki tek monitörün kaydı; yoksa hiçbir şey çizilmez (kart değişmez).
 * Çizgi: saatlik ortalama süre (ms). Kırmızı nokta = hatalı kontrol. Sağda 24 sa erişilebilirlik yüzdesi.
 */
export default function MonitorSpark({ spark, unit = 'ms' }) {
  const t = useT()
  if (!spark || !spark.n) return null
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
    </div>
  )
}
