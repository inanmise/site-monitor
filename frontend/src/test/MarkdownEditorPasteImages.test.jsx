import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, fireEvent, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'

// "Hızlı Giriş" (2026-11-20) — markdown alanına görsel YAPIŞTIRMA / BIRAKMA: imlece yer tutucu, yükleme bitince gerçek
// bağlantı (arada yazılan metin kaybolmaz), başarısızsa yer tutucu silinir + neden; sıra Excel tablosu → görsel → metin;
// bir seferde en çok 10 görsel, aynı anda en çok 3 yükleme; okuma görünümünde görsel büyütülür, olmayan görsel "artık yok".

const { toastMock } = vi.hoisted(() => ({ toastMock: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../components/ui/mdEditor/LazyMdEditor.jsx', () => ({
  default: function FakeMdEditor(props) {
    const tp = props.textareaProps || {}
    return <textarea data-slot="md-ta" {...tp} value={props.value} onChange={(e) => props.onChange(e.target.value)} />
  },
}))

import MarkdownEditor, { MAX_FILES_PER_DROP, MAX_PARALLEL_UPLOADS } from '../components/ui/MarkdownEditor.jsx'
import { UPLOAD_SCHEME, uploadTokensIn, uploadInFlight, hasPendingUpload, resetUploadsForTests } from '../utils/mdUpload.js'

