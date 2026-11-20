import { describe, it, expect } from 'vitest'
import {
  READY_FIGURE_TYPES, SCALE_FACTOR, approvalLockFingerprint, approvalLockState, fieldScaleWarning, fixableCalc, groupReadiness,
  isBlankReport, issueSumWarning, readinessKey, readinessTarget, readinessTone, reportReadiness, scaleWarning,
} from '../components/techerror/readinessModel.js'
import { calcResults } from '../components/techerror/techErrorModel.js'

// Hazırlık rehberi (2026-11-20, B paketi): saf model — boş rakam / görsel / konu cevabı, özete girmeyen cümle, birim ve toplam
// soruları (B2), kesinti çelişkisi, alıcı önkoşulları, bilinmeyen girdi, gömülü bölüm, muaf alan, eski şekiller; onay kilidi (B3).

const TPL = {
  lang: 'tr',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['Toplam işlem {f:total-tx}.', 'Teknik hata {f:error-count} adet.'] },
    { id: 'quota', kind: 'metrics', title: 'Kota', host: 'exec', fields: [{ id: 'quota-count', label: 'Kota hata adedi', type: 'number' }] },
    { id: 'summary', kind: 'metrics', title: '{kanal} Verileri', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'had-outage', label: 'Dönemde kesinti oldu mu?', type: 'yesno' },
      { id: 'outage-errors', label: 'Kesinti kaynaklı hata', type: 'number', skip_if_no: 'had-outage' },
      { id: 'note', label: 'Açıklama', type: 'textarea' },
      { id: 'short', label: 'Kısa not', type: 'text' },
    ] },
    { id: 'rates', kind: 'metrics', title: 'Oranlar', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
      { id: 'tx-increase', label: 'İşlem arttı mı?', type: 'yesno', calc: { kind: 'increase', of: 'total-tx' } },
      { id: 'quota-rate', label: 'Kota payı', type: 'percent', calc: { kind: 'ratio', num: 'quota-count', den: 'error-count' } },
      { id: 'tx-change', label: 'İşlem değişimi', type: 'percent', calc: { kind: 'change', of: 'total-tx' } },
    ] },
    { id: 'issues', kind: 'issues', title: 'Anomali', item_title: '{konu}: Hata Artışı', ask_time: true, ask_outage: true,
      ratio_of: 'error-count', allow_images: true },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [{ id: 'a', label: 'A grafiği' }, { id: 'b', label: 'B grafiği' }] },
    { id: 'tbl', kind: 'table', title: 'Tablo', columns: [{ id: 'c', label: 'C', type: 'number' }], rows: [{ id: 'r', label: 'R' }] },
  ],
}

const FULL = {
  fields: { 'total-tx': 1000, 'error-count': 5, 'had-outage': false, 'quota-count': 2, note: 'x' },
  issues: { issues: [] }, texts: {}, tables: {},
}

// Geçen ayın raporu: "işlem arttı mı?" hesaplanabilsin (yoksa MANUAL → elle sorulur ve boşsa sayılır)
const PREV = { 'total-tx': 900 }

function run(values = FULL, extra = {}) {
  const previousFields = 'previousFields' in extra ? extra.previousFields : PREV
  const calc = calcResults(TPL, values.fields, previousFields, values.targets)
  return reportReadiness({ template: TPL, values, calc, images: [{ slot_id: 'a' }, { slot_id: 'b' }],
    recipients: { approvalRequired: false, teamEmailCount: 2 }, ...extra, previousFields })
}

const kinds = (r) => r.items.map((i) => `${i.kind}:${(i.fieldId || i.slotId || i.part || i.index) ?? ''}`)

describe('B2 — birim sorusu (scaleWarning)', () => {
  it.each([
    // [tür, bu ay, geçen ay, beklenen]
    ['number', 44_000_000, 4_400_000, 'up'],
    ['number', 4_400_000, 44_000_000, 'down'],
    ['number', 10, 1, 'up'],              // tam 10 kat
    ['number', 9.99, 1, null],
    ['number', 0.1, 1, 'down'],            // tam 1/10
    ['number', 0.11, 1, null],
    ['number', 0, 5, null],                // sıfır karşılaştırılmaz
    ['number', 5, 0, null],
    ['number', null, 5, null],
    ['percent', 4.2, 0.42, 'up'],
    ['percent', -5, 0.4, 'up'],            // işaret değişse de büyüklük
    ['yesno', true, false, null],
    ['text', '44', '4', null],
  ])('%s %s / %s → %s', (type, cur, prev, want) => {
    const w = scaleWarning({ type }, cur, prev)
    expect(w?.dir ?? null).toBe(want)
  })

  it('hesaplanan alan sorulmaz; elle girilen (MANUAL) hesaplanan alan sorulur; muaf alan sorulmaz', () => {
    const f = { id: 'r', type: 'percent', calc: { kind: 'change', of: 'x' } }
    expect(fieldScaleWarning(f, { r: 50 }, { r: 2 }, { r: { status: 'OK', value: 50 } })).toBeNull()
    expect(fieldScaleWarning(f, { r: 50 }, { r: 2 }, { r: { status: 'MANUAL' } })?.dir).toBe('up')
    const ex = { id: 'e', type: 'number', skip_if_no: 'q' }
    expect(fieldScaleWarning(ex, { e: 100, q: false }, { e: 1 }, {})).toBeNull()
    expect(SCALE_FACTOR).toBe(10)
  })
})

