import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { MAIL_PREVIEW_SANDBOX } from '../utils/mailPreview.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

// Telefon/masaüstü yapı farkı hook'tan (useIsMobile): testte deterministik anahtar.
let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts:        vi.fn(),
      acknowledgeAlert: vi.fn(),
      resolveAlert:     vi.fn(),
      reNotifyAlert:    vi.fn(),
      previewReNotify:  vi.fn(),
      bulkAlertAction:  vi.fn(),
      getAlertNotifications: vi.fn(),
      getAlertPushDeliveries: vi.fn(),
      getTeams:         vi.fn(),   // süzgeç çubuğu takım listesini çeker (yalnız urlSync modunda)
      getAlertsCsvUrl:  vi.fn(() => '/api/admin/alerts/export'),
    },
  }),
}))

import { api } from '../api/client'

/**
 * LİSTE istekleri: üst istatistikler iki küçük istek (size=1) atar — açık küme + son 24 saatte çözülen; liste
 * isteğini sınayan iddialar onları ayıklar (sayfa boyutu ön ayarları ≥ 10).
 */
const listCalls = () => api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p?.size !== 1)
const lastList = () => listCalls().at(-1)
/** Radix Tabs tetiği pointerdown/mousedown ile etkinleşir (jsdom'da click yetmez). */
const pickTab = (name) => pressMenuTrigger(screen.getByRole('tab', { name }))

const closedAlert = {
  id: 101,
  domain: 'foo.example.com',
  alert_type: 'EXPIRY',
  alert_level: 'CRITICAL',
  days_remaining: 7,
  acknowledged: true,
  acknowledged_by: 'erdi',
  acknowledged_at: '2026-06-05T10:00:00',
  resolved: true,
  resolved_by: 'erdi',
  resolved_at: '2026-06-07T10:00:00',
  created_at: '2026-06-01T08:00:00',
  // enrichment fields
  sy_team_name:       'SY-Team-A',
  ug_team_name:       'UG-Team-B',
  cert_tier:          1,
  email_sent_count:   3,
  email_failed_count: 1,
}

beforeEach(() => {
  MOBILE = false
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
})

describe('AlertHistory — kapalı alarm özeti ve detayı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closedAlert], total: 1, page: 0, size: 20 })
  })

  it('renders without crashing on the open tab', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(screen.getByRole('tab', { name: /^Open/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('kapalı satır ÖZETİ: süre, gönderim sayıları (başarılı/başarısız), çözen ve not önizlemesi', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, resolved_by: 'system', resolved_note: 'otomatik kapandı çünkü düzeldi' }] })
    render(<AlertHistory />)
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-history-card]')).not.toBeNull())
    const row = document.querySelector('[data-history-card]')
    expect(row.textContent).toMatch(/3 sent/)
    expect(row.textContent).toMatch(/1 failed/)
    // 2026-06-01 08:00 → 2026-06-07 10:00 = 6 gün 2 saat; birimler i18n'den (incov.unit.*), çıplak "d" tek başına değil
    expect(row.textContent).toMatch(/6\s*[gd]\b/)
    // Sistem jetonu KİŞİ rozeti değil, okunur karşılığıyla çizilir
    expect(within(row).getByText(/Automatic|Otomatik/)).toBeInTheDocument()
    expect(row.querySelector('[data-slot="resolve-note"]').textContent).toBe('otomatik kapandı çünkü düzeldi')
  })

  it('zenginleştirme (SY/UG takımları, tier) DETAY panelinde görünür', async () => {
    render(<AlertHistory urlSync />)
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-alert-row]'))
    const detail = await screen.findByRole('dialog')
    expect(within(detail).getByText('SY-Team-A')).toBeInTheDocument()
    expect(within(detail).getByText('UG-Team-B')).toBeInTheDocument()
    expect(within(detail).getByText('T1')).toBeInTheDocument()
  })

  it('"gönderilemedi" rozeti açık kartta, email_failed_count > 0 iken', async () => {
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.getByText(/could not be sent/i)).toBeDefined()
  })

  it('email_failed_count 0 iken rozet çizilmez', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [{ ...closedAlert, email_failed_count: 0 }], total: 1, page: 0, size: 20 })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    expect(screen.queryByText(/could not be sent/i)).toBeNull()
  })

  it('toplu seçim: kartın kutusu seçilince çubuk sayıyı ve üç eylemi gösterir', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, id: 201, resolved: false, acknowledged: false }] })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())

    expect(screen.getByText(/select all/i)).toBeDefined()
    expect(screen.queryByTestId('alh-bulk-actions')).toBeNull()

    fireEvent.click(screen.getByRole('checkbox', { name: /foo\.example\.com.*select this alert/i }))

    await waitFor(() => expect(screen.getByText(/1 selected/i)).toBeDefined())
    const bar = screen.getByTestId('alh-bulk-actions')
    expect(within(bar).getByRole('button', { name: /^Acknowledge$/ })).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: /^Re-Notify$/i })).toBeInTheDocument()
    expect(within(bar).getByRole('button', { name: /^Resolve$/ })).toBeInTheDocument()
  })

  it('Tekrar Bildir: önizleme pencereyi alıcılarla açar; biri çıkarılınca excludeEmails ile gönderir', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }] })
    api.admin.previewReNotify.mockResolvedValue({ success: true, data: { alert_id: 301, recipients: [
      { email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' },
      { email: 'mudur@example.com', name: 'Ayşe Yılmaz', role: 'MANAGER', kind: 'CONTACT' },
    ] } })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    const card = document.querySelector('[data-alert-card]')
    fireEvent.click(within(card).getByRole('button', { name: /foo\.example\.com.*Re-Notify/ }))
    await waitFor(() => expect(api.admin.previewReNotify).toHaveBeenCalledWith(301))
    expect(api.admin.reNotifyAlert).not.toHaveBeenCalled()

    await screen.findByText(/confirm recipients/i)
    expect(screen.getByText('takim-a@example.com')).toBeDefined()
    expect(screen.getByText(/2 recipients selected/i)).toBeDefined()
    const modal = screen.getByRole('dialog')
    const row = Array.from(modal.querySelectorAll('label')).find((l) => l.textContent.includes('mudur@example.com'))
    fireEvent.click(row.parentElement.querySelector('[role="checkbox"]'))
    expect(screen.getByText(/1 recipients selected/i)).toBeDefined()
    fireEvent.click(within(modal).getByRole('button', { name: /^send$/i }))
    await waitFor(() => expect(api.admin.reNotifyAlert).toHaveBeenCalledWith(301, { excludeEmails: ['mudur@example.com'] }))
  })
})

