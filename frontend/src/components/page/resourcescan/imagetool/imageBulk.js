import { abortableSleep, retryWaitMs } from '../../../techerror/yearArchive.js'
import { SERVER_CONCURRENCY } from './imageToolModel.js'

/**
 * TOPLU GÖRSEL İNDİRME koşucusu (2026-11-14, kullanıcı kararı 4) — kanal raporlarının yıl arşivi deseni, iki farkla:
 * sunucu kişi başına aynı anda en çok 2 görsel isteğine izin verir → en çok {@link SERVER_CONCURRENCY} şerit; her görsel
 * önce sunucudan alınır, sonra TARAYICIDA işlenir (`processOne`) ve hazır olur olmaz `onFile`'a verilir (akış ZIP'i).
 *
 * Sınırlar (sonsuz bekleme yok): 429 → sunucunun `Retry-After` önerisine uyulur (tek bekleme ≤ `maxWaitMs`), aynı görsel
 * en çok `maxRetries` kez yeniden istenir, koşudaki beklemelerin TOPLAMI ≤ `waitBudgetMs` — dolunca beklenmez, görsel
 * eksik sayılır. İlk 401 / 403'te DURULUR: başlanmamış görseller istenmez (`skipped`). Bir görselin hatası ötekileri
 * durdurmaz. İptal (`signal`) süren işi keser ve `{ aborted: true }` döner — çağıran hiçbir şey indirmez.
 *
 * Saf: React / API / motor bağımlılığı yok — çağıran `fetchOriginal` ve `processOne`'ı verir.
 */

/** 429'da öneri yoksa beklenen süre (sunucunun RESOURCE_IMAGE_BUSY önerisi 2 sn). */
export const BULK_RETRY_WAIT_MS = 2000
/** Aynı görsel için en çok bu kadar YENİDEN deneme. */
export const BULK_MAX_RETRIES = 4
/** Tek beklemenin üst sınırı (dakikalık sınır en geç 60 sn'de açılır). */
export const BULK_MAX_WAIT_MS = 60 * 1000
/** Bir koşudaki 429 beklemelerinin TOPLAM üst sınırı. */
export const BULK_WAIT_BUDGET_MS = 5 * 60 * 1000

/**
 * @param {object[]} plan  görsel nesneleri (sıra = eşleşme listesinin sırası)
 * @param {{
 *   fetchOriginal: (item, signal) => Promise<{ success: boolean, blob?: Blob, name?: string|null, type?: string|null, width?: number|null,
 *     height?: number|null, status?: number, code?: string, error?: string, retryAfter?: number, aborted?: boolean }>,
 *   processOne: (item, original, signal) => Promise<{ ok: true, result: object } | { ok: false, problem: object, aborted?: boolean }>,
 *   onFile?: (f: { index: number, item, original, result }) => any,
 *   onProgress?: (p: { done: number, total: number, waiting: boolean, waitMs?: number }) => void,
 *   signal?: AbortSignal, sleep?: Function, concurrency?: number, maxRetries?: number, waitMs?: number, maxWaitMs?: number,
 *   waitBudgetMs?: number }} opts
 * @returns {Promise<{ aborted: boolean, stopped: number|null,
 *   outcomes: ({ item, ok: true, entry: any, result: object } | { item, ok: false, reason: 'server'|'process'|'skipped', res?: object, problem?: object, status?: number })[] }>}
 */
export async function runImageBulk(plan, {
  fetchOriginal, processOne, onFile = null, onProgress = () => {}, signal = null, sleep = abortableSleep,
  concurrency = SERVER_CONCURRENCY, maxRetries = BULK_MAX_RETRIES, waitMs = BULK_RETRY_WAIT_MS, maxWaitMs = BULK_MAX_WAIT_MS,
  waitBudgetMs = BULK_WAIT_BUDGET_MS,
} = {}) {
  const list = Array.isArray(plan) ? plan : []
  const total = list.length
  const outcomes = new Array(total).fill(null)
  const ABORTED = { aborted: true, stopped: null, outcomes: [] }
  let next = 0
  let done = 0
  let waitedMs = 0
  let waitingLanes = 0
  let stopped = null
  let fatal = null
  const report = (extra = {}) => { try { onProgress({ done, total, waiting: waitingLanes > 0, ...extra }) } catch { /* ilerleme bildirimi işi durdurmaz */ } }

  async function fetchWithRetry(item) {
    for (let retries = 0; ; retries += 1) {
      let res
      try {
        res = await fetchOriginal(item, signal)
      } catch (e) {
        res = { success: false, status: 0, error: e?.message || null, aborted: e?.name === 'AbortError' }
      }
      if (signal?.aborted || res?.aborted) return { aborted: true }
      if (res?.success) return { res }
      if (res?.status === 401 || res?.status === 403) return { res, stop: res.status }
      const wait = res?.status === 429 ? retryWaitMs(res, waitMs, maxWaitMs) : 0
      if (res?.status === 429 && retries < maxRetries && waitedMs + wait <= waitBudgetMs) {
        waitedMs += wait
        waitingLanes += 1
        report({ waitMs: wait })
        const slept = await sleep(wait, signal)
        waitingLanes -= 1
        if (!slept || signal?.aborted) return { aborted: true }
        report()
        continue
      }
      return { res }
    }
  }

  async function lane() {
    // Sınırlı döngü: her tur bir görsel tüketir (`next` yalnız artar)
    while (next < total && stopped === null && !fatal) {
      if (signal?.aborted) return
      const index = next
      next += 1
      const item = list[index]
      const got = await fetchWithRetry(item)
      if (got.aborted || signal?.aborted) return
      if (got.stop) {
        stopped = got.stop
        outcomes[index] = { item, ok: false, reason: 'server', res: got.res }
      } else if (!got.res?.success) {
        outcomes[index] = { item, ok: false, reason: 'server', res: got.res }
      } else {
        let out
        try {
          out = await processOne(item, got.res, signal)
        } catch (e) {
          out = { ok: false, problem: { kind: 'engine', code: 'other', params: {}, technical: e?.message || null } }
        }
        if (signal?.aborted || out?.aborted) return
        if (out?.ok) {
          try {
            const entry = onFile ? await onFile({ index, item, original: got.res, result: out.result }) : null
            outcomes[index] = { item, ok: true, entry, result: out.result }
          } catch (e) {
            fatal = e || new Error('The archive could not be written.')
            return
          }
        } else {
          outcomes[index] = { item, ok: false, reason: 'process', problem: out?.problem || { kind: 'engine', code: 'other', params: {} } }
        }
      }
      done += 1
      report()
    }
  }

  report()
  const lanes = []
  const laneCount = Math.max(1, Math.min(Number(concurrency) || 1, 4, total || 1))
  for (let i = 0; i < laneCount; i += 1) lanes.push(lane())
  await Promise.all(lanes)
  if (fatal) throw fatal
  if (signal?.aborted) return ABORTED
  // Durdurulan koşuda (401 / 403) başlanmamış görseller: istenmedi
  for (let i = 0; i < total; i += 1) {
    if (!outcomes[i]) outcomes[i] = { item: list[i], ok: false, reason: 'skipped', status: stopped }
  }
  return { aborted: false, stopped, outcomes }
}
