import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Alarm Geçmişi ↔ 7/24 arama kaydı (2026-09-27): liste göstergesi (kart + kapalı satır), yazma kapısı SUNUCUDAN
 * (`noc_can_write`), "Arama kaydet" hızlı eylemi (masaüstünde detay + form odakta, telefonda alttan Sheet),
 * derin bağlantı `?tab=alerthistory&alert={id}&n_call=1` (listede yoksa tekil uçtan) ve `n_call`'ın tüketilmesi,
 * kayıt eklenince göstergenin yeniden yüklemeden güncellenmesi.
 */
let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(),
      getAlert: vi.fn(),
      getTeams: vi.fn(),
      getAlertNotifications: vi.fn(),
      getAlertPushDeliveries: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'),
    },
    nocCalls: { list: vi.fn(), create: vi.fn(), remove: vi.fn(), contacts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { toIso } from '../components/admin/alerts/nocCallModel.js'
import { PAGE_STATE_PARAMS, PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

const MIN = 60_000
const ago = (m) => toIso(Date.now() - m * MIN)
const OPEN = {
  id: 50, domain: 'a.example.com', alert_type: 'PING_DOWN', alert_level: 'CRITICAL', resolved: false, acknowledged: false,
  created_at: ago(90), team_id: 1, message: 'Ping yanıt vermiyor',
  noc_call_count: 2, noc_last_call: { contacted_name: 'Kişi A', outcome: 'REACHED', contacted_at: ago(10) },
}
const QUIET = { ...OPEN, id: 51, domain: 'b.example.com', noc_call_count: 0, noc_last_call: null }
const CLOSED = { ...OPEN, id: 60, domain: 'c.example.com', resolved: true, resolved_at: ago(5), resolved_by: 'system' }

const setUrl = (qs) => window.history.replaceState({}, '', `/?${qs}`)
const listCalls = () => api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p?.size !== 1)

function stubList({ canWrite, data = [OPEN, QUIET], canAct }) {
  // can_act (2026-09-28): verilmezse yanıtta YOK — eski sunucu/mocks davranışı (eylemler açık) korunur
  const act = canAct === undefined ? {} : { can_act: canAct }
  api.admin.getAlerts.mockImplementation(async (p) => {
    if (p?.size === 1) return { success: true, data: [], total: 0, level_counts: {}, noc_can_write: canWrite, ...act }
    return { success: true, data, total: data.length, page: 0, size: 20, noc_can_write: canWrite, ...act }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  MOBILE = false
  setUrl('tab=alerthistory')
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.contacts.mockResolvedValue({ success: true, data: [
    { user_id: 11, display_name: 'Kişi A', title: 'Uzman', has_phone: true, source: 'CALL_LIST', is_manager: false },
  ] })
  api.nocCalls.create.mockImplementation(async (_id, body) => ({ success: true, data: {
    id: 901, contacted_user_id: 11, contacted_name: 'Kişi A', contacted_at: body.contactedAt ?? toIso(Date.now()), channel: 'PHONE',
    outcome: body.outcome, note: null, created_by_name: 'NOC', created_at: toIso(Date.now()), can_delete: true,
    delete_until: toIso(Date.now() + 15 * MIN) } }))
})
afterEach(() => setUrl('tab=alerthistory'))

const card = (id) => document.querySelector(`[data-alert-card][data-alert-id="${id}"]`)

describe('liste göstergesi + izin kapısı', () => {
  it('kartta "2 calls · last: Reached …"; kaydı olmayan kartta gösterge yok; izinsizde "Log a call" YOK', async () => {
    stubList({ canWrite: false })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(card(50)).not.toBeNull())
    const ind = card(50).querySelector('[data-slot="noc-call-indicator"]')
    expect(ind).not.toBeNull()
    expect(ind.textContent).toMatch(/2 calls/)
    expect(ind.textContent).toMatch(/Reached/)
    expect(ind.textContent).toMatch(/Kişi A/)
    expect(card(51).querySelector('[data-slot="noc-call-indicator"]')).toBeNull()
    expect(document.querySelector('[data-noc-log-call]')).toBeNull()
  })

  it('izinli: kartta "Log a call" → masaüstünde detay açılır, form odakta; kayıt sonrası kart göstergesi güncellenir', async () => {
    stubList({ canWrite: true })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(card(51)).not.toBeNull())
    fireEvent.click(within(card(51)).getByRole('button', { name: /Log a call/ }))
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="alert-detail"][data-alert-id="51"]')
      expect(el).not.toBeNull()
      return el
    })
    const form = await waitFor(() => {
      const f = detail.querySelector('[data-slot="noc-call-form"]')
      expect(f).not.toBeNull()
      return f
    })
    await waitFor(() => expect(form.contains(document.activeElement)).toBe(true))
    expect(document.activeElement.getAttribute('role')).toBe('radio')

    await waitFor(() => expect(within(form).getByRole('combobox', { name: /Who did you call/ })).toHaveTextContent('Kişi A'))
    fireEvent.click(within(form).getByRole('radio', { name: 'Reached' }))
    fireEvent.click(within(form).getByRole('button', { name: /Save call/ }))
    await waitFor(() => expect(api.nocCalls.create).toHaveBeenCalledWith(51, expect.objectContaining({ outcome: 'REACHED', contactedUserId: 11 })))
    await waitFor(() => expect(card(51).querySelector('[data-slot="noc-call-indicator"]')?.textContent).toMatch(/1 call/))
    expect(listCalls().length).toBe(1)   // gösterge yeniden YÜKLEMEDEN güncellendi
  })

  it('kapalı satırlar: tabloda gösterge; menüde "Log a call" yalnız izinliye', async () => {
    stubList({ canWrite: true, data: [CLOSED] })
    setUrl('tab=alerthistory&view=closed')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-alert-row][data-alert-id="60"]')).not.toBeNull())
    const row = document.querySelector('[data-alert-row][data-alert-id="60"]')
    expect(row.querySelector('[data-slot="noc-call-indicator"]').textContent).toMatch(/2 calls/)
    pressMenuTrigger(within(row).getByRole('button', { name: /More actions/ }))
    expect(await screen.findByRole('menuitem', { name: /Log a call/ })).toBeInTheDocument()
  })
})

