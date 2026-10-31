import { render, screen, fireEvent, waitFor, within, act, fillGroupAndTags } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

// Sentetik İzleme formu + ilk kurulum (2026-10-31, k6r fe1): bölümlü düzen, kaynak değişiminde onay + env birleştirme
// (H1), kendi izlemesinde not yok (D1), sürüm yüklemede env adları (D2), alan altı sayısal hata (D3), kod
// düzenleyici adı (E1), kayıtlı gizli değerle test (B1), şablon değişken sihirbazı, canlı __ENV denetimi, test paneli
// (kaydırma / adım listesi / durduğu adım / hata sınıfı / bağlam çipleri / satıra git), zamanlama düzenleyicisi (D2),
// boş durum düğmeleri.

// CodeEditor (prismjs) jsdom'da ağır → düz textarea; erişilebilir ad ve "satıra git" isteği öznitelik olarak görünür
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange, ariaLabel, gotoLine }) => (
    <textarea data-testid="code-editor" aria-label={ariaLabel} data-goto={gotoLine ? `${gotoLine.line}:${gotoLine.seq}` : ''}
      value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
// Adım listesi fe2'nin paylaşılan bileşeni — burada aldığı prop'lar sınanır
vi.mock('../components/scripted/StepList.jsx', () => ({
  default: (props) => (
    <div data-testid="step-list" data-props={JSON.stringify({
      steps: props.steps ?? null, truncated: !!props.truncated, checksJson: props.checksJson ?? null, status: props.status ?? null })} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../components/scripted/ScriptedTemplatesTab.jsx', () => ({
  default: () => <div data-testid="templates-tab" />,
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
}))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks, TEMPLATE_ROWS } from './helpers/scriptedHarness.jsx'

const MON = {
  id: 7, name: 'OIDC Login', status: 'FAIL', team_id: 5, team_name: 'SY-A', group_name: 'G', tags: 'prod',
  script: 'export default function(){ http.get(__ENV.BASE_URL) }', script_version: '1.0.0',
  interval_seconds: 300, timeout_seconds: 60, confirm_attempts: 3, confirm_interval_seconds: 30,
  recovery_checks: 3, recovery_interval_seconds: 30, active: true, checked_at: '2026-10-30T10:00:00',
  env: [{ name: 'BASE_URL', secret: false, value: 'https://ibank.example.com' },
        { name: 'PASSWORD', secret: true, value_set: true },
        { name: 'MANUAL_ONE', secret: false, value: 'el' }],
  error: 'k6 check failed', exit_code: 99, checks_passed: 1, checks_failed: 1, can_edit: true,
}
const OTHER = { id: 9, name: 'Other monitor', status: 'PASS', team_id: 5, script: 'export default function(){}',
  env: [{ name: 'TOKEN', secret: true, value_set: true }, { name: 'API_URL', secret: false, value: 'https://api.example.com' }] }

function listWith(rows) {
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true, data: { k6_available: true, k6_version: 'v2.3.0', can_manage: true, monitors: rows },
  })
}

const dialog = () => screen.getAllByRole('dialog')[0]
const inDialog = (sel) => dialog().querySelector(sel)
const sourceTrigger = () => document.querySelector('.sc-source-select button[role="combobox"]')
function pickSource(labelRe) {
  if (sourceTrigger().getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(sourceTrigger())
  for (const b of document.querySelectorAll('[role="listbox"] button[aria-expanded]')) {
    if (b.getAttribute('aria-expanded') !== 'true') fireEvent.mouseDown(b)
  }
  fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find(o => labelRe.test(o.textContent)))
}
const envNames = () => within(dialog()).queryAllByRole('textbox', { name: /^NAME$/ }).map(i => i.value)
const openTiming = () => {
  const trig = inDialog('[data-slot="sc-timing"] button[aria-expanded]')
  if (trig.getAttribute('aria-expanded') !== 'true') fireEvent.click(trig)
}

