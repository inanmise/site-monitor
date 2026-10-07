import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

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
    transferCertSy: vi.fn(),
  } }),
}))
// Yetki: USER için inventory.crud/edit AÇIK (2026-09-18 varsayılanı); satır kapısı üyeliğe bakar.
// `permState`: tek testte ekleme yetkisini kapatmak için (openAddSignal kapısı, 2026-09-26).
const permState = vi.hoisted(() => ({ canAdd: true, perms: {} }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: permState.perms, canView: () => true, canEdit: (r) => r === 'inventory.crud' && permState.canAdd, canExecute: () => false, refresh: () => {} }),
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
import { __resetDeletedMarks } from '../utils/recentlyDeleted.js'

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
  pressMenuTrigger(within(row).getByRole('button', { name: /işlem|actions/i }))
}

// Satır SEÇİM kutuları (ilk hücre, shadcn Checkbox → role="checkbox") — aktif/pasif anahtarı shadcn Switch (role="switch"), o sayılmaz
const checkboxes = () => [...document.querySelectorAll('tbody td:first-child [role="checkbox"]')]
// Özet kartı (MonitorStatsBar düğmesi, 2026-09-27 — eski durum hapları `data-stat` yerine) — kancası `data-key`
const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)

describe('InventoryManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    confirmMock.mockResolvedValue(true)
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 0 })
    api.admin.bulkInventory.mockResolvedValue({ success: true, data: { processed: 2, skipped: 0 } })
    api.admin.deleteInventory.mockResolvedValue({ success: true, permanent: true })
    __resetDeletedMarks()
  })

  it('silinmemiş kayıtları listeler; silinmiş kayıt varsayılan görünümde GİZLİ', async () => {
    renderIm()
    expect(await screen.findByText('aktif-bir.example.com')).toBeInTheDocument()
    expect(screen.getByText('pasif.example.com')).toBeInTheDocument()
    expect(screen.queryByText('silinmis.example.com')).toBeNull()
  })

  // ── Geri alınamaz: silme KALICI (2026-10-07) — çöp kutusu yok ──────────────────

  it('çöp kutusu arayüzü YOK: "Silinmiş" kartı / süzgeci, "Geri Getir", "Kalıcı Sil" ve "Tümünü kalıcı sil" bandı çizilmez', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')
    expect(tile('deleted')).toBeNull()
    expect(screen.queryByRole('button', { name: /Tüm Silinmişleri|Permanently Delete All/i })).toBeNull()
    await openRowMenu('aktif-bir.example.com')
    expect(await screen.findByText(/^Sil$|^Delete$/)).toBeInTheDocument()
    expect(screen.queryByText(/^Geri Getir$|^Restore$/)).toBeNull()
    expect(screen.queryByText(/^Kalıcı Sil$|^Permanent Delete$/)).toBeNull()
  })

  it('silme onayı KALICI olduğunu söyler (danger, ad + geri alınamaz); iptalde uç ÇAĞRILMAZ', async () => {
    confirmMock.mockResolvedValue(false)
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const arg = confirmMock.mock.calls[0][0]
    expect(arg.variant).toBe('danger')
    expect(arg.title).toMatch(/Kalıcı olarak sil|Delete permanently/)
    expect(arg.confirmText).toMatch(/Kalıcı olarak sil|Delete permanently/)
    expect(arg.message).toContain('aktif-bir.example.com')
    expect(arg.message).toMatch(/geri alınamaz|can't be undone/)
    expect(arg.message).not.toMatch(/Silinmişleri göster|Show deleted/)
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
  })

  it('onaylanınca TAM o id gider; satır liste yüklemesi BEKLENMEDEN düşer, bayat yükleme onu GERİ GETİRMEZ', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')
    let release
    api.admin.getInventory.mockImplementation(() => new Promise((r) => { release = r }))   // tazeleme askıda
    await openRowMenu('aktif-iki.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(api.admin.deleteInventory).toHaveBeenCalledWith(2))
    await waitFor(() => expect(screen.queryByText('aktif-iki.example.com')).toBeNull())   // tazeleme hâlâ askıda
    expect(release).toBeTypeOf('function')
    release({ success: true, data: ITEMS })   // başka pod'un bayat önbelleği: silinen kayıt hâlâ listede
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('aktif-iki.example.com')).toBeNull()
    expect(screen.getByText('aktif-bir.example.com')).toBeInTheDocument()
  })

  it('silme sunucuda reddedilirse satır KALIR ve hata söylenir', async () => {
    api.admin.deleteInventory.mockResolvedValue({ success: false, error: 'yetki yok' })
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('yetki yok'))
    expect(screen.getByText('aktif-bir.example.com')).toBeInTheDocument()
  })

  // ── Toplu eylemler: yanlış küme = görünmeyen domainlerin silinmesi ───────────

  it('toplu silme SEÇİLİ id kümesini ve doğru aksiyonu gönderir; onay KALICI ve adları sayar; silinenler ANINDA düşer', async () => {
    api.admin.bulkInventory.mockResolvedValue({ success: true, data: { processed: 2, skipped: 0, permanent: true,
      deleted_domains: ['aktif-bir.example.com', 'pasif.example.com'] } })
    renderIm()
    await screen.findByText('aktif-bir.example.com')

    fireEvent.click(checkboxes()[0])   // id=1
    fireEvent.click(checkboxes()[2])   // id=3 (pasif — varsayılan görünümde de listede)
    fireEvent.click(await screen.findByRole('button', { name: /^Sil$|^Delete$/ }))

    await waitFor(() => expect(api.admin.bulkInventory).toHaveBeenCalled())
    const opts = confirmMock.mock.calls[0][0]
    expect(opts.variant).toBe('danger')
    expect(opts.message).toContain('aktif-bir.example.com')
    expect(opts.message).toContain('pasif.example.com')
    expect(opts.message).toMatch(/geri alınamaz|can't be undone/)
    const [ids, action] = api.admin.bulkInventory.mock.calls[0]
    expect([...ids].sort()).toEqual([1, 3])
    expect(action).toBe('delete')
    await waitFor(() => expect(screen.queryByText('pasif.example.com')).toBeNull())
    expect(screen.queryByText('aktif-bir.example.com')).toBeNull()
    expect(screen.getByText('aktif-iki.example.com')).toBeInTheDocument()
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

    fireEvent.click(tile('inactive'))   // filtre değişti (alan adı düğmesi de 'pasif' içerir → kancayla seç)

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

  it('açık alarmı olan domain silinirken sayım okunur ve onay metninde söylenir (kalıcı silme: hep danger)', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 3 })
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByText(/^Sil$|^Delete$/))

    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const arg = confirmMock.mock.calls[0][0]
    expect(arg.variant).toBe('danger')
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

  // ── Kolon süzgeci hiçbir şeyi eşleştirmediğinde (2026-09-22 QA) ────────────────

  it('kolon süzgeci sonuç vermezse tablo + süzgeç satırı EKRANDA KALIR; "Temizle" süzgeçleri sıfırlar', async () => {
    window.history.replaceState(null, '', '/?i_dom=yok-boyle-bir-sey')
    renderIm()
    await waitFor(() => expect(api.admin.getInventory).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /Kolon süzgeçleri|Column filters/i }))

    // Süzgeç satırı ekranda kalır (kullanıcı ne yazdığını görür), tablo kaldırılmaz
    await waitFor(() => expect(screen.getByTestId('inv-filter-row')).toBeInTheDocument())
    expect(document.querySelector('[data-slot="table-empty-row"]')).not.toBeNull()
    expect(screen.queryByText('aktif-bir.example.com')).toBeNull()

    fireEvent.click(within(document.querySelector('[data-slot="table-empty-row"]')).getByRole('button', { name: /Temizle|Clear/i }))
    expect(await screen.findByText('aktif-bir.example.com')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="table-empty-row"]')).toBeNull()
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
    expect(container.querySelector('thead [role="checkbox"]')).toBeNull()

    const own = screen.getByText('kendi.example.com').closest('tr')
    pressMenuTrigger(within(own).getByRole('button', { name: /işlem|actions/i }))
    let items = screen.getAllByRole('menuitem').map((b) => b.textContent.trim())
    expect(items).toEqual(expect.arrayContaining([expect.stringMatching(/^(Düzenle|Edit)$/), expect.stringMatching(/Kopyala|Duplicate/i)]))
    expect(items.some((x) => /^(Sil|Delete)$/.test(x))).toBe(false)
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    const other = screen.getByText('baska.example.com').closest('tr')
    pressMenuTrigger(within(other).getByRole('button', { name: /işlem|actions/i }))
    items = screen.getAllByRole('menuitem').map((b) => b.textContent.trim())
    expect(items.some((x) => /^(Düzenle|Edit)$/.test(x))).toBe(false)
  })

  // 2026-09-25 kullanıcı bildirimi: USER "Domain Ekle"yi açabiliyordu ama form seçicileri (takım/grup/etiket)
  // yalnız yöneticiye açıktı → takım seçilemediği için kayıt HİÇ açılamıyordu. Form artık ekleme yetkisiyle açılır.
  it('USER: "Domain Ekle" formunda takım (tek takımı önseçili) ve grup seçicileri AÇIK', async () => {
    vi.clearAllMocks()
    api.admin.getInventory.mockResolvedValue({ success: true, data: [] })
    render(<LangProvider><InventoryManager systemRole="USER" teams={[{ id: 5, name: 'SY-A' }]} /></LangProvider>)
    fireEvent.click(await screen.findByRole('button', { name: /Domain Ekle|Add Domain/i }))
    const team = await screen.findByRole('combobox', { name: /Takım|Team/ })
    expect(team).toBeEnabled()
    await waitFor(() => expect(team).toHaveTextContent('SY-A'))
    expect(screen.getByRole('combobox', { name: /Grup|Group/ })).toBeEnabled()
  })
})

