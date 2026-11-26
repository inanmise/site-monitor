import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ oncallRoutines: { me: vi.fn() } }),
}))

const perm = vi.hoisted(() => ({ value: {}, scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.value, scope: perm.scope, canView: (r) => !!perm.value[r]?.view, canEdit: () => false, canExecute: () => false,
    refresh: () => {}, loaded: true, failed: false,
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'
import {
  DEFAULT_TYPES, DETAIL_ONLY_TYPES, NOC_PRESET, ONCALL_ROUTINES, STAT_PRESET, WIDGET_CATALOG, addableTypes, tvDetail,
} from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'
import { progressOf, routineSummary, statusCode, teamTone } from '../components/mydash/oncallRoutineModel.js'
import { resetRoutineBadgeForTests } from '../components/oncallroutine/useRoutineBadge.js'
import { clearSharedFetches } from '../utils/sharedFetch.js'

/**
 * Kişisel Panom "Nöbet rutinim" (2026-11-26): rutini açık takımlarımın bugünkü kontrol listesi — en çok 3 takım, nöbetçisi
 * olduklarım önce; durum rozeti, ilerleme, gecikmiş / açık sorun, "Sen nöbetçisin" ve devir onayı çipleri. Yeşil yalnız
 * gösterilen HER takım tamamlandıysa; bekleyen turuncu; gecikmiş kırmızı; bilinmeyen / takım yok gri. Satır o takımın sayfasını
 * `or_team` ile açar. Modül kapsamı (`@oncall_routines`) yoksa kutu sunulmaz.
 */
const LONG = 'Dijital Kanallar Uygulama Destek ve Nöbet Ekibi (Bireysel İnternet Bankacılığı — Canlı Ortam)'
const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (size = 'md') => ({ v: 1, widgets: [{ id: 'my-routine', type: 'oncall_routine', size }] })
const widget = () => document.querySelector('[data-slot="mydash-widget"][data-type="oncall_routine"]')
const team = (id, extra = {}) => ({
  team_id: id, team_name: `Takım ${id}`, date: '2026-11-26', workday: true, status: 'OPEN', required: 10, resolved: 4,
  pending: 6, overdue: 0, problems_open: 0, responsible_me: false, responsible_name: 'Ayşe Yılmaz', next_reminder: '16:30',
  handover_pending_ack: false, ...extra,
})
const ok = (teams) => ({ success: true, data: { generated_at: '2026-11-26T10:00:00Z', badge: { count: 0, level: 'none' }, teams } })

let navs = []
const onNav = (e) => navs.push(e.detail)
beforeEach(() => {
  vi.clearAllMocks()
  api.oncallRoutines.me.mockReset()
  resetRoutineBadgeForTests()
  clearSharedFetches()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = { 'oncall_routines.read': { view: true } }
  perm.scope = { oncall_routines: true }
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Panom — Nöbet rutinim: katalog', () => {
  it('katalogda (izin + modül kapsamı), çizim kaydında; varsayılanda / hazır düzenlerde YOK; TV\'de hep ayrıntılı; TR + EN metin', () => {
    const e = WIDGET_CATALOG.find((x) => x.type === 'oncall_routine')
    expect(e).toMatchObject({ size: 'md', tab: 'oncallroutines', perm: ['oncall_routines.read', '@oncall_routines'] })
    expect(ONCALL_ROUTINES).toBe('@oncall_routines')
    expect('oncall_routine').toMatch(/^[a-z_]{1,40}$/)
    expect(WIDGETS.oncall_routine).toBeTruthy()
    expect(addableTypes([], (p) => p === 'oncall_routines.read' || p === ONCALL_ROUTINES)).toContain('oncall_routine')
    expect(addableTypes([], (p) => p === 'oncall_routines.read')).not.toContain('oncall_routine')
    expect(addableTypes([], (p) => p === ONCALL_ROUTINES)).not.toContain('oncall_routine')
    expect(DEFAULT_TYPES).not.toContain('oncall_routine')
    expect(NOC_PRESET.map((w) => w.type)).not.toContain('oncall_routine')
    expect(STAT_PRESET.map((w) => w.type)).not.toContain('oncall_routine')
    expect(DETAIL_ONLY_TYPES.has('oncall_routine')).toBe(true)
    expect(tvDetail({ type: 'oncall_routine', style: 'stat' }, 'stat')).toBe(true)
    for (const d of [EN, TR]) {
      expect(d['mydash.w.oncall_routine']).toBeTruthy()
      expect(d['mydash.wd.oncall_routine']).toBeTruthy()
      for (const c of ['OPEN', 'COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED', 'UNKNOWN']) {
        expect(d[`mydash.or.status.${c}`], c).toBeTruthy()
      }
    }
  })

  it('modül kapsamı yok (eski sunucu) → kayıtlı düzendeki kutu çizilmez ve /me istenmez', async () => {
    perm.scope = null
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-page"]')).not.toBeNull())
    expect(widget()).toBeNull()
    expect(api.oncallRoutines.me).not.toHaveBeenCalled()
  })
})

describe('Panom — Nöbet rutinim: çizim', () => {
  it('nöbetçisi olduğum takım önce, en çok 3 satır + "N daha"; gecikmiş → kırmızı; satır takımın sayfasını açar; uzun ad tam', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([
      team(1, { team_name: LONG }),
      team(2),
      team(3, { status: 'COMPLETED', pending: 0, resolved: 10 }),
      team(4, { responsible_me: true, overdue: 2, problems_open: 3, handover_pending_ack: true }),
    ]))
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-or-list"]')).not.toBeNull())
    const w = widget()
    expect(w).toHaveAttribute('data-tone', 'danger')
    const rows = w.querySelectorAll('[data-slot="mydash-or-team"]')
    expect([...rows].map((r) => r.getAttribute('data-team'))).toEqual(['4', '1', '2'])
    expect(rows[0]).toHaveAttribute('data-tone', 'danger')
    expect(rows[0].querySelector('[data-slot="mydash-or-me"]')).not.toBeNull()
    expect(rows[0].querySelector('[data-slot="mydash-or-handover"]')).not.toBeNull()
    const facts0 = rows[0].querySelector('[data-slot="mydash-or-facts"]')
    expect(facts0).toHaveTextContent(EN['mydash.or.progress'].replace('{0}', '4').replace('{1}', '10'))
    expect(facts0).toHaveTextContent(EN['mydash.or.overdue'].replace('{0}', '2'))
    expect(facts0).toHaveTextContent(EN['mydash.or.problems'].replace('{0}', '3'))
    // Nöbetçi başkasıysa adı yazar
    expect(rows[1].querySelector('[data-slot="mydash-or-facts"]')).toHaveTextContent('Ayşe Yılmaz')
    expect(rows[1].querySelector('[data-slot="mydash-or-me"]')).toBeNull()
    // Uzun takım adı kırpılmaz
    const name = rows[1].querySelector('[data-slot="mydash-or-team-name"]')
    expect(name).toHaveTextContent(LONG)
    expect(name.className).not.toMatch(/truncate|line-clamp/)
    expect(name.className).toMatch(/overflow-wrap:anywhere/)
    // İlerleme çubuğu düğmenin DIŞINDA (düğme yalnız satır içi içerik taşır)
    expect(rows[1].querySelector('button [data-slot="progress-bar"]')).toBeNull()
    expect(rows[1].querySelector('[data-slot="mydash-or-bar"] [data-slot="progress-bar"]')).not.toBeNull()
    expect(w).toHaveTextContent(EN['mydash.more'].replace('{0}', '1'))
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'oncallroutines', params: { or_team: '4' } })
  })

  it('hepsi tamamlandı → yeşil; aralarında iş günü olmayan varsa yeşil DEĞİL', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([
      team(1, { status: 'COMPLETED', pending: 0, resolved: 10 }),
      team(2, { status: 'COMPLETED_PARTIAL', pending: 0, resolved: 10 }),
    ]))
    const r1 = render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'success'))
    r1.unmount()
    resetRoutineBadgeForTests()
    clearSharedFetches()
    api.oncallRoutines.me.mockResolvedValue(ok([
      team(1, { status: 'COMPLETED', pending: 0, resolved: 10 }),
      team(2, { status: 'NOT_WORKDAY', workday: false, pending: 0 }),
    ]))
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-or-list"]')).not.toBeNull())
    expect(widget()).toHaveAttribute('data-tone', 'muted')
  })

  it('sayı 1 iken İngilizce tekil: "1 overdue task", "1 open problem", "0 / 1 task" (çoğul -s yok)', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([team(1, { required: 1, resolved: 0, pending: 1, overdue: 1, problems_open: 1 })]))
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-or-list"]')).not.toBeNull())
    const facts = widget().querySelector('[data-slot="mydash-or-facts"]')
    expect(facts).toHaveTextContent(EN['mydash.or.progress.one'].replace('{0}', '0').replace('{1}', '1'))
    expect(facts).toHaveTextContent(EN['mydash.or.problems.one'].replace('{0}', '1'))
    expect(facts.textContent).not.toContain('1 open problems')
    expect(facts.textContent).not.toContain('1 tasks')
    const tone = widget().querySelector('[data-slot="mydash-widget-tone"]')
    expect(tone).toHaveTextContent(EN['mydash.or.overdueCount.one'].replace('{0}', '1'))
    expect(tone.textContent).not.toContain('tasks')
  })

  it('bekleyen tek görev (gecikme yok): "1 pending task"', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([team(1, { required: 2, resolved: 1, pending: 1 })]))
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-widget-tone"]')).not.toBeNull())
    expect(widget().querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.or.pendingCount.one'].replace('{0}', '1'))
  })

  it('bekleyen görev (gecikme yok) → turuncu; durum rozeti açık', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([team(1)]))
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-or-list"]')).not.toBeNull())
    expect(widget()).toHaveAttribute('data-tone', 'warning')
    expect(widget().querySelector('[data-slot="mydash-or-status"]')).toHaveAttribute('data-status', 'OPEN')
  })

  it('takım yok → gri "Rutin tanımlı takımın yok"; yetki yok (403) → gri, hata değil; eski pod (404) → gri "bu sürümde yok"', async () => {
    api.oncallRoutines.me.mockResolvedValue(ok([]))
    const r1 = render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-empty"]')).not.toBeNull())
    expect(widget()).toHaveAttribute('data-tone', 'muted')
    expect(widget().querySelector('[data-slot="mydash-empty"]')).toHaveTextContent(EN['mydash.or.noTeams'])
    r1.unmount()

    for (const [status, key] of [[403, 'mydash.noAccessBody'], [404, 'mydash.or.unsupported']]) {
      resetRoutineBadgeForTests()
      clearSharedFetches()
      api.oncallRoutines.me.mockResolvedValue({ success: false, status, error: 'x' })
      const r = render(<Shell initial={layout()}><MyDashPage /></Shell>)
      await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-empty"]')).not.toBeNull())
      expect(widget()).toHaveAttribute('data-tone', 'muted')
      expect(widget()).not.toHaveAttribute('data-failed')
      expect(widget().querySelector('[data-slot="mydash-empty"]')).toHaveTextContent(EN[key])
      r.unmount()
    }
  })

  it('ilk okuma başarısız (5xx) → kutu "veri yok" durumunda, asla yeşil / "takımın yok" değil', async () => {
    api.oncallRoutines.me.mockResolvedValue({ success: false, status: 503, error: 'unavailable' })
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-failed', 'true'))
    expect(widget()).toHaveAttribute('data-tone', 'muted')
    expect(widget().querySelector('[data-slot="mydash-or-list"]')).toBeNull()
  })
})

