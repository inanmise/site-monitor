import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? ''), api: withApiFallback({ me: { inbox: vi.fn(), inboxHistory: vi.fn() } }) }))
import { api } from '../api/client'
import InboxBell, { fmtDuration } from '../components/InboxBell.jsx'

/**
 * Bildirim kutusu v2 davranışları (2026-09-20) v3 tasarımında (2026-09-26): takım + başlangıç/süre satırı, izlemeye git,
 * satır temizle / tümünü temizle / temizlenenleri göster (çoklu seçimin yerini aldı), geçmiş sekmesi (standart sayfalama).
 * Zaman: yalnız Date sahte, öğlene sabit (gece yarısı sınırı); fixture'lar göreli.
 */
const noon = () => { const d = new Date(); d.setHours(12, 0, 0, 0); return d }
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const ITEMS = () => [
  { key: 'alert:9', kind: 'alert_open', level: 'CRITICAL', title: 'down.example.com', sub: 'HTTP_DOWN', at: iso(3 * 3600e3 + 12 * 60e3), tab: 'alerthistory', params: { alert: 9 },
    team_id: 7, team_name: 'Takım A', started_at: iso(3 * 3600e3 + 12 * 60e3), monitor_tab: 'http', monitor_params: { monitor: 77 }, monitor_name: 'Ana site' },
  { key: 'resolved:4:x', kind: 'alert_resolved', level: 'OK', title: 'ok.example.com', sub: 'PING_DOWN', at: iso(3600e3), tab: 'alerthistory', params: { alert: 4, view: 'closed' },
    team_id: 7, team_name: 'Takım A', started_at: iso(3 * 3600e3), ended_at: iso(3600e3), monitor_tab: 'ping', monitor_params: { monitor: 5 } },
  { key: 'maint:3:x', kind: 'maintenance_soon', level: 'INFO', title: 'Gece bakımı', at: iso(-5 * 3600e3), tab: 'maintenance', params: { window: 3 } },
]
const bell = () => screen.getByRole('button', { name: /Bildirimler|Notifications/ })