/** TİP SÖZLÜĞÜ REGRESYONU (2026-08-16): 28 tipin hepsi okunur adıyla görünür ve süzülebilir. */
describe('AlertHistory — alarm tipi sözlüğü', () => {
  const alertOfType = (type, id) => ({
    id, domain: `x${id}.example.com`, alert_type: type, alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00',
  })

  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/'); api.admin.getTeams.mockResolvedValue({ success: true, data: [] }) })

  it('YENİ izleme türlerinin alarmları ham enum DEĞİL, okunur adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 3, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1), alertOfType('KEYWORD_SLOW', 2), alertOfType('PING_DOWN', 3)] })
    render(<AlertHistory />)
    const chips = await waitFor(() => {
      const el = [...document.querySelectorAll('[data-alert-card] [data-slot="alert-type"]')]
      if (el.length !== 3) throw new Error('kartlar henüz çizilmedi')
      return el.map((c) => c.textContent.trim())
    })
    for (const raw of ['SCRIPTED_FAIL', 'KEYWORD_SLOW', 'PING_DOWN']) expect(chips).not.toContain(raw)
    expect(chips.every((c) => c.length > 0)).toBe(true)
  })

  it('Tür faset menüsü yeni türleri CANLI sayılarıyla listeler (ham enum yok)', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)], type_counts: { SCRIPTED_FAIL: 4, PAGE_INTEGRITY: 2 } })
    render(<AlertHistory urlSync />)
    await screen.findByText('x1.example.com')
    pressMenuTrigger(document.querySelector('[data-slot="facet-trigger"][data-facet="type"]'))
    const items = await screen.findAllByRole('menuitemradio')
    const texts = items.map((i) => i.textContent)
    expect(texts.filter((x) => /4$/.test(x))).toHaveLength(1)
    expect(texts.filter((x) => /2$/.test(x))).toHaveLength(1)
    expect(texts.some((x) => x.includes('SCRIPTED_FAIL'))).toBe(false)
  })

  it('tür seçilince O TİPLE süzülerek yeniden yüklenir ve etkin çip çıkar', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [alertOfType('SCRIPTED_FAIL', 1)], type_counts: { SCRIPTED_FAIL: 4 } })
    render(<AlertHistory urlSync />)
    await screen.findByText('x1.example.com')
    pressMenuTrigger(document.querySelector('[data-facet="type"]'))
    const opt = (await screen.findAllByRole('menuitemradio')).find((i) => i.getAttribute('data-facet-value') === 'SCRIPTED_FAIL')
    fireEvent.click(opt)
    await waitFor(() => expect(lastList().alertType).toBe('SCRIPTED_FAIL'))
    expect(document.querySelector('[data-slot="active-filters"] [data-filter="type"]')).not.toBeNull()
  })

  it('SÖZLÜKTE OLMAYAN bir tip ekranı çökertmez, ham adıyla görünür', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20, data: [alertOfType('HENUZ_OLMAYAN_TIP', 9)] })
    render(<AlertHistory />)
    expect((await screen.findAllByText('HENUZ_OLMAYAN_TIP')).length).toBeGreaterThan(0)
  })
})

/** GEREKÇE NOTU — "kim ve ne zaman"ın yanına "NEDEN". Notsuz eski kayıtta boş blok çizilmez. */
describe('AlertHistory — onay/çözüm gerekçesi', () => {
  const withNotes = { ...closedAlert, id: 301, acknowledged_note: 'planlı bakım kapsamında susturuldu', resolved_note: 'sertifika yenilendi ve doğrulandı' }

  beforeEach(() => { vi.clearAllMocks(); api.admin.getTeams.mockResolvedValue({ success: true, data: [] }) })

  /** Gömülü kullanım (izleme penceresi): kapalı görünüm → satır → iç içe detay penceresi → zaman çizelgesi. */
  async function openClosedDetail() {
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-alert-row]'))
    await waitFor(() => expect(document.querySelector('[data-timeline]')).not.toBeNull())
  }

  it('kapalı alarmın zaman çizelgesinde sahiplenme ve çözüm notları GÖRÜNÜR', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [withNotes], total: 1, page: 0, size: 20 })
    render(<AlertHistory domain="foo.example.com" />)
    await openClosedDetail()
    const notes = [...document.querySelectorAll('[data-timeline] [data-tl-note]')].map((n) => n.textContent)
    expect(notes).toEqual(['planlı bakım kapsamında susturuldu', 'sertifika yenilendi ve doğrulandı'])
  })

  it('NOTSUZ eski alarmda boş gerekçe bloğu ÇİZİLMEZ', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [closedAlert], total: 1, page: 0, size: 20 })
    const { container } = render(<AlertHistory domain="foo.example.com" />)
    await openClosedDetail()
    expect(document.querySelector('[data-tl-note]')).toBeNull()
    expect(container.querySelector('[data-audit-note]')).toBeNull()
  })

  it('AÇIK alarmın kartında sahiplenen + notu gösterilir', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20, data: [{
      id: 302, domain: 'acik.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
      acknowledged: true, acknowledged_by: 'erdi', acknowledged_at: '2026-06-05T10:00:00',
      acknowledged_note: 'bilinen sorun takip ediliyor', resolved: false, created_at: '2026-06-01T08:00:00',
    }] })
    const { container } = render(<AlertHistory domain="acik.example.com" />)
    expect(await screen.findByText('bilinen sorun takip ediliyor')).toBeInTheDocument()
    expect(container.querySelector('[data-alert-card] [data-audit-note]')).not.toBeNull()
  })
})

/**
 * İSTATİSTİKLER EN ÜSTTE (2026-09-27 kullanıcı isteği): başlığın hemen altında, VARSAYILAN AÇIK; sayılar SUNUCUDAN
 * (kapsamdaki açık küme + son 24 saatte çözülen), süzgeçten bağımsız. Kartlar süzgeçtir; "24 sa+ açık" sayaçtır.
 */
