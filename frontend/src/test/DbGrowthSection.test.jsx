import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'

const { api, nav } = vi.hoisted(() => ({
  api: {
    admin: {
      dbGrowth: {
        get: vi.fn(), whatIf: vi.fn(), saveCapacity: vi.fn(), runAnalysis: vi.fn(), latestAnalysis: vi.fn(),
      },
      saveRetentionSettings: vi.fn(),
    },
  },
  nav: vi.fn(),
}))
vi.mock('../api/client', () => ({ api, formatDateSec: (s) => s ?? '' }))
vi.mock('../utils/navigate.js', () => ({ navigateTo: nav, default: nav }))

import DbGrowthSection from '../components/admin/dbgrowth/DbGrowthSection.jsx'

const MB = 1024 * 1024
const GB = 1024 * MB

/** DbGrowthService + DbGrowthController görünümünün birebir şekli (tahmin geçmişten). */
function view(over = {}) {
  return {
    range_days: 90, generated_at: '2026-11-13T10:00:00', capacity_gb: 100, hold_active: false, supported: true,
    history_days: 14, first_day: '2026-10-31', last_day: '2026-11-13', snapshot_today: true, live_only: false,
    current_bytes: 42 * GB, current_source: 'live', forecast_basis: 'history', history_points: 14, min_points: 7,
    forecast: [{ day: '2026-11-14', value: 43 * GB, low: 42.5 * GB, high: 43.5 * GB }],
    horizons: [{ days: 30, value: 50 * GB, low: 48 * GB, high: 52 * GB }, { days: 90, value: 70 * GB, low: 60 * GB, high: 80 * GB }],
    kpis: {
      current_bytes: 42 * GB, daily_growth_bytes: 300 * MB, daily_growth_estimate: false, growth_30d_bytes: 9 * GB,
      projection_90d_bytes: 70 * GB, projection_90d_estimate: false, used_pct: 42, days_until_full: 180,
      fills_within_horizon: true, days_until_full_estimate: false,
    },
    series: {
      top_tables: ['port_checks'],
      database: [{ day: '2026-11-12', bytes: 41 * GB }, { day: '2026-11-13', bytes: 42 * GB }],
      tables: [{ name: 'port_checks', points: [{ day: '2026-11-12', bytes: 10 * GB }, { day: '2026-11-13', bytes: 10.2 * GB }] }],
    },
    tables: [{
      name: 'port_checks', total_bytes: 10 * GB, table_bytes: 6 * GB, index_bytes: 4 * GB, toast_bytes: 0, live_rows: 5e7,
      dead_rows: 0, growth_30d_bytes: 3 * GB, daily_growth_bytes: 100 * MB, daily_growth_estimate: false, rows_per_day: 50000,
      rows_per_day_method: 'stats', oldest_at: '2026-08-15T00:00:00', oldest_source: 'min',
      retention: { policy_id: 'series-port', mode: 'AGE', days: 90, min_days: 7, configurable: true, setting_key: 'k.port', data_class: 'OPERATIONAL', policies: 1 },
      saturation_pct: 100, saturated: true, equilibrium_bytes: null, projection_90d_bytes: 10 * GB, findings: ['SHORTEN_CANDIDATE'],
    }],
    findings: [{
      code: 'SHORTEN_CANDIDATE', severity: 'warn', table: 'port_checks', policy_id: 'series-port', setting_key: 'k.port',
      current_days: 90, suggested_days: 30, min_days: 7, est_rows: 33_000_000, est_bytes: 6 * GB, est_method: 'uniform',
      data_class: 'OPERATIONAL', action: 'apply_retention', params: { share_pct: 23.8, table_bytes: 10 * GB, compliance: false },
    }],
    can_edit_capacity: true, can_apply: true, can_open_retention: true, can_analyze: true, capacity_key: 'site.monitor.db.capacity-gb',
    max_capacity_gb: 1_000_000, last_analysis: null, analysis_running: false,
    ...over,
  }
}

const ok = (data) => Promise.resolve({ success: true, data })

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'tr')
  api.admin.dbGrowth.get.mockImplementation(() => ok(view()))
  api.admin.dbGrowth.latestAnalysis.mockImplementation(() => ok({ analysis: null, running: false }))
})

