import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, waitFor } from './test-utils.jsx'

/**
 * Denetleyici (GapsPromptCore) henüz inmemişken gelen menü / Panom isteği, StrictMode'un bağla → sök → yeniden bağla
 * döngüsünde KAYBOLMAMALI (e2e 2026-11-17: `npm run dev` StrictMode'la çalışır; söküm başlayan okumayı iptal ediyor, yeniden
 * bağlanmada istek tüketilmiş olduğundan pencere "yükleniyor"da, Panom kutusu çizilmeden kalıyordu).
 * Kök StrictMode: `render(ui, { reactStrictMode: true })` (iç içe StrictMode React 19'da etkileri iki kez koşmaz).
 */
const gate = vi.hoisted(() => ({ release: null }))
vi.mock('../utils/importWithDeadline.js', () => ({
  // Denetleyici modülü testin istediği ana dek inmez (kabuk bu sürede istekleri pendingRef'e yazar)
  importWithDeadline: vi.fn((loader) => new Promise((resolve, reject) => {
    gate.release = () => loader().then(resolve, reject)
  })),
}))
vi.mock('../api/client', () => ({
  api: {
    getMe: vi.fn(), getLoginMethods: vi.fn(), me: { getPushPreferences: vi.fn() }, dataQuality: { team: vi.fn() },
    notificationGroups: { list: vi.fn() }, oncall: { get: vi.fn() }, alertForwards: { list: vi.fn() },
    techErrors: { overview: vi.fn() }, incidents: { actions: vi.fn() }, teams: { directory: vi.fn() },
  },
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  // Yetkiler henüz okunmadı → kabuk kendiliğinden yükleme istemez; yalnız menü / Panom isteği denetleyiciyi indirir
  usePermissions: () => ({ canView: () => true, canEdit: () => false, loaded: false, scope: null, perms: {} }),
}))
vi.mock('../components/tour/TourProvider.jsx', () => ({ useTour: () => null }))

import { api } from '../api/client'
import GapsPrompt, { resetGapsCoreForTests } from '../components/gaps/GapsPrompt.jsx'
import { getGapsSummary, openGapsDialog, publishGapsSummary, requestGapsRefresh } from '../components/gaps/gapsBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

const ME = { username: 'ayse', user_id: 'u-1', team_id: 4, email: 'ayse@example.com', phone: '05551112233', team_ids: [4],
  team_names: ['Dijital Kanallar'], manage_team_ids: [], noc_operator: false }

beforeEach(() => {
  vi.clearAllMocks()
  gate.release = null
  resetGapsCoreForTests()            // her test denetleyici "henüz inmemiş" hâlden başlar
  publishGapsSummary(null)
  try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.getMe.mockResolvedValue(ME)
  api.getLoginMethods.mockResolvedValue({ success: true, otp_push: false, otp_email: false })
  api.me.getPushPreferences.mockResolvedValue({ success: true, opt_out: false, min_level: 'WARNING' })
  api.dataQuality.team.mockResolvedValue({ success: true, data: { team: { id: 4, name: 'Dijital Kanallar', score: 100 }, rules: [] } })
  api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [] } })
  api.oncall.get.mockResolvedValue({ success: true, data: { enabled: false, members: [] } })
  api.alertForwards.list.mockResolvedValue({ success: true, data: { items: [] } })
  api.techErrors.overview.mockResolvedValue({ success: true, data: { teams: [], channels: [] } })
  api.incidents.actions.mockResolvedValue({ success: true, data: { items: [], total: 0 } })
  api.teams.directory.mockResolvedValue({ success: true, data: [] })
})

describe('eksikler denetleyicisi inmeden gelen istek — StrictMode', () => {
  it('menü "Eksiklerim": pencere açılır ve okuma tamamlanır ("yükleniyor"da kalmaz)', async () => {
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />, { reactStrictMode: true })
    act(() => openGapsDialog())
    await waitFor(() => expect(gate.release).toBeTypeOf('function'))
    await act(async () => { gate.release() })
    await waitFor(() => expect(document.querySelector('[data-slot="gaps-dialog"]')?.getAttribute('data-status')).toBe('ready'),
      { timeout: 4000 })
  })

  it('Panom "Denetle": pencere açılmadan okunur ve özet "ready" yayınlanır', async () => {
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />, { reactStrictMode: true })
    act(() => requestGapsRefresh())
    await waitFor(() => expect(gate.release).toBeTypeOf('function'))
    await act(async () => { gate.release() })
    await waitFor(() => expect(getGapsSummary()?.status).toBe('ready'), { timeout: 4000 })
    expect(document.querySelector('[data-slot="gaps-dialog"]')).toBeNull()
  })
})
