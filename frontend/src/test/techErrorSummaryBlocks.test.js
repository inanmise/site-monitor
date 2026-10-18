import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { summaryBlocks, summaryAutoText, capTextBlocks, MAX_RESOLVED_TEXT } from '../components/techerror/techErrorModel.js'

// Yönetici özeti BLOKLARI (2026-10-17): sunucudaki TechErrorSummary.blocks ile AYNI doğruluk tablosu — tablo Java test
// kaynağından okunur (TechErrorSummaryBlocksTest.BLOCKS_TABLE), iki taraf ayrışamaz. Şablon Java testindeki template()
// ile elle aynıdır (kimlik / ad / tür / cümle bire bir).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const JAVA = fs.readFileSync(path.join(ROOT, 'backend/src/test/java/com/sitemonitor/service/techerror/TechErrorSummaryBlocksTest.java'), 'utf8')

const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} teknik hata oranı {f:rate} olarak gerçekleşmiştir.',
      'Map yapılmamış hata adedi {f:un-count} olup teknik hataların {f:un-rate} kadarını oluşturmaktadır.',
      '- Kota hatası adedi {f:q-count} olarak kaydedilmiştir.',
      'Kapanış cümlesi.',
      'Kaynak {f:q-short} olarak belirlenmiştir.',
    ] },
    { id: 'base', kind: 'metrics', title: 'Oranlar', fields: [
      { id: 'rate', label: 'Teknik hata oranı', type: 'percent' },
      { id: 'un-rate', label: 'Map yapılmamış hataların oranı', type: 'percent' },
    ] },
    { id: 'un', kind: 'metrics', title: 'Map Yapılmamış Hatalar', host: 'exec', fields: [
      { id: 'un-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'un-note', label: 'Map yapılmamış hatalar hakkında açıklama', type: 'textarea' },
    ] },
    { id: 'q', kind: 'metrics', title: 'Kota Hataları', host: 'exec', fields: [
      { id: 'q-count', label: 'Kota hatası adedi', type: 'number' },
      { id: 'q-flag', label: 'Kota aşımı oldu mu?', type: 'yesno' },
      { id: 'q-note', label: 'Kota hataları hakkında açıklama', type: 'textarea' },
      { id: 'q-short', label: 'Kota kaynağı', type: 'text' },
      { id: 'ex-note', label: 'Kesinti notu', type: 'textarea', skip_if_no: 'q-flag' },
    ] },
    { id: 'free', kind: 'metrics', title: 'Serbest', host: 'exec', fields: [
      { id: 'fr-count', label: '{kanal} serbest adedi', type: 'number' },
      { id: 'fr-note', label: 'Serbest açıklama', type: 'textarea' },
      { id: 'fr-short', label: 'Serbest kaynak', type: 'text' },
    ] },
  ],
}
const FIELDS = new Map(TPL.sections.filter((s) => s.kind === 'metrics').flatMap((s) => s.fields).map((f) => [f.id, f]))

/** Java kaynak kaçışları: \n \t \" \\ \uXXXX. */
function unescapeJava(s) {
  return s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t', '"': '"', '\\': '\\' }[c] ?? c))
}

/** `String[][] AD = { { "a", null, "b" }, … };` → satır dizileri (hücre: dizgi ya da null). Dizgi içindeki süslü parantez satır sınırı değildir. */
function javaStringTable(src, name) {
  const start = src.indexOf(`${name} = {`)
  expect(start, `${name} Java kaynağında`).toBeGreaterThan(0)
  const body = src.slice(start + name.length + 4)
  const rows = []
  let row = null
  let i = 0
  while (i < body.length) {
    const ch = body[i]
    if (ch === '"') {
      let j = i + 1
      let s = ''
      while (j < body.length && body[j] !== '"') {
        if (body[j] === '\\') { s += body[j] + body[j + 1]; j += 2 } else { s += body[j]; j++ }
      }
      row.push(unescapeJava(s))
      i = j + 1
    } else if (ch === '{') {
      row = []
      i++
    } else if (ch === '}') {
      if (row === null) break
      rows.push(row)
      row = null
      i++
    } else if (body.startsWith('null', i)) {
      row.push(null)
      i += 4
    } else {
      i++
    }
  }
  return rows
}