const png = (name = 'image.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const clip = (extra = {}) => ({ items: [], files: [], types: [], getData: () => '', ...extra })
const imageItem = (file) => ({ kind: 'file', type: file.type, getAsFile: () => file })

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function Harness({ initial = '', ...props }) {
  const [v, setV] = useState(initial)
  return (
    <>
      <MarkdownEditor value={v} onChange={setV} {...props} />
      <output data-testid="val">{v}</output>
    </>
  )
}

beforeEach(() => {
  localStorage.setItem('site-monitor-lang', 'tr')
  toastMock.success.mockClear(); toastMock.error.mockClear(); toastMock.info.mockClear()
})
afterEach(() => {
  localStorage.removeItem('site-monitor-lang')
  resetUploadsForTests()
})

/** Editörü üstte DURAN değerle söküp takar (form açık kalırken editörün sökülmesi). */
function Toggle({ initial = '', ...props }) {
  const [v, setV] = useState(initial)
  const [show, setShow] = useState(true)
  return (
    <>
      {show && <MarkdownEditor value={v} onChange={setV} onUpdate={(fn) => setV((cur) => fn(cur))} {...props} />}
      <button type="button" data-testid="toggle" onClick={() => setShow((s) => !s)}>toggle</button>
      <output data-testid="val">{v}</output>
    </>
  )
}

function setup(props = {}) {
  const utils = render(<Harness {...props} />)
  const ta = () => utils.container.querySelector('[data-slot="md-ta"]')
  const val = () => utils.getByTestId('val').textContent
  return { ...utils, ta, val }
}

describe('görsel yapıştırma', () => {
  it('yer tutucu imlece kendi satırında yazılır; yükleme bitince gerçek bağlantıyla değişir', async () => {
    const d = deferred()
    const uploadImage = vi.fn(() => d.promise)
    const s = setup({ initial: 'Giriş\nSon', uploadImage, prepareFile: async (f) => ({ file: f }) })
    s.ta().setSelectionRange?.(6, 6)
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    await waitFor(() => expect(s.val()).toContain(UPLOAD_SCHEME))
    expect(s.container.querySelector('[data-slot="md-uploading"]')).not.toBeNull()
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(1))
    const name = uploadImage.mock.calls[0][1]
    expect(name).toMatch(/^gorsel-\d{8}-\d{6}\.png$/)
    await act(async () => { d.resolve('/api/incidents/images/9') })
    await waitFor(() => expect(s.val()).not.toContain(UPLOAD_SCHEME))
    expect(s.val()).toContain(`![${name}](/api/incidents/images/9)`)
    expect(s.val().startsWith('Giriş')).toBe(true)
    expect(s.val().endsWith('Son')).toBe(true)
    expect(s.container.querySelector('[data-slot="md-uploading"]')).toBeNull()
  })

  it('yükleme sürerken yazılan metin kaybolmaz', async () => {
    const d = deferred()
    const uploadImage = vi.fn(() => d.promise)
    const s = setup({ initial: 'A', uploadImage, prepareFile: async (f) => ({ file: f }) })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png('ekran.png'))], types: ['Files'] }) })
    await waitFor(() => expect(uploadImage).toHaveBeenCalled())
    fireEvent.change(s.ta(), { target: { value: s.val() + '\n\nsonradan yazdım' } })
    await act(async () => { d.resolve('/api/incidents/images/5') })
    await waitFor(() => expect(s.val()).toContain('![ekran.png](/api/incidents/images/5)'))
    expect(s.val()).toContain('sonradan yazdım')
    expect(s.val()).not.toContain(UPLOAD_SCHEME)
  })

  it('başarısız yükleme: yer tutucu silinir, neden söylenir; yazılan metin kalır', async () => {
    const uploadImage = vi.fn(async () => { throw new Error('Görsel 5 MB sınırını aşıyor.') })
    const s = setup({ initial: 'Metin', uploadImage, prepareFile: async (f) => ({ file: f }) })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png('buyuk.png'))], types: ['Files'] }) })
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1))
    expect(toastMock.error.mock.calls[0][0]).toBe(
      TR['md.uploadFailedNamed'].replace('{0}', 'buyuk.png').replace('{1}', 'Görsel 5 MB sınırını aşıyor.'))
    expect(s.val()).not.toContain(UPLOAD_SCHEME)
    expect(s.val()).toContain('Metin')
  })

  it('hazırlama hatası (prepareFile) da yer tutucuyu siler', async () => {
    const uploadImage = vi.fn()
    const s = setup({ uploadImage, prepareFile: async () => { throw new Error('PDF görsel değildir.') } })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled())
    expect(uploadImage).not.toHaveBeenCalled()
    expect(s.val()).not.toContain(UPLOAD_SCHEME)
  })

  it('düz metin yapıştırma metin kalır (işlenmez)', () => {
    const uploadImage = vi.fn()
    const s = setup({ uploadImage })
    const notPrevented = fireEvent.paste(s.ta(), { clipboardData: clip({ items: [{ kind: 'string', type: 'text/plain' }], types: ['text/plain'], getData: () => 'düz' }) })
    expect(notPrevented).toBe(true)
    expect(uploadImage).not.toHaveBeenCalled()
  })

  it('görsel olmayan dosya + metin: metin yapıştırılır', () => {
    const uploadImage = vi.fn()
    const s = setup({ uploadImage })
    const pdf = new File([new Uint8Array([1])], 'a.pdf', { type: 'application/pdf' })
    const notPrevented = fireEvent.paste(s.ta(), { clipboardData: clip({ items: [{ kind: 'file', type: pdf.type, getAsFile: () => pdf }], types: ['Files', 'text/plain'] }) })
    expect(notPrevented).toBe(true)
    expect(uploadImage).not.toHaveBeenCalled()
  })

  it('Excel tablosu önce gelir (görsel de olsa)', async () => {
    const uploadImage = vi.fn()
    const s = setup({ uploadImage })
    const html = '<table><tr><td>Alan</td><td>Değer</td></tr><tr><td>A</td><td>1</td></tr></table>'
    fireEvent.paste(s.ta(), { clipboardData: clip({
      items: [imageItem(png('excel.png'))], types: ['text/html', 'text/plain', 'Files'],
      getData: (type) => (type === 'text/html' ? html : 'Alan\tDeğer\nA\t1'),
    }) })
    await waitFor(() => expect(s.val()).toContain('| Alan | Değer |'))
    expect(uploadImage).not.toHaveBeenCalled()
  })

  it('görsel yükleme yoksa (uploadImage verilmedi) görsel yapıştırma işlenmez', () => {
    const s = setup({})
    const notPrevented = fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    expect(notPrevented).toBe(true)
    expect(s.val()).toBe('')
  })
})

