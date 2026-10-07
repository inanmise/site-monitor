import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * ORG GENELİ ENVANTER GÖRÜNÜRLÜĞÜ (kullanıcı kararı 2026-09-26) — Envanter ekranı.
 *
 * Sözleşme: "Takımlarım | Tüm takımlar" anahtarı YALNIZ sunucu `visible_to_all` derse çizilir; seçim isteğe
 * (`scope`), adrese (`i_scope`) ve kayıtlı görünüme (inventory-view.scope) yazılır; başka takımın satırında
 * (`can_manage:false`) HİÇBİR değiştiren kontrol yoktur (kutu, kontrol et, satır-içi düzenleme, anahtar, menü
 * eylemleri), salt okunur rozet vardır ve satır toplu işleme seçilemez; çekmece salt okunur açılır (düzenle /
 * kontrol et / "Değişiklikler" sekmesi yok). Takım paneli: metin özet + tablo/kart, bayrak ikon kümesi yok.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    refreshCertificateHealth: vi.fn(),
    admin: { getInventory: vi.fn(), getTeams: vi.fn(), getAlerts: vi.fn(), bulkInventory: vi.fn(), deleteInventory: vi.fn() },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../utils/exportInventory', () => ({ exportInventoryCsv: vi.fn(() => 0), exportInventoryPdf: vi.fn(async () => 0) }))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'
import InventoryTeamView from '../components/inventory/InventoryTeamView.jsx'

