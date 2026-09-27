import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * DOMAIN ENVANTERİ YENİDEN TASARIMI (2026-09-27, shadcn + mobil web).
 *
 * Sözleşmeler: PageHeader (başlık, açıklama, meta çipleri, eylemler) · özet kartları SÜZGEÇ (aria-pressed, tek seçim,
 * adres çubuğuna yazılır, veri desteklemeyen kart çizilmez) · fasetler + etkin süzgeç çipleri (tek tek kaldır, "Tümünü
 * temizle") · satır tıklaması / Enter çekmeceyi açar, ↑/↓ satırlar arasında gezer · telefonda KART listesi (tablo yok;
 * yabancı kayıt salt okunur) · çekmece sekmeleri + salt okunur altlık · form satır içi doğrulama + 409 satır içi ·
 * `domain` derin bağlantısı · sayfalama · ilk yükleme iskeleti / hata + yeniden dene.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const mobile = vi.hoisted(() => ({ value: false }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? '').slice(0, 10),
  api: withApiFallback({
    refreshCertificateHealth: vi.fn(),
    admin: {
      getInventory: vi.fn(), getTeams: vi.fn(), getAlerts: vi.fn(), bulkInventory: vi.fn(), deleteInventory: vi.fn(),
      getInventoryHygiene: vi.fn(), addInventory: vi.fn(), updateInventory: vi.fn(),
    },
  }),
}))
vi.mock('@/hooks/use-mobile', () => ({ useIsMobile: () => mobile.value }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../utils/exportInventory', () => ({ exportInventoryCsv: vi.fn(() => 0), exportInventoryPdf: vi.fn(async () => 0) }))
vi.mock('../components/history/ChangeHistoryTab.jsx', () => ({ default: () => <div>CHANGES-TAB</div> }))
vi.mock('../components/history/CheckHistoryTab.jsx', () => ({ default: ({ monitorId }) => <div>CHECKS-TAB {monitorId}</div> }))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'
import {
  TILES, tileCounts, activeTile, applyTile, facetCounts, activeFilterChips, removeFilterChip, EMPTY_FILTERS, applyFilters,
} from '../components/inventory/inventoryModel.js'

const ITEMS = [
  { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, team_name: 'Takım A', tier: 1, cert_status: 'valid', cert_days_remaining: 120,
    cert_not_after: '2027-01-24T00:00:00', cert_issuer: 'Example CA', cert_checked_at: '2026-09-27T08:00:00', svc_mgmt_contact: 'Ayşe Örnek - ayse@example.com',
    platform: 'IIS', group_name: 'core', tags: 'pci,web', can_manage: true, description: 'Kurumsal site' },
  { id: 2, domain: 'b.example.com', port: 8443, active: true, team_id: 5, team_name: 'Takım A', tier: 2, cert_status: 'warning', cert_days_remaining: 12,
    cert_not_after: '2026-10-09T00:00:00', platform: 'OpenShift', group_name: 'core', tags: 'api', can_manage: true },
  { id: 3, domain: 'c.example.com', port: 443, active: false, team_id: 5, team_name: 'Takım A', tier: 3, cert_status: 'warning', cert_days_remaining: -2,
    platform: null, can_manage: true },
  { id: 4, domain: 'foreign.example.com', port: 443, active: true, team_id: 9, team_name: 'Takım B', tier: 1, cert_status: 'error', cert_days_remaining: null,
    cert_error: 'timeout', platform: 'IIS', can_manage: false },
  { id: 5, domain: 'gone.example.com', port: 443, active: false, team_id: 5, team_name: 'Takım A', deleted_at: '2026-09-20T10:00:00', can_manage: true },
]
const TEAMS = [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }]
const renderIm = (role = 'TEAM_ADMIN') => render(<LangProvider><InventoryManager systemRole={role} teams={TEAMS} /></LangProvider>)
const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const rowsShown = () => [...document.querySelectorAll('tbody [data-inv-row]')].map((r) => r.getAttribute('data-inv-row'))

beforeEach(() => {
  vi.clearAllMocks()
  mobile.value = false
  localStorage.clear()
  window.history.replaceState(null, '', '/?tab=domains')
  api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS, scope: 'mine', visible_to_all: true })
  api.admin.getTeams.mockResolvedValue({ success: true, data: TEAMS })
  api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
  api.admin.getInventoryHygiene.mockResolvedValue({ success: true, data: { total: 0, scanned: 5, groups: [] } })
  api.refreshCertificateHealth.mockResolvedValue({ success: true })
})
afterEach(() => { mobile.value = false })

