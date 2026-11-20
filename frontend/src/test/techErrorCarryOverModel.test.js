import { describe, it, expect } from 'vitest'
import {
  CARRY_REASONS, CARRY_TOPIC_KEYS, applyCarryOver, carryOverflow, carrySource, carryTopicIds, carryTopicPart, planCarryOver,
  planHasItems, textSectionEmpty, topicKey,
} from '../components/techerror/carryOverModel.js'
import { LIMITS } from '../components/techerror/techErrorModel.js'

// "Geçen aydan getir" (2026-11-20, C1): yalnız BOŞ metin / not alanları dolar (yazılmış metin ASLA ezilmez), konular yeni
// kimlikle ve yalnız ad / başlık / açıklama / kesinti / etki cevaplarıyla gelir; sayılar, tarihler, görseller, tablo, özet,
// hedef, birim hiç gelmez.

const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  ask_time: true, ask_outage: true, ask_impact: true, allow_images: true,
}
const TPL = {
  lang: 'tr',
  sections: [
    { id: 'exec-summary', kind: 'summary', title: 'Yönetici Özeti', lines: ['Toplam {f:cnt} işlem.'] },
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'had-outage', type: 'yesno', label: 'Dönemde kesinti oldu mu?' },
      { id: 'cnt', type: 'number', label: 'Toplam işlem adedi' },
      { id: 'note', type: 'textarea', label: 'Map yapılmamış hatalar hakkında açıklama' },
      { id: 'outage-note', type: 'textarea', label: 'Kesinti notu', skip_if_no: 'had-outage' },
      { id: 'short', type: 'text', label: 'Kısa not' },
    ] },
    ISSUES,
    { id: 'trend-note', kind: 'text', title: 'Grafikler hakkında', text: '{kanal} kanalının {ay} {yil} grafikleri aşağıdadır.' },
    { id: 'tbl', kind: 'table', title: 'SLA', columns: [{ id: 'c1', label: 'Oran', type: 'percent' }], rows: [{ id: 'r1', label: 'Mobil' }] },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [{ id: 'monthly-tx', label: 'Aylık', width: 'half' }] },
  ],
}
const topic = (id, name, extra = {}) => ({ id, topic: name, title: '', title_auto: true, description: '', ...extra })
const FULL_TOPIC = topic('p1', 'Kart limiti', {
  description: 'Limit sorunu yaşandı.', errors: 1250, customers: 40, date: '2026-08-03', start: '10:00', end: '11:30',
  end_next_day: false, outage: true, outage_id: 'INC-2026-0815', impact: true, share: 3.4, captions: { 1: 'Grafik' },
})

const prev = (values, template = TPL) => ({ template, year: 2026, month: 8, values })
const cur = (values, template = TPL) => ({ template, year: 2026, month: 9, values })
const PREV_VALUES = {
  fields: { cnt: 1000, note: 'Geçen ayın map notu', 'outage-note': 'Kesinti 2 saat sürdü', short: 'kısa', 'had-outage': true },
  texts: { 'trend-note': 'Elle yazılmış grafik notu', 'exec-summary': 'Elle düzenlenmiş özet' },
  issues: { issues: [FULL_TOPIC, topic('p2', '', {}), topic('p3', 'Fatura', { outage: false, outage_id: 'ESKI', impact: false })] },
  tables: { tbl: { r1: { c1: 99.9 } } },
  targets: { x: 1 },
  units: { cnt: 3 },
}

