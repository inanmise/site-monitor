import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: { getMe: vi.fn(), dataQuality: { team: vi.fn() } },
}))
const perms = { dq: true }
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: (r) => (r === 'data_quality.view' ? perms.dq : false), loaded: true, scope: null, perms: {} }),
}))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), applyTabView: vi.fn() }))

import { api } from '../api/client'
import GapsPrompt from '../components/gaps/GapsPrompt.jsx'
import { openGapsDialog } from '../components/gaps/gapsBus.js'
import { GAPS_STATE_KEY, SNOOZE_MS } from '../components/gaps/gapsModel.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

const ME = { username: 'ayse', email: null, phone: '05551112233', team_ids: [4], manage_team_ids: [] }
const DQ = {
  team: { id: 4, name: 'Dijital Kanallar' },
  rules: [
    { code: 'TEAM_NO_ESCALATION', scope: 'TEAM', severity: 'HIGH', failing: 1, items: [{ kind: 'team', id: 4, name: 'Dijital Kanallar', can_edit: true }] },
    { code: 'MON_NO_GROUP', scope: 'MONITOR', severity: 'LOW', failing: 2,
      items: [{ kind: 'monitor', type: 'HTTP', id: 11, name: 'İnternet Şubesi Giriş', can_edit: true },
        { kind: 'monitor', type: 'PING', id: 12, name: 'Çağrı Merkezi', can_edit: false }] },
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  perms.dq = true
  try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.getMe.mockResolvedValue(ME)
  api.dataQuality.team.mockResolvedValue({ success: true, data: DQ })
})
afterEach(() => { vi.useRealTimers() })

async function mountAuto(props = {}) {
  render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} {...props} />)
}

describe('eksikler penceresi — kendiliğinden gösterme ve menü', () => {
  it('girişten sonra eksik varsa açılır: kategoriler, sayılar, profil e-postası, takım ve izleme eksikleri; istekler süre sınırlı', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')).not.toBeNull(), { timeout: 4000 })
    await waitFor(() => expect(slot('gaps-dialog').getAttribute('data-status')).toBe('ready'))
    expect(api.getMe).toHaveBeenCalledWith({ timeoutMs: 20000 })
    expect(api.dataQuality.team).toHaveBeenCalledWith(4, false, { timeoutMs: 20000 })
    const counts = Object.fromEntries(slots('gaps-tab').map((t) => [t.getAttribute('data-category'), t.getAttribute('data-count')]))
    expect(counts).toEqual({ profile: '1', team: '1', monitors: '1', inventory: '0' })
    expect(slot('gaps-panel').textContent).toMatch(/E-posta adresiniz yok/)
    expect(slot('gaps-panel').textContent).not.toMatch(/Telefon numaranız yok/)
    expect(document.body.textContent).not.toMatch(/gaps\.|dq\.rule\.|undefined/)
  })

  it('"Düzelt" ilgili sayfaya götürür ve pencereyi kapatır; düzenleme yetkisi yoksa "Görüntüle"', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    fireEvent.mouseDown(screen.getByRole('tab', { name: /İzlemeler/ }), { button: 0 })
    const items = slots('gaps-subitem')
    expect(items.map((i) => i.querySelector('[data-slot="gaps-fix"]')?.textContent)).toEqual(['Düzelt', 'Görüntüle'])
    fireEvent.click(items[0].querySelector('[data-slot="gaps-fix"]'))
    expect(nav.mock.calls[0][0]).toBe('http')
    expect(String(nav.mock.calls[0][1]?.monitor)).toBe('11')
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
  })

  it('"Daha sonra hatırlat" 1 gün saklar; "Bir daha gösterme" anahtar özetlerini saklar; aynı oturumda yeniden açılmaz', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    const before = Date.now()
    fireEvent.click(slot('gaps-snooze'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    const s1 = JSON.parse(localStorage.getItem(GAPS_STATE_KEY))
    expect(s1.snoozeUntil).toBeGreaterThanOrEqual(before + SNOOZE_MS)
    // menüden her zaman açılır
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    const s2 = JSON.parse(localStorage.getItem(GAPS_STATE_KEY))
    expect(s2.snoozeUntil).toBeNull()
    expect(s2.dismissed.length).toBeGreaterThanOrEqual(3)
    expect(JSON.stringify(s2)).not.toMatch(/İnternet|MON_NO_GROUP/)
  })

  it('"gösterme" dendiyse ve eksikler aynıysa sonraki girişte kendiliğinden açılmaz', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    // yeni giriş (oturum sıfırlandı), eksikler aynı → açılmaz
    act(() => emitSessionReset())
    api.getMe.mockClear()
    await mountAuto()
    await waitFor(() => expect(api.getMe).toHaveBeenCalled(), { timeout: 4000 })
    await new Promise((r) => setTimeout(r, 100))
    expect(slot('gaps-dialog')).toBeNull()
  })

  it('kişisel tercihler okunana dek kendiliğinden açılmaz (başka cihazdaki karar beklenir); okununca açılır', async () => {
    const { rerender } = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} prefsSettled={false} />)
    await new Promise((r) => setTimeout(r, 1800))
    expect(api.getMe).not.toHaveBeenCalled()
    expect(slot('gaps-dialog')).toBeNull()
    rerender(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} prefsSettled />)
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
  })

  it('eksik yoksa açılmaz; menüden açılınca "tamamlanması gereken bir şey yok"', async () => {
    api.getMe.mockResolvedValue({ ...ME, email: 'ayse@kurum.com.tr' })
    api.dataQuality.team.mockResolvedValue({ success: true, data: { team: { id: 4, name: 'X' }, rules: [] } })
    await mountAuto()
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalled(), { timeout: 4000 })
    await new Promise((r) => setTimeout(r, 50))
    expect(slot('gaps-dialog')).toBeNull()
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-none')).not.toBeNull())
  })

  it('okunamayan takım ve profil: "okunamadı" (eksik yok denmez); veri kalitesi yetkisi yoksa yalnız profil', async () => {
    api.getMe.mockRejectedValue(new Error('ağ'))
    api.dataQuality.team.mockResolvedValue({ success: false, status: 500 })
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    expect(slot('gaps-failed').textContent).toMatch(/Okunamayan takım sayısı: 1/)
    expect(slot('gaps-none')).toBeNull()
    expect(slot('gaps-profile-failed')).not.toBeNull()
    fireEvent.click(slot('gaps-close'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())

    perms.dq = false
    api.getMe.mockResolvedValue(ME)
    act(() => emitSessionReset())
    api.dataQuality.team.mockClear()
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    expect(api.dataQuality.team).not.toHaveBeenCalled()
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Takım/ }), { button: 0 })
    expect(slot('gaps-no-permission')).not.toBeNull()
  })
})