describe('Envanter yeniden tasarımı — başlık ve özet kartları', () => {
  it('PageHeader: başlık + açıklama + meta çipleri (canlı alan sayısı, takım sayısı, son yenileme) + eylemler; Yenile listeyi yeniden çeker', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    expect(screen.getByRole('heading', { level: 2, name: /Domain Envanteri|Domain Inventory/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="page-description"]').textContent.length).toBeGreaterThan(20)
    expect(document.querySelector('[data-slot="inv-meta-domains"]')).toHaveTextContent(/^4 /)   // silinmiş sayılmaz
    expect(document.querySelector('[data-slot="inv-meta-teams"]')).toHaveTextContent(/^2 /)
    expect(document.querySelector('[data-slot="inv-meta-sync"]')).not.toBeNull()
    const actions = within(document.querySelector('[data-slot="page-actions"]'))
    expect(actions.getByRole('button', { name: /Domain Ekle|Add Domain/ })).toBeInTheDocument()
    expect(actions.getByRole('button', { name: /İçe Aktar|Import/ })).toBeInTheDocument()
    fireEvent.click(actions.getByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenCalledTimes(2))
    // App'in "Şimdi Kontrol Et" akışı verilmezse başlıkta düğme yok (eski üst .controls satırı kalktı, 2026-09-27)
    expect(document.querySelector('[data-tour="check-now"]')).toBeNull()
  })

  it('onCheckNow verilince "Şimdi Kontrol Et" başlıkta, birincil "Domain ekle"nin HEMEN solunda ikincil; koşarken ilerleme + meşgul', async () => {
    const onCheckNow = vi.fn()
    const { rerender } = render(<LangProvider><InventoryManager systemRole="TEAM_ADMIN" teams={TEAMS} onCheckNow={onCheckNow} /></LangProvider>)
    await screen.findByText('a.example.com')
    const bar = document.querySelector('[data-slot="page-actions"]')
    const btn = within(bar).getByRole('button', { name: /^(Şimdi Kontrol Et|Check Now)$/ })
    expect(btn).toHaveAttribute('data-tour', 'check-now')
    expect(btn).toHaveAttribute('data-variant', 'secondary')
    expect(btn.nextElementSibling).toHaveAccessibleName(/Domain Ekle|Add Domain/)
    fireEvent.click(btn)
    expect(onCheckNow).toHaveBeenCalledTimes(1)
    rerender(<LangProvider><InventoryManager systemRole="TEAM_ADMIN" teams={TEAMS} onCheckNow={onCheckNow} checkRunning checkLabel="3/11 Checked..." /></LangProvider>)
    const running = within(bar).getByRole('button', { name: '3/11 Checked...' })
    expect(running).toBeDisabled()
    expect(running).toHaveAttribute('aria-busy', 'true')
  })

  it('kart = süzgeç: "30 gün altı" basılınca yalnız 0–30 gün kalanlar, aria-pressed + URL i_days=30; yeniden basınca kalkar', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    expect(tile('expiring')).toHaveAttribute('aria-pressed', 'false')
    expect(tile('expiring').querySelector('[data-slot="stat-value"]')).toHaveTextContent('1')
    fireEvent.click(tile('expiring'))
    await waitFor(() => expect(rowsShown()).toEqual(['b.example.com']))
    expect(tile('expiring')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('i_days')).toBe('30'))
    fireEvent.click(tile('expiring'))
    await waitFor(() => expect(rowsShown()).toHaveLength(4))
    expect(tile('expiring')).toHaveAttribute('aria-pressed', 'false')
  })

  it('kartlar arasında TEK seçim: "Hatalı" → "T1 kritik" öncekini geri alır; "Silinmiş" çöp kutusu görünümüne geçer; "Toplam" kart süzgecini kaldırır', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(tile('errors'))
    await waitFor(() => expect(rowsShown()).toEqual(['foreign.example.com']))
    fireEvent.click(tile('tier1'))
    await waitFor(() => expect(rowsShown()).toEqual(['a.example.com', 'foreign.example.com']))
    expect(tile('errors')).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(tile('deleted'))
    await waitFor(() => expect(rowsShown()).toEqual(['gone.example.com']))
    fireEvent.click(tile('total'))
    await waitFor(() => expect(rowsShown()).toHaveLength(4))
    expect(tile('total')).not.toHaveAttribute('aria-pressed')   // eylem kartı
  })

  it('veri desteklemeyen kart çizilmez: hiç platform / katman / pasif / silinmiş yoksa o kartlar yok', async () => {
    api.admin.getInventory.mockResolvedValue({ success: true, data: [{ id: 1, domain: 'x.example.com', active: true, team_id: 5, cert_status: 'valid', cert_days_remaining: 90 }] })
    renderIm()
    await screen.findByText('x.example.com')
    expect(tile('total')).not.toBeNull()
    for (const k of ['noPlatform', 'tier1', 'inactive', 'deleted']) expect(tile(k), k).toBeNull()
  })
})

