import { describe, it, expect, vi } from 'vitest'
import {
  UNDO_FETCH_TIMEOUT_MS, UNDO_MAX_BYTES, UNDO_MS, pendingUndoCount, registerUndo, restoreTopic, snapshotImage,
} from '../components/techerror/imageUndo.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'

// Kanal teknik hata raporu — "Geri al" (A3, 2026-11-20): görselin baytları silme / değiştirme ÖNCESİ süre sınırlı okunur
// (≤ 5 MB; okunamazsa geri alma sunulmaz, hiç atmaz), bildirim oturum sıfırlanınca kapanır, konu silindiği yere geri gelir.
function blobOf(size, type = 'image/png') {
  return new Blob([new Uint8Array(size)], { type })
}
function fakeFetch({ ok = true, blob = blobOf(10), reject = null } = {}) {
  return vi.fn(async (url, init, timeoutMs) => {
    if (reject) throw reject
    return { ok, status: ok ? 200 : 404, blob: async () => blob, done: vi.fn(), url, init, timeoutMs }
  })
}

describe('imageUndo — snapshotImage', () => {
  it('sabitler: bildirim 10 sn, sınır 5 MB (sunucunun görsel sınırı), okuma süre sınırlı', () => {
    expect(UNDO_MS).toBe(10000)
    expect(UNDO_MAX_BYTES).toBe(5 * 1024 * 1024)
    expect(UNDO_FETCH_TIMEOUT_MS).toBeGreaterThan(0)
    expect(UNDO_FETCH_TIMEOUT_MS).toBeLessThanOrEqual(15000)
  })

  it('okunan baytlar adıyla ve türüyle File olur; okuma süre sınırıyla yapılır', async () => {
    const f = fakeFetch({ blob: blobOf(1234, 'image/jpeg') })
    const file = await snapshotImage({ id: 7, file_name: 'grafik.jpg', size_bytes: 1234 }, '/api/x/7', { fetchImpl: f })
    expect(file).toBeInstanceOf(Blob)
    expect(file.size).toBe(1234)
    expect(file.type).toBe('image/jpeg')
    expect(file.name).toBe('grafik.jpg')
    expect(f).toHaveBeenCalledTimes(1)
    expect(f.mock.calls[0][2]).toBe(UNDO_FETCH_TIMEOUT_MS)
  })

  it('adı yoksa kimlik ve türden üretilir', async () => {
    const file = await snapshotImage({ id: 9 }, '/u', { fetchImpl: fakeFetch({ blob: blobOf(5, 'image/png') }) })
    expect(file.name).toBe('image-9.png')
  })

  it('beyan edilen boyut sınırın üstündeyse hiç istek atılmaz', async () => {
    const f = fakeFetch()
    expect(await snapshotImage({ id: 1, size_bytes: UNDO_MAX_BYTES + 1 }, '/u', { fetchImpl: f })).toBeNull()
    expect(f).not.toHaveBeenCalled()
  })

  it('gerçek gövde sınırın üstünde, boş, ret yanıtı, ağ hatası → null (atmaz); ret yanıtında zamanlayıcı sökülür', async () => {
    expect(await snapshotImage({ id: 1 }, '/u', { fetchImpl: fakeFetch({ blob: blobOf(UNDO_MAX_BYTES + 1) }) })).toBeNull()
    expect(await snapshotImage({ id: 1 }, '/u', { fetchImpl: fakeFetch({ blob: blobOf(0) }) })).toBeNull()
    const notOk = fakeFetch({ ok: false })
    expect(await snapshotImage({ id: 1 }, '/u', { fetchImpl: notOk })).toBeNull()
    expect(await snapshotImage({ id: 1 }, '/u', { fetchImpl: fakeFetch({ reject: new TypeError('ağ') }) })).toBeNull()
    const timeout = Object.assign(new Error('deadline'), { name: 'TimeoutError' })
    expect(await snapshotImage({ id: 1 }, '/u', { fetchImpl: fakeFetch({ reject: timeout }) })).toBeNull()
  })

  it('görsel ya da adres yoksa null', async () => {
    const f = fakeFetch()
    expect(await snapshotImage(null, '/u', { fetchImpl: f })).toBeNull()
    expect(await snapshotImage({ id: null }, '/u', { fetchImpl: f })).toBeNull()
    expect(await snapshotImage({ id: 1 }, '', { fetchImpl: f })).toBeNull()
    expect(f).not.toHaveBeenCalled()
  })
})

describe('imageUndo — oturum sıfırlama', () => {
  it('oturum sıfırlanınca bekleyen her geri alma bildirimi kapanır, kayıt boşalır', () => {
    const a = vi.fn()
    const b = vi.fn(() => { throw new Error('kapatma hatası') })
    const c = vi.fn()
    registerUndo(a)
    registerUndo(b)
    const offC = registerUndo(c)
    offC()                                     // kendiliğinden kapanan bildirim kaydını siler
    expect(pendingUndoCount()).toBe(2)
    emitSessionReset()
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)        // bir kapatma hatası ötekileri durdurmaz
    expect(c).not.toHaveBeenCalled()
    expect(pendingUndoCount()).toBe(0)
    emitSessionReset()
    expect(a).toHaveBeenCalledTimes(1)
  })

  it('işlev olmayan kayıt yok sayılır', () => {
    const off = registerUndo(null)
    expect(typeof off).toBe('function')
    expect(pendingUndoCount()).toBe(0)
  })
})

describe('imageUndo — restoreTopic', () => {
  const A = { id: 'a' }
  const B = { id: 'b' }
  const C = { id: 'c' }

  it('konu silindiği konuma AYNI nesneyle geri girer; liste kısaldıysa sona', () => {
    expect(restoreTopic([A, C], B, 1)).toEqual([A, B, C])
    expect(restoreTopic([A, C], B, 1)[1]).toBe(B)
    expect(restoreTopic([A], B, 5)).toEqual([A, B])
    expect(restoreTopic([], B, 3)).toEqual([B])
    expect(restoreTopic([A, C], B, -2)).toEqual([B, A, C])
    expect(restoreTopic([A, C], B, null)).toEqual([A, C, B])
  })

  it('aynı kimlikli konu zaten varsa liste aynen döner (iki kez geri alma çoğaltmaz)', () => {
    const list = [A, B, C]
    expect(restoreTopic(list, { id: 'b', title: 'başka' }, 0)).toBe(list)
  })

  it('girdi değişmez; konu yoksa / liste değilse güvenli', () => {
    const list = [A, C]
    restoreTopic(list, B, 1)
    expect(list).toEqual([A, C])
    expect(restoreTopic(null, B, 0)).toEqual([B])
    const l2 = [A]
    expect(restoreTopic(l2, null, 0)).toBe(l2)
  })
})
