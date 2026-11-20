import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useCallback, useState } from 'react'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// "Geri al" (A3, 2026-11-20): görseli olmayan anomali konusu onaysız silinir ve ~10 sn "Geri al" bildirimi AYNI konuyu
// silindiği yere koyar; arada kayıt olduysa ve olay kaydı bağı silindiyse bunu söyler (bağ silinmediyse söylemez). Görselli
// konu onayla silinir, geri alma yok. Görsel silme / değiştirme sonrası eski baytlar (önceden süre sınırlı okunan) "Geri al"
// ile NORMAL yükleme yolundan geri yüklenir; yuvaya o arada başka görsel geldiyse önce sorulur.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(),
      deleteImage: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
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
import { IssuesBlock } from '../components/techerror/ReportIssues.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu?', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const TPL = { ...TEMPLATE, sections: [
  { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [{ id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' }] },
  ISSUES,
  TEMPLATE.sections.find((s) => s.id === 'charts'),
] }
const topic = (id, name, extra = {}) => ({ id, topic: name, title: '', title_auto: true, description: '', ...extra })
const cards = () => [...document.querySelectorAll('[data-slot="te-issue"]')]
const cardOf = (id) => document.querySelector(`[data-slot="te-issue"][data-issue="${id}"]`)
const undoButton = () => screen.queryByRole('button', { name: 'Geri al' })
const PNG = [0x89, 0x50, 0x4e, 0x47]

let realFetch
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  realFetch = globalThis.fetch
  // Görselin baytları (geri alma için önceden okunur): her görsel kimliği için küçük bir PNG
  globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, blob: async () => new Blob([new Uint8Array(PNG)], { type: 'image/png' }) }))
})
afterEach(() => {
  globalThis.fetch = realFetch
})

function respond(issues, images = []) {
  return reportResponse({ template: TPL, values: { fields: { 'error-count': 5000 }, issues: { issues }, texts: {} }, images })
}

