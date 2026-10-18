import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse, TEMPLATE, TEAMS, openAllSections } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Takım şablon kütüphanesi — inceleme bulguları (2026-10-18):
// 1. Yönetim penceresi işlemden sonra listeyi YERİNDE tutar (iskelete dönüp yeniden kurulmaz), "yenileniyor" der; odak
//    satırın menü tetiğine (silmede bir sonraki satıra, liste boşalınca kapat düğmesine) döner.
// 2. Yeni kanal: seçili TR kartına bilerek basmak kaynağı kullanıcının yapar (kütüphane gelince ezilmez); kaynağa
//    dokunulmadıysa kütüphane okunurken "Kanalı aç" bekler.
// 3. "Takım varsayılanı seçili geldi" yalnız otomatik seçim duruyorken.
// 4. Sunucu kütüphane şablonunu yanıtta onaylamazsa (eski pod) uyarı.
// 6. Okunamayan kütüphane kaydı (409 TEMPLATE_UNREADABLE): yeni kanalda seçicinin altında, "Varsayılana dön"de bildirim —
//    taslak değişmez.
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
const hintText = (dialog) => dialog.querySelector('[data-slot="te-source-library-hint"]')?.textContent || ''

/** Bekletilen bir yanıt: `release(value)` çağrılana kadar çözülmez. */
function deferred() {
  let release
  const promise = new Promise((r) => { release = r })
  return { promise, release }
}

async function pickFrom(scope, name, label) {
  const trigger = await within(scope).findByRole('combobox', { name })
  fireEvent.mouseDown(trigger)
  fireEvent.mouseDown(await screen.findByRole('option', { name: label }))
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().createChannel.mockImplementation(async (body) => ({ success: true, data: { id: 30, name: 'ATM',
    template_source: body.library_template_id != null ? { kind: 'library', library_template_id: body.library_template_id }
      : { kind: 'default' } } }))
  te().teamTemplates.mockImplementation(async (id) => (String(id) === '1' ? LIB : libResponse([])))
})

describe('yönetim penceresi — işlem sonrası liste yerinde, odak geri döner (bulgu 1)', () => {
  async function draw() {
    render(<LibraryManagerDialog open teams={[LIB_TEAMS[0]]} defaultTeam="1" onClose={vi.fn()} />)
    const dlg = await screen.findByRole('dialog', { name: 'Takım şablon kütüphanesi' })
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2))
    return dlg
  }

  it('varsayılan yap: yeniden okuma sürerken liste AYNI düğümle durur (iskelet yok, "yenileniyor"), sonra odak aynı satırın menü tetiğinde', async () => {
    te().updateTeamTemplate.mockResolvedValue({ success: true, data: { ...entry(6, 'POS şablonu', { is_default: true }), changed: true } })
    const dlg = await draw()
    const list = dlg.querySelector('[data-slot="te-lib-list"]')
    const trigger = within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' })
    expect(dlg.querySelector('[data-slot="te-lib-refreshing"]')).toHaveAttribute('data-state', 'off')
    const hold = deferred()
    te().teamTemplates.mockImplementation(() => hold.promise)
    pressMenuTrigger(trigger)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Takım varsayılanı yap: POS şablonu' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-lib-refreshing"]')).toHaveAttribute('data-state', 'on'))
    expect(dlg.querySelector('[data-slot="te-lib-refreshing"]')).toHaveTextContent('Liste yenileniyor…')
    expect(dlg.querySelector('[data-slot="te-lib-list"]')).toBe(list)                    // yeniden kurulmadı
    expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(2)
    expect(dlg.querySelector('[data-slot="skeleton"]')).toBeNull()
    expect(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' })).toBe(trigger)
    hold.release(libResponse([entry(6, 'POS şablonu', { lang: 'en', is_default: true, version: 4 }), entry(5, LONG_NAME, { version: 4 })]))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-lib-refreshing"]')).toHaveAttribute('data-state', 'off'))
    expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')[0]).toHaveAttribute('data-entry', '6')   // yeni varsayılan önce
    expect(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' })).toBe(trigger)          // aynı düğüm, yer değiştirdi
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('sil: odak bir sonraki satırın menü tetiğine; son kayıt da silinince kapat düğmesine (gövdeye düşmez)', async () => {
    te().deleteTeamTemplate.mockResolvedValue({ success: true, data: { deleted: true } })
    const dlg = await draw()
    // Önce varsayılan (5) siliniyor → sıradaki POS (6)
    te().teamTemplates.mockResolvedValue(libResponse([entry(6, 'POS şablonu', { lang: 'en' })]))
    pressMenuTrigger(within(dlg).getByRole('button', { name: `${LONG_NAME} — İşlemler` }))
    fireEvent.click(await screen.findByRole('menuitem', { name: `Sil: ${LONG_NAME}` }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kütüphane şablonu silinsin mi?' })).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-lib-entry"]')).toHaveLength(1))
    await waitFor(() => expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' })))
    expect(te().deleteTeamTemplate).toHaveBeenCalledWith('1', 5)
    // Son kayıt silinir → liste boş → odak kapat düğmesinde
    te().teamTemplates.mockResolvedValue(libResponse([]))
    pressMenuTrigger(within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sil: POS şablonu' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kütüphane şablonu silinsin mi?' })).getByRole('button', { name: 'Sil' }))
    expect(await within(dlg).findByText('Kütüphanede şablon yok')).toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: 'Kapat' })))
  })

  it('yeniden adlandır: ad penceresi kapanınca (kayıt ya da vazgeç) odak o satırın menü tetiğine döner', async () => {
    te().updateTeamTemplate.mockResolvedValue({ success: true, data: { ...entry(6, 'POS kanalı'), changed: true } })
    const dlg = await draw()
    const trigger = within(dlg).getByRole('button', { name: 'POS şablonu — İşlemler' })
    pressMenuTrigger(trigger)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Yeniden adlandır: POS şablonu' }))
    let rn = await screen.findByRole('dialog', { name: 'Kütüphane şablonunu yeniden adlandır' })
    fireEvent.click(within(rn).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kütüphane şablonunu yeniden adlandır' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
    te().teamTemplates.mockResolvedValue(libResponse([entry(6, 'POS kanalı', { lang: 'en', version: 4 }), entry(5, LONG_NAME, { is_default: true })]))
    pressMenuTrigger(trigger)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Yeniden adlandır: POS şablonu' }))
    rn = await screen.findByRole('dialog', { name: 'Kütüphane şablonunu yeniden adlandır' })
    fireEvent.change(within(rn).getByLabelText(/Şablon adı/), { target: { value: 'POS kanalı' } })
    fireEvent.click(within(rn).getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kütüphane şablonunu yeniden adlandır' })).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: 'POS kanalı — İşlemler' })))
    expect(within(dlg).getByRole('button', { name: 'POS kanalı — İşlemler' })).toBe(trigger)
  })
})