const OWN = [
  { id: 1, domain: 'own-a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 1, cert_status: 'valid', cert_days_remaining: 120,
    cert_not_after: '2027-01-24T00:00:00', svc_mgmt_contact: 'Servis - servis@example.com', netscaler: true, can_manage: true },
  { id: 2, domain: 'own-b.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 2, cert_status: 'warning', cert_days_remaining: 20, can_manage: true },
]
const FOREIGN = [
  { id: 9, domain: 'foreign.example.com', port: 8443, active: true, team_id: 9, team_name: 'Takım B', tier: 3, cert_status: 'error', cert_days_remaining: null,
    app_dev_contact: 'Geliştirme - dev@example.com', waf_enabled: true, platform: 'IIS', can_manage: false },
]

function stubInventory({ visible = true } = {}) {
  api.admin.getInventory.mockImplementation((scope) => Promise.resolve({
    success: true, data: scope === 'all' && visible ? [...OWN, ...FOREIGN] : OWN, scope: scope === 'all' && visible ? 'all' : 'mine', visible_to_all: visible,
  }))
}
const renderIm = (role = 'TEAM_ADMIN') => render(<LangProvider><InventoryManager systemRole={role} teams={[{ id: 5, name: 'Takım A' }]} /></LangProvider>)
const scopeGroup = () => screen.queryByRole('group', { name: /görünürlük kapsamı|visible teams/i })
const allTeamsBtn = () => within(scopeGroup()).getByRole('button', { name: /tüm takımlar|all teams/i })
const rowOf = async (domain) => (await screen.findByText(domain)).closest('tr')

describe('Envanter — org geneli görünürlük', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    stubInventory()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
  })

  it('anahtar YALNIZ sunucu visible_to_all derse çizilir; varsayılan "Takımlarım" ve istek scope=mine', async () => {
    renderIm()
    await screen.findByText('own-a.example.com')
    expect(api.admin.getInventory).toHaveBeenCalledWith('mine')
    expect(scopeGroup()).not.toBeNull()
    expect(within(scopeGroup()).getByRole('button', { name: /takımlarım|my teams/i })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText('foreign.example.com')).toBeNull()
  })

  it('ayar kapalı (visible_to_all=false) → anahtar YOK, liste bugünkü gibi', async () => {
    stubInventory({ visible: false })
    renderIm()
    await screen.findByText('own-a.example.com')
    expect(scopeGroup()).toBeNull()
  })

  it('"Tüm takımlar" → scope=all ile yeniden çekilir, adrese i_scope=all yazılır, kayıtlı görünüm hatırlar; yeniden açılışta aynı kapsam', async () => {
    const { unmount } = renderIm()
    await screen.findByText('own-a.example.com')
    fireEvent.click(allTeamsBtn())
    expect(await screen.findByText('foreign.example.com')).toBeInTheDocument()
    expect(api.admin.getInventory).toHaveBeenLastCalledWith('all')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('i_scope')).toBe('all'))
    expect(JSON.parse(localStorage.getItem('inventory-view')).scope).toBe('all')
    unmount()
    window.history.replaceState(null, '', '/')
    renderIm()
    await screen.findByText('foreign.example.com')
    expect(api.admin.getInventory).toHaveBeenLastCalledWith('all')
  })

  it('adresteki i_scope=all kayıtlı görünümü EZER (paylaşılan bağlantı kazanır)', async () => {
    localStorage.setItem('inventory-view', JSON.stringify({ scope: 'mine' }))
    window.history.replaceState(null, '', '/?tab=domains&i_scope=all')
    renderIm()
    await screen.findByText('foreign.example.com')
    expect(api.admin.getInventory).toHaveBeenLastCalledWith('all')
  })

  it('yabancı satır SALT OKUNUR: kutu / kontrol et / satır-içi katman / aktif anahtarı yok, rozet var, menüde yalnız "Göster"; kendi satırı değişmez', async () => {
    renderIm()
    await screen.findByText('own-a.example.com')
    fireEvent.click(allTeamsBtn())
    const foreign = await rowOf('foreign.example.com')
    const own = await rowOf('own-a.example.com')

    expect(foreign.querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
    expect(foreign.querySelector('td:first-child [role="checkbox"]')).toBeNull()
    expect(foreign.querySelector('[role="switch"]')).toBeNull()
    expect(within(foreign).queryByRole('button', { name: /kontrol et|check now/i })).toBeNull()
    expect(foreign.querySelector('[data-inv-tier-edit]')).toHaveAttribute('disabled')   // satır-içi katman düzenleme kapalı

    expect(own.querySelector('[data-slot="read-only-badge"]')).toBeNull()
    expect(own.querySelector('td:first-child [role="checkbox"]')).not.toBeNull()
    expect(own.querySelector('[role="switch"]')).not.toBeNull()
    expect(within(own).getByRole('button', { name: /kontrol et|check now/i })).toBeInTheDocument()

    pressMenuTrigger(within(foreign).getByRole('button', { name: /işlem|actions/i }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((m) => m.textContent.trim())).toEqual([expect.stringMatching(/^(Göster|Show)$/)])
  })

  it('"tümünü seç" yabancı satırı ATLAR: toplu çubuk yalnız kendi kayıtlarını sayar', async () => {
    renderIm()
    await screen.findByText('own-a.example.com')
    fireEvent.click(allTeamsBtn())
    await screen.findByText('foreign.example.com')
    fireEvent.click(screen.getByRole('checkbox', { name: /tümünü seç|select all/i }))
    const bar = await screen.findByRole('region', { name: /seçili|selected/i })
    expect(bar).toHaveAttribute('aria-label', expect.stringMatching(/^2 /))
    fireEvent.click(within(bar).getByRole('button', { name: /pasif|deactivate/i }))
    await waitFor(() => expect(api.admin.bulkInventory).toHaveBeenCalledWith([1, 2], 'deactivate'))
  })

  it('yabancı kaydın çekmecesi salt okunur: rozet + sahibi takım, düzenle / kontrol et yok, "Değişiklikler" sekmesi yok', async () => {
    renderIm()
    await screen.findByText('own-a.example.com')
    fireEvent.click(allTeamsBtn())
    const foreign = await rowOf('foreign.example.com')
    fireEvent.click(within(foreign).getByRole('button', { name: 'foreign.example.com' }))
    const dlg = await screen.findByRole('dialog')   // Sheet adı SheetTitle'dan gelir (alan adı + rozetler)
    expect(within(dlg).getAllByText('foreign.example.com').length).toBeGreaterThan(0)   // başlık + ayrıntı alanı
    expect(within(dlg).getByText(/salt okunur|read only/i)).toBeInTheDocument()
    expect(within(dlg).getAllByText('Takım B').length).toBeGreaterThan(0)
    expect(within(dlg).queryByRole('button', { name: /düzenle|edit/i })).toBeNull()
    expect(within(dlg).queryByRole('button', { name: /kontrol et|check now/i })).toBeNull()
    expect(within(dlg).queryByRole('tab', { name: /değişiklik|changes/i })).toBeNull()
    expect(within(dlg).getByRole('tab', { name: /kontroller|checks/i })).toBeInTheDocument()
  })
})

