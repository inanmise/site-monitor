import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'

// "Hızlı Giriş" (2026-11-20) — olay formu:
//  • hızlı kayıt: aynı formun beş alanlık sade görünümü; "Tüm alanları göster" yazılanları korur; gizli alanda sunucu
//    hatası tam görünüme geçirir,
//  • çoğaltma: bilgi bandı (kaynak kimliği, çıkarılan görseller, düşen takım), kendi taslak kapsamı,
//  • özet satırdan düzenleme: markdown alanları taze okumaya dek "yükleniyor", okunamazsa "okunamadı + yeniden dene";
//    markdown okunmadan kayıtta o alanlar gövdeye GİRMEZ,
//  • runbook bağlantısı alanı (yalnız http(s)).

const { apiMock, toastMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {} }), toastMock: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }
})
vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate: (s) => `F(${s})`,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange, textareaProps }) => (
    <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} {...(textareaProps || {})} />
  ),
}))

import IncidentFormModal from '../components/incidenthistory/IncidentFormModal.jsx'
import { quickInitial } from '../components/incidenthistory/quickEntryModel.js'
import { MARKDOWN_KEYS } from '../components/incidenthistory/incidentHistoryModel.js'
import { uploadPlaceholder, beginUpload, endUpload, resetUploadsForTests } from '../utils/mdUpload.js'

const OWNER = 'ayse'
const TEAMS = [{ id: 1, name: 'Takım A' }]
const FULL = Object.freeze({
  id: 9, version: 2, title: 'Ödeme kesintisi', occurred_at: '2026-11-16T10:00:00', team_id: 1, team_name: 'Takım A',
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', rca_summary: 'Kök neden metni', description: 'Açıklama metni',
  resolution_steps: 'Çözüm metni', business_impact: 'İş etkisi metni',
})
const SUMMARY = Object.freeze(Object.fromEntries([
  ...Object.entries(FULL).filter(([k]) => !MARKDOWN_KEYS.includes(k)), ['markdown_omitted', true],
]))

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  localStorage.setItem('sm.storage.owner', OWNER)
  apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: FULL }))
  toastMock.success.mockClear(); toastMock.error.mockClear(); toastMock.info.mockClear()
})
afterEach(() => {
  for (const k of Object.keys(localStorage)) if (k.startsWith('sm.ih.draft.')) localStorage.removeItem(k)
  localStorage.removeItem('site-monitor-lang')
  localStorage.removeItem('sm.storage.owner')
  resetUploadsForTests()
})

const EDIT_DRAFT_KEY = () => `sm.ih.draft.9:${OWNER}`
/** Düzenleme taslağını (kayıt 9, sürüm 2) doğrudan yazar. */
function seedDraft(changes) {
  localStorage.setItem(EDIT_DRAFT_KEY(), JSON.stringify({ v: 1, at: Date.now(), base_version: 2, base_updated_at: null, changes }))
}
const storedDraft = (key = EDIT_DRAFT_KEY()) => JSON.parse(localStorage.getItem(key) || 'null')

/** Radix Tabs jsdom'da mousedown ile değişir. */
/** Etkin bölüm (bölümler hep DOM'da; gizli olanlar `hidden`). */
const pane = (f) => f.dlg.querySelector('[data-slot="tabs-content"]:not([hidden])')

async function go(f, key) {
  fireEvent.mouseDown(f.tab(key))
  await waitFor(() => expect(f.tab(key)).toHaveAttribute('aria-selected', 'true'))
}

