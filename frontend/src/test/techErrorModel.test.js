import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  LIMITS, MAX_RESOLVED, canonicalTemplate, canonicalValues, fieldExempt, sanitizeSkipRefs, changePercent, formatBytes, formatNumber, formatPercent, imageRows,
  issueDisplayTitle, monthName, move, newId, parseNumber, periodOptions, preflightImage, resolveIssueTitle, resolvePlaceholders,
  stableJson, statusMeta, templateErrors,
} from '../components/techerror/techErrorModel.js'
import { TEMPLATE } from './helpers/techErrorFixtures.js'

// Kanal teknik hata raporu modeli (2026-10-13): sunucu kuralının istemci aynası — sınırlar ve ay adları Java kaynağından
// okunur (iki taraf ayrışırsa kırmızı), sayı çözümleme / biçim, yer tutucular, PDF ile aynı grafik ızgarası.
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const JAVA = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorTemplate.java'), 'utf8')
const JAVA_VALUES = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorValues.java'), 'utf8')

const javaInt = (src, name) => Number(new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(src)[1].replace(/_/g, ''))

describe('techErrorModel — sunucu aynası', () => {
  it('sınırlar Java sabitleriyle aynı', () => {
    expect(LIMITS.sections).toBe(javaInt(JAVA, 'MAX_SECTIONS'))
    expect(LIMITS.fieldsPerSection).toBe(javaInt(JAVA, 'MAX_FIELDS'))
    expect(LIMITS.slots).toBe(javaInt(JAVA, 'MAX_SLOTS'))
    expect(LIMITS.title).toBe(javaInt(JAVA, 'MAX_TITLE'))
    expect(LIMITS.label).toBe(javaInt(JAVA, 'MAX_LABEL'))
    expect(LIMITS.hint).toBe(javaInt(JAVA, 'MAX_HINT'))
    expect(LIMITS.sectionText).toBe(javaInt(JAVA, 'MAX_SECTION_TEXT'))
    expect(LIMITS.issues).toBe(javaInt(JAVA_VALUES, 'MAX_ISSUES'))
    expect(LIMITS.topic).toBe(javaInt(JAVA_VALUES, 'MAX_TOPIC'))
  })

  it('ay adları Java ile aynı (PDF başlığı ile ekrandaki örnek aynı ayı yazar)', () => {
    const tr = /MONTHS_TR = \{([^}]*)\}/.exec(JAVA)[1].match(/"([^"]+)"/g).map((s) => s.slice(1, -1))
    const en = /MONTHS_EN = \{([^}]*)\}/.exec(JAVA)[1].match(/"([^"]+)"/g).map((s) => s.slice(1, -1))
    expect(Array.from({ length: 12 }, (_, i) => monthName(i + 1, 'tr'))).toEqual(tr)
    expect(Array.from({ length: 12 }, (_, i) => monthName(i + 1, 'en'))).toEqual(en)
  })

  it('yer tutucular: kanal / ay / yıl (TR ve EN adları); konu kalıbı', () => {
    expect(resolvePlaceholders(TEMPLATE.title, 'Mobil', 2026, 9, 'tr')).toBe('Mobil Kanalı Teknik Hata Mesajları - Eylül 2026')
    expect(resolvePlaceholders('{channel} - {month} {year}', 'POS', 2025, 12, 'en')).toBe('POS - December 2025')
    expect(resolveIssueTitle('{konu} Konusu ile yaşanan problem', 'Kart Limiti')).toBe('Kart Limiti Konusu ile yaşanan problem')
    expect(issueDisplayTitle({ topic: 'X', title: 'Elle', title_auto: false }, '{konu} problem')).toBe('Elle')
    expect(issueDisplayTitle({ topic: 'Ödeme', title: '', title_auto: true }, '{konu} problem')).toBe('Ödeme problem')
  })
})

