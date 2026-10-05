import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { useTheme } from '../i18n/theme.jsx'

vi.mock('../api/client', async () => {
  const { withApiFallback } = await import('./apiMock.js')
  return {
    api: withApiFallback({ themesAdmin: { get: vi.fn(), save: vi.fn() } }),
    formatDate: (s) => String(s ?? ''),
  }
})
import { api } from '../api/client'
import ThemeSettings, { validateThemePolicy } from '../components/admin/ThemeSettings.jsx'

/**
 * Ayarlar → Görünüm → Temalar (2026-10-05): tema kartları (canlı önizleme + "Listede göster" + "Varsayılan" + "Önizle"),
 * sayı özeti, alan yanındaki doğrulama, sunucu alan hatası, kayıt gövdesi, önizleme ve kapsamlı müdürde salt okunur.
 */
const ALL = ['light', 'dark', 'blueprint', 'parchment', 'alloy', 'obsidian', 'slag', 'crucible']
const THEMES = ALL.map((id) => ({ id, scheme: ['light', 'parchment', 'alloy'].includes(id) ? 'light' : 'dark' }))
const view = (over = {}) => ({ success: true, data: {
  enabled: ALL, default: 'system', read_only: false, themes: THEMES, defaults: { enabled: ALL, default: 'system' }, ...over,
} })
const html = () => document.documentElement

let themeApi
function Grab() { themeApi = useTheme(); return null }

const card = (id) => document.querySelector(`[data-slot="theme-card"][data-theme-id="${id}"]`)
const enabledSwitch = (id) => card(id).querySelector('[data-slot="theme-enabled-switch"]')
const defaultRadio = (id) => card(id).querySelector('[data-slot="theme-default-radio"]')
const saveBtn = () => within(document.querySelector('[data-slot="settings-save-bar"]')).getByRole('button', { name: /^(Save|Kaydet)$/ })

async function renderPage(resp = view()) {
  api.themesAdmin.get.mockResolvedValue(resp)
  render(<><Grab /><ThemeSettings /></>)
  await waitFor(() => expect(screen.getByTestId('theme-settings')).toBeInTheDocument())
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.removeItem('site-monitor-theme')
  localStorage.removeItem('site-monitor-theme-policy')
})
afterEach(() => {
  localStorage.removeItem('site-monitor-theme')
  localStorage.removeItem('site-monitor-theme-policy')
})

