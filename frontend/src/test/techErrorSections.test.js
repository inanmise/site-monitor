import { describe, it, expect } from 'vitest'
import {
  OPEN_STATE_MAX_IDS, allSectionsOpen, errorCountsBySection, isSectionOpen, parseOpenState, reportErrorSection,
  revertInvalidValues, templateErrorSection, withSectionOpen, withSectionsOpened,
} from '../components/techerror/techErrorModel.js'

// Katlanır bölümler + otomatik kayıt (2026-10-16): hata anahtarı → bölüm eşlemesi, otomatik kayıtta geçersiz alanların
// kayıtlı değere döndürülmesi, hatırlanan açık bölüm durumu. Saf model — bileşen testi SectionCollapse.test.jsx.

const TPL = {
  sections: [
    { id: 'sum', kind: 'metrics', title: 'Özet', fields: [
      { id: 'total-tx', label: 'Toplam işlem', type: 'number' },
      { id: 'error-rate', label: 'Hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
    ] },
    { id: 'rates', kind: 'metrics', title: 'Oranlar', fields: [{ id: 'error-count', label: 'Hata adedi', type: 'number' }] },
    // metrics DIŞI bir bölümde aynı biçimde `fields` olsa bile rapor `values.<alan>` anahtarı oraya düşmez
    { id: 'odd', kind: 'text', title: 'Not', fields: [{ id: 'stray', label: 'Başıboş', type: 'number' }] },
    { id: 'iss', kind: 'issues', title: 'Konular' },
    { id: 'tbl', kind: 'table', title: 'SLA', columns: [{ id: 'c1', label: 'Oran', type: 'percent' }], rows: [{ id: 'r1', label: 'Ocak' }] },
    { id: 'sumtext', kind: 'summary', title: 'Yönetici özeti', lines: ['{f:total-tx} işlem'] },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [{ id: 'monthly', label: 'Aylık', width: 'half' }] },
  ],
}

/** Girdinin değişmediğini kanıtlamak için derin dondurma (yazma denemesi katı kipte hata fırlatır). */
function deepFreeze(o) {
  if (o && typeof o === 'object' && !Object.isFrozen(o)) {
    Object.freeze(o)
    for (const v of Object.values(o)) deepFreeze(v)
  }
  return o
}

describe('reportErrorSection — rapor hata anahtarı → bölüm', () => {
  it('values.<alan>: alanı taşıyan metrics bölümü', () => {
    expect(reportErrorSection('values.total-tx', TPL)).toBe('sum')
    expect(reportErrorSection('values.error-rate', TPL)).toBe('sum')
    expect(reportErrorSection('values.error-count', TPL)).toBe('rates')
  })

  it('values.<alan>: bilinmeyen alan ya da metrics dışı bölümdeki alan → null; şablonsuz → null', () => {
    expect(reportErrorSection('values.nope', TPL)).toBeNull()
    expect(reportErrorSection('values.stray', TPL)).toBeNull()
    expect(reportErrorSection('values.', TPL)).toBeNull()
    expect(reportErrorSection('values.total-tx', null)).toBeNull()
    expect(reportErrorSection('values.total-tx', {})).toBeNull()
  })

  it('istemci konu anahtarı issue:<bölüm>:<konu>:<alan> → bölüm', () => {
    expect(reportErrorSection('issue:iss:i-17:errors', TPL)).toBe('iss')
    expect(reportErrorSection('issue:başka-bölüm:x:title', TPL)).toBe('başka-bölüm')
    expect(reportErrorSection('issue::x:title', TPL)).toBeNull()          // bölüm kimliği boş
    expect(reportErrorSection('issue:iss', TPL)).toBeNull()               // ikinci ayraç yok
  })

  it('sunucu anahtarları: issues.<b>.<n>, tables.<b>.<s>.<s>, text.<b>, texts.<b>', () => {
    expect(reportErrorSection('issues.iss.0', TPL)).toBe('iss')
    expect(reportErrorSection('issues.iss', TPL)).toBe('iss')
    expect(reportErrorSection('tables.tbl.r1.c1', TPL)).toBe('tbl')
    expect(reportErrorSection('text.sumtext', TPL)).toBe('sumtext')
    expect(reportErrorSection('texts.sumtext', TPL)).toBe('sumtext')
    expect(reportErrorSection('issues.', TPL)).toBeNull()
    expect(reportErrorSection('tables..r1.c1', TPL)).toBeNull()
  })

  it('belge geneli "values" ve anlamsız anahtarlar → null', () => {
    expect(reportErrorSection('values', TPL)).toBeNull()
    expect(reportErrorSection('template', TPL)).toBeNull()
    expect(reportErrorSection('', TPL)).toBeNull()
    expect(reportErrorSection(null, TPL)).toBeNull()
    expect(reportErrorSection(undefined, TPL)).toBeNull()
    expect(reportErrorSection('issue', TPL)).toBeNull()
    expect(reportErrorSection('textx.sumtext', TPL)).toBeNull()
    expect(reportErrorSection('xvalues.total-tx', TPL)).toBeNull()
  })
})

