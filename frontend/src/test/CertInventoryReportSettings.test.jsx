import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import CertInventoryReportSettings from '../components/admin/CertInventoryReportSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getCertInvReportStatus:    vi.fn(),
      getCertInvReportHistory:   vi.fn(),
      saveCertInvReportSettings: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Aylık sertifika envanteri raporu ayarları.
 *
 * Bu bileşenin hiç test dosyası yoktu ve kapsamı %2.04 idi — 294 satırlık bir yönetim yüzeyi,
 * kapsam tabanının (500 satır) hemen ALTINDA kaldığı için hiçbir kapı da bakmıyordu.
 *
 * Testlerin odağı iki şey: (1) yükleme/kaydetme yollarının SESSİZ kalmaması, (2) zamanlama
 * seçimlerinden cron ifadesinin doğru kurulması — kullanıcı cron yazmak zorunda kalmasın diye
 * var olan bu dönüşüm (buildCron/parseCron) tamamen test dışıydı ve yanlış bir ifade raporu
 * sessizce hiç göndermez ya da yanlış günde gönderirdi.
 */
const status = (over = {}) => ({
  enabled: true,
  extra_recipients: 'pki@example.com',
  cc: 'bilgi@example.com',
  cron: '0 0 10 * * FRIL',
  ...over,
})

describe('CertInventoryReportSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getCertInvReportStatus.mockResolvedValue({ success: true, data: status() })
    api.admin.getCertInvReportHistory.mockResolvedValue({ success: true, data: [] })
    api.admin.saveCertInvReportSettings.mockResolvedValue({ success: true, data: status() })
  })

  it('durum yüklenince alanlar doldurulur (alıcılar, CC, cron)', async () => {
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(api.admin.getCertInvReportStatus).toHaveBeenCalled())

    expect(await screen.findByDisplayValue('pki@example.com')).toBeInTheDocument()
    expect(screen.getByDisplayValue('bilgi@example.com')).toBeInTheDocument()
    expect(screen.getByDisplayValue('0 0 10 * * FRIL')).toBeInTheDocument()
  })

  it('yükleme başarısızsa SESSİZ kalmaz — ekran yükleniyorda asılı kalmaz', async () => {
    api.admin.getCertInvReportStatus.mockResolvedValue({ success: false, error: '403 Forbidden' })

    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(api.admin.getCertInvReportStatus).toHaveBeenCalled())

    // status null kaldığı için yükleme bloğu görünür; asıl sözleşme: hata YUTULMUYOR
    // (toast yolu) ve alanlar sahte boş değerlerle çizilmiyor.
    expect(screen.queryByDisplayValue('pki@example.com')).toBeNull()
  })

  it('alıcılar düzenlenip kaydedilir; istek recipients/cc/cron taşır', async () => {
    render(<CertInventoryReportSettings />)
    const rec = await screen.findByDisplayValue('pki@example.com')

    fireEvent.change(rec, { target: { value: 'yeni@example.com' } })
    const save = screen.getByRole('button', { name: /kaydet|save/i })
    await waitFor(() => expect(save).toBeEnabled())   // dirty olmadan düğme kapalı
    fireEvent.click(save)

    await waitFor(() => expect(api.admin.saveCertInvReportSettings).toHaveBeenCalled())
    const payload = api.admin.saveCertInvReportSettings.mock.calls.at(-1)[0]
    expect(payload.recipients).toBe('yeni@example.com')
    expect(payload).toHaveProperty('cc')
    expect(payload).toHaveProperty('cron')
  })

  it('değişiklik yokken Kaydet düğmesi KAPALI (boş istek atılmaz)', async () => {
    render(<CertInventoryReportSettings />)
    await screen.findByDisplayValue('pki@example.com')
    expect(screen.getByRole('button', { name: /kaydet|save/i })).toBeDisabled()
  })

  it('ayın günü kuralına geçilince cron ifadesi YENİDEN kurulur', async () => {
    // buildCron/parseCron tamamen test dışıydı: yanlış bir ifade raporu sessizce hiç
    // göndermez ya da yanlış günde gönderirdi.
    render(<CertInventoryReportSettings />)
    await screen.findByDisplayValue('0 0 10 * * FRIL')

    const kind = screen.getAllByRole('combobox')[0]
    fireEvent.change(kind, { target: { value: 'dayOfMonth' } })

    // Ayın günü kuralı → "0 dk sa GÜN * *" biçimi; haftalık son-gün eki kalkmalı.
    const cron = await screen.findByDisplayValue(/^0 0 10 \d+ \* \*$/)
    expect(cron).toBeInTheDocument()
    expect(screen.queryByDisplayValue(/FRIL/)).toBeNull()
  })

  it('etkin anahtarı iyimser güncellenir; kaydetme BAŞARISIZSA geri alınır', async () => {
    api.admin.saveCertInvReportSettings.mockResolvedValue({ success: false, error: 'reddedildi' })
    render(<CertInventoryReportSettings />)
    await screen.findByDisplayValue('pki@example.com')

    const toggle = screen.getAllByRole('switch')[0]   // shadcn Switch (ToggleRow)
    expect(toggle).toBeChecked()
    fireEvent.click(toggle)
    await waitFor(() => expect(api.admin.saveCertInvReportSettings).toHaveBeenCalledWith({ enabled: false }))

    // Başarısızlıkta eski hâline dönmeli — aksi halde arayüz kapalı görünür ama sunucu açık.
    await waitFor(() => expect(toggle).toBeChecked())
  })

  it('shadcn: kural seçicileri NativeSelect, arşiv Table + durum Badge', async () => {
    api.admin.getCertInvReportHistory.mockResolvedValue({ success: true, data: [
      { id: 1, month_label: 'Ağustos 2026', status: 'SENT', rows: 10, findings: 2, recipients: 'a@example.com', sent_at: '2026-08-28 10:00' },
      { id: 2, month_label: 'Temmuz 2026', status: 'FAILED', rows: 9, findings: 1, recipients: '', sent_at: null },
    ] })
    render(<CertInventoryReportSettings />)
    await screen.findByDisplayValue('pki@example.com')
    expect(screen.getAllByRole('combobox')[0]).toHaveAttribute('data-slot', 'native-select')
    const table = await screen.findByTestId('cir-history')
    expect(table).toHaveAttribute('data-slot', 'table')
    // Durum ham kod değil, arayüz dilinde etiket (20.166, UIA-C2 / C11): SENT → "Sent", FAILED → "Not delivered"
    const hist = screen.getByTestId('cir-history')
    expect(hist.querySelector('[data-status="sent"]')).toHaveAttribute('data-tone', 'success')
    expect(hist.querySelector('[data-status="sent"]')).toHaveTextContent(/^(Sent|Gönderildi)$/)
    expect(hist.querySelector('[data-status="failed"]')).toHaveAttribute('data-tone', 'danger')
    expect(hist.querySelector('[data-status="failed"]')).toHaveTextContent(/^(Not delivered|Gönderilemedi)$/)
  })

  it('geçmiş listesi yüklenir (24 kayıt istenir)', async () => {
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(api.admin.getCertInvReportHistory).toHaveBeenCalledWith(24))
  })
})

