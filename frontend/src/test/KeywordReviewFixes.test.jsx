import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({
      monitoring: {
        keywordChange: vi.fn(),
        takeKeywordBaseline: vi.fn(),
        clearKeywordBaseline: vi.fn(),
        keywordContentSeries: vi.fn(),
        keywordMatches: vi.fn(),
      },
    }),
    formatDate: (s) => String(s),
  }
})

import { api } from '../api/client'
import RuleTestResults from '../components/keyword/rules/RuleTestResults.jsx'
import KeywordContentTab from '../components/keyword/detail/KeywordContentTab.jsx'
import KeywordChangePanel from '../components/keyword/detail/KeywordChangePanel.jsx'
import KeywordMatchesPanel from '../components/keyword/detail/KeywordMatchesPanel.jsx'
import { KeywordFailurePanel } from '../components/keyword/KeywordCheckFailure.jsx'
import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import KeywordRulesEditor from '../components/keyword/rules/KeywordRulesEditor.jsx'
import KeywordRequestSection from '../components/keyword/KeywordRequestSection.jsx'
import { KW_EMPTY_FORM } from '../components/keyword/keywordFormModel.js'

/**
 * Keyword son inceleme (kw-review-fe.md) — bileşen düzeltmeleri: kural hükmü genel kararı yalanlamaz (Y1), kart kural dışı
 * nedeni hüküm yapar (O1), içerik sekmesi basit kuralda / okunamayan sayfada doğru konuşur (DÜŞÜK-7), değişim ölçülemediyse
 * "aynıydı" yeşili yok + temel metni gizliyse söylenir, canlı eşleşme notları (DÜŞÜK-5), hata panelinde kural listesi bir kez
 * (DÜŞÜK-2), düzenleyicide B10 bilgi notu / kritik eşik kilidi / koşul seçicisi sarar, "Özel" durum kodu bölüm kapanınca kalır.
 */

const slot = (s, root = document) => root.querySelector(`[data-slot="${s}"]`)
const slots = (s, root = document) => [...root.querySelectorAll(`[data-slot="${s}"]`)]

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.keywordContentSeries.mockResolvedValue({ success: true, data: { points: [], capped: false } })
  api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: null, latest: null } })
})

const RESULTS = [{ id: 'r1', ok: true, count: 2, level: 'NONE' }, { id: 'r2', ok: true, count: 0, level: 'NONE' }]

describe('Y1 — kural sonuçları genel kararı yalanlamaz', () => {
  it('kurallar tuttu ama kontrol başka nedenle başarısız → "Rules hold (other reason: …)"; "no alarm" hiç yok', () => {
    render(<RuleTestResults results={RESULTS} outcome={{ ok: false, reason: 'Unexpected HTTP status' }} />)
    const v = slot('kw-rule-results-verdict')
    expect(v.getAttribute('data-verdict')).toBe('other')
    expect(v.textContent).toBe('Rules hold (other reason: Unexpected HTTP status)')
    expect(slot('kw-rule-results').getAttribute('data-ok')).toBe('false')
    expect(document.body.textContent).not.toMatch(/no alarm/i)
  })

  it('neden bilinmiyorsa genel cümle; genel karar sağlıklıysa "Rules hold" (alarm iddiası yok)', () => {
    const { unmount } = render(<RuleTestResults results={RESULTS} outcome={{ ok: false, reason: null }} />)
    expect(slot('kw-rule-results-verdict').textContent).toBe('Rules hold — the check did not pass for another reason')
    unmount()
    render(<RuleTestResults results={RESULTS} outcome={{ ok: true, reason: null }} />)
    expect(slot('kw-rule-results-verdict').getAttribute('data-verdict')).toBe('ok')
    expect(slot('kw-rule-results-verdict').textContent).toBe('Rules hold')
  })

  it('liste yoksa kural hükmü YOK (eskiden boş liste "sağlandı" sayılıyordu); boyut satırı tek başına çizilir', () => {
    render(<RuleTestResults results={[]} sizeOk={false} />)
    expect(slot('kw-rule-results')).not.toBeNull()
    expect(slot('kw-rule-results-verdict')).toBeNull()
    expect(slot('kw-rule-results').hasAttribute('data-ok')).toBe(false)
    expect(slot('kw-rule-size').getAttribute('data-ok')).toBe('false')
  })

  it('boyut kuralı dışarıda, kurallar tuttu → "başka neden" boyut metniyle', () => {
    render(<RuleTestResults results={RESULTS} sizeOk={false} />)
    expect(slot('kw-rule-results-verdict').textContent).toBe('Rules hold (other reason: Page size is outside the expected range)')
  })

  it('VEYA: bir kuralın tutması yeter; VE: hepsi gerekir', () => {
    const mixed = [{ id: 'r1', ok: true, count: 1 }, { id: 'r2', ok: false, count: 0 }]
    const { unmount } = render(<RuleTestResults results={mixed} logic="OR" />)
    expect(slot('kw-rule-results-verdict').getAttribute('data-verdict')).toBe('ok')
    unmount()
    render(<RuleTestResults results={mixed} logic="AND" />)
    expect(slot('kw-rule-results-verdict').getAttribute('data-verdict')).toBe('notMet')
  })
})

