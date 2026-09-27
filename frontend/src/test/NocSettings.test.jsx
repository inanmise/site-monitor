import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const toastMock = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { noc: {
    getConfig: vi.fn(), saveConfig: vi.fn(), listGroups: vi.fn(), createGroup: vi.fn(), updateGroup: vi.fn(),
    deleteGroup: vi.fn(), testGroup: vi.fn(),
  } } }),
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import NocSettings from '../components/admin/NocSettings.jsx'

const ALL_ON = { SSL: true, PING: true, HTTP: true, KEYWORD: true, PAGE: true, PAGESPEED: true, SCRIPTED: true, DNS: true, PORT: true, DOMAIN: true }
const CONFIG = { enabled_types: ALL_ON, min_level: 'CRITICAL', send_resolve: true, call_instructions: '' }
const GROUPS = [
  { id: 2, name: 'Hafta sonu', description: '', emails: ['haftasonu@example.com'], active: true, is_default: false, monitor_count: 0,
    updated_at: '2026-09-20T10:00:00', updated_by_name: 'Kişi B' },
  { id: 1, name: 'NOC Nöbet', description: 'Gece masası', active: true, is_default: true, monitor_count: 12, explicit_monitor_count: 9,
    emails: ['noc@example.com', 'izleme@example.com', 'vardiya@example.com', 'yedek@example.com', 'sef@example.com'],
    updated_at: '2026-09-26T10:00:00', updated_by_name: 'Kişi A' },
  { id: 3, name: 'Eski liste', emails: ['eski@example.com'], active: false, is_default: false, monitor_count: 3 },
]

const cards = () => [...document.querySelectorAll('[data-slot="noc-group"]')]
const typeRow = (k) => document.querySelector(`[data-slot="noc-type-row"][data-type="${k}"]`)

async function renderReady(props = {}) {
  const utils = render(<NocSettings {...props} />)
  await waitFor(() => expect(document.querySelector('[data-slot="noc-type-list"]')).not.toBeNull())
  return utils
}

