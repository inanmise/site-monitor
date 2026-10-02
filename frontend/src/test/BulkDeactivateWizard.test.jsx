import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import BulkDeactivateWizard from '../components/admin/BulkDeactivateWizard.jsx'
import UserManager from '../components/admin/UserManager.jsx'
import { EscalationPanel } from '../components/ui/TeamContactPanels.jsx'
import EscalationContacts from '../components/admin/EscalationContacts.jsx'
import {
  EMPTY_FORM, buildCriteria, canProceed, confirmMatches, filterTargets, isListChanged, parseDays, validateForm,
} from '../components/admin/bulkDeactivateModel.js'
import { eventLabel } from '../components/admin/audit/auditFormat.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const confirmMock = vi.hoisted(() => vi.fn())

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      bulkDeactivatePreview: vi.fn(), bulkDeactivate: vi.fn(), bulkDeactivateUndo: vi.fn(), bulkOperations: vi.fn(),
      searchUsers: vi.fn(), getContacts: vi.fn(), getUsers: vi.fn(),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/admin/AdminChangeHistory.jsx', () => ({ default: () => null }))
import { api } from '../api/client'

const TEAMS = [{ id: 1, name: 'Ödeme' }, { id: 2, name: 'Kartlar' }]
const TARGETS = [
  { id: 11, username: 'ALI', display_name: 'Ali Yılmaz', email: 'ali@example.com', system_role: 'USER', auth_source: 'LDAP', team_ids: [1], last_login_at: null },
  { id: 12, username: 'VELI', display_name: 'Veli Kaya', email: 'veli@example.com', system_role: 'TEAM_ADMIN', auth_source: 'LOCAL', team_ids: [2], last_login_at: '2026-01-01T00:00:00' },
  { id: 13, username: 'AYSE', display_name: 'Ayşe Demir', email: 'ayse@example.com', system_role: 'AUDIT', auth_source: 'LOCAL', team_ids: [], last_login_at: null },
]
const PREVIEW = {
  total: 3, max: 5000, over_limit: false, targets: TARGETS, targets_truncated: false,
  excluded: { admins: 2, self: 1, already_inactive: 4 }, criteria: {}, inactive_cutoff: null,
}
const RESULT = {
  operation_id: 42, total: 3, ok: 2, failed: 1, skipped: 0,
  failures: [{ id: 13, username: 'AYSE', error: 'db patladı' }], skipped_rows: [],
}

const dialog = () => screen.getByRole('dialog')
const btn = (name) => within(dialog()).getByRole('button', { name })

function renderWizard(props = {}) {
  const onClose = vi.fn()
  const onDone = vi.fn()
  render(<BulkDeactivateWizard open teams={TEAMS} onClose={onClose} onDone={onDone} {...props} />)
  return { onClose, onDone }
}

async function toPreview() {
  fireEvent.click(btn(/^(Önizle|Preview)$/))
  await screen.findByText(/3 kullanıcı pasife alınacak|3 users will be deactivated/)
}

async function toConfirm() {
  await toPreview()
  fireEvent.click(btn(/^(Devam|Continue)$/))
  await screen.findByLabelText(/Onaylamak için 3 yazın|Type 3 to confirm/)
}

describe('bulkDeactivateModel', () => {
  it('ölçüt gövdesi: varsayılan tüm kullanıcılar; takım / gün / kaynak / rol yalnız seçilince', () => {
    expect(buildCriteria(EMPTY_FORM)).toEqual({ scope: 'all', auth_source: 'ALL' })
    expect(buildCriteria({ ...EMPTY_FORM, scope: 'teams', teamIds: ['2', 1], inactiveOn: true, days: '30', includeNever: false, source: 'LDAP', role: 'AUDIT' }))
      .toEqual({ scope: 'teams', team_ids: [2, 1], inactive_days: 30, include_never_logged_in: false, auth_source: 'LDAP', system_role: 'AUDIT' })
    // ADMIN rolü seçilemez — gövdeye girmez
    expect(buildCriteria({ ...EMPTY_FORM, role: 'ADMIN' })).not.toHaveProperty('system_role')
  })

  it('doğrulama: takım kapsamında takım zorunlu, gün 1–3650 tam sayı', () => {
    expect(validateForm({ ...EMPTY_FORM, scope: 'teams' })).toEqual({ teams: true })
    expect(validateForm({ ...EMPTY_FORM, inactiveOn: true, days: '0' })).toEqual({ days: true })
    expect(validateForm({ ...EMPTY_FORM, inactiveOn: true, days: '3651' })).toEqual({ days: true })
    expect(validateForm({ ...EMPTY_FORM, inactiveOn: true, days: '90' })).toEqual({})
    expect(parseDays(' 12 ')).toBe(12)
    expect(parseDays('1.5')).toBeNull()
  })

  it('onay eşleşmesi tam sayı; arama ad/kullanıcı adı/e-posta/takım; 409 tanıma; ilerleme kuralı', () => {
    expect(confirmMatches('3', 3)).toBe(true)
    expect(confirmMatches(' 3 ', 3)).toBe(true)
    expect(confirmMatches('03', 3)).toBe(false)
    expect(confirmMatches('0', 0)).toBe(false)
    expect(filterTargets(TARGETS, 'kartlar', { 1: 'Ödeme', 2: 'Kartlar' }).map((u) => u.id)).toEqual([12])
    expect(filterTargets(TARGETS, 'AYŞE').map((u) => u.id)).toEqual([13])
    expect(isListChanged({ success: false, code: 'BULK_LIST_CHANGED' })).toBe(true)
    expect(isListChanged({ success: false, error: 'x' })).toBe(false)
    expect(canProceed({ total: 0 })).toBe(false)
    expect(canProceed({ total: 6000, over_limit: true })).toBe(false)
    expect(canProceed(PREVIEW)).toBe(true)
  })

  it('denetim etiketleri: yeni olay türleri okunur ad alır (ham kod değil)', () => {
    const t = (k) => ({ 'audit.ev.USER_BULK_DEACTIVATE': 'Kullanıcılar toplu pasife alındı' }[k] ?? k)
    expect(eventLabel('USER_BULK_DEACTIVATE', t)).toBe('Kullanıcılar toplu pasife alındı')
  })
})

