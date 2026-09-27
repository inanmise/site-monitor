import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import IncidentsPage from '../components/IncidentsPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

// Telefon/masaüstü yapı farkı hook'tan (useIsMobile): testte deterministik anahtar.
let MOBILE = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => MOBILE }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      incidents: {
        list:          vi.fn(),
        get:           vi.fn(),
        comments:      vi.fn(),
        addComment:    vi.fn(),
        deleteComment: vi.fn(),
        remove:        vi.fn(),
      },
    },
    admin: {
      acknowledgeAlert:      vi.fn(),
      resolveAlert:          vi.fn(),
      getAlertNotifications: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const incident = {
  id: 1, status: 'ongoing',
  monitor: { name: 'https://x.example.com', type: 'http', tab: 'http', monitor_id: 9 },
  root_cause: { code: '500', category: 'server_error' }, comment_count: 2,
  alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', started_at: '2026-07-10T10:00:00',
  resolved_at: null, resolved_by: null, acknowledged: false, domain: 'https://x.example.com', message: 'HTTP 500',
  team_id: 5, team_name: 'Takım A',
}
const acked = { ...incident, id: 2, acknowledged: true, alert_level: 'HIGH', monitor: { ...incident.monitor, name: 'api.example.com', monitor_id: 10 }, comment_count: 1, team_id: null, team_name: null }
const resolved = { ...incident, id: 3, status: 'resolved', alert_level: 'WARNING', resolved_at: '2026-07-10T11:00:00', resolved_by: 'system',
  monitor: { ...incident.monitor, name: 'cdn.example.net', monitor_id: 11 }, comment_count: 0, team_id: 6, team_name: 'Takım B' }
const cert = {
  ...incident, id: 4, alert_type: 'EXPIRY', domain: 'cert.example.com', alert_level: 'HIGH',
  monitor: { name: 'cert.example.com', type: 'cert', tab: 'dashboard', monitor_id: null },
  root_cause: { code: 'EXPIRY', category: 'expiry' },
}

/**
 * Sayfa mount'ta ÜÇ liste isteği atar: sayfa listesi, açık küme örneği (status=ongoing&size=200) ve son 24 saat
 * çözülen toplamı (status=resolved&size=1). Parametreye göre ayrıştırılır; sayfa listesi çağrıları `calls`a düşer.
 */
function mockList({ rows = [], total = rows.length, typeCounts = {}, open = null, resolved24 = 0 } = {}) {
  const openRows = open ?? rows.filter(r => r.status === 'ongoing')
  api.monitoring.incidents.list.mockImplementation((p = {}) => {
    if (p.status === 'ongoing' && Number(p.size) === 200) return Promise.resolve({ success: true, data: openRows, total: openRows.length, type_counts: {} })
    if (p.status === 'resolved' && Number(p.size) === 1) return Promise.resolve({ success: true, data: [], total: resolved24, type_counts: {} })
    return Promise.resolve({ success: true, data: rows, total, type_counts: typeCounts })
  })
}
/** Yalnız SAYFA listesi çağrıları (özet istekleri hariç). */
const pageCalls = () => api.monitoring.incidents.list.mock.calls.map(c => c[0]).filter(p => !(p?.status === 'ongoing' && Number(p?.size) === 200) && !(p?.status === 'resolved' && Number(p?.size) === 1))

const openDetail = async (name = 'https://x.example.com') => {
  fireEvent.click(await screen.findByRole('button', { name: new RegExp(`Open incident ${name}|${name} olayını aç`) }))
  return screen.findByRole('dialog', { name: /Incident #|Olay #/ })
}
/** Gerekçe penceresini (useDialog note) doldurup onaylar. */
async function confirmNote(titleRe, confirmRe, note = 'known issue being tracked') {
  const dlg = await screen.findByRole('dialog', { name: titleRe })
  fireEvent.change(within(dlg).getByRole('textbox'), { target: { value: note } })
  fireEvent.click(within(dlg).getByRole('button', { name: confirmRe }))
}

describe('IncidentsPage', () => {
  beforeEach(() => { vi.clearAllMocks(); MOBILE = false; localStorage.clear(); api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] }); api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [] }) })

  it('hiç olay yokken olumlu "her şey yolunda" boş durumu (success tonlu StatusBlock)', async () => {
    mockList({ rows: [], total: 0 })
    render(<IncidentsPage systemRole="ADMIN" />)
    expect(await screen.findByText(/All clear|Her şey yolunda/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="empty"][data-tone="success"]')).not.toBeNull()
  })

  it('süzgeçle eşleşen olay yokken nötr boş durum + "Süzgeçleri temizle" eylemi', async () => {
    mockList({ rows: [], total: 0 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText(/All clear|Her şey yolunda/)
    fireEvent.change(screen.getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'resolved' } })
    expect(await screen.findByText(/No incidents match|eşleşen olay yok/)).toBeInTheDocument()
    const empty = document.querySelector('[data-slot="empty"][data-tone="neutral"]')
    expect(empty).not.toBeNull()
    fireEvent.click(within(empty).getByRole('button', { name: /Clear filters|Filtreleri temizle/ }))
    expect(await screen.findByText(/All clear|Her şey yolunda/)).toBeInTheDocument()
  })

  it('ilk yüklemede iskelet, sonra pano: üç şerit; olay kartı kök neden kodu + izleme + yorum sayısıyla doğru şeritte', async () => {
    // Mount üç liste isteği atar (sayfa + iki özet); hepsi bekletilir, sonra birlikte çözülür.
    const pending = []
    api.monitoring.incidents.list.mockImplementation(() => new Promise(r => { pending.push(r) }))
    render(<IncidentsPage systemRole="ADMIN" />)
    expect(document.querySelector('[data-slot="incidents-skeleton"]')).not.toBeNull()
    await act(async () => { for (const r of pending) r({ success: true, data: [incident, acked, resolved], total: 3, type_counts: { HTTP_DOWN: 3 } }) })
    expect(await screen.findByText('https://x.example.com')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="incidents-skeleton"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="incident-lane"]')).toHaveLength(3)
    const lane = (l) => document.querySelector(`[data-slot="incident-lane"][data-lane="${l}"]`)
    expect(within(lane('open')).getByText('https://x.example.com')).toBeInTheDocument()
    expect(within(lane('ack')).getByText('api.example.com')).toBeInTheDocument()
    expect(within(lane('resolved')).getByText('cdn.example.net')).toBeInTheDocument()
    expect(within(lane('open')).getByText(/2 comments|2 yorum/)).toBeInTheDocument()
    expect(within(lane('ack')).getByText(/^(1 comment|1 yorum)$/)).toBeInTheDocument()
    expect(screen.queryByText(/1 comments/)).toBeNull()
    // Kart sol renk şeridi taşımaz (kullanıcı kuralı): border-l-* / before: yok
    for (const card of document.querySelectorAll('[data-slot="incident-card"]')) expect(card.className).not.toMatch(/border-l-|before:/)
  })

  it('özet kartları sunucu sayılarından: Açık = açık toplam, Onaylı/Kritik örnekten, Çözüldü (24 sa) = ayrı toplam; kart tıklaması sunucu süzgeci', async () => {
    mockList({ rows: [incident], total: 1, open: [incident, acked, { ...acked, id: 7, alert_level: 'CRITICAL' }], resolved24: 4 })
    render(<IncidentsPage systemRole="ADMIN" teamId={5} />)
    const tile = async (re) => (await screen.findByRole('button', { name: re })).closest('[data-slot="stat-item"]')
    expect(within(await tile(/Filter: Open|Açık filtrele/)).getByText('3')).toBeInTheDocument()
    expect(within(await tile(/Filter: Acknowledged|Onaylı filtrele/)).getByText('2')).toBeInTheDocument()
    expect(within(await tile(/Filter: Critical|Kritik filtrele/)).getByText('2')).toBeInTheDocument()
    expect(within(await tile(/Filter: Resolved \(24 h\)|Çözüldü \(24 sa\) filtrele/)).getByText('4')).toBeInTheDocument()
    expect(within(await tile(/Filter: My team|Takımım filtrele/)).getByText('1')).toBeInTheDocument()
    expect(screen.getByText(/3 open · 2 acknowledged · 4 resolved|3 açık · 2 onaylı · son 24 saatte 4 çözüldü/)).toBeInTheDocument()

    fireEvent.click(await tile(/Filter: Open|Açık filtrele/))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: 'ongoing', page: 0 })))
    expect(screen.getByRole('button', { name: /^(Clear filter|Filtreyi kaldır)$/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: /^(Clear filter|Filtreyi kaldır)$/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: '' })))
  })

  it('"Kritik" kartı istemci süzgeci: yalnız CRITICAL satırlar kalır ve "yüklenen sayfaya uygulanır" notu görünür', async () => {
    mockList({ rows: [incident, acked], total: 2 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('api.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Filter: Critical|Kritik filtrele/ }))
    await waitFor(() => expect(screen.queryByText('api.example.com')).toBeNull())
    expect(screen.getByText('https://x.example.com')).toBeInTheDocument()
    expect(screen.getByText(/showing 1 of 2 rows|2 satırın 1 tanesi/)).toBeInTheDocument()
  })

  it('Pano | Liste anahtarı: liste tablo çizer (sütun başlıkları, aria-sort), seçim kalıcı; sıralama başlığı sunucuya sort/dir gönderir', async () => {
    mockList({ rows: [incident, resolved], total: 2 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('https://x.example.com')
    expect(document.querySelector('table')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^(List|Liste)$/ }))
    expect(await screen.findByRole('columnheader', { name: /Takım|Team/ })).toBeInTheDocument()
    expect(localStorage.getItem('sm.incidents.view')).toBe('list')
    const started = screen.getByRole('columnheader', { name: /Started|Başlangıç/ })
    expect(started).toHaveAttribute('aria-sort', 'none')
    fireEvent.click(within(started).getByRole('button'))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ sort: 'started', dir: 'desc' })))
    expect(screen.getByRole('columnheader', { name: /Started|Başlangıç/ })).toHaveAttribute('aria-sort', 'descending')
    fireEvent.click(within(screen.getByRole('columnheader', { name: /Started|Başlangıç/ })).getByRole('button'))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ sort: 'started', dir: 'asc' })))
    // Takım sütunu: takımlı satırda TeamBadge, çözülmüş satırda "Çözüldü:" alt satırı
    expect(document.querySelector('tbody tr [data-slot="team-badge"]')).not.toBeNull()
    expect(screen.getByText(/Resolved:|Çözüldü:/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^(Board|Kanban)$/ }))
    await waitFor(() => expect(document.querySelector('table')).toBeNull())
  })

  it('süzgeçler: durum + arama sunucuya gider, etkin çipler listelenir, × tek süzgeci kaldırır, "Temizle" hepsini', async () => {
    mockList({ rows: [incident], total: 1, typeCounts: { HTTP_DOWN: 1 } })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('500')
    fireEvent.change(screen.getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'ongoing' } })
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: 'ongoing' })))
    const search = screen.getByRole('searchbox', { name: /Search monitor|Monitör ara/ })
    fireEvent.change(search, { target: { value: 'x.example' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: 'ongoing', q: 'x.example' })))
    const chips = screen.getByRole('group', { name: /Active filters|Etkin süzgeçler/ })
    expect(within(chips).getByText(/Search: x\.example|Arama: x\.example/)).toBeInTheDocument()
    fireEvent.click(within(chips).getByRole('button', { name: /Remove filter: (Search|Arama)/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: 'ongoing', q: '' })))
    // Kök neden çipi (canlı sayı) → rootCause
    fireEvent.click(screen.getByRole('button', { name: /HTTP\/Website (Down|Erişilemez)/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ rootCause: 'HTTP_DOWN' })))
    fireEvent.click(within(screen.getByRole('group', { name: /Active filters|Etkin süzgeçler/ })).getByRole('button', { name: /Clear filters|Filtreleri temizle/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: '', rootCause: '', q: '' })))
  })

  it('detay çekmecesi: kart başlığı açar; zaman çizelgesi açılış + bildirim + yorum; yorum silme adı yorumu ayırır; Ctrl+Enter yorum gönderir', async () => {
    mockList({ rows: [incident], total: 1 })
    api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [{ id: 3, author_name: 'Ayse Y', author_username: 'ayse', body: 'yeniden başlatıldı', created_at: '2026-07-10T10:05:00' }] })
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [{ id: 8, sent_at: '2026-07-10T10:01:00', recipient_name: 'Takım A', trigger: 'NEW', email_status: 'SENT' }] })
    api.monitoring.incidents.addComment.mockResolvedValue({ success: true })
    render(<IncidentsPage systemRole="ADMIN" />)
    const dlg = await openDetail()
    expect(within(dlg).getByText(/Incident #1|Olay #1/)).toBeInTheDocument()
    expect(await within(dlg).findByText('yeniden başlatıldı')).toBeInTheDocument()
    const kinds = [...dlg.querySelectorAll('[data-slot="timeline-event"]')].map(li => li.getAttribute('data-kind'))
    expect(kinds).toEqual(['opened', 'notified', 'comment'])
    expect(within(dlg).getByText(/Notification sent to Takım A|Takım A alıcısına/)).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /^Ayse Y · .+ — (Sil|Delete)$/ })).toBeInTheDocument()
    // Temel bilgiler: izleme bağlantısı kaynağa gider, kök neden, takım rozeti
    expect(within(dlg).getByRole('link', { name: /https:\/\/x\.example\.com/ })).toHaveAttribute('href', '?tab=http&monitor=9')
    expect(dlg.querySelector('[data-slot="incident-facts"] [data-slot="team-badge"]')).not.toBeNull()
    const box = within(dlg).getByRole('textbox', { name: /Write a comment|Yorum yaz/ })
    fireEvent.change(box, { target: { value: 'kontrol edildi' } })
    fireEvent.keyDown(box, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.monitoring.incidents.addComment).toHaveBeenCalledWith(1, 'kontrol edildi'))
    // Yorum sayısı kartta +1 (iyimser)
    await waitFor(() => expect(screen.getAllByText(/3 comments|3 yorum/).length).toBeGreaterThan(0))
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Close|Kapat)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Incident #|Olay #/ })).toBeNull())
  })

  it('Onayla: gerekçe penceresi → acknowledgeAlert(id, not) → "Onaylandı" rozeti (iyimser); Çöz → resolveAlert → durum Çözüldü', async () => {
    mockList({ rows: [incident], total: 1 })
    api.admin.acknowledgeAlert.mockResolvedValue({ success: true, data: { acknowledged_by: 'Demo', acknowledged_at: '2026-07-10T10:20:00' } })
    api.admin.resolveAlert.mockResolvedValue({ success: true, data: { resolved_at: '2026-07-10T10:30:00', resolved_by: 'Demo' } })
    render(<IncidentsPage systemRole="USER" />)
    const dlg = await openDetail()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Acknowledge|Onayla)$/ }))
    await confirmNote(/Acknowledge incident|Olayı onayla/, /^(Acknowledge|Onayla)$/)
    await waitFor(() => expect(api.admin.acknowledgeAlert).toHaveBeenCalledWith(1, 'known issue being tracked'))
    expect(await within(dlg).findByText(/Acknowledged by Demo|Demo onayladı/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="incident-ack"]')).not.toBeNull()
    expect(within(dlg).queryByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeNull()

    fireEvent.click(within(dlg).getByRole('button', { name: /^(Resolve|Çöz)$/ }))
    await confirmNote(/Resolve incident|Olayı çöz/, /^(Resolve|Çöz)$/, 'fix deployed and verified')
    await waitFor(() => expect(api.admin.resolveAlert).toHaveBeenCalledWith(1, 'fix deployed and verified'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="incident-status"][data-status="resolved"]')).not.toBeNull())
    expect(within(dlg).queryByRole('button', { name: /^(Resolve|Çöz)$/ })).toBeNull()
    // USER: silme yok
    expect(within(dlg).queryByRole('button', { name: /— (Sil|Delete)$/ })).toBeNull()
  })

  it('onay başarısızsa iyimser güncelleme geri alınır ve hata bildirimi çıkar', async () => {
    mockList({ rows: [incident], total: 1 })
    api.admin.acknowledgeAlert.mockResolvedValue({ success: false, error: 'yetki yok' })
    render(<IncidentsPage systemRole="USER" />)
    const dlg = await openDetail()
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Acknowledge|Onayla)$/ }))
    await confirmNote(/Acknowledge incident|Olayı onayla/, /^(Acknowledge|Onayla)$/)
    await waitFor(() => expect(api.admin.acknowledgeAlert).toHaveBeenCalled())
    await waitFor(() => expect(dlg.querySelector('[data-slot="incident-ack"]')).toBeNull())
    expect(within(dlg).getByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeInTheDocument()
    expect((await screen.findAllByText('yetki yok')).length).toBeGreaterThan(0)
  })

  it('ADMIN: detaydaki Sil → onay → remove(id) → çekmece kapanır; ad olayı ayırır', async () => {
    mockList({ rows: [incident], total: 1 })
    api.monitoring.incidents.remove.mockResolvedValue({ success: true })
    render(<IncidentsPage systemRole="ADMIN" />)
    const dlg = await openDetail()
    fireEvent.click(within(dlg).getByRole('button', { name: /^https:\/\/x\.example\.com · .+ — (Sil|Delete)$/ }))
    const confirm = await screen.findByRole('dialog', { name: /^(Delete|Sil)$/ })
    fireEvent.click(within(confirm).getByRole('button', { name: /^(Delete|Sil)$/ }))
    await waitFor(() => expect(api.monitoring.incidents.remove).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Incident #|Olay #/ })).toBeNull())
  })

  it('derin bağlantı ?incident=<id>&action=comment: sayfada olmayan olay tekil uçtan gelir, çekmece açılır, yazıcı odaklanır, action URL\'den silinir', async () => {
    window.history.replaceState({}, '', '/?tab=incidents&incident=42&action=comment')
    mockList({ rows: [incident], total: 1 })
    api.monitoring.incidents.get.mockResolvedValue({ success: true, data: { ...cert, id: 42 } })
    render(<IncidentsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.incidents.get).toHaveBeenCalledWith('42'))
    const dlg = await screen.findByRole('dialog', { name: /Incident #42|Olay #42/ })
    await waitFor(() => expect(document.activeElement).toBe(within(dlg).getByRole('textbox', { name: /Write a comment|Yorum yaz/ })))
    expect(within(dlg).getByRole('link', { name: /cert\.example\.com/ })).toHaveAttribute('href', '?tab=dashboard&domain=cert.example.com')
    expect(window.location.search).not.toContain('action=')
    expect(window.location.search).toContain('incident=42')
  })

  it('sayfa 2 iken filtre değişince TEK yükleme (page 0) yapar — çift-fetch/bayat yarış yok (M4)', async () => {
    mockList({ rows: [incident], total: 100, typeCounts: { HTTP_DOWN: 1 } })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('500')
    fireEvent.click(await screen.findByRole('button', { name: /^(Sonraki|Next)$/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ page: 1 })))
    api.monitoring.incidents.list.mockClear()
    fireEvent.change(screen.getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'ongoing' } })
    await waitFor(() => expect(pageCalls()).toHaveLength(1))
    expect(pageCalls()[0]).toEqual(expect.objectContaining({ page: 0, status: 'ongoing' }))
  })

  it('sayfa boyutu standart ön ayardan (varsayılan 50, [25,50,100,200]); boyut değişince page 0', async () => {
    mockList({ rows: [incident], total: 300, typeCounts: { HTTP_DOWN: 1 } })
    render(<IncidentsPage systemRole="ADMIN" />)
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ page: 0, size: 50 })))
    fireEvent.click(await screen.findByRole('button', { name: /^(Sonraki|Next)$/ }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ page: 1, size: 50 })))
    fireEvent.click(screen.getByRole('combobox', { name: /Sayfa başına|Per page/ }))
    expect(screen.getAllByRole('option').map(o => o.textContent)).toEqual(['25', '50', '100', '200'])
    fireEvent.click(screen.getByRole('option', { name: '100' }))
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ page: 0, size: 100 })))
  })

  it('takım rozetine tıklamak detayı AÇMAZ (örtünün üstünde, yayılım durur)', async () => {
    mockList({ rows: [incident], total: 1 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('500')
    fireEvent.click(document.querySelector('[data-slot="incident-card"] [data-slot="team-badge"]'))
    expect(screen.queryByRole('dialog', { name: /Incident #|Olay #/ })).toBeNull()
  })

  it('yükleme hatası: danger AlertBanner + "Tekrar dene" yeniden ister', async () => {
    api.monitoring.incidents.list.mockRejectedValue(new Error('ağ koptu'))
    render(<IncidentsPage systemRole="ADMIN" />)
    const banner = await screen.findByRole('alert')
    expect(banner).toHaveAttribute('data-tone', 'danger')
    expect(within(banner).getByText('ağ koptu')).toBeInTheDocument()
    mockList({ rows: [incident], total: 1 })
    fireEvent.click(within(banner).getByRole('button', { name: /Try again|Tekrar dene/ }))
    expect(await screen.findByText('500')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('başlık "Olaylar/Incidents" — Olay Geçmişi ile i18n çakışması yok (H1)', async () => {
    mockList({ rows: [], total: 0 })
    render(<IncidentsPage systemRole="ADMIN" />)
    expect(await screen.findByText(/^Olaylar$|^Incidents$/)).toBeInTheDocument()
    expect(screen.queryByText(/Olay & Hata Geçmişi|Incident & Error History/)).not.toBeInTheDocument()
  })
})

describe('IncidentsPage — telefon (useIsMobile)', () => {
  beforeEach(() => { vi.clearAllMocks(); MOBILE = true; localStorage.clear(); api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] }); api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [] }) })
  afterEach(() => { MOBILE = false })

  it('pano şeritleri sekme olur (sayılı), liste görünümü tablo yerine kart yığını; kartlar durum rozeti taşır', async () => {
    mockList({ rows: [incident, acked, resolved], total: 3 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('500')
    expect(document.querySelector('[data-slot="incident-board"][data-layout="tabs"]')).not.toBeNull()
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map(x => x.textContent)).toEqual([expect.stringMatching(/Open|Açık/), expect.stringMatching(/In progress|çalışılıyor/), expect.stringMatching(/Resolved|Çözüldü/)])
    expect(tabs[0].textContent).toMatch(/1$/)
    fireEvent.click(screen.getByRole('button', { name: /^(List|Liste)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="incident-list"]')).not.toBeNull())
    expect(document.querySelector('table')).toBeNull()
    expect(document.querySelectorAll('[data-slot="incident-card"] [data-slot="incident-status"]')).toHaveLength(3)
  })

  it('süzgeçler alt Sheet\'te: "Süzgeçler" düğmesi açar, durum seçici oradadır ve etkin süzgeç sayısı rozette', async () => {
    mockList({ rows: [incident], total: 1 })
    render(<IncidentsPage systemRole="ADMIN" />)
    await screen.findByText('500')
    expect(screen.queryByRole('combobox', { name: /^(Durum|Status)$/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^(Filters|Süzgeçler)/ }))
    const sheetEl = await screen.findByRole('dialog', { name: /Filters|Süzgeçler/ })
    fireEvent.change(within(sheetEl).getByRole('combobox', { name: /^(Durum|Status)$/ }), { target: { value: 'resolved' } })
    await waitFor(() => expect(pageCalls().at(-1)).toEqual(expect.objectContaining({ status: 'resolved' })))
    fireEvent.click(within(sheetEl).getByRole('button', { name: /^(Apply|Uygula)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /Filters|Süzgeçler/ })).toBeNull())
    expect(document.querySelector('[data-slot="filter-count"]').textContent).toBe('1')
  })

  it('detay çekmecesi telefonda da açılır ve eylemler (Onayla/Çöz/İzlemeyi aç) ulaşılabilir', async () => {
    mockList({ rows: [incident], total: 1 })
    render(<IncidentsPage systemRole="ADMIN" />)
    fireEvent.click(await screen.findByRole('button', { name: /Open incident https:\/\/x\.example\.com|https:\/\/x\.example\.com olayını aç/ }))
    const dlg = await screen.findByRole('dialog', { name: /Incident #1|Olay #1/ })
    expect(within(dlg).getByRole('button', { name: /^(Acknowledge|Onayla)$/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: /^(Resolve|Çöz)$/ })).toBeInTheDocument()
    expect(within(dlg).getByRole('link', { name: /Open monitor|İzlemeyi aç/ })).toHaveAttribute('href', '?tab=http&monitor=9')
  })
})
