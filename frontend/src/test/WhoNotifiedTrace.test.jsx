import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import RecipientSimulator from '../components/admin/RecipientSimulator.jsx'
import { buildView, recipientCsvRows, compareLevels } from '../components/admin/whonotified/whoNotifiedModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { simulateRecipients: vi.fn() },
    notificationGroups: { list: vi.fn() },
    noc: { coverage: vi.fn() },
  }),
}))
const blobs = vi.hoisted(() => ({ calls: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({
  ...(await orig()),
  downloadBlob: (name, blob) => { blobs.calls.push({ name, blob }); return true },
}))
import { api } from '../api/client'

/**
 * "Kim bilgilendirilir?" zincir izi (2026-11-13): hariç kalanların gerekçesi, nöbetçi, CSV dışa aktarım ve seviyelere göre
 * karşılaştırma. Kararlar sunucunun — model ve ekran yalnız biçimler.
 */
const TEAMS = [{ id: 7, name: 'Takım A' }]
const sim = (level, extra = {}) => ({
  team_id: 7, team_name: 'Takım A', level, standalone_monitor: false, managers_included: true,
  team_contacts_missing: false, team_contacts_defined: true, email_total: 1,
  team_emails: [{ email: 'takim@x.com', team: 'Takım A', source: 'Takım maili', kind: 'TEAM' }],
  contacts: [], webhooks: [], push: [], push_access: 'NONE',
  excluded_contacts: [{ id: 3, name: 'Kritik Müdür', email: 'kritik@x.com', role: 'MANAGER', min_level: 'CRITICAL', reason: 'LEVEL_THRESHOLD' }],
  ...extra,
})
const BY_LEVEL = {
  WARNING: sim('WARNING'),
  HIGH: sim('HIGH', { oncall: [{ team_id: 7, name: 'Nöbetçi Ayşe', email: 'ayse@x.com', duplicate: false }] }),
  CRITICAL: sim('CRITICAL', {
    contacts: [{ id: 3, name: 'Kritik Müdür', email: 'kritik@x.com', role: 'MANAGER', min_level: 'CRITICAL', team_id: 7, email_duplicate: false }],
    excluded_contacts: [], oncall: [{ team_id: 7, name: 'Nöbetçi Ayşe', email: 'ayse@x.com', duplicate: false }],
  }),
}

describe('zincir izi — model', () => {
  it('CSV: başlık + dahil olanlar + hariç kalanlar gerekçesiyle; dil etiketleri çağırandan', () => {
    const v = buildView(BY_LEVEL.HIGH)
    const rows = recipientCsvRows(v, {
      header: ['K', 'Ad', 'Adres', 'Kaynak', 'Durum', 'Gerekçe'],
      channel: (c) => c, source: (s) => s, yes: 'Alır', no: 'Almaz', later: (m) => `${m} dk`, reason: (r) => `R:${r}`,
    })
    expect(rows[0]).toEqual(['K', 'Ad', 'Adres', 'Kaynak', 'Durum', 'Gerekçe'])
    expect(rows.slice(1)).toEqual([
      ['email', 'Takım A', 'takim@x.com', 'team', 'Alır', ''],
      ['email', 'Nöbetçi Ayşe', 'ayse@x.com', 'oncall', 'Alır', ''],
      ['email', 'Kritik Müdür', 'kritik@x.com', '', 'Almaz', 'R:LEVEL_THRESHOLD'],
    ])
  })

  it('karşılaştırma: seviyeye göre değişen satırlar önce; okunamayan seviye "bilinmiyor"', () => {
    const rows = compareLevels({ WARNING: buildView(BY_LEVEL.WARNING), HIGH: buildView(BY_LEVEL.HIGH), CRITICAL: null })
    expect(rows.map((r) => [r.name, r.varies])).toEqual([['Nöbetçi Ayşe', true], ['Kritik Müdür', false], ['Takım A', false]])
    const muduR = rows.find((r) => r.name === 'Kritik Müdür')
    expect(muduR.cells).toEqual({ WARNING: { state: 'no', reason: 'LEVEL_THRESHOLD' }, HIGH: { state: 'no', reason: 'LEVEL_THRESHOLD' }, CRITICAL: { state: 'unknown' } })
    expect(rows.find((r) => r.name === 'Nöbetçi Ayşe').cells.WARNING).toEqual({ state: 'no' })
  })
})

describe('zincir izi — ekran', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    blobs.calls = []
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified&g_team=7')
    api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [], writable_team_ids: [7], team_names: {}, team_emails: {} } })
    api.noc.coverage.mockResolvedValue({ success: true, data: { summary: { total: 0, covered: 0, not_covered: 0, paused: 0, by_type: {}, active_groups: 0, disabled_types: [], min_level: 'CRITICAL' }, items: [] } })
    api.admin.simulateRecipients.mockImplementation(async ({ level }) => ({ success: true, data: BY_LEVEL[level] }))
  })
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('nöbetçi e-posta kartında, eşiği yüksek kişi gerekçesiyle "bilgilendirilmeyenler"de', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    await waitFor(() => expect(document.querySelector('[data-slot="wn-recipient"][data-source="oncall"]')).toBeTruthy())
    expect(document.querySelector('[data-slot="stat-item"][data-key="email"] [data-slot="stat-value"]').textContent).toBe('2')
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="excluded"]'))   // bölüm kapalı başlar
    const excluded = await waitFor(() => { const el = document.querySelector('[data-slot="wn-recipient"][data-source="excluded-email"]'); expect(el).toBeTruthy(); return el })
    expect(excluded.textContent).toMatch(/Kritik Müdür/)
    expect(excluded.textContent).toMatch(/eşiği bu seviyenin üstünde|threshold is above this level/)
  })

  it('CSV indir: dosya adı takım + seviye, BOM ile Excel uyumlu', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    const btn = await waitFor(() => { const b = document.querySelector('[data-slot="wn-export"]'); expect(b).toBeTruthy(); return b })
    fireEvent.click(btn)
    expect(blobs.calls).toHaveLength(1)
    expect(blobs.calls[0].name).toBe('kim-bilgilendirilir-takım-a-high.csv')
    const bytes = new Uint8Array(await blobs.calls[0].blob.arrayBuffer())
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf])   // UTF-8 BOM (Blob.text() onu siler)
    const text = await blobs.calls[0].blob.text()
    expect(text).toContain('ayse@x.com')
    expect(text).toContain('kritik@x.com')
  })

  it('seviye karşılaştırması kapalı başlar; açılınca üç seviye simüle edilir ve tablo çizilir', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    await waitFor(() => expect(document.querySelector('[data-slot="wn-compare-section"]')).toBeTruthy())
    expect(api.admin.simulateRecipients).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /Seviyelere göre karşılaştır|Compare by level/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="wn-compare-row"]').length).toBe(3))
    const levels = api.admin.simulateRecipients.mock.calls.map((c) => c[0].level)
    expect(levels).toEqual(expect.arrayContaining(['WARNING', 'HIGH', 'CRITICAL']))
    const varies = document.querySelectorAll('[data-slot="wn-compare-row"][data-varies="true"]')
    expect(varies.length).toBe(2)   // nöbetçi (UYARI'da yok) ve kritik müdür (yalnız KRİTİK'te)
  })
})
