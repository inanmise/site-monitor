import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent } from './test-utils.jsx'
import { KeywordFailurePanel } from '../components/keyword/KeywordCheckFailure.jsx'
import { failedChecks, kwMonitor } from './helpers/keywordDiagnoseFixtures.js'

/**
 * Kontrol geçmişi hata paneli — kw paket C / F3 eklemeleri: kural sonuçları bölümü (satırın `rule_results`'ı), giriş /
 * JavaScript sayfası ipucunda "Sentetik izleme ile kur" (yalnız `onConvert` verildiğinde), kural #1 düzenli ifadeyse
 * alıntıda metin araması / vurgu YOK (yanlış "geçmiyor" demesin), Türkçe katlamalı alıntı araması.
 */
const [fresh] = failedChecks().data.items
const slot = (root, s) => root.querySelector(`[data-slot="${s}"]`)
const slots = (root, s) => [...root.querySelectorAll(`[data-slot="${s}"]`)]

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('KeywordFailurePanel — kural sonuçları ve sentetik öneri', () => {
  it('satırda kural sonucu varsa her kural sonucu listelenir (etiketler izlemeden); yoksa bölüm yok', () => {
    const monitor = {
      ...kwMonitor, alert_level: 'HIGH', rule_logic: 'AND',
      rules: [
        { id: 'r1', kind: 'TEXT', pattern: 'Kampanya', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: 'Kampanya başlığı' },
        { id: 'r2', kind: 'TEXT', pattern: 'Stokta yok', case_sensitive: false, operator: 'LTE', count: 0, critical: { operator: 'LTE', count: 3 }, scope: null, label: '' },
      ],
    }
    const check = { ...fresh, failure_reason: 'KEYWORD_COUNT_MISMATCH', breach_level: 'CRITICAL',
      rule_results: [{ id: 'r1', kind: 'TEXT', pattern_preview: 'Kampanya', count: 0, ok: false, level: 'MONITOR' },
        { id: 'r2', kind: 'TEXT', pattern_preview: 'Stokta yok', count: 4, ok: false, level: 'CRITICAL', note: 'COUNT_CAPPED' }] }
    const { container } = render(<KeywordFailurePanel check={check} monitor={monitor} />)
    const box = slot(container, 'kwfail-rules')
    expect(box).not.toBeNull()
    const rows = slots(box, 'kw-rule-result')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Kampanya başlığı')
    expect(rows[0].getAttribute('data-level')).toBe('HIGH')   // MONITOR → izlemenin seviyesi
    expect(rows[1].getAttribute('data-level')).toBe('CRITICAL')
    expect(slot(rows[1], 'kw-rule-result-note').getAttribute('data-note')).toBe('COUNT_CAPPED')
    // Bilinmeyen not ekrana ham düşmez
    const { container: c2 } = render(<KeywordFailurePanel check={{ ...check, rule_results: [{ id: 'r1', ok: false, note: 'GARIP' }] }} monitor={monitor} />)
    expect(slot(c2, 'kw-rule-result-note')).toBeNull()
    expect(c2.textContent).not.toContain('GARIP')

    const { container: c3 } = render(<KeywordFailurePanel check={fresh} monitor={kwMonitor} />)
    expect(slot(c3, 'kwfail-rules')).toBeNull()
  })

  it('giriş sayfası / JS ipucunda "Sentetik izleme ile kur" — yalnız onConvert verildiğinde; tıklama çağırır', () => {
    const onConvert = vi.fn()
    const { container } = render(<KeywordFailurePanel check={fresh} monitor={kwMonitor} onConvert={onConvert} />)
    fireEvent.click(slot(container, 'kwfail-convert'))
    expect(onConvert).toHaveBeenCalledTimes(1)
    expect(slot(container, 'kwfail-synthetic')).toHaveTextContent(/JavaScript/)

    const { container: noPerm } = render(<KeywordFailurePanel check={fresh} monitor={kwMonitor} />)
    expect(slot(noPerm, 'kwfail-convert')).toBeNull()

    const { container: other } = render(<KeywordFailurePanel check={{ ...fresh, hints: ['WAF_OR_BLOCK_PAGE'] }} monitor={kwMonitor} onConvert={onConvert} />)
    expect(slot(other, 'kwfail-convert')).toBeNull()
    const { container: js } = render(<KeywordFailurePanel check={{ ...fresh, hints: ['JS_RENDERED'] }} monitor={kwMonitor} onConvert={onConvert} />)
    expect(slot(js, 'kwfail-convert')).not.toBeNull()
  })

  it('alıntı: Türkçe katlama ile arar (İSTANBUL ~ istanbul); kural #1 düzenli ifadeyse arama / vurgu yok', () => {
    const tr = { ...kwMonitor, keyword: 'istanbul şubesi' }
    const { container } = render(<KeywordFailurePanel check={{ ...fresh, excerpt: 'Hoş geldiniz İSTANBUL ŞUBESİ sayfası' }} monitor={tr} />)
    expect(slot(container, 'kwfail-excerpt').getAttribute('data-has-keyword')).toBe('true')
    expect(slot(container, 'kwfail-mark')).toHaveTextContent('İSTANBUL ŞUBESİ')

    const rx = { ...kwMonitor, keyword: 'Kampanya\\s+\\d+', rules: [{ id: 'r1', kind: 'REGEX', pattern: 'Kampanya\\s+\\d+', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: '' }] }
    const { container: c2 } = render(<KeywordFailurePanel check={{ ...fresh, excerpt: 'Kampanya 12 bitti' }} monitor={rx} />)
    expect(slot(c2, 'kwfail-excerpt').getAttribute('data-has-keyword')).toBe('')
    expect(slot(c2, 'kwfail-mark')).toBeNull()
    expect(c2.textContent).not.toMatch(/bu alıntıda geçmiyor/)
  })
})
