import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
// Sentetik düzenleme formu: CodeEditor (prismjs) jsdom'da ağır → düz textarea (Sentetik testleriyle aynı)
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
 * DOKUZ izleme sayfasında `?tab=<tür>&monitor=<id>` derin bağlantısı (2026-09-28, 7/24 Kapsamı → izleme; e-posta
 * CTA'ları ve kart "Bağlantıyı kopyala" da aynı adresi üretir). Her tür için:
 *   - hedef 2. SAYFADA, DURAKLATILMIŞ ve sayfanın süzgeci/araması onu GİZLİYORKEN de detay penceresi açılır;
 *   - liste 300 ms'den GEÇ gelse de açılır (URL senkronu `monitor`'ü siliyordu — yarış);
 *   - pencere açıkken `monitor` adreste kalır (paylaşılabilir), kapanınca silinir (Geri/yenile yeniden açmaz);
 *   - bulunamayan / erişilemeyen kimlikte "İzleme bulunamadı ya da erişiminiz yok" uyarısı;
 *   - ardışık gezinme: sayfa yeniden bağlanınca da, aynı sekmede ikinci bağlantıda da İKİNCİ izleme açılır;
 *   - `open=noc` ("7/24 ayarını düzenle"): düzenleme formu açılır, "7/24 izleme ekibine bildir" anahtarı odaklı; `open` silinir.
 */
const host = (i) => `m${i}.example.com`
const PAGES = [
  ['Http', 'http', 'HTTP', HttpMonitorPage, 'getHttpMonitors', (i) => ({ url: `https://${host(i)}/`, method: 'GET', status: 'up' })],
  ['Keyword', 'keyword', 'KEYWORD', KeywordMonitorPage, 'getKeywordMonitors', (i) => ({ url: `https://${host(i)}/`, keyword: 'Giriş', operator: 'GTE', match_count: 1, status: 'up' })],
  ['Page', 'page', 'PAGE', PageMonitorPage, 'getPageMonitors', (i) => ({ url: `https://${host(i)}/`, mode: 'SINGLE', status: 'up' })],
  ['PageSpeed', 'pagespeed', 'PAGESPEED', PageSpeedMonitorPage, 'getPageSpeedMonitors', (i) => ({ url: `https://${host(i)}/`, status: 'up', breached_metrics: [] })],
  ['Domain', 'domain', 'DOMAIN', DomainMonitorPage, 'getDomainMonitors', (i) => ({ domain: host(i), status: 'OK', days_remaining: 90 })],
  ['Ping', 'ping', 'PING', PingMonitorPage, 'getPingMonitors', (i) => ({ host: host(i), status: 'up', ip_version: 'V4', packet_count: 4 })],
  ['Port', 'port', 'PORT', PortMonitorPage, 'getPortMonitors', (i) => ({ host: host(i), port: 443, protocol: 'TCP', status: 'open' })],
  ['Dns', 'dns', 'DNS', DnsMonitorPage, 'getDnsMonitors', (i) => ({ domain: host(i), record_type: 'A', value: '203.0.113.10', standalone: true })],
  ['Scripted', 'scripted', 'SCRIPTED', ScriptedMonitorPage, 'getScriptedMonitors', () => ({ status: 'up', script: 'export default function () {}', env: [] })],
]
const N = 60   // sayfa boyu 50 → son kayıtlar 2. sayfada
const row = (fields, i) => ({
  id: i, name: host(i), team_id: 5, team_name: 'Takım A', group_name: 'Grup', tags: 'prod',
  active: i !== N,   // hedef (N) DURAKLATILMIŞ
  checked_at: '2026-09-26T09:00:00', interval_seconds: 300, ...fields(i),
})
const NOT_FOUND = /^(Monitor not found, or you don’t have access to it|İzleme bulunamadı ya da erişiminiz yok)$/
const NOC_SWITCH = /^(Notify the 24\/7 monitoring team|7\/24 izleme ekibine bildir)$/
const param = (k) => new URLSearchParams(window.location.search).get(k)
const props = { systemRole: 'ADMIN', teamId: 5, teamName: 'Takım A', myTeams: [{ id: 5, name: 'Takım A' }], globalAdmin: false }

