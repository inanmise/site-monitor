import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import CheckTeamPicker from '../components/check/CheckTeamPicker.jsx'
import { WhyOpenChips } from '../components/admin/alerts/AlertBadges.jsx'
import StormSettings from '../components/admin/StormSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { storm: {} }, admin: {} }),
}))
import { api } from '../api/client'

/**
 * ELLE KONTROL KAPSAMI + FIRTINA YALITIMI — arayüz sözleşmesi (ürün kararı 2026-09-29, prod olayı).
 *
 * <p>Kapsamlı yönetici (AD ADMIN: rol dizesi "ADMIN" ama takım kapsamlı) sayfalarda `isAdmin` sayıldığı için
 * görebildiği her satır toplu "Şimdi Kontrol Et (N)" sayısına ve kuyruğuna giriyordu; sunucu başka takımın satırını
 * 403 ile reddediyordu. Artık sunucu her satıra tetik ucunun kapısıyla AYNI kuraldan `can_check` yazar; sayı ve kuyruk
 * bayrağı `false` olan satırı İÇERMEZ (bayrak yoksa eski davranış). Dokuz sayfanın sekizi burada; sentetik sayfa
 * kendi düzeneğiyle ScriptedMonitorPage.checkScope.test.jsx'te.
 */
const PAGES = [
  ['Http', HttpMonitorPage, 'getHttpMonitors', { url: 'https://a.example.com/', method: 'GET', status: 'up' }],
  ['Keyword', KeywordMonitorPage, 'getKeywordMonitors', { url: 'https://a.example.com/', keyword: 'Giriş', operator: 'GTE', match_count: 1, status: 'up' }],
  ['Page', PageMonitorPage, 'getPageMonitors', { url: 'https://a.example.com/', mode: 'SINGLE', status: 'up' }],
  ['PageSpeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', { url: 'https://a.example.com/', status: 'up', breached_metrics: [] }],
  ['Domain', DomainMonitorPage, 'getDomainMonitors', { domain: 'a.example.com', status: 'OK', days_remaining: 90 }],
  ['Ping', PingMonitorPage, 'getPingMonitors', { host: 'a.example.com', status: 'up', ip_version: 'V4', packet_count: 4 }],
  ['Port', PortMonitorPage, 'getPortMonitors', { host: 'a.example.com', port: 443, protocol: 'TCP', status: 'open' }],
  ['Dns', DnsMonitorPage, 'getDnsMonitors', { domain: 'a.example.com', record_type: 'A', value: '203.0.113.10', standalone: true }],
]

const row = (fields, id, teamId, extra = {}) => ({
  id, name: `mon-${id}`, team_id: teamId, team_name: teamId === 5 ? 'Takım A' : 'Takım B', group_name: 'Grup', tags: 'prod',
  active: true, checked_at: '2026-09-26T09:00:00', ...fields, ...extra,
  ...(fields.url ? { url: fields.url.replace('a.', `h${id}.`) } : {}),
  ...(fields.domain ? { domain: fields.domain.replace('a.', `h${id}.`) } : {}),
  ...(fields.host ? { host: fields.host.replace('a.', `h${id}.`) } : {}),
})

const checkAll = (n) => new RegExp(`^(Şimdi Kontrol Et|Check Now) \\(${n}\\)$`)

