import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — satır içi görev notu (TaskNote / NoteEditor, 2026-11-26). Pinlenenler (sözleşme §9.4):
 *  - yazdıkça 800 ms sonra TEK istek (`NOTE_DEBOUNCE_MS`); alandan çıkınca / Esc / Ctrl+Enter / söküm anında kaydeder;
 *  - hazır not çipleri: alan boşsa yerine yazar, doluysa yeni satıra ekler;
 *  - kaydedilmemiş metin kişisel depoda (`sm.or.note.<takım>.<madde anahtarı>` + kullanıcı, değer `{v:1, text, base, at}`)
 *    — kaydedilince silinir, kayıt başarısızsa kalır; açılışta taslak geri gelir (kendi taban sürümüyle);
 *  - yoklama kirli metnin tabanını taşımaz (inceleme #1); bozuk / eski / bayat taslak silinir (inceleme #3);
 *  - 409 NOTE_CHANGED: iki metin yan yana; "Benimkini kaydet" sunucunun sürümüyle yeniden gönderir, "Onlarınkini al" yazar;
 *  - hata → metin + "Yeniden dene"; sonucu belirsiz yanıtın kendi metni.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ oncallRoutines: { saveItemNote: vi.fn() } }),
}))

import { api } from '../api/client'
import TaskNote, {
  NOTE_DEBOUNCE_MS, NOTE_DRAFT_MAX_AGE_MS, normalizeNote, readNoteDraft, writeNoteDraft,
} from '../components/oncallroutine/TaskNote.jsx'
import { OWNER_KEY } from '../utils/personalStorage.js'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { restoreRealTimers } from './helpers/fakeTimers.js'
import { KEY, ME, OTHER, TEAM_ID, TODAY, buildItem, deferred, fail, ok } from './helpers/oncallRoutineFixtures.js'

const save = () => api.oncallRoutines.saveItemNote
const tr = (k, ...a) => TR[k].replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const area = () => screen.getByRole('textbox', { name: TR['or.note.taskLabel'] })
const status = () => slot('or-task-note-status')
/** Kişisel taslak anahtarı: `sm.or.note.<takım>.<madde anahtarı>:<kullanıcı>`. */
const DRAFT_BASE = `sm.or.note.${TEAM_ID}.${KEY.errors}`
const DRAFT = `${DRAFT_BASE}:${ME.username}`
/** Taslağın metni (biçim `{v:1, text, base, at}`); yoksa null. */
const draftText = () => { const r = localStorage.getItem(DRAFT); return r == null ? null : JSON.parse(r).text }
const draftOf = () => { const r = localStorage.getItem(DRAFT); return r == null ? null : JSON.parse(r) }
const seedDraft = (text, base, at = Date.now()) => localStorage.setItem(DRAFT, JSON.stringify({ v: 1, text, base, at }))

function savedResponse(note, version = 1, at = '2026-11-26T10:45:00Z') {
  return ok({
    item: buildItem({ task_id: 't-errors', title: 'Gece hata kayıtlarını incele', note, note_version: version, note_by_name: ME.name, note_at: at }),
    changed: true,
  })
}

function renderNote(itemOver = {}, props = {}) {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  const item = buildItem({ task_id: 't-errors', title: 'Gece hata kayıtlarını incele', ...itemOver })
  const utils = render(
    <TaskNote teamId={TEAM_ID} contextDate={TODAY} item={item} onSaved={onSaved} onClose={onClose} {...props} />,
  )
  return { ...utils, onSaved, onClose, item }
}

/** Aynı bileşeni sunucudan yeni gelmiş madde ile yeniden çizer (yoklama / başkası kaydetti). */
function rerenderItem(utils, itemOver = {}, props = {}) {
  const item = buildItem({ task_id: 't-errors', title: 'Gece hata kayıtlarını incele', ...itemOver })
  utils.rerender(
    <TaskNote teamId={TEAM_ID} contextDate={TODAY} item={item} onSaved={utils.onSaved} onClose={utils.onClose} {...props} />,
  )
  return item
}

beforeEach(() => {
  save().mockReset()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  localStorage.setItem(OWNER_KEY, ME.username)
})
afterEach(() => {
  restoreRealTimers()
})

