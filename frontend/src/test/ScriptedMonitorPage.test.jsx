import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage, { invalidNumericField, SCRIPTED_NUM_FIELDS }
  from '../components/ScriptedMonitorPage.jsx'

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
    /** Script kaynağı artık aranabilir seçici (SearchableSelect): tetikleyiciye tıkla, seçeneği tıkla. */
    // Tetikleyici TOGGLE: açıkken yeniden tıklamak kapatır → yalnız kapalıysa aç (idempotent).
    const isSourceOpen = () => !!document.querySelector('.sc-source-select .ss-dropdown')
    const openSource = () => { if (!isSourceOpen()) fireEvent.mouseDown(document.querySelector('.sc-source-select .ss-trigger')) }
    const sourceOptions = () => [...document.querySelectorAll('.sc-source-select .ss-option')]
    const pickSource = (labelRe) => {
      openSource()
      const opt = sourceOptions().find(o => labelRe.test(o.textContent))
      fireEvent.mouseDown(opt)
    }

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
      openSource()
      const groups = [...document.querySelectorAll('.sc-source-select .ss-group')].map(g => g.textContent)
      expect(groups).toHaveLength(2)
      expect(groups[0]).toMatch(/saved scripts|kayıtlı/i)
      expect(groups[1]).toMatch(/templates|şablon/i)
      // Düzenlenen monitör kendi adıyla ve "(bu monitör)" işaretiyle EN BAŞTA
      const first = sourceOptions()[0].textContent
      expect(first).toContain('llm-test')
      expect(first).toMatch(/this monitor|bu monitör/i)
    })

    it('ada göre ARAMA: eşleşmeyen seçenekler ve boşalan grup başlığı düşer', async () => {
      await openEditFor(FAILING)
      openSource()
      // "llm" YALNIZ kayıtlı script'in adında geçer → şablon grubu tamamen boşalmalı
      fireEvent.change(document.querySelector('.sc-source-select .ss-search-input'), { target: { value: 'llm' } })

      const labels = sourceOptions().map(o => o.textContent)
      expect(labels.some(l => l.includes('llm-test'))).toBe(true)
      expect(labels.some(l => /smoke/i.test(l))).toBe(false)
      const groups = [...document.querySelectorAll('.sc-source-select .ss-group')].map(g => g.textContent)
      expect(groups).toHaveLength(1)                       // yalnız "Kayıtlı script'ler" kaldı
      expect(groups[0]).toMatch(/saved scripts|kayıtlı/i)
    })

    it('BAŞKA takımın script\'i listelenmez (düzenlenen monitörün kendi girdisi hariç)', async () => {
      const otherTeam = { id: 99, name: 'baska-takim-scripti', status: 'PASS', team_id: 42,
        script: 'export default function(){}', checked_at: '2026-08-13T10:00:00' }
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true,
        data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [FAILING, otherTeam] },
      })
      const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      await screen.findByText('llm-test')
      fireEvent.click(utils.container.querySelector('.mon-btn-edit'))

      openSource()
      const labels = sourceOptions().map(o => o.textContent)
      expect(labels.some(l => l.includes('llm-test'))).toBe(true)              // kendi takımı (5)
      expect(labels.some(l => l.includes('baska-takim-scripti'))).toBe(false)  // takım 42 → GÖRÜNMEZ
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

      pickSource(/smoke/i)

      expect(inModal('.sc-testrun')).toBeNull()
      expect(screen.getByTestId('code-editor').value).toContain('www.akbank.com')
    })

    it('kayıtlı script seçilince o monitörün script+env\'i yüklenir ve paneli geri gelir', async () => {
      await openEditFor(FAILING)
      pickSource(/smoke/i)
      expect(inModal('.sc-testrun')).toBeNull()

      pickSource(/llm-test/)

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
      openSource()
      // Düzenlemede placeholder yok: monitörün kendi girdisi listede, "boşalt" yolu veri kaybettiriyordu
      expect(document.querySelector('.sc-source-select .ss-opt-placeholder')).toBeNull()
      unmount()

      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [FAILING] },
      })
      render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedMonitors).toHaveBeenCalled())
      fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
      openSource()
      expect(document.querySelector('.sc-source-select .ss-opt-placeholder')).not.toBeNull()

      pickSource(/smoke/i)
      expect(screen.getByTestId('code-editor').value).toContain('www.akbank.com')
      openSource()
      fireEvent.mouseDown(document.querySelector('.sc-source-select .ss-opt-placeholder'))
      expect(screen.getByTestId('code-editor').value).toBe('')
    })
  })

  // ── Otomatik taslak + sürüm geçmişi ──────────────────────────────────────────────────
  describe('otomatik taslak ve sürümler', () => {
    const MON = {
      id: 3, name: 'llm-test', status: 'PASS', team_id: 5, team_name: 'SY-A',
      // group_name ŞART: save() zorunlu alan denetiminde erken dönerse kaydetme testleri
      // YANLIŞ sebeple yeşil/kırmızı olur.
      group_name: 'SY-A grubu',
      script: 'export default function(){}', script_version: '1.0.2',
      checked_at: '2026-08-13T10:00:00',
    }

    async function renderPage() {
      api.monitoring.getScriptedMonitors.mockResolvedValue({
        success: true, data: { k6_available: true, k6_version: 'v0.49.0', can_manage: true, monitors: [MON] },
      })
      api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: MON.id } })
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

    it('F1: BAŞARILI kayıttan sonra taslak DİRİLMEZ (başkasının değişikliğini geri aldırıyordu)', async () => {
      // save() basarili → backend taslagi siler → closeEdit() → flushDraft() → isFormDirty() hala
      // true (karsilastirma kayit ONCESINDEKI `modal` ile) → silinen taslak yeniden yazilirdi.
      // Sonuc: kullanici bir sonraki acilista "kaydedilmemis taslaginiz var" gorup onu yukluyor ve
      // ARADA baskasinin yaptigi degisikligi sessizce geri aliyordu.
      await renderPage()
      fireEvent.click(document.querySelector('.mon-btn-edit'))
      fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'export default function(){ /* duzeltme */ }' } })

      api.monitoring.saveScriptedDraft.mockClear()
      fireEvent.click(screen.getByRole('button', { name: /^(save|kaydet)$/i }))
      await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())

      expect(api.monitoring.saveScriptedDraft,
        'kayittan sonra taslak yeniden yazildi').not.toHaveBeenCalled()
    })

    it('F1: SİLME sonrası da taslak yazılmaz (erişilemez yetim satır kalmasın)', async () => {
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
      api.monitoring.deleteScriptedMonitor.mockResolvedValue({ success: true })
      await renderPage()
      fireEvent.click(document.querySelector('.mon-btn-edit'))
      fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'kirli icerik' } })

      api.monitoring.saveScriptedDraft.mockClear()
      fireEvent.click(screen.getByRole('button', { name: /^(delete|sil)$/i }))
      await waitFor(() => expect(api.monitoring.deleteScriptedMonitor).toHaveBeenCalled())

      expect(api.monitoring.saveScriptedDraft).not.toHaveBeenCalled()
      confirmSpy.mockRestore()
    })

    it('F2: yarım kalmış "new" taslağı varken Yeni/Kopyala onu EZMEZ, sorar', async () => {
      // Eskiden form dogar dogmaz 1,5 sn'lik otomatik yazim ayni 'new' anahtarina basip
      // saatlerce yazilmis yarim script'i geri donulmez bicimde eziyordu.
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: 'new', monitor_name: 'yarim-oauth-script', updated_at: '2026-08-14T08:00:00',
          form_json: JSON.stringify({ name: 'yarim-oauth-script', script: 'cok emek verdim' }) },
      ] } })
      vi.useFakeTimers({ shouldAdvanceTime: true })
      try {
        await renderPage()
        fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
        fireEvent.change(screen.getByTestId('code-editor'), { target: { value: 'yeni bir sey' } })

        // Teklif gorunuyor: kullanici karar verene kadar yazim DURUR
        expect(document.querySelector('.modal-box .alert-banner')).not.toBeNull()
        await vi.advanceTimersByTimeAsync(2000)
        expect(api.monitoring.saveScriptedDraft,
          'karar verilmeden taslak uzerine yazildi').not.toHaveBeenCalled()
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
        fireEvent.mouseDown(document.querySelector('.sc-source-select .ss-trigger'))
        fireEvent.mouseDown([...document.querySelectorAll('.sc-source-select .ss-option')]
          .find(o => /OAuth2/i.test(o.textContent)))
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

  it('koşum detayı: faz kırılımı takılma noktasını gösterir (DNS/TCP/TLS/TTFB ayrımı)', async () => {
    // Sahadaki 288-koşumluk vaka: "request timeout" görülüyor ama hangi fazda takıldığı
    // hiçbir ekranda yoktu. Veri k6'dan geliyordu, backend'de atılıyordu.
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      // k6'nın GERÇEK biçimi: girilmemiş faz 0 gelir, metrik eksilmez (v0.49 ile ölçüldü).
      items: [{ id: 78, checked_at: '2026-08-10T09:00:00', status: 'FAIL', duration_ms: 60300,
                error: 'Request Failed — request timeout',
                req_blocked_ms: 0, req_connecting_ms: 4, req_tls_ms: 0,
                req_sending_ms: 0, req_waiting_ms: 0, req_receiving_ms: 0,
                data_sent: 381, data_received: 99, via_proxy: false }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByText('Request Failed — request timeout'))

    const panel = await waitFor(() => {
      const el = document.querySelector('.sc-phases')
      if (!el) throw new Error('faz paneli yok')
      return el
    })
    // Ölçülen fazlar değerleriyle, ölçülmeyenler "—" ile
    expect(panel.textContent).toContain('4 ms')
    // Takılma noktası TLS: işaretli satır TAM olarak bir tane olmalı
    const stuck = panel.querySelectorAll('.sc-phase--stuck')
    expect(stuck).toHaveLength(1)
    expect(stuck[0].textContent).toMatch(/TLS/i)
    // Taşınan byte — "hiç yanıt yok" ile "kısa yanıt geldi" ayrımı
    expect(panel.textContent).toContain('381 B')
    expect(panel.textContent).toContain('99 B')
    // Vekil kararı görünür (via_proxy alanı DB'de vardı ama hiçbir bileşen çizmiyordu)
    expect(panel.textContent).toMatch(/direct|doğrudan/i)
  })

  it('faz verisi olmayan koşumda panel HİÇ çizilmez (eski satırlar boş kutu göstermesin)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 79, checked_at: '2026-08-10T09:00:00', status: 'ERROR', duration_ms: 100,
                error: 'k6 bulunamadı' }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-10T00:00:00', to: '2026-08-11T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByText('k6 bulunamadı'))

    // Detay paneli açıldı (hata metni hem satırda hem panelde geçtiği için sınıfla hedefleniyor)
    await waitFor(() => {
      if (!document.querySelector('.sc-detail')) throw new Error('detay paneli yok')
    })
    expect(document.querySelector('.sc-phases')).toBeNull()
  })

  it('Bağlantı Teşhisi: bacakları faz kırılımıyla çizer, takılma noktasını işaretler', async () => {
    // "Java çekiyor, k6 çekmiyor" ayrımını ÖLÇEN ekran. Değerler gerçek k6 v0.49 biçiminde:
    // TCP açılıyor (connecting>0), TLS tamamlanmıyor (sonrası 0).
    api.monitoring.diagnoseScripted = vi.fn().mockResolvedValue({ success: true, data: {
      url: 'https://hedef.example/x', candidates: ['https://hedef.example/x'],
      proxy_configured: true, no_proxy: 'akbank.com', k6_version: 'v0.49.0',
      legs: [
        { key: 'k6-direct-ca', label: 'k6 · doğrudan · kurumsal CA', status: 'FAIL', ok: false,
          duration_ms: 15200, error: 'Request Failed — request timeout', via_proxy: false,
          phases: { blocked_ms: 0, connecting_ms: 41, tls_ms: 0, sending_ms: 0,
                    waiting_ms: 0, receiving_ms: 0, data_sent: 281, data_received: 316 } },
        { key: 'k6-proxy-ca', label: 'k6 · vekil · kurumsal CA', status: 'PASS', ok: true,
          duration_ms: 820, error: null, via_proxy: true,
          phases: { blocked_ms: 2, connecting_ms: 30, tls_ms: 90, sending_ms: 1,
                    waiting_ms: 60, receiving_ms: 3, data_sent: 500, data_received: 5000 } },
      ] } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    fireEvent.click(await screen.findByRole('button', { name: /connection diagnostics|bağlantı teşhisi/i }))
    fireEvent.click(await screen.findByRole('button', { name: /run diagnostics|teşhisi çalıştır/i }))

    await waitFor(() => expect(api.monitoring.diagnoseScripted).toHaveBeenCalledWith(1, undefined))

    const legs = await waitFor(() => {
      const els = document.querySelectorAll('.sc-diag-leg')
      if (els.length !== 2) throw new Error('bacaklar cizilmedi')
      return els
    })
    // Başarısız bacak TLS'te takılmış olarak işaretli; BAŞARILI bacakta takılma işareti YOK
    expect(legs[0].querySelectorAll('.sc-phase--stuck')).toHaveLength(1)
    expect(legs[0].querySelector('.sc-phase--stuck').textContent).toMatch(/TLS/i)
    expect(legs[1].querySelectorAll('.sc-phase--stuck')).toHaveLength(0)
    // Etkin vekil bağlamı görünür (NO_PROXY sonek eşleşmesi yanlış teşhisin kaynağıydı)
    expect(document.querySelector('.sc-diag-meta').textContent).toContain('akbank.com')
  })

  it('Detay hücresi: kriptik "2✓/0✗" YERİNE okunur ifade (kullanıcı bildirimi)', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 91, checked_at: '2026-08-14T09:00:00', status: 'PASS', duration_ms: 812,
                checks_passed: 2, checks_failed: 0 }],
      counts: { total: 1, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    // Kapsam GEÇMİŞ satırı: kart metriği de aynı bileşeni kullanıyor (bilinçli — tek ifade).
    const cell = await waitFor(() => {
      const el = document.querySelector('.upt-rt-ms .sc-checks-sum')
      if (!el) throw new Error('doğrulama özeti yok')
      return el
    })
    expect(cell.className).toContain('sc-checks-sum--ok')
    expect(cell.textContent).toMatch(/2 doğrulama geçti|2 checks passed/i)
    // Eski kriptik notasyon HİÇBİR yerde kalmamalı
    expect(document.body.textContent).not.toContain('✓/')
  })

  it('Detay hücresi: çok satırlı hata TEK satıra iner, tam metin tooltip\'te, panel yine açılır', async () => {
    // Backend hata metnini 14 satır / 1500 karaktere kadar üretiyor (Babel kod çerçevesi);
    // kırpılmazsa tek satır tabloyu şişiriyordu.
    const FRAME = [
      'script: Unexpected token (46:29)',
      '  44 |       try {',
      '> 46 |         const c = a?.b;',
      '     |                        ^',
    ].join('\n')
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 92, checked_at: '2026-08-14T09:00:00', status: 'ERROR', duration_ms: 512,
                exit_code: 107, error: FRAME }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    const cell = await waitFor(() => {
      const el = document.querySelector('.upt-rt-error')
      if (!el) throw new Error('hata hücresi yok')
      return el
    })
    expect(cell.textContent).toContain('Unexpected token (46:29)')
    expect(cell.textContent).not.toContain('44 |')        // kod çerçevesi hücreye GİRMEZ
    expect(cell.getAttribute('title')).toBe(FRAME)        // tam metin tooltip'te
    // Tıklama hedefi korunuyor: hücre hâlâ detay panelini açıyor
    fireEvent.click(cell)
    await waitFor(() => {
      if (!document.querySelector('.sc-detail')) throw new Error('detay paneli açılmadı')
    })
  })

  it('Detay hücresi: takılan faz rozeti hata metninin yanında görünür', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [{ id: 93, checked_at: '2026-08-14T09:00:00', status: 'FAIL', duration_ms: 60300,
                error: 'Request Failed — request timeout',
                req_blocked_ms: 0, req_connecting_ms: 41, req_tls_ms: 0,
                req_sending_ms: 0, req_waiting_ms: 0, req_receiving_ms: 0 }],
      counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
      range: { from: '2026-08-14T00:00:00', to: '2026-08-15T00:00:00' }, total: 1, page: 0, size: 50 } })

    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByText('OIDC Login'))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())

    const chip = await waitFor(() => {
      const el = document.querySelector('.sc-stuck-chip')
      if (!el) throw new Error('faz rozeti yok')
      return el
    })
    expect(chip.textContent).toMatch(/TLS/i)
  })

  it('F4: invalidNumericField boş ve aralık dışı değerleri yakalar (sessiz 5 sn tuzağı)', () => {
    // `Number('')` 0 verir ve backend timeout'u max(5,…) ile 5 SANİYEYE çeker; 60 sn'lik monitör
    // her koşumda TIMEOUT verip gece alarm yağdırırdı. Girdideki min/max nitelikleri hiçbir şey
    // yapmıyor (bu bir <form> değil, Kaydet submit değil, checkValidity çağrılmıyor).
    const ok = { timeoutSeconds: 60, confirmAttempts: 3, recoveryChecks: 3 }
    expect(invalidNumericField(ok)).toBeNull()

    expect(invalidNumericField({ ...ok, timeoutSeconds: '' })?.key).toBe('timeoutSeconds')
    expect(invalidNumericField({ ...ok, timeoutSeconds: null })?.key).toBe('timeoutSeconds')
    expect(invalidNumericField({ ...ok, timeoutSeconds: 4 })?.key).toBe('timeoutSeconds')    // alt sınır
    expect(invalidNumericField({ ...ok, timeoutSeconds: 999 })?.key).toBe('timeoutSeconds')  // sessiz kırpma
    expect(invalidNumericField({ ...ok, confirmAttempts: '' })?.key).toBe('confirmAttempts')
    expect(invalidNumericField({ ...ok, recoveryChecks: 0 })?.key).toBe('recoveryChecks')    // min 1

    // 0 GEÇERLİ bir teyit değeri (teyitsiz mod) — yanlışlıkla reddedilmemeli
    expect(invalidNumericField({ ...ok, confirmAttempts: 0 })).toBeNull()
    // Sınırlar girdi nitelikleriyle TEK kaynaktan gelmeli
    expect(SCRIPTED_NUM_FIELDS.map(f => f.key))
      .toEqual(['timeoutSeconds', 'confirmAttempts', 'recoveryChecks'])
  })

  it('F3: "Gizli" işaretlemek girilen env DEĞERİNİ silmez', async () => {
    // Eskiden `value: ''` yazılıyordu: 200 karakterlik token yapıştırıp gizli yapan kullanıcı
    // değerini kaybediyor, alan password olduğu için fark etmiyor, kayıtta env boş kalıyordu.
    render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /new monitor|yeni monitör/i }))
    fireEvent.click(await screen.findByRole('button', { name: /add variable|değişken ekle/i }))

    const val = document.querySelector('.modal-box .env-row input.env-val')
    fireEvent.change(val, { target: { value: 'cok-gizli-token' } })
    fireEvent.click(document.querySelector('.modal-box .env-row input[type="checkbox"]'))

    expect(document.querySelector('.modal-box .env-row input.env-val').value).toBe('cok-gizli-token')
  })

  it('boş monitör listesi spinner DEĞİL boş-durum bloğu gösterir', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, can_manage: true, monitors: [] } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(container.querySelector('.status-block')).not.toBeNull())
    expect(container.querySelector('.pg-spinner')).toBeNull()   // dönen spinner yok
  })
})
