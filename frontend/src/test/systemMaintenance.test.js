import { describe, it, expect, beforeEach, vi } from 'vitest'
import {
  MAINTENANCE_EVENT, MAINTENANCE_STORAGE_KEY, clientPhase, clockText, dateTimeText, dismiss, isDismissed,
  isMaintenancePayload, lastWindow, maintenanceSignaled, messageFor, onMaintenance, resetMaintenanceSignal,
  secondsUntil, serverOffset, signalMaintenance, timeText, windowText,
} from '../utils/systemMaintenance.js'

/**
 * Sistem Bakım Modu — istemci modeli (2026-10-02): aşamalar SUNUCU saatine göre, İstanbul saatiyle biçimlendirme,
 * tek seferlik oturum kesimi sinyali + sekmeler arası duyuru, şerit kapatma (pencere + sürüm başına).
 */
const START = Date.parse('2026-10-02T19:00:00Z')   // İstanbul 22:00
const block = (over = {}) => ({
  state: 'warning', id: 7, revision: 1, warn_minutes: 10, announce_hours: 24,
  start_at: '2026-10-02T19:00:00Z', end_at: '2026-10-02T20:00:00Z', message_tr: 'DB yükseltme', message_en: 'DB upgrade', ...over,
})

describe('clientPhase — sunucu saatine göre', () => {
  it('duyuru → uyarı → son 60 sn → aktif → bitti', () => {
    const b = block({ state: 'announced' })
    expect(clientPhase(b, START - 3 * 3600e3)).toBe('announced')
    expect(clientPhase(b, START - 10 * 60e3)).toBe('warning')
    expect(clientPhase(b, START - 61e3)).toBe('warning')
    expect(clientPhase(b, START - 60e3)).toBe('final')
    expect(clientPhase(b, START - 1e3)).toBe('final')
    expect(clientPhase(b, START)).toBe('active')
    expect(clientPhase(b, START + 3600e3)).toBe('none')
  })
  it('sunucu "bitti" diyorsa saat pencerenin İÇİNDE olsa da none — geri sayım/çıkış asla tetiklenmez', () => {
    // İstemci saati geride (ya da sapma ölçülmeden): saat başlangıç-bitiş arasında görünüyor
    const b = block({ state: 'ended' })
    expect(clientPhase(b, START + 60e3)).toBe('none')
    expect(clientPhase(b, START - 30e3)).toBe('none')
  })
  it('bakım yok / bozuk blok → none', () => {
    expect(clientPhase(null, START)).toBe('none')
    expect(clientPhase({ state: 'none' }, START)).toBe('none')
    expect(clientPhase({ state: 'active' }, START)).toBe('none')
  })
  it('sunucu saati farkı: istemci saati 5 dk ileride olsa da aşama sunucuya göre', () => {
    const received = START - 20 * 60e3 + 5 * 60e3        // istemci 5 dk ileri
    const offset = serverOffset('2026-10-02T18:40:00Z', received)
    expect(offset).toBe(-5 * 60e3)
    expect(clientPhase(block(), received + offset)).toBe('announced')
    expect(secondsUntil(block().start_at, received + offset)).toBe(20 * 60)
  })
})

describe('biçimlendirme — İstanbul saati', () => {
  it('pencere metni aynı gün / gün aşımı; HH:mm; tarih saat', () => {
    expect(windowText(block())).toBe('02.10.2026 22:00 – 23:00')
    expect(windowText(block({ end_at: '2026-10-02T22:30:00Z' }))).toBe('02.10.2026 22:00 – 03.10.2026 01:30')
    expect(timeText('2026-10-02T20:00:00Z')).toBe('23:00')
    expect(dateTimeText('2026-10-02T19:05:00Z')).toBe('02.10.2026 22:05')
    expect(clockText(65)).toBe('1:05')
    expect(clockText(3725)).toBe('1:02:05')
  })
  it('mesaj dile göre; boşsa null', () => {
    expect(messageFor(block(), 'tr')).toBe('DB yükseltme')
    expect(messageFor(block(), 'en')).toBe('DB upgrade')
    expect(messageFor(block({ message_en: '  ' }), 'en')).toBeNull()
  })
})

describe('oturum kesimi sinyali', () => {
  beforeEach(() => {
    resetMaintenanceSignal()
    localStorage.clear()
    sessionStorage.clear()
  })
  it('gövde tanıma: code ya da error_code MAINTENANCE', () => {
    expect(isMaintenancePayload({ code: 'MAINTENANCE' })).toBe(true)
    expect(isMaintenancePayload({ error_code: 'MAINTENANCE' })).toBe(true)
    expect(isMaintenancePayload({ code: 'ACCOUNT_INACTIVE' })).toBe(false)
    expect(isMaintenancePayload(null)).toBe(false)
  })
  it('sayfa ömründe TEK sinyal; öteki sekmelere localStorage duyurusu; pencere bilgisi giriş sayfasına taşınır', () => {
    const seen = vi.fn()
    window.addEventListener(MAINTENANCE_EVENT, seen)
    expect(signalMaintenance(block({ state: 'active' }))).toBe(true)
    expect(signalMaintenance(block())).toBe(false)
    window.removeEventListener(MAINTENANCE_EVENT, seen)
    expect(seen).toHaveBeenCalledTimes(1)
    expect(maintenanceSignaled()).toBe(true)
    expect(JSON.parse(localStorage.getItem(MAINTENANCE_STORAGE_KEY)).maintenance.state).toBe('active')
    expect(lastWindow().start_at).toBe('2026-10-02T19:00:00Z')
  })
  it('öteki sekmenin duyurusu bu sekmede dinleyiciyi tetikler ve sinyali "verilmiş" sayar', () => {
    const handler = vi.fn()
    const off = onMaintenance(handler)
    window.dispatchEvent(new StorageEvent('storage', {
      key: MAINTENANCE_STORAGE_KEY, newValue: JSON.stringify({ at: 1, maintenance: block({ state: 'active' }) }),
    }))
    expect(handler).toHaveBeenCalledWith('remote', expect.objectContaining({ state: 'active' }))
    expect(maintenanceSignaled()).toBe(true)
    expect(signalMaintenance(block())).toBe(false)
    off()
  })
})

describe('şerit kapatma', () => {
  beforeEach(() => localStorage.clear())
  it('pencere + sürüm başına; saat değişirse (sürüm artar) yeniden görünür', () => {
    expect(isDismissed('announce', block())).toBe(false)
    dismiss('announce', block())
    expect(isDismissed('announce', block())).toBe(true)
    expect(isDismissed('warning', block())).toBe(false)
    expect(isDismissed('announce', block({ revision: 2 }))).toBe(false)
  })
})

describe('Etkinliklerim — bakımda reddedilen giriş gerekçesi', () => {
  it('LOGIN_FAILED BLOCKED + MAINTENANCE kodu "oran sınırı" DEĞİL, bakım metniyle', async () => {
    const { reasonText } = await import('../components/myactivity/activityModel.js')
    const t = (k) => k
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED',
      failure_reason: 'MAINTENANCE: sistem bakımı — yalnız global yöneticiler giriş yapabilir (PASSWORD)' }, t))
      .toBe('lastLogin.reasonMaintenance')
    expect(reasonText({ event_type: 'LOGIN_FAILED', outcome: 'BLOCKED', failure_reason: 'Rate limited: x' }, t))
      .toBe('myact.reason.rateLimited')
  })
})
