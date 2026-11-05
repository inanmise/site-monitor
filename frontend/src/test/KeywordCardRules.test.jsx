import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: [] } })) } },
  }),
}))

import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import {
  breachLevel, changeState, highlightParts, metaRow, ruleOutcome, ruleSet, sameUrl, sizeRule, statusUnchecked, verdictOf,
} from '../components/keyword/keywordCardModel.js'

const stamp = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const RULES = [
  { id: 'r1', kind: 'TEXT', pattern: 'Hoş geldiniz', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: 'Karşılama' },
  { id: 'r2', kind: 'REGEX', pattern: 'Hata\\s+\\d{3}', case_sensitive: false, operator: 'LTE', count: 0, critical: { operator: 'LTE', count: 5 }, scope: null, label: '' },
  { id: 'r3', kind: 'TEXT', pattern: 'Bakım', case_sensitive: false, operator: 'LTE', count: 0, critical: null, scope: null, label: '' },
  { id: 'r4', kind: 'TEXT', pattern: 'Kampanya', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: '' },
]
const base = {
  id: 9, name: 'Kurumsal site', url: 'https://www.example.com/', keyword: 'Hoş geldiniz', operator: 'GTE', match_count: 1,
  case_sensitive: false, status: 'up', found: true, occurrences: 3, ok: true, http_status: 200, response_ms: 212, error: null,
  snippet: null, active: true, team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', interval_seconds: 300,
  timeout_ms: 10000, use_proxy: 'AUTO', proxy_effective: 'direct', proxy_source: 'none', proxy_bypassed: false,
  slow_response_enabled: false, slow_threshold_ms: 3000, checked_at: stamp(60_000), alert_level: 'HIGH',
}
const statusKey = (m) => (m.status === 'up' ? 'up' : m.status === 'unknown' ? 'unknown' : 'down')
const slot = (root, s) => root.querySelector(`[data-slot="${s}"]`)
const slots = (root, s) => [...root.querySelectorAll(`[data-slot="${s}"]`)]

