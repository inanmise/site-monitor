import { describe, it, expect } from 'vitest'
import {
  CAPTURE_IDLE_MS, CAPTURE_MIN, HANDLES, captureFileName, captureStamp, clampRect, drawRect, extensionOf, fitScale, fullRect,
  friendlyTrackLabel, imageFromDataTransfer, isFullRect, isUniformFrame, keyRect, moveRect, pickImageType, pointInRect, resizeRect, shortcutFamily,
  sourceRect, toDisplayRect, toSourcePoint,
} from '../components/techerror/capture/captureModel.js'
import { SERVER_IMAGE } from '../components/techerror/imagePrep.js'

// Ekrandan görsel alma (2026-10-18) — kırpma matematiği kaynak pikselleriyle; yüklenen bölge tam sayı, en az 16 px.
const W = 1600
const H = 900

describe('captureModel — sınırlar', () => {
  it('en küçük kenar sunucunun alt sınırı (tek kaynak imagePrep), boşta kalma 10 dk', () => {
    expect(CAPTURE_MIN).toBe(SERVER_IMAGE.minSide)
    expect(CAPTURE_MIN).toBe(16)
    expect(CAPTURE_IDLE_MS).toBe(10 * 60_000)
    expect(HANDLES).toEqual(['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'])
  })

  it('clampRect: kaynağın içinde, kenar ≥ 16, kaynaktan büyük olamaz; küçük kaynakta kaynağın kendisi', () => {
    expect(clampRect({ x: -50, y: -10, w: 100, h: 50 }, W, H)).toEqual({ x: 0, y: 0, w: 100, h: 50 })
    expect(clampRect({ x: 1590, y: 890, w: 100, h: 50 }, W, H)).toEqual({ x: 1500, y: 850, w: 100, h: 50 })
    expect(clampRect({ x: 10, y: 10, w: 3, h: 2 }, W, H)).toEqual({ x: 10, y: 10, w: 16, h: 16 })
    expect(clampRect({ x: 0, y: 0, w: 5000, h: 5000 }, W, H)).toEqual({ x: 0, y: 0, w: W, h: H })
    // Kaynak 16 px'den küçük (ince şerit): kaynağın kendisi
    expect(clampRect({ x: 0, y: 0, w: 2, h: 2 }, 10, 8)).toEqual({ x: 0, y: 0, w: 10, h: 8 })
    // Bozuk değerler: konum 0, ölçü kaynağın tamamı (seçim yokmuş gibi)
    expect(clampRect({ x: NaN, y: undefined, w: null, h: 'x' }, W, H)).toEqual({ x: 0, y: 0, w: W, h: H })
  })

  it('moveRect: ölçü korunur, kenarda durur', () => {
    const r = { x: 100, y: 100, w: 200, h: 100 }
    expect(moveRect(r, 50, -20, W, H)).toEqual({ x: 150, y: 80, w: 200, h: 100 })
    expect(moveRect(r, -500, -500, W, H)).toEqual({ x: 0, y: 0, w: 200, h: 100 })
    expect(moveRect(r, 5000, 5000, W, H)).toEqual({ x: 1400, y: 800, w: 200, h: 100 })
  })

  it('resizeRect: karşı kenar sabit, ters dönmez, en az 16, kaynağın dışına çıkmaz — sekiz tutamak', () => {
    const r = { x: 100, y: 100, w: 200, h: 100 }
    expect(resizeRect(r, 'e', 50, 0, W, H)).toEqual({ x: 100, y: 100, w: 250, h: 100 })
    expect(resizeRect(r, 'w', 50, 0, W, H)).toEqual({ x: 150, y: 100, w: 150, h: 100 })
    expect(resizeRect(r, 'n', 0, -40, W, H)).toEqual({ x: 100, y: 60, w: 200, h: 140 })
    expect(resizeRect(r, 's', 0, 40, W, H)).toEqual({ x: 100, y: 100, w: 200, h: 140 })
    expect(resizeRect(r, 'se', 10, 20, W, H)).toEqual({ x: 100, y: 100, w: 210, h: 120 })
    expect(resizeRect(r, 'nw', -10, -20, W, H)).toEqual({ x: 90, y: 80, w: 210, h: 120 })
    expect(resizeRect(r, 'ne', 10, -20, W, H)).toEqual({ x: 100, y: 80, w: 210, h: 120 })
    expect(resizeRect(r, 'sw', -10, 20, W, H)).toEqual({ x: 90, y: 100, w: 210, h: 120 })
    // Karşı kenarı geçemez: en az 16 kalır
    expect(resizeRect(r, 'e', -1000, 0, W, H)).toEqual({ x: 100, y: 100, w: 16, h: 100 })
    expect(resizeRect(r, 'w', 1000, 0, W, H)).toEqual({ x: 284, y: 100, w: 16, h: 100 })
    expect(resizeRect(r, 'n', 0, 1000, W, H)).toEqual({ x: 100, y: 184, w: 200, h: 16 })
    // Kaynağın dışına çıkmaz
    expect(resizeRect(r, 'nw', -1000, -1000, W, H)).toEqual({ x: 0, y: 0, w: 300, h: 200 })
    expect(resizeRect(r, 'se', 9000, 9000, W, H)).toEqual({ x: 100, y: 100, w: 1500, h: 800 })
  })

  it('drawRect: yönden bağımsız, kaynağın içinde; kısa sürükleme sürükleme yönünde 16 px, kenara dayanınca içe kayar', () => {
    expect(drawRect({ x: 300, y: 200 }, { x: 100, y: 50 }, W, H)).toEqual({ x: 100, y: 50, w: 200, h: 150 })
    expect(drawRect({ x: 100, y: 50 }, { x: 300, y: 200 }, W, H)).toEqual({ x: 100, y: 50, w: 200, h: 150 })
    expect(drawRect({ x: 100, y: 100 }, { x: 105, y: 102 }, W, H)).toEqual({ x: 100, y: 100, w: 16, h: 16 })
    expect(drawRect({ x: 100, y: 100 }, { x: 95, y: 98 }, W, H)).toEqual({ x: 84, y: 84, w: 16, h: 16 })
    expect(drawRect({ x: 1598, y: 899 }, { x: 1600, y: 900 }, W, H)).toEqual({ x: 1584, y: 884, w: 16, h: 16 })
    expect(drawRect({ x: 2, y: 1 }, { x: 0, y: 0 }, W, H)).toEqual({ x: 0, y: 0, w: 16, h: 16 })
    // Kaynağın dışındaki imleç kenara kırpılır
    expect(drawRect({ x: 100, y: 100 }, { x: -50, y: 2000 }, W, H)).toEqual({ x: 0, y: 100, w: 100, h: 800 })
  })

  it('keyRect: oklar 1 px (Shift 10) taşır; Alt + oklar sağ / alt kenarı büyütür-küçültür; ok değilse null', () => {
    const r = { x: 100, y: 100, w: 200, h: 100 }
    expect(keyRect(r, 'ArrowRight', {}, W, H)).toEqual({ x: 101, y: 100, w: 200, h: 100 })
    expect(keyRect(r, 'ArrowLeft', { shift: true }, W, H)).toEqual({ x: 90, y: 100, w: 200, h: 100 })
    expect(keyRect(r, 'ArrowDown', {}, W, H)).toEqual({ x: 100, y: 101, w: 200, h: 100 })
    expect(keyRect(r, 'ArrowUp', { shift: true }, W, H)).toEqual({ x: 100, y: 90, w: 200, h: 100 })
    expect(keyRect(r, 'ArrowRight', { alt: true }, W, H)).toEqual({ x: 100, y: 100, w: 201, h: 100 })
    expect(keyRect(r, 'ArrowLeft', { alt: true, shift: true }, W, H)).toEqual({ x: 100, y: 100, w: 190, h: 100 })
    expect(keyRect(r, 'ArrowDown', { alt: true }, W, H)).toEqual({ x: 100, y: 100, w: 200, h: 101 })
    expect(keyRect(r, 'ArrowUp', { alt: true, shift: true }, W, H)).toEqual({ x: 100, y: 100, w: 200, h: 90 })
    expect(keyRect({ x: 0, y: 0, w: 16, h: 16 }, 'ArrowLeft', { alt: true }, W, H)).toEqual({ x: 0, y: 0, w: 16, h: 16 })
    expect(keyRect({ x: 0, y: 0, w: 16, h: 16 }, 'ArrowLeft', {}, W, H)).toEqual({ x: 0, y: 0, w: 16, h: 16 })
    expect(keyRect(r, 'Enter', {}, W, H)).toBeNull()
    expect(keyRect(r, 'a', { alt: true }, W, H)).toBeNull()
  })

  it('fitScale / dönüşümler: kutuya sığdırır, büyütmez; ekran ↔ kaynak', () => {
    expect(fitScale(1600, 900, 800, 900)).toBe(0.5)
    expect(fitScale(1600, 900, 3200, 450)).toBe(0.5)
    expect(fitScale(400, 300, 1000, 1000)).toBe(1)
    expect(fitScale(0, 300, 100, 100)).toBe(1)
    expect(fitScale(1600, 900, 0, 0)).toBe(1)
    expect(toSourcePoint({ x: 100, y: 50 }, 0.5)).toEqual({ x: 200, y: 100 })
    expect(toSourcePoint({ x: 100, y: 50 }, 0)).toEqual({ x: 100, y: 50 })
    expect(toDisplayRect({ x: 200, y: 100, w: 400, h: 200 }, 0.5)).toEqual({ x: 100, y: 50, w: 200, h: 100 })
  })

  it('sourceRect: tam sayı, kenarlar ayrı yuvarlanır (komşu bölgeler boşluksuz), sınır içinde, ≥ 16', () => {
    expect(sourceRect({ x: 10.4, y: 20.6, w: 100.2, h: 50.5 }, W, H)).toEqual({ x: 10, y: 21, w: 101, h: 50 })
    // Komşu iki bölge: a'nın sağ kenarı = b'nin sol kenarı
    const a = sourceRect({ x: 0, y: 0, w: 333.5, h: 100 }, W, H)
    const b = sourceRect({ x: 333.5, y: 0, w: 333.5, h: 100 }, W, H)
    expect(a.x + a.w).toBe(b.x)
    expect(sourceRect({ x: 0, y: 0, w: W, h: H }, W, H)).toEqual({ x: 0, y: 0, w: W, h: H })
    expect(sourceRect({ x: 1599.6, y: 899.6, w: 1, h: 1 }, W, H)).toEqual({ x: 1584, y: 884, w: 16, h: 16 })
    for (const v of [sourceRect({ x: 3.3, y: 4.7, w: 99.9, h: 77.7 }, W, H)]) {
      for (const n of Object.values(v)) expect(Number.isInteger(n)).toBe(true)
    }
  })

  it('isFullRect / pointInRect / fullRect', () => {
    expect(isFullRect(fullRect(W, H), W, H)).toBe(true)
    expect(isFullRect({ x: 0.3, y: 0, w: W - 0.3, h: H }, W, H)).toBe(true)
    expect(isFullRect({ x: 1, y: 0, w: W - 1, h: H }, W, H)).toBe(false)
    expect(isFullRect(null, W, H)).toBe(false)
    expect(pointInRect({ x: 150, y: 150 }, { x: 100, y: 100, w: 100, h: 100 })).toBe(true)
    expect(pointInRect({ x: 201, y: 150 }, { x: 100, y: 100, w: 100, h: 100 })).toBe(false)
  })
})

