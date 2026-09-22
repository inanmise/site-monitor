import { useT } from '../../i18n/index.jsx'
import { formatDateSec } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { isOutageAlert } from '../../utils/alertKinds.js'

/**
 * Kesinti zaman çizelgesi (2026-09-12, zenginleştirme #12): seçili aralık üzerinde alarm açılış→çözüm
 * segmentleri. Yeşil zemin = alarm yok; kırmızı/turuncu segment = açık alarm süresi (çözülmemişse aralık
 * sonuna dek). Üzerine gelince süre + tür; tıklayınca Alarm Geçmişi'nde o olay. Saf div — kütüphane yok.
 * `alerts`: [{ id, alert_type, alert_level, created_at, resolved, resolved_at }], `range`: { from, to } (UTC ISO, Z'siz).
 */
function ms(iso) { if (!iso) return NaN; return Date.parse(iso.endsWith('Z') ? iso : iso + 'Z') }
function fmtDur(m) {
  if (m < 60) return `${Math.max(1, Math.round(m))} dk`
  const h = Math.floor(m / 60), r = Math.round(m % 60)
  if (h < 24) return r ? `${h} sa ${r} dk` : `${h} sa`
  const d = Math.floor(h / 24)
  return `${d} g ${h % 24} sa`
}

export default function OutageTimeline({ alerts = [], range }) {
  const t = useT()
  if (!range?.from || !range?.to) return null
  const from = ms(range.from), to = ms(range.to)
  if (!(to > from)) return null
  const span = to - from
  // Yalnız KESİNTİ türleri segment/erişilebilirlik; uyarı türleri (HTTP_SSL, *_SLOW, *_EXPIRY…) işaretçi (2026-09-22)
  const outages = alerts.filter((a) => isOutageAlert(a.alert_type))
  const advisories = alerts.filter((a) => !isOutageAlert(a.alert_type))
  const marks = advisories.map((a) => { const at = ms(a.created_at); if (!(at >= from && at <= to)) return null; return { a, left: ((at - from) / span) * 100, open: !(a.resolved && a.resolved_at) } }).filter(Boolean)
  const segs = outages.map((a) => {
    const s = Math.max(from, ms(a.created_at) || from)
    const e = a.resolved && a.resolved_at ? Math.min(to, ms(a.resolved_at)) : to
    if (!(e > s)) return null
    return { a, left: ((s - from) / span) * 100, width: Math.max(0.4, ((e - s) / span) * 100), minutes: (e - s) / 60000, open: !(a.resolved && a.resolved_at) }
  }).filter(Boolean)
  const downMinutes = segs.reduce((n, s) => n + s.minutes, 0)
  const rangeMinutes = span / 60000

  return (
    <div className="otl" role="group" aria-label={t('otl.aria')}>
      <div className="otl-head">
        <span className="otl-title">{t('otl.title')}</span>
        <span className={`otl-sum${segs.length ? ' is-bad' : ' is-ok'}`}>
          {segs.length ? t('otl.summary', segs.length, fmtDur(downMinutes), (100 - Math.min(100, (downMinutes / rangeMinutes) * 100)).toFixed(2)) : t('otl.none')}
        </span>
        {marks.length > 0 && <span className="otl-adv-sum" title={t('otl.advisoryHint')}>{t('otl.advisories', marks.length)}</span>}
      </div>
      <div className="otl-track">
        {segs.map((s, i) => (
          <button type="button" key={`${s.a.id}-${i}`}
            className={`otl-seg${s.open ? ' is-open' : ''} otl-seg--${String(s.a.alert_level || '').toLowerCase()}`}
            style={{ left: `${s.left}%`, width: `${s.width}%` }}
            title={`${s.a.alert_type}${s.a.alert_level ? ` · ${s.a.alert_level}` : ''} · ${formatDateSec(s.a.created_at)} → ${s.open ? t('otl.stillOpen') : formatDateSec(s.a.resolved_at)} · ${fmtDur(s.minutes)}`}
            aria-label={`${s.a.alert_type} ${fmtDur(s.minutes)}`}
            onClick={() => navigateTo('alerthistory', { incident: s.a.id })} />
        ))}
        {marks.map((m, i) => (
          <button type="button" key={`m-${m.a.id}-${i}`} className={`otl-mark${m.open ? ' is-open' : ''}`} style={{ left: `${m.left}%` }}
            title={`${t('otl.advisory')}: ${m.a.alert_type}${m.a.alert_level ? ` · ${m.a.alert_level}` : ''} · ${formatDateSec(m.a.created_at)}${m.open ? ` · ${t('otl.stillOpen')}` : ''}`}
            aria-label={`${t('otl.advisory')} ${m.a.alert_type}`}
            onClick={() => navigateTo('alerthistory', { incident: m.a.id })} />
        ))}
      </div>
      <div className="otl-axis"><span>{formatDateSec(range.from)}</span><span>{formatDateSec(range.to)}</span></div>
    </div>
  )
}
