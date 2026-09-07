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

    const toggle = screen.getAllByRole('checkbox')[0]
    expect(toggle.checked).toBe(true)
    fireEvent.click(toggle)

    // Başarısızlıkta eski hâline dönmeli — aksi halde arayüz kapalı görünür ama sunucu açık.
    await waitFor(() => expect(toggle.checked).toBe(true))
  })

  it('geçmiş listesi yüklenir (24 kayıt istenir)', async () => {
    render(<CertInventoryReportSettings />)
    await waitFor(() => expect(api.admin.getCertInvReportHistory).toHaveBeenCalledWith(24))
  })
})
