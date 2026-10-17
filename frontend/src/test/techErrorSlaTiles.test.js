import { describe, it, expect } from 'vitest'
import {
  summaryTiles, summaryLineStatus, summaryLineIssues, summaryLinePreview, templateErrors, templateDiff, canonicalTemplate,
  canonicalValues, normalizeValues, revertInvalidValues, reportErrorSection, calcResults, applyCalc, calcResult, calcRefIssues,
  calcShapeError, CALC_KINDS, BETTER, LIMITS, pruneRemovedTiles, tileSectionsUsing,
} from '../components/techerror/techErrorModel.js'
import { javaStringTable, readJavaMain, readJavaTest } from './helpers/javaTable.js'

// Renkli kutucuklar + SLA hedefi (2026-10-17, 4b): sunucudaki TechErrorTiles.build ile AYNI doğruluk tablosu
// (TechErrorTilesTest.TILES_TABLE, Java kaynağından okunur) ve istemci modelinin hedef / yön / kutucuk / karşılaştırma kuralları.
const JAVA = readJavaTest('service/techerror/TechErrorTilesTest.java')

/** Java testindeki template() ile elle aynı. */
const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['{kanal}: {f:rate}.'], tiles: ['rate', 'total', 'count', 'sla', 'note', 'q-count'] },
    { id: 'm', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'rate', label: 'Teknik hata oranı', type: 'percent' },
      { id: 'total', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'count', label: 'Teknik hata adedi', type: 'number', better: 'lower' },
      { id: 'sla', label: 'SLA oranı', type: 'percent', target: 99.95, better: 'higher' },
      { id: 'note', label: 'Not', type: 'textarea' },
      { id: 'q-flag', label: 'Kota aşımı oldu mu?', type: 'yesno' },
      { id: 'q-count', label: 'Kota hatası adedi', type: 'number', skip_if_no: 'q-flag' },
    ] },
  ],
}
const FIELDS = new Map(TPL.sections[1].fields.map((f) => [f.id, f]))

