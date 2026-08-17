import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, formatDate, formatDateSec, formatTime, formatDateOnly } from '../api/client.js'

// ── fetch mock helpers ────────────────────────────────────────────────────────

function mockFetch(body, status = 200) {
  global.fetch = vi.fn().mockResolvedValue({
    status,
    json: () => Promise.resolve(body),
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ── api.login ─────────────────────────────────────────────────────────────────

describe('api.login', () => {
  it('POSTs to /api/login with credentials (rememberMe=false by default)', async () => {
    mockFetch({ success: true, username: 'testuser' })
    const result = await api.login('testuser', 'pass')
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'testuser', password: 'pass', remember_me: 'false', force_login: 'false' }),
      })
    )
    expect(result.success).toBe(true)
  })

  it('passes remember_me=true when requested', async () => {
    mockFetch({ success: true, username: 'testuser' })
    await api.login('testuser', 'pass', true)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/login',
      expect.objectContaining({
        body: JSON.stringify({ username: 'testuser', password: 'pass', remember_me: 'true', force_login: 'false' }),
      })
    )
  })

  it('passes force_login=true when confirming an existing active session', async () => {
    mockFetch({ success: true, username: 'testuser' })
    await api.login('testuser', 'pass', false, true)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/login',
      expect.objectContaining({
        body: JSON.stringify({ username: 'testuser', password: 'pass', remember_me: 'false', force_login: 'true' }),
      })
    )
  })
})

// ── api.getMe ─────────────────────────────────────────────────────────────────

describe('api.getMe', () => {
  it('GETs /api/me', async () => {
    mockFetch({ success: true, username: 'alice' })
    const result = await api.getMe()
    expect(global.fetch).toHaveBeenCalledWith('/api/me', expect.any(Object))
    expect(result.username).toBe('alice')
  })
})

// ── request timeout / dayanıklılık (beyaz ekran önleme) ────────────────────────

describe('request timeout resilience', () => {
  it('getMe: bağlantı asılırsa abort olur ve yumuşak {success:false} döner (sonsuz loading yok)', async () => {
    vi.useFakeTimers()
    // Yanıt vermeyen bağlantı: yalnız abort sinyalinde reject eder.
    global.fetch = vi.fn((url, opts) => new Promise((_, reject) => {
      opts.signal.addEventListener('abort', () =>
        reject(Object.assign(new Error('aborted'), { name: 'AbortError' })))
    }))
    const p = api.getMe()
    await vi.advanceTimersByTimeAsync(15000)   // DEFAULT_TIMEOUT_MS
    const res = await p
    expect(res).toEqual(expect.objectContaining({ success: false }))
    expect(global.fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
    vi.useRealTimers()
  })

  it('uzun-süren çağrılar (runScheduler) abort edilmez — timeout sinyali eklenmez', async () => {
    mockFetch({ success: true })
    await api.runScheduler()
    expect(global.fetch.mock.calls[0][1].signal).toBeUndefined()
  })

  it('login: ağ hatasında yumuşak {success:false} döner (yakalanmamış throw yok)', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const res = await api.login('u', 'p')
    expect(res.success).toBe(false)
    expect(typeof res.error).toBe('string')
  })
})

// ── api.logout ────────────────────────────────────────────────────────────────

describe('api.logout', () => {
  it('POSTs to /api/logout', async () => {
    mockFetch({ success: true })
    await api.logout()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/logout',
      expect.objectContaining({ method: 'POST' })
    )
  })
})

// ── api.getCertificates ───────────────────────────────────────────────────────

describe('api.getCertificates', () => {
  it('GETs /api/certificates', async () => {
    mockFetch({ success: true, data: [] })
    const result = await api.getCertificates()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/certificates',
      expect.any(Object)
    )
    expect(result.success).toBe(true)
  })

  it('returns null on 401', async () => {
    mockFetch({}, 401)
    const result = await api.getCertificates()
    expect(result).toBeNull()
  })
})

// ── api.getCertificatesPaginated ──────────────────────────────────────────────

describe('api.getCertificatesPaginated', () => {
  it('builds query string from params', async () => {
    mockFetch({ success: true, data: [] })
    await api.getCertificatesPaginated({ page: 2, per_page: 10 })
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/certificates/list?'),
      expect.any(Object)
    )
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('page=2')
    expect(url).toContain('per_page=10')
  })
})

// ── api.getStats ──────────────────────────────────────────────────────────────

describe('api.getStats', () => {
  it('GETs /api/stats', async () => {
    mockFetch({ success: true, data: { total_certificates: 5 } })
    const result = await api.getStats()
    expect(global.fetch).toHaveBeenCalledWith('/api/stats', expect.any(Object))
    expect(result.data.total_certificates).toBe(5)
  })
})

// ── api.runScheduler ──────────────────────────────────────────────────────────

describe('api.runScheduler', () => {
  it('POSTs to /api/scheduler/run', async () => {
    mockFetch({ success: true })
    await api.runScheduler()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/scheduler/run',
      expect.objectContaining({ method: 'POST' })
    )
  })
})

