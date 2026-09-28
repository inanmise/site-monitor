import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Mükerrer alan adı bandı (2026-09-28): ileti + SAHİBİ takım rozeti + yönlendirme; eylemler sunucu bayraklarına göre.
 * "Aktar" yalnız can_transfer'de ve İKİ takımı adıyla söyleyen onaydan SONRA doğru kimliklerle mevcut transfer ucuna;
 * aktaramayan için "Aktarım talebi oluştur" gerçek Sorun Bildir penceresini aktarım kipinde açar (DOMAIN_TRANSFER).
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { transferCertSy: vi.fn(), restoreInventory: vi.fn() },
    getMe: vi.fn(),
    sendIssueReport: vi.fn(),
  }),
  formatDateOnly: (iso) => (iso ? String(iso).slice(0, 10) : '—'),
  getRecentFailures: () => [],
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
// Salt okunur kayıt görünümü: gerçek pencere ağır; sözleşme (alan adı + salt okunur + sahibi takım) pinlenir.
vi.mock('../components/CertificateModal.jsx', () => ({
  default: ({ domain, readOnly, readOnlyTeam, onClose }) => (
    <div data-testid="cert-modal" data-domain={domain} data-readonly={String(!!readOnly)}>
      {readOnlyTeam?.name}<button type="button" onClick={onClose}>close-cert</button>
    </div>
  ),
}))

import { api } from '../api/client'
import DomainConflictBanner from '../components/inventory/DomainConflictBanner.jsx'

const FOREIGN = {
  domain: 'shop.example.com', inventory_id: 2, team_id: 9, team_name: 'Takım B', ug_team_id: null, ug_team_name: null,
  deleted: false, deleted_at: null, same_team: false, can_view: true, can_restore: false, can_transfer: false,
}
const MSG = "This domain is already registered in the 'Takım B' team's inventory. A duplicate record can't be created; if the domain should belong to your team, the record needs to be transferred to it."
const TARGET = { id: 5, name: 'Takım A' }

function show(existing = FOREIGN, extra = {}) {
  const onResolved = vi.fn()
  render(<DomainConflictBanner conflict={{ message: MSG, existing }} targetTeam={TARGET} onResolved={onResolved} {...extra} />)
  return { onResolved, banner: document.querySelector('[data-slot="domain-conflict"]') }
}
const btn = (name) => screen.queryByRole('button', { name })

