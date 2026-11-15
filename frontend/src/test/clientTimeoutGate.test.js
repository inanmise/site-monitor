import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

/**
 * Kaynak kapısı — "zaman aşımı olmayan istek yok" (kullanıcı kuralı, 2026-11-15). `src/**` (testler hariç) içinde:
 *
 * <ol>
 *   <li>`timeoutMs: 0` YASAK — eskiden "sınırsız" demekti; artık `request()` sıfırı yönteme göre varsayılana çevirir, ama
 *       kaynakta görünmesi okuyanı yanıltır. Uzun süren uç sonlu, AÇIK bir değer verir (api/client.js LIVE_CHECK / REPORT /
 *       LONG_JOB / SQL_EXPORT sabitleri).</li>
 *   <li>Ham `fetch(` çağrısı YALNIZ bir sinyal (`signal`) taşıyorsa geçer — sinyal bir son tarihle bağlanmış olmalı
 *       (`utils/timedFetch.js` `timedFetch` / `deadlineSignal`, ya da kendi zamanlayıcılı AbortController). Gövdesi okunan
 *       her ham çağrı `timedFetch` kullanır (son tarih gövde okunana dek sürer).</li>
 *   <li>`new XMLHttpRequest` yalnız `api/client.js`'te (yükleme taşıyıcısı — `request()`'in son tarih sinyalini dinler).</li>
 * </ol>
 *
 * <p>`ALLOWED` gerekçeli istisna listesidir ve YALNIZ küçülebilir: listedeki bir yer düzeltilince test bunu söyler, satır silinir.
 */
const ROOT = join(process.cwd(), 'src')
const ALLOWED = {
  // dosya (src'ye göre, '/' ayırıcı) → izin verilen ihlal sayısı (gerekçe yorumda). Şu an istisna YOK.
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) {
      if (p === join(ROOT, 'test')) continue
      walk(p, out)
    } else if (/\.(jsx?|mjs)$/.test(name)) {
      out.push(p)
    }
  }
  return out
}

/** Bir aralığı satır sonları korunarak boşlukla örter (konumlar ve satır numaraları değişmez). */
const blank = (s) => s.replace(/[^\n]/g, ' ')
/** Bu karakterden sonra gelen `/` bir düzenli ifade başlatır (bölme değil) — basit, bilinen sezgi. */
const REGEX_PREV = new Set('(,=:[!&|?{};+-*%<>~^'.split(''))

/**
 * Yalnız KOD kalır (konumlar korunur): satır / blok yorumları ve dizge, şablon dizgesi ve düzenli ifade İÇERİKLERİ boşlukla
 * örtülür (tırnaklar kalır). Doğrusal, dilim tabanlı (karakter karakter birleştirme yok — büyük sözlük dosyaları hızlı).
 * Bilinen sınır: şablon dizgesinin `${…}` içindeki kod da örtülür; iç içe şablon dizgesi taramayı kaydırabilir (kaynakta yok).
 */
export function codeOnly(src) {
  const parts = []
  const n = src.length
  let i = 0
  let start = 0
  let prev = ''
  const cover = (a, b) => parts.push(blank(src.slice(a, b)))
  while (i < n) {
    const c = src[i]
    if (c === '"' || c === "'" || c === '`') {
      parts.push(src.slice(start, i + 1))
      let j = i + 1
      while (j < n && src[j] !== c && (c === '`' || src[j] !== '\n')) j += src[j] === '\\' ? 2 : 1
      const end = Math.min(j, n)
      cover(i + 1, end)
      if (end < n && src[end] === c) { parts.push(c); i = end + 1 } else i = end
      start = i
      prev = c
      continue
    }
    if (c === '/' && src[i + 1] === '/') {
      parts.push(src.slice(start, i))
      let j = src.indexOf('\n', i)
      if (j < 0) j = n
      cover(i, j)
      i = j
      start = i
      continue
    }
    if (c === '/' && src[i + 1] === '*') {
      parts.push(src.slice(start, i))
      let j = src.indexOf('*/', i + 2)
      j = j < 0 ? n : j + 2
      cover(i, j)
      i = j
      start = i
      continue
    }
    if (c === '/' && (prev === '' || REGEX_PREV.has(prev))) {
      let j = i + 1
      let inClass = false
      while (j < n && src[j] !== '\n') {
        const d = src[j]
        if (d === '\\') { j += 2; continue }
        if (d === '[') inClass = true
        else if (d === ']') inClass = false
        else if (d === '/' && !inClass) break
        j++
      }
      if (j < n && src[j] === '/') {
        parts.push(src.slice(start, i + 1))
        cover(i + 1, j)
        parts.push('/')
        i = j + 1
        start = i
        prev = '/'
        continue
      }
    }
    if (c !== ' ' && c !== '\t' && c !== '\n' && c !== '\r') prev = c
    i++
  }
  parts.push(src.slice(start))
  return parts.join('')
}

