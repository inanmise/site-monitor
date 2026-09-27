import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * 7/24 ARAMA KAYDI (2026-09-27) — uyarı detayı bölümü: zaman çizelgesi (en yeni önce, sonuç rozeti, göreli zaman +
 * kesin saat, kanal, kaydeden, not), boş durum, izin kapısı (form yalnız yazabilene), hızlı giriş (kişi önseçili,
 * sonuç tek dokunuş, zaman çipleri, Ctrl+Enter, sıfırla ama kişiyi koru, odak sonuç çiplerine), kişi seçicisi
 * grupları + "Başka biri…", doğrulama, 15 dk silme penceresi, liste göstergesi ve yükleme yarışı.
 *
 * Zamanlar sabit takvim tarihi DEĞİL, gerçek saatten türetilir (kayan pencere + sabit fixture = zaman bombası).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    nocCalls: { list: vi.fn(), create: vi.fn(), remove: vi.fn(), contacts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { NocCallSection, NocCallIndicator, NocCallSummary } from '../components/admin/alerts/NocCallLog.jsx'

let PERMS = {}
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canEdit: (r) => Boolean(PERMS[r]?.edit), canView: () => false, canExecute: () => false, perms: PERMS, refresh: () => {} }),
}))
import { toIso, validateCall, buildBody, relTime, canDeleteNow, groupContacts, defaultPerson } from '../components/admin/alerts/nocCallModel.js'

const MIN = 60_000
const ago = (m) => toIso(Date.now() - m * MIN)
const ALERT = { id: 50, domain: 'a.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', created_at: ago(120) }

const CONTACTS = [
  { user_id: 11, display_name: 'Kişi A', title: 'Uzman', has_phone: true, source: 'CALL_LIST', is_manager: false },
  { user_id: 12, display_name: 'Kişi B', title: 'Uzman', has_phone: false, source: 'CALL_LIST', is_manager: false },
  { user_id: 20, display_name: 'Kişi M', title: 'Müdür', has_phone: true, source: 'MANAGER', is_manager: true },
  { user_id: 30, display_name: 'Kişi D', title: 'Mühendis', has_phone: true, source: 'MEMBER', is_manager: false },
]

const call = (id, over = {}) => ({
  id, contacted_user_id: 11, contacted_name: 'Kişi A', contacted_at: ago(12), channel: 'PHONE', outcome: 'REACHED',
  note: null, created_by_name: 'Kişi N', created_at: ago(12), can_delete: false, delete_until: null, ...over,
})

/** Yalnız SONUÇ çipleri (zaman çipleri de radio). */
const radios = () => within(screen.getByRole('radiogroup', { name: 'Outcome' })).getAllByRole('radio')
const outcomeRadio = (name) => screen.getByRole('radio', { name })
const saveBtn = () => screen.getByRole('button', { name: /Save call/ })
const personTrigger = () => screen.getByRole('combobox', { name: /Who did you call/ })

beforeEach(() => {
  vi.clearAllMocks()
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.contacts.mockResolvedValue({ success: true, data: CONTACTS })
  api.nocCalls.create.mockImplementation(async (_id, body) => ({
    success: true,
    data: call(900, { contacted_user_id: body.contactedUserId ?? null, contacted_name: body.contactedName ?? 'Kişi A',
      contacted_at: body.contactedAt ?? toIso(Date.now()), outcome: body.outcome, channel: body.channel, note: body.note ?? null,
      can_delete: true, delete_until: toIso(Date.now() + 15 * MIN), created_at: toIso(Date.now()) }),
  }))
  api.nocCalls.remove.mockResolvedValue({ success: true, data: { deleted: 1 } })
})

