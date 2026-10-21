import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render } from './test-utils.jsx'

// Sert yönlendirme jsdom'da taklit edilemez — tek noktadan geçer ve burada sahtelenir.
vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api } from '../api/client.js'
import { assignLocation } from '../utils/accountInactive.js'
import {
  USERNAME_PREFIX_EVENT, USERNAME_PREFIX_STORAGE_KEY, lastUsernamePrefixes, resetUsernamePrefixSignal,
} from '../utils/usernamePrefix.js'
import AccountInactiveDialog from '../components/AccountInactiveDialog.jsx'

/**
 * Kullanıcı adı ön eki sinyali (2026-10-21): 401 USERNAME_PREFIX_NOT_ALLOWED → pencere sinyali (YÖNLENDİRME YOK), eşzamanlı
 * 401'ler tek sinyal, izinli ön ekler sinyale taşınır; pencere ön ek kipinde kendi kancalarıyla, kapatılamaz, 0'da çıkış.
 */
const BODY = {
  success: false, code: 'USERNAME_PREFIX_NOT_ALLOWED', error_code: 'USERNAME_PREFIX_NOT_ALLOWED',
  error: 'Only usernames that start with N can sign in.', allowed_prefixes: ['N'],
}

function mockFetch(body, status) {
  global.fetch = vi.fn().mockResolvedValue({ status, ok: status < 400, json: () => Promise.resolve(body) })
}

describe('api/client — 401 USERNAME_PREFIX_NOT_ALLOWED sinyali', () => {
  let events
  let off
  beforeEach(() => {
    resetUsernamePrefixSignal()
    vi.mocked(assignLocation).mockClear()
    sessionStorage.setItem('sm.session.active', '1')
    localStorage.removeItem(USERNAME_PREFIX_STORAGE_KEY)
    events = 0
    const h = () => { events++ }
    window.addEventListener(USERNAME_PREFIX_EVENT, h)
    off = () => window.removeEventListener(USERNAME_PREFIX_EVENT, h)
  })
  afterEach(() => {
    off()
    sessionStorage.clear()
  })

  it('pencere sinyali, YÖNLENDİRME YOK, oturum bayrağı silinir, ön ekler taşınır; eşzamanlı 401 tek sinyal', async () => {
    mockFetch(BODY, 401)
    const results = await Promise.all([api.sessionPing('dashboard'), api.getStats()])
    expect(results).toEqual([null, null])
    expect(events).toBe(1)
    expect(assignLocation).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
    expect(lastUsernamePrefixes()).toEqual(['N'])
    expect(JSON.parse(localStorage.getItem(USERNAME_PREFIX_STORAGE_KEY)).prefixes).toEqual(['N'])
  })
})

describe('AccountInactiveDialog — ön ek kipi', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('kendi kancaları ve metni (ön ekleri adlandırır); 10 → 0, 0\'da çıkış BİR kez; Escape kapatmaz', () => {
    const onExpire = vi.fn()
    render(<AccountInactiveDialog variant="prefix" open prefixes={['N', 'T2']} onExpire={onExpire} />)
    const dlg = document.querySelector('[data-slot="username-prefix-dialog"]')
    expect(dlg).not.toBeNull()
    expect(dlg).toHaveTextContent(/N, T2/)
    expect(document.querySelector('[data-slot="account-inactive-dialog"]')).toBeNull()
    fireEvent.keyDown(dlg, { key: 'Escape' })
    expect(document.querySelector('[data-slot="username-prefix-dialog"]')).not.toBeNull()
    act(() => { vi.advanceTimersByTime(10_000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(3_000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('ön ek listesi yoksa genel metin', () => {
    render(<AccountInactiveDialog variant="prefix" open prefixes={[]} onExpire={() => {}} />)
    expect(document.querySelector('[data-slot="username-prefix-dialog"]')).toHaveTextContent(/no longer sign in|artık giriş yapamıyor/)
  })
})
