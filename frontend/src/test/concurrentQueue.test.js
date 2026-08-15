import { describe, it, expect, vi } from 'vitest'
import { runWithConcurrency } from '../utils/concurrentQueue.js'

/** Belirtilen ms sonra çözülen söz (sahte zamanlayıcı kullanılmıyor: gerçek ama çok kısa süreler). */
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

describe('runWithConcurrency', () => {
  it('aynı anda limitten fazla iş başlatmaz', async () => {
    let inFlight = 0, peak = 0
    const items = Array.from({ length: 20 }, (_, i) => i)

    await runWithConcurrency(items, async () => {
      inFlight++
      peak = Math.max(peak, inFlight)
      await sleep(5)
      inFlight--
    }, { limit: 4 })

    expect(peak).toBe(4)
    expect(inFlight).toBe(0)
  })

  it('her öğeyi TAM BİR KEZ işler', async () => {
    const items = Array.from({ length: 25 }, (_, i) => `d${i}`)
    const seen = []
    await runWithConcurrency(items, async (d) => { await sleep(1); seen.push(d) }, { limit: 6 })

    expect(seen).toHaveLength(items.length)
    expect(new Set(seen).size).toBe(items.length)
  })

  it('YAVAŞ bir öğe arkasındakileri bekletmez — sonuçlar tamamlanma sırasında biter', async () => {
    // Asıl hata buydu: timeout alan tek sertifika (6 sn) sıralı döngüde tüm listeyi bekletiyordu.
    const done = []
    await runWithConcurrency(['yavas', 'hizli1', 'hizli2'], async (d) => {
      await sleep(d === 'yavas' ? 40 : 2)
      done.push(d)
    }, { limit: 3 })

    expect(done[0]).not.toBe('yavas')          // hızlılar önce bitti
    expect(done).toHaveLength(3)               // yavaş olan yine de tamamlandı
    expect(done.at(-1)).toBe('yavas')
  })

  it('shouldStop true olunca YENİ iş başlatılmaz (uçuştakiler tamamlanır)', async () => {
    let stop = false
    const processed = []
    const items = Array.from({ length: 50 }, (_, i) => i)

    const p = runWithConcurrency(items, async (i) => {
      processed.push(i)
      await sleep(2)
    }, { limit: 2, shouldStop: () => stop })

    await sleep(10)
    stop = true
    await p

    expect(processed.length).toBeGreaterThan(0)
    expect(processed.length).toBeLessThan(items.length)   // durduruldu
  })

  it('boş liste ve limit>öğe sayısı güvenli', async () => {
    const worker = vi.fn()
    await runWithConcurrency([], worker, { limit: 6 })
    expect(worker).not.toHaveBeenCalled()

    await runWithConcurrency(['a'], worker, { limit: 10 })
    expect(worker).toHaveBeenCalledTimes(1)
  })

  it('çağıranın dizisini değiştirmez', async () => {
    const items = ['a', 'b', 'c']
    await runWithConcurrency(items, async () => {}, { limit: 2 })
    expect(items).toEqual(['a', 'b', 'c'])
  })
})
