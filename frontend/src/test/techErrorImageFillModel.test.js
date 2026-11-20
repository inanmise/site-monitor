import { describe, it, expect, beforeEach } from 'vitest'
import {
  BULK_HOWS, BULK_MAX_FILES, SEQFILL_KEY, hasWords, bulkConflicts, fileBase, foldName, looksLikeImage, mapDroppedFiles, nextEmptySlot,
  readSeqFill, reportSlotOrder, slotFileKey, slotPosition, writeSeqFill,
} from '../components/techerror/imageFillModel.js'
import { captureFileName } from '../components/techerror/capture/captureModel.js'
import { PERSONAL_PREFIXES, clearPersonalStorage } from '../utils/personalStorage.js'

// Görsel yuvalarını doldurma (2026-11-20, C2 / C4): sıradaki BOŞ yuva şablon sırasıyla; toplu bırakmada dosya → yuva eşlemesi
// (önce dosya adındaki yuva kimliği, sonra yuva adı, sonra sıradaki boş yuva) ve varsayılan YALNIZ BOŞ yuva.

const SECTIONS = [
  { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [] },
  { id: 'sla-shots', kind: 'images', title: 'SLA', slots: [{ id: 'sla-1', label: 'SLA ekran görüntüsü 1', width: 'full' }] },
  { id: 'issues', kind: 'issues', title: 'Konular', allow_images: true },
  { id: 'charts', kind: 'images', title: 'Grafikler', slots: [
    { id: 'monthly-tx', label: '{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri', width: 'half' },
    { id: 'tech-errors', label: '{kanal} Kanalı Teknik Hata Grafiği', width: 'half' },
    { id: 'daily-errors', label: '{kanal} Kanalı Teknik Hata (Günlük)', width: 'full' },
    { id: 'monthly-tx', label: 'Aynı kimlik ikinci kez', width: 'half' },
  ] },
  { id: 'daily', kind: 'images', title: 'Günlük', slots: [
    { id: 'tech-ratio', label: 'Teknik oran', width: 'half' },
    { id: 'tech-ratio-monthly', label: 'Teknik oran aylık', width: 'half' },
  ] },
]
const ORDER = reportSlotOrder(SECTIONS).map((o) => ({ ...o, label: o.label.replace('{kanal}', 'Mobil') }))

describe('reportSlotOrder / slotPosition', () => {
  it('yalnız görsel bölümlerinin yuvaları, şablon sırasıyla, kimlik bir kez (konu görselleri yok)', () => {
    expect(reportSlotOrder(SECTIONS).map((o) => [o.id, o.sectionId])).toEqual([
      ['sla-1', 'sla-shots'], ['monthly-tx', 'charts'], ['tech-errors', 'charts'], ['daily-errors', 'charts'],
      ['tech-ratio', 'daily'], ['tech-ratio-monthly', 'daily'],
    ])
    expect(reportSlotOrder(null)).toEqual([])
  })
  it('sıra 1\'den, toplamla; bilinmeyen null', () => {
    expect(slotPosition(ORDER, 'daily-errors')).toEqual({ pos: 4, total: 6 })
    expect(slotPosition(ORDER, 'yok')).toBeNull()
  })
})

describe('nextEmptySlot — dolu yuva ASLA hedef olmaz', () => {
  const filled = (...ids) => (id) => !ids.includes(id)
  const ROWS = [
    // [dolular, afterId, beklenen]
    [[], null, 'sla-1'],
    [['sla-1'], null, 'monthly-tx'],
    [['sla-1', 'monthly-tx'], 'sla-1', 'tech-errors'],
    [[], 'tech-errors', 'daily-errors'],                         // imleçten SONRAKİ ilk boş
    [['daily-errors', 'tech-ratio', 'tech-ratio-monthly'], 'tech-errors', 'sla-1'],   // sona varınca baştan dolanır
    [[], 'bilinmeyen', 'sla-1'],                                  // imleç listede yoksa baştan
    [['sla-1', 'monthly-tx', 'tech-errors', 'daily-errors', 'tech-ratio', 'tech-ratio-monthly'], null, null],
  ]
  it.each(ROWS)('dolu %j, sonra %j → %j', (full, after, want) => {
    expect(nextEmptySlot(ORDER, filled(...full), after)?.id ?? null).toBe(want)
  })
  it('boş liste → null', () => {
    expect(nextEmptySlot([], () => true)).toBeNull()
  })
})