describe('DomainConflictBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.getMe.mockResolvedValue({ success: true, username: 'u1', email: 'u1@example.com' })
    api.sendIssueReport.mockResolvedValue({ success: true, reference: 'LIR-2026-000123' })
  })

  it('uyarı bandı: sunucu iletisi + sahibi takım TeamBadge (tıklanabilir → üyeler) + aktarım yönlendirmesi; toast değil', () => {
    const { banner } = show()
    const alert = screen.getByRole('alert')
    expect(alert).toHaveAttribute('data-tone', 'warning')
    expect(within(alert).getByText(MSG)).toBeInTheDocument()
    const badge = banner.querySelector('[data-slot="team-badge"]')
    expect(badge.textContent).toBe('Takım B')
    expect(within(alert).getByRole('button', { name: /members of team Takım B/i })).toBeInTheDocument()
    expect(alert.textContent).toMatch(/Only global administrators can do that/)
  })

  it('aktarım yetkisi YOK: "Aktar" çizilmez; "Kaydı görüntüle" + "Aktarım talebi oluştur" var', () => {
    show()
    expect(btn(/^Move to/)).toBeNull()
    expect(btn('View record')).toBeInTheDocument()
    expect(btn('Request a transfer')).toBeInTheDocument()
  })

  it('global yönetici (can_transfer): onay İKİ takımı adıyla söyler; onaydan SONRA transfer(kayıt id, seçili takım id) → onResolved', async () => {
    api.admin.transferCertSy.mockResolvedValue({ success: true, data: { id: 2, domain: 'shop.example.com', team_id: 5 } })
    const { onResolved } = show({ ...FOREIGN, can_transfer: true })
    expect(btn('Request a transfer')).toBeNull()
    fireEvent.click(btn("Move to 'Takım A'"))
    await waitFor(() => expect(api.admin.transferCertSy).toHaveBeenCalledWith(2, 5))
    const opts = confirmMock.mock.calls[0][0]
    expect(opts.message).toContain('shop.example.com')
    expect(opts.message).toContain("'Takım B'")
    expect(opts.message).toContain("'Takım A'")
    expect(api.admin.restoreInventory).not.toHaveBeenCalled()
    await waitFor(() => expect(onResolved).toHaveBeenCalledWith({
      kind: 'transferred', domain: 'shop.example.com', record: { id: 2, domain: 'shop.example.com', team_id: 5 } }))
  })

  it('onay İPTAL → transfer ucu ÇAĞRILMAZ', async () => {
    confirmMock.mockResolvedValue(false)
    const { onResolved } = show({ ...FOREIGN, can_transfer: true })
    fireEvent.click(btn("Move to 'Takım A'"))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.transferCertSy).not.toHaveBeenCalled()
    expect(onResolved).not.toHaveBeenCalled()
  })

  it('aktarım TALEBİ: Sorun Bildir aktarım kipinde açılır (alan adı + mevcut/istenen ekip dolu); gerekçe zorunlu; DOMAIN_TRANSFER ile gider', async () => {
    const { banner } = show()
    fireEvent.click(btn('Request a transfer'))
    const dlg = await screen.findByRole('dialog', { name: /Domain transfer request/ })
    const summary = dlg.querySelector('[data-slot="transfer-summary"]')
    expect(summary.textContent).toContain('shop.example.com')
    expect([...summary.querySelectorAll('[data-slot="team-badge"]')].map((b) => b.textContent)).toEqual(['Takım B', 'Takım A'])
    expect(within(dlg).getByText('Domain transfer')).toBeInTheDocument()   // tür rozeti
    // önem kartları ve ekran görüntüsü bölümü bu kipte YOK
    expect(within(dlg).queryByText('How is it affecting you?')).toBeNull()
    expect(within(dlg).queryByText('Screenshots')).toBeNull()

    fireEvent.click(within(dlg).getByRole('button', { name: 'Send report' }))
    expect(await within(dlg).findByText('A reason is required')).toBeInTheDocument()
    expect(api.sendIssueReport).not.toHaveBeenCalled()

    fireEvent.change(within(dlg).getByRole('textbox', { name: /Reason/ }), { target: { value: 'Our team now owns the application' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Send report' }))
    await waitFor(() => expect(api.sendIssueReport).toHaveBeenCalledTimes(1))
    const dto = api.sendIssueReport.mock.calls[0][0]
    expect(dto.category).toBe('DOMAIN_TRANSFER')
    expect(dto.message).toContain('shop.example.com (inventory record #2)')
    expect(dto.message).toContain('Current team: Takım B (#9)')
    expect(dto.message).toContain('Requested team: Takım A (#5)')
    expect(dto.message).toContain('Our team now owns the application')
    // Gönderildi: bant referansı söyler, ikinci talep düğmesi gizlenir
    await waitFor(() => expect(banner.querySelector('[data-slot="domain-conflict-requested"]').textContent).toContain('LIR-2026-000123'))
    expect(btn('Request a transfer')).toBeNull()
  })

  it('"Kaydı görüntüle": kayıt SALT OKUNUR pencerede, sahibi takımla açılır', async () => {
    show()
    fireEvent.click(btn('View record'))
    const modal = await screen.findByTestId('cert-modal')
    expect(modal).toHaveAttribute('data-domain', 'shop.example.com')
    expect(modal).toHaveAttribute('data-readonly', 'true')
    expect(modal.textContent).toContain('Takım B')
  })

  it('çöp kutusu + AYNI takım + geri yükleme yetkisi: "Çöp kutusundan geri yükle" → onay → restore(id) → onResolved; aktar/talep yok', async () => {
    api.admin.restoreInventory.mockResolvedValue({ success: true, data: { id: 4, domain: 'shop.example.com' } })
    const gone = { ...FOREIGN, inventory_id: 4, team_id: 5, team_name: 'Takım A', deleted: true, deleted_at: '2026-09-01T00:00:00',
      same_team: true, can_view: false, can_restore: true }
    const { onResolved, banner } = show(gone)
    expect(screen.getByRole('alert').textContent).toMatch(/This domain is in the bin/)
    expect(banner.textContent).toContain('Moved to the bin: 2026-09-01')
    expect(btn('View record')).toBeNull()
    expect(btn('Request a transfer')).toBeNull()
    fireEvent.click(btn('Restore from the bin'))
    await waitFor(() => expect(api.admin.restoreInventory).toHaveBeenCalledWith(4))
    await waitFor(() => expect(onResolved).toHaveBeenCalledWith({ kind: 'restored', domain: 'shop.example.com', record: { id: 4, domain: 'shop.example.com' } }))
  })

  it('çöp kutusu + AYNI takım + yetki YOK: yöneticiye yönlendirir, düğme yok', () => {
    show({ ...FOREIGN, team_id: 5, team_name: 'Takım A', deleted: true, same_team: true, can_view: false })
    expect(screen.getByRole('alert').textContent).toMatch(/ask your team's manager to restore it/)
    expect(btn('Restore from the bin')).toBeNull()
  })

  it('çöp kutusu + BAŞKA takım: yetkisize talep; global yöneticiye "Geri yükle ve aktar" (transfer → restore sırası)', async () => {
    const gone = { ...FOREIGN, deleted: true, deleted_at: '2026-09-01T00:00:00', can_view: false }
    const { unmount } = render(<DomainConflictBanner conflict={{ message: MSG, existing: gone }} targetTeam={TARGET} onResolved={() => {}} />)
    expect(btn('Request a transfer')).toBeInTheDocument()
    expect(btn('Restore from the bin')).toBeNull()
    unmount()

    api.admin.transferCertSy.mockResolvedValue({ success: true, data: { id: 2 } })
    api.admin.restoreInventory.mockResolvedValue({ success: true, data: { id: 2, deleted_at: null } })
    const { onResolved } = show({ ...gone, can_transfer: true, can_restore: true })
    fireEvent.click(btn("Restore and move to 'Takım A'"))
    await waitFor(() => expect(api.admin.restoreInventory).toHaveBeenCalledWith(2))
    expect(api.admin.transferCertSy).toHaveBeenCalledWith(2, 5)
    expect(api.admin.transferCertSy.mock.invocationCallOrder[0]).toBeLessThan(api.admin.restoreInventory.mock.invocationCallOrder[0])
    expect(confirmMock.mock.calls[0][0].message).toMatch(/restored from the bin and moved from 'Takım B' to 'Takım A'/)
    await waitFor(() => expect(onResolved).toHaveBeenCalledWith(expect.objectContaining({ kind: 'transferred', record: { id: 2, deleted_at: null } })))
  })

  it('AYNI takım (kayıt var): düzenleme yönlendirmesi; aktar/talep yok', () => {
    show({ ...FOREIGN, team_id: 5, team_name: 'Takım A', same_team: true, can_transfer: true })
    expect(screen.getByRole('alert').textContent).toMatch(/edit the existing record rather than creating a new one/)
    expect(btn(/^Move to/)).toBeNull()
    expect(btn('Request a transfer')).toBeNull()
    expect(btn('View record')).toBeInTheDocument()
  })
})