describe('carrySource', () => {
  const ROWS = [
    // [yanıt, beklenen]
    [null, { state: 'unknown' }],
    [{}, { state: 'unknown' }],
    [{ previous: null }, { state: 'unknown' }],                         // eski sunucu: neighbors yok → "yok" denmez
    [{ previous: null, neighbors: { prev: null, next: null } }, { state: 'none' }],
    [{ previous: { id: 90, year: 2026, month: 8 } }, { state: 'ok', id: 90, year: 2026, month: 8, exact: true }],
    [{ previous: null, neighbors: { prev: { id: 70, year: 2026, month: 6 } } }, { state: 'ok', id: 70, year: 2026, month: 6, exact: false }],
    [{ previous: { id: 90, year: 2026, month: 8 }, neighbors: { prev: { id: 70 } } }, { state: 'ok', id: 90, year: 2026, month: 8, exact: true }],
  ]
  it.each(ROWS)('%j → %j', (data, want) => {
    expect(carrySource(data)).toEqual(want)
  })
})

describe('textSectionEmpty', () => {
  it.each([
    [undefined, 'Varsayılan', true],
    [null, 'Varsayılan', true],
    // KAYITLI boş metin bilerek boşaltılmış bölümdür (PDF / e-postada basılmaz) — boş SAYILMAZ (inceleme 2026-11-20)
    ['', 'Varsayılan', false],
    ['   \n ', 'Varsayılan', false],
    ['Varsayılan', 'Varsayılan', true],                // şablonun varsayılan metni = boş
    ['Varsayılan.', 'Varsayılan', false],
    ['Elle yazdım', null, false],
  ])('%j (varsayılan %j) → %s', (stored, def, want) => {
    expect(textSectionEmpty(stored, def)).toBe(want)
  })
})

describe('carryTopicPart', () => {
  it('yalnız izinli alanlar taşınır — adet, tarih, saat, pay, açıklama görseli ASLA', () => {
    const part = carryTopicPart(FULL_TOPIC)
    expect(Object.keys(part).sort()).toEqual(['description', 'impact', 'outage', 'outage_id', 'title', 'title_auto', 'topic'])
    for (const k of Object.keys(part)) expect(CARRY_TOPIC_KEYS).toContain(k)
    for (const k of ['errors', 'customers', 'date', 'start', 'end', 'end_next_day', 'share', 'captions', 'id']) expect(part).not.toHaveProperty(k)
  })
  it('kesinti Hayır ise Kesinti ID taşınmaz; tür dışı değerler atılır', () => {
    expect(carryTopicPart({ topic: 'X', outage: false, outage_id: 'ESKI', impact: 'evet', title_auto: 'x' }))
      .toEqual({ topic: 'X', outage: false })
    expect(carryTopicPart(null)).toEqual({})
  })
})