describe('Envanter yeniden tasarımı — araç çubuğu ve tablo', () => {
  it('faset seçimi çip üretir; çipin × düğmesi yalnız o süzgeci kaldırır; "Tümünü temizle" hepsini', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(within(document.querySelector('[data-slot="inv-facets"]')).getByRole('button', { name: /Kritiklik|Tier/ }))
    fireEvent.click(await screen.findByRole('option', { name: /T2/ }))
    await waitFor(() => expect(rowsShown()).toEqual(['b.example.com']))
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    const chips = () => [...document.querySelectorAll('[data-slot="inv-chip"]')]
    expect(chips().map((c) => c.getAttribute('data-key'))).toEqual(['tier'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'example' } })
    await waitFor(() => expect(chips().map((c) => c.getAttribute('data-key')).sort()).toEqual(['q', 'tier']))
    fireEvent.click(within(chips().find((c) => c.getAttribute('data-key') === 'tier')).getByRole('button'))
    await waitFor(() => expect(rowsShown()).toHaveLength(4))
    expect(chips().map((c) => c.getAttribute('data-key'))).toEqual(['q'])
    fireEvent.click(screen.getByRole('button', { name: /Tümünü temizle|Clear all/ }))
    await waitFor(() => expect(chips()).toHaveLength(0))
  })

  it('sıralanabilir başlık aria-sort taşır; satır tıklaması ve Enter çekmeceyi açar; ↓ bir sonraki satıra odaklanır', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    const domainHead = screen.getByRole('columnheader', { name: /Domain/ })
    expect(domainHead).toHaveAttribute('aria-sort', 'ascending')
    fireEvent.click(within(domainHead).getByRole('button'))
    expect(domainHead).toHaveAttribute('aria-sort', 'descending')
    const rowA = document.querySelector('[data-inv-row="a.example.com"]')
    rowA.focus()
    fireEvent.keyDown(rowA, { key: 'ArrowUp' })   // azalan: foreign, c, b, a → a'dan yukarı b
    expect(document.activeElement).toHaveAttribute('data-inv-row', 'b.example.com')
    fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' })
    expect(document.activeElement).toHaveAttribute('data-inv-row', 'c.example.com')
    fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' })
    expect(document.activeElement).toHaveAttribute('data-inv-row', 'b.example.com')
    fireEvent.keyDown(document.activeElement, { key: 'Enter' })
    // Sheet'in adı başlıktan (alan adı + 443 dışı port rozeti)
    expect(await screen.findByRole('dialog', { name: /^b\.example\.com/ })).toBeInTheDocument()
  })

  it('varsayılan sütunlar: durum rozeti "Kalan gün" hücresinde (ayrı Sertifika sütunu yok); grup çipi alan adı altında ve grup süzgecini uygular', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    expect(screen.queryByRole('columnheader', { name: /^(Sertifika|Certificate)$/ })).toBeNull()
    const rowErr = document.querySelector('[data-inv-row="foreign.example.com"]')
    expect(rowErr.querySelector('[data-slot="inv-expiry"] [data-slot="inv-cert"]')).toHaveAttribute('data-tone', 'err')   // gün yok → "Hata" rozeti
    const rowA = document.querySelector('[data-inv-row="a.example.com"]')
    expect(rowA.querySelector('[data-slot="inv-expiry"] [data-slot="inv-cert"]')).toHaveAttribute('data-tone', 'ok')
    fireEvent.click(within(rowA).getByRole('button', { name: 'core' }))
    await waitFor(() => expect(rowsShown()).toEqual(['a.example.com', 'b.example.com']))
    expect(document.querySelector('[data-slot="inv-chip"][data-key="group"]')).not.toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()   // çip tıklaması satırı (çekmeceyi) açmaz
  })

  it('telefonda kayıtlı tercih yoksa "Özet" kapalı başlar; açınca kartlar görünür ve tercih hatırlanır', async () => {
    const orig = window.matchMedia
    window.matchMedia = (q) => ({ ...orig(q), matches: q.includes('max-width: 767px') })
    try {
      mobile.value = true
      renderIm()
      await screen.findByText('a.example.com')
      const toggle = document.querySelector('[data-slot="stats-toggle"]')
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(tile('total')).toBeNull()
      fireEvent.click(toggle)
      expect(tile('total')).not.toBeNull()
      expect(localStorage.getItem('inv-stats-open')).toBe('true')
    } finally { window.matchMedia = orig }
  })

  it('443 dışı port alan adı hücresinde rozet; bitiş göreli metin; sorumlular sayaç adıyla', async () => {
    renderIm()
    await screen.findByText('a.example.com')
    const rowB = document.querySelector('[data-inv-row="b.example.com"]')
    expect(within(rowB).getByText(':8443')).toBeInTheDocument()
    expect(rowB.querySelector('[data-slot="inv-expiry"]')).toHaveAttribute('data-tone', 'warn')
    expect(within(rowB).getByText(/12 gün içinde|in 12 days/)).toBeInTheDocument()
    const rowA = document.querySelector('[data-inv-row="a.example.com"]')
    expect(within(rowA).getByRole('button', { name: /1\/4 sorumlu|1 of 4 contacts/ })).toBeInTheDocument()
  })
})