function renderCard(over = {}) {
  const m = { ...base, ...over }
  return render(<KeywordMonitorCard monitor={m} status={statusKey(m)} onOpen={() => {}} meta={<MonitorCardMeta monitor={metaRow(m)} />} />)
}

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('keywordCardModel — kural seti yardımcıları', () => {
  it('kural seti: eski tek kural = basit; kural listesi = çoklu; sonuç bozuk / eksikse bilinmiyor', () => {
    expect(ruleSet(base)).toMatchObject({ multi: false, custom: false, results: null, failed: null })
    const s = ruleSet({ ...base, rules: RULES, rule_logic: 'OR', rule_results: [{ id: 'r1', ok: true }, { id: 'r2', ok: false }, null] })
    expect(s).toMatchObject({ multi: true, logic: 'OR', failed: 1, evaluated: 2 })
    expect(ruleOutcome(s, 'r1')).toBe(true)
    expect(ruleOutcome(s, 'r2')).toBe(false)
    expect(ruleOutcome(s, 'r3')).toBeNull()
    // Tek ama özel kural (düzenli ifade) "custom"
    expect(ruleSet({ ...base, rules: [RULES[1]] })).toMatchObject({ multi: false, custom: true })
  })

  it('karar: çoklu kural → rulesOk / rulesFail / countFail; tek kural eski kararlar', () => {
    expect(verdictOf({ ...base, rules: RULES }).kind).toBe('rulesOk')
    expect(verdictOf({ ...base, rules: RULES, status: 'down', rule_results: [{ id: 'r1', ok: false }, { id: 'r2', ok: true }] }))
      .toMatchObject({ kind: 'rulesFail', tone: 'bad', failed: 1, total: 4 })
    expect(verdictOf({ ...base, rules: RULES, status: 'down', rule_results: [] }).kind).toBe('countFail')
    expect(verdictOf({ ...base, status: 'down', found: false, occurrences: 0 }).kind).toBe('missing')
  })

  it('ihlal seviyesi yalnız kapalı izlemede, yalnız WARNING / CRITICAL', () => {
    expect(breachLevel({ status: 'down', breach_level: 'critical' })).toBe('CRITICAL')
    expect(breachLevel({ status: 'up', breach_level: 'CRITICAL' })).toBeNull()
    expect(breachLevel({ status: 'down', breach_level: 'MONITOR' })).toBeNull()
    expect(breachLevel({ status: 'down', breach_level: null })).toBeNull()
  })

  it('durum kodu denetlenmiyor: yalnız sağlıklı + HTTP ≥ 400 + kural kapalı (alan yok / boş)', () => {
    expect(statusUnchecked({ status: 'up', http_status: 404 })).toBe(404)
    expect(statusUnchecked({ status: 'up', http_status: 503, expected_status: '  ' })).toBe(503)
    expect(statusUnchecked({ status: 'up', http_status: 404, expected_status: '200-299' })).toBeNull()
    expect(statusUnchecked({ status: 'up', http_status: 200 })).toBeNull()
    expect(statusUnchecked({ status: 'down', http_status: 500 })).toBeNull()
  })

  it('aynı URL sayısı: görünen + gizli; ikisi de 0 / eksik → null', () => {
    expect(sameUrl({ same_url_count: 1, same_url_hidden_count: 2 })).toEqual({ visible: 1, hidden: 2, total: 3 })
    expect(sameUrl({ same_url_count: 0 })).toBeNull()
    expect(sameUrl({})).toBeNull()
  })

  it('değişim durumu ve boyut kuralı', () => {
    expect(changeState({ change_detect: false, content_changed: true })).toBeNull()
    expect(changeState({ change_detect: true, content_changed: true })).toBe('changed')
    expect(changeState({ change_detect: true, change_baseline: null })).toBe('noBaseline')
    expect(changeState({ change_detect: true })).toBe('watching')
    expect(changeState({ change_detect: true, change_baseline: { taken_at: 'x' }, content_changed: false })).toBe('watching')
    expect(sizeRule({})).toBeNull()
    expect(sizeRule({ min_bytes: 1000, status: 'down', failure_reason: 'BODY_TOO_SMALL', body_bytes: 300 })).toEqual({ min: 1000, max: null, bytes: 300, bad: true })
    expect(sizeRule({ max_bytes: 9000, status: 'up', failure_reason: 'BODY_TOO_LARGE' }).bad).toBe(false)
  })

  it('vurgulama Türkçe büyük / küçük harf katlamasını izler (İ / ı)', () => {
    expect(highlightParts('İSTANBUL şubesi', 'istanbul', false)).toEqual([{ text: 'İSTANBUL', match: true }, { text: ' şubesi', match: false }])
    expect(highlightParts('Kapalı', 'KAPALI', false)).toEqual([{ text: 'Kapalı', match: true }])
    expect(highlightParts('Kapalı', 'KAPALI', true)).toEqual([{ text: 'Kapalı', match: false }])
  })
})

