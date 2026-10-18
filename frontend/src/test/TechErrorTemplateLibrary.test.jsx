import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse, TEMPLATE, TEAMS, openAllSections } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Takım şablon kütüphanesi (2026-10-18, D17): yeni kanal penceresinde "Takım kütüphanesinden" kaynağı (takım varsayılanı
// ilk seçenek, okunamayan kütüphane "yok" sayılmaz, takım değişince yeniden okunur), şablon düzenleyicisinde "Kütüphaneye
// kaydet" (yeni / üzerine, takım varsayılanı) ve "Varsayılana dön" menüsünde kütüphane kayıtları, kanal listesinde
// kütüphane yönetim penceresi (yeniden adlandır, varsayılan yap, silme onayı kaydın adını söyler).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, templateVersions: vi.fn(), templateVersion: vi.fn(),
      teamTemplates: vi.fn(), teamTemplate: vi.fn(), createTeamTemplate: vi.fn(), updateTeamTemplate: vi.fn(), deleteTeamTemplate: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelDialog from '../components/techerror/ChannelDialog.jsx'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { LibraryManagerDialog } from '../components/techerror/TemplateLibrary.jsx'

const te = () => api.techErrors
const LONG_NAME = 'Dijital Kanallar Bireysel Bankacılık Mobil ve İnternet Şubesi Ortak Aylık Teknik Hata Şablonu (2026)'
const entry = (id, name, extra = {}) => ({
  id, team_id: 1, name, is_default: false, lang: 'tr', section_count: 8, field_count: 20, slot_count: 17,
  created_at: '2026-10-10T08:00:00Z', created_by_name: 'Ayşe Yılmaz', updated_at: '2026-10-17T08:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', version: 3, can_edit: true, ...extra,
})
const libResponse = (templates, extra = {}) => ({ success: true, data: { team_id: 1, team_name: TEAMS[0].name, max: 20, can_edit: true, templates, ...extra } })
const LIB = libResponse([entry(6, 'POS şablonu', { lang: 'en' }), entry(5, LONG_NAME, { is_default: true })])
const LIB_TEAMS = [
  { ...TEAMS[0], library_read: true },
  { id: 3, name: 'Kart Sistemleri', active: true, has_email: true, can_edit: true, can_manage: false, library_read: true },
]
const radioOf = (source) => document.querySelector(`[data-source="${source}"] [role="radio"]`)

async function pickFrom(scope, name, label) {
  const trigger = await within(scope).findByRole('combobox', { name })
  fireEvent.mouseDown(trigger)
  fireEvent.mouseDown(await screen.findByRole('option', { name: label }))
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  // Yeni pod gibi: gerçekten kullanılan şablon kaynağını yanıtta onaylar (template_source)
  te().createChannel.mockImplementation(async (body) => ({ success: true, data: { id: 30, name: 'ATM',
    template_source: body.library_template_id != null ? { kind: 'library', library_template_id: body.library_template_id }
      : { kind: body.copy_from != null ? 'copy' : 'default' } } }))
  te().teamTemplates.mockImplementation(async (id) => (String(id) === '1' ? LIB : libResponse([])))
})

