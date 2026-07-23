import { useState } from 'react'
import { useBranding } from '../contexts/BrandingProvider.jsx'

const DISMISS_KEY = 'cm.banner.dismissedVersion'

/** Ton → renkler (mevcut tema tonlarıyla uyumlu; INFO mavi, WARNING amber, CRITICAL kırmızı). */
const TONES = {
  INFO:     { bg: '#eff6ff', border: '#bfdbfe', ink: '#1d4ed8' },
  WARNING:  { bg: '#fffbeb', border: '#fde68a', ink: '#b45309' },
  CRITICAL: { bg: '#fef2f2', border: '#fecaca', ink: '#b91c1c' },
}

function readDismissed() {
  try { return localStorage.getItem(DISMISS_KEY) } catch { return null }
}
function writeDismissed(version) {
  try { localStorage.setItem(DISMISS_KEY, String(version)) } catch { /* in-memory state yeterli */ }
}

/**
 * Genel duyuru şeridi — tüm sayfaların (login dahil) üstünde. Kullanıcı X ile kapatabilir;
 * kapatılan VERSİYON saklanır: admin metni güncelleyince banner-version arttığından şerit
 * herkese yeniden görünür.
 */
export default function AnnouncementBanner() {
  const { branding } = useBranding()
  const [dismissed, setDismissed] = useState(readDismissed())

  const enabled = branding.banner_enabled === true || branding.banner_enabled === 'true'
  const text = branding.banner_text || ''
  const version = String(branding.banner_version ?? 0)
  if (!enabled || !text.trim() || dismissed === version) return null

  const tone = TONES[branding.banner_tone] || TONES.INFO
  const link = branding.banner_link || ''
  const linkLabel = branding.banner_link_label || link

  return (
    <div role="status" style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px',
      background: tone.bg, borderBottom: `1px solid ${tone.border}`, color: tone.ink,
      fontSize: 13, lineHeight: 1.4,
    }}>
      <span style={{ flex: 1 }}>
        {text}
        {link && (
          <a href={link} target="_blank" rel="noopener noreferrer"
             style={{ marginLeft: 8, color: tone.ink, fontWeight: 600, textDecoration: 'underline' }}>
            {linkLabel}
          </a>
        )}
      </span>
      <button aria-label="close" onClick={() => { writeDismissed(version); setDismissed(version) }}
              style={{ background: 'none', border: 'none', cursor: 'pointer', color: tone.ink,
                       fontSize: 16, lineHeight: 1, padding: '0 2px' }}>
        ×
      </button>
    </div>
  )
}
