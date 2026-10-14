import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  COLUMN_TYPES, KINDS, LIMITS, RICH_MAX_BLOCKS, RICH_MAX_LEVEL, canonicalTemplate, canonicalValues, insertToken, newSection,
  normalizeValues, parseRichText, richBlank, richInline, richPlain, richStrip, shortTextValue, stableJson, summaryAutoText,
  summaryEffective, summaryLineIssues, summaryLinePreview, summaryLineStatus, summaryLinesUsing, summaryMalformed, summaryRefs,
  tableCellKey, tableMinWidth, templateErrors,
} from '../components/techerror/techErrorModel.js'
import { indentLines, toggleBold, toggleBullets } from '../components/techerror/richTextEdit.js'

// Yönetici raporu yapısı (2026-10-14): istemci aynası — zengin metin, özet kalıpları ve tablo kuralları sunucudaki
// TechErrorRichText / TechErrorSummary / TechErrorTemplate ile AYNI doğruluk tablosunu taşır (TechErrorExecutiveTest).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const SRC = (f) => fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror', f), 'utf8')
const javaInt = (src, name) => Number(new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(src)[1].replace(/_/g, ''))

const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Özet', lines: [
      '{kanal} Kanalı {ay} {yil} teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      'Kesintiler hariç oran {f:excl} olarak hesaplanmaktadır.',
      'Toplam işlem {f:total}; önceki aya göre {f:pct} {f:up?artış|azalış} görülmüştür.',
      '- Not: {f:note}',
      'Kapanış cümlesi.',
    ] },
    { id: 'm', kind: 'metrics', title: 'G', fields: [
      { id: 'error-rate', label: 'Oran', type: 'percent' },
      { id: 'had', label: 'Kesinti oldu mu?', type: 'yesno' },
      { id: 'excl', label: 'Kesintisiz oran', type: 'percent', skip_if_no: 'had' },
      { id: 'total', label: 'Toplam', type: 'number' },
      { id: 'pct', label: 'Artış', type: 'percent' },
      { id: 'up', label: 'Artış var mı?', type: 'yesno' },
      { id: 'note', label: 'Not', type: 'text' },
    ] },
  ],
}

describe('sınırlar ve türler Java ile aynı', () => {
  it('özet / tablo sınırları', () => {
    const tpl = SRC('TechErrorTemplate.java')
    expect(LIMITS.summaryLines).toBe(javaInt(tpl, 'MAX_SUMMARY_LINES'))
    expect(LIMITS.summaryLine).toBe(javaInt(tpl, 'MAX_SUMMARY_LINE'))
    expect(LIMITS.columns).toBe(javaInt(tpl, 'MAX_COLUMNS'))
    expect(LIMITS.rows).toBe(javaInt(tpl, 'MAX_ROWS'))
    expect(LIMITS.columnLabel).toBe(javaInt(tpl, 'MAX_COLUMN_LABEL'))
    expect(LIMITS.cellText).toBe(javaInt(SRC('TechErrorValues.java'), 'MAX_CELL_TEXT'))
    const rich = SRC('TechErrorRichText.java')
    expect(RICH_MAX_LEVEL).toBe(javaInt(rich, 'MAX_LEVEL'))
    expect(RICH_MAX_BLOCKS).toBe(javaInt(rich, 'MAX_BLOCKS'))
    const kinds = /KINDS = List\.of\(([^)]*)\)/.exec(tpl)[1].match(/"([^"]+)"/g).map((s) => s.slice(1, -1))
    expect(KINDS).toEqual(kinds)
    const cols = /COLUMN_TYPES = List\.of\(([^)]*)\)/.exec(tpl)[1].match(/"([^"]+)"/g).map((s) => s.slice(1, -1))
    expect(COLUMN_TYPES).toEqual(cols)
  })
})