async function renderPage(rows = [MON, OTHER]) {
  listWith(rows)
  const utils = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" globalAdmin />)
  await waitFor(() => expect(api.monitoring.getScriptedTemplates).toHaveBeenCalled())
  if (rows.length) await screen.findByText(rows[0].name)
  return utils
}
async function openEdit(name = MON.name) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${name} — Edit$`) }))
  return screen.findByRole('dialog')
}
async function openNew() {
  fireEvent.click(screen.getByRole('button', { name: /new monitor/i }))
  return screen.findByRole('dialog')
}

beforeEach(() => resetScriptedMocks(api))

describe('ScriptedMonitorPage — form düzeni (C)', () => {
  it('bölümler Genel → Senaryo → Zamanlama ve alarm → Bildirimler; zamanlama KAPALI ve özetli', async () => {
    await renderPage()
    await openNew()
    const order = [...dialog().querySelectorAll('[data-slot="sc-sec-general"], [data-slot="sc-sec-scenario"], [data-slot="sc-timing"], [data-slot="sc-sec-notify"]')]
      .map(el => el.getAttribute('data-slot'))
    expect(order).toEqual(['sc-sec-general', 'sc-sec-scenario', 'sc-timing', 'sc-sec-notify'])
    // Şablon seçici zamanlama bölümünden ÖNCE (asıl iş üstte)
    const general = inDialog('[data-slot="sc-sec-general"]')
    expect(general.compareDocumentPosition(sourceTrigger()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // Kapalı: süre alanı DOM'da yok, özet görünür
    expect(within(dialog()).queryByLabelText(/process timeout/i)).toBeNull()
    expect(inDialog('[data-slot="sc-timing-summary"]').textContent).toMatch(/Every 5 min.*timeout 60 s.*3 failures/i)
    openTiming()
    expect(within(dialog()).getByLabelText(/process timeout/i)).toBeInTheDocument()
  })

  it('kod düzenleyicinin erişilebilir adı var (E1)', async () => {
    await renderPage()
    await openNew()
    expect(screen.getByTestId('code-editor')).toHaveAttribute('aria-label', 'k6 script')
  })

  it('D3: Kaydet ad boşken PASİF değil — basınca hata alanın altında; sayısal hata bölümü açıp alanın altına yazar', async () => {
    await renderPage()
    await openNew()
    const save = within(dialog()).getByRole('button', { name: /^save$/i })
    expect(save).not.toBeDisabled()
    fireEvent.click(save)
    expect(await within(dialog()).findByText(/name is required/i)).toBeInTheDocument()
    expect(api.monitoring.createScriptedMonitor).not.toHaveBeenCalled()

    // Geçerli zorunlu alanlar + bozuk zaman aşımı → bölüm açılır, hata alanın altında, kayıt yok
    fireEvent.change(within(dialog()).getByRole('textbox', { name: /^Name\b/ }), { target: { value: 'yeni' } })
    await fillGroupAndTags({ root: dialog() })
    openTiming()
    fireEvent.change(within(dialog()).getByLabelText(/process timeout/i), { target: { value: '999' } })
    // Bölümü KAPAT: kaydet hatalı alanı görmek için yeniden açmalı
    fireEvent.click(inDialog('[data-slot="sc-timing"] button[aria-expanded]'))
    expect(within(dialog()).queryByLabelText(/process timeout/i)).toBeNull()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^save$/i }))
    const field = await within(dialog()).findByLabelText(/process timeout/i)
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog()).getByText(/"Process timeout \(s\)" cannot be empty; enter a number between 5 and 180/)).toBeInTheDocument()
    expect(inDialog('[data-slot="sc-timing-invalid"]')).not.toBeNull()
    expect(api.monitoring.createScriptedMonitor).not.toHaveBeenCalled()
  })

  it('zamanlama: Cron kipi gövdeye gider; boş cron alanın altında hata, önizleme sentetik izleme ucundan', async () => {
    api.monitoring.scriptedSchedulePreview.mockResolvedValue({ success: true, data: { valid: true, next: ['2026-11-02T06:00:00Z'], effective_interval_seconds: 86400 } })
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: true, data: { id: MON.id } })
    await renderPage()
    await openEdit()
    openTiming()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^cron expression$/i }))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^save$/i }))
    expect(await within(dialog()).findByText(/enter a cron expression or pick one/i)).toBeInTheDocument()
    expect(api.monitoring.updateScriptedMonitor).not.toHaveBeenCalled()

    fireEvent.change(within(dialog()).getByLabelText(/cron expression/i), { target: { value: '0 9 * * 1-5' } })
    await waitFor(() => expect(api.monitoring.scriptedSchedulePreview).toHaveBeenCalled(), { timeout: 2000 })
    expect(api.monitoring.dbQuerySchedulePreview).not.toHaveBeenCalled()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^save$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
    const body = api.monitoring.updateScriptedMonitor.mock.calls[0][1]
    expect(body).toMatchObject({ schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5', windows: [], blackouts: [], skip_holidays: false, skip_dates: [], intervalSeconds: 300 })
  })

  it('sunucunun zamanlama alan hatası kapalı bölümü açar ve alanın altına yazar', async () => {
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'cron_expression',
      error: 'Cron ifadesi çok sık çalışıyor (en az 60 sn)' })
    await renderPage([{ ...MON, schedule_mode: 'CRON', cron_expression: '* * * * *' }, OTHER])
    await openEdit()
    expect(within(dialog()).queryByLabelText(/cron expression/i)).toBeNull()   // kapalı
    fireEvent.click(within(dialog()).getByRole('button', { name: /^save$/i }))
    await waitFor(() => expect(api.monitoring.updateScriptedMonitor).toHaveBeenCalled())
    expect(await within(dialog()).findByText(/çok sık çalışıyor/)).toBeInTheDocument()
    expect(within(dialog()).getByLabelText(/cron expression/i)).toHaveValue('* * * * *')
  })
})

describe('ScriptedMonitorPage — kaynak değişimi (H1 / D1)', () => {
  it('düzenlemede şablona geçiş ONAY ister; Vazgeç hiçbir şeyi değiştirmez', async () => {
    await renderPage()
    await openEdit()
    pickSource(/JSON health/i)
    const confirm = await screen.findByRole('button', { name: /^change source$/i })
    const box = confirm.closest('[role="dialog"]')
    expect(box.textContent).toMatch(/environment variables are kept/i)
    fireEvent.click(within(box).getByRole('button', { name: /^cancel$/i }))
    await waitFor(() => expect(screen.queryByRole('button', { name: /^change source$/i })).toBeNull())
    expect(screen.getByTestId('code-editor').value).toBe(MON.script)
    expect(envNames()).toEqual(['BASE_URL', 'PASSWORD', 'MANUAL_ONE'])
  })

  it('onaylanınca env BİRLEŞİR: aynı ad korunur (değer + kayıtlı gizli), eksikler eklenir, fazlalar SİLİNMEZ', async () => {
    await renderPage()
    await openEdit()
    pickSource(/OAuth2/i)
    const confirm = await screen.findByRole('button', { name: /^change source$/i })
    // OAuth2 şablonu: TOKEN_URL, CLIENT_ID, CLIENT_SECRET (gizli), API_URL → 4 yeni, 1'i gizli
    expect(confirm.closest('[role="dialog"]').textContent).toMatch(/4 new variable\(s\) will be added: TOKEN_URL, CLIENT_ID, CLIENT_SECRET, API_URL/)
    expect(confirm.closest('[role="dialog"]').textContent).toMatch(/1 of them are secret/)
    await act(async () => { fireEvent.click(confirm) })
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('client_credentials'))
    expect(envNames()).toEqual(['BASE_URL', 'PASSWORD', 'MANUAL_ONE', 'TOKEN_URL', 'CLIENT_ID', 'CLIENT_SECRET', 'API_URL'])
    const values = within(dialog()).getAllByLabelText(/^value$/)
    expect(values[0]).toHaveValue('https://ibank.example.com')   // BASE_URL değeri korundu
    expect(values[1].getAttribute('placeholder')).toMatch(/configured/i)   // PASSWORD kayıtlı gizli → "ayarlı"
  })

  it('yeni izlemede dokunulmamış şablondan başka şablona geçiş onaysız; ad şablondan önerilir', async () => {
    await renderPage()
    await openNew()
    pickSource(/smoke/i)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('www.example.com'))
    expect(within(dialog()).getByRole('textbox', { name: /^Name\b/ })).toHaveValue('System health check (smoke)')
    pickSource(/JSON health/i)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('actuator/health'))
    expect(screen.queryByRole('button', { name: /^change source$/i })).toBeNull()
    // Önceki öneri duruyordu → yeni şablonun adıyla değişti
    expect(within(dialog()).getByRole('textbox', { name: /^Name\b/ })).toHaveValue('JSON health endpoint (Actuator / microservice)')
    // Kullanıcının yazdığı ad bir daha ezilmez
    fireEvent.change(within(dialog()).getByRole('textbox', { name: /^Name\b/ }), { target: { value: 'Benim adım' } })
    pickSource(/OAuth2/i)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('client_credentials'))
    expect(within(dialog()).getByRole('textbox', { name: /^Name\b/ })).toHaveValue('Benim adım')
  })

  it('D1: kendi izlemesinde "gizli değerler taşınmaz" notu YOK; başka izlemeden yüklenince var ve gizli "ayarlı" değil', async () => {
    await renderPage()
    await openEdit()
    expect(within(dialog()).queryByText(/secret values are not carried over/i)).toBeNull()
    pickSource(/Other monitor/)
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: /^change source$/i })) })
    expect(within(dialog()).getByText(/loaded from monitor "Other monitor"/i)).toBeInTheDocument()
    expect(envNames()).toEqual(['BASE_URL', 'PASSWORD', 'MANUAL_ONE', 'TOKEN', 'API_URL'])
    const values = within(dialog()).getAllByLabelText(/^value$/)
    expect(values[3].getAttribute('placeholder')).not.toMatch(/configured/i)   // başka izlemenin gizlisi taşınmaz
    expect(values[4]).toHaveValue('https://api.example.com')
  })
})

describe('ScriptedMonitorPage — Test Çalıştır (B1 + test paneli)', () => {
  it('düzenlemede boş + kayıtlı gizli satır keep:true ve monitorId ile gider; rozet "kayıtlı değerle"', async () => {
    api.monitoring.testScripted.mockResolvedValue({ success: true, data: { status: 'PASS', checks_passed: 1, checks_failed: 0, duration_ms: 500, kept_secrets: ['PASSWORD'] } })
    await renderPage()
    await openEdit()
    expect(inDialog('[data-slot="env-saved-value"]')).not.toBeNull()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    await waitFor(() => expect(api.monitoring.testScripted).toHaveBeenCalled())
    const body = api.monitoring.testScripted.mock.calls[0][0]
    expect(body.monitorId).toBe(7)
    expect(body.env).toEqual([
      { name: 'BASE_URL', value: 'https://ibank.example.com', secret: false },
      { name: 'PASSWORD', value: '', secret: true, keep: true },
      { name: 'MANUAL_ONE', value: 'el', secret: false },
    ])
    expect(await within(dialog()).findByText(/tested with saved secret values: PASSWORD/i)).toBeInTheDocument()
    expect(inDialog('[data-slot="kept-ignored"]')).toBeNull()
  })

  it('yanıtta kept_secrets yoksa (eski pod) "gönderilemedi" notu', async () => {
    api.monitoring.testScripted.mockResolvedValue({ success: true, data: { status: 'FAIL', checks_passed: 0, checks_failed: 1, duration_ms: 500 } })
    await renderPage()
    await openEdit()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    expect(await within(dialog()).findByText(/saved secret values were not sent to the test/i)).toBeInTheDocument()
  })

  it('yeni izlemede keep / monitorId gönderilmez; değeri boş değişken için uyarı (engellemez)', async () => {
    await renderPage()
    await openNew()
    pickSource(/JSON health/i)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('actuator/health'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    await waitFor(() => expect(api.monitoring.testScripted).toHaveBeenCalled())
    const body = api.monitoring.testScripted.mock.calls[0][0]
    expect(body.monitorId).toBeUndefined()
    expect(body.env).toEqual([{ name: 'BASE_URL', value: '', secret: false }])
    expect(await within(dialog()).findByText(/BASE_URL — the script reads these variables as empty/i)).toBeInTheDocument()
    // Değer girilince uyarıdan düşer
    fireEvent.change(within(dialog()).getByLabelText(/^value$/), { target: { value: 'https://ibank.example.com/x' } })
    expect(inDialog('[data-slot="env-empty-warning"]')).toBeNull()
    expect(within(dialog()).getByText(/Target: ibank\.example\.com/)).toBeInTheDocument()
  })

  it('sonuç paneli: odak alır, adım listesine prop gider, durduğu adım, hata sınıfı, bağlam çipleri, satıra git', async () => {
    api.monitoring.testScripted.mockResolvedValue({ success: true, data: {
      status: 'ERROR', duration_ms: 900, exit_code: 107, kept_secrets: [],
      error: 'script: Line 4:1 Unexpected token\nsüreç bütçesi=60s · script istek timeout\'u=20s · çıkış=doğrudan · kurumsal CA=verildi',
      failure_reason: 'DNS_TIMEOUT',
      steps: [{ name: 'Giriş', duration_ms: 300, requests: 1, failed_requests: 0, checks_passed: 1, checks_failed: 0 },
              { name: 'Giriş › OTP', duration_ms: 600, requests: 1, failed_requests: 1, checks_passed: 0, checks_failed: 1, error_code: 1101 }],
      steps_truncated: false, checks_json: '[{"name":"a","passed":true}]',
      run_context: { process_budget_s: 60, request_timeout_s: 20, egress: 'direct', corporate_ca: true, inverted_budget: false },
    } })
    await renderPage()
    await openEdit()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    const panel = await waitFor(() => {
      const el = inDialog('[data-slot="test-run"]')
      expect(el.textContent).toMatch(/Unexpected token/)
      return el
    })
    await waitFor(() => expect(document.activeElement).toBe(panel))
    expect(inDialog('[data-slot="test-live"]').textContent).toMatch(/Test finished: Error/)
    const props = JSON.parse(screen.getByTestId('step-list').getAttribute('data-props'))
    expect(props).toEqual({ steps: expect.any(Array), truncated: false, checksJson: '[{"name":"a","passed":true}]', status: 'ERROR' })
    expect(props.steps).toHaveLength(2)
    // Adımlar varken "durduğu adım" notunu StepList yazar — panel ikinci kez yazmaz
    expect(panel.querySelector('[data-slot="stop-point"]')).toBeNull()
    expect(within(panel).getByText(/Failure class: DNS timeout/)).toBeInTheDocument()
    expect(panel.querySelector('[data-ctx="budget"]').textContent).toBe('Process budget 60 s')
    expect(panel.querySelector('[data-ctx="egressDirect"]')).not.toBeNull()
    // Yapılandırılmış bağlam varken Türkçe bağlam satırı metinden düşer (EN arayüzde Türkçe kalmasın)
    expect(panel.querySelector('[data-slot="error-text"]').textContent).not.toMatch(/süreç bütçesi/)
    fireEvent.click(within(panel).getByRole('button', { name: /^go to line 4$/i }))
    expect(screen.getByTestId('code-editor').getAttribute('data-goto')).toBe('4:1')
  })

  it('adımsız sonuçta (eski sunucu) durduğu adım checks_json listesinden yazılır', async () => {
    api.monitoring.testScripted.mockResolvedValue({ success: true, data: {
      status: 'ERROR', duration_ms: 900, exit_code: 107, error: 'k6 hata',
      checks_json: '[{"name":"giriş","passed":true},{"name":"hesap özeti","passed":false}]',
    } })
    await renderPage()
    await openEdit()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    const panel = await waitFor(() => { const el = inDialog('[data-slot="test-run"]'); expect(el.textContent).toMatch(/k6 hata/); return el })
    const stop = panel.querySelector('[data-slot="stop-point"]')
    expect(stop.textContent).toMatch(/stopped after this step: giriş/)
    expect(stop.textContent).toMatch(/step it stopped at: hesap özeti/i)
  })

  it('ret (400 + dosya okuma bulguları) "Test başlatılamadı" bandıyla; ağ hatası da yakalanır', async () => {
    api.monitoring.testScripted.mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'script',
      error: 'Betik pod dosyalarını okuyor; politika BLOCK', file_reads: ['open', 'handleSummary'] })
    await renderPage()
    await openEdit()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    const panel = await waitFor(() => { const el = inDialog('[data-slot="test-run"]'); expect(el).not.toBeNull(); return el })
    expect(panel.getAttribute('data-refused')).toBe('true')
    expect(within(panel).getAllByText(/test could not start/i).length).toBeGreaterThan(0)
    expect(within(panel).getByText(/File read findings: open, handleSummary/)).toBeInTheDocument()

    api.monitoring.testScripted.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı'))
    fireEvent.click(within(dialog()).getByRole('button', { name: /^test run$/i }))
    expect(await within(dialog()).findByText('Sunucuya ulaşılamadı')).toBeInTheDocument()
    expect(within(dialog()).getByRole('button', { name: /^test run$/i })).not.toBeDisabled()
  })

  it('kayıt sözdizimi hatası bandında da "satıra git"', async () => {
    api.monitoring.updateScriptedMonitor.mockResolvedValue({ success: false, field: 'script', error: 'script:12:3 SyntaxError: Unexpected token' })
    await renderPage()
    await openEdit()
    fireEvent.click(within(dialog()).getByRole('button', { name: /^save$/i }))
    const btn = await within(dialog()).findByRole('button', { name: /^go to line 12$/i })
    fireEvent.click(btn)
    expect(screen.getByTestId('code-editor').getAttribute('data-goto')).toBe('12:1')
  })
})

describe('ScriptedMonitorPage — canlı __ENV denetimi ve sihirbaz', () => {
  it('tanımsız / kullanılmıyor / gizli işaretli değil çipleri; "Değişken ekle" ve "Gizli yap"', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      await renderPage()
      await openNew()
      fireEvent.change(screen.getByTestId('code-editor'), {
        target: { value: "// __ENV.COMMENTED\nconst u = `${__ENV.API_URL}/x`; const p = __ENV['API_PASSWORD']; http.get('__ENV.IN_STRING')" },
      })
      fireEvent.click(within(dialog()).getByRole('button', { name: /^add variable$/i }))
      fireEvent.change(within(dialog()).getAllByRole('textbox', { name: /^NAME$/ })[0], { target: { value: 'OLD_ONE' } })
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      const lint = inDialog('[data-slot="env-lint"]')
      expect(lint).not.toBeNull()
      const undef = [...lint.querySelectorAll('[data-lint="undefined"]')].map(li => li.textContent)
      expect(undef.join('|')).toMatch(/API_URL/)
      expect(undef.join('|')).toMatch(/API_PASSWORD/)
      expect(undef.join('|')).not.toMatch(/COMMENTED|IN_STRING/)
      expect(lint.querySelector('[data-lint="unused"]').textContent).toMatch(/OLD_ONE/)

      fireEvent.click(within(lint).getByRole('button', { name: /add variable: API_PASSWORD/i }))
      expect(envNames()).toContain('API_PASSWORD')
      // parola benzeri ad gizli doğar
      const rows = [...dialog().querySelectorAll('[data-slot="env-row"]')]
      const pwRow = rows.find(r => r.querySelector('input').value === 'API_PASSWORD')
      expect(within(pwRow).getByRole('checkbox')).toBeChecked()

      // gizli işaretli olmayan parola benzeri ad → "Gizli yap"
      fireEvent.change(within(rows[0]).getByRole('textbox', { name: /^NAME$/ }), { target: { value: 'DB_TOKEN' } })
      await act(async () => { await vi.advanceTimersByTimeAsync(500) })
      fireEvent.click(within(inDialog('[data-slot="env-lint"]')).getByRole('button', { name: /make secret: DB_TOKEN/i }))
      expect(within([...dialog().querySelectorAll('[data-slot="env-row"]')][0]).getByRole('checkbox')).toBeChecked()
    } finally { vi.useRealTimers() }
  })

  it('şablon değişken açıklaması satırın altında; seçenekler açıklamayı ipucu olarak taşır', async () => {
    api.monitoring.getScriptedTemplates.mockResolvedValue({ success: true, data: { templates: [
      ...TEMPLATE_ROWS.slice(0, 2),
      { ...TEMPLATE_ROWS[2], id: 201, select_token: '201', builtin: false, scope: 'team', team_id: 5, team_name: 'SY-A',
        env: [{ name: 'TOKEN_URL', secret: false, desc: 'Keycloak token adresi', desc_en: 'Keycloak token endpoint' },
              { name: 'CLIENT_SECRET', secret: true, desc: 'İstemci sırrı' }] },
    ], can_create_general: false, can_view_trash: false, writable_team_ids: [5], k6_version: 'v2.3.0' } })
    api.monitoring.getScriptedTemplate.mockResolvedValue({ success: true, data: { script: "export default function(){ http.post(__ENV.TOKEN_URL) }" } })
    await renderPage()
    await openNew()
    fireEvent.mouseDown(sourceTrigger())
    const opt = [...document.querySelectorAll('[role="listbox"] [role="option"]')].find(o => /OAuth2/.test(o.textContent))
    expect(opt.textContent).toMatch(/Gets a token, calls the protected API/)   // ipucu (hint) seçenekte
    fireEvent.mouseDown(opt)
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toContain('TOKEN_URL'))
    const descs = [...dialog().querySelectorAll('[data-slot="env-desc"]')].map(e => e.textContent)
    expect(descs).toEqual(['Keycloak token endpoint', 'İstemci sırrı'])
  })
})

describe('ScriptedMonitorPage — sürümü editöre yükle (D2)', () => {
  it('betik + env ADLARI: aynı ad korunur, eksik ad eklenir (gizli değeri boş), gizli olmayanın değeri gelir', async () => {
    api.monitoring.getScriptedVersions.mockResolvedValue({ success: true, data: { current_version: '1.0.0', versions: [
      { id: 3, version: '0.9.0', event_type: 'UPDATE', created_at: '2026-10-01T10:00:00', created_by: 'ali' },
      { id: 4, version: '1.0.0', event_type: 'UPDATE', created_at: '2026-10-02T10:00:00', created_by: 'ali' },
    ] } })
    api.monitoring.getScriptedVersion.mockResolvedValue({ success: true, data: {
      id: 3, version: '0.9.0', script: 'export default function(){ /* eski */ }',
      env: [{ name: 'BASE_URL', secret: false, value: 'https://old.example.com' }, { name: 'OLD_KEY', secret: true, value_set: true },
            { name: 'LEGACY_FLAG', secret: false, value: 'on' }],
    } })
    await renderPage()
    fireEvent.click(screen.getByRole('button', { name: /^OIDC Login — open details$/i }))
    fireEvent.mouseDown(await screen.findByRole('tab', { name: /^versions$/i }), { button: 0 })
    fireEvent.click((await screen.findByText('v0.9.0')).closest('[data-slot="version-pick"]'))
    fireEvent.click(await screen.findByRole('button', { name: /load this version/i }))
    await waitFor(() => expect(screen.getByTestId('code-editor').value).toBe('export default function(){ /* eski */ }'))
    expect(envNames()).toEqual(['BASE_URL', 'PASSWORD', 'MANUAL_ONE', 'OLD_KEY', 'LEGACY_FLAG'])
    const values = within(dialog()).getAllByLabelText(/^value$/)
    expect(values[0]).toHaveValue('https://ibank.example.com')   // formdaki değer korunur
    expect(values[3].getAttribute('placeholder')).not.toMatch(/configured/i)   // eklenen gizli: değer yok
    expect(values[4]).toHaveValue('on')
  })
})

describe('ScriptedMonitorPage — boş durum', () => {
  it('"Şablondan başla" kütüphaneye götürür, "Kılavuz" kılavuzu açar', async () => {
    await renderPage([])
    const empty = await waitFor(() => { const el = document.querySelector('[data-slot="sc-empty"]'); expect(el).not.toBeNull(); return el })
    fireEvent.click(within(empty).getByRole('button', { name: /^guide$/i }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(within(document.querySelector('[data-slot="sc-empty"]')).getByRole('button', { name: /start from a template/i }))
    expect(await screen.findByTestId('templates-tab')).toBeInTheDocument()
  })

  it('yazma izni yoksa yalnız Kılavuz', async () => {
    api.monitoring.getScriptedMonitors.mockResolvedValue({ success: true, data: { k6_available: true, can_manage: false, monitors: [] } })
    render(<ScriptedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    const empty = await waitFor(() => { const el = document.querySelector('[data-slot="sc-empty"]'); expect(el).not.toBeNull(); return el })
    expect(within(empty).queryByRole('button', { name: /start from a template/i })).toBeNull()
    expect(within(empty).getByRole('button', { name: /^guide$/i })).toBeInTheDocument()
  })
})