describe('KeywordMonitorCard — kural seti görünümü', () => {
  it('çoklu kural: rozet "N kural · VE", ilk 3 kural satırı sonuç durumuyla + "+1 kural daha", kart başarısız sayıyı söyler', () => {
    const { container } = renderCard({
      rules: RULES, rule_logic: 'AND', status: 'down',
      rule_results: [{ id: 'r1', ok: true, count: 2 }, { id: 'r2', ok: false, count: 7, level: 'CRITICAL' }, { id: 'r3', ok: true, count: 0 }],
      breach_level: 'CRITICAL',
    })
    const badge = slot(container, 'keyword-rules-badge')
    expect(badge).toHaveTextContent('4 kural · VE')
    const rows = slots(container, 'keyword-rule-row')
    expect(rows.map((r) => r.getAttribute('data-outcome'))).toEqual(['ok', 'fail', 'ok'])
    expect(rows[0]).toHaveTextContent('Karşılama:')
    expect(rows[1]).toHaveTextContent('/Hata\\s+\\d{3}/')
    expect(container.textContent).toContain('+1 kural daha')
    expect(slot(container, 'keyword-result')).toHaveTextContent('1/4 kural sağlanmadı')
    expect(slot(container, 'keyword-breach').getAttribute('data-level')).toBe('CRITICAL')
    expect(slot(container, 'keyword-breach')).toHaveTextContent('Kritik eşik')
    // Çoklu kuralda tek kelime çipi ve alıntı çizilmez
    expect(slot(container, 'keyword-chip')).toBeNull()
    expect(slot(container, 'keyword-snippet')).toBeNull()
  })

  it('uzun desen / etiket iki satırda kırpılsa da tamamı dokun-gör balonunda (kırpılan metin kaybolmaz)', () => {
    const long = 'Sayın müşterimiz, internet şubemize hoş geldiniz — işlemlerinize kaldığınız yerden devam edebilirsiniz'
    const { container } = renderCard({ rules: [{ ...RULES[0], pattern: long, label: 'Karşılama metni (Bireysel İnternet Şubesi)' }, RULES[2]] })
    const [first, second] = slots(container, 'keyword-rule-row')
    expect(first.querySelector('button[aria-label*="tamamını göster"]')).not.toBeNull()
    expect(second.querySelector('button[aria-label*="tamamını göster"]')).toBeNull()
  })

  it('sonuç yoksa satırlar "bilinmiyor" (yeşil DEĞİL); sağlıklı çoklu kural "Kurallar sağlandı"', () => {
    const { container } = renderCard({ rules: RULES.slice(0, 2), rule_logic: 'OR' })
    expect(slots(container, 'keyword-rule-row').map((r) => r.getAttribute('data-outcome'))).toEqual(['unknown', 'unknown'])
    expect(slot(container, 'keyword-rules-badge')).toHaveTextContent('2 kural · VEYA')
    expect(slot(container, 'keyword-result')).toHaveTextContent('Kurallar sağlandı')
  })

  it('tek düzenli ifade kuralı: "Düzenli ifade" çipi, /desen/ biçimi, metin gibi vurgulanmaz; kapsam çipi', () => {
    const rule = { ...RULES[1], scope: { type: 'CSS', value: '#durum > .mesaj' }, critical: null }
    const { container } = renderCard({ rules: [rule], keyword: rule.pattern, snippet: 'Hata 500 oluştu' })
    expect(slot(container, 'keyword-regex')).toHaveTextContent('Düzenli ifade')
    expect(slot(container, 'keyword-chip').getAttribute('data-regex')).toBe('true')
    expect(slot(container, 'keyword-chip')).toHaveTextContent('/Hata\\s+\\d{3}/')
    expect(slot(container, 'keyword-scope')).toHaveTextContent('CSS: #durum > .mesaj')
    expect(container.querySelector('[data-slot="keyword-snippet"] mark')).toBeNull()
  })

  it('boyut / değişim / durum kodu / aynı URL çipleri yalnız ilgiliyse; adlar sarar (kırpılmaz)', () => {
    const { container } = renderCard({
      min_bytes: 1000, max_bytes: 2_000_000, body_bytes: 5000, change_detect: true, content_changed: true,
      http_status: 404, same_url_count: 1, same_url_hidden_count: 1,
    })
    expect(slot(container, 'keyword-size')).toHaveTextContent('Boyut 1 KB–2 MB')
    expect(slot(container, 'keyword-change').getAttribute('data-state')).toBe('changed')
    expect(slot(container, 'keyword-status-unchecked').getAttribute('data-status')).toBe('404')
    expect(slot(container, 'keyword-same-url')).toHaveTextContent('Aynı URL: +2')
    for (const s of ['keyword-breach', 'keyword-status-unchecked', 'keyword-same-url']) {
      const el = slot(container, s)
      if (el) expect(el.className).toMatch(/overflow-visible/)
    }
    const { container: plain } = renderCard({})
    for (const s of ['keyword-size', 'keyword-change', 'keyword-status-unchecked', 'keyword-same-url', 'keyword-rules-badge', 'keyword-breach']) {
      expect(slot(plain, s)).toBeNull()
    }
  })

  it('boyut kaynaklı başarısızlıkta boyut çipi kırmızı (data-bad)', () => {
    const { container } = renderCard({ status: 'down', min_bytes: 1000, body_bytes: 200, failure_reason: 'BODY_TOO_SMALL', found: true })
    expect(slot(container, 'keyword-size').getAttribute('data-bad')).toBe('true')
  })
})