describe('zengin metin', () => {
  it('Java ile aynı doğruluk tablosu: madde düzeyleri, numara, kalın, paragraf aralığı', () => {
    const text = 'Giriş **kalın** metin\n\n\n- Birinci\n  - Alt madde\n      - Çok derin\n* Yıldızlı\n• Noktalı\n12. Numaralı\n3) Parantezli\n'
      + '  devam satırı\n**yarım'
    const b = parseRichText(text)
    expect(b.map((x) => x.kind)).toEqual(['p', 'gap', 'li', 'li', 'li', 'li', 'li', 'li', 'li', 'p', 'p'])
    expect(b[0].runs).toEqual([{ text: 'Giriş ', bold: false }, { text: 'kalın', bold: true }, { text: ' metin', bold: false }])
    expect([b[2].level, b[3].level, b[4].level]).toEqual([0, 1, RICH_MAX_LEVEL])
    expect(b[7].marker).toBe('12.')
    expect(b[8].marker).toBe('3.')
    expect(b[9].level).toBe(1)
    expect(b[10].runs).toEqual([{ text: '**yarım', bold: false }])
    expect(richPlain('- **A**\n  - b\n\nson')).toBe('- A\n  - b\n\nson')
    expect(parseRichText('**başta kalın** sonra')[0].kind).toBe('p')
    expect(parseRichText('*yalnız')[0].kind).toBe('p')
    expect(parseRichText('- x\n'.repeat(RICH_MAX_BLOCKS + 10))).toHaveLength(RICH_MAX_BLOCKS)
  })

  it('kalın işaret: Java testindeki doğruluk tablosunun AYNISI (BOLD_TABLE kaynaktan okunur — iki taraf ayrışamaz)', () => {
    const java = fs.readFileSync(path.join(ROOT, 'backend/src/test/java/com/sitemonitor/service/techerror/TechErrorExecutiveTest.java'), 'utf8')
    const block = /BOLD_TABLE = \{([\s\S]*?)\n\s*\};/.exec(java)[1]
    const rows = [...block.matchAll(/\{([^{}]*)\},/g)].map((m) => [...m[1].matchAll(/"((?:[^"\\]|\\.)*)"|true|false/g)]
      .map((t) => (t[0] === 'true' ? true : t[0] === 'false' ? false : t[1])))
    expect(rows.length).toBeGreaterThanOrEqual(12)
    for (const [input, ...rest] of rows) {
      const expected = []
      for (let i = 0; i < rest.length; i += 2) expected.push({ text: rest[i], bold: rest[i + 1] })
      expect(richInline(input), input).toEqual(expected)
    }
  })

  it('boşluk kümesi Java ile aynı: bölünmez boşluklu satır paragraf aralığı, ASCII olmayan rakam numara değil', () => {
    expect(parseRichText('a\n\u00A0\u00A0\nb').map((x) => x.kind)).toEqual(['p', 'gap', 'p'])
    expect(parseRichText('\u0661. madde')[0].kind).toBe('p')
    expect(richStrip('\u00A0 x \uFEFF')).toBe('x')
    expect(richBlank('\u2007\u202F')).toBe(true)
    expect(richBlank('\u200B')).toBe(false)       // sıfır genişlikli boşluk Java'da da boşluk değil
    expect(summaryRefs('{f:\u00A0rate}').map((r) => r.id)).toEqual(['rate'])
    expect(shortTextValue('  a \t  b\n ')).toBe('a b')
    expect(shortTextValue('   ')).toBe('')
  })

  it('düzenleme: kalın sarar / açar, madde ekler / kaldırır, girinti en çok 3 düzey', () => {
    expect(toggleBold('abc def', 0, 3)).toEqual({ value: '**abc** def', start: 2, end: 5 })
    expect(toggleBold('**abc** def', 2, 5)).toEqual({ value: 'abc def', start: 0, end: 3 })
    expect(toggleBold('**abc** def', 0, 7)).toEqual({ value: 'abc def', start: 0, end: 3 })
    expect(toggleBold('ab', 1, 1)).toEqual({ value: 'a****b', start: 3, end: 3 })
    // Kenardaki boşluk işaretin dışında kalır (çift tıklama sondaki boşluğu da seçer) → geçerli işaret
    expect(toggleBold('Limit sorgusu', 0, 6)).toEqual({ value: '**Limit** sorgusu', start: 2, end: 7 })
    expect(richInline(toggleBold('Limit sorgusu', 0, 6).value)[0]).toEqual({ text: 'Limit', bold: true })
    // Çok satırlı seçim: her satır ayrı sarılır, madde işareti dışarıda; ikinci basış hepsini geri açar
    expect(toggleBold('- bir\niki', 0, 9).value).toBe('- **bir**\n**iki**')
    const r1 = toggleBold('foo\nbar', 0, 7)
    expect(r1.value).toBe('**foo**\n**bar**')
    expect(toggleBold(r1.value, r1.start, r1.end).value).toBe('foo\nbar')
    const b1 = toggleBold('- a\n- b', 0, 7)
    expect(b1.value).toBe('- **a**\n- **b**')
    expect(toggleBold(b1.value, b1.start, b1.end).value).toBe('- a\n- b')
    expect(toggleBold('**a**\nb', 0, 7).value).toBe('**a**\n**b**')     // karışık: yalnız kalın olmayan sarılır
    // Madde satırının tamamı seçilse de işaret dışarıda kalır; ikinci basış geri açar
    const l1 = toggleBold('- Limit aşıldı', 0, 14)
    expect(l1.value).toBe('- **Limit aşıldı**')
    expect(parseRichText(l1.value)[0].kind).toBe('li')
    expect(toggleBold(l1.value, l1.start, l1.end).value).toBe('- Limit aşıldı')
    expect(toggleBold('1. adım', 0, 7).value).toBe('1. **adım**')
    // Sekmeli işaret (Word / Outlook yapıştırması) madde sayılır: kalın işaretin dışında, madde düğmesi ikinci işaret eklemez
    expect(toggleBold('•\tKart hatası', 0, 13).value).toBe('•\t**Kart hatası**')
    expect(parseRichText(toggleBold('•\tKart hatası', 0, 13).value)[0].kind).toBe('li')
    expect(toggleBullets('•\tKart', 0, 6).value).toBe('Kart')
    expect(indentLines('\t- x', 0, 4, -1).value).toBe('- x')
    expect(indentLines('\t- x', 0, 4, 1).value).toBe('    - x')
    // İçinde kalın olan seçim iç içe işaret üretmez (PDF'e ham ** düşmez)
    const nested = toggleBold('Kart **hatası** arttı', 0, 21)
    expect(nested.value).toBe('**Kart hatası arttı**')
    expect(richInline(nested.value)).toEqual([{ text: 'Kart hatası arttı', bold: true }])
    // Yalnız işaretten ibaret satır kalın yapılmaz
    expect(toggleBold('- \nmetin', 0, 8).value).toBe('- \n**metin**')
    expect(toggleBullets('a\nb\n\nc', 0, 7).value).toBe('- a\n- b\n\n- c')
    expect(toggleBullets('- a\n- b', 0, 7).value).toBe('a\nb')
    expect(toggleBullets('x\n- a\nb', 2, 5).value).toBe('x\na\nb')           // yalnız seçili satır
    expect(toggleBullets('', 0, 0).value).toBe('- ')
    expect(indentLines('a', 0, 1, 1).value).toBe('  - a')
    expect(indentLines('  - a', 0, 5, 1).value).toBe('    - a')
    expect(indentLines('    - a', 0, 7, 1).value).toBe('    - a')                 // en derin düzey
    expect(indentLines('    - a', 0, 7, -1).value).toBe('  - a')
    expect(indentLines('- a', 0, 3, -1).value).toBe('- a')
  })
})

