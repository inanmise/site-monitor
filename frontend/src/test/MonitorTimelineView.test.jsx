import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivityTimeline: vi.fn() }),
}))
// Alarm düğmesi alarm okuma iznine bağlı (inceleme FR D4) — izin testten ayarlanır
const perm = vi.hoisted(() => ({ alerts: true }))
vi.mock('../contexts/PermissionsProvider.jsx', async (orig) => ({
  ...(await orig()),
  usePermissions: () => ({ perms: {}, scope: null, canView: (r) => (r === 'alerts.read' ? perm.alerts : false), canEdit: () => false,
    canExecute: () => false, refresh: () => {}, loaded: true }),
}))
import { api } from '../api/client'
import MonitorTimelineView from '../components/activity/timeline/MonitorTimelineView.jsx'
import {
  availabilityTone, hasMeasured, outageKpi, outageSeconds, readTlParams, seriesPoints, stripSegments, tlWindow, unwrapTimeline,
  writeTlParams,
} from '../components/activity/timeline/timelineModel.js'

/** İzleme zaman çizelgesi (2026-11-05, D) — model + görünüm. Sunucu yanıtı sözleşme biçiminde taklit edilir. */
const LONG = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat)'
const BODY = {
  monitor: { type: 'HTTP', id: 4, name: LONG, target: 'https://odeme.example.com/health', team_id: 1, team_name: 'Ödeme Ekibi' },
  from: '2026-11-04T08:00:00', to: '2026-11-05T08:00:00', checks: 288, scheduled_checks: 280,
  availability_pct: 98.75, mttr_s: 600, longest_outage_s: 1200, outage_count: 2,
  periods: [
    { from: '2026-11-04T08:00:00', to: '2026-11-04T20:00:00', state: 'up', checks: 144 },
    { from: '2026-11-04T20:00:00', to: '2026-11-04T20:20:00', state: 'down', checks: 4, reason: 'READ_TIMEOUT' },
    { from: '2026-11-04T20:20:00', to: '2026-11-05T07:50:00', state: 'up', checks: 138 },
    { from: '2026-11-05T07:50:00', to: null, state: 'down', checks: 2, reason: 'HTTP_STATUS' },
  ],
  outages: [
    { from: '2026-11-04T20:00:00', to: '2026-11-04T20:20:00', duration_s: 1200, checks: 4, reason: 'READ_TIMEOUT', first_activity_id: 501, alert_event_id: 77 },
    { from: '2026-11-05T07:50:00', to: null, duration_s: null, checks: 2, reason: 'HTTP_STATUS', first_activity_id: 900, alert_event_id: null },
  ],
  series: [{ t: '2026-11-04T08:00:00Z', ms_avg: 120, ms_max: 300, fail: 0 }, { t: '2026-11-04T09:00:00Z', ms_avg: null, ms_max: null, fail: 0 }],
  truncated: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'en')
  window.history.replaceState(null, '', '/?tab=activity&amon=HTTP:4')
})