describe('B2 — konu adetleri toplamı (issueSumWarning)', () => {
  const s = TPL.sections.find((x) => x.id === 'issues')
  it.each([
    [[{ errors: 3 }, { errors: 3 }], { 'error-count': 5 }, { sum: 6, total: 5 }],
    [[{ errors: 3 }, { errors: 2 }], { 'error-count': 5 }, null],        // eşit aşmaz
    [[{ errors: 9 }], { 'error-count': null }, null],                     // toplam boş
    [[{}], { 'error-count': 1 }, null],                                   // hiçbir konuda adet yok
    [[], { 'error-count': 1 }, null],
  ])('%j / %j → %j', (issues, fields, want) => {
    const w = issueSumWarning(s, issues, TPL, fields)
    expect(w ? { sum: w.sum, total: w.total } : null).toEqual(want)
  })

  it('pay sorulmayan bölüm, muaf ya da sayı olmayan toplam alanı sorulmaz', () => {
    expect(issueSumWarning({ id: 'x', kind: 'issues' }, [{ errors: 99 }], TPL, { 'error-count': 1 })).toBeNull()
    const tpl = { ...TPL, sections: [{ id: 'm', kind: 'metrics', fields: [{ id: 'error-count', type: 'percent' }] }] }
    expect(issueSumWarning({ id: 'i', kind: 'issues', ratio_of: 'error-count' }, [{ errors: 99 }], tpl, { 'error-count': 1 })).toBeNull()
  })
})

