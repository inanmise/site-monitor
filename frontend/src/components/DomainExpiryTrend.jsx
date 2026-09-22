import { useEffect, useMemo, useState } from 'react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { Activity } from 'lucide-react'

/**
 * Kalan-gün trendi (2026-09-22, alan adı denetimi madde I).
 *
 * Kontrol Geçmişi sekmesindeki "kesinti zaman çizelgesi" bir alan adı kaydı için anlamsızdı (kesinti yok, süre var).
 * Bunun yerine: gün başına kalan-gün çizgisi; yenileme (kalan günün SIÇRAMASI) gözle görülür; registrar/NS/EPP/DNSSEC
 * değişikliği tespit edilen günler işaretlenir (tooltip: ne değişti); uyarı/kritik eşikleri yatay çizgi.
 * Veri `GET /domain/{id}/trend?days=N` — sayfalı geçmişten türetilmez.
 */
const W = 600, H = 96, PAD_Y = 8

export default function DomainExpiryTrend({ monitorId, days = 90, reloadSignal = 0 }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null, error: null })

  useEffect(() => {
    let alive = true
    setState(s => ({ ...s, loading: true }))
    api.monitoring.getDomainTrend(monitorId, days)
      .then(r => { if (alive) setState(r?.success ? { loading: false, data: r.data, error: null } : { loading: false, data: null, error: r?.error || 'error' }) })
      .catch(e => { if (alive) setState({ loading: false, data: null, error: e?.message || 'error' }) })
    return () => { alive = false }
  }, [monitorId, days, reloadSignal])

  const pts = useMemo(() => (state.data?.points || []).filter(p => p.days_remaining != null), [state.data])
  const geo = useMemo(() => {
    if (pts.length === 0) return null
    const vals = pts.map(p => p.days_remaining)
    const warn = state.data?.warning_days ?? 30, crit = state.data?.critical_days ?? 7
    // Y ekseni VERİ aralığına ölçeklenir (3000 gün kalan bir alan adında 0..3000 ölçeği 30 günlük eğimi düz çizgiye
    // çevirirdi); eşik çizgileri yalnız görünür aralığa giriyorsa çizilir, aksi hâlde yalnız açıklamada kalır.
    const vMin = Math.min(...vals), vMax = Math.max(...vals)
    const pad = Math.max(3, Math.round((vMax - vMin) * 0.15))
    const lo = Math.max(vMin - pad, Math.min(vMin, 0)), hi = vMax + pad
    const range = Math.max(1, hi - lo)
    const x = (i) => pts.length === 1 ? W / 2 : (i / (pts.length - 1)) * W
    const y = (v) => H - PAD_Y - ((v - lo) / range) * (H - PAD_Y * 2)
    return { x, y, lo, hi, warn, crit }
  }, [pts, state.data])

  if (state.loading && !state.data) return null
  if (state.error) return <div className="dom-trend dom-trend--empty">{t('dom.trendError')}</div>
  if (!geo) return <div className="dom-trend dom-trend--empty">{t('dom.trendEmpty')}</div>

  const line = pts.map((p, i) => `${geo.x(i).toFixed(1)},${geo.y(p.days_remaining).toFixed(1)}`).join(' ')
  const changes = pts.map((p, i) => ({ p, i })).filter(({ p }) => p.changed)
  // Yenileme: kalan gün bir önceki güne göre ARTMIŞSA (bitiş ileri gitti)
  const renewals = pts.map((p, i) => ({ p, i })).filter(({ p, i }) => i > 0 && p.days_remaining > pts[i - 1].days_remaining + 1)
  const first = pts[0], last = pts[pts.length - 1]

  return (
    <div className="dom-trend" aria-label={t('dom.trendTitle')}>
      <div className="dom-trend-head">
        <span className="dom-trend-title"><Activity size={13} /> {t('dom.trendTitle')}</span>
        <span className="dom-trend-legend">
          <span className="dom-trend-leg dom-trend-leg--warn">{t('dom.warningDays')} {geo.warn}</span>
          <span className="dom-trend-leg dom-trend-leg--crit">{t('dom.criticalDays')} {geo.crit}</span>
          {changes.length > 0 && <span className="dom-trend-leg dom-trend-leg--chg">{t('dom.trendChanges', changes.length)}</span>}
          {renewals.length > 0 && <span className="dom-trend-leg dom-trend-leg--renew">{t('dom.trendRenewals', renewals.length)}</span>}
        </span>
      </div>
      <svg className="dom-trend-svg" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-hidden="true">
        {geo.warn >= geo.lo && geo.warn <= geo.hi && <line x1="0" x2={W} y1={geo.y(geo.warn)} y2={geo.y(geo.warn)} className="dom-trend-th dom-trend-th--warn" />}
        {geo.crit >= geo.lo && geo.crit <= geo.hi && <line x1="0" x2={W} y1={geo.y(geo.crit)} y2={geo.y(geo.crit)} className="dom-trend-th dom-trend-th--crit" />}
        <polyline points={line} className="dom-trend-line" />
        {renewals.map(({ p, i }) => (
          <line key={'r' + i} x1={geo.x(i)} x2={geo.x(i)} y1={PAD_Y} y2={H - PAD_Y} className="dom-trend-renew">
            <title>{t('dom.trendRenewedTip', p.day, p.days_remaining)}</title>
          </line>
        ))}
      </svg>
      {/* Değişiklik işaretçileri SVG dışında (preserveAspectRatio=none daireleri ezerdi): yatay konum yüzdeyle */}
      <div className="dom-trend-marks">
        {changes.map(({ p, i }) => (
          <span key={'c' + i} className="dom-trend-mark" style={{ left: `${(geo.x(i) / W) * 100}%` }}
            title={`${p.day} — ${p.change_detail || t('dom.changedTip')}`} />
        ))}
      </div>
      <div className="dom-trend-foot">
        <span>{first.day} · {first.days_remaining} {t('card.daysUnit')}</span>
        <span>{last.day} · {last.days_remaining} {t('card.daysUnit')} · {t('dom.trendLastCheck')} {formatDateSec(last.checked_at)}</span>
      </div>
    </div>
  )
}