describe('BulkDeactivateWizard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.bulkDeactivatePreview.mockResolvedValue({ success: true, data: PREVIEW })
    api.admin.bulkDeactivate.mockResolvedValue({ success: true, data: RESULT })
    api.admin.bulkDeactivateUndo.mockResolvedValue({ success: true, data: { operation_id: 42, ok: 2, skipped: 0, failed: 0 } })
    api.admin.bulkOperations.mockResolvedValue({ success: true, data: { operations: [] } })
    confirmMock.mockResolvedValue(true)
  })

  it('adımlar: Ölçüt etkin adım; her zaman hariç notu görünür', async () => {
    renderWizard()
    const steps = await screen.findByRole('list', { name: /Sihirbaz adımları|Wizard steps/ })
    expect(steps.querySelector('[data-step="criteria"]')).toHaveAttribute('aria-current', 'step')
    expect(within(dialog()).getByText(/Her zaman hariç|Always excluded/)).toBeInTheDocument()
  })

  it('takım kapsamında takım seçilmeden önizleme İSTENMEZ; hata alanın altında', async () => {
    renderWizard()
    fireEvent.click(within(dialog()).getByRole('radio', { name: /Seçili takımlar|Selected teams/ }))
    fireEvent.click(btn(/^(Önizle|Preview)$/))
    expect(await within(dialog()).findByText(/En az bir takım seçin|Choose at least one team/)).toBeInTheDocument()
    expect(api.admin.bulkDeactivatePreview).not.toHaveBeenCalled()
  })

  it('ölçüt → önizleme: sunucuya normalize gövde gider; toplam, dışlama sayıları, satırlar ve arama', async () => {
    renderWizard()
    fireEvent.click(within(dialog()).getByRole('checkbox', { name: /Belirli bir süredir|haven’t signed in/ }))
    const days = within(dialog()).getByLabelText(/Gün sayısı|Number of days/)
    fireEvent.change(days, { target: { value: '120' } })
    await toPreview()
    expect(api.admin.bulkDeactivatePreview).toHaveBeenCalledWith({
      scope: 'all', auth_source: 'ALL', inactive_days: 120, include_never_logged_in: true,
    })
    const ex = dialog().querySelector('[data-slot="ubd-excluded"]')
    expect(ex.querySelector('[data-excluded="admins"]')).toHaveTextContent('2')
    expect(ex.querySelector('[data-excluded="self"]')).toHaveTextContent('1')
    expect(ex.querySelector('[data-excluded="already_inactive"]')).toHaveTextContent('4')
    expect(dialog().querySelectorAll('[data-slot="ubd-row"]')).toHaveLength(3)
    expect(within(dialog()).getAllByText(/Hiç giriş yapmadı|Never signed in/).length).toBe(2)
    fireEvent.change(within(dialog()).getByRole('textbox', { name: /Listede ara|Search the list/ }), { target: { value: 'kartlar' } })
    await waitFor(() => expect(dialog().querySelectorAll('[data-slot="ubd-row"]')).toHaveLength(1))
    expect(within(dialog()).getByText('Veli Kaya')).toBeInTheDocument()
  })

  it('onay: düğme yazılan sayı TAM eşleşene dek kapalı; uygulama expected_count + not ile gider; sonuç + geri al', async () => {
    const { onDone, onClose } = renderWizard()
    await toConfirm()
    const apply = btn(/3 kullanıcıyı pasife al|Deactivate 3 users/)
    expect(apply).toBeDisabled()
    const input = within(dialog()).getByLabelText(/Onaylamak için 3 yazın|Type 3 to confirm/)
    fireEvent.change(input, { target: { value: '4' } })
    expect(apply).toBeDisabled()
    fireEvent.change(input, { target: { value: '3' } })
    expect(apply).toBeEnabled()
    fireEvent.change(within(dialog()).getByLabelText(/Not \/ gerekçe|Note \/ reason/), { target: { value: 'yıllık temizlik' } })
    fireEvent.click(apply)

    await screen.findByText(/2 pasife alındı|2 deactivated/)
    expect(api.admin.bulkDeactivate).toHaveBeenCalledWith({
      criteria: { scope: 'all', auth_source: 'ALL' }, expected_count: 3, note: 'yıllık temizlik',
    })
    expect(within(dialog()).getByText(/1 başarısız|1 failed/)).toBeInTheDocument()
    expect(within(dialog()).getByText('db patladı')).toBeInTheDocument()
    expect(dialog().querySelector('[data-slot="ubd-steps"] [data-step="result"]')).toHaveAttribute('aria-current', 'step')

    fireEvent.click(btn(/Bu işlemi geri al|Undo this operation/))
    await waitFor(() => expect(api.admin.bulkDeactivateUndo).toHaveBeenCalledWith(42))
    expect(confirmMock).toHaveBeenCalled()
    expect((await screen.findAllByText(/2 kullanıcı aktifleşti|2 users reactivated/)).length).toBeGreaterThan(0)
    expect(within(dialog()).queryByRole('button', { name: /Bu işlemi geri al|Undo this operation/ })).toBeNull()

    fireEvent.click(within(dialog().querySelector('[data-slot="dialog-footer"]')).getByRole('button', { name: /^(Kapat|Close)$/ }))
    expect(onDone).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('409 liste değişti: mesaj + "Önizlemeyi yenile" → önizleme yeniden alınır, onay metni sıfırlanır', async () => {
    api.admin.bulkDeactivate.mockResolvedValueOnce({ success: false, code: 'BULK_LIST_CHANGED', current_count: 4, error: 'Liste değişti' })
    renderWizard()
    await toConfirm()
    fireEvent.change(within(dialog()).getByLabelText(/Onaylamak için 3 yazın|Type 3 to confirm/), { target: { value: '3' } })
    fireEvent.click(btn(/3 kullanıcıyı pasife al|Deactivate 3 users/))
    expect(await within(dialog()).findByText(/Liste değişti — şimdi 4|list has changed — 4 users/)).toBeInTheDocument()
    expect(dialog().querySelector('[data-slot="ubd-error"]')).toHaveAttribute('data-list-changed', 'true')

    api.admin.bulkDeactivatePreview.mockResolvedValueOnce({ success: true, data: { ...PREVIEW, total: 4, targets: [...TARGETS, { ...TARGETS[0], id: 14, username: 'YENI' }] } })
    fireEvent.click(btn(/Önizlemeyi yenile|Refresh preview/))
    await screen.findByText(/4 kullanıcı pasife alınacak|4 users will be deactivated/)
    expect(api.admin.bulkDeactivatePreview).toHaveBeenCalledTimes(2)
    expect(dialog().querySelector('[data-slot="ubd-steps"] [data-step="preview"]')).toHaveAttribute('aria-current', 'step')
    expect(dialog().querySelector('[data-slot="ubd-error"]')).toBeNull()
  })

  it('sınır aşımı ya da hedef yok → Devam kapalı', async () => {
    api.admin.bulkDeactivatePreview.mockResolvedValueOnce({ success: true, data: { ...PREVIEW, total: 6000, over_limit: true } })
    renderWizard()
    fireEvent.click(btn(/^(Önizle|Preview)$/))
    expect(await within(dialog()).findByText(/en fazla 5000|at most 5000/)).toBeInTheDocument()
    expect(btn(/^(Devam|Continue)$/)).toBeDisabled()
  })

  it('son toplu işlemler: geri alınabilene düğme, geri alınmışa rozet; geri al → API + liste tazelenir', async () => {
    api.admin.bulkOperations.mockResolvedValue({ success: true, data: { operations: [
      { id: 9, status: 'DONE', created_at: '2026-10-01T10:00:00Z', actor: 'ADMIN1', ok_count: 5, criteria: { scope: 'teams', team_ids: [1], inactive_days: 90 }, note: 'gözden geçirme', can_undo: true },
      { id: 8, status: 'DONE', created_at: '2026-09-01T10:00:00Z', actor: 'ADMIN1', ok_count: 2, criteria: { scope: 'all' }, undone_at: '2026-09-02T10:00:00Z', can_undo: false },
    ] } })
    renderWizard()
    const rows = await screen.findAllByText(/kullanıcı pasife alındı|users deactivated/)
    expect(rows).toHaveLength(2)
    const history = dialog().querySelector('[data-slot="ubd-history"]')
    expect(history.querySelector('[data-op="8"] [data-slot="ubd-undone"]')).not.toBeNull()
    expect(within(history.querySelector('[data-op="8"]')).queryByRole('button')).toBeNull()
    fireEvent.click(within(history.querySelector('[data-op="9"]')).getByRole('button', { name: /#9/ }))
    await waitFor(() => expect(api.admin.bulkDeactivateUndo).toHaveBeenCalledWith(9))
    await waitFor(() => expect(api.admin.bulkOperations).toHaveBeenCalledTimes(2))
  })
})

describe('UserManager — Toplu pasife al düğmesi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.admin.searchUsers.mockResolvedValue({ success: true, data: [], total: 0, page: 0, total_pages: 0, active_admin_count: 1 })
    api.admin.bulkOperations.mockResolvedValue({ success: true, data: { operations: [] } })
  })

  it('yalnız GLOBAL yöneticiye görünür ve sihirbazı açar', async () => {
    render(<UserManager systemRole="ADMIN" teams={TEAMS} currentUsername="admin" globalAdmin />)
    const open = await screen.findByRole('button', { name: /Toplu pasife al|Bulk deactivate/ })
    fireEvent.click(open)
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: /Sihirbaz adımları|Wizard steps/ })).toBeInTheDocument()
  })

  it('kapsamlı müdür (ADMIN, global değil), TEAM_ADMIN ve USER görmez', async () => {
    for (const role of ['ADMIN', 'TEAM_ADMIN', 'USER']) {
      const { unmount } = render(<UserManager systemRole={role} teams={TEAMS} currentUsername="x" ownTeamId={1} />)
      await waitFor(() => expect(api.admin.searchUsers).toHaveBeenCalled())
      expect(screen.queryByRole('button', { name: /Toplu pasife al|Bulk deactivate/ })).toBeNull()
      unmount()
    }
  })
})

