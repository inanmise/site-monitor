/**
 * Kullanım Kılavuzu (Yardım sekmesi) markdown'ı için başlık slug'ı ve İçindekiler çıkarımı.
 *
 * Bu dosya İKİ taraftan birden import edilir:
 *   - Uygulama: `HelpPage.jsx` — TOC listesi ve her başlığın `id` niteliği
 *   - Build script'i: `frontend/scripts/gen-whitepaper-pdf.mjs` — PDF'teki çapalar
 *
 * Bu yüzden HİÇBİR ŞEY import etmez: React yok, `?raw` yok, `import.meta.env` yok.
 * Düz Node ESM olarak çalışabilmesi zorunludur (package.json "type": "module").
 *
 * Slug üretimi kasıtlı olarak `github-slugger`/`rehype-slug`'dan AYRIDIR: onlar Türkçe
 * harfleri korur (`#1-yönetici-özeti`) ve kılavuzdaki elle yazılmış İçindekiler
 * bağlantılarıyla (`#1-yonetici-ozeti`) uyuşmaz — her bağlantı kırılırdı.
 */

/** Başlık metnini çapa slug'ına çevirir (Türkçe harfler ASCII'ye indirgenir). */
export function slugify(text) {
  return String(text)
    .toLowerCase()
    .replace(/ş/g, 's').replace(/ğ/g, 'g').replace(/ı/g, 'i')
    .replace(/ü/g, 'u').replace(/ö/g, 'o').replace(/ç/g, 'c')
    .replace(/[^a-z0-9\s-]/g, '')
    .trim()
    .replace(/\s+/g, '-')
}

/**
 * Markdown'dan `#`/`##`/`###` başlıklarını çıkarır (TOC üç seviye okur, `####` hariç).
 *
 * Kod çitleri (``` ve ~~~) ATLANIR: kılavuzda yaml/shell örnekleri `#` ile başlayan yorum
 * satırları içeriyor ve bunlar eskiden TOC'a hayalet girdi olarak düşüyordu — render eşleşen
 * bir başlık elemanı yaratmadığı için tıklanınca hiçbir şey olmuyordu.
 */
export function parseToc(markdown) {
  const lines = String(markdown ?? '').split('\n')
  const items = []
  let fence = null   // açık çitin işareti ('```' veya '~~~'), yoksa null
  for (const line of lines) {
    const fenceMatch = line.match(/^\s*(```|~~~)/)
    if (fenceMatch) {
      const marker = fenceMatch[1]
      if (fence === null) fence = marker
      else if (fence === marker) fence = null   // aynı tür işaret çiti kapatır
      continue
    }
    if (fence !== null) continue
    const m = line.match(/^(#{1,4})\s+(.+)/)
    if (!m) continue
    const level = m[1].length
    if (level > 3) continue
    const text = m[2].replace(/\*\*/g, '').replace(/`/g, '').trim()
    items.push({ level, text, id: slugify(text) })
  }
  return items
}

/**
 * Aynı slug'a düşen başlıkları döndürür (TOC bağlantıları çakışan başlıklarda yanlış
 * hedefe gider). Boş dizi = sorun yok. Kılavuz kapı testleri bunu kullanır.
 */
export function duplicateSlugs(markdown) {
  const seen = new Map()
  for (const item of parseToc(markdown)) {
    seen.set(item.id, (seen.get(item.id) ?? 0) + 1)
  }
  return [...seen.entries()].filter(([, n]) => n > 1).map(([id]) => id)
}
