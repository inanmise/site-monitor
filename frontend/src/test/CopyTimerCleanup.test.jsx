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

    // Değerin yanındaki salt-ikon kopyalama butonu — adı satırı (sütunu) içerir. (Kabuk artık ModalShell:
    // ilk metinsiz düğme pencerenin X'i olurdu, bu yüzden ada göre bulunur.)
    // fireEvent kullanılır: userEvent sahte zamanlayıcılarla jsdom'da asılı kalıyor.
    const copyBtn = screen.getByRole('button', { name: /(Copy value|Değeri kopyala).* — ad$/i })
    // Kabuğun (Radix Dialog) kendi 0 ms zamanlayıcıları sayımı kirletmesin: önce onları boşalt.
    await act(async () => { vi.advanceTimersByTime(1) })
    const baseline = vi.getTimerCount()
    fireEvent.click(copyBtn)

    // clipboard.writeText().then(...) mikro-görevini boşalt → rozet zamanlayıcısı kurulur.
    await act(async () => { await Promise.resolve() })
    expect(vi.getTimerCount()).toBeGreaterThan(baseline)

    unmount()
    // Radix FocusScope unmount'ta 0 ms'lik odak-iade zamanlayıcısı kurar — onu çalıştır (1 ms < rozetin 1200 ms'i).
    vi.advanceTimersByTime(1)

    expect(vi.getTimerCount()).toBe(0)
  })
})
