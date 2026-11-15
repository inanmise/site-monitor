import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, fillGroupAndTags } from './test-utils.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'

/**
 * Sayfa Hızı 2. sürüm madde 14 (2026-11-15): Sayfa Hızı formu ortak zamanlama düzenleyicisini taşır (aralık / cron +
 * pencereler + tatil), önizleme Sayfa Hızı ucuna gider, zamanlama anahtarları yalnız gerektiğinde gövdeye girer (eski
 * sunucunun satırı dokunulmadan kaydedilirse kayıtlı cron / pencereler silinmez), sunucu alan hatası düzenleyicinin
 * alanının altında çıkar, zamanlamalı kart bir çip taşır (düz aralıkta çip yok).
 */

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:               vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:          vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageSpeedMonitors:     vi.fn(),
      getPageSpeedResources:    vi.fn(),
      createPageSpeedMonitor:   vi.fn(),
      updatePageSpeedMonitor:   vi.fn(),
      pageSpeedSchedulePreview: vi.fn(),
      scriptedSchedulePreview:  vi.fn(),
      dbQuerySchedulePreview:   vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

const base = {
  id: 1, name: 'Ödeme sayfası', url: 'https://x.com/odeme',
  team_id: 5, team_name: 'SY-A', group_name: 'Kanal', tags: 'prod', active: true,
  interval_seconds: 1800, timeout_ms: 10000,
  max_load_ms: 3000, max_ttfb_ms: null, max_page_kb: null, max_requests: null,
  status: 'OK', ok: true, response_ms: 900, ttfb_ms: 120,
  total_bytes: 2048 * 1024, request_count: 42, failed_count: 0, capped: false,
  breached_metrics: [], last_check: '2026-08-23T10:00:00',
  has_basic_auth_pass: false, has_custom_headers: false, custom_header_names: [],
}
/** Yeni sunucunun düz aralıklı satırı: zamanlama anahtarları VAR, değerleri boş. */
const plain = { ...base, schedule_mode: 'INTERVAL', cron_expression: null, windows: [], blackouts: [], skip_holidays: false, skip_dates: [], next_run_at: null }
const cron = { ...plain, schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5', interval_seconds: 86400, next_run_at: '2026-11-16T06:00:00' }

const PREVIEW_OK = { success: true, data: { valid: true, errors: [], next_runs: ['2026-11-16T06:00:00'], effective_interval_seconds: 1800 } }

async function openEdit() {
  fireEvent.click(await screen.findByRole('button', { name: /edit|düzenle/i }))
  return document.querySelector('[data-slot="dbq-schedule-editor"]')
}
const save = () => fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

describe('Sayfa Hızı zamanlaması (2. sürüm madde 14)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [] } })
    api.monitoring.pageSpeedSchedulePreview.mockResolvedValue(PREVIEW_OK)
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  it('zamanlamalı kart çip taşır (cron), düz aralıklı kartta çip YOK', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [cron, { ...plain, id: 2, url: 'https://y.com/', name: 'Y' }] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="pspd-chips"]').length).toBe(2))
    const chips = document.querySelectorAll('[data-slot="pspd-schedule-chip"]')
    expect(chips).toHaveLength(1)
    expect(chips[0]).toHaveAttribute('data-schedule', 'cron')
    // Uzun özet sarar, kesilmez
    expect(chips[0].className).toMatch(/whitespace-normal/)
    expect(chips[0].className).not.toMatch(/\btruncate\b/)
  })

  it('pencereli aralıkta çip "filtered" — süzgeç eki görünür', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true,
      data: [{ ...plain, windows: [{ days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' }] }] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const chip = await waitFor(() => {
      const el = document.querySelector('[data-slot="pspd-schedule-chip"]')
      if (!el) throw new Error('çip yok')
      return el
    })
    expect(chip).toHaveAttribute('data-schedule', 'filtered')
    expect(chip.textContent).toMatch(/allowed windows|izinli pencerelerde/i)
  })

  it('form ortak düzenleyiciyi taşır; önizleme Sayfa Hızı ucuna gider', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [plain] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const editor = await openEdit()
    expect(editor).not.toBeNull()
    expect(editor).toHaveAttribute('data-mode', 'INTERVAL')
    expect(document.querySelector('[data-slot="pspd-schedule"]')).not.toBeNull()
    await waitFor(() => expect(api.monitoring.pageSpeedSchedulePreview).toHaveBeenCalled(), { timeout: 3000 })
    expect(api.monitoring.scriptedSchedulePreview).not.toHaveBeenCalled()
    expect(api.monitoring.dbQuerySchedulePreview).not.toHaveBeenCalled()
    const [body] = api.monitoring.pageSpeedSchedulePreview.mock.calls[0]
    expect(body.schedule_mode).toBe('INTERVAL')
    expect(body.interval_seconds).toBe(1800)
  })

  it('cron satırı düzenleyicide cron kipinde açılır ve kayıt gövdesi zamanlamayı taşır', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [cron] })
    api.monitoring.updatePageSpeedMonitor.mockResolvedValue({ success: true, data: cron })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const editor = await openEdit()
    expect(editor).toHaveAttribute('data-mode', 'CRON')
    await fillGroupAndTags()
    save()
    await waitFor(() => expect(api.monitoring.updatePageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.updatePageSpeedMonitor.mock.calls[0][1]
    expect(payload.schedule_mode).toBe('CRON')
    expect(payload.cron_expression).toBe('0 9 * * 1-5')
    expect(payload.windows).toEqual([])
    expect(payload.skip_holidays).toBe(false)
  })

  it('eski sunucunun satırı (zamanlama alanı yok) dokunulmadan kaydedilirse zamanlama anahtarı GİTMEZ', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [base] })
    api.monitoring.updatePageSpeedMonitor.mockResolvedValue({ success: true, data: base })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openEdit()
    await fillGroupAndTags()
    save()
    await waitFor(() => expect(api.monitoring.updatePageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.updatePageSpeedMonitor.mock.calls[0][1]
    expect(Object.prototype.hasOwnProperty.call(payload, 'schedule_mode')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(payload, 'windows')).toBe(false)
    expect(payload.intervalSeconds).toBe(1800)
  })

  it('yeni kayıt: varsayılan aralık kipi gövdede (pencere yok), aralık 30 dk', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [] })
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: true, data: plain })
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getAllByRole('button', { name: /add monitor|izleme ekle/i })[0])
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://y.com' } })
    await fillGroupAndTags()
    save()
    await waitFor(() => expect(api.monitoring.createPageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPageSpeedMonitor.mock.calls[0][0]
    expect(payload.schedule_mode).toBe('INTERVAL')
    expect(payload.windows).toEqual([])
    expect(payload.blackouts).toEqual([])
    expect(payload.intervalSeconds).toBe(1800)
  })

  it('cron kipinde boş ifade: alanın altında hata, kayıt isteği YOK', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [plain] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openEdit()
    fireEvent.click(screen.getByRole('button', { name: /^cron expression$|^cron ifadesiyle$/i }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-schedule-editor"]')).toHaveAttribute('data-mode', 'CRON'))
    await fillGroupAndTags()
    save()
    expect(await screen.findByText(/^enter a cron expression or pick|^cron ifadesini girin ya da/i)).toHaveAttribute('data-slot', 'field-error')
    expect(api.monitoring.updatePageSpeedMonitor).not.toHaveBeenCalled()
  })

  it('sunucu zamanlama alan hatası (cron_expression) düzenleyicinin alanının altında çıkar', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [cron] })
    api.monitoring.updatePageSpeedMonitor.mockResolvedValue({ success: false, code: 'DBQUERY_CRON_TOO_FREQUENT',
      field: 'cron_expression', error: 'Sayfa Hızı ölçümleri en az 5 dakika arayla planlanabilir.' })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await openEdit()
    await fillGroupAndTags()
    save()
    const msg = await screen.findByText(/en az 5 dakika arayla/)
    expect(msg.closest('[data-field="cron_expression"]')).not.toBeNull()
    // Form açık kalır (kullanıcı düzeltip yeniden kaydeder)
    expect(document.querySelector('[data-slot="dbq-schedule-editor"]')).not.toBeNull()
  })
})
