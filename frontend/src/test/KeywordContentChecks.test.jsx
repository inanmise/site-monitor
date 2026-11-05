import { useState } from 'react'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import KeywordContentChecks from '../components/keyword/content/KeywordContentChecks.jsx'
import {
  bytesText, changeSettingsReset, contentFormFrom, contentPayload, maskList, parseSize, sizeRangeText, sizeText, unitFor, validateContentChecks,
} from '../components/keyword/content/contentChecksModel.js'
import {
  changedLineCount, isLonePoint, matchesOf, seriesStats, shapeContentSeries, sizeOkOf, summaryOf,
} from '../components/keyword/detail/contentDetailModel.js'

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('contentChecksModel', () => {
  it('satırdan form; maske CSV / dizi, bilinmeyen atılır, sıra kanonik', () => {
    expect(contentFormFrom({ min_bytes: 1000, max_bytes: null, change_detect: true, change_mask: 'DATES,digits,x', change_selector: 'main' }))
      .toEqual({ minBytes: 1000, maxBytes: null, changeDetect: true, changeMask: ['DIGITS', 'DATES'], changeSelector: 'main' })
    expect(contentFormFrom({})).toEqual({ minBytes: null, maxBytes: null, changeDetect: false, changeMask: [], changeSelector: '' })
    expect(maskList(['DATES', 'DATES'])).toEqual(['DATES'])
  })

  it('birim: KB’ye tam bölünen değer KB görünür; yazılan metin + birim → bayt; geçersiz metin ham kalır', () => {
    expect(unitFor(2000)).toBe('KB')
    expect(unitFor(1500)).toBe('B')
    expect(sizeText(2000, 'KB')).toBe('2')
    expect(sizeText(1500, 'KB')).toBe('1500')
    expect(parseSize('3', 'KB')).toBe(3000)
    expect(parseSize(' 7 ', 'B')).toBe(7)
    expect(parseSize('', 'KB')).toBeNull()
    expect(parseSize('1.5', 'KB')).toBe('1.5')
  })

  it('doğrulama: sayı / aralık / sıra / seçici (sunucu sınırları)', () => {
    expect(validateContentChecks({ minBytes: 'x' }).minBytes.code).toBe('sizeNumber')
    expect(validateContentChecks({ maxBytes: 2_000_001 }).maxBytes.code).toBe('sizeRange')
    expect(validateContentChecks({ minBytes: 0 }).minBytes.code).toBe('sizeRange')
    expect(validateContentChecks({ minBytes: 5000, maxBytes: 100 }).maxBytes.code).toBe('sizeOrder')
    expect(validateContentChecks({ changeSelector: 'a'.repeat(301) }).changeSelector.code).toBe('selectorTooLong')
    expect(validateContentChecks({ changeSelector: 'a\nb' }).changeSelector.code).toBe('selectorNewline')
    // Seçici kuralı kural kapsamıyla aynı (be2): :matches… ve ~= yok, :has( en çok bir kez
    expect(validateContentChecks({ changeSelector: 'main:matchesWholeText(x)' }).changeSelector.code).toBe('selectorCssRegex')
    expect(validateContentChecks({ changeSelector: '[class~=x]' }).changeSelector.code).toBe('selectorCssRegex')
    expect(validateContentChecks({ changeSelector: 'div:has(a) p:has(b)' }).changeSelector.code).toBe('selectorCssHasMany')
    expect(validateContentChecks({ changeSelector: 'main:has(> h1)' })).toEqual({})
    expect(validateContentChecks({ minBytes: 1, maxBytes: 2_000_000, changeSelector: 'main' })).toEqual({})
  })

  it('istek parçası: boş → null; maske dizi; seçici kırpılır', () => {
    expect(contentPayload({ minBytes: 10, maxBytes: '', changeDetect: true, changeMask: 'DIGITS', changeSelector: '  ' }))
      .toEqual({ minBytes: 10, maxBytes: null, changeDetect: true, changeMask: ['DIGITS'], changeSelector: null })
  })

  it('değişim ayarı temeli sıfırlar mı: kapatma / maske / seçici değişimi evet, aynı ayar ve yeni izleme hayır', () => {
    const stored = { changeDetect: true, changeMask: ['DIGITS'], changeSelector: 'main' }
    expect(changeSettingsReset({ ...stored }, stored)).toBe(false)
    expect(changeSettingsReset({ ...stored, changeDetect: false }, stored)).toBe(true)
    expect(changeSettingsReset({ ...stored, changeMask: [] }, stored)).toBe(true)
    expect(changeSettingsReset({ ...stored, changeSelector: ' main ' }, stored)).toBe(false)
    expect(changeSettingsReset({ ...stored, changeSelector: '#x' }, stored)).toBe(true)
    expect(changeSettingsReset({ changeDetect: true }, null)).toBe(false)
    expect(changeSettingsReset({ changeDetect: true }, { changeDetect: false })).toBe(false)
  })

  it('bayt metni ve aralık metni', () => {
    expect(bytesText(850)).toBe('850 B')
    expect(bytesText(1500)).toBe('1,5 KB')
    expect(bytesText(1500, 'en')).toBe('1.5 KB')
    expect(bytesText(2_000_000)).toBe('2 MB')
    expect(sizeRangeText(1000, 2_000_000)).toBe('1 KB–2 MB')
    expect(sizeRangeText(null, 5000)).toBe('≤ 5 KB')
    expect(sizeRangeText(null, null)).toBeNull()
  })
})