// ── api.checkDomain ───────────────────────────────────────────────────────────

describe('api.checkDomain', () => {
  it('GETs /api/check/:domain with URL encoding', async () => {
    mockFetch({ success: true, data: {} })
    await api.checkDomain('my domain.com')
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('/api/check/')
    expect(url).toContain('my%20domain.com')
  })
})

// ── api.getHistory ────────────────────────────────────────────────────────────

describe('api.getHistory', () => {
  it('GETs /api/history/:domain', async () => {
    mockFetch({ success: true, data: [] })
    await api.getHistory('example.com')
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/history/example.com',
      expect.any(Object)
    )
  })
})

// ── api.admin ─────────────────────────────────────────────────────────────────

describe('api.admin.getInventory', () => {
  it('GETs /api/admin/inventory with showDeleted=false by default', async () => {
    mockFetch({ success: true, data: [] })
    await api.admin.getInventory()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/inventory?showDeleted=false',
      expect.any(Object)
    )
  })
  it('GETs /api/admin/inventory with showDeleted=true when passed', async () => {
    mockFetch({ success: true, data: [] })
    await api.admin.getInventory(true)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/inventory?showDeleted=true',
      expect.any(Object)
    )
  })
})

describe('api.admin.addInventory', () => {
  it('POSTs to /api/admin/inventory with body', async () => {
    mockFetch({ success: true, data: {} })
    await api.admin.addInventory({ domain: 'new.com', port: 443 })
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/inventory',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ domain: 'new.com', port: 443 }),
      })
    )
  })
})

describe('api.admin.deleteInventory', () => {
  it('DELETEs /api/admin/inventory/:id', async () => {
    mockFetch({ success: true })
    await api.admin.deleteInventory(42)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/inventory/42',
      expect.objectContaining({ method: 'DELETE' })
    )
  })
})

describe('api.admin.getAlerts', () => {
  it('GETs /api/admin/alerts with no query params by default', async () => {
    mockFetch({ success: true, data: [], total: 0 })
    await api.admin.getAlerts()
    const url = global.fetch.mock.calls[0][0]
    expect(url).toBe('/api/admin/alerts')
  })

  it('GETs /api/admin/alerts?onlyOpen=true when legacy boolean arg passed', async () => {
    mockFetch({ success: true, data: [] })
    await api.admin.getAlerts(true)
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('onlyOpen=true')
  })

  it('GETs /api/admin/alerts with paging + filter params', async () => {
    mockFetch({ success: true, data: [], total: 0 })
    await api.admin.getAlerts({ resolved: 'true', page: 2, size: 50, resolvedSince: '2026-05-01T00:00:00' })
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('resolved=true')
    expect(url).toContain('page=2')
    expect(url).toContain('size=50')
    expect(url).toContain('resolvedSince=2026-05-01T00%3A00%3A00')
  })
})

describe('api.admin.acknowledgeAlert', () => {
  // İkinci parametre artık "kim" değil ZORUNLU GEREKÇE. Sunucu da doğruluyor (AlertActionNote);
  // burada yalnız notun gövdeye doğru anahtarla konduğu kilitleniyor.
  it('POSTs to /api/admin/alerts/:id/acknowledge with the note', async () => {
    mockFetch({ success: true })
    await api.admin.acknowledgeAlert(5, 'planlı bakım kapsamında')
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/alerts/5/acknowledge',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ note: 'planlı bakım kapsamında' }),
      })
    )
  })
})

describe('api.admin.resolveAlert', () => {
  it('POSTs to /api/admin/alerts/:id/resolve', async () => {
    mockFetch({ success: true })
    await api.admin.resolveAlert(7, 'düzeltme devrede doğrulandı')
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/alerts/7/resolve',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ note: 'düzeltme devrede doğrulandı' }),
      })
    )
  })
})

// ── api.admin — system health & audit ────────────────────────────────────────

describe('api.admin.getDbStats', () => {
  it('GETs /api/admin/system/db-stats', async () => {
    mockFetch([])
    await api.admin.getDbStats()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/system/db-stats',
      expect.any(Object)
    )
  })
})

describe('api.admin.getSmtpLogs', () => {
  it('GETs /api/admin/system/smtp-logs', async () => {
    mockFetch([])
    await api.admin.getSmtpLogs()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/system/smtp-logs',
      expect.any(Object)
    )
  })
})

describe('api.admin.getSystemHealth', () => {
  it('GETs /api/admin/system', async () => {
    mockFetch({ success: true })
    await api.admin.getSystemHealth()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/system',
      expect.any(Object)
    )
  })
})