function values(spec) {
  if (spec === '-') return null
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

const show = (x) => [x.id, x.value, x.delta ?? '-', x.dir ?? '-', x.tone, x.target ?? '-', x.met == null ? '-' : String(x.met)].join('|')

describe('renkli kutucuklar — Java ile ortak doğruluk tablosu', () => {
  it('TILES_TABLE satırları Java ile aynı kutucukları verir (değer, fark + yön, hedef + tutturuldu, ton; muaf / uzun açıklama çizilmez)', () => {
    const rows = javaStringTable(JAVA, 'TILES_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(4)
    for (const [name, fields, prev, targets, ...expected] of rows) {
      const tiles = summaryTiles(TPL, TPL.sections[0], values(fields), values(targets) || {}, values(prev), 'Mobil', 2026, 9)
      expect(tiles.map(show), name).toEqual(expected)
      for (const x of tiles) expect(x.label, `${name} ${x.id}`).toBeTruthy()
    }
  })

  it('şablonda olmayan kimlik, sayı / yüzde olmayan alan ve kutucuksuz özet; etiketin yer tutucusu çözülür; en çok 6', () => {
    const ghost = { lang: 'tr', title: '{kanal}', sections: [
      { id: 'exec', kind: 'summary', title: 'Özet', lines: ['x'], tiles: ['ghost', 'flag', 'rate'] },
      { id: 'm', kind: 'metrics', title: 'V', fields: [
        { id: 'rate', label: '{kanal} oranı', type: 'percent' }, { id: 'flag', label: 'Evet mi?', type: 'yesno' }] }] }
    const tiles = summaryTiles(ghost, ghost.sections[0], { rate: 1.5, flag: true }, {}, null, 'Mobil', 2026, 1)
    expect(tiles.map((x) => x.id)).toEqual(['rate'])
    expect(tiles[0].label).toBe('Mobil oranı')
    expect(summaryTiles(TPL, TPL.sections[1], {}, {}, null, 'K', 2026, 1)).toEqual([])
    expect(summaryTiles(TPL, { ...TPL.sections[0], tiles: [] }, {}, {}, null, 'K', 2026, 1)).toEqual([])
    const many = { ...TPL, sections: [{ ...TPL.sections[0], tiles: ['rate', 'total', 'count', 'sla', 'q-count', 'rate', 'total', 'count'] }, TPL.sections[1]] }
    expect(summaryTiles(many, many.sections[0], {}, {}, null, 'K', 2026, 1)).toHaveLength(LIMITS.tiles)
    // Sınır Java kaynağından (TechErrorTemplate.MAX_TILES) — iki taraf ayrışamaz
    const javaTpl = readJavaMain('service/techerror/TechErrorTemplate.java')
    expect(LIMITS.tiles).toBe(Number(/MAX_TILES = (\d+);/.exec(javaTpl)[1]))
  })

  it('inceleme 2026-10-17: yönsüz (none) alanın hedefi karşılaştırılmaz — kutucuk nötr (hedef yazılır, tutturuldu yok), compare BAD_REF', () => {
    const tpl = { lang: 'tr', title: '{kanal}', sections: [
      { id: 'exec', kind: 'summary', title: 'Özet', lines: ['x'], tiles: ['r'] },
      { id: 'm', kind: 'metrics', title: 'V', fields: [
        { id: 'r', label: 'Oran', type: 'percent', target: 1, better: 'none' },
        { id: 'q', label: 'Hedefte mi?', type: 'yesno', calc: { kind: 'compare', of: 'r' } }] }] }
    const [tile] = summaryTiles(tpl, tpl.sections[0], { r: 2 }, {}, { r: 1 }, 'K', 2026, 1)
    expect(tile).toMatchObject({ tone: 'neutral', met: null, target: '%1', delta: '+1 puan' })
    const byId = new Map(tpl.sections[1].fields.map((f) => [f.id, f]))
    expect(calcResult(tpl.sections[1].fields[1], byId, { r: 2 }, null).status).toBe('BAD_REF')
    expect(templateErrors(tpl)['target:r']).toBe('te.err.targetBetter')
  })

  it('pruneRemovedTiles / tileSectionsUsing: silinen alan kutucuklardan düşer (boş liste → anahtar silinir), sarkan eski kimliğe dokunulmaz, değişiklik yoksa aynı nesne', () => {
    expect(tileSectionsUsing(TPL, 'rate').map((s) => s.id)).toEqual(['exec'])
    expect(tileSectionsUsing(TPL, 'q-flag')).toEqual([])
    const without = (ids) => ({ ...TPL, sections: [TPL.sections[0], { ...TPL.sections[1], fields: TPL.sections[1].fields.filter((f) => !ids.includes(f.id)) }] })
    const pruned = pruneRemovedTiles(TPL, without(['rate', 'count']))
    expect(pruned.sections[0].tiles).toEqual(['total', 'sla', 'note', 'q-count'])
    // Önceden sarkan kimlik ('ghost') bu değişiklikle gitmedi → kalır; hiç alan gitmediyse aynı nesne
    const ghost = { ...TPL, sections: [{ ...TPL.sections[0], tiles: ['ghost', 'rate'] }, TPL.sections[1]] }
    const ghostNext = { ...ghost, sections: [ghost.sections[0], without(['rate']).sections[1]] }
    expect(pruneRemovedTiles(ghost, ghostNext).sections[0].tiles).toEqual(['ghost'])
    const same = { ...TPL }
    expect(pruneRemovedTiles(TPL, same)).toBe(same)
    // Hepsi gidince anahtar silinir (sunucu boş listeyi yazmaz)
    const one = { ...TPL, sections: [{ ...TPL.sections[0], tiles: ['rate'] }, TPL.sections[1]] }
    const oneNext = { ...one, sections: [one.sections[0], without(['rate']).sections[1]] }
    expect(pruneRemovedTiles(one, oneNext).sections[0]).not.toHaveProperty('tiles')
  })
})

describe('şablon kuralları — hedef, yön, kutucuk, karşılaştırma (sunucu ile aynı)', () => {
  const withField = (patch, id = 'sla') => ({ ...TPL, sections: [TPL.sections[0],
    { ...TPL.sections[1], fields: TPL.sections[1].fields.map((f) => (f.id === id ? { ...f, ...patch } : f)) }] })
  const withTiles = (tiles) => ({ ...TPL, sections: [{ ...TPL.sections[0], tiles }, TPL.sections[1]] })

  it('templateErrors: hedef yalnız yüzde / 0–100 / yönlü; yön sayı-yüzde ve bilinen; kutucuk ≤ 6; karşılaştırma yalnız evet/hayır', () => {
    expect(templateErrors(TPL)).toEqual({})
    expect(templateErrors(withField({ target: 50 }, 'total'))['target:total']).toBe('te.err.targetType')
    expect(templateErrors(withField({ target: 101 }))['target:sla']).toBe('te.err.targetRange')
    expect(templateErrors(withField({ target: -1 }))['target:sla']).toBe('te.err.targetRange')
    expect(templateErrors(withField({ target: 'çok' }))['target:sla']).toBe('te.err.targetRange')
    expect(templateErrors(withField({ better: undefined }))['target:sla']).toBe('te.err.targetBetter')
    expect(templateErrors(withField({ better: 'up' }))['target:sla']).toBe('te.err.betterKind')
    expect(templateErrors(withField({ better: 'higher' }, 'q-flag'))['target:q-flag']).toBe('te.err.betterKind')
    expect(templateErrors(withField({ better: 'lower' }, 'total'))).toEqual({})
    expect(templateErrors(withTiles(['a', 'b', 'c', 'd', 'e', 'f', 'g']))['section:exec']).toBe('te.err.tooManyTiles')
    expect(templateErrors(withTiles(['a', 'b', 'c', 'd', 'e', 'f']))).toEqual({})
    expect(CALC_KINDS).toContain('compare')
    expect(BETTER).toEqual(['lower', 'higher', 'none'])
    expect(calcShapeError({ id: 'q', type: 'yesno', calc: { kind: 'compare', of: 'sla' } })).toBeNull()
    expect(calcShapeError({ id: 'q', type: 'percent', calc: { kind: 'compare', of: 'sla' } })).toBe('te.err.calcType')
    expect(calcShapeError({ id: 'q', type: 'yesno', calc: { kind: 'compare' } })).toBe('te.err.calcRequired')
    expect(calcShapeError({ id: 'q', type: 'yesno', calc: { kind: 'compare', of: 'q' } })).toBe('te.err.calcSelf')
  })

  it('canonicalTemplate: hedef sayı olarak, boş kutucuk listesi ve boş hedef yazılmaz (sunucu ile aynı belge)', () => {
    const c = canonicalTemplate(withField({ target: '99.95' }))        // metin hedef (sayıya çevrilir; virgül girişi NumberInput'ta çözülür)
    expect(c.sections[1].fields.find((f) => f.id === 'sla').target).toBe(99.95)
    const noTiles = canonicalTemplate(withTiles([]))
    expect(noTiles.sections[0]).not.toHaveProperty('tiles')
    expect(canonicalTemplate(TPL).sections[0].tiles).toEqual(TPL.sections[0].tiles)
    const noTarget = canonicalTemplate(withField({ target: '' }))
    expect(noTarget.sections[1].fields.find((f) => f.id === 'sla')).not.toHaveProperty('target')
  })

  it('calcRefIssues: karşılaştırma hedefli yüzde ister (olmayan / yüzde olmayan / hedefsiz uyarılır; hesaplanan yüzde olabilir)', () => {
    const tpl = { ...TPL, sections: [TPL.sections[0], { ...TPL.sections[1], fields: [...TPL.sections[1].fields,
      { id: 'r2', label: 'Oran 2', type: 'percent', target: 1, better: 'lower', calc: { kind: 'ratio', num: 'count', den: 'total' } }] }] }
    const q = (of) => ({ id: 'q', type: 'yesno', calc: { kind: 'compare', of } })
    expect(calcRefIssues(tpl, q('sla'))).toEqual([])
    expect(calcRefIssues(tpl, q('r2'))).toEqual([])
    expect(calcRefIssues(tpl, q('rate')).map((x) => x.status)).toEqual(['noTarget'])
    expect(calcRefIssues(tpl, q('total')).map((x) => x.status)).toEqual(['notPercent'])
    expect(calcRefIssues(tpl, q('yok')).map((x) => x.status)).toEqual(['missing'])
  })

  it('templateDiff: hedef, yön ve kutucuk değişiklikleri ayrı satırlar', () => {
    const next = { ...withField({ target: 99.9, better: 'lower' }), sections: [{ ...TPL.sections[0], tiles: ['rate', 'sla'] },
      withField({ target: 99.9, better: 'lower' }).sections[1]] }
    const d = templateDiff(TPL, next)
    expect(d).toContainEqual(expect.objectContaining({ group: 'field', op: 'target', before: 99.95, after: 99.9, label: 'SLA oranı' }))
    expect(d).toContainEqual(expect.objectContaining({ group: 'field', op: 'better', before: 'higher', after: 'lower' }))
    expect(d).toContainEqual(expect.objectContaining({ group: 'summary', op: 'tiles', before: 6, after: 2 }))
    expect(templateDiff(TPL, { ...TPL })).toEqual([])
  })
})

describe('rapor değerleri — o aya özel hedef', () => {
  it('normalizeValues / canonicalValues: hedef yalnız şablonda hedefi olan yüzde alanında, boş yazılmaz; revert ve bölüm eşlemesi', () => {
    const v = normalizeValues({ fields: { sla: 99.9 }, targets: { sla: 99.8, rate: 1, total: 5, ghost: 2 } })
    expect(v.targets).toEqual({ sla: 99.8, rate: 1, total: 5, ghost: 2 })      // taslak her anahtarı taşır
    expect(canonicalValues(v, TPL).targets).toEqual({ sla: 99.8 })               // kayda yalnız hedefli yüzde
    expect(canonicalValues(normalizeValues({ fields: { sla: 99.9 } }), TPL)).not.toHaveProperty('targets')
    expect(canonicalValues(normalizeValues({ fields: {}, targets: { sla: '' } }), TPL)).not.toHaveProperty('targets')
    expect(canonicalValues(normalizeValues({ fields: {}, targets: { sla: ' ' } }), TPL)).not.toHaveProperty('targets')
    // Geçersiz yazılmış hedef otomatik kayıtta kayıtlı değerinde gider
    const base = { fields: {}, targets: { sla: 99.5 } }
    const draft = { fields: { sla: 99.9 }, targets: { sla: 'abc' } }
    expect(revertInvalidValues(draft, base, ['targets.sla']).targets).toEqual({ sla: 99.5 })
    expect(revertInvalidValues(draft, { fields: {} }, ['targets.sla']).targets).toEqual({})
    expect(revertInvalidValues(draft, base, [])).toBe(draft)
    expect(reportErrorSection('targets.sla', TPL)).toBe('m')
    expect(reportErrorSection('values.sla', TPL)).toBe('m')
  })

  it('calcResults: karşılaştırma raporun hedefini okur ve hesaplanmış yüzdeyi görür (iki geçiş); applyCalc hedefi taşır', () => {
    const tpl = { ...TPL, sections: [TPL.sections[0], { ...TPL.sections[1], fields: [...TPL.sections[1].fields,
      { id: 'r2', label: 'Oran 2', type: 'percent', target: 1, better: 'lower', calc: { kind: 'ratio', num: 'count', den: 'total' } },
      { id: 'ok', label: 'Hedefte mi?', type: 'yesno', calc: { kind: 'compare', of: 'r2' } },
      { id: 'sla-ok', label: 'SLA hedefte mi?', type: 'yesno', calc: { kind: 'compare', of: 'sla' } }] }] }
    const fields = { count: 5, total: 1000, sla: 99.9, ok: false, 'sla-ok': true }
    const r = calcResults(tpl, fields, null)
    expect(r.r2).toEqual({ status: 'OK', value: 0.5, missing: [] })
    expect(r.ok).toEqual({ status: 'OK', value: true, missing: [] })             // %0,5 ≤ hedef %1 (düşük iyi)
    expect(r['sla-ok']).toEqual({ status: 'OK', value: false, missing: [] })     // %99,9 < şablon hedefi %99,95
    expect(calcResults(tpl, fields, null, { sla: 99.8 })['sla-ok'].value).toBe(true)   // raporun hedefi %99,8 → tutturuldu
    const shown = applyCalc(tpl, fields, null, null, { sla: 99.8 })
    expect(shown).toMatchObject({ r2: 0.5, ok: true, 'sla-ok': true })
    // Oran hesaplanamazsa (bölen yok) karşılaştırma da boş — girilen cevap kalmaz
    const miss = calcResults(tpl, { count: 5, ok: true }, null)
    expect(miss.r2.status).toBe('MISSING')
    expect(miss.ok).toEqual({ status: 'MISSING', value: null, missing: ['r2'] })
    expect(applyCalc(tpl, { count: 5, ok: true }, null)).not.toHaveProperty('ok')
    // Tek alan: hedefsiz yüzde / sayı / olmayan alan → formül kullanılamaz
    const byId = new Map(tpl.sections[1].fields.map((f) => [f.id, f]))
    expect(calcResult({ id: 'q', type: 'yesno', calc: { kind: 'compare', of: 'rate' } }, byId, { rate: 1 }, null).status).toBe('BAD_REF')
    expect(calcResult({ id: 'q', type: 'yesno', calc: { kind: 'compare', of: 'total' } }, byId, { total: 1 }, null).status).toBe('BAD_REF')
    expect(calcResult({ id: 'q', type: 'yesno', calc: { kind: 'compare', of: 'sla' } }, byId, {}, null)).toEqual({ status: 'MISSING', value: null, missing: ['sla'] })
  })
})

describe('özet cümle durumu ve önizleme — fark / hedef başvuruları', () => {
  const tpl = (line) => ({ ...TPL, sections: [{ ...TPL.sections[0], lines: [line] }, TPL.sections[1]] })

  it('summaryLineStatus: fark geçen ayın raporunu bekler (missing + needsPrevious); hedef bu ayın değerini istemez; sayı dışı fark ve hedefsiz hedef bozuk', () => {
    const t1 = tpl('Hata {d:count}.')
    expect(summaryLineStatus(t1.sections[0], t1, { count: 5 }, null)[0]).toMatchObject({ state: 'missing', needsPrevious: true, missing: [] })
    expect(summaryLineStatus(t1.sections[0], t1, { count: 5 }, { count: 4 })[0].state).toBe('ok')
    expect(summaryLineStatus(t1.sections[0], t1, {}, { count: 4 })[0]).toMatchObject({ state: 'missing', needsPrevious: false })
    expect(summaryLineStatus(t1.sections[0], t1, {}, { count: 4 })[0].missing.map((f) => f.id)).toEqual(['count'])
    const t2 = tpl('Hedef {t:sla}.')
    expect(summaryLineStatus(t2.sections[0], t2, {}, null)[0].state).toBe('ok')
    const t3 = tpl('Fark {dt:sla}.')
    expect(summaryLineStatus(t3.sections[0], t3, {}, null)[0]).toMatchObject({ state: 'missing' })
    expect(summaryLineStatus(t3.sections[0], t3, { sla: 99.9 }, null)[0].state).toBe('ok')
    const t4 = tpl('Hedef {t:rate}.')
    expect(summaryLineStatus(t4.sections[0], t4, {}, null)[0]).toMatchObject({ state: 'broken', reason: 'noTarget' })
    // Rapor hedefi yalnız ŞABLONDA hedefi olan alanda geçer (sunucu ile aynı): bayat bir yedek hedefi cümleyi canlandırmaz
    expect(summaryLineStatus(t4.sections[0], t4, {}, null, { rate: 1 })[0].state).toBe('broken')
    const t5 = tpl('Not {d:note}.')
    expect(summaryLineStatus(t5.sections[0], t5, {}, null)[0]).toMatchObject({ state: 'broken', reason: 'textarea' })
    const t6 = tpl('Bayrak {d:q-flag}.')
    expect(summaryLineStatus(t6.sections[0], t6, {}, null)[0]).toMatchObject({ state: 'broken', reason: 'notNumeric' })
    // Muaf alanın farkı: cümle muaf
    expect(summaryLineStatus(tpl('Kota {d:q-count}.').sections[0], tpl('x'), { 'q-flag': false }, { 'q-count': 3 })[0].state).toBe('exempt')
  })

  it('summaryLineIssues ve summaryLinePreview: tür etiketi önizlemede, uyarılar şablon düzenleyicide', () => {
    expect(summaryLineIssues('Hedef {t:rate} ve {d:q-flag} ve {d:count}', TPL).map((x) => [x.kind, x.id]))
      .toEqual([['noTarget', 'rate'], ['notNumeric', 'q-flag']])
    expect(summaryLineIssues('{t:sla} {dt:sla} {d:rate}', TPL)).toEqual([])
    const decorate = (kind, label) => `${label} · ${kind}`
    expect(summaryLinePreview('A {d:count} B {t:sla} C {dt:sla} D {f:rate} E {f:q-flag?evet|hayır}', TPL, (x) => x, '?', (x) => x, decorate))
      .toBe('A [Teknik hata adedi · d] B [SLA oranı · t] C [SLA oranı · dt] D [Teknik hata oranı] E [evet / hayır]')
  })
})
