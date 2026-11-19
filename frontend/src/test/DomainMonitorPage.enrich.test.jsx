import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
await vi.hoisted(async () => (await import('./helpers/orgNoonClock.js')).freezeOrgNoon())   // kalan gün İstanbul günüyle (LOW 2): sabit 09:00Z
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const dl = vi.hoisted(() => ({ ok: true, calls: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({
  ...(await orig()),
  downloadBlob: (name, blob) => { dl.calls.push({ name, blob }); return dl.ok },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:          vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDomainMonitors:   vi.fn(),
      getCheckHistory:     vi.fn(() => Promise.resolve({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      monitorDefaults:     vi.fn(() => Promise.resolve({ success: true, data: { domain: {} } })),
      domainRenewalPlanBulk: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const base = { team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod', status: 'OK', active: true, can_edit: true, can_check: true,
  warning_days: 30, critical_days: 7, checked_at: '2026-11-01T00:00:00' }
const rows = [
  { ...base, id: 1, name: 'godaddy-1', domain: 'godaddy-1.com', registrar: 'GoDaddy.com, LLC', source: 'RDAP', expiry_date: day(-2), days_remaining: 9 },
  { ...base, id: 2, name: 'godaddy-2', domain: 'godaddy-2.com', registrar: 'GODADDY.COM, LLC', source: 'RDAP_REGISTRY', expiry_date: day(90), days_remaining: 90 },
  { ...base, id: 3, name: 'whois-tr', domain: 'whois-tr.com.tr', registrar: 'İsimtescil', source: 'WHOIS', expiry_date: day(40), days_remaining: 40,
    renewal_planned_at: '2099-01-01' },
  { ...base, id: 4, name: 'unknown', domain: 'unknown.com', registrar: null, source: 'NONE', expiry_date: null, days_remaining: null },
]
const cards = () => [...document.querySelectorAll('.upt-grid [data-monitor-open]')].map((b) => b.textContent.trim()).sort()
// Faset düğmeleri (2026-11-19): kesikli kenarlı düğme → Popover + Command listesi (role=option), sayı ayrı sütunda
const facetBtn = (name) => screen.getByRole('button', { name: new RegExp('^' + name) })
const facetOptions = () => [...document.querySelectorAll('[role="listbox"] [role="option"]')]
const optText = (o) => [o.querySelector('span:not([data-slot])')?.textContent ?? o.textContent, o.querySelector('[data-slot="facet-count"]')?.textContent]
function openFacet(name) { pressMenuTrigger(facetBtn(name)) }
function closeFacet() { fireEvent.keyDown(document.activeElement, { key: 'Escape' }) }
function pick(name, label) {
  openFacet(name)
  const opt = facetOptions().find((o) => label.test(optText(o)[0]))
  if (!opt) throw new Error(`seçenek yok: ${label}`)
  fireEvent.click(opt)
  closeFacet()
}

beforeEach(() => {
  vi.clearAllMocks()
  dl.ok = true
  dl.calls = []
  api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: rows })
  window.history.replaceState({}, '', '/?tab=domain')
})
afterEach(() => window.history.replaceState({}, '', '/'))

describe('DomainMonitorPage — C1 ek süzgeçler', () => {
  it('kalan gün canlı: kayıtlı 9 gün dese de bitişi geçmiş satır "dolmuş" sayılır ve en başta', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    const first = document.querySelector('.upt-grid [data-monitor-open]')
    expect(first).toHaveTextContent('godaddy-1.com')
  })

  it('registrar Türkçe katlanır ve sayılır; kaynak RDAP ailesi; süre durumu; Süzgeçleri temizle hepsini sıfırlar', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    openFacet('Registrar')
    const labels = facetOptions().map(optText)
    expect(labels.some(([l, n]) => /GoDaddy\.com, LLC/i.test(l) && n === '2')).toBe(true)
    expect(labels.at(-1)).toEqual(['Registrar unknown', '1'])
    fireEvent.click(facetOptions().find((o) => /GoDaddy/i.test(o.textContent)))
    closeFacet()
    await waitFor(() => expect(cards()).toEqual(['godaddy-1.com', 'godaddy-2.com']))
    expect(facetBtn('Registrar').querySelector('[data-slot="badge"]')).toHaveTextContent('1')
    pick('Expiry state', /^Expired$/)
    await waitFor(() => expect(cards()).toEqual(['godaddy-1.com']))
    // kaynak sayımı öteki iki ek süzgeç uygulanmış listeden
    openFacet('Data source')
    expect(facetOptions().map(optText)).toEqual([['RDAP', '1'], ['WHOIS', '0'], ['Source unknown', '0']])
    closeFacet()
    fireEvent.click(document.querySelector('[data-slot="domain-filter-clear"]'))
    await waitFor(() => expect(cards()).toHaveLength(4))
    expect(document.querySelector('[data-slot="domain-filter-clear"]')).toBeNull()
  })

  it('LOW 6 + sade düğme (2026-11-19): tetik kısa başlık ("Registrar", sayı rozeti); uzun registrar adı listede SARAR, kesilmez', async () => {
    const long = 'Tucows Domains Inc. dba OpenSRS Reseller Services (Toronto / Canada Registrar Operations)'
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [...rows, { ...base, id: 9, name: 'uzun', domain: 'uzun.com',
      registrar: long, source: 'RDAP', expiry_date: day(200), days_remaining: 200 }] })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    for (const name of ['Registrar', 'Data source', 'Expiry state']) {
      const trig = facetBtn(name)
      expect(trig.textContent, name).toBe(name)   // "Tüm kayıt kuruluşları (4)" gibi uzun metin YOK
      expect(trig.className, name).toMatch(/border-dashed/)
      expect(trig.className, name).toMatch(/pointer-coarse:h-10/)
    }
    openFacet('Registrar')
    const opt = facetOptions().find((o) => o.textContent.includes('Tucows'))
    expect(optText(opt)).toEqual([long, '1'])
    const label = [...opt.querySelectorAll('span')].find((x) => !x.querySelector('span') && x.textContent.includes('Tucows'))
    expect(label.className).toMatch(/overflow-wrap:anywhere/)
    expect(label.className).not.toMatch(/\btruncate\b/)
    fireEvent.click(opt)
    closeFacet()
    await waitFor(() => expect(cards()).toEqual(['uzun.com']))
    expect(facetBtn('Registrar')).toHaveAttribute('title', long)   // seçili ad ipucunda
  })

  it('çoklu seçim URL\'ye `|` ile yazılır ve geri okunur; eski tek değerli bağlantı çalışır', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    openFacet('Data source')
    fireEvent.click(facetOptions().find((o) => /^WHOIS/.test(optText(o)[0])))
    fireEvent.click(facetOptions().find((o) => /^Source unknown/.test(optText(o)[0])))
    closeFacet()
    await waitFor(() => expect(cards()).toEqual(['unknown.com', 'whois-tr.com.tr']))
    await waitFor(() => expect(new URL(window.location.href).searchParams.get('ds')).toBe('whois|unknown'), { timeout: 3000 })
  })

  it('URL\'den okunur (kayıtlı görünüm / paylaşılan bağlantı): ?dx=unknown&ds=unknown', async () => {
    window.history.replaceState({}, '', '/?tab=domain&dx=unknown&ds=unknown')
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards()).toEqual(['unknown.com']))
  })

  it('hızlı süzgeç "Yalnız etkin izlemeler" (dq=active) duraklatılanı listelemez — Panom kutucuğunun bağlantısı (2026-11-15)', async () => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [
      ...rows,
      { ...base, id: 5, name: 'durdu', domain: 'durdu.com', active: false, registrar: 'X', source: 'RDAP', expiry_date: day(-30), days_remaining: -30 },
    ] })
    window.history.replaceState({}, '', '/?tab=domain&dx=expired')
    const r = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards()).toEqual(['durdu.com', 'godaddy-1.com']))   // süzgeçsiz: duraklatılan da listede
    r.unmount()
    window.history.replaceState({}, '', '/?tab=domain&dq=active&dx=expired')
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards()).toEqual(['godaddy-1.com']))
  })
})

