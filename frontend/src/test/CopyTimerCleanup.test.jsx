import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from './test-utils.jsx'

/**
 * Kopyalama geri bildirimi zamanlayıcılarının unmount temizliği (2026-08-20 bellek denetimi).
 *
 * İki bileşen "kopyalandı" rozetini bir setTimeout ile geri alıyordu ama zamanlayıcıyı ref'te
 * tutmuyor ve unmount'ta temizlemiyordu. Kullanıcı kopyaladıktan hemen sonra modalı/paneli
 * kapatırsa zamanlayıcı ayakta kalıyor ve closure'ıyla bileşenin setState'ini canlı tutuyordu.
 * Doğru desen ui/CopyButton.jsx'te: ref + useEffect(() => () => clearTimeout(ref.current), []).
 *
 * Ölçüm doğrudan: kopyalamadan SONRA bekleyen zamanlayıcı VAR olmalı, unmount'tan sonra SIFIR.
 * (Düzeltme öncesi unmount sonrası 1 zamanlayıcı kalıyordu.)
 */

import SqlRowDetailModal from '../components/admin/SqlRowDetailModal.jsx'

describe('kopyalama zamanlayıcısı — unmount temizliği', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // jsdom'da navigator.clipboard yok; kopyalama yolu çalışsın diye stub.
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn(() => Promise.resolve()) },
    })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('SqlRowDetailModal: kopyaladıktan sonra unmount → bekleyen zamanlayıcı SIFIR', async () => {
    const { unmount } = render(
      <SqlRowDetailModal row={{ ad: 'deger' }} cols={['ad']} index={0} onClose={() => {}} />,
    )

    // Değerin yanındaki (metinsiz, salt-ikon) kopyalama butonu.
    // fireEvent kullanılır: userEvent sahte zamanlayıcılarla jsdom'da asılı kalıyor.
    const copyBtn = screen.getAllByRole('button').find(b => !b.textContent?.trim())
    expect(copyBtn).toBeTruthy()
    fireEvent.click(copyBtn)

    // clipboard.writeText().then(...) mikro-görevini boşalt → rozet zamanlayıcısı kurulur.
    await act(async () => { await Promise.resolve() })
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
  })
})