/** `fetch(` çağrısının argüman metni (eşleşen kapanış parantezine dek; en çok 4000 karakter — sınırlı). */
function callArgs(src, openIdx) {
  let depth = 0
  const end = Math.min(src.length, openIdx + 4000)
  for (let i = openIdx; i < end; i++) {
    const c = src[i]
    if (c === '(' || c === '{' || c === '[') depth++
    else if (c === ')' || c === '}' || c === ']') {
      depth--
      if (depth === 0) return src.slice(openIdx, i + 1)
    }
  }
  return src.slice(openIdx, end)
}

/** Bir kaynak metindeki ihlaller: `[{ kind, line }]`. */
export function violations(rawSrc, file = '') {
  // Ön süzgeç: ilgili simge geçmeyen dosya (ör. büyük sözlükler) taranmaz
  if (!/fetch|timeoutMs|XMLHttpRequest/.test(rawSrc)) return []
  const src = codeOnly(rawSrc)
  const out = []
  const lineOf = (idx) => src.slice(0, idx).split('\n').length
  for (const m of src.matchAll(/timeoutMs\s*:\s*0(?![\d_.xXbBoOeE])/g)) out.push({ kind: 'timeoutMs: 0', line: lineOf(m.index) })
  for (const m of src.matchAll(/(^|[^.\w$])fetch\s*\(/g)) {
    const open = m.index + m[0].length - 1
    if (!/\bsignal\b/.test(callArgs(src, open))) out.push({ kind: 'fetch( sinyalsiz', line: lineOf(open) })
  }
  if (!file.endsWith('api/client.js')) {
    for (const m of src.matchAll(/new\s+XMLHttpRequest\b/g)) out.push({ kind: 'XMLHttpRequest', line: lineOf(m.index) })
  }
  return out
}

describe('zaman aşımı kapısı (kaynak tarama)', () => {
  it('kendini sınar: sıfır süre, sinyalsiz fetch ve XHR yakalanır; yorum içi ve sinyalli çağrı geçer', () => {
    const bad = [
      "request('/x', { timeoutMs: 0 })",
      "const r = await fetch('/api/a')",
      "fetch(url, { method: 'POST', body })",
      'const x = new XMLHttpRequest()',
    ].join('\n')
    expect(violations(bad, 'src/x.js').map((v) => v.kind)).toEqual(['timeoutMs: 0', 'fetch( sinyalsiz', 'fetch( sinyalsiz', 'XMLHttpRequest'])
    const good = [
      '// fetch(\'/api/eski\') ve timeoutMs: 0 yorumda',
      '/* fetch(url) */',
      "request('/x', { timeoutMs: 20_000 })",
      "await fetch(u, { signal: ac.signal })",
      'await timedFetch(u, {}, 3000)',
      'src.fetch()',
      "const s = 'fetch(' + x",
      'const re = /fetch\\(/; const t = `timeoutMs: 0 ${a}`',
      "const d = a / b; fetch(u, { signal })",
    ].join('\n')
    expect(violations(good, 'src/y.js')).toEqual([])
    // Konum korunur: dizge / yorum örtüldükten sonra satır numarası doğru
    expect(violations("// x\n'a'\nfetch('/b')", 'src/z.js')).toEqual([{ kind: 'fetch( sinyalsiz', line: 3 }])
  })

  it('src/** içinde sıfır süre, sinyalsiz ham fetch ve client dışı XHR yok (istisna listesi yalnız küçülür)', () => {
    const found = {}
    for (const f of walk(ROOT)) {
      const rel = relative(ROOT, f).split(sep).join('/')
      const v = violations(readFileSync(f, 'utf8'), rel)
      if (v.length) found[rel] = v
    }
    const over = Object.entries(found)
      .filter(([rel, v]) => v.length > (ALLOWED[rel] || 0))
      .map(([rel, v]) => `${rel}: ${v.map((x) => `${x.kind} @${x.line}`).join(', ')}`)
    expect(over, 'Süresiz istek: sonlu bir timeoutMs ya da utils/timedFetch.js kullanın').toEqual([])
    const stale = Object.entries(ALLOWED).filter(([rel, n]) => (found[rel]?.length || 0) < n).map(([rel]) => rel)
    expect(stale, 'İstisna düzeldi: ALLOWED listesinden düşürün (liste yalnız küçülür)').toEqual([])
  }, 60_000)   // ~1 400 dosya okunur (normalde ~2 sn); yoğun makinede varsayılan 15 sn yetmeyebilir
})
