import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor, within, fireEvent } from './test-utils.jsx'

/**
 * Bağımsız izleme alarmında eylem yalnız SAHİBİ takımın (2026-10-09, AlertOwnership). Alarm Geçmişi satırı sunucudan
 * `act_scope` taşır (yalnız global olmayan görüntüleyicide): `false` → Sahiplen / Çöz / Tekrar bildir ve toplu seçim
 * çizilmez, yerine neden notu ('owner'; 7/24 operatöründe 'team' — arama kaydı kalır). Alan yoksa (global görüntüleyici,
 * eski sunucu) davranış değişmez. Sunucu bayrağı istemci tahmininin (`outsideActScope`) önüne geçer.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(),
      getAlert: vi.fn(),
      getTeams: vi.fn(),
      getAlertNotifications: vi.fn(),
      getAlertPushDeliveries: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
    nocCalls: { list: vi.fn(), create: vi.fn(), remove: vi.fn(), contacts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { actBlockReason } from '../components/admin/alerts/alertHistoryModel.js'
import { toIso } from '../components/admin/alerts/nocCallModel.js'

const ago = (m) => toIso(Date.now() - m * 60_000)
/** Benim takımımın (1) bağımsız Ping alarmı. */
const MINE = {
  id: 70, domain: 'api.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: ago(30), team_id: 1, message: 'Ping yanıt vermiyor', act_scope: true,
}
/** B takımının (9) bağımsız Ping alarmı — host'un sertifikası benim takımımın envanterinde (sy_team_id = 1). */
const FOREIGN_STANDALONE = { ...MINE, id: 71, team_id: 9, sy_team_id: 1, act_scope: false }

const setUrl = (qs) => window.history.replaceState({}, '', `/?${qs}`)
const card = (id) => document.querySelector(`[data-alert-card][data-alert-id="${id}"]`)
const actions = (id) => card(id).querySelector('[data-alert-actions]')

function stubList({ canWrite = false, data }) {
  api.admin.getAlerts.mockImplementation(async (p) => {
    if (p?.size === 1) return { success: true, data: [], total: 0, level_counts: {}, noc_can_write: canWrite }
    return { success: true, data, total: data.length, page: 0, size: 20, noc_can_write: canWrite }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setUrl('tab=alerthistory')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.contacts.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => setUrl('tab=alerthistory'))

describe('actBlockReason — sunucunun act_scope bayrağı', () => {
  it('act_scope=false → takım üyesine "owner", 7/24 operatörüne "team" (arama kaydı kalır); izin yoksa izin nedeni önce gelir', () => {
    expect(actBlockReason(FOREIGN_STANDALONE, { myTeamIds: [1] })).toBe('owner')
    expect(actBlockReason(FOREIGN_STANDALONE, { nocCanWrite: true, myTeamIds: [1] })).toBe('team')
    expect(actBlockReason(FOREIGN_STANDALONE, { canAct: false })).toBe('perm')
  })

  it('act_scope=true → açık (istemci tahmini "başka takım" dese bile sunucu kazanır); alan yoksa eski davranış', () => {
    expect(actBlockReason({ ...MINE, team_id: 9 }, { nocCanWrite: true, myTeamIds: [1] })).toBeNull()
    const legacy = { ...FOREIGN_STANDALONE }
    delete legacy.act_scope
    expect(actBlockReason(legacy, { myTeamIds: [1] })).toBeNull()                       // operatör değil: kısıt yok
    expect(actBlockReason(legacy, { nocCanWrite: true, myTeamIds: [1] })).toBeNull()    // sy_team_id benim → tahmin "içeride"
    expect(actBlockReason({ ...legacy, sy_team_id: null }, { nocCanWrite: true, myTeamIds: [1] })).toBe('team')
  })
})

describe('Alarm Geçmişi — başka takımın bağımsız alarmı (host benim envanterimde)', () => {
  it('takım üyesi: Sahiplen/Çöz/Tekrar bildir ve seçim kutusu YOK, "owner" notu var; kendi alarmında eylemler durur — kart ve detay', async () => {
    stubList({ data: [MINE, FOREIGN_STANDALONE] })
    render(<AlertHistory urlSync myTeamIds={[1]} />)
    await waitFor(() => expect(card(71)).not.toBeNull())

    expect(within(actions(70)).getByRole('button', { name: /Resolve/ })).toBeInTheDocument()
    expect(within(actions(71)).queryByRole('button', { name: /Resolve|Acknowledge|Re-Notify/ })).toBeNull()
    expect(within(card(71)).queryByRole('checkbox')).toBeNull()
    const note = card(71).querySelector('[data-slot="alert-act-blocked"]')
    expect(note.getAttribute('data-reason')).toBe('owner')
    expect(note.textContent).toMatch(/standalone monitor|bağımsız izlemesine/)
    expect(note.textContent).not.toMatch(/log calls|arama kaydı/)

    fireEvent.click(card(71).querySelector('[data-alert-open]'))
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="alert-detail"][data-alert-id="71"]')
      expect(el).not.toBeNull()
      return el
    })
    const acts = detail.querySelector('[data-slot="alert-detail-actions"]')
    expect(within(acts).queryByRole('button', { name: /^(Resolve|Acknowledge|Re-Notify)$/ })).toBeNull()
    expect(acts.querySelector('[data-slot="alert-act-blocked"]').getAttribute('data-reason')).toBe('owner')
  })

  it('7/24 operatörü: sunucu act_scope=false → eylem yok, "Log a call" KALIR, not "team"', async () => {
    stubList({ canWrite: true, data: [MINE, FOREIGN_STANDALONE] })
    render(<AlertHistory urlSync myTeamIds={[1]} />)
    await waitFor(() => expect(card(71)).not.toBeNull())
    expect(within(actions(71)).queryByRole('button', { name: /Resolve|Acknowledge|Re-Notify/ })).toBeNull()
    expect(within(actions(71)).getByRole('button', { name: /Log a call/ })).toBeInTheDocument()
    expect(card(71).querySelector('[data-slot="alert-act-blocked"]').getAttribute('data-reason')).toBe('team')
  })
})