/** "alan=değer;…" → değer nesnesi (tür alandan: sayı, evet/hayır, metin olduğu gibi — Java testindeki values()). */
function values(spec) {
  const out = {}
  if (!spec) return out
  for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    const id = part.slice(0, eq)
    const raw = part.slice(eq + 1)
    const f = FIELDS.get(id)
    expect(f, `tablo alanı ${id}`).toBeTruthy()
    out[id] = f.type === 'number' || f.type === 'percent' ? Number(raw) : f.type === 'yesno' ? raw === 'true' : raw
  }
  return out
}

function expected(s) {
  if (s.startsWith('T:')) return { kind: 'text', text: s.slice(2) }
  const rest = s.slice(2)
  const p = []
  let from = 0
  for (let k = 0; k < 3; k++) {
    const c = rest.indexOf(':', from)
    p.push(rest.slice(from, c))
    from = c + 1
  }
  return { kind: 'note', section: p[0], field: p[1], label: p[2], text: rest.slice(from) }
}

describe('yönetici özeti blokları — Java ile ortak doğruluk tablosu', () => {
  const rows = javaStringTable(JAVA, 'BLOCKS_TABLE')

  it('tablo okunur (en az 10 satır) ve her satır: ad, değerler, elle metin, en az bir blok', () => {
    expect(rows.length).toBeGreaterThanOrEqual(10)
    for (const r of rows) expect(r.length, r[0]).toBeGreaterThanOrEqual(4)
  })

  it('her satır Java ile aynı blokları verir: açıklama ilgili cümlenin altında, söylenmemiş değer kendi satırında, elle metinde yalnız notlar', () => {
    for (const [name, spec, override, ...exp] of rows) {
      const texts = override == null ? {} : { exec: override }
      expect(summaryBlocks(TPL, TPL.sections[0], values(spec), texts, 'Mobil', 2026, 9), name).toEqual(exp.map(expected))
    }
  })

  it('gömülü bölüm yoksa tek metin bloğu = otomatik metin (davranış değişmez)', () => {
    const flat = { ...TPL, sections: TPL.sections.map(({ host, ...s }) => s) }
    for (const [name, spec, override] of rows) {
      if (override != null) continue
      const v = values(spec)
      expect(summaryBlocks(flat, flat.sections[0], v, {}, 'Mobil', 2026, 9), name)
        .toEqual([{ kind: 'text', text: summaryAutoText(flat.sections[0], flat, v, 'Mobil', 2026, 9) }])
    }
  })

  it('metin sınırı yalnız metne (Java capText ile aynı): sonra başlayan metin blokları düşer, kalan son blok … ile biter, notlar düşmez', () => {
    const note = { kind: 'note', section: 'un', field: 'un-note', label: 'Açıklama', text: 'Not' }
    const cut = capTextBlocks([{ kind: 'text', text: 'a'.repeat(MAX_RESOLVED_TEXT + 500) }, note, { kind: 'text', text: 'b' }], MAX_RESOLVED_TEXT)
    expect(cut).toHaveLength(2)
    expect(cut[0].text).toHaveLength(MAX_RESOLVED_TEXT)
    expect(cut[0].text.endsWith('…')).toBe(true)
    expect(cut[1]).toEqual(note)
    const second = capTextBlocks([{ kind: 'text', text: 'abcde' }, note, { kind: 'text', text: 'x'.repeat(MAX_RESOLVED_TEXT) }], MAX_RESOLVED_TEXT)
    expect(second).toHaveLength(3)
    expect(second[0].text).toBe('abcde')
    expect(second[2].text).toHaveLength(MAX_RESOLVED_TEXT - 1 - 7 + 1)
    const same = [{ kind: 'text', text: 'kısa' }, note]
    expect(capTextBlocks(same, MAX_RESOLVED_TEXT)).toBe(same)
    // Elle metin yer tutucu çözümüyle sınırda kesilir; not kalır
    const long = summaryBlocks(TPL, TPL.sections[0], { 'un-note': 'Not' }, { exec: 'y'.repeat(MAX_RESOLVED_TEXT + 10) }, 'Mobil', 2026, 9)
    expect(long[0].text).toHaveLength(MAX_RESOLVED_TEXT)
    expect(long[1].kind).toBe('note')
  })
})

