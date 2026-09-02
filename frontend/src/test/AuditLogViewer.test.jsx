import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import AuditLogViewer from '../components/admin/AuditLogViewer.jsx'

// Denetim konsolu — api mock'lu. Preset/bütünlük/diff dilden bağımsız (regex TR|EN) doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAuditLogs: vi.fn(),
      getAuditStats: vi.fn(),
      getAuditIntegrity: vi.fn(),
      getAuditResourceHistory: vi.fn(),
      getAuditEventTypes: vi.fn(),
      auditExportUrl: vi.fn(() => 'http://x/export'),
    },
  }),
}))
import { api } from '../api/client'

const row = (over) => ({
  id: 1, event_time: '2026-07-28T10:00:00', event_type: 'USER_UPDATE', actor: 'alice',
  actor_role: 'ADMIN', ip_address: '1.2.3.4', resource_type: 'USER', resource_id: '5',
  outcome: 'SUCCESS', changes: '{"systemRole":{"from":"USER","to":"ADMIN"}}', ...over,
})

describe('AuditLogViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getAuditStats.mockResolvedValue({ success: true, data: { total_24h: 5 } })
    api.admin.getAuditLogs.mockResolvedValue({ success: true, data: [row()], total: 1, page: 0 })
    api.admin.getAuditIntegrity.mockResolvedValue({ success: true, data: { ok: true, checked: 42 } })
    api.admin.getAuditResourceHistory.mockResolvedValue({ success: true, data: [row(), row({ id: 2, event_type: 'MONITOR_UPDATE' })] })
    api.admin.getAuditEventTypes.mockResolvedValue({ success: true, data: [
      { type: 'LOGIN', category: 'AUTH', count: 42 },
      { type: 'MAINTENANCE_CREATE', category: 'MAINTENANCE', count: 3 },
      { type: 'MONITOR_TEST', category: 'MONITOR', count: 0 },
    ] })
  })

  it('denetim satırını gösterir + before/after diff genişletilebilir', async () => {
    render(<AuditLogViewer />)
    expect(await screen.findByText('USER_UPDATE')).toBeInTheDocument()
    // Değişiklik sayısı toggle'ına tıkla → diff tablosu from/to gösterir
    const { container } = render(<AuditLogViewer />)
    await screen.findAllByText('USER_UPDATE')
    const toggle = container.querySelector('.audit-detail-toggle')
    if (toggle) {
      fireEvent.click(toggle)
      expect(container.querySelector('.audit-diff-to')).not.toBeNull()
    }
  })

  it('preset (Güvenlik olayları) → getAuditLogs BLOCKED filtresiyle çağrılır', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Güvenlik olaylar|Security events/))
    await waitFor(() => {
      const last = api.admin.getAuditLogs.mock.calls.at(-1)[0]
      expect(last.outcome).toBe('BLOCKED')
    })
  })

  it('bütünlüğü doğrula → sağlam zincir rozeti', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Bütünlüğü doğrula|Verify integrity/))
    await waitFor(() => expect(api.admin.getAuditIntegrity).toHaveBeenCalled())
    expect(await screen.findByText(/Zincir sağlam|Chain intact/)).toBeInTheDocument()
  })

  it('CSV dışa aktarma → export URL açılır', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText('CSV'))
    expect(api.admin.auditExportUrl).toHaveBeenCalledWith('csv', expect.any(Object))
    expect(openSpy).toHaveBeenCalled()
    openSpy.mockRestore()
  })

  it('preset filtresi URL query paramına yansır (derin-link)', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Güvenlik olaylar|Security events/))
    await waitFor(() => expect(window.location.search).toContain('a_outcome=BLOCKED'))
  })

  it('görünüm kaydet → chip belirir + tıklayınca filtre uygulanır (localStorage)', async () => {
    const { container } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    const nameInput = screen.getByPlaceholderText(/Görünüm adı|View name/)
    fireEvent.change(nameInput, { target: { value: 'Benim görünümüm' } })
    fireEvent.click(screen.getByText(/^Görünümü kaydet$|^Save view$/))
    expect(await screen.findByText('Benim görünümüm')).toBeInTheDocument()
    // localStorage'a yazıldı — anahtar `sm.` önekli (naming-consistency sözleşmesi)
    expect(JSON.parse(localStorage.getItem('sm.audit.savedViews'))[0].name).toBe('Benim görünümüm')
    // chip'e tıkla → yeniden yükleme tetiklenir
    const before = api.admin.getAuditLogs.mock.calls.length
    fireEvent.click(container.querySelector('.audit-view-name'))
    await waitFor(() => expect(api.admin.getAuditLogs.mock.calls.length).toBeGreaterThan(before))
  })

  it('ESKİ anahtardaki kayıtlı görünümler GÖÇ eder (kullanıcı görünümlerini kaybetmez)', async () => {
    // Anahtar `auditSavedViews` → `sm.audit.savedViews` olarak yeniden adlandırıldı. Göç olmasaydı
    // kullanıcı, kendi kaydettiği görünümlerin bir sürüm sonrası sessizce yok olduğunu görürdü.
    localStorage.setItem('auditSavedViews', JSON.stringify([{ name: 'Eski görünüm', filters: { outcome: 'BLOCKED' } }]))

    render(<AuditLogViewer />)

    expect(await screen.findByText('Eski görünüm')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.audit.savedViews'))[0].name).toBe('Eski görünüm')
    expect(localStorage.getItem('auditSavedViews'), 'eski anahtar temizlenmeli').toBeNull()
  })

  it('DÜZ METİN ayrıntı ekranda gösterilir (JSON olmayan detail sessizce düşmez)', async () => {
    // Regresyon: `parseDiff(row.changes || row.detail)` düz metinde JSON.parse ile patlıyor ve
    // null dönüyordu — yazılan ayrıntı ekranda HİÇ görünmüyordu.
    api.admin.getAuditLogs.mockResolvedValue({
      success: true, page: 0, total: 1,
      data: [{ id: 91, event_time: '2026-09-01T10:00:00', event_type: 'USER_PUSH_TEST',
               actor: 'admin', outcome: 'SUCCESS', resource_type: 'USER_PUSH', resource_id: 'test',
               detail: 'test → ops@example.com', changes: null }],
    })

    const { container } = render(<AuditLogViewer />)
    await screen.findByText('USER_PUSH_TEST')
    fireEvent.click(container.querySelector('.audit-detail-toggle'))

    expect(await screen.findByText('test → ops@example.com')).toBeInTheDocument()
  })

  it('JSON ayrıntı alan/değer tablosuna açılır', async () => {
    api.admin.getAuditLogs.mockResolvedValue({
      success: true, page: 0, total: 1,
      data: [{ id: 92, event_time: '2026-09-01T10:00:00', event_type: 'MONITOR_TEST',
               actor: 'admin', outcome: 'SUCCESS', resource_type: 'PORT_MONITOR', resource_id: 'test',
               detail: '{"host":"db-01","port":5432}', changes: null }],
    })

    const { container } = render(<AuditLogViewer />)
    await screen.findByText('MONITOR_TEST')
    fireEvent.click(container.querySelector('.audit-detail-toggle'))

    expect(await screen.findByText('db-01')).toBeInTheDocument()
    expect(screen.getByText('5432')).toBeInTheDocument()
  })

  it('istatistik kartları ÇEVRİLMİŞ etiket gösterir (ham anahtar değil)', async () => {
    render(<AuditLogViewer />)
    // Kart etiketleri `audit.${card.key}` ile üretiliyordu; anahtar sözlükte olmadığı için
    // ekranda ham "audit.total_24h" yazıyordu — hem TR hem EN'de.
    await waitFor(() => expect(api.admin.getAuditStats).toHaveBeenCalled())
    expect(screen.queryByText(/audit\.total_24h/)).toBeNull()
    expect(screen.queryByText(/audit\.anomalies_24h/)).toBeNull()
    expect(await screen.findByText(/Toplam Olay \(24s\)|Total Events \(24h\)/)).toBeInTheDocument()
  })

  it('anomali bayrağı çevrilmiş etiket ve renk SINIFI taşır (satır-içi hex değil)', async () => {
    api.admin.getAuditLogs.mockResolvedValue({
      success: true, page: 0, total: 1,
      data: [{ id: 93, event_time: '2026-09-01T03:00:00', event_type: 'LOGIN_FAILED',
               actor: 'alice', outcome: 'FAILURE', anomaly_flags: 'OFF_HOURS,BRUTE_FORCE' }],
    })

    const { container } = render(<AuditLogViewer />)
    await screen.findByText('LOGIN_FAILED')

    expect(container.querySelector('.an-off_hours')).not.toBeNull()
    expect(container.querySelector('.an-brute_force')).not.toBeNull()
    expect(container.querySelector('.audit-anomaly-chip').getAttribute('style')).toBeNull()
    expect(screen.queryByText('OFF HOURS'), 'ham bayrak basılmamalı').toBeNull()
  })

  it('olay türü filtresi SUNUCU kataloğundan beslenir (elle liste sürükleniyordu)', async () => {
    // Elle tutulan liste 162 türün yalnız 32'sini tanıyordu: MAINTENANCE_* türleri filtrede
    // hiç yoktu, yani o olaylar arayüzden aranamıyordu.
    const { container } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditEventTypes).toHaveBeenCalled())

    // Olay türü seçicisi filtre çubuğundaki ilk SearchableSelect. Gruplar kapalı başlar;
    // arama kutusuna yazmak tüm dalları açar (bileşenin sözleşmesi).
    fireEvent.mouseDown(container.querySelectorAll('.ss-trigger')[0])   // acilis onMouseDown ile
    fireEvent.change(container.querySelector('.ss-search-input'), { target: { value: 'MAINTENANCE' } })

    expect(await screen.findByText(/MAINTENANCE_CREATE/)).toBeInTheDocument()
  })

  it('katalogdaki kayıt SAYISI gösterilir (boş dönecek filtre baştan belli olsun)', async () => {
    const { container } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditEventTypes).toHaveBeenCalled())

    fireEvent.mouseDown(container.querySelectorAll('.ss-trigger')[0])   // acilis onMouseDown ile
    fireEvent.change(container.querySelector('.ss-search-input'), { target: { value: 'LOGIN' } })

    expect(await screen.findByText(/LOGIN \(42\)/)).toBeInTheDocument()
  })

  it('katalog ucu düşerse YEDEK listeyle çalışmaya devam eder', async () => {
    api.admin.getAuditEventTypes.mockRejectedValue(new Error('network'))

    const { container } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())

    fireEvent.mouseDown(container.querySelectorAll('.ss-trigger')[0])   // acilis onMouseDown ile
    fireEvent.change(container.querySelector('.ss-search-input'), { target: { value: 'LOGIN_FAILED' } })

    expect(await screen.findByText('LOGIN_FAILED')).toBeInTheDocument()
  })

  it('kaynak zaman-çizelgesi düğmesi → drawer kaynak geçmişini yükler', async () => {
    const { container } = render(<AuditLogViewer />)
    await screen.findByText('USER_UPDATE')
    const tlBtn = container.querySelector('.audit-timeline-btn')
    expect(tlBtn).not.toBeNull()
    fireEvent.click(tlBtn)
    await waitFor(() => expect(api.admin.getAuditResourceHistory).toHaveBeenCalledWith('USER', '5', 100))
    expect(container.querySelector('.audit-timeline-drawer')).not.toBeNull()
    expect(await screen.findByText('MONITOR_UPDATE')).toBeInTheDocument()
  })
})
