import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { speedMonitor, speedSlow } from './helpers/pageDiagnoseFixtures.js'
import PageSpeedDiagnoseDialog from '../components/pagespeed/diagnose/PageSpeedDiagnoseDialog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const recent = vi.hoisted(() => ({ value: [] }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => recent.value,
  api: withApiFallback({
    monitoring: {
      diagnosePageSpeed: vi.fn(),
      pageSpeedDiagnoseHistory: vi.fn(),
      pageSpeedDiagnoseRun: vi.fn(),
      diagnosePage: vi.fn(),
      diagnoseHttp: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Sayfa Hızı uçtan uca tanılama penceresi (2026-10-05): açılışta KOŞMAZ; hedef şeridinde ve başlangıçta eşikler; başlat →
 * hüküm (THRESHOLD_BREACH · LOAD, uyarı) → "NEDEN YAVAŞ?" (eşik ↔ ölçülen satırları, yol başına fazlar, künye, en ağır /
 * en yavaş kaynaklar, tür kırılımı) → yol kartı (izleme ölçümü, "Yavaş" sonucu); rapor ölçüm bölümünü taşır; 429.
 */
const dlg = () => screen.getByRole('dialog', { name: /page speed diagnosis|sayfa hızı tanılama/i })
const startBtn = () => within(dlg()).getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ })

async function runWith(data) {
  api.monitoring.diagnosePageSpeed.mockResolvedValueOnce({ success: true, data })
  fireEvent.click(startBtn())
  return waitFor(() => {
    const el = document.querySelector('[data-slot="httpdx-result"]')
    if (!el) throw new Error('sonuç yok')
    return el
  })
}

describe('PageSpeedDiagnoseDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    recent.value = []
    api.monitoring.pageSpeedDiagnoseHistory.mockResolvedValue({ success: true, data: [] })
    api.monitoring.pageSpeedDiagnoseRun.mockResolvedValue({ success: true, data: speedSlow() })
  })

  it('başlangıç: eşikler hedef şeridinde ve listede; API çağrılmaz', () => {
    render(<PageSpeedDiagnoseDialog monitor={speedMonitor} onClose={() => {}} />)
    expect(dlg().querySelector('[data-slot="httpdx-body"]')).toHaveAttribute('data-kind', 'pagespeed')
    const chips = dlg().querySelectorAll('[data-slot="psdx-target"] [data-metric]')
    expect([...chips].map((c) => c.getAttribute('data-metric'))).toEqual(['LOAD', 'TTFB', 'SIZE'])
    expect(dlg().querySelector('[data-slot="psdx-start-thresholds"]').textContent).toMatch(/5000 ms/)
    expect(api.monitoring.diagnosePageSpeed).not.toHaveBeenCalled()
  })

  it('eşik aşımı: hüküm uyarı LOAD; metrik satırları (aşan işaretli), fazlar, kaynaklar; yol sonucu "Yavaş"', async () => {
    render(<PageSpeedDiagnoseDialog monitor={speedMonitor} onClose={() => {}} />)
    const res = await runWith(speedSlow())
    expect(api.monitoring.diagnosePageSpeed).toHaveBeenCalledWith(11, { compare: true }, expect.anything())
    expect(api.monitoring.diagnosePage).not.toHaveBeenCalled()
    const verdict = res.querySelector('[data-slot="httpdx-verdict"]')
    expect(verdict).toHaveAttribute('data-status', 'warn')
    expect(verdict).toHaveAttribute('data-code', 'THRESHOLD_BREACH')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-title"]').textContent).toMatch(/(Load time threshold exceeded|Yükleme süresi eşiği aşıldı)/)
    expect(verdict.querySelector('[data-slot="httpdx-verdict-body"]').textContent).toMatch(/4100 ms/)
    expect(verdict.querySelector('[data-slot="httpdx-findings"]').textContent).toMatch(/(Size|boyut)/i)
    expect(res.textContent).not.toMatch(/psdx\.|pgdx\.|httpdx\.finding/)
    const a = res.querySelector('[data-slot="psdx-analysis"]')
    expect(a).toHaveAttribute('data-status', 'SLOW')
    const metrics = a.querySelectorAll('[data-slot="psdx-metric"]')
    expect([...metrics].map((m) => `${m.getAttribute('data-key')}:${m.getAttribute('data-breached')}`))
      .toEqual(['LOAD:true', 'TTFB:false', 'SIZE:true', 'REQUESTS:false'])
    expect(metrics[0].querySelector('[data-slot="psdx-metric-state"]').textContent).toMatch(/\+4100 ms/)
    expect(metrics[3].querySelector('[data-slot="psdx-metric-state"]')).toBeNull()   // eşik yok → rozet yok
    const phases = a.querySelectorAll('[data-slot="psdx-route"] [data-slot="psdx-phase"]')
    expect([...phases].map((p) => p.getAttribute('data-phase'))).toEqual(['dns', 'connect', 'tls', 'ttfb', 'download'])
    expect(a.querySelector('[data-phase="ttfb"]')).toHaveAttribute('data-over', 'true')
    expect(a.querySelectorAll('[data-slot="psdx-heaviest"] [data-slot="psdx-resource"]')).toHaveLength(2)
    expect(a.querySelector('[data-slot="psdx-slowest"]').textContent).toMatch(/(not downloaded|inmedi)/)
    expect(a.querySelectorAll('[data-slot="psdx-by-type"] [data-type]')).toHaveLength(2)
    const card = res.querySelector('[data-slot="httpdx-path-card"]')
    expect(card).toHaveAttribute('data-outcome', 'slow')
    expect(card.textContent).toMatch(/(Slow|Yavaş)/)
    expect(card.querySelector('[data-metric="measured"]').textContent).toMatch(/^9100 ms/)
  })

  it('Raporu kopyala → Sayfa Hızı başlığı + ölçüm bölümü', async () => {
    const write = vi.fn(() => Promise.resolve())
    navigator.clipboard.writeText = write
    render(<PageSpeedDiagnoseDialog monitor={speedMonitor} onClose={() => {}} />)
    await runWith(speedSlow())
    pressMenuTrigger(within(dlg()).getByRole('button', { name: /(Raporu kopyala|Copy report)/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /markdown/i }))
    await waitFor(() => expect(write).toHaveBeenCalled())
    const text = write.mock.calls[0][0]
    expect(text).toMatch(/^# SiteMonitor — (Page speed diagnosis report|Sayfa Hızı tanılama raporu)/)
    expect(text).toMatch(/## (Why slow\? — measurement analysis|Neden yavaş\? — ölçüm çözümlemesi)/)
  })

  it('geçmiş Sayfa Hızı uçlarından; boş liste', async () => {
    render(<PageSpeedDiagnoseDialog monitor={speedMonitor} onClose={() => {}} />)
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(Geçmiş|History)$/ }))
    await waitFor(() => expect(api.monitoring.pageSpeedDiagnoseHistory).toHaveBeenCalledWith(11))
  })

  it('429 → hız sınırı şeridi (Sayfa Hızı ucunun yolu)', async () => {
    recent.value = [{ path: '/api/monitoring/pagespeed/11/diagnose', status: 429 }]
    api.monitoring.diagnosePageSpeed.mockResolvedValueOnce({ success: false, error: 'Too many' })
    render(<PageSpeedDiagnoseDialog monitor={speedMonitor} onClose={() => {}} />)
    fireEvent.click(startBtn())
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-rate-limit"]')).not.toBeNull())
  })
})
