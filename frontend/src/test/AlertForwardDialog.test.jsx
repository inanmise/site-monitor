import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

/**
 * Alarm iletimi penceresi (2026-11-05): yeni kayıt (türler + takım; izleme seçici; üye seçici — opak kimlik), istemci
 * doğrulaması alanın altında (istek yok), düzenlemede YALNIZ değişen alanlar (değişmediyse istek yok), sunucu alan hatası
 * alanın altında, 409 pencerede, 404 → onGone, "görüntüleme erişimi" kutusu, adressiz takım uyarısı, özet kutusu.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    alertForwards: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), monitors: vi.fn() },
    teams: { directory: vi.fn(), members: vi.fn() },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder, id }) => (
    <input id={id} aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />
  ),
}))
import { api } from '../api/client'
import AlertForwardDialog from '../components/admin/forwards/AlertForwardDialog.jsx'
import { normalizeForward } from '../components/admin/forwards/alertForwardModel.js'

const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const TEAMS = [{ id: 10, name: OWNER }, { id: 11, name: 'Kart Operasyon' }]
const DIR = [{ id: 10, name: OWNER, active: true, email: 'odeme@x' }, { id: 20, name: LONG_TEAM, active: true, email: 'mobil@x' },
  { id: 30, name: 'Ağ Operasyon', active: true, email: '' }, { id: 50, name: 'Arşiv Takımı', active: false, email: 'a@x' }]

const pick = (opt) => { fireEvent.mouseDown(opt); fireEvent.click(opt) }
const form = () => document.querySelector('[data-slot="fwd-form"]')
const submit = () => fireEvent.click(document.querySelector('[data-slot="fwd-submit"]'))
const combobox = (label) => within(form()).getByRole('combobox', { name: new RegExp(`^${label}`) })

async function chooseRecipientTeam(name) {
  const picker = combobox(EN['fwd.f.recipientTeam'])
  await waitFor(() => expect(picker).not.toBeDisabled())
  fireEvent.mouseDown(picker)
  pick(await screen.findByRole('option', { name }))
  await waitFor(() => expect(picker).toHaveTextContent(name))
}

function renderDialog(props = {}) {
  const onSaved = vi.fn()
  const onClose = vi.fn()
  const onGone = vi.fn()
  render(<AlertForwardDialog open teams={TEAMS} canGrant onSaved={onSaved} onClose={onClose} onGone={onGone} {...props} />)
  return { onSaved, onClose, onGone }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.teams.directory.mockResolvedValue({ success: true, data: DIR })
  api.teams.members.mockResolvedValue({ success: true, data: { team: { id: 20 }, members: [
    { id: 'op-a', display_name: LONG_PERSON, title: 'Bölüm Başkanı', department: 'Teknoloji Servis Yönetimi', email: 'a@x', active: true },
    { id: 'op-b', display_name: 'Barış Ünlü', title: 'Uzman', email: '', active: true },
    { id: 'op-c', display_name: 'Pasif Kişi', email: 'p@x', active: false },
  ] } })
  api.alertForwards.monitors.mockResolvedValue({ success: true, data: { items: [
    { type: 'http', id: 5, name: 'Ödeme ağ geçidi', target: 'https://odeme.example.com', active: true },
    { type: 'ping', id: 6, name: 'Çekirdek yönlendirici', target: '10.0.0.1', active: false },
    { type: 'cert', id: 40, name: 'ornek.example.com', target: 'ornek.example.com', active: true },
  ] } })
  api.alertForwards.create.mockResolvedValue({ success: true, data: { id: 1 }, grant: null, info: { emails: 1, push: 0 } })
  api.alertForwards.update.mockResolvedValue({ success: true, data: { id: 1 } })
})

describe('AlertForwardDialog — yeni', () => {
  it('türler + takımın tamamı → tam gövde; pasif takım ve sahip takım alıcı listesinde yok; özet kutusu', async () => {
    const { onSaved } = renderDialog()
    // Sahip takım seç (iki yönetilen takım → otomatik seçilmez)
    fireEvent.mouseDown(combobox(EN['fwd.f.owner']))
    pick(await screen.findByRole('option', { name: OWNER }))
    fireEvent.click(within(form()).getByRole('radio', { name: new RegExp(EN['fwd.scope.types']) }))
    const chips = form().querySelector('[data-slot="fwd-type-chips"]')
    expect(chips.querySelectorAll('[data-type]')).toHaveLength(11)
    fireEvent.click(chips.querySelector('[data-type="ping"]'))
    fireEvent.click(chips.querySelector('[data-type="cert"]'))
    const picker = combobox(EN['fwd.f.recipientTeam'])
    await waitFor(() => expect(picker).not.toBeDisabled())
    fireEvent.mouseDown(picker)
    const opt = await screen.findByRole('option', { name: LONG_TEAM })
    const names = [...document.querySelectorAll('[cmdk-item]')].map((o) => o.textContent)
    expect(names).toEqual([LONG_TEAM, 'Ağ Operasyon'])
    pick(opt)
    await waitFor(() => expect(picker).toHaveTextContent(LONG_TEAM))
    expect(picker.innerHTML).not.toContain('truncate')
    const sum = form().querySelector('[data-slot="fwd-summary"]')
    expect(sum.querySelector('[data-slot="fwd-sum-owner"]')).toHaveTextContent(OWNER)
    expect(sum.querySelector('[data-slot="fwd-sum-recipient"]')).toHaveTextContent(`${LONG_TEAM} — whole team`)
    expect(sum.querySelector('[data-slot="fwd-sum-scope"]')).toHaveTextContent(`${EN['fwd.type.cert']}, ${EN['nav.ping']}`)
    submit()
    await waitFor(() => expect(api.alertForwards.create).toHaveBeenCalledWith({ owner_team_id: 10, scope_kind: 'TYPES',
      scope_types: ['cert', 'ping'], scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 20, recipient_user_ids: [] }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  it('istemci doğrulaması: eksikler alanın altında, istek yok', async () => {
    renderDialog()
    fireEvent.click(within(form()).getByRole('radio', { name: new RegExp(EN['fwd.scope.types']) }))
    submit()
    await waitFor(() => expect(form().querySelector('[data-field="ownerTeamId"]')).toHaveTextContent(EN['fwd.err.owner']))
    expect(form().querySelector('[data-field="recipientTeamId"]')).toHaveTextContent(EN['fwd.err.recipientTeam'])
    expect(form().querySelector('[data-field="scopeTypes"]')).toHaveTextContent(EN['fwd.err.typesRequired'])
    expect(api.alertForwards.create).not.toHaveBeenCalled()
  })

  it('seçilen izlemeler: sahip takımın listesi okunur; tür süzgeci + arama; işaretlenenler çip; gövdede {type,id}', async () => {
    renderDialog({ teams: [TEAMS[0]] })   // tek yönetilen takım → otomatik seçili
    fireEvent.click(within(form()).getByRole('radio', { name: new RegExp(EN['fwd.scope.monitors']) }))
    await waitFor(() => expect(api.alertForwards.monitors).toHaveBeenCalledWith(10))
    const list = await waitFor(() => { const l = form().querySelector('[data-slot="fwd-monitor-list"]'); expect(l).not.toBeNull(); return l })
    expect(list.querySelectorAll('[data-slot="fwd-monitor-item"]')).toHaveLength(3)
    fireEvent.change(within(form()).getByRole('combobox', { name: EN['fwd.mon.typeFilter'] }), { target: { value: 'http' } })
    expect(list.querySelectorAll('[data-slot="fwd-monitor-item"]')).toHaveLength(1)
    fireEvent.change(within(form()).getByRole('combobox', { name: EN['fwd.mon.typeFilter'] }), { target: { value: '' } })
    fireEvent.change(within(form()).getByRole('textbox', { name: EN['fwd.mon.search'] }), { target: { value: 'cekirdek' } })
    await waitFor(() => expect(list.querySelectorAll('[data-slot="fwd-monitor-item"]')).toHaveLength(1))
    fireEvent.click(within(list).getByRole('checkbox'))
    fireEvent.change(within(form()).getByRole('textbox', { name: EN['fwd.mon.search'] }), { target: { value: '' } })
    const http = [...list.querySelectorAll('[data-slot="fwd-monitor-item"]')].find((l) => l.dataset.key === 'http:5')
    fireEvent.click(within(http).getByRole('checkbox'))
    await waitFor(() => expect(form().querySelectorAll('[data-slot="fwd-monitor-chip"]')).toHaveLength(2))
    expect(form().querySelector('[data-slot="fwd-monitor-count"]')).toHaveTextContent('2 / 200')
    // Çipten kaldır
    fireEvent.click(within(form().querySelector('[data-slot="fwd-monitor-chip"][data-key="ping:6"]')).getByRole('button'))
    await waitFor(() => expect(form().querySelectorAll('[data-slot="fwd-monitor-chip"]')).toHaveLength(1))
    await chooseRecipientTeam(LONG_TEAM)
    submit()
    await waitFor(() => expect(api.alertForwards.create).toHaveBeenCalledWith(expect.objectContaining({
      scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 5 }], scope_types: [] })))
  })

  it('izleme listesi okunamazsa "okunamadı" + yeniden dene (asla "izleme yok")', async () => {
    api.alertForwards.monitors.mockResolvedValueOnce({ success: false, status: 500, error: 'Liste okunamadı (test).' })
    renderDialog({ teams: [TEAMS[0]] })
    fireEvent.click(within(form()).getByRole('radio', { name: new RegExp(EN['fwd.scope.monitors']) }))
    await screen.findByText('Liste okunamadı (test).')
    expect(screen.queryByText(EN['fwd.mon.none'])).toBeNull()
    fireEvent.click(within(form().querySelector('[data-slot="fwd-monitor-picker"]')).getByRole('button', { name: EN['fwd.retry'] }))
    await waitFor(() => expect(form().querySelectorAll('[data-slot="fwd-monitor-item"]')).toHaveLength(3))
  })

  it('seçilen üyeler: alıcı takımın AKTİF üyeleri (ad + unvan sarar); e-postasız üyeye not; gövdede opak kimlik', async () => {
    renderDialog({ teams: [TEAMS[0]] })
    fireEvent.click(within(form()).getByRole('button', { name: EN['fwd.rcpt.members'] }))
    expect(form().querySelector('[data-slot="fwd-member-need-team"]')).not.toBeNull()
    await chooseRecipientTeam(LONG_TEAM)
    await waitFor(() => expect(api.teams.members).toHaveBeenCalledWith(20))
    const list = await waitFor(() => { const l = form().querySelector('[data-slot="fwd-member-list"]'); expect(l).not.toBeNull(); return l })
    const items = [...list.querySelectorAll('[data-slot="fwd-member-item"]')]
    expect(items.map((i) => i.textContent)).toEqual([`${LONG_PERSON}Bölüm Başkanı · Teknoloji Servis Yönetimi`, `Barış Ünlü${EN['fwd.mem.noEmail']}Uzman`])
    expect(list.innerHTML).not.toContain('truncate')
    fireEvent.click(within(items[0]).getByRole('checkbox'))
    fireEvent.click(within(items[1]).getByRole('checkbox'))
    await waitFor(() => expect(form().querySelectorAll('[data-slot="fwd-member-chip"]')).toHaveLength(2))
    expect(form().querySelector('[data-slot="fwd-member-no-email-note"]')).toHaveTextContent('1 member without an e-mail address')
    expect(form().querySelector('[data-slot="fwd-sum-recipient"]')).toHaveTextContent(`${LONG_TEAM} — 2 members`)
    submit()
    await waitFor(() => expect(api.alertForwards.create).toHaveBeenCalledWith(expect.objectContaining({
      recipient_kind: 'MEMBERS', recipient_team_id: 20, recipient_user_ids: ['op-a', 'op-b'] })))
  })

  it('üye seçilmeden kaydet → alan hatası; adressiz takım uyarısı', async () => {
    renderDialog({ teams: [TEAMS[0]] })
    await chooseRecipientTeam('Ağ Operasyon')
    expect(form().querySelector('[data-slot="fwd-no-team-email"]')).toHaveTextContent(EN['fwd.rcpt.noTeamEmail'])
    fireEvent.click(within(form()).getByRole('button', { name: EN['fwd.rcpt.members'] }))
    expect(form().querySelector('[data-slot="fwd-no-team-email"]')).toBeNull()
    submit()
    await waitFor(() => expect(form().querySelector('[data-field="recipientUserIds"]')).toHaveTextContent(EN['fwd.err.membersRequired']))
    expect(api.alertForwards.create).not.toHaveBeenCalled()
  })

  it('görüntüleme erişimi kutusu (canGrant) → grant_view; canGrant yoksa kutu yok', async () => {
    renderDialog({ teams: [TEAMS[0]] })
    await chooseRecipientTeam(LONG_TEAM)
    const grant = form().querySelector('[data-slot="fwd-grant"]')
    expect(grant).toHaveTextContent(EN['fwd.f.grantHint'])
    fireEvent.click(within(grant).getByRole('checkbox'))
    fireEvent.change(within(form()).getByRole('textbox', { name: new RegExp(EN['fwd.f.noExpiry']) }), { target: { value: '2026-12-31' } })
    submit()
    await waitFor(() => expect(api.alertForwards.create).toHaveBeenCalledWith(expect.objectContaining({ grant_view: true, expires_on: '2026-12-31' })))
  })

  it('canGrant false → erişim kutusu çizilmez; bilgi e-postası satırı görünür', async () => {
    renderDialog({ teams: [TEAMS[0]], canGrant: false })
    await waitFor(() => expect(form()).not.toBeNull())
    expect(form().querySelector('[data-slot="fwd-grant"]')).toBeNull()
    expect(form().querySelector('[data-slot="fwd-info-mail"]')).toHaveTextContent(EN['fwd.f.infoMail'])
  })

  it('sunucu alan hatası (400 + field) alanın altında; 409 FORWARD_EXISTS pencerede', async () => {
    renderDialog({ teams: [TEAMS[0]] })
    await chooseRecipientTeam(LONG_TEAM)
    api.alertForwards.create.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'recipient_team_id',
      error: 'Alıcı takım pasif; aktif bir takım seçin (test).' })
    submit()
    await waitFor(() => expect(form().querySelector('[data-field="recipientTeamId"]')).toHaveTextContent('Alıcı takım pasif'))
    api.alertForwards.create.mockResolvedValueOnce({ success: false, status: 409, code: 'FORWARD_EXISTS', error: 'Aynı iletim zaten var (test).' })
    submit()
    const banner = await waitFor(() => { const b = form().querySelector('[data-slot="fwd-dialog-banner"]'); expect(b).not.toBeNull(); return b })
    expect(banner).toHaveAttribute('data-code', 'FORWARD_EXISTS')
    expect(banner).toHaveTextContent('Aynı iletim zaten var (test).')
  })
})

const ROW = normalizeForward({ id: 7, version: 4, owner_team_id: 10, owner_team_name: OWNER, scope_kind: 'TYPES', scope_types: ['http'],
  scope_monitors: [], recipient_kind: 'MEMBERS', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true,
  recipient_members: [{ user_id: 'op-a', display_name: LONG_PERSON, active: true, in_team: true },
    { user_id: 'op-old', display_name: 'Eski Üye', active: false, in_team: true }],
  recipient_address_count: 1, enabled: true, expires_on: null, state: 'active', note: 'not', can_edit: true })

describe('AlertForwardDialog — düzenle', () => {
  it('değişiklik yoksa istek YOK (pencere kapanır); sahip takım kilitli; pasif kayıtlı üye çipte rozetli', async () => {
    const { onClose } = renderDialog({ forward: ROW })
    await waitFor(() => expect(form().querySelectorAll('[data-slot="fwd-member-chip"]')).toHaveLength(2))
    expect(form().querySelector('[data-slot="fwd-owner-fixed"]')).toHaveTextContent(OWNER)
    expect(within(form()).queryByRole('combobox', { name: new RegExp(`^${EN['fwd.f.owner']}`) })).toBeNull()
    const old = [...form().querySelectorAll('[data-slot="fwd-member-chip"]')].find((c) => c.textContent.includes('Eski Üye'))
    expect(old).toHaveTextContent(EN['fwd.memberPassive'])
    expect(form().querySelector('[data-slot="fwd-member-stale-note"]')).not.toBeNull()
    // erişim kutusu yeni alıcı yokken çizilmez
    expect(form().querySelector('[data-slot="fwd-grant"]')).toBeNull()
    submit()
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(api.alertForwards.update).not.toHaveBeenCalled()
  })

  it('YALNIZ değişen alanlar + sürüm: etkin kapatılır, not değişir', async () => {
    const { onSaved } = renderDialog({ forward: ROW })
    await waitFor(() => expect(form().querySelector('[data-slot="fwd-enabled"]')).not.toBeNull())
    fireEvent.click(within(form().querySelector('[data-slot="fwd-enabled"]')).getByRole('switch'))
    fireEvent.change(within(form()).getByRole('textbox', { name: EN['fwd.f.note'] }), { target: { value: 'yeni not' } })
    submit()
    await waitFor(() => expect(api.alertForwards.update).toHaveBeenCalledWith(7, { enabled: false, note: 'yeni not', version: 4 }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  it('yeni üye eklenince erişim kutusu ("yeni alıcılara") çıkar; gövdede yalnız üyeler + grant_view', async () => {
    renderDialog({ forward: ROW })
    const list = await waitFor(() => { const l = form().querySelector('[data-slot="fwd-member-list"]'); expect(l).not.toBeNull(); return l })
    const barış = [...list.querySelectorAll('[data-slot="fwd-member-item"]')].find((i) => i.textContent.includes('Barış'))
    fireEvent.click(within(barış).getByRole('checkbox'))
    const grant = await waitFor(() => { const g = form().querySelector('[data-slot="fwd-grant"]'); expect(g).not.toBeNull(); return g })
    expect(grant).toHaveTextContent(EN['fwd.f.grantNew'])
    expect(form().querySelector('[data-slot="fwd-info-mail"]')).toHaveTextContent(EN['fwd.f.infoMailNew'])
    fireEvent.click(within(grant).getByRole('checkbox'))
    submit()
    await waitFor(() => expect(api.alertForwards.update).toHaveBeenCalledWith(7, {
      recipient_user_ids: ['op-a', 'op-b', 'op-old'], grant_view: true, version: 4 }))
  })

  it('404 → onGone; 409 CONCURRENT_MODIFICATION → pencerede uyarı + "Kapat ve listeyi yenile"', async () => {
    const { onGone } = renderDialog({ forward: ROW })
    await waitFor(() => expect(form()).not.toBeNull())
    fireEvent.change(within(form()).getByRole('textbox', { name: EN['fwd.f.note'] }), { target: { value: 'x' } })
    api.alertForwards.update.mockResolvedValueOnce({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Başkası değiştirdi (test).' })
    submit()
    const banner = await waitFor(() => { const b = form().querySelector('[data-slot="fwd-dialog-banner"]'); expect(b).not.toBeNull(); return b })
    expect(banner).toHaveTextContent('Başkası değiştirdi (test).')
    fireEvent.click(within(banner).getByRole('button', { name: EN['fwd.reloadClose'] }))
    expect(onGone).toHaveBeenCalledTimes(1)
    api.alertForwards.update.mockResolvedValueOnce({ success: false, status: 404, error: 'Yok (test).' })
    submit()
    await waitFor(() => expect(onGone).toHaveBeenCalledTimes(2))
  })
})
