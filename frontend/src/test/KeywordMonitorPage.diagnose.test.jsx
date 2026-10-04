import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import { failedChecks, kwMonitor, kwPathDiffers } from './helpers/keywordDiagnoseFixtures.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// Kap genişliği (useElementWidthState) — 0 = ölçüm yok (tablo); dar değer kart görünümünü zorlar.
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidthState: () => [width.value, () => {}],
  useElementWidth: () => [() => {}, width.value],
}))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    monitoring: {
      listGroups:           vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:      vi.fn(() => Promise.resolve({ success: true, data: { keyword: {} } })),
      getKeywordMonitors:   vi.fn(),
      getCheckHistory:      vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      triggerKeywordCheck:  vi.fn(),
      diagnoseKeyword:      vi.fn(),
      keywordDiagnoseHistory: vi.fn(),
      keywordDiagnoseRun:   vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Keyword sayfası — hata teşhisi + uçtan uca tanılama GİRİŞ NOKTALARI (2026-10-04): kontrol geçmişinde başarısız satır
 * neden rozeti + tek satır açıklama + aç/kapa ile çizilir, açılınca satırın altında teşhis paneli; eski satır en yakın
 * nedenle. "Uçtan uca tanıla" (başlık) ve "Bu kontrolü tanıla" (panel) YALNIZ `can_diagnose` ile; pencere kendiliğinden
 * koşmaz; derin bağlantı `kdx` kayıtlı çalıştırmayı açar. Dar kapta geçmiş kart görünümüne iner, panel kartın içinde.
 */
const diagBtn = () => document.querySelector('[data-slot="kwdx-open"]')
const diagDialog = () => screen.queryByRole('dialog', { name: /keyword diagnosis|anahtar kelime tanılama/i })

async function openDetail(row = kwMonitor) {
  api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [row] })
  window.history.replaceState({}, '', '/?tab=keyword&monitor=7')
  render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
  await waitFor(() => { if (!document.querySelector('[data-slot="kwfail-cell"]')) throw new Error('geçmiş yok') })
}

describe('KeywordMonitorPage — hata teşhisi ve uçtan uca tanılama', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    width.value = 0
    api.monitoring.getCheckHistory.mockResolvedValue(failedChecks())
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { keyword: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [] })
    api.monitoring.keywordDiagnoseHistory.mockResolvedValue({ success: true, data: [] })
    api.monitoring.keywordDiagnoseRun.mockResolvedValue({ success: true, data: kwPathDiffers() })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('geçmiş: başarısız satırda neden rozeti + tek satır; eski satır en yakın nedenle; başarılı satır eskisi gibi', async () => {
    await openDetail()
    const cells = document.querySelectorAll('[data-slot="kwfail-cell"]')
    expect(cells).toHaveLength(2)
    expect(cells[0]).toHaveAttribute('data-code', 'HTTP_STATUS')
    expect(cells[0].querySelector('[data-slot="kwfail-badge"]').textContent).toMatch(/^(HTTP 403 returned|HTTP 403 döndü)$/)
    expect(cells[1]).toHaveAttribute('data-code', 'TIMEOUT_READ')
    expect(cells[1]).toHaveAttribute('data-legacy', 'true')
    // başarılı satır: "2 kez bulundu · ≥1" (eski hücre)
    expect(document.querySelector('[data-slot="hist-rows"]').textContent).toMatch(/2\s*(occurrence\(s\) found|kez bulundu)/)
    expect(document.querySelector('[data-slot="hist-rows"]')).toHaveAttribute('data-view', 'table')
  })

  it('aç/kapa: panel satırın ALTINDA tam genişlik ek satırda; tekrar basınca kapanır', async () => {
    await openDetail()
    const toggle = document.querySelectorAll('[data-slot="kwfail-toggle"]')[0]
    expect(document.querySelector('[data-slot="kwfail-panel"]')).toBeNull()
    fireEvent.click(toggle)
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="kwfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(panel.closest('[data-slot="hist-row-extra"]')).not.toBeNull()
    expect(panel.querySelectorAll('[data-slot="kwfail-hint"]')).toHaveLength(2)
    fireEvent.click(document.querySelectorAll('[data-slot="kwfail-toggle"]')[0])
    await waitFor(() => expect(document.querySelector('[data-slot="kwfail-panel"]')).toBeNull())
  })

  it('can_diagnose YOKSA: başlıkta tanıla düğmesi yok, panelde "Bu kontrolü tanıla" yok', async () => {
    await openDetail()
    expect(diagBtn()).toBeNull()
    fireEvent.click(document.querySelectorAll('[data-slot="kwfail-toggle"]')[0])
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="kwfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(within(panel).queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
  })

  it('can_diagnose VARSA: panelden "Bu kontrolü tanıla" → tanılama penceresi başlangıç ekranıyla açılır, API çağrılmaz', async () => {
    await openDetail({ ...kwMonitor, can_diagnose: true })
    expect(diagBtn()).not.toBeNull()
    fireEvent.click(document.querySelectorAll('[data-slot="kwfail-toggle"]')[0])
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="kwfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    expect(dlg.querySelector('[data-slot="httpdx-body"]')).toHaveAttribute('data-kind', 'keyword')
    expect(dlg.querySelector('[data-slot="httpdx-start"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="kwdx-target"]').textContent).toMatch(/Kampanya/)
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(api.monitoring.diagnoseKeyword).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(kapat|close)$/i })[0])
    await waitFor(() => expect(diagDialog()).toBeNull())
  })

  it('başlıktaki "Uçtan uca tanıla" düğmesi pencereyi açar', async () => {
    await openDetail({ ...kwMonitor, can_diagnose: true })
    fireEvent.click(diagBtn())
    await waitFor(() => expect(diagDialog()).not.toBeNull())
    expect(api.monitoring.diagnoseKeyword).not.toHaveBeenCalled()
  })

  it('derin bağlantı ?monitor=7&kdx=501 → KAYITLI çalıştırma açılır, canlı tanılama başlamaz; kdx URL\'de kalır', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [{ ...kwMonitor, can_diagnose: true }] })
    window.history.replaceState({}, '', '/?tab=keyword&monitor=7&kdx=501')
    render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => expect(api.monitoring.keywordDiagnoseRun).toHaveBeenCalledWith(7, 501))
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    await waitFor(() => expect(dlg.querySelector('[data-slot="kwdx-analysis"]')).not.toBeNull())
    expect(api.monitoring.diagnoseKeyword).not.toHaveBeenCalled()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('kdx')).toBe('501'), { timeout: 2000 })
  })

  it('derin bağlantı kdx ama can_diagnose yok → pencere açılmaz, kayıt istenmez', async () => {
    api.monitoring.getKeywordMonitors.mockResolvedValue({ success: true, data: [kwMonitor] })
    window.history.replaceState({}, '', '/?tab=keyword&monitor=7&kdx=501')
    render(<KeywordMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => { if (!document.querySelector('[data-slot="kwfail-cell"]')) throw new Error('geçmiş yok') })
    expect(diagDialog()).toBeNull()
    expect(api.monitoring.keywordDiagnoseRun).not.toHaveBeenCalled()
  })

  it('dar kap (360 px): geçmiş KART görünümünde, açılan panel kartın içinde', async () => {
    width.value = 360
    await openDetail()
    expect(document.querySelector('[data-slot="hist-rows"]')).toHaveAttribute('data-view', 'cards')
    fireEvent.click(document.querySelectorAll('[data-slot="kwfail-toggle"]')[0])
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="kwfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(panel.closest('[data-slot="hist-card"]')).not.toBeNull()
  })
})