describe('DomainMonitorPage — C3 takvim (.ics)', () => {
  it('görünen listeyi indirir; bitişi bilinmeyen atlanır ve bildirimde sayılır', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^(dışa aktar|export)$/i }))
    const ics = await screen.findByRole('menuitem', { name: /calendar \(\.ics\)/i })
    fireEvent.click(ics)
    await waitFor(() => expect(dl.calls).toHaveLength(1))
    expect(dl.calls[0].name).toMatch(/\.ics$/)
    expect(await screen.findByText(/3 expiry dates were exported.*1 monitors with an unknown expiry were skipped/i)).toBeInTheDocument()
  })

  it('tarayıcı indirmeyi başlatamazsa HATA (asla "aktarıldı" değil)', async () => {
    dl.ok = false
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^(dışa aktar|export)$/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /calendar \(\.ics\)/i }))
    expect(await screen.findByText(/could not download the calendar file/i)).toBeInTheDocument()
    expect(screen.queryByText(/were exported to the calendar file/i)).toBeNull()
  })
})

describe('DomainMonitorPage — D1 toplu yenileme planı', () => {
  const selectAll = async () => {
    await screen.findByText('godaddy-1.com')
    for (const cb of document.querySelectorAll('.upt-grid [role="checkbox"]')) fireEvent.click(cb)
    return await waitFor(() => { const b = document.querySelector('[data-slot="domain-bulk-plan"]'); expect(b).not.toBeNull(); return b })
  }

  it('önizleme → tek istek → satır satır sonuç; tarih zorunlu (alanın altında); görünmeyen satırın adı gizli', async () => {
    api.monitoring.domainRenewalPlanBulk.mockResolvedValue({ success: true, data: { action: 'set', done: 2, skipped: 2, failed: 0, results: [
      { id: 1, name: 'godaddy-1', status: 'done', reason: 'done' },
      { id: 2, name: 'godaddy-2', status: 'done', reason: 'done' },
      { id: 3, name: 'whois-tr', status: 'skipped', reason: 'unchanged' },
      { id: 4, name: null, status: 'skipped', reason: 'not_found' },
    ] } })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await selectAll())
    const dlg = await screen.findByRole('dialog')
    // en son kontrolü kaynaksız (NONE) satır: daha eski kontrol bitişi bilebilir → "sunucu denetleyecek" (LOW 4), atlandı DEĞİL
    const row4 = [...dlg.querySelectorAll('[data-slot="bulk-preview-row"]')].find((r) => r.textContent.includes('unknown.com'))
    expect(row4.dataset.state).toBe('check')
    expect(within(row4).getByText('Server will check')).toBeInTheDocument()
    expect(within(dlg).queryAllByText('Expiry date unknown — no plan can be set')).toHaveLength(0)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Apply plan' }))
    expect(await within(dlg).findByText(/a bulk plan needs a date/i)).toBeInTheDocument()
    expect(api.monitoring.domainRenewalPlanBulk).not.toHaveBeenCalled()
    fireEvent.change(within(dlg).getByLabelText(/Plan date/), { target: { value: '2026-12-20' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Apply plan' }))
    await waitFor(() => expect(api.monitoring.domainRenewalPlanBulk).toHaveBeenCalledTimes(1))
    const body = api.monitoring.domainRenewalPlanBulk.mock.calls[0][0]
    expect({ ...body, ids: [...body.ids].sort() }).toEqual({ action: 'set', ids: [1, 2, 3, 4], date: '2026-12-20', note: null })
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'result'))
    const result = [...dlg.querySelectorAll('[data-slot="bulk-result-row"]')].map((r) => [r.dataset.status, r.dataset.reason])
    expect(result).toEqual(expect.arrayContaining([['done', 'done'], ['skipped', 'unchanged'], ['skipped', 'not_found']]))
  })

  it('kaldır: planı olmayan "zaten bu durumda" önizlenir; sunucu alan hatası alanın altında', async () => {
    api.monitoring.domainRenewalPlanBulk.mockResolvedValue({ success: false, field: 'ids', error: 'Select at least one monitor.' })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await selectAll())
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('radio', { name: 'Remove plan' }))
    const preview = [...dlg.querySelectorAll('[data-slot="bulk-preview-row"]')]
    expect(preview.filter((r) => r.dataset.state === 'change')).toHaveLength(1)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Remove plans' }))
    expect(await within(dlg).findByText('Select at least one monitor.')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'preview')
  })
})

