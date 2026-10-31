import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act, cleanup } from './test-utils.jsx'

// Ayarlar → Platform → k6 motoru (2026-10-29): yükleme + motorlar (çalışıyor / bulunamadı / tanımlı değil), TLS kaçışları →
// yalnız değişen alanın kaydı, sunucu alan hatası alanın altında, geçiş onayı + hazırlık (tarama yok / gerileme / sürüyor),
// tarama başlatma + 5 sn yoklama + bitince yeniden okuma, sonuç süzgeçleri (kutucuk / fark / arama), CSV, okuma hatası.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      k6Engine: vi.fn(), k6EngineReprobe: vi.fn(), saveK6EngineSettings: vi.fn(), k6EngineSwitch: vi.fn(), k6EngineRollback: vi.fn(),
      k6ScanStart: vi.fn(), k6Scans: vi.fn(), k6Scan: vi.fn(), k6ScanCancel: vi.fn(),
    },
  }),
}))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: vi.fn() }))
import { api } from '../api/client'
import { downloadCsv } from '../utils/csvExport.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import K6EngineSettings from '../components/admin/k6engine/K6EngineSettings.jsx'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const GODEBUG_KEYS = [
  { key: 'tlsrsakex', description: 'RSA anahtar değişimli şifre takımları (1 = aç)' },
  { key: 'tls3des', description: '3DES şifre takımları (1 = aç)' },
  { key: 'tlssha1', description: "TLS 1.2'de SHA-1 imzalar (1 = aç)" },
  { key: 'x509negativeserial', description: 'negatif seri numaralı sertifikalar (1 = kabul et)' },
  { key: 'rsa1024min', description: '1024 bitten kısa RSA anahtarları (0 = kabul et)' },
  { key: 'tlsmlkem', description: 'ML-KEM anahtar paylaşımı (0 = kapat)' },
  { key: 'tlssecpmlkem', description: 'SecP256r1/SecP384r1 ML-KEM anahtar paylaşımı (0 = kapat)' },
]
const ACTIVE = { bin: '/usr/local/bin/k6', available: true, version: 'v2.2.0', probed_at: '2026-10-29T20:15:03' }
const CANDIDATE = { bin: '/usr/local/bin/k6-2.3', available: true, version: 'v2.3.0', probed_at: '2026-10-29T20:15:04' }
const summary = (extra = {}) => ({
  id: 7, state: 'DONE', started_at: '2026-10-29T18:00:00', finished_at: '2026-10-29T18:05:00', heartbeat_at: '2026-10-29T18:05:00',
  started_by_name: 'Erdi İnanmış', owner_pod: 'site-monitor-7c9d', active_bin: ACTIVE.bin, active_version: 'v2.2.0',
  candidate_bin: CANDIDATE.bin, candidate_version: 'v2.3.0', scope: 'ALL', total: 3, done: 3, regressions: 1, improvements: 0,
  unchanged: 1, no_baseline: 0, failure: null, ...extra,
})
const RESULTS = [
  { monitor_id: 11, monitor_name: 'İnternet Şubesi Girişi', team_id: 1, team_name: LONG_TEAM, diff: 'SAME_PASS', status: 'PASS', exit_code: 0,
    duration_ms: 1200, error_class: null, error: null, archive: 'OK', retried: false, baseline_status: 'PASS', baseline_k6_version: 'v2.2.0',
    baseline_checked_at: '2026-10-29T17:50:00', baseline_duration_ms: 1100, checked_at: '2026-10-29T18:01:00' },
  { monitor_id: 12, monitor_name: 'Ödeme API Senaryosu', team_id: 2, team_name: 'Ödeme Sistemleri', diff: 'REGRESSION', status: 'FAIL', exit_code: 99,
    duration_ms: 3400, error_class: 'TLS', error: 'tls: handshake failure\nremote error: tls: insufficient security level', archive: 'OK', retried: true,
    baseline_status: 'PASS', baseline_k6_version: 'v2.2.0', baseline_checked_at: '2026-10-29T17:51:00', baseline_duration_ms: 900, checked_at: '2026-10-29T18:02:00' },
  { monitor_id: 13, monitor_name: 'Kampanya Sayfası', team_id: null, team_name: null, diff: 'NO_BASELINE', status: 'PASS', exit_code: 0,
    duration_ms: 800, error_class: null, error: null, archive: 'OK', retried: false, baseline_status: null, checked_at: '2026-10-29T18:03:00' },
]
const detail = (extra = {}) => ({
  ...summary(extra),
  counts: { REGRESSION: 1, FLAKY: 0, CHANGED: 0, NEW_FAIL: 0, SLOWER: 0, IMPROVED: 0, SAME_FAIL: 0, SAME_PASS: 1, NO_BASELINE: 1, NOT_RUN: 0 },
  results: RESULTS,
})
const data = (extra = {}) => ({
  pod: 'site-monitor-7c9d6f8b5-xk2lq',
  engines: { active: ACTIVE, rollback: null, candidate: CANDIDATE },
  min_version: 'v2.0.0',
  settings: { rollback_bin: '', candidate_bin: CANDIDATE.bin, godebug: '', user_agent: '', excluded_ids: [] },
  godebug_keys: GODEBUG_KEYS,
  scripted_enabled: true, can_switch: true, can_rollback: false,
  monitors: [
    { id: 11, name: 'İnternet Şubesi Girişi', team_id: 1, team_name: LONG_TEAM, active: true, excluded: false },
    { id: 12, name: 'Ödeme API Senaryosu', team_id: 2, team_name: 'Ödeme Sistemleri', active: true, excluded: false },
    { id: 13, name: 'Kampanya Sayfası', team_id: null, team_name: null, active: true, excluded: false },
    { id: 14, name: 'Eski senaryo', team_id: 2, team_name: 'Ödeme Sistemleri', active: false, excluded: false },
  ],
  scan: summary(),
  ...extra,
})
const ok = (d) => ({ success: true, data: d })
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const slots = (name) => [...document.querySelectorAll(`[data-slot="${name}"]`)]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.admin.k6Engine.mockResolvedValue(ok(data()))
  api.admin.k6Scans.mockResolvedValue(ok({ scans: [summary()] }))
  api.admin.k6Scan.mockResolvedValue(ok(detail()))
})
afterEach(() => { vi.useRealTimers() })

