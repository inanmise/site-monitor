import { useCallback, useState } from 'react'
import { ShieldCheck, AlertTriangle, OctagonAlert, CircleOff, RefreshCw, ChevronDown, ArrowRight } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { navigateTo } from '../../utils/navigate.js'
import { Spinner } from '../ui/Progress.jsx'

/**
 * Yapılandırma sağlığı kartı (2026-09-12, zenginleştirme #25): Ayarlar 12 sekmeye dağılmış — "kırmızı
 * olan ne" tek kartta. Her satır tıklanınca ilgili ayar bölümüne (ya da nav sekmesine) götürür.
 * Sorunsuzken küçük yeşil şerit; sorun varsa açık liste. 5 dk'da bir görünürken tazelenir.
 */
const SETTINGS_SECTIONS = new Set(['general', 'smtp', 'ldap', 'userpush', 'weeklyavail', 'retention', 'branding'])
const ICON = { ok: ShieldCheck, warn: AlertTriangle, bad: OctagonAlert, off: CircleOff }

export default function ConfigHealthCard({ onOpenSection }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [open, setOpen] = useState(false)

  const load = useCallback(async () => {
    try {
      const r = await api.admin.getConfigHealth()
      if (r?.success) { setData(r.data); setOpen((o) => o || r.data?.overall !== 'ok') }
    } catch { /* kart süs — ayar sayfası etkilenmez */ }
    finally { setLoading(false) }
  }, [])
  useVisibleInterval(load, 300_000, true)

  if (loading && !data) return <div className="cfg-health cfg-health--loading"><Spinner size={14} inline decorative /> {t('cfg.loading')}</div>
  if (!data) return null

  const overall = data.overall || 'ok'
  const Icon = ICON[overall] || ShieldCheck
  const checks = data.checks || []

  function go(c) {
    if (SETTINGS_SECTIONS.has(c.tab)) { onOpenSection?.(c.tab); return }
    navigateTo(c.tab === 'inventory' ? 'admin' : c.tab)
  }

  function detailText(c) {
    const d = c.detail || ''
    const [kind, rest] = d.includes(':') ? [d.slice(0, d.indexOf(':')), d.slice(d.indexOf(':') + 1)] : [d, '']
    const key = `cfg.detail.${kind}`
    const tr = t(key, rest)
    return tr === key ? d : tr
  }

  return (
    <section className={`cfg-health cfg-health--${overall}`} aria-label={t('cfg.title')}>
      <button type="button" className="cfg-health-head" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon size={18} aria-hidden="true" />
        <span className="cfg-health-title">{t('cfg.title')}</span>
        <span className="cfg-health-summary">
          {data.bad > 0 && <span className="cfg-pill cfg-pill--bad">{t('cfg.bad', data.bad)}</span>}
          {data.warn > 0 && <span className="cfg-pill cfg-pill--warn">{t('cfg.warn', data.warn)}</span>}
          <span className="cfg-pill cfg-pill--ok">{t('cfg.ok', data.ok)}</span>
        </span>
        <ChevronDown size={16} className={`cfg-health-chevron${open ? ' is-open' : ''}`} aria-hidden="true" />
      </button>
      {open && (
        <ul className="cfg-health-list">
          {checks.map((c) => {
            const CI = ICON[c.status] || ShieldCheck
            return (
              <li key={c.key} className={`cfg-row cfg-row--${c.status}`}>
                <CI size={14} aria-hidden="true" />
                <span className="cfg-row-name">{t(`cfg.check.${c.key}`)}</span>
                <span className="cfg-row-detail">{detailText(c)}</span>
                <button type="button" className="btn btn-sm btn-secondary cfg-row-go" onClick={() => go(c)}>
                  {t('cfg.go')} <ArrowRight size={12} aria-hidden="true" />
                </button>
              </li>
            )
          })}
          <li className="cfg-row cfg-row--foot">
            <button type="button" className="btn btn-sm btn-secondary" onClick={load}><RefreshCw size={12} /> {t('cfg.refresh')}</button>
          </li>
        </ul>
      )}
    </section>
  )
}
