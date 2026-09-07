import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import HttpMetricsExplorer from '../components/admin/HttpMetricsExplorer.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getHttpMetricsEndpoints: vi.fn(),
      getHttpMetricsSeries:    vi.fn(),
      getGeneralSettings:      vi.fn(),
      saveGeneralSettings:     vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Kalıcı HTTP metrikleri gezgini — %0 kapsamla duruyordu (183 satır).
 *
 * İki riskli bağ var ve ikisi de test dışıydı:
 *
 * 1. SAKLAMA ANAHTARI. Kutu, Genel Ayarlar'daki `site.monitor.metrics.http.retention-days`
 *    satırını ADIYLA arıyor (RETENTION_KEY). Anahtar öneki yeniden adlandırılırsa arama
 *    sessizce başarısız olur, kutu görünmez ve hiçbir hata çıkmaz — bu projede tam olarak
 *    yaşanmış bir sınıf (bkz. CLAUDE.md, `cert.monitor.*` → `site.monitor.*` geçişi).
 * 2. KAYDETME DOĞRULAMASI. Geçersiz gün sayısında istek ATILMAMALI; aksi halde backend'e
 *    çöp değer gidip saklama politikasını bozardı.
 */
const settings = (value = '7') => ({
  success: true,
  data: { settings: [{ key: 'site.monitor.metrics.http.retention-days', value, default: '7' }] },
})

const series = {
  success: true,
  data: {
    granularity: 'hour',
    summary: { total: 120, errors: 3, avg: 45, p95: 90, p99: 140 },
    series: [{ ts: '2026-09-07T10:00:00', count: 60, errors: 1, avg: 40 }],
  },
}

describe('HttpMetricsExplorer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getHttpMetricsEndpoints.mockResolvedValue({ success: true, data: ['/api/certificates'] })
    api.admin.getHttpMetricsSeries.mockResolvedValue(series)
    api.admin.getGeneralSettings.mockResolvedValue(settings())
    api.admin.saveGeneralSettings.mockResolvedValue({ success: true })
  })

  it('açılışta uç listesi ve seriyi birlikte ister', async () => {
    render(<HttpMetricsExplorer />)
    await waitFor(() => {
      expect(api.admin.getHttpMetricsEndpoints).toHaveBeenCalled()
      expect(api.admin.getHttpMetricsSeries).toHaveBeenCalled()
    })
  })

  it('özet rozetleri sunucudan gelen değerleri basar', async () => {
    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-pills')).not.toBeNull())
    expect(container.textContent).toContain('120')
    expect(container.textContent).toContain('3')
  })

  it('SAKLAMA ANAHTARI: kutu, Genel Ayarlar satırını ADIYLA bulur', async () => {
    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(api.admin.getGeneralSettings).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('.hme-retention')).not.toBeNull())
  })

  it('anahtar BULUNAMAZSA kutu gizlenir (yanlış değerle çizilmez)', async () => {
    // Anahtar öneki yeniden adlandırılırsa arama sessizce boş döner. Doğru davranış:
    // kutuyu HİÇ göstermemek — yanlış bir varsayılanla göstermek operatörü yanıltırdı.
    api.admin.getGeneralSettings.mockResolvedValue({ success: true, data: { settings: [] } })

    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(api.admin.getGeneralSettings).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelector('.hme-pills')).not.toBeNull())
    expect(container.querySelector('.hme-retention')).toBeNull()
  })

  it('yetki yoksa (success:false) kutu gizlenir ve ekran ÇÖKMEZ', async () => {
    api.admin.getGeneralSettings.mockResolvedValue({ success: false, error: '403' })

    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-pills')).not.toBeNull())
    expect(container.querySelector('.hme-retention')).toBeNull()
  })

  it('getGeneralSettings REJECT ederse de ekran ayakta kalır', async () => {
    api.admin.getGeneralSettings.mockRejectedValue(new Error('network'))

    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-pills')).not.toBeNull())
  })

  it('GEÇERSİZ saklama günü kaydedilmez (backend çöp değer almaz)', async () => {
    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-retention')).not.toBeNull())

    const input = container.querySelector('.hme-retention input')
    expect(input, 'saklama girdisi bulunamadı').not.toBeNull()
    fireEvent.change(input, { target: { value: '0' } })

    const saveBtn = container.querySelector('.hme-retention button')
    fireEvent.click(saveBtn)

    await waitFor(() => expect(api.admin.saveGeneralSettings).not.toHaveBeenCalled())
  })

  it('geçerli gün sayısı DOĞRU anahtarla kaydedilir', async () => {
    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-retention')).not.toBeNull())

    fireEvent.change(container.querySelector('.hme-retention input'), { target: { value: '30' } })
    fireEvent.click(container.querySelector('.hme-retention button'))

    await waitFor(() => expect(api.admin.saveGeneralSettings).toHaveBeenCalled())
    const payload = api.admin.saveGeneralSettings.mock.calls.at(-1)[0]
    expect(payload.values).toHaveProperty('site.monitor.metrics.http.retention-days', '30')
  })

  it('seri boş dönerse çökmez', async () => {
    api.admin.getHttpMetricsSeries.mockResolvedValue({ success: true, data: null })
    const { container } = render(<HttpMetricsExplorer />)
    await waitFor(() => expect(container.querySelector('.hme-panel')).not.toBeNull())
  })
})