describe('yeni kanal — takım kütüphanesi kaynağı', () => {
  function draw() {
    render(<ChannelDialog mode="create" teams={LIB_TEAMS} channels={[]} onClose={vi.fn()} onSaved={vi.fn()} />)
    return screen.findByRole('dialog', { name: 'Yeni kanal' })
  }

  it('takımın varsayılanı varsa ilk kaynak odur (kayıt seçili); gönderimde library_template_id', async () => {
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    expect(te().teamTemplates).toHaveBeenCalledWith('1')
    expect(within(dialog).getAllByRole('radio')).toHaveLength(3)          // tr / en / kütüphane (kopyalanacak kanal yok)
    expect(dialog.querySelector('[data-slot="te-source-library-hint"]')).toHaveTextContent(`takım varsayılanı “${LONG_NAME}” seçili geldi`)
    expect(within(dialog).getByRole('combobox', { name: 'Kütüphaneden şablon' })).toHaveTextContent(LONG_NAME)
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, library_template_id: 5 }))
  })

  it('varsayılanı olmayan takım: arayüz dilinin varsayılanı kalır; kütüphaneden elle seçilen kayıt gider; takım değişince kütüphane yeniden okunur', async () => {
    te().teamTemplates.mockImplementation(async (id) => (String(id) === '3'
      ? libResponse([entry(9, 'Kart şablonu', { team_id: 3 })]) : LIB))
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    // Takım → Kart Sistemleri: varsayılanı yok → Türkçe varsayılan (kullanıcı kaynağa dokunmadı)
    await pickFrom(dialog, 'Takım', 'Kart Sistemleri')
    await waitFor(() => expect(te().teamTemplates).toHaveBeenCalledWith('3'))
    await waitFor(() => expect(radioOf('tr')).toHaveAttribute('data-state', 'checked'))
    expect(radioOf('library')).not.toBeDisabled()
    fireEvent.click(radioOf('library'))
    // Kayıt seçilmeden gönderim: alan altında hata, istek yok
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'Kart' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await within(dialog).findByText('Kütüphaneden bir şablon seçin ya da başka bir şablon kaynağı işaretleyin.')).toBeInTheDocument()
    expect(te().createChannel).not.toHaveBeenCalled()
    await pickFrom(dialog, 'Kütüphaneden şablon', 'Kart şablonu')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 3, name: 'Kart', description: null, library_template_id: 9 }))
  })

  it('kütüphane okunamazsa kart "okunamadı" der (gizlenmez), seçilemez, yeniden denenir; diğer kaynaklar çalışır', async () => {
    te().teamTemplates.mockResolvedValue({ success: false, status: 500, error: 'Sunucu hatası' })
    const dialog = await draw()
    await waitFor(() => expect(dialog.querySelector('[data-slot="te-source-library-error"]')).not.toBeNull())
    expect(radioOf('library')).toBeDisabled()
    expect(dialog.querySelector('[data-slot="te-source-library-hint"]')).toHaveTextContent('kütüphanesi okunamadı')
    expect(radioOf('tr')).toHaveAttribute('data-state', 'checked')
    te().teamTemplates.mockResolvedValue(LIB)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    fireEvent.click(radioOf('en'))
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, lang: 'en' }))
  })

  it('boş kütüphane: kart çizilir, "henüz şablon yok" der, seçilemez', async () => {
    te().teamTemplates.mockResolvedValue(libResponse([]))
    const dialog = await draw()
    await waitFor(() => expect(dialog.querySelector('[data-slot="te-source-library-hint"]')).toHaveTextContent('henüz şablon yok'))
    expect(radioOf('library')).toBeDisabled()
    expect(radioOf('tr')).toHaveAttribute('data-state', 'checked')
  })

  it('sunucu kütüphane kaydını reddederse (silinmiş) hata seçicinin altında, liste yeniden okunur', async () => {
    te().createChannel.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'library_template_id',
      error: 'Seçilen şablon bu takımın kütüphanesinde bulunamadı; silinmiş olabilir. Listeden yeniden seçin.' })
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    const calls = te().teamTemplates.mock.calls.length
    // Kayıt başka bir sekmede silinmiş, başka bir kayıt varsayılan olmuş: yeniden okuma kaynağı sessizce DEĞİŞTİRMEZ
    // (inceleme 2026-10-18) — kütüphane seçili kalır, seçim temizlenir, hata görünür; ikinci tıklama istek atmaz
    te().teamTemplates.mockResolvedValue(libResponse([entry(6, 'POS şablonu', { is_default: true })]))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await within(dialog).findByText(/bu takımın kütüphanesinde bulunamadı/)).toBeInTheDocument()
    await waitFor(() => expect(te().teamTemplates.mock.calls.length).toBeGreaterThan(calls))
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Kütüphaneden şablon' })).toHaveTextContent('Şablon seçin'))
    expect(radioOf('library')).toHaveAttribute('data-state', 'checked')
    expect(within(dialog).getByText(/bu takımın kütüphanesinde bulunamadı/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await within(dialog).findByText('Kütüphaneden bir şablon seçin ya da başka bir şablon kaynağı işaretleyin.')).toBeInTheDocument()
    expect(te().createChannel).toHaveBeenCalledTimes(1)
  })
})

