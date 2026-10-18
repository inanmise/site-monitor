import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Ayarlar → Platform → Teknik hata raporu (2026-10-17, S6; yeniden tasarım aynı gün): gören takımlar (boş = herkes), salt
// okunur müdür, kanal özeti — KPI kartları süzgeç, arama Türkçe katlar, takım / durum süzgeçleri, okuma hatası → tehlike bloğu
// + Tekrar dene, "Kanalı aç" kanal sayfasına gider. jsdom'da kap genişliği 0 → TABLO (kart dalı: TechErrorReportSettings.mobile).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn(),
      techErrorReminderSettings: vi.fn(), saveTechErrorReminderSettings: vi.fn(),
    },
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
    fireEvent.click(screen.getByRole('button', { name: 'Süzgeçleri temizle' }))
    expect(names()).toEqual(['İnternet Şubesi', 'Mobil', 'POS'])          // ad: aktifler önce, Türkçe harf sırası
    // Sıralama takıma göre: Dijital Kanallar (Mobil) önce, aynı takımda aktifler önce — ad sırasından FARKLI
    fireEvent.change(screen.getByLabelText('Sıralama'), { target: { value: 'team' } })
    expect(names()).toEqual(['Mobil', 'İnternet Şubesi', 'POS'])
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

  it('eski sunucu satırı (e-posta / görünürlük alanı yok): rozetler "Bilinmiyor"; bilinmeyen alan "e-postası yok" / "kapalı takım" sayımına girmez', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [{ id: 40, name: 'Eski', active: true, team_id: 1, team_name: 'Dijital Kanallar',
      executive_count: 0, approver_count: 0, period_report: null }] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Eski')
    const row = rows()[0]
    expect(row.querySelector('[data-slot="tes-email"]')).toHaveAttribute('data-state', 'unknown')
    expect(row.querySelector('[data-slot="tes-visibility"]')).toHaveAttribute('data-state', 'unknown')
    expect(within(row).getAllByText('Bilinmiyor')).toHaveLength(2)
    expect(document.querySelector('[data-slot="stat-item"][data-key="noEmail"] [data-slot="stat-value"]')).toHaveTextContent('0')
    expect(document.querySelector('[data-slot="stat-item"][data-key="hidden"] [data-slot="stat-value"]')).toHaveTextContent('0')
  })

  it('inceleme 2026-10-17: Yenile kaydedilmemiş seçimi korur; kayıttan sonra görünürlük rozetleri kaydedilen seçimi izler', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [] }))
    api.admin.saveTechErrorSettings.mockResolvedValue({ success: true, data: { team_ids: [1] } })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Modülü gören takımlar' }))
    const opt = (await screen.findAllByText('Dijital Kanallar')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    const bar = document.querySelector('[data-slot="settings-save-bar"]')
    await waitFor(() => expect(bar).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(document.querySelector('[data-slot="tes-refresh"]'))
    await waitFor(() => expect(api.admin.techErrorSettings).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('[data-slot="tes-refresh"]')).not.toBeDisabled())
    expect(bar).toHaveAttribute('data-dirty', 'true')                       // seçim yerinde
    fireEvent.click(within(bar).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorSettings).toHaveBeenCalledWith({ team_ids: [1] }))
    // Ödeme Sistemleri artık modülü göremez: POS satırının rozeti "kapalı", KPI "kapalı takım" sayımı güncel
    await waitFor(() => expect(document.querySelector('[data-channel="20"] [data-slot="tes-visibility"]')).toHaveAttribute('data-state', 'hidden'))
    expect(document.querySelector('[data-channel="10"] [data-slot="tes-visibility"]')).toHaveAttribute('data-state', 'visible')
  })

  it('inceleme 2026-10-17: salt okunur görüntüleyici modülü kapalı takımın kanalını açamaz (bağlantı yok, açıklama var)', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ read_only: true }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const pos = document.querySelector('[data-channel="20"]')
    expect(pos.querySelector('[data-slot="tes-open"]')).toBeNull()
    expect(document.querySelector('[data-channel="10"] [data-slot="tes-open"]')).not.toBeNull()
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

