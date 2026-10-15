import { describe, it, expect } from 'vitest'
import { groupByYear, istanbulDay, templateDiff } from '../components/techerror/techErrorModel.js'
import { TEMPLATE } from './helpers/techErrorFixtures.js'

// Faz B (2026-10-14): yıllara göre gruplama, İstanbul günü ve şablon sürümleri arası fark — saf işlevler.

describe('groupByYear', () => {
  it('en yeni yıl önce, yıl içinde en yeni ay önce; yılı okunamayan satır atlanır', () => {
    const g = groupByYear([{ id: 1, year: 2025, month: 12 }, { id: 2, year: 2026, month: 3 }, { id: 3, year: 2026, month: 9 },
      { id: 4, year: null, month: 1 }])
    expect(g.map((x) => x.year)).toEqual([2026, 2025])
    expect(g[0].items.map((x) => x.id)).toEqual([3, 2])
    expect(groupByYear(null)).toEqual([])
  })
})

describe('istanbulDay', () => {
  it('UTC 21:30 → İstanbul ertesi gün; bozuk tarih boş', () => {
    expect(istanbulDay('2026-10-13T21:30:00Z')).toBe('2026-10-14')
    expect(istanbulDay('2026-10-13T20:59:00Z')).toBe('2026-10-13')
    expect(istanbulDay('x')).toBe('')
  })
})

