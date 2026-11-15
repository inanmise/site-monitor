import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import { growthFieldErrors, GROWTH_FIELDS } from '../utils/pageSpeedThresholds.js'
import { compareSnapshots, CMP_CUT_SENSITIVE } from '../components/pagespeed/deep/pageSpeedDeepModel.js'
import { CHANGE_EVENTS } from '../components/responsechart/chartEvents.js'
import { EXTRA_BREACH_KEYS } from '../components/pagespeed/pageSpeedCardModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:              vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:         vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageSpeedMonitors:    vi.fn(),
      getPageSpeedResources:   vi.fn(),
      createPageSpeedMonitor:  vi.fn(),
      updatePageSpeedMonitor:  vi.fn(),
      pageSpeedSchedulePreview: vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Sayfa Hızı 2c (2026-11-15): genişletilmiş ölçüm anahtarı (YENİ izleme açık doğar, mevcut izleme kendi kipini korur, kip
 * değişimi uyarısı) ve büyüme alarmı alanları (1–1000 tam yüzde, alanın altında hata, sunucu alan hatası da), farklı kipteki
 * iki ölçümün karşılaştırmasında toplamlar YÖN almaz, grafik olay katmanı kip değişimini tanır, kart büyüme ihlalini adlandırır.
 */
const monitor = {
  id: 1, name: 'Ödeme sayfası', url: 'https://x.com/odeme', team_id: 5, team_name: 'SY-A', group_name: 'Kanal', tags: 'prod',
  active: true, interval_seconds: 1800, timeout_ms: 10000,
  max_load_ms: null, max_ttfb_ms: null, max_page_kb: null, max_requests: null,
  status: 'OK', ok: true, response_ms: 900, ttfb_ms: 120, total_bytes: 2048 * 1024, request_count: 42, failed_count: 0,
  capped: false, breached_metrics: [], last_check: '2026-11-14T10:00:00',
  has_basic_auth_pass: false, has_custom_headers: false, custom_header_names: [],
}
const save = () => fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
const extendedSwitch = () => document.querySelector('[data-slot="pspd-extended-switch"]')

describe('Sayfa Hızı 2c — modeller', () => {
  const t = (k) => k
  it('growthFieldErrors: boş = kapalı; 1..1000 tam sayı; değişmeyen kayıtlı değer denetlenmez', () => {
    expect(GROWTH_FIELDS).toEqual(['growthBytesPct', 'growthRequestsPct'])
    expect(growthFieldErrors({ growthBytesPct: '', growthRequestsPct: null }, t)).toEqual({})
    expect(growthFieldErrors({ growthBytesPct: '0' }, t)).toEqual({ growthBytesPct: 'pspd.err.growthRange' })
    expect(growthFieldErrors({ growthBytesPct: '1001' }, t)).toEqual({ growthBytesPct: 'pspd.err.growthRange' })
    expect(growthFieldErrors({ growthRequestsPct: '12.5' }, t)).toEqual({ growthRequestsPct: 'pspd.err.growthRange' })
    expect(growthFieldErrors({ growthBytesPct: '1', growthRequestsPct: '1000' }, t)).toEqual({})
    expect(growthFieldErrors({ growthBytesPct: 5000 }, t, { growthBytesPct: 5000 })).toEqual({})
  })

  it('farklı kipteki iki ölçüm: boyut / istek / süre toplamları yön almaz; aynı kipte alır', () => {
    const snap = (ext, over) => ({ check: { response_ms: 1000, total_bytes: 1000, request_count: 10, ttfb_ms: 100, html_ms: 200, extended: ext, ...over }, resources: [] })
    const mixed = compareSnapshots(snap(false), snap(true, { response_ms: 2000, total_bytes: 3000, request_count: 30, ttfb_ms: 300 }))
    expect(mixed.modeDiffers).toBe(true)
    for (const k of CMP_CUT_SENSITIVE) expect(mixed.rows.find((r) => r.key === k).worse, k).toBeNull()
    expect(mixed.rows.find((r) => r.key === 'ttfb').worse).toBe(true)
    const same = compareSnapshots(snap(true), snap(true, { total_bytes: 3000 }))
    expect(same.modeDiffers).toBe(false)
    expect(same.rows.find((r) => r.key === 'bytes').worse).toBe(true)
  })

  it('grafik olay katmanı kip değişimini tanır; kart büyüme ihlallerini adlandırır', () => {
    expect(CHANGE_EVENTS).toContain('MODE')
    expect(EXTRA_BREACH_KEYS).toEqual(expect.arrayContaining(['GROWSIZE', 'GROWREQ', 'SIZEBAND']))
  })
})

