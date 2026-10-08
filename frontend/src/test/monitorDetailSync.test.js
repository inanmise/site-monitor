import { describe, it, expect, vi } from 'vitest'
import { freshestRow, mergeSavedRow, reloadAndSyncDetail } from '../utils/monitorDetailSync.js'

/**
 * Açık detay kopyası ↔ güncel satır yardımcıları (2026-10-09): "açık detaydan ikinci düzenleme ilkini geri alıyordu".
 * Dokuz izleme sayfası bu üç fonksiyonla açık pencerenin kopyasını tazeler ve "Düzenle" formunu güncel satırdan kurar.
 */
describe('mergeSavedRow — kaydetme yanıtı açık detaya', () => {
  const open = { id: 1, url: 'https://a/', expected_status: '201-204', can_diagnose: true }

  it('AYNI izleme: sunucu satırı kopyanın üstüne birleşir (yanıtta olmayan alan kalır)', () => {
    const out = mergeSavedRow(open, { id: 1, expected_status: '200' })
    expect(out).toEqual({ id: 1, url: 'https://a/', expected_status: '200', can_diagnose: true })
    expect(out).not.toBe(open)
  })

  it('başka izleme / yeni kayıt / gövdesiz yanıt / kapalı pencere: kopyaya DOKUNULMAZ (aynı nesne döner)', () => {
    expect(mergeSavedRow(open, { id: 2, expected_status: '200' })).toBe(open)
    expect(mergeSavedRow(open, {})).toBe(open)
    expect(mergeSavedRow(open, undefined)).toBe(open)
    expect(mergeSavedRow(open, null)).toBe(open)
    expect(mergeSavedRow(null, { id: 1 })).toBeNull()
  })
})

describe('freshestRow — Düzenle formunun kaynağı', () => {
  const stale = { id: 1, name: 'eski', expected_status: '201-204', only_in_copy: 'x' }

  it('listede varsa güncel satır kopyanın ÜSTÜNE birleşir', () => {
    const list = [{ id: 2, name: 'b' }, { id: 1, name: 'yeni', expected_status: '200' }]
    expect(freshestRow(stale, list)).toEqual({ id: 1, name: 'yeni', expected_status: '200', only_in_copy: 'x' })
  })

  it('listede yoksa, liste yoksa ya da kimliksizse aynen döner', () => {
    expect(freshestRow(stale, [{ id: 9 }])).toBe(stale)
    expect(freshestRow(stale, undefined)).toBe(stale)
    expect(freshestRow('new', [{ id: 1 }])).toBe('new')
    expect(freshestRow(null, [{ id: 1 }])).toBeNull()
    const noId = { name: 'x' }
    expect(freshestRow(noId, [{ id: 1 }])).toBe(noId)
  })
})

describe('reloadAndSyncDetail — geri alma sonrası', () => {
  it('liste yeniden yüklenir; taze satır AÇIK kopyaya işlenir (kimlik kapısı ayarlayıcıda)', async () => {
    const load = vi.fn().mockResolvedValue([{ id: 1, expected_status: '418' }, { id: 2 }])
    let detail = { id: 1, expected_status: '200', url: 'https://a/' }
    const setDetail = vi.fn((fn) => { detail = fn(detail) })
    const fresh = await reloadAndSyncDetail(load, 1, setDetail)
    expect(load).toHaveBeenCalledTimes(1)
    expect(fresh).toEqual({ id: 1, expected_status: '418' })
    expect(detail).toEqual({ id: 1, expected_status: '418', url: 'https://a/' })

    // Pencere o arada başka izlemeye geçtiyse A'nın satırı B'yi değiştirmez
    detail = { id: 2, name: 'B' }
    await reloadAndSyncDetail(load, 1, setDetail)
    expect(detail).toEqual({ id: 2, name: 'B' })
  })

  it('yükleme başarısız (undefined) ya da satır yok → ayarlayıcı çağrılmaz', async () => {
    const setDetail = vi.fn()
    expect(await reloadAndSyncDetail(vi.fn().mockResolvedValue(undefined), 1, setDetail)).toBeNull()
    expect(await reloadAndSyncDetail(vi.fn().mockResolvedValue([{ id: 3 }]), 1, setDetail)).toBeNull()
    expect(setDetail).not.toHaveBeenCalled()
  })
})