describe('templateErrorSection — şablon düzenleyici hata anahtarı → bölüm', () => {
  it('section: / line: / col: / row: doğrudan bölüm kimliği', () => {
    expect(templateErrorSection('section:sum', TPL)).toBe('sum')
    expect(templateErrorSection('line:sumtext:3', TPL)).toBe('sumtext')
    expect(templateErrorSection('col:tbl:c1', TPL)).toBe('tbl')
    expect(templateErrorSection('row:tbl:r1', TPL)).toBe('tbl')
    // şablonda olmasa da doğrudan anahtar kimliği taşır (yeni eklenmiş bölüm)
    expect(templateErrorSection('section:yeni', TPL)).toBe('yeni')
    expect(templateErrorSection('section:', TPL)).toBeNull()
    expect(templateErrorSection('line::2', TPL)).toBeNull()
  })

  it('field: / calc: alanın bölümü (metrics dışı dahil); slot: yuvanın bölümü', () => {
    expect(templateErrorSection('field:total-tx', TPL)).toBe('sum')
    expect(templateErrorSection('calc:error-rate', TPL)).toBe('sum')
    expect(templateErrorSection('field:error-count', TPL)).toBe('rates')
    expect(templateErrorSection('field:stray', TPL)).toBe('odd')
    expect(templateErrorSection('slot:monthly', TPL)).toBe('charts')
  })

  it('bilinmeyen kimlik, bilinmeyen tür, ayraçsız anahtar (title) → null', () => {
    expect(templateErrorSection('field:nope', TPL)).toBeNull()
    expect(templateErrorSection('calc:nope', TPL)).toBeNull()
    expect(templateErrorSection('slot:nope', TPL)).toBeNull()
    expect(templateErrorSection('slot:total-tx', TPL)).toBeNull()        // alan kimliği yuva değildir
    expect(templateErrorSection('field:monthly', TPL)).toBeNull()        // yuva kimliği alan değildir
    expect(templateErrorSection('title', TPL)).toBeNull()
    expect(templateErrorSection('foo:sum', TPL)).toBeNull()
    expect(templateErrorSection('', TPL)).toBeNull()
    expect(templateErrorSection(null, TPL)).toBeNull()
    expect(templateErrorSection('field:total-tx', null)).toBeNull()
  })
})