describe('timelineModel', () => {
  it('aralık ↔ URL; özel aralık doğrulaması (ters, 90 günden uzun, başlangıçsız)', () => {
    expect(readTlParams('?atlr=30d')).toEqual({ range: '30d', from: '', to: '' })
    expect(readTlParams('?atlr=custom')).toEqual({ range: '7d', from: '', to: '' })
    expect(readTlParams('?atlr=forever')).toEqual({ range: '7d', from: '', to: '' })
    const s = writeTlParams('?tab=activity&amon=HTTP:4', { range: 'custom', from: '2026-10-01T00:00:00', to: '' })
    expect(new URLSearchParams(s).get('atlf')).toBe('2026-10-01T00:00:00')
    expect(writeTlParams('?tab=activity&atlr=30d', { range: '7d' })).toBe('?tab=activity')
    const now = Date.parse('2026-11-05T12:00:00Z')
    expect(tlWindow({ range: '24h' }, now)).toEqual({ from: '2026-11-04T12:00:00', to: null })
    expect(tlWindow({ range: 'custom', from: '2026-11-02T00:00:00', to: '2026-11-01T00:00:00' }, now)).toEqual({ error: 'order' })
    expect(tlWindow({ range: 'custom', from: '2026-01-01T00:00:00', to: '' }, now)).toEqual({ error: 'tooLong' })
    expect(tlWindow({ range: 'custom', from: '', to: '' }, now)).toEqual({ error: 'missing' })
  })

  it('şerit bölümleri: aralığa kırpılır, DOM sırası kronolojik, kesinti üst katmanda; tonlar bilinmeyeni asla iyi saymaz', () => {
    const segs = stripSegments(BODY.periods, BODY.from, BODY.to)
    // Kronolojik (ekran okuyucu sırayla okur — inceleme FR D12); üst üste binen dar dönemde kesinti `z` ile üstte
    expect(segs.map((x) => x.index)).toEqual([0, 1, 2, 3])
    expect(segs.map((x) => x.state)).toEqual(['up', 'down', 'up', 'down'])
    expect(segs.find((x) => x.state === 'down').z).toBeGreaterThan(segs.find((x) => x.state === 'up').z)
    const first = segs.find((x) => x.index === 0)
    expect(first.left).toBeCloseTo(0)
    expect(first.width).toBeCloseTo(50)
    expect(stripSegments([], 'x', 'y')).toEqual([])
    expect(availabilityTone(null)).toBe('unknown')
    expect(availabilityTone(100)).toBe('good')
    expect(availabilityTone(99.5)).toBe('warn')
    expect(availabilityTone(90)).toBe('bad')
    expect(outageSeconds({ from: '2026-11-05T07:50:00', to: null }, Date.parse('2026-11-05T08:00:00Z'))).toBe(600)
  })

  it('hata avı O3: hiçbir kontrol ölçülemediyse kesinti "—" nötr (asla yeşil "0"); ölçülen ve kesintisizse "0" yeşil', () => {
    const unknownOnly = unwrapTimeline({ success: true, ...BODY, checks: 7, scheduled_checks: 7, availability_pct: null, outage_count: 0,
      outages: [], counts: { up: 0, warn: 0, down: 0, unknown: 7 },
      periods: [{ from: '2026-11-04T08:00:00', to: null, state: 'unknown', checks: 7, reason: 'RDAP_UNREACHABLE' }] })
    expect(unknownOnly.counts).toEqual({ up: 0, warn: 0, down: 0, unknown: 7 })
    expect(hasMeasured(unknownOnly)).toBe(false)
    expect(outageKpi(unknownOnly)).toEqual({ value: '—', tone: 'unknown', measured: false })
    // elle kontroller (zamanlanmış sayımda yok) dönemlerde ölçülmüşse ölçülmüş sayılır
    expect(hasMeasured({ ...unknownOnly, periods: [{ state: 'up' }] })).toBe(true)
    const clean = { ...unknownOnly, counts: { up: 7, warn: 0, down: 0, unknown: 0 }, availability_pct: 100 }
    expect(outageKpi(clean)).toEqual({ value: '0', tone: 'good', measured: true })
    expect(outageKpi({ ...clean, outage_count: 2 })).toEqual({ value: '2', tone: 'bad', measured: true })
    expect(outageKpi({ checks: 0, outage_count: 0, periods: [] }).tone).toBe('unknown')
    expect(hasMeasured({ checks: 0 })).toBeNull()
    // eski gövde (counts yok): dönemlerden / erişilebilirlikten
    expect(hasMeasured(unwrapTimeline({ success: true, ...BODY }))).toBe(true)
    expect(unwrapTimeline({ success: true, ...BODY }).counts).toBeNull()
  })

  it('yanıt gövdesi kökte ya da data altında; boş kova null kalır (sahte 0 değil)', () => {
    expect(unwrapTimeline({ success: true, ...BODY }).outage_count).toBe(2)
    expect(unwrapTimeline({ success: true, data: BODY }).periods).toHaveLength(4)
    expect(unwrapTimeline({ success: true, data: [] })).toBeNull()
    const pts = seriesPoints(BODY.series, 'en', 86_400_000)
    expect(pts[1].avg).toBeNull()
    expect(pts[0].label).toBe('11:00')   // İstanbul saati
  })
})

