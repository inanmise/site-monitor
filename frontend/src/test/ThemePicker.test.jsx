import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, act, within, waitFor } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import ThemePicker from '../components/theme/ThemePicker.jsx'
import ThemePreviewBar from '../components/theme/ThemePreviewBar.jsx'
import { useTheme } from '../i18n/theme.jsx'

vi.mock('../api/client', async () => {
  const { withApiFallback } = await import('./apiMock.js')
  return { api: withApiFallback({}), formatDate: (s) => String(s ?? '') }
})
import MobileTopBar from '../components/nav/MobileTopBar.jsx'

/**
 * Kullanıcı tema seçicisi (2026-10-05): yalnız yöneticinin AÇIK bıraktığı temalar, renk örneği + ad + şema ipucu, etkin
 * olanda onay; klavye ile açılır/gezinilir/seçilir; seçim localStorage'a yazılır; telefonda üst çubukta 40 px tetik.
 */
const KEY = 'site-monitor-theme'
const POLICY_KEY = 'site-monitor-theme-policy'
const html = () => document.documentElement

let themeApi
function Grab() { themeApi = useTheme(); return null }

beforeEach(() => {
  localStorage.removeItem(KEY)
  localStorage.removeItem(POLICY_KEY)
})
afterEach(() => {
  localStorage.removeItem(KEY)
  localStorage.removeItem(POLICY_KEY)
})

const trigger = () => screen.getByRole('button', { name: /^(Theme|Tema): / })
const options = () => screen.getAllByRole('menuitemradio')

describe('ThemePicker', () => {
  it('tetik etkin temayı adıyla duyurur; menü YALNIZ açık temaları listeler (renk örneği + şema ipucu)', () => {
    localStorage.setItem(POLICY_KEY, JSON.stringify({ enabled: ['light', 'dark', 'crucible', 'parchment'], default: 'system' }))
    render(<ThemePicker />)
    expect(trigger()).toHaveAccessibleName(/^(Theme|Tema): (Light|Açık)$/)
    pressMenuTrigger(trigger())
    expect(options().map((o) => o.getAttribute('data-theme-id'))).toEqual(['light', 'dark', 'parchment', 'crucible'])
    for (const o of options()) expect(o.querySelector('[data-slot="theme-swatch"]')).not.toBeNull()
    const crucible = screen.getByRole('menuitemradio', { name: /^Crucible (Dark scheme|Koyu şema)$/ })
    expect(crucible).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('menuitemradio', { name: /^(Light|Açık) /i })).toHaveAttribute('aria-checked', 'true')
    // renk örneği temanın kendi şemasında çizilir (iç içe data-theme / data-scheme)
    const sw = crucible.querySelector('[data-slot="theme-swatch"]')
    expect(sw).toHaveAttribute('data-theme', 'crucible')
    expect(sw).toHaveAttribute('data-scheme', 'dark')
  })

  it('seçim anında uygulanır, saklanır, onay işareti taşınır; menü açık kalır', () => {
    render(<ThemePicker />)
    pressMenuTrigger(trigger())
    fireEvent.click(screen.getByRole('menuitemradio', { name: /^Blueprint/ }))
    expect(html().getAttribute('data-theme')).toBe('blueprint')
    expect(html().getAttribute('data-scheme')).toBe('dark')
    expect(localStorage.getItem(KEY)).toBe('blueprint')
    const bp = screen.getByRole('menuitemradio', { name: /^Blueprint/ })
    expect(bp).toHaveAttribute('aria-checked', 'true')
    expect(bp.querySelector('[data-slot="theme-option-check"]')).not.toBeNull()
    expect(screen.getAllByRole('menuitemradio').filter((o) => o.querySelector('[data-slot="theme-option-check"]'))).toHaveLength(1)
    expect(screen.getByRole('menu')).toBeInTheDocument()
  })

  it('klavye: Enter ile açılır, oklarla gezinilir, Enter seçer', async () => {
    localStorage.setItem(POLICY_KEY, JSON.stringify({ enabled: ['light', 'dark', 'slag'], default: 'system' }))
    render(<ThemePicker />)
    act(() => { trigger().focus() })
    fireEvent.keyDown(trigger(), { key: 'Enter' })
    expect(screen.getByRole('menu')).toBeInTheDocument()
    const items = options()
    act(() => { items[0].focus() })
    // Radix roving odak, ok tuşundan sonra odağı bir zamanlayıcıyla taşır
    fireEvent.keyDown(items[0], { key: 'ArrowDown' })
    await waitFor(() => expect(document.activeElement).toBe(items[1]))
    fireEvent.keyDown(items[1], { key: 'ArrowDown' })
    await waitFor(() => expect(document.activeElement).toBe(items[2]))
    fireEvent.keyDown(items[2], { key: 'Enter' })
    expect(html().getAttribute('data-theme')).toBe('slag')
    expect(localStorage.getItem(KEY)).toBe('slag')
  })

  it('yönetici seçili temayı kaldırırsa liste güncellenir ve varsayılan uygulanır', () => {
    localStorage.setItem(KEY, 'obsidian')
    render(<><Grab /><ThemePicker /></>)
    expect(html().getAttribute('data-theme')).toBe('obsidian')
    act(() => themeApi.setPolicy({ enabled: ['light', 'dark', 'alloy'], default: 'alloy' }))
    expect(html().getAttribute('data-theme')).toBe('alloy')
    pressMenuTrigger(trigger())
    expect(options().map((o) => o.getAttribute('data-theme-id'))).toEqual(['light', 'dark', 'alloy'])
    expect(screen.queryByRole('menuitemradio', { name: /^Obsidian/ })).toBeNull()
  })

  it('telefon üst çubuğunda: 40 px tetik (size-10) seçiciyi açar', () => {
    render(withSidebar(<MobileTopBar onTabChange={() => {}} username="u1" />))
    const bar = document.querySelector('[data-slot="mobile-top-bar"]')
    const btn = within(bar).getByRole('button', { name: /^(Theme|Tema): / })
    expect(btn.className).toMatch(/\bsize-10\b/)
    pressMenuTrigger(btn)
    expect(options()).toHaveLength(8)
  })
})