describe('görsel bırakma ve sınırlar', () => {
  it(`bir seferde en çok ${MAX_FILES_PER_DROP} görsel; fazlası söylenir`, async () => {
    const uploadImage = vi.fn(() => new Promise(() => {}))
    const s = setup({ uploadImage, prepareFile: async (f) => ({ file: f }) })
    const files = Array.from({ length: MAX_FILES_PER_DROP + 2 }, (_, i) => png(`g${i}.png`))
    fireEvent.drop(s.ta().parentElement.parentElement, { dataTransfer: { files, types: ['Files'] } })
    await waitFor(() => expect(toastMock.info).toHaveBeenCalledTimes(1))
    expect(toastMock.info.mock.calls[0][0]).toBe(TR['md.tooManyFiles'].replaceAll('{0}', String(MAX_FILES_PER_DROP)).replace('{1}', String(MAX_FILES_PER_DROP + 2)))
    expect(s.val().split(UPLOAD_SCHEME).length - 1).toBe(MAX_FILES_PER_DROP)
  })

  it(`aynı anda en çok ${MAX_PARALLEL_UPLOADS} yükleme; biri bitince sıradaki başlar`, async () => {
    const pending = []
    const uploadImage = vi.fn(() => { const d = deferred(); pending.push(d); return d.promise })
    const s = setup({ uploadImage, prepareFile: async (f) => ({ file: f }) })
    const files = Array.from({ length: 5 }, (_, i) => png(`p${i}.png`))
    fireEvent.drop(s.ta().parentElement.parentElement, { dataTransfer: { files, types: ['Files'] } })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(MAX_PARALLEL_UPLOADS))
    await act(async () => { pending[0].resolve('/api/incidents/images/1') })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(MAX_PARALLEL_UPLOADS + 1))
    expect(s.val()).toContain('](/api/incidents/images/1)')
  })

  it('metin sürüklemesi dosya bırakma sayılmaz', () => {
    const uploadImage = vi.fn()
    const s = setup({ uploadImage })
    fireEvent.drop(s.ta().parentElement.parentElement, { dataTransfer: { files: [], types: ['text/plain'] } })
    expect(uploadImage).not.toHaveBeenCalled()
  })

  it('yazma alanı verilen erişilebilir adı taşır; ipucu görsel yapıştırmayı anlatır', () => {
    const s = setup({ uploadImage: vi.fn(), textareaProps: { 'aria-labelledby': 'lbl-x' } })
    expect(s.ta()).toHaveAttribute('aria-labelledby', 'lbl-x')
    expect(s.container.textContent).toContain(TR['md.pasteHintImages'])
  })
})