describe('yönetici özeti', () => {
  const sec = TPL.sections[0]

  it('Java ile aynı: değer kalın, seçim cevaba göre, eksik / muaf rakamın cümlesi düşer, madde ayrı satır', () => {
    const f = { 'error-rate': 0.42, had: false, excl: 0.3, total: 1250000, pct: 3.5, up: false, note: 'a**b**c' }
    const text = summaryAutoText(sec, TPL, f, 'Mobil', 2026, 9)
    expect(text).toBe('Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir. Toplam işlem **1.250.000**; '
      + 'önceki aya göre **%3,5** azalış görülmüştür.\n- Not: a*b*c\n\nKapanış cümlesi.')
    expect(summaryAutoText(sec, TPL, { ...f, had: true }, 'Mobil', 2026, 9)).toContain('Kesintiler hariç oran **%0,3**')
    expect(summaryAutoText(sec, { ...TPL, lang: 'en' }, { 'error-rate': 0.42 }, 'Mobil', 2026, 9))
      .toBe('Mobil Kanalı September 2026 teknik hata oranı **0.42%** olarak gerçekleşmiştir. Kapanış cümlesi.')
  })

  it('değer güvenliği: yıldız dizisi tek yıldız, uzun açıklama alanı cümleyi düşürür, boşluktan ibaret metin yok sayılır; madde kararı kalıptan', () => {
    const tpl = { lang: 'tr', sections: [
      { id: 's', kind: 'summary', lines: ['Kart {f:card} ile {f:n} hata oldu.', 'Not: {f:long}', '{f:card} ikinci cümle.'] },
      { id: 'm', kind: 'metrics', fields: [
        { id: 'card', label: 'Kart', type: 'text' }, { id: 'n', label: 'Adet', type: 'number' },
        { id: 'long', label: 'Not', type: 'textarea' }] },
    ] }
    // Java TechErrorExecutiveTest.summaryValueSafety ile aynı girdi ve çıktı
    const text = summaryAutoText(tpl.sections[0], tpl, { card: '4543 **** 1234', n: 12, long: 'uzun\nnot' }, 'K', 2026, 1)
    expect(text).toBe('Kart 4543 * 1234 ile **12** hata oldu. 4543 * 1234 ikinci cümle.')
    expect(summaryAutoText(tpl.sections[0], tpl, { card: '1. şube', n: 3 }, 'K', 2026, 1))
      .toBe('Kart 1. şube ile **3** hata oldu. 1. şube ikinci cümle.')
    // Boşluktan ibaret kısa metin sunucuda saklanmaz → önizleme de cümleyi düşürür
    expect(summaryAutoText(tpl.sections[0], tpl, { card: '   ', n: 3 }, 'K', 2026, 1)).toBe('')
    const st = summaryLineStatus(tpl.sections[0], tpl, { n: 3 })
    expect(st.map((x) => [x.state, x.reason])).toEqual([['missing', null], ['broken', 'textarea'], ['missing', null]])
    expect(summaryLineIssues('Not: {f:long} {f:long}', tpl)).toEqual([{ kind: 'textarea', id: 'long', label: 'Not' }])
    // Aynı eksik alan bir kez listelenir (yinelenen React anahtarı olmasın)
    expect(summaryLineStatus({ lines: ['{f:n} ve {f:n}'] }, tpl, {})[0].missing.map((f) => f.id)).toEqual(['n'])
    // Silinmiş alan kimliği gösterilmez
    expect(summaryLinePreview('Oran {f:gitti}', tpl, (x) => x, 'silinmiş alan')).toBe('Oran [silinmiş alan]')
    // Java TechErrorExecutiveTest.summaryValueSafety ile aynı: paragraf başındaki değer "1. " ile başlarsa madde olmaz
    const lead = { ...tpl, sections: [{ ...tpl.sections[0], lines: ['{f:card} kanalında {f:n} hata.', '- Madde {f:n}', '{f:card} son.'] },
      tpl.sections[1]] }
    const led = summaryAutoText(lead.sections[0], lead, { card: '1. Bölge', n: 3 }, 'K', 2026, 1)
    expect(led).toBe('1.\u00A0Bölge kanalında **3** hata.\n- Madde **3**\n\n1.\u00A0Bölge son.')
    expect(parseRichText(led).map((x) => x.kind)).toEqual(['p', 'li', 'gap', 'p'])
    // Madde kalıbı boş gövdeye çözülürse cümle düşer
    const empty = { ...tpl, sections: [{ ...tpl.sections[0], lines: ['Giriş.', '- {f:var?Var|}'] },
      { ...tpl.sections[1], fields: [...tpl.sections[1].fields, { id: 'var', label: 'Var mı', type: 'yesno' }] }] }
    expect(summaryAutoText(empty.sections[0], empty, { var: false }, 'K', 2026, 1)).toBe('Giriş.')
    expect(summaryAutoText(empty.sections[0], empty, { var: true }, 'K', 2026, 1)).toBe('Giriş.\n- Var')
  })

  it('iç içe süslü parantezli başvuru okunamaz (kaydı engeller), seçim kesilmez; satır içi sekme kaydedilen gibi', () => {
    expect(summaryMalformed('{f:sla-above?{ay} ayında üstünde|altında}')).toBe(true)
    const long = 'ü'.repeat(200)
    expect(summaryRefs(`{f:a?${long}|b}`)[0].yes).toBe(long)
    const b = parseRichText('1.\tMadde\n-\tİkinci\n\t- alt')
    expect(b.map((x) => [x.kind, x.level])).toEqual([['li', 0], ['li', 0], ['li', 1]])
  })

  it('tablo en az genişliği: satır adı 176, sayı sütunu 112, metin 160 px (en az 640); varsayılan 6 sütun 1280 px dizüstüne sığar', () => {
    expect(tableMinWidth([{ type: 'number' }])).toBe(640)
    expect(tableMinWidth(Array.from({ length: 6 }, () => ({ type: 'number' })))).toBe(176 + 6 * 112)
    expect(tableMinWidth(Array.from({ length: 6 }, () => ({ type: 'number' })))).toBeLessThanOrEqual(860)
    expect(tableMinWidth([{ type: 'text' }, { type: 'percent' }, ...Array.from({ length: 4 }, () => ({ type: 'number' }))]))
      .toBe(176 + 160 + 5 * 112)
  })

  it('değişen aralık: ortak baş / son korunur (geri al yalnız değişen parçayı seçsin)', async () => {
    const { changedSpan } = await import('../components/techerror/RichText.jsx')
    expect(changedSpan('abc def', '**abc** def')).toEqual([0, 4])
    expect(changedSpan('aaaa', 'aa')).toEqual([2, 0])
    expect(changedSpan('x', 'x')).toEqual([1, 0])
    expect(changedSpan('- a\n- b', '- **a**\n- **b**')).toEqual([2, 0])
  })

  it('elle düzenlenen metin kullanılır; boş elle metin de anahtar yokluğu da otomatik (Java ile aynı)', () => {
    expect(summaryEffective(sec, TPL, { fields: {}, texts: { exec: '{kanal} **elle**' } }, 'POS', 2026, 9)).toBe('POS **elle**')
    expect(summaryEffective(sec, TPL, { fields: {}, texts: { exec: '' } }, 'POS', 2026, 9)).toBe('Kapanış cümlesi.')
    expect(summaryEffective(sec, TPL, { fields: {}, texts: { exec: '  \n ' } }, 'POS', 2026, 9)).toBe('Kapanış cümlesi.')
    expect(summaryEffective(sec, TPL, { fields: {}, texts: {} }, 'POS', 2026, 9)).toBe('Kapanış cümlesi.')
  })

  it('cümle durumu: eksik rakamlar adlarıyla, muaf ve bozuk ayrı', () => {
    const s = summaryLineStatus({ ...sec, lines: [...sec.lines, 'Yok {f:silindi}', 'Tür {f:total?a|b}'] }, TPL,
      { had: false, total: 5 })
    expect(s.map((x) => x.state)).toEqual(['missing', 'exempt', 'missing', 'missing', 'ok', 'broken', 'broken'])
    expect(s.slice(5).map((x) => x.reason)).toEqual(['removed', 'notYesNo'])
    expect(s[2].missing.map((x) => x.id)).toEqual(['pct', 'up'])
  })

  it('başvuru taraması ve şablon uyarıları', () => {
    expect(summaryRefs('{f:a} ve {f:b?x|y} ve {f:c?yalnız}').map((r) => [r.id, r.yes, r.no]))
      .toEqual([['a', null, null], ['b', 'x', 'y'], ['c', 'yalnız', '']])
    expect(summaryMalformed('{f:a} {f:')).toBe(true)
    expect(summaryMalformed('{f:A B}')).toBe(true)
    expect(summaryMalformed('{kanal} {f:a}')).toBe(false)
    expect(summaryRefs('{f:a}'.repeat(150))).toHaveLength(100)
    expect(summaryLineIssues('{f:yok} {f:total?a|b} {f:up?a|b}', TPL)).toEqual([
      { kind: 'missing', id: 'yok' }, { kind: 'notYesNo', id: 'total', label: 'Toplam' }])
    expect(summaryLineIssues('{f:x', TPL)).toEqual([{ kind: 'malformed' }])
    expect(summaryLinePreview('Oran {f:error-rate} · {f:up?artış|azalış}', TPL)).toBe('Oran [Oran] · [artış / azalış]')
    expect(summaryLinesUsing(TPL, 'pct')).toHaveLength(1)
  })

  it('imlece parça ekleme: gerekirse boşluk, imleç parçanın sonuna', () => {
    expect(insertToken('Oran', '{f:a}', 4)).toEqual(['Oran {f:a}', 10])
    expect(insertToken('Oran test', '{f:a}', 4)).toEqual(['Oran {f:a} test', 10])
    expect(insertToken('', '{f:a}', null)).toEqual(['{f:a}', 5])
    expect(insertToken('a ', '{f:a}', 99)).toEqual(['a {f:a}', 7])
  })
})

