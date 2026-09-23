import { AlertTriangle } from 'lucide-react'
import Sparkline from './Sparkline.jsx'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { toUtc } from '../../utils/localDay.js'

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

/**
 * 30 günlük kullanılabilirlik / hedef satırı (2026-09-12, #11): hedef altındaysa kırmızı ok, üstündeyse yeşil.
 * 2026-09-24: "x hatalı saat" yerine dönem etiketleri (1 / 7 / 15 / 30 gün) — hata görülen dönemde uyarı ikonu
 * (hedefin altındaysa kırmızı, üstündeyse turuncu); üzerine gelince dönemin kontrol / hata / erişilebilirlik
 * özeti ve hata görülen her saat dilimi o dilimdeki hata ADEDİYLE ("23.09 17:00–18:00 — 5 hata alındı"; en yeni 6,
 * fazlası "+N saat dilimi daha"). Dokuz izleme türünden SLA satırı olan sekizi bu bileşeni kullanır → hepsinde aynı.
 * Sunucu `windows` göndermiyorsa eski "x hatalı saat" metni gösterilir.
 */
function SlaLine({ sla, target, days }) {
  const t = useT()
  if (!sla || !sla.n || sla.up_pct == null) return null
  const below = target != null && sla.up_pct < target
  const wins = Array.isArray(sla.windows) ? sla.windows : null
  return (
    <div className={`mspark-sla${below ? ' is-below' : ' is-met'}`}>
      <span className="mspark-sla-val" title={t('spark.slaTip', sla.n, sla.fail, sla.bad_hours ?? 0)}>{t('spark.sla', days, sla.up_pct.toFixed(2))}</span>
      {target != null && <span className="mspark-sla-target">{t('spark.slaTarget', target)} {below ? '↓' : '✓'}</span>}
      {wins
        ? <span className="mspark-wins">{wins.map((w) => <WindowChip key={w.days} w={w} target={target} t={t} />)}</span>
        : (sla.bad_hours ?? 0) > 0 && <span className="mspark-sla-bad">{t('spark.slaBadHours', sla.bad_hours)}</span>}
    </div>
  )
}

const num = (v) => Number(v || 0).toLocaleString(dateLocale())

/** UTC saat kovası ("yyyy-MM-ddTHH") → yerel "gg.aa SS:dd–SS:dd". */
function slotText(h) {
  const start = new Date(toUtc(`${h}:00:00`))
  if (Number.isNaN(start.getTime())) return String(h)
  const loc = dateLocale()
  const hm = (d) => d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit' })
  return `${start.toLocaleDateString(loc, { day: '2-digit', month: '2-digit' })} ${hm(start)}–${hm(new Date(start.getTime() + 3_600_000))}`
}

/** Tek dönem etiketi: kontrol yok (soluk) · hatasız (yeşil) · hata var (⚠ turuncu) · hata var ve hedefin altında (⚠ kırmızı). */
function WindowChip({ w, target, t }) {
  const label = w.days === 1 ? t('spark.win1') : t('spark.winN', w.days)
  const period = w.days === 1 ? t('spark.winTip1') : t('spark.winTipN', w.days)
  const belowTarget = target != null && w.up_pct != null && w.up_pct < target
  const state = !w.n ? 'none' : w.fail > 0 ? (belowTarget ? 'bad' : 'warn') : 'ok'
  const pct = w.up_pct == null ? '' : w.up_pct.toFixed(2)
  let tip = !w.n ? t('spark.winNoData', period)
    : w.fail > 0 ? t('spark.winTipFail', period, num(w.n), num(w.fail), pct)
    : t('spark.winTipOk', period, num(w.n), pct)
  if (belowTarget) tip += ` · ${t('spark.winBelow', target)}`
  // Hata görülen saat dilimleri — her biri o dilimdeki hata adediyle (1 hata / 5 hata alındı)
  const slots = Array.isArray(w.slots) ? w.slots : []
  for (const s of slots) tip += `\n${slotText(s.h)} — ${s.fail === 1 ? t('spark.slotFail1') : t('spark.slotFailN', num(s.fail))}`
  const more = (w.bad_hours ?? 0) - slots.length
  if (slots.length && more > 0) tip += `\n${t('spark.slotMore', more)}`
  return (
    <span className={`mspark-win is-${state}`} title={tip} aria-label={tip} role="img">
      {(state === 'warn' || state === 'bad') && <AlertTriangle size={11} aria-hidden="true" />}
      {label}
    </span>
  )
}
