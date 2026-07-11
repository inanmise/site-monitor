import { useState, useEffect, useRef } from 'react'
import { useT, useLanguage } from '../i18n/index.jsx'
import CertMonitorLogo from '../components/ui/CertMonitorLogo.jsx'
import DashboardPreview from '../components/landing/DashboardPreview.jsx'
import { Check, ChevronDown, Menu, X, Globe, LogIn, ArrowRight,
  ShieldCheck, Award, Lock, Server, Search, Activity, Clock } from 'lucide-react'

/**
 * Public LANDING sayfası (UptimeRobot-esintili, CertMonitor markasına uyarlanmış). Hep-koyu, Türkçe (i18n).
 * Giriş yok — "Giriş Yap" / "Hemen Başla" mevcut <Login>'u açar (App.jsx showLogin gate). Auth akışı korunur.
 */
const SOLUTIONS = [
  { Icon: ShieldCheck, key: 'landing.solSsl' },
  { Icon: Server,      key: 'landing.solPort' },
  { Icon: Globe,       key: 'landing.solDns' },
  { Icon: Search,      key: 'landing.solKeyword' },
  { Icon: Activity,    key: 'landing.solPing' },
  { Icon: Clock,       key: 'landing.solDomain' },
]
const VALUE_PROPS = ['landing.vp1', 'landing.vp2', 'landing.vp3', 'landing.vp4']

export default function Landing({ onGetStarted }) {
  const t = useT()
  const { lang, toggle: toggleLang } = useLanguage()
  const [menuOpen, setMenuOpen] = useState(false)
  const rootRef = useRef(null)

  // Scroll-reveal — kütüphanesiz IntersectionObserver (subtle fade/slide-up).
  useEffect(() => {
    const els = rootRef.current ? rootRef.current.querySelectorAll('.ld-reveal') : []
    if (!('IntersectionObserver' in window)) { els.forEach(e => e.classList.add('is-visible')); return }
    const io = new IntersectionObserver((entries) => {
      entries.forEach(en => { if (en.isIntersecting) { en.target.classList.add('is-visible'); io.unobserve(en.target) } })
    }, { threshold: 0.12 })
    els.forEach(e => io.observe(e))
    return () => io.disconnect()
  }, [])

  const go = () => { setMenuOpen(false); onGetStarted?.() }

  return (
    <div className="ld-root" ref={rootRef}>
      {/* NAV */}
      <header className="ld-nav-wrap" id="top">
        <nav className="ld-nav" aria-label="CertMonitor">
          <a className="ld-nav-brand" href="#top" onClick={() => setMenuOpen(false)}>
            <CertMonitorLogo variant="icon" size={26} /><span>CertMonitor</span>
          </a>
          <button className="ld-nav-burger" aria-label={t('landing.menuToggle')} aria-expanded={menuOpen}
            onClick={() => setMenuOpen(o => !o)}>{menuOpen ? <X size={20} /> : <Menu size={20} />}</button>
          <div className={`ld-nav-menu${menuOpen ? ' is-open' : ''}`}>
            <a href="#features" onClick={() => setMenuOpen(false)}>{t('landing.navFeatures')}</a>
            <div className="ld-nav-drop">
              <button className="ld-nav-drop-btn" type="button">
                {t('landing.navSolutions')} <ChevronDown size={14} />
              </button>
              <div className="ld-nav-drop-menu" role="menu">
                {SOLUTIONS.map(({ Icon, key }) => (
                  <a key={key} href="#features" role="menuitem" onClick={() => setMenuOpen(false)}><Icon size={15} /> {t(key)}</a>
                ))}
              </div>
            </div>
            <a href="#security" onClick={() => setMenuOpen(false)}>{t('landing.navSecurity')}</a>
            <a href="#preview" onClick={() => setMenuOpen(false)}>{t('landing.navHow')}</a>
            <div className="ld-nav-cta">
              <button className="ld-btn ld-btn-ghost" onClick={go}><LogIn size={15} /> {t('landing.login')}</button>
              <button className="ld-btn ld-btn-accent" onClick={go}>{t('landing.getStarted')}</button>
            </div>
          </div>
        </nav>
      </header>

      <main>
        {/* TRUST STRIP */}
        <div className="ld-trust ld-reveal">
          <div className="ld-trust-badges">
            <span className="ld-trust-badge"><ShieldCheck size={13} /> {t('landing.trustEnterprise')}</span>
            <span className="ld-trust-badge"><Lock size={13} /> {t('landing.trustSecure')}</span>
            <span className="ld-trust-badge"><Award size={13} /> {t('landing.trustRealtime')}</span>
          </div>
          <span className="ld-trust-text">{t('landing.trustText')} · <strong>{t('landing.trustUptime')}</strong></span>
        </div>

        {/* HERO */}
        <section className="ld-hero ld-reveal" id="features">
          <h1 className="ld-hero-title">
            {t('landing.heroPre')} <span className="ld-accent">{t('landing.heroAccent')}</span> {t('landing.heroPost')}
          </h1>
          <p className="ld-hero-sub">{t('landing.heroSub')}</p>
          <ul className="ld-vprops">
            {VALUE_PROPS.map(k => (
              <li key={k}><span className="ld-vprop-check"><Check size={14} /></span>{t(k)}</li>
            ))}
          </ul>

          {/* CTA (buton çifti — self-serve domain onboarding yok) */}
          <div className="ld-cta">
            <button className="ld-btn ld-btn-accent ld-btn-lg" onClick={go}>{t('landing.getStarted')} <ArrowRight size={17} /></button>
            <button className="ld-btn ld-btn-ghost ld-btn-lg" onClick={go}><LogIn size={16} /> {t('landing.login')}</button>
          </div>
          <p className="ld-cta-note">{t('landing.ctaNote')}</p>
        </section>

        {/* PRODUCT PREVIEW */}
        <section className="ld-preview-wrap ld-reveal" id="preview">
          <div className="ld-preview-frame">
            <DashboardPreview />
          </div>
          <p className="ld-preview-caption" id="security">{t('landing.previewCaption')}</p>
        </section>
      </main>

      {/* FOOTER */}
      <footer className="ld-footer">
        <div className="ld-footer-inner">
          <div className="ld-footer-brand">
            <CertMonitorLogo variant="icon" size={22} /><span>CertMonitor</span>
            <span className="ld-enterprise-badge">ENTERPRISE</span>
          </div>
          <p className="ld-footer-tagline">{t('landing.footerTagline')}</p>
          <div className="ld-footer-meta">
            <span>v{__APP_VERSION__} &nbsp;·&nbsp; &copy; {new Date().getFullYear()} CertMonitor &nbsp;·&nbsp; {t('landing.footerRights')}</span>
            <button type="button" className="ld-lang-btn" onClick={toggleLang}><Globe size={13} /> {lang === 'tr' ? 'English' : 'Türkçe'}</button>
          </div>
        </div>
      </footer>
    </div>
  )
}