describe('errorCountsBySection', () => {
  it('bölüm başına hatalı alan sayısı; boş mesaj ve bölümsüz anahtar sayılmaz', () => {
    const errors = {
      'values.total-tx': 'Sayı girin',
      'values.error-count': 'Sayı girin',
      'issue:iss:a:errors': 'Sayı girin',
      'issue:iss:b:customers': 'Sayı girin',
      'tables.tbl.r1.c1': 'Yüzde girin',
      'values.stray': 'yok sayılır',     // bölüm bulunamadı
      values: 'belge geneli',            // bölüm yok
      'values.error-rate': '',           // boş mesaj
      'text.sumtext': null,
      'texts.sumtext': undefined,
    }
    const counts = errorCountsBySection(errors, (k) => reportErrorSection(k, TPL))
    expect(counts).toBeInstanceOf(Map)
    expect(Object.fromEntries(counts)).toEqual({ sum: 1, rates: 1, iss: 2, tbl: 1 })
  })

  it('hata yoksa boş Map; sectionOf yalnız dolu mesajlar için çağrılır', () => {
    expect(errorCountsBySection(null, () => 'x').size).toBe(0)
    expect(errorCountsBySection({}, () => 'x').size).toBe(0)
    const seen = []
    errorCountsBySection({ a: 'x', b: '', c: 'y' }, (k) => { seen.push(k); return 's' })
    expect(seen).toEqual(['a', 'c'])
  })
})

describe('revertInvalidValues — otomatik kayıtta geçersiz alan kayıtlı değerde kalır', () => {
  const BASE = deepFreeze({
    fields: { 'total-tx': 1000, 'error-count': 12 },
    issues: { iss: [{ id: 'a', title: 'Giriş', errors: 5 }, { id: 'b', title: 'Ödeme', customers: 40 }] },
    tables: { tbl: { r1: { c1: '99,5' } } },
    texts: {},
  })

  it('values.<alan>: kayıtlı değere döner; kayıtta değer yoksa anahtar SİLİNİR; diğer alanlar gönderilir', () => {
    const values = deepFreeze({ fields: { 'total-tx': 1500, 'error-count': 30, 'error-rate': 7, 'sla-rate': 99 }, issues: {}, tables: {} })
    const out = revertInvalidValues(values, BASE, ['values.total-tx', 'values.sla-rate'])
    expect(out).not.toBe(values)
    expect(out.fields).toEqual({ 'total-tx': 1000, 'error-count': 30, 'error-rate': 7 })
    expect('sla-rate' in out.fields).toBe(false)
    // girdi değişmedi
    expect(values.fields).toEqual({ 'total-tx': 1500, 'error-count': 30, 'error-rate': 7, 'sla-rate': 99 })
  })

  it('values.<alan>: taslakta fields hiç yoksa kayıtlı değer yazılır', () => {
    const values = deepFreeze({ issues: {} })
    expect(revertInvalidValues(values, BASE, ['values.error-count']).fields).toEqual({ 'error-count': 12 })
  })

  it('konu alanı konu KİMLİĞİYLE (sırası değişse de) döner; yeni konunun alanı kaldırılır', () => {
    const values = deepFreeze({
      fields: {},
      issues: { iss: [
        { id: 'n', title: 'Yeni konu', errors: 3 },
        { id: 'b', title: 'Ödeme', customers: 99 },          // sıra değişti, customers geçersiz yazıldı
        { id: 'a', title: 'Giriş', errors: 8 },
      ] },
    })
    const out = revertInvalidValues(values, BASE, ['issue:iss:b:customers', 'issue:iss:n:errors', 'issue:iss:a:title'])
    expect(out.issues.iss[1]).toEqual({ id: 'b', title: 'Ödeme', customers: 40 })
    expect(out.issues.iss[0]).toEqual({ id: 'n', title: 'Yeni konu' })            // kayıtta yok → alan kaldırıldı
    expect('errors' in out.issues.iss[0]).toBe(false)
    expect(out.issues.iss[2]).toEqual({ id: 'a', title: 'Giriş', errors: 8 })     // başlık zaten aynı
    expect(values.issues.iss[0].errors).toBe(3)
    expect(values.issues.iss[1].customers).toBe(99)
  })

  it('tablo hücresi tables.<b>.<satır>.<sütun> döner (kayıtta yoksa silinir)', () => {
    const values = deepFreeze({ fields: {}, tables: { tbl: { r1: { c1: 'abc', c2: '5' }, r2: { c1: '7' } } } })
    const out = revertInvalidValues(values, BASE, ['tables.tbl.r1.c1', 'tables.tbl.r2.c1'])
    expect(out.tables.tbl.r1).toEqual({ c1: '99,5', c2: '5' })
    expect(out.tables.tbl.r2).toEqual({})
    expect(values.tables.tbl.r1.c1).toBe('abc')
    // taslakta tablo hiç yoksa kayıtlı hücre geri yazılır
    const out2 = revertInvalidValues(deepFreeze({ fields: {} }), BASE, ['tables.tbl.r1.c1'])
    expect(out2.tables).toEqual({ tbl: { r1: { c1: '99,5' } } })
  })

  it('değişecek bir şey yoksa AYNI nesne döner', () => {
    const values = deepFreeze({ fields: { 'total-tx': 1000 }, issues: { iss: [{ id: 'a', errors: 5 }] }, tables: { tbl: { r1: { c1: '99,5' } } } })
    expect(revertInvalidValues(values, BASE, [])).toBe(values)
    expect(revertInvalidValues(values, BASE, null)).toBe(values)
    expect(revertInvalidValues(values, BASE, ['values.total-tx', 'issue:iss:a:errors', 'tables.tbl.r1.c1'])).toBe(values)
    // kayıtta da taslakta da yok
    expect(revertInvalidValues(values, BASE, ['values.unknown', 'tables.tbl.r9.c9'])).toBe(values)
    // taslakta olmayan konu → atlanır
    expect(revertInvalidValues(values, BASE, ['issue:iss:zzz:errors'])).toBe(values)
  })

  it('bozuk anahtarlar yok sayılır', () => {
    const values = deepFreeze({ fields: { 'total-tx': 5 }, issues: { iss: [{ id: 'a', errors: 9 }] }, tables: { tbl: { r1: { c1: 'x' } } } })
    const keys = ['issue:iss:a', 'issue:iss:a:errors:extra', 'tables.tbl.r1', 'tables.tbl.r1.c1.x', 'text.sumtext', 'values', 'foo', '', null]
    expect(revertInvalidValues(values, BASE, keys)).toBe(values)
  })

  it('values boşsa olduğu gibi döner; base yoksa her şey kaldırılır', () => {
    expect(revertInvalidValues(null, BASE, ['values.total-tx'])).toBeNull()
    const values = deepFreeze({ fields: { 'total-tx': 5, 'error-count': 1 } })
    expect(revertInvalidValues(values, null, ['values.total-tx']).fields).toEqual({ 'error-count': 1 })
  })
})

