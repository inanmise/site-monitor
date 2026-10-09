import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const nav = vi.hoisted(() => vi.fn())
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a) }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { getDatabaseInfo: vi.fn() } }),
}))
import { api } from '../api/client'
import DatabaseInfo from '../components/admin/DatabaseInfo.jsx'

/**
 * Ayarlar → Veritabanı Bilgileri (2026-10-09 shadcn yeniden tasarımı). Rol/ad ve data-slot ile sorgulanır (legacy sınıf
 * yok). Kapsam: iskelet, eski alanların HEPSİ, yeni alanlar (oturum kullanıcısı, tam sürüm, SSL, saat dilimi, collation,
 * havuz süreleri, sağlık denetimleri, şema yamaları), uzun JDBC URL (sarar + kopyalanır, parola maskeli), ilk yükleme
 * hatası + Tekrar dene, yenileme hatasında son değerler, Yenile, sorgu analitiği bağlantısı, eski sunucu (health yok).
 */
const LONG_URL = 'jdbc:postgresql://veritabani-birincil-sunucu-bolge-2.cok-uzun-bir-alt-alan-adi.example.com:5432,'
  + 'veritabani-yedek-sunucu.example.com:5433/sitemonitor_app?user=app&password=s3cr3t&sslmode=verify-full&ApplicationName=site-monitor'

function info(extra = {}) {
  return {
    database: 'appdb', user: 'app_user', session_user: 'app_login', server_addr: '203.0.113.10', server_port: 5432,
    version: 'PostgreSQL 16.4', version_full: 'PostgreSQL 16.4 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 12.2.0, 64-bit',
    encoding: 'UTF8', collation: 'tr_TR.UTF-8', size: '512 MB', start_time: '2026-10-01 08:00:00',
    uptime: '8 days 01:02:03', server_time: '2026-10-09 09:02:03', max_connections: '100', active_connections: 12,
    timezone: 'Europe/Istanbul', table_count: 87, ssl: true, ssl_version: 'TLSv1.3',
    jdbc_url: LONG_URL, driver_name: 'PostgreSQL JDBC Driver', driver_version: '42.7.4',
    pool: { name: 'SiteMonitorPool', active: 3, idle: 7, total: 10, waiting: 0, max_size: 20, min_idle: 5,
      connection_timeout_ms: 30000, idle_timeout_ms: 600000, max_lifetime_ms: 0 },
    schema_patches: { applied: 4, noop: 512, failed: 0, locked: true, finished_at: '2026-10-09T06:00:00Z' },
    health: {
      status: 'DEGRADED', component: 'database', checked_at: new Date(Date.now() - 2000).toISOString(), duration_ms: 14, cached: true,
      checks: {
        connection: { status: 'UP', acquire_ms: 2 }, query: { status: 'DEGRADED', latency_ms: 1500 }, writable: { status: 'UP' },
        pool: { status: 'UP', active: 3, idle: 7, total: 10, max: 20, waiting: 0 }, schema: { status: 'UP', failed_patches: 0 },
      },
    },
    ...extra,
  }
}