describe('AlertHistory — üst istatistikler, süzgeçler ve paylaşılabilir bağlantı', () => {
  const openAlert = { id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00' }

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.admin.getAlerts.mockResolvedValue({
      success: true, data: [openAlert], total: 10, page: 0, size: 20,
      level_counts: { CRITICAL: 5, HIGH: 3, WARNING: 2 }, unacked_total: 7, stale_total: 4, stale_hours: 24,
    })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  })

  const tiles = async () => waitFor(() => {
    const el = [...document.querySelectorAll('[data-slot="stat-item"]')]
    if (el.length === 0) throw new Error('istatistikler henüz yok')
    return el
  })

  it('GÖMÜLÜ modda başlık, istatistik, araç çubuğu ÇIKMAZ ve takım listesi istenmez', async () => {
    const { container } = render(<AlertHistory domain="a.example.com" />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(container.querySelector('[data-slot="page-header"]')).toBeNull()
    expect(container.querySelector('[data-testid="alh-toolbar"]')).toBeNull()
    expect(container.querySelector('[data-slot="stats-panel"]')).toBeNull()
    expect(api.admin.getTeams).not.toHaveBeenCalled()
    expect(listCalls()).toHaveLength(api.admin.getAlerts.mock.calls.length)   // özet istekleri de YOK
  })

  it('istatistikler başlığın HEMEN ALTINDA, sekmelerden ÖNCE ve varsayılan AÇIK', async () => {
    const { container } = render(<AlertHistory urlSync />)
    await tiles()
    const header = container.querySelector('[data-slot="page-header"]')
    const stats = container.querySelector('[data-slot="stats-panel"]')
    const tabs = container.querySelector('[data-slot="tabs-list"]')
    expect(header.compareDocumentPosition(stats) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(stats.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(container.querySelector('[data-slot="stats-toggle"]')).toBeNull()   // katlanır şerit değil
    expect(screen.getByRole('heading', { level: 2, name: 'Alert History' })).toBeInTheDocument()
  })

  it('sayılar SUNUCUDAN: açık · kritik · yüksek · uyarı · sahiplenilmemiş · sahiplenilmiş · 24 sa+ · son 24 sa çözülen', async () => {
    await (render(<AlertHistory urlSync />), tiles())
    const values = [...document.querySelectorAll('[data-slot="stat-value"]')].map((v) => v.textContent)
    // Listede 1 satır var; sayfa içinden hesaplansaydı hepsi 1 olurdu. Sahiplenilmiş = 10 − 7.
    expect(values).toEqual(['10', '5', '3', '2', '7', '3', '4', '10'])
    // Başlığın canlı özeti aynı kaynaktan
    expect(document.querySelector('[data-slot="alert-history-live"]').textContent).toMatch(/10 open.*7 unacknowledged.*10 resolved in the last 24 hours/)
    // Özet iki küçük istekle: açık küme + son 24 saatte çözülen (resolvedSince)
    const summaries = api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p.size === 1)
    expect(summaries.map((p) => p.resolved)).toEqual(['false', 'true'])
    expect(summaries[1].resolvedSince).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  })

  it('Kritik kartı seviye süzgecini SUNUCUYA gönderir ve kart basılı görünür', async () => {
    const all = await (render(<AlertHistory urlSync />), tiles())
    fireEvent.click(all[1])   // Kritik
    await waitFor(() => expect(lastList().level).toBe('CRITICAL'))
    await waitFor(() => expect([...document.querySelectorAll('[data-slot="stat-item"]')][1]).toHaveAttribute('aria-pressed', 'true'))
  })

  it('Sahiplenilmemiş kartı SEVİYE değil sahiplenme boyutunu süzer', async () => {
    const all = await (render(<AlertHistory urlSync />), tiles())
    fireEvent.click(all[4])
    await waitFor(() => {
      expect(lastList().acknowledged).toBe('false')
      expect(lastList().level).toBeUndefined()
    })
  })

  it('"Son 24 saatte çözülen" kartı KAPALI görünüme son 24 saat aralığıyla geçer', async () => {
    const all = await (render(<AlertHistory urlSync />), tiles())
    fireEvent.click(all[7])
    await waitFor(() => {
      expect(lastList().resolved).toBe('true')
      expect(lastList().resolvedSince).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    })
    expect(screen.getByRole('tab', { name: /Closed/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('"24 sa+ açık" kartı SAYAÇTIR — tıklanınca yeni istek atılmaz', async () => {
    const all = await (render(<AlertHistory urlSync />), tiles())
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(0))
    const before = api.admin.getAlerts.mock.calls.length
    fireEvent.click(all[6])
    await new Promise((r) => setTimeout(r, 50))
    expect(api.admin.getAlerts.mock.calls.length).toBe(before)
  })

  it('Arama DEBOUNCE edilir — her tuşta istek atılmaz', async () => {
    render(<AlertHistory urlSync />)
    const box = await screen.findByRole('searchbox', { name: /Search domain/ })
    await waitFor(() => expect(listCalls().length).toBeGreaterThan(0))
    const before = listCalls().length
    fireEvent.change(box, { target: { value: 'a' } })
    fireEvent.change(box, { target: { value: 'ak' } })
    fireEvent.change(box, { target: { value: 'akb' } })
    await waitFor(() => expect(lastList().q).toBe('akb'), { timeout: 2000 })
    expect(listCalls().length - before).toBeLessThan(3)
  })

  it('Seviye faseti → etkin çip; çipin × düğmesi süzgeci kaldırır, "Temizle" hepsini', async () => {
    render(<AlertHistory urlSync />)
    await screen.findByText('a.example.com')
    pressMenuTrigger(document.querySelector('[data-facet="level"]'))
    fireEvent.click(await screen.findByRole('menuitemradio', { name: /HIGH/ }))
    await waitFor(() => expect(lastList().level).toBe('HIGH'))
    const chips = screen.getByRole('group', { name: /Active filters/ })
    fireEvent.click(within(chips).getByRole('button', { name: /Remove filter: HIGH/ }))
    await waitFor(() => expect(lastList().level).toBeUndefined())
    expect(screen.queryByRole('group', { name: /Active filters/ })).toBeNull()
  })

  it('PAYLAŞILABİLİR BAĞLANTI: süzgeçler adres çubuğunda yaşar', async () => {
    const all = await (render(<AlertHistory urlSync />), tiles())
    fireEvent.click(all[1])
    await waitFor(() => expect(window.location.search).toContain('level=CRITICAL'), { timeout: 2000 })
  })

  it("URL'deki süzgeçlerle AÇILIR — bağlantıyı alan aynı listeyi görür", async () => {
    window.history.replaceState({}, '', '/?level=HIGH&q=example&view=closed')
    render(<AlertHistory urlSync />)
    await waitFor(() => {
      const first = api.admin.getAlerts.mock.calls[0][0]
      expect(first.level).toBe('HIGH')
      expect(first.q).toBe('example')
      expect(first.resolved).toBe('true')
    })
  })

  it('view=all → "Tümü" görünümü: resolved parametresi GİTMEZ, satırlarda durum sütunu var', async () => {
    window.history.replaceState({}, '', '/?view=all')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts.mock.calls[0][0].resolved).toBeUndefined())
    await waitFor(() => expect(document.querySelector('[data-alert-row] [data-slot="alert-state"]')).not.toBeNull())
  })

  // Regression: ISSUE-002 — alt görünüm `tab` anahtarını ezmemeli (derin bağlantı dashboard'a düşüyordu).
  it('ISSUE-002: ?tab=alerthistory korunur; alt görünüm `view` anahtarıyla yazılır', async () => {
    window.history.replaceState({}, '', '/?tab=alerthistory')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 400))
    expect(window.location.search).toContain('tab=alerthistory')
    expect(window.location.search).not.toContain('view=')
    pickTab(/Closed/)
    await waitFor(() => expect(window.location.search).toContain('view=closed'), { timeout: 2000 })
    expect(window.location.search).toContain('tab=alerthistory')
  })

  // Regresyon B3 (2026-09-28): haftalık e-postanın "Haftanın alarmları" bağlantısı önceki haftadan DEVREDEN alarmları
  // göstermiyordu (liste açılış anına göre süzülüyordu). Bağlantı artık `range=active` taşır → "aralıkta aktif" kipi.
  it('B3: e-posta bağlantısı (view=all&from&to&range=active&team) → aralıkta-aktif isteği + çip; × varsayılana döner, tarih kalır', async () => {
    window.history.replaceState({}, '', '/?tab=alerthistory&view=all&from=2026-09-21&to=2026-09-27&range=active&team=5')
    render(<AlertHistory urlSync />)
    await waitFor(() => {
      const first = listCalls()[0]
      expect(first.range).toBe('active')
      expect(first.since).toBeTruthy()
      expect(first.until).toBeTruthy()
      expect(first.teamId).toBe('5')
      expect(first.resolved).toBeUndefined()
    })
    const chips = await screen.findByRole('group', { name: /Active filters/ })
    expect(within(chips).getByText('Active during this range')).toBeInTheDocument()
    expect(document.querySelector('[data-facet="dates"]')).toHaveTextContent(/Active during/)   // tarih faseti kipi söyler

    fireEvent.click(within(chips).getByRole('button', { name: 'Remove filter: Active during this range' }))
    await waitFor(() => expect(lastList().range).toBeUndefined())
    expect(lastList().since).toBeTruthy()                                                      // yalnız kip değişti
    expect(screen.queryByText('Active during this range')).toBeNull()
    expect(document.querySelector('[data-facet="dates"]')).toHaveTextContent(/Opened on/)
    await waitFor(() => expect(window.location.search).not.toContain('range='), { timeout: 2000 })
    expect(window.location.search).toContain('tab=alerthistory')
    expect(window.location.search).toContain('from=2026-09-21')
  })

  it('B3: range=active yalnız "Tümü"nde — kapalı görünümde istek kip taşımaz, çip çıkmaz (gizli süzgeç yok); bozuk değer yok sayılır', async () => {
    window.history.replaceState({}, '', '/?view=closed&from=2026-09-21&range=active')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.resolvedSince).toBeTruthy())
    expect(lastList().range).toBeUndefined()
    expect(screen.queryByText('Active during this range')).toBeNull()
  })

  it('B3: tarih süzgecindeki kip seçimi (RadioGroup): "Active during the range" → range=active isteği, çip ve URL', async () => {
    window.history.replaceState({}, '', '/?view=all&from=2026-09-21&to=2026-09-27&range=7')   // başka sayfanın range'i → yok sayılır
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.since).toBeTruthy())
    expect(lastList().range).toBeUndefined()
    fireEvent.click(document.querySelector('[data-facet="dates"]'))
    const group = await screen.findByRole('radiogroup', { name: 'Which alarms should the range include?' })
    expect(within(group).getByRole('radio', { name: /Opened in the range/ })).toHaveAttribute('data-state', 'checked')
    fireEvent.click(within(group).getByRole('radio', { name: /Active during the range/ }))
    await waitFor(() => expect(lastList().range).toBe('active'))
    expect(lastList().since).toBeTruthy()
    expect(within(screen.getByRole('group', { name: /Active filters/ })).getByText('Active during this range')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('range=active'), { timeout: 2000 })
  })

  it('B3: kip seçimi yalnız "Tümü"nde çizilir — kapalı görünümün tarih süzgecinde yok', async () => {
    window.history.replaceState({}, '', '/?view=closed')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(lastList()?.resolved).toBe('true'))
    fireEvent.click(document.querySelector('[data-facet="dates"]'))
    await screen.findByText('Last 7 days')
    expect(screen.queryByRole('radiogroup', { name: 'Which alarms should the range include?' })).toBeNull()
  })
})

