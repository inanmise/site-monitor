import { useCallback, useState } from 'react'
import { ShieldCheck, AlertTriangle, OctagonAlert, CircleOff, RefreshCw, ChevronDown, ArrowRight } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { navigateTo } from '../../utils/navigate.js'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Yapılandırma sağlığı kartı (2026-09-12, zenginleştirme #25): Ayarlar 12 sekmeye dağılmış — "kırmızı
 * olan ne" tek kartta. Her satır tıklanınca ilgili ayar bölümüne (ya da nav sekmesine) götürür.
 * Sorunsuzken küçük yeşil şerit; sorun varsa açık liste. 5 dk'da bir görünürken tazelenir.
 */
const SETTINGS_SECTIONS = new Set(['general', 'smtp', 'ldap', 'userpush', 'weeklyavail', 'retention', 'branding'])
/** Kontrol → Genel Ayarlar'daki alan anahtarı (Aç → kaydır + odakla). */
const CHECK_SETTING_KEY = { base_url: 'site.monitor.app.base-url', admin_email: 'site.monitor.system-admin.email', reminder: 'site.monitor.weekly-report.deadline-day' }
const ICON = { ok: ShieldCheck, warn: AlertTriangle, bad: OctagonAlert, off: CircleOff }

export default function ConfigHealthCard({ onOpenSection }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  // Varsayılan KAPALI (kullanıcı kararı 2026-09-13): sorun olsa da kart kendiliğinden açılmaz — başlıktaki
  // sayaç çipleri ("1 sorun · 2 uyarı") zaten yeterli; açan kişinin tercihi bu tarayıcıda kalır.
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('cfg-health-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try {
      const r = await api.admin.getConfigHealth()
      if (r?.success) setData(r.data)
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
    // Bölüm zaten açıksa "Aç" hiçbir şey yapmıyor gibi görünüyordu (QA ISSUE-012): ilgili alan anahtarı
    // da iletilir; GeneralSettings alana kaydırır, odaklar ve kısa süre vurgular.
    if (SETTINGS_SECTIONS.has(c.tab)) { onOpenSection?.(c.tab, CHECK_SETTING_KEY[c.key] || null); return }
    navigateTo(c.tab === 'inventory' ? 'admin' : c.tab)
  }

  function detailText(c) {
    const d = c.detail || ''
    const [kind, rest] = d.includes(':') ? [d.slice(0, d.indexOf(':')), d.slice(d.indexOf(':') + 1)] : [d, '']
    const key = `cfg.detail.${kind}`
    // Ham ISO damgası ("2026-09-12T14:33:10.386063100") arayüz biçimine (QA ISSUE-011)
    const arg = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(rest) ? formatDate(rest.replace(/(\.\d{3})\d+$/, '$1')) : rest
    const tr = t(key, arg)
    return tr === key ? d : tr
  }

  return (
    <section className={`cfg-health cfg-health--${overall}`} aria-label={t('cfg.title')}>
      <button type="button" className="cfg-health-head" aria-expanded={open}
        onClick={() => setOpen((o) => { try { localStorage.setItem('cfg-health-open', String(!o)) } catch { /* yoksay */ } return !o })}>
        <Icon size={18} aria-hidden="true" />
        <span className="cfg-health-title">{t('cfg.title')}</span>
        <span className="cfg-health-summary">
          {data.bad > 0 && <span className="cfg-pill cfg-pill--bad">{data.bad === 1 ? t('cfg.badOne') : t('cfg.bad', data.bad)}</span>}
          {data.warn > 0 && <span className="cfg-pill cfg-pill--warn">{data.warn === 1 ? t('cfg.warnOne') : t('cfg.warn', data.warn)}</span>}
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
                <Button type="button" variant="secondary" size="sm" className="cfg-row-go" onClick={() => go(c)}>
                  {t('cfg.go')} <ArrowRight size={12} aria-hidden="true" />
                </Button>
              </li>
            )
          })}
          <li className="cfg-row cfg-row--foot">
            <Button type="button" variant="secondary" size="sm" onClick={load}><RefreshCw size={12} /> {t('cfg.refresh')}</Button>
          </li>
        </ul>
      )}
    </section>
  )
}
