import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',   // Kontrol Geçmişi gün ayırıcısı (hata teşhisi testi geçmişi açar)
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
  group_name: 'X Sistemleri', tags: 'prod', team_name: 'SY-A', status: 'DEGRADED',
  broken_resources: 2, mixed_content_count: 0, total_resources: 12, active: true, checked_at: '2026-06-24T00:00:00',
}

/**
 * Kartın başlığı (stretched button) — URL görsel olarak PARÇALI çizilir (2026-09-27 kart yeniden tasarımı: host vurgulu,
 * yol soluk, https şeması gizli), bu yüzden kart düz metinle değil ROL + erişilebilir adla ("<url> — open details") bulunur.
 */
const escapeRx = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
const cardTitleName = (url) => new RegExp(`^${escapeRx(url)} — (open details|detayları aç)$`)
const findCard = (url) => screen.findByRole('button', { name: cardTitleName(url) })
const getCard = (url) => screen.getByRole('button', { name: cardTitleName(url) })
const queryCard = (url) => screen.queryByRole('button', { name: cardTitleName(url) })
/** 50 kartlık ızgarada rol sorgusu çok yavaş (her düğmenin adı hesaplanır) — AYNI başlık düğmesini adıyla doğrudan bulur. */
const cardOpenEl = (url) => [...document.querySelectorAll('[data-monitor-open]')]
  .find((b) => cardTitleName(url).test(b.getAttribute('aria-label') || '')) || null

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
    fireEvent.click(await findCard('https://www.example.com/'))
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
    expect(await findCard('https://www.example.com/')).toBeInTheDocument()
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
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
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
    await findCard('https://www.example.com/')
    // Kesinti gibi gösterilmez — kart "down" durumunu almaz (alarm/e-posta da üretilmez); "bilinmiyor"
    // sözlüğünde durur (shadcn MonitorCard `data-status`).
    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    expect(card).not.toBeNull()
    expect(card.querySelector('[data-slot="badge"][data-status]')).toHaveTextContent(/yapılandırma hatası|configuration error/i)
    // Sonuç paneli mor "URL denetlenemiyor" + sunucunun nedeni (kesinti kırmızısı değil)
    const panel = card.querySelector('[data-slot="page-integrity"]')
    expect(panel).toHaveAttribute('data-tone', 'config')
    expect(panel.querySelector('[data-slot="page-reason"]')).toHaveAttribute('data-reason', 'config')
    expect(panel.textContent).toMatch(/(The URL can’t be checked|URL denetlenemiyor)(It has no valid host|URL'de geçerli bir host yok)/)
    expect(card.getAttribute('data-status')).toBe('unknown')
    expect(container.querySelector('[data-slot="card"][data-status="down"]')).toBeNull()
  })

  it('karta tıkla → detay modalında Sorunlar + Grafik sekmeleri; issues yüklenir', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(getCard('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    // Detay penceresi ui/ModalShell (role="dialog"), sekmeler shadcn Tabs (role="tab")
    const detail = screen.getByRole('dialog')
    expect(within(detail).getByRole('tab', { name: /issues|sorunlar/i })).toHaveAttribute('aria-selected', 'true')
    expect(within(detail).getByRole('tab', { name: /chart|grafik/i })).toBeInTheDocument()
  })

  it('E5: issues istegi REJECT ederse spinner kalici kalmaz (modal kapanmadan cozulur)', async () => {
    // setIssuesLoading(true) ile setIssuesLoading(false) arasinda `await` vardi ama try/finally
    // yoktu: istek reject olunca bayrak hic indirilmiyor ve "Sorunlar" sekmesindeki spinner
    // modal kapatilip yeniden acilana kadar donuyordu.
    api.monitoring.getPageIssues.mockRejectedValue(new Error('Failed to fetch'))

    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(getCard('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    // Sinif adi ayirt edici DEGIL: yukleme bloguyla "sorun yok" blogu ayni `upt-modal-loading`
    // sinifini kullaniyor (PageMonitorPage:763-764). Ayirt eden sey METIN — bayrak inmezse
    // "Yukleniyor..." kalir, inerse bos-durum metni gelir.
    expect(await screen.findByText(/sorunlu kaynak yok|no resource issues/i)).toBeInTheDocument()
    expect(screen.queryByText(/^yükleniyor\.\.\.$|^loading\.\.\.$/i)).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /issues|sorunlar/i })).toBeInTheDocument()
  })

  it('istatistik panosu: OK/DEGRADED/DOWN ayrımı + filtreleme', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, url: 'https://a.example.com', status: 'OK',       active: true },
      { id: 2, url: 'https://b.example.com', status: 'DEGRADED', active: true },
      { id: 3, url: 'https://c.example.com', status: 'DOWN',     active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await findCard('https://a.example.com')

    expect(container.querySelector('[data-slot="stats-panel"]')).toBeNull()
    fireEvent.click(container.querySelector('[data-slot="stats-toggle"]'))
    expect(container.querySelector('[data-slot="stats-panel"]')).not.toBeNull()

    expect(container.querySelector('[data-slot="stat-item"][data-tone="total"] [data-slot="stat-value"]').textContent).toBe('3')
    expect(container.querySelector('[data-slot="stat-item"][data-tone="valid"] [data-slot="stat-value"]').textContent).toBe('1')     // OK
    expect(container.querySelector('[data-slot="stat-item"][data-tone="warning"] [data-slot="stat-value"]').textContent).toBe('1')   // DEGRADED
    expect(container.querySelector('[data-slot="stat-item"][data-tone="critical"] [data-slot="stat-value"]').textContent).toBe('1')  // DOWN

    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-tone="warning"]'))
    await waitFor(() => expect(queryCard('https://a.example.com')).not.toBeInTheDocument())
    expect(getCard('https://b.example.com')).toBeInTheDocument()
    expect(queryCard('https://c.example.com')).not.toBeInTheDocument()

    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-tone="warning"]'))
    await findCard('https://a.example.com')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Example', url: 'https://www.example.com/', status: 'DEGRADED', checked_at: '2026-06-24T00:00:00',
      group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A', tags: 'prod,kritik', alert_level: 'HIGH', notify_email: false,
      mode: 'CRAWL', crawl_depth: 3, crawl_max_pages: 80, exclude_patterns: '/ads/\n/tracker/',
      slow_resource_ms: 1500, alert_third_party: true, alert_mixed_content: false, alert_timeout: false,
      resource_concurrency: 8, interval_seconds: 600, timeout_ms: 6000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false, notification_group_id: 7, noc_notify: true, noc_group_ids: [2, 3],
    }] })
    api.monitoring.createPageMonitor.mockResolvedValue({ success: true, data: {} })

    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await findCard('https://www.example.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('[data-slot="duplicate-badge"]')).not.toBeNull()
    expect(screen.getByText(/kaynak izlemenin birebir kopyası|an exact copy of the source monitor/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText('https://www.example.com/').value).toMatch(/\(Kopya\)$/)
    // Hariç desenleri alanı (shadcn Textarea, etiketine bağlı) + etiketteki desen sayacı rozeti
    expect(screen.getByRole('textbox', { name: /exclude patterns|hariç tutulan/i }).value).toBe('/ads/\n/tracker/')
    expect(screen.getByText(/^(2 patterns|2 desen)$/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPageMonitor).toHaveBeenCalled())
    expect(api.monitoring.updatePageMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createPageMonitor.mock.calls[0][0]).toEqual({
      name: 'Example (Kopya)', url: 'https://www.example.com/',
      groupName: 'Kurumsal', teamId: 5, tags: 'prod,kritik', alertLevel: 'HIGH', notifyEmail: false, notifyWebhook: true,
      mode: 'CRAWL', crawlDepth: 3, crawlMaxPages: 80, excludePatterns: '/ads/\n/tracker/',
      slowResourceMs: 1500, useProxy: 'AUTO', alertThirdParty: true, alertMixedContent: false, alertTimeout: false,
      resourceConcurrency: 8, intervalSeconds: 600, timeoutMs: 6000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitsin.
      notificationGroupId: 7,
      // 7/24 izleme ekibi (2026-09-27): açık anahtar + açık grup seçimi de kopyalanır
      nocNotify: true, nocGroupIds: [2, 3],
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
    fireEvent.click(getCard('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    // Düğmenin erişilebilir adı KAYNAK URL'ini taşıyor (satırlar ayırt edilsin diye): EN "Exclude <url>",
    // TR "<url> — hariç tut". Etkin (disabled olmayan) dışlama düğmesi:
    const btn = await screen.findByRole('button', {
      name: /^Exclude https:\/\/voting\.institutionalinvestor\.com\/welcome$|^https:\/\/voting\.institutionalinvestor\.com\/welcome — hariç tut$/,
    })
    expect(btn).not.toBeDisabled()
    // İkon düğmesinin ipucu artık shadcn Tooltip (title değil): odaklanınca görünür
    expect(btn).not.toHaveAttribute('title')
    fireEvent.focus(btn)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/^(Exclude|Hariç tut)$/)
    fireEvent.click(btn)

    // Prompt açıldı, giriş değeri = kaynak URL'i (düzenlenebilir)
    const prompt = await screen.findByRole('dialog', { name: /Add to exclude patterns|Hariç tutulanlara ekle/ })
    const input = within(prompt).getByRole('textbox')
    expect(input.value).toBe('https://voting.institutionalinvestor.com/welcome')
    fireEvent.click(within(prompt).getByRole('button', { name: /^(Exclude|Hariç tut)$/ }))

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
    fireEvent.click(getCard('https://www.example.com/'))
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
    fireEvent.click(getCard('https://www.example.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    await screen.findAllByText(/googletagmanager|voting\.institutionalinvestor/)

    const hdrs = document.querySelectorAll('[data-slot="issue-run-header"]')
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
    fireEvent.click(getCard('https://www.example.com/'))
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
    fireEvent.click(getCard('https://www.example.com/'))
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
    await waitFor(() => expect(cardOpenEl('https://m1.example.com/')).not.toBeNull())
    expect(container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(cardOpenEl('https://m51.example.com/')).not.toBeNull())
    expect(cardOpenEl('https://m1.example.com/')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cardOpenEl('https://m1.example.com/')).not.toBeNull())
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

      // Kartın kontrol düğmesi (modal da aynı adlı düğmeyi taşır; DOM sırasında kart önce gelir)
      const runBtn = screen.getAllByRole('button', { name: /^(Kontrol|Check)$/i })[0]
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerPageCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  // ── shadcn geçişi (2026-09-25) ────────────────────────────────────────────
  it('durum sözlüğü: OK/DEGRADED/DOWN/CONFIG_ERROR → kart ve rozet up/warn/down/unknown', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, id: 1, url: 'https://ok.example.com/', status: 'OK' },
      { ...monitor, id: 2, url: 'https://deg.example.com/', status: 'DEGRADED' },
      { ...monitor, id: 3, url: 'https://down.example.com/', status: 'DOWN' },
      { ...monitor, id: 4, url: 'https://cfg.example.com/', status: 'CONFIG_ERROR' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await findCard('https://ok.example.com/')
    // Varsayılan sıra (2026-10-01): kırmızı (DOWN) → sarı (DEGRADED) → diğerleri (ad, eşitlikte kimlik)
    const all = [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]
    expect(all.map(c => c.getAttribute('data-status'))).toEqual(['down', 'warn', 'up', 'unknown'])
    const byUrl = (u) => all.find(c => c.querySelector(`[aria-label^="${u} — "]`))
    const cards = ['https://ok.example.com/', 'https://deg.example.com/', 'https://down.example.com/', 'https://cfg.example.com/'].map(byUrl)
    expect(cards.map(c => c.getAttribute('data-status'))).toEqual(['up', 'warn', 'down', 'unknown'])
    // Rozet kartın sözlüğünü izler; yapılandırma hatası "bilinmiyor"da ama kendi metniyle
    const badges = cards.map(c => c.querySelector('[data-slot="badge"][data-status]'))
    expect(badges.map(b => b.getAttribute('data-status'))).toEqual(['up', 'warn', 'down', 'unknown'])
    expect(badges[3]).toHaveTextContent(/configuration error|yapılandırma hatası/i)
  })

  it('canlı teyit zinciri detayda uyarı bandı (AlertBanner + Spinner) olarak görünür', async () => {
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [
      { alert_type: 'PAGE_DOWN', attempt: 2, total_attempts: 3, next_attempt_at: '2026-09-25T10:00:30' },
      { alert_type: 'HTTP_DOWN', attempt: 1, total_attempts: 3, next_attempt_at: null },   // başka tür → gösterilmez
    ] })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await findCard('https://www.example.com/'))
    const text = await screen.findByText(/^(Confirmation attempt 2\/3|Teyit denemesi 2\/3) — /)
    const banner = text.closest('[data-slot="alert"]')
    expect(banner).toHaveAttribute('data-tone', 'warning')
    expect(banner.querySelector('[data-slot="spinner"]')).not.toBeNull()
    expect(screen.getAllByText(/Confirmation attempt|Teyit denemesi/)).toHaveLength(1)
  })

  it('Gelişmiş ayarlar (Collapsible) kapalı başlar; açınca alanlar görünür ve değer payload\'a gider', async () => {
    api.monitoring.createPageMonitor.mockResolvedValue({ success: true, data: {} })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    const form = screen.getByRole('dialog')
    const toggle = within(form).getByRole('button', { name: /advanced settings|gelişmiş ayarlar/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(within(form).queryByRole('spinbutton', { name: /timeout \(ms\)|zaman aşımı/i })).toBeNull()

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    fireEvent.change(within(form).getByRole('spinbutton', { name: /timeout \(ms\)|zaman aşımı/i }), { target: { value: '7500' } })

    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    await fillGroupAndTags()
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPageMonitor).toHaveBeenCalled())
    expect(api.monitoring.createPageMonitor.mock.calls[0][0].timeoutMs).toBe(7500)
  })
})