describe('AlertHistory — telefon (useIsMobile)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    MOBILE = true
    window.history.replaceState({}, '', '/?level=CRITICAL')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, id: 5 }], level_counts: { CRITICAL: 1 }, unacked_total: 0 })
  })
  afterEach(() => { MOBILE = false; window.history.replaceState({}, '', '/') })

  it('arama + "Süzgeçler (n)" düğmesi; süzgeçler alt Sheet içinde', async () => {
    render(<AlertHistory urlSync />)
    const btn = await screen.findByRole('button', { name: /Filters/ })
    expect(within(btn).getByText('1')).toBeInTheDocument()   // etkin süzgeç sayısı
    expect(document.querySelector('[data-facet="level"]')).toBeNull()   // satır içi faset yok
    fireEvent.click(btn)
    const sheet = await screen.findByRole('dialog')
    expect(sheet).toHaveAttribute('data-slot', 'alert-filters-sheet')
    expect(within(sheet).getAllByRole('button').some((b) => b.getAttribute('data-facet') === 'level')).toBe(true)
  })

  it('kapalı görünüm telefonda tablo değil KART', async () => {
    render(<AlertHistory urlSync />)
    await screen.findByText('foo.example.com')
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    expect(document.querySelector('[data-slot="alert-list"] table')).toBeNull()
    expect(document.querySelector('[data-alert-row]').getAttribute('data-slot')).toBe('card')
  })

  it('B3: "Tümü"nde alt Sheet\'in tarih bölümünde kip seçimi var; "Active during the range" range=active gönderir', async () => {
    window.history.replaceState({}, '', '/?view=all&from=2026-09-21')
    render(<AlertHistory urlSync />)
    fireEvent.click(await screen.findByRole('button', { name: /Filters/ }))
    const sheet = await screen.findByRole('dialog')
    const group = within(sheet).getByRole('radiogroup', { name: 'Which alarms should the range include?' })
    fireEvent.click(within(group).getByRole('radio', { name: /Active during the range/ }))
    await waitFor(() => expect(lastList().range).toBe('active'))
    expect(lastList().since).toBeTruthy()
  })
})

/** Günlere göre bölümleme — görünen sayfa içinde (sunucu sıralaması korunur): Bugün / Dün / tarih. */
describe('AlertHistory — gün bölümleri ve klavye', () => {
  const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
  const alertAt = (id, createdAt) => ({ id, domain: `d${id}.example.com`, alert_type: 'HTTP_DOWN', alert_level: 'HIGH',
    acknowledged: false, resolved: false, created_at: createdAt })

  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/') })

  it('bugünkü ve eski alarmlar ayrı gün başlıklarında, sayılarıyla', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 3, page: 0, size: 20,
      data: [alertAt(1, iso(60_000)), alertAt(2, iso(120_000)), alertAt(3, '2026-01-10T08:00:00')] })
    render(<AlertHistory />)
    const heads = await waitFor(() => {
      const h = [...document.querySelectorAll('[data-slot="day-heading"]')]
      if (h.length !== 2) throw new Error('başlıklar henüz yok')
      return h.map((x) => x.textContent)
    })
    expect(heads[0]).toMatch(/^Today\s*2$/)
    expect(heads[1]).toMatch(/2026/)
  })

  it('↓ / ↑ kartlar arasında odağı gezdirir', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 2, page: 0, size: 20,
      data: [alertAt(1, iso(60_000)), alertAt(2, iso(120_000))] })
    render(<AlertHistory />)
    await waitFor(() => expect(document.querySelectorAll('[data-alert-open]')).toHaveLength(2))
    const [first, second] = document.querySelectorAll('[data-alert-open]')
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(second)
    fireEvent.keyDown(second, { key: 'ArrowUp' })
    expect(document.activeElement).toBe(first)
  })
})

