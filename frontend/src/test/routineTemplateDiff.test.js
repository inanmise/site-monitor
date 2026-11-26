import { describe, it, expect } from 'vitest'
import {
  diffCounts, diffTemplates, frequencyOf, isEmptyDiff, linkHost, lisPositions,
} from '../components/oncallroutine/routineTemplateDiff.js'

/**
 * Nöbet rutini şablon sürümleri arası FARK (2026-11-26). Eşleştirme kalıcı kimlikle; "taşındı" en uzun artan alt dizi ile
 * (yer değiştiren tek görev sayılır, kaydırılan komşuları değil); bağlantılar yalnız etiket + ana makine — tam adres
 * (belirteç taşıyabilir) farkta hiç görünmez.
 */
const task = (id, title, extra = {}) => ({
  id, title, description: '', links: [], signals: [], required: true, active: true, frequency: 'DAILY', weekday: null,
  monthly_rule: null, month_day: null, nth: null, window_from: null, window_to: null, ...extra,
})
const sec = (id, title, tasks, extra = {}) => ({ id, title, window_from: null, window_to: null, tasks, ...extra })
const tpl = (...sections) => ({ v: 1, sections })
const clone = (x) => JSON.parse(JSON.stringify(x))

const BASE = tpl(
  sec('s-a', 'Gün başlangıcı', [task('t-1', 'Bir'), task('t-2', 'İki'), task('t-3', 'Üç'), task('t-4', 'Dört')]),
  sec('s-b', 'Gün içi', [task('t-5', 'Beş')]),
)

describe('linkHost / frequencyOf / lisPositions', () => {
  it('linkHost yalnız http(s) ana makinesi', () => {
    expect(linkHost('https://grafana.example.internal/d/x?token=gizli')).toBe('grafana.example.internal')
    expect(linkHost('ftp://files.example.internal/a')).toBe('')
    expect(linkHost('bozuk')).toBe('')
    expect(linkHost(null)).toBe('')
    expect(linkHost(`https://a.example.internal/${'x'.repeat(2100)}`)).toBe('')
  })

  it('frequencyOf yalnız kendi parametreleri', () => {
    expect(frequencyOf({ frequency: 'DAILY', weekday: 3 })).toEqual({ frequency: 'DAILY' })
    expect(frequencyOf({ frequency: 'WEEKLY' })).toEqual({ frequency: 'WEEKLY', weekday: 5 })
    expect(frequencyOf({ frequency: 'MONTHLY', monthly_rule: 'DAY', month_day: 15, weekday: 2 }))
      .toEqual({ frequency: 'MONTHLY', monthly_rule: 'DAY', month_day: 15 })
    expect(frequencyOf({ frequency: 'BOGUS' })).toEqual({ frequency: 'DAILY' })
  })

  it('lisPositions', () => {
    expect([...lisPositions([0, 1, 2, 3])].sort()).toEqual([0, 1, 2, 3])
    expect(lisPositions([3, 0, 1, 2]).size).toBe(3)
    expect(lisPositions([]).size).toBe(0)
  })
})

