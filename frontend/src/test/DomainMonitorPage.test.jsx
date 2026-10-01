import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, fillGroupAndTags } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import { eppLabel } from '../utils/domainEpp.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:          vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDomainMonitors:   vi.fn(),
      getDomainHistory:    vi.fn(),
      // Detay modali paylasilan CheckHistoryTab'i mount ediyor -> bu iki uc mock'ta OLMAK ZORUNDA,
      // yoksa modal render'i patlar ve kart hic cizilmez (diger 5 sayfa testinde zaten var).
      getCheckHistory:       vi.fn(() => Promise.resolve({ success: true, data: {
        items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createDomainMonitor: vi.fn(),
      updateDomainMonitor: vi.fn(),
      deleteDomainMonitor: vi.fn(),
      triggerDomainCheck:  vi.fn(),
      testDomain:          vi.fn(),
      monitorDefaults:     vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'example', domain: 'example.com.tr', team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod',
  status: 'OK', source: 'RDAP', days_remaining: 120, expiry_date: '2026-08-13', registrar: 'TR Registry',
  status_codes: ['clientTransferProhibited'], nameservers: ['ns1.example.com.tr'], ns_resolves: true,
  active: true, interval_seconds: 86400, warning_days: 30, critical_days: 7, checked_at: '2026-07-10T00:00:00',
}

describe('DomainMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { domain: { intervalSeconds: 86400, warningDays: 30, criticalDays: 7, thresholds: '60,30,14,7,3,1' } } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('alan adı monitörünü listeler', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    expect(await screen.findByText('example.com.tr')).toBeInTheDocument()
    expect(screen.getByText('TR Registry')).toBeInTheDocument()
  })

  it('Yeni Monitör butonu ADMIN için modal açar (alan adı alanı)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    expect(screen.getByText(/new domain monitor|yeni alan adı monit/i)).toBeInTheDocument()
  })

  it('alan adı girip kaydet → createDomainMonitor doğru payload ile çağrılır', async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)   // USER → takım otomatik dolar (zorunlu takım)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.createDomainMonitor.mock.calls[0][0].domain).toBe('example.org')
  })

  /**
   * Koruma anahtarlarinin VARSAYILANLARI payload'a girmeli.
   *
   * Kilit ve degisiklik ACIK (bugunku fiili davranisin devami), kara liste KAPALI: her kontrolde
   * dis DNS sorgusu uretir ve bilincli acilmalidir. Bir anahtar payload'a hic girmezse backend
   * kendi varsayilanini yazar ve kullanicinin ekranda gordugu ile kaydedilen AYRISIR.
   */
  /**
   * Sunucu alan adini KAYITLI alan adina (eTLD+1) indirgiyor: kayit bilgisi bir HOST'a degil
   * alan adinin kendisine aittir. Kullanici "www.x.com yazdim ama www silindi" diye bildirdi;
   * alan altindaki ipucu bunu yaziyordu ama surpriz KAYDETTIKTEN sonra yasaniyordu.
   *
   * Indirgeme SUNUCUNUN dondurdugu degerle duyurulur — eTLD+1 kurali JS'te ikinci kez
   * yazilmaz (iki kopya kacinilmaz olarak ayrisir).
   */
  it("indirgeme olduysa kaydedilen alan adi kullaniciya SOYLENIR", async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({
      success: true, data: { id: 9, domain: 'example.com' },   // www. sunucuda dustu
    })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'www.example.com' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    // Sessizce degistirmek "kaydim bozuldu" hissi veriyordu; ne olduğu yazili olmali.
    expect(await screen.findByText(/www\.example\.com/)).toBeInTheDocument()
  })

  it("indirgemeYOKSA sade kaydedildi mesaji cikar", async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({
      success: true, data: { id: 9, domain: 'example.org' },
    })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(screen.queryByText(/olarak kaydedildi|Saved as/i)).toBeNull()
  })

  it("yeni izleme: koruma anahtarlarinin varsayilanlari payload'a girer", async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    const p = api.monitoring.createDomainMonitor.mock.calls[0][0]
    expect(p.transferLockAlert).toBe(true)
    expect(p.changeAlert).toBe(true)
    expect(p.blacklistEnabled).toBe(false)
  })

  it("kara liste anahtari acilabilir ve payload'a AÇIK gider", async () => {
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })
    render(<DomainMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    fireEvent.click(screen.getByLabelText(/blacklist|kara liste/i))
    await fillGroupAndTags()   // grup + etiket zorunlu (2026-09-18)
    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.createDomainMonitor.mock.calls[0][0].blacklistEnabled).toBe(true)
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (yalnız ad "(Kopya)" olur)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [{
      id: 1, name: 'example', domain: 'example.com.tr', status: 'OK', source: 'RDAP',
      checked_at: '2026-07-10T00:00:00',
      team_id: 3, team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod',
      thresholds_csv: '90,45,10,2', warning_days: 45, critical_days: 9,
      interval_seconds: 43200, check_timeout_ms: 12000, active: false, notification_group_id: 7, noc_notify: true, noc_group_ids: [2, 3],
      // Koruma anahtarlari da varsayilanin TERSI: biri formFrom'dan duserse
      // tam-payload karsilastirmasi kirilir (bu testin varlik sebebi).
      transfer_lock_alert: false, blacklist_enabled: true, change_alert: false,
    }] })
    api.monitoring.createDomainMonitor.mockResolvedValue({ success: true, data: {} })

    render(<DomainMonitorPage systemRole="ADMIN" teamId={3} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    await screen.findByText('example.com.tr')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu, kaynak belli)
    expect(document.querySelector('[data-slot="duplicate-badge"]')).not.toBeNull()
    expect(screen.getByText(/kaynak izlemenin birebir kopyası|an exact copy of the source monitor/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText('example.com.tr').value).toMatch(/\(Kopya\)$/)

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createDomainMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateDomainMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createDomainMonitor.mock.calls[0][0]).toEqual({
      name: 'example (Kopya)', domain: 'example.com.tr', groupName: 'Kurumsal', tags: 'prod', teamId: 3,
      thresholdsCsv: '90,45,10,2', warningDays: 45, criticalDays: 9,
      intervalSeconds: 43200, checkTimeoutMs: 12000,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitsin.
      notificationGroupId: 7,
      // 7/24 izleme ekibi (2026-09-27): açık anahtar + açık grup seçimi de kopyalanır
      nocNotify: true, nocGroupIds: [2, 3],
      transferLockAlert: false, blacklistEnabled: true, changeAlert: false,
      alertLevel: 'WARNING', notifyEmail: true, notifyWebhook: true,
      // B3: dogrulama/kurtarma alanlari bu iki ture eklendi (eskiden yalniz global ayar vardi).
      // "TUM ayarlar birebir kopyalanir" iddiasi degismedi; kume dort alan buyudu.
      confirmAttempts: 3, confirmIntervalSeconds: 30, recoveryChecks: 3, recoveryIntervalSeconds: 30,
    })
  })

  it('ADMIN: takım seçilmeden Kaydet devre dışı (zorunlu takım)', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByPlaceholderText('example.com'), { target: { value: 'example.org' } })
    expect(screen.getByRole('button', { name: /^save$|^kaydet$/i })).toBeDisabled()   // takım yok → engellendi
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ ...monitor, id: i + 1, domain: `d${i + 1}.example.org` }))
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: many })
    const { container, unmount } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())
    await screen.findByText('d1.example.org')
    expect(container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('d51.example.org')
    expect(screen.queryByText('d1.example.org')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: many.slice(0, 30) })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('d1.example.org')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  }, 45000)   // 120 kayıt × yeniden tasarlanmış kart: yalnız ~4 sn, kapsam ölçümü + paralel işçi altında 16 sn ölçüldü (2026-09-27)

  it('REGRESYON: detay modali acikken kontrol butonu patlamaz ve kilitli kalmaz', async () => {
    // Eski kod burada TANIMSIZ loadHistory(m.id, rangeDays) cagiriyordu -> ReferenceError;
    // ardindan gelen setChecking(null) hic calismadigi icin buton kalici disabled kaliyordu.
    // Ayni hata ScriptedMonitorPage'de duzeltilmisti, bu 6 kopyaya tasinmamisti.
    localStorage.clear()
    api.monitoring.triggerDomainCheck.mockResolvedValue({ success: true, data: { ...monitor } })
    window.history.replaceState({}, '', '/?monitor=1')   // detay modalini ac -> selected.id === m.id
    try {
      render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getDomainMonitors).toHaveBeenCalled())

      // Kartın kontrol düğmesi (modal da aynı adlı düğmeyi taşır; DOM sırasında kart önce gelir)
      const runBtn = screen.getAllByRole('button', { name: /^(Şimdi kontrol et|Check now)$/i })[0]
      expect(runBtn).not.toBeNull()
      fireEvent.click(runBtn)

      await waitFor(() => expect(api.monitoring.triggerDomainCheck).toHaveBeenCalledWith(1))
      await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmiyor
    } finally {
      window.history.replaceState({}, '', '/')
    }
  })

  // ── shadcn geçişi (2026-09-25): kart / detay / iç içe pencereler / menü ──────────────────
  it('kart: koruma rozetleri ve EPP kodları shadcn Badge; transfer kilidi yoksa "bad" tonu', async () => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, transfer_lock: 'NONE', dnssec: 'signed' }] })
    const { container } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('example.com.tr')
    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    // İpuçlu rozetlerde Tooltip tetiği (asChild) data-slot'u kendi adıyla ezer; rozet kimliği
    // `data-variant` (shadcn Badge) + `data-tone` (koruma tonu) ile sınanır.
    const lock = within(card).getByText(/^(kilit yok|no lock)$/i)
    expect(lock).toHaveAttribute('data-variant', 'secondary')
    expect(lock).toHaveAttribute('data-tone', 'bad')
    expect(within(card).getByText(/^DNSSEC/)).toHaveAttribute('data-tone', 'ok')
    expect(within(card).getByText(eppLabel('clientTransferProhibited')).closest('[data-slot="badge"]')).not.toBeNull()
  })

  /**
   * 2026-09-26: Kritik durumlu kartta "Kritik" İKİ kez yazıyordu (durum rozeti + "⚠ Kritik" alarm rozeti). Seviye
   * durumla aynıysa alarm durum rozetine katlanır (⚠ ikon + ekran okuyucu metni); farklıysa ayrı rozet kalır.
   */
  it.each([
    ['CRITICAL', 'CRITICAL', false],
    ['WARNING', 'WARNING', false],
    ['CRITICAL', 'HIGH', true],
  ])('kart: durum %s + alarm %s → ayrı alarm rozeti %s', async (status, level, separate) => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [
      { ...monitor, status, active_alarm: true, alarm_level: level, alarm_acknowledged: false },
    ] })
    const { container } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('example.com.tr')
    const card = container.querySelector('.upt-grid > [data-slot="card"]')
    const badge = card.querySelector('[data-slot="badge"][data-status]')
    expect(!!card.querySelector('[data-slot="monitor-alarm"]')).toBe(separate)
    // Seviye her durumda GÖRÜNÜR (durum rozetinde ya da ayrı rozette) ve ekran okuyucuya tam metinle duyurulur.
    expect(badge.textContent).toMatch(/Critical|Warning/)
    expect(card.textContent).toContain(`Active alarm — ${level}`)
    const visibleCritical = [...card.querySelectorAll('[data-slot="badge"]')]
      .filter((b) => /^(⚠\s*)?(Critical|Kritik)$/.test(b.textContent.replace(/Active alarm.*$/, '').trim()))
    if (!separate) {
      expect(badge.querySelector('[data-slot="monitor-alarm-inline"]')).not.toBeNull()
      expect(visibleCritical.length).toBeLessThanOrEqual(1)   // "Kritik" artık tek kez
    }
  })

  it('detay: kart başlığı düğmesi pencereyi açar, sekmeler Tabs; Tanıla penceresi detayın ÜSTÜNDE ayrı pencerede açılır', async () => {
    api.admin.runDomainExpiryDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /example\.com\.tr — (detayları aç|open details)/i }))
    const detail = await screen.findByRole('dialog')
    expect(within(detail).getByText(eppLabel('clientTransferProhibited')).closest('[data-slot="badge"]')).not.toBeNull()

    // Sekmeler shadcn Tabs: tetik mousedown ile değişir
    const regTab = within(detail).getByRole('tab', { name: /domain kaydı|registration/i })
    fireEvent.mouseDown(regTab, { button: 0 })
    await waitFor(() => expect(regTab).toHaveAttribute('aria-selected', 'true'))
    fireEvent.mouseDown(within(detail).getByRole('tab', { name: /check history|kontrol geçmişi/i }), { button: 0 })

    // Tanıla: detayın içinden açılan İKİNCİ pencere; hata onun içinde, detay açık kalır
    fireEvent.click(await within(detail).findByRole('button', { name: /^(sorun tanıla|diagnose)$/i }))
    const err = await screen.findByText('tanı ucu yanıt vermedi')
    const diag = err.closest('[role="dialog"]')
    expect(diag).not.toBe(detail)
    expect(within(diag).getByText(/example\.com\.tr/)).toBeInTheDocument()
    // Katman: Tanıla, onu açan detayın İÇİNDE çizilir → ModalShell derinliği +1, detayın ÜSTÜNDE durur.
    // (Sayfa düzeyinde çizilseydi iki pencere aynı z-index'i alır, sıra DOM'a kalırdı.)
    expect(Number(diag.style.zIndex)).toBeGreaterThan(Number(detail.style.zIndex))
    expect(screen.getAllByRole('dialog')).toHaveLength(2)

    fireEvent.click(within(diag).getByRole('button', { name: /^(iptal|cancel)$/i }))
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(screen.getByRole('dialog')).toBe(detail)
  })

  it('dışa aktar menüsü (DropdownMenu): CSV ve PDF öğeleri görünen kayıt sayısını söyler', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('example.com.tr')
    pressMenuTrigger(screen.getByRole('button', { name: /^(dışa aktar|export)$/i }))
    const items = await screen.findAllByRole('menuitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent(/csv/i)
    expect(items[1]).toHaveTextContent(/pdf/i)
    items.forEach((it) => expect(it).toHaveTextContent(/1 visible records|görünen 1 kayıt/i))
  })
})