// ── R4 (2026-09-25): düzenleme formunda takım kutusu sunucu kuralına eşit — updateInventory takımı yalnız
//    rol ADMIN'de yazar, TEAM_ADMIN'de mevcut takıma sabitler ("Kaydedildi" deyip bildirim grubunu düşürüyordu). ──
describe('InventoryManager — düzenlemede takım aktarımı (R4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }, { id: 9, name: 'SY-B' }] })
  })

  /** Satırın Düzenle eylemiyle formu açar; sorgular FORMA kapsanır (listenin takım süzgeci de bir combobox). */
  async function openEditForm(role) {
    renderIm(role)
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByRole('menuitem', { name: /^(Düzenle|Edit)$/ }))
    const form = await waitFor(() => {
      const el = document.querySelector('[role="dialog"]')   // envanter formu ModalShell (shadcn Dialog)
      if (!el) throw new Error('form henüz açılmadı')
      return el
    })
    return within(form)
  }

  it('ADMIN: düzenleme formunda takım kutusu AÇIK', async () => {
    const form = await openEditForm('ADMIN')
    expect(form.getByRole('combobox', { name: /Takım|Team/ })).toBeEnabled()
  })

  it('TEAM_ADMIN: düzenleme formunda takım kutusu KİLİTLİ, grup seçicisi açık', async () => {
    const form = await openEditForm('TEAM_ADMIN')
    expect(form.getByRole('combobox', { name: /Takım|Team/ })).toBeDisabled()
    expect(form.getByRole('combobox', { name: /Grup|Group/ })).toBeEnabled()
  })
})

