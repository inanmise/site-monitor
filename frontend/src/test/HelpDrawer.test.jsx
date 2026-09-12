import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
vi.mock('react-markdown', () => ({ default: ({ children }) => <div data-testid="md">{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
import HelpDrawer, { extractSection, TAB_HELP_SECTION } from '../components/HelpDrawer.jsx'

/** Bağlama duyarlı yardım (2026-09-12, #24): sekme → §14.N; "?" açar, bölüm markdown'ı panelde; Tam kılavuz → help sekmesi. */
describe('HelpDrawer', () => {
  it('extractSection: 14.4 başlığından sonraki başlığa kadar; olmayan bölüm null', () => {
    const md = '## 14. Ekranlar\n### 14.3 Giriş\ngiriş metni\n### 14.4 Genel Bakış\nözet\n- madde\n### 14.5 Detay\nx'
    expect(extractSection(md, '14.4')).toBe('### 14.4 Genel Bakış\nözet\n- madde')
    expect(extractSection(md, '14.99')).toBeNull()
    expect(TAB_HELP_SECTION.weakalgo).toBe('14.13')
  })
  it('"?" düğmesi paneli açar, gerçek kılavuzun §14.13 bölümü gelir; "Tam kılavuz" help sekmesine gider', () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<HelpDrawer tab="weakalgo" />)
    fireEvent.click(screen.getByRole('button', { name: /Bu sayfa için yardım|Help for this page/ }))
    const md = screen.getByTestId('md')
    expect(md.textContent).toMatch(/^### 14\.13/)
    fireEvent.click(screen.getByRole('button', { name: /Tam kılavuz|Full guide/ }))
    expect(nav.mock.calls[0][0].detail.tab).toBe('help')
    expect(screen.queryByRole('dialog')).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })
  it('bölümü olmayan sekme → "ayrı bölüm yok" metni; Esc kapatır', () => {
    render(<HelpDrawer tab="nonexistent" />)
    fireEvent.click(screen.getByRole('button', { name: /Bu sayfa için yardım|Help for this page/ }))
    expect(screen.getByText(/ayrı bir kılavuz bölümü yok|no dedicated guide section/)).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
