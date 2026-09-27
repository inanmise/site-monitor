import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? ''), api: withApiFallback({ me: { inbox: vi.fn(), inboxHistory: vi.fn() } }) }))
import { api } from '../api/client'
import InboxBell from '../components/InboxBell.jsx'

/**
 * Bildirim kutusu v3 (2026-09-26, shadcn yeniden tasarım): zil → Popover (masaüstü) / Sheet (telefon); sekmeler
 * Okunmamış / Tümü / Geçmiş; güne göre gruplama; satır eylemleri; boş / iskelet / hata durumları; odak yönetimi.
 *
 * Zaman: yalnız Date sahte (saat ÖĞLEN'e sabit) — "12 dk önce" gece yarısına yakın koşuda düne kaymasın (CI UTC,
 * yerel Europe/Istanbul); fixture'lar sahte "şimdi"ye GÖRELİ (sabit tarih = zaman bombası).
 */
const noon = () => { const d = new Date(); d.setHours(12, 0, 0, 0); return d }
const MIN = 60_000, HOUR = 3_600_000, DAY = 86_400_000
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const items = () => [
  { key: 'alert:9', kind: 'alert_open', level: 'CRITICAL', title: 'down.example.com', sub: 'HTTP_DOWN', at: iso(12 * MIN), started_at: iso(12 * MIN), tab: 'alerthistory', params: { alert: 9 },
    team_id: 7, team_name: 'Takım A', monitor_tab: 'http', monitor_params: { monitor: 77 }, monitor_name: 'Ana site' },
  { key: 'maint:3:x', kind: 'maintenance_soon', level: 'INFO', title: 'Gece bakımı', at: iso(-3 * HOUR), tab: 'maintenance', params: { window: 3 } },
  { key: 'resolved:4:x', kind: 'alert_resolved', level: 'OK', title: 'ok.example.com', sub: 'PING_DOWN', at: iso(DAY + HOUR), started_at: iso(DAY + 3 * HOUR), ended_at: iso(DAY + HOUR), tab: 'alerthistory', params: { alert: 4, view: 'closed' } },
  { key: 'exception:old.example.net:x', kind: 'exception_expired', level: 'WARNING', title: 'old.example.net', sub: 'SHA-1', at: iso(3 * DAY), tab: 'weakalgo', params: {} },
]
const bell = () => screen.getByRole('button', { name: /^(Bildirimler|Notifications)$/ })
const badge = () => document.querySelector('[data-sidebar="menu-badge"]')