describe('Sıralı doldur tercihi (kişisel kayıt)', () => {
  beforeEach(() => { try { localStorage.clear() } catch { /* yok */ } })
  it('varsayılan KAPALI; açılınca "1", kapanınca silinir', () => {
    expect(readSeqFill()).toBe(false)
    writeSeqFill(true)
    expect(localStorage.getItem(SEQFILL_KEY)).toBe('1')
    expect(readSeqFill()).toBe(true)
    writeSeqFill(false)
    expect(localStorage.getItem(SEQFILL_KEY)).toBeNull()
  })
  it('çıkışta silinir (PERSONAL_PREFIXES)', () => {
    expect(PERSONAL_PREFIXES).toContain('sm.te.seqfill')
    writeSeqFill(true)
    clearPersonalStorage()
    expect(localStorage.getItem(SEQFILL_KEY)).toBeNull()
  })
})

describe('dosya adı yardımcıları', () => {
  it('slotFileKey = ekrandan / panodan alınan dosyanın adındaki yuva kısmı', () => {
    for (const id of ['monthly-tx', 'iss:issues:k1:1', 'Tech_Ratio']) {
      expect(captureFileName(id, 'pano', new Date(Date.UTC(2026, 10, 3, 7, 15)))).toMatch(new RegExp(`^${slotFileKey(id)}-pano-`))
    }
  })
  it('fileBase / looksLikeImage / foldName', () => {
    expect(fileBase('Grafik.Eylül.PNG')).toBe('grafik.eylül')
    expect(fileBase('.gizli')).toBe('.gizli')
    expect(looksLikeImage({ name: 'a.bin', type: 'image/png' })).toBe(true)
    expect(looksLikeImage({ name: 'a.JPG', type: '' })).toBe(true)
    expect(looksLikeImage({ name: 'notlar.txt', type: 'text/plain' })).toBe(false)
    expect(foldName('Mobil Kanalı (Günlük) İŞLEM')).toBe('mobil kanali gunluk islem')
  })
})