describe('techErrorModel — sayılar', () => {
  it('Türkçe ve İngilizce yazımlar çözülür; belirsiz tek ayraç dilin alışkanlığına göre', () => {
    expect(parseNumber('1.250.000', 'tr')).toBe(1250000)
    expect(parseNumber('1.250', 'tr')).toBe(1250)
    expect(parseNumber('0,42', 'tr')).toBe(0.42)
    expect(parseNumber('%3,5', 'tr')).toBe(3.5)
    expect(parseNumber('1,250,000', 'en')).toBe(1250000)
    expect(parseNumber('1,250', 'en')).toBe(1250)
    expect(parseNumber('0.42', 'en')).toBe(0.42)
    expect(parseNumber('1.234,5', 'en')).toBe(1234.5)
    expect(parseNumber('-2,5', 'tr')).toBe(-2.5)
    expect(parseNumber('', 'tr')).toBeNull()
    expect(parseNumber('abc', 'tr')).toBeNaN()
    expect(parseNumber('1e5', 'tr')).toBeNaN()
    expect(parseNumber('9'.repeat(41), 'tr')).toBeNaN()
    // İnceleme 2026-10-13: sıfırla başlayan tek ayraç binlik DEĞİL; geçersiz gruplama reddedilir; yüzdede tek ayraç ondalık
    expect(parseNumber('0.125', 'tr')).toBe(0.125)
    expect(parseNumber('0,125', 'en')).toBe(0.125)
    expect(parseNumber('1.25.0', 'tr')).toBeNaN()
    expect(parseNumber('1,2,3', 'tr')).toBeNaN()
    expect(parseNumber('3.500', 'tr', { percent: true })).toBe(3.5)
    expect(parseNumber('3.500', 'tr')).toBe(3500)
    expect(parseNumber('1.250,75', 'tr')).toBe(1250.75)
    expect(parseNumber('1,234.5', 'tr')).toBe(1234.5)
    expect(parseNumber('12,34.5', 'en')).toBeNaN()
  })

  it('biçim sunucuyla aynı: TR %0,42 ve 1.250.000; EN 0.42% ve 1,250,000; değişim ipucu', () => {
    expect(formatPercent(0.42, 'tr')).toBe('%0,42')
    expect(formatPercent(0.42, 'en')).toBe('0.42%')
    expect(formatNumber(1250000, 'tr')).toBe('1.250.000')
    expect(formatNumber(1250000, 'en')).toBe('1,250,000')
    expect(formatNumber(null, 'tr')).toBe('—')
    expect(changePercent(100, 103)).toBeCloseTo(3)
    expect(changePercent(0, 5)).toBeNull()
  })
})