describe.each(PAGES)('%s izleme sayfası — toplu kontrol sayısı sunucunun can_check bayrağından', (_name, Page, listFn, fields) => {
  beforeEach(() => { vi.clearAllMocks() })

  it('kapsamlı yönetici: can_check=false satır "Şimdi Kontrol Et (N)" sayısına GİRMEZ; bayraksız satır eski davranışla girer', async () => {
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [
      row(fields, 1, 5, { can_check: true }),
      row(fields, 2, 9, { can_check: false }),   // başka takımın satırı (ör. UG olarak görünen envanter-türevi)
      row(fields, 3, 5),                          // bayrak yok (eski sunucu) → eski davranış
    ] })
    render(<Page systemRole="ADMIN" globalAdmin={false} teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    expect(await screen.findByRole('button', { name: checkAll(2) })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: checkAll(3) })).toBeNull()
  })

  // D-4 (2026-09-29): kartın tekil ▶ düğmesi de aynı kural — can_check=false satırda HİÇ çizilmez (basılıp 403 yiyen düğme yok).
  it('kapsamlı yönetici: can_check=false kartta tekil "Şimdi kontrol et" düğmesi YOK, diğer kartlarda VAR', async () => {
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [
      row(fields, 1, 5, { can_check: true }),
      row(fields, 2, 9, { can_check: false }),
      row(fields, 3, 5),
    ] })
    const { container } = render(<Page systemRole="ADMIN" globalAdmin={false} teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await screen.findByRole('button', { name: checkAll(2) })
    const counts = [...container.querySelectorAll('.upt-grid > [data-slot="card"] [data-slot="card-footer"]')]
      .map((f) => f.querySelectorAll('button').length).sort((a, b) => a - b)
    expect(counts).toHaveLength(3)
    expect(counts[0]).toBe(counts[2] - 1)   // yalnız can_check=false kartta tek düğme eksik (▶)
    expect(counts[1]).toBe(counts[2])
  })
})

describe('Toplu kontrol takım seçicisi — elle kontrol notu', () => {
  it('"alarm açmaz / fırtına ve bildirim tetiklemez" bilgisini BAŞLATMADAN önce gösterir', () => {
    render(<CheckTeamPicker buckets={[{ key: 'Takım A', label: 'Takım A', count: 2 }]} onStart={vi.fn()} onClose={vi.fn()} />)
    const note = document.querySelector('[data-slot="alert"][data-tone="info"]')
    expect(note).not.toBeNull()
    expect(note.textContent).toMatch(/yeni alarm açmaz|won't raise a new alert/)
    expect(note.textContent).toMatch(/fırtına|storm/i)
  })
})

describe('"Neden hâlâ açık?" — fırtına üyesi alarm', () => {
  const nobodyChip = () => document.querySelector('[data-why] [data-tone="danger"]')

  it('fırtına üyesinde "kimseye ulaşmadı" (kırmızı) YOK, "fırtına bildirimine devredildi" (bilgi) VAR', () => {
    render(<WhyOpenChips alert={{ alert_type: 'HTTP_DOWN', storm_id: 42, email_sent_count: 0 }} push={null} />)
    const storm = document.querySelector('[data-why-storm]')
    expect(storm).not.toBeNull()
    expect(storm).toHaveAttribute('data-tone', 'info')
    expect(nobodyChip()).toBeNull()
  })

  it('pozitif kontrol: fırtına üyesi OLMAYAN ve hiç bildirim gitmemiş alarmda kırmızı çip görünür', () => {
    render(<WhyOpenChips alert={{ alert_type: 'HTTP_DOWN', storm_id: null, email_sent_count: 0 }} push={null} />)
    expect(document.querySelector('[data-why-storm]')).toBeNull()
    expect(nobodyChip()).not.toBeNull()
  })
})

describe('Alarm fırtınası ayarları — takım yalıtımı notu', () => {
  it('fırtınanın TAKIM BAZINDA değerlendirildiğini ve kuruluş geneli bildirim olmadığını söyler; yüzde birimi takımın izlemeleri', async () => {
    api.monitoring.storm.getSettings.mockResolvedValue({ success: true, data: {
      enabled: true, threshold_unit: 'PERCENT', threshold_value: 20, window_minutes: 5, per_group: false,
      total_active_monitors: 40, effective_threshold: 8,
    } })
    render(<StormSettings />)
    await screen.findAllByRole('spinbutton')
    expect(screen.getByText(/her takım için ayrı değerlendirilir|assessed separately for each team/)).toBeInTheDocument()
    expect(screen.queryByText(/Tüm monitörlerin|% of all monitors/)).toBeNull()
    expect(screen.getByRole('option', { name: /Takımın izlemelerinin|of the team's monitors/ })).toBeInTheDocument()
  })
})