describe('Envanter yeniden tasarımı — telefon kart listesi', () => {
  it('telefonda tablo YOK, kart listesi var; yabancı kart salt okunur (kutu / anahtar / kontrol et yok, rozet var, menüde yalnız "Göster")', async () => {
    mobile.value = true
    renderIm()
    await screen.findByText('a.example.com')
    expect(document.querySelector('[data-slot="inv-table"]')).toBeNull()
    const cards = document.querySelector('[data-slot="inv-cards"]')
    expect(cards).not.toBeNull()
    const foreign = cards.querySelector('[data-inv-card="foreign.example.com"]')
    const own = cards.querySelector('[data-inv-card="a.example.com"]')
    expect(foreign.querySelector('[role="checkbox"]')).toBeNull()
    expect(foreign.querySelector('[role="switch"]')).toBeNull()
    expect(within(foreign).queryByRole('button', { name: /kontrol et|check now/i })).toBeNull()
    expect(foreign.querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
    expect(own.querySelector('[role="checkbox"]')).not.toBeNull()
    expect(within(own).getByRole('button', { name: /kontrol et|check now/i })).toBeInTheDocument()
    pressMenuTrigger(within(foreign).getByRole('button', { name: /işlem|actions/i }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((m) => m.textContent.trim())).toEqual([expect.stringMatching(/^(Göster|Show)$/)])
  })

  it('telefonda kart seçimi toplu çubuğu açar; çubuktaki "tümünü seç" yalnız yönetilebilir kayıtları alır', async () => {
    mobile.value = true
    renderIm()
    await screen.findByText('a.example.com')
    fireEvent.click(within(document.querySelector('[data-inv-card="a.example.com"]')).getByRole('checkbox'))
    const bar = await screen.findByRole('region', { name: /seçili|selected/i })
    fireEvent.click(within(bar).getByRole('button', { name: /Tümünü seç|Select all/i }))
    await waitFor(() => expect(bar).toHaveAttribute('aria-label', expect.stringMatching(/^3 /)))
  })
})

describe('Envanter yeniden tasarımı — çekmece', () => {
  it('sekmeler Genel bakış / Sertifika / Değişiklikler / Kontroller; Sertifika özeti + "Sertifikayı aç" Tüm Sertifikalar\'a gider; altlıkta kontrol et / düzenle / sil', async () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    try {
      renderIm()
      fireEvent.click(await screen.findByRole('button', { name: 'a.example.com' }))
      const dlg = await screen.findByRole('dialog', { name: 'a.example.com' })
      expect(within(dlg).getAllByRole('tab').map((t) => t.textContent)).toEqual([
        expect.stringMatching(/Genel bakış|Overview/), expect.stringMatching(/Sertifika|Certificate/),
        expect.stringMatching(/Değişiklikler|Changes/), expect.stringMatching(/Kontroller|Checks/),
      ])
      expect(within(dlg).getByRole('link', { name: 'ayse@example.com' })).toHaveAttribute('href', 'mailto:ayse@example.com')
      pressMenuTrigger(within(dlg).getByRole('tab', { name: /^(Sertifika|Certificate)$/ }))
      const summary = await within(dlg).findByText('Example CA')
      expect(summary.closest('[data-slot="inv-cert-summary"]')).not.toBeNull()
      fireEvent.click(within(dlg).getByRole('button', { name: /Sertifikayı aç|Open certificate/ }))
      expect(nav).toHaveBeenCalled()
      expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'all', params: { domain: 'a.example.com' } })
      const footer = dlg.querySelector('[data-slot="inv-drawer-actions"]')
      expect(within(footer).getByRole('button', { name: /kontrol et|check now/i })).toBeInTheDocument()
      expect(within(footer).getByRole('button', { name: /(Düzenle|Edit)$/ })).toBeInTheDocument()
      fireEvent.click(within(footer).getByRole('button', { name: /(Sil|Delete)$/ }))
      await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalledWith(1))
    } finally { window.removeEventListener('sm:navigate', nav) }
  })

  it('yabancı kaydın çekmecesinde altlık eylemleri YOK', async () => {
    renderIm()
    fireEvent.click(await screen.findByRole('button', { name: 'foreign.example.com' }))
    const dlg = await screen.findByRole('dialog', { name: 'foreign.example.com' })
    expect(dlg.querySelector('[data-slot="inv-drawer-actions"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="read-only-badge"]')).not.toBeNull()
  })

  it('`domain` derin bağlantısı çekmeceyi açar; kapatınca adresten düşer', async () => {
    window.history.replaceState(null, '', '/?tab=domains&domain=B.example.com')
    renderIm()
    const dlg = await screen.findByRole('dialog', { name: /^b\.example\.com/ })   // ad başlıktan: alan adı + :8443 rozeti
    // Açık çekmece adrese KAYITLI biçimiyle yazılır (paylaşılan bağlantı aynı kaydı açsın)
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('domain')).toBe('b.example.com'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Kapat|Close)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('domain')).toBeNull())
    expect(new URLSearchParams(window.location.search).get('tab')).toBe('domains')   // uygulamanın anahtarına dokunulmaz
  })
})

