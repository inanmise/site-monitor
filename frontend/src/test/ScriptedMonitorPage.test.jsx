import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

// CodeEditor (prismjs/CSS) jsdom'da ağır → basit textarea ile mock
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({
  default: (props) => <div data-testid="monitor-notes" data-type={props.type} data-target={props.target} />,
}))
vi.mock('../components/admin/AlertHistory.jsx', () => ({
  default: (props) => <div data-testid="alert-history" data-domain={props.domain} />,
}))

vi.mock('../api/client', () => ({
  // GERÇEK davranış pini: formatDateSec undefined/null'a 'N/A' basar — 2026-08 regresyonunda
  // test mock'u `s ?? ''` ile bunu maskelemişti ve alan-adı hatası (checkedAt vs checked_at) kaçmıştı.
  formatDateSec: (s) => (s ? `FMT:${s}` : 'N/A'),
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
  api: {
    monitoring: {
      getScriptedMonitors: vi.fn(),
      getScriptedVersions: vi.fn(() => Promise.resolve({ success: true, data: { versions: [], current_version: null } })),
      getScriptedVersion: vi.fn(),
      saveScriptedDraft: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getScriptedDrafts: vi.fn(() => Promise.resolve({ success: true, data: { drafts: [] } })),
      deleteScriptedDraft: vi.fn(() => Promise.resolve({ success: true })),
      getCheckHistory: vi.fn(() => Promise.resolve({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [], range: { from: '', to: '' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      createScriptedMonitor: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      updateScriptedMonitor: vi.fn(),
      deleteScriptedMonitor: vi.fn(),
      triggerScriptedCheck: vi.fn(),
      testScripted: vi.fn(() => Promise.resolve({ success: true, data: { status: 'PASS', checks_passed: 3, checks_failed: 0, duration_ms: 820, output_tail: 'out' } })),
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  },
}))

import { api } from '../api/client'

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
  api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [] } })
  api.monitoring.saveScriptedDraft.mockResolvedValue({ success: true, data: {} })
  api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { versions: [], current_version: null } })
  api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { scripted: { intervalSeconds: 300, timeoutSeconds: 60 } } })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A', duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00' }],
    },
  })
})

