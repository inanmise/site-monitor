import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import AlertHistory from '../components/admin/AlertHistory.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(), getTeams: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))

import { api } from '../api/client'

/**
 * Alarm Geçmişi — hızlı dönemler, tarih alanı seçici, sütun sıralaması / süzgeçleri, Takım sütunu (2026-10-01).
 * Liste istekleri: üst istatistiklerin iki küçük isteği (size=1) ayıklanır.
 */
const listCalls = () => api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p?.size !== 1)
const lastList = () => listCalls().at(-1)
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/

const closed = (o = {}) => ({
  id: 101, domain: 'foo.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', acknowledged: false, resolved: true,
  resolved_by: 'system', resolved_at: '2026-06-07T10:00:00', created_at: '2026-06-01T08:00:00', team_id: 5, team_name: 'Ops', ...o,
})
const open = (o = {}) => ({ id: 7, domain: 'bar.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL', acknowledged: false, resolved: false,
  created_at: '2026-08-01T08:00:00', ...o })

beforeEach(() => {
  vi.clearAllMocks()
  MOBILE = false
  window.history.replaceState({}, '', '/')
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Ops' }, { id: 6, name: 'Net' }] })
  api.admin.getAlerts.mockResolvedValue({ success: true, data: [open()], total: 1, page: 0, size: 20, type_counts: { EXPIRY: 1, HTTP_DOWN: 3 } })
})
afterEach(() => { MOBILE = false; window.history.replaceState({}, '', '/') })

describe('hızlı dönemler (ToggleGroup)', () => {
  it('"Last hour" → AÇIK görünümde since gider (göreli damga), çip "Period: Last hour", URL from=1h; çip × kaldırır', async () => {
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()).toBeTruthy())
    expect(lastList().since).toBeUndefined()
    const presets = screen.getByRole('radiogroup', { name: 'Period' })   // Radix ToggleGroup type="single" → radiogroup
    expect(within(presets).getByRole('radio', { name: 'All time' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(within(presets).getByRole('radio', { name: 'Last hour' }))
    await waitFor(() => expect(lastList().since).toMatch(ISO))
    expect(Date.now() - new Date(lastList().since + 'Z').getTime()).toBeGreaterThan(3_500_000)
    expect(lastList().resolved).toBe('false'); expect(lastList().until).toBeUndefined()
    const chips = screen.getByRole('group', { name: /Active filters/ })
    expect(within(chips).getByText('Period: Last hour')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('from=1h'), { timeout: 2000 })
    fireEvent.click(within(chips).getByRole('button', { name: 'Remove filter: Period: Last hour' }))
    await waitFor(() => expect(lastList().since).toBeUndefined())
    await waitFor(() => expect(window.location.search).not.toContain('from='), { timeout: 2000 })
  })

  it('URL from=7d ile açılır: 7 gün öncesinin yerel gün başından itibaren; "Last 7 days" seçili; "Custom" tarih seçiciyi açar', async () => {
    window.history.replaceState({}, '', '/?from=7d&view=all')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.since).toMatch(ISO))
    const sinceMs = new Date(lastList().since + 'Z').getTime()
    expect(Date.now() - sinceMs).toBeGreaterThan(7 * 86_400_000 - 1)
    expect(Date.now() - sinceMs).toBeLessThan(8 * 86_400_000 + 1)
    const presets = screen.getByRole('radiogroup', { name: 'Period' })
    expect(within(presets).getByRole('radio', { name: 'Last 7 days' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(within(presets).getByRole('radio', { name: 'Custom' }))
    await screen.findByRole('radiogroup', { name: 'Which alarms should the range include?' })   // tarih penceresi açıldı
  })

  it('boş sonuç + hızlı dönem → "No alerts in this period" ve "Widen period: Last 24h" bir üst dönemi seçer (30d → tüm zamanlar)', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 20 })
    window.history.replaceState({}, '', '/?from=1h')
    render(<AlertHistory urlSync />)
    await screen.findByText('No alerts in this period')
    fireEvent.click(screen.getByRole('button', { name: 'Widen period: Last 24h' }))
    await waitFor(() => expect(window.location.search).toContain('from=24h'), { timeout: 2000 })
    await screen.findByRole('button', { name: 'Widen period: Last 7 days' })
    fireEvent.click(screen.getByRole('button', { name: 'Widen period: Last 7 days' }))
    await screen.findByRole('button', { name: 'Widen period: Last 30 days' })
    fireEvent.click(screen.getByRole('button', { name: 'Widen period: Last 30 days' }))
    const all = await screen.findByRole('button', { name: 'Show all time' })
    fireEvent.click(all)
    await waitFor(() => expect(lastList().since).toBeUndefined())
    expect(screen.queryByText('No alerts in this period')).toBeNull()
  })
})