describe('MonitorTimelineView', () => {
  it('başlık (uzun ad kesilmez), KPI kutucukları, şerit seçimi, kesinti listesi → ilk aktivite / alarm', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    const open = vi.fn()
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={open} onFilterList={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    expect(api.getActivityTimeline).toHaveBeenCalledWith(expect.objectContaining({ monitor: 'HTTP:4' }))
    const name = container.querySelector('[data-slot="act-tl-name"]')
    expect(name).toHaveTextContent(LONG)
    expect(name.className).toMatch(/overflow-wrap:anywhere/)
    const kpi = (k) => container.querySelector(`[data-slot="act-tl-kpi"][data-key="${k}"]`)
    expect(kpi('availability')).toHaveAttribute('data-tone', 'bad')   // %98,75 < %99
    expect(kpi('availability')).toHaveTextContent('98.75%')
    expect(kpi('outages')).toHaveTextContent('2')
    expect(kpi('mttr')).toHaveTextContent('10 min')
    // Şerit: varsayılan seçim son kesinti; ok tuşuyla önceki döneme
    const segs = container.querySelectorAll('[data-slot="act-tl-seg"]')
    expect(segs).toHaveLength(4)
    const period = () => container.querySelector('[data-slot="act-tl-period"]')
    expect(period()).toHaveAttribute('data-state', 'down')
    expect(period()).toHaveTextContent(EN['act.tl.mon.ongoing'])
    const selected = container.querySelector('[data-slot="act-tl-seg"][aria-pressed="true"]')
    expect(selected).toHaveAttribute('tabindex', '0')
    fireEvent.keyDown(selected, { key: 'ArrowLeft' })
    await waitFor(() => expect(period()).toHaveAttribute('data-state', 'up'))
    fireEvent.click(container.querySelector('[data-slot="act-tl-seg"][data-index="1"]'))
    expect(period()).toHaveAttribute('data-state', 'down')
    expect(within(period()).getByText(/2026-11-04T20:20:00/)).toBeInTheDocument()
    // Kesintiler: en yeni üstte, sürüyor rozeti; ilk aktivite ve alarm bağlantıları
    const outages = container.querySelectorAll('[data-slot="act-tl-outage"]')
    expect(outages).toHaveLength(2)
    expect(outages[0]).toHaveAttribute('data-ongoing', 'true')
    fireEvent.click(within(outages[1]).getByRole('button', { name: EN['act.tl.mon.openAlertFor'].replace('{0}', '77') }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { alert: 77 } })
    fireEvent.click(within(outages[0]).getByRole('button', { name: EN['act.tl.mon.firstActivity'] }))
    expect(open).toHaveBeenCalledWith(900)
    window.removeEventListener('sm:navigate', nav)
  })

  it('alarm okuma izni yoksa "Alarm #id" düğme değil düz metin (boş / 403 sayfaya gitmez — inceleme FR D4)', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    perm.alerts = false
    try {
      const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={() => {}} onOpenActivity={() => {}} />)
      await waitFor(() => expect(container.querySelectorAll('[data-slot="act-tl-outage"]')).toHaveLength(2))
      expect(container.querySelector('[data-slot="act-tl-alarm"]')).toBeNull()
      expect(container.querySelector('[data-slot="act-tl-alarm-text"]')).toHaveTextContent('#77')
    } finally {
      perm.alerts = true
    }
  })

  it('eski pod (uç yok → 400 INVALID_PARAMETER) "bu sürümde yok"; aralık değişimi okunamazsa önceki aralığın verisi gösterilmez', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    api.getActivityTimeline.mockResolvedValueOnce({ success: false, status: 400, code: 'INVALID_PARAMETER', error: "'id' değeri geçersiz" })
    const first = render(<MonitorTimelineView type="HTTP" id={4} onBack={() => {}} onOpenActivity={() => {}} />)
    await waitFor(() => expect(first.container.querySelector('[data-slot="act-tl-view"]')).toHaveAttribute('data-state', 'unsupported'))
    expect(first.container).toHaveTextContent(EN['act.tl.mon.unsupported'])
    first.unmount()
    // Aralık değişimi: yeni aralık okunamazsa eski aralığın KPI'ları yeni etiketin altında kalmaz (tam hata bloğu)
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={() => {}} onOpenActivity={() => {}} />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="act-tl-kpi"]').length).toBeGreaterThan(0))
    api.getActivityTimeline.mockResolvedValueOnce({ success: false, status: 500, error: 'boom' })
    fireEvent.click(screen.getByRole('button', { name: EN['act.tl.mon.r.30d'] }))
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"]')).toHaveAttribute('data-state', 'error'))
    expect(container.querySelectorAll('[data-slot="act-tl-kpi"]')).toHaveLength(0)
    expect(container).toHaveTextContent(EN['act.tl.mon.loadFailed'])
  })

  it('be2 ek alanları: data kabı + Z\'li zamanlar, NO_DATA dönemi nötr metin, sürüyor bayrağı, aralıktan önce başlamış, silinmiş izleme', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, timestamp: 'x', data: { ...BODY,
      monitor: { ...BODY.monitor, exists: false },
      from: '2026-11-04T08:00:00Z', to: '2026-11-05T08:00:00Z', periods_merged: true,
      periods: [
        { from: '2026-11-04T08:00:00Z', to: '2026-11-04T20:00:00Z', state: 'unknown', checks: 0, reason: 'NO_DATA' },
        { from: '2026-11-04T20:00:00Z', to: '2026-11-05T08:00:00Z', state: 'down', checks: 3, reason: 'class:NETWORK' },
      ],
      outages: [{ from: '2026-11-04T20:00:00Z', to: '2026-11-05T08:00:00Z', ongoing: true, open_start: true, duration_s: 43200, checks: 3,
        reason: 'class:NETWORK', first_activity_id: 1, alert_event_id: null }] } })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="act-tl-deleted"]')).not.toBeNull()
    expect(container.querySelectorAll('[data-slot="act-tl-seg"]')).toHaveLength(2)
    fireEvent.click(container.querySelector('[data-slot="act-tl-seg"][data-index="0"]'))
    const period = container.querySelector('[data-slot="act-tl-period"]')
    expect(period).toHaveAttribute('data-state', 'unknown')
    expect(period.querySelector('[data-slot="act-tl-nodata"]')).not.toBeNull()
    expect(period.querySelector('[data-slot="act-reason"]')).toBeNull()   // NO_DATA hata nedeni rozeti değil
    const outage = container.querySelector('[data-slot="act-tl-outage"]')
    expect(outage).toHaveAttribute('data-ongoing', 'true')
    expect(outage.querySelector('[data-slot="act-tl-open-start"]')).not.toBeNull()
    expect(outage.querySelector('[data-slot="act-reason"]')).toHaveTextContent(EN['act.cls.NETWORK'])
    expect(screen.getByText(new RegExp(EN['act.tl.mon.merged']))).toBeInTheDocument()
  })

  it('kesinti listesi sunucuda sınırsız → "Tümünü göster" değil standart sayfalama: 30 kesintide 25 + çubuk, 2. sayfada 5', async () => {
    const many = Array.from({ length: 30 }, (_, i) => ({
      from: `2026-11-04T${String(i % 24).padStart(2, '0')}:${String(i).padStart(2, '0')}:00`, to: `2026-11-04T${String(i % 24).padStart(2, '0')}:59:00`,
      duration_s: 60, checks: 1, reason: 'READ_TIMEOUT', first_activity_id: 1000 + i, alert_event_id: null }))
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY, outage_count: 30, outages: many })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    expect(container.querySelectorAll('[data-slot="act-tl-outage"]')).toHaveLength(25)
    expect(screen.queryByRole('button', { name: /Show all/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: EN['pg.next'] }))
    await waitFor(() => expect(container.querySelectorAll('[data-slot="act-tl-outage"]')).toHaveLength(5))
  })

  it('kısa kesinti listesinde sayfalama çubuğu çizilmez', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    expect(container.querySelectorAll('[data-slot="act-tl-outage"]')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: EN['pg.next'] })).toBeNull()
  })

  it('kontrol yoksa erişilebilirlik "—" ve nötr (asla yeşil); grafik boş durum; tavan uyarısı', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY, checks: 0, scheduled_checks: 0, availability_pct: null, mttr_s: null,
      longest_outage_s: null, outage_count: 0, periods: [], outages: [], series: [], truncated: true })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    const av = container.querySelector('[data-slot="act-tl-kpi"][data-key="availability"]')
    expect(av).toHaveAttribute('data-tone', 'unknown')
    expect(av).toHaveTextContent('—')
    expect(container.querySelector('[data-slot="act-tl-kpi"][data-key="outages"]')).toHaveAttribute('data-tone', 'unknown')
    expect(container.querySelector('[data-slot="act-tl-strip-empty"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="act-tl-chart-empty"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="act-tl-truncated"]')).not.toBeNull()
  })

  it('hata avı O3: tüm kontroller bilinmiyor (RDAP okunamıyor) → Kesinti "—" nötr, liste "ölçülebilen kontrol yok"', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY, checks: 7, scheduled_checks: 7, availability_pct: null,
      mttr_s: null, longest_outage_s: null, outage_count: 0, outages: [], counts: { up: 0, warn: 0, down: 0, unknown: 7 },
      periods: [{ from: '2026-11-04T08:00:00', to: null, state: 'unknown', checks: 7, reason: 'RDAP_UNREACHABLE' }] })
    const { container } = render(<MonitorTimelineView type="DOMAIN" id={8} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    const out = container.querySelector('[data-slot="act-tl-kpi"][data-key="outages"]')
    expect(out).toHaveAttribute('data-tone', 'unknown')
    expect(out).toHaveTextContent('—')
    expect(out).toHaveTextContent(EN['act.tl.mon.kpi.noMeasured'])
    const none = container.querySelector('[data-slot="act-tl-no-outage"]')
    expect(none).toHaveAttribute('data-measured', 'false')
    expect(none).toHaveTextContent(EN['act.tl.mon.noOutageUnmeasured'])
    expect(container).not.toHaveTextContent(EN['act.tl.mon.noOutage'])
  })

  it('hata avı D5: adresteki geçersiz özel aralık (90 günden uzun) — istek yok, sonsuz iskelet yok, "aralığı düzeltin", Uygula pasif', async () => {
    window.history.replaceState(null, '', '/?tab=activity&amon=HTTP:4&atlr=custom&atlf=2026-01-01T00:00&atlt=2026-11-01T00:00')
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"]')).toHaveAttribute('data-state', 'invalid'))
    expect(api.getActivityTimeline).not.toHaveBeenCalled()
    expect(container.querySelector('[aria-busy="true"]')).toBeNull()
    expect(container.querySelector('[data-slot="act-tl-invalid"]')).toHaveTextContent(EN['act.tl.mon.invalidTitle'])
    expect(container.querySelector('[data-slot="act-tl-custom-error"]')).toHaveTextContent(EN['act.tl.mon.bad.tooLong'])
    expect(container.querySelector('[data-slot="act-tl-custom-apply"]')).toBeDisabled()
    expect(container.querySelectorAll('[data-slot="act-tl-kpi"]')).toHaveLength(0)
    // Yenile de istek atmaz (geçersiz aralık)
    fireEvent.click(screen.getByRole('button', { name: EN['act.refresh'] }))
    expect(api.getActivityTimeline).not.toHaveBeenCalled()
    // Hazır aralık seçilince okur
    fireEvent.click(screen.getByRole('button', { name: EN['act.tl.mon.r.7d'] }))
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    expect(api.getActivityTimeline).toHaveBeenCalledTimes(1)
  })

  it('aralık değişince yeniden okur ve URL\'ye yazar; özel aralık ters ise istek gitmez, alanın altında söylenir', async () => {
    api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(api.getActivityTimeline).toHaveBeenCalledTimes(1))
    fireEvent.click(await screen.findByRole('button', { name: EN['act.tl.mon.r.30d'] }))
    await waitFor(() => expect(api.getActivityTimeline).toHaveBeenCalledTimes(2))
    expect(new URLSearchParams(window.location.search).get('atlr')).toBe('30d')
    expect(new URLSearchParams(window.location.search).get('amon')).toBe('HTTP:4')
    // Özel: o anki pencere başlangıcı dolu gelir (boş alan + hata yok)
    fireEvent.click(screen.getByRole('button', { name: EN['act.tl.mon.r.custom'] }))
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-custom"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="act-tl-custom-error"]')).toBeNull()
  })

  it('yenileme hatası son okunan veriyi silmez — üstte "yenilenemedi" bandı', async () => {
    api.getActivityTimeline.mockResolvedValueOnce({ success: true, ...BODY })
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={vi.fn()} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    api.getActivityTimeline.mockResolvedValueOnce({ success: false, status: 503, error: 'down' })
    fireEvent.click(screen.getByRole('button', { name: EN['act.refresh'] }))
    expect(await screen.findByText(EN['act.tl.mon.refreshFailed'])).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="act-tl-outage"]')).toHaveLength(2)
  })

  it('404 → "bulunamadı ya da yetkiniz yok" + geri; okuma hatası → danger + yeniden dene', async () => {
    api.getActivityTimeline.mockResolvedValueOnce({ success: false, status: 404, error: 'yok' })
    const back = vi.fn()
    const first = render(<MonitorTimelineView type="PING" id={9} onBack={back} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(first.container.querySelector('[data-slot="act-tl-view"][data-state="notfound"]')).not.toBeNull())
    expect(screen.getByText(EN['act.tl.mon.notFound'])).toBeInTheDocument()
    first.unmount()
    api.getActivityTimeline.mockResolvedValueOnce({ success: false, status: 500, error: 'boom' })
    const second = render(<MonitorTimelineView type="PING" id={9} onBack={back} onOpenActivity={vi.fn()} />)
    await waitFor(() => expect(second.container.querySelector('[data-slot="act-tl-view"][data-state="error"]')).not.toBeNull())
    api.getActivityTimeline.mockResolvedValueOnce({ success: true, ...BODY })
    fireEvent.click(screen.getByRole('button', { name: /^(Retry|Yeniden dene)$/ }))
    await waitFor(() => expect(second.container.querySelector('[data-slot="act-tl-view"][data-state="ok"]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: EN['act.backToList'] }))
    expect(back).toHaveBeenCalled()
  })
})