describe('planCarryOver', () => {
  it('boş alanlara getirilir; dolu alan "filled" ile listelenir, ASLA ezilmez', () => {
    const plan = planCarryOver({
      prev: prev(PREV_VALUES),
      cur: cur({ fields: { short: 'bu ay yazdım', note: '  ' }, texts: {}, issues: {} }),
      channelName: 'Mobil',
    })
    expect(plan.texts).toEqual([{ id: 'trend-note', label: 'Grafikler hakkında', value: 'Elle yazılmış grafik notu' }])
    // note: bu ay yalnız boşluk → boş sayılır; short: yazılmış → atlanır; outage-note: bu ay kesinti cevabı yok → muaf değil
    expect(plan.fields.map((f) => f.id)).toEqual(['note', 'outage-note'])
    expect(plan.skipped).toEqual([{ kind: 'field', id: 'short', label: 'Kısa not', reason: 'filled' }])
  })

  it('sayılar, özet metni (elle), tablo, hedef ve birim plana HİÇ girmez', () => {
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}) })
    const ids = [...plan.texts, ...plan.fields].map((x) => x.id)
    expect(ids).not.toContain('cnt')
    expect(ids).not.toContain('had-outage')
    expect(ids).not.toContain('exec-summary')
    expect(ids).not.toContain('tbl')
    // tablo hücresi, Hayır cevaplı konunun eski Kesinti ID'si, konu adedi ve tarihi plana girmez
    expect(JSON.stringify(plan)).not.toMatch(/99\.9|"ESKI"|1250|2026-08-03|10:00/)
  })

  it('geçen ayın VARSAYILAN metni (kimse yazmamış) getirilmez; bu ayın varsayılan metni boş sayılır', () => {
    const def = 'Mobil kanalının Ağustos 2026 grafikleri aşağıdadır.'
    expect(planCarryOver({ prev: prev({ texts: { 'trend-note': def } }), cur: cur({}), channelName: 'Mobil' }).texts).toEqual([])
    const plan = planCarryOver({
      prev: prev({ texts: { 'trend-note': 'Elle' } }),
      cur: cur({ texts: { 'trend-note': 'Mobil kanalının Eylül 2026 grafikleri aşağıdadır.' } }),
      channelName: 'Mobil',
    })
    expect(plan.texts.map((x) => x.id)).toEqual(['trend-note'])
  })

  it('muaf alan (bağlı soru bu ay Hayır) getirilmez ve nedeni söylenir', () => {
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({ fields: { 'had-outage': false } }) })
    expect(plan.fields.map((f) => f.id)).toEqual(['note', 'short'])
    expect(plan.skipped).toContainEqual({ kind: 'field', id: 'outage-note', label: 'Kesinti notu', reason: 'exempt' })
  })

  it('aynı kimlik + aynı tür şartı: tür farklı → typeMismatch, yoksa → notInTemplate', () => {
    const curTpl = { ...TPL, sections: TPL.sections.map((s) => {
      if (s.id === 'summary') return { ...s, fields: s.fields.filter((f) => f.id !== 'short').map((f) => (f.id === 'note' ? { ...f, type: 'text' } : f)) }
      if (s.id === 'trend-note') return { ...s, kind: 'images', slots: [] }
      return s
    }) }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}, curTpl) })
    expect(plan.texts).toEqual([])
    expect(plan.fields.map((f) => f.id)).toEqual(['outage-note'])
    const reasons = Object.fromEntries(plan.skipped.map((x) => [x.id, x.reason]))
    expect(reasons).toEqual({ 'trend-note': 'typeMismatch', note: 'typeMismatch', short: 'notInTemplate' })
    for (const r of Object.values(reasons)) expect(CARRY_REASONS).toContain(r)
  })

  it('konular: içeriksiz konu atlanır; hedef aynı kimlikli bölüm, yoksa TEK konu bölümü, o da yoksa noSection', () => {
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}) })
    expect(plan.topics.map((x) => [x.key, x.targetSectionId, x.status])).toEqual([
      ['issues:p1', 'issues', 'ok'], ['issues:p3', 'issues', 'ok'],
    ])
    expect(plan.topics[0].name).toBe('Kart limiti: Hata Artışı')
    const other = { ...TPL, sections: TPL.sections.map((s) => (s.id === 'issues' ? { ...s, id: 'anomali' } : s)) }
    expect(planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}, other) }).topics[0].targetSectionId).toBe('anomali')
    const none = { ...TPL, sections: TPL.sections.filter((s) => s.kind !== 'issues') }
    const p3 = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}, none) })
    expect(p3.topics.every((x) => x.status === 'skip' && x.reason === 'noSection')).toBe(true)
    expect(planHasItems({ texts: [], fields: [], topics: p3.topics })).toBe(false)
  })

  it('hiçbir şey yoksa planHasItems false', () => {
    expect(planHasItems(planCarryOver({ prev: prev({}), cur: cur({}) }))).toBe(false)
    expect(planHasItems(null)).toBe(false)
  })
})

