import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'

/**
 * SEKİZ izleme sayfasının ortak davranış sözleşmesi (2026-09-26 kullanıcı istekleri) — tek dosyada, tür başına:
 *   (1) Duraklatılmış kartta hızlı "Sürdür": yalnız duraklatılmış kartta; sayfanın güncelleme ucunu `{ active: true }`
 *       ile çağırır ve listeyi tazeler; "Duraklatıldı" rozeti görünür; düğme soluk kabın DIŞINDA (alt çubukta).
 *   (2) Detay penceresi de duraklatılmış izlemede "Sürdür" sunar (MonitorModalActions).
 *   (3) USER rolü izleme ekleyebilir: tek takımlı, oturum takımı BOŞ üye için form o takımla açılır; hiç takımı
 *       olmayan üye formda açık bir uyarı görür (eskiden sessizce kilitli "Takımsız" alan + gri Kaydet).
 * (Sentetik sayfa kendi test dosyasında: ScriptedMonitorPage.list.test.jsx.)
 */
const PAGES = [
  ['Http', HttpMonitorPage, 'getHttpMonitors', 'updateHttpMonitor', (m) => m.url, { url: 'https://a.example.com/', method: 'GET', status: 'up' }],
  ['Keyword', KeywordMonitorPage, 'getKeywordMonitors', 'updateKeywordMonitor', (m) => m.url, { url: 'https://a.example.com/', keyword: 'Giriş', operator: 'GTE', match_count: 1, status: 'up' }],
  ['Page', PageMonitorPage, 'getPageMonitors', 'updatePageMonitor', (m) => m.url, { url: 'https://a.example.com/', mode: 'SINGLE', status: 'up' }],
  ['PageSpeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', 'updatePageSpeedMonitor', (m) => m.url, { url: 'https://a.example.com/', status: 'up', breached_metrics: [] }],
  ['Domain', DomainMonitorPage, 'getDomainMonitors', 'updateDomainMonitor', (m) => m.domain, { domain: 'a.example.com', status: 'OK', days_remaining: 90 }],
  ['Ping', PingMonitorPage, 'getPingMonitors', 'updatePingMonitor', (m) => m.host, { host: 'a.example.com', status: 'up', ip_version: 'V4', packet_count: 4 }],
  ['Port', PortMonitorPage, 'getPortMonitors', 'updatePortMonitor', (m) => `${m.host}:${m.port}`, { host: 'a.example.com', port: 443, protocol: 'TCP', status: 'open' }],
  ['Dns', DnsMonitorPage, 'getDnsMonitors', 'updateDnsMonitor', (m) => m.domain, { domain: 'a.example.com', record_type: 'A', value: '203.0.113.10', standalone: true }],
]

const ADD = /^(Yeni Monitör|Yeni İzleme|İzleme Ekle|New Monitor|Add Monitor)$/
const row = (fields, id, active, extra = {}) => ({
  id, name: `mon-${id}`, team_id: 5, team_name: 'Takım A', group_name: 'Grup', tags: 'prod', active,
  checked_at: '2026-09-26T09:00:00', ...fields, ...extra,
  ...(fields.url ? { url: fields.url.replace('a.', `${id === 1 ? 'a' : 'b'}.`) } : {}),
  ...(fields.domain ? { domain: fields.domain.replace('a.', `${id === 1 ? 'a' : 'b'}.`) } : {}),
  ...(fields.host ? { host: fields.host.replace('a.', `${id === 1 ? 'a' : 'b'}.`) } : {}),
})