describe('contentDetailModel', () => {
  it('değişim özeti: nesne ya da JSON; bozuk → null; listeler sınırlı, fazlalık sayılır', () => {
    const s = summaryOf(JSON.stringify({ lines_before: 10, lines_after: 11, removed: ['a'], added: ['b', 'c'], removed_total: 4, added_total: 2, truncated: true }))
    expect(s).toMatchObject({ removed: ['a'], added: ['b', 'c'], removedMore: 3, addedMore: 0, truncated: true })
    expect(changedLineCount(s)).toBe(6)
    expect(summaryOf('{bozuk')).toBeNull()
    expect(summaryOf([1])).toBeNull()
    expect(summaryOf({ removed: Array.from({ length: 30 }, (_, i) => `x${i}`) }).removed).toHaveLength(20)
    expect(summaryOf({ added: ['y'.repeat(250)] }).added[0]).toHaveLength(201)
  })

  it('içerik serisi: kontrolsüz kova BOŞLUK (null, 0 değil); zaman sırası; değişim işareti', () => {
    const rows = shapeContentSeries({ points: [
      { ts: '2026-11-05T10:00:00', checks: 2, failed: 0, occ_min: 1, occ_avg: 2, occ_max: 3, bytes_avg: 5000, changed: false },
      { ts: '2026-11-05T09:00:00', checks: 0, failed: 0, occ_min: null, occ_avg: null, occ_max: null, bytes_avg: null, changed: false },
      { ts: 'bozuk', checks: 1 },
      { ts: '2026-11-05T11:00:00Z', checks: 1, failed: 1, occ_min: 0, occ_avg: 0, occ_max: 0, bytes_avg: 10, changed: true },
    ] })
    expect(rows.map((r) => r.ts)).toEqual(['2026-11-05T09:00:00', '2026-11-05T10:00:00', '2026-11-05T11:00:00Z'])
    expect(rows[0]).toMatchObject({ checks: 0, occAvg: null, occBand: null, bytes: null })
    expect(rows[1]).toMatchObject({ occAvg: 2, occBand: [1, 3], bytes: 5000 })
    expect(rows[2]).toMatchObject({ occAvg: 0, changed: true })
    expect(isLonePoint(rows, 'occAvg', 1)).toBe(false)
    expect(isLonePoint([{ v: null }, { v: 1 }, { v: null }], 'v', 1)).toBe(true)
    expect(seriesStats(rows)).toEqual({ buckets: 2, checks: 3, failed: 1, maxOcc: 3, changed: 1 })
  })

  it('boyut sonucu: sınır ya da boyut yoksa null (bilinmiyor ≠ sağlıklı); kırpılan gövde maksimumu aşmış sayılır', () => {
    expect(sizeOkOf({ min_bytes: 10, body_bytes: null })).toBeNull()
    expect(sizeOkOf({ body_bytes: 10 })).toBeNull()
    expect(sizeOkOf({ min_bytes: 10, body_bytes: 9 })).toBe(false)
    expect(sizeOkOf({ max_bytes: 100, body_bytes: 50 })).toBe(true)
    expect(sizeOkOf({ max_bytes: 100, body_bytes: 50, body_truncated: true })).toBe(false)
  })

  it('canlı eşleşmeler: bozuk alanlar düşer, kural başına ≤ 5 bağlam', () => {
    const m = matchesOf({ fetched_at: 'x', http_status: '200', rules: [{ id: 'r1', pattern_preview: 'a', count: 7,
      contexts: [...Array.from({ length: 8 }, () => ({ before: 'x', match: 'a', after: 'y', source: 'page' })), null] }, 'bozuk'] })
    expect(m.httpStatus).toBe(200)
    expect(m.rules).toHaveLength(1)
    expect(m.rules[0].contexts).toHaveLength(5)
    expect(m.rules[0].contexts[0].source).toBe('PAGE')
    expect(matchesOf(null)).toBeNull()
  })
})