describe('yeni kanal — kullanıcının kaynak seçimi ve takım varsayılanı (bulgu 2, 3, 4, 6)', () => {
  function draw(onSaved = vi.fn()) {
    render(<ChannelDialog mode="create" teams={LIB_TEAMS} channels={[]} onClose={vi.fn()} onSaved={onSaved} />)
    return screen.findByRole('dialog', { name: 'Yeni kanal' })
  }

  it('kütüphane okunurken seçili TR kartına bilerek basmak: kaynak kullanıcınındır — varsayılan gelince TR kalır, gönderim lang tr', async () => {
    const hold = deferred()
    te().teamTemplates.mockImplementation(() => hold.promise)
    const dialog = await draw()
    expect(radioOf('tr')).toHaveAttribute('data-state', 'checked')
    fireEvent.click(radioOf('tr'))                                                     // değer değişmedi (Radix onValueChange yok)
    expect(within(dialog).getByRole('button', { name: 'Kanalı aç' })).toBeEnabled()    // dokunuldu → beklemez
    hold.release(LIB)
    await waitFor(() => expect(radioOf('library')).not.toBeDisabled())
    expect(radioOf('tr')).toHaveAttribute('data-state', 'checked')
    expect(radioOf('library')).toHaveAttribute('data-state', 'unchecked')
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, lang: 'tr' }))
  })

  it('kaynağa dokunulmadıysa kütüphane okunurken "Kanalı aç" bekler (not görünür, istek yok); varsayılan gelince seçilir ve açılır', async () => {
    const hold = deferred()
    te().teamTemplates.mockImplementation(() => hold.promise)
    const dialog = await draw()
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    const create = within(dialog).getByRole('button', { name: 'Kanalı aç' })
    expect(create).toBeDisabled()
    expect(dialog.querySelector('[data-slot="te-source-waiting"]')).toHaveTextContent('Takımın varsayılan şablonu okunuyor')
    expect(create).toHaveAccessibleDescription(/Takımın varsayılan şablonu okunuyor/)
    fireEvent.submit(create.closest('form'))                                             // Enter ile gönderim de beklemede
    expect(te().createChannel).not.toHaveBeenCalled()
    hold.release(LIB)
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    expect(create).toBeEnabled()
    expect(dialog.querySelector('[data-slot="te-source-waiting"]')).toBeNull()
    fireEvent.click(create)
    await waitFor(() => expect(te().createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, library_template_id: 5 }))
  })

  it('beklerken başka bir kaynak seçmek (İngilizce) bekleyişi bitirir; kütüphane gelince seçim ezilmez', async () => {
    const hold = deferred()
    te().teamTemplates.mockImplementation(() => hold.promise)
    const dialog = await draw()
    expect(within(dialog).getByRole('button', { name: 'Kanalı aç' })).toBeDisabled()
    fireEvent.click(radioOf('en'))
    expect(within(dialog).getByRole('button', { name: 'Kanalı aç' })).toBeEnabled()
    hold.release(LIB)
    await waitFor(() => expect(radioOf('library')).not.toBeDisabled())
    expect(radioOf('en')).toHaveAttribute('data-state', 'checked')
  })

  it('"takım varsayılanı seçili geldi" yalnız otomatik seçim duruyorken; başka kayda ya da TR kaynağına geçince nötr sayı', async () => {
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    expect(hintText(dialog)).toContain(`takım varsayılanı “${LONG_NAME}” seçili geldi`)
    await pickFrom(dialog, 'Kütüphaneden şablon', 'POS şablonu')
    await waitFor(() => expect(hintText(dialog)).not.toContain('seçili geldi'))
    expect(hintText(dialog)).toContain('Takımın kütüphanesinde 2 şablon var; birini seçin')
    // Varsayılanı elle yeniden seçmek "otomatik geldi" demez
    await pickFrom(dialog, 'Kütüphaneden şablon', `${LONG_NAME} (takım varsayılanı)`)
    expect(hintText(dialog)).not.toContain('seçili geldi')
    fireEvent.click(radioOf('tr'))
    await waitFor(() => expect(radioOf('tr')).toHaveAttribute('data-state', 'checked'))
    expect(hintText(dialog)).not.toContain('seçili geldi')
    expect(hintText(dialog)).toContain('2 şablon var')
  })

  it('sunucu kütüphane şablonunu onaylamazsa (eski pod: yanıtta template_source yok) uyarı gösterilir; kanal yine açılmış sayılır', async () => {
    te().createChannel.mockResolvedValue({ success: true, data: { id: 30, name: 'ATM' } })
    const onSaved = vi.fn()
    const dialog = await draw(onSaved)
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await screen.findByText(/sunucu kütüphane şablonunu uyguladığını doğrulamadı/)).toBeInTheDocument()
    expect(onSaved).toHaveBeenCalledWith({ id: 30, name: 'ATM' })
  })

  it('yeni pod onaylarsa uyarı yok; sistem kaynağında onay aranmaz', async () => {
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    expect(await screen.findByText(/“ATM” kanalı açıldı/)).toBeInTheDocument()
    expect(screen.queryByText(/uyguladığını doğrulamadı/)).toBeNull()
  })

  it('okunamayan kütüphane kaydı (409 TEMPLATE_UNREADABLE): hata seçicinin altında, sistem şablonuna geçilmez, liste yeniden okunur', async () => {
    const msg = 'Kütüphanedeki “Takım varsayılanı” şablonu okunamadı; kayıt bozulmuş olabilir. Sistem şablonu yerine kullanılmadı — bu kaydı kütüphaneden silip şablonu bir kanalın şablon ekranından yeniden kaydedin.'
    te().createChannel.mockResolvedValue({ success: false, status: 409, code: 'TEMPLATE_UNREADABLE', field: 'library_template_id', error: msg })
    const dialog = await draw()
    await waitFor(() => expect(radioOf('library')).toHaveAttribute('data-state', 'checked'))
    const calls = te().teamTemplates.mock.calls.length
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    const alert = await within(dialog.querySelector('[data-slot="te-source-library"]')).findByRole('alert')
    expect(alert).toHaveTextContent('şablonu okunamadı')
    await waitFor(() => expect(te().teamTemplates.mock.calls.length).toBeGreaterThan(calls))
    expect(radioOf('library')).toHaveAttribute('data-state', 'checked')
  })
})

