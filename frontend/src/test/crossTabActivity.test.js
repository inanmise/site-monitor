import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  ACTIVITY_EVENT, ACTIVITY_PREFIX, ACTIVITY_THROTTLE_MS, FUTURE_SLACK_MS, POINTER_MOVE_MIN_PX, activityUserKey, bindActivityUser,
  createPointerMoveFilter,
  boundActivityUser, clearOwnActivityStamp, latestOtherActivity, markActivity, onOtherActivity, ownActivityKey,
  parseActivityStamp, resetActivityThrottleForTests, writeActivityStamp,
} from '../utils/crossTabActivity.js'
import { OWNER_KEY } from '../utils/personalStorage.js'

// Sekmeler arası etkinlik damgası (2026-10-18, Görsel paneli): her sekme kendi anahtarına en çok 15 sn'de bir yazar;
// öteki sekmenin damgası okunurken kısma payı eklenir (erken çıkış yok), "şimdi"yi geçmez; kendi damgası sayılmaz.
// İnceleme 2026-10-18: damga kullanıcıya bağlı ({u, t}); ileri tarihli damga yok sayılır ve silinir; saat geri gidince
// kısma sıfırlanır; çıkışta kendi damgası silinir; tarayıcıdaki son giriş başka kullanıcınınsa öteki damgalar sayılmaz.
const ME = 'ayse.yilmaz'
const stamp = (t, user = ME) => JSON.stringify({ u: activityUserKey(user), t })
const other = (name, t, user = ME) => localStorage.setItem(`${ACTIVITY_PREFIX}${name}`, stamp(t, user))

beforeEach(() => {
  localStorage.clear()
  resetActivityThrottleForTests()
  localStorage.setItem(OWNER_KEY, ME)   // claimPersonalStorage: tarayıcıdaki son giriş
  bindActivityUser(ME)
})