describe('İçerik sekmesi (Y1 a / DÜŞÜK-7)', () => {
  const SIMPLE = { id: 7, checked_at: '2026-11-05T10:00:00', status: 'down', occurrences: 0, keyword: 'Giriş', operator: 'GTE',
    match_count: 1, rules: [{ id: 'r1', kind: 'TEXT', pattern: 'Giriş', operator: 'GTE', count: 1 }], rules_custom: false, rule_results: [] }

  it('basit tek kural: kart ile aynı hüküm + sayım (asla "Rules hold")', async () => {
    render(<KeywordContentTab monitor={SIMPLE} />)
    const box = slot('kw-content-last-simple')
    expect(box.getAttribute('data-result')).toBe('missing')
    expect(box.textContent).toMatch(/Not found/)
    expect(slot('kw-content-last-count')).toHaveTextContent('0')
    expect(slot('kw-rule-results-verdict')).toBeNull()
    await waitFor(() => expect(slot('kw-content-chart')).not.toBeNull(), { timeout: 8000 })
  })

  it('sayfa okunamadı (status error) → "kural sonucu yok" değil, okunamadı + kısa neden', async () => {
    render(<KeywordContentTab monitor={{ ...SIMPLE, status: 'error', failure_reason: 'TIMEOUT_READ', occurrences: null }} />)
    expect(slot('kw-content-last-unreadable')).toHaveTextContent(/could not be read/)
    expect(slot('kw-content-last-none')).toBeNull()
    expect(slot('kw-content-last-simple')).toBeNull()
    await waitFor(() => expect(slot('kw-content-chart')).not.toBeNull(), { timeout: 8000 })
  })

  it('özel kurallar tuttu ama izleme son adres kuralından kapalı → "başka neden" (yeşil "sağlandı" yok)', async () => {
    render(<KeywordContentTab monitor={{ ...SIMPLE, status: 'down', failure_reason: 'FINAL_URL_MISMATCH', ok: false, rules_custom: true,
      rules: [{ id: 'r1', kind: 'TEXT', pattern: 'A', operator: 'GTE', count: 1 }, { id: 'r2', kind: 'TEXT', pattern: 'B', operator: 'GTE', count: 1 }],
      rule_results: RESULTS }} />)
    expect(slot('kw-rule-results-verdict').getAttribute('data-verdict')).toBe('other')
    await waitFor(() => expect(slot('kw-content-chart')).not.toBeNull(), { timeout: 8000 })
  })
})