describe('zaman çizelgesi + boş durum + izin kapısı', () => {
  it('kayıtlar en yeni önce: sonuç rozeti, göreli zaman + saat, kanal, kaydeden, not; yazamayan formu GÖRMEZ', async () => {
    api.nocCalls.list.mockResolvedValue({ success: true, data: [
      call(1, { contacted_at: ago(40), outcome: 'NO_ANSWER', contacted_name: 'Kişi B' }),
      call(2, { contacted_at: ago(12), outcome: 'REACHED', note: 'Kişi A bakıyor', channel: 'TEAMS' }),
      call(3, { contacted_at: ago(25), outcome: 'VOICEMAIL', contacted_name: 'Kişi C' }),
    ] })
    render(<NocCallSection alert={ALERT} canWrite={false} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(3))
    const entries = [...document.querySelectorAll('[data-slot="noc-call-entry"]')]
    expect(entries.map((e) => e.getAttribute('data-call-id'))).toEqual(['2', '3', '1'])
    const first = entries[0]
    expect(within(first).getByText('Reached')).toBeInTheDocument()
    expect(first.textContent).toMatch(/12 min ago/)
    expect(first.textContent).toMatch(/\d{2}:\d{2}/)
    expect(within(first).getByText('Teams')).toBeInTheDocument()
    expect(within(first).getByText('logged by Kişi N')).toBeInTheDocument()
    expect(within(first).getByText('Kişi A bakıyor')).toBeInTheDocument()
    expect(within(entries[1]).getByText('Left a voicemail')).toBeInTheDocument()
    expect(within(entries[2]).getByText('No answer')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Quick call entry' })).toBeNull()
    expect(api.nocCalls.contacts).not.toHaveBeenCalled()
    // telefon numarası yok — yalnız ad
    expect(document.body.textContent).not.toMatch(/0555/)
  })

  it('boş durum: okuyana açıklama, yazana "yukarıdan kaydedin"', async () => {
    const { unmount } = render(<NocCallSection alert={ALERT} canWrite={false} />)
    expect(await screen.findByText('No calls have been logged for this alert yet.')).toBeInTheDocument()
    expect(screen.getByText(/When the 24\/7 monitoring team rings someone in your team/)).toBeInTheDocument()
    unmount()
    render(<NocCallSection alert={ALERT} canWrite />)
    expect(await screen.findByText(/When you ring the on-call person, log it above/)).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Quick call entry' })).toBeInTheDocument()
  })
})

describe('hızlı giriş', () => {
  it('mutlu yol: arama listesinin ilki önseçili, tek dokunuşla sonuç, kaydet → gövde, bildirim, kayıt listede; form sıfırlanır, kişi KALIR, odak sonuç çiplerinde', async () => {
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    fireEvent.click(outcomeRadio('Reached'))
    expect(outcomeRadio('Reached')).toHaveAttribute('aria-checked', 'true')
    const NOTE = 'Kişi A bakıyor, 15 dk'
    fireEvent.change(screen.getByRole('textbox', { name: 'Note' }), { target: { value: NOTE } })
    expect(document.querySelector('[data-slot="noc-note-count"]').textContent).toBe(`${NOTE.length}/1000`)
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalledTimes(1))
    const [alertId, body] = api.nocCalls.create.mock.calls[0]
    expect(alertId).toBe(50)
    expect(body).toMatchObject({ contactedUserId: 11, outcome: 'REACHED', channel: 'PHONE', note: 'Kişi A bakıyor, 15 dk' })
    expect(body.contactedName).toBeUndefined()
    // "Just now": istemci saati GÖNDERİLMEZ — sunucu kendi saatini yazar (kaymış saat 400 üretmesin)
    expect(body).not.toHaveProperty('contactedAt')
    expect(await screen.findByText('Call logged')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(1))
    // sıfırlama: sonuç yok, not boş, kişi korunur, odak ilk sonuç çipinde
    expect(radios().every((r) => r.getAttribute('aria-checked') === 'false')).toBe(true)
    expect(screen.getByRole('textbox', { name: 'Note' })).toHaveValue('')
    expect(personTrigger()).toHaveTextContent('Kişi A')
    await waitFor(() => expect(document.activeElement).toBe(outcomeRadio('Reached')))
  })

  it('doğrulama: sonuç seçilmeden kaydedilmez (hata + istek yok); telefon numarası ad olarak girilemez', async () => {
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    fireEvent.click(saveBtn())
    expect(await screen.findByText('Choose an outcome')).toBeInTheDocument()
    expect(api.nocCalls.create).not.toHaveBeenCalled()

    fireEvent.mouseDown(personTrigger())
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Someone else/, hidden: true }))
    const name = await screen.findByRole('textbox', { name: 'Name of the person you called' })
    fireEvent.change(name, { target: { value: '0555 000 00 00' } })
    fireEvent.click(outcomeRadio('Line busy'))
    fireEvent.click(saveBtn())
    expect(await screen.findByText(/Don't put a phone number here/)).toBeInTheDocument()
    expect(api.nocCalls.create).not.toHaveBeenCalled()
  })

  it('kişi seçicisi: Arama listesi (numaralı) → Takım Müdürü → Diğer üyeler; AD telefonu yoksa uyarı; "Başka biri…" serbest ad', async () => {
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    fireEvent.mouseDown(personTrigger())
    const list = await screen.findByRole('listbox', { hidden: true })
    const headings = [...list.querySelectorAll('[cmdk-group-heading]')].map((h) => h.textContent)
    expect(headings).toEqual(['Call list', 'Team manager', 'Other members'])
    const options = await waitFor(() => {
      // hidden: Radix `hideWhenDetached` jsdom'da (sıfır boyutlu tetik) içeriğe visibility:hidden yazar
      const o = within(list).getAllByRole('option', { hidden: true })
      expect(o).toHaveLength(5)
      return o
    })
    expect(options.map((o) => o.getAttribute('data-source'))).toEqual(['CALL_LIST', 'CALL_LIST', 'MANAGER', 'MEMBER', null])
    expect(within(options[1]).getByText('No phone in AD')).toBeInTheDocument()
    expect(within(options[0]).queryByText('No phone in AD')).toBeNull()
    expect(options[0].textContent).toMatch(/^1/)
    expect(options[1].textContent).toMatch(/^2/)

    fireEvent.mouseDown(options[4])   // Başka biri…
    const name = await screen.findByRole('textbox', { name: 'Name of the person you called' })
    fireEvent.change(name, { target: { value: 'Kişi E (vardiya amiri)' } })
    fireEvent.click(outcomeRadio('Passed on to someone else'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalled())
    const body = api.nocCalls.create.mock.calls[0][1]
    expect(body.contactedName).toBe('Kişi E (vardiya amiri)')
    expect(body.contactedUserId).toBeUndefined()
    expect(body.outcome).toBe('ESCALATED')
  })

  it('kişi seçicisi boşken açıklama GÖRÜNÜR (cmdk "Başka biri…" öğesini saydığı için CommandEmpty hiç çizilmiyordu)', async () => {
    api.nocCalls.contacts.mockResolvedValue({ success: true, data: [] })
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(api.nocCalls.contacts).toHaveBeenCalled())
    await waitFor(() => expect(personTrigger()).toHaveTextContent(/Choose|Seç/))
    fireEvent.mouseDown(personTrigger())
    const list = await screen.findByRole('listbox', { hidden: true })
    const empty = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-person-empty"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(empty).toHaveAttribute('role', 'status')
    expect(empty).toHaveTextContent(/No one to call was found in this alert’s team|No one to call was found in this alert's team/)
    // Kaçış yolu yine orada
    expect(within(list).getAllByRole('option', { hidden: true }).map((o) => o.textContent)).toEqual([expect.stringMatching(/Someone else/)])
  })

  it('zaman çipleri: "15 min ago" 15 dk önceyi, "Another time" tarih-saat seçicisini açar; kanal değişir', async () => {
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    expect(screen.getByRole('radio', { name: 'Just now' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('radio', { name: '15 min ago' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Channel' }), { target: { value: 'SMS' } })
    fireEvent.click(outcomeRadio('No answer'))
    const before = Date.now()
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalled())
    const body = api.nocCalls.create.mock.calls[0][1]
    const at = Date.parse(body.contactedAt + 'Z')
    expect(Math.abs(before - 15 * MIN - at)).toBeLessThan(5000)
    expect(body.channel).toBe('SMS')
    expect(body.outcome).toBe('NO_ANSWER')
    // Başka zaman → tarih-saat seçicisi (ui/DateTimeField) görünür, "Şimdi"yle dolu
    const form = document.querySelector('[data-slot="noc-call-form"]')
    expect(form.querySelector('[data-slot="date-picker"]')).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'Another time' }))
    await waitFor(() => expect(form.querySelector('[data-slot="date-picker"]')).not.toBeNull())
    expect(screen.getByRole('radio', { name: 'Another time' })).toHaveAttribute('aria-checked', 'true')
  })

  it('Ctrl+Enter kaydeder (not alanından), ⌘+Enter de', async () => {
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    fireEvent.click(outcomeRadio('Reached'))
    const note = screen.getByRole('textbox', { name: 'Note' })
    fireEvent.change(note, { target: { value: 'hızlı' } })
    fireEvent.keyDown(note, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalledTimes(1))
    await screen.findByText('Call logged')
    fireEvent.click(outcomeRadio('Line busy'))
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Note' }), { key: 'Enter', metaKey: true })
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalledTimes(2))
    // düz Enter kaydetmez (not çok satırlı)
    fireEvent.click(outcomeRadio('Reached'))
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Note' }), { key: 'Enter' })
    await act(async () => {})
    expect(api.nocCalls.create).toHaveBeenCalledTimes(2)
  })
})

