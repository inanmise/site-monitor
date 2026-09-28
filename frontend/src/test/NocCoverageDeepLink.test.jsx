import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ noc: {
    coverage: vi.fn(), setMonitor: vi.fn(), bulk: vi.fn(), getCallList: vi.fn(), saveCallList: vi.fn(), teamMembers: vi.fn(),
  } }),
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import NocCoveragePage from '../pages/NocCoveragePage.jsx'

/**
 * 7/24 Kapsamı → izlemenin KENDİSİNE bağlantı (2026-09-28, kullanıcı isteği: "izleme kartına tıklanınca açılan ekran
 * direkt açılabilir"). Satır adı ve "İzlemeyi aç" ikonu gerçek `<a href>`: düz tık uygulama içinde gezinir, Ctrl/⌘/orta
 * tık tarayıcıya kalır (yeni sekme). "…" menüsü: Bağlantıyı kopyala · 7/24 ayarını düzenle (yetkili) · kapat.
 * Hedef sayfaların açma davranışı: monitorPagesDeepLink.test (9 tür) ve CertDeepLink.app.test (SSL).
 */
const ITEMS = [
  { type: 'HTTP', id: 12, name: 'web', target: 'https://www.example.com', team_id: 1, team_name: 'Takım A', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [] },
  { type: 'SCRIPTED', id: 3, name: 'login-flow', target: 'login-flow', team_id: 1, team_name: 'Takım A', active: true, noc_notify: true, covered: true, reason: null, group_names: [] },
  { type: 'SSL', id: 5, name: 'shop.example.com', target: 'shop.example.com:8443', team_id: 1, team_name: 'Takım A', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [] },
  { type: 'DNS', id: 7, name: 'dns-b', target: 'example.org', team_id: 2, team_name: 'Takım B', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF', group_names: [] },
]
const USER = { systemRole: 'USER', globalAdmin: false, myTeamIds: [1], myTeams: [{ id: 1, name: 'Takım A' }], userId: 7 }
const rowOf = (key) => document.querySelector(`[data-slot="noc-row"][data-key="${key}"]`)
const base = () => `${window.location.origin}${window.location.pathname}`
const OPEN = /^(Open monitor|İzlemeyi aç) — /
const COPY = /^(Copy link|Bağlantıyı kopyala)$/
const EDIT = /^(Edit 24\/7 setting|7\/24 ayarını düzenle)$/
const MENU = (name) => new RegExp(`^${name} — (Actions|İşlemler)$`)

async function renderPage() {
  render(<NocCoveragePage {...USER} />)
  await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-row"]')).toHaveLength(ITEMS.length))
}

let nav
beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=noc')
  api.noc.coverage.mockResolvedValue({ success: true, data: { summary: { active_groups: 1, disabled_types: [] }, items: ITEMS } })
  api.noc.getCallList.mockResolvedValue({ success: true, data: [] })
  api.noc.teamMembers.mockResolvedValue({ success: true, data: [] })
  nav = vi.fn()
  window.addEventListener('sm:navigate', nav)
})
afterEach(() => {
  window.removeEventListener('sm:navigate', nav)
  window.history.replaceState(null, '', '/')
  window.innerWidth = 1024
})

