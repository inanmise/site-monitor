import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { KW_HISTORY_ROWS, kwFoundSingle, kwMonitor, kwPathDiffers } from './helpers/keywordDiagnoseFixtures.js'
import KeywordDiagnoseDialog from '../components/keyword/diagnose/KeywordDiagnoseDialog.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const recent = vi.hoisted(() => ({ value: [] }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  getRecentFailures: () => recent.value,
  api: withApiFallback({
    monitoring: {
      diagnoseKeyword: vi.fn(),
      keywordDiagnoseHistory: vi.fn(),
      keywordDiagnoseRun: vi.fn(),
      diagnoseHttp: vi.fn(),
      httpDiagnoseHistory: vi.fn(),
      httpDiagnoseRun: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

/**
 * Keyword uçtan uca tanılama penceresi (2026-10-04) — HTTP penceresinin aynısı + keyword parçaları: açılışta KOŞMAZ;
 * başlat → hüküm (PATH_DIFFERS keyword metni) → ANAHTAR KELİME ÇÖZÜMLEMESİ (bulunan / koşul, meta, alternatifler,
 * ipuçları, bağlam, görünür metin) → yollar (bulunan adet ölçüsü) → ayrıntı; geçmiş KEYWORD uçlarından; rapor keyword
 * bölümünü taşır; 429 şeridi keyword ucunun yoluna göre; HTTP uçları HİÇ çağrılmaz.
 */
const dlg = () => screen.getByRole('dialog', { name: /keyword diagnosis|anahtar kelime tanılama/i })
const startBtn = () => within(dlg()).getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ })

async function runWith(data) {
  api.monitoring.diagnoseKeyword.mockResolvedValueOnce({ success: true, data })
  fireEvent.click(startBtn())
  return waitFor(() => {
    const el = document.querySelector('[data-slot="httpdx-result"]')
    if (!el) throw new Error('sonuç yok')
    return el
  })
}

describe('KeywordDiagnoseDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    recent.value = []
    api.monitoring.keywordDiagnoseHistory.mockResolvedValue({ success: true, data: KW_HISTORY_ROWS })
    api.monitoring.keywordDiagnoseRun.mockResolvedValue({ success: true, data: kwPathDiffers() })
  })

  it('başlangıç: aranan metin + kural hedef şeridinde ve listede; API çağrılmaz; ölçülenler keyword metni', async () => {
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    expect(dlg().querySelector('[data-slot="httpdx-body"]')).toHaveAttribute('data-kind', 'keyword')
    expect(dlg().querySelector('[data-slot="kwdx-target"]').textContent).toMatch(/« Kampanya »/)
    const start = dlg().querySelector('[data-slot="httpdx-start"]')
    expect(start.textContent).toMatch(/(Searching for|Aranan)/)
    expect(start.textContent).toMatch(/(keyword analysis|anahtar kelime çözümlemesi)/)
    expect(api.monitoring.diagnoseKeyword).not.toHaveBeenCalled()
  })

  it('PATH_DIFFERS: keyword varyant metni, çözümleme bölümü, yol kartında bulunan adet, ipucu bulgusu çevrilmiş', async () => {
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    const res = await runWith(kwPathDiffers())
    expect(api.monitoring.diagnoseKeyword).toHaveBeenCalledWith(7, { compare: true }, expect.anything())
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    const verdict = res.querySelector('[data-slot="httpdx-verdict"]')
    expect(verdict).toHaveAttribute('data-code', 'PATH_DIFFERS')
    expect(verdict.querySelector('[data-slot="httpdx-verdict-title"]').textContent)
      .toMatch(/(keyword rule fails on the monitor's route|kelime koşulu sağlanmıyor)/)
    // diğer bulgular: keyword kodları kwdx / kwhint ad alanından (ham anahtar yok)
    const findings = verdict.querySelector('[data-slot="httpdx-findings"]')
    expect(findings.textContent).toMatch(/(Server returned an error status|Sunucu hata kodu döndü)/)
    expect(findings.textContent).toMatch(/(WAF \/ block page returned|WAF \/ engelleme sayfası geldi)/)
    expect(res.textContent).not.toMatch(/kwdx\.|kwhint\.|httpdx\.finding/)
    // çözümleme
    const a = res.querySelector('[data-slot="kwdx-analysis"]')
    expect(a).toHaveAttribute('data-met', 'false')
    expect(a.querySelector('[data-slot="kwdx-met"]').textContent).toMatch(/(Rule not met|Koşul sağlanmadı)/)
    expect(a.querySelector('[data-slot="kwdx-cap"]')).toHaveAttribute('data-truncated', 'false')
    expect(a.querySelectorAll('[data-slot="kwfail-hint"]')).toHaveLength(1)
    expect(a.querySelector('[data-slot="kwdx-no-contexts"]')).not.toBeNull()
    expect(a.querySelector('[data-slot="kwdx-visible"]').textContent).toMatch(/Access Denied/)
    // yol kartları: bulunan adet ölçüsü (izlemenin yolunda 0 kırmızı, öteki yolda 2)
    const cards = res.querySelectorAll('[data-slot="httpdx-path-card"]')
    expect(cards).toHaveLength(2)
    expect(cards[0].querySelector('[data-metric="occurrences"]').textContent).toMatch(/^0/)
    expect(cards[1].querySelector('[data-metric="occurrences"]').textContent).toMatch(/^2/)
  })

  it('bulundu: alternatif okuma fazlasını bulursa vurgulu; bağlamlar işaretli', async () => {
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    const res = await runWith(kwFoundSingle())
    expect(res.querySelector('[data-slot="httpdx-verdict"]')).toHaveAttribute('data-code', 'KEYWORD_OK')
    const alts = res.querySelectorAll('[data-slot="kwdx-alts"] [data-alt]')
    expect([...alts].map((e) => e.getAttribute('data-alt'))).toEqual(['raw', 'case_insensitive', 'normalized', 'visible_text'])
    expect(res.querySelector('[data-alt="normalized"]')).toHaveAttribute('data-better', 'true')
    expect(res.querySelector('[data-alt="raw"]')).not.toHaveAttribute('data-better')
    const ctx = res.querySelectorAll('[data-slot="kwdx-contexts"] li')
    expect(ctx).toHaveLength(2)
    expect(ctx[0].querySelector('mark').textContent).toBe('Kampanya')
  })

  it('geçmiş KEYWORD uçlarından; kayıtlı çalıştırma (bağlam/görünür metin kaydedilmez notları)', async () => {
    const stored = kwPathDiffers()
    stored.keyword = { ...stored.keyword, visible_text_preview: null, contexts: [], preview_stored: false }
    api.monitoring.keywordDiagnoseRun.mockResolvedValueOnce({ success: true, data: stored })
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    fireEvent.click(within(dlg()).getByRole('button', { name: /^(Geçmiş|History)$/ }))
    const row = await waitFor(() => { const r = dlg().querySelector('[data-slot="httpdx-history-row"]'); if (!r) throw new Error('satır yok'); return r })
    expect(api.monitoring.keywordDiagnoseHistory).toHaveBeenCalledWith(7)
    expect(api.monitoring.httpDiagnoseHistory).not.toHaveBeenCalled()
    fireEvent.click(row)
    await waitFor(() => expect(api.monitoring.keywordDiagnoseRun).toHaveBeenCalledWith(7, 501))
    const res = await waitFor(() => { const r = document.querySelector('[data-slot="httpdx-result"]'); if (!r) throw new Error('yok'); return r })
    expect(res).toHaveAttribute('data-stored', 'true')
    expect(res.querySelector('[data-slot="kwdx-visible-note"]').textContent).toMatch(/(not stored|kaydedilmez)/)
    expect(api.monitoring.diagnoseKeyword).not.toHaveBeenCalled()
  })

  it('Raporu kopyala → keyword başlığı + çözümleme bölümü', async () => {
    const write = vi.fn(() => Promise.resolve())
    navigator.clipboard.writeText = write
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    await runWith(kwPathDiffers())
    pressMenuTrigger(within(dlg()).getByRole('button', { name: /(Raporu kopyala|Copy report)/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /markdown/i }))
    await waitFor(() => expect(write).toHaveBeenCalled())
    const text = write.mock.calls[0][0]
    expect(text).toMatch(/^# SiteMonitor — (Keyword diagnosis report|Anahtar kelime tanılama raporu)/)
    expect(text).toContain('Authorization: ••••')
    expect(text).toMatch(/## (Keyword analysis|Anahtar kelime çözümlemesi)/)
    expect(text).toMatch(/« Kampanya »/)
    expect(text).toMatch(/(WAF \/ block page returned|WAF \/ engelleme sayfası geldi)/)
  })

  it('429 → hız sınırı şeridi (keyword ucunun yolu son başarısızlıklarda)', async () => {
    recent.value = [{ path: '/api/monitoring/keyword/7/diagnose', status: 429 }]
    api.monitoring.diagnoseKeyword.mockResolvedValueOnce({ success: false, error: 'Too many' })
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    fireEvent.click(startBtn())
    await waitFor(() => expect(dlg().querySelector('[data-slot="httpdx-rate-limit"]')).not.toBeNull())
  })

  it('403 → satır içi hata (sunucu mesajı)', async () => {
    api.monitoring.diagnoseKeyword.mockResolvedValueOnce({ success: false, status: 403, error: 'Tanılama çalıştırma izniniz yok (diagnostics.run).' })
    render(<KeywordDiagnoseDialog monitor={kwMonitor} onClose={() => {}} />)
    fireEvent.click(startBtn())
    const msg = await waitFor(() => { const m = dlg().querySelector('[data-slot="httpdx-error-msg"]'); if (!m) throw new Error('yok'); return m })
    expect(msg).toHaveAttribute('data-kind', 'forbidden')
    expect(msg.textContent).toMatch(/diagnostics\.run/)
  })
})
