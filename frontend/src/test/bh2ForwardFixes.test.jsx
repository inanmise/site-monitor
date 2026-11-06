import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'

/**
 * Alarm iletimi ön yüz hata avı düzeltmeleri (2026-11-06):
 * - H3#3: satır meşgul kilidi KÜME — iki satırda art arda başlayan işlemlerde ilki bitince ikincinin kilidi açılmaz;
 * - sunucunun `state: "unknown"` satırı (izleme dizini okunamadı) nötr "Bilinmiyor" rozeti — etkin (yeşil) değil, "Dikkat"e
 *   sayılmaz, "Etkin" kutusuna da girmez;
 * - H3#6: önizleme satırında takım adı "-" / boş → rozet yalnız "İletim" ("İletim · -" değil).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn() }))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    alertForwards: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), monitors: vi.fn(), forMonitor: vi.fn() },
    teams: { directory: vi.fn(), members: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
import { api } from '../api/client'
import AlertForwardManager from '../components/admin/forwards/AlertForwardManager.jsx'
import ForwardBadge from '../components/forwards/ForwardBadge.jsx'
import { forwardTeamName, pillKind } from '../components/forwards/forwardModel.js'
import { needsAttention, normalizeForward, summarize } from '../components/admin/forwards/alertForwardModel.js'

const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi (Teknoloji / Ödeme Platformları)'
const base = (over) => ({
  id: 1, version: 1, owner_team_id: 10, owner_team_name: OWNER, owner_team_active: true, scope_kind: 'ALL', scope_types: [],
  scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: 'Mobil Bankacılık Uygulama Ekibi',
  recipient_team_active: true, recipient_members: [], recipient_address_count: 2, enabled: true, expires_on: null, state: 'active',
  created_at: '2026-11-01T08:00:00', created_by: 'yonetici', can_edit: true, ...over,
})
const listResponse = (items) => ({ success: true, data: { items, incoming: [], manageable_teams: [{ id: 10, name: OWNER }],
  can_grant: false, limits: { per_team: 100, members: 50, monitors: 200, note: 500 } } })
const rowOf = (id) => document.querySelector(`[data-slot="fwd-list"] [data-slot="fwd-row"][data-id="${id}"]`)
const tile = (k) => document.querySelector(`[data-slot="stat-item"][data-key="${k}"]`)

/** Elle çözülen söz (işlem sürerken satır kilidini görmek için). */
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=forwards')
  confirmMock.mockResolvedValue(true)
})

async function openMenu(id) {
  pressMenuTrigger(within(rowOf(id)).getByRole('button', { name: new RegExp(EN['fwd.actions']) }))
  return screen.findByRole('menu')
}

describe('AlertForwardManager — meşgul kilidi satır başına (H3#3)', () => {
  it('A ve B art arda duraklatılırken B bitse de A KİLİTLİ kalır; A bitince açılır', async () => {
    api.alertForwards.list.mockResolvedValue(listResponse([base({ id: 1 }), base({ id: 2, version: 4, recipient_team_name: 'Ağ Operasyon Merkezi' })]))
    const a = deferred()
    const b = deferred()
    api.alertForwards.update.mockImplementation((id) => (String(id) === '1' ? a.promise : b.promise))
    render(<AlertForwardManager />)
    await waitFor(() => expect(rowOf(2)).toBeTruthy())

    fireEvent.click(within(await openMenu(1)).getByRole('menuitem', { name: EN['fwd.pause'] }))
    fireEvent.click(within(await openMenu(2)).getByRole('menuitem', { name: EN['fwd.pause'] }))
    // B biter (A hâlâ sürüyor)
    await act(async () => { b.resolve({ success: true, data: base({ id: 2, version: 5, enabled: false, state: 'paused' }) }) })
    // A'nın menüsü: öğeler hâlâ kapalı (eski tek kimlikli kilit burada açılıyordu)
    const menuA = await openMenu(1)
    for (const item of within(menuA).getAllByRole('menuitem')) expect(item).toHaveAttribute('data-disabled')
    fireEvent.keyDown(menuA, { key: 'Escape' })
    await act(async () => { a.resolve({ success: true, data: base({ id: 1, version: 2, enabled: false, state: 'paused' }) }) })
    // İki işlemin arka plan yenilemesi de bitti (ilk okuma + 2)
    await waitFor(() => expect(api.alertForwards.list).toHaveBeenCalledTimes(3))
    const again = await openMenu(1)
    for (const item of within(again).getAllByRole('menuitem')) expect(item).not.toHaveAttribute('data-disabled')
  })
})