function Harness({ initial, stored = null, showErrors = false, errors = null }) {
  const [v, setV] = useState(initial)
  return (
    <>
      <KeywordContentChecks value={v} onChange={(p) => setV((x) => ({ ...x, ...p }))} stored={stored} showErrors={showErrors} errors={errors} />
      <output data-testid="state">{JSON.stringify(v)}</output>
    </>
  )
}
const state = () => JSON.parse(screen.getByTestId('state').textContent)

describe('KeywordContentChecks', () => {
  it('boyut: KB biriminde yazılır, bayt olarak forma geçer; birim değişince yeniden hesaplanır', () => {
    render(<Harness initial={{ minBytes: 2000, maxBytes: null, changeDetect: false, changeMask: [], changeSelector: '' }} />)
    const min = document.querySelector('[data-slot="kw-content-minBytes"]')
    expect(min.value).toBe('2')
    expect(document.querySelector('[data-slot="kw-content-minBytes-unit"]').value).toBe('KB')
    fireEvent.change(min, { target: { value: '5' } })
    expect(state().minBytes).toBe(5000)
    fireEvent.change(document.querySelector('[data-slot="kw-content-minBytes-unit"]'), { target: { value: 'B' } })
    expect(state().minBytes).toBe(5)
    fireEvent.change(document.querySelector('[data-slot="kw-content-maxBytes"]'), { target: { value: '3' } })
    fireEvent.blur(document.querySelector('[data-slot="kw-content-maxBytes"]'))
    expect(document.querySelector('[data-field="maxBytes"]')).toHaveTextContent(/En az değeri en fazla değerinden büyük olamaz/)
  })

  it('değişim: aç → maske çipleri + seçici; maskesiz ve bölgesiz dinamik sayfa uyarısı; maske seçilince kalkar', () => {
    render(<Harness initial={{ minBytes: null, maxBytes: null, changeDetect: false, changeMask: [], changeSelector: '' }} />)
    expect(document.querySelector('[data-slot="kw-content-masks"]')).toBeNull()
    fireEvent.click(document.querySelector('[data-slot="kw-content-change-toggle"]'))
    expect(state().changeDetect).toBe(true)
    expect(document.querySelector('[data-slot="kw-content-dynamic-warn"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Rakamlar' }))
    expect(state().changeMask).toEqual(['DIGITS'])
    expect(document.querySelector('[data-slot="kw-content-dynamic-warn"]')).toBeNull()
  })

  it('düzenlemede değişim ayarı değişirse temelin sıfırlanacağı önceden söylenir', () => {
    const stored = { changeDetect: true, changeMask: ['DIGITS'], changeSelector: '' }
    render(<Harness initial={{ ...stored, minBytes: null, maxBytes: null }} stored={stored} />)
    expect(document.querySelector('[data-slot="kw-content-reset-note"]')).toBeNull()
    fireEvent.change(document.querySelector('[data-slot="kw-content-selector"]'), { target: { value: 'main' } })
    expect(document.querySelector('[data-slot="kw-content-reset-note"]')).toHaveTextContent(/mevcut temel silinir/)
  })

  it('sunucu alan hatası alanın altında (istemci denetiminden önce)', () => {
    render(<Harness initial={{ minBytes: 10, maxBytes: null, changeDetect: true, changeMask: [], changeSelector: '##' }}
      errors={{ changeSelector: 'Geçersiz CSS seçici (2. karakter)' }} />)
    expect(document.querySelector('[data-field="changeSelector"]')).toHaveTextContent('Geçersiz CSS seçici (2. karakter)')
  })
})
