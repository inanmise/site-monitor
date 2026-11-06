import { useState } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'

/**
 * Anahtar kelime ön yüzü hata avı düzeltmeleri (2026-11-06):
 * - H5#1 gizli alan: GET'te gövde / içerik türü, değişim kapalıyken seçici gönderilmez ve doğrulanmaz;
 * - H5#3 canlı eşleşme: sayfa okunamadıysa neden uyarısı (boş liste "eşleşme yok" değil);
 * - H5#4 "Test et": kullanıcı adı kayıtlıdan farklıysa kayıtlı parola istenmez;
 * - H5#6 paylaşımla düzenleme: kritik eşikli kuralda kip / işleç ailesi değişimi ve silme kilitli;
 * - H5#7 + not: değişim paneli izleme / değişim ayarı değişince eski veriyi göstermeden yeniden okur; grafik izleme değişince;
 * - H5#8 basite dönüşte birleşim VE;
 * - H5#9 istemci B10 aynası: "en fazla <tavan>" / "<tavan>'dan fazla" sunucu gibi reddedilir (kritik eşikte de).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({
      monitoring: {
        keywordChange: vi.fn(), takeKeywordBaseline: vi.fn(), clearKeywordBaseline: vi.fn(),
        keywordContentSeries: vi.fn(), keywordMatches: vi.fn(),
      },
    }),
    formatDate: (s) => String(s),
  }
})

import { api } from '../api/client'
import KeywordRulesEditor from '../components/keyword/rules/KeywordRulesEditor.jsx'
import KeywordMatchesPanel from '../components/keyword/detail/KeywordMatchesPanel.jsx'
import KeywordChangePanel from '../components/keyword/detail/KeywordChangePanel.jsx'
import KeywordContentChart from '../components/keyword/detail/KeywordContentChart.jsx'
import { matchesOf, matchesPageUnread } from '../components/keyword/detail/contentDetailModel.js'
import { REQUEST_CODES } from '../components/keyword/keywordFailureModel.js'
import {
  KW_EMPTY_FORM, keywordBody, keywordFormFrom, keywordPayload, keywordTestPayload, savedPassUserChanged, storedSecretsOf, unplacedErrors,
} from '../components/keyword/keywordFormModel.js'
import { NOT_SEARCHED_NOTES, RULE_LIMITS, RULE_NOTES, criticalPossible, defaultCritical, validateRule } from '../components/keyword/rules/ruleModel.js'
import RuleTestResults from '../components/keyword/rules/RuleTestResults.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

const slot = (s, root = document) => root.querySelector(`[data-slot="${s}"]`)
const rule = (over = {}) => ({ id: 'r1', kind: 'TEXT', pattern: 'Fiyat', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null, ...over })

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.setItem('site-monitor-lang', 'tr')
  api.monitoring.keywordContentSeries.mockResolvedValue({ success: true, data: { points: [], capped: false } })
})
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('gizli alan (H5#1) — model', () => {
  const ROW = { id: 1, url: 'https://x.example.com/', keyword: 'ok', operator: 'GTE', match_count: 1, method: 'POST',
    request_body: 'q=1', request_content_type: 'text/plain', change_detect: true, change_mask: 'DIGITS', change_selector: 'main',
    team_id: 5, group_name: 'G', tags: 't' }

  it('keywordBody: GET\'te gövde / içerik türü null; değişim kapalıyken seçici null', () => {
    expect(keywordBody({ ...KW_EMPTY_FORM, method: 'GET', requestBody: 'q=1', requestContentType: 'text/plain' }))
      .toMatchObject({ method: 'GET', requestBody: null, requestContentType: null })
    expect(keywordBody({ ...KW_EMPTY_FORM, method: 'POST', requestBody: 'q=1', requestContentType: ' text/plain ' }))
      .toMatchObject({ method: 'POST', requestBody: 'q=1', requestContentType: 'text/plain' })
    expect(keywordBody({ ...KW_EMPTY_FORM, changeDetect: false, changeSelector: '[class~=x]' }).changeSelector).toBeNull()
    expect(keywordBody({ ...KW_EMPTY_FORM, changeDetect: true, changeSelector: ' main ' }).changeSelector).toBe('main')
  })

  it('keywordPayload (düzenleme): POST → GET gövdeyi null gönderir; değişimi kapatmak seçiciyi null gönderir', () => {
    const base = { ...keywordFormFrom(ROW), changeDetect: true, changeMask: ['DIGITS'], changeSelector: 'main' }
    expect(keywordPayload({ ...base, method: 'GET' }, base)).toMatchObject({ method: 'GET', requestBody: null, requestContentType: null })
    const off = keywordPayload({ ...base, changeDetect: false, changeSelector: '[class~=x]' }, base)
    expect(off).toMatchObject({ changeDetect: false, changeSelector: null })
  })

  it('unplacedErrors: DOM\'da alanı olmayan hataların iletileri', () => {
    document.body.innerHTML = '<div data-field="url"></div><div data-field="rules[0].pattern"></div>'
    expect(unplacedErrors({ url: 'a', 'rules[0].pattern': 'b', changeSelector: 'Seçici geçersiz', minBytes: null })).toEqual(['Seçici geçersiz'])
    expect(unplacedErrors({})).toEqual([])
    document.body.innerHTML = ''
  })
})

describe('"Test et" kayıtlı parola (H5#4) — model', () => {
  const stored = storedSecretsOf({ has_basic_auth_pass: true, basic_auth_user: ' izleme ' })
  const url = 'https://x.example.com/'
  it('kullanıcı adı farklı + parola boş → useSaved yok; aynı → var; parola yazıldı → not yok', () => {
    expect(stored.user).toBe('izleme')
    const changed = { ...KW_EMPTY_FORM, url, basicAuthUser: 'yeni' }
    expect(savedPassUserChanged(changed, stored)).toBe(true)
    expect(keywordTestPayload(changed, { monitorId: 1, stored, storedUrl: url })).not.toHaveProperty('useSaved')
    const same = { ...KW_EMPTY_FORM, url, basicAuthUser: 'izleme' }
    expect(savedPassUserChanged(same, stored)).toBe(false)
    expect(keywordTestPayload(same, { monitorId: 1, stored, storedUrl: url }).useSaved).toEqual(['basicAuthPass'])
    expect(savedPassUserChanged({ ...changed, basicAuthPass: 'p' }, stored)).toBe(false)
    expect(savedPassUserChanged(changed, { hasPass: true, user: null })).toBe(false)
    expect(savedPassUserChanged(changed, { hasPass: false, user: 'izleme' })).toBe(false)
  })
})

describe('basite dönüş (H5#8)', () => {
  it('özel kurallardan basite dönerken ruleLogic AND gider', () => {
    const base = { ...KW_EMPTY_FORM, rules: [rule(), rule({ id: 'r2', pattern: 'Stok' })], ruleLogic: 'OR', keyword: 'Fiyat' }
    expect(keywordPayload({ ...base, rules: null }, base)).toMatchObject({ rules: null, ruleLogic: 'AND' })
  })
})

describe('istemci B10 aynası (H5#9) — sunucu KeywordRules.meaningless', () => {
  const cap = RULE_LIMITS.textCountMax
  const rcap = RULE_LIMITS.regexCountMax
  const count = (over) => validateRule(rule(over), 0)['rules[0].count']?.code ?? null
  const crit = (over) => validateRule(rule(over), 0)['rules[0].critical']?.code ?? null

  it('uyarı koşulu: LTE tavan her zaman sağlanır, GT tavan hiç sağlanamaz (TEXT ve REGEX tavanı)', () => {
    expect(count({ operator: 'LTE', count: cap })).toBe('alwaysHealthyCap')
    expect(count({ operator: 'LTE', count: cap - 1 })).toBeNull()
    expect(count({ operator: 'GT', count: cap })).toBe('neverHealthyCap')
    expect(count({ operator: 'GT', count: cap - 1 })).toBeNull()
    expect(count({ kind: 'REGEX', pattern: 'a+b', operator: 'LTE', count: rcap })).toBe('alwaysHealthyCap')
    expect(count({ kind: 'REGEX', pattern: 'a+b', operator: 'GT', count: rcap })).toBe('neverHealthyCap')
    // Önceki kurallar aynen
    expect(count({ operator: 'GTE', count: 0 })).toBe('alwaysHealthy')
    expect(count({ operator: 'LT', count: 0 })).toBe('neverHealthy')
  })

  it('kritik eşik de: LTE tavan / GT tavan / LT 0 / GTE 0 anlamsız (ikili kontrolden önce)', () => {
    expect(crit({ operator: 'LTE', count: 5, critical: { operator: 'LTE', count: cap } })).toBe('criticalAlwaysCap')
    expect(crit({ operator: 'GTE', count: 10, critical: { operator: 'GT', count: cap } })).toBe('criticalNeverCap')
    expect(crit({ operator: 'LTE', count: 0, critical: { operator: 'LT', count: 0 } })).toBe('criticalLtZero')
    expect(crit({ operator: 'GTE', count: 10, critical: { operator: 'GTE', count: 0 } })).toBe('criticalNeverFires')
    expect(crit({ operator: 'LTE', count: 5, critical: { operator: 'LTE', count: cap - 1 } })).toBeNull()
  })

  it('önerilen kritik eşik asla tavanda değil; kritik ancak tavan − 2 sınırına kadar mümkün', () => {
    const big = rule({ operator: 'LTE', count: 400_000 })
    const d = defaultCritical(big)
    expect(d).toEqual({ operator: 'LTE', count: cap - 1 })
    expect(validateRule({ ...big, critical: d }, 0)['rules[0].critical']).toBeUndefined()
    expect(criticalPossible(rule({ operator: 'LTE', count: cap - 2 }))).toBe(true)
    expect(criticalPossible(rule({ operator: 'LTE', count: cap - 1 }))).toBe(false)
  })

  it('yeni kodların TR + EN metni var (ham kod görünmez)', () => {
    for (const c of ['alwaysHealthyCap', 'neverHealthyCap', 'criticalLtZero', 'criticalAlwaysCap', 'criticalNeverCap']) {
      expect(TR[`keyword.rule.v.${c}`], c).toBeTruthy()
      expect(EN[`keyword.rule.v.${c}`], c).toBeTruthy()
    }
  })
})

function Harness({ initial, spy, ...props }) {
  const [rules, setRules] = useState(initial)
  const [lg, setLg] = useState('AND')
  return <KeywordRulesEditor rules={rules} logic={lg} onChange={(r, l) => { setRules(r); setLg(l); spy?.(r, l) }} {...props} />
}

describe('KeywordRulesEditor — kritik eşik kilidi dolaylı da korunur (H5#6)', () => {
  const critRule = rule({ id: 'a', operator: 'GTE', count: 10, critical: { operator: 'GTE', count: 3 } })
  const cards = () => [...document.querySelectorAll('[data-slot="kw-rule-card"]')]

  it('kritik eşikli kural: silme kapalı, diğer koşul kipleri kapalı, işleç listesi yalnız aynı yön; not görünür', () => {
    const spy = vi.fn()
    render(<Harness initial={[critRule, rule({ id: 'b', pattern: 'Stok' })]} criticalLocked spy={spy} />)
    const [a, b] = cards()
    expect(a.querySelector('[data-slot="kw-rule-delete"]')).toBeDisabled()
    expect(b.querySelector('[data-slot="kw-rule-delete"]')).not.toBeDisabled()   // kritiksiz kural silinebilir
    expect(slot('kw-rule-critical-held', a)).toHaveTextContent(TR['keyword.rule.criticalHeld'])
    expect(slot('kw-rule-critical-held', b)).toBeNull()
    // "İçermeli" (kritiği siler) seçilemez
    const contains = within(a).getByRole('button', { name: TR['keyword.rule.mode.contains'] })
    expect(contains).toBeDisabled()
    fireEvent.click(contains)
    expect(spy).not.toHaveBeenCalled()
    // İşleç: yalnız alt sınır ailesi (GTE / GT) — LTE / LT / EQ seçeneği yok
    const ops = [...within(a).getByRole('combobox', { name: TR['keyword.rule.operator'] }).querySelectorAll('option')].map((o) => o.value)
    expect(ops).toEqual(['GTE', 'GT'])
  })

  it('kilit yokken davranış aynen: silme açık, tüm işleçler', () => {
    render(<Harness initial={[critRule, rule({ id: 'b', pattern: 'Stok' })]} />)
    const [a] = cards()
    expect(a.querySelector('[data-slot="kw-rule-delete"]')).not.toBeDisabled()
    expect(slot('kw-rule-critical-held', a)).toBeNull()
    expect([...within(a).getByRole('combobox', { name: TR['keyword.rule.operator'] }).querySelectorAll('option')]).toHaveLength(5)
  })
})

describe('KeywordMatchesPanel — sayfa okunamadı (H5#3)', () => {
  it('model: error / istek kodu → okunamadı; kural sonucu kodu → okundu', () => {
    expect(matchesPageUnread(matchesOf({ rules: [], error: 'Bağlantı zaman aşımı', failure_reason: 'TIMEOUT_READ' }), REQUEST_CODES)).toBe(true)
    expect(matchesPageUnread(matchesOf({ rules: [], failure_reason: 'EMPTY_BODY' }), REQUEST_CODES)).toBe(true)
    expect(matchesPageUnread(matchesOf({ rules: [{ id: 'r1', count: 0 }], failure_reason: 'RULES_NOT_MET' }), REQUEST_CODES)).toBe(false)
    expect(matchesPageUnread(matchesOf({ rules: [] }), REQUEST_CODES)).toBe(false)
  })

  it('okunamayan sayfa: neden uyarısı (kwfail kısa metni + sunucu iletisi), kural satırı / "eşleşme yok" YOK', async () => {
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: { fetched_at: '2026-11-06T08:00:00', http_status: null,
      final_url: null, error: 'Bağlantı zaman aşımı (10 sn)', failure_reason: 'TIMEOUT_READ', rules: [], truncated: false } })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[rule()]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-unread')).not.toBeNull())
    expect(slot('kw-matches-unread')).toHaveAttribute('data-reason', 'TIMEOUT_READ')
    expect(slot('kw-matches-unread')).toHaveTextContent(TR['kwfail.TIMEOUT_READ.short'])
    expect(slot('kw-matches-unread')).toHaveTextContent('Bağlantı zaman aşımı (10 sn)')
    expect(slot('kw-matches-rule')).toBeNull()
    expect(slot('kw-matches-none')).toBeNull()
  })

  it('HTTP durum kodu nedeni: yer tutucu yanıttan doldurulur; sayfa okunduysa kurallar çizilir', async () => {
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: { http_status: 404, error: null,
      failure_reason: 'RULES_NOT_MET', rules: [{ id: 'r1', pattern_preview: 'Fiyat', count: 0, contexts: [] }] } })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[rule()]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-rule')).not.toBeNull())
    expect(slot('kw-matches-unread')).toBeNull()
    expect(slot('kw-matches-none')).not.toBeNull()
  })
})

const BL = (who) => ({ enabled: true, mask: [], selector: null,
  baseline: { taken_at: '2026-11-04T08:00:00', taken_by_name: who, source: 'MANUAL', bytes: 1200, line_count: 4, text: null }, latest: null })

describe('KeywordChangePanel — bayat veri yok (H5#7)', () => {
  it('değişim seçicisi / maskesi değişince yeniden okunur; eski temel yüklenirken GÖSTERİLMEZ', async () => {
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: true, data: BL('Eski Temel Sahibi') })
    const { rerender } = render(<KeywordChangePanel monitorId={7} canEdit changeMask="DIGITS" changeSelector="main" />)
    await waitFor(() => expect(slot('kw-change-baseline')).toHaveTextContent('Eski Temel Sahibi'))
    let release
    api.monitoring.keywordChange.mockImplementationOnce(() => new Promise((r) => { release = r }))
    rerender(<KeywordChangePanel monitorId={7} canEdit changeMask="DIGITS" changeSelector="#icerik" />)
    await waitFor(() => expect(api.monitoring.keywordChange).toHaveBeenCalledTimes(2))
    expect(slot('kw-change-baseline')).toBeNull()
    expect(slot('kw-change-skeleton')).not.toBeNull()
    await act(async () => { release({ success: true, data: { enabled: true, baseline: null, latest: null } }) })
    expect(slot('kw-change-no-baseline')).not.toBeNull()
    // Maske değişimi de yeniden okur
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: true, data: BL('Yeni') })
    rerender(<KeywordChangePanel monitorId={7} canEdit changeMask="DIGITS,DATES" changeSelector="#icerik" />)
    await waitFor(() => expect(api.monitoring.keywordChange).toHaveBeenCalledTimes(3))
  })

  it('izleme değişince önceki izlemenin temeli ve hata durumu taşınmaz', async () => {
    api.monitoring.keywordChange.mockResolvedValueOnce({ success: true, data: BL('Birinci İzlemenin Sahibi') })
    const { rerender } = render(<KeywordChangePanel monitorId={7} canEdit />)
    await waitFor(() => expect(slot('kw-change-baseline')).toHaveTextContent('Birinci İzlemenin Sahibi'))
    api.monitoring.keywordChange.mockImplementationOnce(() => new Promise(() => {}))
    rerender(<KeywordChangePanel monitorId={8} canEdit />)
    await waitFor(() => expect(api.monitoring.keywordChange).toHaveBeenLastCalledWith(8))
    expect(document.body).not.toHaveTextContent('Birinci İzlemenin Sahibi')
  })

  it('aynı ayarla yeniden okuma (temel alındı) veriyi korur', async () => {
    api.monitoring.keywordChange.mockResolvedValue({ success: true, data: BL('Sabit') })
    const { rerender } = render(<KeywordChangePanel monitorId={7} canEdit changeSelector="main" />)
    await waitFor(() => expect(slot('kw-change-baseline')).not.toBeNull())
    rerender(<KeywordChangePanel monitorId={7} canEdit changeSelector=" main " />)   // kırpılmış aynı seçici: yeniden okuma yok
    await act(async () => {})
    expect(api.monitoring.keywordChange).toHaveBeenCalledTimes(1)
  })
})

describe('KeywordContentChart — izleme değişince eski seri yok', () => {
  const pt = (ts) => ({ ts, checks: 2, failed: 0, occ_min: 1, occ_avg: 2, occ_max: 3, bytes_avg: 4000, changed: false })
  it('önceki izlemenin grafiği yeni izleme okunurken çizilmez', async () => {
    api.monitoring.keywordContentSeries.mockResolvedValueOnce({ success: true, data: { capped: false, points: [pt('2026-11-05T08:00:00'), pt('2026-11-05T09:00:00')] } })
    const { rerender } = render(<KeywordContentChart monitorId={7} />)
    await waitFor(() => expect(slot('kw-content-chart-plot')).not.toBeNull())
    api.monitoring.keywordContentSeries.mockImplementationOnce(() => new Promise(() => {}))
    rerender(<KeywordContentChart monitorId={8} />)
    await waitFor(() => expect(api.monitoring.keywordContentSeries).toHaveBeenLastCalledWith(8, { days: 7 }))
    expect(slot('kw-content-chart-plot')).toBeNull()
    expect(slot('kw-content-chart-skeleton')).not.toBeNull()
  })
})

describe('kural notu PATTERN_INVALID (saklı düzenli ifade derlenemedi — sunucu B ajanı)', () => {
  it('model: bilinen not (ham kod düşmez), "aranamadı" kümesinde; iki dilde metin', () => {
    expect(RULE_NOTES).toContain('PATTERN_INVALID')
    expect(NOT_SEARCHED_NOTES).toContain('PATTERN_INVALID')
    expect(matchesOf({ rules: [{ id: 'r1', count: 0, note: 'PATTERN_INVALID', contexts: [] }] }).rules[0].note).toBe('PATTERN_INVALID')
    expect(TR['keyword.rule.note.PATTERN_INVALID']).toMatch(/Desen geçersiz/)
    expect(EN['keyword.rule.note.PATTERN_INVALID']).toMatch(/Invalid pattern/)
  })

  it('kural sonucu: not metni görünür, sunucunun 0\'ı ölçüm gibi gösterilmez', () => {
    render(<RuleTestResults results={[{ id: 'r1', ok: false, level: 'MONITOR', count: 0, note: 'PATTERN_INVALID', kind: 'REGEX',
      pattern_preview: '(a' }]} rules={[rule({ kind: 'REGEX', pattern: '(a' })]} monitorLevel="WARNING" title="Kurallar" />)
    expect(slot('kw-rule-result-note')).toHaveTextContent(TR['keyword.rule.note.PATTERN_INVALID'])
    expect(slot('kw-rule-result-count')).toBeNull()
    expect(document.body).not.toHaveTextContent('PATTERN_INVALID')
  })

  it('canlı eşleşme: "eşleşme yok" ve sayı yok, not var', async () => {
    api.monitoring.keywordMatches.mockResolvedValue({ success: true, data: { http_status: 200, error: null, failure_reason: 'RULES_NOT_MET',
      rules: [{ id: 'r1', pattern_preview: '(a', count: 0, note: 'PATTERN_INVALID', contexts: [] }] } })
    render(<KeywordMatchesPanel monitorId={7} canExecute rules={[rule({ kind: 'REGEX', pattern: '(a' })]} />)
    fireEvent.click(slot('kw-matches-run'))
    await waitFor(() => expect(slot('kw-matches-note')).not.toBeNull())
    expect(slot('kw-matches-note')).toHaveTextContent(TR['keyword.rule.note.PATTERN_INVALID'])
    expect(slot('kw-matches-none')).toBeNull()
    expect(slot('kw-matches-count')).toBeNull()
  })
})

describe('sözlük — yeni metinler iki dilde', () => {
  it('matches / form / test notları', () => {
    for (const k of ['keyword.matches.unreadTitle', 'keyword.matches.unreadBody', 'keyword.matches.pageUnread', 'keyword.form.unplaced',
      'keyword.adv.savedPassUserChanged', 'keyword.rule.criticalHeld']) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
    expect(screen).toBeTruthy()
  })
})
