import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Manuel Sertifikalar sayfası (2026-10-06): liste (sorunlular önce), özet kutuları (süzgeç), arama / durum / takım
 * süzgeçleri, "Hangi dosyayı yüklemeliyim?" rehberi (boş durumda açık, kapatma tercihi hatırlanır), satır eylemleri
 * (Aç · Yeni sürüm yükle · PEM indir), `mc_upload=1` ile sihirbaz, 403 → yetki yok durumu.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const ROWS = [
  { inventory_id: 1, domain: 'ok.example.test', team_id: 1, team_name: 'Takım A', tier: 2, status: 'valid', days_remaining: 200,
    not_after: '2027-04-01T00:00:00', issuer: 'Example CA', subject: 'ok.example.test', versions_count: 1, can_manage: true,
    current_version: { version: 1, fingerprint: 'AA', uploaded_at: '2026-10-01T10:00:00', uploaded_by_name: 'Kişi A', file_name: 'ok.pem', file_format: 'PEM' } },
  { inventory_id: 2, domain: 'gone.example.test', team_id: 2, team_name: 'Takım B', status: 'warning', alert_level: 'expired', days_remaining: -3,
    not_after: '2026-10-03T00:00:00', issuer: 'Example CA', subject: 'gone.example.test', versions_count: 2, can_manage: true,
    current_version: { version: 2, fingerprint: 'BB', uploaded_at: '2026-09-01T10:00:00', uploaded_by_name: 'Kişi B', file_name: 'gone.pfx', file_format: 'PKCS12' } },
  { inventory_id: 3, domain: 'soon.example.test', team_id: 2, team_name: 'Takım B', status: 'warning', alert_level: 'high', days_remaining: 12,
    not_after: '2026-10-18T00:00:00', issuer: 'Other CA', subject: 'soon.example.test', versions_count: 1, can_manage: false,
    current_version: { version: 1, fingerprint: 'CC', uploaded_at: '2026-08-01T10:00:00', uploaded_by_name: 'Kişi C', file_name: 'trust.jks', file_format: 'JKS' } },
]

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    manualCerts: {
      list: vi.fn(),
      get: vi.fn(),
      analyze: vi.fn(),
      pemUrl: vi.fn((id, v) => `/api/manual-certs/${id}/versions/${v}/pem`),
    },
    admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }] }) },
  }),
}))
const perms = { value: { 'inventory.crud': { edit: true } } }
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perms.value, canView: () => true,
    canEdit: (r) => !!perms.value?.[r]?.edit, canExecute: () => true, refresh: () => {},
  }),
}))

import { api } from '../api/client'
import ManualCertsPage from '../components/manualcert/ManualCertsPage.jsx'
import { announceInventoryRenamed } from '../utils/inventoryEvent.js'

const rowsShown = () => [...document.querySelectorAll('[data-mcert-row]')].map((el) => el.getAttribute('data-mcert-row'))

function renderPage(props = {}) {
  return render(<ManualCertsPage systemRole="USER" myTeams={[{ id: 1, name: 'Takım A' }]} onOpenCert={vi.fn()} {...props} />)
}

