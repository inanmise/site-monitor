import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    refreshCertificateHealth: vi.fn(),
    notificationGroups: { list: vi.fn().mockResolvedValue({ success: true, data: { groups: [{ id: 7, name: 'Ops' }] } }) },
    admin: {
      getInventory: vi.fn(), getTeams: vi.fn(), getInventoryHygiene: vi.fn(), importInventory: vi.fn(),
      updateInventory: vi.fn(), getAlerts: vi.fn(), bulkInventory: vi.fn(),
    },
  }),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../utils/exportInventory', () => ({ exportInventoryCsv: vi.fn(() => 0), exportInventoryPdf: vi.fn(async () => 0) }))
vi.mock('../components/history/ChangeHistoryTab.jsx', () => ({ default: () => <div>CHANGES-TAB</div> }))
vi.mock('../components/history/CheckHistoryTab.jsx', () => ({ default: ({ kind, monitorId }) => <div>CHECKS-TAB {kind} {monitorId}</div> }))
import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'

/**
 * Domain Envanteri zenginleştirme (2026-09-12): arama/süzgeç (#1), hijyen bandı (#2), canlı sertifika
 * sütunları (#3), sütun seçici (#4), içe aktarma (#6), takım görünümü (#7), çekmece (#8), satır-içi (#8),
 * şimdi kontrol et (#11), etiket çipi (#14).
 */
const ITEMS = [
  { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 1, cert_status: 'valid', cert_days_remaining: 120, cert_checked_at: '2026-09-12T10:00:00', svc_mgmt_contact: 'ops@example.com', netscaler: true, tags: 'pci,web', group_name: 'core' },
  { id: 2, domain: 'b.example.com', port: 443, active: true, team_id: 9, team_name: 'Takım B', tier: null, cert_status: 'error', cert_error: 'timeout', waf_enabled: true },
  { id: 3, domain: 'c.example.com', port: 8443, active: false, team_id: 5, team_name: 'Takım A', tier: 2, cert_status: 'warning', cert_days_remaining: 9 },
]
const HYGIENE = { success: true, data: { total: 3, scanned: 3, groups: [
  { key: 'missing', title: 'x', total: 1, findings: [{ domain: 'b.example.com', detail: 'tier', codes: ['no_tier'] }] },
  { key: 'contacts', title: 'y', total: 2, findings: [{ domain: 'b.example.com', detail: 'c', codes: ['no_contacts'] }, { domain: 'c.example.com', detail: 'c', codes: ['no_contacts'] }] },
] } }

const TEAMS = [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }]
const renderIm = (role = 'ADMIN') => render(<LangProvider><InventoryManager systemRole={role} teams={TEAMS} /></LangProvider>)
const rowsShown = () => [...document.querySelectorAll('tbody .inv-domain')].map((b) => b.textContent)