describe('kütüphane yönetim penceresi', () => {
  function draw(teams = LIB_TEAMS) {
    render(<LibraryManagerDialog open teams={teams} defaultTeam="1" onClose={vi.fn()} />)
    return screen.findByRole('dialog', { name: 'Takım şablon kütüphanesi' })
  }

  it('kayıtlar tablo olarak (önce varsayılan), uzun ad tam, sayımlar ve güncelleyen; takım seçilince o takım okunur', async () => {
    const dlg = await draw()
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2))
    expect(dlg.querySelector('[data-slot="te-lib-list"]')).toHaveAttribute('data-layout', 'table')
    const rows = dlg.querySelectorAll('[data-slot="te-lib-entry"]')
    expect(rows[0]).toHaveAttribute('data-default', 'true')
    expect(rows[0]).toHaveTextContent(LONG_NAME)
    expect(rows[0]).toHaveTextContent('Takım varsayılanı')
    expect(rows[0]).toHaveTextContent('8 bölüm · 20 alan · 17 görsel yuvası')
    expect(rows[1]).toHaveTextContent('Şablon dili: İngilizce')
    expect(dlg.querySelector('[data-slot="te-lib-count"]')).toHaveTextContent('Kütüphanede 2 / 20 şablon')
    await pickFrom(dlg, 'Takım', 'Kart Sistemleri')
    await waitFor(() => expect(te().teamTemplates).toHaveBeenLastCalledWith('3'))
    expect(await within(dlg).findByText('Kütüphanede şablon yok')).toBeInTheDocument()
  })

  it('yeniden adlandır: alan altında hata (çakışan ad), sonra yalnız ad + okunan sürüm gider', async () => {
    te().updateTeamTemplate.mockResolvedValue({ success: true, data: { ...entry(6, 'POS kanalı şablonu'), changed: true } })
    const dlg = await draw()
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2))
    pressMenuTrigger(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Yeniden adlandır: POS şablonu' }))
    const rn = await screen.findByRole('dialog', { name: 'Kütüphane şablonunu yeniden adlandır' })
    const input = within(rn).getByLabelText(/Şablon adı/)
    fireEvent.change(input, { target: { value: LONG_NAME.toLocaleLowerCase('tr') } })
    fireEvent.click(within(rn).getByRole('button', { name: 'Kaydet' }))
    expect(await within(rn).findByText(/Bu ad kütüphanede zaten kullanılıyor/)).toBeInTheDocument()
    expect(te().updateTeamTemplate).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: '  POS kanalı   şablonu ' } })
    fireEvent.click(within(rn).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().updateTeamTemplate).toHaveBeenCalledWith(String(1), 6, { name: 'POS kanalı şablonu', version: 3 }))
  })

  it('varsayılan yap / kaldır: yalnız default + sürüm gider; silme onayı kaydın adını ve varsayılan olduğunu söyler', async () => {
    te().updateTeamTemplate.mockResolvedValue({ success: true, data: { ...entry(6, 'POS şablonu', { is_default: true }), changed: true } })
    te().deleteTeamTemplate.mockResolvedValue({ success: true, data: { deleted: true } })
    const dlg = await draw()
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2))
    pressMenuTrigger(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Takım varsayılanı yap: POS şablonu' }))
    await waitFor(() => expect(te().updateTeamTemplate).toHaveBeenCalledWith('1', 6, { default: true, version: 3 }))
    pressMenuTrigger(within(dlg).getByRole('button', { name: `${LONG_NAME} — İşlemler` }))
    fireEvent.click(await screen.findByRole('menuitem', { name: `Sil: ${LONG_NAME}` }))
    const confirm = await screen.findByRole('dialog', { name: 'Kütüphane şablonu silinsin mi?' })
    expect(confirm).toHaveTextContent(`“${LONG_NAME}” takımın kütüphanesinden kalıcı olarak silinir`)
    expect(confirm).toHaveTextContent('Bu şablon takım varsayılanı')
    fireEvent.click(within(confirm).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(te().deleteTeamTemplate).toHaveBeenCalledWith('1', 5))
  })

  it('salt okuyan (can_edit false): işlem menüsü yok, "yalnız okuyabilirsiniz" der; okuma hatası "yok" demez', async () => {
    te().teamTemplates.mockResolvedValue(libResponse([entry(5, 'Ana', { is_default: true, can_edit: false })], { can_edit: false }))
    const dlg = await draw([LIB_TEAMS[0]])
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(1))
    expect(within(dlg).queryByRole('button', { name: /İşlemler/ })).toBeNull()
    expect(dlg.querySelector('[data-slot="te-lib-readonly"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="te-lib-team"]')).toHaveTextContent(TEAMS[0].name)
  })

  it('okuma hatası: hata bloğu + yeniden dene; "şablon yok" denmez', async () => {
    te().teamTemplates.mockResolvedValue({ success: false, status: 500, error: 'Takımın şablon kütüphanesi okunamadı.' })
    const dlg = await draw([LIB_TEAMS[0]])
    expect(await within(dlg).findByText('Şablon kütüphanesi okunamadı')).toBeInTheDocument()
    expect(within(dlg).queryByText('Kütüphanede şablon yok')).toBeNull()
    te().teamTemplates.mockResolvedValue(LIB)
    fireEvent.click(within(dlg).getByRole('button', { name: /Tekrar dene/ }))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2))
  })
})