const row = (key) => document.querySelector(`[data-slot="dbinfo-row"][data-key="${key}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: info() })
})

describe('DatabaseInfo — Ayarlar → Veritabanı Bilgileri', () => {
  it('ilk yüklemede iskelet + durum metni; veri gelince iskelet kalkar', async () => {
    let resolve
    api.admin.getDatabaseInfo.mockReturnValue(new Promise((r) => { resolve = r }))
    render(<DatabaseInfo />)
    expect(screen.getByTestId('dbinfo-skeleton')).toBeInTheDocument()
    expect(screen.getByTestId('database-info')).toHaveAttribute('aria-busy', 'true')
    resolve({ success: true, data: info() })
    await waitFor(() => expect(screen.queryByTestId('dbinfo-skeleton')).toBeNull())
    expect(screen.getByTestId('database-info')).not.toHaveAttribute('aria-busy')
  })

  it('eski ekranın tüm alanları + yeni alanlar görünür (kartlar ve özet kutucukları)', async () => {
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    // Dört özet kutucuğu
    const kpis = document.querySelectorAll('[data-slot="dbinfo-kpi"]')
    expect([...kpis].map((k) => k.dataset.kpi)).toEqual(['status', 'size', 'connections', 'pool'])
    expect(document.querySelector('[data-kpi="connections"] [data-slot="dbinfo-kpi-value"]')).toHaveTextContent('12 / 100')
    expect(document.querySelector('[data-kpi="pool"] [data-slot="dbinfo-kpi-value"]')).toHaveTextContent('3 / 20')
    expect(document.querySelector('[data-kpi="status"]')).toHaveAttribute('data-tone', 'warning')
    expect(document.querySelector('[data-kpi="size"]')).toHaveTextContent(/87/)
    // Beş kart
    expect([...document.querySelectorAll('[data-slot="dbinfo-card"]')].map((c) => c.dataset.card))
      .toEqual(['connection', 'health', 'server', 'pool', 'jdbc'])
    expect(screen.getByRole('region', { name: /^(Connection|Bağlantı)$/ })).toBeInTheDocument()
    // Eski alanlar (değer korunur)
    expect(row('database')).toHaveTextContent('appdb')
    expect(row('user')).toHaveTextContent('app_user')
    expect(row('host')).toHaveTextContent('203.0.113.10:5432')
    expect(row('version')).toHaveTextContent('PostgreSQL 16.4')
    expect(row('size')).toHaveTextContent('512 MB')
    expect(row('uptime')).toHaveTextContent('8 days 01:02:03')   // ham metin ikinci satırda
    expect(row('start_time')).toHaveTextContent('2026-10-01 08:00:00')
    expect(row('encoding')).toHaveTextContent('UTF8')
    expect(row('max_connections')).toHaveTextContent('100')
    expect(row('active_connections')).toHaveTextContent('12')
    expect(row('pool_name')).toHaveTextContent('SiteMonitorPool')
    for (const [k, v] of [['pool_active', '3'], ['pool_idle', '7'], ['pool_total', '10'], ['pool_waiting', '0'], ['pool_max', '20'], ['pool_min', '5']]) {
      expect(row(k).querySelector('[data-slot="dbinfo-value"]')).toHaveTextContent(new RegExp(`^${v}$`))
    }
    expect(row('driver')).toHaveTextContent('PostgreSQL JDBC Driver 42.7.4')
    // Yeni alanlar
    expect(row('user')).toHaveTextContent(/app_login/)
    expect(row('version')).toHaveTextContent(/x86_64-pc-linux-gnu/)
    expect(row('ssl')).toHaveTextContent(/TLSv1\.3/)
    expect(row('timezone')).toHaveTextContent('Europe/Istanbul')
    expect(row('collation')).toHaveTextContent('tr_TR.UTF-8')
    expect(row('server_time')).toHaveTextContent('2026-10-09 09:02:03')
    expect(row('pool_conn_timeout')).toHaveTextContent(/30 (sec|sn)/)
    expect(row('pool_idle_timeout')).toHaveTextContent(/10 (min|dk)/)
    expect(row('pool_max_lifetime')).toHaveTextContent(/Unlimited|Sınırsız/)
    // Havuz şeridi metinli
    expect(screen.getByRole('img', { name: /(Pool breakdown|Havuz dağılımı)/ })).toBeInTheDocument()
  })

  it('uzun JDBC URL sarar, parola maskeli, kopyalanabilir; "gizli değerler maskelendi" rozeti', async () => {
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    const r = row('jdbc_url')
    const value = r.querySelector('[data-slot="dbinfo-value"]')
    expect(value.className).toMatch(/break-all/)
    expect(value.textContent).toContain('password=***')
    expect(value.textContent).not.toContain('s3cr3t')
    expect(value.textContent).toContain('sslmode=verify-full')
    expect(document.body.textContent).not.toContain('s3cr3t')
    expect(document.querySelector('[data-slot="dbinfo-masked"]')).toBeInTheDocument()
    expect(within(r).getByRole('button', { name: /(Copy|Kopyala) — JDBC URL/ })).toBeInTheDocument()
  })

  it('istemci de maskeler: sunucu maskelemese bile parola ekrana düşmez', async () => {
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: info({ jdbc_url: 'jdbc:postgresql://u:hunter2@db.example.com/appdb?sslpassword=kp' }) })
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    expect(document.body.textContent).not.toContain('hunter2')
    expect(row('jdbc_url')).toHaveTextContent('jdbc:postgresql://u:***@db.example.com/appdb?sslpassword=***')
  })

  it('kopyala düğmesi değeri panoya alır (tam sürüm metni)', async () => {
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    fireEvent.click(within(row('version')).getByRole('button', { name: /(Copy|Kopyala) — / }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(info().version_full))
  })

  it('sağlık denetimleri: her satır durum + açıklama; dış izleme ucu kopyalanır', async () => {
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    const checks = [...document.querySelectorAll('[data-slot="dbinfo-check"]')]
    expect(checks.map((c) => [c.dataset.check, c.dataset.status])).toEqual([
      ['connection', 'UP'], ['query', 'DEGRADED'], ['writable', 'UP'], ['pool', 'UP'], ['schema', 'UP'],
    ])
    expect(checks[1]).toHaveTextContent(/1,500 ms|1\.500 ms/)
    expect(checks[4]).toHaveTextContent(/4 (applied|uygulandı)/)
    expect(row('endpoint')).toHaveTextContent('/api/public/health/db')
    expect(within(row('endpoint')).getByRole('button', { name: /(Copy|Kopyala) — / })).toBeInTheDocument()
    // Başlık rozeti genel durumu metinle söyler
    expect(document.querySelector('[data-slot="dbinfo-status"]')).toHaveAttribute('data-status', 'DEGRADED')
    expect(document.querySelector('[data-slot="dbinfo-status"]')).toHaveTextContent(/Needs attention|Dikkat gerekiyor/)
  })

  it('bağlantı düşmüşse: DOWN + açıklayıcı neden; ölçülemeyen satırlar "denetlenemedi"', async () => {
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: info({
      health: { status: 'DOWN', checks: { connection: { status: 'DOWN', error: 'TIMEOUT' }, pool: { status: 'UP' }, schema: { status: 'UP', pending: true } } },
    }) })
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    const c = document.querySelector('[data-check="connection"]')
    expect(c).toHaveAttribute('data-status', 'DOWN')
    expect(c).toHaveTextContent(/4 seconds|4 saniye/)
    expect(document.querySelector('[data-check="query"]')).toHaveAttribute('data-status', 'UNKNOWN')
    expect(document.querySelector('[data-check="query"]')).toHaveTextContent(/no connection|Bağlantı kurulamadığı/)
    expect(document.querySelector('[data-kpi="status"]')).toHaveAttribute('data-tone', 'danger')
  })

  it('eski sunucu (health / yeni alanlar yok): sayfa çalışır, durum "Bilinmiyor", boş değerler "—"', async () => {
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: {
      database: 'appdb', user: 'app_user', version: 'PostgreSQL 16.4', size: '120 MB', pool: {},
    } })
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    expect(document.querySelector('[data-kpi="status"]')).toHaveAttribute('data-unknown', 'true')
    expect(document.querySelector('[data-slot="dbinfo-health-none"]')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="dbinfo-pool-none"]')).toBeInTheDocument()
    expect(row('timezone')).toHaveAttribute('data-blank', 'true')
    expect(row('timezone')).toHaveTextContent('—')
    expect(row('host')).toHaveTextContent('—')
    expect(within(row('timezone')).queryByRole('button', { name: /(Copy|Kopyala)/ })).toBeNull()
  })

  it('ilk yükleme hatası: hata bloğu + sunucu iletisi + Tekrar dene ile toparlanır', async () => {
    api.admin.getDatabaseInfo
      .mockResolvedValueOnce({ success: false, error: 'You need global administrator rights to view the database details.' })
      .mockResolvedValueOnce({ success: true, data: info() })
    render(<DatabaseInfo />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/global administrator rights/)
    expect(alert.closest('[data-slot="empty"]')).toHaveAttribute('data-tone', 'danger')
    fireEvent.click(within(alert).getByRole('button', { name: /Try again|Tekrar dene/ }))
    await screen.findByText('appdb')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(api.admin.getDatabaseInfo).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası (istisna): istemcinin açıklayıcı iletisi gösterilir', async () => {
    api.admin.getDatabaseInfo.mockRejectedValue(new Error('The server could not be reached. Check your connection and try again.'))
    render(<DatabaseInfo />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not be reached/)
  })

  it('Yenile: yeniden yükler, döner simge + aria-busy; hata olursa son değerler kalır ve uyarı şeridi çıkar', async () => {
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    let resolve
    api.admin.getDatabaseInfo.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    const btn = screen.getByRole('button', { name: /^(Refresh|Yenile)$/ })
    fireEvent.click(btn)
    const busy = screen.getByRole('button', { name: /^(Loading…|Yükleniyor…)$/ })
    expect(busy).toHaveAttribute('aria-busy', 'true')
    expect(document.querySelector('[data-slot="dbinfo-card"]')).toBeInTheDocument()   // veriler ekranda kalır
    resolve({ success: true, data: info({ database: 'appdb2' }) })
    await screen.findByText('appdb2')
    expect(api.admin.getDatabaseInfo).toHaveBeenCalledTimes(2)
    expect(document.querySelector('[data-slot="dbinfo-updated"]')).toBeInTheDocument()

    api.admin.getDatabaseInfo.mockResolvedValueOnce({ success: false, error: 'The database didn\'t answer in time; try again in a moment.' })
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="alert"][data-tone="danger"]')).toBeInTheDocument())
    expect(document.querySelector('[data-slot="alert"][data-tone="danger"]')).toHaveTextContent(/didn't answer in time/)
    expect(screen.getByText('appdb2')).toBeInTheDocument()
  })

  it('geç gelen eski yanıt yeni yanıtın üstüne yazmaz (sıra koruması)', async () => {
    let first
    api.admin.getDatabaseInfo
      .mockReturnValueOnce(new Promise((r) => { first = r }))
      .mockResolvedValueOnce({ success: true, data: info({ database: 'yeni-db' }) })
    render(<DatabaseInfo />)
    fireEvent.click(screen.getByRole('button', { name: /^(Loading…|Yükleniyor…)$/ }))
    await screen.findByText('yeni-db')
    first({ success: true, data: info({ database: 'eski-db' }) })
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByText('eski-db')).toBeNull()
    expect(screen.getByText('yeni-db')).toBeInTheDocument()
  })

  it('"Sorgu analitiği" Sistem Sağlığı → Veritabanı bölümünü açar', async () => {
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    fireEvent.click(screen.getByRole('button', { name: /Query analytics|Sorgu analitiği/ }))
    expect(nav).toHaveBeenCalledWith('health', { sec: 'db' })
  })

  it('SSL kapalıysa uyarı tonlu "Şifresiz" rozeti; bekleyen iş parçacığı varsa havuz kartında uyarı', async () => {
    api.admin.getDatabaseInfo.mockResolvedValue({ success: true, data: info({ ssl: false,
      pool: { name: 'P', active: 20, idle: 0, total: 20, waiting: 4, max_size: 20, min_idle: 5 } }) })
    render(<DatabaseInfo />)
    await screen.findByText('appdb')
    expect(row('ssl').querySelector('[data-tone="warning"]')).toHaveTextContent(/Not encrypted|Şifresiz/)
    const poolCard = document.querySelector('[data-card="pool"]')
    expect(poolCard.querySelector('[data-tone="warning"]')).toHaveTextContent(/4/)
    expect(document.querySelector('[data-kpi="pool"]')).toHaveAttribute('data-tone', 'danger')
  })
})