// 20.166 bugfix (UIA-C2 / C11): sonuç DURUMDAN okunur; kaydedilmemiş değişiklikte "Şimdi çalıştır" kapalı
describe('CertInventoryReportSettings — gönderim sonucu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getCertInvReportStatus.mockResolvedValue({ success: true, data: status({ recipients: 'ops@example.com' }) })
    api.admin.getCertInvReportHistory.mockResolvedValue({ success: true, data: [] })
  })
  const runBtn = () => screen.getByRole('button', { name: /^(Send now|Şimdi gönder)$/ })

  it('Şimdi çalıştır → FAILED: yeşil "gönderildi" YOK, tehlike bandı + ham SMTP metni', async () => {
    api.admin.runCertInvReport.mockResolvedValue({ success: true, data: { status: 'FAILED: Connection refused', rows: 3, findings: 1 } })
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(runBtn()).toBeEnabled())
    fireEvent.click(runBtn())
    const box = await waitFor(() => { const el = document.querySelector('[data-slot="cir-result"]'); if (!el) throw new Error('yok'); return el })
    expect(box).toHaveAttribute('data-outcome', 'failed')
    expect(box.querySelector('[data-tone="danger"]')).not.toBeNull()
    expect(box.querySelector('[data-slot="cir-result-raw"]')).toHaveTextContent('FAILED: Connection refused')
    expect(screen.queryByText(/Report sent:|Rapor gönderildi:/)).toBeNull()
  })

  it('Şimdi çalıştır → SKIPPED (kanal kapalı) uyarı, SENT başarı', async () => {
    api.admin.runCertInvReport.mockResolvedValueOnce({ success: true, data: { status: 'SKIPPED_DISABLED', rows: 3, findings: 1 } })
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(runBtn()).toBeEnabled())
    fireEvent.click(runBtn())
    await waitFor(() => expect(document.querySelector('[data-slot="cir-result"]')).toHaveAttribute('data-outcome', 'skipped'))
    api.admin.runCertInvReport.mockResolvedValueOnce({ success: true, data: { status: 'SENT', rows: 3, findings: 1 } })
    await waitFor(() => expect(runBtn()).toBeEnabled())
    fireEvent.click(runBtn())
    await waitFor(() => expect(document.querySelector('[data-slot="cir-result"]')).toHaveAttribute('data-outcome', 'sent'))
  })

  it('test gönderimi: success:false + message SKIPPED (K9) → "gönderildi" denmez', async () => {
    api.admin.sendCertInvReportTest.mockResolvedValue({ success: false, message: 'SKIPPED_DISABLED', data: { status: 'SKIPPED_DISABLED', rows: 1, findings: 0 } })
    render(<CertInventoryReportSettings />)
    await screen.findByDisplayValue('pki@example.com')
    fireEvent.change(screen.getByRole('textbox', { name: /Send test|Test gönder/i }), { target: { value: 'me@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Send test|Test gönder)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="cir-result"]')).toHaveAttribute('data-outcome', 'skipped'))
    expect(screen.queryByText(/Test report sent to|adresine gönderildi/)).toBeNull()
  })

  it('kaydedilmemiş değişiklik varken "Şimdi çalıştır" KAPALI ve nedeni yazar', async () => {
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(runBtn()).toBeEnabled())
    fireEvent.change(screen.getByDisplayValue('pki@example.com'), { target: { value: 'pki@example.com, yeni@example.com' } })
    expect(runBtn()).toBeDisabled()
    expect(screen.getByText(/Save your changes first|Önce değişiklikleri kaydedin/)).toBeInTheDocument()
  })
})