describe('Eskalasyon kişileri — pasif hesaba bağlı kişi', () => {
  const t = (k) => ({ 'team.memberInactive': 'Pasif', 'team.contactInactiveTip': 'Bağlı hesap pasif — bu kişiye bildirim gitmez' }[k] ?? k)

  it('takım penceresi Eskalasyon sekmesi: user_active=false → belirgin "Pasif" rozeti (UserX); aktif/bağsız kişide yok', () => {
    render(<EscalationPanel t={t} contacts={[
      { id: 1, name: 'Ayrılmış Kişi', email: 'a@x.com', role: 'MANAGER', min_alert_level: 'HIGH', user_active: false },
      { id: 2, name: 'Aktif Kişi', email: 'b@x.com', role: 'TECH', min_alert_level: 'HIGH', user_active: true },
      { id: 3, name: 'Nöbet Kutusu', email: 'noc@x.com', role: 'TECH', min_alert_level: 'HIGH', user_active: null },
    ]} />)
    const cards = document.querySelectorAll('[data-slot="team-escalation-card"]')
    expect(cards).toHaveLength(3)
    const passive = [...cards].find((c) => c.textContent.includes('Ayrılmış Kişi'))
    expect(passive).toHaveAttribute('data-inactive', 'true')
    const badge = passive.querySelector('[data-slot="team-escalation-inactive"]')
    expect(badge).toHaveTextContent('Pasif')
    expect(badge.getAttribute('data-variant')).toBe('destructive')
    expect(badge.querySelector('svg')).not.toBeNull()
    expect(document.querySelectorAll('[data-slot="team-escalation-inactive"]')).toHaveLength(1)
  })

  it('Yönetim → Eskalasyon kişileri listesi: bağlı kullanıcı pasifse rozet', async () => {
    api.admin.getContacts.mockResolvedValue({ success: true, data: [
      { id: 1, name: 'Ayrılmış Kişi', email: 'a@x.com', role: 'TECH', min_alert_level: 'HIGH', active: true, team_id: 1, user_id: 5 },
      { id: 2, name: 'Aktif Kişi', email: 'b@x.com', role: 'TECH', min_alert_level: 'HIGH', active: true, team_id: 1, user_id: 6 },
    ] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: [
      { id: 5, username: 'gone', display_name: 'Ayrılmış Kişi', active: false },
      { id: 6, username: 'here', display_name: 'Aktif Kişi', active: true },
    ] })
    render(<EscalationContacts teams={TEAMS} systemRole="ADMIN" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="contact-user-inactive"]')).toHaveLength(1))
    const row = document.querySelector('[data-slot="contact-user-inactive"]').closest('tr')
    expect(row).toHaveTextContent('Ayrılmış Kişi')
  })
})
