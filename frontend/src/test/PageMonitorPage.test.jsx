import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { page: {} } })),
      getPageMonitors:   vi.fn(),
      getPageHistory:    vi.fn(),
      getPageIssues:     vi.fn(),
      getConfirmations:  vi.fn(() => Promise.resolve({ success: true, data: [] })),
      createPageMonitor: vi.fn(),
      updatePageMonitor: vi.fn(),
      deletePageMonitor: vi.fn(),
      triggerPageCheck:  vi.fn(),
      testPage:          vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Example', url: 'https://www.example.com/', mode: 'SINGLE_PAGE',
  group_name: 'X Sistemleri', team_name: 'SY-A', status: 'DEGRADED',
  broken_resources: 2, mixed_content_count: 0, total_resources: 12, active: true, checked_at: '2026-06-24T00:00:00',
}

/**
 * O2: loadIssues'un üç eşzamanlı çağıranı var (filtre tıklaması, checkNow, 30sn sessiz
 * refreshModal) ve sıra guard'ı yoktu — yavaş bir 'all' yanıtı, kullanıcının sonradan seçtiği
 * filtrenin sonucunu EZEBİLİYORDU (çip 'Kırıklar' iken liste 'Hepsi'). Kardeş PageSpeed sayfası
 * aynı sınıf için resSeq guard'ı taşıyor; desen buraya taşındı.
 *
 * İzole describe: mockImplementationOnce kuyruğu diğer testlere sızmasın diye sonunda
 * mockReset ile temizlenir (clearAllMocks implementasyon kuyruğunu TEMİZLEMEZ).
 */
describe("PageMonitorPage — yarış guardı (O2)", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })
  // mockImplementationOnce kuyrugu diger testlere sizmasin (clearAllMocks onu TEMIZLEMEZ).
  afterEach(() => { api.monitoring.getPageIssues.mockReset() })

  it('GECİKEN eski yanıt, sonradan gelen yeni yanıtı EZMEZ', async () => {
    let resolveOld
    api.monitoring.getPageIssues
      .mockImplementationOnce(() => new Promise(r => { resolveOld = () => r({ success: true, data: [
        { id: 1, issue_type: 'MIXED_CONTENT', resource_url: 'http://eski.example.com/a.js', first_party: true },
      ] }) }))
      .mockResolvedValue({ success: true, data: [
        { id: 2, issue_type: 'BROKEN', resource_url: 'https://yeni.example.com/b.js', first_party: true },
      ] })

    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalledTimes(1))

    // Kullanıcı filtreyi değiştiriyor → İKİNCİ istek hızlı döner ve ekranı doldurur.
    fireEvent.click(screen.getByRole('button', { name: /Kırıklar|Broken/ }))
    // Metin birden cok elemana bolunebiliyor (URL kirpma) -> govde metninden dogrula.
    // Metin birden çok elemana bölünebiliyor → gövde metninden doğrula.
    await waitFor(() => expect(document.body.textContent).toContain('yeni.example.com'))

    // ŞİMDİ eski (yavaş) yanıt geliyor — guard onu ATMALI.
    resolveOld()
    await new Promise(r => setTimeout(r, 30))
    expect(document.body.textContent).not.toContain('eski.example.com')
    expect(document.body.textContent).toContain('yeni.example.com')
  })
})

