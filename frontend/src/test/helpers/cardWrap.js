import { expect } from 'vitest'

/**
 * İzleme kartlarında "adlar ve uzun metin kesilmez" (2026-11-14, kullanıcı kararı) — sınıf düzeyinde ortak denetimler.
 * jsdom yerleşim yapmaz; gerçek ölçüm `e2e/monitor-card-titles.spec.js`'te. Burada yalnız sözleşme: tek satır kırpması
 * (`truncate`), satır sınırı (`line-clamp-*`) ve `whitespace-nowrap` YOK, boşluksuz uzun metin `[overflow-wrap:anywhere]`
 * ile kırılır.
 */
const CLIP = /(^|\s)(truncate|line-clamp-\d+|whitespace-nowrap|text-ellipsis)(\s|$)/

/** Metin öğesi (ad satırı, kompakt alt satırdaki ad, hedef host) sarar. */
export function expectWraps(el, what = 'öğe') {
  expect(el, `${what} bulunamadı`).not.toBeNull()
  expect(el.className, `${what} kırpılıyor`).not.toMatch(CLIP)
  expect(el.className, `${what} uzun kelimeyi kırmıyor`).toContain('[overflow-wrap:anywhere]')
}

/** Kart başlık düğmesi (MonitorCardTitle) ve iç metni sarar; satır sınırı / tek satır kırpması yok. */
export function expectTitleWraps(button) {
  expect(button, 'başlık düğmesi bulunamadı').not.toBeNull()
  expect(button.className, 'başlık düğmesi tek satıra zorlanıyor').toMatch(/(^|\s)whitespace-normal(\s|$)/)
  expect(button.className, 'başlık düğmesi kırpılıyor').not.toMatch(CLIP)
  expect(button.className, 'başlıkta satır sınırı var').not.toMatch(/\[&>span\]:(truncate|line-clamp)/)
  const text = button.querySelector(':scope > span')
  expectWraps(text, 'başlık metni')
}