describe('InboxBell v3', () => {
  beforeEach(() => {
    vi.clearAllMocks(); mobile.on = false
    try { localStorage.clear() } catch { /* yok */ }
    vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(noon())
    api.me.inbox.mockResolvedValue({ success: true, data: items() })
  })
  afterEach(() => { vi.useRealTimers() })

  const openBox = async () => {
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalled())
    fireEvent.click(bell())
    return screen.getByRole('dialog')
  }

  it('rozet 4; zil masaüstünde POPOVER açar (dialog adı Bildirimler, odak panelde); satır tıklanınca sm:navigate + okundu; "Tümünü okundu say" saklanır', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(badge()?.textContent).toBe('4'))
    const trigger = bell()
    expect(trigger).toHaveAttribute('data-slot', 'sidebar-menu-button')   // Nav satır sözleşmesi (PopoverTrigger asChild ezmez)
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog')
    fireEvent.click(trigger)
    const dlg = screen.getByRole('dialog', { name: /Bildirimler|Notifications/ })
    expect(dlg).toHaveAttribute('data-slot', 'popover-content')
    expect(document.querySelector('[data-slot="sheet-content"]')).toBeNull()
    await waitFor(() => expect(dlg.contains(document.activeElement)).toBe(true))
    // "4 yeni" rozeti + kibar duyuru
    expect(within(dlg).getByText(/^4 (yeni|new)$/)).toBeInTheDocument()
    expect(dlg.querySelector('[aria-live="polite"]').textContent).toMatch(/4 (okunmamış|unread)/)

    fireEvent.click(within(dlg).getByText('down.example.com').closest('[data-inbox-open]'))
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'alerthistory', params: { alert: 9 } })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(badge().textContent).toBe('3')
    expect(JSON.parse(localStorage.getItem('inbox-seen:u1'))).toEqual(['alert:9'])

    fireEvent.click(bell())
    fireEvent.click(screen.getByRole('button', { name: /Tümünü okundu say|Mark all as read/ }))
    expect(badge()).toBeNull()
    expect(JSON.parse(localStorage.getItem('inbox-seen:u1'))).toEqual(expect.arrayContaining(['alert:9', 'maint:3:x', 'resolved:4:x', 'exception:old.example.net:x']))
    expect(screen.getByRole('button', { name: /Tümünü okundu say|Mark all as read/ })).toBeDisabled()
    window.removeEventListener('sm:navigate', nav)
  })

  it('sekmeler: Okunmamış yalnız okunmamışları, Tümü hepsini gösterir; sayaçlar sekmede', async () => {
    localStorage.setItem('inbox-seen:u1', JSON.stringify(['maint:3:x']))
    const dlg = await openBox()
    const tabs = within(dlg).getAllByRole('tab')
    expect(tabs.map((x) => x.textContent.trim())).toEqual([
      expect.stringMatching(/^(Okunmamış|Unread)\s*3$/), expect.stringMatching(/^(Tümü|All)\s*4$/), expect.stringMatching(/Geçmiş|History/)])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(within(dlg).queryByText('Gece bakımı')).toBeNull()                 // okunmuş → Okunmamış'ta yok
    expect(dlg.querySelectorAll('[data-inbox-row]')).toHaveLength(3)
    pressMenuTrigger(tabs[1])
    expect(within(dlg).getByText('Gece bakımı')).toBeInTheDocument()
    expect(dlg.querySelectorAll('[data-inbox-row]')).toHaveLength(4)
    expect(dlg.querySelectorAll('[data-inbox-row][data-unread]')).toHaveLength(3)
    // Pasif sekme içeriği DOM'da `hidden` (yer kaplamaması globals.css [hidden] kuralına bağlı — e2e/inbox-panel.spec.js)
    expect(dlg.querySelector('[data-slot="tabs-content"][data-state="inactive"]')).toHaveAttribute('hidden')
  })

  it('güne göre gruplama: Yaklaşan / Bugün / Dün / Daha önce sırayla, yalnız dolu gruplar', async () => {
    const dlg = await openBox()
    const heads = [...dlg.querySelectorAll('h3')].map((h) => h.textContent)
    expect(heads).toEqual([
      expect.stringMatching(/^(Yaklaşan|Upcoming)$/), expect.stringMatching(/^(Bugün|Today)$/),
      expect.stringMatching(/^(Dün|Yesterday)$/), expect.stringMatching(/^(Daha önce|Earlier)$/)])
    const sectionOf = (title) => within(dlg).getByText(title).closest('section').getAttribute('aria-label')
    expect(sectionOf('Gece bakımı')).toMatch(/Yaklaşan|Upcoming/)
    expect(sectionOf('down.example.com')).toMatch(/Bugün|Today/)
    expect(sectionOf('ok.example.com')).toMatch(/Dün|Yesterday/)
    expect(sectionOf('old.example.net')).toMatch(/Daha önce|Earlier/)
    // Göreli zaman + okunmamış işareti (nokta görsel, ekran okuyucuya sr-only)
    const row = within(dlg).getByText('down.example.com').closest('[data-inbox-row]')
    expect(row.textContent).toMatch(/12 (dakika önce|minutes ago)/)
    expect(row.textContent).toMatch(/okunmamış|unread/)
  })

  it('satır eylemi "okundu say": satır Okunmamış\'tan düşer, rozet azalır, gezinme YOK', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const dlg = await openBox()
    await waitFor(() => expect(badge()?.textContent).toBe('4'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^down\.example\.com — (okundu say|mark as read)$/ }))
    expect(nav).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()                    // panel açık kalır
    expect(within(dlg).queryByText('down.example.com')).toBeNull()
    expect(badge().textContent).toBe('3')
    expect(JSON.parse(localStorage.getItem('inbox-seen:u1'))).toEqual(['alert:9'])
    window.removeEventListener('sm:navigate', nav)
  })

  it('boş kutu: rozet yok; Okunmamış → "Hepsini okudunuz", Tümü → "Bildirim yok"', async () => {
    api.me.inbox.mockResolvedValue({ success: true, data: [] })
    const dlg = await openBox()
    expect(badge()).toBeNull()
    expect(dlg.querySelector('[data-slot="empty"]').textContent).toMatch(/Hepsini okudunuz|You're all caught up/)
    pressMenuTrigger(within(dlg).getByRole('tab', { name: /Tümü|All/ }))
    expect(dlg.querySelector('[data-slot="empty"]').textContent).toMatch(/Bildirim yok|No notifications/)
  })

  it('yükleme: veri gelene kadar iskelet; hata: uyarı + "Yeniden dene" yeniden ister', async () => {
    let resolve
    api.me.inbox.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    render(withSidebar(<InboxBell username="u1" />))
    fireEvent.click(bell())
    const dlg = screen.getByRole('dialog')
    expect(dlg.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
    expect(within(dlg).getByRole('status').textContent).toMatch(/Yükleniyor|Loading/)
    await act(async () => { resolve({ success: true, data: items() }) })
    expect(dlg.querySelector('[data-slot="skeleton"]')).toBeNull()
    expect(dlg.querySelectorAll('[data-inbox-row]')).toHaveLength(4)
  })

  it('hata: ilk yükleme düşerse uyarı + "Yeniden dene"; tekrar başarılıysa liste gelir', async () => {
    api.me.inbox.mockRejectedValueOnce(new Error('ağ'))
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalledTimes(1))
    fireEvent.click(bell())
    const dlg = screen.getByRole('dialog')
    await waitFor(() => expect(dlg.querySelector('[data-slot="alert"][data-tone="danger"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="skeleton"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Yeniden dene|Try again/ }))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(dlg.querySelectorAll('[data-inbox-row]')).toHaveLength(4))
    expect(dlg.querySelector('[data-slot="alert"]')).toBeNull()
  })

  it('telefon: ikon zil tam yükseklikte SHEET açar; satır eylemleri tek menüde (KebabMenu); "Tümünü okundu say" ikon düğme', async () => {
    mobile.on = true
    render(withSidebar(<InboxBell username="u1" variant="icon" />))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalled())
    const trigger = bell()
    expect(trigger.querySelector('[data-slot="inbox-count"]').textContent).toBe('4')
    fireEvent.click(trigger)
    const dlg = screen.getByRole('dialog', { name: /Bildirimler|Notifications/ })
    expect(dlg).toHaveAttribute('data-slot', 'sheet-content')
    expect(document.querySelector('[data-slot="popover-content"]')).toBeNull()
    await waitFor(() => expect(dlg.contains(document.activeElement)).toBe(true))
    // Masaüstü eylem kümesi YOK; satır menüsü satırı adlandırır
    expect(within(dlg).queryByRole('button', { name: /okundu say|mark as read/ })).toBeNull()
    const row = within(dlg).getByText('down.example.com').closest('[data-inbox-row]')
    pressMenuTrigger(within(row).getByRole('button', { name: /^down\.example\.com — (İşlemler|Actions)$/ }))
    const names = screen.getAllByRole('menuitem').map((m) => m.textContent.trim())
    expect(names).toEqual([
      expect.stringMatching(/okundu say|mark as read/), expect.stringMatching(/Ana site — (izlemeye git|go to the monitor)/), expect.stringMatching(/temizle|dismiss/)])
    fireEvent.click(screen.getByRole('menuitem', { name: /temizle|dismiss/ }))
    expect(within(dlg).queryByText('down.example.com')).toBeNull()
    expect(JSON.parse(localStorage.getItem('inbox-dismissed:u1'))).toEqual(['alert:9'])
    // Başlık eylemleri 40 px ikon (etiket ekran okuyucuya)
    const markAll = within(dlg).getByRole('button', { name: /Tümünü okundu say|Mark all as read/ })
    expect(markAll.className).toMatch(/size-10/)
    // Kapat düğmesi başlıkta (i18n); Sheet kapanır, odak zile
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Kapat|Close)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(bell()))
  })

  it('odak: Escape kapatır ve odak zile döner; ↓/↑ satırlar arasında gezer', async () => {
    const dlg = await openBox()
    await waitFor(() => expect(dlg.contains(document.activeElement)).toBe(true))
    const rows = [...dlg.querySelectorAll('[data-inbox-open]')]
    expect(rows.length).toBeGreaterThan(2)
    act(() => { rows[0].focus() })
    fireEvent.keyDown(rows[0], { key: 'ArrowDown' })
    expect(document.activeElement).toBe(rows[1])
    fireEvent.keyDown(rows[1], { key: 'End' })
    expect(document.activeElement).toBe(rows[rows.length - 1])
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(rows[rows.length - 2])
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(bell()))
    expect(bell()).toHaveAttribute('aria-expanded', 'false')
  })
})