describe('reportReadiness — doğruluk tablosu', () => {
  it('her şey dolu ve biliniyor → hazır, tek ton "ok"', () => {
    const r = run()
    expect(r.items).toEqual([])
    expect(r.known).toBe(true)
    expect(r.ready).toBe(true)
    expect(r.images).toEqual({ filled: 2, total: 2 })
    expect(readinessTone(r)).toBe('ok')
  })

  it('boş rakam sayılır; metin / uzun açıklama sayılmaz; muaf (Hayır) alan sayılmaz; hesaplanan alan (MISSING) sayılmaz', () => {
    const r = run({ ...FULL, fields: { 'total-tx': 1000, 'had-outage': false } })
    // Şablon sırası: özetin düşen cümlesi (uyarı), gömülü quota-count (boş), error-count (boş); error-rate MISSING
    // (girdisi boş) sayılmaz; outage-errors muaf; metin / uzun açıklama sayılmaz
    expect(kinds(r)).toEqual(['line:1', 'field:quota-count', 'field:error-count'])
    expect(r.empty).toBe(2)
    expect(r.warnings).toBe(1)
    // Gömülü bölümün alanı özet bölümüne yazılır; cümlenin eksik rakamı kendi (gösterge) bölümüne götürür
    expect(r.items[1].sectionId).toBe('exec')
    expect(r.items[0]).toMatchObject({ kind: 'line', sectionId: 'exec', fieldIds: ['error-count'], fieldSectionId: 'summary' })
    expect(r.bySection).toEqual({ exec: 2, summary: 1 })
    expect(readinessTone(r)).toBe('warn')
  })

  it('evet/hayır boş → sayılır; "Evet" iken bağlı alan boşsa sayılır', () => {
    const r = run({ ...FULL, fields: { ...FULL.fields, 'had-outage': true } })
    expect(kinds(r)).toEqual(['field:outage-errors'])
    const r2 = run({ ...FULL, fields: { ...FULL.fields, 'had-outage': null } })
    expect(kinds(r2)).toEqual(['field:had-outage', 'field:outage-errors'])
  })

  it('hesaplanan alan düzeltilebilir nedenle sonuçsuz (ZERO) → "sonuç yok" diye sayılır; MANUAL ve boş → sayılır', () => {
    const r = run({ ...FULL, fields: { ...FULL.fields, 'total-tx': 0 } })
    expect(r.items.find((i) => i.fieldId === 'error-rate')).toMatchObject({ kind: 'field', reason: 'ZERO', severity: 'empty' })
    // Geçen ayın raporu yok → "arttı mı?" elle (MANUAL) sorulur; boşsa sayılır
    const manual = run(FULL, { previousFields: null })
    expect(manual.items.find((i) => i.fieldId === 'tx-increase')).toMatchObject({ reason: 'empty' })
    const withPrev = run(FULL, { previousFields: { 'total-tx': 900 } })
    expect(withPrev.items.find((i) => i.fieldId === 'tx-increase')).toBeUndefined()
  })

  // İnceleme 2026-11-20: hatasız ay (teknik hata 0 → kota payı 0 ÷ 0) ve geçen ayı 0 olan değişim doğru girdinin sonucudur
  it.each([
    // [alanlar, geçen ay, sayılan hesaplanan alanlar]
    [{ 'error-count': 0, 'quota-count': 0 }, { 'total-tx': 900 }, []],                       // 0 ÷ 0 — sayılmaz
    [{ 'error-count': 0, 'quota-count': 3 }, { 'total-tx': 900 }, ['quota-rate:ZERO']],       // bölen 0, pay var — girdi yanlış
    [{ 'total-tx': 0, 'error-count': 5 }, { 'total-tx': 900 }, ['error-rate:ZERO']],          // işlem 0 ama hata var
    [{ 'total-tx': 0, 'error-count': 0, 'quota-count': 0 }, { 'total-tx': 900 }, []],         // hiç işlem / hata yok
    [{}, { 'total-tx': 0 }, []],                                                             // geçen ay 0: değişim ZERO — sayılmaz
  ])('hesap sonucu ZERO: %j / geçen ay %j → %j', (patch, prev, want) => {
    const r = run({ ...FULL, fields: { ...FULL.fields, ...patch } }, { previousFields: prev })
    const got = r.items.filter((i) => i.kind === 'field' && i.reason !== 'empty').map((i) => `${i.fieldId}:${i.reason}`)
    expect(got).toEqual(want)
  })

  it('hatasız ay (teknik hata 0, kota 0) eksiksizse "hazır"', () => {
    const r = run({ ...FULL, fields: { ...FULL.fields, 'error-count': 0, 'quota-count': 0 } })
    expect(r.items).toEqual([])
    expect(r.ready).toBe(true)
  })

  it('boş görsel yuvası sayılır; görsel listesi bilinmiyorsa yuva sayılmaz ama "hazır" da değil', () => {
    const r = run(FULL, { images: [{ slot_id: 'a' }] })
    expect(kinds(r)).toEqual(['slot:b'])
    expect(r.images).toEqual({ filled: 1, total: 2 })
    // Boş yuva "boş alan" sayısına katılmaz (çip onu "Görsel 1/2" ile söyler); ayrı sayılır
    expect(r.empty).toBe(0)
    expect(r.emptySlots).toBe(1)
    expect(r.warnings).toBe(0)
    const unknown = run(FULL, { images: null })
    expect(unknown.items).toEqual([])
    expect(unknown.known).toBe(false)
    expect(unknown.ready).toBe(false)
    expect(unknown.images).toEqual({ filled: null, total: 2 })
    expect(readinessTone(unknown)).toBe('unknown')
  })

  it('konunun boş ZORUNLU cevabı sayılır (Kesinti ID, aralığın eksik ucu); toplamı aşan konu adetleri uyarıdır', () => {
    // Kesinti "Evet" (konuyla çelişki uyarısı çıkmasın), bağlı rakam dolu
    const values = { ...FULL, fields: { ...FULL.fields, 'had-outage': true, 'outage-errors': 1 }, issues: { issues: [
      { id: 'k1', topic: 'Kart', errors: 4, outage: true },
      { id: 'k2', topic: 'POS', errors: 4, start: '10:00' },
    ] } }
    const r = run(values, { period: { year: 2026, month: 9 } })
    expect(kinds(r)).toEqual(['topic:outage_id', 'topic:end', 'issueSum:'])
    expect(r.items[0]).toMatchObject({ sectionId: 'issues', issueId: 'k1', index: 0 })
    expect(r.items[2]).toMatchObject({ sum: 8, total: 5, ref: 'error-count' })
  })

  it('B2 birim sorusu uyarıdır; geçen ayın aynı kimlikli rakamıyla', () => {
    const r = run({ ...FULL, fields: { ...FULL.fields, 'total-tx': 44_000_000 } }, { previousFields: { 'total-tx': 4_400_000 } })
    expect(r.items.find((i) => i.kind === 'scale')).toMatchObject({ fieldId: 'total-tx', dir: 'up', cur: 44_000_000, prev: 4_400_000, severity: 'warning' })
  })

  it('kesinti "Hayır" + kesinti kaynaklı konu → uyarı', () => {
    const r = run({ ...FULL, issues: { issues: [{ id: 'k1', topic: 'Kart', errors: 1, outage: true, outage_id: 'INC-1' }] } })
    expect(r.items.find((i) => i.kind === 'outage')).toMatchObject({ fieldId: 'had-outage', count: 1 })
  })

  it('elle yazılmış özet cümle kalıplarını kullanmaz — eksik rakamın cümlesi sayılmaz', () => {
    const v = { ...FULL, fields: { ...FULL.fields, 'error-count': null }, texts: { exec: 'Elle özet' } }
    expect(run(v).items.some((i) => i.kind === 'line')).toBe(false)
  })

  it('alıcılar: onaysız kanalda takım adresi yok → uyarı; onay akışında PO yok → uyarı; bilinmeyen → known false; verilmezse denetlenmez', () => {
    expect(kinds(run(FULL, { recipients: { approvalRequired: false, teamEmailCount: 0 } }))).toEqual(['teamEmail:'])
    expect(kinds(run(FULL, { recipients: { approvalRequired: false, teamEmailSet: false } }))).toEqual(['teamEmail:'])
    expect(kinds(run(FULL, { recipients: { approvalRequired: true, approverCount: 0 } }))).toEqual(['noApprover:'])
    const unk = run(FULL, { recipients: { approvalRequired: false } })
    expect(unk.known).toBe(false)
    expect(unk.unknown).toEqual(['recipients'])
    expect(run(FULL, { recipients: { approvalRequired: true } }).unknown).toEqual(['approvers'])
    expect(run(FULL, { recipients: null }).known).toBe(true)
  })

  it('şablonsuz / bölümsüz eski şekil → bilinmiyor, kalem yok', () => {
    for (const template of [null, {}, { sections: [] }]) {
      const r = reportReadiness({ template, values: { fields: { a: 1 } } })
      expect(r.known).toBe(false)
      expect(r.items).toEqual([])
      expect(readinessTone(r)).toBe('unknown')
    }
    expect(READY_FIGURE_TYPES).toEqual(['number', 'percent', 'yesno'])
  })
})

