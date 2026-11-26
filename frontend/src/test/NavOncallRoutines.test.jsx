import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, act, screen } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'

/**
 * Nöbet Rutinleri menü girişi + bekleyen görev rozeti (2026-11-26).
 *
 * Kurallar: giriş yalnız sunucu kapsamı `oncall_routines === true` VE `oncall_routines.read` görme izni varken çizilir (eski
 * sunucu bayrak göndermez → gizli); gizliyken `/me` HİÇ istenmez. Rozet `/me` → `badge {count, level}`: sayı > 0 ise
 * çizilir, 0 / okunamadı / hata → rozet YOK (bilinmeyen asla 0 değildir). Yoklayıcı modül düzeyinde TEK: aynı anda iki tüketici
 * tek istek yapar; sayfa işaretleyince `refreshRoutineBadge` taze okur; geç gelen ESKİ yanıt yenisini ezmez; oturum
 * sıfırlanınca rozet silinir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    oncallRoutines: { me: vi.fn() },
    me: { openAlerts: vi.fn().mockResolvedValue({ success: true, data: { visible: false } }), inbox: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
}))

const permState = vi.hoisted(() => ({ scope: null, perms: {} }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    scope: permState.scope, perms: permState.perms, canView: (r) => !!permState.perms[r]?.view,
    canEdit: () => false, canExecute: () => false, loaded: true, failed: false, refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import Nav from '../components/Nav.jsx'
import { itemBadge, sectionItemBadge } from '../components/nav/NavMain.jsx'
import {
  badgeOf, fetchRoutineMe, refreshRoutineBadge, resetRoutineBadgeForTests, useRoutineBadge,
} from '../components/oncallroutine/useRoutineBadge.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'

const PROPS = { activeTab: 'dashboard', onTabChange: vi.fn(), username: 'u', onLogout: vi.fn() }
const READ = { 'oncall_routines.read': { view: true } }
const me = (count, level = 'warning') => ({ success: true, data: { generated_at: '2026-11-26T10:00:00Z', badge: { count, level }, teams: [] } })
const tab = (c) => c.querySelector('[data-tour="nav-tab-oncallroutines"]')
const pill = (c) => c.querySelector('[data-tab="oncallroutines"] [data-slot="nav-routine-count"]')

/** Rozeti okuyan ikinci tüketici (telefon çekmecesi yerine). */
function Probe() {
  const b = useRoutineBadge(true)
  return <span data-testid="probe">{b ? `${b.count}:${b.level}` : 'none'}</span>
}

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  resetRoutineBadgeForTests()
  vi.clearAllMocks()
  api.oncallRoutines.me.mockReset()
  permState.scope = null
  permState.perms = {}
  try { localStorage.clear() } catch { /* yok */ }
})

describe('Nöbet Rutinleri menü girişi — görünürlük', () => {
  it('eski sunucu (kapsam bayrağı yok) → giriş çizilmez ve /me istenmez', async () => {
    permState.scope = null
    permState.perms = READ
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(container)).toBeNull()
    await act(async () => { await Promise.resolve() })
    expect(api.oncallRoutines.me).not.toHaveBeenCalled()
  })

  it('kapsam açık ama okuma izni yok → giriş yok, istek yok; kapsam false → giriş yok', async () => {
    permState.scope = { oncall_routines: true }
    permState.perms = {}
    let r = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(r.container)).toBeNull()
    r.unmount()
    permState.scope = { oncall_routines: false }
    permState.perms = READ
    r = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(r.container)).toBeNull()
    await act(async () => { await Promise.resolve() })
    expect(api.oncallRoutines.me).not.toHaveBeenCalled()
  })

  it('kapsam açık + okuma izni → giriş raporlar grubunda, kanal raporlarının ardından', async () => {
    permState.scope = { oncall_routines: true, channel_reports: true }
    permState.perms = { ...READ, 'tech_error_reports.read': { view: true } }
    api.oncallRoutines.me.mockResolvedValue(me(0, 'none'))
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    expect(tab(container)).not.toBeNull()
    const channel = container.querySelector('[data-tour="nav-tab-channelreports"]')
    expect(channel).not.toBeNull()
    // DOM sırası: kanal raporları ÖNCE
    expect(channel.compareDocumentPosition(tab(container)) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await waitFor(() => expect(api.oncallRoutines.me).toHaveBeenCalledTimes(1))
  })
})