describe('templateDiff', () => {
  const clone = () => JSON.parse(JSON.stringify(TEMPLATE))

  it('aynı şablon → fark yok; okunamayan sürüm → null (bilinmiyor, "fark yok" değil)', () => {
    expect(templateDiff(TEMPLATE, clone())).toEqual([])
    expect(templateDiff(null, TEMPLATE)).toBeNull()
    expect(templateDiff(TEMPLATE, undefined)).toBeNull()
  })

  it('bölüm içi sıra: alan / grafik yuvası / tablo sütunu / satırı yer değiştirince tek "reorder" satırı', () => {
    const prev = clone()
    prev.sections.push({ id: 'tb', kind: 'table', title: 'T', columns: [{ id: 'c1', label: 'C1', type: 'number' }, { id: 'c2', label: 'C2', type: 'number' }],
      rows: [{ id: 'r1', label: 'R1' }, { id: 'r2', label: 'R2' }] })
    const cur = JSON.parse(JSON.stringify(prev))
    cur.sections[0].fields = [...cur.sections[0].fields].reverse()
    cur.sections[3].slots = [...cur.sections[3].slots].reverse()
    cur.sections[4].columns = [...cur.sections[4].columns].reverse()
    cur.sections[4].rows = [...cur.sections[4].rows].reverse()
    const d = templateDiff(prev, cur)
    expect(d.filter((x) => x.op === 'reorder').map((x) => x.group).sort()).toEqual(['column', 'field', 'row', 'slot'])
    expect(d.find((x) => x.group === 'field')).toMatchObject({ op: 'reorder', section: prev.sections[0].title })
    // yalnız ekleme sırayı "değişti" saymaz
    const added = clone()
    added.sections[0].fields = [...added.sections[0].fields, { id: 'yeni', label: 'Yeni', type: 'number' }]
    expect(templateDiff(TEMPLATE, added).map((x) => x.op)).toEqual(['add'])
  })

  it('başlık / dil, bölüm ekle-kaldır-yeniden adlandır, sıra değişimi', () => {
    const cur = clone()
    cur.title = 'Yeni başlık'
    cur.lang = 'en'
    cur.sections = cur.sections.filter((s) => s.id !== 'trend-note')
    cur.sections.push({ id: 'sla', kind: 'table', title: 'SLA', columns: [], rows: [] })
    cur.sections[0].title = 'Özet (yeni)'
    ;[cur.sections[1], cur.sections[2]] = [cur.sections[2], cur.sections[1]]
    const d = templateDiff(TEMPLATE, cur)
    const ops = d.map((x) => `${x.group}.${x.op}`)
    expect(ops).toEqual(expect.arrayContaining(['report.title', 'report.lang', 'section.remove', 'section.add', 'section.rename', 'section.reorder']))
    expect(d.find((x) => x.op === 'remove')).toMatchObject({ label: 'Grafikler hakkında', kind: 'text' })
    expect(d.find((x) => x.op === 'add')).toMatchObject({ label: 'SLA', kind: 'table' })
  })

  it('alan: eklenen, kaldırılan, yeniden adlandırılan, türü değişen, "Hayır" bağı değişen; bölüm adı ile', () => {
    const cur = clone()
    const f = cur.sections[0].fields
    f[0] = { ...f[0], label: 'Hata oranı' }
    f[1] = { ...f[1], type: 'percent' }
    f[2] = { ...f[2], skip_if_no: 'x' }
    f.push({ id: 'yeni', label: 'Yeni alan', type: 'number' })
    cur.sections[0].fields = f.filter((x) => x.id !== 'total-tx' || true)
    const prev = clone()
    prev.sections[0].fields.push({ id: 'eski', label: 'Eski alan', type: 'text' })
    const d = templateDiff(prev, cur).filter((x) => x.group === 'field')
    expect(d.map((x) => x.op).sort()).toEqual(['add', 'link', 'remove', 'rename', 'retype'])
    expect(d.find((x) => x.op === 'rename')).toMatchObject({ before: 'Teknik hata oranı', after: 'Hata oranı' })
    expect(d.find((x) => x.op === 'retype')).toMatchObject({ before: 'number', after: 'percent', section: TEMPLATE.sections[0].title })
  })

  it('grafik yuvası genişliği, özet cümleleri, metin bölümü, konu etiketleri, tablo sütun / satır / grup', () => {
    const prev = clone()
    prev.sections.push({ id: 'ex', kind: 'summary', title: 'Özet', lines: ['a', 'b'] })
    prev.sections.push({ id: 'tb', kind: 'table', title: 'T', columns: [{ id: 'c1', label: 'C1', type: 'number' }],
      rows: [{ id: 'r1', label: 'R1' }] })
    const cur = JSON.parse(JSON.stringify(prev))
    cur.sections[3].slots[0] = { ...cur.sections[3].slots[0], width: 'full' }
    cur.sections[4].lines = ['a', 'b', 'c']
    cur.sections[2].text = 'Başka metin'
    cur.sections[1].errors_label = 'Hata'
    cur.sections[5].columns = [{ id: 'c1', label: 'C1', type: 'percent' }, { id: 'c2', label: 'C2', type: 'text' }]
    cur.sections[5].rows = [{ id: 'r1', label: 'R1', group: true }]
    const ops = templateDiff(prev, cur).map((x) => `${x.group}.${x.op}`)
    expect(ops).toEqual(expect.arrayContaining(['slot.resize', 'summary.change', 'text.change', 'section.labels',
      'column.retype', 'column.add', 'row.group-on']))
    expect(templateDiff(prev, cur).find((x) => x.group === 'summary')).toMatchObject({ before: 2, after: 3 })
  })

  it('bölümler arası taşınan yuva / alan "kaldırıldı + eklendi" değil, tek "taşındı" satırı (inceleme 2026-10-15)', () => {
    const prev = clone()
    prev.sections.push({ id: 'imgs2', kind: 'images', title: 'Ay Bazlı', slots: [] })
    const cur = JSON.parse(JSON.stringify(prev))
    const moved = cur.sections[3].slots.shift()
    cur.sections[4].slots.push(moved)
    const d = templateDiff(prev, cur).filter((x) => x.group === 'slot')
    expect(d).toEqual([{ group: 'slot', op: 'move', label: moved.label, section: prev.sections[3].title, to: 'Ay Bazlı' }])
    // gerçekten silinen yuva hâlâ "kaldırıldı"
    const gone = JSON.parse(JSON.stringify(prev))
    gone.sections[3].slots.shift()
    expect(templateDiff(prev, gone).filter((x) => x.group === 'slot').map((x) => x.op)).toEqual(['remove'])
  })

  it('taşınan öğe: kaynak bölüm silindiyse hedefte "eklendi"; taşınırken adı / türü değişen öğenin değişiklikleri de listelenir', () => {
    const prev = clone()
    prev.sections.push({ id: 'imgs2', kind: 'images', title: 'Ay Bazlı', slots: [] })
    // kaynak bölüm (grafikler) silindi, yuva yeni bölüme taşındı → satır kaybolmaz
    const cur = JSON.parse(JSON.stringify(prev))
    const slot = cur.sections[3].slots[0]
    cur.sections[4].slots.push(slot)
    cur.sections.splice(3, 1)
    const d = templateDiff(prev, cur)
    expect(d).toEqual(expect.arrayContaining([
      expect.objectContaining({ group: 'section', op: 'remove' }),
      expect.objectContaining({ group: 'slot', op: 'add', label: slot.label, section: 'Ay Bazlı' }),
    ]))
    // alan bölümler arası taşındı + adı ve türü değişti
    const p2 = clone()
    p2.sections.push({ id: 'm2', kind: 'metrics', title: 'Yeni göstergeler', fields: [] })
    const c2 = JSON.parse(JSON.stringify(p2))
    const f = c2.sections[0].fields.shift()
    c2.sections[4].fields.push({ ...f, label: 'Yeni ad', type: 'text' })
    const fd = templateDiff(p2, c2).filter((x) => x.group === 'field')
    expect(fd.map((x) => x.op)).toEqual(['move', 'rename', 'retype'])
    expect(fd[1]).toMatchObject({ before: f.label, after: 'Yeni ad', section: 'Yeni göstergeler' })
    expect(fd[2]).toMatchObject({ before: f.type, after: 'text', section: 'Yeni göstergeler' })
  })

  it('aynı kimlikli bölümün türü değişirse alt fark aranmaz (retype tek satır)', () => {
    const cur = clone()
    cur.sections[2] = { id: 'trend-note', kind: 'summary', title: 'Grafikler hakkında', lines: [] }
    const d = templateDiff(TEMPLATE, cur)
    expect(d).toEqual([{ group: 'section', op: 'retype', label: 'Grafikler hakkında', before: 'text', after: 'summary' }])
  })
})
