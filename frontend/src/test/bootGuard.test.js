import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { CHUNK_RELOAD_KEY, RELOAD_COOLDOWN_MS, claimChunkReload, currentBuildId } from '../utils/chunkReloadGuard.js'

/**
 * Açılış koruması — public/boot-guard.js (2026-10-23, çok pod R2b · MP-47).
 *
 * Yuvarlanan dağıtımda index.html bir poddan, paketin kendi dosyaları öbüründen gelebilir → düz metin 404 → React hiç
 * başlamaz → boş sayfa. Betik paket yüklenemediğinde sayfayı BİR KEZ yeniler, hak yoksa boş sayfa yerine uyarı çizer.
 * Kural utils/chunkReloadGuard.js ile AYNI kayıtta ve aynı doğruluk tablosuyla sınanır (iki kopya, tek kural).
 * Betik paket dışıdır (hash'siz public dosya); test onu jsdom penceresinde sahte bir kök nesneyle çalıştırır
 * (jsdom'da location.reload değiştirilemez).
 */

const SRC = readFileSync(resolve(__dirname, '..', '..', 'public', 'boot-guard.js'), 'utf8')
const T0 = 1_800_000_000_000
let installed = []

/** Betiği "yeni bir sayfa yüklemesi" gibi kurar: kendi dinleyicileri, sahte location.reload. */
function install() {
  const listeners = []
  const reload = vi.fn()
  const root = {
    document,
    sessionStorage: window.sessionStorage,
    localStorage: window.localStorage,
    location: { reload },
    addEventListener: (t, fn, cap) => { window.addEventListener(t, fn, cap); listeners.push([t, fn, cap]) },
    removeEventListener: (t, fn, cap) => window.removeEventListener(t, fn, cap),
  }
  new Function(SRC).call(root)
  installed.push(listeners)
  return { reload }
}

/** Sayfanın bir dosyası yüklenemedi: öğeyi ekle, error olayını ateşle (kabarmaz — window yakalama dinleyicisi alır). */
function fail(tag, attrs) {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v)
  el.setAttribute('data-test', '1')
  document.head.appendChild(el)
  el.dispatchEvent(new Event('error'))
  return el
}

const entry = (hash) => fail('script', { type: 'module', crossorigin: '', src: `/assets/index-${hash}.js` })
const notice = () => document.querySelector('[data-slot="boot-failure"]')
const stored = () => JSON.parse(sessionStorage.getItem(CHUNK_RELOAD_KEY))

beforeEach(() => {
  sessionStorage.clear()
  localStorage.removeItem('site-monitor-lang')
  vi.spyOn(Date, 'now').mockReturnValue(T0)
})
/** Önceki "sayfa yüklemelerinin" dinleyicileri — gerçek tarayıcıda yeniden yükleme onları zaten siler. */
function uninstallAll() {
  for (const ls of installed) for (const [t, fn, cap] of ls) window.removeEventListener(t, fn, cap)
  installed = []
}

afterEach(() => {
  uninstallAll()
  document.querySelectorAll('[data-test], #sm-boot-failure').forEach((el) => el.remove())
  document.getElementById('root')?.remove()
  vi.restoreAllMocks()
})