describe('tarih alanı seçici (Açılış / Kapanış)', () => {
  it('KAPALI: varsayılan kapanış (resolvedSince); "Opened" seçilince since + URL range=opened + çip "By opened date"', async () => {
    window.history.replaceState({}, '', '/?view=closed&from=2026-09-21&to=2026-09-27')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closed()], total: 1, page: 0, size: 20 })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.resolvedSince).toMatch(ISO))
    expect(lastList().since).toBeUndefined()
    const sel = screen.getAllByRole('combobox', { name: 'Date field' })[0]
    expect(sel.value).toBe('resolved')
    expect(within(sel).getAllByRole('option').map((o) => o.value)).toEqual(['resolved', 'opened'])
    fireEvent.change(sel, { target: { value: 'opened' } })
    await waitFor(() => expect(lastList().since).toMatch(ISO))
    expect(lastList().until).toMatch(ISO); expect(lastList().resolvedSince).toBeUndefined()
    expect(within(screen.getByRole('group', { name: /Active filters/ })).getByText('By opened date')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('range=opened'), { timeout: 2000 })
  })

  it('AÇIK görünümde tarih alanı seçicisi YOK (yalnız açılış)', async () => {
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()).toBeTruthy())
    expect(screen.queryByRole('combobox', { name: 'Date field' })).toBeNull()
  })

  it('TÜMÜ\'nde üç seçenek; "Resolved" → resolvedSince (range=resolved), since gitmez', async () => {
    window.history.replaceState({}, '', '/?view=all&from=2026-09-21')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.since).toMatch(ISO))
    const sel = await screen.findByRole('combobox', { name: 'Date field' })
    expect(within(sel).getAllByRole('option').map((o) => o.value)).toEqual(['opened', 'resolved', 'active'])
    fireEvent.change(sel, { target: { value: 'resolved' } })
    await waitFor(() => expect(lastList().resolvedSince).toMatch(ISO))
    expect(lastList().since).toBeUndefined(); expect(lastList().range).toBeUndefined()
  })
})

describe('sıralama', () => {
  it('tablo başlığı (KAPALI): varsayılan Kapanış descending; "Sort by Opened" → sort=opened&dir=desc, tekrar → asc; aria-sort güncel', async () => {
    window.history.replaceState({}, '', '/?view=closed')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closed()], total: 1, page: 0, size: 20 })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    expect(lastList().sort).toBeUndefined()
    const th = (k) => document.querySelector(`th[data-sort-key="${k}"]`)
    expect(th('resolved')).toHaveAttribute('aria-sort', 'descending')
    expect(th('opened')).toHaveAttribute('aria-sort', 'none')
    expect(th('level')).toHaveAttribute('aria-sort', 'none')
    fireEvent.click(screen.getByRole('button', { name: 'Sort by Opened' }))
    await waitFor(() => expect(lastList()).toMatchObject({ sort: 'opened', dir: 'desc' }))
    expect(th('opened')).toHaveAttribute('aria-sort', 'descending')
    expect(th('resolved')).toHaveAttribute('aria-sort', 'none')
    await waitFor(() => expect(window.location.search).toContain('sort=opened'), { timeout: 2000 })
    fireEvent.click(screen.getByRole('button', { name: 'Sort by Opened' }))
    await waitFor(() => expect(lastList()).toMatchObject({ sort: 'opened', dir: 'asc' }))
    expect(th('opened')).toHaveAttribute('aria-sort', 'ascending')
    // Seviye sıralamasında gün bölümü yok
    fireEvent.click(screen.getByRole('button', { name: 'Sort by Level' }))
    await waitFor(() => expect(lastList()).toMatchObject({ sort: 'level', dir: 'desc' }))
    await waitFor(() => expect(document.querySelector('[data-slot="day-group"]')).toBeNull())
    expect(document.querySelector('[data-alert-row]')).not.toBeNull()
  })

  it('telefon: araç çubuğundaki sıralama seçicisi (NativeSelect) aynı seçenekler; "Level: critical first" → sort=level; açıkta "Resolved" yok', async () => {
    MOBILE = true
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()).toBeTruthy())
    const sel = screen.getByRole('combobox', { name: 'Sort' })
    const values = within(sel).getAllByRole('option').map((o) => o.value)
    expect(values).toEqual(['opened_desc', 'opened_asc', 'level_desc', 'level_asc', 'team_desc', 'team_asc', 'domain_desc', 'domain_asc', 'type_desc', 'type_asc'])
    expect(sel.value).toBe('opened_desc')
    fireEvent.change(sel, { target: { value: 'level_desc' } })
    await waitFor(() => expect(lastList()).toMatchObject({ sort: 'level', dir: 'desc' }))
    await waitFor(() => expect(window.location.search).toContain('sort=level'), { timeout: 2000 })
    fireEvent.change(sel, { target: { value: 'opened_desc' } })
    await waitFor(() => expect(lastList().sort).toBeUndefined())
  })

  it('URL sort=team_asc ile açılır → istek sort=team&dir=asc; bilinmeyen sort düşer', async () => {
    window.history.replaceState({}, '', '/?view=all&sort=team_asc')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()).toMatchObject({ sort: 'team', dir: 'asc' }))
  })
})