describe('TaskNote — otomatik kayıt', () => {
  it('yazdıkça 800 ms sonra TEK istek; sürüm ve madde kimliği gövdede; "Kaydedildi 13:45"', async () => {
    const { onSaved } = renderNote()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    save().mockResolvedValue(savedResponse('Kontrol edildi, sorun yok'))
    fireEvent.change(area(), { target: { value: 'Kontrol' } })
    act(() => { vi.advanceTimersByTime(500) })
    fireEvent.change(area(), { target: { value: 'Kontrol edildi, sorun yok' } })
    expect(status().dataset.status).toBe('dirty')
    expect(status().textContent).toContain(TR['or.note.unsaved'])
    act(() => { vi.advanceTimersByTime(NOTE_DEBOUNCE_MS - 1) })
    expect(save()).not.toHaveBeenCalled()
    await act(async () => { vi.advanceTimersByTime(1) })
    expect(NOTE_DEBOUNCE_MS).toBe(800)
    expect(save()).toHaveBeenCalledTimes(1)
    expect(save()).toHaveBeenCalledWith(TEAM_ID, {
      date: TODAY, task_id: 't-errors', period_kind: 'DAY', period_key: TODAY, note: 'Kontrol edildi, sorun yok', note_version: 0,
    })
    expect(status().dataset.status).toBe('saved')
    expect(status().textContent).toContain(tr('or.note.saved', '13:45'))
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ item: expect.objectContaining({ note_version: 1 }) }))
    act(() => { vi.advanceTimersByTime(5_000) })
    expect(save()).toHaveBeenCalledTimes(1)
  })

  it('alandan çıkınca bekleyen kayıt HEMEN gider; zamanlayıcı ikinci istek atmaz', async () => {
    renderNote()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    save().mockResolvedValue(savedResponse('Sorun yok'))
    fireEvent.change(area(), { target: { value: 'Sorun yok' } })
    fireEvent.blur(area())
    expect(save()).toHaveBeenCalledTimes(1)
    await act(async () => { vi.advanceTimersByTime(2_000) })
    expect(save()).toHaveBeenCalledTimes(1)
    expect(status().dataset.status).toBe('saved')
  })

  it('değişiklik yokken alandan çıkmak istek atmaz', () => {
    renderNote({ note: 'Önceki not', note_version: 3 })
    fireEvent.blur(area())
    expect(save()).not.toHaveBeenCalled()
  })

  it('Esc: önce kaydeder, sonra kapatır', async () => {
    const { onClose } = renderNote()
    save().mockResolvedValue(savedResponse('Bilgi verildi'))
    fireEvent.change(area(), { target: { value: 'Bilgi verildi' } })
    fireEvent.keyDown(area(), { key: 'Escape' })
    expect(save()).toHaveBeenCalledTimes(1)
    expect(save().mock.calls[0][1].note).toBe('Bilgi verildi')
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('Ctrl+Enter: kaydeder ve kapatır', async () => {
    const { onClose } = renderNote()
    save().mockResolvedValue(savedResponse('Takip ediliyor'))
    fireEvent.change(area(), { target: { value: 'Takip ediliyor' } })
    fireEvent.keyDown(area(), { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(save()).toHaveBeenCalledTimes(1)
  })

  it('söküm (satır kapanır) bekleyen notu gönderir; değişiklik yoksa göndermez', async () => {
    save().mockResolvedValue(savedResponse('Yarıda kalmasın'))
    const first = renderNote()
    fireEvent.change(area(), { target: { value: 'Yarıda kalmasın' } })
    first.unmount()
    expect(save()).toHaveBeenCalledTimes(1)
    expect(save().mock.calls[0][1]).toMatchObject({ note: 'Yarıda kalmasın', note_version: 0 })
    // Yanıt gelince taslak silinir (kayıt uçuştayken taslak kalır: istek düşerse metin kaybolmasın)
    await waitFor(() => expect(Object.keys(localStorage).some((k) => k.includes('sm.or.note.'))).toBe(false))

    save().mockClear()
    const second = renderNote({ note: 'Değişmedi', note_version: 2 })
    second.unmount()
    expect(save()).not.toHaveBeenCalled()
  })

  it('metin kaydedilmeden önce normalleşir (CRLF → LF, kenar boşlukları atılır)', () => {
    expect(normalizeNote('  satır 1\r\nsatır 2\r  ')).toBe('satır 1\nsatır 2')
    expect(normalizeNote(null)).toBe('')
  })
})

describe('TaskNote — çipler ve taslak', () => {
  it('çip boş alana yazar, dolu alana yeni satır olarak ekler; taslak kişisel depoda', () => {
    renderNote()
    const chips = [...document.querySelectorAll('[data-slot="or-note-chip"]')].map((b) => b.textContent)
    expect(chips).toEqual([TR['or.note.chip.ok'], TR['or.note.chip.tracking'], TR['or.note.chip.informed']])
    fireEvent.click(screen.getByRole('button', { name: TR['or.note.chip.ok'] }))
    expect(area().value).toBe(TR['or.note.chip.ok'])
    fireEvent.click(screen.getByRole('button', { name: TR['or.note.chip.tracking'] }))
    expect(area().value).toBe(`${TR['or.note.chip.ok']}\n${TR['or.note.chip.tracking']}`)
    expect(draftText()).toBe(`${TR['or.note.chip.ok']}\n${TR['or.note.chip.tracking']}`)
    // Kullanıcısız (ortak) anahtara yazılmaz
    expect(localStorage.getItem(`sm.or.note.${TEAM_ID}.${KEY.errors}`)).toBeNull()
    expect(status().dataset.status).toBe('dirty')
  })

  it('taslak kayıt başarısızken KALIR, başarılı kayıttan sonra silinir', async () => {
    renderNote()
    save().mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR', { error: 'Not kaydedilemedi: veritabanı meşgul.' }))
    fireEvent.change(area(), { target: { value: 'Kuyruk 1.240 mesaj' } })
    expect(draftText()).toBe('Kuyruk 1.240 mesaj')
    fireEvent.blur(area())
    await waitFor(() => expect(status().dataset.status).toBe('failed'))
    expect(draftText()).toBe('Kuyruk 1.240 mesaj')

    save().mockResolvedValueOnce(savedResponse('Kuyruk 1.240 mesaj'))
    fireEvent.click(slot('or-task-note-retry'))
    await waitFor(() => expect(status().dataset.status).toBe('saved'))
    expect(draftText()).toBeNull()
  })

  it('açılışta kaydedilmemiş taslak geri gelir ve "Kaydedilmedi" denir', () => {
    seedDraft('Yarım kalan not', 0)
    renderNote({ note: '', note_version: 0 })
    expect(area().value).toBe('Yarım kalan not')
    expect(status().dataset.status).toBe('dirty')
    expect(status().textContent).toContain(TR['or.note.unsaved'])
  })

  it('salt okunur: alan değiştirilemez, çip yok, alandan çıkınca istek yok', () => {
    renderNote({ note: 'Önceki nöbetçinin notu', note_version: 1 }, { readOnly: true })
    expect(area()).toHaveAttribute('readonly')
    expect(slot('or-note-chip')).toBeNull()
    fireEvent.blur(area())
    expect(save()).not.toHaveBeenCalled()
  })
})

describe('TaskNote — çakışma ve hata', () => {
  const theirs = { note: 'Kuyruk boşaltıldı, izlemede.', note_version: 2, note_by_name: OTHER.name, note_at: '2026-11-26T10:44:00Z' }

  it('409 NOTE_CHANGED → iki metin; "Benimkini kaydet" sunucunun sürümüyle yeniden gönderir', async () => {
    renderNote()
    save().mockResolvedValueOnce(fail(409, 'NOTE_CHANGED', { current: theirs }))
    fireEvent.change(area(), { target: { value: 'Benim notum: tekrar kontrol edildi' } })
    fireEvent.blur(area())
    await waitFor(() => expect(slot('or-task-note-conflict')).not.toBeNull())
    const box = slot('or-task-note-conflict')
    expect(box.textContent).toContain(tr('or.note.conflict', OTHER.name, '13:44'))
    expect(box.textContent).toContain(theirs.note)
    expect(status().dataset.status).toBe('conflict')
    // Kendi metni alanda kalır
    expect(area().value).toBe('Benim notum: tekrar kontrol edildi')
    expect(draftText()).toBe('Benim notum: tekrar kontrol edildi')

    save().mockResolvedValueOnce(savedResponse('Benim notum: tekrar kontrol edildi', 3))
    fireEvent.click(slot('or-task-note-keep-mine'))
    expect(save()).toHaveBeenCalledTimes(2)
    expect(save().mock.calls[1][1]).toMatchObject({ note: 'Benim notum: tekrar kontrol edildi', note_version: 2 })
    await waitFor(() => expect(status().dataset.status).toBe('saved'))
    expect(slot('or-task-note-conflict')).toBeNull()
    expect(draftText()).toBeNull()
  })

  it('409 NOTE_CHANGED → "Onlarınkini al" onların metnini yazar, taslağı siler, yeni istek atmaz', async () => {
    renderNote()
    save().mockResolvedValueOnce(fail(409, 'NOTE_CHANGED', { current: theirs }))
    fireEvent.change(area(), { target: { value: 'Benim notum' } })
    fireEvent.blur(area())
    await waitFor(() => expect(slot('or-task-note-take-theirs')).not.toBeNull())
    fireEvent.click(slot('or-task-note-take-theirs'))
    expect(area().value).toBe(theirs.note)
    expect(status().dataset.status).toBe('idle')
    expect(slot('or-task-note-conflict')).toBeNull()
    expect(draftText()).toBeNull()
    expect(save()).toHaveBeenCalledTimes(1)
    // Onların sürümü artık temel: aynı metinle çıkmak istek atmaz
    fireEvent.blur(area())
    expect(save()).toHaveBeenCalledTimes(1)
  })

  it('hata → sunucu metni + "Yeniden dene" aynı not ve sürümle yeniden gönderir', async () => {
    renderNote()
    save().mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR', { error: 'Not kaydedilemedi: veritabanı meşgul.' }))
    fireEvent.change(area(), { target: { value: 'Gece 02:10 alarmı kapandı' } })
    fireEvent.blur(area())
    await waitFor(() => expect(status().dataset.status).toBe('failed'))
    expect(status().textContent).toContain('Not kaydedilemedi: veritabanı meşgul.')
    save().mockResolvedValueOnce(savedResponse('Gece 02:10 alarmı kapandı'))
    fireEvent.click(slot('or-task-note-retry'))
    expect(save()).toHaveBeenCalledTimes(2)
    expect(save().mock.calls[1][1]).toMatchObject({ note: 'Gece 02:10 alarmı kapandı', note_version: 0 })
    await waitFor(() => expect(status().dataset.status).toBe('saved'))
    expect(slot('or-task-note-retry')).toBeNull()
  })

  it('sonucu belirsiz yanıt (zaman aşımı) kendi metniyle söylenir; not yerelde kalır', async () => {
    renderNote()
    save().mockResolvedValueOnce({ success: false, status: 0, code: 'REQUEST_TIMEOUT', error: 'İstek zaman aşımına uğradı' })
    fireEvent.change(area(), { target: { value: 'Ağ yavaş' } })
    fireEvent.blur(area())
    await waitFor(() => expect(status().dataset.status).toBe('failed'))
    expect(status().textContent).toContain(TR['or.note.unsure'])
    expect(draftText()).toBe('Ağ yavaş')
  })
})

/**
 * İnceleme bulgusu #1 (2026-11-26): yerel metin kirliyken ya da kayıt sürerken yoklama yeni not / sürüm getirirse TABAN
 * TAŞINMAZ — sonraki kayıt ESKİ sürümle gider, sunucu 409 verir ve çakışma paneli açılır (başkasının notu sessizce
 * ezilmez). Yerel metin = yeni sunucu metniyse taban sessizce yeni sürüme geçer; değişiklik geri alınırsa bekleyen yeni not
 * ekrana alınır; temizken yoklama metni ekrana alır.
 */
describe('TaskNote — yoklama yeni not getirince taban (inceleme #1)', () => {
  const theirs = { note: 'Mehmet: kuyruk boşaltıldı, izlemede.', note_version: 3, note_by_name: OTHER.name, note_at: '2026-11-26T10:44:00Z' }

  it('kirliyken gelen yeni not yazılanı ezmez; kayıt ESKİ sürümle gider → 409 → çakışma paneli', async () => {
    const u = renderNote({ note: 'Önceki not', note_version: 2 })
    fireEvent.change(area(), { target: { value: 'Benim yeni notum' } })
    rerenderItem(u, { note: theirs.note, note_version: 3, note_by_name: OTHER.name })
    // Yoklama yazılanı silmedi; hâlâ kaydedilmemiş
    expect(area().value).toBe('Benim yeni notum')
    expect(status().dataset.status).toBe('dirty')
    save().mockResolvedValueOnce(fail(409, 'NOTE_CHANGED', { current: theirs }))
    fireEvent.blur(area())
    expect(save()).toHaveBeenCalledTimes(1)
    // Taban taşınmadı: istek yoklamanın sürümüyle (3) değil, yazmaya başlarken bilinen sürümle (2) gider
    expect(save().mock.calls[0][1]).toMatchObject({ note: 'Benim yeni notum', note_version: 2 })
    await waitFor(() => expect(slot('or-task-note-conflict')).not.toBeNull())
    expect(slot('or-task-note-conflict').textContent).toContain(theirs.note)
    expect(status().dataset.status).toBe('conflict')
    expect(area().value).toBe('Benim yeni notum')
    expect(draftText()).toBe('Benim yeni notum')
  })

  it('temizken gelen yeni not ekrana alınır; sonraki kayıt YENİ sürümle gider', () => {
    const u = renderNote({ note: 'Önceki not', note_version: 2 })
    rerenderItem(u, { note: theirs.note, note_version: 3 })
    expect(area().value).toBe(theirs.note)
    expect(status().dataset.status).toBe('idle')
    save().mockResolvedValueOnce(savedResponse(`${theirs.note}\nBen de baktım.`, 4))
    fireEvent.change(area(), { target: { value: `${theirs.note}\nBen de baktım.` } })
    fireEvent.blur(area())
    expect(save()).toHaveBeenCalledTimes(1)
    expect(save().mock.calls[0][1]).toMatchObject({ note: `${theirs.note}\nBen de baktım.`, note_version: 3 })
  })

  it('kirliyken yeni not geldi, sonra yazılan geri alındı (metin = taban) → yeni not gösterilir, istek gitmez', () => {
    const u = renderNote({ note: 'Önceki not', note_version: 2 })
    fireEvent.change(area(), { target: { value: 'Önceki not — ek' } })
    rerenderItem(u, { note: theirs.note, note_version: 3 })
    expect(area().value).toBe('Önceki not — ek')
    // Kullanıcı değişikliğini geri alır
    fireEvent.change(area(), { target: { value: 'Önceki not' } })
    fireEvent.blur(area())
    expect(save()).not.toHaveBeenCalled()
    expect(area().value).toBe(theirs.note)
    expect(status().dataset.status).toBe('idle')
    expect(draftText()).toBeNull()
    // Artık taban yeni sürüm: sonraki kayıt 3 ile gider
    save().mockResolvedValueOnce(savedResponse('Benim ekim', 4))
    fireEvent.change(area(), { target: { value: 'Benim ekim' } })
    fireEvent.blur(area())
    expect(save().mock.calls[0][1]).toMatchObject({ note: 'Benim ekim', note_version: 3 })
  })

  it('başkası TAM yazdığım metni kaydettiyse çakışma yok: durum boşta, taslak silinir, istek gitmez', () => {
    const u = renderNote({ note: 'Önceki not', note_version: 2 })
    fireEvent.change(area(), { target: { value: 'Ortak metin' } })
    expect(draftText()).toBe('Ortak metin')
    rerenderItem(u, { note: 'Ortak metin', note_version: 3 })
    expect(status().dataset.status).toBe('idle')
    expect(draftText()).toBeNull()
    expect(area().value).toBe('Ortak metin')
    expect(slot('or-task-note-conflict')).toBeNull()
    // Bekleyen zamanlayıcı da istek atmaz (metin = yeni taban)
    fireEvent.blur(area())
    expect(save()).not.toHaveBeenCalled()
    // Sonraki değişiklik yeni sürümle gider
    save().mockResolvedValueOnce(savedResponse('Ortak metin ve ek', 4))
    fireEvent.change(area(), { target: { value: 'Ortak metin ve ek' } })
    fireEvent.blur(area())
    expect(save().mock.calls[0][1]).toMatchObject({ note: 'Ortak metin ve ek', note_version: 3 })
  })

  it('kayıt sürerken gelen DAHA YENİ not (bizimkinden sonra) kayıt bitince gösterilir', async () => {
    const u = renderNote({ note: 'Önceki not', note_version: 2 })
    const d = deferred()
    save().mockReturnValueOnce(d.promise)
    fireEvent.change(area(), { target: { value: 'Benim notum' } })
    fireEvent.blur(area())
    expect(status().dataset.status).toBe('saving')
    // Bizim kaydımız v3 olacak; yoklama o arada başkasının v4'ünü getirdi
    rerenderItem(u, { note: 'Mehmet sonra yazdı', note_version: 4 })
    expect(area().value).toBe('Benim notum')
    await act(async () => { d.resolve(savedResponse('Benim notum', 3)) })
    await waitFor(() => expect(area().value).toBe('Mehmet sonra yazdı'))
    expect(draftText()).toBeNull()
    // Taban artık v4
    save().mockResolvedValueOnce(savedResponse('Mehmet sonra yazdı; teyit', 5))
    fireEvent.change(area(), { target: { value: 'Mehmet sonra yazdı; teyit' } })
    fireEvent.blur(area())
    expect(save().mock.calls[1][1]).toMatchObject({ note_version: 4 })
  })
})

/**
 * İnceleme bulgusu #3 (2026-11-26): kişisel not taslağı `{v:1, text, base, at}` — metin + DAYANDIĞI not sürümü. Açılışta
 * sunucudan farklı taslak → metin taslak, durum "kirli", gönderim taslağın tabanıyla (başkası kaydettiyse 409). Bozuk, eski
 * biçimli (düz metin) ya da bayat (8 günden eski) taslak okunurken SİLİNİR ve kullanılmaz.
 */
describe('TaskNote — taslak biçimi ve geri yükleme (inceleme #3)', () => {
  it('yazmak {v:1, text, base: güncel sürüm, at} yazar; çip de aynı biçim', () => {
    renderNote({ note: 'Önceki not', note_version: 5 })
    const before = Date.now()
    fireEvent.change(area(), { target: { value: 'Yeni metin' } })
    const d = draftOf()
    expect(d).toMatchObject({ v: 1, text: 'Yeni metin', base: 5 })
    expect(Object.keys(d).sort()).toEqual(['at', 'base', 'text', 'v'])
    expect(d.at).toBeGreaterThanOrEqual(before)
    expect(d.at).toBeLessThanOrEqual(Date.now())
    fireEvent.click(screen.getByRole('button', { name: TR['or.note.chip.ok'] }))
    expect(draftOf()).toMatchObject({ v: 1, text: `Yeni metin\n${TR['or.note.chip.ok']}`, base: 5 })
  })

  it('temiz yoklama tabanı taşıdıktan sonra yazılan taslak YENİ sürüme dayanır', () => {
    const u = renderNote({ note: 'Önceki not', note_version: 5 })
    rerenderItem(u, { note: 'Başkasının notu', note_version: 6 })
    fireEvent.change(area(), { target: { value: 'Başkasının notu + benim' } })
    expect(draftOf()).toMatchObject({ v: 1, text: 'Başkasının notu + benim', base: 6 })
  })

  it('ESKİ sürüme dayanan taslak: açılışta kirli, gönderim taslağın tabanıyla (0) → 409 → taslak metni korunur', async () => {
    const theirs = { note: 'Başkasının notu', note_version: 3, note_by_name: OTHER.name, note_at: '2026-11-26T10:44:00Z' }
    seedDraft('Dünkü taslak', 0)
    renderNote({ note: theirs.note, note_version: 3, note_by_name: OTHER.name })
    expect(area().value).toBe('Dünkü taslak')
    expect(status().dataset.status).toBe('dirty')
    save().mockResolvedValueOnce(fail(409, 'NOTE_CHANGED', { current: theirs }))
    fireEvent.blur(area())
    expect(save()).toHaveBeenCalledTimes(1)
    expect(save().mock.calls[0][1]).toMatchObject({ note: 'Dünkü taslak', note_version: 0 })
    await waitFor(() => expect(slot('or-task-note-conflict')).not.toBeNull())
    expect(slot('or-task-note-conflict').textContent).toContain(theirs.note)
    expect(area().value).toBe('Dünkü taslak')
    expect(draftOf()).toMatchObject({ v: 1, text: 'Dünkü taslak', base: 0 })
    // "Benimkini kaydet" sunucunun sürümüyle (3) gönderir
    save().mockResolvedValueOnce(savedResponse('Dünkü taslak', 4))
    fireEvent.click(slot('or-task-note-keep-mine'))
    expect(save().mock.calls[1][1]).toMatchObject({ note: 'Dünkü taslak', note_version: 3 })
  })

  it('sunucudakiyle aynı metinli taslak atılır: durum boşta, taslak silinir', () => {
    seedDraft('Aynı not', 1)
    renderNote({ note: 'Aynı not', note_version: 4 })
    expect(area().value).toBe('Aynı not')
    expect(status().dataset.status).toBe('idle')
    expect(localStorage.getItem(DRAFT)).toBeNull()
  })

  it.each([
    ['bayat (8 günden eski)', () => seedDraft('Bayat taslak', 0, Date.now() - NOTE_DRAFT_MAX_AGE_MS - 60_000)],
    ['eski biçim (düz metin)', () => localStorage.setItem(DRAFT, 'Eski düz metin taslak')],
    ['bozuk JSON', () => localStorage.setItem(DRAFT, '{"v":1,"text":"yarım')],
    ['bilinmeyen sürüm', () => localStorage.setItem(DRAFT, JSON.stringify({ v: 2, text: 'X', base: 0, at: Date.now() }))],
    ['geçersiz taban', () => localStorage.setItem(DRAFT, JSON.stringify({ v: 1, text: 'X', base: -1, at: Date.now() }))],
  ])('%s taslak okunurken silinir ve kullanılmaz', (_label, seed) => {
    seed()
    renderNote({ note: 'Sunucudaki not', note_version: 2 })
    expect(area().value).toBe('Sunucudaki not')
    expect(status().dataset.status).toBe('idle')
    expect(localStorage.getItem(DRAFT)).toBeNull()
    fireEvent.blur(area())
    expect(save()).not.toHaveBeenCalled()
  })

  it('readNoteDraft / writeNoteDraft: kişisel anahtar, sürüm sayıya çevrilir, ömür sınırı, null siler', () => {
    writeNoteDraft(DRAFT_BASE, 'Kısa not', 4)
    const stored = JSON.parse(localStorage.getItem(DRAFT))
    expect(stored).toMatchObject({ v: 1, text: 'Kısa not', base: 4 })
    expect(localStorage.getItem(DRAFT_BASE)).toBeNull()
    expect(readNoteDraft(DRAFT_BASE)).toEqual({ text: 'Kısa not', base: 4, at: stored.at })
    // Tam ömür sınırında okunur, bir ms sonra silinir
    expect(readNoteDraft(DRAFT_BASE, stored.at + NOTE_DRAFT_MAX_AGE_MS)).not.toBeNull()
    expect(readNoteDraft(DRAFT_BASE, stored.at + NOTE_DRAFT_MAX_AGE_MS + 1)).toBeNull()
    expect(localStorage.getItem(DRAFT)).toBeNull()
    // Sayı olmayan taban 0 olur
    writeNoteDraft(DRAFT_BASE, 'Y', 'abc')
    expect(JSON.parse(localStorage.getItem(DRAFT)).base).toBe(0)
    writeNoteDraft(DRAFT_BASE, null)
    expect(localStorage.getItem(DRAFT)).toBeNull()
    expect(readNoteDraft(null)).toBeNull()
  })
})