describe('yükleme kaydı ve sökülme', () => {
  function setupToggle(props = {}) {
    const utils = render(<Toggle {...props} />)
    const ta = () => utils.container.querySelector('[data-slot="md-ta"]')
    const val = () => utils.getByTestId('val').textContent
    const toggle = () => fireEvent.click(utils.getByTestId('toggle'))
    return { ...utils, ta, val, toggle }
  }

  it('yükleme sürerken belirteç kayıtlıdır; bitince kayıttan düşer', async () => {
    const d = deferred()
    const uploadImage = vi.fn(() => d.promise)
    const s = setupToggle({ uploadImage, prepareFile: async (f) => ({ file: f }) })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(1))
    const [token] = uploadTokensIn(s.val())
    expect(token).toBeTruthy()
    expect(uploadInFlight(token)).toBe(true)
    expect(hasPendingUpload(s.val())).toBe(true)
    await act(async () => { d.resolve('/api/incidents/images/4') })
    await waitFor(() => expect(s.val()).not.toContain(UPLOAD_SCHEME))
    expect(uploadInFlight(token)).toBe(false)
  })

  it('başarısız yükleme de belirteci kayıttan düşürür', async () => {
    const uploadImage = vi.fn(async () => { throw new Error('olmadı') })
    const s = setupToggle({ uploadImage, prepareFile: async (f) => ({ file: f }) })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png())], types: ['Files'] }) })
    const [token] = uploadTokensIn(s.val())
    expect(token).toBeTruthy()
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledTimes(1))
    expect(uploadInFlight(token)).toBe(false)
    expect(s.val()).not.toContain(UPLOAD_SCHEME)
  })

  it('sökülen editör kendi yarım yer tutucularını EN GÜNCEL metinden siler; sonra yazılan metin kalır', async () => {
    const d = deferred()
    const uploadImage = vi.fn(() => d.promise)
    const s = setupToggle({ initial: 'Başlangıç', uploadImage, prepareFile: async (f) => ({ file: f }) })
    fireEvent.paste(s.ta(), { clipboardData: clip({ items: [imageItem(png('ekran.png'))], types: ['Files'] }) })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(1))
    fireEvent.change(s.ta(), { target: { value: s.val() + '\nsonra yazdım' } })
    const [token] = uploadTokensIn(s.val())
    s.toggle()                                    // editör sökülür (yükleme sürüyor)
    await waitFor(() => expect(s.val()).not.toContain(UPLOAD_SCHEME))
    expect(s.val()).toContain('Başlangıç')
    expect(s.val()).toContain('sonra yazdım')
    expect(uploadInFlight(token)).toBe(false)
    // sökülmüş editörün geç gelen sonucu metne YAZILMAZ
    await act(async () => { d.resolve('/api/incidents/images/8') })
    expect(s.val()).not.toContain('/api/incidents/images/8')
  })

  it('sökülünce sıradaki yüklemeler başlamaz', async () => {
    const pending = []
    const uploadImage = vi.fn(() => { const d = deferred(); pending.push(d); return d.promise })
    const s = setupToggle({ uploadImage, prepareFile: async (f) => ({ file: f }) })
    const files = Array.from({ length: MAX_PARALLEL_UPLOADS + 2 }, (_, i) => png(`s${i}.png`))
    fireEvent.drop(s.ta().parentElement.parentElement, { dataTransfer: { files, types: ['Files'] } })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(MAX_PARALLEL_UPLOADS))
    s.toggle()
    await act(async () => { for (const d of pending) d.resolve('/api/incidents/images/1') })
    await act(async () => {})
    expect(uploadImage).toHaveBeenCalledTimes(MAX_PARALLEL_UPLOADS)
    expect(s.val()).not.toContain(UPLOAD_SCHEME)
    expect(s.val()).not.toContain('/api/incidents/images/1')
  })

  it('StrictMode (kur → sök → kur) yüklemeyi bozmaz', async () => {
    const d = deferred()
    const uploadImage = vi.fn(() => d.promise)
    const utils = render(<Toggle uploadImage={uploadImage} prepareFile={async (f) => ({ file: f })} />, { reactStrictMode: true })
    const ta = () => utils.container.querySelector('[data-slot="md-ta"]')
    const val = () => utils.getByTestId('val').textContent
    fireEvent.paste(ta(), { clipboardData: clip({ items: [imageItem(png('a.png'))], types: ['Files'] }) })
    await waitFor(() => expect(uploadImage).toHaveBeenCalledTimes(1))
    await act(async () => { d.resolve('/api/incidents/images/6') })
    await waitFor(() => expect(val()).toContain('![a.png](/api/incidents/images/6)'))
    expect(val()).not.toContain(UPLOAD_SCHEME)
  })
})

describe('okuma görünümü — görseller', () => {
  it('görsel büyütme düğmesi; yüklenemeyen görsel "artık yok" olur', () => {
    const { container } = render(<MarkdownEditor editable={false} imagePreview value={'![Grafik](/api/incidents/images/3)'} />)
    const btn = container.querySelector('[data-slot="md-image"]')
    expect(btn).toHaveAccessibleName(TR['md.imageZoom'].replace('{0}', 'Grafik'))
    fireEvent.error(btn.querySelector('img'))
    expect(container.querySelector('[data-slot="md-image-gone"]').textContent).toContain(TR['md.imageGone'].replace('{0}', 'Grafik'))
  })

  it('imagePreview yoksa düz görsel (eski davranış)', () => {
    const { container } = render(<MarkdownEditor editable={false} value={'![Grafik](/api/incidents/images/3)'} />)
    expect(container.querySelector('[data-slot="md-image"]')).toBeNull()
    expect(container.querySelector('img')).not.toBeNull()
  })
})
