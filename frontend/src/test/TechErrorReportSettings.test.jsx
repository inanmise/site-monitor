import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6; yeniden tasarım aynı gün): gören takımlar (boş = herkes), salt
// okunur müdür, kanal özeti — KPI kartları süzgeç, arama Türkçe katlar, takım / durum süzgeçleri, okuma hatası → tehlike bloğu
// + Tekrar dene, "Kanalı aç" kanal sayfasına gider. jsdom'da kap genişliği 0 → TABLO (kart dalı: TechErrorReportSettings.mobile).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: { techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn() },
    teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportSettings from '../components/admin/TechErrorReportSettings.jsx'

const CHANNELS = [
  { id: 10, name: 'Mobil', active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 2, approver_count: 1,
    team_email_set: true, period_report: { id: 100, status: 'draft', last_send_status: null, approval: { state: 'pending' } } },
  { id: 20, name: 'POS', active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, executive_count: 0, approver_count: 0,
    team_email_set: false, period_report: null },
]
const THIRD = { id: 30, name: 'İnternet Şubesi', active: true, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: true, executive_count: 0,
  approver_count: 0, team_email_set: false, period_report: null }

const settings = (extra = {}) => ({ success: true, data: {
  team_ids: [], read_only: false, period: { year: 2026, month: 9 }, channels: CHANNELS, ...extra,
} })

const rows = () => document.querySelectorAll('[data-slot="tes-channel"]')
const names = () => [...rows()].map((r) => r.querySelector('[data-slot="tes-channel-name"]').textContent)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Dijital Kanallar', active: true }, { id: 2, name: 'Ödeme Sistemleri', active: true }] })
})