describe('derin bağlantı (?alert=&n_call=1)', () => {
  it('URL ad alanı: n_call sayfa önekiyle (n_) sekme değişince temizlenir; alert uygulamanın PAGE_STATE_PARAMS listesinde', () => {
    expect(PAGE_STATE_PREFIXES.some((pre) => 'n_call'.startsWith(pre))).toBe(true)
    expect(PAGE_STATE_PARAMS).toContain('alert')
    expect(PAGE_STATE_PARAMS).not.toContain('call')   // önek dışı çıplak ad kullanılmıyor
  })

  it('listede olan uyarı: masaüstünde detay + form odakta; alert ve n_call URL\'den silinir', async () => {
    stubList({ canWrite: true })
    setUrl('tab=alerthistory&alert=50&n_call=1')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="50"] [data-slot="noc-call-form"]')).not.toBeNull())
    await waitFor(() => {
      expect(window.location.search).not.toContain('n_call')
      expect(window.location.search).not.toContain('alert=50')
    })
    expect(api.admin.getAlert).not.toHaveBeenCalled()
  })

  it('listede OLMAYAN (kapalı) uyarı: tekil uçtan açılır', async () => {
    stubList({ canWrite: true })
    api.admin.getAlert.mockResolvedValue({ success: true, data: CLOSED, noc_can_write: true })
    setUrl('tab=alerthistory&alert=60&n_call=1')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlert).toHaveBeenCalledWith('60'))
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="60"] [data-slot="noc-call-form"]')).not.toBeNull())
    await waitFor(() => expect(window.location.search).not.toContain('n_call'))
  })

  it('telefonda: alttan hızlı giriş Sheet\'i açılır (tam detay değil)', async () => {
    MOBILE = true
    stubList({ canWrite: true })
    setUrl('tab=alerthistory&alert=50&n_call=1')
    render(<AlertHistory urlSync />)
    const sheet = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-call-sheet"][data-alert-id="50"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(within(sheet).getByRole('heading', { name: /Log a call/ })).toBeInTheDocument()
    expect(sheet.querySelector('[data-slot="noc-call-form"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="alert-detail"]')).toBeNull()
    fireEvent.click(within(sheet).getByRole('button', { name: /Full alert details/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')).not.toBeNull())
  })

  it('yarış: A bağlantısının tekil-uç yanıtı GEÇ gelir, arada B bağlantısı açılmışsa B\'nin detayını EZMEZ', async () => {
    stubList({ canWrite: true })
    let resolveA
    api.admin.getAlert.mockImplementation(() => new Promise((r) => { resolveA = r }))
    setUrl('tab=alerthistory&alert=60&n_call=1')   // 60 listede yok → tekil uç (askıda)
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlert).toHaveBeenCalledWith('60'))
    // Sayfa açıkken ikinci bildirim tıklaması (sm:navigate) → B = 50 (listede)
    act(() => { window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'alerthistory', params: { alert: 50 } } })) })
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')).not.toBeNull())
    await act(async () => { resolveA({ success: true, data: CLOSED, noc_can_write: true }) })
    await act(async () => { await new Promise((r) => setTimeout(r, 30)) })
    expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="60"]')).toBeNull()
    expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')).not.toBeNull()
  })

  it('izni yoksa n_call yalnız detayı açar (form YOK) ve yine tüketilir', async () => {
    stubList({ canWrite: false })
    setUrl('tab=alerthistory&alert=50&n_call=1')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="noc-call-form"]')).toBeNull()
    await waitFor(() => expect(window.location.search).not.toContain('n_call'))
  })
})

