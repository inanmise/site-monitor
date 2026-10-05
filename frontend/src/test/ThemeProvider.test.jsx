import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react'
import { ThemeProvider, useTheme } from '../i18n/theme.jsx'

vi.mock('../api/client', () => ({ api: { getBranding: vi.fn() } }))
import { api } from '../api/client'
import { BrandingProvider } from '../contexts/BrandingProvider.jsx'

/**
 * ThemeProvider (2026-10-05, sekiz tema): `<html data-theme data-scheme>` + color-scheme, kullanıcı seçimi (localStorage),
 * yönetici politikası (açık temalar + varsayılan, BrandingProvider → setPolicy), kapalı/bilinmeyen seçimde varsayılana
 * düşüş, `system` = işletim sistemi tercihi, hızlı açık↔koyu eşlemesi ve sekmeye özel önizleme.
 */
const KEY = 'site-monitor-theme'
const POLICY_KEY = 'site-monitor-theme-policy'
const html = () => document.documentElement

let api_
function Probe() {
  api_ = useTheme()
  return <div data-testid="probe" data-theme={api_.theme} data-scheme={api_.scheme}>{api_.themes.map((t) => t.id).join(',')}</div>
}

function mockOsDark(on) {
  const orig = window.matchMedia
  window.matchMedia = (q) => ({
    matches: on && q.includes('prefers-color-scheme: dark'), media: q, onchange: null,
    addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
  })
  return () => { window.matchMedia = orig }
}

beforeEach(() => {
  localStorage.removeItem(KEY)
  localStorage.removeItem(POLICY_KEY)
  html().removeAttribute('data-theme')
  html().removeAttribute('data-scheme')
  html().style.colorScheme = ''
})
afterEach(() => {
  localStorage.removeItem(KEY)
  localStorage.removeItem(POLICY_KEY)
})