const ADD_TITLE = /^(Domain Ekle|Add Domain)$/
describe('InventoryManager — panodan "Domain Ekle" sinyali yetkiye uyar (2026-09-26)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [] })
  })
  afterEach(() => { permState.canAdd = true; permState.perms = {} })

  it('ekleme yetkisi olan USER: sinyal formu açar ve tüketilir', async () => {
    const onAddConsumed = vi.fn()
    render(<LangProvider><InventoryManager systemRole="USER" teams={[{ id: 5, name: 'SY-A' }]} openAddSignal onAddConsumed={onAddConsumed} /></LangProvider>)
    expect(await screen.findByRole('heading', { name: ADD_TITLE })).toBeInTheDocument()   // form başlığı (kabuk türünden bağımsız)
    expect(onAddConsumed).toHaveBeenCalledTimes(1)
  })

  it('ekleme yetkisi YOK (yetki yüklendi): form AÇILMAZ, sinyal düşürülür; Ekle düğmesi de yok', async () => {
    permState.canAdd = false
    permState.perms = { 'inventory.crud': { view: true } }
    const onAddConsumed = vi.fn()
    render(<LangProvider><InventoryManager systemRole="USER" teams={[{ id: 5, name: 'SY-A' }]} openAddSignal onAddConsumed={onAddConsumed} /></LangProvider>)
    await screen.findByText('aktif-bir.example.com')
    expect(onAddConsumed).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('heading', { name: ADD_TITLE })).toBeNull()
    expect(screen.queryByRole('button', { name: /domain ekle|add domain/i })).toBeNull()
  })

  it('yetki henüz yüklenmediyse sinyal BEKLER (düşürülmez, form açılmaz)', async () => {
    permState.canAdd = false
    permState.perms = {}
    const onAddConsumed = vi.fn()
    render(<LangProvider><InventoryManager systemRole="USER" teams={[{ id: 5, name: 'SY-A' }]} openAddSignal onAddConsumed={onAddConsumed} /></LangProvider>)
    await screen.findByText('aktif-bir.example.com')
    expect(onAddConsumed).not.toHaveBeenCalled()
    expect(screen.queryByRole('heading', { name: ADD_TITLE })).toBeNull()
  })
})

