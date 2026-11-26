import { describe, it, expect } from 'vitest'
import {
  DEFAULT_LIMITS, DEFAULT_SETTINGS, ID_RE, addSection, addTask, applyImport, canonicalJson, collectIds, countActive, countLinks,
  countTasks, dirtyCount, errorCountsBySection, errorSectionId, errorTaskId, frequencyLabel, importLimitProblems, insertTask,
  isDirty, isSectionLevelKey, istanbulStamp, locateTask, mapServerErrors, moveSection, moveTask, moveTaskTo, newId,
  normalizeSettings, normalizeTemplate, pasteUrlIntoLink, removeSection, removeTask, reidSections, resolveLimits,
  settingsPatch, suggestLabel, taskErrorSignature, taskSummary, toWire, toggleSignal, updateTask, validateSettings,
  validateTemplate, visibleErrors, windowLabel, withFrequency, withMonthlyRule, linkUrlProblem,
} from '../components/oncallroutine/templateEditorModel.js'

/**
 * Nöbet rutini şablon düzenleyicisinin SAF modeli (2026-11-26): biçim, kimlik, taslak işlemleri, sunucu doğrulamasının
 * aynası, sunucu alan hatalarının eşlenmesi, wiki içe aktarımının taslağa uygulanması ve takım ayarlarının yalnız
 * değişen alanları.
 */
const task = (id, title = id, extra = {}) => ({ id, title, frequency: 'DAILY', ...extra })
const tpl = (...sections) => normalizeTemplate({ v: 1, sections })
const sec = (id, title, tasks = []) => ({ id, title, tasks })
const fakeT = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)

describe('biçim', () => {
  it('normalizeTemplate eksikleri doldurur, bozuğu atar', () => {
    const t = normalizeTemplate({ sections: [null, { id: 's-1', title: 'A', tasks: [{ id: 't-1', title: 'X', frequency: 'BOGUS', links: [null, { url: 'u' }], signals: [1, 'open_alerts'] }] }] })
    expect(t.sections).toHaveLength(1)
    expect(t.sections[0].tasks[0]).toMatchObject({
      frequency: 'DAILY', required: true, active: true, links: [{ label: '', url: 'u' }], signals: ['open_alerts'],
      window_from: null, description: '',
    })
    expect(normalizeTemplate(null)).toEqual({ v: 1, sections: [] })
  })

  it('toWire: tek satır başlık, boş bağlantı satırı düşer, sıklık yalnız kendi parametreleri', () => {
    const t = tpl(sec('s-1', '  Bölüm \n adı ', [
      task('t-1', ' Görev ', { links: [{ label: '', url: '' }, { label: ' L ', url: ' https://a.example.internal ' }], weekday: 3, month_day: 4 }),
      task('t-2', 'W', { frequency: 'WEEKLY', weekday: 2, month_day: 9 }),
      task('t-3', 'M', { frequency: 'MONTHLY', monthly_rule: 'NTH_WEEKDAY', nth: 2, weekday: 4, month_day: 9 }),
      task('t-4', 'D', { description: 'a\r\nb  ', signals: ['x', 'x'] }),
    ]))
    const w = toWire(t)
    expect(w.sections[0].title).toBe('Bölüm   adı')
    const [a, b, c, d] = w.sections[0].tasks
    expect(a).toMatchObject({ title: 'Görev', links: [{ label: 'L', url: 'https://a.example.internal' }], weekday: null, month_day: null })
    expect(b).toMatchObject({ weekday: 2, month_day: null, monthly_rule: null })
    expect(c).toMatchObject({ monthly_rule: 'NTH_WEEKDAY', nth: 2, weekday: 4, month_day: null })
    expect(d).toMatchObject({ description: 'a\nb', signals: ['x'] })
  })

  it('isDirty / dirtyCount: boşluk farkı değişiklik değildir', () => {
    const base = tpl(sec('s-1', 'A', [task('t-1', 'X')]))
    const same = tpl(sec('s-1', 'A ', [task('t-1', ' X')]))
    expect(isDirty(base, same)).toBe(false)
    expect(dirtyCount(base, same)).toBe(0)
    const changed = updateTask(base, 't-1', { title: 'Y', required: false })
    expect(isDirty(base, changed)).toBe(true)
    expect(dirtyCount(base, changed)).toBe(2)
    expect(isDirty(null, base)).toBe(true)
    expect(isDirty(base, null)).toBe(false)
    expect(canonicalJson(null)).toBe('')
  })

  it('sayımlar', () => {
    const t = tpl(sec('s-1', 'A', [task('t-1', 'X', { active: false, links: [{ url: 'https://a.example.internal' }, { label: '', url: '' }] }), task('t-2')]))
    expect([countTasks(t), countActive(t), countLinks(t)]).toEqual([2, 1, 1])
    expect(taskSummary(t.sections[0].tasks[0])).toEqual({ links: 1, signals: 0, description: false, window: false })
  })

  it('resolveLimits yalnız pozitif tam sayıları alır', () => {
    expect(resolveLimits({ sections: 10, tasks: -1, title: 'x' })).toMatchObject({ sections: 10, tasks: 300, title: 200 })
    expect(resolveLimits(null)).toEqual(DEFAULT_LIMITS)
  })
})

