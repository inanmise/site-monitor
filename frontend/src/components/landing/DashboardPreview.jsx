import { useT } from '../../i18n/index.jsx'
import CertMonitorLogo from '../ui/CertMonitorLogo.jsx'
import { Activity, Siren, Wrench, BarChart3, ChevronLeft, CheckCircle2, Clock, ShieldCheck } from 'lucide-react'

/**
 * Landing "ürün önizlemesi" — CANLI dashboard'un statik, tema-bağımsız (hep-koyu) taklidi.
 * Gerçek panelleri (auth + provider gerektirir) render etmez; placeholder verilerle görünümü verir.
 * Kullanıcı düzenleyebilsin diye tüm etiketler landing.pv* i18n anahtarlarından, sayılar sabit.
 */
export default function DashboardPreview() {
  const t = useT()
  const nav = [
    { Icon: Activity,  label: t('landing.pvMonitoring'), active: true },
    { Icon: Siren,     label: t('landing.pvIncidents') },
    { Icon: Wrench,    label: t('landing.pvMaintenance') },
    { Icon: BarChart3, label: t('landing.pvReports') },
  ]
  return (
    <div className="ld-pv" role="img" aria-label="CertMonitor dashboard preview">
      <aside className="ld-pv-side">
        <div className="ld-pv-brand"><CertMonitorLogo variant="icon" size={18} /><span>CertMonitor</span></div>
        <nav className="ld-pv-nav">
          {nav.map(({ Icon, label, active }) => (
            <div key={label} className={`ld-pv-navitem${active ? ' is-active' : ''}`}><Icon size={14} /><span>{label}</span></div>
          ))}
        </nav>
      </aside>

      <div className="ld-pv-main">
        <div className="ld-pv-crumb"><ChevronLeft size={12} /> {t('landing.pvMonitoring')}</div>

        <div className="ld-pv-header">
          <span className="ld-pv-dot" aria-hidden="true" />
          <div className="ld-pv-header-txt">
            <div className="ld-pv-host">example.akbank.com</div>
            <div className="ld-pv-hostsub">HTTP/S · Client 1 · Server Europe</div>
          </div>
          <span className="ld-pv-badge"><CheckCircle2 size={12} /> {t('landing.pvUp')}</span>
        </div>

        <div className="ld-pv-cards">
          <div className="ld-pv-card">
            <div className="ld-pv-clabel">{t('landing.pvStatus')}</div>
            <div className="ld-pv-cbig ld-pv-green">{t('landing.pvUp')}</div>
            <div className="ld-pv-cnote">{t('landing.pvUpFor')}</div>
          </div>
          <div className="ld-pv-card">
            <div className="ld-pv-clabel"><Clock size={11} /> {t('landing.pvLastCheck')}</div>
            <div className="ld-pv-cbig">{t('landing.pvAgo')}</div>
            <div className="ld-pv-cnote">{t('landing.pvNoIncidents')}</div>
          </div>
          <div className="ld-pv-card">
            <div className="ld-pv-clabel">{t('landing.pvLast24')}</div>
            <div className="ld-pv-bars" aria-hidden="true">{Array.from({ length: 24 }).map((_, i) => <span key={i} className="ld-pv-bar" />)}</div>
            <div className="ld-pv-cnote ld-pv-green">100%</div>
          </div>
          <div className="ld-pv-card">
            <div className="ld-pv-clabel"><ShieldCheck size={11} /> {t('landing.pvCert')}</div>
            <div className="ld-pv-certrow"><span>{t('landing.pvDomainValid')}</span><strong>12.03.2027</strong></div>
            <div className="ld-pv-certrow"><span>{t('landing.pvSslValid')}</span><strong>04.09.2026</strong></div>
          </div>
        </div>

        <div className="ld-pv-uptime">
          <div className="ld-pv-clabel">{t('landing.pvUptime')}</div>
          <div className="ld-pv-uprow">
            <div><span>{t('landing.pv7d')}</span><strong className="ld-pv-green">100%</strong></div>
            <div><span>{t('landing.pv30d')}</span><strong className="ld-pv-green">99.99%</strong></div>
            <div><span>{t('landing.pv365d')}</span><strong>99.95%</strong></div>
          </div>
        </div>
      </div>
    </div>
  )
}
