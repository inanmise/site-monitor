import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import RetentionSettings from '../components/admin/RetentionSettings.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getRetentionOverview: vi.fn(),
      saveRetentionSettings: vi.fn(),
      retentionDryRun: vi.fn(),
      retentionRunNow: vi.fn(),
      getRetentionRuns: vi.fn(),
      getRetentionChanges: vi.fn(),
      saveRetentionApproval: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const overview = (over = {}) => ({
  success: true,
  data: {
    hold_active: false,
    hold_key: 'site.monitor.retention.hold-enabled',
    cleanup_cron: '0 0 3 * * *',
    cleanup_zone: 'Europe/Istanbul',
    totals: { rows: 1250000, bytes: 734003200, purgeable: 40321, policies: 2, tables: 2 },
    last_run: { started_at: '2026-08-08T03:30:00', total_deleted: 12000, failed_count: 0 },
    approvals: {},
    policies: [
      {
        id: 'activity-log', table: 'activity_log', data_class: 'PERSONAL', mode: 'AGE',
        setting_key: 'site.monitor.activity.retention-days', configurable: true, deletes: true,
        days: 365, default_days: 365, min_days: 1, rows: 1000000, bytes: 524288000,
        oldest_at: '2026-05-10T00:00:00', newest_at: '2026-08-08T15:00:00', purgeable: 40000,
        rationale: 'Her kontrol +1 satır', rule: 'activity_time < ?', time_column: 'activity_time',
      },
      {
        id: 'series-ping', table: 'ping_checks', data_class: 'OPERATIONAL', mode: 'AGE',
        setting_key: 'site.monitor.series.ping.retention-days', configurable: true, deletes: true,
        days: 180, default_days: 180, min_days: 30, rows: 250000, bytes: 209715200,
        oldest_at: '2026-02-10T00:00:00', newest_at: '2026-08-08T15:00:00', purgeable: 321,
        rationale: 'Ping ham serisi', rule: 'checked_at < ?', time_column: 'checked_at',
      },
    ],
    ...over,
  },
})

/** Bir veri sınıfı akordiyonunu açar. */
async function openClass(label) {
  const bar = (await screen.findByText(label)).closest('.stats-collapse-bar')
  fireEvent.click(bar)
}