describe('applyCarryOver', () => {
  const allPicks = (plan) => ({
    texts: new Set(plan.texts.map((x) => x.id)), fields: new Set(plan.fields.map((x) => x.id)), topics: new Set(plan.topics.map((x) => x.key)),
  })

  it('seçilenleri taslağa yazar; konular yeni kimlikle, yalnız izinli alanlarla gelir', () => {
    const values = { fields: { cnt: 5 }, texts: {}, issues: { issues: [topic('k1', 'Bu ayın konusu')] }, tables: {}, targets: { a: 1 } }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(values) })
    const picks = allPicks(plan)
    const ids = carryTopicIds(values, plan, picks.topics)
    const r = applyCarryOver(values, plan, picks, { template: TPL, ids, year: 2026, month: 9 })
    expect(r.counts).toEqual({ texts: 1, fields: 3, topics: 2, notFit: 0, stale: 0 })
    expect(r.values.fields).toEqual({ cnt: 5, note: 'Geçen ayın map notu', 'outage-note': 'Kesinti 2 saat sürdü', short: 'kısa' })
    expect(r.values.texts).toEqual({ 'trend-note': 'Elle yazılmış grafik notu' })
    expect(r.values.tables).toBe(values.tables)
    expect(r.values.targets).toBe(values.targets)
    const list = r.values.issues.issues
    expect(list).toHaveLength(3)
    expect(list[0].id).toBe('k1')
    const added = list[1]
    expect(added.id).not.toBe('p1')
    expect(added.id).toBe(ids.get('issues:p1'))
    expect(added).toMatchObject({ topic: 'Kart limiti', description: 'Limit sorunu yaşandı.', outage: true, outage_id: 'INC-2026-0815',
      impact: true, title_auto: true })
    // ASLA: adet, tarih, saat, pay, görsel açıklaması
    expect(added.errors).toBeNull()
    expect(added.customers).toBeNull()
    for (const k of ['date', 'start', 'end', 'end_next_day', 'share', 'captions']) expect(added).not.toHaveProperty(k)
    expect(r.added.map((a) => a.issueId)).toEqual([ids.get('issues:p1'), ids.get('issues:p3')])
  })

  it('YAZILMIŞ METİN ASLA EZİLMEZ: plan ile uygulama arasında yazılan alan "stale" sayılır', () => {
    const before = { fields: {}, texts: {}, issues: {} }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(before) })
    const picks = allPicks(plan)
    // Pencere açıkken kullanıcı yazdı
    const typed = { fields: { note: 'Bu ay yazdığım not' }, texts: { 'trend-note': 'Bu ay yazdığım grafik notu' }, issues: {} }
    const r = applyCarryOver(typed, plan, picks, { template: TPL, ids: carryTopicIds(typed, plan, picks.topics), year: 2026, month: 9 })
    expect(r.values.fields.note).toBe('Bu ay yazdığım not')
    expect(r.values.texts['trend-note']).toBe('Bu ay yazdığım grafik notu')
    expect(r.counts.stale).toBe(2)
    expect(r.counts.fields).toBe(2)                     // outage-note ve short boştu
  })

  it('seçilmeyen getirilmez; hiçbir şey değişmezse AYNI values nesnesi döner', () => {
    const values = { fields: {}, texts: {}, issues: {} }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(values) })
    const r = applyCarryOver(values, plan, { texts: new Set(), fields: new Set(), topics: new Set() }, { template: TPL, ids: new Map() })
    expect(r.values).toBe(values)
    expect(r.counts).toEqual({ texts: 0, fields: 0, topics: 0, notFit: 0, stale: 0 })
  })

  it('bölüm sınırı (30): sığmayan konu eklenmez ve sayılır; carryOverflow önceden söyler', () => {
    const full = Array.from({ length: LIMITS.issues - 1 }, (_, i) => topic(`k${i}`, `Konu ${i}`))
    const values = { fields: {}, texts: {}, issues: { issues: full } }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(values) })
    const picks = allPicks(plan)
    expect(carryOverflow(values, plan, picks.topics)).toBe(1)
    const r = applyCarryOver(values, plan, picks, { template: TPL, ids: carryTopicIds(values, plan, picks.topics) })
    expect(r.values.issues.issues).toHaveLength(LIMITS.issues)
    expect(r.counts).toMatchObject({ topics: 1, notFit: 1 })
  })

  it('bölüm soruları sormuyorsa (conformIssue) kesinti / etki cevapları düşer', () => {
    const plain = { ...TPL, sections: TPL.sections.map((s) => (s.id === 'issues' ? { id: 'issues', kind: 'issues', title: 'Konular' } : s)) }
    const values = { fields: {}, texts: {}, issues: {} }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(values, plain) })
    const picks = allPicks(plan)
    const r = applyCarryOver(values, plan, picks, { template: plain, ids: carryTopicIds(values, plan, picks.topics) })
    for (const i of r.values.issues.issues) {
      expect(i).not.toHaveProperty('outage')
      expect(i).not.toHaveProperty('outage_id')
      expect(i).not.toHaveProperty('impact')
    }
  })

  it('carryTopicIds: mevcut kimliklerle çakışmayan, birbirinden farklı yeni kimlikler', () => {
    const values = { issues: { issues: [topic('p1', 'Aynı kimlik'), topic('p3', 'Aynı kimlik 2')] } }
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(values) })
    const ids = carryTopicIds(values, plan, new Set(plan.topics.map((x) => x.key)))
    const fresh = [...ids.values()]
    expect(new Set(fresh).size).toBe(fresh.length)
    for (const id of fresh) expect(['p1', 'p3']).not.toContain(id)
  })
})