// Bütünleştirme incelemesi 2026-10-18: "söylenen" = sunucudaki shownValues kuralı (tamamı basılan cümle + seçilen dal boş
// değil) — seçimli başvuru tablosu Java kaynağından (CHOICE_TABLE), metin sınırı senaryosu Java testindekinin aynısı
const CHOICE_TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      'Dönem değerlendirmesi tamamlandı.{f:q-flag? Kota aşıldı.|}',
      'Kota adedi {f:q-count} olarak kaydedilmiştir.',
    ] },
    { id: 'q', kind: 'metrics', title: 'Kota', host: 'exec', fields: [
      { id: 'q-flag', label: 'Kota aşımı oldu mu?', type: 'yesno' },
      { id: 'q-count', label: 'Kota hatası adedi', type: 'number' },
    ] },
  ],
}

function valuesFor(tpl, spec) {
  const byId = new Map(tpl.sections.filter((s) => s.kind === 'metrics').flatMap((s) => s.fields).map((f) => [f.id, f]))
  const out = {}
  if (!spec) return out
  for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    const f = byId.get(part.slice(0, eq))
    const raw = part.slice(eq + 1)
    expect(f, `alan ${part}`).toBeTruthy()
    out[f.id] = f.type === 'number' || f.type === 'percent' ? Number(raw) : f.type === 'yesno' ? raw === 'true' : raw
  }
  return out
}

/** 450 karakterlik dolgu cümlesi (Java filler ile aynı). */
const filler = (k) => {
  const head = `Dolgu ${k} `
  return `${head}${'x'.repeat(449 - head.length)}.`
}

describe('yönetici özeti blokları — söylenen kümesi = shownValues kuralı', () => {
  const rows = javaStringTable(JAVA, 'CHOICE_TABLE')

  it('seçimli başvuru tablosu Java ile aynı: seçilen dal boşsa gömülü değer satır olarak basılır', () => {
    expect(rows.length).toBeGreaterThanOrEqual(3)
    for (const [name, spec, override, ...exp] of rows) {
      const texts = override == null ? {} : { exec: override }
      expect(summaryBlocks(CHOICE_TPL, CHOICE_TPL.sections[0], valuesFor(CHOICE_TPL, spec), texts, 'Mobil', 2026, 9), name)
        .toEqual(exp.map(expected))
    }
  })

  it('cümlesi metin sınırında kesilen ya da sınırın ötesinde kalan gömülü değer kaybolmaz: değer satırı tamamıyla özet metninden sonra (Java cutSentenceValuesStillPrint)', () => {
    const lines = Array.from({ length: 26 }, (_, k) => filler(k))
    const tail = ' değeri {f:e2}.'
    lines.push(`Gömülü ${'y'.repeat(450 - 'Gömülü '.length - tail.length)}${tail}`)
    lines.push('Sınırın ötesinde {f:e1}.')
    const tpl = {
      lang: 'tr',
      title: '{kanal}',
      sections: [
        { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines },
        { id: 'e', kind: 'metrics', title: 'Gömülü', host: 'exec', fields: [
          { id: 'e1', label: 'Gömülü adet', type: 'number' },
          { id: 'e2', label: 'Gömülü iki', type: 'number' },
          { id: 'e-note', label: 'Gömülü açıklama', type: 'textarea' },
        ] },
      ],
    }
    const got = summaryBlocks(tpl, tpl.sections[0], { e1: 7, e2: 8, 'e-note': 'Not' }, {}, 'Mobil', 2026, 9)
    expect(got).toHaveLength(3)
    expect(got[0].kind).toBe('text')
    expect(got[0].text.startsWith('Dolgu 0 ')).toBe(true)
    expect(got[0].text.endsWith('…')).toBe(true)
    expect(got[0].text).not.toContain('**8**')
    expect(got[0].text.length).toBeLessThanOrEqual(MAX_RESOLVED_TEXT)
    expect(got[1]).toEqual({ kind: 'note', section: 'e', field: 'e-note', label: 'Gömülü açıklama', text: 'Not' })
    expect(got[2]).toEqual({ kind: 'text', text: 'Gömülü adet: **7**\nGömülü iki: **8**' })
  })
})
