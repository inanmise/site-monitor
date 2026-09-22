import { useMemo, useState } from 'react'
import { Globe, CalendarPlus, ExternalLink } from 'lucide-react'
import { formatDateOnly } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import { todayKey } from './forecastModel.js'

/**
 * Vade Takvimi → "Alan adı bitişleri" paneli (2026-09-22, alan adı denetimi madde F).
 *
 * Sertifika modeline (parmak izi, issuer, tier-lead, plan durumu = not_before) KARIŞTIRILMAZ: alan adı kaydı
 * ayrı bir vade sınıfıdır ve ayrı seri/renkle çizilir. İçerik: KPI (≤30 / ≤90 / gecikmiş plan), pencere içi günlük
 * yoğunluk şeridi (mor), sıralı liste (takım, registrar, plan rozeti) → tıklayınca alan adı izlemesi derin bağlantısı.
 * Ölçülemeyen (UNKNOWN, kalan gün yok) satırlar listenin sonunda "—" ile durur, gizlenmez.
 */
const RANGES = [30, 90, 180]
const DAY_MS = 86_400_000

export default function ForecastDomainsPanel({ domains = [], t, num = '07' }) {
  const [range, setRange] = useState(90)
  const today = todayKey()
  const list = useMemo(() => domains.filter(d => d.days_remaining == null || d.days_remaining <= range), [domains, range])
  const kpi = useMemo(() => ({
    // Dolmuşlar ayrı KPI'da; "≤N gün" yalnız henüz dolmamışları sayar (aynı kayıt iki kez sayılmasın)
    soon30: domains.filter(d => d.days_remaining != null && d.days_remaining >= 0 && d.days_remaining <= 30).length,
    soon90: domains.filter(d => d.days_remaining != null && d.days_remaining >= 0 && d.days_remaining <= 90).length,
    expired: domains.filter(d => d.days_remaining != null && d.days_remaining < 0).length,
    overdue: domains.filter(d => d.renewal_overdue).length,
    unknown: domains.filter(d => d.days_remaining == null).length,
  }), [domains])
  // Günlük yoğunluk: pencere içindeki her gün için biten alan adı sayısı (yoğunluk çubuğu, bugünden itibaren)
  const strip = useMemo(() => {
    const counts = new Array(range).fill(0)
    for (const d of domains) {
      if (d.days_remaining == null || d.days_remaining < 0 || d.days_remaining >= range) continue
      counts[d.days_remaining] += 1
    }
    const max = Math.max(1, ...counts)
    return counts.map((c, i) => ({ i, c, h: c ? Math.max(18, Math.round((c / max) * 100)) : 0 }))
  }, [domains, range])
  const cls = (d) => d.days_remaining == null ? 'later' : d.days_remaining < 0 ? 'overdue'
    : d.days_remaining <= (d.critical_days ?? 7) ? 'critical' : d.days_remaining <= (d.warning_days ?? 30) ? 'warning' : 'later'

  return (
    <div className="fc-card fc-section-card fc-domains">
      <div className="fc-sec-header">
        <span className="fc-sec-num">{num}</span>
        <span className="fc-sec-title"><Globe size={14} /> {t('forecast.domTitle')}</span>
        <span className="fc-sec-badge">{t('forecast.domCount', domains.length)}</span>
        <div className="fc-range-filter">{RANGES.map((d) => <button key={d} type="button" className={`fc-range-btn${range === d ? ' active' : ''}`} onClick={() => setRange(d)}>{t('forecast.chartDays', d)}</button>)}</div>
      </div>
      <p className="fc-domains-note">{t('forecast.domNote')}</p>
      <div className="fc-domains-kpis">
        <span className={`fc-exp-cls fc-exp-cls--${kpi.expired ? 'overdue' : 'later'}`}>{t('forecast.domKpiExpired', kpi.expired)}</span>
        <span className={`fc-exp-cls fc-exp-cls--${kpi.soon30 ? 'critical' : 'later'}`}>{t('forecast.domKpiSoon', 30, kpi.soon30)}</span>
        <span className={`fc-exp-cls fc-exp-cls--${kpi.soon90 ? 'warning' : 'later'}`}>{t('forecast.domKpiSoon', 90, kpi.soon90)}</span>
        {kpi.overdue > 0 && <span className="fc-exp-cls fc-exp-cls--overdue">{t('forecast.domKpiOverdue', kpi.overdue)}</span>}
        {kpi.unknown > 0 && <span className="fc-exp-cls fc-exp-cls--later">{t('forecast.domKpiUnknown', kpi.unknown)}</span>}
      </div>
      <div className="fc-domains-strip" role="img" aria-label={t('forecast.domStripLabel', range)}>
        {strip.map(({ i, c, h }) => (
          <span key={i} className={`fc-domains-bar${c ? ' has' : ''}`} style={{ height: `${h}%` }}
            title={c ? `${formatDateOnly(new Date(Date.parse(today + 'T00:00:00') + i * DAY_MS).toISOString().slice(0, 10))} · ${t('forecast.domCount', c)}` : undefined} />
        ))}
      </div>
      <div className="fc-domains-strip-foot"><span>{t('forecast.domToday')}</span><span>+{range} {t('card.daysUnit')}</span></div>
      {list.length === 0 ? <div className="fc-no-data">{t('forecast.domNone', range)}</div> : (
        <ul className="fc-intel-list fc-domains-list">
          {list.map((d) => (
            <li key={d.id}>
              <span className={`fc-exp-cls fc-exp-cls--${cls(d)}`}>{d.days_remaining == null ? '—' : d.days_remaining < 0 ? t('dom.expiredAgo') + ' ' + Math.abs(d.days_remaining) : d.days_remaining + ' ' + t('card.daysUnit')}</span>
              <button type="button" className="inv-domain fc-domains-link" onClick={() => navigateTo('domain', { monitor: d.id })} title={t('forecast.domOpen')}>{d.domain} <ExternalLink size={11} /></button>
              <span className="inv-dim">· {d.expiry_date ? formatDateOnly(String(d.expiry_date).slice(0, 10)) : '—'}{d.team_name ? ` · ${d.team_name}` : ''}{d.registrar ? ` · ${d.registrar}` : ''}</span>
              {d.renewal_planned_at && <span className={`ccx-chip ${d.renewal_overdue ? 'ccx-chip--bad' : 'ccx-chip--info'}`}><CalendarPlus size={11} />{d.renewal_overdue ? t('ccx.planOverdue', formatDateOnly(d.renewal_planned_at)) : t('ccx.plan', formatDateOnly(d.renewal_planned_at))}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
