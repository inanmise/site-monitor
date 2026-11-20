import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections, TEMPLATE } from './helpers/techErrorFixtures.js'

// Aylık tekrarı azaltma (2026-11-20, C2 / C3 / C4) — rapor düzenleyicisinde:
//   C2 "Sıralı doldur": varsayılan KAPALI; açıkken pencere düzeyindeki Ctrl/⌘+V sıradaki BOŞ yuvaya, yuvanın normal yükleme
//      yolundan yükler; dolu yuvaya ASLA yüklemez; boş yuva kalmayınca söyler.
//   C3 "Geçen ay": isteyince geçen ayın raporu BİR KEZ okunur, aynı yuvanın görseli gösterilir; okunamazsa "okunamadı".
//   C4 toplu bırakma: bölüme bırakılan dosyalar yuvalara eşlenir (varsayılan YALNIZ BOŞ yuva), sırayla yüklenir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const L1 = 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'
const L2 = 'Mobil Kanalı Teknik Hata Grafiği'
const L3 = 'Mobil Kanalı Teknik Hata (Günlük)'
const png = (name = 'image.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const IMG = (id, slot, extra = {}) => ({ id, slot_id: slot, file_name: `${slot}.png`, width: 800, height: 400, size_bytes: 4000, ...extra })
const PREV = {
  success: true,
  data: {
    report: { id: 90, channel_id: 10, year: 2026, month: 8, status: 'sent', version: 2 },
    channel: channel(10, 'Mobil'), template: TEMPLATE, values: { fields: {}, issues: {}, texts: {} },
    images: [IMG(55, 'monthly-tx', { file_name: 'agustos-aylik.png' })],
  },
}

let nextId
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockImplementation(async (id) => (Number(id) === 90 ? PREV : reportResponse()))
  nextId = 700
  te().uploadImage.mockImplementation(async (_r, slotId, file) => ({ success: true, data: IMG(nextId++, slotId, { file_name: file.name }) }))
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
})
afterEach(() => { window.innerWidth = 1024 })

async function openEditor(response = null) {
  if (response) te().report.mockImplementation(async (id) => (Number(id) === 90 ? PREV : response))
  const view = render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  const slot = (id) => view.container.querySelector(`[data-slot-id="${id}"]`)
  return { view, slot }
}
const windowPaste = (file) => fireEvent.paste(document.body, {
  clipboardData: { items: [{ kind: 'file', type: file.type, getAsFile: () => file }], types: ['Files'] },
})
const uploadedSlots = () => te().uploadImage.mock.calls.map((c) => c[1])
const seqSwitch = () => screen.getByRole('switch', { name: 'Sıralı doldur' })
const seqNext = () => document.querySelector('[data-slot="te-seqfill-next"]')
const seqMsg = () => document.querySelector('[data-slot="te-seqfill-msg"]')