describe('kimlikler', () => {
  it('newId biçimli, benzersiz ve kümeye eklenir', () => {
    const taken = new Set()
    const ids = Array.from({ length: 200 }, (_, i) => newId(i % 2 ? 's' : 't', taken))
    expect(ids.every((id) => ID_RE.test(id))).toBe(true)
    expect(new Set(ids).size).toBe(200)
    expect(taken.size).toBe(200)
    expect(ids[0].startsWith('t-')).toBe(true)
    expect(ids[1].startsWith('s-')).toBe(true)
  })

  it('collectIds / reidSections çakışan kimliği değiştirir', () => {
    const taken = collectIds(tpl(sec('s-1', 'A', [task('t-1')])))
    expect([...taken]).toEqual(['s-1', 't-1'])
    const out = reidSections([sec('s-1', 'B', [task('t-1'), task('t-new'), task('BAD ID')])], taken)
    expect(out[0].id).not.toBe('s-1')
    expect(out[0].tasks[0].id).not.toBe('t-1')
    expect(out[0].tasks[1].id).toBe('t-new')
    expect(ID_RE.test(out[0].tasks[2].id)).toBe(true)
  })
})

describe('taslak işlemleri', () => {
  const base = () => tpl(sec('s-1', 'A', [task('t-1'), task('t-2'), task('t-3')]), sec('s-2', 'B', [task('t-4')]))

  it('ekle / sil / geri al', () => {
    const taken = collectIds(base())
    const r = addTask(base(), 's-2', taken)
    expect(locateTask(r.template, r.id)).toMatchObject({ sectionId: 's-2', index: 1 })
    const loc = locateTask(base(), 't-2')
    const removed = removeTask(base(), 't-2')
    expect(locateTask(removed, 't-2')).toBeNull()
    const back = insertTask(removed, loc.sectionId, loc.index, loc.task)
    expect(back.sections[0].tasks.map((t) => t.id)).toEqual(['t-1', 't-2', 't-3'])
    // Zaten varsa ikinci kez eklenmez
    expect(insertTask(back, 's-1', 0, loc.task)).toBe(back)
    // Bölüm silindiyse ilk bölüme döner
    const noSec = removeSection(removed, 's-1')
    expect(insertTask(noSec, 's-1', 0, loc.task).sections[0].tasks.map((t) => t.id)).toEqual(['t-2', 't-4'])
  })

  it('yukarı / aşağı taşıma uçta değişmez', () => {
    const b = base()
    expect(moveTask(b, 't-1', -1)).toEqual(b)
    expect(moveTask(b, 't-1', 1).sections[0].tasks.map((t) => t.id)).toEqual(['t-2', 't-1', 't-3'])
    expect(moveSection(b, 's-1', -1)).toBe(b)
    expect(moveSection(b, 's-1', 1).sections.map((s) => s.id)).toEqual(['s-2', 's-1'])
  })

  it('moveTaskTo: başka bölüme, bir görevin önüne ya da sona', () => {
    const b = base()
    expect(moveTaskTo(b, 't-1', 's-2', 't-4').sections[1].tasks.map((t) => t.id)).toEqual(['t-1', 't-4'])
    expect(moveTaskTo(b, 't-1', 's-2', null).sections[1].tasks.map((t) => t.id)).toEqual(['t-4', 't-1'])
    expect(moveTaskTo(b, 't-3', 's-1', 't-1').sections[0].tasks.map((t) => t.id)).toEqual(['t-3', 't-1', 't-2'])
    expect(moveTaskTo(b, 't-1', 's-yok', null)).toBe(b)
    expect(moveTaskTo(b, 't-1', 's-1', 't-1')).toBe(b)
  })

  it('addSection başlıkla', () => {
    const r = addSection(base(), new Set(), 'Yeni')
    expect(r.template.sections.at(-1)).toMatchObject({ id: r.id, title: 'Yeni', tasks: [] })
  })

  it('sıklık değişimi varsayılan parametreleri koyar', () => {
    const t = task('t-1')
    expect(withFrequency(t, 'WEEKLY')).toMatchObject({ frequency: 'WEEKLY', weekday: 5, monthly_rule: null })
    expect(withFrequency({ ...t, weekday: 2 }, 'WEEKLY').weekday).toBe(2)
    expect(withFrequency(t, 'MONTHLY')).toMatchObject({ frequency: 'MONTHLY', monthly_rule: 'FIRST_WORKDAY', weekday: null })
    expect(withFrequency({ ...t, frequency: 'WEEKLY', weekday: 3 }, 'DAILY')).toMatchObject({ frequency: 'DAILY', weekday: null })
    expect(withFrequency(t, 'X')).toBe(t)
    expect(withMonthlyRule(t, 'DAY')).toMatchObject({ monthly_rule: 'DAY', month_day: 1 })
    expect(withMonthlyRule(t, 'NTH_WEEKDAY')).toMatchObject({ monthly_rule: 'NTH_WEEKDAY', nth: 1, weekday: 1 })
    expect(withMonthlyRule({ ...t, month_day: 9 }, 'LAST_WORKDAY')).toMatchObject({ month_day: null, nth: null })
  })

  it('canlı sayaç en çok 4', () => {
    let t = task('t-1', 'X', { signals: [] })
    for (const k of ['a', 'b', 'c', 'd', 'e']) t = toggleSignal(t, k)
    expect(t.signals).toEqual(['a', 'b', 'c', 'd'])
    expect(toggleSignal(t, 'b').signals).toEqual(['a', 'c', 'd'])
  })
})