describe('anomali konusu — geri al (düzenleyici)', () => {
  it('görselsiz konu onaysız silinir; "Geri al" aynı konuyu (değerleriyle) silindiği yere koyar', async () => {
    te().report.mockResolvedValue(respond([topic('k1', 'Kart', { description: 'Limit sorunu' }), topic('k2', 'Fatura')]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k1', 'k2'])
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    expect(screen.queryByRole('dialog')).toBeNull()                    // onay sorulmaz
    await waitFor(() => expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k2']))
    expect(await screen.findByText(/konusu silindi\./)).toBeInTheDocument()
    fireEvent.click(undoButton())
    await waitFor(() => expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k1', 'k2']))
    expect(within(cardOf('k1')).getByLabelText('Konu')).toHaveValue('Kart')
    expect(within(cardOf('k1')).getByDisplayValue('Limit sorunu')).toBeInTheDocument()
    expect(await screen.findByText(/konusu geri alındı\./)).toBeInTheDocument()
    expect(screen.queryByText(/olay kaydı bağı/)).toBeNull()
  })

  it('görselli konu onayla silinir ve geri alma sunulmaz', async () => {
    te().report.mockResolvedValue(respond([topic('k1', 'Kart'), topic('k2', 'Fatura')],
      [{ id: 700, slot_id: 'iss:issues:k1:1', file_name: 'k.png', width: 800, height: 400, size_bytes: 4 }]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    const dlg = await screen.findByRole('dialog', { name: 'Konu ve görselleri kaldırılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Konuyu kaldır' }))
    await waitFor(() => expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k2']))
    expect(undoButton()).toBeNull()
  })
})

// Bölüm düzeyi: olay kaydı bağı yalnız GERÇEKTEN silindiyse söylenir; aynı kimlik o arada geri geldiyse çoğaltılmaz
const LINK = { incidentId: 77, state: 'ok', title: 'Kart limiti kesintisi', status: 'RESOLVED', pinned: [], notes: [] }
function Harness({ initial, initialLinks, expose }) {
  const [list, setList] = useState(initial)
  const [links, setLinks] = useState(initialLinks)
  const [readOnly, setReadOnly] = useState(false)
  expose.current = { setLinks, setList, setReadOnly, get list() { return list } }
  const update = useCallback((_sid, fn) => setList((l) => fn(l)), [])
  return (
    <IssuesBlock section={ISSUES} template={TPL} list={list} shownFields={{ 'error-count': 5000 }} resolve={(s) => s} uiLang="tr"
      readOnly={readOnly} errors={{}} onIssuesUpdate={update} onFieldError={() => {}} reportId="100" images={[]}
      onImagesChange={() => {}} period={{ year: 2026, month: 9 }} beforeIssueUpload={async () => ({ ok: true })}
      links={links} linksKnown="ok" />
  )
}

describe('anomali konusu — geri al (bölüm)', () => {
  it('arada kayıt bağı sildiyse geri alma bunu söyler; bağ duruyorsa olağan ileti', async () => {
    const expose = { current: null }
    render(<Harness initial={[topic('k1', 'Kart'), topic('k2', 'Fatura')]} initialLinks={new Map([['k1', LINK]])} expose={expose} />)
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    await waitFor(() => expect(cards()).toHaveLength(1))
    // Kayıt: sunucu silinen konunun bağını sildi
    act(() => { expose.current.setLinks(new Map()) })
    fireEvent.click(await screen.findByRole('button', { name: 'Geri al' }))
    await waitFor(() => expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k1', 'k2']))
    expect(await screen.findByText(/olay kaydı bağı ise arada yapılan kayıtla silinmişti/)).toBeInTheDocument()
  })

  it('bağ hâlâ duruyorsa (kayıt olmadı) bağ uyarısı yok', async () => {
    const expose = { current: null }
    render(<Harness initial={[topic('k1', 'Kart')]} initialLinks={new Map([['k1', LINK]])} expose={expose} />)
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    await waitFor(() => expect(cards()).toHaveLength(0))
    fireEvent.click(await screen.findByRole('button', { name: 'Geri al' }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(await screen.findByText(/konusu geri alındı\./)).toBeInTheDocument()
    expect(screen.queryByText(/olay kaydı bağı ise/)).toBeNull()
  })

  it('aynı kimlik o arada geri geldiyse (Vazgeç / yeniden yükleme) geri alma çoğaltmaz', async () => {
    const expose = { current: null }
    const k1 = topic('k1', 'Kart')
    render(<Harness initial={[k1]} initialLinks={null} expose={expose} />)
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    await waitFor(() => expect(cards()).toHaveLength(0))
    act(() => { expose.current.setList([k1]) })
    fireEvent.click(await screen.findByRole('button', { name: 'Geri al' }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(screen.queryByText(/konusu geri alındı\./)).toBeNull()
  })

  it('onay kilidi inince (B3 inceleme 2026-11-20) açık "Geri al" bildirimi kapanır; geri alma kilitli bölümü değiştirmez', async () => {
    const expose = { current: null }
    render(<Harness initial={[topic('k1', 'Kart'), topic('k2', 'Fatura')]} initialLinks={null} expose={expose} />)
    fireEvent.click(cardOf('k1').querySelector('[data-slot="te-issue-remove"]'))
    await waitFor(() => expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k2']))
    const undo = await screen.findByRole('button', { name: 'Geri al' })
    act(() => { expose.current.setReadOnly(true) })
    await waitFor(() => expect(undoButton()).toBeNull())
    // Düğmeye yine de basılırsa (söküm animasyonu sürerken) konu geri gelmez
    fireEvent.click(undo)
    await act(async () => { await Promise.resolve() })
    expect(cards().map((c) => c.getAttribute('data-issue'))).toEqual(['k2'])
    expect(expose.current.list.map((x) => x.id)).toEqual(['k2'])
    expect(screen.queryByText(/konusu geri alındı./)).toBeNull()
  })

  it('salt okunur bölümde silme düğmesi yok — geri alma sunulmaz', () => {
    const update = vi.fn()
    render(
      <IssuesBlock section={ISSUES} template={TPL} list={[topic('k1', 'Kart')]} shownFields={{}} resolve={(s) => s} uiLang="tr"
        readOnly errors={{}} onIssuesUpdate={update} onFieldError={() => {}} reportId="100" images={[]} onImagesChange={() => {}}
        period={{ year: 2026, month: 9 }} beforeIssueUpload={async () => ({ ok: true })} />,
    )
    expect(cardOf('k1').querySelector('[data-slot="te-issue-remove"]')).toBeNull()
  })
})

describe('görsel yuvası — geri al', () => {
  const slotEl = () => document.querySelector('[data-slot-id="monthly-tx"]')
  const IMG = { id: 501, slot_id: 'monthly-tx', file_name: 'eski-grafik.png', width: 800, height: 400, size_bytes: 4, content_type: 'image/png' }
  const ok = (slotId, file, id) => ({ success: true, data: { id, slot_id: slotId, file_name: file.name, width: 800, height: 400, size_bytes: 4 } })

  it('silme: eski baytlar silmeden önce okunur; "Geri al" aynı yuvaya normal yükleme yolundan geri yükler', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().deleteImage.mockResolvedValue({ success: true })
    te().uploadImage.mockImplementation(async (_r, slotId, file) => ok(slotId, file, 502))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-remove"]'))
    const dlg = await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(te().deleteImage).toHaveBeenCalledWith('100', 501))
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/tech-error-reports/images/501', expect.anything())
    expect(await screen.findByText(/yuvasındaki görsel kaldırıldı\./)).toBeInTheDocument()
    await waitFor(() => expect(slotEl().querySelector('img')).toBeNull())
    fireEvent.click(undoButton())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    const [rid, slot, file] = te().uploadImage.mock.calls[0]
    expect([rid, slot, file.name, file.type, file.size]).toEqual(['100', 'monthly-tx', 'eski-grafik.png', 'image/png', 4])
    expect(prepareImage).toHaveBeenCalledTimes(1)                       // normal yol: tarayıcıda hazırlanır
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
  })

  it('değiştirme: yeni görsel yüklenince "Geri al" eskisini sormadan geri getirir', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().uploadImage.mockImplementationOnce(async (_r, slotId, file) => ok(slotId, file, 503))
      .mockImplementationOnce(async (_r, slotId, file) => ok(slotId, file, 504))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const fresh = new File([new Uint8Array(PNG)], 'yeni.png', { type: 'image/png' })
    fireEvent.change(slotEl().querySelector('input[type="file"]'), { target: { files: [fresh] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/yuvasındaki görsel değiştirildi\./)).toBeInTheDocument()
    fireEvent.click(undoButton())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(2))
    expect(te().uploadImage.mock.calls[1][2].name).toBe('eski-grafik.png')
    expect(screen.queryByRole('dialog', { name: 'Görsel değiştirilsin mi?' })).toBeNull()
  })

  it('silmeden sonra yuvaya yeni görsel geldiyse "Geri al" önce sorar; vazgeçilirse yükleme yok', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().deleteImage.mockResolvedValue({ success: true })
    te().uploadImage.mockImplementation(async (_r, slotId, file) => ok(slotId, file, 600))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-remove"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })).getByRole('button', { name: 'Kaldır' }))
    await screen.findByText(/yuvasındaki görsel kaldırıldı\./)
    const fresh = new File([new Uint8Array(PNG)], 'baska.png', { type: 'image/png' })
    fireEvent.change(slotEl().querySelector('input[type="file"]'), { target: { files: [fresh] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
    fireEvent.click(undoButton())
    // Geri almanın KENDİ sorusu (inceleme 2026-11-20): yapıştırma metni değil
    const ask = await screen.findByRole('dialog', { name: 'Yine de geri alınsın mı?' })
    expect(ask).toHaveTextContent('bu arada başka bir görsel kondu')
    fireEvent.click(within(ask).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Yine de geri alınsın mı?' })).toBeNull())
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
  })

  it('silme onayında Vazgeç: görsel okunmaz, silinmez (inceleme 2026-11-20 — okuma onaydan SONRA başlar)', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().deleteImage.mockResolvedValue({ success: true })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-remove"]'))
    const dlg = await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })
    expect(dlg).toHaveTextContent('“Geri al” ile geri getirebilirsiniz')   // metin geri almayla çelişmez
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Görsel kaldırılsın mı?' })).toBeNull())
    await new Promise((r) => setTimeout(r, 20))
    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(te().deleteImage).not.toHaveBeenCalled()
  })

  it('yuvada yükleme sürerken "Geri al": geri alma kaybolmaz, aynı baytlarla yeniden sunulur; yükleme bitince çalışır', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().deleteImage.mockResolvedValue({ success: true })
    let releaseUpload
    te().uploadImage
      .mockImplementationOnce((_r, slotId, file) => new Promise((r) => { releaseUpload = () => r(ok(slotId, file, 610)) }))
      .mockImplementation(async (_r, slotId, file) => ok(slotId, file, 611))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-remove"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })).getByRole('button', { name: 'Kaldır' }))
    await screen.findByText(/yuvasındaki görsel kaldırıldı\./)
    const fresh = new File([new Uint8Array(PNG)], 'baska.png', { type: 'image/png' })
    fireEvent.change(slotEl().querySelector('input[type="file"]'), { target: { files: [fresh] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))   // sürüyor
    fireEvent.click(undoButton())
    expect(await screen.findByText(/yuvasında bir yükleme sürüyor; bittiğinde buradan geri alabilirsiniz\./)).toBeInTheDocument()
    // yeniden sunuldu (kapanan eski bildirim çıkış canlandırması bitince DOM'dan düşer)
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Geri al' })).toHaveLength(1))
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
    releaseUpload()
    await waitFor(() => expect(slotEl().querySelector('img')).not.toBeNull())
    fireEvent.click(undoButton())
    const ask = await screen.findByRole('dialog', { name: 'Yine de geri alınsın mı?' })
    fireEvent.click(within(ask).getByRole('button', { name: 'Geri al' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(2))
    expect(te().uploadImage.mock.calls[1][2].name).toBe('eski-grafik.png')
  })

  it('baytlar okunamazsa silme yine olur, geri alma sunulmaz', async () => {
    globalThis.fetch = vi.fn(async () => { throw new TypeError('Failed to fetch') })
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    te().deleteImage.mockResolvedValue({ success: true })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(slotEl().querySelector('[data-slot="te-image-remove"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(te().deleteImage).toHaveBeenCalled())
    await waitFor(() => expect(slotEl().querySelector('img')).toBeNull())
    expect(undoButton()).toBeNull()
    expect(screen.queryByText(/yuvasındaki görsel kaldırıldı\./)).toBeNull()
  })
})

describe('konu görseli — geri al açıklamayı da getirir (inceleme 2026-11-20)', () => {
  it('silinen konu görselinin açıklaması taslaktan düşer; "Geri al" görseli ve açıklamayı geri koyar', async () => {
    const TIMG = { id: 720, slot_id: 'iss:issues:k1:1', file_name: 'k.png', width: 800, height: 400, size_bytes: 4, content_type: 'image/png' }
    te().report.mockResolvedValue(respond([topic('k1', 'Kart', { captions: { 1: 'Limit grafiği' } })], [TIMG]))
    te().deleteImage.mockResolvedValue({ success: true })
    te().uploadImage.mockImplementation(async (_r, slotId, file) => ({ success: true,
      data: { id: 721, slot_id: slotId, file_name: file.name, width: 800, height: 400, size_bytes: 4 } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const caption = () => screen.queryByLabelText('Görsel 1 açıklaması (isteğe bağlı)')
    expect(caption()).toHaveValue('Limit grafiği')
    const imgSlot = () => cardOf('k1').querySelector('[data-slot="te-issue-image"][data-n="1"]')
    fireEvent.click(imgSlot().querySelector('[data-slot="te-image-remove"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(te().deleteImage).toHaveBeenCalledWith('100', 720))
    await waitFor(() => expect(caption()).toBeNull())                   // görsel yok → açıklama alanı yok, taslaktan düştü
    fireEvent.click(await screen.findByRole('button', { name: 'Geri al' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(te().uploadImage.mock.calls[0][1]).toBe('iss:issues:k1:1')
    await waitFor(() => expect(caption()).toHaveValue('Limit grafiği'))
  })
})