// 12. sürüm (2026-10-17, C9 / C10 / E21): "Hatırlatmalar ve posta sınırları" kartı — değerler AYRI uçtan
// (`/settings/reminders`, inceleme 2026-10-18), alan hataları alanın altında, PUT YALNIZ değişen alanlar ve görünürlük PUT'u
// hiç gitmez, kapsamlı müdür salt okunur, eski pod (404 / 405) → "henüz desteklemiyor" notu, kayıt sürerken Yenile kapalı ve
// kayıttan önce başlamış okuma kayıttan sonra gelirse düşer.
const REM = {
  team_ids: [], reminder_day: 5, reminder_repeat_days: 3, approval_reminder_days: 3, max_mail_mb: 15, mail_inline_mb: 6,
  reminder_push: true,
  limits: {
    reminder_day: { min: 1, max: 28, default: 5 }, reminder_repeat_days: { min: 0, max: 14, default: 3 },
    approval_reminder_days: { min: 1, max: 14, default: 3 }, max_mail_mb: { min: 5, max: 50, default: 15 },
    mail_inline_mb: { min: 0, max: 20, default: 6 },
  },
}
const remOk = (extra = {}) => ({ success: true, data: { ...REM, read_only: false, ...extra } })
const remCard = () => document.querySelector('[data-slot="tes-reminders"]')
const remBar = () => document.querySelector('[data-slot="tes-rem-save"] [data-slot="settings-save-bar"]')
const DAY = 'Hatırlatma günü (ayın kaçıncı günü)'

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

