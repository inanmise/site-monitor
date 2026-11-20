/**
 * ÖNCEKİ AYIN RAPORU — paylaşılan, tembel, süre sınırlı okuma (2026-11-20, C1 / C3). "Geçen aydan getir" penceresi ve
 * yuvaların "Geçen ay" görünümü AYNI okumayı kullanır: rapor düzenleyicisi (ya da Görsel paneli) açıkken ilk istekte BİR KEZ
 * okunur; başarısız okuma bırakılır (sonraki deneme yeniden ister), başarılısı o düzenleyici yaşadıkça tutulur. Okuma iki
 * katmanlı sınırlıdır: istemcinin kendi zaman aşımı ({@link PREV_READ_TIMEOUT_MS}) + üst süre ({@link PREV_READ_DEADLINE_MS} —
 * gövde asılı kalsa bile söz biter). Modül düzeyinde önbellek YOK (başka kullanıcıya kalmaz); örnek düzenleyiciyle ölür.
 */

import { errorInfoOf } from '../../utils/errorMessages.js'

export const PREV_READ_TIMEOUT_MS = 20_000
export const PREV_READ_DEADLINE_MS = 25_000

/** Okuma neden olmadı (kalıcı; arayüz `te.prev.fail.<kod>`). */
export class PrevReadError extends Error {
  constructor(kind, message = '') {
    super(message || kind)
    this.name = 'PrevReadError'
    this.kind = kind          // 'timeout' | 'gone' | 'failed'
  }
}

/**
 * `read(id)` = `api.techErrors.report(id, { timeoutMs })` gibi bir işlev. Dönen nesne: `{ id, load() }` — `load()` raporun
 * `data`'sını (template, values, images, report…) verir ya da {@link PrevReadError} ile reddeder.
 */
export function createPrevReportLoader(id, read, { deadlineMs = PREV_READ_DEADLINE_MS } = {}) {
  let pending = null
  function once() {
    let timer
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new PrevReadError('timeout')), deadlineMs)
    })
    let call
    try {
      call = Promise.resolve(read(id))
    } catch (e) {
      clearTimeout(timer)
      return Promise.reject(new PrevReadError('failed', e?.message))
    }
    const p = Promise.race([
      call.then((res) => {
        if (res?.success && res.data && typeof res.data === 'object') return res.data
        const info = errorInfoOf(res) || {}
        const kind = info.status === 404 || info.status === 403 ? 'gone'
          : info.code === 'REQUEST_TIMEOUT' ? 'timeout' : 'failed'
        throw new PrevReadError(kind, typeof res?.error === 'string' ? res.error : '')
      }, (e) => { throw e instanceof PrevReadError ? e : new PrevReadError('failed', e?.message) }),
      deadline,
    ]).finally(() => clearTimeout(timer))
    return p
  }
  return {
    id,
    load() {
      if (pending) return pending
      const p = once()
      pending = p
      p.catch(() => { if (pending === p) pending = null })
      return p
    },
  }
}