describe('InboxBell v2 davranışları', () => {
  beforeEach(() => {
    vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ }
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(noon())
    api.me.inbox.mockResolvedValue({ success: true, data: ITEMS() })
    api.me.inboxHistory.mockResolvedValue({ success: true, data: [ITEMS()[1]], total: 41, page: 0, size: 25, total_pages: 2 })
  })
  afterEach(() => { vi.useRealTimers() })
  const openBox = async () => {
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalled())
    fireEvent.click(bell())
    return screen.getByRole('dialog')
  }

  it('satırda tür kutucuğu, seviye rozeti, takım (TeamBadge), "başladı … · 3 sa 12 dk açık"; çözülende "sürdü 2 sa"; bakımda takım yok', async () => {
    const dlg = await openBox()
    const open = within(dlg).getByText('down.example.com').closest('[data-inbox-row]')
    expect(open.getAttribute('data-inbox-row')).toBe('alert_open')
    expect(open.querySelector('[data-slot="team-badge"]').textContent).toContain('Takım A')
    expect(open.querySelector('[data-slot="badge"][data-variant="secondary"]').textContent).toBe('CRITICAL')
    expect(open.textContent).toMatch(/Açık alarm|Open alert/)
    expect(open.textContent).toMatch(/başladı|started/)
    expect(open.textContent).toMatch(/3 sa 12 dk açık|open for 3 h 12 min/)
    expect(open.textContent).toContain('Ana site')
    const res = within(dlg).getByText('ok.example.com').closest('[data-inbox-row]')
    expect(res.textContent).toMatch(/sürdü 2 sa 0 dk|lasted 2 h 0 min/)
    expect(res.textContent).toMatch(/çözüldü|resolved/)
    const maint = within(dlg).getByText('Gece bakımı').closest('[data-inbox-row]')
    expect(maint.querySelector('[data-slot="team-badge"]')).toBeNull()   // takım rozeti yok
    expect(maint.querySelector('[data-slot="badge"]')).toBeNull()        // seviye rozeti yalnız açık alarmda
  })

  it('"İzlemeye git" (hover eylemi, satır adıyla) izleme sekmesine monitor paramıyla gider; ana tıklama Alarm Geçmişi\'ne; bakımda izleme eylemi yok', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const dlg = await openBox()
    const row = within(dlg).getByText('down.example.com').closest('[data-inbox-row]')
    // Ad satırı ayırır (izleme adı + eylem) — her satırda aynı "İzlemeye git" değil (2026-09-25, R15)
    fireEvent.click(within(row).getByRole('button', { name: /^Ana site — (izlemeye git|go to the monitor)$/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 77 } })
    expect(screen.queryByRole('dialog')).toBeNull()
    // okundu sayıldı → Okunmamış'ta yok, Tümü'nde var
    fireEvent.click(bell())
    const dlg2 = screen.getByRole('dialog')
    expect(within(dlg2).queryByText('down.example.com')).toBeNull()
    pressMenuTrigger(within(dlg2).getByRole('tab', { name: /Tümü|All/ }))
    fireEvent.click(within(dlg2).getByText('down.example.com').closest('[data-inbox-open]'))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { alert: 9 } })
    fireEvent.click(bell())
    const maint = screen.getByText('Gece bakımı').closest('[data-inbox-row]')
    expect(within(maint).queryByRole('button', { name: /izlemeye git|go to the monitor/i })).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })

  it('satır "temizle": listeden düşer, okundu sayılır ve saklanır; rozet 3→2', async () => {
    const dlg = await openBox()
    await waitFor(() => expect(document.querySelector('[data-sidebar="menu-badge"]')?.textContent).toBe('3'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^Gece bakımı — (temizle|dismiss)$/ }))
    expect(within(dlg).queryByText('Gece bakımı')).toBeNull()
    expect(JSON.parse(localStorage.getItem('inbox-dismissed:u1'))).toEqual(['maint:3:x'])
    expect(JSON.parse(localStorage.getItem('inbox-seen:u1'))).toEqual(['maint:3:x'])
    expect(document.querySelector('[data-sidebar="menu-badge"]').textContent).toBe('2')
  })

  it('"Diğer işlemler" → "Tümünü temizle" listeyi boşaltır ("3 temizlendi"); "Temizlenenleri göster" geri getirir (soluk, data-dismissed)', async () => {
    const dlg = await openBox()
    pressMenuTrigger(within(dlg).getByRole('button', { name: /Diğer işlemler|More actions/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Tümünü temizle|Clear all/ }))
    expect(document.querySelector('[data-sidebar="menu-badge"]')).toBeNull()
    pressMenuTrigger(within(dlg).getByRole('tab', { name: /Tümü|All/ }))
    expect(dlg.querySelector('[data-slot="empty"]').textContent).toMatch(/3 bildirim temizlendi|3 cleared/)
    pressMenuTrigger(within(dlg).getByRole('button', { name: /Diğer işlemler|More actions/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: /Temizlenenleri göster \(3\)|Show cleared \(3\)/ }))
    const row = within(dlg).getByText('down.example.com').closest('[data-inbox-row]')
    expect(row.getAttribute('data-dismissed')).toBe('true')
    expect(within(row).queryByRole('button', { name: /temizle|dismiss/ })).toBeNull()   // temizlenmişte temizle eylemi yok
    expect(JSON.parse(localStorage.getItem('inbox-dismissed:u1'))).toEqual(['alert:9', 'resolved:4:x', 'maint:3:x'])
  })

  it('Geçmiş sekmesi: standart compact PaginationBar ile sayfalı yükler (modal ön ayarı: page 0, size 10), sonraki sayfa page=1; satırda temizle yok', async () => {
    api.me.inboxHistory.mockResolvedValue({ success: true, data: [ITEMS()[1]], total: 41, page: 0, size: 10, total_pages: 5 })
    const dlg = await openBox()
    pressMenuTrigger(within(dlg).getByRole('tab', { name: /Geçmiş|History/ }))   // Radix Tabs mousedown ile geçer
    await waitFor(() => expect(api.me.inboxHistory).toHaveBeenCalledWith(0, 10))
    expect(await within(dlg).findByText('ok.example.com')).toBeInTheDocument()
    // Çubuk: "Sayfalama" gezinmesi, compact konum "1 / 5" ve kayıt aralığı; el yapımı "Sayfa 1 / 2 — 41" yok
    const nav = within(dlg).getByRole('navigation', { name: /Sayfalama|Pagination/ })
    expect(within(nav).getByText('1 / 5')).toBeInTheDocument()
    expect(within(dlg).getByText(/1–10 (\/|of) 41/)).toBeInTheDocument()
    expect(within(dlg).queryByText(/— 41/)).toBeNull()
    fireEvent.click(within(nav).getByRole('button', { name: /^(Sonraki|Next)$/ }))
    await waitFor(() => expect(api.me.inboxHistory).toHaveBeenLastCalledWith(1, 10))
    // geçmişte okundu/temizle eylemi ve seçim kutusu yok; izlemeye git var
    const row = within(dlg).getByText('ok.example.com').closest('[data-inbox-row]')
    expect(within(row).queryByRole('button', { name: /temizle|dismiss|okundu say|mark as read/ })).toBeNull()
    expect(within(row).getByRole('button', { name: /izlemeye git|go to the monitor/ })).toBeInTheDocument()
    expect(within(dlg).queryByRole('checkbox')).toBeNull()
    expect(within(dlg).getByText(/Geçmiş: son 30 günde|History: alerts resolved/)).toBeInTheDocument()
  })

  it('Geçmiş: yükleme düşerse uyarı + "Yeniden dene" yeniden ister', async () => {
    api.me.inboxHistory.mockRejectedValueOnce(new Error('ağ'))
    const dlg = await openBox()
    pressMenuTrigger(within(dlg).getByRole('tab', { name: /Geçmiş|History/ }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="alert"][data-tone="danger"]')).not.toBeNull())
    fireEvent.click(within(dlg).getByRole('button', { name: /Yeniden dene|Try again/ }))
    await waitFor(() => expect(api.me.inboxHistory).toHaveBeenCalledTimes(2))
    expect(await within(dlg).findByText('ok.example.com')).toBeInTheDocument()
  })

  it('fmtDuration: dk / sa dk / g sa', () => {
    const t = (k, a, b) => ({ 'inbox.durMin': `${a} dk`, 'inbox.durHour': `${a} sa ${b} dk`, 'inbox.durDay': `${a} g ${b} sa` })[k]
    expect(fmtDuration(5 * 60e3, t)).toBe('5 dk')
    expect(fmtDuration(3 * 3600e3 + 12 * 60e3, t)).toBe('3 sa 12 dk')
    expect(fmtDuration(50 * 3600e3, t)).toBe('2 g 2 sa')
    expect(fmtDuration(-1, t)).toBe('')
  })
})
