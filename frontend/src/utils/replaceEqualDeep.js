/**
 * Yapısal paylaşım (2026-11-06, performans): sunucudan gelen YENİ değeri öncekiyle derin karşılaştırır ve DEĞİŞMEYEN her alt
 * ağacın ESKİ başvurusunu korur. Tamamen aynıysa `prev` döner (React durumu değişmez → yeniden çizim yok); bir öğe değiştiyse
 * yalnız o öğe ve onu içeren dizi / nesne yeni olur — `memo` kartlar değişmeyen satırlar için yeniden çizilmez.
 *
 * <p>Yalnız düz JSON değerleri paylaşılır (düz nesne, dizi, ilkel). Başka her tür (Date, Set, Map, sınıf örneği) `===` ile
 * karşılaştırılır ve eşit değilse `next` döner. Diziler SIRAYLA (indeks) karşılaştırılır. Derinlik sınırı: daha derin alt ağaç
 * paylaşılmadan `next` olarak alınır (sonsuz döngü / yığın taşması yok).
 *
 * <p>Sonuç `next` ile içerik olarak HER ZAMAN aynıdır; tek fark başvurular. Çağıranlar değeri değiştirmemeli (salt okunur).
 *
 * @template T
 * @param {*} prev  önceki değer
 * @param {T} next  yeni değer
 * @returns {T}
 */
export function replaceEqualDeep(prev, next) {
  return share(prev, next, 0)
}

const MAX_DEPTH = 32

function isPlainObject(v) {
  if (v === null || typeof v !== 'object') return false
  const proto = Object.getPrototypeOf(v)
  return proto === Object.prototype || proto === null
}

function share(a, b, depth) {
  if (a === b) return a
  if (depth > MAX_DEPTH) return b
  const arrA = Array.isArray(a)
  const arrB = Array.isArray(b)
  if (arrA && arrB) {
    const out = new Array(b.length)
    let same = a.length === b.length
    for (let i = 0; i < b.length; i++) {
      const v = i < a.length ? share(a[i], b[i], depth + 1) : b[i]
      out[i] = v
      if (!Object.is(v, a[i])) same = false
    }
    return same ? a : out
  }
  if (!arrA && !arrB && isPlainObject(a) && isPlainObject(b)) {
    const keysB = Object.keys(b)
    const keysA = Object.keys(a)
    let same = keysA.length === keysB.length
    const out = {}
    for (const k of keysB) {
      const has = Object.prototype.hasOwnProperty.call(a, k)
      const v = has ? share(a[k], b[k], depth + 1) : b[k]
      out[k] = v
      if (!has || !Object.is(v, a[k])) same = false
    }
    return same ? a : out
  }
  // İlkeller: NaN === NaN değildir — iki NaN aynı sayılır (gereksiz yeni başvuru yok)
  if (typeof a === 'number' && typeof b === 'number' && Number.isNaN(a) && Number.isNaN(b)) return a
  return b
}

/**
 * İki kümenin içeriği aynıysa öncekini döner (Set her dalgada yeniden kuruluyordu → tüketenler boşuna yeniden çizilirdi).
 * @param {Set} prev
 * @param {Set} next
 * @returns {Set}
 */
export function keepSameSet(prev, next) {
  if (prev === next) return prev
  if (!(prev instanceof Set) || !(next instanceof Set) || prev.size !== next.size) return next
  for (const v of next) if (!prev.has(v)) return next
  return prev
}
