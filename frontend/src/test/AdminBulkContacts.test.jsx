import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Eskalasyon kişileri TOPLU işlem (2026-11-13, yönetim zenginleştirmesi (d)): seçim yalnız yönetilebilir satırlarda,
 * onay penceresi tahmini sonucu listeler, tek istek sunucuya gider, sonuç satır satır gösterilir; alan hatası alanın altında.
 */
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getContacts: vi.fn(), getUsers: vi.fn(), teamStats: vi.fn(), bulkContacts: vi.fn(),
    addContact: vi.fn(), updateContact: vi.fn(), deleteContact: vi.fn(),
  } }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))

import { api } from '../api/client'
import EscalationContacts from '../components/admin/EscalationContacts.jsx'

const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const TEAMS = [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }]
const CONTACTS = [
  { id: 1, user_id: 7, name: LONG, email: 'ayse@x', role: 'PO', min_alert_level: 'WARNING', active: true, team_id: 5 },
  { id: 2, user_id: 8, name: 'Ali Veli', email: 'ali@x', role: 'TECH', min_alert_level: 'HIGH', active: false, team_id: 5 },
  { id: 3, user_id: 9, name: 'Başka Takım', email: 'b@x', role: 'TECH', min_alert_level: 'WARNING', active: true, team_id: 6 },
]

const renderEc = (props = {}) => render(<LangProvider><EscalationContacts systemRole="ADMIN" teams={TEAMS} globalAdmin {...props} /></LangProvider>)
const rowOf = (id) => document.querySelector(`[data-contact-id="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getContacts.mockResolvedValue({ success: true, data: CONTACTS })
  api.admin.getUsers.mockResolvedValue({ success: true, data: [{ id: 7, active: true }, { id: 8, active: true }, { id: 9, active: true }] })
  api.admin.teamStats.mockResolvedValue({ success: true, data: {} })
})

describe('EscalationContacts — toplu işlem', () => {
  it('seç → pasifleştir önizlemesi (değişecek / zaten bu durumda) → tek istek → sonuç; kapatınca liste yenilenir', async () => {
    api.admin.bulkContacts.mockResolvedValue({ success: true, data: { action: 'deactivate', done: 1, skipped: 1, failed: 0, results: [
      { id: 1, name: LONG, team_id: 5, team_name: 'SY-A', status: 'done', reason: 'done' },
      { id: 2, name: 'Ali Veli', team_id: 5, team_name: 'SY-A', status: 'skipped', reason: 'unchanged' }] } })
    renderEc()
    await waitFor(() => expect(rowOf(1)).toBeTruthy())
    fireEvent.click(within(rowOf(1)).getByRole('checkbox'))
    fireEvent.click(within(rowOf(2)).getByRole('checkbox'))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="ec-bulk-bar"]'); expect(b).toBeTruthy(); return b })
    expect(bar.textContent).toMatch(/2/)
    fireEvent.click(bar.querySelector('[data-action="deactivate"]'))
    const dlg = await screen.findByRole('dialog')
    const preview = [...dlg.querySelectorAll('[data-slot="bulk-preview-row"]')]
    expect(preview.map((r) => r.getAttribute('data-state'))).toEqual(['change', 'skip'])
    expect(preview[1].getAttribute('data-reason')).toBe('unchanged')
    // uzun ad kesilmez (tam metin)
    expect(dlg.textContent).toContain(LONG)
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(api.admin.bulkContacts).toHaveBeenCalledWith({ action: 'deactivate', ids: [1, 2] }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-dialog"]').getAttribute('data-phase')).toBe('result'))
    expect(dlg.querySelectorAll('[data-slot="bulk-result-row"][data-status="done"]')).toHaveLength(1)
    expect(dlg.querySelector('[data-slot="bulk-result-row"][data-reason="unchanged"]')).toBeTruthy()
    fireEvent.click(dlg.querySelector('[data-slot="bulk-close"]'))
    await waitFor(() => expect(api.admin.getContacts).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('[data-slot="ec-bulk-bar"]')).toBeNull())
  })

  it('kapsamlı müdür: yönetmediği takımın kişisi seçilemez; takıma atama hedefleri yalnız yönettiği takımlar', async () => {
    renderEc({ globalAdmin: false, manageTeamIds: [5] })
    await waitFor(() => expect(rowOf(3)).toBeTruthy())
    expect(within(rowOf(3)).queryByRole('checkbox')).toBeNull()
    fireEvent.click(within(rowOf(1)).getByRole('checkbox'))
    const bar = await waitFor(() => { const b = document.querySelector('[data-slot="ec-bulk-bar"]'); expect(b).toBeTruthy(); return b })
    fireEvent.click(bar.querySelector('[data-action="set_team"]'))
    const dlg = await screen.findByRole('dialog')
    fireEvent.mouseDown(within(dlg).getByRole('combobox'))
    const options = await screen.findAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['SY-A'])
  })

  it('seviye ata: seviye seçilmeden onay kapalı; sunucu alan hatası seviyenin altında, sonuç evresine geçilmez', async () => {
    api.admin.bulkContacts.mockResolvedValue({ success: false, field: 'min_alert_level', error: 'Geçersiz alarm seviyesi' })
    renderEc()
    await waitFor(() => expect(rowOf(1)).toBeTruthy())
    fireEvent.click(within(rowOf(1)).getByRole('checkbox'))
    fireEvent.click((await waitFor(() => document.querySelector('[data-slot="ec-bulk-bar"] [data-action="set_level"]'))))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.querySelector('[data-slot="bulk-confirm"]')).toBeDisabled()
    fireEvent.mouseDown(within(dlg).getByRole('combobox'))
    fireEvent.mouseDown((await screen.findAllByRole('option')).find((o) => /KRİTİK|CRITICAL/i.test(o.textContent)))
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-confirm"]')).not.toBeDisabled())
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(api.admin.bulkContacts).toHaveBeenCalledWith({ action: 'set_level', ids: [1], min_alert_level: 'CRITICAL' }))
    await waitFor(() => expect(dlg.textContent).toContain('Geçersiz alarm seviyesi'))
    expect(dlg.querySelector('[data-field="min_alert_level"]')).toBeTruthy()
    expect(dlg.querySelector('[data-slot="bulk-dialog"]').getAttribute('data-phase')).toBe('preview')
  })

  it('istek düşerse pencere açık kalır, hata gösterilir ("0 işlendi" denmez)', async () => {
    api.admin.bulkContacts.mockRejectedValue(new Error('Sunucuya ulaşılamadı'))
    renderEc()
    await waitFor(() => expect(rowOf(1)).toBeTruthy())
    fireEvent.click(within(rowOf(1)).getByRole('checkbox'))
    fireEvent.click((await waitFor(() => document.querySelector('[data-slot="ec-bulk-bar"] [data-action="deactivate"]'))))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(dlg.querySelector('[data-slot="bulk-confirm"]'))
    await waitFor(() => expect(dlg.textContent).toContain('Sunucuya ulaşılamadı'))
    expect(dlg.querySelector('[data-slot="bulk-dialog"]').getAttribute('data-phase')).toBe('preview')
  })
})