function open({ mode = 'edit', initial = FULL, quick = false, copyOf = null, onSubmit = vi.fn(async () => ({ ok: true })), onClose = vi.fn() } = {}) {
  const utils = render(<IncidentFormModal mode={mode} initial={{ ...initial }} teams={TEAMS} quick={quick} copyOf={copyOf}
    onSubmit={onSubmit} onClose={onClose} onFresh={vi.fn()} />)
  const dlg = screen.getByRole('dialog')
  return {
    ...utils, dlg, onSubmit, onClose,
    form: () => dlg.querySelector('[data-slot="incident-form"]'),
    title: () => within(dlg).getByRole('textbox', { name: /^Başlık/ }),
    save: () => fireEvent.click(dlg.querySelector('[data-action="save"]')),
    tab: (key) => dlg.querySelector(`[data-section="${key}"]`),
    field: (name) => dlg.querySelector(`[data-field="${name}"]`),
  }
}

describe('hızlı kayıt', () => {
  it('sade görünüm: beş alan, sekme yok; kayıt tam gövdeyle gider', async () => {
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    expect(f.form()).toHaveAttribute('data-view', 'quick')
    expect(f.dlg.querySelector('[data-slot="ih-quick"]')).not.toBeNull()
    expect(f.tab('impact')).toBeNull()
    expect(within(f.dlg).getAllByText(TR['inc.quick.title']).length).toBeGreaterThan(0)
    for (const name of ['title', 'occurred_at', 'team_id', 'severity', 'status']) expect(f.field(name)).not.toBeNull()
    expect(f.field('runbook_url')).toBeNull()
    fireEvent.change(f.title(), { target: { value: 'Hızlı olay' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toMatchObject({ title: 'Hızlı olay', team_id: '1', occurred_at: '2026-11-20T09:00:00', status: 'OPEN' })
    expect(apiMock.incidents.get).not.toHaveBeenCalled()
  })

  it('"Tüm alanları göster" yazılanları korur', () => {
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    fireEvent.change(f.title(), { target: { value: 'Yazdığım' } })
    fireEvent.click(f.dlg.querySelector('[data-action="quick-expand"]'))
    expect(f.form()).toHaveAttribute('data-view', 'full')
    expect(f.title()).toHaveValue('Yazdığım')
    expect(f.tab('impact')).not.toBeNull()
  })

  it('istemci hatası görünen alandaysa hızlı görünüm kalır', () => {
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    expect(f.form()).toHaveAttribute('data-view', 'quick')
    expect(f.field('title').textContent).toContain(TR['inc.errTitle'])
  })

  it('sunucu gizli alanda hata dönerse tam görünüme geçer, hata alanın altında', async () => {
    const onSubmit = vi.fn(async () => ({ ok: false, error: 'x', fields: { runbook_url: 'Sunucu: runbook bağlantısı geçersiz.' } }))
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true, onSubmit })
    fireEvent.change(f.title(), { target: { value: 'Hızlı olay' } })
    f.save()
    await waitFor(() => expect(f.form()).toHaveAttribute('data-view', 'full'))
    await waitFor(() => expect(f.field('runbook_url')?.textContent).toContain('Sunucu: runbook bağlantısı geçersiz.'))
  })
})

describe('çoğaltma', () => {
  it('bilgi bandı: kaynak kimliği, çıkarılan görseller, düşen takım; başlık "çoğalt"', () => {
    const f = open({ mode: 'create', initial: { title: 'Kopya', description: 'x' }, copyOf: { id: 41, imagesRemoved: 2, teamDropped: true } })
    const note = f.dlg.querySelector('[data-slot="ih-copy-note"]')
    expect(note).not.toBeNull()
    expect(note.textContent).toContain(TR['inc.copy.title'].replace('{0}', '41'))
    expect(note.querySelector('[data-slot="ih-copy-images"]').textContent).toBe(TR['inc.copy.images'].replace('{0}', '2'))
    expect(note.querySelector('[data-slot="ih-copy-team"]')).not.toBeNull()
    expect(within(f.dlg).getAllByText(TR['inc.copy.formTitle']).length).toBeGreaterThan(0)
    expect(f.form()).toHaveAttribute('data-view', 'full')
  })

  it('tek görsel tekil metin; görsel yoksa satır yok', () => {
    const one = open({ mode: 'create', initial: {}, copyOf: { id: 5, imagesRemoved: 1, teamDropped: false } })
    expect(one.dlg.querySelector('[data-slot="ih-copy-images"]').textContent).toBe(TR['inc.copy.images.one'])
    expect(one.dlg.querySelector('[data-slot="ih-copy-team"]')).toBeNull()
    one.unmount()
    const none = open({ mode: 'create', initial: {}, copyOf: { id: 5, imagesRemoved: 0, teamDropped: false } })
    expect(none.dlg.querySelector('[data-slot="ih-copy-images"]')).toBeNull()
  })

  it('taslak kendi kapsamında saklanır (yeni kayıt taslağını ezmez)', async () => {
    const f = open({ mode: 'create', initial: { title: 'Kopya' }, copyOf: { id: 41, imagesRemoved: 0, teamDropped: false } })
    fireEvent.change(f.title(), { target: { value: 'Kopya düzenlendi' } })
    f.unmount()
    expect(JSON.parse(localStorage.getItem(`sm.ih.draft.new.copy.41:${OWNER}`)).changes.title).toBe('Kopya düzenlendi')
    expect(localStorage.getItem(`sm.ih.draft.new:${OWNER}`)).toBeNull()
  })
})

describe('özet satırdan düzenleme — markdown', () => {
  it('taze okumaya dek "yükleniyor"; gelince metin görünür; kayıtta markdown değişmediği için gövdede yok', async () => {
    const first = deferred()
    apiMock.incidents.get = vi.fn(() => first.promise)
    const f = open({ initial: SUMMARY })
    await go(f, 'rca')
    expect(pane(f).querySelectorAll('[data-slot="ih-md-loading"]').length).toBe(3)
    expect(pane(f).querySelector('[data-slot="md-edit"]')).toBeNull()
    await act(async () => { first.resolve({ success: true, data: FULL }) })
    await waitFor(() => expect(pane(f).querySelectorAll('[data-slot="md-edit"]').length).toBe(3))
    expect([...pane(f).querySelectorAll('[data-slot="md-edit"]')].map((el) => el.value))
      .toEqual(['Kök neden metni', 'Açıklama metni', 'Çözüm metni'])
    // markdown'ın "boş → metin" olması başkasının değişikliği sayılmaz
    expect(f.dlg.querySelector('[data-slot="ih-fresh-note"][data-kind="updated"]')).toBeNull()
    await go(f, 'summary')
    fireEvent.change(f.title(), { target: { value: 'Yeni başlık' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, title: 'Yeni başlık' })
  })

  it('yazmaya başlandıysa markdown yine işlenir, yazılan kalır', async () => {
    const first = deferred()
    apiMock.incidents.get = vi.fn(() => first.promise)
    const f = open({ initial: SUMMARY })
    fireEvent.change(f.title(), { target: { value: 'Benim başlığım' } })
    await act(async () => { first.resolve({ success: true, data: FULL }) })
    expect(f.title()).toHaveValue('Benim başlığım')
    await go(f, 'rca')
    await waitFor(() => expect(pane(f).querySelectorAll('[data-slot="md-edit"]').length).toBe(3))
    expect(pane(f).querySelector('[data-slot="md-edit"]')).toHaveValue('Kök neden metni')
  })

  it('okunamazsa "okunamadı + yeniden dene"; kayıtta markdown gönderilmez; yeniden dene okur', async () => {
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: false, error: 'Sunucuya ulaşılamadı.' }))
    const f = open({ initial: SUMMARY })
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-md-note"]')).not.toBeNull())
    await go(f, 'rca')
    const failed = pane(f).querySelectorAll('[data-slot="ih-md-failed"]')
    expect(failed.length).toBe(3)
    expect(failed[0].textContent).toContain(TR['inc.md.failed'])

    await go(f, 'summary')
    fireEvent.change(f.title(), { target: { value: 'Başlık değişti' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    const body = f.onSubmit.mock.calls[0][0]
    for (const k of MARKDOWN_KEYS) expect(body).not.toHaveProperty(k)
    expect(body.title).toBe('Başlık değişti')
  })

  it('yeniden dene: ikinci okuma başarılıysa metin gelir', async () => {
    apiMock.incidents.get = vi.fn()
      .mockImplementationOnce(() => Promise.resolve({ success: false, error: 'x' }))
      .mockImplementation(() => Promise.resolve({ success: true, data: FULL }))
    const f = open({ initial: SUMMARY })
    await go(f, 'rca')
    await waitFor(() => expect(pane(f).querySelectorAll('[data-slot="ih-md-failed"]').length).toBe(3))
    fireEvent.click(pane(f).querySelector('[data-action="md-retry"]'))
    await waitFor(() => expect(pane(f).querySelectorAll('[data-slot="md-edit"]').length).toBe(3))
    expect(apiMock.incidents.get).toHaveBeenCalledTimes(2)
    expect(f.dlg.querySelector('[data-slot="ih-md-note"]')).toBeNull()
  })

  it('tam satırdan açılan düzenleme beklemez (markdown hazır)', async () => {
    const f = open({ initial: FULL })
    await go(f, 'rca')
    expect(pane(f).querySelectorAll('[data-slot="md-edit"]').length).toBe(3)
    expect(pane(f).querySelector('[data-slot="ih-md-loading"]')).toBeNull()
  })

  it('markdown alanının yazı kutusu etiket ve ipucuyla adlandırılır', async () => {
    const f = open({ initial: FULL })
    await go(f, 'rca')
    const area = pane(f).querySelector('[data-slot="md-edit"]')
    const labelledBy = area.getAttribute('aria-labelledby')
    expect(labelledBy).toBeTruthy()
    expect(document.getElementById(labelledBy.split(' ')[0])?.textContent).toContain(TR['inc.fRca'])
  })
})

describe('runbook bağlantısı', () => {
  it('http(s) dışı adres kaydedilmez; hata alanın altında', async () => {
    const f = open({ initial: FULL })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await go(f, 'codes')
    const input = f.field('runbook_url').querySelector('input')
    fireEvent.change(input, { target: { value: 'javascript:alert(1)' } })
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    await waitFor(() => expect(f.field('runbook_url').textContent).toContain(TR['inc.errRunbookUrl']))
  })

  it('geçerli adres yalnız değişen alan olarak gider', async () => {
    const f = open({ initial: FULL })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    await go(f, 'codes')
    fireEvent.change(f.field('runbook_url').querySelector('input'), { target: { value: 'https://wiki.example.com/r' } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, runbook_url: 'https://wiki.example.com/r' })
  })
})

describe('bölümler hep DOM\'da (inceleme H2a)', () => {
  it('beş bölümün içeriği takılı; yalnız etkin olan görünür', async () => {
    const f = open({ initial: FULL })
    const panes = f.dlg.querySelectorAll('[data-slot="tabs-content"]')
    expect(panes.length).toBe(5)
    expect([...panes].filter((p) => !p.hidden).length).toBe(1)
    // gizli bölümün alanı DOM'da ama erişilebilirlik ağacında yok (rol sorguları tek öğe bulur)
    expect(f.field('rca_summary')).not.toBeNull()
    expect(within(f.dlg).getAllByRole('textbox', { name: /^Başlık/ })).toHaveLength(1)
    await go(f, 'rca')
    expect(within(f.dlg).queryAllByRole('textbox', { name: /^Başlık/ })).toHaveLength(0)
    expect(f.title).toThrow()
  })
})

describe('yükleme yer tutucusu (inceleme H2c)', () => {
  it('sahipsiz (yüklemesi sürmeyen) yer tutucu kaydı durdurmaz; gövdeye girmez', async () => {
    const f = open({ initial: FULL })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    await go(f, 'rca')
    fireEvent.change(pane(f).querySelector('[data-slot="md-edit"]'),
      { target: { value: `Yeni kök neden${uploadPlaceholder('g yükleniyor…', 'eskibelirtec')}` } })
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, rca_summary: 'Yeni kök neden' })
  })

  it('yüklemesi SÜREN yer tutucu kaydı durdurur; yükleme bitince kaydedilir', async () => {
    const f = open({ initial: FULL })
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalled())
    await act(async () => {})
    await go(f, 'rca')
    beginUpload('canli')
    fireEvent.change(pane(f).querySelector('[data-slot="md-edit"]'),
      { target: { value: `Yeni${uploadPlaceholder('g yükleniyor…', 'canli')}` } })
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    await waitFor(() => expect(f.field('rca_summary').textContent).toContain(TR['inc.errUploadPending']))
    endUpload('canli')
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, rca_summary: 'Yeni' })
  })

  it('geri yüklenen taslaktaki sahipsiz yer tutucu uygulanmaz', async () => {
    seedDraft({ description: `Taslak açıklama${uploadPlaceholder('g', 'eski')}` })
    const f = open({ initial: FULL })
    await waitFor(() => expect(f.dlg.querySelector('[data-action="draft-restore"]')).not.toBeNull())
    await act(async () => {})
    fireEvent.click(f.dlg.querySelector('[data-action="draft-restore"]'))
    await go(f, 'rca')
    const mds = pane(f).querySelectorAll('[data-slot="md-edit"]')
    expect(mds[1]).toHaveValue('Taslak açıklama')
  })
})

