import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

// dbq2 A1–A4 (2026-11-05): bağlantı dizesi yapıştırma (parola forma TAŞINMAZ, kutu temizlenir), "Bu bağlantıdan yeni"
// (parola boş, yeni kayıt), adım adım teşhis (kod başına çare), son 24 saatin sağlık kartı (eski sunucu → desteklenmiyor),
// kullanılmayan bağlantı işareti, izleme adı ilgili izlemeyi açar.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `T:${s}` : ''),
  api: withApiFallback({ monitoring: {
    dbConnections: vi.fn(), createDbConnection: vi.fn(), updateDbConnection: vi.fn(), deleteDbConnection: vi.fn(),
    testDbConnection: vi.fn(), testDbConnectionDraft: vi.fn(), dbConnectionHealth: vi.fn(),
  } }),
}))
import { api } from '../api/client'
import ConnectionsManager from '../components/dbquery/ConnectionsManager.jsx'

const PG = {
  id: 7, team_id: 3, name: 'Muhasebe Ana Veritabanı — Gün Sonu Mutabakat Okuma Kopyası', db_type: 'POSTGRESQL',
  host: 'pg-reporting-replica-01.dc1.example.com.tr', port: 5432, database_name: 'muhasebe', username: 'sm_reader', password_set: true,
  ssl_mode: 'require', connect_timeout_ms: 10000, description: '', can_edit: true,
  health: { state: 'ok', last_test_ok: true }, used_by: [{ id: 11, name: 'Bekleyen siparişler', active: true }], tables_used: [],
}
const IDLE = { ...PG, id: 8, name: 'Boşta bağlantı', used_by: [], health: { state: 'untested' } }
const HEALTH = {
  monitors: [{ id: 11, name: 'Bekleyen siparişler', active: true, status: 'BREACH' }, { id: 12, name: 'Gece mutabakatı', active: false, status: 'OK' }],
  monitor_count: 2, runs_24h: 288, failed_24h: 3, success_pct: 98.96, avg_duration_ms: 412.4, last_ok_at: '2026-11-05T10:00:00',
  last_error: { at: '2026-11-05T03:00:00', code: 'DB_TIMEOUT', message: 'Bağlanma süresi doldu (10 sn)' }, unused: false,
}
const open = (props = {}) => render(<ConnectionsManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'Takım A' }]} canEdit {...props} />)
const rowOf = async (id) => waitFor(() => { const r = document.querySelector(`[data-slot="dbq-conn-row"][data-id="${id}"]`); if (!r) throw new Error('yok'); return r })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [PG, IDLE] })
  api.monitoring.dbConnectionHealth.mockResolvedValue({ success: true, data: HEALTH })
})

async function newForm() {
  open()
  fireEvent.click((await screen.findAllByRole('button', { name: 'Yeni bağlantı' }))[0])
  return waitFor(() => { const f = document.querySelector('[data-slot="dbq-conn-form"]'); if (!f) throw new Error('yok'); return f })
}
const pasteInto = (input, text) => fireEvent.paste(input, { clipboardData: { getData: () => text } })