describe('Nöbet Rutinleri menü rozeti', () => {
  beforeEach(() => {
    permState.scope = { oncall_routines: true }
    permState.perms = READ
  })

  it('bekleyen görev sayısı rozet olarak çizilir; kritik seviye kırmızı tonda; etiket sayıyı söyler', async () => {
    api.oncallRoutines.me.mockResolvedValue(me(6, 'critical'))
    const { container } = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(pill(container)).not.toBeNull())
    const p = pill(container)
    expect(p).toHaveTextContent('6')
    expect(p).toHaveAttribute('data-level', 'critical')
    expect(p.getAttribute('aria-label')).toMatch(/6/)
    // Rozet satır düğmesinin İÇİNDE değil (iç içe etkileşimli öğe yok) ve tıklamayı geçirir
    expect(container.querySelector('[data-tour="nav-tab-oncallroutines"] [data-slot="nav-routine-count"]')).toBeNull()
    expect(p.parentElement.className).toMatch(/pointer-events-none/)
  })

  it('etiket sayı 1 iken tekil ("1 pending on-call task"), çoklukta çoğul', async () => {
    api.oncallRoutines.me.mockResolvedValue(me(1, 'warning'))
    let r = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(pill(r.container)).not.toBeNull())
    expect(pill(r.container).getAttribute('aria-label')).toBe(EN['nav.oncallRoutinesPending.one'].replace('{0}', '1'))
    r.unmount()
    resetRoutineBadgeForTests()
    api.oncallRoutines.me.mockResolvedValue(me(6, 'warning'))
    r = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(pill(r.container)?.textContent).toBe('6'))
    expect(pill(r.container).getAttribute('aria-label')).toBe(EN['nav.oncallRoutinesPending'].replace('{0}', '6'))
  })

  it('uyarı seviyesi uyarı tonunda; sayı 0 → rozet yok', async () => {
    api.oncallRoutines.me.mockResolvedValue(me(3, 'warning'))
    let r = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(pill(r.container)).not.toBeNull())
    expect(pill(r.container)).toHaveAttribute('data-level', 'warning')
    r.unmount()
    resetRoutineBadgeForTests()
    api.oncallRoutines.me.mockResolvedValue(me(0, 'none'))
    r = render(withSidebar(<Nav {...PROPS} />))
    await waitFor(() => expect(api.oncallRoutines.me).toHaveBeenCalledTimes(2))
    await act(async () => { await Promise.resolve() })
    expect(pill(r.container)).toBeNull()
  })

  it('okuma başarısız (5xx, ağ hatası, oturum bitti) → rozet YOK, asla "0"', async () => {
    for (const fail of [
      () => api.oncallRoutines.me.mockResolvedValue({ success: false, status: 503, error: 'x' }),
      () => api.oncallRoutines.me.mockRejectedValue(new Error('network')),
      () => api.oncallRoutines.me.mockResolvedValue(null),
    ]) {
      resetRoutineBadgeForTests()
      api.oncallRoutines.me.mockReset()
      fail()
      const r = render(withSidebar(<><Nav {...PROPS} /><Probe /></>))
      await waitFor(() => expect(api.oncallRoutines.me).toHaveBeenCalled())
      await act(async () => { await Promise.resolve(); await Promise.resolve() })
      expect(pill(r.container)).toBeNull()
      expect(screen.getByTestId('probe')).toHaveTextContent('none')
      r.unmount()
    }
  })

  it('iki tüketici aynı anda bağlanınca TEK istek; ikisi de aynı rozeti görür', async () => {
    api.oncallRoutines.me.mockResolvedValue(me(4, 'warning'))
    const { container } = render(withSidebar(<><Nav {...PROPS} /><Probe /></>))
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('4:warning'))
    expect(pill(container)).toHaveTextContent('4')
    expect(api.oncallRoutines.me).toHaveBeenCalledTimes(1)
  })

  it('refreshRoutineBadge taze okur (süren eski okumaya katılmaz); abone yokken istek atmaz', async () => {
    refreshRoutineBadge({ immediate: true })
    expect(api.oncallRoutines.me).not.toHaveBeenCalled()
    api.oncallRoutines.me.mockResolvedValueOnce(me(5, 'warning')).mockResolvedValueOnce(me(2, 'critical'))
    render(<Probe />)
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('5:warning'))
    await act(async () => { refreshRoutineBadge({ immediate: true }) })
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('2:critical'))
    expect(api.oncallRoutines.me).toHaveBeenCalledTimes(2)
  })

  it('geç gelen ESKİ yanıt yenisini ezmez', async () => {
    const slow = deferred()
    api.oncallRoutines.me.mockImplementationOnce(() => slow.promise).mockResolvedValueOnce(me(2, 'warning'))
    render(<Probe />)
    await waitFor(() => expect(api.oncallRoutines.me).toHaveBeenCalledTimes(1))
    await act(async () => { await fetchRoutineMe() })
    expect(screen.getByTestId('probe')).toHaveTextContent('2:warning')
    await act(async () => { slow.resolve(me(9, 'critical')); await slow.promise })
    expect(screen.getByTestId('probe')).toHaveTextContent('2:warning')
  })

  it('oturum sıfırlanınca rozet silinir; süren isteğin yanıtı sonra yazılmaz', async () => {
    api.oncallRoutines.me.mockResolvedValueOnce(me(7, 'warning'))
    render(<Probe />)
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('7:warning'))
    const slow = deferred()
    api.oncallRoutines.me.mockImplementationOnce(() => slow.promise)
    let p
    act(() => { p = fetchRoutineMe() })
    act(() => { emitSessionReset() })
    expect(screen.getByTestId('probe')).toHaveTextContent('none')
    await act(async () => { slow.resolve(me(8, 'critical')); await p })
    expect(screen.getByTestId('probe')).toHaveTextContent('none')
  })
})