describe('taslak — kaydın metinleri okunmadan (inceleme M1)', () => {
  const restoreBtn = (f) => f.dlg.querySelector('[data-action="draft-restore"]')

  it('metinler yüklenirken geri yükleme: metin değişiklikleri uygulanmaz, saklanır; okununca yeniden önerilir', async () => {
    seedDraft({ title: 'Taslak başlık', rca_summary: 'Taslak kök neden' })
    const first = deferred()
    apiMock.incidents.get = vi.fn(() => first.promise)
    const f = open({ initial: SUMMARY })
    fireEvent.click(restoreBtn(f))
    expect(f.title()).toHaveValue('Taslak başlık')
    expect(f.dlg.querySelector('[data-slot="ih-draft-md-held"]').textContent).toContain(TR['inc.draft.mdHeld'])
    expect(f.dlg.querySelector('[data-slot="ih-draft"]')).toBeNull()
    // saklanan metin değişikliği taslaktan SİLİNMEZ
    expect(storedDraft().changes.rca_summary).toBe('Taslak kök neden')
    await act(async () => { first.resolve({ success: true, data: FULL }) })
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-draft"]')).not.toBeNull())
    expect(f.dlg.querySelector('[data-slot="ih-draft-md-held"]')).toBeNull()
    await go(f, 'rca')
    expect(pane(f).querySelector('[data-slot="md-edit"]')).toHaveValue('Kök neden metni')
    fireEvent.click(restoreBtn(f))
    await waitFor(() => expect(pane(f).querySelector('[data-slot="md-edit"]')).toHaveValue('Taslak kök neden'))
    await go(f, 'summary')
    expect(f.title()).toHaveValue('Taslak başlık')
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, title: 'Taslak başlık', rca_summary: 'Taslak kök neden' })
  })

  it('metinler okunamadıysa: metin değişikliği saklı kalır; kayıt yalnız uygulanan anahtarları taslaktan siler', async () => {
    seedDraft({ title: 'Taslak başlık', rca_summary: 'Taslak kök neden' })
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: false, error: 'Sunucuya ulaşılamadı.' }))
    const f = open({ initial: SUMMARY })
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-md-note"]')).not.toBeNull())
    fireEvent.click(restoreBtn(f))
    expect(f.dlg.querySelector('[data-slot="ih-draft-md-held"]')).not.toBeNull()
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toEqual({ id: 9, version: 2, title: 'Taslak başlık' })
    expect(storedDraft().changes).toEqual({ rca_summary: 'Taslak kök neden' })
  })

  it('metinler okunamadıysa "değişiklik yok" kapanışı metin değişikliğini taslakta bırakır', async () => {
    seedDraft({ rca_summary: 'Taslak kök neden' })
    apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: false, error: 'x' }))
    const f = open({ initial: SUMMARY })
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-md-note"]')).not.toBeNull())
    fireEvent.click(restoreBtn(f))
    f.save()                                   // değişiklik yok → "kaydedilecek bir şey yok", pencere kapanır
    await waitFor(() => expect(f.onClose).toHaveBeenCalledTimes(1))
    expect(f.onSubmit).not.toHaveBeenCalled()
    expect(storedDraft().changes).toEqual({ rca_summary: 'Taslak kök neden' })
  })

  it('"Son hâli yükle" taslağın metin değişikliklerini korur (metinler okunmamışken)', async () => {
    seedDraft({ title: 'Taslak başlık', rca_summary: 'Taslak kök neden' })
    apiMock.incidents.get = vi.fn()
      .mockImplementationOnce(() => Promise.resolve({ success: false, error: 'x' }))
      .mockImplementation(() => Promise.resolve({ success: true, data: { ...FULL, version: 3, title: 'Başkasının başlığı' } }))
    const onSubmit = vi.fn(async () => ({ ok: false, code: 'CONCURRENT_MODIFICATION', conflict: { by: 'Mehmet', at: '2026-11-20T10:00:00' } }))
    const f = open({ initial: SUMMARY, onSubmit })
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-md-note"]')).not.toBeNull())
    fireEvent.click(restoreBtn(f))
    f.save()
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-conflict"]')).not.toBeNull())
    fireEvent.click(f.dlg.querySelector('[data-slot="ih-conflict"] button'))
    await waitFor(() => expect(f.dlg.querySelector('[data-slot="ih-draft"]')).not.toBeNull())
    expect(storedDraft().changes).toMatchObject({ title: 'Taslak başlık', rca_summary: 'Taslak kök neden' })
    expect(f.title()).toHaveValue('Başkasının başlığı')
    fireEvent.click(restoreBtn(f))
    expect(f.title()).toHaveValue('Taslak başlık')
    await go(f, 'rca')
    expect(pane(f).querySelector('[data-slot="md-edit"]')).toHaveValue('Taslak kök neden')
  })
})

