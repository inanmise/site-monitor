import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse, TEMPLATE, TEAMS, openAllSections } from './helpers/techErrorFixtures.js'

// Başka kanaldan şablon al (2026-10-17, D16): şablon düzenleyicisinin araç çubuğunda "Başka kanaldan al" (yalnız düzenleyebilen);
// pencere okuyabildiğiniz DİĞER kanalları listeler (overview — bu kanal hariç); "Yükle" kaynak şablonu sürüm geçmişindeki
// "düzenleyiciye yükle" ile AYNI yoldan kaydedilmemiş taslak yapar (Kaydet bu kanalın ŞU ANKİ sürümüyle gider); kirli taslak
// önce sorulur; okuma hatası / yarım şablon yüklenmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, templateVersions: vi.fn(), templateVersion: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { importOptions } from '../components/techerror/TemplateImport.jsx'

const te = () => api.techErrors
const POS_TEMPLATE = {
  ...TEMPLATE, lang: 'en', title: 'POS Channel Technical Errors - {month} {year}',
  sections: [TEMPLATE.sections[0], { id: 'pos-note', kind: 'text', title: 'POS notes', text: 'Card terminal notes.' }],
}
const OVERVIEW = {
  success: true,
  data: { period: { year: 2026, month: 9 }, teams: TEAMS, can_create: true, channels: [
    channel(10, 'Mobil'),
    channel(20, 'POS', { lang: 'en', section_count: 2, field_count: 3, slot_count: 0 }),
    channel(30, 'ATM', { team_id: 2, team_name: 'Kurumsal Kanallar', can_edit: false, shared_access: true }),
    channel(40, 'Eski Kanal', { active: false }),
  ] },
}

const importButton = () => document.querySelector('[data-slot="te-tpl-import"]')

async function openEditor() {
  render(<TechErrorReportsPage />)
  await screen.findByText('Rapor başlığı ve dili')
}

async function openDialog() {
  fireEvent.click(screen.getByRole('button', { name: 'Başka kanaldan al' }))
  const dlg = await screen.findByRole('dialog', { name: 'Başka kanalın şablonunu al' })
  return dlg
}

async function pick(dlg, label) {
  const trigger = await within(dlg).findByRole('combobox', { name: 'Şablonu alınacak kanal' })
  fireEvent.mouseDown(trigger)
  const option = await screen.findByRole('option', { name: label })
  fireEvent.mouseDown(option)
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(OVERVIEW)
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => (String(id) === '20'
    ? { success: true, data: { channel: channel(20, 'POS'), template: POS_TEMPLATE, version: 7 } }
    : templateResponse(channel(Number(id), 'Mobil'), 3)))
  te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
})