describe('"Varsayılana dön" — okunamayan kütüphane kaydı (bulgu 6)', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
    openAllSections()
    te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: LIB_TEAMS, can_create: true,
      channels: [channel(10, 'Mobil')] } })
    te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
      default_period: { year: 2026, month: 9 } } }))
    te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil'), 3))
  })

  it('409 TEMPLATE_UNREADABLE: sunucunun açıklaması bildirimde, taslak DEĞİŞMEZ (sistem TR şablonu "yüklendi" denmez)', async () => {
    const msg = `Kütüphanedeki “${LONG_NAME}” şablonu okunamadı; kayıt bozulmuş olabilir. Sistem şablonu yerine kullanılmadı — bu kaydı kütüphaneden silip şablonu bir kanalın şablon ekranından yeniden kaydedin.`
    te().teamTemplate.mockResolvedValue({ success: false, status: 409, code: 'TEMPLATE_UNREADABLE', error: msg })
    render(<TechErrorReportsPage />)
    await screen.findByText('Rapor başlığı ve dili')
    const before = screen.getByLabelText('Rapor başlığı').value
    pressMenuTrigger(screen.getByRole('button', { name: /Varsayılana dön/ }))
    const menu = await screen.findByRole('menu')
    await waitFor(() => expect(menu.querySelectorAll('[data-slot="te-tpl-reset-lib"]')).toHaveLength(2))
    fireEvent.click(menu.querySelectorAll('[data-slot="te-tpl-reset-lib"]')[0])
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kütüphane şablonu yüklensin mi?' })).getByRole('button', { name: 'Şablonu yükle' }))
    expect(await screen.findByText(/şablonu okunamadı; kayıt bozulmuş olabilir/)).toBeInTheDocument()
    expect(screen.getByLabelText('Rapor başlığı')).toHaveValue(before)
    expect(screen.queryByText(/düzenleyiciye yüklendi/)).toBeNull()
    expect(screen.queryByText('Kaydedilmemiş değişiklikler var')).toBeNull()
    expect(TEMPLATE.title).toBeTruthy()
  })
})