describe('C2 — Sıralı doldur', () => {
  it('varsayılan KAPALI: pencere düzeyindeki yapıştırma hiçbir yuvaya yüklemez (bugünkü davranış)', async () => {
    await openEditor()
    expect(seqSwitch()).toHaveAttribute('aria-checked', 'false')
    expect(seqNext()).toBeNull()
    windowPaste(png('pano.png'))
    await new Promise((r) => setTimeout(r, 20))
    expect(te().uploadImage).not.toHaveBeenCalled()
    expect(localStorage.getItem('sm.te.seqfill')).toBeNull()
  })

  it('açıkken ÜÇ yapıştırma ardışık boş yuvalara gider; sonra "boş yuva kalmadı", dolu yuvaya asla yüklenmez', async () => {
    await openEditor()
    fireEvent.click(seqSwitch())
    expect(localStorage.getItem('sm.te.seqfill')).toBe('1')
    expect(seqNext()).toHaveTextContent(`Sıradaki: ${L1} (1/3)`)

    windowPaste(png('a.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['monthly-tx']))
    // Panodan gelen dosya yuvanın adıyla yeniden adlandırılır (ekrandan / panodan alma kuralı)
    expect(te().uploadImage.mock.calls[0][2].name).toMatch(/^monthly-tx-pano-\d{8}-\d{6}\.png$/)
    await waitFor(() => expect(seqNext()).toHaveTextContent(`Sıradaki: ${L2} (2/3)`))
    await waitFor(() => expect(seqMsg()).toHaveTextContent(`Sıradaki boş yuva: ${L2} (2/3).`))

    windowPaste(png('b.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['monthly-tx', 'tech-errors']))
    await waitFor(() => expect(seqNext()).toHaveTextContent(`Sıradaki: ${L3} (3/3)`))

    windowPaste(png('c.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['monthly-tx', 'tech-errors', 'daily-errors']))
    await waitFor(() => expect(seqNext()).toHaveTextContent('Boş yuva kalmadı'))

    windowPaste(png('d.png'))
    await waitFor(() => expect(seqMsg()).toHaveTextContent('Panodaki görsel yüklenmedi: boş yuva kalmadı'))
    expect(te().uploadImage).toHaveBeenCalledTimes(3)
  })

  it('dolu yuva atlanır: ilk yapıştırma sıradaki BOŞ yuvaya gider', async () => {
    await openEditor(reportResponse({ images: [IMG(7, 'monthly-tx')] }))
    fireEvent.click(seqSwitch())
    expect(seqNext()).toHaveTextContent(`Sıradaki: ${L2} (2/3)`)
    windowPaste(png('a.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['tech-errors']))
  })

  it('yazı alanındaki ya da açık penceredeki yapıştırma oraya aittir — yuvaya gitmez', async () => {
    await openEditor()
    fireEvent.click(seqSwitch())
    const ta = document.getElementById('te-t-trend-note')
    fireEvent.paste(ta, { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => png() }], types: ['Files'] } })
    await new Promise((r) => setTimeout(r, 20))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('salt okunur raporda çubuk yok ve yapıştırma dinlenmez', async () => {
    localStorage.setItem('sm.te.seqfill', '1')
    await openEditor(reportResponse({ can_edit: false }))
    expect(document.querySelector('[data-slot="te-seqfill"]')).toBeNull()
    windowPaste(png())
    await new Promise((r) => setTimeout(r, 20))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('telefonda (< 768 px) çubuk yok', async () => {
    window.innerWidth = 390
    await openEditor()
    expect(document.querySelector('[data-slot="te-seqfill"]')).toBeNull()
  })
})

describe('C3 — Geçen ay', () => {
  it('istek yok, açılınca BİR KEZ okunur (bütün yuvalar paylaşır); görsel ya da "görsel yoktu"', async () => {
    const { slot } = await openEditor(reportResponse({ images: [IMG(7, 'monthly-tx')] }))
    expect(te().report.mock.calls.map((c) => Number(c[0]))).not.toContain(90)
    fireEvent.click(within(slot('monthly-tx')).getByRole('button', { name: `Geçen ay: ${L1}` }))
    const thumb = await within(slot('monthly-tx')).findByRole('img', { name: `${L1} — Ağustos 2026` })
    expect(thumb).toHaveAttribute('src', '/api/tech-error-reports/images/55')
    expect(within(slot('monthly-tx')).getByRole('button', { name: `Geçen ay: ${L1}` })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(within(slot('tech-errors')).getByRole('button', { name: `Geçen ay: ${L2}` }))
    expect(await within(slot('tech-errors')).findByText('Ağustos 2026 raporunda bu yuvada görsel yoktu.')).toBeInTheDocument()
    expect(te().report.mock.calls.filter((c) => Number(c[0]) === 90)).toHaveLength(1)
    // Görsel kopyalanmaz
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('büyük görünümde "Bu ay | Geçen ay"', async () => {
    const { slot } = await openEditor(reportResponse({ images: [IMG(7, 'monthly-tx')] }))
    fireEvent.click(within(slot('monthly-tx')).getByRole('button', { name: `Geçen ay: ${L1}` }))
    await within(slot('monthly-tx')).findByRole('img', { name: `${L1} — Ağustos 2026` })
    fireEvent.click(within(slot('monthly-tx')).getByRole('button', { name: `Görseli büyüt: ${L1}` }))
    const dlg = await screen.findByRole('dialog')
    const view = dlg.querySelector('[data-slot="te-image-lightbox-view"]')
    expect(view).toHaveAttribute('data-month', 'current')
    expect(view.querySelector('img')).toHaveAttribute('src', '/api/tech-error-reports/images/7')
    fireEvent.click(within(dlg).getByRole('radio', { name: 'Geçen ay' }))
    await waitFor(() => expect(view).toHaveAttribute('data-month', 'prev'))
    expect(view.querySelector('img')).toHaveAttribute('src', '/api/tech-error-reports/images/55')
    expect(within(dlg).getByRole('heading')).toHaveTextContent(`${L1} — Ağustos 2026 raporu`)
  })

  it('okunamazsa "okunamadı" + yeniden dene (ASLA "görsel yoktu")', async () => {
    te().report.mockImplementation(async (id) => (Number(id) === 90
      ? Object.assign({ success: false, error: 'x' }, { errorInfo: { status: 500, code: 'INTERNAL' } })
      : reportResponse()))
    const { view } = await openEditor()
    const s = view.container.querySelector('[data-slot-id="monthly-tx"]')
    fireEvent.click(within(s).getByRole('button', { name: `Geçen ay: ${L1}` }))
    expect(await within(s).findByText('Ağustos 2026 raporunun görselleri okunamadı — yeniden deneyin.')).toBeInTheDocument()
    expect(within(s).queryByText(/görsel yoktu/)).toBeNull()
    te().report.mockImplementation(async (id) => (Number(id) === 90 ? PREV : reportResponse()))
    fireEvent.click(within(s).getByRole('button', { name: `Tekrar dene: ${L1}` }))
    await within(s).findByRole('img', { name: `${L1} — Ağustos 2026` })
    expect(te().report.mock.calls.filter((c) => Number(c[0]) === 90)).toHaveLength(2)
  })

  it('previous: null ve neighbors alanı yoksa (önceki rapor bilinmiyor) düğme yok', async () => {
    const { slot } = await openEditor(reportResponse({ previous: null }))
    expect(within(slot('monthly-tx')).queryByRole('button', { name: /^Geçen ay/ })).toBeNull()
  })

  it('eski sunucu (yanıtta previous / neighbors hiç yok) ya da önceki rapor YOK: düğme yok, istek yok', async () => {
    const old = reportResponse()
    delete old.data.previous
    const { slot, view } = await openEditor(old)
    expect(within(slot('monthly-tx')).queryByRole('button', { name: /^Geçen ay|^Önceki rapor/ })).toBeNull()
    view.unmount()
    await openEditor(reportResponse({ previous: null, neighbors: { prev: null, next: null } }))
    expect(document.querySelector('[data-slot="te-image-prev-toggle"]')).toBeNull()
    expect(te().report.mock.calls.filter((c) => Number(c[0]) === 90)).toHaveLength(0)
  })

  it('kaynak tam önceki ay değilse (neighbors.prev) "Geçen ay" denmez — raporun ayı söylenir', async () => {
    const { slot } = await openEditor(reportResponse({ images: [IMG(7, 'monthly-tx')], previous: null,
      neighbors: { prev: { id: 90, year: 2026, month: 6 }, next: null } }))
    expect(within(slot('monthly-tx')).queryByRole('button', { name: /^Geçen ay/ })).toBeNull()
    fireEvent.click(within(slot('tech-errors')).getByRole('button', { name: `Önceki rapor: ${L2}` }))
    expect(await within(slot('tech-errors')).findByText('Haziran 2026 raporunda bu yuvada görsel yoktu.')).toBeInTheDocument()
    fireEvent.click(within(slot('monthly-tx')).getByRole('button', { name: `Önceki rapor: ${L1}` }))
    await within(slot('monthly-tx')).findByRole('img', { name: `${L1} — Haziran 2026` })
    fireEvent.click(within(slot('monthly-tx')).getByRole('button', { name: `Görseli büyüt: ${L1}` }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('radio', { name: 'Önceki rapor' })).toBeInTheDocument()
    expect(within(dlg).queryByRole('radio', { name: 'Geçen ay' })).toBeNull()
  })
})

describe('C4 — toplu bırakma', () => {
  const dropOnSection = (files) => {
    const card = document.querySelector('[data-slot="te-images"][data-section="charts"]')
    fireEvent.dragOver(card, { dataTransfer: { types: ['Files'], files } })
    fireEvent.drop(card, { dataTransfer: { types: ['Files'], files } })
  }

  it('eşleme: dosya adındaki yuva kimliği, yuva adı, sıradaki boş; varsayılan YALNIZ BOŞ yuva; sırayla yüklenir', async () => {
    await openEditor(reportResponse({ images: [IMG(7, 'daily-errors')] }))
    dropOnSection([png('tech-errors-pano-20261103-101500.png'), png('Ay Bazlı Toplam İşlem Adetleri.png'), png('IMG_0042.png'),
      png('daily-errors-ekran-20261103-101600.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    const rows = () => [...dlg.querySelectorAll('[data-slot="te-bulk-row"]')]
    expect(rows().map((r) => [r.getAttribute('data-target'), r.getAttribute('data-how')])).toEqual([
      ['tech-errors', 'name'], ['monthly-tx', 'label'], ['', 'noSlot'], ['', 'filled'],
    ])
    // Dolu yuva eşleşmesi atlanır ve nedeni söylenir
    expect(within(rows()[3]).getByText(`Adı “${L3}” yuvasıyla eşleşiyor ama o yuva dolu — atlanacak; değiştirmek için yuvayı seçin.`))
      .toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle (2)' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-bulk"]')).toHaveAttribute('data-phase', 'done'))
    expect(uploadedSlots()).toEqual(['tech-errors', 'monthly-tx'])
    expect(within(dlg).getByText('2 yüklendi, 2 atlandı, 0 yüklenemedi.')).toBeInTheDocument()
    fireEvent.click(dlg.querySelector('[data-slot="te-bulk-close"]'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })).toBeNull())
  })

  it('dolu yuva AÇIKÇA seçilirse başlamadan onay sorulur; vazgeçilirse hiçbir şey yüklenmez', async () => {
    await openEditor(reportResponse({ images: [IMG(7, 'monthly-tx'), IMG(8, 'tech-errors'), IMG(9, 'daily-errors')] }))
    dropOnSection([png('foto.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    expect(within(dlg).getByRole('button', { name: 'Yükle (0)' })).toBeDisabled()
    fireEvent.change(within(dlg).getByRole('combobox', { name: '“foto.png” dosyasının yuvası' }), { target: { value: 'tech-errors' } })
    expect(within(dlg).getByText('Bu yuva dolu — görseli değiştirilecek.')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle (1)' }))
    const confirm = await screen.findByRole('dialog', { name: 'Dolu yuvalar değiştirilsin mi?' })
    expect(confirm).toHaveTextContent(`1 dosya dolu bir yuvaya yüklenecek ve şu görselin yerini alacak: “${L2}”.`)
    fireEvent.click(within(confirm).getByRole('button', { name: 'Vazgeç' }))
    await new Promise((r) => setTimeout(r, 20))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('aynı yuvaya iki dosya seçilirse başlatılamaz', async () => {
    await openEditor()
    dropOnSection([png('a.png'), png('b.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    fireEvent.change(within(dlg).getByRole('combobox', { name: '“b.png” dosyasının yuvası' }), { target: { value: 'monthly-tx' } })
    expect(within(dlg).getAllByText('Bu yuva başka bir dosyaya da seçildi — her yuvaya tek dosya seçin.')).toHaveLength(2)
    expect(within(dlg).getByRole('button', { name: 'Yükle (2)' })).toBeDisabled()
  })

  it('yuvaya birden çok dosya bırakılırsa yalnız ilki yüklenir ve söylenir', async () => {
    const { slot } = await openEditor()
    fireEvent.drop(slot('tech-errors'), { dataTransfer: { types: ['Files'], files: [png('a.png'), png('b.png'), png('c.png')] } })
    await waitFor(() => expect(uploadedSlots()).toEqual(['tech-errors']))
    expect(await screen.findByText(`3 dosya bırakıldı; yalnız ilki “${L2}” yuvasına alındı. Birden çok görseli yuvalara dağıtmak için dosyaları bölümün boş alanına bırakın.`))
      .toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })).toBeNull()
  })

  it('salt okunur raporda toplu bırakma penceresi açılmaz', async () => {
    await openEditor(reportResponse({ can_edit: false }))
    dropOnSection([png('a.png'), png('b.png')])
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })).toBeNull()
  })
})

// İnceleme düzeltmeleri (2026-11-20, bağımsız inceleme)
describe('C2 / C4 — inceleme düzeltmeleri', () => {
  const dropOnSection = (files) => {
    const card = document.querySelector('[data-slot="te-images"][data-section="charts"]')
    fireEvent.dragOver(card, { dataTransfer: { types: ['Files'], files } })
    fireEvent.drop(card, { dataTransfer: { types: ['Files'], files } })
  }
  /** Bitişi elle verilen yükleme (yavaş yükleme sırasında kullanıcı başka bir yere geçer). */
  const deferredUpload = () => {
    const gates = []
    te().uploadImage.mockImplementation((_r, slotId, file) => new Promise((resolve) => {
      gates.push(() => resolve({ success: true, data: IMG(nextId++, slotId, { file_name: file.name }) }))
    }))
    return gates
  }

  it('yükleme sonrası odak sıradaki boş yuvaya geçer (kullanıcı başka bir yere gitmediyse)', async () => {
    const { slot } = await openEditor()
    fireEvent.click(seqSwitch())
    document.activeElement?.blur?.()
    windowPaste(png('a.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['monthly-tx']))
    await waitFor(() => expect(document.activeElement).toBe(slot('tech-errors')))
  })

  it('yavaş yükleme sırasında kullanıcı bir alana geçip yazıyorsa odak YERİNDE kalır', async () => {
    const gates = deferredUpload()
    const { slot } = await openEditor()
    fireEvent.click(seqSwitch())
    document.activeElement?.blur?.()
    windowPaste(png('a.png'))
    await waitFor(() => expect(gates).toHaveLength(1))
    const ta = document.getElementById('te-t-trend-note')
    ta.focus()
    fireEvent.change(ta, { target: { value: 'yazıyorum' } })
    gates[0]()
    await waitFor(() => expect(slot('monthly-tx')).toHaveAttribute('data-has-image', 'true'))
    await waitFor(() => expect(seqMsg()).toHaveTextContent(`Sıradaki boş yuva: ${L2} (2/3) — odak bulunduğunuz yerde bırakıldı.`))
    await new Promise((r) => setTimeout(r, 300))          // odak denemeleri (0 / 80 / 250 ms) bitsin
    expect(document.activeElement).toBe(ta)
  })

  it('telefonda (anahtar yok) açık kalmış "Sıralı doldur" pencere yapıştırmasını DİNLEMEZ', async () => {
    localStorage.setItem('sm.te.seqfill', '1')
    window.innerWidth = 390
    await openEditor()
    windowPaste(png('a.png'))
    await new Promise((r) => setTimeout(r, 30))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('düzenleyiciden çıkınca pencere yapıştırma dinleyicisi kalkar', async () => {
    localStorage.setItem('sm.te.seqfill', '1')
    const { view } = await openEditor()
    expect(seqSwitch()).toHaveAttribute('aria-checked', 'true')
    view.unmount()
    windowPaste(png('a.png'))
    await new Promise((r) => setTimeout(r, 30))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('yüklenemeyen yapıştırma (hazırlanamadı) sonsuza dek "yükleniyor" demez — nedeniyle biter', async () => {
    await openEditor()
    fireEvent.click(seqSwitch())
    const { ImagePrepError } = await import('../components/techerror/imagePrep.js')
    prepareImage.mockImplementationOnce(async () => { throw new ImagePrepError('te.img.errDecode', []) })
    windowPaste(png('a.png'))
    await waitFor(() => expect(seqMsg()).toHaveTextContent(`“${L1}” yuvasına yüklenemedi — nedeni yuvanın altında yazıyor.`))
    expect(seqMsg()).not.toHaveTextContent('yükleniyor')
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('yüklenmeyen HER sonucun kendi son cümlesi var (TR + EN) — "yükleniyor…" diye takılı kalmaz', async () => {
    const { SEQ_OUTCOME_KEYS, seqOutcomeKey } = await import('../components/techerror/ImageFill.jsx')
    const { TR } = await import('../i18n/tr.js')
    const { EN } = await import('../i18n/en.js')
    expect(seqOutcomeKey({ status: 'done' })).toBeNull()
    expect(seqOutcomeKey({ status: 'failed', message: 'x' })).toBe('te.seq.pasteFailed')
    for (const reason of ['filled', 'busy', 'kept', 'noSlot', 'readOnly', 'notUploaded']) {
      const key = seqOutcomeKey({ status: 'skipped', reason })
      expect(key).toBe(SEQ_OUTCOME_KEYS[reason])
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
    expect(seqOutcomeKey({ status: 'skipped', reason: 'bilinmeyen' })).toBe('te.seq.notUploaded')
    expect(seqOutcomeKey(undefined)).toBe('te.seq.notUploaded')
  })

  it('gönderilemeyen (bekletilen) görseli olan yuva sıralı yapıştırmada ATLANIR — bekletilen görsel kaybolmaz', async () => {
    const { slot } = await openEditor()
    te().uploadImage.mockImplementationOnce(async () => { throw new Error('Ağ bağlantısı koptu.') })
    fireEvent.change(document.getElementById('te-img-monthly-tx'), { target: { files: [png('kirpilmis.png')] } })
    await waitFor(() => expect(slot('monthly-tx').querySelector('[data-slot="te-image-kept"]')).not.toBeNull())
    fireEvent.click(seqSwitch())
    windowPaste(png('b.png'))
    await waitFor(() => expect(uploadedSlots()).toEqual(['monthly-tx', 'tech-errors']))
    // Bekletilen görsel hâlâ orada ("Yeniden dene" sunuluyor)
    expect(slot('monthly-tx').querySelector('[data-slot="te-image-kept"]')).not.toBeNull()
  })

  it('toplu bırakma bekletilen görseli olan yuvayı hedef SEÇMEZ (ad eşleşse bile) ve seçilemez', async () => {
    const { slot } = await openEditor()
    te().uploadImage.mockImplementationOnce(async () => { throw new Error('Ağ bağlantısı koptu.') })
    fireEvent.change(document.getElementById('te-img-monthly-tx'), { target: { files: [png('kirpilmis.png')] } })
    await waitFor(() => expect(slot('monthly-tx').querySelector('[data-slot="te-image-kept"]')).not.toBeNull())
    dropOnSection([png('monthly-tx-pano-20261103-101500.png'), png('foto.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    const rows = [...dlg.querySelectorAll('[data-slot="te-bulk-row"]')]
    expect(rows.map((r) => [r.getAttribute('data-target'), r.getAttribute('data-how')])).toEqual([['', 'kept'], ['tech-errors', 'next']])
    expect(within(rows[0]).getByText(`“${L1}” yuvasında gönderilemeyen bir görsel bekliyor — o yuvaya yüklenmez.`)).toBeInTheDocument()
    const opt = within(rows[0]).getByRole('option', { name: `${L1} (gönderilemeyen görsel bekliyor)` })
    expect(opt).toBeDisabled()
  })

  it('eşleme açıldıktan SONRA dolan yuva yine de değiştirilmez (yuvanın kendi denetimi): "dolu" diye atlanır', async () => {
    const { slot } = await openEditor()
    dropOnSection([png('monthly-tx-pano-20261103-101500.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    expect(dlg.querySelector('[data-slot="te-bulk-row"]')).toHaveAttribute('data-target', 'monthly-tx')
    // Pencere açıkken yuva doldu (ör. Görsel paneli ya da yuvaya doğrudan yükleme)
    fireEvent.change(document.getElementById('te-img-monthly-tx'), { target: { files: [png('dogrudan.png')] } })
    await waitFor(() => expect(slot('monthly-tx')).toHaveAttribute('data-has-image', 'true'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle (1)' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-bulk"]')).toHaveAttribute('data-phase', 'done'))
    expect(dlg.querySelector('[data-slot="te-bulk-row"]')).toHaveAttribute('data-state', 'skipped')
    expect(uploadedSlots()).toEqual(['monthly-tx'])                       // yalnız doğrudan yükleme — ikinci yükleme YOK
    expect(within(dlg).getByText('0 yüklendi, 1 atlandı, 0 yüklenemedi.')).toBeInTheDocument()
  })

  it('kilit çakışmasından sonra yuvada BU pencerenin görseli bulunursa toplu bırakma "yüklendi" der', async () => {
    let conflicted = false
    te().uploadImage.mockImplementation(async () => {
      conflicted = true
      return Object.assign({ success: false, code: 'LOCK_CONFLICT', error: 'Kilit çakışması', retry_after_seconds: 0 },
        { errorInfo: { status: 503, code: 'LOCK_CONFLICT' } })
    })
    te().report.mockImplementation(async (id) => {
      if (Number(id) === 90) return PREV
      return conflicted ? reportResponse({ images: [IMG(901, 'monthly-tx', { file_name: 'monthly-tx-pano-20261103-101500.png', size_bytes: 4 })] })
        : reportResponse()
    })
    const { slot } = await openEditor()
    dropOnSection([png('monthly-tx-pano-20261103-101500.png')])
    const dlg = await screen.findByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle (1)' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-bulk"]')).toHaveAttribute('data-phase', 'done'))
    expect(dlg.querySelector('[data-slot="te-bulk-row"]')).toHaveAttribute('data-state', 'done')
    expect(within(dlg).getByText('1 yüklendi, 0 atlandı, 0 yüklenemedi.')).toBeInTheDocument()
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(slot('monthly-tx')).toHaveAttribute('data-has-image', 'true'))
  })
})
