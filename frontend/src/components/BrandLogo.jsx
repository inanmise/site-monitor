import { useT } from '../i18n/index.jsx'

/**
 * Marka logosu — durum-duyarlı turp (BRAND.md tek doğruluk kaynağı).
 * UI'da varyant seçimi YALNIZ buradan geçer; sayfalar doğrudan /brand dosyası seçmez.
 * Gövde moru sabittir; durum yalnız yapraklarda. Renk tek sinyal değildir: alt/aria-label
 * durumu i18n metniyle söyler (TR+EN).
 */
const STATUSES = new Set(['ok', 'warning', 'critical', 'muted'])

/** İstenen px boyuta en yakın (>=) üretilmiş varlık boyutu. */
function assetSize(px) {
  if (px <= 32) return 32
  if (px <= 64) return 64
  if (px <= 192) return 192
  return 512
}

export function brandLogoSrc(status, px) {
  const s = STATUSES.has(status) ? status : 'ok'
  return `/brand/logo-${s}-${assetSize(px)}.png`
}

export default function BrandLogo({ status = 'ok', size = 32, withText = false, className = '', style }) {
  const t = useT()
  const s = STATUSES.has(status) ? status : 'ok'   // savunmacı: bilinmeyen durum → nötr
  const alt = t('brand.logo.alt.' + s)
  const img = (
    <img
      src={brandLogoSrc(s, size)}
      width={size}
      height={size}
      alt={alt}
      aria-label={alt}
      className={`brand-logo brand-logo--${s} ${className}`.trim()}
      style={{ display: 'inline-block', transition: 'opacity .25s ease', ...style }}
      draggable={false}
    />
  )
  if (!withText) return img
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
      {img}
      <span style={{ fontWeight: 600 }}>SiteMonitor</span>
    </span>
  )
}