describe('yeni kayıt taslağı ön dolu değerleri saklar (inceleme L1)', () => {
  it('hızlı kayıt: oluş zamanı ve takım da saklanır', () => {
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    fireEvent.change(f.title(), { target: { value: 'Hızlı olay' } })
    f.unmount()
    const d = storedDraft(`sm.ih.draft.new:${OWNER}`)
    expect(d.changes).toMatchObject({ title: 'Hızlı olay', occurred_at: '2026-11-20T09:00:00', team_id: '1' })
  })

  it('hiçbir şey yazılmadıysa taslak yok', () => {
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    f.unmount()
    expect(localStorage.getItem(`sm.ih.draft.new:${OWNER}`)).toBeNull()
  })

  it('kopya: kaynaktan gelen alanlar da saklanır', () => {
    const f = open({ mode: 'create', initial: { title: 'Kopya', description: 'Kaynak açıklama', team_id: '1' },
      copyOf: { id: 41, imagesRemoved: 0, teamDropped: false } })
    fireEvent.change(f.title(), { target: { value: 'Kopya düzenlendi' } })
    f.unmount()
    const d = storedDraft(`sm.ih.draft.new.copy.41:${OWNER}`)
    expect(d.changes).toMatchObject({ title: 'Kopya düzenlendi', description: 'Kaynak açıklama', team_id: '1' })
  })

  it('geri yüklenen yeni kayıt taslağı saklanan oluş zamanını getirir (o anın "şimdi"si değil)', async () => {
    localStorage.setItem(`sm.ih.draft.new:${OWNER}`, JSON.stringify({ v: 1, at: Date.now(), base_version: null, base_updated_at: null,
      changes: { title: 'Dünkü olay', occurred_at: '2026-11-19T08:15:00', team_id: '1', team_name: 'Takım A' } }))
    const f = open({ mode: 'create', initial: quickInitial(TEAMS, '2026-11-20T09:00:00'), quick: true })
    fireEvent.click(f.dlg.querySelector('[data-action="draft-restore"]'))
    f.save()
    await waitFor(() => expect(f.onSubmit).toHaveBeenCalledTimes(1))
    expect(f.onSubmit.mock.calls[0][0]).toMatchObject({ title: 'Dünkü olay', occurred_at: '2026-11-19T08:15:00' })
  })
})

