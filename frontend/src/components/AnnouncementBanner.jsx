import { useEffect, useState } from 'react'
import { Info, AlertTriangle, AlertOctagon, X, ArrowUpRight } from 'lucide-react'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import { useT } from '../i18n/index.jsx'

const DISMISS_KEY = 'sm.banner.dismissedVersion'
/** Hero (giriş sonrası öne çıkan kart) oturumda BİR kez gösterilir. */
const HERO_KEY = 'sm.banner.heroShown'
const HERO_MS = 1500

/** Ton → ikon + sınıf soneki + etiket anahtarı (Dialog.jsx VARIANTS deseni). */
const TONES = {
  INFO:     { Icon: Info,          mod: 'info',  labelKey: 'branding.toneInfo' },
  WARNING:  { Icon: AlertTriangle, mod: 'warn',  labelKey: 'branding.toneWarning' },
  CRITICAL: { Icon: AlertOctagon,  mod: 'crit',  labelKey: 'branding.toneCritical' },
}

function readDismissed() {
  try { return localStorage.getItem(DISMISS_KEY) } catch { return null }
}
function writeDismissed(version) {
  try { localStorage.setItem(DISMISS_KEY, String(version)) } catch { /* in-memory state yeterli */ }
}

/**
 * Kurumsal duyuru şeridi — içerik kolonunun üstünde yapışkan (sol menünün SAĞINDA kalır).
 * Kullanıcı X ile kapatabilir; kapatılan VERSİYON saklanır: admin metni güncelleyince
 * banner-version arttığından şerit herkese yeniden görünür.
 *
 * {@code heroOnMount} verildiğinde (giriş anı) duyuru önce ortada bir kart olarak
 * {@link HERO_MS} kadar durur, sonra üst şeride toplanır — oturumda yalnız bir kez.
 */
export default function AnnouncementBanner({ heroOnMount = false }) {
  const { branding } = useBranding()
  const t = useT()
  const [dismissed, setDismissed] = useState(readDismissed)
  const [hero, setHero] = useState(false)

  const enabled = branding.banner_enabled === true || branding.banner_enabled === 'true'
  const text = branding.banner_text || ''
  const version = String(branding.banner_version ?? 0)
  const visible = enabled && !!text.trim() && dismissed !== version

  useEffect(() => {
    if (!heroOnMount || !visible) return
    let shown = null
    try { shown = sessionStorage.getItem(HERO_KEY) } catch { /* yoksay */ }
    if (shown === version) return
    try { sessionStorage.setItem(HERO_KEY, version) } catch { /* yoksay */ }
    setHero(true)
    const id = setTimeout(() => setHero(false), HERO_MS)
    return () => clearTimeout(id)
  }, [heroOnMount, visible, version])

  if (!visible) return null

  const tone = TONES[branding.banner_tone] || TONES.INFO
  const { Icon } = tone
  const link = branding.banner_link || ''
  const linkLabel = branding.banner_link_label || link
  const close = () => { writeDismissed(version); setDismissed(version); setHero(false) }

  const body = (
    <>
      <span className={`ann-badge ann-badge--${tone.mod}`} aria-hidden="true"><Icon size={15} /></span>
      <span className="ann-body">
        <span className="ann-tone">{t(tone.labelKey)}</span>
        <span className="ann-text">{text}</span>
      </span>
      {link && (
        <a className="ann-link" href={link} target="_blank" rel="noopener noreferrer">
          {linkLabel}<ArrowUpRight size={13} />
        </a>
      )}
      <button className="ann-close" aria-label="close" onClick={close}><X size={15} /></button>
    </>
  )

  return (
    <>
      <div role="status" className={`ann-bar ann-bar--${tone.mod}${hero ? ' ann-bar--hidden' : ''}`}>
        {body}
      </div>
      {hero && (
        <div className="ann-hero-overlay" onClick={() => setHero(false)}>
          <div className={`ann-hero ann-bar--${tone.mod}`} onClick={e => e.stopPropagation()}>
            {body}
          </div>
        </div>
      )}
    </>
  )
}