/**
 * İstatistik şeridine dört yeni kart (2026-09-26 kullanıcı seçimi): NS çözülmüyor / DNSSEC kapalı / kara listede /
 * süresi dolmuş. Her sayım fixture satırlarından hesaplanır; bilinmeyen (null / UNKNOWN / SKIPPED) değerler SAYILMAZ;
 * karta basmak listeyi TAM o satırlara süzer (diğer süzgeçlerle birlikte çalışan statFilter yolu).
 */
describe('DomainMonitorPage — istatistik kartları (NS / DNSSEC / kara liste / süresi dolmuş)', () => {
  const base = { team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod', status: 'OK', source: 'RDAP', active: true,
    expiry_date: '2027-01-01', checked_at: '2026-09-26T00:00:00' }
  const rows = [
    { ...base, id: 11, domain: 'ns-fail.example.com', ns_resolves: false, dnssec: 'signed', blacklist_status: 'CLEAN', days_remaining: 100 },
    { ...base, id: 12, domain: 'ns-unknown.example.com', ns_resolves: null, dnssec: null, blacklist_status: 'UNKNOWN', days_remaining: null },
    { ...base, id: 13, domain: 'unsigned.example.com', ns_resolves: true, dnssec: 'unsigned', blacklist_status: 'SKIPPED', days_remaining: 50 },
    { ...base, id: 14, domain: 'listed.example.com', ns_resolves: true, dnssec: 'unsigned', blacklist_status: 'LISTED', days_remaining: 0 },
    { ...base, id: 15, domain: 'expired.example.com', ns_resolves: false, dnssec: 'signed', blacklist_status: 'CLEAN', days_remaining: -4 },
  ]
  const card = (tone, label) => [...document.querySelectorAll(`[data-slot="stat-item"][data-tone="${tone}"]`)]
    .find((el) => label.test(el.textContent))
  const value = (el) => el.querySelector('[data-slot="stat-value"]').textContent
  const shown = () => [...document.querySelectorAll('.upt-grid > [data-slot="card"] [data-monitor-open]')].map((b) => b.textContent).sort()

  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: rows })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { domain: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  const open = async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('ns-fail.example.com')
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="stats-panel"]')).not.toBeNull())
  }

  it('sayımlar: yalnız kesin değerler (null / UNKNOWN / SKIPPED / 0 gün sayılmaz); 12 kart', async () => {
    await open()
    expect(document.querySelectorAll('[data-slot="stat-item"]')).toHaveLength(12)
    expect(value(card('high', /NS çözülmüyor|Nameservers not resolving/))).toBe('2')        // 11, 15 — null (12) değil
    expect(value(card('warning', /DNSSEC kapalı|DNSSEC off/))).toBe('2')                     // 13, 14 — null (12) değil
    expect(value(card('critical', /Kara listede|On a blacklist/))).toBe('1')                  // 14 — UNKNOWN/SKIPPED/CLEAN değil
    expect(value(card('critical', /Süresi dolmuş|^.*Expired/))).toBe('1')                   // 15 — 0 gün (14) ve null (12) değil
  })

  it.each([
    [/NS çözülmüyor|Nameservers not resolving/, 'high', ['expired.example.com', 'ns-fail.example.com']],
    [/DNSSEC kapalı|DNSSEC off/, 'warning', ['listed.example.com', 'unsigned.example.com']],
    [/Kara listede|On a blacklist/, 'critical', ['listed.example.com']],
    [/Süresi dolmuş|Expired/, 'critical', ['expired.example.com']],
  ])('%s kartına basınca liste TAM o satırlara süzülür; ikinci basış süzgeci kaldırır', async (label, tone, expected) => {
    await open()
    const c = card(tone, label)
    fireEvent.click(c)
    await waitFor(() => expect(shown()).toEqual(expected))
    expect(c).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(c)
    await waitFor(() => expect(shown()).toHaveLength(rows.length))
  })

  it('ipuçları ekran okuyucuya bağlı (yalnız fareye bırakılmaz)', async () => {
    await open()
    const c = card('critical', /Kara listede|On a blacklist/)
    expect(c).toHaveAccessibleDescription(/DNSBL/)
  })
})

