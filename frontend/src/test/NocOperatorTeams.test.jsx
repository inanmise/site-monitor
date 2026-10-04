import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { noc: { getOperatorTeams: vi.fn(), previewOperatorTeams: vi.fn(), saveOperatorTeams: vi.fn() } },
    teams: { directory: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import NocOperatorTeams from '../components/admin/NocOperatorTeams.jsx'
import {
  diffIds, isDirty, normalizeIds, normalizePreview, normalizeSettings, teamOptions,
} from '../components/admin/noc/operatorTeamsModel.js'

const DIRECTORY = [
  { id: 1, name: 'Takım A', active: true },
  { id: 2, name: 'Takım B', active: true },
  { id: 3, name: 'Takım NOC', active: true },
  { id: 4, name: 'Takım Eski', active: false },
]
const SETTINGS = { team_ids: [3], operator_count: 2, updated_at: '2026-10-04T08:00:00', updated_by_name: 'Kişi Y', max_teams: 50 }
const preview = (ids) => ({
  teams: ids.map((id) => ({ id, name: DIRECTORY.find((d) => d.id === id)?.name, active: id !== 4, member_count: id === 3 ? 2 : 1 })),
  user_count: ids.length + 1,
  users: [
    { user_id: 11, display_name: 'Kişi A', username: 'kisia', team_ids: [3], team_names: ['Takım NOC'] },
    { user_id: 12, display_name: 'Kişi B', username: 'kisib', team_ids: [3], team_names: ['Takım NOC'] },
  ],
  truncated: false,
})

const chips = () => [...document.querySelectorAll('[data-slot="noc-ot-chip"]')]
// Seçenekler Radix popper sarmalayıcısında (jsdom ölçmediği için visibility:hidden) — erişilebilir ad hesaplanmaz; seçenek
// METNİNDEN bulunur (MultiTeamSelect.test.jsx deseni), basış satıra kabarcıklanır.
const option = async (label) => (await screen.findByText(label)).closest('[role="option"]')

async function renderReady(props = {}) {
  const utils = render(<NocOperatorTeams {...props} />)
  await waitFor(() => expect(chips()).toHaveLength(1))
  return utils
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.noc.getOperatorTeams.mockResolvedValue({ success: true, data: SETTINGS })
  api.teams.directory.mockResolvedValue({ success: true, data: DIRECTORY })
  api.admin.noc.previewOperatorTeams.mockImplementation(async (ids) => ({ success: true, data: preview(ids) }))
  api.admin.noc.saveOperatorTeams.mockImplementation(async (ids) => ({ success: true, data: { ...SETTINGS, team_ids: ids, operator_count: 3 } }))
})

