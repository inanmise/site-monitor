import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ dataQuality: { summary: vi.fn(), team: vi.fn() } }),
}))
const mobile = vi.hoisted(() => ({ value: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
import { api } from '../api/client'
import DataQualityPage from '../components/dataquality/DataQualityPage.jsx'

const trend = (base) => Array.from({ length: 10 }, (_, i) => ({ day: `2026-10-${String(i + 1).padStart(2, '0')}`, score: base - 9 + i }))

const SUMMARY = { success: true, data: {
  generated_at: '2026-10-10T06:00:00',
  org: { score: 81, band: 'GOOD', findings: 30, items: 400, delta_7d: 3, trend: trend(81), rules: [
    { code: 'TEAM_NO_ESCALATION', severity: 'HIGH', weight: 3, eligible: 4, failing: 2, points: 9.9 },
    { code: 'INV_NO_TIER', severity: 'MEDIUM', weight: 2, eligible: 300, failing: 12, points: 2.1 },
  ] },
  teams: [
    { id: 1, name: 'Ödeme', score: 41, band: 'POOR', findings: 18, items: 60, delta_7d: -4, trend: [],
      top_issues: [{ code: 'TEAM_NO_ESCALATION', severity: 'HIGH', failing: 1, eligible: 1, points: 18.8 }] },
    { id: 2, name: 'Altyapı', score: 96, band: 'EXCELLENT', findings: 1, items: 80, delta_7d: null, trend: [], top_issues: [] },
    { id: 3, name: 'Kanal', score: 78, band: 'GOOD', findings: 6, items: 50, delta_7d: 0, trend: [],
      top_issues: [{ code: 'INV_NO_TIER', severity: 'MEDIUM', failing: 3, eligible: 30, points: 3.1 }] },
  ],
  unassigned: { findings: 4, items: 3, rules: [] },
  notes: [{ code: 'NOC_NOT_CONFIGURED', params: { reason: 'NO_ACTIVE_GROUP', critical: 12 } }],
  catalog: [], config: { band_excellent: 90, band_good: 75, band_fair: 50 },
} }

const DETAIL = { success: true, data: {
  generated_at: '2026-10-10T06:00:00', unassigned: false, org_score: 81, notes: [],
  team: { id: 1, name: 'Ödeme', score: 41, band: 'POOR', findings: 4, items: 60, delta_7d: -4, trend: [] },
  rules: [
    { code: 'NOC_CRITICAL_UNCOVERED', scope: 'INVENTORY', severity: 'HIGH', weight: 3, eligible: 10, failing: 1, points: 12.5, truncated: 0,
      items: [{ kind: 'inventory', type: 'SSL', id: 11, name: 'pay.example.com', target: 'pay.example.com', team_id: 1, facts: { tier: 1 }, can_edit: true }] },
    { code: 'TEAM_NO_ESCALATION', scope: 'TEAM', severity: 'HIGH', weight: 3, eligible: 1, failing: 1, points: 30, truncated: 0,
      items: [{ kind: 'team', type: 'TEAM', id: 1, name: 'Ödeme', target: null, team_id: 1, facts: { missing: ['HIGH', 'CRITICAL'] }, can_edit: false }] },
    { code: 'MON_NO_GROUP', scope: 'MONITOR', severity: 'LOW', weight: 1, eligible: 5, failing: 2, points: 2.5, truncated: 0,
      items: [
        { kind: 'monitor', type: 'HTTP', id: 21, name: 'Ödeme API', target: 'https://pay.example.com/api', team_id: 1, facts: {}, can_edit: true },
        { kind: 'monitor', type: 'PING', id: 22, name: 'gw-01', target: '10.0.0.1', team_id: 1, facts: {}, can_edit: true },
      ] },
    { code: 'TEAM_NO_MEMBERS', scope: 'TEAM', severity: 'HIGH', weight: 3, eligible: 1, failing: 0, points: 0, items: [], truncated: 0 },
  ],
} }

function navEvents() {
  const events = []
  const on = (e) => events.push(e.detail)
  window.addEventListener('sm:navigate', on)
  return { events, stop: () => window.removeEventListener('sm:navigate', on) }
}

describe('Veri Kalitesi sayfası (2026-10-10)', () => {
  let nav
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.value = false
    window.history.replaceState({}, '', '/?tab=dataquality')
    api.dataQuality.summary.mockResolvedValue(SUMMARY)
    api.dataQuality.team.mockResolvedValue(DETAIL)
    nav = navEvents()
  })
  afterEach(() => nav.stop())

  it('kurum puanı, bant, 7 gün farkı, eğilim ve en çok puan kaybettiren sorunlar', async () => {
    render(<DataQualityPage />)
    const org = await screen.findByRole('progressbar', { name: /Organisation data quality score 81|Kurum veri kalitesi puanı 81/ })
    expect(org).toHaveAttribute('aria-valuenow', '81')
    const card = document.querySelector('[data-slot="dq-org"]')
    expect(within(card).getByText(/\+3/)).toBeInTheDocument()
    expect(card.querySelector('[data-slot="dq-trend"]')).not.toBeNull()
    const issues = document.querySelectorAll('[data-slot="dq-org-issue"]')
    expect([...issues].map((el) => el.dataset.code)).toEqual(['TEAM_NO_ESCALATION', 'INV_NO_TIER'])
    expect(api.dataQuality.summary).toHaveBeenCalledWith(false)
  })

  it('takım sıralaması tabloda: en düşük puan üstte; bant kartı süzer; arama süzer', async () => {
    render(<DataQualityPage />)
    await screen.findByRole('table')
    const ids = () => [...document.querySelectorAll('[data-slot="dq-team-row"]')].map((r) => r.dataset.teamId)
    expect(ids()).toEqual(['1', '3', '2'])
    // bant kartı (MonitorStatsBar): "Zayıf / Poor" yalnız 1 numara
    const poor = document.querySelector('[data-slot="stat-item"][data-tone="critical"]')
    fireEvent.click(poor)
    await waitFor(() => expect(ids()).toEqual(['1']))
    fireEvent.click(poor)
    await waitFor(() => expect(ids()).toHaveLength(3))
    fireEvent.change(screen.getByRole('searchbox', { name: /Search teams|Takım ara/ }), { target: { value: 'kan' } })
    await waitFor(() => expect(ids()).toEqual(['3']))
  })

  it('sıralama seçici: önce yüksek puan', async () => {
    render(<DataQualityPage />)
    await screen.findByRole('table')
    fireEvent.change(screen.getByRole('combobox', { name: /Sort by|Sıralama/ }), { target: { value: 'score_desc' } })
    await waitFor(() => expect([...document.querySelectorAll('[data-slot="dq-team-row"]')].map((r) => r.dataset.teamId))
      .toEqual(['2', '3', '1']))
  })

  it('telefonda takım KARTLARI (tablo yok), düğmeler 40 px', async () => {
    mobile.value = true
    render(<DataQualityPage />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="dq-team-card"]')).toHaveLength(3))
    expect(screen.queryByRole('table')).toBeNull()
    const btn = document.querySelector('[data-slot="dq-team-card"] [data-action="dq-team-open"]')
    expect(btn.className).toMatch(/h-10/)
  })

  it('7/24 kurulmamış notu; Sahipsiz kayıtlar şeridi listeyi açar', async () => {
    render(<DataQualityPage globalAdmin />)
    expect(await screen.findByText(/24\/7 notifications aren’t set up|7\/24 bildirimi kurulmamış/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /24\/7 settings|7\/24 ayarları/ }))
    expect(nav.events.at(-1)).toEqual({ tab: 'settings', params: { sec: 'noc' } })
    fireEvent.click(document.querySelector('[data-action="dq-unassigned-open"]'))
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalledWith('unassigned', false))
  })

  it('düzeltme listesi: kurallar akordiyonda, kalemler derin bağlantıyla doğru yeri açar', async () => {
    render(<DataQualityPage />)
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('button', { name: /Open the fix list for Ödeme|Ödeme için düzeltme listesini aç/ }))
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalledWith('1', false))
    await waitFor(() => expect(dialog.querySelectorAll('[data-slot="dq-rule"]').length).toBe(3))
    // etki sırası (en çok puan kaybettiren üstte); kusursuz kural (TEAM_NO_MEMBERS) listelenmez
    expect([...dialog.querySelectorAll('[data-slot="dq-rule"]')].map((r) => r.dataset.code))
      .toEqual(['TEAM_NO_ESCALATION', 'NOC_CRITICAL_UNCOVERED', 'MON_NO_GROUP'])
    // 7/24 bölümünü aç: kalemin "Düzelt"i → Pano sertifika penceresinin 7/24 alanı
    fireEvent.click(within(dialog).getByRole('button', { name: /Critical record not notified to 24\/7|Kritik kayıt 7\/24 ekibine bildirilmiyor/ }))
    const fix = await within(dialog).findByRole('button', { name: /Open pay\.example\.com to fix it|pay\.example\.com kaydını düzeltmek için aç/ })
    fireEvent.click(fix)
    expect(nav.events.at(-1)).toEqual({ tab: 'dashboard', params: { domain: 'pay.example.com', open: 'noc' } })
  })

  it('takım kalemi düzeltilemiyorsa "Aç" (salt okunur); eskalasyon → Yönetim/Eskalasyon kişileri', async () => {
    window.history.replaceState({}, '', '/?tab=dataquality&dq_team=1')
    render(<DataQualityPage />)
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(dialog.querySelectorAll('[data-slot="dq-rule"]').length).toBe(3))
    // en çok puan kaybettiren bölüm (eskalasyon) varsayılan açık
    expect(dialog.querySelector('[data-slot="dq-rule"][data-code="TEAM_NO_ESCALATION"]')).toHaveAttribute('data-state', 'open')
    const open = await within(dialog).findByRole('button', { name: /^(Open Ödeme|Ödeme kaydını aç)$/ })
    fireEvent.click(open)
    expect(nav.events.at(-1)).toEqual({ tab: 'admin', params: { g_tab: 'contacts', g_team: '1' } })
  })

  it('önem süzgeci ve kalem araması; eşleşme yoksa boş durum', async () => {
    window.history.replaceState({}, '', '/?tab=dataquality&dq_team=1')
    render(<DataQualityPage />)
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(dialog.querySelectorAll('[data-slot="dq-rule"]').length).toBe(3))
    fireEvent.click(within(dialog).getByRole('button', { name: /^(Low|Düşük)$/ }))
    await waitFor(() => expect([...dialog.querySelectorAll('[data-slot="dq-rule"]')].map((r) => r.dataset.code)).toEqual(['MON_NO_GROUP']))
    fireEvent.change(within(dialog).getByRole('searchbox'), { target: { value: 'yok-boyle-bir-sey' } })
    await waitFor(() => expect(dialog.querySelectorAll('[data-slot="dq-rule"]').length).toBe(0))
    expect(within(dialog).getByText(/No matches|Eşleşen sonuç yok/)).toBeInTheDocument()
  })

  it('düzeltilecek bir şey yoksa başarı durumu; CSV düğmesi pasif', async () => {
    api.dataQuality.team.mockResolvedValue({ success: true, data: { ...DETAIL.data, rules: [DETAIL.data.rules[3]] } })
    window.history.replaceState({}, '', '/?tab=dataquality&dq_team=2')
    render(<DataQualityPage />)
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/Nothing to fix|Düzeltilecek bir şey yok/)).toBeInTheDocument()
    expect(dialog.querySelector('[data-action="dq-csv"]')).toBeDisabled()
  })

  it('hata: yükleme düşerse açıklayıcı durum + Tekrar dene', async () => {
    api.dataQuality.summary.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı; ağ bağlantınızı kontrol edin.'))
    render(<DataQualityPage />)
    expect(await screen.findByText(/Sunucuya ulaşılamadı/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Try again|Tekrar dene/ }))
    await screen.findByRole('table')
    expect(api.dataQuality.summary).toHaveBeenLastCalledWith(true)
  })

  it('görünür takım yoksa boş durum; varsayılan dizi gövdesi (mock) çökmez', async () => {
    api.dataQuality.summary.mockResolvedValue({ success: true, data: { ...SUMMARY.data, teams: [], unassigned: undefined, notes: [] } })
    render(<DataQualityPage />)
    expect(await screen.findByText(/No teams to show|Görüntülenecek takım yok/)).toBeInTheDocument()
  })

  it('Yenile sunucu belleğini atlar (fresh)', async () => {
    render(<DataQualityPage />)
    await screen.findByRole('table')
    fireEvent.click(document.querySelector('[data-action="dq-refresh"]'))
    await waitFor(() => expect(api.dataQuality.summary).toHaveBeenLastCalledWith(true))
  })
})