/** Ayarlar → 7/24 İzleme Ekibi (2026-09-27): gruplar, çip girişi, tür anahtarları, kurallar, kapsamlı müdür. */
describe('NocSettings — gruplar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.noc.getConfig.mockResolvedValue({ success: true, data: CONFIG })
    api.admin.noc.listGroups.mockResolvedValue({ success: true, data: GROUPS })
    api.admin.noc.saveConfig.mockResolvedValue({ success: true, data: CONFIG })
    api.admin.noc.createGroup.mockResolvedValue({ success: true, data: { id: 9 } })
    api.admin.noc.updateGroup.mockResolvedValue({ success: true, data: { id: 1 } })
    api.admin.noc.deleteGroup.mockResolvedValue({ success: true, data: { id: 1, affected_monitors: 9 } })
  })

  it('boş durum: açıklama + "İlk grubu ekle" yeni grup penceresini açar', async () => {
    api.admin.noc.listGroups.mockResolvedValue({ success: true, data: [] })
    await renderReady()
    expect(await screen.findByText(/No 24\/7 groups yet|Henüz 7\/24 grubu yok/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="noc-group-list"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Add the first group|İlk grubu ekle/ }))
    expect(await screen.findByRole('dialog', { name: /New 24\/7 group|Yeni 7\/24 grubu/ })).toBeInTheDocument()
  })

  it('kartlar: varsayılan önce; rozetler, ilk 3 adres + "+2" listesi, izleme sayısı; pasif grupta test kapalı', async () => {
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    const [first, second, third] = cards()
    expect(first).toHaveAttribute('data-default', 'true')
    expect(within(first).getByText('NOC Nöbet')).toBeInTheDocument()
    expect(within(first).getByText(/^(Default|Varsayılan)$/)).toBeInTheDocument()
    expect(within(first).getByText('vardiya@example.com')).toBeInTheDocument()
    expect(within(first).queryByText('sef@example.com')).toBeNull()
    expect(within(first).getByText(/Monitors using this group: 12|Bu grubu kullanan izleme: 12/)).toBeInTheDocument()
    expect(within(second).getByText(/^1 (address|adres)$/)).toBeInTheDocument()
    fireEvent.click(within(first).getByRole('button', { name: /2 (more addresses|adres daha) — NOC Nöbet/ }))
    expect(await screen.findByText('sef@example.com')).toBeInTheDocument()
    expect(third).toHaveAttribute('data-active', 'false')
    expect(within(third).getByRole('button', { name: /(Test e-mail|Test e-postası) — Eski liste/ })).toBeDisabled()
  })

  it('ekle: yapıştırılan liste doğrulanır + tekilleşir, geçersiz adres başına hata; düzeltilince createGroup', async () => {
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /^(Add group|Grup ekle)$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByRole('textbox', { name: /Group name|Grup adı/ }), { target: { value: 'NOC Gece' } })
    const input = within(dlg).getByRole('textbox', { name: /E-mail addresses|E-posta adresleri/ })
    fireEvent.paste(input, { clipboardData: { getData: () => 'NOC@example.com; Kişi A <izleme@example.com>, noc@example.com\nbozuk@' } })
    const chips = () => [...dlg.querySelectorAll('[data-slot="email-chip"]:not([data-invalid])')].map((c) => c.textContent.trim())
    expect(chips()).toEqual(['noc@example.com', 'izleme@example.com'])
    expect(dlg.querySelectorAll('[data-slot="email-chip"][data-invalid]')).toHaveLength(1)
    expect(within(dlg.querySelector('[data-slot="email-errors"]')).getByText(/"bozuk@"/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="email-notice"]').textContent).toMatch(/(Duplicates skipped|yinelenen adres): 1/)

    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    expect(await within(dlg).findByText(/(Correct or remove the invalid addresses|Geçersiz adresleri düzeltin ya da kaldırın) \(1\)/)).toBeInTheDocument()
    expect(api.admin.noc.createGroup).not.toHaveBeenCalled()

    fireEvent.click(within(dlg).getByRole('button', { name: /^(Remove|Kaldır:) ?bozuk@$/ }))
    fireEvent.click(within(dlg).getByRole('switch', { name: /Default group|Varsayılan grup/ }))
    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    await waitFor(() => expect(api.admin.noc.createGroup).toHaveBeenCalledWith({
      name: 'NOC Gece', description: '', emails: ['noc@example.com', 'izleme@example.com'], active: true, isDefault: true,
    }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(toastMock.success).toHaveBeenCalledWith(expect.stringMatching(/NOC Gece/))
    expect(api.admin.noc.listGroups).toHaveBeenCalledTimes(2)
  })

  it('çip girişi: Enter/virgül çip yapar, geçersiz çipe basınca kutuya geri alınır; ad ve adres yoksa kaydetmez', async () => {
    await renderReady()
    fireEvent.click(await screen.findByRole('button', { name: /^(Add group|Grup ekle)$/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    expect(await within(dlg).findByText(/Enter a group name|Grup adı zorunlu/)).toBeInTheDocument()
    expect(within(dlg).getByText(/Add at least one e-mail address|En az bir e-posta adresi ekleyin/)).toBeInTheDocument()
    expect(api.admin.noc.createGroup).not.toHaveBeenCalled()

    const input = within(dlg).getByRole('textbox', { name: /E-mail addresses|E-posta adresleri/ })
    fireEvent.change(input, { target: { value: 'a@example.com' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.change(input, { target: { value: 'yanlis@adres,' } })
    expect(dlg.querySelectorAll('[data-slot="email-chip"]:not([data-invalid])')).toHaveLength(1)
    fireEvent.click(within(dlg).getByRole('button', { name: /(Correct|Düzelt:) ?yanlis@adres/ }))
    expect(input).toHaveValue('yanlis@adres')
    expect(dlg.querySelector('[data-slot="email-errors"]')).toBeNull()
  })

  it('ad tekilliği (harf duyarsız): başka grubun adı hata verir, grubun KENDİ adı vermez', async () => {
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /^(Add group|Grup ekle)$/ }))
    let dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByRole('textbox', { name: /Group name|Grup adı/ }), { target: { value: 'hafta SONU' } })
    fireEvent.change(within(dlg).getByRole('textbox', { name: /E-mail addresses|E-posta adresleri/ }), { target: { value: 'a@example.com,' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    expect(await within(dlg).findByText(/already a 24\/7 group with this name|Bu adla bir 7\/24 grubu zaten var/)).toBeInTheDocument()
    expect(api.admin.noc.createGroup).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Cancel|İptal|Vazgeç)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Düzenlemede kendi adını korumak serbest
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle) — Hafta sonu$/ }))
    dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    await waitFor(() => expect(api.admin.noc.updateGroup).toHaveBeenCalledWith(2, expect.objectContaining({ name: 'Hafta sonu' })))
  })

  it('test e-postası: genel e-posta kapalıysa (hepsi SKIPPED_DISABLED) tek anlaşılır hata', async () => {
    api.admin.noc.testGroup.mockResolvedValueOnce({ success: true, data: { sent: 0, failed: [
      { email: 'haftasonu@example.com', error: 'SKIPPED_DISABLED' }] } })
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /^(Test e-mail|Test e-postası) — Hafta sonu$/ }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/SMTP/)))
    expect(toastMock.error.mock.calls[0][0]).not.toMatch(/haftasonu@example\.com/)
  })

  it('düzenle → updateGroup(id, gövde); sunucu hatası pencereyi KAPATMAZ', async () => {
    api.admin.noc.updateGroup.mockResolvedValueOnce({ success: false, error: 'Aynı adlı grup var' })
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle) — NOC Nöbet$/ }))
    const dlg = await screen.findByRole('dialog', { name: /(Edit group|Grubu düzenle) — NOC Nöbet/ })
    expect(dlg.querySelectorAll('[data-slot="email-chip"]')).toHaveLength(5)
    fireEvent.change(within(dlg).getByRole('textbox', { name: /Group name|Grup adı/ }), { target: { value: 'NOC Ana' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /Save group|Grubu kaydet/ }))
    await waitFor(() => expect(api.admin.noc.updateGroup).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'NOC Ana', isDefault: true, active: true })))
    expect(toastMock.error).toHaveBeenCalledWith('Aynı adlı grup var')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('sil: onay grubu AÇIKÇA seçen izleme sayısını (varsayılana düşecekler) söyler; deleteGroup + toast + liste tazelenir', async () => {
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /^(Delete|Sil) — NOC Nöbet$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    // monitor_count 12 (varsayılan üzerinden kullananlar dâhil) değil, explicit_monitor_count 9
    expect(confirmMock.mock.calls[0][0]).toMatchObject({ variant: 'danger', message: expect.stringMatching(/NOC Nöbet[\s\S]*\(9\)/) })
    await waitFor(() => expect(api.admin.noc.deleteGroup).toHaveBeenCalledWith(1))
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(expect.stringMatching(/\(9\)/)))
    expect(api.admin.noc.listGroups).toHaveBeenCalledTimes(2)
  })

  it('test e-postası: tümü giderse başarı; başarısız adresler hata toast\'ında listelenir', async () => {
    api.admin.noc.testGroup
      .mockResolvedValueOnce({ success: true, data: { sent: 5, failed: [] } })
      // Sunucunun gerçek biçimi: failed = [{ email, error }]
      .mockResolvedValueOnce({ success: true, data: { sent: 3, failed: [
        { email: 'yedek@example.com', error: '550 mailbox unavailable' }, { email: 'sef@example.com', error: 'timeout' }] } })
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(3))
    const btn = () => screen.getByRole('button', { name: /^(Test e-mail|Test e-postası) — NOC Nöbet$/ })
    fireEvent.click(btn())
    await waitFor(() => expect(toastMock.success).toHaveBeenCalledWith(expect.stringMatching(/NOC Nöbet.*5/)))
    await waitFor(() => expect(btn()).not.toBeDisabled())
    fireEvent.click(btn())
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/: 3; .*: 2 \(yedek@example\.com, sef@example\.com\)/)))
  })
})