describe('A1 — bağlantı dizesi yapıştırma', () => {
  it('JDBC yapıştırınca tür + alanlar dolar, ne doldurulduğu söylenir; parola forma alınmaz, kutu temizlenir', async () => {
    const form = await newForm()
    const box = within(form).getByLabelText('Bağlantı dizesinden doldur')
    pasteInto(box, 'jdbc:sqlserver://sql01.example.com:1433;databaseName=Sales;encrypt=true;user=ro;password=CokGizli1')
    await waitFor(() => expect(form.getAttribute('data-type')).toBe('MSSQL'))
    expect(within(form).getByLabelText(/^Sunucu/)).toHaveValue('sql01.example.com')
    expect(within(form).getByLabelText(/^Port/)).toHaveValue('1433')
    expect(within(form).getByLabelText(/^Kullanıcı adı/)).toHaveValue('ro')
    expect(within(form).getByLabelText(/^Parola/)).toHaveValue('')
    expect(box).toHaveValue('')
    expect(form.querySelector('[data-slot="dbq-conn-paste-password"]')).not.toBeNull()
    expect(form.querySelector('[data-slot="dbq-conn-paste-filled"]').textContent).toMatch(/Veritabanı türü.*Sunucu.*Port.*Veritabanı.*Kullanıcı adı/)
    // parola hiçbir alanda / DOM'da kalmadı
    expect(document.body.innerHTML).not.toContain('CokGizli1')
  })

  it('türsüz dize porttan tür önerir; "Türü … yap" uygulanır, port korunur', async () => {
    const form = await newForm()
    const box = within(form).getByLabelText('Bağlantı dizesinden doldur')
    fireEvent.change(box, { target: { value: 'db.example.com:1433/Sales' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Doldur' }))
    const sug = await waitFor(() => { const s = form.querySelector('[data-slot="dbq-conn-paste-suggest"]'); if (!s) throw new Error('yok'); return s })
    expect(form.getAttribute('data-type')).toBe('POSTGRESQL')
    fireEvent.click(within(sug).getByRole('button', { name: 'Türü SQL Server yap' }))
    expect(form.getAttribute('data-type')).toBe('MSSQL')
    expect(within(form).getByLabelText(/^Port/)).toHaveValue('1433')
    expect(form.querySelector('[data-slot="dbq-conn-paste-suggest"]')).toBeNull()
  })

  it('tanınmayan dize: hata, yazılan metin kalır; parolalı tanınmayan dize kutudan silinir', async () => {
    const form = await newForm()
    const box = within(form).getByLabelText('Bağlantı dizesinden doldur')
    fireEvent.change(box, { target: { value: 'bu bir dize değil' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(form.querySelector('[data-slot="dbq-conn-paste-error"]').textContent).toContain('tanınmadı')
    expect(box).toHaveValue('bu bir dize değil')
    pasteInto(box, 'mysql://u:Gizli9@h/db')
    expect(box).toHaveValue('')
    expect(form.querySelector('[data-slot="dbq-conn-paste-password"]')).not.toBeNull()
    expect(document.body.innerHTML).not.toContain('Gizli9')
  })
})

describe('A3 — adım adım teşhis', () => {
  it('başarısız adım + çare metni, sonrakiler atlandı; eski alanlar aynen', async () => {
    api.monitoring.testDbConnection.mockResolvedValue({ success: true, data: {
      ok: false, duration_ms: 10012, error: 'Sunucuya ulaşılamadı', failure_reason: 'DB_UNREACHABLE',
      steps: [
        { key: 'dns', status: 'ok', ms: 4 }, { key: 'tcp', status: 'fail', ms: 10003, code: 'TCP_TIMEOUT', detail: '10.20.30.40:5432 — zaman aşımı' },
        { key: 'tls', status: 'skip' }, { key: 'login', status: 'skip' }, { key: 'query', status: 'skip' }, { key: 'privileges', status: 'skip' },
      ],
    } })
    open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    fireEvent.click(screen.getByRole('button', { name: 'Bağlantıyı dene' }))
    const steps = await waitFor(() => { const s = document.querySelector('[data-slot="dbq-conn-steps"]'); if (!s) throw new Error('yok'); return s })
    expect(steps.getAttribute('data-state')).toBe('fail')
    const items = steps.querySelectorAll('[data-slot="dbq-conn-step"]')
    expect([...items].map((i) => i.getAttribute('data-status'))).toEqual(['ok', 'fail', 'skip', 'skip', 'skip', 'skip'])
    const tcp = steps.querySelector('[data-key="tcp"]')
    expect(tcp.querySelector('[data-slot="dbq-conn-step-fix"]').textContent).toContain('güvenlik duvarı')
    expect(tcp.querySelector('[data-slot="dbq-conn-step-detail"]').textContent).toContain('zaman aşımı')
    expect(steps.querySelector('[data-key="dns"] [data-slot="dbq-conn-step-fix"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-conn-test"]').textContent).toContain('Sunucuya ulaşılamadı')
  })

  it('başarılı test: TLS kapalıysa uyarı adımı + çare; adım alanı yoksa liste çizilmez (eski sunucu)', async () => {
    api.monitoring.testDbConnection.mockResolvedValueOnce({ success: true, data: {
      ok: true, duration_ms: 42, steps: [{ key: 'dns', status: 'ok', ms: 2 }, { key: 'tcp', status: 'ok', ms: 5 },
        { key: 'tls', status: 'warn', code: 'SSL_DISABLED' }, { key: 'login', status: 'ok', ms: 20 }, { key: 'query', status: 'ok', ms: 3 }],
    } })
    open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    fireEvent.click(screen.getByRole('button', { name: 'Bağlantıyı dene' }))
    const steps = await waitFor(() => { const s = document.querySelector('[data-slot="dbq-conn-steps"]'); if (!s) throw new Error('yok'); return s })
    expect(steps.getAttribute('data-state')).toBe('warn')
    expect(steps.querySelector('[data-key="tls"] [data-slot="dbq-conn-step-fix"]').textContent).toContain('require')
    api.monitoring.testDbConnection.mockResolvedValueOnce({ success: true, data: { ok: true, duration_ms: 42 } })
    fireEvent.click(screen.getByRole('button', { name: 'Bağlantıyı dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-steps"]')).toBeNull())
    expect(document.querySelector('[data-slot="dbq-conn-test"][data-ok="true"]')).not.toBeNull()
  })
})

describe('A4 — sağlık kartı', () => {
  it('son 24 saat kutuları, son sorun, kullanan izlemeler durum rozetiyle; ad izlemeyi açar', async () => {
    const onOpenMonitor = vi.fn()
    open({ onOpenMonitor })
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="dbq-conn-health24"][data-state="ok"]'); if (!c) throw new Error('yok'); return c })
    expect(api.monitoring.dbConnectionHealth).toHaveBeenCalledWith(7)
    expect(card.getAttribute('data-tone')).toBe('warning')
    expect(card.querySelector('[data-slot="dbq-conn-health-runs"]').textContent).toContain('288')
    expect(card.querySelector('[data-slot="dbq-conn-health-failed"]').textContent).toContain('3')
    expect(card.querySelector('[data-slot="dbq-conn-health-pct"]').textContent).toContain('%99')
    expect(card.querySelector('[data-slot="dbq-conn-health-error-last"]').textContent).toContain('DB_TIMEOUT')
    const used = document.querySelector('[data-slot="dbq-conn-usedby"]')
    expect([...used.querySelectorAll('[data-slot="dbq-conn-usedby-state"]')].map((b) => b.getAttribute('data-state'))).toEqual(['warn', 'paused'])
    fireEvent.click(within(used).getByRole('button', { name: 'Gece mutabakatı' }))
    expect(onOpenMonitor).toHaveBeenCalledWith(12)
  })

  it('çalışma yok → nötr + "durum bilinmiyor" notu (asla yeşil değil); kullanılmayan işareti', async () => {
    api.monitoring.dbConnectionHealth.mockResolvedValue({ success: true, data: { monitors: [], monitor_count: 0, runs_24h: 0, failed_24h: 0, success_pct: null, unused: true } })
    open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    const card = await waitFor(() => { const c = document.querySelector('[data-slot="dbq-conn-health24"][data-state="ok"]'); if (!c) throw new Error('yok'); return c })
    expect(card.getAttribute('data-tone')).toBe('neutral')
    expect(card.querySelector('[data-slot="dbq-conn-health-norun"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="dbq-conn-unused"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="dbq-conn-health-pct"]').textContent).toContain('—')
  })

  it('eski sunucu (404) → "desteklemiyor"; okunamadı → hata + tekrar dene (asla "sorun yok" değil)', async () => {
    api.monitoring.dbConnectionHealth.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'Bulunamadı' })
    const { unmount } = open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-health-unsupported"]')).not.toBeNull())
    unmount()
    api.monitoring.dbConnectionHealth.mockResolvedValueOnce({ success: false, status: 503, error: 'Veritabanı yanıt vermedi' })
      .mockResolvedValueOnce({ success: true, data: HEALTH })
    open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    const err = await waitFor(() => { const e = document.querySelector('[data-slot="dbq-conn-health-error"]'); if (!e) throw new Error('yok'); return e })
    expect(err.textContent).toContain('Veritabanı yanıt vermedi')
    expect(document.querySelector('[data-slot="dbq-conn-health24"]').textContent).not.toMatch(/sorun yok/i)
    fireEvent.click(within(err).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-health24"][data-state="ok"]')).not.toBeNull())
  })

  it('listede hiçbir izlemenin kullanmadığı bağlantı "Kullanılmıyor" diye işaretlenir', async () => {
    open()
    const idle = await rowOf(8)
    expect(idle.querySelector('[data-slot="dbq-conn-unused"]')).not.toBeNull()
    expect((await rowOf(7)).querySelector('[data-slot="dbq-conn-unused"]')).toBeNull()
  })
})

describe('A2 — "Bu bağlantıdan yeni"', () => {
  it('satır menüsünden: aynı alanlar, ad (kopya), parola BOŞ; kaydet → yeni kayıt (kimliksiz, parolasız)', async () => {
    api.monitoring.createDbConnection.mockResolvedValue({ success: true, data: { ...PG, id: 31, name: `${PG.name} (kopya)` } })
    open()
    const row = await rowOf(7)
    pressMenuTrigger(within(row).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Bu bağlantıdan yeni' }))
    const form = await waitFor(() => { const f = document.querySelector('[data-slot="dbq-conn-form"]'); if (!f) throw new Error('yok'); return f })
    expect(document.querySelector('[data-slot="dbq-conn-copy-note"]').textContent).toContain('KOPYALANMAZ')
    expect(within(form).getByLabelText(/^Bağlantı adı/)).toHaveValue(`${PG.name} (kopya)`)
    expect(within(form).getByLabelText(/^Sunucu/)).toHaveValue(PG.host)
    const pw = within(form).getByLabelText(/^Parola/)
    expect(pw).toHaveValue('')
    expect(pw.getAttribute('placeholder')).toBe('')
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(api.monitoring.createDbConnection).toHaveBeenCalled())
    const body = api.monitoring.createDbConnection.mock.calls[0][0]
    expect(body).toMatchObject({ team_id: 3, name: `${PG.name} (kopya)`, db_type: 'POSTGRESQL', host: PG.host, port: 5432, database_name: 'muhasebe', username: 'sm_reader', ssl_mode: 'require' })
    expect(body).not.toHaveProperty('id')
    expect(body).not.toHaveProperty('password')
    expect(body).not.toHaveProperty('password_clear')
    expect(api.monitoring.updateDbConnection).not.toHaveBeenCalled()
    await act(async () => {})
  })

  it('ayrıntıdan da açılır; salt okunur kullanıcıda yok', async () => {
    const { unmount } = open()
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    expect(document.querySelector('[data-slot="dbq-conn-copy"]')).not.toBeNull()
    unmount()
    open({ canEdit: false })
    fireEvent.click(await screen.findByRole('button', { name: PG.name }))
    expect(document.querySelector('[data-slot="dbq-conn-copy"]')).toBeNull()
  })
})