// ── D1 dalgası (2026-09-26): durum süzgeci düğmeleri, Dışa aktar menüsü (DropdownMenu), Devret penceresi (ModalShell) ──
describe('InventoryManager — shadcn üst çubuk ve Devret penceresi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getInventory.mockResolvedValue({ success: true, data: ITEMS })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }, { id: 9, name: 'SY-B' }] })
    api.admin.transferCertSy.mockResolvedValue({ success: true })
  })

  it('durum düğmesi aria-pressed taşır; basınca süzer, yeniden basınca varsayılana döner', async () => {
    renderIm()
    await screen.findByText('aktif-bir.example.com')
    const inactive = tile('inactive')
    expect(inactive).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(inactive)
    expect(inactive).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(screen.queryByText('aktif-bir.example.com')).toBeNull())
    expect(screen.getByText('pasif.example.com')).toBeInTheDocument()
    fireEvent.click(inactive)
    expect(inactive).toHaveAttribute('aria-pressed', 'false')
    expect(await screen.findByText('aktif-bir.example.com')).toBeInTheDocument()
  })

  it('Dışa aktar menüsü: CSV öğesi tüm envanteri (silinmişler hariç) çekip CSV üretir', async () => {
    const { exportInventoryCsv } = await import('../utils/exportInventory')
    renderIm()
    await screen.findByText('aktif-bir.example.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^Export$|Dışa Aktar/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /CSV/i }))
    await waitFor(() => expect(exportInventoryCsv).toHaveBeenCalled())
    expect(api.admin.getInventory).toHaveBeenCalledWith('mine')   // dışa aktarma ekrandaki kapsamı taşır (2026-09-26); çöp kutusu parametresi yok (2026-10-07)
  })

  it('Devret: satır menüsünden açılan pencerede yeni takım seçilir ve TAM o kayıt aktarılır', async () => {
    renderIm()
    await openRowMenu('aktif-bir.example.com')
    fireEvent.click(await screen.findByRole('menuitem', { name: /^(Devret|Transfer)$/ }))
    const dialog = await screen.findByRole('dialog', { name: /aktif-bir\.example\.com/ })
    const picker = within(dialog).getByRole('combobox', { name: /Yeni Takım|New Team/ })
    fireEvent.mouseDown(picker)
    fireEvent.mouseDown(await screen.findByRole('option', { name: 'SY-B' }))
    fireEvent.click(within(dialog).getByRole('button', { name: /^(Devret|Transfer)$/ }))
    await waitFor(() => expect(api.admin.transferCertSy).toHaveBeenCalledWith(1, 9))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})