describe.each(PAGES)('%s izleme sayfası — ortak standart', (name, Page, listFn, updateFn, labelOf, fields) => {
  const paused = row(fields, 1, false)
  const running = row(fields, 2, true)

  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [paused, running] })
    api.monitoring[updateFn].mockResolvedValue({ success: true, data: { ...paused, active: true } })
  })

  const cards = (container) => [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]

  it('duraklatılmış kartta "Sürdür": { active: true } yazılır, liste tazelenir; etkin kartta yok; rozet görünür, düğme soluk değil', async () => {
    const { container } = render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(cards(container)).toHaveLength(2))
    const [p, r] = cards(container)
    expect(p).toHaveAttribute('data-inactive', 'true')
    expect(r).not.toHaveAttribute('data-inactive')
    expect(within(r).queryByRole('button', { name: /(Sürdür|Resume)$/ })).toBeNull()
    expect(p.querySelector('[data-slot="card-footer"] [data-slot="monitor-paused"]').textContent).toMatch(/Duraklatıldı|Paused/)
    expect(r.querySelector('[data-slot="monitor-paused"]')).toBeNull()

    const btn = within(p).getByRole('button', { name: new RegExp(`^${labelOf(paused).replace(/[.:/]/g, (c) => `\\${c}`)} — (Sürdür|Resume)$`) })
    // Soluklaşma yalnız başlık/içerik kabında: Sürdür alt çubukta ve birincil
    expect(btn.closest('[data-slot="card-footer"]')).not.toBeNull()
    expect(btn.closest('[data-slot="card-content"], [data-slot="card-header"]')).toBeNull()
    expect(btn).toHaveAttribute('data-variant', 'default')

    const loadsBefore = api.monitoring[listFn].mock.calls.length
    fireEvent.click(btn)
    await waitFor(() => expect(api.monitoring[updateFn]).toHaveBeenCalledWith(1, { active: true }))
    await waitFor(() => expect(api.monitoring[listFn].mock.calls.length).toBeGreaterThan(loadsBefore))
  })

  it('detay penceresi duraklatılmış izlemede "Sürdür" sunar; etkin izlemede sunmaz', async () => {
    const { container } = render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(cards(container)).toHaveLength(2))
    fireEvent.click(container.querySelector('.upt-grid > [data-slot="card"] [data-monitor-open]'))
    const dlg = await screen.findByRole('dialog')
    const btn = within(dlg).getByRole('button', { name: /^(Sürdür|Resume)$/ })
    fireEvent.click(btn)
    await waitFor(() => expect(api.monitoring[updateFn]).toHaveBeenCalledWith(1, { active: true }))
    // Açık pencerenin kopyası da etkinleşir → Sürdür kaybolur (bayat "duraklatıldı" kalmaz)
    await waitFor(() => expect(within(screen.getByRole('dialog')).queryByRole('button', { name: /^(Sürdür|Resume)$/ })).toBeNull())
  })

  it('USER, tek takımlı + oturum takımı BOŞ: Ekle görünür, form o takımla açılır (kilitli "Takımsız" değil)', async () => {
    render(<Page systemRole="USER" teamId={null} teamName={null} myTeams={[{ id: 5, name: 'Takım A' }]} />)
    fireEvent.click(await screen.findByRole('button', { name: ADD }))
    const dlg = await screen.findByRole('dialog')
    const team = within(dlg).getByDisplayValue('Takım A')
    expect(team).toBeDisabled()
    expect(dlg.querySelector('[data-slot="alert"][data-tone="warning"]')).toBeNull()
    // Formun KENDİ takım değeri de o takım (yalnız görünen ad değil): form takımı dolunca sayfa o takımın
    // gruplarını ister — boş kalsaydı istek hiç atılmaz ve Kaydet takımsız kalırdı.
    await waitFor(() => expect(api.monitoring.listGroups).toHaveBeenCalledWith('5', expect.any(String)))
  })

  it('hiç takımı olmayan USER: formda "yöneticinize başvurun" uyarısı (sessiz kilit değil)', async () => {
    render(<Page systemRole="USER" teamId={null} teamName={null} myTeams={[]} />)
    fireEvent.click(await screen.findByRole('button', { name: ADD }))
    const dlg = await screen.findByRole('dialog')
    const alert = dlg.querySelector('[data-slot="alert"][data-tone="warning"]')
    expect(alert).not.toBeNull()
    expect(alert.textContent).toMatch(/yöneticinize başvurun|contact your administrator/)
    expect(within(dlg).queryByDisplayValue('Takım A')).toBeNull()
  })
})