describe('Takım sütunu ve sütun süzgeçleri', () => {
  it('tabloda Takım sütunu (team_name sunucudan); rozete tıklama takım süzgecini uygular (teamId + çip), satır detayı AÇILMAZ', async () => {
    window.history.replaceState({}, '', '/?view=closed')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closed(), closed({ id: 102, domain: 'cert.example.com', alert_type: 'EXPIRY', team_id: null, sy_team_id: 6, sy_team_name: 'Net' })], total: 2, page: 0, size: 20 })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="alert-team-cell"]')).toHaveLength(2))
    expect(screen.getByRole('columnheader', { name: /Team/ })).toBeInTheDocument()
    const cells = document.querySelectorAll('[data-slot="alert-team-cell"]')
    expect(cells[0].textContent).toContain('Ops')
    expect(cells[1].textContent).toContain('Net')   // sertifika alarmı: envanterin SY takımı
    fireEvent.click(within(cells[0]).getByRole('button', { name: 'Filter by team: Ops' }))
    await waitFor(() => expect(lastList().teamId).toBe('5'))
    expect(within(screen.getByRole('group', { name: /Active filters/ })).getByText('Team: Ops')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(window.location.search).toContain('team=5'), { timeout: 2000 })
  })

  it('başlık süzgeç menüsü: "Filter by Level" → Critical → level=CRITICAL ve çip; Tür / Takım / Durum menüleri de var', async () => {
    window.history.replaceState({}, '', '/?view=all')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closed()], total: 1, page: 0, size: 20, type_counts: { HTTP_DOWN: 1 } })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    for (const col of ['level', 'type', 'team', 'ack']) expect(document.querySelector(`[data-slot="column-filter"][data-column="${col}"]`)).not.toBeNull()
    pressMenuTrigger(screen.getByRole('button', { name: 'Filter by Level' }))
    const item = await screen.findByRole('menuitemradio', { name: 'CRITICAL' })   // EN seviye etiketi büyük harf (alh.level.critical)
    fireEvent.click(item)
    await waitFor(() => expect(lastList().level).toBe('CRITICAL'))
    expect(within(screen.getByRole('group', { name: /Active filters/ })).getByText('CRITICAL')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="column-filter"][data-column="level"]')).toHaveAttribute('data-active', 'true')
    pressMenuTrigger(screen.getByRole('button', { name: 'Filter by Team' }))
    fireEvent.click(await screen.findByRole('menuitemradio', { name: 'Net' }))
    await waitFor(() => expect(lastList().teamId).toBe('6'))
  })

  it('açık kartta takım rozeti (bağımsız sayfa) takım süzgecini uygular; telefon kartında takım satırı var', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open({ team_id: 5, team_name: 'Ops' })], total: 1, page: 0, size: 20 })
    render(<AlertHistory urlSync />)
    const badge = await screen.findByRole('button', { name: 'Filter by team: Ops' })
    fireEvent.click(badge)
    await waitFor(() => expect(lastList().teamId).toBe('5'))
  })

  it('telefon kapalı kartı takım satırı taşır', async () => {
    MOBILE = true
    window.history.replaceState({}, '', '/?view=closed')
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closed()], total: 1, page: 0, size: 20 })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-team-line"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="alert-team-line"]').textContent).toContain('Ops')
  })
})