/**
 * Kontrol geçmişi hata teşhisi (2026-10-05): DOWN satırı durumun altında neden (HTTP 503) + aç/kapa gösterir; açılınca
 * panel HTTP durumunu ve ham hatayı yazar (eskiden geçmişte hata metni ve HTTP kodu HİÇ görünmüyordu). DEGRADED satırı
 * kırık kaynak nedenini taşır; OK satırda hücre yok.
 */
describe('PageMonitorPage — kontrol geçmişi hata teşhisi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 31, monitor_id: 1, ok: false, status: 'DOWN', http_status: 503, response_ms: 210, total_resources: 0,
          broken_resources: 0, timeout_count: 0, mixed_content_count: 0, error: 'ana sayfa HTTP 503',
          checked_at: '2026-10-05T09:00:00', failure_reason: 'HTTP_STATUS',
          failure_detail: JSON.stringify({ phase: 'RESPONSE', http_status: 503, target: 'www.example.com', via: 'direct', timeout_ms: 4000 }) },
        { id: 30, monitor_id: 1, ok: false, status: 'DEGRADED', http_status: 200, total_resources: 12, broken_resources: 2,
          timeout_count: 0, mixed_content_count: 0, checked_at: '2026-10-05T08:00:00' },
        { id: 29, monitor_id: 1, ok: true, status: 'OK', http_status: 200, total_resources: 12, broken_resources: 0,
          timeout_count: 0, mixed_content_count: 0, checked_at: '2026-10-05T07:00:00' },
      ], counts: { total: 3, fail: 2 }, buckets: [], alerts: [],
      range: { from: '2026-09-29T00:00:00', to: '2026-10-05T23:59:59' }, total: 3, page: 0, size: 50 } })
  })

  it('DOWN: "HTTP 503" nedeni + panelde HTTP durumu ve ham hata; DEGRADED: kırık kaynak nedeni (eski satır); OK: hücre yok', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await findCard('https://www.example.com/'))
    const detail = await screen.findByRole('dialog')
    fireEvent.mouseDown(within(detail).getByRole('tab', { name: /check history|kontrol geçmişi/i }), { button: 0 })
    await waitFor(() => expect(detail.querySelectorAll('[data-slot="chkfail-cell"]')).toHaveLength(2))
    const [down, degraded] = detail.querySelectorAll('[data-slot="chkfail-cell"]')
    expect(down).toHaveAttribute('data-code', 'HTTP_STATUS')
    expect(within(down).getByText(/^(Returned HTTP 503|HTTP 503 döndü)$/)).toBeInTheDocument()
    expect(degraded).toHaveAttribute('data-code', 'RESOURCES_BROKEN')
    expect(degraded).toHaveAttribute('data-legacy', 'true')
    fireEvent.click(down.querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await within(detail).findByRole('region', { name: /failure detail|hata ayrıntısı/i })
    expect(panel.querySelector('[data-key="httpStatus"]').textContent).toBe('HTTP 503')
    expect(panel.querySelector('[data-slot="chkfail-technical"]').textContent).toContain('ana sayfa HTTP 503')
  })
})
