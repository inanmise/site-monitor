import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import SystemHealth from '../components/admin/SystemHealth.jsx'

/**
 * Sistem Sağlığı — GERÇEK render testi.
 *
 * Neden var: 2026-08-19'da üretimde bu ekran `ReferenceError: ProgressBar is not defined`
 * ile ErrorBoundary'ye düştü. Bileşen `<ProgressBar>` kullanıyordu ama import listesinde
 * yoktu (2026-08-09'daki ilerleme-göstergesi süpürmesinde atlanmış).
 *
 * Neden mevcut test yakalamadı — iki katmanlı gizlenme:
 *   1) Üç kullanım da VARSAYILAN KAPALI akordiyonun içinde (`openSection` başlangıçta null),
 *      yani sekmenin yüklenmesi yetmiyor; bölümün AÇILMASI gerekiyor.
 *   2) SystemHealthLoadError.test.jsx'in başarı fixture'ı `{ scheduler: {}, network: {} }` —
 *      `pool`/`memory`/`executor_pool` yok, üç iç kapı da (`pool ?`, `memory ?`,
 *      `executor_pool &&`) false dalına gidiyor. Kırık JSX hiç değerlendirilmiyor.
 *
 * Bu yüzden buradaki fixture BACKEND'İN GERÇEK ŞEKLİNİ taşır (SchedulerService health
 * payload'ı) ve test BEŞ akordiyon bölümünün hepsini tek tek açar. Fixture'ı budarken
 * dikkat: bir alanı silmek ilgili kapıyı kapatır ve testi sessizce değersizleştirir.
 */

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    admin: {
      getSystemHealth:      vi.fn(),
      getMetrics:           vi.fn(),
      getHttpMetrics:       vi.fn(),
      getDbStats:           vi.fn(),
      getUserActivity:      vi.fn().mockResolvedValue({ success: true, data: {} }),
      getUserActivitySeries: vi.fn().mockResolvedValue({ success: true, data: [] }),
      terminateUserSession: vi.fn().mockResolvedValue({ success: true }),
      releaseSchedulerLock: vi.fn().mockResolvedValue({ success: true }),
      triggerSchedulerRun:  vi.fn().mockResolvedValue({ success: true }),
      getCertPoolHealth:    vi.fn().mockResolvedValue({ success: true, data: {} }),
      getSchedulerHistory:  vi.fn().mockResolvedValue({ success: true, data: [] }),
      getMailLogs:          vi.fn().mockResolvedValue({ success: true, data: [] }),
      getHeartbeat:         vi.fn().mockResolvedValue({ success: true, data: [] }),
      getDbAnalytics:       vi.fn().mockResolvedValue({ success: true, data: {} }),
    },
  },
}))

import { api } from '../api/client'

/** SchedulerService'in ürettiği sağlık payload'ının birebir şekli. */
const HEALTH = {
  scheduler: { running: false, cron: '0 0 * * * *', next_run: '2026-08-19T04:00:00' },
  lock: { locked: false, locked_by: null, locked_until: null },
  // ↓ bu üç alan ProgressBar dallarını AÇAR — budarsanız test değersizleşir
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: {
    queue_size: 12, queue_capacity: 1000, active_count: 4, pool_size: 20,
    core_pool_size: 20, max_pool_size: 50, completed_tasks: 84213,
    jvm_start_time: '2026-08-19T03:00:00Z',
  },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-08-19T03:00:00', duration_ms: 42150, total: 214, warnings: 3, errors: 1, last_failure: null },
  scan_alarm: false,
  smtp: { sent_24h: 12, failed_24h: 0, last_error: null },
  heartbeat: { last_seen: '2026-08-19T03:57:00', pod: 'site-monitor-0' },
  network: { status: 'OK' },
  domain_expiry: { source: 'RDAP', last_ok: '2026-08-19T03:00:00' },
  weekly_availability: { enabled: true, last_run: '2026-08-18T10:00:00' },
}

/**
 * Akordiyon bölümleri — DOM SIRASIYLA (SystemHealth.jsx:443/953/1023/1080/1293).
 * Sıra önemli: testler konuma göre tıklıyor, kaynak sırası bozulursa etiketler kayar.
 * İki bölüm koşulludur: "http" `httpMetrics` dolu olmasını, "users" ise `globalAdmin`
 * (ya da AUDIT rolü) ister — bu yüzden aşağıda `globalAdmin` geçiliyor.
 */
const SECTIONS = ['sys', 'http', 'cpu', 'db', 'users']

function collapseBars() {
  return [...document.querySelectorAll('.stats-collapse-bar')]
}

function renderHealth() {
  return render(<SystemHealth systemRole="ADMIN" globalAdmin />)
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getSystemHealth.mockResolvedValue({ success: true, data: HEALTH })
  api.admin.getMetrics.mockResolvedValue({ success: true, data: [] })
  api.admin.getHttpMetrics.mockResolvedValue({ success: true, data: { summary: {}, history: [] } })
  api.admin.getDbStats.mockResolvedValue({ success: true, data: [] })
})

describe('SystemHealth — akordiyon bölümleri hatasız render eder', () => {
  it('yüklenince beş katlanabilir bölüm sunar', async () => {
    renderHealth()
    await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())
    expect(collapseBars().length).toBe(SECTIONS.length)
  })

  it.each(SECTIONS.map((s, i) => [s, i]))(
    '"%s" bölümü açıldığında çökmez',
    async (_name, index) => {
      const user = userEvent.setup()
      renderHealth()
      await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())

      // Bölüm açılırken bileşen throw ederse render ağacı patlar ve test kırmızıya döner
      // (test-utils.jsx'te ErrorBoundary YOK — bu bilinçli, hatayı yutmasın diye).
      await user.click(collapseBars()[index])

      expect(collapseBars().length).toBe(SECTIONS.length)
    }
  )
})

describe('SystemHealth — ilerleme çubukları', () => {
  it('Sistem bölümünde pool, bellek ve kuyruk çubuklarını çizer', async () => {
    // Bu iddia kasıtlıdır: eksik ProgressBar import'u tam olarak burada patlar.
    const user = userEvent.setup()
    renderHealth()
    await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())

    await user.click(collapseBars()[0])   // "Sistem"

    const bars = document.querySelectorAll('progress')
    expect(bars.length, 'pool + bellek + executor kuyruğu = 3 ilerleme çubuğu').toBeGreaterThanOrEqual(3)
  })

  it('pool/bellek/executor verisi yoksa çubuk çizmez ama yine de çökmez', async () => {
    // Karşı kontrol: eski fixture'ın (yalnız scheduler+network) neden hiçbir şeyi
    // korumadığını kanıtlar — kapılar false dalına gider, ProgressBar hiç değerlendirilmez.
    api.admin.getSystemHealth.mockResolvedValue({
      success: true,
      data: { scheduler: HEALTH.scheduler, network: HEALTH.network },
    })
    const user = userEvent.setup()
    renderHealth()
    await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())

    await user.click(collapseBars()[0])

    expect(document.querySelectorAll('progress').length).toBe(0)
  })
})