describe('fixableCalc ve cümle hedefi', () => {
  const ratio = { calc: { kind: 'ratio', num: 'n', den: 'd' } }
  const ratioMinus = { calc: { kind: 'ratio', num: 'n', minus: 'm', den: 'd' } }
  it.each([
    [ratio, 'ZERO', { n: 0, d: 0 }, false],
    [ratio, 'ZERO', { n: 2, d: 0 }, true],
    [ratioMinus, 'ZERO', { n: 2, m: 2, d: 0 }, false],
    [ratioMinus, 'ZERO', { n: 3, m: 2, d: 0 }, true],
    [ratio, 'ZERO', { d: 0 }, false],
    [{ calc: { kind: 'change', of: 'x' } }, 'ZERO', { x: 5 }, false],
    [{ calc: { kind: 'increase', of: 'x' } }, 'ZERO', { x: 5 }, false],
    [ratio, 'NEGATIVE', { n: 1, d: 2 }, true],
    [ratio, 'RANGE', { n: 1, d: 2 }, true],
    [ratio, 'MISSING', {}, false],
    [ratio, 'OK', { n: 1, d: 2 }, false],
  ])('%j %s %j → %s', (field, status, fields, want) => {
    expect(fixableCalc(field, { status }, fields)).toBe(want)
  })

  it('özet cümlesinin eksik rakamı hesaplanan alansa "Git" onun ilk boş girdisine gider', () => {
    const tpl = { lang: 'tr', sections: [
      { id: 'exec', kind: 'summary', lines: ['Oran {f:error-rate}.'] },
      { id: 'm', kind: 'metrics', fields: [
        { id: 'total-tx', type: 'number' }, { id: 'error-count', type: 'number' },
        { id: 'error-rate', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
      ] },
    ] }
    const values = { fields: { 'total-tx': 10 } }
    const r = reportReadiness({ template: tpl, values, calc: calcResults(tpl, values.fields, null), images: [] })
    const line = r.items.find((i) => i.kind === 'line')
    expect(line).toMatchObject({ fieldIds: ['error-rate'], targetFieldId: 'error-count', fieldSectionId: 'm' })
    expect(readinessTarget(line).selector).toBe('[data-field="values.error-count"]')
  })
})

describe('yardımcılar', () => {
  it('gruplama şablon sırası; alıcı kalemleri sonda', () => {
    const g = groupReadiness([{ sectionId: null, kind: 'teamEmail' }, { sectionId: 's1' }, { sectionId: 's2' }, { sectionId: 's1' }])
    expect(g.map((x) => [x.sectionId, x.items.length])).toEqual([['s1', 2], ['s2', 1], [null, 1]])
  })

  it('anahtar metni sıralı ve kararlı (Map yalnız sayılar değişince yenilenir)', () => {
    expect(readinessKey({ b: 2, a: 1 })).toBe('a:1|b:2')
    expect(readinessKey({})).toBe('')
  })

  it('hedefler: alan, cümle (eksik rakamın bölümü), yuva, konu, toplam uyarısı, alıcılar', () => {
    expect(readinessTarget({ kind: 'field', sectionId: 's', fieldId: 'f' })).toEqual({ sectionId: 's', topic: null, selector: '[data-field="values.f"]' })
    expect(readinessTarget({ kind: 'line', sectionId: 'exec', fieldIds: ['x'], fieldSectionId: 'summary' }).sectionId).toBe('summary')
    expect(readinessTarget({ kind: 'slot', sectionId: 'c', slotId: 'a' }).selector).toBe('[data-slot="te-image-slot"][data-slot-id="a"]')
    expect(readinessTarget({ kind: 'topic', sectionId: 'i', issueId: 'k', part: 'end' }))
      .toEqual({ sectionId: 'i', topic: ['i', 'k'], selector: '[data-field="issue:i:k:end"]' })
    expect(readinessTarget({ kind: 'issueSum', sectionId: 'i' }).selector).toContain('te-issue-sum-warn')
    expect(readinessTarget({ kind: 'teamEmail', sectionId: null }).selector).toBe('[data-slot="te-recipients"]')
  })

  it('boş rapor: rakam / metin / konu / tablo / görsel yok', () => {
    expect(isBlankReport({ fields: { a: null, b: '  ' }, texts: {}, issues: { i: [] }, tables: { t: { r: { c: null } } } }, [])).toBe(true)
    expect(isBlankReport({ fields: { a: 0 } }, [])).toBe(false)
    expect(isBlankReport({ fields: {}, tables: { t: { r: { c: 1 } } } }, [])).toBe(false)
    expect(isBlankReport({ fields: {} }, [{ slot_id: 'a' }])).toBe(false)
    expect(isBlankReport(null, null)).toBe(true)
  })
})

describe('B3 — onay kilidi (approvalLockState)', () => {
  const base = (extra = {}) => ({ can_edit: true, approval_detail: { required: true },
    report: { status: 'draft', approval: { state: 'pending' } }, ...extra })
  it.each([
    ['bekleyen istek', base(), 'pending'],
    ['onaylı', base({ report: { status: 'draft', approval: { state: 'approved' } } }), 'approved'],
    ['eskimiş', base({ report: { status: 'changed', approval: { state: 'stale' } } }), null],
    ['reddedilmiş', base({ report: { status: 'draft', approval: { state: 'rejected' } } }), null],
    ['yetkisiz', base({ can_edit: false }), null],
    ['akış kaldırılmış', base({ approval_detail: { required: false } }), null],
    ['eski sunucu (approval_detail yok)', base({ approval_detail: undefined }), null],
    ['gönderilmiş', base({ report: { status: 'sent', approval: { state: 'approved' } } }), null],
    ['kuyrukta', base({ report: { status: 'queued', approval: { state: 'approved' } } }), null],
    ['gönderiliyor', base({ report: { status: 'sending', approval: { state: 'approved' } } }), null],
    ['veri yok', null, null],
  ])('%s → %s', (_n, data, want) => {
    expect(approvalLockState(data)).toBe(want)
  })

  it('parmak izi: durum + istek + karar anı; kilit yoksa null', () => {
    const d = base({ report: { status: 'draft', approval: { state: 'pending', requested_at: 'A' } } })
    expect(approvalLockFingerprint(d)).toBe('pending|A|')
    const dec = base({ report: { status: 'draft', approval: { state: 'approved', requested_at: 'A', decided_at: 'B' } } })
    expect(approvalLockFingerprint(dec)).toBe('approved|A|B')
    expect(approvalLockFingerprint(base({ can_edit: false }))).toBeNull()
  })
})