describe('DomainRegistrationTab — A4 canlı sorgu sınırı', () => {
  it('429 → sunucunun metni, veri korunur, Yenile bekleme bitince yeniden kullanılabilir', async () => {
    vi.useRealTimers()   // dosyanın sabit saati (yalnız Date) yerine tüm zamanlayıcılar sahte — sonra geri alınır
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { default: DomainRegistrationTab } = await import('../components/DomainRegistrationTab.jsx')
      const reg = { ...rows[1], checked_at: '2026-11-15T08:00:00' }
      api.monitoring.getDomainRegistration = vi.fn((id, { live } = {}) => Promise.resolve(live
        ? { success: false, code: 'RATE_LIMITED', retry_after_seconds: 30, error: 'Too many live lookups: at most 6 per minute. Try again in 30 s.' }
        : { success: true, data: reg }))
      api.monitoring.getDomainReminders = vi.fn(() => Promise.resolve({ success: true, data: { thresholds: [], items: [] } }))
      render(<DomainRegistrationTab monitor={{ id: 2, can_check: true }} />)
      const refresh = await waitFor(() => { const b = document.querySelector('[data-slot="domain-reg-refresh"]'); expect(b).not.toBeNull(); return b })
      fireEvent.click(refresh)
      expect(await screen.findByText(/Too many live lookups: at most 6 per minute/)).toBeInTheDocument()
      expect(document.querySelector('[data-slot="domain-registration"]')).not.toBeNull()   // veri korunur
      expect(document.querySelector('[data-slot="domain-reg-refresh"]')).toBeDisabled()
      await act(async () => { vi.advanceTimersByTime(31_000) })
      await waitFor(() => expect(document.querySelector('[data-slot="domain-reg-refresh"]')).not.toBeDisabled())
    } finally {
      vi.useRealTimers()
      ;(await import('./helpers/orgNoonClock.js')).refreezeOrgNoon()
    }
  })
})