describe('TechErrorReportSettings', () => {
  it('yükler: "herkes görür" notu, kanal satırları (yönetici alıcı / PO / dönem durumu / onay / kapalı takım / e-posta), kayıt çubuğu değişiklik olmadan sessiz', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    render(<TechErrorReportSettings />)
    expect(await screen.findByText('Seçim yok — modülü herkes görür.')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="tes-list"]')).toHaveAttribute('data-layout', 'table')   // jsdom: kap 0 → tablo
    const list = rows()
    expect(list).toHaveLength(2)
    const mobil = within(list[0])
    expect(mobil.getByText('Mobil')).toBeInTheDocument()
    expect(mobil.getByText('Yönetici alıcı: 2')).toBeInTheDocument()
    expect(mobil.getByText('PO onaylayıcı: 1')).toBeInTheDocument()
    expect(mobil.getByText('Onay bekliyor')).toBeInTheDocument()
    expect(mobil.getByText('Görünür')).toBeInTheDocument()
    expect(mobil.getByText('Tanımlı')).toBeInTheDocument()
    expect(mobil.getByText('Taslak')).toBeInTheDocument()
    expect(list[0]).toHaveAttribute('data-flow', 'approval')
    expect(list[0]).toHaveAttribute('data-status', 'open')
    const pos = within(list[1])
    expect(pos.getByText('Onay akışı yok')).toBeInTheDocument()
    expect(pos.getByText('PO yok')).toBeInTheDocument()
    expect(pos.getByText('Modül bu takıma kapalı')).toBeInTheDocument()
    expect(pos.getByText('Dönem raporu yok')).toBeInTheDocument()
    expect(pos.getByText('Eksik — rapor gönderilemez')).toBeInTheDocument()
    expect(pos.getByText('Pasif')).toBeInTheDocument()
    expect(list[1]).toHaveAttribute('data-flow', 'team')
    expect(list[1]).toHaveAttribute('data-status', 'inactive')
    // KPI kartları (dönem etiketiyle) + sayaç satırı
    expect(screen.getByText('Eylül 2026 raporu gönderilen')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="tes-count"]')).toHaveTextContent('2 / 2 kanal gösteriliyor')
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    expect(bar).not.toBeNull()
    expect(bar).not.toHaveAttribute('data-dirty')
    expect(api.admin.saveTechErrorSettings).not.toHaveBeenCalled()
  })

  it('sunucu salt okunur (kapsamlı müdür): seçici kilitli, kayıt çubuğu yok, "yalnız global yöneticiler" notu; sunucu hatası → tehlike bloğu + yeniden dene', async () => {
    api.admin.techErrorSettings.mockResolvedValueOnce(settings({ team_ids: [1], read_only: true }))
    render(<TechErrorReportSettings />)
    expect(await screen.findByText(/yalnız global yöneticiler/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    expect(screen.queryByText('Seçim yok — modülü herkes görür.')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Modülü gören takımlar' })).toBeDisabled()
    expect(rows()).toHaveLength(2)   // kanal özeti salt okunurda da görünür

    api.admin.techErrorSettings.mockResolvedValueOnce({ success: false, error: 'Veritabanı yok' }).mockResolvedValueOnce(settings())
    render(<TechErrorReportSettings />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Veritabanı yok')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(document.querySelector('[data-slot="tes-empty"]')).toBeNull()   // okuma hatası "kanal yok" DEĞİL
    fireEvent.click(screen.getByRole('button', { name: /Tekrar dene|Yeniden dene/ }))
    await waitFor(() => expect(api.admin.techErrorSettings).toHaveBeenCalledTimes(3))
    expect(await screen.findByText('Seçim yok — modülü herkes görür.')).toBeInTheDocument()
  })

  it('kaydet: seçilen takımlar gönderilir, yanıt listeyi günceller', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [1, 2] }))
    api.admin.saveTechErrorSettings.mockResolvedValue({ success: true, data: { team_ids: [2] } })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    // Seçiciyi aç, seçili takımı listeden kaldır (ui/MultiTeamSelect: Popover + çoklu listbox)
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Modülü gören takımlar' }))
    const opt = (await screen.findAllByText('Dijital Kanallar')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    await waitFor(() => expect(bar).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(bar).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorSettings).toHaveBeenCalledWith({ team_ids: [2] }))
    await waitFor(() => expect(bar).not.toHaveAttribute('data-dirty'))
  })

  it('kayıt hatası: toast, seçim korunur, çubuk kirli kalır', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [1] }))
    api.admin.saveTechErrorSettings.mockResolvedValue({ success: false, error: 'Seçilen takımlardan biri bulunamadı; listeyi yenileyin.' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Modülü gören takımlar' }))
    const opt = (await screen.findAllByText('Ödeme Sistemleri')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    await waitFor(() => expect(bar).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(bar).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorSettings).toHaveBeenCalledWith({ team_ids: [1, 2] }))
    expect(await screen.findByText(/Seçilen takımlardan biri bulunamadı/)).toBeInTheDocument()
    expect(bar).toHaveAttribute('data-dirty', 'true')
  })

  it('KPI kartı süzgeçtir: "Raporu başlanmamış" yalnız o kanalları listeler (aria-pressed), ikinci tık kaldırır; "Kanal" kartı temizler', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [...CHANNELS, THIRD] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    expect(names()).toEqual(['İnternet Şubesi', 'Mobil', 'POS'])   // aktifler önce, ad A→Z
    const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
    expect(tile('missing').querySelector('[data-slot="stat-value"]')).toHaveTextContent('1')
    expect(tile('noEmail').querySelector('[data-slot="stat-value"]')).toHaveTextContent('2')
    expect(tile('hidden').querySelector('[data-slot="stat-value"]')).toHaveTextContent('1')
    expect(tile('exec').querySelector('[data-slot="stat-value"]')).toHaveTextContent('1')
    fireEvent.click(tile('missing'))
    expect(tile('missing')).toHaveAttribute('aria-pressed', 'true')
    expect(names()).toEqual(['İnternet Şubesi'])
    expect(document.querySelector('[data-slot="tes-count"]')).toHaveTextContent('1 / 3 kanal gösteriliyor')
    expect(screen.getByRole('button', { name: 'Süzgeçleri temizle' })).toBeInTheDocument()
    fireEvent.click(tile('missing'))
    expect(tile('missing')).toHaveAttribute('aria-pressed', 'false')
    expect(names()).toHaveLength(3)
    fireEvent.click(tile('hidden'))
    expect(names()).toEqual(['POS'])
    fireEvent.click(tile('all'))
    expect(names()).toHaveLength(3)
  })

  it('arama Türkçe katlar: "internet" → İnternet Şubesi; takım adıyla da bulur; eşleşme yoksa "süzgeçlerle eşleşen kanal yok"', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [...CHANNELS, THIRD] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const search = screen.getByRole('searchbox', { name: 'Kanal ya da takım ara' })
    fireEvent.change(search, { target: { value: 'internet' } })
    expect(names()).toEqual(['İnternet Şubesi'])
    fireEvent.change(search, { target: { value: 'odeme' } })
    expect(names()).toEqual(['İnternet Şubesi', 'POS'])
    fireEvent.change(search, { target: { value: 'yok böyle kanal' } })
    expect(rows()).toHaveLength(0)
    expect(screen.getByText('Süzgeçlerle eşleşen kanal yok')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Süzgeçleri temizle' })[0])
    expect(names()).toHaveLength(3)
    expect(search).toHaveValue('')
  })

  it('takım ve durum süzgeçleri: takım seçimi (SearchableSelect) + durum kutusu (NativeSelect) VE ile birleşir; durum kutusu KPI kartıyla aynı durumu sürer', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [...CHANNELS, THIRD] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Takım' }))
    const opt = (await screen.findAllByText('Ödeme Sistemleri')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    await waitFor(() => expect(names()).toEqual(['İnternet Şubesi', 'POS']))
    // KPI sayımları arama + takım süzgecinin üzerinden: Ödeme Sistemleri'nde 1 pasif, 1 eksik rapor
    expect(document.querySelector('[data-slot="stat-item"][data-key="all"] [data-slot="stat-value"]')).toHaveTextContent('2')
    expect(document.querySelector('[data-slot="stat-item"][data-key="missing"] [data-slot="stat-value"]')).toHaveTextContent('1')
    const status = screen.getByLabelText('Durum')
    fireEvent.change(status, { target: { value: 'inactive' } })
    expect(names()).toEqual(['POS'])
    expect(document.querySelector('[data-slot="stat-item"][data-key="all"]')).toHaveAttribute('aria-pressed', 'false')
    fireEvent.change(status, { target: { value: 'missing' } })
    expect(names()).toEqual(['İnternet Şubesi'])
    expect(document.querySelector('[data-slot="stat-item"][data-key="missing"]')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(status, { target: { value: 'all' } })
    expect(names()).toEqual(['İnternet Şubesi', 'POS'])
    // Sıralama: takıma göre → aynı takımda aktifler önce
    fireEvent.change(screen.getByLabelText('Sıralama'), { target: { value: 'status' } })
    expect(names()).toEqual(['İnternet Şubesi', 'POS'])
    fireEvent.click(screen.getByRole('button', { name: 'Süzgeçleri temizle' }))
    expect(names()).toHaveLength(3)
  })

  it('"Kanalı aç" (kanal adı) kanal sayfasına gider: sm:navigate → channelreports + te_view=channel + te_ch', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    try {
      render(<TechErrorReportSettings />)
      await screen.findByText('Mobil')
      fireEvent.click(screen.getByRole('button', { name: 'Kanalı aç — POS' }))
      expect(seen).toEqual([{ tab: 'channelreports', params: { te_view: 'channel', te_ch: '20' } }])
    } finally {
      window.removeEventListener('sm:navigate', onNav)
    }
  })

  it('yenileme hatası: liste korunur, tehlike afişi + Tekrar dene; başarılı yenileme afişi kaldırır', async () => {
    api.admin.techErrorSettings.mockResolvedValueOnce(settings()).mockRejectedValueOnce(new Error('Ağ bağlantısı kesildi'))
      .mockResolvedValueOnce(settings({ channels: [...CHANNELS, THIRD] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    fireEvent.click(document.querySelector('[data-slot="tes-refresh"]'))
    const banner = await screen.findByText('Ağ bağlantısı kesildi')
    expect(banner.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    expect(rows()).toHaveLength(2)   // son okunan değerler duruyor
    fireEvent.click(within(banner.closest('[data-slot="alert"]')).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(rows()).toHaveLength(3))
    expect(screen.queryByText('Ağ bağlantısı kesildi')).toBeNull()
  })

  it('kanal yok: başarılı boş yanıtta nötr "Henüz kanal yok." bloğu; KPI ve araç çubuğu çizilmez', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [] }))
    render(<TechErrorReportSettings />)
    expect(await screen.findByText('Henüz kanal yok.')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="tes-empty"] [data-slot="empty"]')).toHaveAttribute('data-tone', 'neutral')
    expect(document.querySelector('[data-slot="tes-kpis"]')).toBeNull()
    expect(document.querySelector('[data-slot="tes-toolbar"]')).toBeNull()
  })

  it('eski sunucu satırı (e-posta / görünürlük alanı yok): rozetler "Bilinmiyor", KPI eksik / kapalı sayımı 0 ama yeşil değil', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [{ id: 40, name: 'Eski', active: true, team_id: 1, team_name: 'Dijital Kanallar',
      executive_count: 0, approver_count: 0, period_report: null }] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Eski')
    const row = rows()[0]
    expect(row.querySelector('[data-slot="tes-email"]')).toHaveAttribute('data-state', 'unknown')
    expect(row.querySelector('[data-slot="tes-visibility"]')).toHaveAttribute('data-state', 'unknown')
    expect(within(row).getAllByText('Bilinmiyor')).toHaveLength(2)
  })

  it('sayfalama: 30 kanal → 25 + 5 (panel ön ayarı), sayfa 2 kalanları gösterir', async () => {
    const many = Array.from({ length: 30 }, (_, i) => ({ ...THIRD, id: 100 + i, name: `Kanal ${String(i + 1).padStart(2, '0')}` }))
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: many }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Kanal 01')
    expect(rows()).toHaveLength(25)
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect(screen.getByText('Kanal 30')).toBeInTheDocument()
  })
})