describe('Envanter yeniden tasarımı — yükleme, hata, sayfalama', () => {
  it('ilk yükleme iskelet (role=status) gösterir; yükleme düşerse hata bloğu + "Yeniden dene"', async () => {
    let resolve
    api.admin.getInventory.mockImplementationOnce(() => new Promise((r) => { resolve = r }))
    renderIm()
    expect(document.querySelector('[data-slot="inv-skeleton"]')).toHaveAttribute('role', 'status')
    resolve({ success: false, error: 'kapalı' })
    const block = await screen.findByRole('alert')
    expect(block).toHaveAttribute('data-tone', 'danger')
    fireEvent.click(within(block).getByRole('button', { name: /Yeniden dene|Try again/ }))
    expect(await screen.findByText('a.example.com')).toBeInTheDocument()
  })

  it('sayfalama: 55 kayıt → 50 + 5; sonraki sayfa adrese page=2 yazar', async () => {
    const many = Array.from({ length: 55 }, (_, i) => ({ id: 100 + i, domain: `s${String(i).padStart(2, '0')}.example.com`, active: true, team_id: 5, team_name: 'Takım A', can_manage: true }))
    api.admin.getInventory.mockResolvedValue({ success: true, data: many })
    renderIm()
    await screen.findByText('s00.example.com')
    expect(rowsShown()).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: /^(Sonraki|Next)$/ }))
    await waitFor(() => expect(rowsShown()).toHaveLength(5))
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('page')).toBe('2'))
  })
})