describe('Envanter — takıma göre panel (2026-09-26 yeniden tasarım)', () => {
  // Süresi dolmuş kayıt tel biçiminde: durum 'warning' + negatif kalan gün ('error' yalnız bağlantı hatasıdır)
  const rows = [...OWN, ...FOREIGN, { id: 3, domain: 'expired.example.com', team_id: 5, team_name: 'Takım A', tier: 4, cert_status: 'warning', cert_days_remaining: -3, can_manage: true }]
  const renderTv = () => render(<LangProvider><InventoryTeamView rows={rows} onShow={vi.fn()} onFilterTeam={vi.fn()} platformNames={{ IIS: 'Microsoft IIS' }} /></LangProvider>)

  it('grup başlığı: takım, alan sayısı ve METİN durum özeti (geçerli / uyarı / 30 gün altı / dolmuş / hatalı / sorumlusuz)', () => {
    renderTv()
    const a = document.querySelector('[data-team-group="5"] [data-slot="inv-team-summary"]')
    const text = a.textContent
    expect(text).toMatch(/1 (geçerli|valid)/)
    expect(text).toMatch(/1 (uyarı|warning)/)
    expect(text).toMatch(/1 .*(30 gün altı|under 30 days)/)
    expect(text).toMatch(/1 (süresi dolmuş|expired)/)
    expect(text).toMatch(/2 (sorumlusuz|without contacts)/)
    expect(document.querySelector('[data-team-group="9"] [data-slot="read-only-badge"]')).not.toBeNull()
    expect(document.querySelector('[data-team-group="5"] [data-slot="read-only-badge"]')).toBeNull()
  })

  it('açılan panel: başlıklı tablo (alan · sertifika · kalan gün · bitiş · katman · platform · sorumlular) + telefon kartları; bayrak ikon kümesi YOK', () => {
    renderTv()
    fireEvent.click(screen.getByRole('button', { name: /Takım B grubunu|collapse Takım B/ }))   // aç/kapat düğmesi (TeamBadge değil)
    const grp = document.querySelector('[data-team-group="9"]')
    const heads = [...grp.querySelectorAll('thead th')].map((th) => th.textContent.trim())
    expect(heads).toHaveLength(7)
    expect(heads.join('|')).toMatch(/bitiş tarihi|expiry date/i)
    const row = grp.querySelector('tbody [data-inv-team-row="foreign.example.com"]')
    expect(row.textContent).toMatch(/Microsoft IIS/)
    expect(row.querySelector('[data-slot="inv-cert"]').textContent).toMatch(/hata|error/i)
    expect(row.querySelector('[data-slot="inv-tv-contacts"]')).toHaveAttribute('data-count', '1')
    expect(grp.querySelector('[data-slot="inv-flags"]')).toBeNull()
    expect(grp.querySelector('[data-slot="inv-team-cards"] [data-inv-team-row="foreign.example.com"]')).not.toBeNull()
    // Sorumlusu olmayan kayıt bunu AÇIKÇA yazar (rozet/ikon yerine metin)
    fireEvent.click(screen.getByRole('button', { name: /Takım A grubunu|collapse Takım A/ }))
    expect(document.querySelector('[data-team-group="5"] [data-inv-team-row="own-b.example.com"] [data-slot="inv-tv-contacts"]').textContent).toMatch(/sorumlu yok|no contacts/i)
  })
})