describe('bağlantılar', () => {
  it.each([
    ['', 'empty'],
    ['   ', 'empty'],
    ['ftp://files.example.internal/a', 'scheme'],
    ['javascript:alert(1)', 'scheme'],
    ['https://u:p@h.example.internal/', 'userinfo'],
    ['https://h.example.internal/a b', 'invalid'],
    ['https://h.example.internal/%zz', 'invalid'],
    ['https:/h.example.internal', 'invalid'],
    ['grafana.example.internal/d/1', 'invalid'],
    [`https://h.example.internal/${'x'.repeat(2000)}`, 'too_long'],
    ['https://grafana.example.internal/d/x?y=1#z', null],
    ['http://h.example.internal:8080/a/b@c', null],
  ])('%s → %s', (url, want) => {
    expect(linkUrlProblem(url)).toBe(want)
  })

  it('yapıştırma boş satıra adres + önerilen etiket', () => {
    expect(pasteUrlIntoLink({ label: '', url: '' }, ' https://a.example.internal/yol/?q=1 ')).toEqual({
      label: 'a.example.internal/yol', url: 'https://a.example.internal/yol/?q=1',
    })
    expect(pasteUrlIntoLink({ label: 'Var', url: '' }, 'https://a.example.internal')).toEqual({ label: 'Var', url: 'https://a.example.internal' })
    expect(pasteUrlIntoLink({ label: '', url: 'https://dolu.example.internal' }, 'https://a.example.internal')).toBeNull()
    expect(pasteUrlIntoLink({ label: '', url: '' }, 'düz metin')).toBeNull()
    expect(suggestLabel('bozuk')).toBe('')
  })
})