describe('Kart (O1) ve hata paneli (DÜŞÜK-2)', () => {
  const base = { id: 1, url: 'https://x.example.com/', keyword: 'Giriş', operator: 'GTE', match_count: 1, active: true,
    checked_at: '2026-11-05T10:00:00', http_status: 200, response_ms: 50 }

  it('çok kurallı izleme son adres kuralından kapalı → "kural sağlanmadı" değil, nedenin kendisi', () => {
    render(<KeywordMonitorCard status="down" monitor={{ ...base, status: 'down', failure_reason: 'FINAL_URL_MISMATCH', rules_custom: true,
      rules: [{ id: 'r1', kind: 'TEXT', pattern: 'A', operator: 'GTE', count: 1 }, { id: 'r2', kind: 'TEXT', pattern: 'B', operator: 'GTE', count: 1 }],
      rule_results: RESULTS }} />)
    const panel = slot('keyword-panel')
    expect(panel.getAttribute('data-result')).toBe('other')
    expect(slot('keyword-result', panel).textContent).not.toMatch(/rules? not met|Not found/i)
  })

  it('kural koşulu nedeni (KEYWORD_NOT_FOUND) eskisi gibi "Not found" + sayaç', () => {
    render(<KeywordMonitorCard status="down" monitor={{ ...base, status: 'down', failure_reason: 'KEYWORD_NOT_FOUND', found: false, occurrences: 0 }} />)
    expect(slot('keyword-panel').getAttribute('data-result')).toBe('missing')
  })

  it('içerik değişimi ölçülmediyse çip nötr "Change not measured" (asla izleniyor / aynı değil)', () => {
    render(<KeywordMonitorCard status="up" monitor={{ ...base, status: 'up', change_detect: true, content_changed: null,
      change_baseline: { taken_at: 'x' }, change_note: 'SELECTOR_NOT_FOUND' }} />)
    const chip = slot('keyword-change')
    expect(chip.getAttribute('data-state')).toBe('skipped')
    expect(chip.getAttribute('data-note')).toBe('SELECTOR_NOT_FOUND')
    expect(chip).toHaveTextContent('Change not measured')
  })

  it('hata paneli showRules=false → kural listesi çizilmez (form testi üstte zaten çiziyor); varsayılan çizer', () => {
    const check = { ok: false, failure_reason: 'RULES_NOT_MET', http_status: 200, rule_results: [{ id: 'r1', ok: false, count: 0 }] }
    const monitor = { keyword: 'A', operator: 'GTE', match_count: 1, url: 'https://x.example.com/' }
    const { unmount } = render(<KeywordFailurePanel check={check} monitor={monitor} showRules={false} />)
    expect(slot('kwfail-panel')).not.toBeNull()
    expect(slot('kwfail-rules')).toBeNull()
    unmount()
    render(<KeywordFailurePanel check={check} monitor={monitor} />)
    expect(slot('kwfail-rules')).not.toBeNull()
  })
})