describe('NocOperatorTeams — 7/24 izleme ekibi takımları', () => {
  it('kayıtlı seçim çip olarak + açıklama + önizleme (üye sayısı, kişiler ve takımları) + künye', async () => {
    await renderReady()
    expect(chips()[0]).toHaveAttribute('data-team-id', '3')
    expect(chips()[0]).toHaveTextContent('Takım NOC')
    expect(document.querySelector('[data-slot="noc-ot-what"]').textContent).toMatch(/noc_calls\.write/)
    await waitFor(() => expect(within(chips()[0]).getByText('2 people')).toBeInTheDocument())
    expect(api.admin.noc.previewOperatorTeams).toHaveBeenCalledWith([3])
    const users = [...document.querySelectorAll('[data-slot="noc-ot-user"]')]
    expect(users.map((u) => u.textContent)).toEqual(['Kişi ATakım NOC', 'Kişi BTakım NOC'])
    expect(document.querySelector('[data-slot="noc-ot-user-count"]')).toHaveTextContent('2 active users')
    expect(document.querySelector('[data-slot="noc-ot-meta"]').textContent).toMatch(/2 operators right now/)
    expect(document.querySelector('[data-slot="noc-ot-meta"]').textContent).toMatch(/Kişi Y/)
    // değişiklik yokken kayıt kapalı
    expect(screen.getByRole('button', { name: /Save teams/ })).toBeDisabled()
  })

  it('aranabilir çoklu seçim: arama → takım seç → çip eklenir, önizleme yeni seçimle; pasif takım etiketli', async () => {
    await renderReady()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /Teams/ }))
    const search = await screen.findByPlaceholderText(/search/i)
    fireEvent.change(search, { target: { value: 'eski' } })
    expect(await screen.findByText('Takım Eski (inactive team)')).toBeInTheDocument()
    expect(screen.queryByText('Takım A')).toBeNull()
    fireEvent.change(search, { target: { value: 'takım a' } })
    fireEvent.mouseDown(await option('Takım A'))
    await waitFor(() => expect(chips().map((c) => c.getAttribute('data-team-id'))).toEqual(['3', '1']))
    await waitFor(() => expect(api.admin.noc.previewOperatorTeams).toHaveBeenLastCalledWith([3, 1]))
    expect(screen.getByRole('button', { name: /Save teams/ })).toBeEnabled()
  })

  it('kaydet: yeni takım EKLENİYORSA önce onay; gövde seçili kimlikler; başarı bildirimi; × ile çıkarma onaysız kaydedilir', async () => {
    await renderReady()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /Teams/ }))
    fireEvent.mouseDown(await option('Takım B'))
    await waitFor(() => expect(chips()).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: /Save teams/ }))
    await waitFor(() => expect(api.admin.noc.saveOperatorTeams).toHaveBeenCalledWith([3, 2]))
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(confirmMock.mock.calls[0][0].message).toMatch(/Takım B/)
    await waitFor(() => expect(toastMock.success).toHaveBeenCalled())

    confirmMock.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Remove team Takım NOC' }))
    await waitFor(() => expect(chips().map((c) => c.getAttribute('data-team-id'))).toEqual(['2']))
    fireEvent.click(screen.getByRole('button', { name: /Save teams/ }))
    await waitFor(() => expect(api.admin.noc.saveOperatorTeams).toHaveBeenLastCalledWith([2]))
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('onay reddedilirse kaydedilmez; sunucu hatası toast', async () => {
    confirmMock.mockResolvedValueOnce(false)
    await renderReady()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /Teams/ }))
    fireEvent.mouseDown(await option('Takım B'))
    await waitFor(() => expect(chips()).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: /Save teams/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.noc.saveOperatorTeams).not.toHaveBeenCalled()

    api.admin.noc.saveOperatorTeams.mockResolvedValueOnce({ success: false, error: 'Only a global administrator can change this setting' })
    fireEvent.click(screen.getByRole('button', { name: /Save teams/ }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Only a global administrator can change this setting'))
  })

  it('salt okunur (kapsamlı müdür / denetçi): seçici kilitli, × yok, kayıt çubuğu yok; seçim ve önizleme görünür', async () => {
    await renderReady({ readOnly: true })
    expect(screen.getByRole('combobox', { name: /Teams/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /Remove team/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Save teams/ })).toBeNull()
    expect(screen.getByText(/Only a global administrator can change the team selection/)).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-ot-user"]').length).toBe(2))
  })

  it('boş seçim: açıklama metni, önizleme isteği yok', async () => {
    api.admin.noc.getOperatorTeams.mockResolvedValue({ success: true, data: { ...SETTINGS, team_ids: [], operator_count: 0 } })
    render(<NocOperatorTeams />)
    expect(await screen.findByText(/No team selected yet/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="noc-ot-preview"]')).toBeNull()
    expect(api.admin.noc.previewOperatorTeams).not.toHaveBeenCalled()
  })
})

describe('operatorTeamsModel', () => {
  it('kimlikler tekil + pozitif; kirli/fark sıradan bağımsız', () => {
    expect(normalizeIds([3, '3', 'x', -1, 0, 2])).toEqual([3, 2])
    expect(isDirty([1, 2], [2, 1])).toBe(false)
    expect(isDirty([1, 2], [2])).toBe(true)
    expect(diffIds([1, 2], [2, 3])).toEqual({ added: [3], removed: [1] })
  })
  it('ayar ve önizleme biçimleri; seçenekler Türkçe sıralı, pasif etiketli, rehberde olmayan seçili kimlik kaybolmaz', () => {
    expect(normalizeSettings({ team_ids: [5], operator_count: '4' })).toMatchObject({ ids: [5], operatorCount: 4, maxTeams: 50 })
    expect(normalizeSettings(null).ids).toEqual([])
    const p = normalizePreview({ teams: [{ id: 2, name: 'B', active: false, member_count: 0 }], user_count: 3, users: [{}], truncated: true })
    expect(p.teams.get(2)).toEqual({ name: 'B', active: false, memberCount: 0 })
    expect(p.truncated).toBe(true)
    const opts = teamOptions([{ id: 2, name: 'Çay' }, { id: 1, name: 'Ada', active: false }], [99], 'pasif')
    expect(opts.map((o) => o.label)).toEqual(['#99', 'Ada (pasif)', 'Çay'])
  })
})
