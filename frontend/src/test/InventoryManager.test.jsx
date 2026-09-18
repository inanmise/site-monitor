import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * ENVANTER YÖNETİMİ — uygulamanın GERİ ALINAMAZ eylemlerini barındıran ekran; buraya kadar kendi
 * testi yoktu (AdminPanel.test.jsx bileşeni vi.mock ile değiştiriyor → "dolaylı kapsam" sahte).
 *
 * Buradaki hatalar sessiz ve kalıcı:
 *  - "Kalıcı Sil" envanteri VE o domainin tüm kontrol geçmişini siler; onay diyaloğu atlanır ya da
 *    yanlış id giderse geri dönüşü yoktur (çöp kutusu yok).
 *  - Toplu eylem yanlış id kümesi gönderirse KULLANICININ GÖRMEDİĞİ domainler silinir. Bu ekranda
 *    seçim filtreden BAĞIMSIZ bir Set'te tutuluyor; filtre değişince temizlenmezse tam bu olur.
 *  - "Kalıcı Sil" yalnız ADMIN'e açık; kapı kayarsa takım yöneticisi kalıcı silebilir.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ admin: {
    getInventory: vi.fn(),
    getTeams: vi.fn(),
    getAlerts: vi.fn(),
    bulkInventory: vi.fn(),
    deleteInventory: vi.fn(),
    restoreInventory: vi.fn(),
    purgeInventory: vi.fn(),
    purgeDeletedInventory: vi.fn(),
    transferCertSy: vi.fn(),
  } }),
}))
// Yetki: USER için inventory.crud/edit AÇIK (2026-09-18 varsayılanı); satır kapısı üyeliğe bakar.
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: (r) => r === 'inventory.crud', canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))
// jspdf/autotable ağır ve bu testin konusu değil
vi.mock('../utils/exportInventory', () => ({
  exportInventoryCsv: vi.fn(() => 0),
  exportInventoryPdf: vi.fn(async () => 0),
}))

import { api } from '../api/client'
import InventoryManager from '../components/admin/InventoryManager.jsx'

const ITEMS = [
  { id: 1, domain: 'aktif-bir.example.com', port: 443, active: true,  team_id: 5, team_name: 'SY-A' },
  { id: 2, domain: 'aktif-iki.example.com', port: 443, active: true,  team_id: 5, team_name: 'SY-A' },
  { id: 3, domain: 'pasif.example.com',     port: 443, active: false, team_id: 5, team_name: 'SY-A' },
  { id: 4, domain: 'silinmis.example.com',  port: 443, active: false, team_id: 5, team_name: 'SY-A',
    deleted_at: '2026-08-01T10:00:00' },
]

const renderIm = (role = 'ADMIN') =>
  render(<LangProvider><InventoryManager systemRole={role} /></LangProvider>)

/** Domain adına göre o satırın kebab menüsünü açar. */
async function openRowMenu(domain) {
  const row = (await screen.findByText(domain)).closest('tr')
  fireEvent.click(within(row).getByRole('button', { name: /işlem|actions/i }))
}

// Satır SEÇİM kutuları (ilk hücre) — aktif/pasif anahtarı da checkbox (2026-09-12, satır-içi düzenleme), o sayılmaz
const checkboxes = () => [...document.querySelectorAll('tbody td:first-child input[type="checkbox"]')]

