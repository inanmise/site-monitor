import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { KeywordFailureCell, KeywordFailurePanel } from '../components/keyword/KeywordCheckFailure.jsx'
import { failedChecks, kwMonitor } from './helpers/keywordDiagnoseFixtures.js'

/**
 * Keyword kontrol geçmişi teşhis hücresi + paneli (2026-10-04): neden rozeti + tek satır + aç/kapa; panelde Neden /
 * Etkisi / Ne yapmalı, kayıttaki ayrıntılar, ipucu kartları, vurgulu alıntı, teknik ayrıntı ve (yalnız can_diagnose)
 * "Bu kontrolü tanıla". Eski satır "ayrıntı kaydedilmemiş" der. Sorgular rol / data-slot ile.
 */
const [fresh, legacy] = failedChecks().data.items
const slot = (root, s) => root.querySelector(`[data-slot="${s}"]`)

describe('KeywordFailureCell', () => {
  it('rozet (kısa neden) + tek satır açıklama + aç/kapa düğmesi (aria-expanded, zamanlı erişilebilir ad)', () => {
    const onToggle = vi.fn()
    const { container, rerender } = render(<KeywordFailureCell check={fresh} monitor={kwMonitor} open={false} onToggle={onToggle} when="04.10 09:00" />)
    const cell = slot(container, 'kwfail-cell')
    expect(cell).toHaveAttribute('data-code', 'HTTP_STATUS')
    expect(slot(container, 'kwfail-badge').textContent).toMatch(/^(HTTP 403 returned|HTTP 403 döndü)$/)
    expect(slot(container, 'kwfail-oneline').textContent).toMatch(/403/)
    const btn = screen.getByRole('button', { name: /04\.10 09:00 · (HTTP 403 returned|HTTP 403 döndü) — (Show details|Ayrıntıyı göster)/ })
    expect(btn).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(btn)
    expect(onToggle).toHaveBeenCalledTimes(1)
    rerender(<KeywordFailureCell check={fresh} monitor={kwMonitor} open onToggle={onToggle} when="04.10 09:00" />)
    expect(screen.getByRole('button', { name: /(Hide details|Ayrıntıyı gizle)/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('eski satır: en yakın neden (hata metninden) + data-legacy', () => {
    const { container } = render(<KeywordFailureCell check={legacy} monitor={kwMonitor} open={false} onToggle={() => {}} when="x" />)
    expect(slot(container, 'kwfail-cell')).toHaveAttribute('data-legacy', 'true')
    expect(slot(container, 'kwfail-cell')).toHaveAttribute('data-code', 'TIMEOUT_READ')
  })

  it('başarılı satırda hiçbir şey çizilmez', () => {
    const { container } = render(<KeywordFailureCell check={{ ok: true }} monitor={kwMonitor} open={false} onToggle={() => {}} when="x" />)
    expect(container.firstChild).toBeNull()
  })
})

describe('KeywordFailurePanel', () => {
  it('Neden / Etkisi / Ne yapmalı + ayrıntılar + ipucu kartları + vurgulu alıntı + teknik ayrıntı + tanıla eylemi', () => {
    const onDiagnose = vi.fn()
    const { container } = render(<KeywordFailurePanel check={fresh} monitor={kwMonitor} canDiagnose onDiagnose={onDiagnose} />)
    const panel = slot(container, 'kwfail-panel')
    expect(panel).toHaveAttribute('data-code', 'HTTP_STATUS')
    expect(slot(panel, 'kwfail-why').textContent).toMatch(/403/)
    expect(slot(panel, 'kwfail-effect').textContent.length).toBeGreaterThan(10)
    expect(slot(panel, 'kwfail-fix').textContent).toMatch(/WAF/)
    // kayıttaki ayrıntılar: durum, son adres, içerik türü, boyut, karakter kümesi, süre, yol
    const keys = [...panel.querySelectorAll('[data-slot="kwfail-details"] [data-key]')].map((e) => e.getAttribute('data-key'))
    expect(keys).toEqual(['status', 'count', 'finalUrl', 'contentType', 'size', 'charset', 'responseMs', 'route'])
    expect(panel.querySelector('[data-key="route"]').textContent).toMatch(/^(Proxy|Vekil)$/)
    // ipucu kartları: başlık + neden/etkisi/ne yapmalı
    const hints = panel.querySelectorAll('[data-slot="kwfail-hint"]')
    expect([...hints].map((h) => h.getAttribute('data-code'))).toEqual(['WAF_OR_BLOCK_PAGE', 'LOGIN_PAGE'])
    expect(hints[0].textContent).toMatch(/(WAF \/ block page returned|WAF \/ engelleme sayfası geldi)/)
    // alıntı: kelime bu alıntıda YOK → açıkça söylenir, işaret yok
    const ex = slot(panel, 'kwfail-excerpt')
    expect(ex).toHaveAttribute('data-has-keyword', 'false')
    expect(ex.textContent).toMatch(/Access Denied/)
    expect(panel.querySelectorAll('[data-slot="kwfail-mark"]')).toHaveLength(0)
    // teknik ayrıntı: sunucunun TR ayrıntısı
    expect(slot(panel, 'kwfail-technical').textContent).toMatch(/Sunucu HTTP 403/)
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    expect(onDiagnose).toHaveBeenCalledTimes(1)
    expect(slot(panel, 'kwfail-legacy')).toBeNull()
  })

  it('alıntıda kelime VARSA vurgulanır', () => {
    const { container } = render(<KeywordFailurePanel check={{ ...fresh, failure_reason: 'KEYWORD_FOUND_FORBIDDEN', occurrences: 1,
      excerpt: 'Yeni Kampanya başladı' }} monitor={{ ...kwMonitor, operator: 'LTE', match_count: 0 }} />)
    expect(slot(container, 'kwfail-excerpt')).toHaveAttribute('data-has-keyword', 'true')
    expect(slot(container, 'kwfail-mark').textContent).toBe('Kampanya')
  })

  it('can_diagnose yoksa tanıla düğmesi YOK; eski satırda "ayrıntı kaydedilmemiş" notu ve yalnız ham hata', () => {
    const { container } = render(<KeywordFailurePanel check={legacy} monitor={kwMonitor} canDiagnose={false} onDiagnose={() => {}} />)
    expect(screen.queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
    expect(slot(container, 'kwfail-legacy').textContent).toMatch(/(no details recorded|ayrıntı kaydedilmemiş)/)
    expect(slot(container, 'kwfail-technical').textContent).toMatch(/request timed out/)
    expect(slot(container, 'kwfail-excerpt')).toBeNull()
    expect(container.querySelectorAll('[data-slot="kwfail-hint"]')).toHaveLength(0)
  })
})