describe('PageMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  it('izleme kartını (url) listeler', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.example.com/')).toBeInTheDocument()
  })

  it('Yeni modal açılır (form alanları görünür)', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    expect(screen.getByPlaceholderText('https://example.com')).toBeInTheDocument()
  })

  it('Test butonu testPage çağırır ve sonucu gösterir', async () => {
    // Arka plan kartı OK olsun → "Degraded" yalnız test-sonucu banner'ında görünsün (tekil eşleşme).
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, status: 'OK' }] })
    api.monitoring.testPage.mockResolvedValue({
      success: true,
      data: { status: 'DEGRADED', total_resources: 10, broken_resources: 2, mixed_content_count: 0, http_status: 200 },
    })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^test$|test et/i }))
    await waitFor(() => expect(api.monitoring.testPage).toHaveBeenCalled())
    expect(await screen.findByText(/degraded|bozulmuş/i)).toBeInTheDocument()
  })

  // ── Şemasız URL sahte alarmı (2026-08-04): giriş normalizasyonu ────────────
  it('URL alanı: şemasız girdi alandan çıkınca https:// ile tamamlanır, http:// korunur', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    const url = screen.getByPlaceholderText('https://example.com')

    fireEvent.change(url, { target: { value: 'www.axess.com.tr' } })
    fireEvent.blur(url)
    expect(url.value).toBe('https://www.axess.com.tr')

    // Kullanıcının bilinçli http:// tercihi https'e taşınmaz (iç servis 443'te olmayabilir).
    fireEvent.change(url, { target: { value: 'http://internal.host:8080/health' } })
    fireEvent.blur(url)
    expect(url.value).toBe('http://internal.host:8080/health')
  })

  it('Kaydet: alandan çıkılmasa bile payload normalize edilmiş URL taşır', async () => {
    api.monitoring.createPageMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'www.axess.com.tr' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPageMonitor).toHaveBeenCalled())
    expect(api.monitoring.createPageMonitor.mock.calls[0][0].url).toBe('https://www.axess.com.tr')
  })

  it('CONFIG_ERROR monitörü "yapılandırma hatası" rozetiyle görünür; kritik sayaca girmez', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, status: 'CONFIG_ERROR', error: 'yapılandırma hatası: URL\'de geçerli bir host yok' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.example.com/')
    expect(screen.getByText(/yapılandırma hatası|configuration error/i)).toBeInTheDocument()
    // Kesinti gibi gösterilmez — kart "down" sınıfını almaz (alarm/e-posta da üretilmez).
    expect(container.querySelector('.upt-card--down')).toBeNull()
  })

  it('karta tıkla → detay modalında Sorunlar + Grafik sekmeleri; issues yüklenir', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /issues|sorunlar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /chart|grafik/i })).toBeInTheDocument()
  })

  it('E5: issues istegi REJECT ederse spinner kalici kalmaz (modal kapanmadan cozulur)', async () => {
    // setIssuesLoading(true) ile setIssuesLoading(false) arasinda `await` vardi ama try/finally
    // yoktu: istek reject olunca bayrak hic indirilmiyor ve "Sorunlar" sekmesindeki spinner
    // modal kapatilip yeniden acilana kadar donuyordu.
    api.monitoring.getPageIssues.mockRejectedValue(new Error('Failed to fetch'))

    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    // Sinif adi ayirt edici DEGIL: yukleme bloguyla "sorun yok" blogu ayni `upt-modal-loading`
    // sinifini kullaniyor (PageMonitorPage:763-764). Ayirt eden sey METIN — bayrak inmezse
    // "Yukleniyor..." kalir, inerse bos-durum metni gelir.
    expect(await screen.findByText(/sorunlu kaynak yok|no resource issues/i)).toBeInTheDocument()
    expect(screen.queryByText(/^yükleniyor\.\.\.$|^loading\.\.\.$/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /issues|sorunlar/i })).toBeInTheDocument()
  })

  it('istatistik panosu: OK/DEGRADED/DOWN ayrımı + filtreleme', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, url: 'https://a.example.com', status: 'OK',       active: true },
      { id: 2, url: 'https://b.example.com', status: 'DEGRADED', active: true },
      { id: 3, url: 'https://c.example.com', status: 'DOWN',     active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://a.example.com')

    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-valid .stat-value').textContent).toBe('1')     // OK
    expect(container.querySelector('.stat-item-warning .stat-value').textContent).toBe('1')   // DEGRADED
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // DOWN

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await waitFor(() => expect(screen.queryByText('https://a.example.com')).not.toBeInTheDocument())
    expect(screen.getByText('https://b.example.com')).toBeInTheDocument()
    expect(screen.queryByText('https://c.example.com')).not.toBeInTheDocument()

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await screen.findByText('https://a.example.com')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Example', url: 'https://www.example.com/', status: 'DEGRADED', checked_at: '2026-06-24T00:00:00',
      group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A', tags: 'prod,kritik', notify_email: false,
      mode: 'CRAWL', crawl_depth: 3, crawl_max_pages: 80, exclude_patterns: '/ads/\n/tracker/',
      slow_resource_ms: 1500, alert_third_party: true, alert_mixed_content: false, alert_timeout: false,
      resource_concurrency: 8, interval_seconds: 600, timeout_ms: 6000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false, notification_group_id: 7,
    }] })
    api.monitoring.createPageMonitor.mockResolvedValue({ success: true, data: {} })

    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.example.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('https://www.example.com/').value).toMatch(/\(Kopya\)$/)
    expect(document.querySelector('.page-exclude-ta').value).toBe('/ads/\n/tracker/')

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPageMonitor).toHaveBeenCalled())
    expect(api.monitoring.updatePageMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createPageMonitor.mock.calls[0][0]).toEqual({
      name: 'Example (Kopya)', url: 'https://www.example.com/',
      groupName: 'Kurumsal', teamId: 5, tags: 'prod,kritik', notifyEmail: false, notifyWebhook: true,
      mode: 'CRAWL', crawlDepth: 3, crawlMaxPages: 80, excludePatterns: '/ads/\n/tracker/',
      slowResourceMs: 1500, alertThirdParty: true, alertMixedContent: false, alertTimeout: false,
      resourceConcurrency: 8, intervalSeconds: 600, timeoutMs: 6000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitsin.
      notificationGroupId: 7,
    })
  })

  // ── Sorun satırından "Hariç tut" aksiyonu ──────────────────────────────────
  const brokenIssue = {
    id: 11, monitor_id: 1, resource_url: 'https://voting.institutionalinvestor.com/welcome',
    resource_type: 'LINK', source_page: 'https://www.example.com/', issue_type: 'BROKEN',
    first_party: false, http_status: null, duration_ms: 100, checked_at: '2026-08-03T19:45:36',
  }

  it('yönetilebilir monitörde sorun satırında "Hariç tut" butonu; prompt onayı → yalnız excludePatterns ile PUT (ekleyerek)', async () => {
    const managed = { ...monitor, team_id: 5, exclude_patterns: '/ads/' }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })
    api.monitoring.updatePageMonitor.mockResolvedValue({ success: true, data: { ...managed,
      exclude_patterns: '/ads/\nhttps://voting.institutionalinvestor.com/welcome' } })

    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    const btn = await screen.findByRole('button', { name: /hariç tut$|^exclude$/i })
    fireEvent.click(btn)

    // Prompt açıldı, giriş değeri = kaynak URL'i (düzenlenebilir)
    const input = document.querySelector('.dlg-input')
    expect(input).not.toBeNull()
    expect(input.value).toBe('https://voting.institutionalinvestor.com/welcome')
    fireEvent.click(document.querySelector('.dlg-btn-confirm'))

    await waitFor(() => expect(api.monitoring.updatePageMonitor).toHaveBeenCalledWith(1, {
      excludePatterns: '/ads/\nhttps://voting.institutionalinvestor.com/welcome',
    }))
  })

  it('alarm kapsamı kolonu: alert_timeout kapalı monitörde TIMEOUT satırı "Kapsam dışı"; 3P açıkken ölü LINK "Alarmda"', async () => {
    const managed = { ...monitor, team_id: 5, alert_timeout: false, alert_third_party: true }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [
      { ...brokenIssue, id: 21, issue_type: 'TIMEOUT', resource_type: 'IFRAME',
        resource_url: 'https://www.googletagmanager.com/ns.html', duration_ms: 10007 },
      { ...brokenIssue, id: 22 },   // ölü 3P LINK (BROKEN, http_status null)
    ] })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    const out = await screen.findAllByText(/kapsam dışı|out of scope/i)
    expect(out.length).toBe(1)   // yalnız TIMEOUT satırı (alert_timeout kapalı)
    const inScope = screen.getAllByText(/^alarmda$|^in scope$/i)
    expect(inScope.length).toBe(1)   // ölü 3P LINK — yeni kural + 3P açık → alarmda
  })

  it('tarama turu başlık bandı: iki farklı checked_at → iki run başlığı', async () => {
    const managed = { ...monitor, team_id: 5 }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [
      { ...brokenIssue, id: 31, checked_at: '2026-08-03T20:23:57' },
      { ...brokenIssue, id: 32, checked_at: '2026-08-03T20:23:57' },
      { ...brokenIssue, id: 33, checked_at: '2026-08-03T20:17:35' },
    ] })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    await screen.findAllByText(/googletagmanager|voting\.institutionalinvestor/)

    const hdrs = document.querySelectorAll('.page-run-hdr')
    expect(hdrs.length).toBe(2)
    expect(hdrs[0].textContent).toMatch(/2 sorun|2 issues/)
    expect(hdrs[1].textContent).toMatch(/1 sorun|1 issues/)
  })

  it('mevcut hariç desenle eşleşen satırda buton disabled; yönetilemeyen kullanıcıda hiç yok', async () => {
    // Desen '/welcome' → kaynak URL'i contains ile eşleşir → buton disabled
    const managed = { ...monitor, team_id: 5, exclude_patterns: '/welcome' }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })

    const { unmount } = render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    const already = await screen.findByRole('button', { name: /zaten hariç|already matches/i })
    expect(already).toBeDisabled()
    unmount()
    // Derin bağlantı senkronu modal seçimini URL'e yazar (throttle'lı — yavaş/enstrümante koşuda
    // yetişir); temizlenmezse ikinci render modalı URL'den otomatik açar → getByText çoklu eşleşir.
    window.history.replaceState({}, '', '/')

    // Yönetilemeyen: USER + farklı takım → aksiyon butonu render edilmez
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{ ...managed, team_id: 9 }] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { page: {} } })
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    await screen.findByText(/voting\.institutionalinvestor\.com/)
    expect(screen.queryByRole('button', { name: /hariç tut$|^exclude$|zaten hariç|already matches/i })).toBeNull()
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://m1.example.com/')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('https://m51.example.com/')
    expect(screen.queryByText('https://m1.example.com/')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://m1.example.com/')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerPageCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())

      const runBtn = document.querySelector('.mon-act--check')
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerPageCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })
})
