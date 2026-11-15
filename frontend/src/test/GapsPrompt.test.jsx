import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: {
    getMe: vi.fn(), getLoginMethods: vi.fn(), me: { getPushPreferences: vi.fn() }, dataQuality: { team: vi.fn() },
    notificationGroups: { list: vi.fn() }, oncall: { get: vi.fn() }, alertForwards: { list: vi.fn() },
    techErrors: { overview: vi.fn() }, incidents: { actions: vi.fn() }, teams: { directory: vi.fn() },
  },
}))
const VIEW_ALL = ['data_quality.view', 'notification.groups', 'oncall.read', 'alerts.forwards', 'tech_error_reports.read', 'incidents.view']
const perms = { loaded: true, view: new Set(VIEW_ALL), edit: new Set() }
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: (r) => perms.view.has(r), canEdit: (r) => perms.edit.has(r), loaded: perms.loaded, scope: null, perms: {} }),
}))
const tourVal = { current: null }
vi.mock('../components/tour/TourProvider.jsx', () => ({ useTour: () => tourVal.current }))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), applyTabView: vi.fn() }))
// Pencereden düzeltme bileşeni (gerçeği toplu düzeltme pencerelerini yükler — burada yalnız akış denetlenir)
vi.mock('../components/gaps/GapsFix.jsx', async () => {
  const React = await import('react')
  return {
    default: ({ fix, onClose, onChanged }) => React.createElement('div', { 'data-slot': 'test-fix', 'data-kind': fix.kind, 'data-team': fix.team.id },
      React.createElement('button', { type: 'button', onClick: () => { onChanged(); onClose() } }, 'bitti')),
  }
})

import { api } from '../api/client'
import GapsPrompt from '../components/gaps/GapsPrompt.jsx'
import { getGapsSummary, openGapsDialog, publishGapsSummary, requestGapsRefresh } from '../components/gaps/gapsBus.js'
import {
  AUTO_DEFER_MAX_MS, AUTO_DEFER_POLL_MS, AUTO_PROMPT_DELAY_MS, AUTO_QUIET_MS, GAPS_SESSION_KEY, GAPS_STATE_KEY, SNOOZE_MS,
  SNOOZE_WEEK_MS, hashKey, readState,
} from '../components/gaps/gapsModel.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const item = (code) => document.querySelector(`[data-slot="gaps-item"][data-code="${code}"]`)
const err = (status) => Object.defineProperty({ success: false, error: 'x' }, 'errorInfo', { value: { status } })

const ME = { username: 'ayse', user_id: 'u-1', team_id: 4, email: null, phone: '05551112233', team_ids: [4], team_names: ['Dijital Kanallar'], manage_team_ids: [], noc_operator: false }
const DQ = {
  team: { id: 4, name: 'Dijital Kanallar', score: 72, band: 'FAIR', delta_7d: -3 },
  rules: [
    { code: 'TEAM_NO_ESCALATION', scope: 'TEAM', severity: 'HIGH', failing: 1, points: 8.5, items: [{ kind: 'team', id: 4, name: 'Dijital Kanallar', can_edit: true }] },
    { code: 'MON_NO_GROUP', scope: 'MONITOR', severity: 'LOW', failing: 2, points: 1.2,
      items: [{ kind: 'monitor', type: 'HTTP', id: 11, name: 'İnternet Şubesi Giriş', can_edit: true },
        { kind: 'monitor', type: 'PING', id: 12, name: 'Çağrı Merkezi', can_edit: false }] },
  ],
}

function setApi() {
  api.getMe.mockResolvedValue(ME)
  api.getLoginMethods.mockResolvedValue({ success: true, otp_push: false, otp_email: false })
  api.me.getPushPreferences.mockResolvedValue({ success: true, opt_out: false, min_level: 'WARNING' })
  api.dataQuality.team.mockResolvedValue({ success: true, data: DQ })
  api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [] } })
  api.oncall.get.mockResolvedValue({ success: true, data: { enabled: false, members: [] } })
  api.alertForwards.list.mockResolvedValue({ success: true, data: { items: [] } })
  api.techErrors.overview.mockResolvedValue({ success: true, data: { teams: [], channels: [] } })
  api.incidents.actions.mockResolvedValue({ success: true, data: { items: [], total: 0 } })
  api.teams.directory.mockResolvedValue({ success: true, data: [] })
}