describe('Domain Envanteri — zenginleştirme', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yok */ }
    window.history.replaceState(null, '', '/')
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }] })
    api.admin.getInventoryHygiene.mockResolvedValue(HYGIENE)
    api.admin.updateInventory.mockResolvedValue({ success: true })
    api.refreshCertificateHealth.mockResolvedValue({ success: true })
  })

  it('#3 canlı sertifika sütunu: durum + kalan gün; #1 arama süzer ve sayaç "x / y" güncellenir; etiket çipi (#14) aramaya yazar', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    expect(screen.getByText(/^Valid$|^Geçerli$/)).toBeInTheDocument()
    expect(screen.getByText(/^Error$|^Hata$/)).toBeInTheDocument()
    expect(screen.getByText('120')).toBeInTheDocument()
    expect(document.querySelector('.invtb-count').textContent).toMatch(/3 (of|\/) 3/)

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'takım b' } })
    await waitFor(() => expect(rowsShown()).toEqual(['b.example.com']))
    expect(document.querySelector('.invtb-count').textContent).toMatch(/1 (of|\/) 3/)
    await waitFor(() => expect(window.location.search).toContain('i_q=tak'))

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: 'pci' }))
    await waitFor(() => expect(rowsShown()).toEqual(['a.example.com']))
  })

  it('#2 hijyen bandı: sayaçlar; tıklayınca listeyi süzer, URL i_hy taşır; tekrar tıklayınca kalkar', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    await screen.findByText(/Inventory hygiene|Envanter hijyeni/)
    const chip = await screen.findByRole('button', { name: /2 (no responsible contacts|sorumlu ekip girilmemiş)/ })
    fireEvent.click(chip)
    await waitFor(() => expect(rowsShown()).toEqual(['b.example.com', 'c.example.com']))
    await waitFor(() => expect(window.location.search).toContain('i_hy=no_contacts'))
    fireEvent.click(chip)
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
  })

  it('#2 hijyen bandı USER rolünde çizilmez ve uç çağrılmaz', async () => {
    renderIm('USER')
    await screen.findByText('a.example.com')
    expect(screen.queryByText(/Inventory hygiene|Envanter hijyeni/)).toBeNull()
    expect(api.admin.getInventoryHygiene).not.toHaveBeenCalled()
  })

  it('#4 sütun seçici: UG takımı sütunu açılır ve localStorage görünümüne yazılır; başlık tıklaması sıralar', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    expect(screen.queryByRole('columnheader', { name: /UG team|UG Takımı/i })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Columns|Sütunlar/ }))
    fireEvent.click(screen.getByLabelText(/UG team|UG Takımı/i))
    expect(screen.getByRole('columnheader', { name: /UG team|UG Takımı/i })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('inventory-view')).cols).toContain('ug_team')
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Days left|Kalan gün/ })).getByRole('button'))
    await waitFor(() => expect(rowsShown()[0]).toBe('c.example.com'))   // 9 gün en üstte
  })

  it('#8 satır-içi: aktif anahtarı PUT ile tam gövde + yama gönderir (cert_* alanları gövdeye girmez)', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    const row = screen.getByText('a.example.com').closest('tr')
    fireEvent.click(within(row).getByRole('checkbox', { name: /Active|Aktif/ }))
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    const [id, body] = api.admin.updateInventory.mock.calls[0]
    expect(id).toBe(1)
    expect(body.active).toBe(false)
    expect(body.domain).toBe('a.example.com')
    expect(body.cert_status).toBeUndefined()
    expect(body.team_name).toBeUndefined()
  })

  it('#11 şimdi kontrol et: sağlık tazeleme ucu domain ile çağrılır, liste yeniden yüklenir', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    const row = screen.getByText('a.example.com').closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: /Check now|Şimdi kontrol et/ }))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('a.example.com'))
    await waitFor(() => expect(api.admin.getInventory.mock.calls.length).toBeGreaterThan(1))
  })

  it('#8 çekmece: alan adına tıklayınca panel açılır; Kontroller sekmesi uptime-ssl geçmişini domain ile ister; sonraki kayıt oku', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByText('a.example.com'))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAttribute('aria-label', 'a.example.com')
    fireEvent.click(within(dlg).getByRole('button', { name: /^Checks$|^Kontroller$/ }))
    expect(await within(dlg).findByText('CHECKS-TAB uptime-ssl a.example.com')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Next record|Sonraki kayıt/ }))
    expect(within(screen.getByRole('dialog')).getByText('b.example.com')).toBeInTheDocument()
  })

  it('#7 takıma göre görünüm: takım başlıkları + sayaçlar; "Tabloda göster" takım süzgeci uygular', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('button', { name: /By team|Takıma göre/ }))
    expect(screen.getByText(/2 domains|2 alan/)).toBeInTheDocument()
    expect(screen.getByText(/1 with errors|1 hatalı/)).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: /Show in table|Tabloda göster/ })[1])
    await waitFor(() => expect(rowsShown()).toEqual(['b.example.com']))
    await waitFor(() => expect(window.location.search).toContain('i_team=9'))
  })

  it('#6 içe aktarma: yapıştırılan CSV önizlenir (dry_run=true), sonra işlenir (dry_run=false) ve liste yenilenir', async () => {
    api.admin.importInventory
      .mockResolvedValueOnce({ success: true, data: { dry_run: true, created: 1, updated: 0, skipped: 0, errors: 1, rows: [
        { line: 2, domain: 'new.example.com', action: 'create', reason: null, changes: ['domain', 'tier'] },
        { line: 3, domain: 'bad', action: 'error', reason: 'invalid_domain', changes: [] }] } })
      .mockResolvedValueOnce({ success: true, data: { dry_run: false, created: 1, updated: 0, skipped: 0, errors: 1, rows: [
        { line: 2, domain: 'new.example.com', action: 'create', reason: null, changes: ['domain', 'tier'] }] } })
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(screen.getByRole('button', { name: /^Import$|^İçe Aktar$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByPlaceholderText(/Paste CSV|CSV metnini/), { target: { value: 'domain,team,tier\nnew.example.com,Takım A,1\nbad,Takım A,1\n' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Preview|Önizle/ }))
    await waitFor(() => expect(api.admin.importInventory).toHaveBeenCalledWith([{ domain: 'new.example.com', team: 'Takım A', tier: '1' }, { domain: 'bad', team: 'Takım A', tier: '1' }], true))
    expect(await within(dlg).findByText(/invalid domain|geçersiz alan adı/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Import \(1\)|^İçe aktar \(1\)/ }))
    await waitFor(() => expect(api.admin.importInventory).toHaveBeenLastCalledWith(expect.any(Array), false))
    await waitFor(() => expect(api.admin.getInventory.mock.calls.length).toBeGreaterThan(1))
    expect(await within(dlg).findByText(/Import complete|İçe aktarma tamamlandı/)).toBeInTheDocument()
  })

  it('#13 kayıtlı görünüm: ad verip kaydet → localStorage; uygula → süzgeç geri gelir', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'pci' } })
    await waitFor(() => expect(rowsShown()).toEqual(['a.example.com']))
    fireEvent.click(screen.getByRole('button', { name: /^Views|^Görünümler/ }))
    fireEvent.change(screen.getByPlaceholderText(/View name|Görünüm adı/), { target: { value: 'PCI' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/ }))
    expect(JSON.parse(localStorage.getItem('inventory-saved-views'))[0]).toMatchObject({ name: 'PCI', filters: expect.objectContaining({ q: 'pci' }) })
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    // görünüm menüsü kayıttan sonra açık kalır → kayıtlı görünüme tıkla
    fireEvent.click(screen.getByRole('button', { name: 'PCI' }))
    await waitFor(() => expect(rowsShown()).toEqual(['a.example.com']))
  })
})
