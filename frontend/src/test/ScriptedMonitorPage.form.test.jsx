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
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — form, taslak ve sürümler', () => {

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
          active: false, script: 'export default function(){}', notification_group_id: 7,
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
      groupName: 'Senaryolar', teamId: 5, tags: 'prod,kritik', notifyEmail: false, notifyWebhook: true,
      intervalSeconds: 900, timeoutSeconds: 45,
      confirmAttempts: 5, confirmIntervalSeconds: 45, recoveryChecks: 4, recoveryIntervalSeconds: 90,
      // Bildirim grubu da kopyalanir: kopya, kaynagin alarmini ALAN ekibe gitsin.
      notificationGroupId: 7,
      active: false,   // duraklatılmış kaynağın kopyası da pasif doğar
      script: 'export default function(){}',
      useProxy: 'AUTO',   // vekil tercihi de kopyalanır (kaynakta yoksa AUTO)
      slowResponseEnabled: false, slowThresholdMs: 15000,   // yavaşlık alarmı opt-in — kaynakta yoksa kapalı
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
    /**
     * Gruplar artik KATLANABILIR (`collapsibleGroups`): yerlesik katalog 100 sablon oldugu icin
     * dallar kapali geliyor ve kapali dalin secenekleri hic CIZILMIYOR. Kullanici da once dali
     * acmak zorunda; test de ayni yolu izler.
     */
    const expandSourceGroups = () => {
      for (const b of document.querySelectorAll('.sc-source-select .ss-group-btn')) {
        if (b.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(b)
      }
    }
    const pickSource = (labelRe) => {
      openSource()
      expandSourceGroups()
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
      // Şablon kütüphanesi de açılışta yükleniyor: beklemezsek state güncellemesi act() dışında
      // düşer ve konsolu uyarıyla doldurur (kural: test çıktısı temiz kalır).
      await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
      await screen.findByText(row.name)
      fireEvent.click(utils.container.querySelector('.mon-act--edit'))
      return utils
    }

    it('kayıtlı scriptler EN ÜSTTE ve AÇIK gelir (bu monitör başta); şablonlar kategori dallarında', async () => {
      // Eskiden secici TEK duz "Sablonlar" basligi gosteriyordu; artik yerlesikler kategoriye
      // dallaniyor. Bu testin ISPATLADIGI sey degismedi: kapsam sirasi + duzenlenen monitorun
      // en basta ve isaretli olmasi. KAYITLI dal ACIK gelir — kendi script'ini secmek en sik
      // yapilan is, ona fazladan tik eklenmemeli (`groupOpen`).
      await openEditFor(FAILING)
      openSource()
      const groups = [...document.querySelectorAll('.sc-source-select .ss-group')].map(g => g.textContent)
      expect(groups[0]).toMatch(/saved scripts|kayıtlı/i)
      expect(groups.length).toBeGreaterThan(1)                      // ardindan sablon dallari
      expect(groups.slice(1).every(g => !/saved scripts|kayıtlı/i.test(g))).toBe(true)

      // Duzenlenen monitor kendi adiyla ve "(bu monitor)" isaretiyle EN BASTA — dal ACIK oldugu
      // icin fazladan tik GEREKMEDEN gorunur.
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
      fireEvent.click(utils.container.querySelector('.mon-act--edit'))

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

    it('yerleşik şablonlar KATEGORİ dalları altında; dal açılınca script seçilebilir', async () => {
      // Kullanici bildirimi 2026-08-24: "scriptin hangi templates grubundan geldigi belli degil".
      // Yerlesik katalog 100 sablon (10 kategori x 10) ve hepsi tek duz baslik altina dokuluyordu.
      render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
      fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni monitör/i }))
      openSource()

      // Dal basliklari VAR ve kategoriye gore ayrilmis...
      const heads = [...document.querySelectorAll('.sc-source-select .ss-group-btn')]
        .map(b => b.textContent)
      // Etiketler i18n'den gelir (tpl.cat.*) — TR "Erişilebilirlik & Uptime" / "Kimlik & Oturum",
      // EN "Availability & uptime" / "Sign-in & sessions".
      expect(heads.some(h => /erişilebilirlik|availability/i.test(h))).toBe(true)
      expect(heads.some(h => /kimlik|sign-in/i.test(h))).toBe(true)
      // Uc sablon, uc AYRI kategori → uc dal; hepsi tek yiginda DEGIL.
      expect(heads).toHaveLength(3)

      // ...ve dal KAPALI oldugu icin script'i henuz secemeyiz.
      expect(sourceOptions().some(o => /smoke/i.test(o.textContent))).toBe(false)

      // Dali ac → altindaki script secilebilir olur.
      const branch = [...document.querySelectorAll('.sc-source-select .ss-group-btn')]
        .find(b => /erişilebilirlik|availability/i.test(b.textContent))
      fireEvent.mouseDown(branch)
      const opt = sourceOptions().find(o => /smoke/i.test(o.textContent))
      expect(opt).toBeTruthy()
      fireEvent.mouseDown(opt)
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))
    })

    it('şablon seçilince panel KAYBOLUR (asıl şikayet) ve script şablonunkiyle değişir', async () => {
      await openEditFor(FAILING)
      expect(inModal('.sc-testrun')).not.toBeNull()

      pickSource(/smoke/i)

      expect(inModal('.sc-testrun')).toBeNull()
      // Şablonun GÖVDESİ liste yanıtında gelmez (bilinçli), tekil uçtan asenkron çekilir → bekle.
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))
    })

    it('kayıtlı script seçilince o monitörün script+env\'i yüklenir ve paneli geri gelir', async () => {
      await openEditFor(FAILING)
      pickSource(/smoke/i)
      expect(inModal('.sc-testrun')).toBeNull()
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))

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
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))
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
      await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
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
      fireEvent.click(document.querySelector('.mon-act--edit'))
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
      fireEvent.click(document.querySelector('.mon-act--edit'))
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
        // Dallar kapali gelir (collapsibleGroups) → once ac, sonra sec.
        for (const b of document.querySelectorAll('.sc-source-select .ss-group-btn')) {
          if (b.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(b)
        }
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

    it('"Taslağı sil" başarılıysa şerit kalkar', async () => {
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: 'new', monitor_id: null, monitor_name: 'yarim-kalan',
          form_json: JSON.stringify({ name: 'yarim-kalan', script: 'export default function(){}' }),
          updated_at: '2026-08-13T09:30:00' },
      ] } })
      await renderPage()

      expect(await screen.findByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /^discard( draft)?$|^taslağı sil$/i }))

      await waitFor(() => expect(api.monitoring.deleteScriptedDraft).toHaveBeenCalledWith('new'))
      await waitFor(() =>
        expect(screen.queryByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeNull())
    })

    it('REGRESYON: silme SUNUCUDA başarısızsa şerit KALIR (yalancı "taslak silindi" bildirimi)', async () => {
      // Yasanan hata: backend'de turetilmis silme sorgusu transaction'siz kostugu icin her seferinde
      // dusuyordu; arayuz ise yaniti hic okumadan "taslak silindi" deyip seridi yerel state'ten
      // kaldiriyordu. Kullanici her acilista ayni uyariyi goruyor, taslak asla silinmiyordu.
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: 'new', monitor_id: null, monitor_name: 'yarim-kalan',
          form_json: JSON.stringify({ name: 'yarim-kalan', script: 'export default function(){}' }),
          updated_at: '2026-08-13T09:30:00' },
      ] } })
      api.monitoring.deleteScriptedDraft.mockResolvedValue({ success: false, error: 'tx yok' })
      await renderPage()

      expect(await screen.findByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: /^discard( draft)?$|^taslağı sil$/i }))

      await waitFor(() => expect(api.monitoring.deleteScriptedDraft).toHaveBeenCalled())
      // Serit YERINDE: kullanici silinmedigini gorsun, sahte basari ile kaybolmasin.
      expect(screen.getByText(/unfinished draft|tamamlanmamış taslağınız/i)).toBeInTheDocument()
    })

    it('mevcut monitörde taslak OTOMATİK uygulanmaz — kullanıcıya sorulur', async () => {
      api.monitoring.getScriptedDrafts.mockResolvedValue({ success: true, data: { drafts: [
        { monitor_key: '3', monitor_id: 3, monitor_name: 'llm-test',
          form_json: JSON.stringify({ script: 'export default function(){ /* taslaktan */ }' }),
          updated_at: '2026-08-13T09:30:00' },
      ] } })
      const { container } = await renderPage()
      fireEvent.click(container.querySelector('.mon-act--edit'))

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

      fireEvent.click(screen.getByText('v1.0.0').closest('.sc-vt-row'))
      const loadBtn = await screen.findByRole('button', { name: /load this version|editöre yükle/i })
      fireEvent.click(loadBtn)

      // Geri dönüş doğrudan YAZMAZ: içerik editöre gelir, kullanıcı kaydedince yeni sürüm olur
      await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('eski surum'))
      expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()
    })

    /**
     * Kaydetme sonrası doğrulama koşumu (2026-08-20). 2026-08-20 olayında bozuk bir sürüm
     * 3 dakika boyunca fark edilmedi; koşum kaydeder kaydetmez tetiklenince saniyeler içinde
     * görünür. KAPI ince: yalnız GERÇEKTEN yeni sürüm yazıldıysa çalışmalı — yoksa her ayar
     * kaydı k6 havuzundan (varsayılan 2) slot yakar.
     */
    it('YENİ SÜRÜM yazılınca doğrulama koşumu tetiklenir ve modal açık kalır', async () => {
      const { container } = await renderPage()
      // Mock renderPage'DEN SONRA kurulur: renderPage kendi varsayılanını yazıyor ve
      // daha önce kurulan mock'u ezerdi (test yanlış sebeple yeşil/kırmızı olurdu).
      api.monitoring.updateScriptedMonitor.mockResolvedValue({
        success: true, data: { id: MON.id, script_version: '1.0.3' },   // sürüm ARTTI
      })
      api.monitoring.triggerScriptedCheck.mockResolvedValue({ success: true, data: { queued: true } })

      fireEvent.click(container.querySelector('.mon-act--edit'))
      fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

      await waitFor(() => expect(api.monitoring.triggerScriptedCheck).toHaveBeenCalledWith(MON.id))
      // Modal KAPANMAZ: sonuç formda gösterilecek.
      expect(container.ownerDocument.querySelector('.sc-smoke')).not.toBeNull()
    })

    it('YALNIZ AYAR kaydında (sürüm değişmedi) doğrulama koşumu tetiklenMEZ', async () => {
      const { container } = await renderPage()
      api.monitoring.updateScriptedMonitor.mockResolvedValue({
        success: true, data: { id: MON.id, script_version: '1.0.2' },   // MON ile AYNI sürüm
      })

      fireEvent.click(container.querySelector('.mon-act--edit'))
      fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))

      await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
      expect(api.monitoring.triggerScriptedCheck).not.toHaveBeenCalled()
    })
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
      .toEqual(['timeoutSeconds', 'confirmAttempts', 'recoveryChecks', 'slowThresholdMs'])

    // Yavaşlık eşiği KOŞULLU: alarm kapalıyken boş/geçersiz değer kaydetmeyi bloklamamalı,
    // açıkken ise sessizce 500 ms'e (ya da 0'a) düşmesin diye zorunlu.
    expect(invalidNumericField({ ...ok, slowResponseEnabled: false, slowThresholdMs: '' })).toBeNull()
    expect(invalidNumericField({ ...ok, slowResponseEnabled: true, slowThresholdMs: '' })?.key)
      .toBe('slowThresholdMs')
    expect(invalidNumericField({ ...ok, slowResponseEnabled: true, slowThresholdMs: 100 })?.key)
      .toBe('slowThresholdMs')
    expect(invalidNumericField({ ...ok, slowResponseEnabled: true, slowThresholdMs: 15000 })).toBeNull()
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

  it('yavaş koşum alarmı: eşik alanı alarm kapalıyken DİSABLE, açılınca payload’a girer', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A',
                   slow_response_enabled: false, slow_threshold_ms: 15000,
                   script: 'export default function(){}', group_name: 'G',
                   interval_seconds: 300, timeout_seconds: 60,
                   confirm_attempts: 3, confirm_interval_seconds: 30,
                   recovery_checks: 3, recovery_interval_seconds: 30, active: true, env: [] }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())
    fireEvent.click(container.querySelector('.mon-act--edit'))

    const thresholdInput = await waitFor(() => {
      const el = document.querySelector('input[type="number"][max="180000"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(thresholdInput.disabled).toBe(true)        // alarm kapalı → eşik düzenlenemez

    // Sınıf adına DEĞİL erişilebilir ada bağlan: sınıf seçicisi, sınıf yanlış/ölü olsa bile
    // yeşil kalıyordu (kaymış tik kutusu hatası tam böyle gözden kaçtı).
    const checkbox = screen.getByRole('checkbox', { name: /yavaş koşum alarmı|slow-run alert/i })
    fireEvent.click(checkbox)
    expect(thresholdInput.disabled).toBe(false)
    fireEvent.change(thresholdInput, { target: { value: '8000' } })

    fireEvent.click(screen.getByRole('button', { name: /^save$|^kaydet$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
    const payload = api.monitoring.updateScriptedMonitor.mock.calls[0][1]
    expect(payload.slowResponseEnabled).toBe(true)
    expect(payload.slowThresholdMs).toBe(8000)
  })

  /**
   * YERLEŞİM REGRESYONU — "yavaş koşum alarmı"nın tik kutusu etiketin üstüne kayıyordu.
   * Sebebi görsel değil YAPISAL: label `sc-check` sınıfını taşıyordu ve o sınıfın App.css'te hiçbir
   * karşılığı yoktu. Böyle ÖLÜ bir sınıfta işaret kutusu `.form-grid label`in varsayılanına düşer
   * (sütun yönü + input'lara metin-kutusu padding/kenarlığı) — yani hata sessizdir: konsol temiz,
   * test yeşil, yalnız ekran bozuk.
   *
   * jsdom yerleşim HESAPLAMAZ; bu yüzden burada piksel değil SÖZLEŞME kilitleniyor: form ızgarasının
   * doğrudan label çocuğu olan her tik kutusu, satır yönünü veren PAYLAŞILAN `checkbox-label`
   * sınıfını kullanmalı. Tek bir kutuya değil, hata SINIFINA bakıyor — sonraki checkbox aynı tuzağa
   * düşerse bu test onu da yakalar. (Gerçek görünüm tarayıcıda doğrulanmalı: [[jsdom kör noktası]])
   */
  it('form ızgarasındaki HER tik kutusu paylaşılan checkbox-label sınıfını kullanır (ölü sınıf = kaymış kutu)', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: {
      k6_available: true, k6_version: 'v0.49.0', can_manage: true,
      monitors: [{ id: 1, name: 'OIDC Login', status: 'PASS', team_id: 5, team_name: 'SY-A',
                   slow_response_enabled: false, slow_threshold_ms: 15000,
                   script: 'export default function(){}', group_name: 'G',
                   interval_seconds: 300, timeout_seconds: 60,
                   confirm_attempts: 3, confirm_interval_seconds: 30,
                   recovery_checks: 3, recovery_interval_seconds: 30, active: true, env: [] }],
    } })
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(screen.getByText('OIDC Login')).toBeInTheDocument())
    fireEvent.click(container.querySelector('.mon-act--edit'))

    const slow = await screen.findByRole('checkbox', { name: /yavaş koşum alarmı|slow-run alert/i })
    expect(slow.closest('label').className).toContain('checkbox-label')

    // Izgaranın DOĞRUDAN label çocukları: env satırlarındaki (.env-secret) kendi düzeni olan
    // kutular kapsam dışı, onlar ızgara hücresi değil.
    const gridCheckboxLabels = [...document.querySelectorAll('.form-grid > label')]
      .filter(l => l.querySelector('input[type="checkbox"]'))
    // B1: e-posta ve webhook kutuları ortak bildirim bloğuna (NotifyChannels) taşındı, artık
    // ızgara hücresi değiller. KURAL değişmedi — ızgaradaki her kutu paylaşılan sınıfı taşır;
    // taban yalnız "seçici boşa düşmesin" güvencesi olarak kalıyor (yavaş alarm + aktif).
    expect(gridCheckboxLabels.length).toBeGreaterThanOrEqual(2)
    for (const label of gridCheckboxLabels) {
      expect(label.className, `sınıfsız/ölü sınıflı tik kutusu: "${label.textContent.trim()}"`)
        .toContain('checkbox-label')
    }
  })
})