describe('Değişim paneli — ölçülemedi notu ve gizli temel metni', () => {
  const BASE = { taken_at: '2026-11-04T08:00:00', source: 'AUTO', bytes: 100, line_count: 3, text: null, text_hidden: true }

  it('son kontrolde ölçülmedi (karşılaştırmadan yeni) → nötr not, "aynıydı" yeşili YOK; temel metni gizli → not', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: BASE, can_edit: false,
      latest: { checked_at: '2026-11-05T08:00:00', changed: false, summary: null },
      skipped: { checked_at: '2026-11-05T09:00:00', note: 'HTTP_ERROR' } } })
    render(<KeywordChangePanel monitorId={7} />)
    await waitFor(() => expect(slot('kw-change-skipped')).not.toBeNull())
    expect(slot('kw-change-skipped').getAttribute('data-note')).toBe('HTTP_ERROR')
    expect(slot('kw-change-skipped')).toHaveTextContent('error code')
    expect(document.querySelector('[data-slot="kw-change-latest"][data-state="same"]')).toBeNull()
    expect(document.querySelector('[data-slot="kw-change-latest"][data-state="older"]')).not.toBeNull()
    expect(slot('kw-change-text-hidden')).toHaveTextContent('Only people who can edit')
    expect(slot('kw-change-text-toggle')).toBeNull()
  })

  it('karşılaştırma ölçülemeyen kontrolden YENİYSE yeşil "aynıydı" kalır; tanınmayan not genel metinle (ham kod yok)', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: { ...BASE, text: 'a', text_hidden: false },
      latest: { checked_at: '2026-11-05T10:00:00', changed: false }, skipped: { checked_at: '2026-11-05T09:00:00', note: 'HTTP_ERROR' } } })
    const { unmount } = render(<KeywordChangePanel monitorId={7} />)
    await waitFor(() => expect(document.querySelector('[data-slot="kw-change-latest"][data-state="same"]')).not.toBeNull())
    expect(slot('kw-change-skipped')).toBeNull()
    expect(slot('kw-change-text-hidden')).toBeNull()
    unmount()
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: BASE,
      latest: { checked_at: '2026-11-05T08:00:00', changed: false }, skipped: { checked_at: '2026-11-05T09:00:00', note: 'UYDURMA' } } })
    render(<KeywordChangePanel monitorId={7} />)
    await waitFor(() => expect(slot('kw-change-skipped')).not.toBeNull())
    expect(slot('kw-change-skipped').getAttribute('data-note')).toBe('OTHER')
    expect(slot('kw-change-skipped')).toHaveTextContent('the reason is not known to this version')
    expect(slot('kw-change-skipped').textContent).not.toContain('UYDURMA')
    expect(document.querySelector('[data-slot="kw-change-latest"][data-state="same"]')).toBeNull()
  })

  it('yeni temel alınamadı (409) → sunucu metni + değişimin neden ölçülemediği (kod metni, ham kod yok)', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: { enabled: true, baseline: null, latest: null, can_edit: true } })
    api.monitoring.takeKeywordBaseline.mockResolvedValue({ success: false, code: 'KEYWORD_BASELINE_FETCH_FAILED',
      error: 'Yeni temel alınamadı.', change_skipped: 'SELECTOR_NOT_FOUND' })
    render(<KeywordChangePanel monitorId={7} canEdit />)
    fireEvent.click(await waitFor(() => { const b = slot('kw-change-take'); expect(b).not.toBeNull(); return b }))
    fireEvent.click(await screen.findByRole('button', { name: /take baseline|temel al/i }))
    await waitFor(() => expect(slot('kw-change-action-error')).not.toBeNull())
    expect(slot('kw-change-action-error')).toHaveTextContent('Yeni temel alınamadı.')
    expect(slot('kw-change-action-error')).toHaveTextContent('Change not measured: the change selector did not match')
    expect(slot('kw-change-action-error').textContent).not.toContain('SELECTOR_NOT_FOUND')
  })
})

describe('Canlı eşleşmeler — notlar (DÜŞÜK-5)', () => {
  it('kapsam bulunamadı → not, "eşleşme yok" ve sayı YOK; sayım tavanı → "en az N"', async () => {
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: { fetched_at: 'x', http_status: 200, truncated: false, rules: [
      { id: 'r1', pattern_preview: 'Sepet', count: 0, note: 'SCOPE_NOT_FOUND', contexts: [] },
      { id: 'r2', pattern_preview: 'a', count: 10000, note: 'COUNT_CAPPED', contexts: [{ before: 'x', match: 'a', after: 'y' }] },
    ] } })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[{ id: 'r1', kind: 'TEXT' }, { id: 'r2', kind: 'REGEX' }]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slots('kw-matches-rule')).toHaveLength(2))
    const [scope, capped] = slots('kw-matches-rule')
    expect(slot('kw-matches-note', scope).getAttribute('data-note')).toBe('SCOPE_NOT_FOUND')
    expect(slot('kw-matches-none', scope)).toBeNull()
    expect(slot('kw-matches-count', scope)).toBeNull()
    expect(slot('kw-matches-count', capped).getAttribute('data-at-least')).toBe('true')
    expect(slot('kw-matches-count', capped)).toHaveTextContent('at least 10000')
  })

  it('sır sınırını aşan bağlam tek parça (yalnız `before`) → vurgusuz düz metin, boş vurgu çizilmez', async () => {
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: { fetched_at: 'x', truncated: false, rules: [
      { id: 'r1', pattern_preview: 'Sepet', count: 1, contexts: [{ before: 'tek parça ••••• bağlam', match: '', after: '' }] },
      { id: 'r2', pattern_preview: 'a', count: 1, contexts: [{ before: 'x', match: 'a', after: 'y' }] },
    ] } })
    render(<KeywordMatchesPanel monitorId={7} canExecute />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slots('kw-matches-context')).toHaveLength(2))
    const [plain, marked] = slots('kw-matches-context')
    expect(plain).toHaveTextContent('tek parça ••••• bağlam')
    expect(plain.querySelector('mark')).toBeNull()
    expect(marked.querySelector('mark')).toHaveTextContent('a')
  })
})