describe('ThemeSettings', () => {
  it('sekiz kart; her kartın önizlemesi kendi teması/şemasıyla; sayı özeti ve varsayılan çipi', async () => {
    await renderPage(view({ enabled: ['light', 'dark', 'blueprint', 'slag', 'crucible', 'parchment'] }))
    const cards = [...document.querySelectorAll('[data-slot="theme-card"]')]
    expect(cards.map((c) => c.getAttribute('data-theme-id'))).toEqual(ALL)
    const preview = card('blueprint').querySelector('[data-slot="theme-preview"]')
    expect(preview).toHaveAttribute('data-theme', 'blueprint')
    expect(preview).toHaveAttribute('data-scheme', 'dark')
    expect(preview).toHaveAttribute('aria-hidden', 'true')
    expect(card('parchment').querySelector('[data-slot="theme-preview"]')).toHaveAttribute('data-scheme', 'light')
    expect(document.querySelector('[data-slot="themes-count"]')).toHaveTextContent(/6 of 8 themes enabled|8 temadan 6 tanesi açık/)
    expect(card('alloy')).toHaveAttribute('data-enabled', 'false')
    expect(enabledSwitch('alloy')).toHaveAttribute('aria-checked', 'false')
    expect(enabledSwitch('crucible')).toHaveAttribute('aria-checked', 'true')
    expect(defaultRadio('alloy')).toBeDisabled()   // kapalı tema varsayılan yapılamaz
    expect(enabledSwitch('crucible')).toHaveAccessibleName(/Crucible/)
  })

  it('kayıt: liste katalog sırasıyla + varsayılan gönderilir; politika bu sekmeye hemen uygulanır', async () => {
    await renderPage()
    fireEvent.click(enabledSwitch('blueprint'))
    fireEvent.click(enabledSwitch('obsidian'))
    fireEvent.click(defaultRadio('crucible'))
    expect(card('crucible')).toHaveAttribute('data-default', 'true')
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toHaveAttribute('data-dirty', 'true')
    api.themesAdmin.save.mockResolvedValue(view({ enabled: ['light', 'dark', 'parchment', 'alloy', 'slag', 'crucible'], default: 'crucible' }))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.themesAdmin.save).toHaveBeenCalledTimes(1))
    expect(api.themesAdmin.save).toHaveBeenCalledWith({
      enabled: ['light', 'dark', 'parchment', 'alloy', 'slag', 'crucible'], default: 'crucible',
    })
    await waitFor(() => expect(themeApi.policy).toEqual({ enabled: ['light', 'dark', 'parchment', 'alloy', 'slag', 'crucible'], default: 'crucible' }))
    expect(html().getAttribute('data-theme')).toBe('crucible')   // seçimi olmayan kullanıcı → yeni varsayılan
    expect(document.querySelector('[data-slot="settings-save-bar"]')).not.toHaveAttribute('data-dirty')
  })

  it('doğrulama alanın yanında: hiç tema açık değil → liste hatası; kayıt gönderilmez', async () => {
    await renderPage(view({ enabled: ['light'], default: 'light' }))
    fireEvent.click(enabledSwitch('light'))
    fireEvent.click(saveBtn())
    const field = document.querySelector('[data-field="enabled"]')
    await waitFor(() => expect(field).toHaveTextContent(/At least one theme|En az bir tema/))
    expect(api.themesAdmin.save).not.toHaveBeenCalled()
    // düzenleyince hata kalkar
    fireEvent.click(enabledSwitch('slag'))
    expect(document.querySelector('[data-field="enabled"]')).not.toHaveTextContent(/At least one theme|En az bir tema/)
  })

  it('doğrulama: varsayılan tema kapatılırsa "default" alanında hata; system Açık/Koyu olmadan hata', async () => {
    await renderPage(view({ default: 'crucible' }))
    fireEvent.click(enabledSwitch('crucible'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="default"]'))
      .toHaveTextContent(/default theme must be enabled|Varsayılan tema listede açık olmalı/))
    // sistem varsayılanı seçip Koyu'yu kapat
    fireEvent.click(screen.getByRole('radio', { name: /^(System|Sistem)/ }))
    fireEvent.click(enabledSwitch('dark'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="default"]'))
      .toHaveTextContent(/needs both the Light and the Dark|Açık ve Koyu temaların ikisi de/))
    expect(api.themesAdmin.save).not.toHaveBeenCalled()
  })

  it('sunucu alan hatası (400 + field) ilgili alanın altında gösterilir', async () => {
    await renderPage()
    fireEvent.click(enabledSwitch('slag'))
    api.themesAdmin.save.mockResolvedValue({ success: false, status: 400, field: 'default', error: 'sunucu reddi' })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(document.querySelector('[data-field="default"]')).toHaveTextContent('sunucu reddi'))
  })

  it('Önizle: tema yalnız bu sekmede uygulanır (saklanmaz), düğme basılı + rozet; ikinci basış bitirir', async () => {
    localStorage.setItem('site-monitor-theme', 'alloy')
    await renderPage()
    const btn = card('obsidian').querySelector('[data-slot="theme-preview-btn"]')
    fireEvent.click(btn)
    expect(html().getAttribute('data-theme')).toBe('obsidian')
    expect(html().getAttribute('data-scheme')).toBe('dark')
    expect(localStorage.getItem('site-monitor-theme')).toBe('alloy')
    expect(btn).toHaveAttribute('aria-pressed', 'true')
    expect(card('obsidian').querySelector('[data-slot="theme-previewing-badge"]')).not.toBeNull()
    fireEvent.click(btn)
    expect(html().getAttribute('data-theme')).toBe('alloy')
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })

  it('kapsamlı müdür (read_only): anahtar ve radyolar kapalı, kaydet çubuğu yok, not görünür; önizleme çalışır', async () => {
    await renderPage(view({ read_only: true }))
    expect(screen.getByText(/Only a global administrator can change this|Yalnız global yönetici değiştirebilir/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="themes-readonly"]')).not.toBeNull()
    for (const id of ALL) {
      expect(enabledSwitch(id)).toBeDisabled()
      expect(defaultRadio(id)).toBeDisabled()
    }
    expect(screen.getByRole('radio', { name: /^(System|Sistem)/ })).toBeDisabled()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    expect(document.querySelector('[data-slot="themes-reset"]')).toBeNull()
    fireEvent.click(card('slag').querySelector('[data-slot="theme-preview-btn"]'))
    expect(html().getAttribute('data-theme')).toBe('slag')
    act(() => themeApi.endPreview())
  })

  it('"Varsayılana dön" formu sekiz tema + Sistem yapar (kaydetmeden önce kirli)', async () => {
    await renderPage(view({ enabled: ['light', 'dark'], default: 'dark' }))
    fireEvent.click(document.querySelector('[data-slot="themes-reset"]'))
    expect(document.querySelector('[data-slot="themes-count"]')).toHaveTextContent(/8 of 8|8 temadan 8/)
    expect(screen.getByRole('radio', { name: /^(System|Sistem)/ })).toHaveAttribute('aria-checked', 'true')
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toHaveAttribute('data-dirty', 'true')
  })

  it('yükleme hatası görünür', async () => {
    api.themesAdmin.get.mockResolvedValue({ success: false, error: 'yok' })
    render(<ThemeSettings />)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('yok'))
  })

  it('validateThemePolicy (saf): sunucu kurallarıyla aynı', () => {
    const t = (k) => k
    expect(validateThemePolicy({ enabled: [], default: 'system' }, t)).toEqual({ enabled: 'themes.err.noneEnabled' })
    expect(validateThemePolicy({ enabled: ['light', 'slag'], default: 'system' }, t)).toEqual({ default: 'themes.err.systemNeedsBase' })
    expect(validateThemePolicy({ enabled: ['light', 'dark'], default: 'slag' }, t)).toEqual({ default: 'themes.err.defaultDisabled' })
    expect(validateThemePolicy({ enabled: ['slag'], default: 'slag' }, t)).toEqual({})
  })
})