describe('rozet yardımcıları (saf)', () => {
  it('badgeOf: okunamayan / biçimsiz yanıt null; 0 → seviye none', () => {
    expect(badgeOf(null)).toBeNull()
    expect(badgeOf({ ok: false, data: { badge: { count: 3 } } })).toBeNull()
    expect(badgeOf({ ok: true, data: {} })).toBeNull()
    expect(badgeOf({ ok: true, data: { badge: { count: 'x' } } })).toBeNull()
    expect(badgeOf({ ok: true, data: { badge: { count: -1 } } })).toBeNull()
    expect(badgeOf({ ok: true, data: { badge: { count: 0, level: 'critical' } } })).toEqual({ count: 0, level: 'none' })
    expect(badgeOf({ ok: true, data: { badge: { count: 3, level: 'critical' } } })).toEqual({ count: 3, level: 'critical' })
    expect(badgeOf({ ok: true, data: { badge: { count: 3, level: 'weird' } } })).toEqual({ count: 3, level: 'warning' })
  })

  it('itemBadge / sectionItemBadge: sayı > 0 değilse null; bölüm toplamı ve kritik seviye', () => {
    expect(itemBadge({})).toBeNull()
    expect(itemBadge({ badge: null })).toBeNull()
    expect(itemBadge({ badge: { count: 0, level: 'none' } })).toBeNull()
    expect(itemBadge({ badge: { count: 2.7, level: 'critical' } })).toEqual({ count: 2, level: 'critical' })
    expect(itemBadge({ badge: { count: 2, level: 'none' } })).toEqual({ count: 2, level: 'warning' })
    expect(sectionItemBadge({ items: [{ badge: { count: 2, level: 'warning' } }, { badge: { count: 3, level: 'critical' } }, {}] }))
      .toEqual({ count: 5, level: 'critical' })
    expect(sectionItemBadge({ items: [{}, { badge: null }] })).toBeNull()
  })
})