describe('silme penceresi', () => {
  it('düğme yalnız pencere açıkken; onaylı silme listeden kaldırır', async () => {
    api.nocCalls.list.mockResolvedValue({ success: true, data: [
      call(1, { contacted_at: ago(2), can_delete: true, delete_until: toIso(Date.now() + 13 * MIN) }),
      call(2, { contacted_at: ago(20), can_delete: true, delete_until: toIso(Date.now() - 5 * MIN) }),   // süre doldu
      call(3, { contacted_at: ago(30), can_delete: false }),
    ] })
    render(<NocCallSection alert={ALERT} canWrite />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(3))
    const del = screen.getAllByRole('button', { name: /Delete the call to/ })
    expect(del).toHaveLength(1)
    expect(del[0].closest('[data-call-id]').getAttribute('data-call-id')).toBe('1')
    fireEvent.click(del[0])
    const dlg = await screen.findByRole('dialog', { name: 'Delete this call entry?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(api.nocCalls.remove).toHaveBeenCalledWith(50, 1))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(2))
    expect(await screen.findByText('Call entry deleted')).toBeInTheDocument()
  })

  it('model: canDeleteNow sunucu bayrağı + süre; yöneticide süresiz', () => {
    const now = Date.now()
    expect(canDeleteNow({ can_delete: true, delete_until: toIso(now + MIN) }, now)).toBe(true)
    expect(canDeleteNow({ can_delete: true, delete_until: toIso(now - MIN) }, now)).toBe(false)
    expect(canDeleteNow({ can_delete: true, delete_until: null }, now)).toBe(true)
    expect(canDeleteNow({ can_delete: false }, now)).toBe(false)
  })
})

describe('liste göstergesi + model', () => {
  it('"2 calls · last: Reached · HH:MM · Kişi A" — kayıt yoksa çizilmez; tekilde "1 call"', () => {
    const { container, rerender } = render(<NocCallIndicator count={2} last={{ contacted_name: 'Kişi A', outcome: 'REACHED', contacted_at: ago(5) }} />)
    const b = container.querySelector('[data-slot="noc-call-indicator"]')
    expect(b).toHaveAttribute('data-slot', 'noc-call-indicator')
    expect(b.textContent).toMatch(/2 calls/)
    expect(b.textContent).toMatch(/last:/)
    expect(b.textContent).toMatch(/Reached · \d{2}:\d{2} · Kişi A/)
    rerender(<NocCallIndicator count={1} last={null} />)
    expect(container.textContent).toBe('1 call')
    rerender(<NocCallIndicator count={0} last={null} />)
    expect(container.querySelector('[data-slot="noc-call-indicator"]')).toBeNull()
  })

  it('model: gruplar, önseçim, doğrulama sınırları, gövde', () => {
    const g = groupContacts(CONTACTS)
    expect([g.callList.length, g.manager.length, g.members.length]).toEqual([2, 1, 1])
    expect(defaultPerson(CONTACTS)).toEqual({ kind: 'user', userId: 11, name: 'Kişi A' })
    expect(defaultPerson(CONTACTS.filter((c) => c.source !== 'CALL_LIST'))).toMatchObject({ userId: 20 })
    expect(defaultPerson([])).toBeNull()
    const now = Date.now()
    const ok = { person: { kind: 'user', userId: 11 }, outcome: 'REACHED', time: { mode: 'now' }, note: '' }
    expect(validateCall(ok, { alertCreatedAt: ALERT.created_at, nowMs: now })).toEqual({})
    expect(validateCall({ ...ok, outcome: '' }, { nowMs: now })).toHaveProperty('outcome')
    expect(validateCall({ ...ok, person: null }, { nowMs: now })).toHaveProperty('person')
    expect(validateCall({ ...ok, note: 'x'.repeat(1001) }, { nowMs: now })).toHaveProperty('note')
    expect(validateCall({ ...ok, time: { mode: 'custom', custom: toIso(now + 10 * MIN) } }, { nowMs: now }).time).toBe('nocCall.err.future')
    expect(validateCall({ ...ok, time: { mode: 'custom', custom: toIso(now - 200 * MIN) } }, { alertCreatedAt: ALERT.created_at, nowMs: now }).time)
      .toBe('nocCall.err.beforeOpen')
    expect(validateCall({ ...ok, time: { mode: 'custom', custom: toIso(now - 125 * MIN) } }, { alertCreatedAt: ALERT.created_at, nowMs: now }))
      .toEqual({})   // açılıştan 5 dk önce: pay içinde
    expect(buildBody({ ...ok, time: { mode: 'm5' }, note: '  n  ' }, now)).toEqual({
      outcome: 'REACHED', channel: 'PHONE', contactedAt: toIso(now - 5 * MIN), contactedUserId: 11, note: 'n' })
    // "Şimdi": gövdede contactedAt YOK (sunucu saati); istemci saati geri kalmışsa (uyarı "gelecekte" açılmış görünür)
    // açılış kontrolü "şimdi"yi ENGELLEMEZ — ama elle seçilen bir an için yine uygulanır.
    expect(buildBody(ok, now)).toEqual({ outcome: 'REACHED', channel: 'PHONE', contactedUserId: 11 })
    expect(buildBody({ ...ok, time: undefined }, now)).not.toHaveProperty('contactedAt')
    const skewed = { alertCreatedAt: toIso(now + 30 * MIN), nowMs: now }
    expect(validateCall(ok, skewed)).toEqual({})
    expect(validateCall({ ...ok, time: { mode: 'm5' } }, skewed).time).toBe('nocCall.err.beforeOpen')
    const t = (k, n) => `${k}:${n ?? ''}`
    expect(relTime(toIso(now - 20_000), now, t)).toBe('nocCall.rel.now:')
    expect(relTime(toIso(now - 3 * 3600_000), now, t)).toBe('nocCall.rel.hour:3')
    expect(relTime(toIso(now - 26 * 3600_000), now, t)).toBe('nocCall.rel.dayOne:')
  })
})

describe('yükleme yarışı', () => {
  it('uyarı değişince eski uyarının GEÇ gelen yanıtı yeni listeyi ezmez', async () => {
    let resolveA
    api.nocCalls.list.mockImplementation((id) => (id === 50
      ? new Promise((r) => { resolveA = r })
      : Promise.resolve({ success: true, data: [call(7, { contacted_name: 'Kişi B' })] })))
    const { rerender } = render(<NocCallSection alert={ALERT} canWrite={false} />)
    rerender(<NocCallSection alert={{ ...ALERT, id: 51 }} canWrite={false} />)
    await waitFor(() => expect(screen.getByText('Kişi B')).toBeInTheDocument())
    await act(async () => { resolveA({ success: true, data: [call(1, { contacted_name: 'Eski Kişi' })] }) })
    expect(screen.queryByText('Eski Kişi')).toBeNull()
    expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(1)
  })

  it('ilk liste GELMEDEN kaydedilirse (derin bağlantı): geç gelen liste yeni kayıtla KİMLİKLE birleşir; gösterge birleşik sayıyı alır', async () => {
    let resolveList
    api.nocCalls.list.mockImplementation(() => new Promise((r) => { resolveList = r }))
    const onChanged = vi.fn()
    render(<NocCallSection alert={ALERT} canWrite onChanged={onChanged} />)
    await waitFor(() => expect(personTrigger()).toHaveTextContent('Kişi A'))
    fireEvent.click(outcomeRadio('Reached'))
    fireEvent.click(saveBtn())
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(1))
    // Sunucu listesi yeni kaydı (900) içermiyor olabilir ya da içerebilir — ikisinde de tek satır
    await act(async () => { resolveList({ success: true, data: [call(1, { contacted_at: ago(30) }), call(2, { contacted_at: ago(40), contacted_name: 'Kişi B' })] }) })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(3))
    await waitFor(() => expect(onChanged).toHaveBeenLastCalledWith(50, expect.objectContaining({ noc_call_count: 3 })))
  })
})

