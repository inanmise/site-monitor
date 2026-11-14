// Zaman aşımı kuralı (2026-11-15, kullanıcı: "timeout olmayan istekler kesinlikle olmamalı"): Sayfa Hızı kaynak CSV'si
// indirmesi istek VE gövde okuması boyunca tek son tarihle sınırlı. Asılı bağlantı → çevrilmiş REQUEST_TIMEOUT, sonsuz bekleme yok.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, EXPORT_FETCH_TIMEOUT_MS } from '../api/client'

/** Sinyal kesilene dek bekleyen, kesilince AbortError ile düşen söz. */
function hangUntilAbort(signal) {
  return new Promise((_, reject) => {
    const fail = () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    if (signal?.aborted) fail()
    else signal?.addEventListener('abort', fail, { once: true })
  })
}

describe('Sayfa Hızı kaynak CSV indirmesi — süre sınırı', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('yanıt hiç gelmezse son tarihte REQUEST_TIMEOUT döner', async () => {
    const fetchMock = vi.fn((url, init) => hangUntilAbort(init?.signal))
    vi.stubGlobal('fetch', fetchMock)
    const p = api.monitoring.downloadPageSpeedResourcesCsv(7, {})
    await vi.advanceTimersByTimeAsync(EXPORT_FETCH_TIMEOUT_MS + 10)
    const res = await p
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1].signal).toBeTruthy()
    expect(res.success).toBe(false)
    expect(res.code).toBe('REQUEST_TIMEOUT')
  })

  it('başlıklar gelip gövde okuması asılı kalırsa da son tarihte REQUEST_TIMEOUT döner', async () => {
    const fetchMock = vi.fn((url, init) => Promise.resolve({
      ok: true, status: 200, headers: { get: () => null }, blob: () => hangUntilAbort(init?.signal),
    }))
    vi.stubGlobal('fetch', fetchMock)
    const p = api.monitoring.downloadPageSpeedResourcesCsv(7, {})
    await vi.advanceTimersByTimeAsync(EXPORT_FETCH_TIMEOUT_MS + 10)
    const res = await p
    expect(res.success).toBe(false)
    expect(res.code).toBe('REQUEST_TIMEOUT')
  })

  it('süre sınırı makul: 1–5 dakika arası', () => {
    expect(EXPORT_FETCH_TIMEOUT_MS).toBeGreaterThanOrEqual(60_000)
    expect(EXPORT_FETCH_TIMEOUT_MS).toBeLessThanOrEqual(300_000)
  })
})