describe('techErrorModel — şablon', () => {
  it('grafik ızgarası PDF ile aynı: ardışık iki yarım yan yana, tek kalan yarım ve tamlar tek satır', () => {
    const s = (id, width) => ({ id, label: id, width })
    const rows = imageRows([s('a', 'half'), s('b', 'half'), s('c', 'half'), s('d', 'full'), s('e', 'half'), s('f', 'half')])
    expect(rows.map((r) => r.map((x) => x.id))).toEqual([['a', 'b'], ['c'], ['d'], ['e', 'f']])
    // PDF kipi: yalnız görseli olanlar sayılır (eksik yuva yerini sonrakine bırakır)
    expect(imageRows([s('a', 'half'), s('b', 'half'), s('c', 'half')], new Set(['a', 'c'])).map((r) => r.map((x) => x.id)))
      .toEqual([['a', 'c']])
  })

  it('istemci denetimi: boş başlık, boş alan adı, boş grafik adı, yuva tavanı', () => {
    expect(templateErrors(TEMPLATE)).toEqual({})
    const bad = structuredClone(TEMPLATE)
    bad.title = ' '
    bad.sections[0].fields[0].label = ''
    bad.sections[3].slots[0].label = ''
    const e = templateErrors(bad)
    expect(e.title).toBe('te.err.titleRequired')
    expect(e['field:error-rate']).toBe('te.err.fieldLabelRequired')
    expect(e['slot:monthly-tx']).toBe('te.err.slotLabelRequired')
    const many = structuredClone(TEMPLATE)
    many.sections[3].slots = Array.from({ length: 41 }, (_, i) => ({ id: `g${i}`, label: `G${i}`, width: 'half' }))
    expect(templateErrors(many).sections).toBe('te.err.tooManySlots')
  })

  it('yeni kimlik çakışmaz, silinmiş bir alanınkini YENİDEN KULLANMAZ, sunucu kimlik kuralına uyar; taşıma sınırda değişmez', () => {
    const id = newId('f', TEMPLATE)
    expect(id).toMatch(/^f-[a-z0-9]+$/)
    expect(id.length).toBeLessThanOrEqual(40)
    // Aynı şablonda f-3 silinip yeni alan eklenince eski kimlik dönmez (sıralı numara yok)
    const t2 = { sections: [{ id: 's', fields: [{ id: 'f-1' }, { id: 'f-2' }] }] }
    const ids = new Set(Array.from({ length: 50 }, (_, i) => newId('f', t2, 1_700_000_000_000 + i)))
    expect(ids.size).toBe(50)
    for (const x of ids) expect(['f-1', 'f-2', 'f-3']).not.toContain(x)
    // Çakışırsa yeniden dener
    let n = 0
    const fixed = () => (n++ < 1 ? 0 : 0.5)
    const taken = newId('f', t2, 1000, () => 0)
    expect(newId('f', { sections: [{ id: 's', fields: [{ id: taken }] }] }, 1000, fixed)).not.toBe(taken)
    const list = [1, 2, 3]
    expect(move(list, 0, -1)).toBe(list)
    expect(move(list, 0, 1)).toEqual([2, 1, 3])
  })
})

describe('techErrorModel — kanonik karşılaştırma ve sınırlar (inceleme 2026-10-13)', () => {
  it('boş konu listesi / null alanlar / boş ipucu sahte değişiklik sayılmaz; boş metin bölümü korunur', () => {
    const a = canonicalValues({ fields: { x: null, y: '', z: 3 }, issues: { iss: [] }, texts: { t: '' } })
    expect(a).toEqual({ fields: { z: 3 }, issues: {}, texts: { t: '' } })
    expect(stableJson(canonicalValues({ fields: {}, issues: { s: [{ id: 'i', topic: 'K', customers: null }] }, texts: {} })))
      .toBe(stableJson(canonicalValues({ fields: {}, issues: { s: [{ id: 'i', topic: 'K' }] }, texts: {} })))
    const withHint = { ...TEMPLATE, sections: [{ ...TEMPLATE.sections[0], fields: [{ id: 'a', label: 'A', type: 'number', hint: '' }] }] }
    const without = { ...TEMPLATE, sections: [{ ...TEMPLATE.sections[0], fields: [{ id: 'a', label: 'A', type: 'number' }] }] }
    expect(stableJson(canonicalTemplate(withHint))).toBe(stableJson(canonicalTemplate(without)))
  })

  it('yer tutucu çözümü sunucuyla aynı sınırda kesilir', () => {
    expect(MAX_RESOLVED).toBe(javaInt(JAVA, 'MAX_RESOLVED'))
    const r = resolvePlaceholders('{kanal}'.repeat(30), 'K'.repeat(120), 2026, 9, 'tr')
    expect(r.length).toBe(MAX_RESOLVED)
    expect(r.endsWith('…')).toBe(true)
    expect(resolveIssueTitle('{konu} '.repeat(17), 'T'.repeat(120)).length).toBeLessThanOrEqual(MAX_RESOLVED)
  })

  it('formatBytes: 0 bayt "0 KB" (1 KB değil)', () => {
    expect(formatBytes(0, 'tr')).toBe('0 KB')
    expect(formatBytes(1, 'tr')).toBe('1 KB')
  })
})