/** KART ROZETLERİ — açık kalma (canlı, UTC), eşik vurgusu, tekrar. Saat dilimi notu: damgalar zone'suz UTC. */
describe('AlertHistory — kart rozetleri', () => {
  const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)
  const openAlertAt = (createdAt, extra = {}) => ({ id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: 'CRITICAL',
    acknowledged: false, resolved: false, created_at: createdAt, ...extra })

  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/'); api.admin.getTeams.mockResolvedValue({ success: true, data: [] }) })

  const withAlert = (a) => api.admin.getAlerts.mockResolvedValue({ success: true, data: [a], total: 1, page: 0, size: 20,
    level_counts: { CRITICAL: 1 }, unacked_total: 1, stale_total: 0, stale_hours: 24 })

  it('AÇIK alarmda "ne kadardır açık" gösterilir', async () => {
    withAlert(openAlertAt(hoursAgo(3)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('[data-open-for]')).not.toBeNull())
    expect(container.querySelector('[data-open-for]').textContent).toMatch(/3\s*(sa|h)\b/)
  })

  it('10 dakikalık alarm "9d" (gün sanılan) DEĞİL dakika birimiyle gösterilir', async () => {
    withAlert(openAlertAt(new Date(Date.now() - 10 * 60_000).toISOString().slice(0, 19)))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('[data-open-for]')).not.toBeNull())
    const txt = container.querySelector('[data-open-for]').textContent
    expect(txt).toMatch(/\b(9|10)\s*(dk|min)\b/)
    expect(txt).not.toMatch(/\b\d+\s*d\b/)
  })

  it('EŞİĞİ AŞAN alarm vurgulanır; altındaki vurgulanmaz', async () => {
    withAlert(openAlertAt(hoursAgo(50)))
    const first = render(<AlertHistory />)
    await waitFor(() => expect(first.container.querySelector('[data-open-for]')).not.toBeNull())
    expect(first.container.querySelector('[data-open-for]')).toHaveAttribute('data-stale', 'true')
    expect(first.container.querySelector('[data-open-for]').textContent).toMatch(/2\s*[gd]\b/)
    first.unmount()
    withAlert(openAlertAt(hoursAgo(2)))
    const second = render(<AlertHistory />)
    await waitFor(() => expect(second.container.querySelector('[data-open-for]')).not.toBeNull())
    expect(second.container.querySelector('[data-open-for]')).toHaveAttribute('data-stale', 'false')
  })

  it('TEKRAR rozeti yalnız 2 ve üstünde çıkar', async () => {
    withAlert(openAlertAt(hoursAgo(1), { repeat_count: 1 }))
    const first = render(<AlertHistory />)
    await waitFor(() => expect(first.container.querySelector('[data-open-for]')).not.toBeNull())
    expect(first.container.querySelector('[data-repeat]')).toBeNull()
    first.unmount()
    withAlert(openAlertAt(hoursAgo(1), { repeat_count: 4 }))
    const second = render(<AlertHistory />)
    await waitFor(() => expect(second.container.querySelector('[data-repeat]')).not.toBeNull())
    expect(second.container.querySelector('[data-repeat]').textContent).toMatch(/4/)
  })

  it('created_at yoksa rozet ÇİZİLMEZ — "NaN" ya da boş rozet görünmez', async () => {
    withAlert(openAlertAt(null))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('[data-alert-card]')).not.toBeNull())
    expect(container.querySelector('[data-open-for]')).toBeNull()
  })
})

/** TEMA / SOL ŞERİT SÖZLEŞMESİ — renk sınıftan; kartta sol renk şeridi YOK (2026-09-26), kritik tüm çerçeve. */
describe('AlertHistory — tema sözleşmesi', () => {
  const alertOf = (level, extra = {}) => ({ id: 1, domain: 'a.example.com', alert_type: 'EXPIRY', alert_level: level,
    acknowledged: false, resolved: false, created_at: '2026-08-01T08:00:00', ...extra })

  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/'); api.admin.getTeams.mockResolvedValue({ success: true, data: [] }) })

  const withAlert = (a) => api.admin.getAlerts.mockResolvedValue({ success: true, data: [a], total: 1, page: 0, size: 20 })

  it('seviye rengi SINIFTAN gelir; kartta sol şerit yok, kritik kart TÜM çerçeveli', async () => {
    withAlert(alertOf('CRITICAL'))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('[data-level-badge]')).not.toBeNull())
    const badge = container.querySelector('[data-alert-card] [data-level-badge]')
    expect(badge).toHaveAttribute('data-level', 'critical')
    expect(badge.getAttribute('style')).toBeNull()
    const card = badge.closest('[data-alert-card]')
    expect(card).toHaveAttribute('data-level', 'critical')
    expect(card.className).not.toMatch(/border-l-|before:|shadow-\[inset/)
    expect(card.className).toContain('border-(--severity-critical)')
  })

  it('bilinmeyen seviye de sınıf alır — renksiz/çıplak kalmaz', async () => {
    withAlert(alertOf('SOMETHING_NEW'))
    const { container } = render(<AlertHistory />)
    await waitFor(() => expect(container.querySelector('[data-level-badge]')).not.toBeNull())
    expect(container.querySelector('[data-level-badge]').getAttribute('data-level')).toBe('unknown')
  })

  it('tier rozeti (detay) sınıfla boyanır, satır içi stil yok', async () => {
    withAlert(alertOf('CRITICAL', { resolved: true, resolved_at: '2026-08-02T08:00:00', resolved_by: 'system', cert_tier: 2 }))
    render(<AlertHistory />)
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-alert-row]'))
    await waitFor(() => expect(document.querySelector('[data-tier]')).not.toBeNull())
    const chip = document.querySelector('[data-tier]')
    expect(chip).toHaveAttribute('data-tier', '2')
    expect(chip.getAttribute('style')).toBeNull()
  })

  it('Tekrar Bildir: webhook alıcıları AYRI listelenir ve ayrı çıkarılabilir (A2)', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }] })
    api.admin.previewReNotify.mockResolvedValue({ success: true, data: {
      alert_id: 301,
      recipients: [{ email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' }],
      webhook: { channel_enabled: true, block_reason: null, recipients: [
        { username: 'N00001', display_name: 'Kisi Bir', status: 'PENDING' },
        { username: 'N00002', display_name: 'Kisi Iki', status: 'PENDING' },
        { username: 'N00003', display_name: 'Kisi Uc', status: 'RATE_LIMITED' },
      ] },
    } })
    api.admin.reNotifyAlert.mockResolvedValue({ success: true, data: { recipients_queued: 1 } })

    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: /foo\.example\.com.*Re-Notify/ }))
    await screen.findByText(/confirm recipients/i)
    expect(screen.getByText('Kisi Bir')).toBeDefined()
    expect(screen.getByText('RATE_LIMITED')).toBeDefined()
    const modal = screen.getByRole('dialog')
    expect(Array.from(modal.querySelectorAll('[role="checkbox"]')).some((b) => b.disabled)).toBe(true)
    expect(screen.getByText(/3 recipients selected/i)).toBeDefined()
    fireEvent.click(screen.getByText('Kisi Iki'))
    fireEvent.click(within(modal).getByRole('button', { name: /^send$/i }))
    await waitFor(() => expect(api.admin.reNotifyAlert).toHaveBeenCalledWith(301, { excludeUsernames: ['N00002'] }))
  })

  it('Tekrar Bildir: webhook kanalı kapalıysa SEBEBİ gösterilir, mail yine gönderilebilir (A2)', async () => {
    api.admin.getAlerts.mockResolvedValue({ success: true, total: 1, page: 0, size: 20,
      data: [{ ...closedAlert, id: 301, resolved: false, acknowledged: false }] })
    api.admin.previewReNotify.mockResolvedValue({ success: true, data: {
      alert_id: 301, recipients: [{ email: 'takim-a@example.com', name: 'SY-Takım A', role: null, kind: 'TEAM' }],
      webhook: { channel_enabled: true, block_reason: 'SKIPPED_TEAM_OFF', recipients: [] },
    } })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('foo.example.com')).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: /foo\.example\.com.*Re-Notify/ }))
    await screen.findByText(/confirm recipients/i)
    expect(screen.getByText(/SKIPPED_TEAM_OFF/)).toBeDefined()
    expect(screen.getByText(/1 recipients selected/i)).toBeDefined()
  })
})

