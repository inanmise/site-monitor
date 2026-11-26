import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — SORUN GÖRSELLERİ (`oncallroutine/ProblemImages.jsx`, 2026-11-26). Pinlenenler:
 *  - yardımcılar: bayt biçimi, yükleme hazırlığı (türü / ölçüsü söyleyen metin, hiçbir zaman atmaz), yapıştırmadan dosya,
 *    sunucu metni, yükleme reddinin metni + yeniden denenebilirlik, pano hatası metni, yanıttan görsel künyesi;
 *  - salt okunur görünüm: görsel yoksa hiçbir şey çizilmez; varsa küçük resimler + büyük görünüm, silme / ekleme yok;
 *  - ekleme: dosya seçici, yapıştırma, sürükle-bırak, "Panodan yapıştır"; hazırlanıyor / yükleniyor %N; başarıda
 *    `onChange` güncel sorunla; geçici retlerde hazır dosya bekletilir ("Yeniden dene" / "Bırak"), sınır retlerinde değil;
 *    bir anda tek yükleme; sorun başına en çok 4 görsel;
 *  - silme: onay sorulur; Vazgeç → istek yok; silindi ya da zaten yok (IMAGE_NOT_FOUND) → listeden düşer; ret / yanıt yok
 *    ayrı metin; silme sürerken silme düğmeleri kilitli;
 *  - dokunmatik vuruş alanı sınıfları (silme 40 px: 28 − 2 + 2 × 7; ekleme / yapıştırma min-h-10).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    oncallRoutines: {
      uploadProblemImage: vi.fn(),
      deleteProblemImage: vi.fn(),
      problemImageUrl: (id, imageId) => `/api/oncall-routines/problems/${id}/images/${imageId}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { ImagePrepError, prepareImage } from '../components/techerror/imagePrep.js'
import { ClipboardImageError } from '../components/techerror/capture/clipboardImage.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import ProblemImages, {
  IMAGE_ACCEPT, PROBLEM_CAPTURE_ID, clipboardProblemText, fileFromPaste, formatBytes, imageMetaOf, pasteKey,
  prepareForUpload, problemImageSrc, serverText, uploadFailureText, uploadRetryable,
} from '../components/oncallroutine/ProblemImages.jsx'

const or = () => api.oncallRoutines
const ok = (data) => ({ success: true, status: 200, data })
const fail = (extra = {}) => ({ success: false, status: 400, ...extra })
const tx = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), TR[key])
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const png = (name = 'ekran.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const pdf = () => new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'rapor.pdf', { type: 'application/pdf' })
const imageItem = (file) => ({ kind: 'file', type: file.type, getAsFile: () => file })
const META = (id) => ({ id, width: 1200, height: 800, size_bytes: 182311, content_type: 'image/png' })
const fakeT = (k, ...a) => (a.length ? `${k}|${a.join(',')}` : k)

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
function problem(extra = {}) {
  return { id: 55, can_edit: true, images: [], ...extra }
}

/** Denetimli bileşen: `onChange` gelen sorunu durumuna yazar (gerçek kullanımdaki gibi). */
function Harness({ initial, canWork = true, onChangeSpy }) {
  const [p, setP] = useState(initial)
  return <ProblemImages problem={p} canWork={canWork} onChange={(next) => { onChangeSpy?.(next); setP(next) }} />
}
function renderImages({ initial = problem(), canWork = true } = {}) {
  const onChange = vi.fn()
  const view = render(<Harness initial={initial} canWork={canWork} onChangeSpy={onChange} />)
  return { ...view, onChange }
}
const fileInput = () => document.querySelector('[data-slot="or-problem-images"] input[type="file"]')
async function pickFile(file) {
  await act(async () => { fireEvent.change(fileInput(), { target: { files: [file] } }) })
}
const problemText = () => slot('or-problem-image-problem')?.querySelector('[role="alert"]')?.textContent || null

const realClipboard = Object.getOwnPropertyDescriptor(globalThis.navigator, 'clipboard')
function stubClipboard(read) {
  Object.defineProperty(globalThis.navigator, 'clipboard', { configurable: true, value: { read } })
}

beforeEach(() => {
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  or().uploadProblemImage.mockReset()
  or().deleteProblemImage.mockReset()
  prepareImage.mockReset()
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
})
afterEach(() => {
  if (realClipboard) Object.defineProperty(globalThis.navigator, 'clipboard', realClipboard)
  else delete globalThis.navigator.clipboard
  localStorage.clear()
})

describe('ProblemImages — yardımcılar', () => {
  it('formatBytes: KB (en az 1) / MB (dilin ondalık ayırıcısı); geçersiz değer "—"', () => {
    expect(formatBytes(0)).toBe('1 KB')
    expect(formatBytes(182311)).toBe('178 KB')
    expect(formatBytes(1468006)).toBe('1,4 MB')
    expect(formatBytes(1468006, 'en')).toBe('1.4 MB')
    expect(formatBytes(-1)).toBe('—')
    expect(formatBytes('abc')).toBe('—')
    expect(formatBytes(Number.POSITIVE_INFINITY)).toBe('—')
  })

  it('pasteKey platforma göre; kabul edilen türler PNG / JPEG ve tarayıcının çevirebildikleri', () => {
    expect(['Ctrl+V', '⌘V']).toContain(pasteKey())
    expect(IMAGE_ACCEPT.split(',')).toEqual(expect.arrayContaining(['image/png', 'image/jpeg', 'image/webp']))
    expect(PROBLEM_CAPTURE_ID).toBe('sorun')
  })

  it('prepareForUpload: başarı dosyayı döner; ImagePrepError metni (bayt parametreleri biçimli); başka hata tarayıcı metni', async () => {
    const f = png()
    prepareImage.mockResolvedValueOnce({ file: f, info: { kept: true } })
    await expect(prepareForUpload(f, fakeT, 'tr')).resolves.toEqual({ ok: true, file: f, info: { kept: true } })

    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errTooBig', [6 * 1048576, 5 * 1048576]))
    await expect(prepareForUpload(f, fakeT, 'tr')).resolves.toEqual({ ok: false, message: 'te.img.errTooBig|6 MB,5 MB' })

    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errPdf'))
    await expect(prepareForUpload(f, fakeT, 'tr')).resolves.toEqual({ ok: false, message: 'te.img.errPdf' })

    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errDecode', ['ham']))
    await expect(prepareForUpload(f, fakeT, 'tr')).resolves.toEqual({ ok: false, message: 'te.img.errDecode|ham' })

    prepareImage.mockRejectedValueOnce(new RangeError('canvas'))
    await expect(prepareForUpload(f, fakeT, 'tr')).resolves.toEqual({ ok: false, message: 'te.img.errBrowser' })
  })

  it('fileFromPaste: görsel → okunur adla; yalnız görsel olmayan dosya → aynı dosya; metinli yapıştırma ve boş → null', () => {
    const img = png('image.png')
    const fromImage = fileFromPaste({ items: [imageItem(img)], files: [], types: ['Files'] })
    expect(fromImage).toBeInstanceOf(File)
    expect(fromImage.name.startsWith('sorun-pano-')).toBe(true)
    expect(fromImage.type).toBe('image/png')

    const doc = pdf()
    expect(fileFromPaste({ items: [], files: [doc], types: ['Files'] })).toBe(doc)
    expect(fileFromPaste({ items: [], files: [doc], types: ['Files', 'text/plain'] })).toBeNull()
    expect(fileFromPaste({ items: [{ kind: 'string', type: 'text/plain' }], files: [], types: ['text/plain'] })).toBeNull()
    expect(fileFromPaste(null)).toBeNull()
  })

  it('serverText: sunucu metni → kod çevirisi → yedek anahtar', () => {
    expect(serverText({ error: '  Sunucu söyledi  ' }, fakeT, 'yedek')).toBe('  Sunucu söyledi  ')
    expect(serverText({ code: 'PROBLEM_NOT_FOUND', error: '   ' }, fakeT, 'yedek')).toBe('or.err.PROBLEM_NOT_FOUND')
    expect(serverText({ code: 'BILINMEYEN_KOD' }, fakeT, 'yedek')).toBe('yedek')
    expect(serverText(null, fakeT, 'yedek')).toBe('yedek')
  })

  it('uploadFailureText: yanıt yok → "eklenmiş olabilir"; görsel sınırı (durum 0 olsa da) kendi metni; diğerleri sunucu metni', () => {
    expect(uploadFailureText({ success: false, status: 0, code: 'NETWORK_ERROR' }, fakeT)).toBe('or.prob.imgUnsure')
    expect(uploadFailureText({ success: false, status: 504 }, fakeT)).toBe('or.prob.imgUnsure')
    expect(uploadFailureText({ success: false, status: 0, code: 'PROBLEM_IMAGE_LIMIT' }, fakeT)).toBe('or.err.PROBLEM_IMAGE_LIMIT')
    expect(uploadFailureText(fail({ error: 'Dosya bozuk' }), fakeT)).toBe('Dosya bozuk')
    expect(uploadFailureText(fail(), fakeT)).toBe('te.img.uploadError')
  })

  it('uploadRetryable: geçici retler (yanıt yok, 5xx, RATE_LIMITED) evet; sınır / sorun yok / doğrulama hayır', () => {
    expect(uploadRetryable({ success: false, status: 0, code: 'NETWORK_ERROR' })).toBe(true)
    expect(uploadRetryable({ success: false, status: 500 })).toBe(true)
    expect(uploadRetryable({ success: false, status: 429, code: 'RATE_LIMITED' })).toBe(true)
    expect(uploadRetryable({ success: false, status: 0, code: 'PROBLEM_IMAGE_LIMIT' })).toBe(false)
    expect(uploadRetryable({ success: false, status: 404, code: 'PROBLEM_NOT_FOUND' })).toBe(false)
    expect(uploadRetryable({ success: false, status: 400, code: 'VALIDATION_FAILED' })).toBe(false)
    expect(uploadRetryable({ success: false, status: 'abc', code: 'VALIDATION_FAILED' })).toBe(false)
  })

  it('clipboardProblemText: panoda görsel yok / izin yok / okunamadı (her biri yapıştırma kısayolunu söyler)', () => {
    const k = pasteKey()
    expect(clipboardProblemText(new ClipboardImageError('empty'), fakeT)).toBe(`or.prob.clipEmpty|${k}`)
    expect(clipboardProblemText(new ClipboardImageError('denied'), fakeT)).toBe(`or.prob.clipDenied|${k}`)
    expect(clipboardProblemText(new ClipboardImageError('unsupported'), fakeT)).toBe(`or.prob.clipUnreadable|${k}`)
    expect(clipboardProblemText(new Error('x'), fakeT)).toBe(`or.prob.clipUnreadable|${k}`)
  })

  it('imageMetaOf: `data.image` ya da `data`; kimliksiz / boş yanıt null; görsel adresi istemcinin adresi', () => {
    expect(imageMetaOf(ok({ image: META(3) }))).toEqual(META(3))
    expect(imageMetaOf(ok(META(4)))).toEqual(META(4))
    expect(imageMetaOf(ok({}))).toBeNull()
    expect(imageMetaOf(ok('metin'))).toBeNull()
    expect(imageMetaOf(null)).toBeNull()
    expect(problemImageSrc(55, 9)).toBe('/api/oncall-routines/problems/55/images/9')
  })
})

describe('ProblemImages — salt okunur', () => {
  it('görsel yoksa ve yazamıyorsa hiçbir şey çizilmez', () => {
    const { container } = renderImages({ canWork: false })
    expect(container.querySelector('[data-slot="or-problem-images"]')).toBeNull()
  })

  it('can_edit false: küçük resimler var, silme / ekleme / yapıştırma yok; tıklayınca büyük görünüm açılır, kapanınca odak döner', async () => {
    renderImages({ initial: problem({ can_edit: false, images: [META(1), { id: 2 }] }) })
    const root = slot('or-problem-images')
    expect(root.dataset.count).toBe('2')
    const imgs = slots('or-problem-image')
    expect(imgs.map((li) => li.dataset.imageId)).toEqual(['1', '2'])
    const first = imgs[0].querySelector('img')
    expect(first.getAttribute('src')).toBe('/api/oncall-routines/problems/55/images/1')
    expect(first.getAttribute('alt')).toBe(tx('or.prob.imageLabel', 1))
    expect(first.getAttribute('width')).toBe('1200')
    expect(first.getAttribute('loading')).toBe('lazy')
    // Ölçüsü bilinmeyen görselde genişlik / yükseklik özniteliği yazılmaz
    expect(imgs[1].querySelector('img').hasAttribute('width')).toBe(false)
    expect(slot('or-problem-image-delete')).toBeNull()
    expect(slot('or-problem-image-add')).toBeNull()
    expect(fileInput()).toBeNull()

    const zoomBtn = screen.getByRole('button', { name: tx('or.prob.imageZoom', 2) })
    fireEvent.click(zoomBtn)
    const box = await waitFor(() => { const el = slot('te-image-lightbox'); expect(el).not.toBeNull(); return el })
    expect(within(box).getByRole('img').getAttribute('src')).toBe('/api/oncall-routines/problems/55/images/2')
    fireEvent.click(slot('te-image-lightbox-close'))
    await waitFor(() => expect(slot('te-image-lightbox')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(zoomBtn))
  })

  it('canWork verilmezse (salt görüntüleme) yapıştırma / bırakma işleyicisi de yok', async () => {
    renderImages({ initial: problem({ images: [META(1)] }), canWork: false })
    const root = slot('or-problem-images')
    await act(async () => {
      fireEvent.paste(root, { clipboardData: { items: [imageItem(png())], files: [], types: ['Files'] } })
    })
    expect(prepareImage).not.toHaveBeenCalled()
    expect(or().uploadProblemImage).not.toHaveBeenCalled()
  })
})

describe('ProblemImages — ekleme', () => {
  it('dosya seçici: hazırlanıyor → yükleniyor %N → başarıda onChange görsel eklenmiş sorunla; düğme yeniden kullanılabilir', async () => {
    const prep = deferred()
    prepareImage.mockImplementationOnce(() => prep.promise)
    const up = deferred()
    let progress = null
    or().uploadProblemImage.mockImplementationOnce((id, file, opts) => { progress = opts.onProgress; return up.promise })
    const { onChange } = renderImages({ initial: problem({ images: [META(1)] }) })

    const add = slot('or-problem-image-add')
    expect(add.textContent).toContain(TR['or.prob.imgAdd'])
    expect(slot('or-problem-images').textContent).toContain(tx('or.prob.imgHint', pasteKey(), 3))
    expect(fileInput().getAttribute('accept')).toBe(IMAGE_ACCEPT)
    const file = png()
    await pickFile(file)
    expect(slot('or-problem-image-add').textContent).toContain(TR['or.prob.imgPreparing'])
    expect(slot('or-problem-image-add')).toBeDisabled()
    expect(slot('or-problem-images').getAttribute('aria-busy')).toBe('true')

    await act(async () => { prep.resolve({ file, info: {} }) })
    await waitFor(() => expect(or().uploadProblemImage).toHaveBeenCalledWith(55, file, expect.objectContaining({ onProgress: expect.any(Function) })))
    expect(slot('or-problem-image-add').textContent).toContain(tx('or.prob.imgUploadingPct', 0))
    await act(async () => { progress({ loaded: 50, total: 200 }) })
    expect(slot('or-problem-image-add').textContent).toContain(tx('or.prob.imgUploadingPct', 25))
    await act(async () => { progress({ loaded: 999, total: 200 }) })
    expect(slot('or-problem-image-add').textContent).toContain(tx('or.prob.imgUploadingPct', 100))
    await act(async () => { progress({ loaded: 10 }) })   // toplam bilinmiyor → değişmez
    expect(slot('or-problem-image-add').textContent).toContain(tx('or.prob.imgUploadingPct', 100))

    await act(async () => { up.resolve(ok({ image: META(7) })) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(onChange.mock.calls[0][0].images.map((x) => x.id)).toEqual([1, 7])
    await waitFor(() => expect(slot('or-problem-image-add')).not.toBeDisabled())
    expect(slot('or-problem-images').hasAttribute('aria-busy')).toBe(false)
    expect(slots('or-problem-image')).toHaveLength(2)
    expect(fileInput().value).toBe('')
    expect(problemText()).toBeNull()
  })

  it('başarı yanıtında görsel künyesi yoksa liste değişmez (onChange çağrılmaz)', async () => {
    or().uploadProblemImage.mockResolvedValueOnce(ok({}))
    const { onChange } = renderImages()
    await pickFile(png())
    await waitFor(() => expect(or().uploadProblemImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(slot('or-problem-image-add')).not.toBeDisabled())
    expect(onChange).not.toHaveBeenCalled()
    expect(problemText()).toBeNull()
  })

  it('hazırlama reddi: türü / ölçüyü söyleyen metin (bayt biçimli), yükleme yok, "Yeniden dene" yok', async () => {
    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errTooBig', [41 * 1048576, 40 * 1048576]))
    renderImages()
    await pickFile(png())
    await waitFor(() => expect(problemText()).toBe(tx('te.img.errTooBig', '41 MB', '40 MB')))
    expect(or().uploadProblemImage).not.toHaveBeenCalled()
    expect(slot('or-problem-image-retry')).toBeNull()

    prepareImage.mockRejectedValueOnce(new ImagePrepError('te.img.errPdf'))
    await pickFile(pdf())
    await waitFor(() => expect(problemText()).toBe(TR['te.img.errPdf']))
  })

  it('geçici ret (500): metin + "Yeniden dene" aynı hazır dosyayı gönderir; başarıda liste güncellenir', async () => {
    const prepared = png('hazir.png')
    prepareImage.mockResolvedValueOnce({ file: prepared, info: {} })
    or().uploadProblemImage.mockResolvedValueOnce({ success: false, status: 500, error: 'Sunucu hatası oluştu' })
    const { onChange } = renderImages()
    await pickFile(png())
    await waitFor(() => expect(problemText()).toBe('Sunucu hatası oluştu'))
    expect(slot('or-problem-image-retry')).not.toBeNull()

    or().uploadProblemImage.mockResolvedValueOnce(ok(META(9)))
    await act(async () => { fireEvent.click(slot('or-problem-image-retry')) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(or().uploadProblemImage).toHaveBeenCalledTimes(2)
    expect(or().uploadProblemImage.mock.calls[1][1]).toBe(prepared)
    expect(prepareImage).toHaveBeenCalledTimes(1)   // yeniden deneme yeniden hazırlamaz
    expect(onChange.mock.calls[0][0].images.map((x) => x.id)).toEqual([9])
    expect(problemText()).toBeNull()
    expect(slot('or-problem-image-retry')).toBeNull()
  })

  it('yükleme isteği atarsa yanıt yok sayılır: "eklenmiş olabilir" + bekletilen dosya; "Bırak" ikisini de temizler', async () => {
    or().uploadProblemImage.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    renderImages()
    await pickFile(png())
    await waitFor(() => expect(problemText()).toBe(TR['or.prob.imgUnsure']))
    expect(slot('or-problem-image-retry')).not.toBeNull()
    fireEvent.click(slot('or-problem-image-drop'))
    expect(slot('or-problem-image-problem')).toBeNull()
    expect(or().uploadProblemImage).toHaveBeenCalledTimes(1)
  })

  it('sınır reddi (PROBLEM_IMAGE_LIMIT) yeniden denenmez: kod metni, "Yeniden dene" yok', async () => {
    or().uploadProblemImage.mockResolvedValueOnce({ success: false, status: 409, code: 'PROBLEM_IMAGE_LIMIT' })
    renderImages()
    await pickFile(png())
    await waitFor(() => expect(problemText()).toBe(TR['or.err.PROBLEM_IMAGE_LIMIT']))
    expect(slot('or-problem-image-retry')).toBeNull()
  })

  it('bir anda tek yükleme: sürerken gelen ikinci dosya reddedilir (metin), istek gönderilmez', async () => {
    const up = deferred()
    or().uploadProblemImage.mockImplementationOnce(() => up.promise)
    renderImages()
    await pickFile(png('ilk.png'))
    await waitFor(() => expect(or().uploadProblemImage).toHaveBeenCalledTimes(1))
    await pickFile(png('ikinci.png'))
    expect(problemText()).toBe(TR['or.prob.imgBusy'])
    expect(prepareImage).toHaveBeenCalledTimes(1)
    await act(async () => { up.resolve(ok(META(5))) })
    await waitFor(() => expect(slots('or-problem-image')).toHaveLength(1))
  })

  it('4 görsel dolu: ekleme satırı çizilmez; yapıştırılan görsel sınır metniyle reddedilir', async () => {
    renderImages({ initial: problem({ images: [META(1), META(2), META(3), META(4)] }) })
    expect(slot('or-problem-image-add')).toBeNull()
    expect(slots('or-problem-image-delete')).toHaveLength(4)
    await act(async () => {
      fireEvent.paste(slot('or-problem-images'), { clipboardData: { items: [imageItem(png())], files: [], types: ['Files'] } })
    })
    expect(problemText()).toBe(tx('or.prob.imgLimit', 4))
    expect(prepareImage).not.toHaveBeenCalled()
  })

  it('yapıştırma: görsel yüklenir (okunur adla); metin yapıştırması tarayıcıya bırakılır', async () => {
    or().uploadProblemImage.mockResolvedValueOnce(ok(META(11)))
    const { onChange } = renderImages()
    const root = slot('or-problem-images')
    await act(async () => {
      fireEvent.paste(root, { clipboardData: { items: [{ kind: 'string', type: 'text/plain' }], files: [], types: ['text/plain'] } })
    })
    expect(prepareImage).not.toHaveBeenCalled()

    await act(async () => {
      fireEvent.paste(root, { clipboardData: { items: [imageItem(png('image.png'))], files: [], types: ['Files'] } })
    })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name.startsWith('sorun-pano-')).toBe(true)
  })

  it('sürükle-bırak: dosya sürüklenirken çerçeve vurgulanır, ayrılınca söner; bırakılan dosya yüklenir; dosyasız sürükleme yok sayılır', async () => {
    or().uploadProblemImage.mockResolvedValueOnce(ok(META(12)))
    const { onChange } = renderImages()
    const root = slot('or-problem-images')
    fireEvent.dragOver(root, { dataTransfer: { types: ['text/plain'] } })
    expect(root.className).not.toContain('ring-2')
    fireEvent.dragOver(root, { dataTransfer: { types: ['Files'] } })
    expect(root.className).toContain('ring-2')
    fireEvent.dragLeave(root)
    expect(root.className).not.toContain('ring-2')

    await act(async () => { fireEvent.drop(root, { dataTransfer: { types: ['text/plain'], files: [] } }) })
    expect(prepareImage).not.toHaveBeenCalled()
    const f = png('birakilan.png')
    fireEvent.dragOver(root, { dataTransfer: { types: ['Files'] } })
    await act(async () => { fireEvent.drop(root, { dataTransfer: { types: ['Files'], files: [f] } }) })
    expect(root.className).not.toContain('ring-2')
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0]).toBe(f)
  })

  it('"Panodan yapıştır": yalnız tarayıcı panoyu okuyabiliyorsa; görsel okunur ve yüklenir; izin yok / görsel yok metni', async () => {
    const blob = new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' })
    const read = vi.fn()
      .mockResolvedValueOnce([{ types: ['image/png'], getType: async () => blob }])
      .mockRejectedValueOnce(Object.assign(new Error('no'), { name: 'NotAllowedError' }))
      .mockResolvedValueOnce([{ types: ['text/plain'], getType: async () => new Blob(['x']) }])
    stubClipboard(read)
    or().uploadProblemImage.mockResolvedValueOnce(ok(META(13)))
    const { onChange } = renderImages()
    const paste = slot('or-problem-image-paste')
    expect(paste).not.toBeNull()
    expect(paste.textContent).toContain(TR['or.prob.imgPaste'])

    await act(async () => { fireEvent.click(paste) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name.startsWith('sorun-pano-')).toBe(true)

    await act(async () => { fireEvent.click(slot('or-problem-image-paste')) })
    await waitFor(() => expect(problemText()).toBe(tx('or.prob.clipDenied', pasteKey())))
    await act(async () => { fireEvent.click(slot('or-problem-image-paste')) })
    await waitFor(() => expect(problemText()).toBe(tx('or.prob.clipEmpty', pasteKey())))
    expect(or().uploadProblemImage).toHaveBeenCalledTimes(1)
  })

  it('pano okunamıyorsa "Panodan yapıştır" düğmesi çizilmez', () => {
    Object.defineProperty(globalThis.navigator, 'clipboard', { configurable: true, value: undefined })
    renderImages()
    expect(slot('or-problem-image-paste')).toBeNull()
    expect(slot('or-problem-image-add')).not.toBeNull()
  })

  it('"Görsel ekle" gizli dosya seçicisini açar', () => {
    renderImages()
    const click = vi.spyOn(fileInput(), 'click').mockImplementation(() => {})
    fireEvent.click(slot('or-problem-image-add'))
    expect(click).toHaveBeenCalledTimes(1)
  })

  it('kimliği olmayan sorunda (henüz kaydedilmemiş) yükleme isteği gönderilmez', async () => {
    renderImages({ initial: { can_edit: true, images: [] } })
    await pickFile(png())
    await waitFor(() => expect(prepareImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(slot('or-problem-image-add')).not.toBeDisabled())
    expect(or().uploadProblemImage).not.toHaveBeenCalled()
  })

  it('yükleme sürerken bileşen kapanırsa geç yanıt onChange çağırmaz', async () => {
    const up = deferred()
    or().uploadProblemImage.mockImplementationOnce(() => up.promise)
    const { onChange, unmount } = renderImages()
    await pickFile(png())
    await waitFor(() => expect(or().uploadProblemImage).toHaveBeenCalledTimes(1))
    unmount()
    await act(async () => { up.resolve(ok(META(20))) })
    expect(onChange).not.toHaveBeenCalled()
  })
})

describe('ProblemImages — silme', () => {
  const confirmBtn = () => screen.getByRole('button', { name: TR['or.prob.imgDeleteConfirm'] })

  it('onay sorulur; Vazgeç → istek yok; onaylanınca silinir ve listeden düşer', async () => {
    or().deleteProblemImage.mockResolvedValueOnce(ok({}))
    const { onChange } = renderImages({ initial: problem({ images: [META(1), META(2)] }) })
    const del = screen.getByRole('button', { name: tx('or.prob.imgDelete', 2) })
    fireEvent.click(del)
    await waitFor(() => expect(screen.getByText(TR['or.prob.imgDeleteTitle'])).toBeTruthy())
    expect(screen.getByText(tx('or.prob.imgDeleteBody', 2))).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: TR['or.prob.cancel'] }))
    await waitFor(() => expect(screen.queryByText(TR['or.prob.imgDeleteTitle'])).toBeNull())
    expect(or().deleteProblemImage).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: tx('or.prob.imgDelete', 2) }))
    await act(async () => { fireEvent.click(await waitFor(confirmBtn)) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(or().deleteProblemImage).toHaveBeenCalledWith(55, 2)
    expect(onChange.mock.calls[0][0].images.map((x) => x.id)).toEqual([1])
    expect(slots('or-problem-image')).toHaveLength(1)
  })

  it('silme sürerken düğmeler kilitli ve silinen görselde yükleniyor işareti; zaten yok (IMAGE_NOT_FOUND) → listeden düşer', async () => {
    const d = deferred()
    or().deleteProblemImage.mockImplementationOnce(() => d.promise)
    const { onChange } = renderImages({ initial: problem({ images: [META(1), META(2)] }) })
    fireEvent.click(screen.getByRole('button', { name: tx('or.prob.imgDelete', 1) }))
    await act(async () => { fireEvent.click(await waitFor(confirmBtn)) })
    await waitFor(() => expect(or().deleteProblemImage).toHaveBeenCalledTimes(1))
    const dels = slots('or-problem-image-delete')
    expect(dels.every((b) => b.disabled)).toBe(true)
    expect(dels[0].querySelector('[role="status"]')).not.toBeNull()
    expect(dels[1].querySelector('[role="status"]')).toBeNull()
    // Kilitliyken ikinci silme isteği gönderilmez
    fireEvent.click(dels[1])
    expect(or().deleteProblemImage).toHaveBeenCalledTimes(1)

    await act(async () => { d.resolve({ success: false, status: 404, code: 'IMAGE_NOT_FOUND' }) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(onChange.mock.calls[0][0].images.map((x) => x.id)).toEqual([2])
    expect(slots('or-problem-image-delete').every((b) => !b.disabled)).toBe(true)
  })

  it('ret: sunucunun metni (yoksa yedek metin); yanıt yok ya da istek atarsa "silinip silinmediği bilinmiyor"; liste değişmez', async () => {
    const { onChange } = renderImages({ initial: problem({ images: [META(1)] }) })
    const run = async (res) => {
      if (res instanceof Error) or().deleteProblemImage.mockRejectedValueOnce(res)
      else or().deleteProblemImage.mockResolvedValueOnce(res)
      fireEvent.click(screen.getByRole('button', { name: tx('or.prob.imgDelete', 1) }))
      await act(async () => { fireEvent.click(await waitFor(confirmBtn)) })
      await waitFor(() => expect(slot('or-problem-image-problem')).not.toBeNull())
      await waitFor(() => expect(slots('or-problem-image-delete').every((b) => !b.disabled)).toBe(true))
      return problemText()
    }
    expect(await run({ success: false, status: 403, error: 'Bu görseli silme yetkiniz yok.' })).toBe('Bu görseli silme yetkiniz yok.')
    expect(await run({ success: false, status: 400 })).toBe(TR['or.prob.imgDeleteRefused'])
    expect(await run({ success: false, status: 0, code: 'NETWORK_ERROR' })).toBe(TR['or.prob.imgDeleteUnsure'])
    expect(await run(new TypeError('Failed to fetch'))).toBe(TR['or.prob.imgDeleteUnsure'])
    expect(onChange).not.toHaveBeenCalled()
    expect(slots('or-problem-image')).toHaveLength(1)
    // Yeni bir silme denemesi önceki metni temizler
    or().deleteProblemImage.mockResolvedValueOnce(ok({}))
    fireEvent.click(screen.getByRole('button', { name: tx('or.prob.imgDelete', 1) }))
    await act(async () => { fireEvent.click(await waitFor(confirmBtn)) })
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(slot('or-problem-image-problem')).toBeNull()
  })

  it('istemci yanıtı null döndürürse (oturum yolu devraldı) hiçbir şey değişmez', async () => {
    or().deleteProblemImage.mockResolvedValueOnce(null)
    const { onChange } = renderImages({ initial: problem({ images: [META(1)] }) })
    fireEvent.click(screen.getByRole('button', { name: tx('or.prob.imgDelete', 1) }))
    await act(async () => { fireEvent.click(await waitFor(confirmBtn)) })
    await waitFor(() => expect(or().deleteProblemImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(slots('or-problem-image-delete').every((b) => !b.disabled)).toBe(true))
    expect(onChange).not.toHaveBeenCalled()
    expect(slot('or-problem-image-problem')).toBeNull()
  })
})

describe('ProblemImages — dokunmatik vuruş alanı', () => {
  it('silme düğmesi 28 px + ::after −7 px (40 px); ekleme / yapıştırma / yeniden dene / bırak min-h-10', async () => {
    stubClipboard(vi.fn())
    or().uploadProblemImage.mockResolvedValueOnce({ success: false, status: 503 })
    renderImages({ initial: problem({ images: [META(1)] }) })
    const del = slot('or-problem-image-delete').className.split(/\s+/)
    expect(del).toEqual(expect.arrayContaining(['size-7', 'pointer-coarse:after:absolute', 'pointer-coarse:after:-inset-[7px]']))
    expect(slot('or-problem-image-add').className.split(/\s+/)).toContain('pointer-coarse:min-h-10')
    expect(slot('or-problem-image-paste').className.split(/\s+/)).toContain('pointer-coarse:min-h-10')
    await pickFile(png())
    await waitFor(() => expect(slot('or-problem-image-retry')).not.toBeNull())
    expect(slot('or-problem-image-retry').className.split(/\s+/)).toContain('pointer-coarse:min-h-10')
    expect(slot('or-problem-image-drop').className.split(/\s+/)).toContain('pointer-coarse:min-h-10')
    // Hata metni sararak tam yazılır (kesilmez)
    expect(slot('or-problem-image-problem').querySelector('[role="alert"]').className).toContain('[overflow-wrap:anywhere]')
  })
})
