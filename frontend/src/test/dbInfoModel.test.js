import { describe, it, expect } from 'vitest'
import {
  driverText, fmtNum, hasMaskedSecret, healthModel, hostPort, isBlank, maskJdbcUrl, parseIntervalSeconds, poolModel,
  publicHealthUrl, usage,
} from '../components/admin/dbinfo/dbInfoModel.js'

/** Ayarlar → Veritabanı Bilgileri saf yardımcıları (2026-10-09). */
describe('dbInfoModel', () => {
  it('isBlank: 0 ve false boş değildir', () => {
    expect(isBlank(null)).toBe(true)
    expect(isBlank(undefined)).toBe(true)
    expect(isBlank('  ')).toBe(true)
    expect(isBlank(0)).toBe(false)
    expect(isBlank(false)).toBe(false)
    expect(fmtNum(null)).toBe('—')
  })

  it('hostPort + driverText eski bileşenle aynı kural', () => {
    expect(hostPort({ server_addr: '203.0.113.10', server_port: 5432 })).toBe('203.0.113.10:5432')
    expect(hostPort({ server_addr: '203.0.113.10' })).toBe('203.0.113.10')
    expect(hostPort({ server_port: 5432 })).toBe('5432')
    expect(hostPort({})).toBeNull()
    expect(driverText({ driver_name: 'PostgreSQL JDBC Driver', driver_version: '42.7.4' })).toBe('PostgreSQL JDBC Driver 42.7.4')
    expect(driverText({ driver_name: 'PostgreSQL JDBC Driver' })).toBe('PostgreSQL JDBC Driver')
    expect(driverText({ driver_version: '42' })).toBeNull()
  })

  it('maskJdbcUrl: sunucu kuralının aynası (parola parametreleri + kullanıcı:parola@)', () => {
    expect(maskJdbcUrl(null)).toBeNull()
    expect(maskJdbcUrl('jdbc:postgresql://db.example.com:5432/appdb')).toBe('jdbc:postgresql://db.example.com:5432/appdb')
    expect(maskJdbcUrl('jdbc:postgresql://h.example.com/db?user=app&password=s3cr3t&sslmode=require'))
      .toBe('jdbc:postgresql://h.example.com/db?user=app&password=***&sslmode=require')
    expect(maskJdbcUrl('jdbc:x://h.example.com/db;PWD=a;passwd=b;secret=c;token=d;sslkey=/k.pem;SSLPASSWORD=e'))
      .toBe('jdbc:x://h.example.com/db;PWD=***;passwd=***;secret=***;token=***;sslkey=***;SSLPASSWORD=***')
    expect(maskJdbcUrl('jdbc:postgresql://app:hunter2@db.example.com/appdb')).toBe('jdbc:postgresql://app:***@db.example.com/appdb')
    expect(maskJdbcUrl('jdbc:postgresql://h1.example.com:5432,h2.example.com:5433/appdb'))
      .toBe('jdbc:postgresql://h1.example.com:5432,h2.example.com:5433/appdb')
    expect(hasMaskedSecret('jdbc:postgresql://h/db?password=***')).toBe(true)
    expect(hasMaskedSecret('jdbc:postgresql://h/db')).toBe(false)
  })

  it('parseIntervalSeconds: PostgreSQL interval metinleri', () => {
    expect(parseIntervalSeconds('04:05:06')).toBe(4 * 3600 + 5 * 60 + 6)
    expect(parseIntervalSeconds('1 day 00:00:01')).toBe(86401)
    expect(parseIntervalSeconds('12 days 03:04:05')).toBe(12 * 86400 + 3 * 3600 + 4 * 60 + 5)
    expect(parseIntervalSeconds('1 year 2 mons 3 days 00:00:00')).toBe((365 + 60 + 3) * 86400)
    expect(parseIntervalSeconds('3 days')).toBe(3 * 86400)
    expect(parseIntervalSeconds('00:00:01.123')).toBe(1)
    expect(parseIntervalSeconds('bilinmeyen')).toBeNull()
    expect(parseIntervalSeconds('')).toBeNull()
    expect(parseIntervalSeconds(null)).toBeNull()
  })

  it('usage: doluluk yüzdesi ve ton eşikleri', () => {
    expect(usage(12, '100')).toEqual({ pct: 12, tone: 'success' })
    expect(usage(70, 100)).toEqual({ pct: 70, tone: 'warning' })
    expect(usage(95, 100)).toEqual({ pct: 95, tone: 'danger' })
    expect(usage(150, 100)).toEqual({ pct: 100, tone: 'danger' })
    expect(usage(1, 0)).toBeNull()
    expect(usage(null, 100)).toBeNull()
  })

  it('poolModel: boş havuz null; bekleyen varsa en az "dikkat"; dilimler kullanımda/boşta/açılabilir', () => {
    expect(poolModel({})).toBeNull()
    expect(poolModel(null)).toBeNull()
    const p = poolModel({ name: 'P', active: 3, idle: 7, total: 10, waiting: 0, max_size: 20, min_idle: 5, connection_timeout_ms: 30000 })
    expect(p).toMatchObject({ name: 'P', active: 3, idle: 7, total: 10, waiting: 0, max: 20, min: 5, pct: 15, tone: 'success', connectionTimeoutMs: 30000 })
    expect(p.segments.map((s) => [s.key, s.count])).toEqual([['active', 3], ['idle', 7], ['free', 10]])
    expect(poolModel({ active: 2, idle: 0, total: 2, waiting: 3, max_size: 20 }).tone).toBe('warning')
    expect(poolModel({ active: 20, idle: 0, total: 20, waiting: 3, max_size: 20 }).tone).toBe('danger')
    expect(poolModel({ active: 20, idle: 0, total: 20, waiting: 0, max_size: 20 }).segments.map((s) => s.key)).toEqual(['active'])
  })

  it('healthModel: denetim sırası, ton, açıklama anahtarları; olmayan denetim "denetlenemedi"', () => {
    expect(healthModel(null)).toBeNull()
    const h = healthModel({
      status: 'degraded', checked_at: '2026-10-09T09:00:00Z', duration_ms: 14, cached: true,
      checks: {
        connection: { status: 'UP', acquire_ms: 2 }, query: { status: 'DEGRADED', latency_ms: 1500 },
        pool: { status: 'DEGRADED', active: 20, max: 20, waiting: 0 }, schema: { status: 'DEGRADED', failed_patches: 2 },
      },
    })
    expect(h).toMatchObject({ status: 'DEGRADED', tone: 'warning', durationMs: 14, cached: true, queryMs: 1500, acquireMs: 2 })
    expect(h.checks.map((c) => c.key)).toEqual(['connection', 'query', 'writable', 'pool', 'schema'])
    const by = Object.fromEntries(h.checks.map((c) => [c.key, c]))
    expect(by.connection.detail).toEqual({ key: 'dbinfo.chkAcquire', args: ['2'] })
    expect(by.query.detail.key).toBe('dbinfo.chkQuerySlow')
    expect(by.writable).toMatchObject({ status: null, tone: 'muted', detail: { key: 'dbinfo.chkSkipped' } })
    expect(by.pool.detail).toEqual({ key: 'dbinfo.chkPoolFull', args: ['20', '20'] })
    expect(by.schema.detail.key).toBe('dbinfo.chkSchemaFailed')
  })

  it('healthModel: hata kodları ve şema yaması sayıları', () => {
    const h = healthModel({ status: 'DOWN', checks: {
      connection: { status: 'DOWN', error: 'CONNECTION_FAILED' }, writable: { status: 'DOWN', error: 'READ_ONLY' },
      pool: { status: 'DEGRADED', waiting: 4, active: 3, max: 20 }, schema: { status: 'UP', failed_patches: 0 },
    } }, { applied: 4, noop: 500, failed: 0 })
    const by = Object.fromEntries(h.checks.map((c) => [c.key, c]))
    expect(h.tone).toBe('danger')
    expect(by.connection.detail.key).toBe('dbinfo.chkConnFailed')
    expect(by.writable.detail.key).toBe('dbinfo.chkReadOnly')
    expect(by.pool.detail).toEqual({ key: 'dbinfo.chkPoolWaiting', args: ['4'] })
    expect(by.schema.detail).toEqual({ key: 'dbinfo.chkSchemaCounts', args: ['4', '500', '0'] })
    expect(healthModel({ status: 'UP', checks: { schema: { status: 'UP', pending: true } } }).checks.at(-1).detail.key).toBe('dbinfo.chkSchemaPending')
    expect(healthModel({ status: 'WEIRD' }).status).toBeNull()
  })

  it('publicHealthUrl: kökün sonundaki eğik çizgi tekilleşir', () => {
    expect(publicHealthUrl('https://monitor.example.com/')).toBe('https://monitor.example.com/api/public/health/db')
    expect(publicHealthUrl('')).toBe('/api/public/health/db')
  })
})
