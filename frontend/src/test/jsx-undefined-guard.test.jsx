import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ: JSX'te kullanılan her bileşen o dosyada TANIMLI olmalı (import ya da yerel bildirim).
 *
 * Gerekçe: 2026-08-19'da üretimde Sistem Sağlığı ekranı
 * `ReferenceError: ProgressBar is not defined` ile ErrorBoundary'ye düştü.
 * `SystemHealth.jsx` `<ProgressBar>` kullanıyordu ama import listesinde yoktu — 2026-08-09'daki
 * ilerleme-göstergesi süpürmesinde atlanmıştı. Vite/Rollup çözülmemiş bir tanımlayıcıyı
 * çalışma-zamanı global'i sayar ve build'i YEŞİL geçirir; hata ancak o sekme açılınca çıkar.
 * Aynı tur ESLint'i de getirdi (react/jsx-no-undef) — bu bekçi onun yedeğidir: bağımlılık
 * gerektirmez ve ESLint yapılandırması silinse bile ayakta kalır.
 *
 * SINIR: yalnız BÜYÜK HARFLE başlayan JSX etiketlerini görür. Düz ifadelerdeki tanımsız
 * tanımlayıcılar (ör. silinmiş bir yardımcı fonksiyon çağrısı) ESLint'in `no-undef` kuralına
 * kalır — nitekim bu turda `InventoryManager.jsx`'teki silinmiş `openTransfer` çağrısını
 * bu bekçi değil, ESLint yakaladı.
 */
const SRC = path.resolve(__dirname, '..')

/** React'in kendi sözdizimi — bileşen değil. */
const BUILTIN = new Set(['Fragment'])

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name !== 'test') walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

/**
 * Yorumları ve dize/şablon gövdelerini boşlukla değiştirir.
 * Şart: kaynakta yorum içinde geçen `<ProgressBar>` gibi örnekler var
 * (PaginationBar.jsx, css-hygiene.test.jsx) — elenmezse bekçi yanlış alarm verir.
 * Satır sayısı korunsun diye yalnız yorum-dışı karakterler boşluğa çevrilir.
 */
function stripCommentsAndStrings(src) {
  let out = ''
  let i = 0
  const keep = (ch) => (ch === '\n' ? '\n' : ' ')
  while (i < src.length) {
    const two = src.slice(i, i + 2)
    if (two === '//') {
      while (i < src.length && src[i] !== '\n') { out += keep(src[i]); i++ }
    } else if (two === '/*') {
      const end = src.indexOf('*/', i + 2)
      const stop = end === -1 ? src.length : end + 2
      for (; i < stop; i++) out += keep(src[i])
    } else if (src[i] === '"' || src[i] === "'" || src[i] === '`') {
      const q = src[i]
      out += ' '; i++
      while (i < src.length) {
        if (src[i] === '\\') { out += '  '.slice(0, 2); i += 2; continue }
        if (src[i] === q) { out += ' '; i++; break }
        out += keep(src[i]); i++
      }
    } else {
      out += src[i]; i++
    }
  }
  return out
}

/** `<Foo`, `<Foo.Bar` → kök tanımlayıcıyı (Foo) satır numarasıyla döndürür. */
function jsxComponents(cleaned) {
  const hits = []
  cleaned.split('\n').forEach((line, idx) => {
    for (const m of line.matchAll(/<([A-Z][A-Za-z0-9_]*)/g)) {
      hits.push({ name: m[1], line: idx + 1, text: line.trim() })
    }
  })
  return hits
}

/**
 * Bir ismin JSX etiketi DIŞINDA da geçip geçmediğini söyler.
 *
 * Ölçüt bilinçli olarak "bağlayıcı bulundu mu" değil, "başka bir yerde anılıyor mu":
 * gerçek kapsam analizi regex'le yapılamaz ve denemesi yanlış alarm üretti — `Icon`,
 * `ItemIcon` gibi isimler prop/parametre destructuring'inden geliyor
 * (`({ Icon, label }) => …`, `items.map(({ Icon }) => …)`), bunların hepsini doğru
 * tanımak için ESLint'in kapsam çözümlemesi gerekir ve o zaten kuruludur.
 *
 * Bu ölçüt asıl hatayı yine de yakalar: `ProgressBar` dosyada YALNIZ `<ProgressBar`
 * biçiminde geçiyordu, import'ta veya başka hiçbir yerde anılmıyordu.
 */
function mentionedOutsideJsxTag(cleaned, name) {
  const withoutTags = cleaned
    .replace(new RegExp(`</?${name}\\b`, 'g'), ' ')
  return new RegExp(`\\b${name}\\b`).test(withoutTags)
}

const files = walk(SRC)
const rel = (f) => path.relative(SRC, f)

describe('JSX tanımsız bileşen bekçisi', () => {
  it('kaynak ağacı taranabiliyor (boş liste = yanlış kök)', () => {
    expect(files.length).toBeGreaterThan(50)
  })

  it('JSX\'te kullanılan her bileşen o dosyada tanımlı', () => {
    const hits = []
    for (const f of files) {
      const cleaned = stripCommentsAndStrings(fs.readFileSync(f, 'utf8'))
      const seen = new Set()
      for (const use of jsxComponents(cleaned)) {
        if (BUILTIN.has(use.name) || seen.has(use.name)) continue
        if (mentionedOutsideJsxTag(cleaned, use.name)) continue
        seen.add(use.name)
        hits.push(`${rel(f)}:${use.line} → <${use.name}> hiçbir yerde tanımlı/anılı değil  |  ${use.text}`)
      }
    }
    expect(
      hits,
      `JSX'te tanımsız bileşen(ler):\n${hits.join('\n')}\n` +
      'Import satırını eklemeyi unutmuş olabilirsiniz (bkz. 2026-08-19 ProgressBar olayı).'
    ).toEqual([])
  })

  it('bekçinin kendisi çalışıyor — uydurma bir bileşen yakalanır', () => {
    // Bekçi sessizce boşa düşerse (regex bozulursa) bu test kırılır.
    const sample = 'import { A } from "x"\nexport default () => <A><Uydurma /></A>\n'
    const cleaned = stripCommentsAndStrings(sample)
    const undef = jsxComponents(cleaned)
      .filter(u => !mentionedOutsideJsxTag(cleaned, u.name))
      .map(u => u.name)
    expect(undef).toEqual(['Uydurma'])
  })

  it('yorum içindeki JSX örneği yanlış alarm üretmez', () => {
    const sample = '// <ProgressBar /> kullanın\nconst s = "<Spinner />"\nexport default () => null\n'
    const cleaned = stripCommentsAndStrings(sample)
    expect(jsxComponents(cleaned)).toEqual([])
  })
})