describe('Olaylar detayı: salt okunur özet', () => {
  it('son 3 kayıt + sayı; bağlantı Alarm Geçmişi sayfasına gider, izin varsa n_call taşır (form kararı yine sunucuda)', async () => {
    PERMS = {}
    api.nocCalls.list.mockResolvedValue({ success: true, data: [call(1), call(2, { contacted_at: ago(20) }), call(3, { contacted_at: ago(30) }), call(4, { contacted_at: ago(40) })] })
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    const { unmount } = render(<NocCallSummary alertId={50} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-call-entry"]')).toHaveLength(3))
    expect(document.querySelector('[data-slot="noc-call-summary"]').textContent).toMatch(/4/)
    expect(screen.queryByRole('group', { name: 'Quick call entry' })).toBeNull()
    expect(screen.queryByRole('button', { name: /Delete the call/ })).toBeNull()   // salt okunur
    const link = screen.getByRole('link', { name: /Open in alert history/ })
    expect(link.getAttribute('href')).toBe('?tab=alerthistory&alert=50')
    fireEvent.click(link)
    expect(seen.at(-1)).toEqual({ tab: 'alerthistory', params: { alert: '50' } })
    unmount()
    PERMS = { 'noc_calls.write': { edit: true } }
    render(<NocCallSummary alertId={50} />)
    const link2 = await screen.findByRole('link', { name: /Open in alert history/ })
    expect(link2.getAttribute('href')).toBe('?tab=alerthistory&alert=50&n_call=1')
    fireEvent.click(link2)
    expect(seen.at(-1)).toEqual({ tab: 'alerthistory', params: { alert: '50', n_call: '1' } })
    window.removeEventListener('sm:navigate', onNav)
    PERMS = {}
  })
})