describe('mapDroppedFiles', () => {
  const f = (name, type = 'image/png') => ({ name, type, size: 10 })
  const rowsOf = (res) => res.rows.map((r) => [r.name, r.target, r.how, r.match])

  it('1) dosya adındaki yuva kimliği (en uzun), 2) yuva adı (katlanmış), 3) sıradaki boş yuva', () => {
    const res = mapDroppedFiles([
      f('tech-ratio-monthly-ekran-20261103-101500.png'), // "tech-ratio" ile karışmaz
      f('tech-ratio.png'),
      f('Eylül - mobil kanali ay bazli toplam islem adetleri (1).png'),
      f('günlük.png'),                                    // yalnız bir yuva adının içinde geçer
      f('IMG_0042.png'),
      f('notlar.txt', 'text/plain'),
    ], ORDER, { startSectionId: 'charts' })
    expect(rowsOf(res)).toEqual([
      ['tech-ratio-monthly-ekran-20261103-101500.png', 'tech-ratio-monthly', 'name', 'tech-ratio-monthly'],
      ['tech-ratio.png', 'tech-ratio', 'name', 'tech-ratio'],
      ['Eylül - mobil kanali ay bazli toplam islem adetleri (1).png', 'monthly-tx', 'label', 'monthly-tx'],
      ['günlük.png', 'daily-errors', 'label', 'daily-errors'],
      ['IMG_0042.png', 'tech-errors', 'next', null],     // bırakılan bölümün ilk boş yuvası
      ['notlar.txt', null, 'notImage', null],
    ])
    for (const r of res.rows) expect(BULK_HOWS).toContain(r.how)
    expect(res.over).toBe(0)
  })

  it('VARSAYILAN YALNIZ BOŞ YUVA: adı dolu yuvayla eşleşen dosya atlanır (eşleşme söylenir), sıradaki boş dolu yuvayı atlar', () => {
    const isFilled = (id) => id !== 'daily-errors'
    const res = mapDroppedFiles([f('sla-1-pano-20261103-101500.png'), f('IMG_1.png'), f('IMG_2.png')], ORDER,
      { isFilled, startSectionId: 'charts' })
    expect(rowsOf(res)).toEqual([
      ['sla-1-pano-20261103-101500.png', null, 'filled', 'sla-1'],
      ['IMG_1.png', 'daily-errors', 'next', null],
      ['IMG_2.png', null, 'noSlot', null],
    ])
  })

  it('aynı yuvaya ikinci eşleşme "taken" (ilk dosya alır)', () => {
    const res = mapDroppedFiles([f('tech-errors-pano-1.png'), f('Mobil Kanalı Teknik Hata Grafiği.png')], ORDER)
    expect(rowsOf(res)).toEqual([
      ['tech-errors-pano-1.png', 'tech-errors', 'name', 'tech-errors'],
      ['Mobil Kanalı Teknik Hata Grafiği.png', null, 'taken', 'tech-errors'],
    ])
  })

  it(`en çok ${BULK_MAX_FILES} dosya; fazlası sayılır`, () => {
    const many = Array.from({ length: BULK_MAX_FILES + 3 }, (_, i) => f(`foto${i}.png`))
    const res = mapDroppedFiles(many, ORDER)
    expect(res.rows).toHaveLength(BULK_MAX_FILES)
    expect(res.over).toBe(3)
    expect(res.rows.filter((r) => r.how === 'next')).toHaveLength(ORDER.length)
    expect(res.rows.filter((r) => r.how === 'noSlot')).toHaveLength(BULK_MAX_FILES - ORDER.length)
  })

  it('bulkConflicts: aynı yuvayı seçen satırların ikisi de işaretlenir; atla (null) çakışmaz', () => {
    expect([...bulkConflicts({ 0: 'a', 1: 'b', 2: 'a', 3: null, 4: null })].sort()).toEqual([0, 2])
    expect(bulkConflicts({}).size).toBe(0)
  })
})

describe('inceleme düzeltmeleri (2026-11-20)', () => {
  const f = (name, type = 'image/png') => ({ name, type, size: 10 })
  const rowsOf = (res) => res.rows.map((r) => [r.name, r.target, r.how, r.match])
  const SMALL = [{ id: 'sla', label: 'SLA', sectionId: 'x' }, { id: 'other', label: 'Diğer görsel', sectionId: 'x' }]

  it.each([
    ['translation', 'sla', false],
    ['sla eylul', 'sla', true],
    ['eylul sla', 'sla', true],
    ['slab', 'sla', false],
    ['ay bazli toplam islem', 'toplam islem', true],
    ['', 'sla', false],
  ])('hasWords(%j, %j) → %s', (hay, needle, want) => {
    expect(hasWords(hay, needle)).toBe(want)
  })

  it('yuva adı TAM SÖZCÜK olarak eşleşir: 3 harflik "SLA" translation.png ile eşleşmez', () => {
    const res = mapDroppedFiles([f('translation.png'), f('SLA - Eylül.png')], SMALL)
    expect(rowsOf(res)).toEqual([
      ['translation.png', 'other', 'next', null],
      ['SLA - Eylül.png', 'sla', 'label', 'sla'],
    ])
  })

  it('bekletilen görseli olan yuva hedef olmaz: ad eşleşse "kept", sıradaki boş da atlar', () => {
    const isKept = (id) => id === 'monthly-tx'
    const res = mapDroppedFiles([f('monthly-tx-pano-20261103-101500.png'), f('IMG_1.png')], ORDER,
      { isKept, startSectionId: 'charts' })
    expect(rowsOf(res)).toEqual([
      ['monthly-tx-pano-20261103-101500.png', null, 'kept', 'monthly-tx'],
      ['IMG_1.png', 'tech-errors', 'next', null],
    ])
    expect(BULK_HOWS).toContain('kept')
  })
})