describe('ThemePreviewBar', () => {
  it('önizleme yokken çizilmez; önizlemede adı, "Bu temayı kullan" (açıksa) ve "Önizlemeyi bitir" gösterir', () => {
    localStorage.setItem(KEY, 'light')
    render(<><Grab /><ThemePreviewBar /></>)
    expect(document.querySelector('[data-slot="theme-preview-bar"]')).toBeNull()
    act(() => themeApi.startPreview('crucible'))
    const bar = document.querySelector('[data-slot="theme-preview-bar"]')
    expect(bar).toHaveTextContent(/Crucible/)
    expect(html().getAttribute('data-theme')).toBe('crucible')
    fireEvent.click(within(bar).getByRole('button', { name: /End preview|Önizlemeyi bitir/ }))
    expect(html().getAttribute('data-theme')).toBe('light')
    expect(document.querySelector('[data-slot="theme-preview-bar"]')).toBeNull()

    act(() => themeApi.startPreview('slag'))
    fireEvent.click(screen.getByRole('button', { name: /Use this theme|Bu temayı kullan/ }))
    expect(localStorage.getItem(KEY)).toBe('slag')
    expect(themeApi.preview).toBeNull()
  })

  it('listede KAPALI temanın önizlemesinde "Bu temayı kullan" yok', () => {
    render(<><Grab /><ThemePreviewBar /></>)
    act(() => themeApi.setPolicy({ enabled: ['light', 'dark'], default: 'system' }))
    act(() => themeApi.startPreview('obsidian'))
    expect(screen.queryByRole('button', { name: /Use this theme|Bu temayı kullan/ })).toBeNull()
    expect(screen.getByRole('button', { name: /End preview|Önizlemeyi bitir/ })).toBeInTheDocument()
  })
})
