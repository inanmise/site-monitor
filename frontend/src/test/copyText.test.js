import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { copyText } from '../utils/copyText.js'

describe('copyText — üç kademeli kopyalama', () => {
  let origClipboard
  let origExec

  beforeEach(() => {
    origClipboard = navigator.clipboard
    origExec = document.execCommand
  })
  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: origClipboard })
    Object.defineProperty(document, 'execCommand', { writable: true, configurable: true, value: origExec })
  })

  function setClipboard(value) {
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value })
  }
  function setExec(fn) {
    Object.defineProperty(document, 'execCommand', { writable: true, configurable: true, value: fn })
  }

  it('1. kademe: clipboard API başarılıysa true döner ve fallback denenmez', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    const exec = vi.fn(() => true)
    setExec(exec)

    expect(await copyText('LIR-2026-000077')).toBe(true)
    expect(writeText).toHaveBeenCalledWith('LIR-2026-000077')
    expect(exec).not.toHaveBeenCalled()
  })

  it('2. kademe: clipboard reddederse textarea + execCommand denenir', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    const exec = vi.fn(() => true)
    setExec(exec)

    expect(await copyText('abc')).toBe(true)
    expect(exec).toHaveBeenCalledWith('copy')
    // Geçici textarea DOM'da bırakılmaz
    expect(document.querySelectorAll('textarea').length).toBe(0)
  })

  it('3. kademe: ikisi de olmazsa false döner (çağıran karar verir)', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    setExec(() => false)
    expect(await copyText('abc')).toBe(false)
  })

  it('clipboard hiç yoksa da throw etmez', async () => {
    setClipboard(undefined)
    setExec(() => false)
    await expect(copyText('abc')).resolves.toBe(false)
  })

  it('boş değer kopyalanmaz', async () => {
    const writeText = vi.fn()
    setClipboard({ writeText })
    expect(await copyText('')).toBe(false)
    expect(await copyText(null)).toBe(false)
    expect(writeText).not.toHaveBeenCalled()
  })
})
