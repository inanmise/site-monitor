import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import { formatBytes } from '../utils/formatBytes.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:              vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:         vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageSpeedMonitors:    vi.fn(),
      getPageSpeedResources:   vi.fn(),
      getPageSpeedSeries:      vi.fn(),
      createPageSpeedMonitor:  vi.fn(),
      updatePageSpeedMonitor:  vi.fn(),
      deletePageSpeedMonitor:  vi.fn(),
      triggerPageSpeedCheck:   vi.fn(),
      testPageSpeed:           vi.fn(),
      getCheckHistory:         vi.fn(),
      getCheckHistoryCsvUrl:   vi.fn(() => '#'),
      getMonitorNotes:         vi.fn(),
      getChanges:              vi.fn(),
      getConfirmations:        vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Ödeme sayfası', url: 'https://x.com/odeme',
  team_id: 5, team_name: 'SY-A', group_name: 'Kanal', active: true,
  interval_seconds: 1800, timeout_ms: 10000,
  max_load_ms: 3000, max_ttfb_ms: null, max_page_kb: null, max_requests: null,
  status: 'OK', ok: true, response_ms: 900, ttfb_ms: 120,
  total_bytes: 2048 * 1024, request_count: 42, failed_count: 0, capped: false,
  breached_metrics: [], last_check: '2026-08-23T10:00:00',
  has_basic_auth_pass: false, has_custom_headers: false, custom_header_names: [],
}

/** Ölçüm dışı bırakılan alanları kolayca değiştirmek için. */
const withMonitor = (over = {}) => ({ ...monitor, ...over })