describe('ManualCertsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    perms.value = { 'inventory.crud': { edit: true } }
    window.history.replaceState(null, '', '/?tab=manualcerts')
    try { localStorage.removeItem('sm.mcert.guide') } catch { /* yok */ }
    api.manualCerts.list.mockResolvedValue({ success: true, data: ROWS })
    api.manualCerts.get.mockResolvedValue({ success: true, data: { versions: [{ id: 91, version: 2, current: true }, { id: 90, version: 1, current: false }] } })
  })

  it('liste sorunlular önce sıralanır; her satırda Manuel rozeti, durum ve kalan gün; özet kutuları', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toEqual(['gone.example.test', 'soon.example.test', 'ok.example.test']))
    expect(document.querySelector('[data-slot="mcert-list"]')).toHaveAttribute('data-view', 'table')
    expect(document.querySelectorAll('[data-mcert-row] [data-slot="manual-cert-badge"]')).toHaveLength(3)
    const gone = document.querySelector('[data-mcert-row="gone.example.test"]')
    expect(gone).toHaveAttribute('data-status', 'expired')
    expect(within(gone).getByText(/Expired 3 days ago|3 gün önce/)).toBeInTheDocument()
    const tile = (k) => document.querySelector(`[data-slot="stat-item"][data-key="${k}"] [data-slot="stat-value"]`)
    expect(tile('total')).toHaveTextContent('3')
    expect(tile('healthy')).toHaveTextContent('1')
    expect(tile('expiring')).toHaveTextContent('1')
    expect(tile('expired')).toHaveTextContent('1')
    expect(tile('versions')).toHaveTextContent('4')
  })

  it('özet kutusu süzer; arama, durum ve takım süzgeçleri; eşleşme yoksa süzgeçleri temizle', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="expired"]'))
    await waitFor(() => expect(rowsShown()).toEqual(['gone.example.test']))
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="expired"]'))
    await waitFor(() => expect(rowsShown()).toHaveLength(3))

    fireEvent.change(screen.getByLabelText(/^(Status|Durum)$/), { target: { value: 'expiring' } })
    await waitFor(() => expect(rowsShown()).toEqual(['soon.example.test']))
    fireEvent.change(screen.getByLabelText(/^(Status|Durum)$/), { target: { value: 'all' } })
    fireEvent.change(screen.getByLabelText(/^(Team|Takım)$/), { target: { value: '1' } })
    await waitFor(() => expect(rowsShown()).toEqual(['ok.example.test']))
    fireEvent.change(screen.getByLabelText(/^(Search|Ara)$/), { target: { value: 'zzz' } })
    await screen.findByText(/No records match the filter|Süzgece uyan kayıt yok/)
    fireEvent.click(screen.getByRole('button', { name: /Clear filters|Süzgeçleri temizle/ }))
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
  })

  it('rehber varsayılan KAPALI; açılınca biçimler + yüklenmemesi gerekenler + gizlilik; açma/kapama tercihi hatırlanır', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    const guide = document.querySelector('[data-slot="mcert-guide"]')
    expect(guide).toBeTruthy()
    expect(document.querySelector('[data-slot="mcert-guide-body"]')).toBeNull()   // varsayılan kapalı (2026-10-07)
    fireEvent.click(within(guide).getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="mcert-guide-body"]')).toBeTruthy())
    expect(localStorage.getItem('sm.mcert.guide')).toBe('open')
    expect(document.querySelectorAll('[data-slot="mcert-guide-formats"] [data-format]')).toHaveLength(7)
    expect(document.querySelector('[data-format="pfx"] [data-slot="mcert-fmt-pw"]')).toHaveAttribute('data-value', 'yes')
    expect(document.querySelector('[data-slot="mcert-guide-dont"]')).toHaveTextContent(/CSR/)
    expect(document.querySelector('[data-slot="mcert-guide-privacy"]')).toHaveTextContent(/never stored|saklanmaz/)
    // 2026-10-08: özel anahtar tarayıcıda ayıklanır, parola yalnız tarayıcıda — rehber bunu söyler
    expect(document.querySelector('[data-slot="mcert-guide-privacy"]')).toHaveTextContent(/never sent to the server|sunucuya hiç gönderilmez/)
    expect(document.querySelector('[data-format="pfx"] [data-slot="mcert-fmt-pw"]')).toHaveTextContent(/used only in your browser|yalnız tarayıcınızda/)
    expect(document.querySelector('[data-format="jks"] [data-slot="mcert-fmt-key"]')).toHaveTextContent(/removed in the browser|tarayıcıda ayıklanır/)
    expect(document.querySelectorAll('[data-slot="mcert-guide-when"] [data-upload="true"]')).toHaveLength(2)
    fireEvent.click(within(guide).getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="mcert-guide-body"]')).toBeNull())
    expect(localStorage.getItem('sm.mcert.guide')).toBe('closed')
  })

  it('boş liste: rehber yine varsayılan kapalı ama açılabilir; yükleme düğmesi; yetkisiz kullanıcıda yükleme yok', async () => {
    api.manualCerts.list.mockResolvedValue({ success: true, data: [] })
    const { unmount } = renderPage()
    await screen.findByText(/No uploaded certificates yet|Henüz dosyadan/)
    expect(document.querySelector('[data-slot="mcert-guide-body"]')).toBeNull()
    fireEvent.click(within(document.querySelector('[data-slot="mcert-guide"]')).getByRole('button', { name: /Which file should I upload|Hangi dosyayı/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="mcert-guide-body"]')).toBeTruthy())
    expect(screen.getAllByRole('button', { name: /Upload certificate|Sertifika yükle/ }).length).toBeGreaterThanOrEqual(1)
    unmount()
    perms.value = {}
    renderPage()
    await screen.findByText(/No uploaded certificates yet|Henüz dosyadan/)
    expect(screen.queryByRole('button', { name: /Upload certificate|Sertifika yükle/ })).toBeNull()
  })

  it('satır eylemleri: Aç → onOpenCert(satır); menü → Yeni sürüm yükle sihirbazı yenileme kipinde; PEM indir sürüm kimliğini ayrıntıdan bulur', async () => {
    const onOpenCert = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    renderPage({ onOpenCert })
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    const row = document.querySelector('[data-mcert-row="gone.example.test"]')
    fireEvent.click(within(row).getByRole('button', { name: /gone\.example\.test — (Open|Aç)/ }))
    expect(onOpenCert).toHaveBeenCalledWith(expect.objectContaining({ inventory_id: 2, domain: 'gone.example.test' }))

    pressMenuTrigger(within(row).getByRole('button', { name: /gone\.example\.test — (Actions|İşlemler)/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Download PEM|PEM indir/ }))
    await waitFor(() => expect(api.manualCerts.get).toHaveBeenCalledWith(2))
    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(api.manualCerts.pemUrl).toHaveBeenCalledWith(2, 91)

    pressMenuTrigger(within(row).getByRole('button', { name: /gone\.example\.test — (Actions|İşlemler)/ }))
    // Takip adı sonradan düzenlenir (2026-10-07): yazma izni + kendi kaydı → "Düzenle" menüde
    expect(await screen.findByRole('menuitem', { name: /Edit \(tracking name|Düzenle \(takip adı/ })).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('menuitem', { name: /Upload new version|Yeni sürüm yükle/ }))
    const title = await screen.findByText(/Upload new version: gone\.example\.test|Yeni sürüm yükle: gone\.example\.test/)
    expect(title).toBeInTheDocument()
    click.mockRestore()
  })

  it('envanter yazma izni (inventory.crud/edit) yoksa yükleme ve yeni sürüm yok; PEM indir ve Aç kalır', async () => {
    perms.value = { 'inventory.list': { view: true } }
    renderPage()
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    expect(screen.queryByRole('button', { name: /^(Upload certificate|Sertifika yükle)$/ })).toBeNull()
    const row = document.querySelector('[data-mcert-row="ok.example.test"]')
    pressMenuTrigger(within(row).getByRole('button', { name: /ok\.example\.test — (Actions|İşlemler)/ }))
    await screen.findByRole('menuitem', { name: /Download PEM|PEM indir/ })
    expect(screen.getByRole('menuitem', { name: /^(Open|Aç)$/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Upload new version|Yeni sürüm yükle/ })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: /Edit \(tracking name|Düzenle \(takip adı/ })).toBeNull()
  })

  it('başka takımın kaydında yeni sürüm yok (salt okunur)', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toHaveLength(3))
    const row = document.querySelector('[data-mcert-row="soon.example.test"]')
    pressMenuTrigger(within(row).getByRole('button', { name: /soon\.example\.test — (Actions|İşlemler)/ }))
    await screen.findByRole('menuitem', { name: /Download PEM|PEM indir/ })
    expect(screen.queryByRole('menuitem', { name: /Upload new version|Yeni sürüm yükle/ })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: /Edit \(tracking name|Düzenle \(takip adı/ })).toBeNull()
  })

  it('?mc_upload=1 sihirbazı açar ve parametre adresten silinir', async () => {
    window.history.replaceState(null, '', '/?tab=manualcerts&mc_upload=1')
    renderPage()
    await screen.findByText(/Add certificate from file|Dosyadan sertifika ekle/)
    expect(document.querySelector('[data-slot="mcert-wizard"]')).toHaveAttribute('data-step', 'file')
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('mc_upload')).toBeNull())
  })

  it('403 → yetki yok durumu', async () => {
    api.manualCerts.list.mockResolvedValue({ success: false, status: 403, error: 'yasak' })
    renderPage()
    await screen.findByText(/not allowed to view this page|görme yetkiniz yok/)
  })

  // 2026-10-08 (kullanıcı: "Manuel Sertifikalar sayfasında takip adı değişikliği hemen görülmüyor"): ad sertifika
  // penceresinin Düzenle'sinden değişince sayfa haberdar değildi. Olayla satır ANINDA yeni adı alır, liste tazelenir;
  // geç dönen ESKİ yanıt yeni adı geri alamaz (yalnız en son istek uygulanır).
  it('yeniden adlandırma olayı: satır tazeleme BEKLENMEDEN yeni adı alır, liste yeniden okunur', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toContain('ok.example.test'))
    let release
    api.manualCerts.list.mockReturnValueOnce(new Promise((r) => { release = r }))
    act(() => announceInventoryRenamed('ok.example.test', 'odeme-imza'))
    expect(rowsShown()).toContain('odeme-imza')
    expect(rowsShown()).not.toContain('ok.example.test')
    expect(api.manualCerts.list).toHaveBeenCalledTimes(2)
    await act(async () => { release({ success: true, data: ROWS.map((r) => (r.domain === 'ok.example.test' ? { ...r, domain: 'odeme-imza' } : r)) }) })
    expect(rowsShown()).toContain('odeme-imza')
  })

  it('geç dönen eski liste yanıtı yeni adı geri almaz', async () => {
    renderPage()
    await waitFor(() => expect(rowsShown()).toContain('ok.example.test'))
    let releaseOld
    api.manualCerts.list.mockReturnValueOnce(new Promise((r) => { releaseOld = r }))
    fireEvent.click(screen.getAllByRole('button', { name: /^(Refresh|Yenile)$/ })[0])
    api.manualCerts.list.mockResolvedValueOnce({ success: true, data: ROWS.map((r) => (r.domain === 'ok.example.test' ? { ...r, domain: 'odeme-imza' } : r)) })
    act(() => announceInventoryRenamed('ok.example.test', 'odeme-imza'))
    await waitFor(() => expect(api.manualCerts.list).toHaveBeenCalledTimes(3))
    await act(async () => { releaseOld({ success: true, data: ROWS }) })
    expect(rowsShown()).toContain('odeme-imza')
    expect(rowsShown()).not.toContain('ok.example.test')
  })
})