describe('inceleme düzeltmeleri (2026-11-20)', () => {
  it('bilerek boşaltılmış metin bölümü önerilmez — nedeni "emptied"', () => {
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({ texts: { 'trend-note': '' } }), channelName: 'Mobil' })
    expect(plan.texts).toEqual([])
    expect(plan.skipped).toContainEqual({ kind: 'text', id: 'trend-note', label: 'Grafikler hakkında', reason: 'emptied' })
    expect(CARRY_REASONS).toContain('emptied')
  })

  it.each([
    [{ topic: 'Kart limiti', description: 'Limit aşımı.' }, { topic: 'KART LİMİTİ', description: '  limit   aşımı. ' }, true],
    [{ topic: 'Kart limiti', description: 'Limit aşımı.' }, { topic: 'Kart limiti', description: 'Başka açıklama' }, false],
    [{ topic: 'İşlem', description: '' }, { topic: 'islem', description: '' }, true],
    [{ topic: '', description: '', title_auto: false, title: 'Elle Başlık' }, { topic: '', description: '', title_auto: false, title: 'elle başlık' }, true],
    [{ topic: '', description: '' }, { topic: '', description: '' }, false],
  ])('topicKey eşliği %#', (a, b, same) => {
    const ka = topicKey(a)
    const kb = topicKey(b)
    expect(ka != null && ka === kb).toBe(same)
  })

  it('bu ayın taslağında aynı konu (ad + açıklama) varsa "exists" ile atlanır; uygulamada da ikinci kez eklenmez', () => {
    const plan = planCarryOver({ prev: prev(PREV_VALUES), cur: cur({}) })
    const k = plan.topics[0]
    const present = { fields: {}, texts: {}, issues: { issues: [{ id: 'c9', ...carryTopicPart(PREV_VALUES.issues.issues[0]) }] } }
    const p2 = planCarryOver({ prev: prev(PREV_VALUES), cur: cur(present) })
    expect(p2.topics.find((x) => x.key === k.key)).toMatchObject({ status: 'skip', reason: 'exists' })
    expect(CARRY_REASONS).toContain('exists')
    // Plan eskiyken (konu arada eklendi) uygulama yine eklemez — sayılır
    const ids = carryTopicIds(present, plan, new Set([k.key]))
    const r = applyCarryOver(present, plan, { texts: new Set(), fields: new Set(), topics: new Set([k.key]) },
      { template: TPL, ids, year: 2026, month: 9 })
    expect(r.values).toBe(present)
    expect(r.counts.stale).toBe(1)
  })
})
