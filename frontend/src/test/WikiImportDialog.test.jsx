import { describe, it, expect, vi, beforeEach } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { render, fireEvent, waitFor, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import WikiImportDialog, { selectedSections } from '../components/oncallroutine/WikiImportDialog.jsx'

/**
 * WİKİDEN İÇE AKTARMA penceresi (2026-11-26): yapıştırılan HTML önce, yoksa düz metin okunur; canlı önizlemede bölüm adı
 * düzenlenir, bölüm / görev seçimi kaldırılır, sıklık değiştirilir; mevcut taslak varsa "Ekle" ya da "Değiştir" (silinecek
 * görev sayısıyla sorulur); birleşik şablon sınırı aşılırsa içe aktarma kilitlenir. Hiçbir şey sunucuya gitmez.
 */
const MD = readFileSync(path.resolve(__dirname, 'fixtures/oncallRoutineWiki.md'), 'utf8')
const HTML = readFileSync(path.resolve(__dirname, 'fixtures/oncallRoutineWiki.html'), 'utf8')

const $ = (slot) => document.querySelector(`[data-slot="${slot}"]`)
const $$ = (slot) => [...document.querySelectorAll(`[data-slot="${slot}"]`)]
const input = () => $('or-imp-input')
const importBtn = () => $('or-imp-confirm')
const paste = (el, { html = '', text = '' }) => fireEvent.paste(el, {
  clipboardData: { getData: (type) => (type === 'text/html' ? html : type === 'text/plain' ? text : '') },
})
const task = (id, title) => ({ id, title, description: '', links: [], signals: [], required: true, active: true, frequency: 'DAILY' })
const confirmDialog = async () => {
  const box = await waitFor(() => { const c = document.querySelector('[data-slot="alert-dialog-content"]'); expect(c).not.toBeNull(); return c })
  const btns = within(box).getAllByRole('button')
  fireEvent.click(btns[btns.length - 1])
}

function mount(props = {}) {
  const onImport = vi.fn()
  const onClose = vi.fn()
  const utils = render(<WikiImportDialog open onClose={onClose} onImport={onImport} lang="tr" {...props} />)
  return { ...utils, onImport, onClose }
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

describe('yapıştırma', () => {
  it('HTML önceliklidir: biçim rozeti, 5 bölüm 21 görev önizlemesi', async () => {
    mount()
    expect(importBtn()).toBeDisabled()
    paste(input(), { html: HTML, text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    expect($('or-imp-format').dataset.format).toBe('html')
    expect($$('or-imp-task')).toHaveLength(21)
    expect(input().value).toBe(MD)
    expect($('or-imp-stats')).not.toBeNull()
    expect(importBtn()).not.toBeDisabled()
  })

  it('yalnız düz metin: markdown ayrıştırılır', async () => {
    mount()
    paste(input(), { text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    expect($('or-imp-format').dataset.format).toBe('text')
  })

  it('yazmak HTML\'yi bırakır ve yeniden ayrıştırır', async () => {
    mount()
    paste(input(), { html: HTML, text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    fireEvent.change(input(), { target: { value: '## Tek\n- Görev' } })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(1))
    expect($('or-imp-format').dataset.format).toBe('text')
  })

  it('anlamsız metin: "aktarılacak bir şey yok" bilgisi', async () => {
    mount()
    fireEvent.change(input(), { target: { value: 'Sadece bir paragraf.' } })
    await waitFor(() => expect($('or-imp-empty')).not.toBeNull())
    expect(importBtn()).toBeDisabled()
  })

  it('1 MB üstü: tehlike bandı, içe aktarma kapalı', async () => {
    mount()
    fireEvent.change(input(), { target: { value: `## X\n- A\n${'b'.repeat(1_000_001)}` } })
    await waitFor(() => expect($('or-imp-too-large')).not.toBeNull())
    expect(importBtn()).toBeDisabled()
  })
})

/**
 * İnceleme bulgusu #14 (2026-11-26): ikinci yapıştırma ilkini SİLMEZ — metin imlecin olduğu yere girer. Biçimli (HTML)
 * içerik yalnız boş kutuya ya da yine biçimli bir içeriğin SONUNA eklenince korunur; aksi hâlde düz metinden okunur ve bu
 * not (`or-imp-paste-note`, `data-note` appended | plain) ile söylenir. Boş kutuya yapıştırmak not göstermez (eski davranış).
 */
describe('ikinci yapıştırma (inceleme #14)', () => {
  const note = () => $('or-imp-paste-note')
  const caret = (el, start, end = start) => { el.focus(); el.setSelectionRange(start, end) }

  it('boş kutuya yapıştırma not göstermez', async () => {
    mount()
    paste(input(), { text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    expect(note()).toBeNull()
  })

  it('dolu kutuda imlece EKLER, ilk metin kalır (seçim yoksa silinmez); "eklendi" notu', async () => {
    mount()
    paste(input(), { text: '## İlk\n- Görev A' })
    await waitFor(() => expect(input().value).toBe('## İlk\n- Görev A'))
    // İmleç "## İlk\n" sonrasında (7. karakter): yeni madde ilk maddenin ÖNÜNE girer
    caret(input(), 7)
    paste(input(), { text: '- Görev B\n' })
    expect(input().value).toBe('## İlk\n- Görev B\n- Görev A')
    expect(note()).not.toBeNull()
    expect(note().dataset.note).toBe('appended')
    expect(note().textContent).toBe(TR['or.imp.pasteAppended'])
    await waitFor(() => expect($$('or-imp-task')).toHaveLength(2))
    expect($$('or-imp-section')).toHaveLength(1)
  })

  it('seçili metin varsa yalnız seçim yapıştırılanla değişir', async () => {
    mount()
    paste(input(), { text: '## İlk\n- Görev A\n- Görev C' })
    await waitFor(() => expect(input().value).toBe('## İlk\n- Görev A\n- Görev C'))
    // "Görev A" seçili (9–16)
    caret(input(), 9, 16)
    paste(input(), { text: 'Görev B' })
    expect(input().value).toBe('## İlk\n- Görev B\n- Görev C')
  })

  it('sona eklenen düz metin yeni satırdan başlar', async () => {
    mount()
    paste(input(), { text: '## İlk\n- Görev A' })
    await waitFor(() => expect(input().value).toBe('## İlk\n- Görev A'))
    const end = input().value.length
    caret(input(), end)
    paste(input(), { text: '## İkinci\n- Görev B' })
    expect(input().value).toBe('## İlk\n- Görev A\n## İkinci\n- Görev B')
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(2))
  })

  it('HTML sonuna HTML: iki biçimli parça da korunur ("eklendi"), biçim rozeti HTML kalır', async () => {
    mount()
    paste(input(), { html: '<h2>Sabah</h2><ul><li>Görev A</li></ul>', text: 'Sabah\nGörev A' })
    await waitFor(() => expect($('or-imp-format')?.dataset.format).toBe('html'))
    const end = input().value.length
    caret(input(), end)
    paste(input(), { html: '<h2>Akşam</h2><ul><li>Görev B</li></ul>', text: 'Akşam\nGörev B' })
    expect(note().dataset.note).toBe('appended')
    expect($('or-imp-format').dataset.format).toBe('html')
    expect(input().value).toBe('Sabah\nGörev A\nAkşam\nGörev B')
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(2))
    expect($$('or-imp-section').map((s) => s.querySelector('input:not([type="checkbox"])').value)).toEqual(['Sabah', 'Akşam'])
  })

  it('elle yazılan düz metne HTML yapıştırılırsa düz metinden okunur ve "düz metin" notu', async () => {
    mount()
    fireEvent.change(input(), { target: { value: '## Elle yazılan\n- Görev A' } })
    const end = input().value.length
    caret(input(), end)
    paste(input(), { html: '<h2>Wiki</h2><ul><li>Görev B</li></ul>', text: '## Wiki\n- Görev B' })
    expect(input().value).toBe('## Elle yazılan\n- Görev A\n## Wiki\n- Görev B')
    expect(note().dataset.note).toBe('plain')
    expect(note().textContent).toBe(TR['or.imp.pastePlain'])
    await waitFor(() => expect($('or-imp-format')?.dataset.format).toBe('text'))
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(2))
  })

  it('biçimli içeriğin ORTASINA HTML yapıştırılırsa biçim bırakılır ("düz metin" notu)', async () => {
    mount()
    paste(input(), { html: '<h2>Sabah</h2><ul><li>Görev A</li></ul>', text: '## Sabah\n- Görev A' })
    await waitFor(() => expect($('or-imp-format')?.dataset.format).toBe('html'))
    caret(input(), 0)
    paste(input(), { html: '<h2>Önce</h2><ul><li>Görev 0</li></ul>', text: '## Önce\n- Görev 0\n' })
    expect(input().value).toBe('## Önce\n- Görev 0\n## Sabah\n- Görev A')
    expect(note().dataset.note).toBe('plain')
    expect($('or-imp-format').dataset.format).toBe('text')
  })

  it('yazmaya devam etmek notu kaldırır', async () => {
    mount()
    paste(input(), { text: '## İlk\n- Görev A' })
    await waitFor(() => expect(input().value).toBe('## İlk\n- Görev A'))
    caret(input(), input().value.length)
    paste(input(), { text: '- Görev B' })
    expect(note()).not.toBeNull()
    fireEvent.change(input(), { target: { value: '## İlk\n- Görev A\n- Görev B\n- Görev C' } })
    expect(note()).toBeNull()
  })
})

describe('önizleme düzenleme ve içe aktarma', () => {
  it('bölüm adı, görev seçimi ve sıklık içe aktarılan bölümlere yansır', async () => {
    const { onImport } = mount()
    paste(input(), { text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    const first = $$('or-imp-section')[0]
    fireEvent.change(first.querySelector('input:not([type="checkbox"])'), { target: { value: 'Sabah kontrolleri' } })
    const tasks = first.querySelectorAll('[data-slot="or-imp-task"]')
    fireEvent.click(tasks[1].querySelector('[role="checkbox"]'))
    fireEvent.change(tasks[0].querySelector('select'), { target: { value: 'WEEKLY' } })
    // Son bölümü tümden çıkar
    const last = $$('or-imp-section')[4]
    fireEvent.click(last.querySelector('[role="checkbox"]'))
    await waitFor(() => expect(last.dataset.include).toBe('false'))
    fireEvent.click(importBtn())
    await waitFor(() => expect(onImport).toHaveBeenCalledTimes(1))
    const { mode, sections } = onImport.mock.calls[0][0]
    expect(mode).toBe('append')
    expect(sections.map((s) => s.title)).toEqual([
      'Sabah kontrolleri', 'Gün içi', 'Düzenli Gelen Mail İçerik kontrolleri', 'Haftalık periyotta',
    ])
    expect(sections[0].tasks).toHaveLength(7)
    expect(sections[0].tasks[0]).toMatchObject({ title: 'Cluster Monitoring', frequency: 'WEEKLY', weekday: 5, active: true })
    expect(sections[0].tasks[0]).not.toHaveProperty('include')
    expect(sections[0].tasks.map((t) => t.title)).not.toContain('OCP Watcher Errors Metrics')
  })

  it('boş bölüm adı içe aktarmayı kilitler', async () => {
    mount()
    paste(input(), { text: '## Tek\n- Görev' })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(1))
    fireEvent.change($('or-imp-section').querySelector('input:not([type="checkbox"])'), { target: { value: '  ' } })
    await waitFor(() => expect($('or-imp-limit')).not.toBeNull())
    expect(importBtn()).toBeDisabled()
  })

  it('taslakta görev varken "Değiştir" silinecek görev sayısını sorar', async () => {
    const draft = [{ id: 's-d', title: 'Eski', tasks: [task('t-d1', 'Eski görev'), task('t-d2', 'İkinci')] }]
    const { onImport } = mount({ draftSections: draft })
    paste(input(), { text: '## Yeni\n- Görev' })
    await waitFor(() => expect($('or-imp-mode')).not.toBeNull())
    expect($('or-imp-mode').dataset.mode).toBe('append')
    fireEvent.click(within($('or-imp-mode')).getAllByRole('button')[1])
    await waitFor(() => expect($('or-imp-mode').dataset.mode).toBe('replace'))
    fireEvent.click(importBtn())
    await confirmDialog()
    await waitFor(() => expect(onImport).toHaveBeenCalledTimes(1))
    expect(onImport.mock.calls[0][0].mode).toBe('replace')
  })

  it('"Değiştir" onayından vazgeçilirse içe aktarılmaz', async () => {
    const draft = [{ id: 's-d', title: 'Eski', tasks: [task('t-d1', 'Eski görev')] }]
    const { onImport } = mount({ draftSections: draft })
    paste(input(), { text: '## Yeni\n- Görev' })
    await waitFor(() => expect($('or-imp-mode')).not.toBeNull())
    fireEvent.click(within($('or-imp-mode')).getAllByRole('button')[1])
    fireEvent.click(importBtn())
    const box = await waitFor(() => { const c = document.querySelector('[data-slot="alert-dialog-content"]'); expect(c).not.toBeNull(); return c })
    fireEvent.click(within(box).getAllByRole('button')[0])
    await waitFor(() => expect(document.querySelector('[data-slot="alert-dialog-content"]')).toBeNull())
    expect(onImport).not.toHaveBeenCalled()
  })

  it('taslak boşken kip seçimi görünmez', async () => {
    mount({ draftSections: [] })
    paste(input(), { text: '## Yeni\n- Görev' })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(1))
    expect($('or-imp-mode')).toBeNull()
  })

  it('birleşik şablon sınırı aşılırsa nedeniyle kilitlenir', async () => {
    const draft = [0, 1, 2].map((n) => ({
      id: `s-${n}`, title: `B${n}`, tasks: Array.from({ length: 95 }, (_, i) => task(`t-${n}-${i}`, `G${n}-${i}`)),
    }))
    mount({ draftSections: draft })
    paste(input(), { text: MD })
    await waitFor(() => expect($$('or-imp-section')).toHaveLength(5))
    expect($('or-imp-limit')).not.toBeNull()
    expect(importBtn()).toBeDisabled()
    // "Değiştir" ile sığar
    fireEvent.click(within($('or-imp-mode')).getAllByRole('button')[1])
    await waitFor(() => expect($('or-imp-limit')).toBeNull())
    expect(importBtn()).not.toBeDisabled()
  })
})

describe('selectedSections', () => {
  it('seçimsiz görevli bölüm düşer; seçilen görev etkin olur, işaret alanı kalkar', () => {
    const rows = [
      { id: 's-1', title: ' A ', include: true, tasks: [{ ...task('t-1', 'X'), active: false, include: true }, { ...task('t-2', 'Y'), include: false }] },
      { id: 's-2', title: 'B', include: true, tasks: [{ ...task('t-3', 'Z'), include: false }] },
      { id: 's-3', title: 'C', include: false, tasks: [{ ...task('t-4', 'W'), include: true }] },
    ]
    const out = selectedSections(rows)
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ id: 's-1', title: 'A', tasks: [{ id: 't-1', active: true }] })
    expect(out[0].tasks[0]).not.toHaveProperty('include')
  })
})
