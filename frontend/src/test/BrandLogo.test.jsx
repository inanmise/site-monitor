import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils'
import BrandLogo, { brandLogoSrc } from '../components/BrandLogo.jsx'

describe('BrandLogo (durum-duyarlı marka logosu)', () => {
  it('4 status → doğru /brand varlık yolu; boyut haritası en yakın üst boyutu seçer', () => {
    expect(brandLogoSrc('ok', 32)).toBe('/brand/logo-ok-32.png')
    expect(brandLogoSrc('warning', 40)).toBe('/brand/logo-warning-64.png')
    expect(brandLogoSrc('critical', 96)).toBe('/brand/logo-critical-192.png')
    expect(brandLogoSrc('muted', 300)).toBe('/brand/logo-muted-512.png')
  })

  it('bilinmeyen status → savunmacı olarak ok varyantına düşer', () => {
    expect(brandLogoSrc('banana', 32)).toBe('/brand/logo-ok-32.png')
    const { container } = render(<BrandLogo status="banana" size={32} />)
    expect(container.querySelector('img').getAttribute('src')).toBe('/brand/logo-ok-32.png')
  })

  it('default status ok; alt/aria-label EN sözlükten durumu söylüyor', () => {
    render(<BrandLogo size={32} />)
    const img = screen.getByAltText('SiteMonitor — all systems healthy')
    expect(img.getAttribute('src')).toBe('/brand/logo-ok-32.png')
    expect(img.getAttribute('aria-label')).toBe('SiteMonitor — all systems healthy')
  })

  it('critical status TR sözlükte durumu söylüyor (renk tek sinyal değil)', () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    try {
      render(<BrandLogo status="critical" size={64} />)
      expect(screen.getByAltText('SiteMonitor — açık kritik alarm var')).toBeInTheDocument()
    } finally {
      localStorage.removeItem('site-monitor-lang')
    }
  })
})