describe('açık bölüm durumu', () => {
  it('parseOpenState: bozuk / dizi olmayan / boş girdi → hepsi kapalı', () => {
    const closed = { all: false, ids: [] }
    expect(parseOpenState('{bozuk')).toEqual(closed)
    expect(parseOpenState(null)).toEqual(closed)
    expect(parseOpenState(undefined)).toEqual(closed)
    expect(parseOpenState('null')).toEqual(closed)
    expect(parseOpenState('42')).toEqual(closed)
    expect(parseOpenState('["a"]')).toEqual(closed)
    expect(parseOpenState('{"ids":"a"}')).toEqual(closed)
    expect(parseOpenState('{"all":"true"}')).toEqual(closed)
  })

  it('parseOpenState: {all:true} → hepsi açık (kimlikler yok sayılır)', () => {
    expect(parseOpenState('{"all":true}')).toEqual({ all: true, ids: [] })
    expect(parseOpenState({ all: true, ids: ['a'] })).toEqual({ all: true, ids: [] })
  })

  it('parseOpenState: geçersiz kimlikler süzülür, tekrarlar teklenir, en çok OPEN_STATE_MAX_IDS', () => {
    const long = 'x'.repeat(121)
    const edge = 'y'.repeat(120)
    expect(parseOpenState(JSON.stringify({ ids: ['a', 'b', 'a', '', 7, null, long, edge, { id: 'c' }] })))
      .toEqual({ all: false, ids: ['a', 'b', edge] })
    // zaten nesne olan girdi de okunur
    expect(parseOpenState({ ids: ['s1', 's2'] })).toEqual({ all: false, ids: ['s1', 's2'] })
    const many = Array.from({ length: OPEN_STATE_MAX_IDS + 10 }, (_, i) => `s${i}`)
    const parsed = parseOpenState(JSON.stringify({ ids: many }))
    expect(parsed.ids).toHaveLength(OPEN_STATE_MAX_IDS)
    expect(parsed.ids[0]).toBe('s0')
    expect(OPEN_STATE_MAX_IDS).toBe(64)
  })

  it('isSectionOpen', () => {
    expect(isSectionOpen({ all: true, ids: [] }, 'any')).toBe(true)
    expect(isSectionOpen({ all: false, ids: ['a'] }, 'a')).toBe(true)
    expect(isSectionOpen({ all: false, ids: ['a'] }, 'b')).toBe(false)
    expect(isSectionOpen(null, 'a')).toBe(false)
  })

  it('withSectionOpen: aç / kapat; değişiklik yoksa aynı nesne', () => {
    const s0 = deepFreeze({ all: false, ids: ['a'] })
    const s1 = withSectionOpen(s0, 'b', true, ['a', 'b', 'c'])
    expect(s1).toEqual({ all: false, ids: ['a', 'b'] })
    expect(withSectionOpen(s1, 'b', true, ['a', 'b', 'c'])).toBe(s1)       // zaten açık
    const s2 = withSectionOpen(s1, 'a', false, ['a', 'b', 'c'])
    expect(s2).toEqual({ all: false, ids: ['b'] })
    expect(withSectionOpen(s2, 'a', false, ['a', 'b', 'c'])).toBe(s2)      // zaten kapalı
    expect(s0).toEqual({ all: false, ids: ['a'] })
  })

  it('withSectionOpen: "hepsi açık"ken biri kapatılınca o anki diğer bölümler açık kalır', () => {
    const all = deepFreeze({ all: true, ids: [] })
    expect(withSectionOpen(all, 'b', true, ['a', 'b'])).toBe(all)
    const s = withSectionOpen(all, 'b', false, ['a', 'b', 'c'])
    expect(s).toEqual({ all: false, ids: ['a', 'c'] })
    expect(isSectionOpen(s, 'a')).toBe(true)
    expect(isSectionOpen(s, 'b')).toBe(false)
    expect(isSectionOpen(s, 'c')).toBe(true)
  })

  it('withSectionOpen: açılan kimlikler en yeni OPEN_STATE_MAX_IDS ile sınırlı (en eski düşer)', () => {
    let s = { all: false, ids: [] }
    for (let i = 0; i < OPEN_STATE_MAX_IDS + 3; i++) s = withSectionOpen(s, `s${i}`, true, [])
    expect(s.ids).toHaveLength(OPEN_STATE_MAX_IDS)
    expect(s.ids[0]).toBe('s3')
    expect(s.ids[s.ids.length - 1]).toBe(`s${OPEN_STATE_MAX_IDS + 2}`)
  })

  it('withSectionsOpened: verilen bölümleri açar; zaten açıksa / hepsi açıksa / liste boşsa aynı nesne', () => {
    const s0 = deepFreeze({ all: false, ids: ['a'] })
    expect(withSectionsOpened(s0, ['b', 'c', 'b'])).toEqual({ all: false, ids: ['a', 'b', 'c'] })
    expect(withSectionsOpened(s0, ['a'])).toBe(s0)
    expect(withSectionsOpened(s0, [])).toBe(s0)
    expect(withSectionsOpened(s0, null)).toBe(s0)
    const all = deepFreeze({ all: true, ids: [] })
    expect(withSectionsOpened(all, ['x', 'y'])).toBe(all)
  })

  it('allSectionsOpen', () => {
    expect(allSectionsOpen({ all: true, ids: [] }, ['a', 'b'])).toBe(true)
    expect(allSectionsOpen({ all: false, ids: ['a', 'b', 'z'] }, ['a', 'b'])).toBe(true)
    expect(allSectionsOpen({ all: false, ids: ['a'] }, ['a', 'b'])).toBe(false)
    expect(allSectionsOpen(null, ['a'])).toBe(false)
  })
})
