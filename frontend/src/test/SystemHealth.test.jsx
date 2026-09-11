import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import SystemHealth from '../components/admin/SystemHealth.jsx'

// Sürüm & Dağıtım bölümü release_history.read ister — mock: her şey görünür (bölüm sayısı 6).
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))

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

/**
 * api.admin vekili: testin AÇIKÇA kontrol ettiği dört uç elle tanımlı, geri kalan HER
 * metot ilk erişimde otomatik üretilir ve başarılı boş yanıt döndürür.
 *
 * Neden vekil: elle sayılan bir mock listesi bileşen yeni bir uç çağırdığı gün sessizce
 * eksik kalır. Nitekim bu testin ilk hâlinde `getLoginSeries` yoktu; "Kullanıcı/Oturum"
 * bölümü açılınca efekt içinde `is not a function` ile REDDEDİLEN bir promise üretti.
 * Yerelde zamanlama nedeniyle yüzeye çıkmadı, CI'da "1 unhandled rejection" olarak koşumu
 * kırmızıya çevirdi — testlerin hepsi geçtiği hâlde. Vekil bu sürüklenmeyi kökten kapatır.
 *
 * `vi.clearAllMocks()` yalnız çağrı kayıtlarını temizler, implementasyonu korur.
 */
// vi.hoisted şart: vi.mock çağrıları dosyanın en üstüne taşınır ve fabrika, bu dosyadaki
// const'lar başlatılmadan önce çalışır (TDZ hatası).
const { adminProxy } = vi.hoisted(() => {
  const target = {
    getSystemHealth: vi.fn(),
    getMetrics:      vi.fn(),
    getHttpMetrics:  vi.fn(),
    getDbStats:      vi.fn(),
  }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy },
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
const SECTIONS = ['sys', 'http', 'cpu', 'db', 'users', 'releases']   // releases EN SONDA (2026-09-11)

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
  it('yüklenince altı katlanabilir bölüm sunar', async () => {
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

describe('SystemHealth — aynı sekmede param olayı (2026-09-11)', () => {
  it("'sm:tab-params' {sec:'releases'} son bölümü açar", async () => {
    renderHealth()
    await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())
    expect(document.querySelector('.deploy-panel')).toBeNull()
    await act(async () => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { sec: 'releases' } })) })
    await waitFor(() => expect(document.querySelector('.stats-collapse-chevron.open')).not.toBeNull())
  })
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

// 2026-09-10: Görev Kuyruğu kartı doygunluğu görünür kılar (kapasite 5000, thread 20/50)
describe('SystemHealth — Görev Kuyruğu doygunluk göstergeleri', () => {
  async function openSys(health) {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: health })
    renderHealth()
    await waitFor(() => expect(api.admin.getSystemHealth).toHaveBeenCalled())
    const user = userEvent.setup()
    await user.click(collapseBars()[0])
  }

  it('sağlıklı havuzda doygunluk uyarısı YOK, caller-runs satırı 0 gösterir, tooltip max thread sayısını yazar', async () => {
    await openSys({ ...HEALTH, executor_pool: { ...HEALTH.executor_pool, queue_capacity: 5000, max_pool_size: 50, caller_runs: 0, saturated: false } })
    await waitFor(() => expect(document.querySelector('.sys-dl')).not.toBeNull())
    expect(document.querySelector('.queue-saturated')).toBeNull()
    const tip = [...document.querySelectorAll('dt[title]')].map(d => d.getAttribute('title')).find(x => /50/.test(x))
    expect(tip).toBeTruthy()
    expect([...document.querySelectorAll('dt')].some(d => /caller-runs/i.test(d.textContent))).toBe(true)
  })

  it('kuyruk %80 üstünde ya da saturated=true iken uyarı bandı ve kırmızı sayaç', async () => {
    await openSys({ ...HEALTH, executor_pool: { ...HEALTH.executor_pool, queue_size: 4500, queue_capacity: 5000, caller_runs: 12, saturated: true } })
    await waitFor(() => expect(document.querySelector('.queue-saturated')).not.toBeNull())
    expect(document.querySelector('.sys-card-alarm')).not.toBeNull()
    expect(document.querySelector('.queue-callerruns-hot')?.textContent).toBe('12')
  })
})