describe('iletim satırı durumu "unknown" (izleme dizini okunamadı)', () => {
  it('model: durum korunur; etkin / dikkat sayılmaz', () => {
    const f = normalizeForward(base({ state: 'unknown', scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 7, missing: null }] }))
    expect(f.state).toBe('unknown')
    expect(needsAttention(f)).toBe(false)
    expect(summarize([f])).toEqual({ total: 1, active: 0, paused: 0, expired: 0, attention: 0 })
    // Sunucu durumu göndermezse (eski sunucu) istemci "unknown" TÜRETMEZ
    expect(normalizeForward(base({ state: undefined })).state).toBe('active')
  })

  it('liste: nötr "Bilinmiyor" rozeti + açıklama; yeşil "Etkin" değil, KPI Etkin / Dikkat 0', async () => {
    api.alertForwards.list.mockResolvedValue(listResponse([base({ id: 5, state: 'unknown', recipient_address_count: null,
      scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 7, name: null, target: null, active: null, missing: null }] })]))
    render(<AlertForwardManager />)
    await waitFor(() => expect(rowOf(5)).toBeTruthy())
    const badge = rowOf(5).querySelector('[data-slot="fwd-state"]')
    expect(badge).toHaveAttribute('data-state', 'unknown')
    expect(badge).toHaveTextContent(EN['fwd.state.unknown'])
    expect(badge.className).not.toMatch(/success/)
    expect(rowOf(5).querySelector('[data-slot="fwd-state-unknown"]')).toHaveTextContent(EN['fwd.stateUnknownHint'])
    expect(rowOf(5).querySelector('[data-slot="fwd-attention"]')).toBeNull()
    expect(tile('active')).toHaveTextContent('0')
    expect(tile('attention')).toHaveTextContent('0')
  })

  it('rozet: etkin yok ama durumu bilinmeyen var → "durum bilinmiyor" (etkin değil DENMEZ)', () => {
    const ok = (items) => ({ status: 'ok', items, canAdd: false, ownerTeams: [] })
    expect(pillKind(ok([{ state: 'unknown' }, { state: 'paused' }]))).toBe('unknown')
    expect(pillKind(ok([{ state: 'active' }, { state: 'unknown' }]))).toBe('active')
    expect(pillKind(ok([{ state: 'paused' }]))).toBe('inactive')
  })
})

describe('ForwardBadge — takım adı "-" / boş (H3#6)', () => {
  it('forwardTeamName: tire ve boşluk → boş; gerçek ad kırpılır', () => {
    for (const v of ['-', ' - ', '—', '–', '', '   ', null, undefined]) expect(forwardTeamName(v)).toBe('')
    expect(forwardTeamName('  Ağ Ekibi ')).toBe('Ağ Ekibi')
    expect(forwardTeamName('-Ağ-')).toBe('-Ağ-')
  })

  it.each(['-', '—', '', null])('teamName %j → yalnız "İletim" (ad yok, tire yok)', (name) => {
    render(<ForwardBadge teamName={name} />)
    const b = document.querySelector('[data-slot="forward-badge"]')
    expect(b).toHaveTextContent(new RegExp(`^${EN['fwdx.role']}$`))
  })

  it('gerçek ad: "İletim · <ad>"', () => {
    render(<ForwardBadge teamName="Ağ Operasyon Merkezi" />)
    expect(document.querySelector('[data-slot="forward-badge"]')).toHaveTextContent(EN['fwdx.badgeTeam'].replace('{0}', 'Ağ Operasyon Merkezi'))
  })
})