describe('doğrulama', () => {
  it('boş başlık "zorunlu" — kayıt denemesine dek gizli; geçersiz içerik her zaman', () => {
    const t = tpl(sec('s-1', '', [task('t-1', '', { links: [{ label: 'x', url: 'ftp://a' }] })]))
    const errs = validateTemplate(t)
    expect(errs['template.s-1.title']).toMatchObject({ k: 'or.tpl.v.required', required: true })
    expect(errs['template.s-1.t-1.title'].required).toBe(true)
    expect(errs['template.s-1.t-1.links.0.url']).toMatchObject({ k: 'or.tpl.v.urlScheme', required: false })
    expect(Object.keys(visibleErrors(errs, false))).toEqual(['template.s-1.t-1.links.0.url'])
    expect(Object.keys(visibleErrors(errs, true))).toHaveLength(3)
  })

  it('şablon düzeyi: bölüm yok, sınırlar, kimlik', () => {
    expect(validateTemplate(tpl()).template).toMatchObject({ k: 'or.tpl.v.sectionsMin', required: true })
    const many = tpl(...Array.from({ length: 31 }, (_, i) => sec(`s-${i}`, `S${i}`, [task(`t-${i}`)])))
    expect(validateTemplate(many).template).toMatchObject({ k: 'or.tpl.v.sectionsMax', a: [30, 31] })
    expect(validateTemplate(tpl(sec('S BAD', 'A', [task('t-1')]))).template.k).toBe('or.tpl.v.ids')
    expect(validateTemplate(tpl(sec('s-1', 'A', [task('t-1'), task('t-1')]))).template.k).toBe('or.tpl.v.ids')
    const active = tpl(...[0, 1, 2].map((n) => sec(`s-${n}`, 'X', Array.from({ length: 70 }, (_, i) => task(`t-${n}-${i}`)))))
    expect(validateTemplate(active).template).toMatchObject({ k: 'or.tpl.v.activeMax', a: [200, 210] })
  })

  it('başlık tek satır ve uzunluk', () => {
    const t = tpl(sec('s-1', 'A\nB', [task('t-1', 'x'.repeat(201))]))
    const errs = validateTemplate(t)
    expect(errs['template.s-1.title'].k).toBe('or.tpl.v.singleLine')
    expect(errs['template.s-1.t-1.title']).toMatchObject({ k: 'or.tpl.v.tooLong', a: [200] })
  })

  it('sıklık parametreleri', () => {
    const t = tpl(sec('s-1', 'A', [
      task('t-1', 'W', { frequency: 'WEEKLY', weekday: 9 }),
      task('t-2', 'M', { frequency: 'MONTHLY', monthly_rule: 'DAY', month_day: 32 }),
      task('t-3', 'N', { frequency: 'MONTHLY', monthly_rule: 'NTH_WEEKDAY', nth: 6, weekday: 1 }),
      task('t-4', 'R', { frequency: 'MONTHLY' }),
    ]))
    const errs = validateTemplate(t)
    expect(errs['template.s-1.t-1.weekday'].k).toBe('or.tpl.v.weekday')
    expect(errs['template.s-1.t-2.month_day'].k).toBe('or.tpl.v.monthDay')
    expect(errs['template.s-1.t-3.nth'].k).toBe('or.tpl.v.nth')
    expect(errs['template.s-1.t-4.monthly_rule'].k).toBe('or.tpl.v.monthlyRule')
  })

  it('saat aralığı biçimi ve sırası', () => {
    const t = tpl({ id: 's-1', title: 'A', window_from: '10:00', window_to: '09:00', tasks: [task('t-1', 'X', { window_from: '25:00' })] })
    const errs = validateTemplate(t)
    expect(errs['template.s-1.window_to'].k).toBe('or.tpl.v.windowOrder')
    expect(errs['template.s-1.t-1.window_from'].k).toBe('or.tpl.v.time')
  })

  it('bağlantı sayısı, açıklama, sayaç', () => {
    const links = Array.from({ length: 21 }, (_, i) => ({ label: `L${i}`, url: `https://l${i}.example.internal` }))
    const t = tpl(sec('s-1', 'A', [
      task('t-1', 'X', { links, description: 'd'.repeat(2001), signals: ['open_alerts', 'nope'] }),
    ]))
    const errs = validateTemplate(t, DEFAULT_LIMITS, { signalKeys: ['open_alerts'] })
    expect(errs['template.s-1.t-1.links'].k).toBe('or.tpl.v.linksMax')
    expect(errs['template.s-1.t-1.description']).toMatchObject({ k: 'or.tpl.v.tooLong', a: [2000] })
    expect(errs['template.s-1.t-1.signals'].k).toBe('or.tpl.v.signalUnknown')
  })

  it('anahtar yardımcıları', () => {
    expect(errorSectionId('template.s-1.title')).toBe('s-1')
    expect(errorSectionId('template')).toBeNull()
    expect(errorTaskId('template.s-1.title')).toBeNull()
    expect(errorTaskId('template.s-1.t-1.links.0.url')).toBe('t-1')
    expect(isSectionLevelKey('template.s-1.window_to')).toBe(true)
    expect(isSectionLevelKey('template.s-1.t-1.title')).toBe(false)
    const errs = { 'template.s-1.title': {}, 'template.s-1.t-1.title': { k: 'a' }, 'template.s-2.t-9.title': { k: 'b' }, template: {} }
    expect([...errorCountsBySection(errs)]).toEqual([['s-1', 2], ['s-2', 1]])
    expect(taskErrorSignature(errs, 't-1')).toBe('template.s-1.t-1.title=a')
    expect(taskErrorSignature(errs, 't-5')).toBe('')
  })

  it('sunucu alan hataları', () => {
    expect(mapServerErrors({
      success: false, error: 'Geçersiz', field: 'template.s-1.t-1.title', fields: { 'template.s-1.t-1.title': 'Boş', template: 'Genel' },
    })).toEqual({ fields: { 'template.s-1.t-1.title': 'Geçersiz' }, general: 'Genel' })
    expect(mapServerErrors({ success: false, error: 'Sadece metin' })).toEqual({ fields: {}, general: 'Sadece metin' })
  })
})