describe('techErrorModel — "Hayır ise sorma" bağı (2026-10-14)', () => {
  const tpl = { sections: [{ id: 's', kind: 'metrics', fields: [
    { id: 'q', type: 'yesno' }, { id: 'a', type: 'percent', skip_if_no: 'q' },
    { id: 'n', type: 'number' }, { id: 'b', type: 'number', skip_if_no: 'n' },
  ] }] }

  it('kanonik değer şablonla muaf alanı düşürür (gönderilmez / karşılaştırılmaz)', () => {
    const v = { fields: { q: false, a: 3, n: 1 }, issues: {}, texts: {} }
    expect(canonicalValues(v, tpl).fields).toEqual({ q: false, n: 1 })
    expect(canonicalValues({ ...v, fields: { ...v.fields, q: true } }, tpl).fields).toEqual({ q: true, a: 3, n: 1 })
    expect(canonicalValues(v).fields).toEqual({ q: false, a: 3, n: 1 })   // şablonsuz: eski davranış
  })

  it('yalnız açıkça "Hayır" muaf eder; cevapsız / "Evet" istenir', () => {
    const a = tpl.sections[0].fields[1]
    expect(fieldExempt(a, { q: false })).toBe(true)
    expect(fieldExempt(a, { q: true })).toBe(false)
    expect(fieldExempt(a, {})).toBe(false)
    expect(fieldExempt({ id: 'x' }, { q: false })).toBe(false)
  })

  it('geçersiz bağlar kalkar (evet/hayır olmayan hedef, kendisi, zincir, silinmiş); değişiklik yoksa aynı nesne', () => {
    const clean = sanitizeSkipRefs(tpl)
    expect(clean.sections[0].fields.find((f) => f.id === 'b').skip_if_no).toBeUndefined()
    expect(clean.sections[0].fields.find((f) => f.id === 'a').skip_if_no).toBe('q')
    expect(sanitizeSkipRefs(clean)).toBe(clean)
    const chained = { sections: [{ id: 's', kind: 'metrics', fields: [
      { id: 'q', type: 'yesno', skip_if_no: 'r' }, { id: 'r', type: 'yesno' }, { id: 'a', type: 'number', skip_if_no: 'q' },
      { id: 'self', type: 'yesno', skip_if_no: 'self' }, { id: 'gone', type: 'number', skip_if_no: 'deleted' },
    ] }] }
    const fs = sanitizeSkipRefs(chained).sections[0].fields
    expect(fs.find((f) => f.id === 'a').skip_if_no).toBeUndefined()
    expect(fs.find((f) => f.id === 'self').skip_if_no).toBeUndefined()
    expect(fs.find((f) => f.id === 'gone').skip_if_no).toBeUndefined()
    expect(fs.find((f) => f.id === 'q').skip_if_no).toBe('r')
  })
})

describe('techErrorModel — durum ve dönem', () => {
  it('kuyruktaki gönderim yeşil değil', () => {
    expect(statusMeta('queued')).toEqual({ tone: 'info', key: 'te.status.queued' })
  })

  it('bilinmeyen durum asla "gönderildi" (yeşil) sayılmaz', () => {
    expect(statusMeta('sent').tone).toBe('success')
    expect(statusMeta('changed').tone).toBe('warning')
    expect(statusMeta('weird').tone).toBe('muted')
    expect(statusMeta(null).tone).toBe('muted')
  })

  it('dönem seçenekleri Ocak sınırında önceki yıla geçer, en yeni önce', () => {
    const opts = periodOptions({ year: 2026, month: 2 }, 3)
    expect(opts.map((o) => o.key)).toEqual(['2026-02', '2026-01', '2025-12'])
  })

  it('görsel ön denetimi: tür ve boyut', () => {
    expect(preflightImage({ name: 'a.png', type: 'image/png', size: 100 })).toBeNull()
    expect(preflightImage({ name: 'a.gif', type: 'image/gif', size: 100 })).toBe('te.img.errType')
    expect(preflightImage({ name: 'a.jpg', type: 'image/jpeg', size: LIMITS.imageBytes + 1 })).toBe('te.img.errSize')
    expect(preflightImage({ name: 'a.png', type: 'image/png', size: 0 })).toBe('te.img.errEmpty')
  })
})