async function mount() {
  render(<K6EngineSettings />)
  await screen.findByText('Motorlar')
  await waitFor(() => expect(slot('k6e-detail')).not.toBeNull())
}

describe('K6EngineSettings — motorlar', () => {
  it('yükler: bu pod, üç rol (çalışıyor / tanımlı değil), sürümler, en eski desteklenen sürüm (sözdizimi satırı yok); Adayı etkin yap açık, Yedeğe dön kapalı', async () => {
    await mount()
    expect(slot('k6e-pod')).toHaveTextContent('Bu pod: site-monitor-7c9d6f8b5-xk2lq')
    const rows = slots('k6e-engine')
    expect(rows.map((r) => r.getAttribute('data-role'))).toEqual(['active', 'rollback', 'candidate'])
    expect(rows.map((r) => r.getAttribute('data-state'))).toEqual(['running', 'unset', 'running'])
    expect(within(rows[0]).getByText('Çalışıyor')).toBeInTheDocument()
    expect(within(rows[0]).getByText('v2.2.0')).toBeInTheDocument()
    expect(within(rows[0]).getByText('/usr/local/bin/k6')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Tanımlı değil')).toBeInTheDocument()
    expect(rows[0].querySelector('[data-slot="k6e-engine-syntax"]')).toBeNull()
    // UTC damga Türkiye saatiyle (20:15 UTC → 23:15)
    expect(within(rows[0]).getByText(/29\.10\.2026 23:15/)).toBeInTheDocument()
    expect(slot('k6e-syntax-version')).toBeNull()
    expect(slot('k6e-min-version')).toHaveTextContent('v2.0.0')
    expect(slot('k6e-switch')).not.toBeDisabled()
    expect(slot('k6e-rollback')).toBeDisabled()
  })

  it('tanımlı ama çalıştırılamayan aday "Bulunamadı" (yeşil değil); geçiş kapalı ve nedeni yazılı', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({
      engines: { active: ACTIVE, rollback: null, candidate: { ...CANDIDATE, available: false, version: null } }, can_switch: false,
    })))
    await mount()
    const cand = slots('k6e-engine')[2]
    expect(cand).toHaveAttribute('data-state', 'missing')
    expect(within(cand).getByText('Bulunamadı').closest('[data-tone]')).toHaveAttribute('data-tone', 'danger')
    expect(within(cand).getByText('Sürüm bilinmiyor')).toBeInTheDocument()
    expect(slot('k6e-switch')).toBeDisabled()
    expect(slot('k6e-switch-why')).toHaveTextContent('çalışan ve etkin olandan farklı')
  })

  it('2.0\'dan eski ikili (ör. 0.49) "Desteklenmiyor" (yeşil değil) ve en eski sürümü söyler; geri dönüş kapalı', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({
      engines: { active: ACTIVE, rollback: { bin: 'k6-old', available: false, unsupported: true, version: 'v0.49.0', probed_at: '2026-10-29T20:15:03' },
        candidate: null },
      can_switch: false, can_rollback: false,
    })))
    await mount()
    const rb = slots('k6e-engine')[1]
    expect(rb).toHaveAttribute('data-state', 'unsupported')
    expect(within(rb).getByText('Desteklenmiyor').closest('[data-tone]')).toHaveAttribute('data-tone', 'danger')
    expect(within(rb).getByText('v0.49.0')).toBeInTheDocument()
    expect(rb.querySelector('[data-slot="k6e-engine-unsupported"]')).toHaveTextContent('en az v2.0.0')
    expect(slot('k6e-rollback')).toBeDisabled()
  })

  it('Yeniden yokla POST eder ve yanıtı uygular', async () => {
    await mount()
    api.admin.k6EngineReprobe.mockResolvedValue(ok(data({ pod: 'site-monitor-yeni' })))
    fireEvent.click(slot('k6e-reprobe'))
    await waitFor(() => expect(slot('k6e-pod')).toHaveTextContent('site-monitor-yeni'))
    expect(api.admin.k6EngineReprobe).toHaveBeenCalledTimes(1)
  })

  it('okuma hatası: tehlike bloğu + Tekrar dene (boş sayfa ya da "yok" değil); tekrar dene yeniden okur', async () => {
    api.admin.k6Engine.mockResolvedValueOnce({ success: false, error: 'Sunucuya ulaşılamadı — ağ bağlantınızı denetleyin.' })
    render(<K6EngineSettings />)
    const root = await waitFor(() => {
      const r = slot('k6e-root')
      expect(r).toHaveAttribute('data-state', 'failed')
      return r
    })
    expect(within(root).getByText('k6 motoru bilgileri okunamadı')).toBeInTheDocument()
    expect(within(root).getByText('Sunucuya ulaşılamadı — ağ bağlantınızı denetleyin.')).toBeInTheDocument()
    fireEvent.click(slot('k6e-retry'))
    await screen.findByText('Motorlar')
    expect(api.admin.k6Engine).toHaveBeenCalledTimes(2)
  })
})