describe('NocSettings — tür anahtarları ve kurallar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.noc.getConfig.mockResolvedValue({ success: true, data: CONFIG })
    api.admin.noc.listGroups.mockResolvedValue({ success: true, data: GROUPS })
    api.admin.noc.saveConfig.mockImplementation((body) => Promise.resolve({ success: true, data: {
      enabled_types: body.enabledTypes, min_level: body.minLevel, send_resolve: body.sendResolve, call_instructions: body.callInstructions,
    } }))
  })

  it('10 tür, tür ikonlu anahtar; kapatınca uyarı + kaydet ONAY ister; saveConfig camelCase gövde', async () => {
    await renderReady()
    expect(document.querySelectorAll('[data-slot="noc-type-row"]')).toHaveLength(10)
    expect(typeRow('PING').querySelector('svg')).not.toBeNull()
    const bar = () => document.querySelector('[data-slot="settings-save-bar"]')
    expect(bar()).not.toHaveAttribute('data-dirty')
    fireEvent.click(within(typeRow('PING')).getByRole('switch'))
    expect(typeRow('PING')).toHaveAttribute('data-on', 'false')
    expect(screen.getByText(/Switched-off types don’t reach the 24\/7 team|Kapalı türler 7\/24 ekibine gitmez/)).toBeInTheDocument()
    expect(bar()).toHaveAttribute('data-dirty', 'true')

    fireEvent.change(screen.getByRole('combobox', { name: /Minimum level|En düşük seviye/ }), { target: { value: 'HIGH' } })
    fireEvent.change(screen.getByRole('textbox', { name: /Call instructions|Arama talimatı/ }), { target: { value: 'Önce Kişi A' } })
    // Tavan sunucuyla aynı (NocConfigService.MAX_INSTRUCTIONS = 2000; nocModel `noc-limits-sync`)
    expect(document.querySelector('[data-slot="noc-instr-count"]').textContent).toMatch(/^11 \/ 2000/)
    expect(screen.getByRole('textbox', { name: /Call instructions|Arama talimatı/ })).toHaveAttribute('maxLength', '2000')
    fireEvent.click(screen.getByRole('button', { name: /Save settings|Ayarları kaydet/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(confirmMock.mock.calls[0][0]).toMatchObject({ variant: 'warning', message: expect.stringMatching(/Ping/) })
    await waitFor(() => expect(api.admin.noc.saveConfig).toHaveBeenCalledWith({
      enabledTypes: { ...ALL_ON, PING: false }, minLevel: 'HIGH', sendResolve: true, callInstructions: 'Önce Kişi A',
    }))
    await waitFor(() => expect(bar()).not.toHaveAttribute('data-dirty'))
    expect(toastMock.success).toHaveBeenCalled()
  })

  it('onay reddedilirse kaydetmez; Vazgeç kaydedilmiş hâle döner; türü AÇMAK onay istemez', async () => {
    confirmMock.mockResolvedValueOnce(false)
    await renderReady()
    fireEvent.click(within(typeRow('DNS')).getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: /Save settings|Ayarları kaydet/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(1))
    expect(api.admin.noc.saveConfig).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Discard|Vazgeç/ }))
    expect(typeRow('DNS')).toHaveAttribute('data-on', 'true')
  })

  it('kapalı türü açmak doğrudan kaydeder (onaysız); "Tümünü aç/kapat"', async () => {
    api.admin.noc.getConfig.mockResolvedValue({ success: true, data: { ...CONFIG, enabled_types: { ...ALL_ON, SSL: false } } })
    await renderReady()
    expect(typeRow('SSL')).toHaveAttribute('data-on', 'false')
    fireEvent.click(screen.getByRole('button', { name: /Switch all on|Tümünü aç/ }))
    expect(typeRow('SSL')).toHaveAttribute('data-on', 'true')
    fireEvent.click(screen.getByRole('button', { name: /Save settings|Ayarları kaydet/ }))
    await waitFor(() => expect(api.admin.noc.saveConfig).toHaveBeenCalledWith(expect.objectContaining({ enabledTypes: ALL_ON })))
    expect(confirmMock).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Switch all off|Tümünü kapat/ }))
    expect(document.querySelectorAll('[data-slot="noc-type-row"][data-on="false"]')).toHaveLength(10)
  })

  it('her ayarın yardım balonu var (HelpTip) — gruplar, türler, seviye, çözüldü e-postası, arama talimatı', async () => {
    await renderReady()
    const helps = screen.getAllByRole('button', { name: /^(Help|Açıklama): / }).map((b) => b.getAttribute('aria-label'))
    for (const re of [/24\/7 groups|7\/24 grupları/, /Notifications by type|Tür bazlı bildirim/, /Minimum level|En düşük seviye/,
      /Also e-mail when resolved|Çözülünce de e-posta gönder/, /Call instructions|Arama talimatı/]) {
      expect(helps.some((h) => re.test(h)), String(re)).toBe(true)
    }
  })
})