describe('7/24 operatörü — başka takımın uyarısında yazma eylemleri', () => {
  // Operatör (noc_can_write) TÜM takımları görür; sahiplen/çöz/tekrar bildir kapsamı DEĞİŞMEDİ (sunucu 403)
  const OTHER = { ...QUIET, id: 52, domain: 'd.example.com', team_id: 9 }
  const INV = { ...QUIET, id: 53, domain: 'e.example.com', team_id: null, sy_team_id: 9, ug_team_id: 1 }   // UG = benim takımım
  const actions = (id) => card(id).querySelector('[data-alert-actions]')

  it('takımım dışı: Sahiplen/Çöz/Tekrar bildir ve seçim kutusu YOK, neden notu var; "Log a call" KALIR; detayda da yok', async () => {
    stubList({ canWrite: true, data: [OPEN, OTHER, INV] })
    render(<AlertHistory urlSync myTeamIds={[1]} />)
    await waitFor(() => expect(card(52)).not.toBeNull())
    expect(within(actions(50)).getByRole('button', { name: /Resolve/ })).toBeInTheDocument()
    expect(within(actions(53)).getByRole('button', { name: /Resolve/ })).toBeInTheDocument()   // envanterin UG takımı benim
    expect(within(actions(52)).queryByRole('button', { name: /Resolve|Acknowledge|Re-Notify/ })).toBeNull()
    expect(card(52).querySelector('[data-slot="alert-act-blocked"]').textContent).toMatch(/another team|isn.t your team|takımınızın değil/)
    expect(within(actions(52)).getByRole('button', { name: /Log a call/ })).toBeInTheDocument()
    expect(within(card(52)).queryByRole('checkbox')).toBeNull()
    fireEvent.click(card(52).querySelector('[data-alert-open]'))
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="alert-detail"][data-alert-id="52"]')
      expect(el).not.toBeNull()
      return el
    })
    const acts = detail.querySelector('[data-slot="alert-detail-actions"]')
    expect(within(acts).queryByRole('button', { name: /^(Resolve|Acknowledge|Re-Notify)$/ })).toBeNull()
    expect(acts.querySelector('[data-slot="alert-act-blocked"]')).not.toBeNull()
  })

  it('global görüntüleyici ve operatör OLMAYAN kullanıcı: kısıt yok (sunucu kapsamı zaten onların görebildiği)', async () => {
    stubList({ canWrite: true, data: [OPEN, OTHER] })
    const { unmount } = render(<AlertHistory urlSync globalViewer myTeamIds={[1]} />)
    await waitFor(() => expect(card(52)).not.toBeNull())
    expect(within(actions(52)).getByRole('button', { name: /Resolve/ })).toBeInTheDocument()
    unmount()
    stubList({ canWrite: false, data: [OPEN, OTHER] })
    render(<AlertHistory urlSync myTeamIds={[1]} />)
    await waitFor(() => expect(card(52)).not.toBeNull())
    expect(within(actions(52)).getByRole('button', { name: /Resolve/ })).toBeInTheDocument()
    expect(card(52).querySelector('[data-slot="alert-act-blocked"]')).toBeNull()
  })

  it('AUDIT + noc_calls.write (2026-09-28): "Log a call" VAR; Sahiplen/Çöz/Tekrar bildir ve seçim YOK (can_act=false) — kart, menü ve detay', async () => {
    // 7/24 ekibi AUDIT rolünde: global görüntüleyici, arama kaydı girer ama alerts.actions izni yok (sunucu can_act=false)
    stubList({ canWrite: true, canAct: false, data: [OPEN, OTHER] })
    render(<AlertHistory urlSync globalViewer myTeamIds={[]} />)
    await waitFor(() => expect(card(52)).not.toBeNull())
    for (const id of [50, 52]) {
      expect(within(actions(id)).queryByRole('button', { name: /Resolve|Acknowledge|Re-Notify/ })).toBeNull()
      expect(within(actions(id)).getByRole('button', { name: /Log a call/ })).toBeInTheDocument()
      const note = card(id).querySelector('[data-slot="alert-act-blocked"]')
      expect(note.getAttribute('data-reason')).toBe('permNoc')
      expect(note.textContent).toMatch(/permission|yetkiniz yok/)
      expect(within(card(id)).queryByRole('checkbox')).toBeNull()
    }
    fireEvent.click(card(50).querySelector('[data-alert-open]'))
    const detail = await waitFor(() => {
      const el = document.querySelector('[data-slot="alert-detail"][data-alert-id="50"]')
      expect(el).not.toBeNull()
      return el
    })
    const acts = detail.querySelector('[data-slot="alert-detail-actions"]')
    expect(within(acts).queryByRole('button', { name: /^(Resolve|Acknowledge|Re-Notify)$/ })).toBeNull()
    expect(within(acts).getByRole('button', { name: /Log a call/ })).toBeInTheDocument()
    expect(acts.querySelector('[data-slot="alert-act-blocked"]').getAttribute('data-reason')).toBe('permNoc')
  })

  it('AUDIT (arama izni de yok): eylem düğmesi yok, not "yetkiniz yok" (arama kaydından söz etmez)', async () => {
    stubList({ canWrite: false, canAct: false, data: [OPEN] })
    render(<AlertHistory urlSync globalViewer myTeamIds={[]} />)
    await waitFor(() => expect(card(50)).not.toBeNull())
    expect(within(actions(50)).queryByRole('button', { name: /Resolve|Acknowledge|Re-Notify|Log a call/ })).toBeNull()
    const note = card(50).querySelector('[data-slot="alert-act-blocked"]')
    expect(note.getAttribute('data-reason')).toBe('perm')
    expect(note.textContent).not.toMatch(/log calls|arama kaydı/)
  })
})
