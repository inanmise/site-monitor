import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  MANIFEST_VERSION, MANIFEST_PATH, REGEN_HINT, FRONTEND_DIR,
  guideSources, toolchainFiles, readManifest, sha256, inspectPdf, outputFor,
} from '../../scripts/whitepaperManifest.mjs'
import { duplicateSlugs } from '../utils/mdToc.js'

/**
 * Kılavuz PDF'i bayatlama kapısı.
 *
 * Sorun: PDF eskiden elle üretilip commit'lenmiş statik bir dosyaydı; kılavuz her
 * değiştiğinde sessizce geride kalıyordu ve bunu yakalayan hiçbir kapı yoktu.
 * Bu test kaynakların hash'ini üreticinin yazdığı manifest ile karşılaştırır.
 *
 * TARAYICI GEREKTİRMEZ — saf fs/crypto. Bu yüzden mevcut frontend CI işinde bedelsiz koşar.
 * Kırmızıysa:  cd frontend && npm run gen:guide-pdf
 */
const sources = guideSources()
const manifest = readManifest()

describe('kılavuz PDF tazeliği', () => {
  it('manifest mevcut ve üretici sürümüyle uyumlu', () => {
    expect(existsSync(MANIFEST_PATH), `Manifest yok — ${REGEN_HINT}`).toBe(true)
    expect(manifest.manifestVersion, REGEN_HINT).toBe(MANIFEST_VERSION)
  })

  it('manifest diskteki TÜM kılavuz dosyalarını tanıyor', () => {
    // Yeni bir dil dosyası eklenip üretici haberdar edilmezse düz hash karşılaştırması
    // bunu sessizce geçerdi; küme eşitliği o boşluğu kapatır.
    const onDisk = sources.map((s) => s.relKey).sort()
    const inManifest = Object.keys(manifest.sources).sort()
    expect(inManifest, `Kılavuz dosyaları ile manifest ayrışmış — ${REGEN_HINT}`).toEqual(onDisk)
  })

  it.each(sources.map((s) => [s.relKey, s]))('%s kaynağı PDF ile aynı sürümde', (relKey, s) => {
    expect(sha256(s.absPath), REGEN_HINT).toBe(manifest.sources[relKey].sha256)
  })

  it.each(toolchainFiles().map((t) => [t.relKey, t]))('%s (yerleşim) değişmemiş', (relKey, t) => {
    // Print CSS de çıktının parçasıdır: stil değişip PDF üretilmezse görünüm ayrışır.
    expect(sha256(t.absPath), REGEN_HINT).toBe(manifest.toolchain[relKey].sha256)
  })

  it.each(sources.map((s) => [s.lang]))('%s PDF çıktısı var, hash tutuyor ve sağlam', (lang) => {
    const out = outputFor(lang)
    expect(existsSync(out.absPath), `${out.relKey} yok — ${REGEN_HINT}`).toBe(true)

    const entry = manifest.outputs[out.relKey]
    expect(entry, `${out.relKey} manifest'te yok — ${REGEN_HINT}`).toBeTruthy()
    expect(
      sha256(out.absPath),
      `PDF manifest ile uyuşmuyor — manifest commit edilmiş ama PDF edilmemiş olabilir.\n${REGEN_HINT}`
    ).toBe(entry.sha256)

    // Hash kapısı PDF'in BOŞ olmadığını söyleyemez — yapısal kontrol onu kapatır.
    const info = inspectPdf(out.absPath)
    expect(info.isPdf, `${out.relKey} geçerli bir PDF değil`).toBe(true)
    expect(info.hasEof, `${out.relKey} eksik/bozuk (kuyrukta %%EOF yok)`).toBe(true)
    expect(info.bytes, `${out.relKey} şüpheli derecede küçük`).toBeGreaterThan(200_000)
    expect(info.pages, `${out.relKey} beklenenden az sayfa içeriyor`).toBeGreaterThanOrEqual(20)
  })

  it.each(sources.map((s) => [s.relKey, s]))('%s sürüm damgasını {{VERSION}} ile yazar', (relKey, s) => {
    // Elle yazılan sürüm her release'de eskiyordu (kılavuz 20.23.0 derken uygulama 20.24.1).
    // Damga artık token; HelpPage __APP_VERSION__ ile, PDF üreticisi kök VERSION ile çözer.
    const text = readFileSync(s.absPath, 'utf8')
    expect(text, 'sürüm damgası {{VERSION}} token\'ı içermeli').toContain('{{VERSION}}')

    const current = readFileSync(join(FRONTEND_DIR, '..', 'VERSION'), 'utf8').trim()
    const hardcoded = text.split('\n')
      .map((l, i) => (l.includes(current) ? `${relKey}:${i + 1} → ${l.trim()}` : null))
      .filter(Boolean)
    expect(
      hardcoded,
      `Kılavuzda sabit sürüm numarası var; {{VERSION}} kullanın:\n${hardcoded.join('\n')}`
    ).toEqual([])
  })

  it.each(sources.map((s) => [s.relKey, s]))('%s içinde çakışan başlık slugu yok', (relKey, s) => {
    // Çakışma TOC bağlantısını yanlış hedefe götürür; slugify bilinçli olarak saf tutuluyor
    // (sayaçlı slugger HelpPage'in iki geçişi arasında kayar), kontrol bu yüzden burada.
    const dupes = duplicateSlugs(readFileSync(s.absPath, 'utf8'))
    expect(dupes, `Çakışan başlık slug'ları: ${dupes.join(', ')}`).toEqual([])
  })
})