describe('Nöbet rutinim modeli (saf)', () => {
  it('teamTone: tamamlandı yeşil, kaçırıldı / gecikmiş kırmızı, bekleyen / eksik turuncu, iş günü dışı / duraklatıldı gri', () => {
    expect(teamTone(team(1, { status: 'COMPLETED' }))).toBe('success')
    expect(teamTone(team(1, { status: 'COMPLETED_PARTIAL' }))).toBe('success')
    expect(teamTone(team(1, { status: 'MISSED' }))).toBe('danger')
    expect(teamTone(team(1, { overdue: 1 }))).toBe('danger')
    expect(teamTone(team(1, { pending: 2 }))).toBe('warning')
    expect(teamTone(team(1, { status: 'INCOMPLETE', pending: 0 }))).toBe('warning')
    expect(teamTone(team(1, { status: 'OPEN', pending: 0, required: 5 }))).toBe('warning')
    expect(teamTone(team(1, { status: 'OPEN', pending: 0, required: 0 }))).toBe('muted')
    expect(teamTone(team(1, { status: 'NOT_WORKDAY', workday: false }))).toBe('muted')
    expect(teamTone(team(1, { status: 'PAUSED', overdue: 3 }))).toBe('muted')
    expect(teamTone(null)).toBe('muted')
  })

  it('statusCode / progressOf: bilinmeyen kod UNKNOWN; yüzde aşağı yuvarlanır, %100 yalnız hepsi çözülünce; bilinmiyorsa null', () => {
    expect(statusCode({ status: 'open' })).toBe('OPEN')
    expect(statusCode({ status: 'WHATEVER' })).toBe('UNKNOWN')
    expect(statusCode({ workday: false })).toBe('NOT_WORKDAY')
    expect(progressOf({ required: 3, resolved: 2 })).toBe(66)
    expect(progressOf({ required: 1000, resolved: 999 })).toBe(99)
    expect(progressOf({ required: 4, resolved: 4 })).toBe(100)
    expect(progressOf({ required: 0, resolved: 0 })).toBeNull()
    expect(progressOf({ required: 4 })).toBeNull()
  })

  it('routineSummary: biçimsiz yanıt bilinmiyor (takım yok DEĞİL); boş liste bilinir ve gri; toplamlar yalnız gösterilenlerden', () => {
    expect(routineSummary(null).known).toBe(false)
    expect(routineSummary({}).known).toBe(false)
    const empty = routineSummary({ teams: [] })
    expect(empty).toMatchObject({ known: true, total: 0, tone: 'muted' })
    const s = routineSummary({ teams: [team(1, { overdue: 1 }), team(2, { responsible_me: true }), team(3), team(4, { overdue: 9 })] }, 3)
    expect(s.teams.map((r) => r.team_id)).toEqual([2, 1, 3])
    expect(s.more).toBe(1)
    expect(s.overdue).toBe(1)
    expect(s.responsibleTeams).toBe(1)
    expect(s.tone).toBe('danger')
  })
})
