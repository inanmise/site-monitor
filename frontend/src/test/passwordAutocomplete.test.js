import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Parola alanı autocomplete kapısı (2026-09-28, kullanıcı: "Anahtar Çözümleme ekranına tıkladığımda sol menüdeki arama
 * alanına admin yazılıyor, 'eşleşen bölüm yok' diyor").
 *
 * <p>Kök: Chrome `autocomplete="off"` değerini PAROLA alanlarında yok sayar; sayfada parola alanı görünce onu giriş formu
 * sanıp kayıtlı hesabı doldurur — kullanıcı adını parolanın ÖNÜNDEKİ ilk metin kutusuna (Ayarlar menü araması) yazar.
 * Kural: kaynaktaki her parola alanı niyetini açıkça söyler — kişinin kendi mevcut parolası `current-password`, yeni /
 * başkasının parolası / sır değeri `new-password` (tarayıcı kayıtlı hesap doldurmaz). `off` ya da eksik = kırmızı.
 */
const SRC = path.resolve(__dirname, '..')

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'node_modules') walk(p, out) }
    else if (/\.jsx?$/.test(e.name)) out.push(p)
  }
  return out
}

/** JSX açılış etiketinin sonu: `{…}` derinliği 0 iken gelen ilk `>` (prop içindeki `=>` ok işlevi sayılmaz). */
function tagEnd(src, from) {
  let depth = 0
  for (let i = from; i < src.length; i++) {
    const c = src[i]
    if (c === '{') depth++
    else if (c === '}') depth--
    else if (c === '>' && depth === 0) return i
  }
  return src.length - 1
}

/** Parola türü taşıyan JSX öğelerinin açılış etiketleri. */
function passwordElements(src) {
  const out = []
  const re = /type=(?:"password"|\{[^}]*'password'[^}]*\})/g
  let m
  while ((m = re.exec(src)) !== null) {
    const start = src.lastIndexOf('<', m.index)
    out.push({ at: src.slice(0, m.index).split('\n').length, tag: src.slice(start, tagEnd(src, start) + 1) })
  }
  return out
}

export const AUTOCOMPLETE_OK = /autoComplete="(new-password|current-password)"/

describe('parola alanı autocomplete kapısı', () => {
  const files = walk(SRC)

  it('tarama gerçekten parola alanı buluyor (seçici bozulup sessizce yeşil kalmasın)', () => {
    const count = files.reduce((n, f) => n + passwordElements(fs.readFileSync(f, 'utf8')).length, 0)
    expect(count).toBeGreaterThanOrEqual(8)
  })

  it('her parola alanı autoComplete="new-password" ya da "current-password" söyler (off / eksik YASAK)', () => {
    const bad = []
    for (const f of files) {
      for (const el of passwordElements(fs.readFileSync(f, 'utf8'))) {
        if (!AUTOCOMPLETE_OK.test(el.tag)) bad.push(`${path.relative(SRC, f)}:${el.at}`)
      }
    }
    expect(bad, 'Chrome parola alanında "off"u yok sayar ve kayıtlı kullanıcı adını öndeki metin kutusuna yazar').toEqual([])
  })

  it('kural örnekleri: eksik ve "off" yakalanır, doğru değerler geçer', () => {
    const tags = (s) => passwordElements(s).map((e) => AUTOCOMPLETE_OK.test(e.tag))
    expect(tags('<Input type="password" value={x} />')).toEqual([false])
    expect(tags('<Input type="password" autoComplete="off" />')).toEqual([false])
    expect(tags("<Input type={a ? 'text' : 'password'} autoComplete=\"new-password\" />")).toEqual([true])
    expect(tags('<Input type="password"\n  autoComplete="current-password" />')).toEqual([true])
    // ok işlevli onChange öncesinde durmaz — sonraki satırdaki autoComplete görülür
    expect(tags('<Input type="password" onChange={(e) => set(e.target.value)}\n  autoComplete="new-password" />')).toEqual([true])
    expect(tags('<Input type="password" onChange={(e) => set(e.target.value)} />')).toEqual([false])
  })
})
