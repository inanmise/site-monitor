import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ search: vi.fn() }) }))
import { api } from '../api/client'
import CommandPalette from '../components/CommandPalette.jsx'

/** Komut paleti (2026-09-12, #1): Ctrl+K açar, sekmeler istemcide, uzak sonuçlar 2+ karakterde, Enter gider. */
describe('CommandPalette', () => {
  const TABS = [{ id: 'dashboard', label: 'Genel Bakış' }, { id: 'weakalgo', label: 'Zayıf Algoritma' }, { id: 'help', label: 'Yardım' }]
  beforeEach(() => { vi.clearAllMocks(); api.search.mockResolvedValue({ success: true, data: [] }) })

  it('kapalı başlar; Ctrl+K açar; Esc kapatır', () => {
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('sekme adı yazınca istemci süzer; Enter onTabChange; 1 karakterde sunucuya gitmez', async () => {
    const onTab = vi.fn()
    render(<CommandPalette tabs={TABS} onTabChange={onTab} />)
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    const input = screen.getByRole('combobox')
    fireEvent.change(input, { target: { value: 'z' } })
    expect(screen.getByText('Zayıf Algoritma')).toBeInTheDocument()
    expect(screen.queryByText('Yardım')).toBeNull()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onTab).toHaveBeenCalledWith('weakalgo')
    expect(api.search).not.toHaveBeenCalled()
  })

  it('2+ karakterde /api/search çağrılır; sertifika sonucu Enter ile sm:navigate (dashboard + domain) yayar', async () => {
    api.search.mockResolvedValue({ success: true, data: [
      { kind: 'certificate', id: 'abc.example.com', label: 'abc.example.com', sub: 'Sahip', tab: 'dashboard', params: { domain: 'abc.example.com' } },
      { kind: 'http', id: '7', label: 'Ödeme', sub: 'https://abc.example.com', tab: 'http', params: { monitor: '7' }, team_name: 'Takım A', group_name: 'Satış', tags: 'prod, odeme', tier: null },
    ] })
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    window.dispatchEvent(new CustomEvent('sm:palette'))
    const input = await screen.findByRole('combobox')
    fireEvent.change(input, { target: { value: 'abc' } })
    await waitFor(() => expect(api.search).toHaveBeenCalledWith('abc'))
    await screen.findByText('abc.example.com')
    expect(screen.getByText('Ödeme')).toBeInTheDocument()
    // 2026-09-20: takım / grup / etiket çipleri satırda
    const row = screen.getByText('Ödeme').closest('[cmdk-item]')   // shadcn Command (cmdk) öğesi
    expect(row.textContent).toContain('Takım A'); expect(row.textContent).toContain('Satış'); expect(row.textContent).toContain('odeme')
    // sekme eşleşmesi yok ('abc') → ilk öğe sertifika
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(nav).toHaveBeenCalled()
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'dashboard', params: { domain: 'abc.example.com' } })
    expect(screen.queryByRole('dialog')).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })
})
