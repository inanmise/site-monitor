import { describe, it, expect } from 'vitest'
import { summaryAutoText, summaryBlocks, summaryRefs, summaryMalformed, effectiveTarget, effectiveBetter, deltaOf, pointsOf }
  from '../components/techerror/techErrorModel.js'
import { javaStringTable, readJavaTest } from './helpers/javaTable.js'

// Yönetici özetinin karşılaştırma başvuruları (2026-10-17, 4b): {d:alan} önceki aya göre fark, {t:alan} etkin hedef,
// {dt:alan} hedefe uzaklık — sunucudaki TechErrorSummary ile AYNI doğruluk tablosu, Java test kaynağından okunur
// (TechErrorSummaryDeltaTest.DELTA_TABLE). Şablon Java testindeki template() ile elle aynıdır.
const JAVA = readJavaTest('service/techerror/TechErrorSummaryDeltaTest.java')

function template(lang, line) {
  return {
    lang,
    title: '{kanal}',
    sections: [
      { id: 'exec', kind: 'summary', title: 'Özet', lines: [line] },
      { id: 'm', kind: 'metrics', title: 'Veriler', fields: [
        { id: 'count', label: 'Hata adedi', type: 'number' },
        { id: 'rate', label: 'Oran', type: 'percent' },
        { id: 'sla', label: 'SLA', type: 'percent', target: 99.95, better: 'higher' },
        { id: 'note', label: 'Not', type: 'textarea' },
        { id: 'flag', label: 'Bayrak', type: 'yesno' },
      ] },
    ],
  }
}
const TYPES = { count: 'number', rate: 'percent', sla: 'percent', note: 'textarea', flag: 'yesno' }

/** "alan=değer;…" → değer nesnesi ("-" = yok → null; "" → boş). */
function values(spec) {
  if (spec === '-') return null
  const out = {}
  if (!spec) return out
  for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    const id = part.slice(0, eq)
    const raw = part.slice(eq + 1)
    const type = TYPES[id]
    expect(type, `tablo alanı ${id}`).toBeTruthy()
    out[id] = type === 'number' || type === 'percent' ? Number(raw) : type === 'yesno' ? raw === 'true' : raw
  }
  return out
}