/** Detay penceresi (başlığında hedefin adı) açık mı — form penceresi hariç. */
const detailOf = async (i, timeout = 3000) => waitFor(() => {
  const d = screen.getAllByRole('dialog').find((x) => x.textContent.includes(host(i)))
  expect(d).toBeTruthy()
  return d
}, { timeout })

describe.each(PAGES)('%s — ?monitor= derin bağlantısı', (name, tab, nocType, Page, listFn, fields) => {
  const list = Array.from({ length: N }, (_, k) => row(fields, k + 1))
  const reply = (rows) => (name === 'Scripted'
    ? { success: true, data: { monitors: rows, k6_available: true, can_manage: true } }
    : { success: true, data: rows })

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring[listFn].mockResolvedValue(reply(list))
  })
  afterEach(() => window.history.replaceState({}, '', '/'))

  it('2. sayfadaki, duraklatılmış, arama/süzgeçle GİZLİ izlemenin detayı açılır; açıkken `monitor` adreste, kapanınca silinir', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=${N}&q=zzz-eslesmez&stat=down`)
    render(<Page {...props} />)
    const dialog = await detailOf(N)
    expect(dialog).toBeInTheDocument()
    // kart ızgarada GÖRÜNMÜYOR (arama eşleşmiyor) — pencere yine de açıldı
    expect(document.querySelector('.upt-grid')?.textContent ?? '').not.toContain(host(N))
    expect(toastMock.error).not.toHaveBeenCalled()
    await waitFor(() => expect(param('monitor')).toBe(String(N)), { timeout: 1500 })   // paylaşılabilir
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryAllByRole('dialog').some((x) => x.textContent.includes(host(N)))).toBe(false))
    await waitFor(() => expect(param('monitor')).toBeNull(), { timeout: 1500 })   // Geri/yenile yeniden açmaz
  })

  it('süzgeçsiz: hedef 2. sayfada (ızgarada yok) — detay yine açılır', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=${N - 1}`)
    render(<Page {...props} />)
    await detailOf(N - 1)
    await waitFor(() => expect(document.querySelector('.upt-grid')?.textContent ?? '').toContain(host(1)))
    expect(document.querySelector('.upt-grid').textContent).not.toContain(host(N - 1))
  })

  it('liste 300 ms\'den GEÇ gelse de açılır (URL senkronu parametreyi silmeden önce okunur)', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=7`)
    api.monitoring[listFn].mockImplementation(() => new Promise((r) => setTimeout(() => r(reply(list)), 700)))
    render(<Page {...props} />)
    await detailOf(7, 4000)
  })

  it('bulunamayan / erişilemeyen kimlik → "İzleme bulunamadı ya da erişiminiz yok", pencere yok', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=99999`)
    render(<Page {...props} />)
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(NOT_FOUND)))
    expect(toastMock.error).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('ardışık gezinme: yeniden bağlanınca da, aynı sekmede ikinci bağlantıda da İKİNCİ izleme açılır', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=3`)
    const first = render(<Page {...props} />)
    await detailOf(3)
    first.unmount()   // sekme değişti (App sayfayı söker)
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=4`)
    render(<Page {...props} />)
    await detailOf(4)
    // aynı sekmedeyken yeni bağlantı: App.handleTabChange `sm:tab-params` yayar
    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { monitor: '5' } })) })
    await detailOf(5)
  })

  it('open=noc → DÜZENLEME formu, "7/24 izleme ekibine bildir" anahtarı odaklı; `open` adresten silinir', async () => {
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=2&open=noc`)
    render(<Page {...props} />)
    await waitFor(() => {
      const el = document.activeElement
      expect(el?.getAttribute('role')).toBe('switch')
      expect(el.closest('[data-slot="noc-notify-field"]')).not.toBeNull()
    }, { timeout: 3000 })
    expect(screen.getByRole('switch', { name: NOC_SWITCH })).toBe(document.activeElement)
    expect(document.activeElement.closest('[data-slot="noc-notify-field"]')).toHaveAttribute('data-focus-target', 'true')
    expect(param('open')).toBeNull()
    // detay penceresi değil form açıldı
    expect(screen.queryAllByRole('tab', { name: /check history|kontrol geçmişi/i })).toHaveLength(0)
    expect(nocType).toBeTruthy()
  })
})