describe('DbGrowthSection', () => {
  it('KPI kutucukları, grafikler, öneri ve tablo listesi çizilir', async () => {
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Öneriler')
    const kpis = document.querySelector('[data-slot="dbg-kpis"]')
    expect(within(kpis).getByText('42 GiB')).toBeInTheDocument()
    expect(within(kpis).getByText('+300 MiB/gün')).toBeInTheDocument()
    expect(within(kpis).getByText('180 gün')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="dbg-chart"]')).toHaveLength(2)
    expect(document.querySelector('[data-slot="dbg-finding"][data-code="SHORTEN_CANDIDATE"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="dbg-table-row"][data-table="port_checks"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="dbg-collecting"]')).toBeNull()
    expect(api.admin.dbGrowth.get).toHaveBeenCalledWith(90, false)
  })

  it('kapasite yoksa doluluk ve dolma günü "—" ve nötr — asla yeşil değil', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ capacity_gb: null, kpis: { ...view().kpis, used_pct: null, days_until_full: null, fills_within_horizon: null } })))
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Öneriler')
    const used = document.querySelector('[data-kpi="used"]')
    const full = document.querySelector('[data-kpi="full"]')
    expect(used.getAttribute('data-tone')).toBe('neutral')
    expect(full.getAttribute('data-tone')).toBe('neutral')
    expect(within(used).getByText('—')).toBeInTheDocument()
    expect(within(full).getByText('Kapasite tanımlı değil')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-tone="ok"]')).toHaveLength(0)
  })

  it('geçmiş yetmiyorsa "toplanıyor" bandı ve tahmin etiketi', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ forecast_basis: 'cold_start', history_points: 2,
      kpis: { ...view().kpis, daily_growth_estimate: true, projection_90d_estimate: true, days_until_full_estimate: true } })))
    render(<DbGrowthSection isAdmin />)
    const band = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-collecting"]'); expect(el).not.toBeNull(); return el })
    expect(band.getAttribute('data-basis')).toBe('cold_start')
    expect(within(document.querySelector('[data-kpi="daily"]')).getByText('Tahmin (istatistiklerden)')).toBeInTheDocument()
  })

  it('PostgreSQL değilse yalnız bilgi bloğu', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok({ supported: false, reason: 'not_postgres', range_days: 90 }))
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Yalnız PostgreSQL')
    expect(document.querySelector('[data-slot="dbg-kpis"]')).toBeNull()
  })

  it('ilk okuma başarısızsa hata bloğu + tekrar dene; tekrar deneme taze okur', async () => {
    api.admin.dbGrowth.get.mockImplementationOnce(() => Promise.resolve({ success: false, error: 'Veritabanı yanıt vermiyor' }))
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Veritabanı yanıt vermiyor')
    await userEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await screen.findByText('Öneriler')
    expect(api.admin.dbGrowth.get).toHaveBeenLastCalledWith(90, true)
  })

  it('Uygula: tahmin okunur, Veri Saklama kayıt ucu aynı anahtarla çağrılır, görünüm taze okunur', async () => {
    api.admin.dbGrowth.whatIf.mockImplementation(() => ok({ policy_id: 'series-port', current_days: 90, days: 30, min_days: 7,
      data_class: 'OPERATIONAL', compliance: false, rows: 32_000_000, rows_method: 'count', bytes_estimate: 6 * GB }))
    api.admin.saveRetentionSettings.mockImplementation(() => ok({}))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Uygula' }))
    const dlg = await waitFor(() => { const d = document.querySelector('[data-slot="dbg-apply-dialog"]'); expect(d).not.toBeNull(); return d })
    await waitFor(() => expect(within(dlg).getByText(/32\.000\.000 satır/)).toBeInTheDocument())
    expect(api.admin.dbGrowth.whatIf).toHaveBeenCalledWith('series-port', 30)
    expect(within(dlg).queryByText('Uyum onayı gerektiren veri')).toBeNull()
    expect(document.querySelector('[data-slot="dbg-apply-affected"]')).toBeNull()
    expect(screen.getByRole('button', { name: '30 güne kısalt' }).getAttribute('data-variant')).toBe('destructive')
    await userEvent.click(screen.getByRole('button', { name: '30 güne kısalt' }))
    await waitFor(() => expect(api.admin.saveRetentionSettings).toHaveBeenCalledWith({ 'k.port': '30' }))
    await waitFor(() => expect(api.admin.dbGrowth.get).toHaveBeenLastCalledWith(90, true))
    expect(await screen.findByText('Saklama süresi kaydedildi; ilk gece temizliğinde uygulanır')).toBeInTheDocument()
  })

  it('güncel süre okunamazsa onay KAPALI (bilinmeyen varsayılmaz); yeniden deneyince açılır; kişisel veride uyum uyarısı', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ findings: [{ ...view().findings[0], data_class: 'PERSONAL' }] })))
    api.admin.dbGrowth.whatIf.mockImplementationOnce(() => Promise.resolve({ success: false, code: 'RATE_LIMITED', error: 'Çok sık' }))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Uygula' }))
    await screen.findByText('Uyum onayı gerektiren veri')
    await waitFor(() => expect(document.querySelector('[data-slot="dbg-apply-unknown"]')).not.toBeNull())
    expect(screen.getByRole('button', { name: '30 güne kısalt' })).toBeDisabled()
    api.admin.dbGrowth.whatIf.mockImplementationOnce(() => ok({ policy_id: 'series-port', current_days: 90, days: 30, min_days: 7,
      data_class: 'PERSONAL', compliance: true, rows: 10, rows_method: 'count', bytes_estimate: 1024 }))
    await userEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '30 güne kısalt' })).toBeEnabled())
    expect(api.admin.dbGrowth.whatIf).toHaveBeenCalledTimes(2)
    expect(api.admin.saveRetentionSettings).not.toHaveBeenCalled()
  })

  it('eski öneri: güncel süre önerilenden kısa ya da eşitse "artık geçerli değil" ve onay kapalı (süre UZAMAZ)', async () => {
    api.admin.dbGrowth.whatIf.mockImplementation(() => ok({ policy_id: 'series-port', current_days: 30, days: 30, min_days: 7,
      data_class: 'OPERATIONAL', compliance: false, rows: 0, rows_method: 'count', bytes_estimate: 0 }))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Uygula' }))
    await screen.findByText('Öneri artık geçerli değil')
    expect(screen.getByRole('button', { name: '30 güne kısalt' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '30 güne kısalt' }), { pointerEventsCheck: 0 })
    expect(api.admin.saveRetentionSettings).not.toHaveBeenCalled()
  })

  it('ortak ayar anahtarı: öneri "N tabloyu etkiler" der; pencere TÜM tabloları tahminiyle listeler, taban en büyük, uyum uyarısı', async () => {
    const f = { ...view().findings[0], table: 'notification_logs', policy_id: 'notification-logs', setting_key: 'k.notif', min_days: 90,
      params: { ...view().findings[0].params, affected_count: 3, affected_tables: ['notification_logs', 'noc_deliveries', 'quiet_digest_items'], compliance: true } }
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ findings: [f] })))
    api.admin.dbGrowth.whatIf.mockImplementation(() => ok({
      policy_id: 'notification-logs', table: 'notification_logs', setting_key: 'k.notif', current_days: 365, days: 30, min_days: 90,
      data_class: 'OPERATIONAL', compliance: true, hold_active: false, affected_count: 3, supported: true,
      rows: 1000, rows_method: 'count', bytes_estimate: MB, total_rows: 1500, total_bytes_estimate: 2 * MB,
      policies: [
        { policy_id: 'notification-logs', table: 'notification_logs', data_class: 'OPERATIONAL', min_days: 30, current_days: 365, compliance: false, rows: 1000, rows_method: 'count', bytes_estimate: MB },
        { policy_id: 'noc-deliveries', table: 'noc_deliveries', data_class: 'PERSONAL', min_days: 90, current_days: 365, compliance: true, rows: 500, rows_method: 'histogram', bytes_estimate: MB },
        { policy_id: 'quiet-digest', table: 'quiet_digest_items', data_class: 'OPERATIONAL', min_days: 30, current_days: 365, compliance: false, rows: null, rows_method: null, bytes_estimate: null },
      ],
    }))
    api.admin.saveRetentionSettings.mockImplementation(() => ok({}))
    render(<DbGrowthSection isAdmin />)
    const affects = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-finding-affects"]'); expect(el).not.toBeNull(); return el })
    expect(affects.textContent).toBe('Bu ayar 3 tabloyu birlikte etkiler: notification_logs, noc_deliveries, quiet_digest_items.')
    await userEvent.click(screen.getByRole('button', { name: 'Uygula' }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="dbg-apply-policy"]')).toHaveLength(3))
    const dlg = document.querySelector('[data-slot="dbg-apply-dialog"]')
    expect(within(dlg).getByText('Bu ayar 3 tabloyu birlikte kısaltır')).toBeInTheDocument()
    expect(within(dlg).getByText('Toplam ≈ 1.500 satır (≈ 2 MiB)')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="dbg-apply-policy"][data-table="noc_deliveries"]').getAttribute('data-compliance')).toBe('true')
    expect(within(dlg).getByText('Uyum onayı gerektiren veri')).toBeInTheDocument()
    expect(within(dlg).getByText('90 gün')).toBeInTheDocument()
    expect(within(document.querySelector('[data-slot="dbg-apply-policy"][data-table="quiet_digest_items"]'))
      .getByText('Tahmin hesaplanamadı (tablo istatistikleri yok).')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '30 güne kısalt' }))
    await waitFor(() => expect(api.admin.saveRetentionSettings).toHaveBeenCalledWith({ 'k.notif': '30' }))
  })

  it('yasal tutma: öneri ve pencere "hold kapanana dek hiçbir satır silinmez" der, bildirim de öyle', async () => {
    const f = { ...view().findings[0], severity: 'info', params: { ...view().findings[0].params, hold: true } }
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ hold_active: true, findings: [f] })))
    api.admin.dbGrowth.whatIf.mockImplementation(() => ok({ policy_id: 'series-port', current_days: 90, days: 30, min_days: 7,
      data_class: 'OPERATIONAL', compliance: false, hold_active: true, rows: 1000, rows_method: 'count', bytes_estimate: MB }))
    api.admin.saveRetentionSettings.mockImplementation(() => ok({}))
    render(<DbGrowthSection isAdmin />)
    const finding = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-finding"]'); expect(el).not.toBeNull(); return el })
    expect(finding.textContent).toMatch(/hold kapanana dek hiçbir satır silinmez/)
    expect(finding.textContent).not.toMatch(/ilk gece temizliği yaklaşık/)
    expect(document.querySelector('[data-slot="dbg-hold"]').textContent).toMatch(/doğrusal büyüme/)
    await userEvent.click(screen.getByRole('button', { name: 'Uygula' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbg-apply-hold"]')).not.toBeNull())
    const dlg = document.querySelector('[data-slot="dbg-apply-dialog"]')
    expect(within(dlg).getByText('Hold kalkınca silinecek')).toBeInTheDocument()
    expect(within(dlg).queryByText('İlk gece silinecek')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '30 güne kısalt' }))
    expect(await screen.findByText('Saklama süresi kaydedildi; yasal tutma açık olduğu için hold kapanana dek hiçbir satır silinmez')).toBeInTheDocument()
  })

  it('görüntüler eski: uyarı bandı, dolmaya kalan "—" ve nötr', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ forecast_stale: true, snapshot_age_days: 5, stale_after_days: 3,
      kpis: { ...view().kpis, days_until_full: null, fills_within_horizon: null } })))
    render(<DbGrowthSection isAdmin />)
    const band = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-stale"]'); expect(el).not.toBeNull(); return el })
    expect(band.textContent).toMatch(/Günlük görüntüler 5 gündür alınamıyor/)
    const full = document.querySelector('[data-kpi="full"]')
    expect(within(full).getByText('—')).toBeInTheDocument()
    expect(within(full).getByText('Tahmin kullanılmıyor — görüntüler 5 gündür alınamıyor')).toBeInTheDocument()
    expect(full.getAttribute('data-tone')).toBe('neutral')
  })

  it('boyut okunamazsa doluluk "%0" değil "—"', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ kpis: { ...view().kpis, current_bytes: null, used_pct: null, days_until_full: null } })))
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Öneriler')
    const used = document.querySelector('[data-kpi="used"]')
    expect(within(used).getByText('—')).toBeInTheDocument()
    expect(within(used).getAllByText('Boyut okunamadı').length).toBeGreaterThan(0)
    expect(used.getAttribute('data-tone')).toBe('neutral')
    expect(used.textContent).not.toMatch(/%0/)
  })

  it('global yönetici değilse Uygula yok; Veri Saklama bağlantısı politikayı odaklar', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ can_apply: false, can_edit_capacity: false })))
    render(<DbGrowthSection isAdmin />)
    await screen.findByText('Öneriler')
    expect(screen.queryByRole('button', { name: 'Uygula' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Kapasiteyi düzenle' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: "Veri Saklama'da aç" }))
    expect(nav).toHaveBeenCalledWith('settings', { sec: 'retention', ret_focus: 'series-port' })
  })

  it('yönetici olmayan rolde (Ayarlar sekmesi yok) saklama bağlantısı da yok', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ can_apply: false })))
    render(<DbGrowthSection isAdmin={false} />)
    await screen.findByText('Öneriler')
    expect(screen.queryByRole('button', { name: "Veri Saklama'da aç" })).toBeNull()
  })

  it('kapasite: geçersiz değer alanın altında, geçerli değer kaydedilip görünüm taze okunur', async () => {
    api.admin.dbGrowth.saveCapacity.mockImplementation(() => ok({ capacity_gb: 250, changed: true }))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Kapasiteyi düzenle' }))
    const input = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-capacity-input"]'); expect(el).not.toBeNull(); return el })
    await userEvent.clear(input)
    await userEvent.type(input, '2,5')
    await userEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(await screen.findByText(/Kapasite tam sayı \(GiB\) olmalı/)).toBeInTheDocument()
    expect(api.admin.dbGrowth.saveCapacity).not.toHaveBeenCalled()
    await userEvent.clear(input)
    await userEvent.type(input, '250')
    await userEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.admin.dbGrowth.saveCapacity).toHaveBeenCalledWith(250))
    await waitFor(() => expect(api.admin.dbGrowth.get).toHaveBeenLastCalledWith(90, true))
  })

  it('analiz: meşgul (409) bilgi olarak gösterilir; başarılı sonuçta bölüm durumları ve bulgular', async () => {
    api.admin.dbGrowth.runAnalysis.mockImplementationOnce(() => Promise.resolve({ success: false, code: 'DB_ANALYSIS_BUSY', error: 'Başka analiz sürüyor' }))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Analizi çalıştır' }))
    await screen.findByText('Başka analiz sürüyor')
    api.admin.dbGrowth.runAnalysis.mockImplementationOnce(() => ok({
      run_id: 7, started_at: '2026-11-13T10:00:00', duration_ms: 4200, actor: 'admin', pod: 'pod-a', status: 'PARTIAL', supported: true,
      sections: { policies: 'ok', vacuum: 'ok', hot_monitors: 'skipped', index_bloat: 'ok', unused_indexes: 'failed' },
      index_bloat: { checked: 12, unknown: 1, bloated: [] },
      findings: [{ code: 'HOT_MONITOR', severity: 'info', table: 'ping_checks', action: 'none', params: { type: 'ping', monitor_id: 42, rows_24h: 2880 } }],
    }))
    await userEvent.click(screen.getByRole('button', { name: 'Analizi çalıştır' }))
    const res = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-analysis-result"]'); expect(el).not.toBeNull(); return el })
    expect(res.getAttribute('data-status')).toBe('PARTIAL')
    expect(res.querySelector('[data-section="unused_indexes"]').getAttribute('data-state')).toBe('failed')
    expect(res.querySelector('[data-slot="dbg-analysis-incomplete"]')).not.toBeNull()
    await userEvent.click(within(res).getByRole('button', { name: 'İzlemeyi aç' }))
    expect(nav).toHaveBeenCalledWith('ping', { monitor: '42' })
  })

  it('kısmi analiz bulgusuzsa "öneri yok" demez; kısmi bölüm uyarı tonunda', async () => {
    api.admin.dbGrowth.runAnalysis.mockImplementation(() => ok({
      run_id: 8, started_at: '2026-11-13T10:00:00', duration_ms: 60000, actor: 'admin', pod: 'pod-a', status: 'PARTIAL', supported: true,
      sections: { policies: 'partial', vacuum: 'ok', hot_monitors: 'ok', index_bloat: 'skipped', unused_indexes: 'ok' },
      findings: [],
    }))
    render(<DbGrowthSection isAdmin />)
    await userEvent.click(await screen.findByRole('button', { name: 'Analizi çalıştır' }))
    const res = await waitFor(() => { const el = document.querySelector('[data-slot="dbg-analysis-result"]'); expect(el).not.toBeNull(); return el })
    expect(res.querySelector('[data-slot="dbg-analysis-incomplete"]')).not.toBeNull()
    const empty = res.querySelector('[data-slot="dbg-analysis-findings-empty"]')
    expect(empty.textContent).toMatch(/bazı bölümler okunamadı/)
    expect(empty.textContent).not.toBe('Şu an öneri yok.')
    const pol = res.querySelector('[data-section="policies"]')
    expect(pol.getAttribute('data-state')).toBe('partial')
    expect(pol.getAttribute('data-tone')).toBe('warning')
  })

  it('son analiz varsa açılışta okunur', async () => {
    api.admin.dbGrowth.get.mockImplementation(() => ok(view({ last_analysis: { run_id: 3, status: 'OK' } })))
    api.admin.dbGrowth.latestAnalysis.mockImplementation(() => ok({ analysis: { run_id: 3, status: 'OK', supported: true, sections: {}, findings: [] }, running: false }))
    render(<DbGrowthSection isAdmin />)
    await waitFor(() => expect(document.querySelector('[data-slot="dbg-analysis-result"][data-status="OK"]')).not.toBeNull())
  })
})