describe('K6EngineSettings — ayarlar', () => {
  it('TLS kaçışı açılınca yalnız godebug gönderilir; kayıt çubuğu temizlenir', async () => {
    await mount()
    const row = document.querySelector('[data-slot="k6e-gd"][data-key="tlsrsakex"]')
    fireEvent.click(within(row).getByRole('switch'))
    expect(slot('k6e-godebug-value')).toHaveTextContent('tlsrsakex=1')
    api.admin.saveK6EngineSettings.mockResolvedValue({ success: true, message: 'k6 motoru ayarları kaydedildi (yeniden başlatma gerekmez).',
      data: data({ settings: { ...data().settings, godebug: 'tlsrsakex=1' } }) })
    const bar = slots('settings-save-bar')[0]
    fireEvent.click(within(bar).getByRole('button', { name: /^Kaydet$/ }))
    await waitFor(() => expect(api.admin.saveK6EngineSettings).toHaveBeenCalledTimes(1))
    expect(api.admin.saveK6EngineSettings).toHaveBeenCalledWith({ godebug: 'tlsrsakex=1' })
    await waitFor(() => expect(slots('settings-save-bar')[0]).not.toHaveAttribute('data-dirty', 'true'))
    // ML-KEM kapatma: değer 0
    fireEvent.click(within(document.querySelector('[data-slot="k6e-gd"][data-key="tlsmlkem"]')).getByRole('switch'))
    expect(slot('k6e-godebug-value')).toHaveTextContent('tlsrsakex=1,tlsmlkem=0')
  })

  it('istemci kuralı alanın altında, istek gitmez; sunucu alan hatası da alanın altında', async () => {
    await mount()
    const input = slot('k6e-f-candidate')
    fireEvent.change(input, { target: { value: '/opt/k6 next' } })
    const bar = slots('settings-save-bar')[0]
    fireEvent.click(within(bar).getByRole('button', { name: /^Kaydet$/ }))
    await waitFor(() => expect(document.querySelector('[data-field="candidate_bin"]')).toHaveTextContent('İkili yolu geçersiz'))
    expect(api.admin.saveK6EngineSettings).not.toHaveBeenCalled()

    fireEvent.change(input, { target: { value: '/opt/k6-next/k6' } })
    fireEvent.change(slot('k6e-f-ua'), { target: { value: 'SiteMonitor/1.0' } })
    api.admin.saveK6EngineSettings.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'user_agent',
      error: 'User-Agent yalnız yazdırılabilir ASCII karakter içerebilir (satır sonu, sekme, Türkçe harf olmaz).' })
    fireEvent.click(within(bar).getByRole('button', { name: /^Kaydet$/ }))
    await waitFor(() => expect(api.admin.saveK6EngineSettings).toHaveBeenCalledTimes(1))
    expect(api.admin.saveK6EngineSettings).toHaveBeenCalledWith({ candidate_bin: '/opt/k6-next/k6', user_agent: 'SiteMonitor/1.0' })
    await waitFor(() => expect(document.querySelector('[data-field="user_agent"]')).toHaveTextContent('yazdırılabilir ASCII'))
  })

  it('değişiklik yokken Kaydet kapalıdır (istek gitmez)', async () => {
    await mount()
    expect(within(slots('settings-save-bar')[0]).getByRole('button', { name: /^Kaydet$/ })).toBeDisabled()
  })
})