describe('Kural düzenleyici: B10 bilgi notu (O3), kritik eşik kilidi, koşul seçicisi sarar', () => {
  const B10 = [{ id: 'r1', kind: 'TEXT', pattern: 'A', operator: 'GTE', count: 0, critical: null, scope: null, label: null }]

  it('softCodes: "en az 0" hata değil bilgi notu (kart uyarı kenarı yok); softCodes yoksa hata', () => {
    const { unmount } = render(<KeywordRulesEditor rules={B10} onChange={() => {}} showErrors softCodes={['alwaysHealthy', 'neverHealthy']} />)
    expect(slot('kw-rule-notice')).not.toBeNull()
    expect(document.querySelector('[data-field="rules[0].count"] [data-slot="field-error"]')).toBeNull()
    expect(slot('kw-rule-card').className).not.toMatch(/border-destructive/)
    unmount()
    render(<KeywordRulesEditor rules={B10} onChange={() => {}} showErrors />)
    expect(slot('kw-rule-notice')).toBeNull()
    expect(document.querySelector('[data-field="rules[0].count"] [data-slot="field-error"]')).not.toBeNull()
  })

  it('criticalLocked: kritik anahtar / işleç / sayı kilitli + not; desen düzenlenebilir', () => {
    const rules = [{ id: 'r1', kind: 'TEXT', pattern: 'A', operator: 'GTE', count: 5, critical: { operator: 'GTE', count: 2 }, scope: null, label: null }]
    render(<KeywordRulesEditor rules={rules} onChange={() => {}} criticalLocked />)
    expect(slot('kw-rule-critical-locked')).not.toBeNull()
    expect(slot('kw-rule-critical-toggle')).toBeDisabled()
    expect(slot('kw-rule-critical-count')).toBeDisabled()
    expect(slot('kw-rule-pattern')).not.toBeDisabled()
  })

  it('koşul seçicisinin seçenekleri satır kaydırır (uzun EN etiketi kırpılmaz)', () => {
    render(<KeywordRulesEditor rules={B10} onChange={() => {}} />)
    const group = screen.getByRole('group', { name: 'Condition' })
    const items = [...group.querySelectorAll('button')]
    expect(items.length).toBeGreaterThan(1)
    for (const b of items) {
      expect(b.className).toMatch(/whitespace-normal/)
      expect(b.className).toMatch(/h-auto/)
    }
  })
})

describe('"Özel" durum kodu (DÜŞÜK-8)', () => {
  function Harness() {
    const [form, setForm] = useState({ ...KW_EMPTY_FORM })
    const [open, setOpen] = useState(true)
    const fe = { errors: {}, clear: () => {}, fieldProps: (k) => ({ name: k }) }
    return (
      <>
        <button type="button" onClick={() => setOpen((o) => !o)}>toggle-section</button>
        <span data-testid="custom">{String(form.expectedStatusCustom)}</span>
        <KeywordRequestSection form={form} setForm={setForm} fe={fe} open={open} onOpenChange={setOpen} />
      </>
    )
  }

  it('"Özel" seçimi formda tutulur — bölüm kapanıp açılınca kaybolmaz (değer boşken bile)', () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('radio', { name: /Custom/ }))
    expect(screen.getByTestId('custom')).toHaveTextContent('true')
    expect(document.querySelector('[data-slot="kw-status"]').getAttribute('data-mode')).toBe('CUSTOM')
    fireEvent.click(screen.getByText('toggle-section'))
    fireEvent.click(screen.getByText('toggle-section'))
    expect(document.querySelector('[data-slot="kw-status"]').getAttribute('data-mode')).toBe('CUSTOM')
    // Ön ayara dönüş bayrağı kaldırır
    fireEvent.click(screen.getByRole('radio', { name: /Only 200/ }))
    expect(screen.getByTestId('custom')).toHaveTextContent('false')
  })
})