// 2026-09-10: "Son Geçerlilik" hesaplanmaz, sunucunun damgaladığı not_after okunur (detay panelinde)
describe('AlertHistory — kapalı alarm son geçerlilik (not_after)', () => {
  beforeEach(() => vi.clearAllMocks())

  async function openClosedDetail(alert) {
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [alert], total: 1, page: 0, size: 20 })
    render(<AlertHistory />)
    pickTab(/Closed/)
    await waitFor(() => expect(document.querySelector('[data-alert-row]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-alert-row]'))
    return screen.findByRole('dialog')
  }

  it('sunucunun not_after damgası çizilir, created_at+days hesabı kullanılmaz', async () => {
    const d = await openClosedDetail({ ...closedAlert, not_after: '2026-09-22T23:59:59' })
    expect(within(d).getByText('2026-09-22T23:59:59')).toBeDefined()
    expect(within(d).queryByText(/2026-06-08/)).toBeNull()
  })

  it('yenilenmiş sertifikada güncel bitiş ikinci rozet olarak gelir', async () => {
    const d = await openClosedDetail({ ...closedAlert, not_after: '2026-09-22T23:59:59', current_not_after: '2026-12-31T23:59:59' })
    expect(within(d).getByText('2026-12-31T23:59:59')).toBeDefined()
    expect(d.querySelector('[data-renewed]')).not.toBeNull()
  })

  it('not_after ile güncel bitiş AYNIYSA ikinci rozet çizilmez', async () => {
    const d = await openClosedDetail({ ...closedAlert, not_after: '2026-09-22T23:59:59', current_not_after: '2026-09-22T23:59:59' })
    expect(d.querySelector('[data-renewed]')).toBeNull()
  })
})

describe('AlertHistory — "neden hâlâ açık?" çipleri (2026-09-12, #16)', () => {
  it('onay/e-posta/push çipleri; push özeti sunucudan; kimseye ulaşmayan alarm kırmızı uyarı', async () => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [
        { ...closedAlert, id: 301, resolved: false, acknowledged: false, notified_contacts: '[]', email_sent_count: 0, email_failed_count: 0 },
        { ...closedAlert, id: 302, domain: 'reached.example.com', resolved: false, acknowledged: true, acknowledged_by: 'ops',
          notified_contacts: JSON.stringify([{ name: 'A', email: 'a@example.com', role: 'owner' }]) },
      ],
      push_summary: { 302: { sent: 2, failed: 1, skipped: 0, other: 0 } },
      total: 2, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('reached.example.com')).toBeDefined())
    const whys = document.querySelectorAll('[data-alert-card] [data-why]')
    expect(whys.length).toBe(2)
    expect(whys[0].textContent).toMatch(/not acknowledged/)
    expect(whys[0].textContent).toMatch(/reached nobody/)
    expect(whys[1].textContent).toMatch(/acknowledged · ops/)
    expect(whys[1].textContent).toMatch(/push: 2 sent · 1 failed/)
    expect(whys[0].textContent).toMatch(/email: none sent/)
    expect(whys[1].textContent).toMatch(/email: 3 sent · 1 failed/)
    expect(whys[1].textContent).not.toMatch(/reached nobody/)
  })

  // 2026-09-29 (prod): sağlıklı Port izlemesinde asılı kalan UYARI alarmı — panel yalnız onay/bildirim gösterdiği için
  // "kimseye ulaşmadı" çipi açık kalma NEDENİ gibi okunuyordu. İlk çip artık kapanış kuralını söyler.
  it('ilk çip kapanış kuralı: otomatik kapanan türde "closes by itself", değişiklik alarmında "only by hand"', async () => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({
      success: true,
      data: [
        { ...closedAlert, id: 401, domain: 'app.example.com', alert_type: 'PORT_DOWN', alert_level: 'WARNING', resolved: false,
          acknowledged: true, acknowledged_by: 'ops', notified_contacts: '[]', email_sent_count: 0, email_failed_count: 0 },
        { ...closedAlert, id: 402, domain: 'dns.example.com', alert_type: 'DNS_CHANGED', alert_level: 'HIGH', resolved: false,
          acknowledged: false, notified_contacts: '[]', email_sent_count: 1, email_failed_count: 0 },
      ],
      total: 2, page: 0, size: 20,
    })
    render(<AlertHistory />)
    await waitFor(() => expect(screen.getByText('dns.example.com')).toBeDefined())
    const whys = document.querySelectorAll('[data-alert-card] [data-why]')
    expect(whys.length).toBe(2)
    const autoChip = whys[0].querySelector('[data-why-close]')
    expect(autoChip.getAttribute('data-auto')).toBe('true')
    expect(autoChip.textContent).toBe('closes by itself once checks pass again')
    expect(autoChip.getAttribute('title')).toMatch(/acknowledging it, or who was notified, makes no difference/)
    // D-3: istisnalar da söylenir (aynı takımın kardeş izlemesi düşükse açık kalır; tür kapalıysa bildirimsiz kapanır)
    expect(autoChip.getAttribute('title')).toMatch(/stays open while another of your team’s monitors .* still failing/)
    expect(autoChip.getAttribute('title')).toMatch(/closes without a notification if alerts for this type are switched off/)
    // Çip ilk sırada: onay/bildirim çiplerinden ÖNCE okunur
    expect(whys[0].querySelectorAll('[data-slot="badge"], [data-why-close]')[0]).toBe(autoChip)
    const manualChip = whys[1].querySelector('[data-why-close]')
    expect(manualChip.getAttribute('data-auto')).toBe('false')
    expect(manualChip.textContent).toBe('closes only when resolved by hand')
  })
})