describe('K6EngineSettings — geçiş onayı ve hazırlık', () => {
  async function openSwitch() {
    fireEvent.click(slot('k6e-switch'))
    return screen.findByRole('alertdialog').catch(() => screen.findByRole('dialog'))
  }

  it('gerileme var → tehlike maddesi; sürüm → sürüm; onay POST eder, sunucu iletisi tostta', async () => {
    await mount()
    const dlg = await openSwitch()
    expect(dlg).toHaveAttribute('data-level', 'danger')
    expect(within(dlg).getByText('v2.2.0')).toBeInTheDocument()
    expect(within(dlg).getByText('v2.3.0')).toBeInTheDocument()
    const item = dlg.querySelector('[data-slot="k6e-ready-item"][data-key="regressions"]')
    expect(item).toHaveTextContent('1 izleme gerileme gösterdi')
    api.admin.k6EngineSwitch.mockResolvedValue({ success: true, message: 'Aday k6 etkin yapıldı (v2.3.0).', data: data({ can_switch: false, can_rollback: true }) })
    fireEvent.click(within(dlg).getByRole('button', { name: /Adayı etkin yap/ }))
    await waitFor(() => expect(api.admin.k6EngineSwitch).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('Aday k6 etkin yapıldı (v2.3.0).')).toBeInTheDocument()
    await waitFor(() => expect(slot('k6e-action-dialog')).toBeNull())
  })

  it('tarama yok → "henüz uyumluluk taraması yapılmadı" uyarısı, onay yine mümkün', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({ scan: null })))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [] }))
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    await waitFor(() => expect(slot('k6e-results-empty')).not.toBeNull())
    const dlg = await openSwitch()
    expect(dlg).toHaveAttribute('data-level', 'warning')
    expect(dlg.querySelector('[data-key="noScan"]')).toHaveTextContent('Henüz uyumluluk taraması yapılmadı')
    expect(slot('k6e-dlg-confirm')).not.toBeDisabled()
  })

  it('son tarama başka aday sürümüyle → uyarı; tarama sürüyorsa geçiş düğmesi kapalı', async () => {
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [summary({ candidate_version: 'v2.2.0', regressions: 0 })] }))
    await mount()
    const dlg = await openSwitch()
    expect(dlg.querySelector('[data-key="otherVersion"]')).toHaveTextContent('v2.2.0')
    fireEvent.click(slot('k6e-dlg-cancel'))
    await waitFor(() => expect(slot('k6e-action-dialog')).toBeNull())
  })

  it('tarama sürerken Adayı etkin yap kapalı ve nedeni yazılı', async () => {
    const running = summary({ id: 8, state: 'RUNNING', done: 1, total: 3, finished_at: null })
    api.admin.k6Engine.mockResolvedValue(ok(data({ scan: running })))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [running, summary()] }))
    api.admin.k6Scan.mockResolvedValue(ok({ ...detail({ id: 8, state: 'RUNNING', done: 1 }), results: [RESULTS[0]] }))
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    await waitFor(() => expect(slot('k6e-scan-running')).not.toBeNull())
    expect(slot('k6e-switch')).toBeDisabled()
    expect(slot('k6e-switch-why')).toHaveTextContent('Uyumluluk taraması sürerken')
    expect(slot('k6e-scan-progress')).toHaveTextContent('1 / 3 izleme tamamlandı')
  })

  it('başka sürüm koşan pod varken Adayı etkin yap kapalı ve pod adı + sürümü yazılı; okunamazsa da kapalı (inceleme M2)', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({ other_version_pods: [{ pod: 'site-monitor-6b8f-old', version: '20.171.0' }] })))
    await mount()
    expect(slot('k6e-switch')).toBeDisabled()
    expect(document.querySelector('[data-slot="k6e-switch-why"][data-why="mixed"]')).toHaveTextContent('site-monitor-6b8f-old (20.171.0)')
  })

  it('Yedeğe geri dön de aynı kapıya tabi: başka sürüm koşan pod varken ya da sürümler okunamazken kapalı, nedeni yazılı (inceleme L1)', async () => {
    const rb = { bin: '/opt/k6-rollback/k6', available: true, version: 'v2.2.0', probed_at: '2026-10-29T20:15:03' }
    const engines = { active: { bin: 'k6', available: true, version: 'v2.3.0', probed_at: '2026-10-29T20:15:03' }, rollback: rb, candidate: null }
    api.admin.k6Engine.mockResolvedValue(ok(data({ engines, can_switch: false, can_rollback: true,
      other_version_pods: [{ pod: 'site-monitor-6b8f-old', version: '20.172.1' }] })))
    await mount()
    expect(slot('k6e-rollback')).toBeDisabled()
    expect(document.querySelector('[data-slot="k6e-switch-why"][data-why="mixed"]')).toHaveTextContent('site-monitor-6b8f-old (20.172.1)')
    cleanup()
    api.admin.k6Engine.mockResolvedValue(ok(data({ engines, can_switch: false, can_rollback: true, other_version_pods: null })))
    await mount()
    expect(slot('k6e-rollback')).toBeDisabled()
    expect(document.querySelector('[data-slot="k6e-switch-why"][data-why="podsUnknown"]')).not.toBeNull()
    cleanup()
    api.admin.k6Engine.mockResolvedValue(ok(data({ engines, can_switch: false, can_rollback: true, other_version_pods: [] })))
    await mount()
    expect(slot('k6e-rollback')).not.toBeDisabled()
  })

  it('pod sürümleri okunamadıysa (null) Adayı etkin yap kapalı ve nedeni yazılı', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({ other_version_pods: null })))
    await mount()
    expect(slot('k6e-switch')).toBeDisabled()
    expect(document.querySelector('[data-slot="k6e-switch-why"][data-why="podsUnknown"]')).toHaveTextContent('Pod sürümleri okunamadı')
  })
})