describe('ThemeProvider', () => {
  it('seçim yok + işletim sistemi açık → light; html data-theme/data-scheme/color-scheme yazılır', () => {
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('light')
    expect(html().getAttribute('data-scheme')).toBe('light')
    expect(html().style.colorScheme).toBe('light')
    expect(screen.getByTestId('probe').textContent.split(',')).toHaveLength(8)
  })

  it('seçim yok + işletim sistemi koyu → dark (varsayılan "system")', () => {
    const restore = mockOsDark(true)
    try {
      render(<ThemeProvider><Probe /></ThemeProvider>)
      expect(html().getAttribute('data-theme')).toBe('dark')
      expect(html().getAttribute('data-scheme')).toBe('dark')
    } finally { restore() }
  })

  it('saklı ek tema uygulanır: crucible → data-scheme dark', () => {
    localStorage.setItem(KEY, 'crucible')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('crucible')
    expect(html().getAttribute('data-scheme')).toBe('dark')
    expect(html().style.colorScheme).toBe('dark')
    expect(api_.isDark).toBe(true)
  })

  it('açık şemalı ek tema: parchment → data-scheme light', () => {
    localStorage.setItem(KEY, 'parchment')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('parchment')
    expect(html().getAttribute('data-scheme')).toBe('light')
  })

  it('bilinmeyen saklı değer → yönetici varsayılanı (system → işletim sistemi)', () => {
    localStorage.setItem(KEY, 'sepia')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('light')
    expect(api_.choice).toBeNull()
  })

  it('yönetici seçilen temayı KALDIRINCA varsayılana düşer; saklı seçim silinmez, tema yeniden açılınca geri gelir', () => {
    localStorage.setItem(KEY, 'crucible')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('crucible')
    act(() => api_.setPolicy({ enabled: ['light', 'dark', 'slag'], default: 'slag' }))
    expect(html().getAttribute('data-theme')).toBe('slag')
    expect(screen.getByTestId('probe').textContent).toBe('light,dark,slag')
    expect(localStorage.getItem(KEY)).toBe('crucible')
    act(() => api_.setPolicy({ enabled: ['light', 'dark', 'crucible'], default: 'system' }))
    expect(html().getAttribute('data-theme')).toBe('crucible')
  })

  it('politika önbelleklenir: sonraki açılış (ilk boyama) kaldırılmış temayı bir an bile göstermez', () => {
    localStorage.setItem(KEY, 'obsidian')
    const first = render(<ThemeProvider><Probe /></ThemeProvider>)
    act(() => api_.setPolicy({ enabled: ['light', 'dark'], default: 'system' }))
    first.unmount()
    expect(JSON.parse(localStorage.getItem(POLICY_KEY))).toEqual({ enabled: ['light', 'dark'], default: 'system' })
    html().removeAttribute('data-theme')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    expect(html().getAttribute('data-theme')).toBe('light')
  })

  it('setTheme saklar; bilinmeyen kimlik reddedilir', () => {
    render(<ThemeProvider><Probe /></ThemeProvider>)
    act(() => api_.setTheme('blueprint'))
    expect(localStorage.getItem(KEY)).toBe('blueprint')
    expect(html().getAttribute('data-theme')).toBe('blueprint')
    act(() => api_.setTheme('sepia'))
    expect(localStorage.getItem(KEY)).toBe('blueprint')
  })

  it('toggle: şema karşılığına geçer (crucible → light, light → dark); karşı şemada açık tema yoksa değişmez', () => {
    localStorage.setItem(KEY, 'crucible')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    act(() => api_.toggle())
    expect(html().getAttribute('data-theme')).toBe('light')
    act(() => api_.toggle())
    expect(html().getAttribute('data-theme')).toBe('dark')
    act(() => api_.setPolicy({ enabled: ['parchment', 'obsidian'], default: 'parchment' }))
    expect(html().getAttribute('data-theme')).toBe('parchment')
    act(() => api_.toggle())
    expect(html().getAttribute('data-theme')).toBe('obsidian')
    act(() => api_.setPolicy({ enabled: ['slag', 'crucible'], default: 'slag' }))
    act(() => api_.toggle())
    expect(html().getAttribute('data-theme')).toBe('slag')   // açık şemalı tema yok → değişmez
  })

  it('önizleme yalnız bu sekmede: saklanmaz, bitirince geri döner; tema seçimi önizlemeyi bitirir', () => {
    localStorage.setItem(KEY, 'alloy')
    render(<ThemeProvider><Probe /></ThemeProvider>)
    act(() => api_.startPreview('obsidian'))
    expect(html().getAttribute('data-theme')).toBe('obsidian')
    expect(html().getAttribute('data-scheme')).toBe('dark')
    expect(api_.preview).toBe('obsidian')
    expect(localStorage.getItem(KEY)).toBe('alloy')
    act(() => api_.endPreview())
    expect(html().getAttribute('data-theme')).toBe('alloy')
    act(() => api_.startPreview('slag'))
    act(() => api_.setTheme('dark'))
    expect(api_.preview).toBeNull()
    expect(html().getAttribute('data-theme')).toBe('dark')
  })

  it('BrandingProvider: /api/branding "themes" alanı politikayı uygular (giriş sayfası da uyar)', async () => {
    localStorage.setItem(KEY, 'crucible')
    api.getBranding.mockResolvedValue({ success: true, data: { app_name: 'X', themes: { enabled: ['light', 'dark', 'parchment'], default: 'parchment' } } })
    render(<ThemeProvider><BrandingProvider><Probe /></BrandingProvider></ThemeProvider>)
    await waitFor(() => expect(html().getAttribute('data-theme')).toBe('parchment'))
    expect(screen.getByTestId('probe').textContent).toBe('light,dark,parchment')
  })

  it('BrandingProvider: themes alanı yoksa (eski sunucu) politika değişmez', async () => {
    localStorage.setItem(KEY, 'slag')
    api.getBranding.mockResolvedValue({ success: true, data: { app_name: 'X' } })
    render(<ThemeProvider><BrandingProvider><Probe /></BrandingProvider></ThemeProvider>)
    await waitFor(() => expect(api.getBranding).toHaveBeenCalled())
    expect(html().getAttribute('data-theme')).toBe('slag')
  })

  it('kullanım: düğmeyle toggle (fireEvent) state günceller', () => {
    function Toggler() {
      const { theme, toggle } = useTheme()
      return <button type="button" onClick={toggle} data-testid="tgl">{theme}</button>
    }
    render(<ThemeProvider><Toggler /></ThemeProvider>)
    fireEvent.click(screen.getByTestId('tgl'))
    expect(screen.getByTestId('tgl').textContent).toBe('dark')
  })
})