describe('sekmeler arası etkinlik', () => {
  it('kendi damgası 15 sn kısılır, kullanıcı özetini taşır (ad yazılmaz) ve öteki-sekme okumasına girmez', () => {
    const t0 = 1_000_000
    expect(writeActivityStamp(t0)).toBe(true)
    const raw = localStorage.getItem(ownActivityKey())
    expect(parseActivityStamp(raw)).toEqual({ u: activityUserKey(ME), t: t0 })
    expect(raw).not.toContain(ME)
    expect(writeActivityStamp(t0 + 5000)).toBe(false)
    expect(parseActivityStamp(localStorage.getItem(ownActivityKey())).t).toBe(t0)
    expect(writeActivityStamp(t0 + ACTIVITY_THROTTLE_MS)).toBe(true)
    expect(latestOtherActivity(t0 + 20_000)).toBe(0)
  })

  it('öteki sekmelerin en yenisi + kısma payı; şimdiyi geçmez; bozuk damga sayılmaz', () => {
    const now = 5_000_000
    other('panel', now - 60_000)
    other('diger', now - 20_000)
    expect(latestOtherActivity(now)).toBe(now - 20_000 + ACTIVITY_THROTTLE_MS)
    other('panel', now - 2000)
    expect(latestOtherActivity(now)).toBe(now)
    localStorage.setItem(`${ACTIVITY_PREFIX}bozuk`, 'x')
    expect(latestOtherActivity(now)).toBe(now)
  })

  it('ileri tarihli damga ("şimdi + 5 sn"den ileri: çift açılış saati, sanal makine geri yükleme) yok sayılır VE silinir', () => {
    const now = 7_000_000
    other('gelecek', now + 3 * 3600_000)
    expect(latestOtherActivity(now)).toBe(0)
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}gelecek`)).toBeNull()
    // Sınırın içindeki küçük fark (aynı makinedeki sekmeler) sayılır, ama "şimdi"yi geçmez
    other('yakin', now + FUTURE_SLACK_MS - 1)
    expect(latestOtherActivity(now)).toBe(now)
    other('sinirdisi', now + FUTURE_SLACK_MS + 1)
    expect(latestOtherActivity(now)).toBe(now)
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}sinirdisi`)).toBeNull()
    // Yazarken de silinir
    other('gelecek2', now + 3 * 3600_000)
    writeActivityStamp(now, true)
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}gelecek2`)).toBeNull()
  })

  it('saat geri gidince (son yazım "gelecekte" kaldı) kısma sıfırlanır: kendi damgası yeniden yazılır', () => {
    const later = 9_000_000 + 3 * 3600_000
    expect(writeActivityStamp(later)).toBe(true)
    const back = 9_000_000
    expect(writeActivityStamp(back)).toBe(true)
    expect(parseActivityStamp(localStorage.getItem(ownActivityKey())).t).toBe(back)
    expect(writeActivityStamp(back + 1000)).toBe(false)   // kısma yine çalışır
  })

  it('yazarken bir günden eski / bozuk / eski biçimli (yalın sayı) damgalar temizlenir; başka anahtara dokunulmaz', () => {
    const now = 9_000_000_000
    other('eski', now - 25 * 3600_000)
    localStorage.setItem(`${ACTIVITY_PREFIX}bozuk`, 'abc')
    localStorage.setItem(`${ACTIVITY_PREFIX}sayi`, String(now - 1000))
    other('taze', now - 1000)
    localStorage.setItem('baska.anahtar', '1')
    writeActivityStamp(now, true)
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}eski`)).toBeNull()
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}bozuk`)).toBeNull()
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}sayi`)).toBeNull()
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}taze`)).not.toBeNull()
    expect(localStorage.getItem('baska.anahtar')).toBe('1')
  })

  it('yalnız AYNI kullanıcının damgası sayılır (başka kullanıcının sekmesi bu sekmeyi açık tutmaz)', () => {
    const now = 3_000_000
    other('mehmet', now - 1000, 'mehmet.demir')
    expect(latestOtherActivity(now)).toBe(0)
    other('ben', now - 1000)
    expect(latestOtherActivity(now)).toBe(now)
  })

  it('tarayıcıdaki son giriş başka kullanıcınınsa (ya da çıkış yapıldıysa) hiçbir öteki damga sayılmaz', () => {
    const now = 3_000_000
    other('ben', now - 1000)
    localStorage.setItem(OWNER_KEY, 'mehmet.demir')   // B 1. sekmede girdi; bu sekme (A) ölü
    expect(latestOtherActivity(now)).toBe(0)
    localStorage.removeItem(OWNER_KEY)                // çıkış: clearPersonalStorage sahibi siler
    expect(latestOtherActivity(now)).toBe(0)
    localStorage.setItem(OWNER_KEY, ME)
    expect(latestOtherActivity(now)).toBe(now)
  })

  it('çıkışta kendi damgası silinir ve sekme kullanıcıdan ayrılır: yeni girişe kadar yazmaz, okumaz', () => {
    const now = 4_000_000
    writeActivityStamp(now)
    other('ben', now - 1000)
    clearOwnActivityStamp()
    expect(localStorage.getItem(ownActivityKey())).toBeNull()
    expect(boundActivityUser()).toBe('')
    expect(writeActivityStamp(now + 60_000)).toBe(false)
    expect(localStorage.getItem(ownActivityKey())).toBeNull()
    expect(latestOtherActivity(now)).toBe(0)
    bindActivityUser(ME)
    expect(writeActivityStamp(now + 60_000)).toBe(true)
  })

  it('onOtherActivity: yalnız AYNI kullanıcının geçerli, ileri tarihli olmayan damga olayı; kendi anahtarı ve başka anahtar sayılmaz', () => {
    const cb = vi.fn()
    const off = onOtherActivity(cb)
    const t = Date.now() - 1000
    const ev = (key, newValue) => window.dispatchEvent(new StorageEvent('storage', { key, newValue }))
    ev(`${ACTIVITY_PREFIX}panel`, stamp(t))
    ev(ownActivityKey(), stamp(t))
    ev('sm.prefs.rev', '125')
    ev(`${ACTIVITY_PREFIX}panel`, null)
    ev(`${ACTIVITY_PREFIX}panel`, String(t))                          // eski biçim
    ev(`${ACTIVITY_PREFIX}panel`, stamp(t, 'mehmet.demir'))           // başka kullanıcı
    ev(`${ACTIVITY_PREFIX}panel`, stamp(Date.now() + 3 * 3600_000))   // ileri tarihli
    expect(cb).toHaveBeenCalledTimes(1)
    expect(cb).toHaveBeenCalledWith(t)
    localStorage.setItem(OWNER_KEY, 'mehmet.demir')
    ev(`${ACTIVITY_PREFIX}panel`, stamp(t))
    expect(cb).toHaveBeenCalledTimes(1)
    off()
    localStorage.setItem(OWNER_KEY, ME)
    ev(`${ACTIVITY_PREFIX}panel`, stamp(t))
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('kullanıcı özeti: aynı ad aynı özet, farklı ad farklı; boş ad boş', () => {
    expect(activityUserKey('a')).toBe(activityUserKey('a'))
    expect(activityUserKey('a')).not.toBe(activityUserKey('b'))
    expect(activityUserKey('')).toBe('')
    expect(activityUserKey(null)).toBe('')
    expect(parseActivityStamp('{"u":"x","t":"y"}')).toBeNull()
    expect(parseActivityStamp('{"t":5}')).toBeNull()
  })

  it('gerçek fare hareketi süzgeci: ilk olay başlangıç; aynı koordinat (imleç dururken tarayıcının sahte mousemove\'u) sayılmaz; ≥ 4 px sayılır, yavaş hareket birikir', () => {
    expect(POINTER_MOVE_MIN_PX).toBe(4)
    const moved = createPointerMoveFilter()
    const at = (x, y) => moved({ screenX: x, screenY: y })
    expect(at(100, 100)).toBe(false)   // başlangıç
    expect(at(100, 100)).toBe(false)   // aynı koordinat
    expect(at(100, 100)).toBe(false)
    expect(at(102, 101)).toBe(false)   // < 4 px
    expect(at(103, 102)).toBe(false)   // son SAYILAN konumdan (100,100) 3,6 px — henüz değil
    expect(at(104, 101)).toBe(true)    // 4,1 px: sayılır (yavaş hareket birikti)
    expect(at(104, 101)).toBe(false)
    expect(at(104, 105)).toBe(true)
    expect(moved({})).toBe(false)      // koordinatsız olay
    expect(moved({ screenX: Number.NaN, screenY: 1 })).toBe(false)
  })

  it('markActivity kendi sekmesine olay yayar (uygulama içi eylem: ekrandan alma, yapıştırma, yükleme)', () => {
    const h = vi.fn()
    window.addEventListener(ACTIVITY_EVENT, h)
    markActivity()
    window.removeEventListener(ACTIVITY_EVENT, h)
    expect(h).toHaveBeenCalledTimes(1)
  })
})