describe('şablon düzenleyici — başka kanaldan şablon al', () => {
  it('salt okuyan için düğme yok (sürüm geçmişi kalır)', async () => {
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil', { can_edit: false }), 3))
    await openEditor()
    expect(screen.getByText('Şablonu yalnız görüntülüyorsunuz')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sürüm geçmişi' })).toBeInTheDocument()
    expect(importButton()).toBeNull()
    expect(te().overview).not.toHaveBeenCalled()
  })

  it('pencere okuyabildiğiniz DİĞER kanalları "<kanal> — <takım>" diye listeler (bu kanal yok); seçilmeden Yükle kapalı', async () => {
    await openEditor()
    expect(importButton()).not.toBeNull()
    const dlg = await openDialog()
    expect(dlg).toHaveAttribute('data-slot', 'te-tpl-import-dialog')
    expect(within(dlg).getByText(/kaydedilmemiş taslak olarak yüklenir; kaydedene kadar hiçbir şey değişmez/)).toBeInTheDocument()
    await waitFor(() => expect(te().overview).toHaveBeenCalledTimes(1))
    expect(te().overview.mock.calls[0]).toEqual([])          // takım süzgeci yok: okunabilen TÜM kanallar
    const load = within(dlg).getByRole('button', { name: 'Yükle' })
    expect(load).toBeDisabled()
    fireEvent.mouseDown(await within(dlg).findByRole('combobox', { name: 'Şablonu alınacak kanal' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent.trim())
    // Pasif kanal "(pasif)" ile işaretli
    expect(options).toEqual(['ATM — Kurumsal Kanallar', `Eski Kanal — ${TEAMS[0].name} (pasif)`, `POS — ${TEAMS[0].name}`])
    expect(options.some((o) => o.startsWith('Mobil'))).toBe(false)
  })

  it('seçilen şablonun dili taslağınkinden farklıysa satır içi uyarı; aynı dilde uyarı yok', async () => {
    await openEditor()
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    const warn = await within(dlg).findByText('Bu şablon İngilizce dilinde; içe alırsanız rapor dili ve ay adları İngilizce olur.')
    expect(warn).toHaveAttribute('data-slot', 'te-tpl-import-lang')
    expect(warn.closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
    await pick(dlg, 'ATM — Kurumsal Kanallar')
    await waitFor(() => expect(within(dlg).queryByText(/Bu şablon .* dilinde/)).toBeNull())
    expect(dlg.querySelector('[data-slot="te-tpl-import-lang"]')).toBeNull()
  })

  it('okumada 404 (kanal bu arada silinmiş): kanal listeden çıkarılır, seçim temizlenir, pencerede söylenir; düzenleyici değişmez', async () => {
    te().template.mockImplementation(async (id) => (String(id) === '20'
      ? { success: false, status: 404, error: 'Kanal bulunamadı.' }
      : templateResponse(channel(Number(id), 'Mobil'), 3)))
    await openEditor()
    const before = screen.getByLabelText('Rapor başlığı').value
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    const gone = await within(dlg).findByText('“POS” kanalı artık yok (silinmiş ya da erişiminiz kalkmış); listeden çıkarıldı. Başka bir kanal seçin.')
    expect(gone).toHaveAttribute('data-slot', 'te-tpl-import-gone')
    expect(within(dlg).getByRole('button', { name: 'Yükle' })).toBeDisabled()
    expect(dlg.querySelector('[data-slot="te-tpl-import-meta"]')).toBeNull()
    fireEvent.mouseDown(within(dlg).getByRole('combobox', { name: 'Şablonu alınacak kanal' }))
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent.trim())
    expect(options).toEqual(['ATM — Kurumsal Kanallar', `Eski Kanal — ${TEAMS[0].name} (pasif)`])
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue(before)
    expect(screen.queryByText('Kanal bulunamadı.')).toBeNull()
  })

  it('bozuk kaynak şablon (409 TEMPLATE_UNREADABLE, inceleme 2026-10-18): pencerede sunucu iletisi; HİÇBİR şey yüklenmez (sistem şablonu da), seçim değişince bant kalkar', async () => {
    te().template.mockImplementation(async (id) => (String(id) === '20'
      ? { success: false, status: 409, code: 'TEMPLATE_UNREADABLE', error: '“POS” kanalının şablonu okunamadı; kayıt bozulmuş olabilir. Sistem şablonu yerine kullanılmadı.' }
      : templateResponse(channel(Number(id), 'Mobil'), 3)))
    await openEditor()
    const before = screen.getByLabelText('Rapor başlığı').value
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    const msg = await within(dlg).findByText(/“POS” kanalının şablonu okunamadı; kayıt bozulmuş olabilir/)
    expect(msg).toHaveAttribute('data-slot', 'te-tpl-import-unreadable')
    expect(within(dlg).getByText('Kaynak şablon okunamadı')).toBeInTheDocument()
    expect(te().template).toHaveBeenLastCalledWith(20, { strict: true })
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue(before)
    expect(document.querySelector('[data-slot="te-tpl-save"]')).toBeDisabled()
    expect(screen.getByRole('dialog', { name: 'Başka kanalın şablonunu al' })).toBeInTheDocument()
    await pick(dlg, 'ATM — Kurumsal Kanallar')
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-tpl-import-unreadable"]')).toBeNull())
  })

  it('yükleme: kaynak şablon kendi diliyle TASLAK olur; Kaydet bu kanalın ŞU ANKİ sürümüyle gider', async () => {
    await openEditor()
    expect(screen.getByRole('button', { name: 'Şablonu kaydet' })).toBeDisabled()
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    expect(within(dlg).getByText('Şablon dili: İngilizce · 2 bölüm · 3 alan · 0 görsel yuvası')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Başka kanalın şablonunu al' })).toBeNull())
    // KATI okuma (inceleme 2026-10-18): bozuk kaynak sistem şablonuna düşmesin
    expect(te().template).toHaveBeenLastCalledWith(20, { strict: true })
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('POS Channel Technical Errors - {month} {year}')
    expect(screen.getByLabelText('Rapor dili')).toHaveValue('en')
    expect(await screen.findByText('“POS” kanalının şablonu düzenleyiciye yüklendi. Kaydedene kadar hiçbir şey değişmez.')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="te-tpl-section"]')).toHaveLength(2)
    // Kirli taslak: Kaydet açık, kayıt bu kanalın okunan sürümüyle (3) — kaynağın sürümüyle (7) değil
    const save = screen.getByRole('button', { name: 'Şablonu kaydet' })
    expect(save).not.toBeDisabled()
    fireEvent.click(save)
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalledTimes(1))
    const [id, tpl, version] = te().saveTemplate.mock.calls[0]
    expect(id).toBe('10')
    expect(version).toBe(3)
    expect(tpl.title).toBe('POS Channel Technical Errors - {month} {year}')
    expect(tpl.lang).toBe('en')
    expect(tpl.sections.map((s) => s.id)).toEqual(['summary', 'pos-note'])
  })

  it('kaydedilmemiş değişiklik varken önce sorulur; Vazgeç taslağı korur, onay yükler', async () => {
    await openEditor()
    fireEvent.change(screen.getByLabelText('Rapor başlığı'), { target: { value: 'Taslak başlık' } })
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    const confirm = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    expect(within(confirm).getByText(/“POS” kanalının şablonu yüklenirse düzenleyicideki kaydedilmemiş değişiklikler kaybolur/)).toBeInTheDocument()
    fireEvent.click(within(confirm).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('Taslak başlık')
    expect(screen.getByRole('dialog', { name: 'Başka kanalın şablonunu al' })).toBeInTheDocument()   // pencere açık kalır

    fireEvent.click(within(screen.getByRole('dialog', { name: 'Başka kanalın şablonunu al' })).getByRole('button', { name: 'Yükle' }))
    const again = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(again).getByRole('button', { name: 'Şablonu yükle' }))
    await waitFor(() => expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('POS Channel Technical Errors - {month} {year}'))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Başka kanalın şablonunu al' })).toBeNull())
  })

  it('okuma hatası sunucu iletisini söyler, yarım şablon yüklenmez; düzenleyici değişmez, pencere açık kalır', async () => {
    te().template.mockImplementation(async (id) => (String(id) === '20'
      ? { success: false, status: 403, error: 'Bu kanalın şablonunu görme izniniz yok.' }
      : String(id) === '30' ? { success: true, data: { template: { title: 'yarım' } } }
        : templateResponse(channel(Number(id), 'Mobil'), 3)))
    await openEditor()
    const before = screen.getByLabelText('Rapor başlığı').value
    const dlg = await openDialog()
    await pick(dlg, `POS — ${TEAMS[0].name}`)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    expect(await screen.findByText('Bu kanalın şablonunu görme izniniz yok.')).toBeInTheDocument()
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue(before)
    expect(screen.getByRole('dialog', { name: 'Başka kanalın şablonunu al' })).toBeInTheDocument()

    await pick(dlg, 'ATM — Kurumsal Kanallar')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Yükle' }))
    expect(await screen.findByText(/Seçilen kanalın şablonu okunamadı; düzenleyicide hiçbir şey değişmedi/)).toBeInTheDocument()
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue(before)
    // Pencere açıkken arka plan erişilebilirlik ağacından gizli: düğme yuvasıyla bulunur
    expect(document.querySelector('[data-slot="te-tpl-save"]')).toBeDisabled()
  })

  it('kanal listesi okunamazsa açıklama + Tekrar dene; başka kanal yoksa bunu söyler', async () => {
    te().overview.mockResolvedValueOnce({ success: false, status: 503, error: 'Veritabanına şu an ulaşılamıyor. Biraz bekleyip yeniden deneyin.' })
    await openEditor()
    const dlg = await openDialog()
    expect(await within(dlg).findByText('Kanal listesi okunamadı')).toBeInTheDocument()
    expect(within(dlg).getByText('Veritabanına şu an ulaşılamıyor. Biraz bekleyip yeniden deneyin.')).toBeInTheDocument()
    te().overview.mockResolvedValueOnce({ success: true, data: { ...OVERVIEW.data, channels: [channel(10, 'Mobil')] } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    expect(await within(dlg).findByText(/Şablonunu alabileceğiniz başka kanal yok/)).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: 'Yükle' })).toBeDisabled()
  })

  it('importOptions: bu kanal hariç (kimlik metin / sayı fark etmez), ad A→Z Türkçe', () => {
    const rows = [{ id: 3, name: 'Şube' }, { id: '1', name: 'İnternet', team_name: 'B' }, { id: 2, name: 'ATM', team_name: 'A' }, null]
    expect(importOptions(rows, 1).map((o) => o.label)).toEqual(['ATM — A', 'Şube'])
    expect(importOptions(rows, '3').map((o) => o.value)).toEqual(['2', '1'])
    expect(importOptions(null, 1)).toEqual([])
    // Pasif işareti yalnız `active === false` ve ek verildiğinde
    const withPassive = [{ id: 5, name: 'Eski', team_name: 'T', active: false }, { id: 6, name: 'Yeni', active: true }, { id: 7, name: 'Belirsiz' }]
    expect(importOptions(withPassive, 1, { inactiveSuffix: '(pasif)' }).map((o) => o.label)).toEqual(['Belirsiz', 'Eski — T (pasif)', 'Yeni'])
    expect(importOptions(withPassive, 1).map((o) => o.label)).toEqual(['Belirsiz', 'Eski — T', 'Yeni'])
  })
})