beforeEach(() => {
  vi.clearAllMocks()
  perms.loaded = true
  perms.view = new Set(VIEW_ALL)
  perms.edit = new Set()
  tourVal.current = null
  document.querySelectorAll('[data-test-dialog]').forEach((n) => n.remove())
  try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  publishGapsSummary(null)
  setApi()
})
afterEach(() => { vi.useRealTimers() })

async function mountAuto(props = {}) {
  render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} {...props} />)
}
async function openReady(props = {}) {
  render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} {...props} />)
  act(() => openGapsDialog())
  await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
}
const tab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })

describe('eksikler penceresi — kendiliğinden gösterme ve menü', () => {
  it('girişten sonra eksik varsa açılır: beş kategori, sayılar, profil e-postası, takım ve izleme eksikleri; istekler süre sınırlı', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')).not.toBeNull(), { timeout: 4000 })
    await waitFor(() => expect(slot('gaps-dialog').getAttribute('data-status')).toBe('ready'))
    expect(api.getMe).toHaveBeenCalledWith({ timeoutMs: 20000 })
    expect(api.dataQuality.team).toHaveBeenCalledWith(4, false, { timeoutMs: 20000 })
    const counts = Object.fromEntries(slots('gaps-tab').map((t) => [t.getAttribute('data-category'), t.getAttribute('data-count')]))
    expect(counts).toEqual({ profile: '1', team: '1', monitors: '1', inventory: '0', tasks: '0' })
    expect(slot('gaps-panel').textContent).toMatch(/E-posta adresiniz yok/)
    expect(slot('gaps-panel').textContent).not.toMatch(/Telefon numaranız yok/)
    expect(slot('gaps-total').getAttribute('data-total')).toBe('3')
    expect(slot('gaps-high')).not.toBeNull()
    expect(document.body.textContent).not.toMatch(/gaps\.|dq\.rule\.|undefined|NaN/)
  })

  it('"Düzelt" ilgili sayfaya götürür ve pencereyi kapatır; düzenleme yetkisi yoksa "Görüntüle"', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    tab(/İzlemeler/)
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
    expect(s1.snoozeUntil).toBeLessThan(before + SNOOZE_WEEK_MS)
    // menüden her zaman açılır
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    const s2 = JSON.parse(localStorage.getItem(GAPS_STATE_KEY))
    expect(s2.snoozeUntil).toBeNull()
    expect(s2.dismissed.length).toBeGreaterThanOrEqual(3)
    expect(JSON.stringify(s2)).not.toMatch(/İnternet|MON_NO_GROUP|Dijital|ayse/)
    expect(s2.v).toBe(2)
  })

  it('"Bir hafta sonra hatırlat" menüsü 7 gün erteler', async () => {
    await openReady()
    pressMenuTrigger(slot('gaps-snooze-more'))
    const week = await waitFor(() => { const n = slot('gaps-snooze-week'); expect(n).not.toBeNull(); return n })
    const before = Date.now()
    fireEvent.click(week)
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    const s = JSON.parse(localStorage.getItem(GAPS_STATE_KEY))
    expect(s.snoozeUntil).toBeGreaterThanOrEqual(before + SNOOZE_WEEK_MS)
  })

  it('"gösterme" dendiyse ve eksikler aynıysa sonraki girişte kendiliğinden açılmaz', async () => {
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    act(() => emitSessionReset())
    localStorage.removeItem(GAPS_SESSION_KEY)
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

  it('ertelendiyse kendiliğinden yol AĞA ÇIKMAZ; "Kapat" sonrası yeni sekme de okumaz', async () => {
    localStorage.setItem(GAPS_STATE_KEY, JSON.stringify({ v: 2, snoozeUntil: Date.now() + 60_000, dismissed: [] }))
    const r1 = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((r) => setTimeout(r, 1800))
    expect(api.getMe).not.toHaveBeenCalled()
    expect(api.getLoginMethods).not.toHaveBeenCalled()
    r1.unmount()

    localStorage.removeItem(GAPS_STATE_KEY)
    sessionStorage.clear()
    const r2 = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'), { timeout: 4000 })
    fireEvent.click(slot('gaps-close'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    expect(JSON.parse(localStorage.getItem(GAPS_SESSION_KEY)).closedAt).toBeGreaterThan(0)
    r2.unmount()
    sessionStorage.clear()
    api.getMe.mockClear()
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((r) => setTimeout(r, 1800))
    expect(api.getMe).not.toHaveBeenCalled()
  })

  it('pasif / görünmeyen takım (404) "okunamadı" değil, atlanır; tamamlanacak bir şey yoksa yeşil bant', async () => {
    api.getMe.mockResolvedValue({ ...ME, email: 'ayse@kurum.com.tr', team_ids: [4, 9], team_names: ['X', 'Y'] })
    api.dataQuality.team.mockImplementation(async (id) => (id === 9 ? err(404) : { success: true, data: { team: { id: 4, name: 'X' }, rules: [] } }))
    api.oncall.get.mockImplementation(async (id) => (id === 9 ? err(404) : { success: true, data: { enabled: false, members: [] } }))
    await openReady({ myTeamIds: [4, 9] })
    expect(slot('gaps-failed')).toBeNull()
    expect(slot('gaps-failed-checks')).toBeNull()
    expect(slot('gaps-none')).not.toBeNull()
  })

  it('yetkiler okunmadan kendiliğinden açılmaz; menüden açılınca "yetkileriniz okunamadı" (yetkiniz yok denmez), hiçbir denetim istenmez', async () => {
    perms.loaded = false
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((r) => setTimeout(r, 1800))
    expect(api.getMe).not.toHaveBeenCalled()
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    for (const fn of [api.dataQuality.team, api.oncall.get, api.notificationGroups.list, api.alertForwards.list, api.techErrors.overview, api.incidents.actions]) {
      expect(fn).not.toHaveBeenCalled()
    }
    tab(/Takım/)
    expect(slot('gaps-perm-unknown')).not.toBeNull()
    expect(slot('gaps-no-permission')).toBeNull()
    expect(slot('gaps-none')).toBeNull()
  })

  it('başka bir pencere açıkken ya da karşılama turu açılacakken kendiliğinden açılmaz', async () => {
    const other = document.createElement('div')
    other.setAttribute('role', 'dialog')
    other.setAttribute('aria-modal', 'true')
    other.setAttribute('data-test-dialog', '')
    document.body.appendChild(other)
    const r1 = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalled(), { timeout: 4000 })
    await new Promise((r) => setTimeout(r, 100))
    expect(slot('gaps-dialog')).toBeNull()
    r1.unmount()
    other.remove()

    sessionStorage.clear()
    localStorage.removeItem(GAPS_SESSION_KEY)
    api.getMe.mockClear()
    tourVal.current = { welcome: null, active: null, tourState: null }
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
    await new Promise((r) => setTimeout(r, 1800))
    expect(api.getMe).not.toHaveBeenCalled()
    expect(slot('gaps-dialog')).toBeNull()
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
    await openReady()
    expect(slot('gaps-failed').textContent).toMatch(/Okunamayan takım sayısı: 1/)
    expect(slot('gaps-none')).toBeNull()
    expect(slot('gaps-profile-failed')).not.toBeNull()
    fireEvent.click(slot('gaps-close'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())

    perms.view.delete('data_quality.view')
    api.getMe.mockResolvedValue(ME)
    act(() => emitSessionReset())
    api.dataQuality.team.mockClear()
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    expect(api.dataQuality.team).not.toHaveBeenCalled()
    tab(/Takım/)
    expect(slot('gaps-no-permission')).not.toBeNull()
  })
})

// Kendiliğinden açılış kullanıcının işini bölmez (2026-11-15, CI hatası: pencere açık "İşlemler" menüsünün üstüne açılıp
// odağı alıyor, kipsiz menü kapanıyordu — "Sürdür" seçilemeden menü kayboluyordu).
describe('eksikler penceresi — kullanıcının işini bölmez', () => {
  /** Açık bir Radix açılır menüsü (yalnız açıkken çizilen popper sarmalayıcısı). */
  function openPopup() {
    const el = document.createElement('div')
    el.setAttribute('data-radix-popper-content-wrapper', '')
    el.setAttribute('data-test-dialog', '')
    el.innerHTML = '<div role="menu"><div role="menuitem">Sürdür</div></div>'
    document.body.appendChild(el)
    return el
  }

  it('açık menü varken açılmaz (menü yerinde kalır); menü kapanınca açılır', async () => {
    const popup = openPopup()
    await mountAuto()
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalled(), { timeout: 4000 })
    await new Promise((r) => setTimeout(r, AUTO_DEFER_POLL_MS * 3))
    expect(slot('gaps-dialog')).toBeNull()
    expect(popup.isConnected).toBe(true)
    popup.remove()
    await waitFor(() => expect(slot('gaps-dialog')).not.toBeNull(), { timeout: 3000 })
  })

  it('odaktaki tetiğin ipucu (tooltip) bekletmez: menü kapanınca açık kalan ipucu pencereyi süresiz bekletmesin', async () => {
    const tip = document.createElement('div')
    tip.setAttribute('data-radix-popper-content-wrapper', '')
    tip.setAttribute('data-test-dialog', '')
    tip.innerHTML = '<div data-slot="tooltip-content">Kullanıcı ayarları<span role="tooltip">Kullanıcı ayarları</span></div>'
    document.body.appendChild(tip)
    await mountAuto()
    await waitFor(() => expect(slot('gaps-dialog')).not.toBeNull(), { timeout: 4000 })
    expect(tip.isConnected).toBe(true)
  })

  it('kullanıcı yazıyor / tıklıyorken açılmaz; en az AUTO_QUIET_MS sessizlikten sonra açılır', async () => {
    await mountAuto()
    const iv = setInterval(() => { fireEvent.keyDown(document.body, { key: 'a' }); fireEvent.pointerDown(document.body) }, 250)
    try {
      await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalled(), { timeout: 4000 })
      await new Promise((r) => setTimeout(r, 1200))
      expect(slot('gaps-dialog')).toBeNull()
    } finally {
      clearInterval(iv)
    }
    const stoppedAt = performance.now()
    await waitFor(() => expect(slot('gaps-dialog')).not.toBeNull(), { timeout: 4000 })
    expect(performance.now() - stoppedAt).toBeGreaterThanOrEqual(AUTO_QUIET_MS - 300)
  })

  it('kesintisiz çalışan kullanıcıda bekleme sınırlı: AUTO_DEFER_MAX_MS dolunca bu oturumda açılmaz; menüden yine açılır', async () => {
    // Tembel pencere modülü önceden yüklensin: sahte saatte dinamik içe aktarma beklenmeden çizilebilsin (yoksa "açılmadı"
    // denetimi yalnız modül gelmediği için geçerdi)
    await import('../components/gaps/GapsDialog.jsx')
    const remove = vi.spyOn(window, 'removeEventListener')
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance'] })
    try {
      const popup = openPopup()
      await mountAuto()
      await act(async () => { await vi.advanceTimersByTimeAsync(AUTO_PROMPT_DELAY_MS + 50) })
      await act(async () => { await vi.advanceTimersByTimeAsync(200) })
      expect(api.dataQuality.team).toHaveBeenCalled()
      // sınırdan hemen önce: hâlâ bekliyor (dinleyiciler kurulu)
      await act(async () => { await vi.advanceTimersByTimeAsync(AUTO_DEFER_MAX_MS - AUTO_DEFER_POLL_MS * 2) })
      expect(remove.mock.calls.some(([t]) => t === 'pointerdown')).toBe(false)
      await act(async () => { await vi.advanceTimersByTimeAsync(AUTO_DEFER_POLL_MS * 4) })
      // sınır doldu: vazgeçildi, dinleyiciler kaldırıldı; menü sonradan kapansa da pencere kendiliğinden açılmaz
      expect(remove.mock.calls.some(([t, , o]) => t === 'pointerdown' && o?.capture === true)).toBe(true)
      popup.remove()
      await act(async () => { await vi.advanceTimersByTimeAsync(AUTO_QUIET_MS * 3) })
      expect(slot('gaps-dialog')).toBeNull()
    } finally {
      vi.useRealTimers()
      remove.mockRestore()
    }
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
  })

  it('bekleme sürerken çıkış / unmount: bekleyiş düşer, girdi dinleyicileri kaldırılır', async () => {
    const add = vi.spyOn(window, 'addEventListener')
    const remove = vi.spyOn(window, 'removeEventListener')
    try {
      const popup = openPopup()
      const r = render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} />)
      await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalled(), { timeout: 4000 })
      expect(add.mock.calls.some(([type]) => type === 'pointerdown')).toBe(true)
      act(() => emitSessionReset())
      popup.remove()
      await new Promise((r2) => setTimeout(r2, AUTO_DEFER_POLL_MS * 3))
      expect(slot('gaps-dialog')).toBeNull()
      for (const type of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
        expect(remove.mock.calls.some(([t, , o]) => t === type && o?.capture === true), type).toBe(true)
      }
      r.unmount()
    } finally {
      add.mockRestore()
      remove.mockRestore()
    }
  })
})