describe('yönetici özeti — fark / hedef / hedefe uzaklık (Java ile ortak doğruluk tablosu)', () => {
  it('DELTA_TABLE satırları Java ile aynı cümleyi verir (TR + EN, düşen cümleler, raporun hedefi)', () => {
    const rows = javaStringTable(JAVA, 'DELTA_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(20)
    for (const [lang, line, fields, prev, targets, expected] of rows) {
      const tpl = template(lang, line)
      const out = summaryAutoText(tpl.sections[0], tpl, values(fields), 'Mobil', 2026, 9, values(prev), values(targets) || {})
      const label = [lang, line, fields, prev, targets].join(' | ')
      if (expected === '-') expect(out, label).toBe('')
      else expect(out, label).toBe(expected)
    }
  })

  it('inceleme 2026-10-17: yalnız {d:} ile anılan gömülü alan "söylenmemiş" sayılır — değeri kendi satırında (Java ile aynı)', () => {
    const tpl = (line) => ({ lang: 'tr', title: '{kanal}', sections: [
      { id: 'exec', kind: 'summary', title: 'Özet', lines: [line] },
      { id: 'm', kind: 'metrics', title: 'Veriler', host: 'exec', fields: [{ id: 'count', label: 'Hata adedi', type: 'number' }] }] })
    const a = tpl('Hata adedi {d:count} değişti.')
    expect(summaryBlocks(a, a.sections[0], { count: 5250 }, {}, 'Mobil', 2026, 9, { count: 6000 }, null).map((b) => b.text))
      .toEqual(['Hata adedi **−750 (−%12,5)** değişti.', 'Hata adedi: **5.250**'])
    const b = tpl('Hata adedi {f:count} oldu, {d:count} değişti.')
    expect(summaryBlocks(b, b.sections[0], { count: 5250 }, {}, 'Mobil', 2026, 9, { count: 6000 }, null)).toHaveLength(1)
  })

  it('başvuru tarama: dört tür, yalnız {f:} seçim alır; kapanmamış / boş kimlikli başvuru bozuk; tanınmayan önek düz metin', () => {
    const refs = summaryRefs('a {d:count} b {t:sla} c {dt:sla} d {f:flag?e|h} e')
    expect(refs.map((r) => r.kind)).toEqual(['d', 't', 'dt', 'f'])
    expect(refs.map((r) => r.id)).toEqual(['count', 'sla', 'sla', 'flag'])
    expect(refs[3].yes).toBe('e')
    expect(refs[0].yes).toBeNull()
    expect(summaryMalformed('{d:count')).toBe(true)
    expect(summaryMalformed('{dt:}')).toBe(true)
    expect(summaryMalformed('{d:Count}')).toBe(true)
    expect(summaryMalformed('{x:count} {d:count}')).toBe(false)
    expect(summaryRefs('{x:count}')).toEqual([])
  })

  it('etkin hedef ve yön: raporun hedefi şablonunkini ezer, okunamayan hedef yok sayılır; yüzde dışı alanda hedef yok; yön varsayılanı yüzde düşük-iyi, sayı yönsüz', () => {
    const [count, rate, sla] = template('tr', 'x').sections[1].fields
    expect(effectiveTarget(sla, {})).toBe(99.95)
    expect(effectiveTarget(sla, { sla: 99.5 })).toBe(99.5)
    expect(effectiveTarget(sla, { sla: '99.5' })).toBe(99.5)
    expect(effectiveTarget(sla, { sla: 'x' })).toBe(99.95)
    expect(effectiveTarget(rate, {})).toBeNull()
    expect(effectiveTarget(rate, { rate: 1 })).toBeNull()         // şablonda hedefi yok → rapor hedefi geçmez (sunucu ile aynı)
    expect(effectiveTarget(count, { count: 5 })).toBeNull()
    expect(effectiveBetter(sla)).toBe('higher')
    expect(effectiveBetter(rate)).toBe('lower')
    expect(effectiveBetter(count)).toBe('none')
    expect(effectiveBetter(null)).toBe('none')
  })

  it('fark ve puan: adet fark + yüzde (önceki 0 → yalnız adet; değişim yok düz), yüzde puan; TR / EN biçim; eksik taraf null', () => {
    const count = { id: 'count', type: 'number' }
    const rate = { id: 'rate', type: 'percent' }
    expect(deltaOf(count, 6000, 5250, 'tr')).toEqual({ text: '−750 (−%12,5)', dir: 'down' })
    expect(deltaOf(count, 1000000, 1250000, 'en')).toEqual({ text: '+250,000 (+25%)', dir: 'up' })
    expect(deltaOf(count, 0, 5, 'tr')).toEqual({ text: '+5', dir: 'up' })
    expect(deltaOf(count, 7, 7, 'tr')).toEqual({ text: 'değişim yok', dir: 'flat' })
    expect(deltaOf(count, 7, 7, 'en')).toEqual({ text: 'no change', dir: 'flat' })
    expect(deltaOf(rate, 0.5, 0.42, 'tr')).toEqual({ text: '−0,08 puan', dir: 'down' })
    expect(deltaOf(rate, 0.42, 0.42, 'tr')).toEqual({ text: '0 puan', dir: 'flat' })
    expect(deltaOf(rate, null, 0.42, 'tr')).toBeNull()
    expect(deltaOf(rate, 0.42, null, 'tr')).toBeNull()
    expect(pointsOf(99.99 - 99.95, 'en')).toEqual({ text: '+0.04 pts', dir: 'up' })
    // Küçük puan farkı 2 anlamlı hane, en çok 6 ondalık (sahte "0 puan" yok)
    expect(pointsOf(0.000042, 'tr')).toEqual({ text: '+0,000042 puan', dir: 'up' })
  })
})