describe('TechErrorReportSettings — hatırlatmalar ve posta sınırları', () => {
  it('kart değerleri ayrı uçtan; "seçim yok — kapalı" notu; aralık ipucunda; eski pod (GET 404) → "henüz desteklemiyor" notu, alan yok', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValueOnce(remOk())
    const { unmount } = render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    expect(card).toHaveAttribute('data-state', 'ok')
    expect(within(card).getByText('Hatırlatmalar ve posta sınırları')).toBeInTheDocument()
    expect(within(card).getByText('Seçim yok — dönem hatırlatması kapalı.')).toBeInTheDocument()
    expect(within(card).getByLabelText(DAY)).toHaveValue(5)
    expect(within(card).getByLabelText('Tekrar aralığı (gün)')).toHaveValue(3)
    expect(within(card).getByLabelText('Onay hatırlatması (gün)')).toHaveValue(3)
    expect(within(card).getByLabelText('E-posta boyut sınırı (MB)')).toHaveValue(15)
    expect(within(card).getByLabelText('Gövdedeki görsellerin bütçesi (MB)')).toHaveValue(6)
    expect(within(card).getByRole('checkbox', { name: 'Takım üyelerine push bildirimi de gönder' })).toBeChecked()
    expect(within(card).getByText(/denetlenir \(1–28\)/)).toBeInTheDocument()
    expect(remBar()).not.toHaveAttribute('data-dirty')
    unmount()

    api.admin.techErrorReminderSettings.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Kaynak bulunamadı' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const old = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    expect(old).toHaveAttribute('data-state', 'unsupported')
    expect(within(old).getByText(/henüz tanımıyor/)).toBeInTheDocument()
    expect(within(old).queryByLabelText(DAY)).toBeNull()
    expect(remBar()).toBeNull()
  })

  it('kaydet: YALNIZ değişen alanlar AYRI uca gider (görünürlük PUT\'u hiç gitmez); yanıt değerleri kayıtlı sayılır, çubuk temizlenir', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk())
    api.admin.saveTechErrorReminderSettings.mockResolvedValue({ success: true, data: { ...REM, read_only: false, team_ids: [2], reminder_day: 7, reminder_push: false } })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    fireEvent.mouseDown(within(card).getByRole('combobox', { name: 'Hatırlatma alan takımlar' }))
    const opt = (await screen.findAllByText('Ödeme Sistemleri')).map((el) => el.closest('[role="option"]')).find(Boolean)
    fireEvent.mouseDown(opt)
    fireEvent.change(within(card).getByLabelText(DAY), { target: { value: '7' } })
    fireEvent.click(within(card).getByRole('checkbox', { name: 'Takım üyelerine push bildirimi de gönder' }))
    await waitFor(() => expect(remBar()).toHaveAttribute('data-dirty', 'true'))
    expect(document.querySelector('[data-slot="settings-save-bar"]')).not.toHaveAttribute('data-dirty')   // görünürlük çubuğu temiz
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorReminderSettings).toHaveBeenCalledWith({ reminder_team_ids: [2], reminder_day: 7, reminder_push: false }))
    expect(api.admin.saveTechErrorSettings).not.toHaveBeenCalled()
    await waitFor(() => expect(remBar()).not.toHaveAttribute('data-dirty'))
    expect(await screen.findByText('Hatırlatma ve posta ayarları kaydedildi.')).toBeInTheDocument()
    expect(within(card).queryByText('Seçim yok — dönem hatırlatması kapalı.')).toBeNull()
  })

  it('doğrulama alanın altında: aralık dışı gün, gövde ≥ sınır; istek gitmez; düzeltince hata kalkar; sunucu alan hatası da alanın altına düşer', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk())
    api.admin.saveTechErrorReminderSettings.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'approval_reminder_days',
      error: 'Onay hatırlatması 1 ile 14 gün arasında olmalı.' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    const day = within(card).getByLabelText(DAY)
    fireEvent.change(day, { target: { value: '29' } })
    fireEvent.change(within(card).getByLabelText('Gövdedeki görsellerin bütçesi (MB)'), { target: { value: '15' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    expect(await within(card).findByText('Bir tam sayı girin: 1 ile 28 arasında olmalı.')).toBeInTheDocument()
    expect(within(card).getByText(/Gövde bütçesi \(15 MB\) boyut sınırından \(15 MB\) küçük olmalı/)).toBeInTheDocument()
    expect(day).toHaveAttribute('aria-invalid', 'true')
    expect(day.closest('[data-field]')).toHaveAttribute('data-field', 'reminder_day')
    expect(api.admin.saveTechErrorReminderSettings).not.toHaveBeenCalled()
    fireEvent.change(day, { target: { value: '10' } })
    expect(within(card).queryByText('Bir tam sayı girin: 1 ile 28 arasında olmalı.')).toBeNull()
    fireEvent.change(within(card).getByLabelText('Gövdedeki görsellerin bütçesi (MB)'), { target: { value: '8' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorReminderSettings).toHaveBeenCalledWith({ reminder_day: 10, mail_inline_mb: 8 }))
    expect(await within(card).findByText('Onay hatırlatması 1 ile 14 gün arasında olmalı.')).toBeInTheDocument()
    expect(remBar()).toHaveAttribute('data-dirty', 'true')
  })

  it('kapsamlı müdür (read_only): kart alanları kilitli, kartın kayıt çubuğu yok', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ read_only: true }))
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk({ team_ids: [1], read_only: true }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    expect(within(card).getByLabelText(DAY)).toBeDisabled()
    expect(within(card).getByLabelText('E-posta boyut sınırı (MB)')).toBeDisabled()
    expect(within(card).getByRole('checkbox', { name: 'Takım üyelerine push bildirimi de gönder' })).toBeDisabled()
    expect(within(card).getByRole('combobox', { name: 'Hatırlatma alan takımlar' })).toBeDisabled()
    expect(remBar()).toBeNull()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
  })

  it('değişiklik yokken Kaydet pasif; Vazgeç taslağı kayıtlı değerlere döndürür', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk())
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    expect(within(remBar()).getByRole('button', { name: /Kaydet/ })).toBeDisabled()
    fireEvent.change(within(card).getByLabelText('Tekrar aralığı (gün)'), { target: { value: '0' } })
    await waitFor(() => expect(remBar()).toHaveAttribute('data-dirty', 'true'))
    fireEvent.click(within(remBar()).getByRole('button', { name: /Vazgeç/ }))
    expect(within(card).getByLabelText('Tekrar aralığı (gün)')).toHaveValue(3)
    expect(remBar()).not.toHaveAttribute('data-dirty')
    expect(api.admin.saveTechErrorReminderSettings).not.toHaveBeenCalled()
  })

  it('inceleme 2026-10-18: kayıt eski pod\'a düştü (PUT 405) → "henüz desteklemiyor" notu, alanlar kilitli, kayıt çubuğu yok, taslak yerinde; görünürlük PUT\'u gitmez', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [1] }))
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk())
    api.admin.saveTechErrorReminderSettings.mockResolvedValue({ success: false, status: 405, code: 'METHOD_NOT_ALLOWED',
      error: 'Bu işlem bu adreste desteklenmiyor; uygulama yeni bir sürüme güncellenmiş olabilir.' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    fireEvent.change(within(card).getByLabelText(DAY), { target: { value: '7' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(card).toHaveAttribute('data-state', 'unsupported'))
    expect(within(card).getByText(/hiçbir değişiklik kaydedilmedi/)).toBeInTheDocument()
    expect(within(card).getByLabelText(DAY)).toBeDisabled()
    expect(within(card).getByLabelText(DAY)).toHaveValue(7)
    expect(remBar()).toBeNull()
    expect(api.admin.saveTechErrorSettings).not.toHaveBeenCalled()
    expect(screen.queryByText('Hatırlatma ve posta ayarları kaydedildi.')).toBeNull()
  })

  it('inceleme 2026-10-18: kart kaydı sürerken Yenile kapalı; kayıttan ÖNCE başlamış okuma kayıttan SONRA gelirse düşer (eski değer "kayıtlı" görünmez, temiz form ezilmez)', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValueOnce(remOk())
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    const refresh = () => document.querySelector('[data-slot="tes-refresh"]')
    await waitFor(() => expect(refresh()).not.toBeDisabled())

    // 1) Yenile başladı (eski değerleri — gün 5 — taşıyan okuma askıda) …
    const lateRead = deferred()
    api.admin.techErrorReminderSettings.mockReturnValueOnce(lateRead.promise)
    fireEvent.click(refresh())
    await waitFor(() => expect(api.admin.techErrorReminderSettings).toHaveBeenCalledTimes(2))
    // 2) … kullanıcı günü 7 yapıp kaydeder (PUT askıda): Yenile kapalı
    const put = deferred()
    api.admin.saveTechErrorReminderSettings.mockReturnValueOnce(put.promise)
    fireEvent.change(within(card).getByLabelText(DAY), { target: { value: '7' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorReminderSettings).toHaveBeenCalledWith({ reminder_day: 7 }))
    expect(refresh()).toBeDisabled()
    // 3) Kayıt biter: gün 7 kayıtlı, çubuk temiz, Yenile yeniden açık
    put.resolve({ success: true, data: { ...REM, read_only: false, reminder_day: 7 } })
    await waitFor(() => expect(remBar()).not.toHaveAttribute('data-dirty'))
    expect(within(card).getByLabelText(DAY)).toHaveValue(7)
    await waitFor(() => expect(refresh()).not.toBeDisabled())
    // 4) Kayıttan ÖNCE başlamış okuma (gün 5) ŞİMDİ gelir: düşmeli — temiz form 5'e dönmez, taban 7 kalır
    lateRead.resolve(remOk())
    await new Promise((r) => setTimeout(r, 0))
    await new Promise((r) => setTimeout(r, 0))
    expect(within(card).getByLabelText(DAY)).toHaveValue(7)
    expect(remBar()).not.toHaveAttribute('data-dirty')
    fireEvent.change(within(card).getByLabelText(DAY), { target: { value: '9' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Vazgeç/ }))
    expect(within(card).getByLabelText(DAY)).toHaveValue(7)
  })

  it('inceleme 2026-10-18: hatırlatma okuması başarısız → kartta tehlike bloğu + Tekrar dene (değer uydurulmaz); yenileme hatası son değerleri korur', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    api.admin.techErrorReminderSettings.mockResolvedValueOnce({ success: false, status: 503, error: 'Veritabanına şu an ulaşılamıyor.' })
      .mockResolvedValueOnce(remOk()).mockRejectedValueOnce(new Error('Ağ bağlantısı kesildi'))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    expect(card).toHaveAttribute('data-state', 'failed')
    expect(within(card).getByText('Veritabanına şu an ulaşılamıyor.')).toBeInTheDocument()
    expect(within(card).queryByLabelText(DAY)).toBeNull()
    fireEvent.click(within(card).getByRole('button', { name: /Tekrar dene/ }))
    await waitFor(() => expect(remCard()).toHaveAttribute('data-state', 'ok'))
    expect(within(remCard()).getByLabelText(DAY)).toHaveValue(5)
    fireEvent.click(document.querySelector('[data-slot="tes-refresh"]'))
    await waitFor(() => expect(remCard()).toHaveAttribute('data-state', 'failed'))
    expect(within(remCard()).getByLabelText(DAY)).toHaveValue(5)                // son okunan değer duruyor
    expect(within(remCard()).getByText('Ağ bağlantısı kesildi')).toBeInTheDocument()
  })
})