describe('K6EngineSettings — tarama', () => {
  it('başlat (onaylı) → 5 sn yoklama ilerlemeyi günceller → bitince sayfa ve geçmiş yeniden okunur', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    api.admin.k6Engine.mockResolvedValue(ok(data({ scan: null })))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [] }))
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    expect(slot('k6e-scan-target')).toHaveTextContent('Taramada 3 etkin izleme koşacak')
    const started = summary({ id: 9, state: 'RUNNING', done: 0, total: 3, finished_at: null, regressions: 0 })
    api.admin.k6ScanStart.mockResolvedValue(ok(started))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [started] }))
    api.admin.k6Scan.mockResolvedValue(ok({ ...started, counts: {}, results: [] }))
    fireEvent.click(slot('k6e-scan-start'))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('3 etkin senaryo izlemesi aday ikiliyle (v2.3.0)')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Taramayı başlat' }))
    await waitFor(() => expect(api.admin.k6ScanStart).toHaveBeenCalledWith({}))
    await waitFor(() => expect(slot('k6e-scan-running')).not.toBeNull())
    expect(slot('k6e-scan-progress')).toHaveTextContent('0 / 3')

    api.admin.k6Scan.mockResolvedValue(ok({ ...started, done: 2, counts: {}, results: [RESULTS[0], RESULTS[1]] }))
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    await waitFor(() => expect(slot('k6e-scan-progress')).toHaveTextContent('2 / 3'))

    const finished = detail({ id: 9, state: 'DONE' })
    api.admin.k6Scan.mockResolvedValue(ok(finished))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [summary({ id: 9 })] }))
    api.admin.k6Engine.mockResolvedValue(ok(data({ scan: summary({ id: 9 }) })))
    const engineCalls = api.admin.k6Engine.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    await waitFor(() => expect(slot('k6e-scan-running')).toBeNull())
    await waitFor(() => expect(api.admin.k6Engine.mock.calls.length).toBeGreaterThan(engineCalls))
    await waitFor(() => expect(slot('k6e-detail')).toHaveAttribute('data-state', 'DONE'))
  })

  it('tarama dışı izleme seçimi kendi kayıt çubuğuyla yalnız excluded_ids gönderir; kaydedilmemişken tarama başlatılamaz', async () => {
    await mount()
    const item = document.querySelector('[data-slot="k6e-ex-item"][data-id="12"]')
    fireEvent.click(within(item).getByRole('checkbox'))
    expect(slot('k6e-scan-start')).toBeDisabled()
    expect(slots('k6e-scan-why').map((n) => n.textContent).join(' ')).toContain('Önce tarama dışı listesini kaydedin')
    api.admin.saveK6EngineSettings.mockResolvedValue({ success: true, data: data({ settings: { ...data().settings, excluded_ids: [12] } }) })
    const exBar = within(slot('k6e-excluded')).getByRole('button', { name: 'Listeyi kaydet' })
    fireEvent.click(exBar)
    await waitFor(() => expect(api.admin.saveK6EngineSettings).toHaveBeenCalledWith({ excluded_ids: [12] }))
    await waitFor(() => expect(slot('k6e-scan-target')).toHaveTextContent('Taramada 2 etkin izleme koşacak'))
    expect(slot('k6e-ex-chip')).toHaveTextContent('Ödeme API Senaryosu')
    // pasif izleme seçicide yok
    expect(document.querySelector('[data-slot="k6e-ex-item"][data-id="14"]')).toBeNull()
  })

  it('satırda Yeniden tara yalnız o izlemeyi gönderir', async () => {
    await mount()
    const row = document.querySelector('[data-slot="k6e-res-row"][data-id="12"]')
    api.admin.k6ScanStart.mockResolvedValue(ok(summary({ id: 10, state: 'RUNNING', scope: 'SELECTED', total: 1, done: 0 })))
    fireEvent.click(within(row).getByRole('button', { name: 'Yeniden tara: Ödeme API Senaryosu' }))
    await waitFor(() => expect(api.admin.k6ScanStart).toHaveBeenCalledWith({ monitor_ids: [12] }))
  })

  it('başlatma reddi (K6_SCAN_RUNNING) sunucu metnini tostta gösterir', async () => {
    api.admin.k6Engine.mockResolvedValue(ok(data({ scan: null })))
    api.admin.k6Scans.mockResolvedValue(ok({ scans: [] }))
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    api.admin.k6ScanStart.mockResolvedValue({ success: false, code: 'K6_SCAN_RUNNING', scan_id: 4,
      error: 'Başka bir uyumluluk taraması sürüyor — bitmesini bekleyin ya da iptal edin.' })
    fireEvent.click(slot('k6e-scan-start'))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Taramayı başlat' }))
    expect(await screen.findByText('Başka bir uyumluluk taraması sürüyor — bitmesini bekleyin ya da iptal edin.')).toBeInTheDocument()
  })
})

