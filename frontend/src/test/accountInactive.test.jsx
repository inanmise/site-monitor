import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen } from './test-utils.jsx'

// Sert yönlendirme jsdom'da taklit edilemez (location.assign değiştirilemez) — tek noktadan geçer ve burada sahtelenir.
vi.mock('../utils/accountInactive.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, assignLocation: vi.fn() }
})

import { api } from '../api/client.js'
import {
  ACCOUNT_INACTIVE_EVENT, ACCOUNT_INACTIVE_STORAGE_KEY, assignLocation, onAccountInactive, resetAccountInactiveSignal,
} from '../utils/accountInactive.js'
import AccountInactiveDialog from '../components/AccountInactiveDialog.jsx'

/**
 * Pasif hesap sinyali (2026-10-02, kullanıcı kararı): 401 ACCOUNT_INACTIVE → bloklayan pencere (yönlendirme YOK), eşzamanlı
 * 401'ler tek sinyal, sıradan 401 eskisi gibi /?session=expired; pencere 10 → 0 sayar, kapatılamaz, bitince çıkış.
 */

const INACTIVE_BODY = {
  success: false, code: 'ACCOUNT_INACTIVE', error_code: 'ACCOUNT_INACTIVE',
  error: 'Your account is inactive; sign-in is not allowed. Contact your administrator.',
}

function mockFetch(body, status) {
  global.fetch = vi.fn().mockResolvedValue({ status, ok: status < 400, json: () => Promise.resolve(body) })
}

describe('api/client — 401 ACCOUNT_INACTIVE sinyali', () => {
  let events
  let off
  beforeEach(() => {
    resetAccountInactiveSignal()
    vi.mocked(assignLocation).mockClear()
    sessionStorage.setItem('sm.session.active', '1')
    localStorage.removeItem(ACCOUNT_INACTIVE_STORAGE_KEY)
    events = 0
    off = (() => { const h = () => { events++ }; window.addEventListener(ACCOUNT_INACTIVE_EVENT, h); return () => window.removeEventListener(ACCOUNT_INACTIVE_EVENT, h) })()
  })
  afterEach(() => {
    off()
    sessionStorage.clear()
  })

  it('ACCOUNT_INACTIVE 401: pencere sinyali, YÖNLENDİRME YOK, oturum bayrağı silinir, öteki sekmelere duyuru', async () => {
    mockFetch(INACTIVE_BODY, 401)

    const r = await api.sessionPing('dashboard')

    expect(r).toBeNull()
    expect(events).toBe(1)
    expect(assignLocation).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('sm.session.active')).toBeNull()
    expect(localStorage.getItem(ACCOUNT_INACTIVE_STORAGE_KEY)).toBeTruthy()
  })

  it('aynı anda düşen birden çok ACCOUNT_INACTIVE 401 TEK sinyal üretir; ardından gelen sıradan 401 de /?session=expired\'a götürmez', async () => {
    mockFetch(INACTIVE_BODY, 401)
    await Promise.all([api.getCertificates(), api.getStats(), api.sessionPing(), api.me.inbox()])
    expect(events).toBe(1)

    mockFetch({ success: false, error: 'Unauthorized' }, 401)
    await api.getCertificates()
    expect(assignLocation).not.toHaveBeenCalled()
  })

  it('sıradan 401 (oturum düştü) eskisi gibi /?session=expired — pencere sinyali yok', async () => {
    mockFetch({ success: false, error: 'Session superseded' }, 401)

    await api.getCertificates()

    expect(assignLocation).toHaveBeenCalledWith('/?session=expired')
    expect(events).toBe(0)
  })

  it('açılış (oturum bayrağı yok) 401 ACCOUNT_INACTIVE: yönlendirme yok, sinyal yine yayılır (App giriş sayfası bildirimine çevirir)', async () => {
    sessionStorage.removeItem('sm.session.active')
    mockFetch(INACTIVE_BODY, 401)
    expect(await api.getMe()).toBeNull()
    expect(events).toBe(1)
    expect(assignLocation).not.toHaveBeenCalled()
  })
})

describe('onAccountInactive — sekmeler arası duyuru', () => {
  beforeEach(() => resetAccountInactiveSignal())

  it('öteki sekmenin duyurusu (storage olayı) dinleyiciyi "remote" ile çağırır — bir kez', () => {
    const handler = vi.fn()
    const off = onAccountInactive(handler)
    const ev = (v) => new StorageEvent('storage', { key: ACCOUNT_INACTIVE_STORAGE_KEY, newValue: v })
    window.dispatchEvent(ev('1'))
    window.dispatchEvent(ev('2'))
    window.dispatchEvent(new StorageEvent('storage', { key: 'baska.anahtar', newValue: 'x' }))
    off()
    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith('remote')
  })
})

describe('AccountInactiveDialog — bloklayan geri sayım', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('başlık + açıklama + büyük 10 → 0 geri sayım; 0\'da çıkış BİR kez', () => {
    const onExpire = vi.fn()
    render(<AccountInactiveDialog open onExpire={onExpire} />)

    const dlg = screen.getByRole('alertdialog')
    expect(dlg).toHaveTextContent(/deactivated|pasife alındı/i)
    const counter = document.querySelector('[data-slot="account-inactive-countdown"]')
    expect(counter).toHaveTextContent('10')

    act(() => { vi.advanceTimersByTime(4000) })
    expect(counter).toHaveTextContent('6')
    expect(onExpire).not.toHaveBeenCalled()

    act(() => { vi.advanceTimersByTime(6000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(5000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('"Şimdi çıkış yap" hemen çıkış yapar; Escape pencereyi KAPATMAZ', () => {
    const onExpire = vi.fn()
    render(<AccountInactiveDialog open onExpire={onExpire} />)

    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()

    fireEvent.click(document.querySelector('[data-slot="account-inactive-logout"]'))
    expect(onExpire).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(11000) })
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('kapalıyken hiçbir şey çizmez ve saymaz', () => {
    const onExpire = vi.fn()
    render(<AccountInactiveDialog open={false} onExpire={onExpire} />)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    act(() => { vi.advanceTimersByTime(20000) })
    expect(onExpire).not.toHaveBeenCalled()
  })
})