describe('wiki içe aktarımını uygulama', () => {
  const draft = () => tpl(sec('s-1', 'GÜN BAŞLANGICI', [task('t-1')]), sec('s-2', 'Gün içi', [task('t-2')]))
  const incoming = () => [sec('s-x', 'Gün başlangıcı', [task('t-x1'), task('t-1')]), sec('s-y', 'Aylık', [task('t-y1')])]

  it('ekle: aynı (Türkçe katlanmış) başlıklı bölüme eklenir, yeni bölüm sona', () => {
    const r = applyImport(draft(), incoming(), 'append', new Set())
    expect(r.removedTasks).toBe(0)
    expect(r.template.sections.map((s) => s.title)).toEqual(['GÜN BAŞLANGICI', 'Gün içi', 'Aylık'])
    expect(r.template.sections[0].tasks).toHaveLength(3)
    const ids = r.template.sections.flatMap((s) => s.tasks.map((t) => t.id))
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('değiştir: taslak silinir, silinen görev sayısı döner', () => {
    const r = applyImport(draft(), incoming(), 'replace', new Set())
    expect(r.removedTasks).toBe(2)
    expect(r.template.sections.map((s) => s.title)).toEqual(['Gün başlangıcı', 'Aylık'])
  })

  it('boş taslağa ekleme = yeni şablon', () => {
    expect(applyImport(null, incoming(), 'append').template.sections).toHaveLength(2)
  })

  it('birleşik sınır sorunları', () => {
    const big = tpl(...[0, 1, 2].map((n) => sec(`s-${n}`, `S${n}`, Array.from({ length: 101 }, (_, i) => task(`t-${n}-${i}`)))))
    const keys = importLimitProblems(big).map((p) => p.k)
    expect(keys).toEqual(['or.tpl.v.tasksMax', 'or.tpl.v.activeMax', 'or.tpl.v.sectionTasksMax'])
    expect(importLimitProblems(draft())).toEqual([])
  })
})

describe('gösterim', () => {
  it('istanbulStamp', () => {
    expect(istanbulStamp('2026-11-26T07:05:00')).toEqual({ date: '26.11.2026', hm: '10:05' })
    expect(istanbulStamp('2026-11-26T22:30:00Z')).toEqual({ date: '27.11.2026', hm: '01:30' })
    expect(istanbulStamp('2026-11-26T10:00:00+03:00')).toEqual({ date: '26.11.2026', hm: '10:00' })
    expect(istanbulStamp('bozuk')).toBeNull()
    expect(istanbulStamp(null)).toBeNull()
  })

  it('frequencyLabel / windowLabel', () => {
    expect(frequencyLabel(fakeT, { frequency: 'DAILY' })).toBe('or.freq.DAILY')
    expect(frequencyLabel(fakeT, { frequency: 'WEEKLY', weekday: 3 })).toBe('or.freq.WEEKLY · or.weekday.3')
    expect(frequencyLabel(fakeT, { frequency: 'MONTHLY', monthly_rule: 'DAY', month_day: 15 })).toBe('or.freq.MONTHLY · or.tpl.monthDayText(15)')
    expect(frequencyLabel(fakeT, { frequency: 'MONTHLY', monthly_rule: 'NTH_WEEKDAY', nth: 5, weekday: 5 }))
      .toBe('or.freq.MONTHLY · or.nth.5 or.weekday.5')
    expect(frequencyLabel(fakeT, { frequency: 'MONTHLY' })).toBe('or.freq.MONTHLY · or.monthly.FIRST_WORKDAY')
    expect(windowLabel(fakeT, '09:00', '11:00')).toBe('or.tpl.windowRange(09:00,11:00)')
    expect(windowLabel(fakeT, null, '11:00')).toBe('or.tpl.windowUntil(11:00)')
    expect(windowLabel(fakeT, '', '')).toBe('')
  })
})

describe('takım ayarları', () => {
  it('normalizeSettings: geçersiz → varsayılan', () => {
    expect(normalizeSettings(null)).toEqual(JSON.parse(JSON.stringify(DEFAULT_SETTINGS)))
    const s = normalizeSettings({
      enabled: 'evet', workdays: [3, 1, 1, 9], day_start: '7:00',
      reminders: { times: ['17:30', 'x', '16:30', '16:30', '08:00', '09:00', '10:00'] },
      completion_notice: { mail: false },
    })
    expect(s.enabled).toBe(true)
    expect(s.workdays).toEqual([1, 3])
    expect(s.day_start).toBe('09:00')
    expect(s.reminders.times).toEqual(['08:00', '09:00', '10:00', '16:30'])
    expect(s.completion_notice).toEqual({ enabled: true, mail: false, push: true })
    expect(normalizeSettings({ workdays: [] }).workdays).toEqual([1, 2, 3, 4, 5])
  })

  it('settingsPatch yalnız değişen alanlar (iç içe kısmi)', () => {
    const base = normalizeSettings(DEFAULT_SETTINGS)
    expect(settingsPatch(base, base)).toEqual({})
    const form = { ...base, workdays: [5, 1], reminders: { ...base.reminders, times: ['17:30', '16:30', '18:00'] }, handover_notice: { ...base.handover_notice, push: false } }
    expect(settingsPatch(base, form)).toEqual({
      workdays: [1, 5], reminders: { times: ['16:30', '17:30', '18:00'] }, handover_notice: { push: false },
    })
    // Yalnız sıra farkı değişiklik değildir
    expect(settingsPatch(base, { ...base, reminders: { ...base.reminders, times: ['17:30', '16:30'] } })).toEqual({})
    // Eklenmiş boş satır değişikliktir
    expect(settingsPatch(base, { ...base, reminders: { ...base.reminders, times: ['16:30', '17:30', ''] } })).toHaveProperty('reminders.times')
  })

  it('validateSettings yalnız değişen alanları denetler', () => {
    const base = normalizeSettings(DEFAULT_SETTINGS)
    const bad = {
      ...base, workdays: [], day_start: '25:00', reminders: { ...base.reminders, times: ['16:30', '16:30'] },
      completion_notice: { enabled: true, mail: false, push: false },
    }
    const errs = validateSettings(bad, settingsPatch(base, bad))
    expect(Object.keys(errs).sort()).toEqual([
      'settings.completion_notice.mail', 'settings.day_start', 'settings.reminders.times', 'settings.workdays',
    ])
    expect(errs['settings.reminders.times'].k).toBe('or.set.v.timesDuplicate')
    expect(validateSettings({ ...base, reminders: { ...base.reminders, times: ['16:30', ''] } }, { reminders: { times: [] } })['settings.reminders.times'].k)
      .toBe('or.set.v.timesInvalid')
    expect(validateSettings({ ...base, reminders: { ...base.reminders, times: ['1', '2', '3', '4', '5'] } }, { reminders: { times: [] } })['settings.reminders.times'])
      .toMatchObject({ k: 'or.set.v.timesMax', a: [4] })
    // Değişmeyen bozuk alan yeniden denetlenmez
    expect(validateSettings({ ...base, day_start: 'x' }, { workdays: [1] })).toEqual({})
  })
})
