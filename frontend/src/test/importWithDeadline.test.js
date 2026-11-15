import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  CHUNK_TIMEOUT, IMPORT_DEADLINE_MS, importTimeoutError, importWithDeadline, isImportTimeout,
} from '../utils/importWithDeadline.js'
import { LANG_STORAGE_KEY, setSessionLang } from '../i18n/dateLocale.js'
import { ERROR_TEXTS, ApiError } from '../utils/errorMessages.js'
import { isUnsureResult } from '../utils/unsureResult.js'

/**
 * Süre sınırlı dinamik import (2026-11-15, prod kapısı incelemesi — "zaman aşımı olmayan bekleme yok"): asılı parça
 * isteği düğmeyi sonsuza dek meşgul bırakmaz; süre dolunca çevrilmiş, özgül hata; zamanlayıcı her yolda söker.
 */
describe('importWithDeadline', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // Dil oturum zorlamasıyla (localStorage yazımı jsdom'da sahte saate olay zamanlayıcısı koyar — sayımı bozardı)
    setSessionLang('en')
  })
  afterEach(() => {
    vi.useRealTimers()
    setSessionLang(null)
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
  })

  it('modül zamanında inerse aynen döner ve zamanlayıcı kalmaz', async () => {
    const base = vi.getTimerCount()
    const mod = { x: 1 }
    await expect(importWithDeadline(() => Promise.resolve(mod))).resolves.toBe(mod)
    expect(vi.getTimerCount()).toBe(base)
  })

  it('asılı parça: süre dolunca CHUNK_TIMEOUT kodlu, çevrilmiş ApiError; geç gelen ret işlenmemiş kalmaz', async () => {
    let lateReject
    const hung = new Promise((_, rej) => { lateReject = rej })
    const p = importWithDeadline(() => hung, 5000)
    let settled = null
    p.then(() => { settled = 'ok' }, (e) => { settled = e })
    await vi.advanceTimersByTimeAsync(4999)
    expect(settled).toBeNull()
    await vi.advanceTimersByTimeAsync(2)
    expect(settled).toBeInstanceOf(ApiError)
    expect(isImportTimeout(settled)).toBe(true)
    expect(settled.code).toBe(CHUNK_TIMEOUT)
    expect(settled.kind).toBe('timeout')
    expect(settled.status).toBe(0)
    expect(settled.message).toBe(ERROR_TEXTS.en.chunkTimeout.replace('{0}', '5'))
    expect(vi.getTimerCount()).toBe(0)
    // Süre dolduktan sonra gelen ret yarışın işleyicisinde kalır (vitest işlenmemiş ret görürse testi düşürür)
    lateReject(new Error('late'))
    await vi.advanceTimersByTimeAsync(10)
  })

  it('yükleyicinin kendi reddi (bayat paket) aynen geçer; eşzamanlı fırlatma da ret olur; zamanlayıcı söker', async () => {
    const stale = new TypeError('Failed to fetch dynamically imported module: /assets/x.js')
    await expect(importWithDeadline(() => Promise.reject(stale))).rejects.toBe(stale)
    expect(vi.getTimerCount()).toBe(0)
    const boom = new Error('sync')
    await expect(importWithDeadline(() => { throw boom })).rejects.toBe(boom)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('metin arayüz dilinde (oturum zorlaması önce gelir); geçersiz süre varsayılana düşer', async () => {
    setSessionLang(null)
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    expect(importTimeoutError(60_000).message).toBe(ERROR_TEXTS.tr.chunkTimeout.replace('{0}', '60'))
    setSessionLang('en')
    expect(importTimeoutError(60_000).message).toBe(ERROR_TEXTS.en.chunkTimeout.replace('{0}', '60'))
    const p = importWithDeadline(() => new Promise(() => {}), 0)
    let err = null
    p.catch((e) => { err = e })
    await vi.advanceTimersByTimeAsync(IMPORT_DEADLINE_MS - 1)
    expect(err).toBeNull()
    await vi.advanceTimersByTimeAsync(2)
    expect(isImportTimeout(err)).toBe(true)
  })
})

describe('isUnsureResult', () => {
  it('yanıt yok / durum 0 / 502–504 / zaman aşımı ve ağ kodları bilinmiyor; kesin ret ve başarı değil', () => {
    expect(isUnsureResult(null)).toBe(true)
    expect(isUnsureResult(undefined)).toBe(true)
    expect(isUnsureResult({ success: false, status: 0, code: 'REQUEST_TIMEOUT' })).toBe(true)
    expect(isUnsureResult({ success: false, code: 'REQUEST_TIMEOUT' })).toBe(true)
    for (const s of [502, 503, 504]) expect(isUnsureResult({ success: false, status: s })).toBe(true)
    expect(isUnsureResult(Object.defineProperty({ success: false }, 'errorInfo', { value: { status: 504 } }))).toBe(true)
    expect(isUnsureResult(new ApiError('x', { status: 0, code: 'NETWORK_ERROR' }))).toBe(true)
    expect(isUnsureResult({ success: false, code: 'OFFLINE' })).toBe(true)
    for (const s of [400, 403, 404, 409, 500]) expect(isUnsureResult({ success: false, status: s }), String(s)).toBe(false)
    expect(isUnsureResult({ success: true, status: 0 })).toBe(false)
    expect(isUnsureResult({ success: false, status: '' })).toBe(false)
  })
})
