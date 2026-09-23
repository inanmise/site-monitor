import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, fillGroupAndTags } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:    vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createHttpMonitor: vi.fn(),
      updateHttpMonitor: vi.fn(),
      deleteHttpMonitor: vi.fn(),
      triggerHttpCheck:  vi.fn(),
      testHttp:          vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Example', url: 'https://www.example.com/', method: 'GET', expected_status: '201-204',
  group_name: 'X Sistemleri', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12,
  interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('HttpMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createHttpMonitor.mockResolvedValue({ success: true, data: {} })
  })

  // ── AG HATASI YOLU (E3) ────────────────────────────────────────────────────
  //
  // api/client.js request() ag hatasinda {success:false} DONDURMEZ, throw eder (yalniz
  // AbortError yumusak payload doner) ve bu cagrida timeoutMs verilmedigi icin abort yolu da
  // devrede degil. `load` try/catch tasimadigi icin promise reject oluyor, setLoadError de
  // setLoading(false) de HIC calismiyordu: ekran iskelette kaliyor, hata bandi cikmiyordu.
  //
  // apiMock yalnizca RESOLVE eden yanitlar uretiyordu; bu yol tum sayfa testlerinde kapsam
  // disiydi ve hata dali yazili olmasina ragmen en sik tetiklenen hata turunde calismiyordu.

  it('E3: api REJECT ederse hata bandi cizilir ve spinner kaybolur (sonsuz iskelet YOK)', async () => {
    api.monitoring.getHttpMonitors.mockRejectedValue(new Error('Failed to fetch'))

    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .toBeInTheDocument()
    // Hatanin kendisi de gosterilmeli — kullanici "neden" sorusuna cevap alsin.
    expect(screen.getByText(/failed to fetch/i)).toBeInTheDocument()
    // "Henuz izleme yok" bos durumu GORUNMEMELI: silinmis sanma hatasinin ta kendisi.
    expect(screen.queryByText(/henüz.*izleme yok|no monitors/i)).not.toBeInTheDocument()
  })

  it('E3: yeniden dene basarili olunca hata bandi kalkar ve liste cizilir', async () => {
    api.monitoring.getHttpMonitors.mockRejectedValueOnce(new Error('Failed to fetch'))

    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText(/izleme listesi yüklenemedi|could not load the monitor list/i)

    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    fireEvent.click(screen.getByRole('button', { name: /yeniden dene|retry/i }))

    expect(await screen.findByText('https://www.example.com/')).toBeInTheDocument()
    expect(screen.queryByText(/izleme listesi yüklenemedi|could not load the monitor list/i))
      .not.toBeInTheDocument()
  })

  it('izleme kartını (url) listeler', async () => {
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.example.com/')).toBeInTheDocument()
  })

  // ── Şemasız URL sahte alarmı (2026-08-04): giriş normalizasyonu ────────────
  it('URL alanı: şemasız girdi https:// ile tamamlanır, http:// korunur; Kaydet normalize URL gönderir', async () => {
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
    const url = screen.getByPlaceholderText('https://example.com')

    fireEvent.change(url, { target: { value: 'http://internal.host:8080/health' } })
    fireEvent.blur(url)
    expect(url.value).toBe('http://internal.host:8080/health')   // bilinçli http:// tercihi korunur

    fireEvent.change(url, { target: { value: 'www.axess.com.tr' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))   // alandan çıkmadan kaydet
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.createHttpMonitor.mock.calls[0][0].url).toBe('https://www.axess.com.tr')
  })

  // ── Etiket filtresi + grup/etiket metin araması (2026-09-18): dokuz sayfada varsayılan; Http temsilci ──
  it('araç çubuğunda etiket filtresi: seçilen etiket listeyi daraltır; arama kutusu etiket/grup metninde de eşleşir', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, id: 1, url: 'https://a.example.com/', tags: 'prod, kritik' },
      { ...monitor, id: 2, url: 'https://b.example.com/', tags: 'edge', group_name: 'Ödeme' },
      { ...monitor, id: 3, url: 'https://c.example.com/', tags: '' },
    ] })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://a.example.com/')
    const toolbar = document.querySelector('.upt-toolbar')
    // Etiket kutusu: "Tüm etiketler" tetikleyicisi
    const tagTrigger = [...toolbar.querySelectorAll('.ss-trigger')].find((b) => /tüm etiketler|all tags/i.test(b.textContent))
    expect(tagTrigger).toBeTruthy()
    fireEvent.mouseDown(tagTrigger)
    const labels = [...toolbar.querySelectorAll('.ss-option')].map((o) => o.textContent.trim())
    expect(labels).toEqual(expect.arrayContaining(['edge', 'kritik', 'prod']))
    expect(labels.some((l) => /etiketsiz|untagged/i.test(l))).toBe(true)   // id 3 etiketsiz → seçenek var
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-option')].find((o) => o.textContent.trim() === 'kritik'))
    await waitFor(() => expect(screen.queryByText('https://b.example.com/')).toBeNull())
    expect(screen.getByText('https://a.example.com/')).toBeInTheDocument()
    expect(screen.queryByText('https://c.example.com/')).toBeNull()

    // Filtreyi sıfırla, serbest metinle grup adı ara
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-trigger')].find((b) => /kritik/.test(b.textContent)))
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-option')].find((o) => /tüm etiketler|all tags/i.test(o.textContent)))
    fireEvent.change(toolbar.querySelector('.upt-search'), { target: { value: 'ödeme' } })
    await waitFor(() => expect(screen.getByText('https://b.example.com/')).toBeInTheDocument())
    expect(screen.queryByText('https://a.example.com/')).toBeNull()
  })

  it('USER rolünde de grup/etiket filtreleri GÖRÜNEN listenin tamamından türer (başka takımın grubu/etiketi seçilebilir)', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, id: 1, url: 'https://own.example.com/', team_id: 5, team_name: 'SY-A', group_name: 'Kendi Grubu', tags: 'kendi' },
      { ...monitor, id: 2, url: 'https://other.example.com/', team_id: 9, team_name: 'SY-B', group_name: 'Öteki Grup', tags: 'öteki' },
    ] })
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://own.example.com/')
    const toolbar = document.querySelector('.upt-toolbar')
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-trigger')].find((b) => /tüm gruplar|all groups/i.test(b.textContent)))
    expect([...toolbar.querySelectorAll('.ss-option')].map((o) => o.textContent.trim())).toEqual(expect.arrayContaining(['Kendi Grubu', 'Öteki Grup']))
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-option')].find((o) => o.textContent.trim() === 'Öteki Grup'))
    await waitFor(() => expect(screen.queryByText('https://own.example.com/')).toBeNull())
    expect(screen.getByText('https://other.example.com/')).toBeInTheDocument()
    fireEvent.mouseDown([...toolbar.querySelectorAll('.ss-trigger')].find((b) => /tüm etiketler|all tags/i.test(b.textContent)))
    expect([...toolbar.querySelectorAll('.ss-option')].map((o) => o.textContent.trim())).toEqual(expect.arrayContaining(['kendi', 'öteki']))
  })

  // ── Alarm seviyesi (2026-09-19): formda seçilir, payload'a alertLevel gider; düzenlemede kayıtlı seviye yüklenir ──
  it('alarm seviyesi: yeni izlemede varsayılan Uyarı; Kritik seçilince createHttpMonitor alertLevel:CRITICAL alır; düzenlemede alert_level formu doldurur', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, alert_level: 'HIGH' }] })
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
    const modal = document.querySelector('.modal-box')
    expect(modal.querySelector('.notify-level-btn--warning').getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(modal.querySelector('.notify-level-btn--critical'))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://lvl.example.com' } })
    await fillGroupAndTags()
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.createHttpMonitor.mock.calls[0][0].alertLevel).toBe('CRITICAL')
    await waitFor(() => expect(document.querySelector('.modal-box')).toBeNull())   // başarılı kayıt modalı kapatır
    // Düzenle: kayıtlı HIGH forma gelir
    fireEvent.click(screen.getByRole('button', { name: /düzenle|edit/i }))
    await waitFor(() => expect(document.querySelector('.modal-box .notify-level-btn--high').getAttribute('aria-pressed')).toBe('true'))
  })

  // ── Grup + etiket zorunlu (2026-09-18): dokuz sayfa aynı kapıyı taşır; Http temsilci ──
  it('yeni izleme: grup seçilmeden Kaydet → grup hatası, etiket girilmeden → etiket hatası; create ÇAĞRILMAZ', async () => {
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    expect(await screen.findByText(/grup seçimi zorunludur|a group is required/i)).toBeInTheDocument()
    expect(api.monitoring.createHttpMonitor).not.toHaveBeenCalled()
    // Grup seçilip etiket yine boş bırakılırsa ikinci kapı
    await fillGroupAndTags({ tag: '' })
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    expect(await screen.findByText(/en az bir etiket zorunludur|at least one tag is required/i)).toBeInTheDocument()
    expect(api.monitoring.createHttpMonitor).not.toHaveBeenCalled()
    // Zorunlu yıldızları: grup ve etiket başlığı
    const modal = document.querySelector('.modal-box')
    expect([...modal.querySelectorAll('.req-star')].length).toBeGreaterThanOrEqual(3)   // takım + grup + etiket
  })

  // ── Çok takımlı kullanıcı (2026-09-18): takım kutusu AÇIK, ikincil takım seçilip gönderilir ──
  it('USER + 2 takım: takım kutusu açılır, "takımsız" seçeneği YOK, ikincil takım payload\'a gider; admin takım ucu ÇAĞRILMAZ', async () => {
    const myTeams = [{ id: 5, name: 'SY-A' }, { id: 9, name: 'SY-B' }]
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={myTeams} />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))

    // Kutu açık (kilitli input değil) ve birincil takım seçili gelir
    const modal = document.querySelector('.modal-box')
    const trigger = [...modal.querySelectorAll('.ss-trigger')].find((b) => /SY-A/.test(b.textContent))
    expect(trigger).toBeTruthy()
    fireEvent.mouseDown(trigger)   // açılış onMouseDown ile
    const labels = [...modal.querySelectorAll('.ss-option')].map((o) => o.textContent.trim())
    expect(labels).toEqual(['SY-A', 'SY-B'])   // takımsız seçenek yok: üye için takım zorunlu
    fireEvent.mouseDown([...modal.querySelectorAll('.ss-option')].find((o) => o.textContent.trim() === 'SY-B'))

    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://iki.example.com' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.createHttpMonitor.mock.calls[0][0].teamId).toBe(9)
    expect(api.admin.getTeams).not.toHaveBeenCalled()
  })

  it('USER + tek takım: takım kutusu kilitli input olarak kalır (regresyon)', async () => {
    render(<HttpMonitorPage systemRole="USER" teamId={5} teamName="SY-A" myTeams={[{ id: 5, name: 'SY-A' }]} />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör|yeni izleme/i }))
    const modal = document.querySelector('.modal-box')
    const locked = [...modal.querySelectorAll('input[disabled]')].find((i) => i.value === 'SY-A')
    expect(locked).toBeTruthy()
    expect([...modal.querySelectorAll('.ss-trigger')].some((b) => /SY-A/.test(b.textContent))).toBe(false)
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse veya save() içinde
    // sessizce varsayılana dönerse tam-payload karşılaştırması kırılır.
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'Example', url: 'https://www.example.com/', status: 'up', checked_at: '2026-06-24T00:00:00',
      method: 'POST', expected_status: '201-204', follow_redirects: false, verify_ssl: true,
      group_name: 'Kurumsal', team_id: 5, team_name: 'SY-A', tags: 'prod,kritik', alert_level: 'HIGH', notify_email: false,
      check_ssl_errors: true, ssl_expiry_reminders: true, domain_expiry_reminders: true,
      ssl_reminder_days: '45,20,5', domain_reminder_days: '60,30,10',
      interval_seconds: 600, timeout_ms: 7000,
      confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
      active: false, notification_group_id: 7,
    }] })

    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.example.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(screen.getByPlaceholderText('https://www.example.com/').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateHttpMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createHttpMonitor.mock.calls[0][0]).toEqual({
      name: 'Example (Kopya)', url: 'https://www.example.com/', method: 'POST',
      expectedStatus: '201-204', followRedirects: false, verifySsl: true, useProxy: 'AUTO',   // vekil tercihi (2026-09-21): kaynakta yok → AUTO
      groupName: 'Kurumsal', teamId: 5, tags: 'prod,kritik', alertLevel: 'HIGH', notifyEmail: false, notifyWebhook: true,
      checkSslErrors: true, sslExpiryReminders: true, domainExpiryReminders: true,
      sslReminderDays: '45,20,5', domainReminderDays: '60,30,10',
      intervalSeconds: 600, timeoutMs: 7000,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitsin.
      notificationGroupId: 7,
    })
  })

  it('Kopyala → değiştirmeden kaydet: backend "zaten izleniyor" hatası toast ile gösterilir, modal kapanmaz', async () => {
    api.monitoring.createHttpMonitor.mockResolvedValue({
      success: false,
      error: 'Bu URL bu takımda zaten izleniyor; mükerrer HTTP monitörü oluşturulamaz.',
    })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    await screen.findByText('https://www.example.com/')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createHttpMonitor).toHaveBeenCalled())
    expect(await screen.findByText(/zaten izleniyor/i)).toBeInTheDocument()
    // Modal açık kalır (veri kaybı yok) → Kopya rozeti hâlâ DOM'da
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
  })

  it('deep-link regresyonu: ?monitor= SON sayfadaki kayda işaret ederken modal yine açılır', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many })
    window.history.replaceState({}, '', '/?monitor=120')   // 3. sayfadaki kayıt
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
      // Detay modalı listede görünürlüğe bağlı DEĞİL — ham monitors.find ile açılır.
      expect(await screen.findByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
      expect(screen.getAllByText('https://m120.example.com/').length).toBeGreaterThan(0)
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, url: `https://m${i + 1}.example.com/` }))
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
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
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('https://m1.example.com/')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('eski e-posta formatı ?tab=http&monitor=1 modal açar; param artık URL DE KALIR (yeni davranış)', async () => {
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      expect(await screen.findByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()   // modal açık
      await waitFor(() => expect(window.location.search).toContain('monitor=1'), { timeout: 1500 })
    } finally { window.history.replaceState({}, '', '/') }
  })

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerHttpCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())

      const runBtn = document.querySelector('.mon-act--check')
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerHttpCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('API hata dönünce "izleme yok" DEĞİL, hata bandı gösterir (silindi sanma bekçisi)', async () => {
    // Eskiden load()'da else dalı yoktu: liste bos kalip "Henüz izleme yok, ekleyin" cikiyordu.
    // Kullanici monitorlerinin silindigini saniyordu; ustelik 60 sn'lik polling sessizce basarisiz
    // olmaya devam ediyordu.
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: false, error: 'HTTP 503 — servis kullanilamiyor' })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()
    expect(screen.queryByText(/no monitors|henüz izleme/i)).toBeNull()
  })

  it('gercekten bos liste hata DEGIL, bos durum gosterir', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [] })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // ── Sayfa düzeyi toplu kontrol ──────────────────────────────────────
  it('toplu kontrol: takım seçici → başlat → aday başına bir tetikleme + koşum tablosu', async () => {
    localStorage.clear()
    api.monitoring.triggerHttpCheck.mockResolvedValue({
      success: true, data: { ...monitor, http_status: 200, response_ms: 42 },
    })
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getHttpMonitors).toHaveBeenCalled())

    // Araç çubuğu düğmesi kartın tekil ▶ düğmesinden AYRI: erişilebilir adı sayfa kapsamını söyler.
    fireEvent.click(screen.getByTitle(/bu sayfadaki 1 izlemenin|check all 1 monitors/i))
    fireEvent.click(await screen.findByRole('button', { name: /kontrolü başlat|start check/i }))

    await waitFor(() => expect(api.monitoring.triggerHttpCheck).toHaveBeenCalledTimes(1))
    expect(api.monitoring.triggerHttpCheck).toHaveBeenCalledWith(1)
    await waitFor(() => expect(document.querySelector('.chk-modal')).not.toBeNull())
    expect(document.querySelectorAll('.chk-td-status').length).toBe(1)
  })

  // Regression: ISSUE-002 — 32 satırın "ayrıntı" düğmesi ekran okuyucuda AYNI adı taşıyordu
  // Found by /qa on 2026-09-23
  // Report: .gstack/qa-reports/
  it('Detay düğmelerinin erişilebilir adı ZAMANI taşır — aynı hata tekrarlasa da satırlar ayırt edilir', async () => {
    // Sahada tipik durum: aynı monitörün 30+ satırı aynı hatayı taşıyor ("HTTP connect timed out").
    // aria-label yalnız hata metnini taşıyınca klavye/ekran okuyucu kullanıcısı Tab ile gezerken
    // her düğmede AYNI adı duyuyor ve hangi kontrolde olduğunu ayırt edemiyordu.
    const ayniHata = 'HTTP connect timed out'
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 1, checked_at: '2026-09-22T20:02:10', ok: false, http_status: null, response_ms: null, error: ayniHata },
        { id: 2, checked_at: '2026-09-22T19:57:03', ok: false, http_status: null, response_ms: null, error: ayniHata },
        { id: 3, checked_at: '2026-09-22T19:51:44', ok: false, http_status: null, response_ms: null, error: ayniHata },
      ],
      counts: { total: 3, fail: 3 }, buckets: [], alerts: [], range: {}, total: 3, page: 0, size: 50,
    } })
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

      const dugmeler = await screen.findAllByRole('button', { name: /ayrı pencerede aç|own window/i })
      expect(dugmeler).toHaveLength(3)

      const adlar = dugmeler.map(b => b.getAttribute('aria-label'))
      expect(new Set(adlar).size).toBe(3)                       // ÜÇÜ DE farklı
      adlar.forEach(a => expect(a).toContain(ayniHata))         // hata metni korunuyor
      expect(adlar[0]).toContain('2026-09-22T20:02:10')         // zaman damgası adda
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  it('geçmişteki başarısız satırın Detay hücresi DÜĞMEDİR ve tanıyı KENDİ penceresinde açar', async () => {
    // 2026-09-23 kullanıcı isteği: tanı listenin altında satır içi değil, ayrı bir pencerede açılsın ve
    // hücre tıklanabilir görünsün. Eskiden salt `cursor:pointer` taşıyan bir span'di — ne düğmeydi
    // (Tab ile erişilemiyordu) ne de tıklanabilir duruyordu.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{
        id: 9, checked_at: '2026-09-22T20:02:10', ok: false, http_status: null, response_ms: 79,
        error: 'No name matching qa.example.com found',
        error_detail: JSON.stringify({ kind: 'HOSTNAME_MISMATCH', phase: 'TLS', scheme: 'https', method: 'GET', url: 'https://qa.example.com/' }),
      }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [], range: {}, total: 1, page: 0, size: 50,
    } })
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    try {
      render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)

      const open = await screen.findByRole('button', { name: /ayrı pencerede aç|own window/i })
      expect(open.tagName).toBe('BUTTON')                              // klavyeyle açılabilir
      expect(open.textContent).toMatch(/ayrıntı|details/i)             // tıklanabilir olduğunu SÖYLÜYOR
      expect(screen.queryByTestId('http-error-detail')).toBeNull()     // tıklamadan önce panel yok

      fireEvent.click(open)

      const panel = await screen.findByTestId('http-error-detail')
      // Sayfada başka role="dialog" yok (izleme detay modalı elle kurulmuş, rol taşımıyor):
      // bu yüzden bu iddia "satır içi değil, ModalShell penceresinde" demenin kesin yolu.
      expect(panel.closest('[role="dialog"]')).not.toBeNull()
    } finally { window.history.replaceState({}, '', '/') }
  })
})