// Varsayılan kart sırası (2026-10-01 kullanıcı kararı): "kullanıcı ilk baktığında en az süresi kalanları görsün" —
// kalan gün ARTAN: süresi geçmiş en üstte, eşit günde alan adı A→Z, günü bilinmeyen en sonda; URL'ye yazılmaz.
// Dokuz türün ortak kuralı (sorunlu → grup → ad) seçicide `default` olarak durur.
describe('DomainMonitorPage — varsayılan sıra: en az gün önce', () => {
  const base = { ...monitor, status: 'OK', group_name: null }
  const LIST = [
    { ...base, id: 21, domain: 'uzun.example.com', days_remaining: 300, group_name: 'A Grubu' },
    { ...base, id: 22, domain: 'bilinmiyor.example.com', days_remaining: null, status: 'UNKNOWN' },
    { ...base, id: 23, domain: 'b-yakin.example.com', days_remaining: 5, status: 'CRITICAL' },
    { ...base, id: 24, domain: 'gecmis.example.com', days_remaining: -4, status: 'CRITICAL' },
    { ...base, id: 25, domain: 'a-yakin.example.com', days_remaining: 5, status: 'CRITICAL' },
    { ...base, id: 26, domain: 'orta.example.com', days_remaining: 25, status: 'WARNING', group_name: 'A Grubu' },
  ].map((m) => ({ ...m, name: m.domain }))
  const order = (c) => [...c.querySelectorAll('.upt-grid > [data-slot="card"]')]
    .map((card) => LIST.find((m) => card.textContent.includes(m.domain))?.domain)
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: LIST })
    api.monitoring.getDomainHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { domain: { intervalSeconds: 86400, warningDays: 30, criticalDays: 7, thresholds: '60,30,14,7,3,1' } } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 3, name: 'SY-A' }] })
  })

  it('ilk açılış: süresi geçmiş → en az gün → … → günü bilinmeyen; eşit günde alan adı A→Z; sort URL\'de yok', async () => {
    window.history.replaceState({}, '', '/?tab=domain')
    const { container } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('uzun.example.com')
    expect(order(container)).toEqual(['gecmis.example.com', 'a-yakin.example.com', 'b-yakin.example.com', 'orta.example.com',
      'uzun.example.com', 'bilinmiyor.example.com'])
    expect(new URLSearchParams(window.location.search).get('sort')).toBeNull()
  })

  it('?sort=default → dokuz türün ortak kuralı (sorunlu önce, sonra grup, sonra ad)', async () => {
    window.history.replaceState({}, '', '/?tab=domain&sort=default')
    const { container } = render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('uzun.example.com')
    // kırmızı (CRITICAL: ad A→Z) → sarı (WARNING) → diğerleri: grubu olan (A Grubu/uzun) önce, sonra grupsuz (bilinmiyor)
    expect(order(container)).toEqual(['a-yakin.example.com', 'b-yakin.example.com', 'gecmis.example.com', 'orta.example.com',
      'uzun.example.com', 'bilinmiyor.example.com'])
    window.history.replaceState({}, '', '/')
  })
})