describe('Envanter yeniden tasarımı — ekle/düzenle formu', () => {
  const renderForm = (props = {}) => render(<LangProvider><InventoryFormModal mode="add" teams={[TEAMS[0]]} canManage onClose={() => {}} onSaved={() => {}} {...props} /></LangProvider>)
  const saveBtn = () => screen.getByRole('button', { name: /^Kaydet$|^Save$/i })

  it('gruplu bölümler: Kimlik / Sahiplik ve sorumlular / Platform… / Gruplama… / Operasyonel / Notlar', () => {
    renderForm()
    const heads = [...document.querySelectorAll('[data-slot="inv-form-section"]')].map((h) => h.textContent)
    expect(heads).toEqual([
      expect.stringMatching(/Kimlik|Identity/), expect.stringMatching(/Sahiplik|Ownership/), expect.stringMatching(/Platform/),
      expect.stringMatching(/Gruplama|Grouping/), expect.stringMatching(/Operasyonel|Operational/), expect.stringMatching(/Notlar|Notes/),
    ])
  })

  it('satır içi doğrulama: şemalı adres alan adı kutusunun altında "geçerli bir alan adı" der ve kayıt gitmez', async () => {
    renderForm({ record: null })
    const domain = screen.getByRole('textbox', { name: /^Domain/ })
    fireEvent.change(domain, { target: { value: 'https://www.example.com/' } })
    fireEvent.blur(domain)
    expect(await screen.findByText(/Geçerli bir alan adı girin|Enter a valid domain/)).toBeInTheDocument()
    expect(domain).toHaveAttribute('aria-invalid', 'true')
    fireEvent.click(saveBtn())
    expect(api.admin.addInventory).not.toHaveBeenCalled()
  })

  it('409 mükerrer alan adı: sunucu iletisi alan adı kutusunun altında (satır içi) + özet bantta; toast YOK, form açık', async () => {
    api.admin.updateInventory.mockResolvedValueOnce({ success: false, status: 409, error: 'This domain is already in the inventory.' })
    const onSaved = vi.fn()
    renderForm({ mode: 'edit', onSaved, record: { id: 7, domain: 'dup.example.com', port: 443, team_id: 5, group_name: 'core', tags: 'web' } })
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.admin.updateInventory).toHaveBeenCalled())
    expect(await screen.findAllByText('This domain is already in the inventory.')).toHaveLength(2)
    expect(screen.getByRole('textbox', { name: /^Domain/ })).toHaveAttribute('aria-invalid', 'true')
    expect(toastMock.error).not.toHaveBeenCalled()
    expect(onSaved).not.toHaveBeenCalled()
  })
})

describe('inventoryModel — özet kartları, fasetler, çipler (saf)', () => {
  const live = ITEMS.filter((r) => !r.deleted_at)
  it('tileCounts her kartı applyFilters ile AYNI kuralla sayar (kart sayısı = kart basılınca görünen satır sayısı)', () => {
    const c = tileCounts(ITEMS)
    expect(c).toMatchObject({ total: 4, active: 3, inactive: 1, valid: 1, expiring: 1, expired: 1, errors: 1, noContacts: 3, noPlatform: 1, tier1: 2, deleted: 1 })
    for (const tl of TILES.filter((x) => x.filters)) {
      expect(applyFilters(live, { ...EMPTY_FILTERS, ...tl.filters }).length, tl.key).toBe(c[tl.key])
    }
  })
  it('activeTile / applyTile: tek seçim, aynı karta ikinci basış geri alır, "total" hepsini kaldırır, kart dışı süzgece dokunmaz', () => {
    const base = { ...EMPTY_FILTERS, q: 'x' }
    let s = applyTile('expired', 'default', base)
    expect(s.filters).toMatchObject({ days: 'expired', q: 'x' })
    expect(activeTile(s.statusFilter, s.filters)).toBe('expired')
    s = applyTile('inactive', s.statusFilter, s.filters)
    expect(s).toMatchObject({ statusFilter: 'inactive', filters: { days: '', q: 'x' } })
    s = applyTile('inactive', s.statusFilter, s.filters)
    expect(s.statusFilter).toBe('default')
    s = applyTile('total', 'deleted', { ...base, tier: '1' })
    expect(s).toMatchObject({ statusFilter: 'default', filters: { tier: '', q: 'x' } })
  })
  it('facetCounts + çipler: sayaçlar durum listesinden; bayrak çipi tek bayrağı kaldırır', () => {
    const f = facetCounts(live)
    expect(f.tier).toMatchObject({ 1: 2, 2: 1, 3: 1 })
    expect(f.platform).toMatchObject({ IIS: 2, OpenShift: 1, none: 1 })
    expect(f.cert.problem).toBe(3)
    const filters = { ...EMPTY_FILTERS, tier: '1', flags: ['netscaler', 'waf_enabled'] }
    const chips = activeFilterChips(filters)
    expect(chips).toEqual([{ key: 'tier', value: '1' }, { key: 'flags', value: 'netscaler' }, { key: 'flags', value: 'waf_enabled' }])
    expect(removeFilterChip(filters, chips[1]).flags).toEqual(['waf_enabled'])
    expect(removeFilterChip(filters, chips[0]).tier).toBe('')
  })
})