describe('posta önizlemesi metinler okunana dek kapalı (inceleme L2)', () => {
  beforeEach(() => {
    apiMock.incidents.previewNotification = vi.fn(() => Promise.resolve({ success: true, html: '<p>x</p>' }))
  })

  it('metinler yüklenirken: düğme kapalı, nedeni yazılı; gelince açılır', async () => {
    const first = deferred()
    apiMock.incidents.get = vi.fn(() => first.promise)
    const f = open({ initial: SUMMARY })
    await go(f, 'notify')
    expect(pane(f).querySelector('[data-action="preview-toggle"]')).toBeDisabled()
    const blocked = pane(f).querySelector('[data-slot="ih-preview-blocked"]')
    expect(blocked).toHaveAttribute('data-state', 'loading')
    expect(blocked).toHaveAttribute('role', 'status')
    expect(blocked.textContent).toContain(TR['inc.previewWaitMd'])
    fireEvent.click(pane(f).querySelector('[data-action="preview-toggle"]'))
    expect(apiMock.incidents.previewNotification).not.toHaveBeenCalled()
    await act(async () => { first.resolve({ success: true, data: FULL }) })
    await waitFor(() => expect(pane(f).querySelector('[data-slot="ih-preview-blocked"]')).toBeNull())
    expect(pane(f).querySelector('[data-action="preview-toggle"]')).not.toBeDisabled()
  })

  it('okunamadıysa not + yeniden dene; yeniden okuma başarılıysa açılır', async () => {
    apiMock.incidents.get = vi.fn()
      .mockImplementationOnce(() => Promise.resolve({ success: false, error: 'x' }))
      .mockImplementation(() => Promise.resolve({ success: true, data: FULL }))
    const f = open({ initial: SUMMARY })
    await go(f, 'notify')
    await waitFor(() => expect(pane(f).querySelector('[data-slot="ih-preview-blocked"]')).toHaveAttribute('data-state', 'failed'))
    const blocked = pane(f).querySelector('[data-slot="ih-preview-blocked"]')
    expect(blocked).toHaveAttribute('role', 'alert')
    expect(blocked.textContent).toContain(TR['inc.previewMdFailed'])
    expect(pane(f).querySelector('[data-action="preview-toggle"]')).toBeDisabled()
    fireEvent.click(pane(f).querySelector('[data-action="preview-md-retry"]'))
    await waitFor(() => expect(pane(f).querySelector('[data-slot="ih-preview-blocked"]')).toBeNull())
    expect(apiMock.incidents.get).toHaveBeenCalledTimes(2)
    expect(pane(f).querySelector('[data-action="preview-toggle"]')).not.toBeDisabled()
  })

  it('tam kayıtla açılan formda önizleme hemen açılabilir', async () => {
    const f = open({ initial: FULL })
    await go(f, 'notify')
    expect(pane(f).querySelector('[data-slot="ih-preview-blocked"]')).toBeNull()
    expect(pane(f).querySelector('[data-action="preview-toggle"]')).not.toBeDisabled()
  })
})
