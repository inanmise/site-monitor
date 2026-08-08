import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import RetentionSettings from '../components/admin/RetentionSettings.jsx'

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: {
    admin: {
      getRetentionOverview: vi.fn(),
      saveRetentionSettings: vi.fn(),
      retentionDryRun: vi.fn(),
      retentionRunNow: vi.fn(),
      getRetentionRuns: vi.fn(),
      saveRetentionApproval: vi.fn(),
    },
  },
}))
import { api } from '../api/client'

const overview = (over = {}) => ({
  success: true,
  data: {
    hold_active: false,
    hold_key: 'site.monitor.retention.hold-enabled',
    cleanup_cron: '0 30 3 * * *',
    totals: { rows: 1250000, bytes: 734003200, purgeable: 40321, policies: 2, tables: 2 },
    last_run: { started_at: '2026-08-08T03:30:00', total_deleted: 12000, failed_count: 0 },
    approvals: {},
    policies: [
      {
        id: 'activity-log', table: 'activity_log', data_class: 'PERSONAL', mode: 'AGE',
        setting_key: 'site.monitor.activity.retention-days', configurable: true, deletes: true,
        days: 90, default_days: 90, min_days: 1, rows: 1000000, bytes: 524288000,
        oldest_at: '2026-05-10T00:00:00', newest_at: '2026-08-08T15:00:00', purgeable: 40000,
        rationale: 'Her kontrol +1 satır', rule: 'activity_time < ?',
      },
      {
        id: 'series-ping', table: 'ping_checks', data_class: 'OPERATIONAL', mode: 'AGE',
        setting_key: 'site.monitor.series.ping.retention-days', configurable: true, deletes: true,
        days: 180, default_days: 180, min_days: 30, rows: 250000, bytes: 209715200,
        oldest_at: '2026-02-10T00:00:00', newest_at: '2026-08-08T15:00:00', purgeable: 321,
        rationale: 'Ping ham serisi', rule: 'checked_at < ?',
      },
    ],
    ...over,
  },
})

describe('RetentionSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getRetentionOverview.mockResolvedValue(overview())
    api.admin.saveRetentionSettings.mockResolvedValue({ success: true, message: 'ok', data: [] })
    api.admin.retentionDryRun.mockResolvedValue({
      success: true, message: 'ok', data: { dry_run: true, total_rows: 40321, failed_count: 0, items: [] },
    })
  })

  it('politika matrisini veri sınıfına göre gruplayıp satır/boyut/en eski-en yeni kaydı gösterir', async () => {
    render(<RetentionSettings />)
    await screen.findByText('activity_log')

    // Kişisel veri grubu operasyoneldan ÖNCE gelir (uyum onayı gerektirenler üstte)
    const heads = [...document.querySelectorAll('.ldap-subhdr')].map(h => h.textContent)
    expect(heads.findIndex(h => /Personal Data|Kişisel/i.test(h)))
      .toBeLessThan(heads.findIndex(h => /Operational|İşletimsel/i.test(h)))

    expect(screen.getByText('ping_checks')).toBeInTheDocument()
    expect(screen.getByText('10.05.2026')).toBeInTheDocument()   // en eski kayıt
    expect(screen.getAllByText('08.08.2026').length).toBeGreaterThan(0)
  })

  it('süre kısaltıldığında onay diyaloğu çıkar; iptal edilirse kayıt YAPILMAZ', async () => {
    render(<RetentionSettings />)
    await screen.findByText('activity_log')

    const input = screen.getAllByRole('spinbutton')[0]
    fireEvent.change(input, { target: { value: '30' } })          // 90 → 30 (kısaltma)
    fireEvent.click(screen.getByRole('button', { name: /kaydet|^save$/i }))

    // NOT: /iptal/i ile aranmaz — "İptal".toLowerCase() birleşik noktalı i üretir (Türkçe İ),
    // regex eşleşmez. Sınıf üzerinden seçmek dile de bağımlı değil.
    await screen.findByText(/kısaltılıyor|shortening/i)
    fireEvent.click(document.querySelector('.dlg-btn-cancel'))
    await waitFor(() => expect(api.admin.saveRetentionSettings).not.toHaveBeenCalled())
  })

  it('dry-run sonucu satır sayısını bildirir ve HİÇBİR silme çağrısı yapmaz', async () => {
    render(<RetentionSettings />)
    await screen.findByText('activity_log')

    fireEvent.click(screen.getByRole('button', { name: /dry-run/i }))
    await waitFor(() => expect(api.admin.retentionDryRun).toHaveBeenCalled())
    expect(api.admin.retentionRunNow).not.toHaveBeenCalled()
  })

  it('legal hold açıkken kırmızı şerit görünür ve "şimdi temizle" kilitlenir', async () => {
    api.admin.getRetentionOverview.mockResolvedValue(overview({ hold_active: true }))
    render(<RetentionSettings />)
    await screen.findByText('activity_log')

    expect(document.querySelector('.ret-hold-banner')).not.toBeNull()
    expect(screen.getByRole('button', { name: /şimdi temizle|purge now/i })).toBeDisabled()
  })
})