describe('InventoryManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    confirmMock.mockResolvedValue(true)
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
    api.admin.bulkInventory.mockResolvedValue({ success: true, data: { processed: 2, skipped: 0 } })
    api.admin.deleteInventory.mockResolvedValue({ success: true })
    api.admin.purgeInventory.mockResolvedValue({ success: true })
    api.admin.purgeDeletedInventory.mockResolvedValue({ success: true, data: { purged: 1 } })
    api.admin.restoreInventory.mockResolvedValue({ success: true })
  })

  it('silinmemiş kayıtları listeler; silinmiş kayıt varsayılan görünümde GİZLİ', async () => {
    renderIm()
    expect(await screen.findByText('aktif-bir.example.com')).toBeInTheDocument()
    expect(screen.getByText('pasif.example.com')).toBeInTheDocument()
    expect(screen.queryByText('silinmis.example.com')).toBeNull()
  })

  // ── Geri alınamaz: kalıcı silme ──────────────────────────────────────────────

  it('KALICI SİL onay ister; iptal edilirse uç ÇAĞRILMAZ (geri dönüşü yok)', async () => {
    confirmMock.mockResolvedValue(false)
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(screen.getByRole('button', { name: /silinmiş|deleted/i }))   // 'deleted' filtre pili
    await openRowMenu('silinmis.example.com')
    fireEvent.click(await screen.findByText(/^Kalıcı Sil$|^Permanent Delete$/))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.purgeInventory).not.toHaveBeenCalled()
  })

  it('kalıcı silme onaylanınca TAM OLARAK o satırın id\'si gider', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(screen.getByRole('button', { name: /silinmiş|deleted/i }))
    await openRowMenu('silinmis.example.com')
    fireEvent.click(await screen.findByText(/^Kalıcı Sil$|^Permanent Delete$/))

    await waitFor(() => expect(api.admin.purgeInventory).toHaveBeenCalledWith(4))
    expect(api.admin.purgeInventory).toHaveBeenCalledTimes(1)
  })

  it('TÜMÜNÜ kalıcı sil onay ister; iptalde toplu purge ucu çağrılmaz', async () => {
    confirmMock.mockResolvedValue(false)
    renderIm()
    await screen.findByText('aktif-bir.example.com')
    fireEvent.click(screen.getByRole('button', { name: /silinmiş|deleted/i }))

    fireEvent.click(await screen.findByRole('button', { name: /Tüm Silinmişleri|Permanently Delete All/i }))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.purgeDeletedInventory).not.toHaveBeenCalled()
  })

  it('KALICI SİL yalnız ADMIN\'e görünür — TEAM_ADMIN menüsünde yok', async () => {
    renderIm('TEAM_ADMIN')
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(screen.getByRole('button', { name: /silinmiş|deleted/i }))
    await openRowMenu('silinmis.example.com')

    expect(screen.queryByText(/^Kalıcı Sil$|^Permanent Delete$/)).toBeNull()
    expect(await screen.findByText(/^Geri Getir$|^Restore$/)).toBeInTheDocument()   // geri getirme açık
  })

  // ── Toplu eylemler: yanlış küme = görünmeyen domainlerin silinmesi ───────────

  it('toplu silme SEÇİLİ id kümesini ve doğru aksiyonu gönderir', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(checkboxes()[0])   // id=1
    fireEvent.click(checkboxes()[2])   // id=3 (pasif — varsayılan görünümde de listede)
    fireEvent.click(await screen.findByRole('button', { name: /^Sil$|^Delete$/ }))

    await waitFor(() => expect(api.admin.bulkInventory).toHaveBeenCalled())
    const [ids, action] = api.admin.bulkInventory.mock.calls[0]
    expect([...ids].sort()).toEqual([1, 3])
    expect(action).toBe('delete')
  })

  it('toplu silme onay ister; iptalde HİÇBİR kayıt gitmez', async () => {
    confirmMock.mockResolvedValue(false)
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(checkboxes()[0])
    fireEvent.click(await screen.findByRole('button', { name: /^Sil$|^Delete$/ }))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(api.admin.bulkInventory).not.toHaveBeenCalled()
  })

  it('FİLTRE değişince seçim TEMİZLENİR — görünmeyen kayıt toplu eyleme takılmaz', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(checkboxes()[0])
    expect(await screen.findByText(/1 seçili|1 selected/)).toBeInTheDocument()

    fireEvent.click(document.querySelector('.inv-stat-inactive'))   // filtre değişti (alan adı düğmesi de 'pasif' içerir → sınıfla seç)

    await waitFor(() => expect(screen.queryByText(/seçili|selected/)).toBeNull())
  })

  it('toplu eylem sunucuda reddedilirse başarı denmez ve liste yeniden yüklenmez', async () => {
    api.admin.bulkInventory.mockResolvedValue({ success: false, error: 'yetki yok' })
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(checkboxes()[0])
    fireEvent.click(await screen.findByRole('button', { name: /^Sil$|^Delete$/ }))

    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(toastMock.success).not.toHaveBeenCalled()
    expect(api.admin.getInventory).toHaveBeenCalledTimes(1)   // yeniden yükleme YOK
  })

  // ── Tekil silme: açık alarm uyarısı ─────────────────────────────────────────

  it('açık alarmı olan domain silinirken sayım okunur ve UYARI varyantıyla onay istenir', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 3 })
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const arg = confirmMock.mock.calls[0][0]
    expect(arg.variant).toBe('warning')
    expect(arg.message).toContain('3')
    await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalledWith(1))
  })

  it('alarm sayımı patlasa da silme akışı DEVAM eder (sayaç ikincil bilgi)', async () => {
    api.admin.getAlerts.mockRejectedValue(new Error('500'))
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalledWith(1))
  })

  // -- teams: turetilmis state senkronu (D22) ---------------------------------
  // `useState(teamsProp)` prop'u state'e KOPYALIYOR; mount efekti prop'u senkronlamiyor,
  // api.admin.getTeams() ile CEKIYOR ve yalniz canManage iken. Sonuc: yonetemeyen rollerde
  // (AUDIT/USER) teams prop'un ILK degerinde donuyordu.
  //
  // DIKKAT - kosulsuz bir prop-senkron efekti YANLIS olurdu: canManage'de cekilen (takim-kapsamli)
  // listeyi ezer, ustelik `teamsProp = []` varsayilani her parent render'inda yeni dizi kimligi
  // oldugundan surekli tetiklenirdi. Senkron YALNIZ !canManage icin.

  const rowNoTeamName = [{ id: 9, domain: 'takimsiz.example.com', port: 443, active: true, team_id: 7 }]

  it('AUDIT: teams prop SONRADAN gelirse takim adi hucresi guncellenir', async () => {
    api.admin.getInventory.mockResolvedValue({ success: true, data: rowNoTeamName })

    const { rerender } = render(
      <LangProvider><InventoryManager systemRole="AUDIT" teams={[]} /></LangProvider>)
    await screen.findByText('takimsiz.example.com')
    expect(screen.queryByText('Takim A')).toBeNull()

    rerender(<LangProvider><InventoryManager systemRole="AUDIT" teams={[{ id: 7, name: 'Takim A' }]} /></LangProvider>)

    expect(await screen.findByText('Takim A')).toBeInTheDocument()
  })

  it('ADMIN: CEKILEN takim listesi prop tarafindan EZILMEZ', async () => {
    api.admin.getInventory.mockResolvedValue({ success: true, data: rowNoTeamName })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 7, name: 'Cekilen' }] })

    const { rerender } = render(
      <LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 7, name: 'Proptan' }]} /></LangProvider>)

    expect(await screen.findByText('Cekilen')).toBeInTheDocument()

    // Parent yeniden render edip prop'u tazeler (yeni dizi kimligi) -> cekilen liste KALMALI.
    rerender(<LangProvider><InventoryManager systemRole="ADMIN" teams={[{ id: 7, name: 'Proptan' }]} /></LangProvider>)

    await waitFor(() => expect(screen.getByText('Cekilen')).toBeInTheDocument())
    expect(screen.queryByText('Proptan')).toBeNull()
  })
})