/**
 * EYLEMLER — sahiplen / çöz GEREKÇE ister (AlertActionNote): gerekçeli pencere (incidents/ActionNoteDialog), en az 3
 * kelime, sunucuya not gider. Sunucu hatası pencerede SATIR İÇİ kalır (pencere kapanmaz, not kaybolmaz, tost yok).
 */
describe('AlertHistory — açık kart eylemleri (gerekçeli)', () => {
  const open = { ...closedAlert, id: 55, resolved: false, acknowledged: false, alert_type: 'HTTP_DOWN', domain: 'act.example.com' }
  const NOTE = 'bilinen sorun takip ediliyor şimdi'

  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open], total: 1, page: 0, size: 20 })
    api.admin.acknowledgeAlert.mockResolvedValue({ success: true, data: { ...open, acknowledged: true, acknowledged_by: 'ops' } })
    api.admin.resolveAlert.mockResolvedValue({ success: true, data: { ...open, resolved: true } })
    api.admin.bulkAlertAction.mockResolvedValue({ success: true, data: { processed: 2, skipped: 0, failed: 0 } })
  })

  async function fillNoteAndConfirm(confirmName, note = NOTE) {
    const dlg = await screen.findByRole('dialog')
    const box = within(dlg).getByRole('textbox', { name: /^Reason/ })
    // Not yazılmadan gönderim sunucuya GİTMEZ — eksikler kırmızıya döner
    fireEvent.click(within(dlg).getByRole('button', { name: confirmName }))
    expect([...dlg.querySelectorAll('[data-slot="action-note-rule"]')].map((r) => r.getAttribute('data-state'))).toEqual(['error', 'error'])
    fireEvent.change(box, { target: { value: note } })
    fireEvent.click(within(dlg).getByRole('button', { name: confirmName }))
    return dlg
  }

  it('Onayla → gerekçeli pencere (alarm bağlamı) → acknowledgeAlert(id, not) → pencere kapanır', async () => {
    render(<AlertHistory />)
    fireEvent.click(await screen.findByRole('button', { name: /act\.example\.com.*— Acknowledge$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAccessibleName('Acknowledge alert')
    expect(dlg.querySelector('[data-slot="action-note-target"]')).toHaveTextContent('act.example.com')
    await fillNoteAndConfirm('Take ownership')
    await waitFor(() => expect(api.admin.acknowledgeAlert).toHaveBeenCalledWith(55, NOTE))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.acknowledgeAlert).toHaveBeenCalledTimes(1)
  })

  it('Çöz → gerekçeli pencere → resolveAlert(id, not); iptal edilirse istek GİTMEZ', async () => {
    render(<AlertHistory />)
    fireEvent.click(await screen.findByRole('button', { name: /act\.example\.com.*— Resolve$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAccessibleName('Resolve alert')
    fireEvent.click(within(dlg).getByRole('button', { name: /Cancel|İptal/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.resolveAlert).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /act\.example\.com.*— Resolve$/ }))
    await fillNoteAndConfirm('Mark as resolved')
    await waitFor(() => expect(api.admin.resolveAlert).toHaveBeenCalledWith(55, NOTE))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sunucu reddederse (403) hata PENCEREDE: açık kalır, not duruyor; düzeltip yeniden denenince gider', async () => {
    api.admin.acknowledgeAlert
      .mockResolvedValueOnce({ success: false, error: 'Bu alarm başka bir takıma ait' })
      .mockResolvedValueOnce({ success: true, data: { ...open, acknowledged: true } })
    render(<AlertHistory />)
    fireEvent.click(await screen.findByRole('button', { name: /act\.example\.com.*— Acknowledge$/ }))
    const dlg = await fillNoteAndConfirm('Take ownership')
    expect(await within(dlg).findByRole('alert')).toHaveTextContent('Bu alarm başka bir takıma ait')
    expect(screen.getByRole('dialog')).toBe(dlg)
    expect(within(dlg).getByRole('textbox', { name: /^Reason/ })).toHaveValue(NOTE)
    expect(screen.getAllByText('Bu alarm başka bir takıma ait')).toHaveLength(1)   // ayrıca tost YOK
    fireEvent.click(within(dlg).getByRole('button', { name: 'Take ownership' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.acknowledgeAlert).toHaveBeenCalledTimes(2)
  })

  it('toplu sahiplen: seçilenlerin özeti (sayı + önem) → bulkAlertAction(acknowledge, ids, not); seçim temizlenir', async () => {
    const second = { ...open, id: 56, domain: 'act2.example.com', alert_level: 'WARNING' }
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open, second], total: 2, page: 0, size: 20 })
    render(<AlertHistory />)
    await screen.findByText('act2.example.com')
    fireEvent.click(screen.getByRole('checkbox', { name: /act\.example\.com.*select this alert/i }))
    fireEvent.click(screen.getByRole('checkbox', { name: /act2\.example\.com.*select this alert/i }))
    fireEvent.click(within(await screen.findByTestId('alh-bulk-actions')).getByRole('button', { name: /^Acknowledge$/ }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveAccessibleName('Acknowledge 2 alerts')
    expect(within(dlg).getByText('2 alerts selected')).toBeInTheDocument()
    expect([...dlg.querySelectorAll('[data-slot="action-note-mix"]')].map((m) => m.getAttribute('data-level'))).toEqual(['CRITICAL', 'WARNING'])
    await fillNoteAndConfirm('Acknowledge 2 alerts')
    await waitFor(() => expect(api.admin.bulkAlertAction).toHaveBeenCalledWith('acknowledge', [55, 56], NOTE))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(screen.queryByTestId('alh-bulk-actions')).toBeNull())
  })

  it('toplu çöz: aynı pencere çöz tonunda → bulkAlertAction(resolve, ids, not); sunucu hatası pencerede kalır', async () => {
    const second = { ...open, id: 56, domain: 'act2.example.com' }
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open, second], total: 2, page: 0, size: 20 })
    api.admin.bulkAlertAction.mockResolvedValueOnce({ success: false, error: 'Gerekçe notu zorunlu' })
    render(<AlertHistory />)
    await screen.findByText('act2.example.com')
    fireEvent.click(screen.getByRole('checkbox', { name: /act\.example\.com.*select this alert/i }))
    fireEvent.click(screen.getByRole('checkbox', { name: /act2\.example\.com.*select this alert/i }))
    fireEvent.click(within(await screen.findByTestId('alh-bulk-actions')).getByRole('button', { name: /^Resolve$/ }))
    const dlg = await fillNoteAndConfirm('Resolve 2 alerts')
    expect(dlg).toHaveAttribute('data-action', 'resolve')
    await waitFor(() => expect(api.admin.bulkAlertAction).toHaveBeenCalledWith('resolve', [55, 56], NOTE))
    expect(await within(dlg).findByRole('alert')).toHaveTextContent('Gerekçe notu zorunlu')
    expect(screen.getByTestId('alh-bulk-actions')).toBeInTheDocument()        // seçim korunur
  })

  // Regresyon taraması FE2: A+B seçiliyken A karttan çözülünce liste yenilenir ama seçim sıfırlanmıyordu → toplu "Çöz"
  // penceresi yalnız B'yi gösterip [A,B] gönderiyor, kullanıcı "1 başarısız" tostu görüyordu.
  it('karttan tekli çöz sonrası seçim listede KALANLARA budanır; toplu çöz yalnız pencerenin gösterdiğini gönderir', async () => {
    const second = { ...open, id: 56, domain: 'act2.example.com' }
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open, second], total: 2, page: 0, size: 20 })
    api.admin.resolveAlert.mockImplementation(async () => {
      // çözülen uyarı AÇIK listeden düşer (refreshAll'un yeni yanıtı)
      api.admin.getAlerts.mockResolvedValue({ success: true, data: [second], total: 1, page: 0, size: 20 })
      return { success: true, data: { ...open, resolved: true } }
    })
    render(<AlertHistory />)
    await screen.findByText('act2.example.com')
    fireEvent.click(screen.getByRole('checkbox', { name: /act\.example\.com.*select this alert/i }))
    fireEvent.click(screen.getByRole('checkbox', { name: /act2\.example\.com.*select this alert/i }))
    expect(await screen.findByText(/^2 selected/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /act\.example\.com.*— Resolve$/ }))
    await fillNoteAndConfirm('Mark as resolved')
    await waitFor(() => expect(api.admin.resolveAlert).toHaveBeenCalledWith(55, NOTE))
    await waitFor(() => expect(screen.queryByText('act.example.com')).toBeNull())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByText(/^1 selected/i)).toBeInTheDocument()   // sayaç listede kalanı sayar

    fireEvent.click(within(screen.getByTestId('alh-bulk-actions')).getByRole('button', { name: /^Resolve$/ }))
    const dlg = await fillNoteAndConfirm('Mark as resolved')
    expect(dlg.querySelector('[data-slot="action-note-target"]')).toHaveTextContent('act2.example.com')
    await waitFor(() => expect(api.admin.bulkAlertAction).toHaveBeenCalledWith('resolve', [56], NOTE))
  })
})

