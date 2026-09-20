import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? ''), api: withApiFallback({ me: { inbox: vi.fn(), inboxHistory: vi.fn() } }) }))
import { api } from '../api/client'
import InboxBell, { fmtDuration } from '../components/InboxBell.jsx'

/** Bildirim kutusu v2 (2026-09-20): takım + başlangıç/süre, izlemeye git, çoklu seçim, temizle, geçmiş sekmesi. */
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const ITEMS = [
  { key: 'alert:9', kind: 'alert_open', level: 'CRITICAL', title: 'down.example.com', sub: 'HTTP_DOWN', at: iso(3 * 3600e3), tab: 'alerthistory', params: { alert: 9 },
    team_id: 7, team_name: 'Takım A', started_at: iso(3 * 3600e3 + 12 * 60e3), monitor_tab: 'http', monitor_params: { monitor: 77 }, monitor_name: 'Ana site' },
  { key: 'resolved:4:x', kind: 'alert_resolved', level: 'OK', title: 'ok.example.com', sub: 'PING_DOWN', at: iso(3600e3), tab: 'alerthistory', params: { alert: 4, view: 'closed' },
    team_id: 7, team_name: 'Takım A', started_at: iso(3 * 3600e3), ended_at: iso(3600e3), monitor_tab: 'ping', monitor_params: { monitor: 5 } },
  { key: 'maint:3:x', kind: 'maintenance_soon', level: 'INFO', title: 'Gece bakımı', at: '2026-09-12T22:00:00', tab: 'maintenance', params: { window: 3 } },
]

describe('InboxBell v2', () => {
  beforeEach(() => {
    vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ }
    api.me.inbox.mockResolvedValue({ success: true, data: ITEMS })
    api.me.inboxHistory.mockResolvedValue({ success: true, data: [ITEMS[1]], total: 41, page: 0, size: 25, total_pages: 2 })
  })
  const openBox = async () => {
    render(<InboxBell username="u1" />)
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    return screen.getByRole('dialog')
  }

  it('satırda takım, "başladı … · 3 sa 12 dk açık" ve çözülende "sürdü 2 sa"; bakımda takım yok', async () => {
    const dlg = await openBox()
    const open = within(dlg).getByText('down.example.com').closest('.inbox-row')
    expect(open.textContent).toContain('Takım A')
    expect(open.textContent).toMatch(/başladı|started/)
    expect(open.textContent).toMatch(/3 sa 12 dk açık|open for 3 h 12 min/)
    expect(open.textContent).toContain('Ana site')
    const res = within(dlg).getByText('ok.example.com').closest('.inbox-row')
    expect(res.textContent).toMatch(/sürdü 2 sa 0 dk|lasted 2 h 0 min/)
    expect(res.textContent).toMatch(/çözüldü|resolved/)
    const maint = within(dlg).getByText('Gece bakımı').closest('.inbox-row')
    expect(maint.querySelector('.inbox-team')).toBeNull()
  })

  it('"İzlemeye git" izleme sekmesine monitor paramıyla gider; ana tıklama Alarm Geçmişi\\u0027ne', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const dlg = await openBox()
    const row = within(dlg).getByText('down.example.com').closest('.inbox-row')
    fireEvent.click(within(row).getByRole('button', { name: /İzlemeye git|Go to monitor/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 77 } })
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    fireEvent.click(screen.getByText('down.example.com').closest('button'))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { alert: 9 } })
    // bakım satırının izleme eylemi yok
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    const maint = screen.getByText('Gece bakımı').closest('.inbox-row')
    expect(within(maint).queryByRole('button', { name: /İzlemeye git|Go to monitor/ })).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })

  it('çoklu seçim: iki satır seç → "Seçilenleri okundu say" rozeti 3→1; "Seçilenleri temizle" listeden düşürür ve saklanır', async () => {
    const dlg = await openBox()
    await waitFor(() => expect(document.querySelector('.sb-bell-badge')?.textContent).toBe('3'))
    fireEvent.click(within(dlg).getByLabelText(/down\.example\.com seç|Select down\.example\.com/))
    fireEvent.click(within(dlg).getByLabelText(/ok\.example\.com seç|Select ok\.example\.com/))
    const bar = within(dlg).getByTestId('inbox-selbar')
    expect(bar.textContent).toMatch(/2 seçili|2 selected/)
    fireEvent.click(within(bar).getByRole('button', { name: /Seçilenleri okundu say|Mark selected as read/ }))
    expect(document.querySelector('.sb-bell-badge').textContent).toBe('1')
    expect(within(dlg).queryByTestId('inbox-selbar')).toBeNull()
    fireEvent.click(within(dlg).getByLabelText(/Gece bakımı seç|Select Gece bakımı/))
    fireEvent.click(within(within(dlg).getByTestId('inbox-selbar')).getByRole('button', { name: /Seçilenleri temizle|Clear selected/ }))
    expect(within(dlg).queryByText('Gece bakımı')).toBeNull()
    expect(JSON.parse(localStorage.getItem('inbox-dismissed:u1'))).toEqual(['maint:3:x'])
    expect(document.querySelector('.sb-bell-badge')).toBeNull()
  })

  it('"Tümünü temizle" listeyi boşaltır, "Temizlenenleri göster" geri getirir', async () => {
    const dlg = await openBox()
    fireEvent.click(within(dlg).getByRole('button', { name: /Tümünü temizle|Clear all/ }))
    expect(within(dlg).getByText(/3 bildirim temizlendi|3 cleared/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Temizlenenleri göster|Show cleared/ }))
    expect(within(dlg).getByText('down.example.com')).toBeInTheDocument()
    expect(within(dlg).getByText('down.example.com').closest('.inbox-row').className).toContain('is-dismissed')
  })

  it('Geçmiş sekmesi: sayfalı yükler (page 0, size 25), sonraki sayfa page=1', async () => {
    const dlg = await openBox()
    fireEvent.click(within(dlg).getByRole('tab', { name: /Geçmiş|History/ }))
    await waitFor(() => expect(api.me.inboxHistory).toHaveBeenCalledWith(0, 25))
    expect(await within(dlg).findByText('ok.example.com')).toBeInTheDocument()
    expect(within(dlg).getByText(/Sayfa 1 \/ 2 — 41|Page 1 \/ 2 — 41/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Sonraki|Next/ }))
    await waitFor(() => expect(api.me.inboxHistory).toHaveBeenLastCalledWith(1, 25))
    // geçmişte seçim kutusu / temizle yok
    expect(within(dlg).queryByRole('checkbox')).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /Tümünü temizle|Clear all/ })).toBeNull()
  })

  it('fmtDuration: dk / sa dk / g sa', () => {
    const t = (k, a, b) => ({ 'inbox.durMin': `${a} dk`, 'inbox.durHour': `${a} sa ${b} dk`, 'inbox.durDay': `${a} g ${b} sa` })[k]
    expect(fmtDuration(5 * 60e3, t)).toBe('5 dk')
    expect(fmtDuration(3 * 3600e3 + 12 * 60e3, t)).toBe('3 sa 12 dk')
    expect(fmtDuration(50 * 3600e3, t)).toBe('2 g 2 sa')
    expect(fmtDuration(-1, t)).toBe('')
  })
})
