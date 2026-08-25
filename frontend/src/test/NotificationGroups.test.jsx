import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const state = vi.hoisted(() => ({ groups: [], writable: ['1'], teamEmails: { 1: 'sy-a@akbank.com' } }))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    notificationGroups: {
      list: vi.fn(async () => ({
        success: true,
        data: { groups: state.groups, writable_team_ids: state.writable, team_emails: state.teamEmails },
      })),
      create: vi.fn(async () => ({ success: true, data: {} })),
      update: vi.fn(async () => ({ success: true, data: {} })),
      remove: vi.fn(async () => ({ success: true })),
      makeDefault: vi.fn(async () => ({ success: true, data: {} })),
    },
  }),
}))

// Onay diyalogunu kontrol edebilmek icin: silme kapisinin GERCEK bir kapi oldugunu
// (iptal edilince HICBIR silme yapilmadigini) kanitlamak sart.
const dialog = vi.hoisted(() => ({ confirm: true, spy: null }))
vi.mock('../components/ui/Dialog.jsx', async (importOriginal) => {
  const actual = await importOriginal()
  const { vi: v } = await import('vitest')
  // SABIT casus: "onay soruldu mu" sorusuna cevap verebilmek icin her render'da
  // yeni bir fn uretilmemeli -- iptal yolunu SENKRONIZE bekleyebilmenin tek yolu bu.
  dialog.spy = v.fn(async () => dialog.confirm)
  return { ...actual, useDialog: () => ({ showConfirm: dialog.spy }) }
})

const { api } = await import('../api/client')
const NotificationGroups = (await import('../components/admin/NotificationGroups.jsx')).default

const TEAMS = [{ id: 1, name: 'SY-A' }]

const group = (over = {}) => ({
  id: 10, team_id: 1, name: 'Ödeme Nöbetçi', emails: ['odeme@akbank.com', 'yedek@akbank.com'],
  is_default: false, active: true, can_write: true, ...over,
})

describe('NotificationGroups', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.groups = []
    state.writable = ['1']
    state.teamEmails = { 1: 'sy-a@akbank.com' }
    dialog.confirm = true
  })

  it('DÜRÜST BOŞ DURUM: grup yokken alarmların ŞU AN nereye gittiğini söyler', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    // "Hiçbir şey yok" demek yetmez — kullanıcı bir şeyin bozuk olmadığını görmeli.
    expect(await screen.findByText(/sy-a@akbank\.com/)).toBeTruthy()
  })

  it('Grupları listeler; varsayılan rozetli, adres sayısı görünür', async () => {
    state.groups = [group({ is_default: true })]
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)

    expect(await screen.findByText('Ödeme Nöbetçi')).toBeTruthy()
    expect(screen.getByText(/Default/)).toBeTruthy()
    expect(screen.getByText('2 addresses')).toBeTruthy()
  })

  it('SORUN sütunu: adressiz grup görünür biçimde işaretlenir', async () => {
    state.groups = [group({ emails: [] })]
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    // Sessizce kimseye gitmeyen bir grup ekranda SESSİZ kalmamalı.
    expect(await screen.findByText(/No addresses/)).toBeTruthy()
  })

  it('Boş adres listesiyle kaydetmeye izin verilmez ve API çağrılmaz', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    fireEvent.click(await screen.findByRole('button', { name: /Add group/i }))

    const name = await screen.findByPlaceholderText(/Payments on-call/)
    fireEvent.change(name, { target: { value: 'Yeni' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$/ }))

    expect(await screen.findByText(/Add at least one address/)).toBeTruthy()
    expect(api.notificationGroups.create).not.toHaveBeenCalled()
  })

  it('Geçerli grup kaydedilir — adresler dizi olarak gider', async () => {
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    fireEvent.click(await screen.findByRole('button', { name: /Add group/i }))

    fireEvent.change(await screen.findByPlaceholderText(/Payments on-call/), { target: { value: 'Yeni Nöbet' } })
    const tag = screen.getByPlaceholderText(/Type an address and press Enter/)
    fireEvent.change(tag, { target: { value: 'a@akbank.com' } })
    fireEvent.keyDown(tag, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.notificationGroups.create).toHaveBeenCalled())
    expect(api.notificationGroups.create.mock.calls[0][0]).toEqual({
      team_id: 1, name: 'Yeni Nöbet', emails: ['a@akbank.com'], is_default: false,
    })
  })

  it('SİLME KAPISI: onaylanınca silinir', async () => {
    state.groups = [group()]
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    await screen.findByText('Ödeme Nöbetçi')

    fireEvent.click(screen.getByRole('button', { name: /Actions/i }))
    fireEvent.click(await screen.findByRole('button', { name: /^Delete$/ }))

    await waitFor(() => expect(api.notificationGroups.remove).toHaveBeenCalledWith(10))
  })

  it('SİLME KAPISI: İPTAL edilirse HİÇBİR silme yapılmaz', async () => {
    // Bu testsiz "onay kapısı" iddiası kanıtlanamaz: kapıyı kaldırsanız üstteki test
    // yine yeşil kalırdı. Mutasyon burada kırmızıya döner.
    dialog.confirm = false
    state.groups = [group()]
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    await screen.findByText('Ödeme Nöbetçi')

    fireEvent.click(screen.getByRole('button', { name: /Actions/i }))
    fireEvent.click(await screen.findByRole('button', { name: /^Delete$/ }))

    // ÖNCE onayın sorulduğunu bekle, SONRA işleyicinin kalanını akıt. Doğrudan
    // waitFor(not.toHaveBeenCalled()) yazmak SAHTE YEŞİL verirdi: iddia daha ilk
    // denemede, asenkron işleyici hiç ilerlemeden geçerdi (mutasyon bunu ele verdi).
    await waitFor(() => expect(dialog.spy).toHaveBeenCalled())
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(api.notificationGroups.remove).not.toHaveBeenCalled()
  })

  it('Yazma yetkisi olmayan satırda işlem menüsü ÇİZİLMEZ (403 alıp "bozuk" sanmasın)', async () => {
    state.groups = [group({ can_write: false })]
    state.writable = []
    render(<NotificationGroups teams={TEAMS} systemRole="USER" />)
    await screen.findByText('Ödeme Nöbetçi')

    expect(screen.queryByRole('button', { name: /Actions/i })).toBeNull()
    // Yazamayan kullanıcıya "Grup Ekle" de gösterilmez.
    expect(screen.queryByRole('button', { name: /Add group/i })).toBeNull()
  })
})
