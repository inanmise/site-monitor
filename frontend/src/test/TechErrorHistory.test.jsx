import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, templateResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Faz B (2026-10-14): rapor değişiklik geçmişi paneli, ay geçişi, şablon sürüm geçmişi ve yıllara göre rapor listesi.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
      reportHistory: vi.fn(), templateVersions: vi.fn(), templateVersion: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const REPORT_META = { created_at: '2026-10-01T08:00:00Z', created_by_name: 'Ali Kaya', updated_at: '2026-10-13T09:00:00Z', updated_by_name: 'Ayşe Yılmaz' }

function ev(id, kind, at, extra = {}) {
  return { id, kind, at, actor_name: 'Ayşe Yılmaz', system: false, version: null, detail: {}, ...extra }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
})

describe('rapor değişiklik geçmişi', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse())
  })

  it('başlıktaki düğmeyle açılır; gün başlıklı; değer değişikliği eski → yeni; sistem olayı; daha eski sayfa getirilir', async () => {
    te().reportHistory
      .mockResolvedValueOnce({ success: true, data: { has_more: true, report: REPORT_META, recorded_since: '2026-10-01T08:00:00Z', events: [
        ev(9, 'SEND_RESULT', '2026-10-13T10:00:00Z', { system: true, actor_name: null, detail: { state: 'SENT' } }),
        ev(8, 'VALUES', '2026-10-13T09:00:00Z', { version: 5, detail: { changes: [
          { section: 'Mobil Kanalı — Eylül 2026 Özeti', label: 'Teknik hata oranı', type: 'percent', op: 'change', before: '%0,42', after: '%0,5' },
          { section: 'Mobil Kanalı — Eylül 2026 Özeti', label: 'Toplam işlem adedi', type: 'number', op: 'clear', before: '1.250.000', after: null },
        ] } }),
        ev(7, 'IMAGE_ADD', '2026-10-12T08:00:00Z', { detail: { slot: 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri', file_name: 'grafik.png', width: 1200, height: 600, size_bytes: 204800 } }),
      ] } })
      .mockResolvedValueOnce({ success: true, data: { has_more: false, report: REPORT_META, events: [
        ev(1, 'CREATE', '2026-10-01T08:00:00Z', { detail: { template_version: 3 } }),
      ] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    await within(sheet).findByText('Değerler kaydedildi')
    expect(te().reportHistory).toHaveBeenCalledWith('100')
    expect(within(sheet).getByRole('heading', { name: '13 Ekim 2026' })).toBeInTheDocument()
    expect(within(sheet).getByRole('heading', { name: '12 Ekim 2026' })).toBeInTheDocument()
    const values = sheet.querySelector('[data-kind="VALUES"]')
    expect(values).toHaveTextContent('Teknik hata oranı')
    expect(values).toHaveTextContent('%0,42→yeni değer:%0,5')
    expect(values.querySelector('[data-op="clear"]')).toHaveTextContent('(boş)')
    expect(sheet.querySelector('[data-kind="SEND_RESULT"]')).toHaveTextContent('Sistem')
    expect(sheet.querySelector('[data-kind="SEND_RESULT"]')).toHaveTextContent('Gönderildi')
    expect(sheet.querySelector('[data-kind="IMAGE_ADD"]')).toHaveTextContent('grafik.png · 1200×600 · 200 KB')
    fireEvent.click(within(sheet).getByRole('button', { name: 'Daha eski değişiklikler' }))
    await within(sheet).findByText('Rapor açıldı')
    expect(te().reportHistory).toHaveBeenLastCalledWith('100', 7)
    expect(within(sheet).getByText('Kanal şablonunun 3. sürümüyle açıldı.')).toBeInTheDocument()
    expect(sheet.querySelector('[data-slot="te-history-baseline"]')).toBeNull()        // açılış olayı var → taban notu yok
    expect(within(sheet).queryByRole('button', { name: 'Daha eski değişiklikler' })).toBeNull()
  })

  it('bu özellikten önceki rapor: açılış olayı yoksa sonda taban notu (oluşturan, son kaydeden)', async () => {
    te().reportHistory.mockResolvedValue({ success: true, data: { has_more: false, report: REPORT_META, events: [] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    expect(await within(sheet).findByText('Bu raporda kayıtlı bir değişiklik yok.')).toBeInTheDocument()
    const base = sheet.querySelector('[data-slot="te-history-baseline"]')
    expect(base).toHaveTextContent('Ali Kaya')
    expect(base).toHaveTextContent('Ayşe Yılmaz')
  })

  it('yükleme hatası "değişiklik yok" diye gösterilmez; hata ve yeniden dene', async () => {
    te().reportHistory.mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; biraz sonra deneyin.' })
      .mockResolvedValueOnce({ success: true, data: { has_more: false, report: REPORT_META, events: [ev(1, 'CREATE', '2026-10-01T08:00:00Z')] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    expect(await within(sheet).findByText('Sunucu şu anda yanıt vermiyor; biraz sonra deneyin.')).toBeInTheDocument()
    expect(within(sheet).queryByText('Bu raporda kayıtlı bir değişiklik yok.')).toBeNull()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Tekrar dene' }))
    expect(await within(sheet).findByText('Rapor açıldı')).toBeInTheDocument()
  })

  it('inceleme 2026-10-14: "daha eski" isteği sürerken kapatılırsa yeniden açılışta düğme meşgul kalmaz; kapanınca odak düğmeye döner', async () => {
    const first = { success: true, data: { has_more: true, report: REPORT_META, events: [ev(5, 'VALUES', '2026-10-13T09:00:00Z', { detail: { changes: [] } })] } }
    let releaseMore
    te().reportHistory
      .mockResolvedValueOnce(first)
      .mockImplementationOnce(() => new Promise((res) => { releaseMore = res }))
      .mockResolvedValueOnce(first)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const opener = screen.getByRole('button', { name: 'Değişiklik geçmişi' })
    fireEvent.click(opener)
    let sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    fireEvent.click(await within(sheet).findByRole('button', { name: 'Daha eski değişiklikler' }))
    expect(within(sheet).getByRole('button', { name: 'Daha eski değişiklikler' })).toBeDisabled()
    fireEvent.keyDown(sheet, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Değişiklik geçmişi' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(opener))
    releaseMore({ success: true, data: { has_more: false, report: REPORT_META, events: [ev(1, 'CREATE', '2026-10-01T08:00:00Z')] } })
    fireEvent.click(opener)
    sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    await waitFor(() => expect(within(sheet).getByRole('button', { name: 'Daha eski değişiklikler' })).toBeEnabled())
    expect(within(sheet).queryByText('Rapor açıldı')).toBeNull()                  // geç gelen eski yanıt eklenmedi
  })

  it('bilinmeyen tür / durum ham anahtar göstermez; gönderilmeme sebebi, üzerine gönderim, bilinmeyen kaldırma sayısı, sıra değişimi', async () => {
    te().reportHistory.mockResolvedValue({ success: true, data: { has_more: false, report: REPORT_META, events: [
      ev(6, 'FUTURE_KIND', '2026-10-13T12:00:00Z'),
      ev(5, 'SEND', '2026-10-13T11:00:00Z', { detail: { state: 'SKIPPED', reason: 'INACTIVE_RECIPIENTS' } }),
      ev(4, 'SEND_RESULT', '2026-10-13T10:30:00Z', { system: true, actor_name: null, detail: { state: 'SUPERSEDED' } }),
      ev(3, 'SEND', '2026-10-13T10:00:00Z', { detail: { state: 'SENDING_LATER', reason: 'UNKNOWN_CODE' } }),
      ev(2, 'TEMPLATE_REFRESH', '2026-10-12T10:00:00Z', { detail: { unavailable: true } }),
      ev(1, 'VALUES', '2026-10-12T09:00:00Z', { detail: { changes: [
        { section: 'Konular', label: 'Konular', type: 'issue', op: 'reorder', before: null, after: null },
      ] } }),
    ] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    await within(sheet).findByText('Diğer değişiklik')
    expect(sheet.textContent).not.toMatch(/te\.hist\./)
    const skipped = sheet.querySelector('[data-kind="SEND"]')
    expect(skipped).toHaveTextContent('Gönderilmedi')
    expect(skipped).toHaveTextContent('Alıcı adreslerinin hepsi pasif kullanıcılara ait.')
    expect(sheet.querySelector('[data-kind="SEND_RESULT"]')).toHaveTextContent('Sonucu bilinmiyor (bitmeden yeniden gönderildi)')
    const states = [...sheet.querySelectorAll('[data-slot="te-history-send-state"]')].map((b) => b.getAttribute('data-state'))
    expect(states).toEqual(['SKIPPED', 'SUPERSEDED', 'UNKNOWN'])
    expect(sheet.querySelectorAll('[data-slot="te-history-send-reason"]')).toHaveLength(1)   // bilinmeyen sebep kodu gösterilmez
    expect(sheet.querySelector('[data-kind="TEMPLATE_REFRESH"]')).toHaveTextContent('kaldırılan değer ve görsel sayısı bilinmiyor')
    expect(sheet.querySelector('[data-kind="TEMPLATE_REFRESH"]')).not.toHaveTextContent('0 değer')
    expect(sheet.querySelector('[data-op="reorder"]')).toHaveTextContent('Konuların sırası değişti')
  })

  it('olay sınırına ulaşılan raporda sonda "en eskiler silindi" notu (başlangıç notu değil)', async () => {
    te().reportHistory.mockResolvedValue({ success: true, data: { has_more: false, capped: true, max_events: 500, report: REPORT_META,
      events: [ev(900, 'VALUES', '2026-10-13T09:00:00Z', { detail: { changes: [] } })] } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Değişiklik geçmişi' }))
    const sheet = await screen.findByRole('dialog', { name: 'Değişiklik geçmişi' })
    const base = await waitFor(() => {
      const b = sheet.querySelector('[data-slot="te-history-baseline"]')
      expect(b).not.toBeNull()
      return b
    })
    expect(base).toHaveAttribute('data-capped', 'true')
    expect(base).toHaveTextContent('Rapor başına en yeni 500 değişiklik saklanır; daha eskileri ve bu özellikten önceki değişiklikler burada görünmez.')
    expect(base).not.toHaveTextContent('bu özellikle tutulmaya başladı')
  })
})

describe('ay geçişi', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  })

  it('önceki / sonraki EN YAKIN rapor tek tıkla açılır; kaydedilmemiş değişiklikte önce onay', async () => {
    te().report.mockImplementation(async (id) => (String(id) === '100'
      ? reportResponse({ neighbors: { prev: { id: 90, year: 2026, month: 6 }, next: null } })
      : reportResponse({ report: { ...reportResponse().data.report, id: 90, month: 6 }, neighbors: { prev: null, next: { id: 100, year: 2026, month: 9 } } })))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.queryByRole('button', { name: /Sonraki rapor/ })).toBeNull()
    // kaydedilmemiş değişiklik → onay; vazgeç → bu raporda kalınır
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Önceki rapor: Haziran 2026' }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sayfada kal' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(te().report).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Önceki rapor: Haziran 2026' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Kaydetmeden ayrıl' }))
    await waitFor(() => expect(te().report).toHaveBeenLastCalledWith('90'))
    await waitFor(() => expect(window.location.search).toContain('te_rep=90'))
    expect(await screen.findByRole('button', { name: 'Sonraki rapor: Eylül 2026' })).toBeInTheDocument()
    // Ay geçişinden sonra odak yeni raporun başlığında (tıklanan düğme yeniden kurulumda kayboldu)
    await waitFor(() => expect(document.activeElement).toHaveAttribute('data-slot', 'te-report-title'))
  })

  it('bir işlem sürerken (PDF / kayıt / gönderim) ay düğmeleri kilitli; geçersiz yazılmış alan varken ayrılmak onay ister', async () => {
    te().report.mockResolvedValue(reportResponse({ neighbors: { prev: { id: 90, year: 2026, month: 6 }, next: null } }))
    let releasePdf
    te().downloadPdf.mockImplementation(() => new Promise((res) => { releasePdf = res }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const prev = screen.getByRole('button', { name: 'Önceki rapor: Haziran 2026' })
    fireEvent.click(screen.getByRole('button', { name: 'PDF indir' }))
    await waitFor(() => expect(prev).toBeDisabled())
    releasePdf({ success: true })
    await waitFor(() => expect(prev).toBeEnabled())
    // taslağa girmemiş geçersiz metin (yalnız alanda): sessizce kaybolmaz
    const total = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(total, { target: { value: '12a' } })
    fireEvent.blur(total)
    fireEvent.click(prev)
    expect(await screen.findByRole('dialog')).toHaveTextContent('Kaydetmeden ayrıl')
  })
})

describe('birden çok görsel bölümü', () => {
  it('rapor toplamı ve yükleme ipucu yalnız İLK görsel bölümünde (her bölümde tekrarlanmaz)', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    const tpl = { ...TEMPLATE, sections: [...TEMPLATE.sections,
      { id: 'charts-daily', kind: 'images', title: 'Gün Bazlı', slots: [{ id: 'daily-x', label: 'Gün Bazlı X', width: 'half' }, { id: 'daily-y', label: 'Gün Bazlı Y', width: 'half' }] }] }
    te().report.mockResolvedValue(reportResponse({ template: tpl }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="te-images"]')).toHaveLength(2))
    expect(document.querySelectorAll('[data-slot="te-img-total"]')).toHaveLength(1)
    expect(document.querySelectorAll('[data-slot="te-images"]')[0].querySelector('[data-slot="te-img-total"]')).not.toBeNull()
  })
})

describe('şablon sürüm geçmişi', () => {
  const V2 = { ...TEMPLATE, title: 'Eski başlık', sections: TEMPLATE.sections.map((s, i) => (i === 0
    ? { ...s, fields: [...s.fields, { id: 'eski', label: 'Eski alan', type: 'number' }] } : s)) }

  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil'), 3))
    te().templateVersion.mockImplementation(async (id, v) => ({ success: true, data: {
      version: v, saved_at: '2026-10-0' + v + 'T08:00:00Z', saved_by_name: 'Kişi ' + v, template: Number(v) === 2 ? V2 : TEMPLATE,
      current: Number(v) === 3 } }))
  })

  it('liste, önceki sürüme göre fark; eski sürüm düzenleyiciye yüklenir (taslak, kayıt yeni sürüm olur)', async () => {
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 3, max_versions: 100, versions: [
      { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi 3', section_count: 4, field_count: 3, slot_count: 3 },
      { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: 'Kişi 2', section_count: 4, field_count: 4, slot_count: 3 },
    ] } })
    te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    const diff = await within(dlg).findByText('Sürüm 2 → 3 değişiklikleri')
    expect(diff).toBeInTheDocument()
    expect(within(dlg).getByText('Rapor başlığı: “Eski başlık” → “Mobil Kanalı Teknik Hata Mesajları - Eylül 2026”')).toBeInTheDocument()
    expect(within(dlg).getByText(/Alan kaldırıldı: Eski alan/)).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: 'Bu sürümü düzenleyiciye yükle' })).toBeNull()     // şu anki sürüm
    fireEvent.click(within(dlg).getByRole('button', { name: /Sürüm 2/ }))
    fireEvent.click(await within(dlg).findByRole('button', { name: 'Bu sürümü düzenleyiciye yükle' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Şablon sürüm geçmişi' })).toBeNull())
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('Eski başlık')
    expect(await screen.findByText('Sürüm 2 düzenleyiciye yüklendi. Kaydederseniz yeni bir sürüm olur.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    expect(te().saveTemplate.mock.calls[0][1].title).toBe('Eski başlık')
    expect(te().saveTemplate.mock.calls[0][2]).toBe(3)
  })

  it('kaydedilmemiş değişiklik varken sürüm yüklemek onay ister; vazgeçilirse taslak korunur', async () => {
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 3, max_versions: 100, versions: [
      { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi 3', section_count: 4, field_count: 3, slot_count: 3 },
      { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: 'Kişi 2', section_count: 4, field_count: 4, slot_count: 3 },
    ] } })
    render(<TechErrorReportsPage />)
    const title = await screen.findByLabelText('Rapor başlığı')
    fireEvent.change(title, { target: { value: 'Taslak başlık' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    fireEvent.click(await within(dlg).findByRole('button', { name: /Sürüm 2/ }))
    fireEvent.click(await within(dlg).findByRole('button', { name: 'Bu sürümü düzenleyiciye yükle' }))
    const confirm = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('Taslak başlık')
  })

  it('geçmişi olmayan kanal: o anki sürüm tek satır, "bu özellikle başladı" notu, "henüz kaydedilmedi" (ilk kayıtlı sürüm DEĞİL)', async () => {
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 3, max_versions: 100, versions: [
      { version: 3, current: true, recorded: false, saved_at: '2026-10-01T08:00:00Z', saved_by_name: 'Kişi 3', section_count: 4, field_count: 3, slot_count: 3 },
    ] } })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    expect(await within(dlg).findByText(/Bu sürüm henüz geçmişe kaydedilmedi/)).toBeInTheDocument()
    expect(within(dlg).queryByText(/geçmişteki en eski sürüm/)).toBeNull()
    expect(dlg.querySelector('[data-slot="te-versions-partial"]')).toBeInTheDocument()
    expect(dlg.querySelectorAll('[data-slot="te-version-item"]')).toHaveLength(1)
  })

  it('sürüm sınırına ulaşıldıysa "eskiler silindi" notu; okunamayan sürüm şablonu "fark yok" demez; kapanınca odak düğmeye', async () => {
    const versions = []
    for (let v = 102; v >= 3; v -= 1) {
      versions.push({ version: v, current: v === 102, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi', section_count: 4, field_count: 3, slot_count: 3 })
    }
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 102, max_versions: 100, versions } })
    te().templateVersion.mockImplementation(async (id, v) => ({ success: true, data: { version: v, saved_at: '2026-10-03T08:00:00Z',
      saved_by_name: 'Kişi', template: Number(v) === 101 ? null : TEMPLATE, current: Number(v) === 102 } }))
    render(<TechErrorReportsPage />)
    const opener = await screen.findByRole('button', { name: 'Sürüm geçmişi' })
    fireEvent.click(opener)
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    const note = await waitFor(() => {
      const n = dlg.querySelector('[data-slot="te-versions-partial"]')
      expect(n).not.toBeNull()
      return n
    })
    expect(note).toHaveAttribute('data-pruned', 'true')
    expect(note).toHaveTextContent('Kanal başına en yeni 100 sürüm saklanır; daha eskileri listede yok.')
    expect(await within(dlg).findByText(/Sürümlerden biri okunamadığı için değişiklikler gösterilemiyor/)).toBeInTheDocument()
    expect(within(dlg).queryByText(/yuva yapısı aynı/)).toBeNull()
    fireEvent.keyDown(dlg, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Şablon sürüm geçmişi' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(opener))
  })

  it('tam sınırda ama sürüm 1 listede: hiçbir şey silinmemiş → "eskiler" notu yok (düzeltme incelemesi)', async () => {
    const versions = []
    for (let v = 100; v >= 1; v -= 1) {
      versions.push({ version: v, current: v === 100, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: 'Kişi', section_count: 4, field_count: 3, slot_count: 3 })
    }
    te().templateVersions.mockResolvedValue({ success: true, data: { current_version: 100, max_versions: 100, versions } })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-version-item"]')).toHaveLength(100))
    expect(dlg.querySelector('[data-slot="te-versions-partial"]')).toBeNull()
  })

  it('sürümler yüklenemezse hata + yeniden dene (boş liste gösterilmez)', async () => {
    te().templateVersions.mockResolvedValueOnce({ success: false, status: 500, error: 'Şablon sürümleri okunamadı; biraz sonra deneyin.' })
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablon sürüm geçmişi' })
    expect(await within(dlg).findByText('Şablon sürümleri okunamadı; biraz sonra deneyin.')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-version-item"]')).toBeNull()
  })
})

describe('kanal sayfası — yıllara göre raporlar', () => {
  it('en yeni yıl önce, her yılın başlığı ve rapor sayısı; yıl içinde en yeni ay önce', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
    const row = (id, year, month) => ({ id, channel_id: 10, year, month, status: 'draft', title: `Mobil ${month}/${year}`,
      updated_at: '2026-10-02T07:00:00Z', updated_by_name: 'Ayşe', can_delete: false })
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'),
      reports: [row(1, 2025, 12), row(2, 2026, 8), row(3, 2026, 9)], default_period: { year: 2026, month: 9 } } })
    render(<TechErrorReportsPage />)
    const years = await waitFor(() => {
      const s = document.querySelectorAll('[data-slot="te-report-year"]')
      expect(s).toHaveLength(2)
      return s
    })
    expect([...years].map((s) => s.getAttribute('data-year'))).toEqual(['2026', '2025'])
    expect(within(years[0]).getByRole('heading', { name: /2026/ })).toHaveTextContent('2 rapor')
    expect([...years[0].querySelectorAll('[data-slot="te-report-row"]')].map((r) => r.getAttribute('data-report'))).toEqual(['3', '2'])
  })
})
