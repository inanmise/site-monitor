import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useMonitorDeepLink } from '../hooks/useMonitorDeepLink.js'
import { consumeNocFieldFocus } from '../components/noc/forms/nocFieldFocus.js'

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

/**
 * 2026-09-28 (7/24 Kapsamı → izleme): istek MOUNT'ta okunur, bulunamayan kimlik bildirilir, aynı sekmede ikinci
 * bağlantı işlenir, `open=noc` düzenleme formunu açar. Sayfa düzeyindeki karşılıkları: monitorPagesDeepLink.test.jsx.
 */
describe('useMonitorDeepLink — 7/24 Kapsamı sözleşmesi', () => {
  beforeEach(() => setSearch(''))
  afterEach(() => setSearch(''))

  it("istek MOUNT'ta okunur: sayfanın URL senkronu `monitor`'ü liste gelmeden silse de açılır (yavaş liste yarışı)", () => {
    setSearch('tab=http&monitor=42')
    const open = vi.fn()
    const { rerender } = renderHook(({ list, loaded }) => useMonitorDeepLink(list, open, { loaded }),
      { initialProps: { list: [], loaded: false } })
    setSearch('tab=http')                       // useUrlQuerySync 300 ms'de siler (pencere henüz kapalı)
    rerender({ list: MONITORS, loaded: true })  // liste 300 ms'den GEÇ geldi
    expect(open).toHaveBeenCalledTimes(1)
    expect(open.mock.calls[0][0].id).toBe(42)
  })

  it('liste yüklendi ve kimlik yok → onNotFound(id) BİR KEZ; boş liste de "yüklendi" sayılır (loaded verilince)', () => {
    setSearch('monitor=999')
    const open = vi.fn()
    const miss = vi.fn()
    const { rerender } = renderHook(({ list, loaded }) => useMonitorDeepLink(list, open, { loaded, onNotFound: miss }),
      { initialProps: { list: [], loaded: false } })
    expect(miss).not.toHaveBeenCalled()         // yüklenmeden karar yok
    rerender({ list: [], loaded: true })        // erişilebilir hiç izleme yok
    expect(miss).toHaveBeenCalledTimes(1)
    expect(miss).toHaveBeenCalledWith('999')
    rerender({ list: [...MONITORS], loaded: true })   // yoklama tazeledi → tekrar bildirilmez
    expect(miss).toHaveBeenCalledTimes(1)
    expect(open).not.toHaveBeenCalled()
  })

  it('yükleme hatasında (loaded=false) bekler, sonraki başarılı yüklemede açar', () => {
    setSearch('monitor=7')
    const open = vi.fn()
    const miss = vi.fn()
    const { rerender } = renderHook(({ list, loaded }) => useMonitorDeepLink(list, open, { loaded, onNotFound: miss }),
      { initialProps: { list: [], loaded: false } })
    rerender({ list: [], loaded: false })       // hata: liste boş, loaded=false (loadError)
    expect(miss).not.toHaveBeenCalled()
    rerender({ list: MONITORS, loaded: true })
    expect(open).toHaveBeenCalledWith(MONITORS[0])
    expect(miss).not.toHaveBeenCalled()
  })

  it('aynı sekmede ikinci bağlantı (sm:tab-params { monitor }) İKİNCİ izlemeyi açar; bulunamayanı bildirir', () => {
    setSearch('monitor=7')
    const open = vi.fn()
    const miss = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open, { loaded: true, onNotFound: miss }))
    expect(open).toHaveBeenLastCalledWith(MONITORS[0])
    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { monitor: '42' } })) })
    expect(open).toHaveBeenCalledTimes(2)
    expect(open).toHaveBeenLastCalledWith(MONITORS[1])
    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { sec: 'x' } })) })   // ilgisiz olay
    expect(open).toHaveBeenCalledTimes(2)
    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { monitor: 5 } })) })
    expect(miss).toHaveBeenCalledWith('5')
  })

  it('open=noc + yetki → DÜZENLEME formu (detay değil), 7/24 alanına odak isteği; `open` adresten silinir, `monitor` kalır', () => {
    setSearch('tab=http&monitor=42&open=noc')
    const open = vi.fn()
    const edit = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open, { loaded: true, onEdit: edit, canEdit: () => true, nocType: 'HTTP' }))
    expect(edit).toHaveBeenCalledWith(MONITORS[1])
    expect(open).not.toHaveBeenCalled()
    expect(consumeNocFieldFocus('PING')).toBe(false)   // başka türün alanı tüketemez
    expect(consumeNocFieldFocus('HTTP')).toBe(true)
    expect(window.location.search).toBe('?tab=http&monitor=42')
  })

  it("open=noc ama yetki yok → detay açılır (paylaşılan bağlantıyı açan başkası 403'lü form görmez); open yine silinir", () => {
    setSearch('tab=http&monitor=7&open=noc')
    const open = vi.fn()
    const edit = vi.fn()
    renderHook(() => useMonitorDeepLink(MONITORS, open, { loaded: true, onEdit: edit, canEdit: () => false, nocType: 'HTTP' }))
    expect(edit).not.toHaveBeenCalled()
    expect(open).toHaveBeenCalledWith(MONITORS[0])
    expect(consumeNocFieldFocus('HTTP')).toBe(false)
    expect(window.location.search).toBe('?tab=http&monitor=7')
  })
})