describe('boot-guard.js — paketin kendi dosyası yüklenemedi', () => {
  it('giriş betiği 404: sayfa BİR KEZ yenilenir, hak ortak kayda yazılır', () => {
    const { reload } = install()
    entry('AAA')
    expect(reload).toHaveBeenCalledTimes(1)
    expect(stored()).toEqual({ v: 2, at: T0, builds: { '/assets/index-AAA.js': 1 } })
    expect(notice()).toBeNull()
  })

  it('yenilemeden sonra yine yüklenemezse yenilemez — boş sayfa yerine İngilizce uyarı + 40 px+ "Reload" düğmesi', () => {
    install(); entry('AAA')
    document.querySelectorAll('[data-test]').forEach((el) => el.remove())
    uninstallAll()
    vi.spyOn(Date, "now").mockReturnValue(T0 + 3000)
    const root = document.createElement('div'); root.id = 'root'; document.body.appendChild(root)
    const second = install()
    entry('AAA')
    expect(second.reload).not.toHaveBeenCalled()
    const box = notice()
    expect(box).not.toBeNull()
    expect(box.getAttribute('role')).toBe('alert')
    expect(box.getAttribute('lang')).toBe('en')
    expect(box.textContent).toContain('SiteMonitor is being updated')
    const button = box.querySelector('button')
    expect(button.textContent).toBe('Reload')
    expect(parseInt(button.style.minHeight, 10)).toBeGreaterThanOrEqual(40)
    button.click()                                                          // kullanıcı yenilemesi sayaçsız
    expect(second.reload).toHaveBeenCalledTimes(1)
  })

  it('B paketi 60 sn içinde de yenilemez (yuvarlanan dağıtım A → B: en çok bir kez); uyarı Türkçe tercihle Türkçe', () => {
    localStorage.setItem('site-monitor-lang', 'tr')
    install(); entry('AAA')
    vi.spyOn(Date, 'now').mockReturnValue(T0 + RELOAD_COOLDOWN_MS - 1)
    const b = install()
    entry('BBB')
    expect(b.reload).not.toHaveBeenCalled()
    expect(notice().textContent).toContain('SiteMonitor güncelleniyor')
    expect(notice().querySelector('button').textContent).toBe('Yeniden yükle')
  })

  it('uygulama zaten çizildiyse uyarı çıkmaz; CSS / modulepreload hatası hak varsa yeniler, yoksa sessiz kalır', () => {
    const root = document.createElement('div'); root.id = 'root'; root.appendChild(document.createElement('main')); document.body.appendChild(root)
    const a = install()
    fail('link', { rel: 'stylesheet', href: '/assets/index-AAA.css' })
    expect(a.reload).toHaveBeenCalledTimes(1)
    vi.spyOn(Date, 'now').mockReturnValue(T0 + 1000)
    const b = install()
    fail('link', { rel: 'modulepreload', href: '/assets/vendor-AAA.js' })
    fail('link', { rel: 'stylesheet', href: '/assets/index-AAA.css' })
    entry('AAA')
    expect(b.reload).not.toHaveBeenCalled()
    expect(notice()).toBeNull()                                             // #root dolu
  })

  it('ilgisiz dosyalar yok sayılır: simge / manifest bağlantısı, başka kaynaktan betik, koruma betiğinin kendisi', () => {
    const { reload } = install()
    fail('link', { rel: 'icon', href: '/favicon.ico' })
    fail('link', { rel: 'manifest', href: '/site.webmanifest' })
    fail('script', { src: 'https://cdn.example.com/x.js' })
    fail('script', { src: '/boot-guard.js' })
    fail('img', { src: '/brand/logo.png' })
    expect(reload).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(CHUNK_RELOAD_KEY)).toBeNull()
  })

  it('ilk "load" olayından sonra devre dışı: tembel parça hataları uygulamanın işi (ErrorBoundary)', () => {
    const { reload } = install()
    window.dispatchEvent(new Event('load'))
    entry('AAA')
    expect(reload).not.toHaveBeenCalled()
  })

  it('depolama yazılamıyorsa yenileme YOK (sayaçsız yenileme döngü olurdu) — uyarı gösterilir', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const { reload } = install()
    entry('AAA')
    expect(reload).not.toHaveBeenCalled()
    expect(notice()).not.toBeNull()
  })
})

describe('boot-guard.js ↔ utils/chunkReloadGuard.js — tek kayıt, tek kural', () => {
  it('açılış koruması hakkı kullandıysa uygulama (ErrorBoundary / dil) aynı paket için yenilemez — ve tersi', () => {
    install(); entry('AAA')
    expect(currentBuildId()).toBe('/assets/index-AAA.js')                   // ikisi de aynı paket kimliğini görür
    expect(claimChunkReload(currentBuildId(), T0 + 10 * RELOAD_COOLDOWN_MS)).toBe(false)
    document.querySelectorAll('[data-test]').forEach((el) => el.remove())
    sessionStorage.clear()
    expect(claimChunkReload('/assets/index-BBB.js', T0)).toBe(true)
    const b = install()
    entry('BBB')
    expect(b.reload).not.toHaveBeenCalled()
  })

  // [paket, zaman (T0'a göre ms), beklenen] — aynı dizi iki uygulamada da aynı sonucu vermeli
  const TABLE = [
    ['A', 0, true],
    ['A', 1, false],
    ['B', RELOAD_COOLDOWN_MS - 1, false],
    ['B', RELOAD_COOLDOWN_MS, true],
    ['A', 5 * RELOAD_COOLDOWN_MS, false],
    ['C', 5 * RELOAD_COOLDOWN_MS, true],
    ['D', 5 * RELOAD_COOLDOWN_MS - 10, true],                              // saat geri: kayıt gelecekte → bekleme yok
    ['D', 6 * RELOAD_COOLDOWN_MS, false],
  ]

  it('aynı doğruluk tablosu', () => {
    const viaApp = TABLE.map(([id, dt]) => claimChunkReload(`/assets/index-${id}.js`, T0 + dt))
    sessionStorage.clear()
    const viaGuard = TABLE.map(([id, dt]) => {
      vi.spyOn(Date, 'now').mockReturnValue(T0 + dt)
      document.querySelectorAll('[data-test], #sm-boot-failure').forEach((el) => el.remove())
      uninstallAll()
      const g = install()
      entry(id)
      return g.reload.mock.calls.length === 1
    })
    expect(viaApp).toEqual(TABLE.map((r) => r[2]))
    expect(viaGuard).toEqual(viaApp)
  })
})