describe('tablo ve kanonik biçim', () => {
  it('boş hücre / satır / tablo düşer; tablo yoksa anahtar hiç yazılmaz (eski raporun belgesi değişmez)', () => {
    expect(canonicalValues({ fields: { a: 1 }, issues: {}, texts: {}, tables: { t: { r: { c: null, d: '' } } } }))
      .toEqual({ fields: { a: 1 }, issues: {}, texts: {} })
    expect(canonicalValues({ fields: {}, tables: { t: { r: { c: 5, d: null }, q: {} } } }).tables).toEqual({ t: { r: { c: 5 } } })
    const n = normalizeValues({ tables: { t: { r: { c: 1 } } } })
    n.tables.t.r.c = 2
    expect(normalizeValues({ tables: { t: { r: { c: 1 } } } }).tables.t.r.c).toBe(1)
    expect(tableCellKey('t', 'r', 'c')).toBe('tables.t.r.c')
  })

  it('şablon kanonik hâli: grup:false ile yokluk aynı; cümleler ve sütunlar karşılaştırılır', () => {
    const a = { lang: 'tr', title: 'x', sections: [{ id: 's', kind: 'table', title: 'T', columns: [{ id: 'c', label: 'C', type: 'number' }],
      rows: [{ id: 'r', label: 'R', group: false }] }] }
    const b = { lang: 'tr', title: 'x', sections: [{ id: 's', kind: 'table', title: 'T', columns: [{ id: 'c', label: 'C', type: 'number' }],
      rows: [{ id: 'r', label: 'R' }] }] }
    expect(stableJson(canonicalTemplate(a))).toBe(stableJson(canonicalTemplate(b)))
    const c = { ...b, sections: [{ ...b.sections[0], columns: [{ id: 'c', label: 'C', type: 'percent' }] }] }
    expect(stableJson(canonicalTemplate(c))).not.toBe(stableJson(canonicalTemplate(b)))
    const s1 = { lang: 'tr', title: 'x', sections: [{ id: 'e', kind: 'summary', title: 'Ö', lines: ['a'] }] }
    const s2 = { lang: 'tr', title: 'x', sections: [{ id: 'e', kind: 'summary', title: 'Ö', lines: ['b'] }] }
    expect(stableJson(canonicalTemplate(s1))).not.toBe(stableJson(canonicalTemplate(s2)))
  })

  it('yeni bölüm ve istemci denetimi: boş özet, okunamayan başvuru, boş sütun / satır adı', () => {
    const labels = { title: 'Yeni', summaryLine: 'Cümle', column: 'Sütun', row: 'Satır' }
    const sum = newSection('summary', TPL, labels)
    expect(sum.lines).toEqual(['Cümle'])
    const tb = newSection('table', TPL, labels)
    expect(tb.columns[0]).toMatchObject({ label: 'Sütun', type: 'number' })
    expect(tb.rows[0]).toMatchObject({ label: 'Satır' })
    const errs = templateErrors({ lang: 'tr', title: 'x', sections: [
      { id: 'a', kind: 'summary', title: 'A', lines: ['', ' '] },
      { id: 'b', kind: 'summary', title: 'B', lines: ['ok', 'kırık {f:x', 'y'.repeat(501)] },
      { id: 'c', kind: 'table', title: 'C', columns: [{ id: 'c1', label: '', type: 'number' }], rows: [{ id: 'r1', label: ' ' }] },
      { id: 'd', kind: 'table', title: 'D', columns: [], rows: [] },
    ] })
    expect(errs['section:a']).toBe('te.err.noLines')
    expect(errs['line:b:1']).toBe('te.err.lineMalformed')
    expect(errs['line:b:2']).toBe('te.err.lineTooLong')
    expect(errs['col:c:c1']).toBe('te.err.columnLabelRequired')
    expect(errs['row:c:r1']).toBe('te.err.rowLabelRequired')
    expect(errs['section:d']).toBe('te.err.noColumns')
  })
})