describe('TechErrorReportSettings — inceleme 2026-10-18', () => {
  it('silinmiş takım (rehberde yok) seçili kalırsa seçiciler "Silinmiş takım" der — çıplak "#99" yok; rehber okunamazsa "Takım adı okunamadı"', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [1, 99] }))
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk({ team_ids: [99] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const vis = screen.getByRole('combobox', { name: 'Modülü gören takımlar' })
    expect(vis).toHaveTextContent('Silinmiş takım')
    expect(vis).not.toHaveTextContent('#99')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    const remTeams = within(card).getAllByRole('combobox').find((el) => /Silinmiş takım/.test(el.textContent))
    expect(remTeams).toBeTruthy()
    expect(card).not.toHaveTextContent('#99')
  })

  it('takım rehberi okunamazsa rehberde olmayan seçili takım "Takım adı okunamadı" (silinmiş denmez)', async () => {
    api.teams.directory.mockResolvedValue({ success: false, error: 'yok' })
    api.admin.techErrorSettings.mockResolvedValue(settings({ team_ids: [99] }))
    api.admin.techErrorReminderSettings.mockResolvedValue(remOk())
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const vis = screen.getByRole('combobox', { name: 'Modülü gören takımlar' })
    expect(vis).toHaveTextContent('Takım adı okunamadı')
    expect(vis).not.toHaveTextContent('Silinmiş takım')
    expect(vis).not.toHaveTextContent('#99')
  })

  it('bilinmeyen yönetici alıcı / PO sayısı "—" (bilinmiyor ≠ 0: "PO yok" / "onay akışı yok" denmez)', async () => {
    const unknown = { ...CHANNELS[0], executive_count: undefined, approver_count: null }
    api.admin.techErrorSettings.mockResolvedValue(settings({ channels: [unknown] }))
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const row = within(rows()[0])
    expect(row.getByText('PO onaylayıcı: —')).toHaveAttribute('data-state', 'unknown')
    expect(row.getByText('Yönetici alıcı: —')).toHaveAttribute('data-state', 'unknown')
    expect(row.queryByText('PO yok')).toBeNull()
    expect(row.queryByText('Onay akışı yok')).toBeNull()
  })

  it('kayıt sürerken "Tekrar dene" düğmeleri (sayfa yenileme afişi + kartın eski değer afişi) kapalı; kayıt bitince açılır', async () => {
    api.admin.techErrorSettings.mockResolvedValueOnce(settings()).mockResolvedValueOnce({ success: false, error: 'Yenileme başarısız oldu' })
    api.admin.techErrorReminderSettings.mockResolvedValueOnce(remOk()).mockResolvedValueOnce({ success: false, status: 503, error: 'Hatırlatma okunamadı' })
    render(<TechErrorReportSettings />)
    await screen.findByText('Mobil')
    const card = await waitFor(() => { const c = remCard(); expect(c).not.toBeNull(); return c })
    fireEvent.click(document.querySelector('[data-slot="tes-refresh"]'))
    const pageRetry = await waitFor(() => { const b = document.querySelector('[data-slot="tes-refresh-retry"]'); expect(b).not.toBeNull(); return b })
    const cardRetry = await waitFor(() => { const b = card.querySelector('[data-slot="tes-rem-retry"]'); expect(b).not.toBeNull(); return b })
    await waitFor(() => expect(pageRetry).not.toBeDisabled())
    expect(cardRetry).not.toBeDisabled()
    const put = deferred()
    api.admin.saveTechErrorReminderSettings.mockReturnValueOnce(put.promise)
    fireEvent.change(within(card).getByLabelText(DAY), { target: { value: '7' } })
    fireEvent.click(within(remBar()).getByRole('button', { name: /Kaydet/ }))
    await waitFor(() => expect(api.admin.saveTechErrorReminderSettings).toHaveBeenCalledWith({ reminder_day: 7 }))
    expect(document.querySelector('[data-slot="tes-refresh-retry"]')).toBeDisabled()
    expect(card.querySelector('[data-slot="tes-rem-retry"]')).toBeDisabled()
    put.resolve({ success: true, data: { ...REM, read_only: false, reminder_day: 7 } })
    await waitFor(() => expect(document.querySelector('[data-slot="tes-refresh-retry"]')).not.toBeDisabled())
  })
})
