import { describe, it, expect } from 'vitest'
import { parseUtc, durationMs, formatDuration, autoDurationMinutes, rcMeta, RC_META } from '../utils/incidentMeta.js'

// Saf tarih/süre yardımcıları — olay (incident) kartlarındaki "ne kadar sürdü" + kök-neden rengi.
// parseUtc her iki tarafı aynı biçimde UTC'ye çevirdiği için süre farkı timezone'dan bağımsızdır.

describe('parseUtc', () => {
  it('zone\'suz ISO → UTC Date (Z eklenir)', () => {
    expect(parseUtc('2026-01-15T12:00:00')).toBeInstanceOf(Date)
  })

  it('zaten zone\'lu ISO\'ya dokunmaz (geçerli Date)', () => {
    expect(parseUtc('2026-01-15T12:00:00Z')).toBeInstanceOf(Date)
    expect(parseUtc('2026-01-15T12:00:00+03:00')).toBeInstanceOf(Date)
  })

  it('null / geçersiz → null', () => {
    expect(parseUtc(null)).toBeNull()
    expect(parseUtc('')).toBeNull()
    expect(parseUtc('garbage')).toBeNull()
  })
})

describe('durationMs', () => {
  it('resolved: resolvedAt − startedAt (ms)', () => {
    expect(durationMs('2026-01-15T12:00:00', '2026-01-15T12:05:00')).toBe(5 * 60 * 1000)
  })

  it('resolved yok: now − startedAt (canlı, nowMs verilir)', () => {
    const now = Date.parse('2026-01-15T12:00:30Z')
    expect(durationMs('2026-01-15T12:00:00', null, now)).toBe(30 * 1000)
  })

  it('start yok / geçersiz → null', () => {
    expect(durationMs(null, null, Date.now())).toBeNull()
    expect(durationMs('garbage', null, Date.now())).toBeNull()
  })
})

describe('autoDurationMinutes', () => {
  it('OLUŞ → çözülme farkı, tam dakika (tespit değil)', () => {
    // oluş 10:00, çözülme 10:50 → 50 dk (tespit 10:20 hesaba KARIŞMAZ)
    expect(autoDurationMinutes('2026-01-15T10:00:00', '2026-01-15T10:50:00')).toBe(50)
  })

  it('saniye → en yakın dakikaya yuvarlar', () => {
    expect(autoDurationMinutes('2026-01-15T10:00:00', '2026-01-15T10:02:40')).toBe(3)   // 2dk40sn → 3
  })

  it('çözülme < oluş (negatif) → null', () => {
    expect(autoDurationMinutes('2026-01-15T10:50:00', '2026-01-15T10:00:00')).toBeNull()
  })

  it('oluş veya çözülme eksik → null', () => {
    expect(autoDurationMinutes('', '2026-01-15T10:50:00')).toBeNull()
    expect(autoDurationMinutes('2026-01-15T10:00:00', null)).toBeNull()
    expect(autoDurationMinutes(null, null)).toBeNull()
  })
})

describe('formatDuration', () => {
  // i18n birim stub — gerçek t() yerine sabit kısaltmalar.
  const t = (k) => ({
    'incov.unit.sec': 'sn', 'incov.unit.min': 'dk', 'incov.unit.hour': 'sa', 'incov.unit.day': 'g',
  }[k] || k)

  it('null / negatif → —', () => {
    expect(formatDuration(null, t)).toBe('—')
    expect(formatDuration(-5, t)).toBe('—')
  })

  it('<60 sn → saniye', () => {
    expect(formatDuration(45_000, t)).toBe('45 sn')
  })

  it('<60 dk → dakika', () => {
    expect(formatDuration(5 * 60 * 1000, t)).toBe('5 dk')
  })

  it('saat + kalan dakika birlikte', () => {
    expect(formatDuration((2 * 60 + 3) * 60 * 1000, t)).toBe('2 sa 3 dk')
  })

  it('gün + kalan saat birlikte', () => {
    expect(formatDuration((3 * 24 + 4) * 60 * 60 * 1000, t)).toBe('3 g 4 sa')
  })
})

describe('rcMeta', () => {
  it('bilinen kategori → ilgili meta (renk + i18n anahtarı)', () => {
    expect(rcMeta('down')).toEqual(RC_META.down)
    expect(rcMeta('ssl')).toEqual(RC_META.ssl)
  })

  it('bilinmeyen / null kategori → unknown fallback', () => {
    expect(rcMeta('nope')).toEqual(RC_META.unknown)
    expect(rcMeta(null)).toEqual(RC_META.unknown)
  })
})
