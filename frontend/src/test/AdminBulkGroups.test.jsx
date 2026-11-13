import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils'

/**
 * Bildirim grupları TOPLU işlem (2026-11-13, (d)): yalnız yazılabilir grup seçilir; silme önizlemesi kullanım sayısından
 * "kullanılıyor / varsayılan" atlamalarını öngörür, kullanım okunamadıysa "sunucu denetleyecek" der (asla "kullanılmıyor").
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const state = vi.hoisted(() => ({ groups: [], counts: {}, countsFail: false, stamped: {} }))

vi.mock('../api/client', () => ({
  formatDateSec: (v) => v ?? '',
  api: withApiFallback({
    notificationGroups: {
      list: vi.fn(async () => ({ success: true, data: { groups: state.groups, writable_team_ids: ['1', '2'], team_emails: { 1: 'a@x', 2: 'b@x' } } })),
      usageCounts: vi.fn(async () => (state.countsFail ? { success: false } : { success: true, data: { counts: state.counts, stamped_open_alerts: state.stamped } })),
      bulk: vi.fn(),
      history: vi.fn(async () => ({ success: true, data: { items: [], truncated: false, hidden: 0 } })),
    },
  }),
}))

const { api } = await import('../api/client')
const NotificationGroups = (await import('../components/admin/NotificationGroups.jsx')).default
const TEAMS = [{ id: 1, name: 'SY-A' }, { id: 2, name: 'SY-B' }]
const grp = (id, name, over = {}) => ({ id, team_id: 1, name, emails: ['a@example.com'], inactive_emails: [], is_default: false, active: true, can_write: true, ...over })
const rowOf = (id) => document.querySelector(`[data-group-id="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=notifyGroups')
  state.countsFail = false
  state.groups = [grp(10, 'Kullanilan'), grp(11, 'Bos'), grp(12, 'Ana', { is_default: true }), grp(13, 'Salt okunur', { can_write: false })]
  state.counts = { 10: 2 }
  state.stamped = {}
})

describe('NotificationGroups — toplu işlem', () => {
  it('salt okunur grupta seçim kutusu yok; silme önizlemesi + tek istek + sonuç', async () => {
    api.notificationGroups.bulk.mockResolvedValue({ success: true, data: { action: 'delete', done: 1, skipped: 2, failed: 0, results: [
      { id: 10, name: 'Kullanilan', team_id: 1, team_name: 'SY-A', status: 'skipped', reason: 'in_use', usage: 2 },
      { id: 11, name: 'Bos', team_id: 1, team_name: 'SY-A', status: 'done', reason: 'done' },
      { id: 12, name: 'Ana', team_id: 1, team_name: 'SY-A', status: 'skipped', reason: 'is_default' }] } })
    render(<NotificationGroups teams={TEAMS} />)
    await waitFor(() => expect(rowOf(13)).toBeTruthy())
    expect(within(rowOf(13)).queryByRole('checkbox')).toBeNull()
    for (const id of [10, 11, 12]) fireEvent.click(within(rowOf(id)).getByRole('checkbox'))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="ng-bulk-bar"]'); expect(b).toBeTruthy(); return b })
    fireEvent.click(bar.querySelector('[data-action="delete"]'))
    const dlg = await screen.findByRole('dialog')
    const states = Object.fromEntries([...dlg.querySelectorAll('[data-slot="bulk-preview-row"]')].map((r) => [r.textContent.includes('Bos') ? 'bos' : r.textContent.includes('Ana') ? 'ana' : 'kul', r.getAttribute('data-reason') || r.getAttribute('data-state')]))
    expect(states).toEqual({ bos: 'change', kul: 'in_use', ana: 'is_default' })
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(api.notificationGroups.bulk).toHaveBeenCalledWith({ action: 'delete', ids: [10, 11, 12] }))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="bulk-result-row"]')).toHaveLength(3))
    fireEvent.click(dlg.querySelector('[data-slot="bulk-close"]'))
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalledTimes(2))
  })

  it('kullanım sayıları okunamadıysa silme önizlemesi "sunucu denetleyecek" der', async () => {
    state.countsFail = true
    render(<NotificationGroups teams={TEAMS} />)
    await waitFor(() => expect(rowOf(11)).toBeTruthy())
    fireEvent.click(within(rowOf(11)).getByRole('checkbox'))
    fireEvent.click(await waitFor(() => document.querySelector('[data-slot="ng-bulk-bar"] [data-action="delete"]')))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="bulk-preview-row"]').getAttribute('data-state')).toBe('check')
    expect(dlg.querySelector('[data-slot="bulk-confirm"]')).not.toBeDisabled()
  })

  it('takıma taşı: hedef seçilmeden onay kapalı; hedef takımda aynı ad name_taken öngörülür', async () => {
    state.groups = [...state.groups, grp(20, 'bos', { team_id: 2 })]
    api.notificationGroups.bulk.mockResolvedValue({ success: true, data: { action: 'set_team', done: 0, skipped: 1, failed: 0, results: [
      { id: 11, name: 'Bos', team_id: 1, status: 'skipped', reason: 'name_taken' }] } })
    render(<NotificationGroups teams={TEAMS} />)
    await waitFor(() => expect(rowOf(11)).toBeTruthy())
    fireEvent.click(within(rowOf(11)).getByRole('checkbox'))
    fireEvent.click(await waitFor(() => document.querySelector('[data-slot="ng-bulk-bar"] [data-action="set_team"]')))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="bulk-confirm"]')).toBeDisabled()
    fireEvent.mouseDown(within(dlg).getByRole('combobox'))
    fireEvent.mouseDown((await screen.findAllByRole('option')).find((o) => o.textContent === 'SY-B'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-preview-row"]').getAttribute('data-reason')).toBe('name_taken'))
  })

  it('takıma taşı: açık alarma damgalı grup önizlemede atlanacak (sayısıyla), damga bilinmiyorsa "sunucu denetleyecek"', async () => {
    state.stamped = { 11: 2 }
    render(<NotificationGroups teams={TEAMS} />)
    await waitFor(() => expect(rowOf(11)).toBeTruthy())
    fireEvent.click(within(rowOf(11)).getByRole('checkbox'))
    fireEvent.click(await waitFor(() => document.querySelector('[data-slot="ng-bulk-bar"] [data-action="set_team"]')))
    const dlg = await screen.findByRole('dialog')
    fireEvent.mouseDown(within(dlg).getByRole('combobox'))
    fireEvent.mouseDown((await screen.findAllByRole('option')).find((o) => o.textContent === 'SY-B'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-preview-row"]').getAttribute('data-reason')).toBe('stamped_open_alerts'))
    expect(dlg.textContent).toMatch(/2 açık alarma|2 open alarms/)
  })

  it('tümünü seç en çok 200 grup seçer ve bunu söyler', async () => {
    state.groups = Array.from({ length: 205 }, (_, i) => grp(1000 + i, `Grup ${i}`))
    state.counts = {}
    render(<NotificationGroups teams={TEAMS} />)
    await waitFor(() => expect(document.querySelector('[data-slot="ng-select-all"]')).toBeTruthy())
    fireEvent.click(document.querySelector('[data-slot="ng-select-all"]'))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="ng-bulk-bar"]'); expect(b).toBeTruthy(); return b })
    expect(bar.textContent).toMatch(/200/)
    expect(bar.querySelector('[data-slot="bulk-cap-note"]').textContent).toMatch(/200/)
  })
})