/**
 * DETAY — kartın başlığı (stretched button) detayı açar: durum şeridi, temel bilgiler, zaman çizelgesi (açılış → e-posta
 * → push → sahiplenme → çözüm) ve bildirimler (mail önizlemesi SANDBOX iframe'de, push partileri).
 */
describe('AlertHistory — detay paneli', () => {
  const open = { id: 77, domain: 'det.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', resolved: false,
    acknowledged: true, acknowledged_by: 'ops', acknowledged_at: '2026-09-20T10:05:00', created_at: '2026-09-20T10:00:00',
    message: 'HTTP 503' }

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [open], total: 1, page: 0, size: 20 })
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [
      { id: 1, trigger: 'INITIAL', recipient_name: 'Ayşe Yılmaz', recipient_email: 'ayse@example.com', email_status: 'SENT',
        sent_at: '2026-09-20T10:01:00', subject: 'KRİTİK: det.example.com', message: '<html><head></head><body><p>alarm</p></body></html>' },
    ] })
    api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [
      { id: 9, username: 'N00001', display_name: 'Kişi Bir', trigger: 'OPEN', status: 'SENT', message: 'push metni', sent_at: '2026-09-20T10:02:00' },
    ] })
  })

  it('kart başlığı Sheet açar; zaman çizelgesi olayları sıralı; mail önizlemesi sandbox iframe', async () => {
    render(<AlertHistory urlSync />)
    fireEvent.click(await screen.findByRole('button', { name: /det\.example\.com.*open details/ }))
    const sheet = await screen.findByRole('dialog')
    expect(sheet).toHaveAttribute('data-slot', 'alert-detail')
    expect(within(sheet).getByRole('heading', { name: /Alert #77/ })).toBeInTheDocument()
    await waitFor(() => expect([...sheet.querySelectorAll('[data-slot="timeline-event"]')].map((e) => e.getAttribute('data-kind')))
      .toEqual(['opened', 'mail', 'push', 'acknowledged']))

    const [emailSection] = sheet.querySelectorAll('[data-slot="accordion-trigger"]')
    fireEvent.click(emailSection)
    fireEvent.click(await waitFor(() => sheet.querySelector('[data-notif-card="initial"] [data-notif-head]')))
    const frame = await waitFor(() => { const f = sheet.querySelector('iframe'); if (!f) throw new Error('önizleme yok'); return f })
    expect(frame.getAttribute('sandbox')).toBe(MAIL_PREVIEW_SANDBOX)
    expect(frame.getAttribute('srcdoc')).toContain('<base target="_blank">')
  })

  it('detay Kapat ile kapanır; eylem düğmeleri (Çöz, Tekrar Bildir, İzlemeyi aç, Bağlantı) var', async () => {
    render(<AlertHistory urlSync />)
    fireEvent.click(await screen.findByRole('button', { name: /det\.example\.com.*open details/ }))
    const sheet = await screen.findByRole('dialog')
    const actions = sheet.querySelector('[data-slot="alert-detail-actions"]')
    expect(within(actions).getByRole('button', { name: /^Resolve$/ })).toBeInTheDocument()
    expect(within(actions).queryByRole('button', { name: /^Acknowledge$/ })).toBeNull()   // zaten sahiplenilmiş
    expect(within(actions).getByRole('link', { name: /Open monitor/ })).toHaveAttribute('href', '?tab=http&q=det.example.com')
    expect(within(actions).getByRole('button', { name: /Copy link/ })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: /^Close$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

// ── Derin bağlantı regresyonu (2026-09-26, sayfalama standardı) ─────────────
describe('AlertHistory — derin bağlantı sayfası', () => {
  it('?page=3&ps=25 → liste istekleri page=2 (0-tabanlı) size=25 kalır, 3. sayfa etkin, adres korunur', async () => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yok */ }
    window.history.replaceState({}, '', '/?tab=alerthistory&page=3&ps=25')
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [{ ...closedAlert, resolved: false, id: 7 }], total: 200, page: 2, size: 25 })
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(api.admin.getAlerts.mock.calls[0][0]).toMatchObject({ page: 2, size: 25 })
    await screen.findByRole('navigation', { name: /Sayfalama|Pagination/ })
    await new Promise((r) => setTimeout(r, 400))
    for (const args of listCalls()) expect(args).toMatchObject({ page: 2 })
    expect(screen.getByRole('button', { name: /^(Sayfa|Page) 3$/ })).toHaveAttribute('aria-current', 'page')
    const q = new URLSearchParams(window.location.search)
    expect(q.get('page')).toBe('3')
    expect(q.get('ps')).toBe('25')
    window.history.replaceState({}, '', '/')
  })
})
