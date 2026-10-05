import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { DIAG_HISTORY_ROWS, pageBroken, pageMonitor, pagePathDiffers } from './helpers/pageDiagnoseFixtures.js'
import PageDiagnoseDialog from '../components/page/diagnose/PageDiagnoseDialog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const recent = vi.hoisted(() => ({ value: [] }))
// Kap genişliği (useElementWidth) — 0 = ölçüm yok (tablo); dar değer kart görünümünü zorlar.
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidthState: () => [width.value, () => {}],
  useElementWidth: () => [() => {}, width.value],
}))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => recent.value,
  api: withApiFallback({
    monitoring: {
      diagnosePage: vi.fn(),
      pageDiagnoseHistory: vi.fn(),
      pageDiagnoseRun: vi.fn(),
      diagnoseHttp: vi.fn(),
      httpDiagnoseHistory: vi.fn(),
      diagnosePageSpeed: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Sayfa Bütünlüğü uçtan uca tanılama penceresi (2026-10-05) — HTTP penceresinin aynısı + kaynak parçaları: açılışta
 * KOŞMAZ; başlat → hüküm (RESOURCES_BROKEN, pgdx metni) → KAYNAK ÇÖZÜMLEMESİ (kayıt durumu, sayaçlar, kurallar, sorun
 * listesi tablo ↔ kart) → yollar (alarm sorunu ölçüsü); PATH_DIFFERS page varyantı; geçmiş Sayfa Bütünlüğü uçlarından;
 * rapor kaynak bölümünü taşır; 429 şeridi türün yolundan; HTTP / Sayfa Hızı uçları HİÇ çağrılmaz.
 */
const dlg = () => screen.getByRole('dialog', { name: /page integrity diagnosis|sayfa bütünlüğü tanılama/i })
const startBtn = () => within(dlg()).getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ })

async function runWith(data) {
  api.monitoring.diagnosePage.mockResolvedValueOnce({ success: true, data })
  fireEvent.click(startBtn())
  return waitFor(() => {
    const el = document.querySelector('[data-slot="httpdx-result"]')
    if (!el) throw new Error('sonuç yok')
    return el
  })
}

describe('PageDiagnoseDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    recent.value = []
    width.value = 0
    api.monitoring.pageDiagnoseHistory.mockResolvedValue({ success: true, data: DIAG_HISTORY_ROWS })
    api.monitoring.pageDiagnoseRun.mockResolvedValue({ success: true, data: pageBroken() })
  })

  it('başlangıç: izleme kipi hedef şeridinde, "Denenen" satırı; API çağrılmaz', () => {
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    expect(dlg().querySelector('[data-slot="httpdx-body"]')).toHaveAttribute('data-kind', 'page')
    expect(dlg().querySelector('[data-slot="pgdx-target"] [data-mode]')).toHaveAttribute('data-mode', 'SINGLE_PAGE')
    expect(dlg().querySelector('[data-slot="pgdx-start-checked"]').textContent).toMatch(/(start page|Başlangıç sayfası)/)
    expect(dlg().querySelector('[data-slot="httpdx-start"]').textContent).toMatch(/(every resource|her kaynak)/)
    expect(api.monitoring.diagnosePage).not.toHaveBeenCalled()
  })

  it('kırık kaynak: hüküm RESOURCES_BROKEN (adım: alt kaynaklar); çözümleme sayaçlar, kurallar, tablo; ham anahtar yok', async () => {
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    const res = await runWith(pageBroken())
    expect(api.monitoring.diagnosePage).toHaveBeenCalledWith(9, { compare: true }, expect.anything())
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    const verdict = res.querySelector('[data-slot="httpdx-verdict"]')
    expect(verdict).toHaveAttribute('data-code', 'RESOURCES_BROKEN')
    expect(verdict).toHaveAttribute('data-status', 'fail')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-step"]')).toHaveAttribute('data-step', 'resources')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-step"]').textContent).toMatch(/(Sub-resources|Alt kaynaklar)/)
    expect(verdict.querySelector('[data-slot="httpdx-verdict-body"]').textContent).toMatch(/42/)
    expect(verdict.querySelector('[data-slot="httpdx-findings"]').textContent).toMatch(/(your own site|kendi sitenizde)/)
    expect(res.textContent).not.toMatch(/pgdx\.|psdx\.|httpdx\.finding/)
    const a = res.querySelector('[data-slot="pgdx-analysis"]')
    expect(a).toHaveAttribute('data-status', 'DEGRADED')
    expect(a.querySelector('[data-slot="pgdx-recorded"]').textContent).toMatch(/(Degraded|Bozulmuş)/)
    expect(a.querySelector('[data-metric="broken"]').textContent).toMatch(/^3/)
    expect(a.querySelector('[data-metric="alarm"]').textContent).toMatch(/^1/)
    expect(a.querySelector('[data-rule="thirdParty"]')).toHaveAttribute('data-on', 'false')
    const list = a.querySelector('[data-slot="pgdx-issues"]')
    expect(list).toHaveAttribute('data-view', 'table')
    const rows = list.querySelectorAll('[data-slot="pgdx-issue"]')
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveAttribute('data-alarm', 'true')
    expect(rows[0].querySelector('[data-slot="pgdx-alarm"]')).not.toBeNull()
    // yol kartı: alarm sorunu ölçüsü
    expect(res.querySelector('[data-slot="httpdx-path-card"] [data-metric="alarm"]').textContent).toMatch(/^1/)
  })

  it('dar kap: sorunlar KART görünümünde (uzun URL kırılır)', async () => {
    width.value = 360
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    const res = await runWith(pageBroken())
    const list = res.querySelector('[data-slot="pgdx-issues"]')
    expect(list).toHaveAttribute('data-view', 'cards')
    expect(list.querySelectorAll('li[data-slot="pgdx-issue"]')).toHaveLength(4)
    expect(list.querySelector('table')).toBeNull()
  })

  it('PATH_DIFFERS (page varyantı): iki yol, sayfa çözümlenemedi notu', async () => {
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    const res = await runWith(pagePathDiffers())
    const verdict = res.querySelector('[data-slot="httpdx-verdict"]')
    expect(verdict).toHaveAttribute('data-code', 'PATH_DIFFERS')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-title"]').textContent)
      .toMatch(/(looks broken on the monitor's route|sayfa bozuk görünüyor)/)
    expect(res.querySelectorAll('[data-slot="httpdx-path-card"]')).toHaveLength(2)
    expect(res.querySelector('[data-slot="pgdx-unanalyzed"]')).not.toBeNull()
  })

  it('geçmiş Sayfa Bütünlüğü uçlarından; kayıtlı çalıştırma açılır', async () => {
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(Geçmiş|History)$/ }))
    const row = await waitFor(() => { const r = dlg().querySelector('[data-slot="httpdx-history-row"]'); if (!r) throw new Error('satır yok'); return r })
    expect(api.monitoring.pageDiagnoseHistory).toHaveBeenCalledWith(9)
    expect(row.textContent).toMatch(/(There are broken resources|Kırık kaynaklar var)/)
    fireEvent.click(row)
    await waitFor(() => expect(api.monitoring.pageDiagnoseRun).toHaveBeenCalledWith(9, 601))
    const res = await waitFor(() => { const r = document.querySelector('[data-slot="httpdx-result"]'); if (!r) throw new Error('yok'); return r })
    expect(res).toHaveAttribute('data-stored', 'true')
    expect(api.monitoring.diagnosePage).not.toHaveBeenCalled()
  })

  it('Raporu kopyala → Sayfa Bütünlüğü başlığı + kaynak bölümü', async () => {
    const write = vi.fn(() => Promise.resolve())
    navigator.clipboard.writeText = write
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    await runWith(pageBroken())
    pressMenuTrigger(within(dlg()).getByRole('button', { name: /(Raporu kopyala|Copy report)/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /markdown/i }))
    await waitFor(() => expect(write).toHaveBeenCalled())
    const text = write.mock.calls[0][0]
    expect(text).toMatch(/^# SiteMonitor — (Page integrity diagnosis report|Sayfa Bütünlüğü tanılama raporu)/)
    expect(text).toMatch(/## (Resource analysis|Kaynak çözümlemesi)/)
    expect(text).toContain('https://shop.example.com/img/yok.png')
  })

  it('429 → hız sınırı şeridi (Sayfa Bütünlüğü ucunun yolu son başarısızlıklarda)', async () => {
    recent.value = [{ path: '/api/monitoring/page/9/diagnose', status: 429 }]
    api.monitoring.diagnosePage.mockResolvedValueOnce({ success: false, error: 'Too many' })
    render(<PageDiagnoseDialog monitor={pageMonitor} onClose={() => {}} />)
    fireEvent.click(startBtn())
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-rate-limit"]')).not.toBeNull())
  })
})