// ── USER: kendi takımının kaydını düzenler (2026-09-18) ──
describe('InventoryManager — USER satır düzenleme kapısı', () => {
  it('USER: "Domain Ekle" görünür; kendi takımının satırında Düzenle/Kopyala VAR, Sil YOK; başka takımın satırında düzenleme yok; toplu seçim sütunu yok', async () => {
    vi.clearAllMocks()
    api.admin.getInventory.mockResolvedValue({ success: true, data: [
      { id: 1, domain: 'kendi.example.com', port: 443, active: true, team_id: 5, team_name: 'SY-A' },
      { id: 2, domain: 'baska.example.com', port: 443, active: true, team_id: 9, team_name: 'SY-B' },
    ] })
    const { container } = render(<LangProvider><InventoryManager systemRole="USER" teams={[{ id: 5, name: 'SY-A' }]} /></LangProvider>)
    await screen.findByText('kendi.example.com')
    expect(screen.getByRole('button', { name: /Domain Ekle|Add Domain/i })).toBeInTheDocument()
    expect(container.querySelector('thead input[type=checkbox]')).toBeNull()

    const own = screen.getByText('kendi.example.com').closest('tr')
    fireEvent.click(own.querySelector('.kebab-trigger'))
    let items = [...document.querySelectorAll('.wr-menu-pop button')].map((b) => b.textContent.trim())
    expect(items).toEqual(expect.arrayContaining([expect.stringMatching(/^(Düzenle|Edit)$/), expect.stringMatching(/Kopyala|Duplicate/i)]))
    expect(items.some((x) => /^(Sil|Delete)$/.test(x))).toBe(false)
    fireEvent.keyDown(document, { key: 'Escape' })

    const other = screen.getByText('baska.example.com').closest('tr')
    fireEvent.click(other.querySelector('.kebab-trigger'))
    items = [...document.querySelectorAll('.wr-menu-pop button')].map((b) => b.textContent.trim())
    expect(items.some((x) => /^(Düzenle|Edit)$/.test(x))).toBe(false)
  })
})