describe('7/24 Kapsamı — satırdan izlemeye gerçek bağlantı (tablo)', () => {
  it('ad = <a href> derin bağlantı; href her türde kartın penceresini açan adres (filtreler taşınmaz)', async () => {
    window.history.replaceState(null, '', '/?tab=noc&n_ct=1&n_page=1')
    await renderPage()
    const link = (key, name) => within(rowOf(key)).getByRole('link', { name })
    expect(link('HTTP:12', 'web')).toHaveAttribute('href', `${base()}?tab=http&monitor=12`)
    expect(link('SCRIPTED:3', 'login-flow')).toHaveAttribute('href', `${base()}?tab=scripted&monitor=3`)
    // SSL: 443 dışı portta bile ALAN ADI; pencere açılır (open=cert)
    expect(link('SSL:5', 'shop.example.com')).toHaveAttribute('href', `${base()}?tab=dashboard&domain=shop.example.com&open=cert`)
    expect(link('DNS:7', 'dns-b')).toHaveAttribute('href', `${base()}?tab=dns&monitor=7`)
    for (const a of document.querySelectorAll('[data-slot="noc-row"] a[href]')) expect(a.getAttribute('href')).not.toMatch(/n_(ct|page)/)
  })

  it('düz sol tık: varsayılan ENGELLENİR ve uygulama içinde gezinir; Ctrl / ⌘ / Shift / orta tık tarayıcıya kalır (yeni sekme)', async () => {
    await renderPage()
    const a = within(rowOf('HTTP:12')).getByRole('link', { name: 'web' })
    expect(fireEvent.click(a)).toBe(false)   // preventDefault
    expect(nav).toHaveBeenCalledTimes(1)
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'http', params: { monitor: 12 } })
    for (const mod of [{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { button: 1 }]) {
      expect(fireEvent.click(a, mod)).toBe(true)   // tarayıcı bağlantıyı kendisi açar
    }
    expect(nav).toHaveBeenCalledTimes(1)
  })

  it('"İzlemeyi aç" ikonu: satırı taşıyan erişilebilir ad, aynı href, düz tık uygulama içinde; HER satırda (yetkisiz dâhil)', async () => {
    await renderPage()
    for (const it of ITEMS) {
      const icon = within(rowOf(`${it.type}:${it.id}`)).getByRole('link', { name: OPEN })
      expect(icon.getAttribute('aria-label')).toMatch(new RegExp(` — ${it.name.replace(/[.]/g, '[.]')}$`))
      expect(icon.getAttribute('href')).toBe(within(rowOf(`${it.type}:${it.id}`)).getByRole('link', { name: it.name }).getAttribute('href'))
    }
    fireEvent.click(within(rowOf('SCRIPTED:3')).getByRole('link', { name: OPEN }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'scripted', params: { monitor: 3 } })
    fireEvent.click(within(rowOf('SSL:5')).getByRole('link', { name: OPEN }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'dashboard', params: { domain: 'shop.example.com', open: 'cert' } })
  })

  it('"…" → Bağlantıyı kopyala: satırın derin bağlantısı panoya, onay tostu', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    try {
      await renderPage()
      pressMenuTrigger(within(rowOf('DNS:7')).getByRole('button', { name: MENU('dns-b') }))
      fireEvent.click(await screen.findByRole('menuitem', { name: COPY }))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${base()}?tab=dns&monitor=7`))
      expect(toastMock.success).toHaveBeenCalled()
      expect(nav).not.toHaveBeenCalled()
    } finally { writeText.mockRestore() }
  })

  it('"…" → 7/24 ayarını düzenle: yalnız düzenleyebilene; ?monitor=&open=noc (SSL: domain&open=noc); yetkisiz satırda yalnız kopyala', async () => {
    await renderPage()
    pressMenuTrigger(within(rowOf('HTTP:12')).getByRole('button', { name: MENU('web') }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EDIT }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'http', params: { monitor: 12, open: 'noc' } })

    pressMenuTrigger(within(rowOf('SSL:5')).getByRole('button', { name: MENU('shop.example.com') }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EDIT }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'dashboard', params: { domain: 'shop.example.com', open: 'noc' } })

    // Takım B satırı (USER Takım A üyesi): menü VAR ama yalnız kopyala — düzenle / kapat yok
    pressMenuTrigger(within(rowOf('DNS:7')).getByRole('button', { name: MENU('dns-b') }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((m) => m.textContent)).toEqual([expect.stringMatching(/Copy link|Bağlantıyı kopyala/)])
  })

  it('bildirimi açık satırın menüsü: kopyala · düzenle · kapat (kapat hâlâ çalışır)', async () => {
    api.noc.setMonitor.mockResolvedValue({ success: true, data: null })
    await renderPage()
    pressMenuTrigger(within(rowOf('SCRIPTED:3')).getByRole('button', { name: MENU('login-flow') }))
    const labels = (await screen.findAllByRole('menuitem')).map((m) => m.textContent)
    expect(labels).toHaveLength(3)
    expect(labels[2]).toMatch(/Stop notifying the 24\/7 team|7\/24 bildirimini kapat/)
    fireEvent.click(screen.getAllByRole('menuitem')[2])
    await waitFor(() => expect(api.noc.setMonitor).toHaveBeenCalledWith('SCRIPTED', 3, { enabled: false }))
  })

  it('Geri süzgeçleri korur: son 300 ms içinde yazılan arama da gezinmeden ÖNCE bu geçmiş kaydına işlenir', async () => {
    await renderPage()
    let urlAtNav = null
    nav.mockImplementation(() => { urlAtNav = window.location.search })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'web' } })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-row"]')).toHaveLength(1))
    // debounce dolmadan (yazımdan hemen sonra) tıkla
    fireEvent.click(within(rowOf('HTTP:12')).getByRole('link', { name: 'web' }))
    expect(urlAtNav).toContain('n_q=web')
    expect(urlAtNav).toContain('tab=noc')
  })
})

describe('7/24 Kapsamı — dar kap (kartlar)', () => {
  it('telefonda da ad bağlantı + "İzlemeyi aç" ikonu + "…" menüsü her kartta', async () => {
    window.innerWidth = 390
    await renderPage()
    expect(document.querySelector('[data-slot="noc-list"]')).toHaveAttribute('data-view', 'cards')
    for (const it of ITEMS) {
      const row = rowOf(`${it.type}:${it.id}`)
      expect(within(row).getByRole('link', { name: it.name })).toHaveAttribute('href')
      expect(within(row).getByRole('link', { name: OPEN })).toHaveAttribute('data-action', 'noc-open-icon')
      expect(within(row).getByRole('button', { name: MENU(it.name) })).toBeInTheDocument()
    }
    fireEvent.click(within(rowOf('SSL:5')).getByRole('link', { name: 'shop.example.com' }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'dashboard', params: { domain: 'shop.example.com', open: 'cert' } })
  })
})