describe('RetentionSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getRetentionOverview.mockResolvedValue(overview())
    api.admin.saveRetentionSettings.mockResolvedValue({ success: true, message: 'ok', data: [] })
    api.admin.retentionDryRun.mockResolvedValue({
      success: true, message: 'ok', data: { dry_run: true, total_rows: 40321, failed_count: 0, items: [] },
    })
    api.admin.getRetentionChanges.mockResolvedValue({
      success: true,
      data: [{ policy_id: 'activity-log', table: 'activity_log', actor: 'ADMIN',
        at: '2026-08-08T19:00:00', from: 90, to: 365, ip: '10.0.0.1' }],
    })
  })

  it('akordiyonlar VARSAYILAN KAPALI açılır; sınıfa tıklayınca politikalar görünür', async () => {
    render(<RetentionSettings />)
    await screen.findByText('Personal Data')

    // Kapalıyken hiçbir politika satırı DOM'da olmamalı
    expect(screen.queryByText('activity_log')).toBeNull()
    expect(document.querySelectorAll('.ret-row').length).toBe(0)

    await openClass('Personal Data')
    expect(await screen.findByText('activity_log')).toBeInTheDocument()
    // Diğer sınıf hâlâ kapalı → çoklu açılabilir ama kendiliğinden açılmaz
    expect(screen.queryByText('ping_checks')).toBeNull()
  })

  it('süre değişince satır "değişti" işaretlenir ve yapışkan çubuk sayacı belirir', async () => {
    render(<RetentionSettings />)
    await openClass('Personal Data')

    expect(document.querySelector('.ret-sticky-bar')).toBeNull()
    fireEvent.click(await screen.findByRole('button', { name: '730' }))

    expect(document.querySelector('.ret-row--changed')).not.toBeNull()
    expect(screen.getByText('365 → 730')).toBeInTheDocument()
    expect(document.querySelector('.ret-sticky-bar')).not.toBeNull()
    expect(screen.getByText(/1 pending|1 bekleyen/i)).toBeInTheDocument()
  })

  it('gözden geçirme penceresi eski→yeni ve etkisini gösterir; iptal edilirse KAYDETMEZ', async () => {
    render(<RetentionSettings />)
    await openClass('Personal Data')
    fireEvent.click(await screen.findByRole('button', { name: '730' }))
    fireEvent.click(screen.getByRole('button', { name: /review and save|gözden geçir/i }))

    await screen.findByText(/review changes|değişiklikleri gözden geçir/i)
    expect(screen.getByText('365 days')).toBeInTheDocument()
    expect(screen.getByText('730 days')).toBeInTheDocument()
    expect(screen.getByText(/\+365 days|\+365 gün/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^cancel$|vazgeç/i }))
    await waitFor(() => expect(api.admin.saveRetentionSettings).not.toHaveBeenCalled())
  })

  it('onaylanınca YALNIZ değişen anahtar gönderilir', async () => {
    render(<RetentionSettings />)
    await openClass('Personal Data')
    fireEvent.click(await screen.findByRole('button', { name: '730' }))
    fireEvent.click(screen.getByRole('button', { name: /review and save|gözden geçir/i }))
    fireEvent.click(await screen.findByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.admin.saveRetentionSettings).toHaveBeenCalledWith({
      'site.monitor.activity.retention-days': '730',
    }))
  })

  it('kısaltmada pencere danger tonuna geçer ve kalıcı silme uyarısı verir', async () => {
    render(<RetentionSettings />)
    await openClass('Personal Data')
    fireEvent.click(await screen.findByRole('button', { name: '90' }))     // 365 → 90
    fireEvent.click(screen.getByRole('button', { name: /review and save|gözden geçir/i }))

    await screen.findByText(/SHORTENED|KISALTILIYOR/)
    expect(document.querySelector('.ret-review-hdr--danger')).not.toBeNull()
    expect(document.querySelector('.ret-review-row--down')).not.toBeNull()
    expect(screen.getByRole('button', { name: /save and shorten|kaydet ve kısalt/i })).toBeInTheDocument()
  })

  it('satır açılınca silme kuralı ve ayar anahtarı görünür', async () => {
    render(<RetentionSettings />)
    await openClass('Personal Data')
    fireEvent.click(document.querySelector('.ret-row-toggle'))

    expect(await screen.findByText('activity_time < ?')).toBeInTheDocument()
    expect(screen.getByText('site.monitor.activity.retention-days')).toBeInTheDocument()
    // Kişisel veri → uyum onayı satırı
    expect(document.querySelector('.ret-approve')).not.toBeNull()
  })

  it('değişiklik geçmişi paneli kim/ne zaman/eski→yeni gösterir', async () => {
    render(<RetentionSettings />)
    const bar = (await screen.findByText(/change history|değişiklik geçmişi/i)).closest('.stats-collapse-bar')
    fireEvent.click(bar)

    await waitFor(() => expect(api.admin.getRetentionChanges).toHaveBeenCalled())
    expect(await screen.findByText('ADMIN')).toBeInTheDocument()
    expect(document.querySelector('.audit-diff-from').textContent).toBe('90')
    expect(document.querySelector('.audit-diff-to').textContent).toBe('365')
  })

  it('dry-run HİÇBİR silme çağrısı yapmaz', async () => {
    render(<RetentionSettings />)
    fireEvent.click(await screen.findByRole('button', { name: /dry-run/i }))
    await waitFor(() => expect(api.admin.retentionDryRun).toHaveBeenCalled())
    expect(api.admin.retentionRunNow).not.toHaveBeenCalled()
  })

  it('legal hold açıkken kırmızı şerit görünür ve "şimdi temizle" kilitlenir', async () => {
    api.admin.getRetentionOverview.mockResolvedValue(overview({ hold_active: true }))
    render(<RetentionSettings />)
    await screen.findByText('Personal Data')

    expect(document.querySelector('.ret-hold-banner')).not.toBeNull()
    expect(screen.getByRole('button', { name: /purge now|şimdi temizle/i })).toBeDisabled()
  })
})