describe('NocSettings — kapsamlı müdür (salt okunur) ve hatalar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.noc.getConfig.mockResolvedValue({ success: true, data: CONFIG })
    api.admin.noc.listGroups.mockResolvedValue({ success: true, data: GROUPS })
  })

  it('readOnly: adresler HİÇ çizilmez, ekle/düzenle/sil/test ve kaydet çubuğu yok, anahtarlar kilitli', async () => {
    await renderReady({ readOnly: true })
    await waitFor(() => expect(cards()).toHaveLength(3))
    expect(screen.getByText(/Only a global administrator can change this section|yalnız global yönetici değiştirebilir/)).toBeInTheDocument()
    expect(screen.queryByText('noc@example.com')).toBeNull()
    expect(screen.getAllByText(/Only a global administrator can see the addresses|Adresleri yalnız global yönetici görür/)).toHaveLength(3)
    expect(screen.queryByRole('button', { name: /^(Add group|Grup ekle)$/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /(Edit|Düzenle) — / })).toBeNull()
    expect(document.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
    for (const sw of within(document.querySelector('[data-slot="noc-type-list"]')).getAllByRole('switch')) expect(sw).toBeDisabled()
    expect(screen.getByRole('textbox', { name: /Call instructions|Arama talimatı/ })).toBeDisabled()
  })

  it('sunucu adresleri gizlerse (emails:[] + emails_hidden) readOnly olmasa da gizli notu + email_count sayısı', async () => {
    api.admin.noc.listGroups.mockResolvedValue({ success: true, data: [
      { id: 7, name: 'Maskeli', emails: [], email_count: 5, emails_hidden: true, active: true, is_default: true, monitor_count: 2 }] })
    await renderReady()
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(within(cards()[0]).getByText(/^5 (addresses|adres)$/)).toBeInTheDocument()
    expect(within(cards()[0]).getByText(/Only a global administrator can see the addresses|Adresleri yalnız global yönetici görür/)).toBeInTheDocument()
  })

  it('403/hata: grup listesi hatası kendi bandında + yeniden dene; ayarlar yine çizilir', async () => {
    api.admin.noc.listGroups.mockResolvedValueOnce({ success: false, error: 'Yetkisiz' })
    await renderReady({ readOnly: true })
    expect(await screen.findByText('Yetkisiz')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Try again|Yeniden dene/ }))
    await waitFor(() => expect(cards()).toHaveLength(3))
    expect(screen.queryByText('Yetkisiz')).toBeNull()
  })
})