describe('şablon düzenleyici — kütüphaneye kaydet ve "Varsayılana dön"', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    openAllSections()
    te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: LIB_TEAMS, can_create: true,
      channels: [channel(10, 'Mobil')] } })
    te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
      default_period: { year: 2026, month: 9 } } }))
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil'), 3))
    te().defaultTemplate.mockImplementation(async (lang) => ({ success: true, data: { template: { ...TEMPLATE, lang, title: `Sistem ${lang} {kanal} {ay} {yil}` } } }))
  })

  async function openEditor() {
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
  }

  it('yeni kayıt: varsayılan ad önerilir, çakışan ad alanın altında (sunucu 409), takım varsayılanı işaretlenerek taslak gider', async () => {
    te().createTeamTemplate
      .mockResolvedValueOnce({ success: false, status: 409, code: 'TEMPLATE_NAME_EXISTS', error: 'Takımın kütüphanesinde “Mobil şablonu” adlı bir şablon zaten var.' })
      .mockResolvedValueOnce({ success: true, data: entry(11, 'Mobil kanallar') })
    await openEditor()
    fireEvent.change(screen.getByLabelText('Rapor başlığı'), { target: { value: 'Yeni başlık {kanal} {ay} {yil}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kütüphaneye kaydet' }))
    const dlg = await screen.findByRole('dialog', { name: 'Takım kütüphanesine kaydet' })
    const name = within(dlg).getByLabelText(/Şablon adı/)
    expect(name).toHaveValue('Mobil şablonu')
    fireEvent.click(within(dlg).getByRole('checkbox'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kütüphaneye kaydet' }))
    expect(await within(dlg).findByText(/adlı bir şablon zaten var/)).toBeInTheDocument()
    expect(te().createTeamTemplate).toHaveBeenLastCalledWith(1, expect.objectContaining({ name: 'Mobil şablonu', default: true,
      template: expect.objectContaining({ title: 'Yeni başlık {kanal} {ay} {yil}' }) }))
    fireEvent.change(name, { target: { value: 'Mobil kanallar' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kütüphaneye kaydet' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Takım kütüphanesine kaydet' })).toBeNull())
    expect(te().createTeamTemplate).toHaveBeenCalledTimes(2)
    expect(te().saveTemplate).not.toHaveBeenCalled()                                   // kanal şablonu kaydedilmez
  })

  it('önerilen ad kütüphane sınırını (100) aşmaz — uzun kanal adı', async () => {
    const longName = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık / Uzun Ad)'
    te().template.mockResolvedValue(templateResponse(channel(10, longName), 3))
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Kütüphaneye kaydet' }))
    const dlg = await screen.findByRole('dialog', { name: 'Takım kütüphanesine kaydet' })
    const value = within(dlg).getByLabelText(/Şablon adı/).value
    expect(value.length).toBeLessThanOrEqual(100)
    expect(value.startsWith('Bireysel İnternet Şubesi')).toBe(true)
  })

  it('üzerine kaydet: seçilen kaydın sürümüyle; varsayılanlık yalnız değiştiyse gönderilir', async () => {
    te().updateTeamTemplate.mockResolvedValue({ success: true, data: { ...entry(6, 'POS şablonu'), changed: true } })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Kütüphaneye kaydet' }))
    const dlg = await screen.findByRole('dialog', { name: 'Takım kütüphanesine kaydet' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-lib-count"]')).toHaveTextContent('2 / 20'))
    fireEvent.click(within(dlg).getByRole('radio', { name: /Var olanın üzerine/ }))
    await pickFrom(dlg, 'Üzerine kaydedilecek şablon', 'POS şablonu')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kütüphaneye kaydet' }))
    await waitFor(() => expect(te().updateTeamTemplate).toHaveBeenCalledWith(1, 6, { template: expect.objectContaining({ sections: expect.any(Array) }), version: 3 }))
  })

  it('"Varsayılana dön" menüsü: sistem TR / EN + takım kütüphanesi (varsayılan önce); kütüphane kaydı onaydan sonra taslağa yüklenir (kaydedilmez)', async () => {
    te().teamTemplate.mockResolvedValue({ success: true, data: { ...entry(5, LONG_NAME, { is_default: true }),
      template: { ...TEMPLATE, title: 'Kütüphane başlığı {kanal} {ay} {yil}' } } })
    await openEditor()
    expect(te().teamTemplates).not.toHaveBeenCalled()                                 // menü açılmadan okunmaz
    pressMenuTrigger(screen.getByRole('button', { name: /Varsayılana dön/ }))
    const menu = await screen.findByRole('menu')
    await waitFor(() => expect(menu.querySelectorAll('[data-slot="te-tpl-reset-lib"]')).toHaveLength(2))
    expect(menu.querySelectorAll('[data-slot="te-tpl-reset-system"]')).toHaveLength(2)
    expect(menu.querySelectorAll('[data-slot="te-tpl-reset-lib"]')[0]).toHaveTextContent(LONG_NAME)
    fireEvent.click(menu.querySelectorAll('[data-slot="te-tpl-reset-lib"]')[0])
    const confirm = await screen.findByRole('dialog', { name: 'Kütüphane şablonu yüklensin mi?' })
    expect(confirm).toHaveTextContent(LONG_NAME)
    fireEvent.click(within(confirm).getByRole('button', { name: 'Şablonu yükle' }))
    await waitFor(() => expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('Kütüphane başlığı {kanal} {ay} {yil}'))
    expect(te().teamTemplate).toHaveBeenCalledWith(1, 5)
    expect(te().saveTemplate).not.toHaveBeenCalled()
    expect(screen.getByText('Kaydedilmemiş değişiklikler var')).toBeInTheDocument()
    // Sistem İngilizce varsayılanı da taslağa yüklenir
    pressMenuTrigger(screen.getByRole('button', { name: /Varsayılana dön/ }))
    const menu2 = await screen.findByRole('menu')
    fireEvent.click(menu2.querySelector('[data-slot="te-tpl-reset-system"][data-lang="en"]'))
    const c2 = await screen.findByRole('dialog', { name: 'Varsayılan şablona dönülsün mü?' })
    fireEvent.click(within(c2).getByRole('button', { name: 'Varsayılanı yükle' }))
    await waitFor(() => expect(screen.getByLabelText('Rapor başlığı')).toHaveValue('Sistem en {kanal} {ay} {yil}'))
    expect(te().defaultTemplate).toHaveBeenCalledWith('en')
  })

  it('menüde kütüphane okunamazsa "okunamadı — yeniden dene" satırı (boş liste gibi davranmaz)', async () => {
    te().teamTemplates.mockResolvedValue({ success: false, status: 500, error: 'x' })
    await openEditor()
    pressMenuTrigger(screen.getByRole('button', { name: /Varsayılana dön/ }))
    const menu = await screen.findByRole('menu')
    await waitFor(() => expect(menu.querySelector('[data-slot="te-tpl-reset-lib-error"]')).not.toBeNull())
    expect(menu.querySelector('[data-slot="te-tpl-reset-lib-empty"]')).toBeNull()
  })
})