describe('PageSpeedMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [] } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })

  it('izlemeyi dört metriğiyle listeler', async () => {
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())

    expect(await screen.findByText('https://x.com/odeme')).toBeInTheDocument()
    expect(screen.getByText('900 ms')).toBeInTheDocument()     // yükleme
    expect(screen.getByText('120 ms')).toBeInTheDocument()     // TTFB
    expect(screen.getByText('2.0 MB')).toBeInTheDocument()     // boyut
    expect(screen.getByText('42')).toBeInTheDocument()         // istek
  })

  it('API düşerse "izleme yok" DEMEZ — hata bandı gösterir (silindi sanılmasın)', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: false, error: 'boom' })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/boom/)).toBeInTheDocument()
  })

  it('eşik aşımı kartta rozet olarak görünür', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({
      success: true, data: [withMonitor({ status: 'SLOW', breached_metrics: ['LOAD', 'SIZE'] })] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/Load time over threshold|Yükleme eşiği aşıldı/)).toBeInTheDocument()
    expect(screen.getByText(/Size over threshold|Boyut eşiği aşıldı/)).toBeInTheDocument()
  })

  it('form açılır ve dört eşik alanı BOŞ bırakılabilir', async () => {
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: true, data: monitor })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    expect(screen.getByText(/new page speed monitor|yeni sayfa hızı/i)).toBeInTheDocument()
    // Dört eşik alanı da "eşik yok" yer tutucusuyla ve BOŞ gelir.
    const blanks = screen.getAllByPlaceholderText(/no threshold|eşik yok/i)
    expect(blanks).toHaveLength(4)
    blanks.forEach(el => expect(el).toHaveValue(null))
  })

  it('kaydederken boş eşik NULL gider (0 değil) — 0 "sınırsız" demek olurdu', async () => {
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: true, data: monitor })
    // USER: takım oturumdan gelir ve Kaydet açıktır. ADMIN'de takım BİLİNÇLİ olarak boş başlar
    // (hangi takıma yazıldığı seçilmeden kaydedilemez), o yüzden bu senaryo USER ile koşuyor.
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))

    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://y.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createPageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPageSpeedMonitor.mock.calls[0][0]
    expect(payload.maxLoadMs).toBeNull()
    expect(payload.maxTtfbMs).toBeNull()
    expect(payload.maxPageKb).toBeNull()
    expect(payload.maxRequests).toBeNull()
    expect(payload.url).toBe('https://y.com')
  })

  it('parola ASLA formdan geri yüklenmez; boş bırakılırsa payload\'a HİÇ girmez', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({
      success: true, data: [withMonitor({ basic_auth_user: 'kadir', has_basic_auth_pass: true })] })
    api.monitoring.updatePageSpeedMonitor.mockResolvedValue({ success: true, data: monitor })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())

    fireEvent.click(await screen.findByTitle(/edit|düzenle/i))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.updatePageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.updatePageSpeedMonitor.mock.calls[0][1]
    // Anahtar HİÇ olmamalı: null göndermek "parolayı sil" anlamına gelirdi.
    expect(Object.prototype.hasOwnProperty.call(payload, 'basicAuthPass')).toBe(false)
    expect(payload.basicAuthUser).toBe('kadir')
  })

  it('özel başlık alanı admin OLMAYANA hiç çizilmez', async () => {
    // Bu test YALNIZ görünürlüğü kanıtlar. "Admin olmayan bu alanı yazamaz" iddiası burada
    // KANITLANAMAZ: alan çizilmediği için form değeri zaten boş kalır, dolayısıyla isAdmin
    // kontrolünü kaldırsanız bile payload değişmez (mutasyonla doğrulandı — test yeşil kaldı).
    // Gerçek kapı sunucudadır ve orada pinlidir:
    // MonitoringControllerTest#customHeadersAreAdminOnlyAndNonAdminEditIsIgnored — el yapımı
    // bir isteğin bile admin'in koyduğu değeri ezemediğini gösterir.
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())

    fireEvent.click(await screen.findByTitle(/edit|düzenle/i))
    fireEvent.click(screen.getByRole('button', { name: /advanced|gelişmiş/i }))

    expect(screen.queryByText(/custom request headers|özel istek başlıkları/i)).toBeNull()
  })

  it('özel başlık alanı ADMIN için çizilir', async () => {
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(await screen.findByTitle(/edit|düzenle/i))
    fireEvent.click(screen.getByRole('button', { name: /advanced|gelişmiş/i }))

    expect(screen.getByText(/custom request headers|özel istek başlıkları/i)).toBeInTheDocument()
  })

  it('detay modali açılır; kaynak kırılımı çekilir ve tablo çizilir', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [
        { url: 'https://x.com/app.js', type: 'JS', bytes: 512000, duration_ms: 210, http_status: 200, third_party: false },
        { url: 'https://cdn.other/a.png', type: 'IMG', bytes: 2048, duration_ms: 30, http_status: 200, third_party: true },
      ],
      breaches: [{ check_id: 77, checked_at: '2026-08-20T09:00:00' }],
    } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    await waitFor(() => expect(api.monitoring.getPageSpeedResources).toHaveBeenCalledWith(1, { checkId: undefined }))
    expect(await screen.findByText('https://x.com/app.js')).toBeInTheDocument()
    expect(screen.getByText('500 KB')).toBeInTheDocument()
    expect(screen.getByText(/third party|3\. taraf/i)).toBeInTheDocument()
  })

  it('ihlal anına tıklanınca O ölçümün kırılımı çekilir (delil görünümü)', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [], breaches: [{ check_id: 77, checked_at: '2026-08-20T09:00:00' }] } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    // Ihlal anlari artik tek tek dugme DEGIL, tek bir secici: birikince (20'ye kadar) tablonun
    // ustunu iki-uc sira dolduruyorlardi. SearchableSelect mouseDown dinler (click DEGIL).
    await screen.findByText(/threshold breaches on record|eşik ihlali kayıtlı/i)
    fireEvent.mouseDown(document.querySelector('.pspd-snapshot-row .ss-trigger'))
    fireEvent.mouseDown(await screen.findByText('2026-08-20T09:00:00'))
    await waitFor(() => expect(api.monitoring.getPageSpeedResources).toHaveBeenCalledWith(1, { checkId: 77 }))
  })

  it('kısmi ölçüm (capped) modalde açıkça uyarılır', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [withMonitor({ capped: true })] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    expect(await screen.findByText(/resource cap reached|kaynak tavanına ulaşıldı/i)).toBeInTheDocument()
  })

  it('kırılım kırpıldığında KAÇ kaynaktan seçildiği yazılır (sessiz kırpma yok)', async () => {
    // Sunucu en agir 50 satiri dondurur. Kirpma soylenmezse kullanici 2 satiri sayfanin TAMAMI
    // sanip agirligin nereden geldigini yanlis okur — esikleri de ona gore yanlis kurar.
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [
        { url: 'https://x.com/app.js', type: 'JS', bytes: 512000, duration_ms: 210, http_status: 200 },
        { url: 'https://x.com/a.png', type: 'IMG', bytes: 2048, duration_ms: 30, http_status: 200 },
      ],
      total: 183,
      breaches: [],
    } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    expect(await screen.findByText(/183/)).toBeInTheDocument()
  })

  it('kırılım TAM olduğunda kırpma uyarısı ÇIKMAZ', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [{ url: 'https://x.com/app.js', type: 'JS', bytes: 512000, duration_ms: 210, http_status: 200 }],
      total: 1,
      breaches: [],
    } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    await screen.findByText('https://x.com/app.js')
    expect(screen.queryByText(/heaviest of|en ağır/i)).not.toBeInTheDocument()
  })

  it('geciken ESKİ kırılım yanıtı YENİSİNİN üzerine YAZMAZ (yarış koşulu)', async () => {
    // Modal 30 sn'de bir kendini tazeliyor ve kullanici bu sirada baska bir anlik goruntuye
    // gecebiliyor. Once gonderilen istek SONRA donerse tablo yanlis olcumun kaynaklarini gosterir
    // ve kullanici bunu FARK EDEMEZ, cunku ustteki secici DOGRU tarihi yaziyor olur.
    const BR = [{ check_id: 77, checked_at: '2026-08-20T09:00:00' }]
    const rows = (name) => [{ url: `https://x.com/${name}`, type: 'JS', bytes: 1, duration_ms: 1, http_status: 200 }]
    let releaseSlow
    let call = 0
    api.monitoring.getPageSpeedResources.mockImplementation(() => {
      call += 1
      if (call === 2) {   // ihlal anlik goruntusu: YAVAS, en son doner
        return new Promise(r => { releaseSlow = () => r({ success: true, data: {
          resources: rows('ESKI.js'), total: 1, breaches: BR } }) })
      }
      return Promise.resolve({ success: true, data: {
        resources: rows(call === 1 ? 'ILK.js' : 'YENI.js'), total: 1, breaches: BR } })
    })

    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))
    await screen.findByText('https://x.com/ILK.js')

    // Ihlal anlik goruntusune gec (2. istek HAVADA kalir)...
    fireEvent.mouseDown(document.querySelector('.pspd-snapshot-row .ss-trigger'))
    fireEvent.mouseDown(await screen.findByText('2026-08-20T09:00:00'))
    // ...ve daha o donmeden son olcume geri don (3. istek HEMEN doner).
    fireEvent.mouseDown(document.querySelector('.pspd-snapshot-row .ss-trigger'))
    fireEvent.mouseDown(await screen.findByText(/^Son ölçüm$|^Latest measurement$/))
    expect(await screen.findByText('https://x.com/YENI.js')).toBeInTheDocument()

    // Simdi GECIKMIS 2. yanit doner — ekrani DEGISTIRMEMELI.
    await act(async () => { releaseSlow() })
    expect(screen.queryByText('https://x.com/ESKI.js')).not.toBeInTheDocument()
    expect(screen.getByText('https://x.com/YENI.js')).toBeInTheDocument()
  })

  it('bozuk kaynak satırı ekranı ÇÖKERTMEZ (eksik alanlar tire ile çizilir)', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [{ url: 'https://x.com/bozuk', type: null, bytes: null, duration_ms: null, http_status: null }],
      breaches: [] } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    expect(await screen.findByText('https://x.com/bozuk')).toBeInTheDocument()
  })

  it('Kontrol Gecmisi sekmesi COKMEDEN acilir ve olcum satirini cizer', async () => {
    // 2026-08-23 regresyonu: sekmeye yalniz kind/monitorId geciliyordu. CheckHistoryTab satir
    // cizmeyi cagirana birakiyor (columns + renderRow ZORUNLU); eksik olunca map icinde
    // "renderRow is not a function" ile TUM sayfa cokuyordu. Modali acmak yetmiyor —
    // satirin gercekten cizildigini gormek sart, cunku bos listede map hic calismiyor.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      // Degerler monitor fixture'indan BILINCLI olarak farkli: ayni olsalardi sorgu modal
      // ozetindeki rakamlarla karisir ve satirin gercekten cizildigini kanitlamazdi.
      items: [{ checked_at: '2026-08-22T09:15:00', ok: true, breached_metrics: null,
                response_ms: 1234, ttfb_ms: 55, total_bytes: 3 * 1024 * 1024, request_count: 7 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-16T00:00:00', to: '2026-08-23T23:59:59' },
      total: 1, page: 0, size: 50 } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    fireEvent.click(screen.getByRole('button', { name: /check history|kontrol geçmişi/i }))

    expect(await screen.findByText('2026-08-22T09:15:00')).toBeInTheDocument()
    expect(screen.getByText('1234 ms')).toBeInTheDocument()
    expect(screen.getByText('55 ms')).toBeInTheDocument()
    expect(screen.getByText('3.0 MB')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
  })

  it('Kontrol Gecmisi: esik asimi KESINTI degil "esik asildi" olarak etiketlenir', async () => {
    // ok=true + breached_metrics dolu → SLOW. Bunu DOWN gostermek uptime okumasini bozardi.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ checked_at: '2026-08-23T11:00:00', ok: true, breached_metrics: 'LOAD,SIZE',
                response_ms: 9000, ttfb_ms: 120, total_bytes: 99 * 1024 * 1024, request_count: 300 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-16T00:00:00', to: '2026-08-23T23:59:59' },
      total: 1, page: 0, size: 50 } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))
    fireEvent.click(screen.getByRole('button', { name: /check history|kontrol geçmişi/i }))

    // Satir artik ihlal eden metrikleri de yaziyor; "esik asildi" birden fazla dugumde geciyor.
    // Onemli olan DOWN degil SLOW etiketlenmesi.
    expect((await screen.findAllByText(/over threshold|eşik aşıldı/i)).length).toBeGreaterThan(0)
    expect(screen.queryByText(/^Down$|^Kesinti$/i)).toBeNull()
  })

  /**
   * "Esik asildi" TEK BASINA yetmez: kullanici hangi metrigin, hangi esikle, kac olculdugu icin
   * asildigini goremiyordu. Veri zaten kayitliydi (breached_metrics + breach_detail), satir onu
   * okuyup atiyordu.
   */
  it('Kontrol Gecmisi: HANGI esik, kac idi, kac olculdu satirda YAZAR', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ checked_at: '2026-08-25T21:53:19', ok: true, breached_metrics: 'TTFB',
                breach_detail: 'TTFB:1000>2955',
                response_ms: 15094, ttfb_ms: 2955, total_bytes: 44 * 1024 * 1024, request_count: 182 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-16T00:00:00', to: '2026-08-26T23:59:59' },
      total: 1, page: 0, size: 50 } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))
    fireEvent.click(screen.getByRole('button', { name: /check history|kontrol geçmişi/i }))

    expect(await screen.findByText(/TTFB over threshold|TTFB eşiği aşıldı/i)).toBeInTheDocument()
    // Esik ve olculen tek bir kutuda: "1000 → 2955"
    const nums = document.querySelector('.pspd-breach-nums')
    expect(nums).not.toBeNull()
    expect(nums.textContent.replace(/\s+/g, ' ')).toContain('1000')
    expect(nums.textContent).toContain('2955')
  })

  it('Delil YOKSA (eski kayit) satir COKMEZ, yalniz metrik adi yazar', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ checked_at: '2026-08-20T10:00:00', ok: true, breached_metrics: 'LOAD',
                breach_detail: null,
                response_ms: 9000, ttfb_ms: 120, total_bytes: 1024, request_count: 10 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-16T00:00:00', to: '2026-08-26T23:59:59' },
      total: 1, page: 0, size: 50 } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))
    fireEvent.click(screen.getByRole('button', { name: /check history|kontrol geçmişi/i }))

    expect(await screen.findByText(/Load time over threshold|Yükleme eşiği aşıldı/i)).toBeInTheDocument()
  })

  // ── Modal sekmelerinin HEPSI acilmali ──────────────────────────────────────────────────
  //
  // Bu sayfada AYNI hata iki kez yasandi: paylasilan bir sekme bileseni yanlis prop adlariyla
  // cagrildi ve sekme acilinca TUM sayfa coktu (CheckHistoryTab -> "renderRow is not a
  // function", ChangeHistoryTab -> "t is not a function"). Ikisi de derleme hatasi vermez ve
  // sekmeye TIKLANMADAN gorunmez. Bu yuzden her sekme tek tek aciliyor: tiklamak yetiyor,
  // cunku hata render aninda atiliyor.
  it('her modal sekmesi COKMEDEN acilir (her biri kendi verisini ISTER)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-16T00:00:00', to: '2026-08-23T23:59:59' },
      total: 0, page: 0, size: 50 } })
    api.monitoring.getPageSpeedSeries.mockResolvedValue({ success: true, data: {
      series: [], bucket: 'hour', capped: false } })
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [], total: 0 } })
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [] } })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [], total: 0 })

    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    // Sekme -> o sekme MONTE OLDUYSA cagrilacak API. Tek basina "modal ayakta" demek yetmiyor:
    // lazy sekmeler (Notlar, Degisiklikler) Suspense arkasinda oldugu icin, cagriyi beklemeden
    // test bilesen hic monte olmadan bitiyordu ve mutasyon yakalanmiyordu (olculdu).
    const tabs = [
      [/^chart$|^grafik$/i,              () => api.monitoring.getPageSpeedSeries],
      [/check history|kontrol geçmişi/i, () => api.monitoring.getCheckHistory],
      [/^alarms$|^alarmlar$/i,           () => api.admin.getAlerts],
      [/^notes$|^notlar$/i,              () => api.monitoring.getMonitorNotes],
      [/^changes$|^değişiklikler$/i,     () => api.monitoring.getChanges],
      [/^resources$|^kaynaklar$/i,       () => api.monitoring.getPageSpeedResources],
    ]
    // ZAMAN ASIMI NEDEN 15 sn: alti sekmenin UCU lazy() + Suspense arkasinda
    // (ResponseTimeChart, MonitorNotes, ChangeHistoryTab — PageSpeedMonitorPage:37,47,48).
    // Dinamik import cozumu + render + efekt + istek, TAM SUIT paralel kosarken 5 sn'yi
    // asabiliyor: bu test 2026-09-07'de tam suitte BIR KEZ boyle dustu (yigin izi bu satiri
    // gosterdi), izole kosumda ve tekrar kosumda gecti. IDDIA DEGISMEDI — yalnizca sabir
    // artti; testler hizli oldugunda bu deger hicbir sey maliyet etmez, yavas bir runner'da
    // ise sahte kirmizi uretmez.
    for (const [name, apiFn] of tabs) {
      fireEvent.click(screen.getByRole('button', { name }))
      await waitFor(() => expect(apiFn()).toHaveBeenCalled(), { timeout: 15000 })
      expect(screen.getAllByText('https://x.com/odeme').length).toBeGreaterThan(0)
    }
  })


  // ── Grafik sekmesi duzeni ─────────────────────────────────────────────────────────────
  //
  // Ekranda iki ayni gorunen etiketsiz dugme sirasi vardi (ustteki metrik, alttaki zaman
  // araligi) ve hangisinin ne yaptigi anlasilmiyordu. Metrik artik ETIKETLI bir segmented
  // control; aralik dugmeleri grafigin kendi satirinda kaliyor.

  async function openChart() {
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))
    fireEvent.click(screen.getByRole('button', { name: /^chart$|^grafik$/i }))
  }

  it('metrik secimi ETIKETLI ve zaman araligindan ayri bir gruptur', async () => {
    await openChart()

    // Etiket olmadan iki dugme sirasi ayirt edilemiyordu.
    expect(await screen.findByText(/^metric$|^metrik$/i, {}, { timeout: 5000 })).toBeInTheDocument()
    const group = screen.getByRole('group', { name: /metric|metrik/i })
    expect(group).toBeInTheDocument()
    // Dort metrik ayni grubun icinde; zaman araligi dugmeleri BU grupta DEGIL.
    expect(within(group).getAllByRole('button')).toHaveLength(4)
    expect(within(group).queryByRole('button', { name: /24h/i })).toBeNull()
  })

  it('metrik degistirince sunucudan O metrik istenir (yeni tarama uretmez)', async () => {
    api.monitoring.getPageSpeedSeries.mockResolvedValue({ success: true, data: {
      series: [], bucket: 'hour', capped: false } })
    await openChart()
    // Grafik LAZY yukleniyor: dinamik import + ilk istek, paralel kosuda waitFor'un 1 sn'lik
    // varsayilanini asabiliyor (olculdu: ~1,1 sn). Sure verilmezse test yuke gore rastgele duser.
    const SLOW = { timeout: 5000 }
    await waitFor(() => expect(api.monitoring.getPageSpeedSeries).toHaveBeenCalled(), SLOW)

    const group = screen.getByRole('group', { name: /metric|metrik/i })
    fireEvent.click(within(group).getByRole('button', { name: /^size$|^boyut$/i }))

    await waitFor(() => {
      const last = api.monitoring.getPageSpeedSeries.mock.calls.at(-1)
      expect(last[1]).toMatchObject({ metric: 'size' })
    }, SLOW)
  })

  it('secili metrik aktif olarak isaretlenir (aria-pressed)', async () => {
    await openChart()
    const group = await screen.findByRole('group', { name: /metric|metrik/i }, { timeout: 5000 })

    expect(within(group).getByRole('button', { name: /load time|yükleme süresi/i }))
      .toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(within(group).getByRole('button', { name: /^ttfb$/i }))
    expect(within(group).getByRole('button', { name: /^ttfb$/i })).toHaveAttribute('aria-pressed', 'true')
    expect(within(group).getByRole('button', { name: /load time|yükleme süresi/i }))
      .toHaveAttribute('aria-pressed', 'false')
  })

  // ── Kırpılmış ölçüm: rakam ALT SINIR olduğunda bunu SÖYLE ─────────────────────────────
  //
  // Tek bir dev dosya boyut tavanında kesildiğinde toplam gerçek değeri göstermez. Çıplak sayı
  // basmak sessiz bir yalandır: kullanıcı eşiğini o eksik toplama göre kurar ve ilk tam ölçümde
  // beklenmedik alarm alır.

  it('kırpılmış TOPLAM kartta ve modalde "≥" ile gösterilir', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({
      success: true, data: [withMonitor({ bytes_truncated: true })] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText('≥ 2.0 MB')).toBeInTheDocument()

    fireEvent.click(screen.getByText('https://x.com/odeme'))
    expect(await screen.findByText(/lower bound|alt sınır/i)).toBeInTheDocument()
  })

  it('kırpılmış KAYNAK satırı "≥" ile gösterilir, kırpılmayan gösterilmez', async () => {
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: {
      resources: [
        { url: 'https://x.com/video.mp4', type: 'OTHER', bytes: 10 * 1024 * 1024, duration_ms: 900, http_status: 200, third_party: false, truncated: true },
        { url: 'https://x.com/app.js', type: 'JS', bytes: 512000, duration_ms: 210, http_status: 200, third_party: false, truncated: false },
      ],
      breaches: [] } })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('https://x.com/odeme'))

    expect(await screen.findByText('≥ 10.0 MB')).toBeInTheDocument()
    expect(screen.getByText('500 KB')).toBeInTheDocument()      // kırpılmayan satırda "≥" YOK
  })

  // ── Önerilen eşikler ──────────────────────────────────────────────────────────────────

  const testResult = {
    status: 'OK', reachable: true, http_status: 200,
    ttfb_ms: 90, html_ms: 300, response_ms: 3297,
    total_bytes: 46.4 * 1024 * 1024, request_count: 183, failed_count: 0,
    capped: false, bytes_truncated: false, error: null, resources: [],
  }

  /** Formu açıp "Şimdi Dene"yi koşturur. */
  async function openFormAndTest(result = testResult) {
    api.monitoring.testPageSpeed.mockResolvedValue({ success: true, data: result })
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.com' } })
    fireEvent.click(screen.getByRole('button', { name: /try now|şimdi dene/i }))
    await waitFor(() => expect(api.monitoring.testPageSpeed).toHaveBeenCalled())
  }

  it('URL BOŞken "Şimdi Dene" kapalıdır ve sebebini söyler', async () => {
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))

    const btn = screen.getByRole('button', { name: /try now|şimdi dene/i })
    expect(btn).toBeDisabled()
    // Sessizce tıklanmayan buton kullanıcıya arıza gibi görünür — sebep title'da yazılı.
    expect(btn).toHaveAttribute('title', expect.stringMatching(/url/i))

    fireEvent.click(btn)
    expect(api.monitoring.testPageSpeed).not.toHaveBeenCalled()
  })

  it('URL yazılınca "Şimdi Dene" açılır ve title kalkar', async () => {
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageSpeedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /add monitor|izleme ekle/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.com' } })

    const btn = screen.getByRole('button', { name: /try now|şimdi dene/i })
    expect(btn).not.toBeDisabled()
    expect(btn).not.toHaveAttribute('title')
  })

  it('ölçümden sonra önerilen eşikler GEREKÇESİYLE gösterilir', async () => {
    await openFormAndTest()

    expect(await screen.findByText(/suggested by this measurement|önerilen eşikler/i)).toBeInTheDocument()
    expect(screen.getByText('7000 ms')).toBeInTheDocument()
    expect(screen.getByText('300 ms')).toBeInTheDocument()
    expect(screen.getByText('55000 KB')).toBeInTheDocument()
    expect(screen.getByText('220')).toBeInTheDocument()
    // Gerekçe olmadan sayı, kullanıcının doğrulayamayacağı bir kara kutu olurdu.
    expect(screen.getByText(/twice the measured|ölçülenin 2 katı/i)).toBeInTheDocument()
  })

  it('butona basınca dört eşik forma yazılır ve kaydedilince payload\'a girer', async () => {
    api.monitoring.createPageSpeedMonitor.mockResolvedValue({ success: true, data: monitor })
    await openFormAndTest()

    fireEvent.click(await screen.findByRole('button', { name: /apply suggested|önerilen eşikleri uygula/i }))

    const blanks = screen.getAllByPlaceholderText(/no threshold|eşik yok/i)
    expect(blanks.map(el => el.value)).toEqual(['7000', '300', '55000', '220'])

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createPageSpeedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.createPageSpeedMonitor.mock.calls[0][0]
    expect(payload).toMatchObject({ maxLoadMs: 7000, maxTtfbMs: 300, maxPageKb: 55000, maxRequests: 220 })
  })

  it('KISMİ ölçümde öneri yine çıkar ama güvenilmez olduğu UYARIYLA söylenir', async () => {
    await openFormAndTest({ ...testResult, bytes_truncated: true })

    expect(await screen.findByText(/partial|kısmi/i)).toBeInTheDocument()
    // Öneri gizlenmez: karar kullanıcınındır, ama bilerek versin.
    expect(screen.getByRole('button', { name: /apply suggested|önerilen eşikleri uygula/i })).toBeInTheDocument()
  })

  it('ölçüm BAŞARISIZsa öneri hiç gösterilmez (eşik türetilecek veri yok)', async () => {
    await openFormAndTest({ ...testResult, status: 'DOWN', reachable: false, error: 'sayfa alınamadı' })

    expect(screen.queryByRole('button', { name: /apply suggested|önerilen eşikleri uygula/i })).toBeNull()
  })
})

describe('formatBytes', () => {
  it('bayt eşiklerinde okunur birime geçer', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1024 * 1024 - 1)).toBe('1024 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB')
    expect(formatBytes(2.5 * 1024 * 1024)).toBe('2.5 MB')
  })

  it('null/anlamsız değerde tire döner (uydurma 0 yazmaz)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('abc')).toBe('—')
  })
})
