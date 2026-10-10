import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { response, SETTINGS, LONG_HOST } from './helpers/executiveFixtures.js'

// Aylık Yönetici Özeti sayfası (2026-10-10): bölümler GENEL çizilir (bilinmeyen bölüm de), metinler i18n + biçimli
// parametreler, ay seçimi URL'ye yazılır, gönderilen rapor ↔ canlı hesap, PDF indirme, 403 → erişim yok, ayar penceresi
// (yalnız global yönetici; alan altı doğrulama, test postası yalnız kendine).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    executiveSummary: {
      get: vi.fn(), getSettings: vi.fn(), saveSettings: vi.fn(), sendTest: vi.fn(), runNow: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ExecutiveSummaryPage from '../components/executive/ExecutiveSummaryPage.jsx'

const ex = () => api.executiveSummary

describe('Yönetici Özeti sayfası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=executive')
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
    ex().get.mockResolvedValue(response())
    ex().getSettings.mockResolvedValue({ success: true, data: SETTINGS })
  })

  it('bölümler sırasıyla ve genel çizilir; bilinmeyen bölüm sunucu metniyle görünür', async () => {
    const { container } = render(<ExecutiveSummaryPage globalAdmin />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    const keys = [...container.querySelectorAll('[data-slot="ex-section"]')].map((el) => el.getAttribute('data-key'))
    expect(keys).toEqual(['availability', 'noise', 'expirations', 'renewals', 'tls-grade'])
    expect(screen.getByRole('region', { name: 'TLS yapılandırma notu' })).toBeInTheDocument()
    expect(screen.getAllByText('Sertifikaların %90\'ı A notunda.').length).toBeGreaterThan(0)
    expect(ex().get).toHaveBeenCalledWith({ month: null, live: false, fresh: false })
  })

  it('üst şerit: genel durum, bölüm göstergeleri, biçimli hükümler', async () => {
    const { container } = render(<ExecutiveSummaryPage globalAdmin />)
    const head = await screen.findByRole('region', { name: /Eylül 2026/ })
    expect(within(head).getByText('Aksiyon gerekli')).toBeInTheDocument()
    const tiles = container.querySelectorAll('[data-slot="ex-headline-kpis"] [data-slot="ex-kpi"]')
    expect([...tiles].map((x) => x.getAttribute('data-code'))).toEqual(['org_availability', 'total_alarms', 'within30', 'on_time_pct'])
    expect(within(head).getByText('Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.')).toBeInTheDocument()
    expect(within(head).getByText('Bu ay 120 alarm açıldı; geçen aya (80) göre %50 artış.')).toBeInTheDocument()
  })

  it('tablolar: boş takım "Takımsız", gruplanmamış hizmet, kırpma notu, durum hücresi metinle; rapor anı rozeti', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const avail = await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    expect(within(avail).getAllByText('Takımsız').length).toBeGreaterThan(0)
    expect(within(avail).getAllByText('Gruplanmamış').length).toBeGreaterThan(0)
    expect(within(avail).getByText(/\+12 kayıt daha/)).toBeInTheDocument()
    expect(within(avail).getAllByText('Kritik').length).toBeGreaterThan(0)
    const exp = screen.getByRole('region', { name: 'Yaklaşan sertifika bitişleri' })
    expect(within(exp).getByText(/Rapor anı:/)).toBeInTheDocument()
    expect(within(exp).getAllByText('3 gün önce doldu').length).toBeGreaterThan(0)
    expect(within(exp).getAllByText(LONG_HOST).length).toBeGreaterThan(0)
  })

  it('ay seçimi yeniden yükler ve URL\'ye ex_m yazar; varsayılan ay yazılmaz', async () => {
    render(<ExecutiveSummaryPage globalAdmin />)
    const select = await screen.findByLabelText('Ay')
    expect(select.value).toBe('2026-09')
    fireEvent.change(select, { target: { value: '2026-08' } })
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: '2026-08', live: false, fresh: false }))
    await waitFor(() => expect(window.location.search).toContain('ex_m=2026-08'))
  })

  it('gönderilen rapor → "Canlı hesapla" live=1 ile yeniden ister; Yenile fresh=1', async () => {
    ex().get.mockResolvedValue(response({ source: 'snapshot' }))
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Canlı hesapla' }))
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: null, live: true, fresh: false }))
    fireEvent.click(screen.getByRole('button', { name: 'Yenile' }))
    await waitFor(() => expect(ex().get).toHaveBeenLastCalledWith({ month: null, live: true, fresh: true }))
  })

  it('PDF indir seçili ayı ister; hata tostu açıklayıcı', async () => {
    ex().downloadPdf.mockResolvedValue({ success: false, status: 503 })
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'PDF indir' }))
    await waitFor(() => expect(ex().downloadPdf).toHaveBeenCalledWith({ month: '2026-09', live: false }))
    expect(await screen.findByText(/PDF indirilemedi/)).toBeInTheDocument()
  })

  it('403 → erişim yok paneli (ham hata değil); ayar düğmesi yalnız global yöneticiye', async () => {
    ex().get.mockResolvedValue({ success: false, status: 403, error: 'FORBIDDEN' })
    render(<ExecutiveSummaryPage />)
    expect(await screen.findByText('Yönetici özetine erişiminiz yok')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ayarlar ve gönderim' })).toBeNull()
  })

  it('AUDIT (global yönetici değil) ayar düğmesini görmez', async () => {
    ex().get.mockResolvedValue(response({}, { can_configure: false }))
    render(<ExecutiveSummaryPage globalAdmin={false} />)
    await screen.findByRole('region', { name: 'Erişilebilirlik hedefi uyumu' })
    expect(screen.queryByRole('button', { name: 'Ayarlar ve gönderim' })).toBeNull()
  })

  it('ayar penceresi: geçersiz adres alanın altında, kayıt gitmez; düzeltince doğru gövdeyle kaydeder', async () => {
    ex().saveSettings.mockImplementation(async (body) => ({ success: true, data: { ...SETTINGS, ...body,
      include_global_admins: body.include_global_admins, availability_target: Number(body.availability_target) } }))
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Ayarlar ve gönderim' }))
    const dialog = await screen.findByRole('dialog')
    const recipients = await within(dialog).findByRole('textbox', { name: /E-posta adresleri/ })
    expect(within(dialog).getByText(/3 alıcı \(1 adres \+ 2 global yönetici\)/)).toBeInTheDocument()
    expect(within(dialog).getByText(/pasif bir kullanıcıya ait/)).toBeInTheDocument()

    fireEvent.change(recipients, { target: { value: 'yonetim@example.com, kotu-adres' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kaydet' }))
    expect(await within(dialog).findByText(/Geçersiz adres: kotu-adres/)).toBeInTheDocument()
    expect(ex().saveSettings).not.toHaveBeenCalled()

    fireEvent.change(recipients, { target: { value: 'yonetim@example.com, cto@example.com' } })
    fireEvent.click(within(dialog).getByRole('switch', { name: 'Zamanlanmış gönderim' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(ex().saveSettings).toHaveBeenCalledWith({
      enabled: true, cron: '0 0 9 1 * *', recipients: 'yonetim@example.com, cto@example.com', include_global_admins: true,
      availability_target: '99.9', renewal_target_days: '30',
    }))
  })

  it('ayar penceresi: sunucu alan hatası (400 field) alanın altına; test postası seçili ay için', async () => {
    ex().saveSettings.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'availability_target',
      error: 'Erişilebilirlik hedefi 90 ile 100 arasında bir yüzde olmalı (ör. 99,9).' })
    ex().sendTest.mockResolvedValue({ success: true, message: 'Test e-postası ben@example.com adresine gönderildi.' })
    render(<ExecutiveSummaryPage globalAdmin />)
    fireEvent.click(await screen.findByRole('button', { name: 'Ayarlar ve gönderim' }))
    const dialog = await screen.findByRole('dialog')
    const target = await within(dialog).findByRole('textbox', { name: /Erişilebilirlik hedefi \(%\)/ })
    fireEvent.change(target, { target: { value: '99.95' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kaydet' }))
    expect(await within(dialog).findByText(/90 ile 100 arasında/)).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Bana test e-postası gönder' }))
    await waitFor(() => expect(ex().sendTest).toHaveBeenCalledWith('2026-09'))
    expect(await screen.findByText(/ben@example.com adresine gönderildi/)).toBeInTheDocument()
  })
})