describe('eksikler penceresi — zenginleştirme (2026-11-15)', () => {
  it('veri kalitesi dışı takım eksikleri ve bekleyen işler listelenir; kalem bağlantıları ilgili sayfaya gider', async () => {
    api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [
      { id: 31, team_id: 4, name: 'Nöbet listesi', active: true, is_default: true, emails: ['a@x.com'], inactive_emails: ['a@x.com'] }] } })
    api.oncall.get.mockResolvedValue({ success: true, data: { enabled: true,
      members: [{ username: 'banu', name: 'Banu Kaya (Ağ / Uzman)', active: false, has_email: true }], current: { active: true, has_email: true } } })
    api.techErrors.overview.mockResolvedValue({ success: true, data: {
      teams: [{ id: 4, can_approve: true, approver_count: 2 }],
      channels: [{ id: 10, team_id: 4, name: 'Mobil Bankacılık', active: true, can_report: true, approval_required: true,
        period_report: { id: 20, status: 'draft', approval: { state: 'pending' } } }] } })
    api.incidents.actions.mockResolvedValue({ success: true, data: { total: 1, items: [{ id: 7, title: 'Yedek al', due_date: '2026-11-01', incident_title: 'Kesinti' }] } })
    await openReady()
    expect(api.incidents.actions).toHaveBeenCalledWith({ status: 'open', mine: true, overdue: true, size: 5 })
    const counts = Object.fromEntries(slots('gaps-tab').map((t) => [t.getAttribute('data-category'), t.getAttribute('data-count')]))
    expect(counts.team).toBe('3')
    expect(counts.tasks).toBe('3')
    tab(/Takım/)
    expect(item('TEAM_GROUP_ALL_PASSIVE').textContent).toMatch(/Nöbet listesi/)
    expect(item('TEAM_ONCALL_MEMBERS').textContent).toMatch(/Banu Kaya \(Ağ \/ Uzman\)/)
    tab(/Bekleyen işler/)
    expect(slots('gaps-item').map((n) => n.getAttribute('data-code'))).toEqual(['TASK_APPROVAL_PENDING', 'TASK_ACTIONS_OVERDUE', 'TASK_CHANNEL_REPORT'])
    fireEvent.click(item('TASK_APPROVAL_PENDING').querySelector('[data-slot="gaps-fix"]'))
    expect(nav).toHaveBeenLastCalledWith('channelreports', { te_view: 'report', te_ch: '10', te_rep: '20' })
    expect(document.body.textContent).not.toMatch(/gaps\.|undefined|NaN/)
  })

  it('bir denetim okunamazsa adıyla "okunamadı"; yetkisi olmayan denetim istenmez ve adıyla söylenir', async () => {
    perms.view.delete('alerts.forwards')
    api.oncall.get.mockResolvedValue(err(500))
    await openReady()
    expect(api.alertForwards.list).not.toHaveBeenCalled()
    expect(slot('gaps-failed-checks').textContent).toMatch(/Nöbet çizelgesi/)
    expect(slot('gaps-failed-checks').textContent).toMatch(/Dijital Kanallar/)
    expect(slot('gaps-denied').textContent).toMatch(/Alarm iletimi/)
    expect(slot('gaps-none')).toBeNull()
  })

  it('sunucunun bu sürümde yapamadığı denetim (405) "yetkiniz yok" denmez, ayrı söylenir; "eksik yok" yeşili çıkmaz', async () => {
    api.getMe.mockResolvedValue({ ...ME, email: 'ayse@kurum.com.tr' })
    api.dataQuality.team.mockResolvedValue({ success: true, data: { team: { id: 4, name: 'X' }, rules: [] } })
    api.techErrors.overview.mockResolvedValue(err(405))
    await openReady()
    expect(slot('gaps-unsupported').textContent).toMatch(/Kanal teknik hata raporları/)
    expect(slot('gaps-denied')).toBeNull()
    expect(slot('gaps-none')).toBeNull()
    expect(getGapsSummary().complete).toBe(false)
  })

  it('denetlenmeyen kategori / takım "eksik yok" demez: iş kaynağı okunamadıysa ve takımın nöbeti okunamadıysa ayrı not', async () => {
    api.dataQuality.team.mockResolvedValue({ success: true, data: { team: { id: 4, name: 'X' }, rules: [] } })
    api.incidents.actions.mockResolvedValue(err(500))
    api.oncall.get.mockResolvedValue(err(500))
    await openReady()
    tab(/Bekleyen işler/)
    expect(slot('gaps-tasks-unchecked')).not.toBeNull()
    expect(slot('gaps-empty', document.querySelector('[data-slot="gaps-panel"][data-category="tasks"]'))).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Takıma göre' }))
    const team = document.querySelector('[data-slot="gaps-team-group"][data-team="4"]')
    expect(slot('gaps-team-unchecked', team)).not.toBeNull()  // nöbet okunamadı → "eksik yok" denmez
    expect(slot('gaps-team-clean', team)).toBeNull()
  })

  it('ortak kaynak okunamadığı turda "bir daha gösterme" o kaynağın eski kayıtlarını SİLMEZ (sonraki girişte yeniden açılmaz)', async () => {
    api.notificationGroups.list.mockResolvedValue({ success: true, data: { groups: [
      { id: 31, team_id: 4, name: 'G', active: true, is_default: true, emails: ['a@x.com'], inactive_emails: ['a@x.com'] }] } })
    await openReady()
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    const before = JSON.parse(localStorage.getItem(GAPS_STATE_KEY)).dismissed.map((d) => d[0])
    expect(before).toContain(hashKey('4:TEAM_GROUP_ALL_PASSIVE'))
    // gruplar bu kez okunamıyor; yeniden "gösterme" → grup eksiğinin kaydı korunur
    api.notificationGroups.list.mockResolvedValue(err(500))
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    fireEvent.click(slot('gaps-dismiss'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())
    expect(JSON.parse(localStorage.getItem(GAPS_STATE_KEY)).dismissed.map((d) => d[0])).toContain(hashKey('4:TEAM_GROUP_ALL_PASSIVE'))
  })

  it('tek eksiği gizleme ve geri alma: gizlenen sayılmaz, listesi açılır, geri alınca yeniden görünür', async () => {
    await openReady()
    tab(/İzlemeler/)
    fireEvent.click(item('MON_NO_GROUP').querySelector('[data-slot="gaps-hide"]'))
    await waitFor(() => expect(item('MON_NO_GROUP')).toBeNull())
    expect(slot('gaps-total').getAttribute('data-total')).toBe('2')
    expect(readState(localStorage.getItem(GAPS_STATE_KEY)).hidden).toEqual([hashKey('4:MON_NO_GROUP')])
    expect(slot('gaps-hidden-toggle').textContent).toMatch(/Gizlenen eksikler \(1\)/)
    fireEvent.click(slot('gaps-hidden-toggle'))
    fireEvent.click(await waitFor(() => { const n = slot('gaps-unhide'); expect(n).not.toBeNull(); return n }))
    await waitFor(() => expect(slot('gaps-total').getAttribute('data-total')).toBe('3'))
    expect(slot('gaps-hidden')).toBeNull()
  })

  it('takıma göre görünüm: takım başlığında veri kalitesi puanı ve 7 günlük değişim; işler "Size ait" grubunda', async () => {
    api.incidents.actions.mockResolvedValue({ success: true, data: { total: 2, items: [{ id: 7, title: 'Yedek al' }] } })
    await openReady()
    fireEvent.click(screen.getByRole('button', { name: 'Takıma göre' }))
    expect(slot('gaps-team-view')).not.toBeNull()
    const groups = slots('gaps-team-group').map((g) => g.getAttribute('data-team'))
    expect(groups).toEqual(['profile', '4', 'mine'])
    const team = document.querySelector('[data-slot="gaps-team-group"][data-team="4"]')
    expect(slot('gaps-team-score', team).textContent).toMatch(/72/)
    expect(slot('gaps-team-delta', team).textContent).toMatch(/-3/)
    expect(slots('gaps-points', team).length).toBeGreaterThan(0)
  })

  it('"Yeni" rozeti ve "düzeldi" notu: ikinci okumada yeni eksik işaretlenir, giden sayılır', async () => {
    await openReady()
    expect(slot('gaps-new')).toBeNull()           // ilk okumada taban yok → "yeni" denmez
    api.dataQuality.team.mockResolvedValue({ success: true, data: { ...DQ, rules: [DQ.rules[1],
      { code: 'INV_NO_CONTACTS', scope: 'INVENTORY', severity: 'MEDIUM', failing: 1, items: [{ kind: 'inventory', id: 5, name: 'api.kurum.com.tr', can_edit: true }] }] } })
    fireEvent.click(slot('gaps-refresh'))
    await waitFor(() => expect(slot('gaps-new-count')).not.toBeNull())
    expect(slot('gaps-fixed').textContent).toMatch(/1/)
    tab(/Envanter/)
    expect(slot('gaps-new', item('INV_NO_CONTACTS'))).not.toBeNull()
  })

  it('profil eksiğinin önemi bağlamdan: nöbetçi + kodla girişte telefon zorunlu → telefon eksiği YÜKSEK ve nedenleriyle; lidere sorma notu', async () => {
    api.getMe.mockResolvedValue({ ...ME, email: 'ayse@kurum.com.tr', phone: '' })
    api.getLoginMethods.mockResolvedValue({ success: true, otp_push: true, push_requires_phone: true })
    api.oncall.get.mockResolvedValue({ success: true, data: { enabled: true, members: [{ username: 'AYSE', name: 'Ayşe', active: true, has_email: true }] } })
    api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 4, name: 'Dijital Kanallar', leader_id: 'u-9', leader_display_name: 'Mehmet Demir (Dijital / Lider)' }] })
    await openReady()
    const phone = item('PROFILE_NO_PHONE')
    expect(phone.querySelector('[data-severity]').getAttribute('data-severity')).toBe('HIGH')
    expect(slot('gaps-reasons', phone).textContent).toMatch(/Nöbet çizelgesindesiniz/)
    expect(slot('gaps-ask', phone).textContent).toMatch(/Mehmet Demir \(Dijital \/ Lider\)/)
  })

  it('10\'dan çok takım: ilk 10 denetlenir, sonrakiler düğmeyle (≤ 4 eşzamanlı istek) eklenir', async () => {
    const ids = [...Array(12)].map((_, i) => i + 1)
    api.getMe.mockResolvedValue({ ...ME, email: 'a@b.c', team_ids: ids, team_names: ids.map((i) => `Takım ${i}`) })
    let running = 0
    let peak = 0
    api.dataQuality.team.mockImplementation(async (id) => {
      running++; peak = Math.max(peak, running)
      await new Promise((r) => setTimeout(r, 5))
      running--
      return { success: true, data: { team: { id, name: `Takım ${id}` }, rules: [] } }
    })
    await openReady({ myTeamIds: ids })
    expect(api.dataQuality.team).toHaveBeenCalledTimes(10)
    expect(peak).toBeLessThanOrEqual(4)
    expect(slot('gaps-more-teams')).not.toBeNull()
    expect(slot('gaps-none')).toBeNull()
    fireEvent.click(slot('gaps-more-teams-load'))
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalledTimes(12))
    await waitFor(() => expect(slot('gaps-more-teams')).toBeNull())
    await waitFor(() => expect(slot('gaps-none')).not.toBeNull())
    // Yenileme elle eklenen takımları düşürmez: 10 + 2 takım yeniden denetlenir
    api.dataQuality.team.mockClear()
    fireEvent.click(slot('gaps-refresh'))
    await waitFor(() => expect(api.dataQuality.team).toHaveBeenCalledTimes(12))
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    expect(slot('gaps-more-teams')).toBeNull()
  })

  it('pencereden düzeltme yalnız takımı yöneten ve yetkili kişiye: düzeltme açılınca pencere gizlenir, bitince taze okumayla döner', async () => {
    await openReady()
    tab(/Takım/)
    expect(slot('gaps-inline-fix')).toBeNull()                // yönetmiyor
    fireEvent.click(slot('gaps-close'))
    await waitFor(() => expect(slot('gaps-dialog')).toBeNull())

    api.getMe.mockResolvedValue({ ...ME, manage_team_ids: [4] })
    perms.edit = new Set(['contacts.crud'])
    act(() => openGapsDialog())
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    tab(/Takım/)
    const btn = slot('gaps-inline-fix', item('TEAM_NO_ESCALATION'))
    expect(btn.getAttribute('data-kind')).toBe('contact')
    const calls = api.dataQuality.team.mock.calls.length
    fireEvent.click(btn)
    await waitFor(() => expect(slot('test-fix')).not.toBeNull())
    expect(slot('gaps-dialog')).toBeNull()
    expect(slot('test-fix').getAttribute('data-team')).toBe('4')
    fireEvent.click(screen.getByRole('button', { name: 'bitti' }))
    await waitFor(() => expect(slot('gaps-dialog')?.getAttribute('data-status')).toBe('ready'))
    expect(api.dataQuality.team.mock.calls.length).toBeGreaterThan(calls)
  })

  it('listeyi panoya kopyalar (ekrandaki eksikler, kişi / takım adı dışında veri yok)', async () => {
    const writeText = vi.fn(async () => {})
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await openReady()
    pressMenuTrigger(slot('gaps-export'))
    fireEvent.click(await waitFor(() => { const n = slot('gaps-export-copy'); expect(n).not.toBeNull(); return n }))
    await waitFor(() => expect(writeText).toHaveBeenCalled())
    const text = writeText.mock.calls[0][0]
    expect(text).toMatch(/E-posta adresiniz yok/)
    expect(text).toMatch(/İnternet Şubesi Giriş/)
    expect(text).not.toMatch(/gaps\.|undefined/)
    await waitFor(() => expect(document.body.textContent).toMatch(/panoya kopyalandı/))
  })

  it('özet yayınlanır (menü rozeti, Panom kutusu) ve oturum sayısı yazılır; Panom isteğinde pencere açılmadan okunur', async () => {
    render(<GapsPrompt username="ayse" myTeamIds={[4]} manageTeamIds={[]} enabled prefsSettled={false} />)
    act(() => requestGapsRefresh())
    await waitFor(() => expect(getGapsSummary()?.status).toBe('ready'))
    expect(slot('gaps-dialog')).toBeNull()
    const s = getGapsSummary()
    expect(s.total).toBe(3)
    expect(s.high).toBe(2)                     // e-posta eksiği + eskalasyon kişisi yok
    expect(s.byCategory).toEqual({ profile: 1, team: 1, monitors: 1, inventory: 0, tasks: 0 })
    expect(s.complete).toBe(false)
    expect(JSON.parse(localStorage.getItem(GAPS_SESSION_KEY)).count).toBe(3)
    act(() => emitSessionReset())
    expect(getGapsSummary()).toBeNull()
  })
})