describe('diffTemplates', () => {
  it('ilk sürüm: her şey eklendi', () => {
    const d = diffTemplates(null, BASE)
    expect(d.sections.added.map((x) => x.title)).toEqual(['Gün başlangıcı', 'Gün içi'])
    expect(d.tasks.added).toHaveLength(5)
    expect(d.tasks.added[0]).toEqual({ id: 't-1', title: 'Bir', section: 'Gün başlangıcı' })
    expect(d.count).toBe(7)
  })

  it('aynı şablon: fark yok', () => {
    const d = diffTemplates(BASE, clone(BASE))
    expect(isEmptyDiff(d)).toBe(true)
    expect(isEmptyDiff(null)).toBe(true)
  })

  it('bölüm adı, saat aralığı, ekleme ve silme', () => {
    const next = tpl(
      sec('s-a', 'Sabah', BASE.sections[0].tasks, { window_from: '09:00', window_to: '11:00' }),
      sec('s-c', 'Akşam', []),
    )
    const d = diffTemplates(BASE, next)
    expect(d.sections.renamed).toEqual([{ id: 's-a', from: 'Gün başlangıcı', to: 'Sabah' }])
    expect(d.sections.window[0]).toMatchObject({ id: 's-a', from: { from: null, to: null }, to: { from: '09:00', to: '11:00' } })
    expect(d.sections.added).toEqual([{ id: 's-c', title: 'Akşam' }])
    expect(d.sections.removed).toEqual([{ id: 's-b', title: 'Gün içi' }])
    expect(d.tasks.removed).toEqual([{ id: 't-5', title: 'Beş', section: 'Gün içi' }])
  })

  it('bir görevin yeri değişince yalnız o görev taşındı sayılır', () => {
    const [a, b, c, e] = BASE.sections[0].tasks
    const next = tpl(sec('s-a', 'Gün başlangıcı', [e, a, b, c]), BASE.sections[1])
    const d = diffTemplates(BASE, next)
    expect(d.tasks.moved).toEqual([{ id: 't-4', title: 'Dört', from_section: 'Gün başlangıcı', to_section: 'Gün başlangıcı' }])
    expect(d.count).toBe(1)
  })

  it('bölümler arası taşıma', () => {
    const [a, b, c, e] = BASE.sections[0].tasks
    const next = tpl(sec('s-a', 'Gün başlangıcı', [a, b, e]), sec('s-b', 'Gün içi', [...BASE.sections[1].tasks, c]))
    const d = diffTemplates(BASE, next)
    expect(d.tasks.moved).toEqual([{ id: 't-3', title: 'Üç', from_section: 'Gün başlangıcı', to_section: 'Gün içi' }])
  })

  it('bölüm sırası', () => {
    const d = diffTemplates(BASE, tpl(BASE.sections[1], BASE.sections[0]))
    expect(d.sections.moved).toHaveLength(1)
  })

  it('görev alanları: ad, sıklık, zorunlu, etkin, açıklama, saat aralığı', () => {
    const next = clone(BASE)
    const t = next.sections[0].tasks
    t[0].title = 'Bir (yeni)'
    t[1].frequency = 'WEEKLY'
    t[1].weekday = 2
    t[2].required = false
    t[3].active = false
    t[3].description = 'Açıklama'
    next.sections[1].tasks[0].window_to = '12:00'
    const d = diffTemplates(BASE, next)
    expect(d.tasks.renamed).toEqual([{ id: 't-1', from: 'Bir', to: 'Bir (yeni)', section: 'Gün başlangıcı' }])
    expect(d.tasks.frequency).toEqual([{ id: 't-2', title: 'İki', from: { frequency: 'DAILY' }, to: { frequency: 'WEEKLY', weekday: 2 } }])
    expect(d.tasks.required).toEqual([{ id: 't-3', title: 'Üç', to: false }])
    expect(d.tasks.active).toEqual([{ id: 't-4', title: 'Dört', to: false }])
    expect(d.tasks.description).toEqual([{ id: 't-4', title: 'Dört' }])
    expect(d.tasks.window[0]).toMatchObject({ id: 't-5', to: { from: null, to: '12:00' } })
    expect(diffCounts(d)).toMatchObject({ 'tasks.renamed': 1, 'tasks.frequency': 1, 'sections.added': 0 })
  })

  it('bağlantılar yalnız etiket + ana makine; tam adres farkta yok', () => {
    const prev = tpl(sec('s-a', 'A', [task('t-1', 'Bir', { links: [
      { label: 'Eski', url: 'https://old.example.internal/x?token=GIZLI1' },
      { label: 'Panel', url: 'https://grafana.example.internal/d/1?apikey=GIZLI2' },
    ] })]))
    const next = tpl(sec('s-a', 'A', [task('t-1', 'Bir', { links: [
      { label: 'Pano', url: 'https://grafana.example.internal/d/1?apikey=GIZLI2' },
      { label: 'Yeni', url: 'https://new.example.internal/y?sig=GIZLI3' },
    ] })]))
    const d = diffTemplates(prev, next)
    expect(d.tasks.links).toEqual([{
      id: 't-1', title: 'Bir',
      added: [{ label: 'Yeni', host: 'new.example.internal' }],
      removed: [{ label: 'Eski', host: 'old.example.internal' }],
      relabeled: [{ from: 'Panel', to: 'Pano', host: 'grafana.example.internal' }],
    }])
    expect(JSON.stringify(d)).not.toMatch(/GIZLI|token=|apikey|sig=/)
  })

  it('tamamen boş bağlantı satırı fark sayılmaz', () => {
    const next = clone(BASE)
    next.sections[0].tasks[0].links = [{ label: '', url: '' }]
    expect(isEmptyDiff(diffTemplates(BASE, next))).toBe(true)
  })

  it('canlı sayaçlar', () => {
    const prev = tpl(sec('s-a', 'A', [task('t-1', 'Bir', { signals: ['open_alerts', 'down_monitors'] })]))
    const next = tpl(sec('s-a', 'A', [task('t-1', 'Bir', { signals: ['down_monitors', 'expiring_certs'] })]))
    expect(diffTemplates(prev, next).tasks.signals).toEqual([
      { id: 't-1', title: 'Bir', added: ['expiring_certs'], removed: ['open_alerts'] },
    ])
  })

  it('bozuk girdi atılmaz', () => {
    expect(diffTemplates({ sections: [null, 5, { id: 's', title: 'X', tasks: [null] }] }, { sections: 'x' }).sections.removed)
      .toEqual([{ id: 's', title: 'X' }])
  })
})