describe('ScriptedMonitorPage', () => {
  it('izleme kartını KANONİK upt-card yapısıyla listeler + "Yeni Monitör" görünür', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="TEAM_ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText('OIDC Login')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new monitor|yeni monitör/i })).toBeInTheDocument()
    // Kanonik kart ailesi: upt-grid içinde upt-card, durum sınıfı + rozet + foot aksiyonları
    expect(container.querySelector('.upt-grid')).not.toBeNull()
    const card = container.querySelector('.upt-card')
    expect(card).not.toBeNull()
    expect(card.className).toContain('upt-card--up')          // PASS → up renk ailesi
    expect(card.querySelector('.upt-badge')).not.toBeNull()
    expect(card.querySelector('.upt-card-domain')).not.toBeNull()
    // Aksiyonlar .upt-card-foot İÇİNDE (2026-08 şikayeti: butonlar kayıyordu)
    expect(card.querySelector('.upt-card-foot .mon-btn-check')).not.toBeNull()
    expect(card.querySelector('.upt-card-foot .mon-btn-edit')).not.toBeNull()
    // Tanımsız eski sınıflar terk edildi
    expect(container.querySelector('.mon-card')).toBeNull()
    expect(container.querySelector('.btn-xs')).toBeNull()
  })

  it('ortamdaki k6 sürümü listede görünür; sürüm bilinmiyorsa rozet HİÇ çıkmaz', async () => {
    // Kullanıcı script'i hangi motora yazdığını bilmeli — sürüm API'den geliyordu ama
    // yalnız hata sonrası tanı ipucunda kullanılıyor, ekranda hiç gösterilmiyordu.
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    expect(await screen.findByText(/k6 v0\.49\.0/)).toBeInTheDocument()
    expect(container.querySelector('.sc-k6ver-chip')).not.toBeNull()
    unmount()

    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true, data: { k6_available: true, k6_version: null, can_manage: true, monitors: [] },
    })
    const second = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(second.container.querySelector('.sc-k6ver-chip')).toBeNull()
  })

  it('k6 yoksa "devre dışı" banner gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: false, monitors: [], can_manage: true } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    expect(await screen.findByText(/devre dışı|disabled/i)).toBeInTheDocument()
  })

  it('detay modalı: snake_case geçmiş satırları DOĞRU çözülür (Time≠N/A, Duration≠—) + 4 sekme', async () => {
    // 2026-08 regresyon pini: API snake_case döndürür (checked_at/duration_ms/checks_*);
    // bileşen camelCase okuyunca Time=N/A, Duration=— görünüyordu.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [
        { id: 11, status: 'PASS',  checked_at: '2026-08-07T09:00:00', duration_ms: 812, checks_passed: 3, checks_failed: 0 },
        { id: 12, status: 'ERROR', checked_at: '2026-08-07T08:00:00', duration_ms: null, error: 'k6 binary bulunamadı' },
      ],
      counts: { total: 2, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-01T00:00:00', to: '2026-08-07T23:59:59' }, total: 2, page: 0, size: 50,
    } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    // Zaman damgası çözüldü (N/A DEĞİL) + süre ms olarak görünür
    expect(await screen.findByText('FMT:2026-08-07T09:00:00')).toBeInTheDocument()
    expect(screen.getByText('812ms')).toBeInTheDocument()
    expect(screen.queryByText('N/A')).toBeNull()
    // Süresi null olan (koşamamış) satır — 0ms değil "—"
    expect(screen.getByText('FMT:2026-08-07T08:00:00')).toBeInTheDocument()
    expect(screen.getByText('k6 binary bulunamadı')).toBeInTheDocument()

    // 4 sekme (kanonik desen)
    expect(screen.getByRole('button', { name: /check history|kontrol geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /alert history|alarm geçmişi/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /duration chart|süre grafiği/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /guide|rehber/i })).toBeInTheDocument()

    // Özet şeridi: son koşum zamanı modalda DA görünür (kartta + modal özetinde ≥2 kez)
    expect(screen.getAllByText('FMT:2026-07-31T10:00:00').length).toBeGreaterThanOrEqual(2)
  })

  it('detay sekmeleri: Alarm → AlertHistory(name), Rehber&Notlar → MonitorNotes(type=SCRIPTED, target=name)', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('OIDC Login')
    fireEvent.click(screen.getByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /alert history|alarm geçmişi/i }))
    expect((await screen.findByTestId('alert-history')).dataset.domain).toBe('OIDC Login')

    fireEvent.click(screen.getByRole('button', { name: /guide|rehber/i }))
    const notes = await screen.findByTestId('monitor-notes')
    expect(notes.dataset.type).toBe('SCRIPTED')
    expect(notes.dataset.target).toBe('OIDC Login')
  })

  it('Kopyala: TÜM kullanıcı ayarları birebir kopyalanır (ad "(Kopya)", gizli env değeri taşınmaz)', async () => {
    // Her alan varsayılandan FARKLI → bir alan formFrom'dan düşerse tam-payload karşılaştırması kırılır.
    api.monitoring.getScriptedMonitors.mockResolvedValue({
      success: true,
      data: {
        k6_available: true, k6_version: 'v0.49.0', can_manage: true,
        monitors: [{
          id: 1, name: 'OIDC Login', status: 'PASS', checked_at: '2026-07-31T10:00:00',
          description: 'Giriş senaryosu', group_name: 'Senaryolar', team_id: 5, team_name: 'SY-A',
          tags: 'prod,kritik', notify_email: false,
          interval_seconds: 900, timeout_seconds: 45,
          confirm_attempts: 5, confirm_interval_seconds: 45, recovery_checks: 4, recovery_interval_seconds: 90,
          active: false, script: 'export default function(){}',
          // gizli env değeri şifreli saklanır → kopyaya taşınamaz (yalnız ad+secret bayrağı gider)
          env: [{ name: 'BASE_URL', secret: false, value: 'https://x.example.com' },
                { name: 'PASSWORD', secret: true, value_set: true }],
        }],
      },
    })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.createScriptedMonitor.mockResolvedValue({ success: true, data: {} })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('OIDC Login')

    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))

    // Kopya rozeti + ipucu görünür (yeni-kayıt modu: modal={} → id yok)
    expect(document.querySelector('.mon-dup-badge')).not.toBeNull()
    expect(document.querySelector('.mon-dup-hint')).not.toBeNull()
    expect(document.querySelector('.form-grid input').value).toMatch(/\(Kopya\)$/)
    expect(screen.getByTestId('code-editor').value).toBe('export default function(){}')

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.createScriptedMonitor).toHaveBeenCalled())
    expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()

    expect(api.monitoring.createScriptedMonitor.mock.calls[0][0]).toEqual({
      name: 'OIDC Login (Kopya)', description: 'Giriş senaryosu',
      groupName: 'Senaryolar', teamId: 5, tags: 'prod,kritik', notifyEmail: false,
      intervalSeconds: 900, timeoutSeconds: 45,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      script: 'export default function(){}',
      useProxy: 'AUTO',   // vekil tercihi de kopyalanır (kaynakta yoksa AUTO)
      bumpType: 'patch',  // sürüm artışı (yeni kayıtta kullanılmaz ama payload şekli tek)
      restoredFrom: null, // eski sürümden yüklenmediyse null
      env: [{ name: 'BASE_URL', secret: false, value: 'https://x.example.com' },
            { name: 'PASSWORD', secret: true }],   // gizli değer taşınmaz → kullanıcı yeniden girer
    })
  })

  it('Test Çalıştır → testScripted çağırır ve sonucu gösterir', async () => {
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){}' } })
    fireEvent.click(screen.getByRole('button', { name: /^test run$|^test çalıştır$/i }))
    await waitFor(() => expect(api.monitoring.testScripted).toHaveBeenCalled())
    expect(await screen.findByText(/820 ms/)).toBeInTheDocument()   // test-sonucu banner'ına özgü süre
  })

  // ── Script kaynağı seçicisi: kayıtlı script'ler ↔ şablonlar, panel bağı ──────────────
  //
  // Şikayet: şablon değişince aşağıdaki hata paneli ekranda kalıyor ve artık editörde olmayan
  // bir script'in hatasını gösteriyordu. Panel DAİMA seçili script'e ait olmalı.
  describe('script kaynağı seçicisi', () => {
    const FAILING = {
      id: 7, name: 'llm-test', status: 'FAIL', team_id: 5, team_name: 'SY-A',
      script: 'export default function(){ /* kayitli */ }',
      env: [{ name: 'BASE_URL', secret: false, value: 'https://x' }, { name: 'TOKEN', secret: true, value_set: true }],
      error: 'k6 check/threshold başarısız:\nRequest Failed — request timeout',
      exit_code: 0, output_tail: 'tail-satiri', duration_ms: 60310,
      checks_passed: 1, checks_failed: 2, checked_at: '2026-08-12T22:25:15',
    }
    const NEVER_RUN = { id: 9, name: 'hic-kosmadi', status: 'unknown', team_id: 5, script: 'export default function(){}' }

    // Modal createPortal ile document.body'ye çiziliyor → sorgular container'a DEĞİL belgeye yapılır.
    const inModal = (sel) => document.querySelector(`.modal-box ${sel}`)
    const sourceSelect = () => screen.getByLabelText(/script source|script kaynağı/i)

    async function openEditFor(row) {
      const rows = row.id === NEVER_RUN.id ? [NEVER_RUN] : [row, NEVER_RUN]
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: rows },
      })
      const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      await screen.findByText(row.name)
      fireEvent.click(utils.container.querySelector('.mon-btn-edit'))
      return utils
    }

    it('seçici İKİ GRUP gösterir: kayıtlı script\'ler (bu monitör başta) ve şablonlar', async () => {
      await openEditFor(FAILING)
      const groups = sourceSelect().querySelectorAll('optgroup')
      expect(groups.length).toBe(2)
      expect(groups[0].label).toMatch(/saved scripts|kayıtlı/i)
      expect(groups[1].label).toMatch(/templates|şablon/i)
      // Düzenlenen monitör kendi adıyla ve "(bu monitör)" işaretiyle EN BAŞTA
      expect(groups[0].children[0].textContent).toContain('llm-test')
      expect(groups[0].children[0].textContent).toMatch(/this monitor|bu monitör/i)
      expect(sourceSelect().value).toBe('saved:7')
    })

    it('düzenleme açılışında panel "son kontrol" etiketiyle ve o monitörün hatasıyla gelir', async () => {
      await openEditFor(FAILING)
      expect(await screen.findByText(/last check|son kontrol/i)).toBeInTheDocument()
      expect(screen.getByText(/request timeout/)).toBeInTheDocument()
    })

    it('hiç koşmamış monitörde panel HİÇ açılmaz', async () => {
      await openEditFor(NEVER_RUN)
      expect(inModal('.sc-testrun')).toBeNull()
    })

    it('şablon seçilince panel KAYBOLUR (asıl şikayet) ve script şablonunkiyle değişir', async () => {
      await openEditFor(FAILING)
      expect(inModal('.sc-testrun')).not.toBeNull()

      fireEvent.change(sourceSelect(), { target: { value: 'tpl:smoke-health' } })

      expect(inModal('.sc-testrun')).toBeNull()
      expect(screen.getByTestId('code-editor').value).toContain('www.akbank.com')
    })

    it('kayıtlı script seçilince o monitörün script+env\'i yüklenir ve paneli geri gelir', async () => {
      await openEditFor(FAILING)
      fireEvent.change(sourceSelect(), { target: { value: 'tpl:smoke-health' } })
      expect(inModal('.sc-testrun')).toBeNull()

      fireEvent.change(sourceSelect(), { target: { value: 'saved:7' } })

      expect(screen.getByTestId('code-editor').value).toBe(FAILING.script)
      expect(inModal('.sc-testrun')).not.toBeNull()
      expect(screen.getByText(/request timeout/)).toBeInTheDocument()
      // env tanımları da geldi; gizli değer TAŞINMAZ (kullanıcı yeniden girer)
      const envNames = [...document.querySelectorAll('.modal-box .env-row .env-name')].map(i => i.value)
      expect(envNames).toEqual(['BASE_URL', 'TOKEN'])
    })

    it('script\'i ELLE düzenlemek paneli kaybettirmez (hatayı okurken düzeltme yapılabilsin)', async () => {
      await openEditFor(FAILING)
      fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* elle */ }' } })
      expect(inModal('.sc-testrun')).not.toBeNull()
    })

    it('yeni monitörde boş seçenek VAR ve script\'i temizler; düzenlemede boş seçenek YOK', async () => {
      const { unmount } = await openEditFor(FAILING)
      // Düzenlemede placeholder yok: monitörün kendi girdisi listede, "boşalt" yolu veri kaybettiriyordu
      expect(sourceSelect().querySelectorAll(':scope > option')).toHaveLength(0)
      unmount()

      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [FAILING] },
      })
      render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
      expect(sourceSelect().querySelectorAll(':scope > option')).toHaveLength(1)   // placeholder

      fireEvent.change(sourceSelect(), { target: { value: 'tpl:smoke-health' } })
      expect(screen.getByTestId('code-editor').value).toContain('www.akbank.com')
      fireEvent.change(sourceSelect(), { target: { value: '' } })
      expect(screen.getByTestId('code-editor').value).toBe('')
    })
  })

  // ── Otomatik taslak + sürüm geçmişi ──────────────────────────────────────────────────
  describe('otomatik taslak ve sürümler', () => {
    const MON = {
      id: 3, name: 'llm-test', status: 'PASS', team_id: 5, team_name: 'SY-A',
      script: 'export default function(){}', script_version: '1.0.2',
      checked_at: '2026-08-13T10:00:00',
    }

    async function renderPage() {
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [MON] },
      })
      const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      await screen.findByText('llm-test')
      return utils
    }

    it('script yazıldıktan sonra taslak SUNUCUYA kaydedilir (yazmayı bırakınca)', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        await renderPage()
        fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
        fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* yazdim */ }' } })

        expect(api.monitoring.saveScriptedDraft).not.toHaveBeenCalled()   // hemen değil
        await vi.advanceTimersByTimeAsync(1600)                           // 1,5 sn debounce

        expect(api.monitoring.saveScriptedDraft).toHaveBeenCalled()
        const payload = api.monitoring.saveScriptedDraft.mock.calls.at(-1)[0]
        expect(payload.monitorKey).toBe('new')                            // hiç kaydedilmemiş monitör
        expect(JSON.parse(payload.formJson).script).toContain('yazdim')
      } finally { vi.useRealTimers() }
    })

    it('boş form taslak olarak kaydedilmez (gereksiz satır üretmesin)', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        await renderPage()
        fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
        await vi.advanceTimersByTimeAsync(2000)
        expect(api.monitoring.saveScriptedDraft).not.toHaveBeenCalled()
      } finally { vi.useRealTimers() }
    })

    it('gizli env DEĞERİ taslağa yazılmaz (düz metin saklanmasın)', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        await renderPage()
        fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
        fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'x' } })
        // Şablon seç → env satırları gelsin, birine gizli değer yazalım
        fireEvent.change(screen.getByLabelText(/script source|script kaynağı/i), { target: { value: 'tpl:oauth2-client-credentials' } })
        const secretInput = document.querySelector('.modal-box .env-row input.env-val[type="password"]')
        if (secretInput) fireEvent.change(secretInput, { target: { value: 'COK-GIZLI' } })
        await vi.advanceTimersByTimeAsync(1600)

        const payload = api.monitoring.saveScriptedDraft.mock.calls.at(-1)[0]
        expect(payload.formJson).not.toContain('COK-GIZLI')
      } finally { vi.useRealTimers() }
    })

    it('hiç kaydedilmemiş taslak varsa sayfada "devam et" şeridi çıkar ve forma yüklenir', async () => {
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: 'new', monitor_id: null, monitor_name: 'yarim-kalan',
          form_json: JSON.stringify({ name: 'yarim-kalan', script: 'export default function(){ /* taslak */ }' }),
          updated_at: '2026-08-13T09:30:00' },
      ] } })
      await renderPage()

      expect(await screen.findByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /^continue$|^devam et$/i }))

      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('taslak'))
    })

    it('mevcut monitörde taslak OTOMATİK uygulanmaz — kullanıcıya sorulur', async () => {
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: '3', monitor_id: 3, monitor_name: 'llm-test',
          form_json: JSON.stringify({ script: 'export default function(){ /* taslaktan */ }' }),
          updated_at: '2026-08-13T09:30:00' },
      ] } })
      const { container } = await renderPage()
      fireEvent.click(container.querySelector('.mon-btn-edit'))

      // Kaydedilmiş script yüklü kalır; taslak yalnız TEKLİF edilir
      expect(screen.getByTestId('code-editor').value).toBe(MON.script)
      expect(await screen.findByText(/unsaved draft|kaydedilmemiş taslak/i)).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /load draft|taslağı yükle/i }))
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('taslaktan'))
    })

    it('Sürümler sekmesi listeyi çizer ve seçilen sürümü editöre yükler', async () => {
      api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: {
        current_version: '1.0.2',
        versions: [
          { id: 22, version: '1.0.2', sequence_no: 2, event_type: 'EDIT', note: null, created_at: '2026-08-13T09:00:00', created_by: 'ADMIN', script_chars: 40, current: true },
          { id: 21, version: '1.0.0', sequence_no: 0, event_type: 'CREATE', note: null, created_at: '2026-08-01T09:00:00', created_by: 'ADMIN', script_chars: 30, current: false },
        ],
      } })
      api.monitoring.getScriptedVersion.mockResolvedValue({ success: true, data: {
        id: 21, version: '1.0.0', script: 'export default function(){ /* eski surum */ }', env: [],
      } })
      const { container } = await renderPage()
      fireEvent.click(container.querySelector('.upt-card'))
      fireEvent.click(await screen.findByRole('button', { name: /^versions$|^sürümler$/i }))

      expect(await screen.findByText('v1.0.2')).toBeInTheDocument()
      expect(screen.getByText('v1.0.0')).toBeInTheDocument()

      fireEvent.click(screen.getByText('v1.0.0').closest('tr'))
      const loadBtn = await screen.findByRole('button', { name: /load this version|editöre yükle/i })
      fireEvent.click(loadBtn)

      // Geri dönüş doğrudan YAZMAZ: içerik editöre gelir, kullanıcı kaydedince yeni sürüm olur
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('eski surum'))
      expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()
    })
  })

  it('sayfalama: 120 kayıt → 50 kart + "Page 1 of 3"; Sonraki → 51.; tek sayfada nav yok', async () => {
    localStorage.clear()
    const many = Array.from({ length: 120 }, (_, i) => ({ id: i + 1, name: `SC-${i + 1}`, status: 'PASS', team_name: 'SY-A', checked_at: '2026-07-31T10:00:00' }))
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many } })
    const { container, unmount } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
    await screen.findByText('SC-1')
    expect(container.querySelectorAll('.upt-card')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('1–50 of 120 records')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('SC-51')
    expect(screen.queryByText('SC-1')).toBeNull()
    expect(screen.getByText('51–100 of 120 records')).toBeInTheDocument()
    unmount()

    // Tek sayfa (30 kayıt): gezinme yok ama kayıt bilgisi var
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: true, monitors: many.slice(0, 30) } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('SC-1')
    expect(screen.getByText('1–30 of 30 records')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next' })).toBeNull()
  })

  it('hiç koşmamış monitör (unknown, null metrikler): süre "—" gösterir, ✓/✗ metriği gizli', async () => {
    // Backend artık latest==null dalında checks_* anahtarlarını NULL koyar (0 değil).
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Hiç Koşmadı', status: 'unknown', team_id: 5, team_name: 'SY-A',
        duration_ms: null, checks_passed: null, checks_failed: null, checked_at: null }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Hiç Koşmadı')
    const card = container.querySelector('.upt-card')
    expect(card.className).toContain('upt-card--unknown')
    expect(card.querySelector('.upt-metric-val').textContent).toBe('—')   // 0ms DEĞİL
    expect(card.textContent).not.toContain('0✓/0✗')                       // yanıltıcı sayaç yok
    expect(screen.getByText(/never run|henüz çalışmadı/i)).toBeInTheDocument()
  })

  // ── 2026-08 regresyonları: iki CANLI ReferenceError silindi, tanı yüzeyi yeniden kuruldu ──

  it('REGRESYON: detay modalı açıkken "Şimdi Çalıştır" patlamaz ve buton kilitlenmez', async () => {
    // Eski kod burada tanımsız loadHistory(m.id, rangeDays) çağırıyordu → ReferenceError;
    // ardından gelen setChecking(null) hiç çalışmıyor ve buton kalıcı disabled kalıyordu.
    api.monitoring.triggerScriptedCheck.mockResolvedValue({
      success: true, data: { id: 1, name: 'OIDC Login', status: 'PASS', duration_ms: 700 } })
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))

    const runBtn = (await screen.findAllByRole('button', { name: /run now|şimdi çalıştır/i }))[0]
    fireEvent.click(runBtn)
    await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(1))
    await waitFor(() => expect(runBtn.disabled).toBe(false))   // kilitli kalmıyor
  })

  it('REGRESYON: modal başlığında bozuk CSV butonu YOK (CheckHistoryTab kendi CSV linkini veriyor)', async () => {
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    // Eski buton tanımsız `history`'yi map'liyordu → window.history.map is not a function
    await waitFor(() => expect(screen.getByRole('dialog', {}) ?? true).toBeTruthy()).catch(() => {})
    expect(screen.queryByRole('button', { name: /^CSV$/ })).toBeNull()
  })

  it('NO_CHECKS: amber kart, "down" sayılmaz, etiketi görünür', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true,
      monitors: [{ id: 9, name: 'Sessiz Script', status: 'NO_CHECKS', team_id: 5, duration_ms: 500, checked_at: '2026-08-10T10:00:00' }] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('Sessiz Script')

    const card = container.querySelector('.upt-card')
    expect(card.className).toContain('upt-card--warn')     // arıza kırmızısı DEĞİL
    expect(card.className).not.toContain('upt-card--down')
    expect(container.querySelector('.upt-badge--warn')).not.toBeNull()
    expect(screen.getByText(/no checks|doğrulama yok/i)).toBeInTheDocument()
  })

  it('koşum detayı: ham metin değil, insan-okur özet + çıkış kodu; teknik detay KATLANMIŞ gelir', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 77, checked_at: '2026-08-10T09:00:00', status: 'ERROR', duration_ms: 512,
                exit_code: 107, error: 'script çalışma-zamanı hatası (çıkış 107)',
                output_tail: 'ERRO[0001] GoError: patladi\nikinci satir' }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))

    // Satırdaki hata metnine tıklamak da detay panelini açar (eskiden yalnız Zaman/Durum açıyordu)
    fireEvent.click(await screen.findByText('script çalışma-zamanı hatası (çıkış 107)'))

    // İnsan-okur çıkış kodu etiketi (kullanıcının DİLİNDE) ayrı bir satırda; backend mesajı Türkçe,
    // bu satır arayüz diline çeviriyor. Sınıfla hedefleniyor — regex ikisini birden yakalardı.
    const exitLine = await waitFor(() => {
      const el = document.querySelector('.sc-err-exit')
      if (!el) throw new Error('çıkış kodu satırı yok')
      return el
    })
    expect(exitLine.textContent).toMatch(/Script runtime error|Script çalışma-zamanı hatası/i)
    expect(exitLine.textContent).toContain('107')
    // Ham k6 çıktısı BAŞLANGIÇTA gizli — tek tıkla açılır
    expect(screen.queryByText(/GoError: patladi/)).toBeNull()
    const toggle = screen.getByRole('button', { name: /technical detail|teknik detay/i })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(await screen.findByText(/GoError: patladi/)).toBeInTheDocument()
  })

  it('boş monitör listesi spinner DEĞİL boş-durum bloğu gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true, monitors: [] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(container.querySelector('.status-block')).not.toBeNull())
    expect(container.querySelector('.pg-spinner')).toBeNull()   // dönen spinner yok
  })
})