describe('Sayfa Hızı 2c — form', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [] } })
    api.monitoring.pageSpeedSchedulePreview.mockResolvedValue({ success: true, data: { valid: true, errors: [], next_runs: [] } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  async function openNew() {
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://y.com' } })
    await fillGroupAndTags()
  }

  it('YENİ izleme genişletilmiş kipte açılır; büyüme alanı hatalıysa kayıt GİTMEZ, hata alanın altında', async () => {
    await openNew()
    expect(extendedSwitch()).toHaveAttribute('aria-checked', 'true')
    fireEvent.change(screen.getByLabelText(/weight growth threshold|ağırlık büyümesi eşiği/i), { target: { value: '0' } })
    save()
    const field = document.querySelector('[data-field="growthBytesPct"]')
    expect(await within(field).findByText(/1 and 1000|1 ile 1000/)).toBeInTheDocument()
    expect(api.monitoring.createPageSpeedMonitor).not.toHaveBeenCalled()
  })

  it('payload kipi ve büyüme alanlarını taşır; sunucu alan hatası alanın altına yazılır', async () => {
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: false, field: 'growthRequestsPct',
      error: 'Büyüme eşiği 1 ile 1000 arasında bir tam sayı (yüzde) olmalı ya da boş bırakılmalı.' })
    await openNew()
    fireEvent.change(screen.getByLabelText(/weight growth threshold|ağırlık büyümesi eşiği/i), { target: { value: '25' } })
    fireEvent.click(document.querySelector('[data-slot="pspd-growth-band-switch"]'))
    save()
    await waitFor(() => expect(api.monitoring.createPageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPageSpeedMonitor.mock.calls[0][0]
    expect(payload).toMatchObject({ extendedMeasurement: true, growthBytesPct: 25, growthRequestsPct: null, growthBytesBand: true })
    const field = document.querySelector('[data-field="growthRequestsPct"]')
    expect(await within(field).findByText(/1 ile 1000/)).toBeInTheDocument()
  })

  it('mevcut izleme (alan yok = klasik) kapalı açılır; açınca kip değişimi uyarısı çıkar ve gövdeye girer', async () => {
    api.monitoring.updatePageSpeedMonitor.mockResolvedValue({ success: true, data: { ...monitor, extended_measurement: true } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /edit|düzenle/i }))
    await waitFor(() => expect(extendedSwitch()).not.toBeNull())
    expect(extendedSwitch()).toHaveAttribute('aria-checked', 'false')
    expect(document.querySelector('[data-slot="pspd-extended-warn"]')).toBeNull()
    fireEvent.click(extendedSwitch())
    expect(document.querySelector('[data-slot="pspd-extended-warn"]')).not.toBeNull()
    save()
    await waitFor(() => expect(api.monitoring.updatePageSpeedMonitor).toHaveBeenCalled())
    expect(api.monitoring.updatePageSpeedMonitor.mock.calls[0][1]).toMatchObject({ extendedMeasurement: true })
  })

  it('kart büyüme ihlalini adlandırır; geçmiş satırı genişletilmiş kip rozetini taşır', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true,
      data: [{ ...monitor, status: 'SLOW', breached_metrics: ['GROWSIZE'], growth_bytes_pct: 20, extended_measurement: true, extended: true }] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-extra-breaches"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="pspd-extra-breaches"]').textContent).toMatch(/weight growth|ağırlık büyümesi/i)
  })
})
