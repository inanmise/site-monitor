import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'
import UptimePage from '../components/UptimePage.jsx'
import { readSearchParam } from '../hooks/useUrlQuerySync.js'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />,
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'

/**
 * E-posta bağlantılarının arama parametresi (H paketi 2026-11-26, deneyim 1–2). Alarm e-postaları önce
 * `?tab=<tür>&domain=<hedef>` üretiyordu; izleme sayfaları `domain` okumadığı için bağlantı süzülmemiş listeye açılıyordu.
 * Artık:
 *   - `q=<hedef>` arama kutusunu doldurur (yeni e-postalar kimliği bilinmeyen izleme için bunu üretir);
 *   - ESKİ `domain=<hedef>` de `q` gibi okunur; sayfanın URL senkronu `domain`'i siler ve değeri `q` olarak yazar;
 *   - ikisi birlikteyse `q` kazanır.
 * On bir sayfa (on izleme türü + Durum İzleme) aynı kuralı izler.
 */
const host = (i) => `m${i}.example.com`
const PAGES = [
  ['Http', 'http', HttpMonitorPage, 'getHttpMonitors', (i) => ({ url: `https://${host(i)}/`, method: 'GET', status: 'up' })],
  ['Keyword', 'keyword', KeywordMonitorPage, 'getKeywordMonitors', (i) => ({ url: `https://${host(i)}/`, keyword: 'Giriş', operator: 'GTE', match_count: 1, status: 'up' })],
  ['Page', 'page', PageMonitorPage, 'getPageMonitors', (i) => ({ url: `https://${host(i)}/`, mode: 'SINGLE', status: 'up' })],
  ['PageSpeed', 'pagespeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', (i) => ({ url: `https://${host(i)}/`, status: 'up', breached_metrics: [] })],
  ['Domain', 'domain', DomainMonitorPage, 'getDomainMonitors', (i) => ({ domain: host(i), status: 'OK', days_remaining: 90 })],
  ['Ping', 'ping', PingMonitorPage, 'getPingMonitors', (i) => ({ host: host(i), status: 'up', ip_version: 'V4', packet_count: 4 })],
  ['Port', 'port', PortMonitorPage, 'getPortMonitors', (i) => ({ host: host(i), port: 443, protocol: 'TCP', status: 'open' })],
  ['Dns', 'dns', DnsMonitorPage, 'getDnsMonitors', (i) => ({ domain: host(i), record_type: 'A', value: '203.0.113.10', standalone: true })],
  ['Scripted', 'scripted', ScriptedMonitorPage, 'getScriptedMonitors', () => ({ status: 'up', script: 'export default function () {}', env: [] })],
  ['DbQuery', 'dbquery', DbQueryMonitorPage, 'getDbQueryMonitors', () => ({ purpose: 'Bekleyen sipariş', profile_id: 3, profile: { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders' }, sql_text: 'SELECT 1', rules: [], status: 'OK', breach_level: 'NONE' })],
]
const row = (fields, i) => ({
  id: i, name: host(i), team_id: 5, team_name: 'Takım A', group_name: 'Grup', tags: 'prod', active: true,
  checked_at: '2026-09-26T09:00:00', interval_seconds: 300, ...fields(i),
})
const param = (k) => new URLSearchParams(window.location.search).get(k)
const props = { systemRole: 'ADMIN', teamId: 5, teamName: 'Takım A', myTeams: [{ id: 5, name: 'Takım A' }], globalAdmin: false }
const searchBox = () => document.querySelector('[data-page-search]')

describe('readSearchParam (saf)', () => {
  afterEach(() => window.history.replaceState({}, '', '/'))
  it('q önce, yoksa domain, ikisi de yoksa boş', () => {
    window.history.replaceState({}, '', '/?tab=http&q=abc&domain=xyz')
    expect(readSearchParam()).toBe('abc')
    window.history.replaceState({}, '', '/?tab=http&domain=xyz.example.com')
    expect(readSearchParam()).toBe('xyz.example.com')
    window.history.replaceState({}, '', '/?tab=http&q=&domain=')
    expect(readSearchParam()).toBe('')
    window.history.replaceState({}, '', '/?tab=http')
    expect(readSearchParam()).toBe('')
  })
})

describe.each(PAGES)('%s — e-posta arama parametresi', (name, tab, Page, listFn, fields) => {
  const list = Array.from({ length: 6 }, (_, k) => row(fields, k + 1))
  const reply = (rows) => (name === 'Scripted'
    ? { success: true, data: { monitors: rows, k6_available: true, can_manage: true } }
    : { success: true, data: rows })

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring[listFn].mockResolvedValue(reply(list))
  })
  afterEach(() => window.history.replaceState({}, '', '/'))

  it('eski `domain=` arama kutusunu doldurur; adres `q`ya taşınır, `domain` silinir', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&domain=${host(3)}`)
    render(<Page {...props} />)
    await waitFor(() => expect(searchBox()).not.toBeNull())
    expect(searchBox().value).toBe(host(3))
    await waitFor(() => expect(param('q')).toBe(host(3)), { timeout: 1500 })
    expect(param('domain')).toBeNull()
    expect(param('tab')).toBe(tab)   // eşlemede olmayan param korunur
  })

  it('`q=` arama kutusunu doldurur; `domain` de verilmişse `q` kazanır', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&q=${host(2)}&domain=${host(5)}`)
    render(<Page {...props} />)
    await waitFor(() => expect(searchBox()).not.toBeNull())
    expect(searchBox().value).toBe(host(2))
    await waitFor(() => expect(param('domain')).toBeNull(), { timeout: 1500 })
    expect(param('q')).toBe(host(2))
  })

  it('parametre yoksa arama boş ve adres `q` / `domain` içermez', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}`)
    render(<Page {...props} />)
    await waitFor(() => expect(searchBox()).not.toBeNull())
    expect(searchBox().value).toBe('')
    await new Promise((r) => setTimeout(r, 400))
    expect(param('q')).toBeNull()
    expect(param('domain')).toBeNull()
  })
})

// ── Durum İzleme (Erişilebilirlik): arama + `monitor=<envanter kimliği>` derin bağlantısı ──
describe('UptimePage — e-posta bağlantıları', () => {
  const up = (i, extra = {}) => ({
    domain: host(i), status: 'up', http_ok: true, uptime_7d: 100, uptime_30d: 100,
    incidents_1d: 0, incidents_7d: 0, incidents_30d: 0, checked_at: '2026-06-24T00:00:00', team_id: 5, team_name: 'Takım A',
    can_manage: true, ...extra,
  })
  const detailOf = (i) => screen.queryAllByRole('dialog').find((d) => d.textContent.includes(host(i)))

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })
  afterEach(() => window.history.replaceState({}, '', '/'))

  it('eski `domain=` arama kutusunu doldurur, adres `q`ya taşınır', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [up(1), up(2)], scope: 'mine' })
    window.history.replaceState({}, '', `/?tab=uptime&domain=${host(2)}`)
    render(<UptimePage />)
    await waitFor(() => expect(searchBox()?.value).toBe(host(2)))
    await waitFor(() => expect(param('q')).toBe(host(2)), { timeout: 1500 })
    expect(param('domain')).toBeNull()
  })

  it('`monitor=<id>` satırın detayını açar (id), açıkken adreste kalır', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [up(1, { id: 11 }), up(2, { id: 12 })], scope: 'mine' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=12')
    render(<UptimePage />)
    await waitFor(() => expect(detailOf(2)).toBeTruthy(), { timeout: 3000 })
    expect(toastMock.error).not.toHaveBeenCalled()
    await waitFor(() => expect(param('monitor')).toBe('12'), { timeout: 1500 })
  })

  it('kimlik `inventory_id` alanındaysa da açılır', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [up(1, { inventory_id: 21 }), up(2, { inventory_id: 22 })], scope: 'mine' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=21')
    render(<UptimePage />)
    await waitFor(() => expect(detailOf(1)).toBeTruthy(), { timeout: 3000 })
  })

  it('inceleme bulgusu 5: satır hem `id` hem `inventory_id` taşırsa e-postadaki ENVANTER kimliği kazanır (id ile karışmaz)', async () => {
    // Satır 1'in başka bir id'si (22) satır 2'nin envanter kimliğiyle aynı: monitor=22 satır 2'yi açmalı, satır 1'i değil
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true,
      data: [up(1, { id: 22, inventory_id: 31 }), up(2, { id: 7, inventory_id: 22 })], scope: 'mine' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=22')
    render(<UptimePage />)
    await waitFor(() => expect(detailOf(2)).toBeTruthy(), { timeout: 3000 })
    expect(detailOf(1)).toBeFalsy()
    expect(toastMock.error).not.toHaveBeenCalled()
  })

  it('satırlar kimlik taşıyor ama eşleşme yok → "bulunamadı" uyarısı, pencere yok', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [up(1, { id: 11 })], scope: 'mine' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=999')
    render(<UptimePage />)
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1))
    expect(detailOf(1)).toBeFalsy()
  })

  it('eski sunucu: satırlarda kimlik YOK → uyarı da pencere de yok (bilinmeyen ≠ yok)', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [up(1), up(2)], scope: 'mine' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=12')
    render(<UptimePage />)
    await screen.findAllByText(host(1))
    await new Promise((r) => setTimeout(r, 300))
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(detailOf(2)).toBeFalsy()
  })

  it('yükleme başarısızsa "bulunamadı" denmez', async () => {
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: false, error: 'db down' })
    window.history.replaceState({}, '', '/?tab=uptime&monitor=12')
    render(<UptimePage />)
    await new Promise((r) => setTimeout(r, 400))
    expect(toastMock.error).not.toHaveBeenCalled()
  })
})
