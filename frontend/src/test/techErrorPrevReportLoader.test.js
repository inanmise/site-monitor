import { describe, it, expect, vi, afterEach } from 'vitest'
import { PREV_READ_DEADLINE_MS, PREV_READ_TIMEOUT_MS, PrevReadError, createPrevReportLoader } from '../components/techerror/prevReportLoader.js'

// Önceki ayın raporu (2026-11-20, C1 / C3): paylaşılan, tembel, süre sınırlı okuma. Başarılı okuma tutulur; başarısız okuma
// bırakılır (sonraki deneme yeniden ister); hata türü "yok / yetki" (gone), "zaman aşımı" (timeout) ya da "okunamadı" (failed).

const ok = (data) => ({ success: true, data })
const fail = (status, code, error = 'x') => Object.assign({ success: false, error }, { errorInfo: { status, code } })

afterEach(() => { vi.useRealTimers() })

describe('createPrevReportLoader', () => {
  it('sınırlar: istemci zaman aşımı üst süreden kısa', () => {
    expect(PREV_READ_TIMEOUT_MS).toBeLessThan(PREV_READ_DEADLINE_MS)
  })

  it('BİR KEZ okur; eşzamanlı ve sonraki çağrılar aynı sonucu paylaşır', async () => {
    const read = vi.fn(async () => ok({ images: [{ id: 1, slot_id: 'a' }] }))
    const l = createPrevReportLoader(90, read)
    const [a, b] = await Promise.all([l.load(), l.load()])
    expect(a).toBe(b)
    await l.load()
    expect(read).toHaveBeenCalledTimes(1)
    expect(read).toHaveBeenCalledWith(90)
    expect(l.id).toBe(90)
  })

  it.each([
    [fail(404, 'NOT_FOUND'), 'gone'],
    [fail(403, 'FORBIDDEN'), 'gone'],
    [fail(0, 'REQUEST_TIMEOUT'), 'timeout'],
    [fail(500, 'INTERNAL'), 'failed'],
    [{ success: true, data: null }, 'failed'],
    [null, 'failed'],
  ])('ret %j → %s; başarısız okuma bırakılır, yeniden deneme yeniden ister', async (res, kind) => {
    const read = vi.fn().mockResolvedValueOnce(res).mockResolvedValueOnce(ok({ images: [] }))
    const l = createPrevReportLoader(90, read)
    const err = await l.load().catch((e) => e)
    expect(err).toBeInstanceOf(PrevReadError)
    expect(err.kind).toBe(kind)
    await expect(l.load()).resolves.toEqual({ images: [] })
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('istek atarsa "failed"', async () => {
    const l = createPrevReportLoader(90, () => { throw new Error('boom') })
    const err = await l.load().catch((e) => e)
    expect(err.kind).toBe('failed')
  })

  it('asılı kalan okuma üst sürede "timeout" ile biter (söz bekletmez)', async () => {
    vi.useFakeTimers()
    const l = createPrevReportLoader(90, () => new Promise(() => {}), { deadlineMs: 1000 })
    const p = l.load().catch((e) => e)
    await vi.advanceTimersByTimeAsync(1001)
    const err = await p
    expect(err.kind).toBe('timeout')
  })
})