describe('K6EngineSettings — sonuçlar', () => {
  it('kutucuk / fark / arama süzer; uzun takım adı tam; hata metni açılır', async () => {
    await mount()
    expect(slots('k6e-res-row')).toHaveLength(3)
    // gerileme önce
    expect(slots('k6e-res-row')[0]).toHaveAttribute('data-id', '12')
    expect(within(document.querySelector('[data-slot="k6e-res-row"][data-id="11"]')).getByText(LONG_TEAM)).toBeInTheDocument()
    // Kutucuk: Gerileme
    const tile = document.querySelector('[data-slot="stat-item"][data-key="REGRESSION"]')
    fireEvent.click(tile)
    expect(slots('k6e-res-row').map((r) => r.getAttribute('data-id'))).toEqual(['12'])
    expect(slot('k6e-res-count')).toHaveTextContent('1 / 3')
    fireEvent.click(tile)   // tekrar → temizler
    expect(slots('k6e-res-row')).toHaveLength(3)
    // Fark seçici
    fireEvent.change(slot('k6e-res-diff'), { target: { value: 'NO_BASELINE' } })
    expect(slots('k6e-res-row').map((r) => r.getAttribute('data-id'))).toEqual(['13'])
    fireEvent.click(slot('k6e-res-clear'))
    // Türkçe katlamalı arama
    fireEvent.change(slot('k6e-res-search'), { target: { value: 'internet sube' } })
    expect(slots('k6e-res-row').map((r) => r.getAttribute('data-id'))).toEqual(['11'])
    fireEvent.change(slot('k6e-res-search'), { target: { value: '' } })
    // Hata metni katlanır
    const reg = document.querySelector('[data-slot="k6e-res-row"][data-id="12"]')
    expect(within(reg).getByText('TLS')).toBeInTheDocument()
    fireEvent.click(within(reg).getByRole('button', { name: /Hata metnini göster/ }))
    expect(reg.querySelector('[data-slot="k6e-problem-pre"]')).toHaveTextContent('insufficient security level')
    expect(within(reg).getByText('Yeniden denendi')).toBeInTheDocument()
  })

  it('CSV yalnız süzülmüş satırları indirir', async () => {
    await mount()
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="REGRESSION"]'))
    fireEvent.click(slot('k6e-results-csv'))
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    const [name, csv] = downloadCsv.mock.calls[0]
    expect(name).toBe('k6-uyumluluk-taramasi-7.csv')
    expect(csv).toContain('Ödeme API Senaryosu')
    expect(csv).toContain('Gerileme')
    expect(csv).not.toContain('İnternet Şubesi Girişi')
  })

  it('tarama geçmişi okunamazsa tehlike bloğu + Tekrar dene ("tarama yok" değil)', async () => {
    api.admin.k6Scans.mockResolvedValueOnce({ success: false, error: 'Sunucu yanıt vermedi (HTTP 503) — birazdan yeniden deneyin.' })
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    await screen.findByText('Tarama geçmişi okunamadı')
    expect(slot('k6e-results-empty')).toBeNull()
    fireEvent.click(slot('k6e-results-retry'))
    await waitFor(() => expect(slot('k6e-detail')).not.toBeNull())
  })

  it('seçili taramanın sonuçları okunamazsa tehlike bloğu + Tekrar dene', async () => {
    api.admin.k6Scan.mockResolvedValueOnce({ success: false, error: 'Uyumluluk taraması bulunamadı (#7).' })
    render(<K6EngineSettings />)
    await screen.findByText('Motorlar')
    await screen.findByText('Tarama sonuçları okunamadı')
    fireEvent.click(slot('k6e-detail-retry'))
    await waitFor(() => expect(slot('k6e-detail')).not.toBeNull())
  })
})

describe('K6EngineSettings — E10 (2026-10-31)', () => {
  it('sıfır değerli fark kutucukları NÖTR; sayısı olan kendi tonunda; tarama seçici sarılır (yerel <select> değil)', async () => {
    await mount()
    const tone = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`).getAttribute('data-tone')
    expect(tone('REGRESSION')).toBe('critical')   // 1 gerileme
    expect(tone('FLAKY')).toBe('total')            // 0 → nötr (amber değil)
    expect(tone('NOT_RUN')).toBe('total')
    const pick = slot('k6e-scan-pick')
    expect(pick.querySelector('select')).toBeNull()
    expect(pick.querySelector('[role="combobox"]')).not.toBeNull()
  })

  it('giriş metni yaşlanmaz: belirli bir geçişi "tamamlandı" diye anlatmaz', async () => {
    await mount()
    expect(document.body.textContent).not.toMatch(/0\.49 → 2\.3 geçişi tamamlandı/)
  })
})