describe('api.admin.getAuditLogs', () => {
  it('filters out blank string and null params from query', async () => {
    mockFetch({ success: true, data: [] })
    await api.admin.getAuditLogs({ actor: 'alice', eventType: '', outcome: null, anomalyOnly: false })
    const url = global.fetch.mock.calls[0][0]
    expect(url).toContain('actor=alice')
    expect(url).not.toContain('eventType')
    expect(url).not.toContain('outcome')
    expect(url).not.toContain('anomalyOnly')
  })
})

describe('api.admin.getAuditStats', () => {
  it('GETs /api/admin/audit/stats', async () => {
    mockFetch({ success: true })
    await api.admin.getAuditStats()
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/audit/stats',
      expect.any(Object)
    )
  })
})

// ── api.admin — team CRUD ─────────────────────────────────────────────────────

describe('api.admin.createTeam', () => {
  it('POSTs to /api/admin/teams with body', async () => {
    mockFetch({ success: true })
    const data = { name: 'Team A', email: 'a@example.com' }
    await api.admin.createTeam(data)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/teams',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(data),
      })
    )
  })
})

describe('api.admin.updateTeam', () => {
  it('PUTs to /api/admin/teams/3', async () => {
    mockFetch({ success: true })
    const data = { name: 'Updated' }
    await api.admin.updateTeam(3, data)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/teams/3',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify(data),
      })
    )
  })
})

describe('api.admin.deleteTeam', () => {
  it('DELETEs /api/admin/teams/5', async () => {
    mockFetch({ success: true })
    await api.admin.deleteTeam(5)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/teams/5',
      expect.objectContaining({ method: 'DELETE' })
    )
  })
})

// ── api.admin — user CRUD ─────────────────────────────────────────────────────

describe('api.admin.createUser', () => {
  it('POSTs to /api/admin/users with body', async () => {
    mockFetch({ success: true })
    const data = { username: 'bob', password: 'secret' }
    await api.admin.createUser(data)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/users',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(data),
      })
    )
  })
})

describe('api.admin.updateUser', () => {
  it('PUTs to /api/admin/users/7', async () => {
    mockFetch({ success: true })
    const data = { displayName: 'Bob Smith' }
    await api.admin.updateUser(7, data)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/users/7',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify(data),
      })
    )
  })
})

describe('api.admin.deleteUser', () => {
  it('DELETEs /api/admin/users/9', async () => {
    mockFetch({ success: true })
    await api.admin.deleteUser(9)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/users/9',
      expect.objectContaining({ method: 'DELETE' })
    )
  })
})

describe('api.admin.unlockUser', () => {
  it('POSTs to /api/admin/users/4/unlock', async () => {
    mockFetch({ success: true })
    await api.admin.unlockUser(4)
    expect(global.fetch).toHaveBeenCalledWith(
      '/api/admin/users/4/unlock',
      expect.objectContaining({ method: 'POST' })
    )
  })
})

// ── formatDate ────────────────────────────────────────────────────────────────

describe('formatDate', () => {
  it('returns N/A for null', () => {
    expect(formatDate(null)).toBe('N/A')
  })

  it('returns N/A for undefined', () => {
    expect(formatDate(undefined)).toBe('N/A')
  })

  it('returns N/A for empty string', () => {
    expect(formatDate('')).toBe('N/A')
  })

  it('formats a valid ISO date string', () => {
    const result = formatDate('2025-01-15T12:00:00')
    // Just check it returns a non-empty non-N/A string
    expect(result).not.toBe('N/A')
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })
})

// ── formatDateSec / formatTime / formatDateOnly ───────────────────────────────
// Boş girdi sentinel'i (N/A veya —) + geçerli ISO'da sentinel-olmayan string döndüğü doğrulanır.
// (Locale/timezone'a bağlı KESİN çıktı iddia edilmez — CI ile makine arası kaymayı önlemek için.)

describe('formatDateSec', () => {
  it('boş girdi → N/A', () => {
    expect(formatDateSec(null)).toBe('N/A')
    expect(formatDateSec('')).toBe('N/A')
  })
  it('geçerli ISO → saniye içeren, sentinel-olmayan string', () => {
    const r = formatDateSec('2025-01-15T12:00:30')
    expect(r).not.toBe('N/A')
    expect(typeof r).toBe('string')
    expect(r.length).toBeGreaterThan(0)
  })
})

describe('formatTime', () => {
  it('boş girdi → —', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime('')).toBe('—')
  })
  it('geçerli ISO → sentinel-olmayan string', () => {
    const r = formatTime('2025-01-15T12:00:30')
    expect(r).not.toBe('—')
    expect(typeof r).toBe('string')
    expect(r.length).toBeGreaterThan(0)
  })
})

describe('formatDateOnly', () => {
  it('boş girdi → —', () => {
    expect(formatDateOnly(null)).toBe('—')
    expect(formatDateOnly('')).toBe('—')
  })
  it('geçerli ISO → sentinel-olmayan string', () => {
    const r = formatDateOnly('2025-01-15T12:00:30')
    expect(r).not.toBe('—')
    expect(typeof r).toBe('string')
    expect(r.length).toBeGreaterThan(0)
  })
})
