import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'

/**
 * E-POSTA CTA DERİN BAĞLANTISI ({@code ?monitor=<id>}) — yedi izleme sayfasında birebir aynı
 * on satırdı ve <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce ölçüldü: "bir kez" guard'ını
 * kaldırmak 818 testin HİÇBİRİNİ kırmadı.
 *
 * O guard olmadan, liste her yenilendiğinde (60 sn'lik yoklama) etki yeniden koşar ve
 * kullanıcının KAPATTIĞI detay modalı kendiliğinden geri açılır — pratikte kapatılamayan bir
 * pencere. Alarm mailindeki bağlantıya tıklayan kişi tam olarak bu ekranda kalır.
 */
const MONITORS = [{ id: 7, name: 'a' }, { id: 42, name: 'b' }]

const setSearch = (qs) => window.history.replaceState({}, '', qs ? `/?${qs}` : '/')

describe('useMonitorDeepLink', () => {
  beforeEach(() => setSearch(''))
  afterEach(() => setSearch(''))

  it('eşleşen monitörü açar', () => {
    setSearch('monitor=42')
    const open = vi.fn()
    renderHook(({ list }) => useMonitorDeepLink(list, open), { initialProps: { list: MONITORS } })
    expect(open).toHaveBeenCalledTimes(1)
    expect(open.mock.calls[0][0].id).toBe(42)
  })

  it('URL id STRING, monitör id SAYI — gevşek karşılaştırma şart', () => {
    setSearch('monitor=7')
    const open = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open))
    // String() sarmalaması olmasaydı '7' === 7 false döner, hiçbir bağlantı açılmazdı
    expect(open).toHaveBeenCalledWith(MONITORS[0])
  })

  it('YALNIZ BİR KEZ açar — liste yenilenince kapatılan modal geri AÇILMAZ', () => {
    setSearch('monitor=7')
    const open = vi.fn()
    const { rerender } = renderHook(({ list }) => useMonitorDeepLink(list, open),
      { initialProps: { list: MONITORS } })

    // 60 sn'lik yoklama: yeni dizi referansı gelir (aynı içerik)
    rerender({ list: [...MONITORS] })
    rerender({ list: [{ id: 7, name: 'a' }, { id: 42, name: 'b' }] })

    expect(open).toHaveBeenCalledTimes(1)
  })

  it('liste HENÜZ boşken guard harcanmaz — veri gelince açılır', () => {
    setSearch('monitor=7')
    const open = vi.fn()
    const { rerender } = renderHook(({ list }) => useMonitorDeepLink(list, open),
      { initialProps: { list: [] } })       // ilk render: veri yok
    expect(open).not.toHaveBeenCalled()

    rerender({ list: MONITORS })            // veri geldi
    expect(open).toHaveBeenCalledTimes(1)
  })

  it('param yoksa ya da id listede yoksa hiçbir şey açılmaz', () => {
    const open = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open))
    expect(open).not.toHaveBeenCalled()

    setSearch('monitor=999')
    const open2 = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open2))
    expect(open2).not.toHaveBeenCalled()
  })
})