describe('captureModel — kare, dosya adı, pano', () => {
  it('isUniformFrame: tek renk (siyah) / gürültü payı içinde → true; blok farkı → false', () => {
    const black = new Uint8ClampedArray(4 * 64).fill(0)
    for (let i = 3; i < black.length; i += 4) black[i] = 255
    expect(isUniformFrame(black)).toBe(true)
    const noisy = Uint8ClampedArray.from(black)
    noisy[4] = 4
    noisy[9] = 5
    expect(isUniformFrame(noisy)).toBe(true)
    const blocks = Uint8ClampedArray.from(black)
    blocks[40] = 200
    expect(isUniformFrame(blocks)).toBe(false)
    expect(isUniformFrame(null)).toBe(true)
  })

  it('captureFileName: <yuva>-ekran-<yyyyMMdd-HHmmss>.png, Türkiye saati; güvenli karakterler', () => {
    const d = new Date('2026-10-18T07:05:09Z')   // İstanbul 10:05:09
    expect(captureStamp(d)).toBe('20261018-100509')
    expect(captureFileName('monthly-tx', 'ekran', d)).toBe('monthly-tx-ekran-20261018-100509.png')
    expect(captureFileName('daily-errors', 'pano', d, 'jpg')).toBe('daily-errors-pano-20261018-100509.jpg')
    expect(captureFileName('../Kötü Ad!', 'ekran', d)).toBe('ktad-ekran-20261018-100509.png')
    expect(captureFileName('', 'ekran', d)).toBe('gorsel-ekran-20261018-100509.png')
    // Gece yarısını geçen UTC saati İstanbul gününe yazılır
    expect(captureStamp(new Date('2026-10-18T22:30:00Z'))).toBe('20261019-013000')
  })

  it('extensionOf / pickImageType: PNG önce, sonra ilk görsel türü; görsel yoksa null', () => {
    expect(extensionOf('image/jpeg')).toBe('jpg')
    expect(extensionOf('image/x-unknown')).toBe('png')
    expect(pickImageType(['text/html', 'image/jpeg', 'image/png'])).toBe('image/png')
    expect(pickImageType(['text/plain', 'image/webp'])).toBe('image/webp')
    expect(pickImageType(['text/plain'])).toBeNull()
    expect(pickImageType(null)).toBeNull()
  })

  it('imageFromDataTransfer: görsel öğe / dosya (image); görsel yoksa ilk dosya (image: false — tür hatası yazılsın); yalnız metin → hasText; boş → null', () => {
    const png = new File([new Uint8Array([1])], 'image.png', { type: 'image/png' })
    expect(imageFromDataTransfer({ items: [{ kind: 'string', type: 'text/plain' }, { kind: 'file', type: 'image/png', getAsFile: () => png }] }))
      .toEqual({ file: png, hasText: false, image: true })
    expect(imageFromDataTransfer({ items: [], files: [png] }).file).toBe(png)
    expect(imageFromDataTransfer({ items: [{ kind: 'string', type: 'text/plain' }], types: ['text/plain'] }))
      .toEqual({ file: null, hasText: true, image: false })
    const pdf = new File([new Uint8Array([1])], 'a.pdf', { type: 'application/pdf' })
    expect(imageFromDataTransfer({ items: [{ kind: 'file', type: 'application/pdf', getAsFile: () => pdf }], files: [pdf] }))
      .toEqual({ file: pdf, hasText: false, image: false })
    expect(imageFromDataTransfer({ items: [], files: [pdf] })).toEqual({ file: pdf, hasText: false, image: false })
    // Görsel ile birlikte başka dosya da varsa görsel seçilir
    expect(imageFromDataTransfer({ items: [{ kind: 'file', type: 'application/pdf', getAsFile: () => pdf },
      { kind: 'file', type: 'image/png', getAsFile: () => png }] }).file).toBe(png)
    expect(imageFromDataTransfer(null)).toEqual({ file: null, hasText: false, image: false })
  })

  it('friendlyTrackLabel: makine kimliği (screen:0:0, web-contents…, uzun belirteç) boş; pencere başlığı kalır', () => {
    expect(friendlyTrackLabel('screen:0:0')).toBe('')
    expect(friendlyTrackLabel('window:5:0')).toBe('')
    expect(friendlyTrackLabel('web-contents-media-stream://123:4')).toBe('')
    expect(friendlyTrackLabel('Hiv66i1iaeOPZvOjrw7igHnzA86Lf0S+GKDH4K5NhSreEfl/BbkUhctKc==')).toBe('')
    expect(friendlyTrackLabel('Qlik Sense — Kanal Raporu')).toBe('Qlik Sense — Kanal Raporu')
    expect(friendlyTrackLabel('Screen 1')).toBe('Screen 1')
    expect(friendlyTrackLabel('')).toBe('')
    expect(friendlyTrackLabel('A '.repeat(80)).length).toBe(120)
  })

  it('shortcutFamily: Windows / macOS / diğer', () => {
    expect(shortcutFamily({ userAgentData: { platform: 'Windows' } })).toBe('win')
    expect(shortcutFamily({ platform: 'MacIntel' })).toBe('mac')
    expect(shortcutFamily({ platform: 'Linux x86_64' })).toBe('other')
    expect(shortcutFamily({})).toBe('other')
  })
})
