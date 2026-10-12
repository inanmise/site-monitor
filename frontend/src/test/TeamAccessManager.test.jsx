import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/en.js'

/**
 * Yönetim → Erişim paylaşımı (2026-10-12): sizinle paylaşılanlar kartı, paylaşım listesi (düzey, bitiş / durum, salt okunur
 * satır), KPI süzgeci, kaldırma onayı, boş / hata durumları, yeni paylaşım akışı (takım alıcı, düzey, gövde), "Tam" düzey
 * onayı, düzenlemede YALNIZ değişen alan (değişmediyse istek yok), sunucu alan hatası alanın altında. Adlar gerçek uzunlukta.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { confirmMock } = vi.hoisted(() => ({ confirmMock: vi.fn() }))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    teamAccess: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), sharedWithMe: vi.fn() },
    teams: { directory: vi.fn() },
    users: { directory: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
import { api } from '../api/client'
import TeamAccessManager from '../components/admin/access/TeamAccessManager.jsx'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'

const GRANTS = [
  { id: 1, owner_team_id: 10, owner_team_name: 'Ödeme', grantee_type: 'TEAM', grantee_team_id: 20, grantee_name: LONG_TEAM,
    level: 'VIEW', expires_on: null, expires_at: null, expired: false, note: 'Gece vardiyası desteği', created_by: 'yonetici',
    created_at: '2026-10-12T08:00:00', can_edit: true },
  { id: 2, owner_team_id: 10, owner_team_name: 'Ödeme', grantee_type: 'USER', grantee_user_id: 'u-1', grantee_name: LONG_PERSON,
    grantee_active: true, level: 'DELETE', expires_on: '2099-01-01', expires_at: '2099-01-01T21:00:00', expired: false,
    can_edit: true },
  { id: 3, owner_team_id: 30, owner_team_name: 'Kart', grantee_type: 'USER', grantee_user_id: 'u-2', grantee_name: 'Can Demir',
    grantee_active: false, level: 'EDIT', can_edit: false },
]

const pick = (opt) => { fireEvent.mouseDown(opt); fireEvent.click(opt) }
const rows = () => [...document.querySelectorAll('[data-slot="access-row"]')]
const rowOf = (text) => rows().find((r) => r.textContent.includes(text))

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=admin&g_tab=sharing')
  confirmMock.mockResolvedValue(true)
  api.teamAccess.list.mockResolvedValue({ success: true, data: { grants: GRANTS, manageable_teams: [{ id: 10, name: 'Ödeme' }] } })
  api.teamAccess.sharedWithMe.mockResolvedValue({ success: true, data: { grants: [
    { owner_team_id: 40, owner_team_name: 'Hazine', via: 'TEAM', via_team_name: 'Mobil', level: 'EXECUTE', expires_on: '2026-12-31' }] } })
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 10, name: 'Ödeme', active: true }, { id: 20, name: LONG_TEAM, active: true },
    { id: 50, name: 'Pasif Takım', active: false }] })
  api.users.directory.mockResolvedValue({ success: true, data: [{ id: 'u-1', username: 'ayse', display_name: LONG_PERSON, active: true }] })
  api.teamAccess.create.mockResolvedValue({ success: true, data: {} })
  api.teamAccess.update.mockResolvedValue({ success: true, data: {} })
  api.teamAccess.remove.mockResolvedValue({ success: true, data: { deleted: true } })
})

describe('TeamAccessManager', () => {
  it('sizinle paylaşılanlar + liste: düzey, bitiş / durum, not, salt okunur satır; adlar kırpılmaz', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    const shared = document.querySelector('[data-slot="access-shared-with-me"]')
    expect(shared).toHaveTextContent('Hazine')
    expect(shared).toHaveTextContent(EN['access.shared.viaTeam'].replace('{0}', 'Mobil'))
    expect(rowOf(LONG_TEAM).querySelector('[data-slot="access-level"]')).toHaveAttribute('data-level', 'VIEW')
    expect(rowOf(LONG_TEAM)).toHaveTextContent('Gece vardiyası desteği')
    expect(rowOf(LONG_TEAM).querySelector('[data-slot="access-expiry"]')).toHaveTextContent(EN['access.noExpiry'])
    expect(rowOf(LONG_PERSON).querySelector('[data-slot="access-level"]')).toHaveAttribute('data-level', 'DELETE')
    expect(rowOf('Can Demir').querySelector('[data-slot="access-state"]')).toHaveAttribute('data-state', 'inactive')
    expect(rowOf('Can Demir')).toHaveTextContent(EN['access.readOnly'])
    for (const r of rows()) expect(r.innerHTML).not.toContain('truncate')
    expect(document.querySelector('[data-slot="access-new"]')).not.toBeNull()
  })

  it('KPI: kişi kartı yalnız kişi paylaşımlarını süzer; URL g_atype; Toplam temizler', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="USER"]'))
    await waitFor(() => expect(rows()).toHaveLength(2))
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('g_atype')).toBe('USER'))   // 300 ms gecikmeli yazım
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="total"]'))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('kaldırma onay ister; onaylanınca silinir ve liste yeniden yüklenir; vazgeçilirse istek yok', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    confirmMock.mockResolvedValueOnce(false)
    pressMenuTrigger(within(rowOf(LONG_TEAM)).getByRole('button', { name: new RegExp(EN['access.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['access.remove.ok'] }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.teamAccess.remove).not.toHaveBeenCalled()
    pressMenuTrigger(within(rowOf(LONG_TEAM)).getByRole('button', { name: new RegExp(EN['access.actions']) }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['access.remove.ok'] }))
    await waitFor(() => expect(api.teamAccess.remove).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.teamAccess.list).toHaveBeenCalledTimes(2))
  })

  it('Takımlar menüsünden g_team ile gelince: paylaşımı olmayan yönetilen takım süzgeçte seçili görünür, yeni paylaşım o takımla açılır', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=sharing&g_team=60')
    api.teamAccess.list.mockResolvedValue({ success: true, data: { grants: GRANTS,
      manageable_teams: [{ id: 10, name: 'Ödeme' }, { id: 60, name: 'Yeni Takım' }] } })
    render(<TeamAccessManager />)
    await screen.findByText(EN['access.noMatch'])
    const owner = screen.getByRole('combobox', { name: EN['access.f.owner'] })
    expect(owner).toHaveValue('60')
    expect(owner.selectedOptions[0].textContent).toBe('Yeni Takım')
    fireEvent.click(document.querySelector('[data-slot="access-new"]'))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="access-form"]'); expect(f).not.toBeNull(); return f })
    expect(within(form).getByRole('combobox', { name: new RegExp(`^${EN['access.f.owner']}`) })).toHaveTextContent('Yeni Takım')
  })

  it('izin yok (403, ör. USER): kırmızı hata DEĞİL — boş durum + sizinle paylaşılanlar kartı', async () => {
    api.teamAccess.list.mockResolvedValue({ success: false, status: 403, error: 'İzin yok (test).' })
    render(<TeamAccessManager />)
    await screen.findByText(EN['access.empty.noManage'])
    expect(screen.queryByText('İzin yok (test).')).toBeNull()
    expect(screen.queryByText(EN['access.loadFailed'])).toBeNull()
    expect(document.querySelector('[data-slot="access-shared-with-me"]')).not.toBeNull()
  })

  it('KPI takım süzgecine uyar (Takımlar menüsünden g_team ile); Toplam yalnız KPI süzgecini temizler, takım kalır', async () => {
    window.history.replaceState({}, '', '/?tab=admin&g_tab=sharing&g_team=10')
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    const tile = (k) => document.querySelector(`[data-slot="stat-item"][data-key="${k}"]`)
    expect(tile('total')).toHaveTextContent('2')
    expect(tile('USER')).toHaveTextContent('1')
    fireEvent.click(tile('USER'))
    await waitFor(() => expect(rows()).toHaveLength(1))
    fireEvent.click(tile('total'))
    await waitFor(() => expect(rows()).toHaveLength(2))
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('g_team')).toBe('10'))
  })

  it('bitişe 1 günden az kala "Son gün"; pasif paylaşan takım satırı "uygulanmıyor"', async () => {
    const soon = new Date(Date.now() + 6 * 3600 * 1000).toISOString().slice(0, 19)
    api.teamAccess.list.mockResolvedValue({ success: true, data: { manageable_teams: [{ id: 10, name: 'Ödeme' }], grants: [
      { id: 8, owner_team_id: 10, owner_team_name: 'Ödeme', grantee_type: 'TEAM', grantee_team_id: 20, grantee_name: 'Mobil',
        level: 'VIEW', expires_on: '2026-10-12', expires_at: soon, expired: false, can_edit: true, owner_active: true, grantee_active: true },
      { id: 9, owner_team_id: 30, owner_team_name: 'Kart', grantee_type: 'TEAM', grantee_team_id: 20, grantee_name: 'Mobil 2',
        level: 'VIEW', expired: false, can_edit: false, owner_active: false, grantee_active: true },
    ] } })
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(rowOf('Mobil 2').querySelector('[data-slot="access-state"]')).toHaveAttribute('data-state', 'inactive')
    const exp = rows().find((r) => r.querySelector('[data-state="expiring"]'))
    expect(exp.querySelector('[data-state="expiring"]')).toHaveTextContent(EN['access.lastDay'])
  })

  it('boş: yönetebiliyorsa "Yeni paylaşım"; yönetemiyorsa açıklama ve düğme yok; hata: yeniden dene', async () => {
    api.teamAccess.list.mockResolvedValue({ success: true, data: { grants: [], manageable_teams: [] } })
    api.teamAccess.sharedWithMe.mockResolvedValue({ success: true, data: { grants: [] } })
    const { unmount } = render(<TeamAccessManager />)
    await screen.findByText(EN['access.empty.noManage'])
    expect(document.querySelector('[data-slot="access-new"]')).toBeNull()
    unmount()
    api.teamAccess.list.mockResolvedValue({ success: false, error: 'Sunucu yanıt vermedi (test).' })
    render(<TeamAccessManager />)
    await screen.findByText('Sunucu yanıt vermedi (test).')
    api.teamAccess.list.mockResolvedValue({ success: true, data: { grants: GRANTS, manageable_teams: [] } })
    fireEvent.click(screen.getByRole('button', { name: EN['sslv.retry'] }))
    await waitFor(() => expect(rows()).toHaveLength(3))
  })

  it('yeni paylaşım: tek yönetilen takım seçili gelir; takım alıcı + düzenleme düzeyi → doğru gövde; pasif takım listede yok', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(document.querySelector('[data-slot="access-new"]'))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="access-form"]'); expect(f).not.toBeNull(); return f })
    await waitFor(() => expect(api.teams.directory).toHaveBeenCalled())
    const teamPicker = within(form).getByRole('combobox', { name: new RegExp(`^${EN['access.f.team']}`) })
    await waitFor(() => expect(teamPicker).not.toBeDisabled())
    fireEvent.mouseDown(teamPicker)
    const opt = await screen.findByRole('option', { name: LONG_TEAM })
    // pasif takım ve paylaşan takımın kendisi listede yok
    expect([...document.querySelectorAll('[cmdk-item]')].map((o) => o.textContent)).toEqual([LONG_TEAM])
    expect(opt.innerHTML).not.toContain('truncate')   // uzun takım adı seçicide kırpılmaz
    pick(opt)
    await waitFor(() => expect(teamPicker).toHaveTextContent(LONG_TEAM))
    expect(teamPicker.innerHTML).not.toContain('truncate')
    fireEvent.click(within(form).getByRole('radio', { name: new RegExp(EN['access.level.EDIT']) }))
    fireEvent.click(document.querySelector('[data-slot="access-submit"]'))
    await waitFor(() => expect(api.teamAccess.create).toHaveBeenCalledWith({ owner_team_id: 10, grantee_type: 'TEAM', level: 'EDIT', grantee_team_id: 20 }))
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('"Tam" düzey ayrıca onay ister; vazgeçilirse kayıt yok', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    fireEvent.click(document.querySelector('[data-slot="access-new"]'))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="access-form"]'); expect(f).not.toBeNull(); return f })
    const teamPicker = within(form).getByRole('combobox', { name: new RegExp(`^${EN['access.f.team']}`) })
    await waitFor(() => expect(teamPicker).not.toBeDisabled())
    fireEvent.mouseDown(teamPicker)
    pick(await screen.findByRole('option', { name: LONG_TEAM }))
    await waitFor(() => expect(teamPicker).toHaveTextContent(LONG_TEAM))
    fireEvent.click(within(form).getByRole('radio', { name: new RegExp(EN['access.level.DELETE'].replace(/[()]/g, '\\$&')) }))
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(document.querySelector('[data-slot="access-submit"]'))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.teamAccess.create).not.toHaveBeenCalled()
  })

  it('düzenleme: sahip / alıcı sabit; YALNIZ değişen düzey gider; değişiklik yoksa istek yok; sunucu alan hatası alanın altında', async () => {
    render(<TeamAccessManager />)
    await waitFor(() => expect(rows()).toHaveLength(3))
    const openEdit = async () => {
      pressMenuTrigger(within(rowOf(LONG_TEAM)).getByRole('button', { name: new RegExp(EN['access.actions']) }))
      fireEvent.click(await screen.findByRole('menuitem', { name: EN['access.edit'] }))
      return waitFor(() => { const f = document.querySelector('[data-slot="access-form"]'); expect(f).not.toBeNull(); return f })
    }
    let form = await openEdit()
    expect(form.querySelector('[data-slot="access-fixed"]')).toHaveTextContent(LONG_TEAM)
    fireEvent.click(document.querySelector('[data-slot="access-submit"]'))   // değişiklik yok
    await waitFor(() => expect(document.querySelector('[data-slot="access-form"]')).toBeNull())
    expect(api.teamAccess.update).not.toHaveBeenCalled()

    form = await openEdit()
    fireEvent.click(within(form).getByRole('radio', { name: new RegExp(EN['access.level.EXECUTE']) }))
    api.teamAccess.update.mockResolvedValueOnce({ success: false, code: 'VALIDATION_FAILED', field: 'level', error: 'Düzey geçersiz (test).' })
    fireEvent.click(document.querySelector('[data-slot="access-submit"]'))
    await waitFor(() => expect(api.teamAccess.update).toHaveBeenCalledWith(1, { level: 'EXECUTE' }))
    await waitFor(() => expect(form.querySelector('[data-field="level"]')).toHaveTextContent('Düzey geçersiz (test).'))
  })
})
