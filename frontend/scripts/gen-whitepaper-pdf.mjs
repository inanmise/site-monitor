// Kullanım Kılavuzu PDF'lerini markdown kaynağından üretir.
// Çalıştır:  npm run gen:guide-pdf            (tüm diller)
//            npm run gen:guide-pdf -- --lang=tr
//            npm run gen:guide-pdf -- --check  (yazmaz; yalnız üretilebildiğini kanıtlar)
//
// Neden Playwright'ın Chromium'u: page.pdf() üç şeyi birden veriyor ve tarayıcı zaten
// devDependency olarak kurulu —
//   outline:true  → başlıklardan gerçek PDF yer imi ağacı
//   tagged:true   → markdown'daki İçindekiler bağlantılarının tıklanabilir olması
//   footerTemplate→ sayfa numaralı altbilgi
// Ayrıca page.evaluate() ile ASCII diyagramların genişliğini ÖLÇÜP yazı boyutunu
// küçültebiliyoruz; harici bir "print-to-pdf" komutunda bu geri besleme yok.
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkGfm from 'remark-gfm'
import remarkRehype from 'remark-rehype'
import rehypeStringify from 'rehype-stringify'
import { chromium } from '@playwright/test'

import { slugify } from '../src/utils/mdToc.js'
import {
  FRONTEND_DIR, PUBLIC_DIR, MANIFEST_PATH, PRINT_CSS_PATH, MANIFEST_VERSION,
  guideSources, toolchainFiles, outputFor, fileEntry, inspectPdf,
} from './whitepaperManifest.mjs'

/* ── Yerelleştirilmiş kapak ve altbilgi metinleri ─────────────────────────── */
const STRINGS = {
  tr: {
    title: 'Site Monitor',
    subtitle: 'Kurumsal İzleme Platformu — Kullanım Kılavuzu',
    versionLabel: 'Sürüm',
    dateLabel: 'Belge tarihi',
    langLabel: 'Dil',
    langName: 'Türkçe',
    footer: 'Site Monitor · Kullanım Kılavuzu',
    pageWord: 'Sayfa',
  },
  en: {
    title: 'Site Monitor',
    subtitle: 'Enterprise Monitoring Platform — User Guide',
    versionLabel: 'Version',
    dateLabel: 'Document date',
    langLabel: 'Language',
    langName: 'English',
    footer: 'Site Monitor · User Guide',
    pageWord: 'Page',
  },
}

/* ── Markdown → HTML ──────────────────────────────────────────────────────── */

/** Başlıklara uygulamayla AYNI slug'ı verir (mdToc.js paylaşılır).
 *  rehype-slug KULLANILMAZ: github-slugger Türkçe harfleri korur ve kılavuzdaki
 *  elle yazılmış çapalarla (#1-yonetici-ozeti) uyuşmaz — her bağlantı kırılırdı. */
function rehypeSharedHeadingIds() {
  const text = (node) =>
    node.type === 'text' ? node.value : (node.children ?? []).map(text).join('')
  return (tree) => {
    const walk = (node) => {
      if (node.type === 'element' && /^h[1-6]$/.test(node.tagName)) {
        node.properties = { ...node.properties, id: slugify(text(node)) }
      }
      ;(node.children ?? []).forEach(walk)
    }
    walk(tree)
  }
}

/** Kod bloklarını satır sayısı/genişliğine göre etiketler — CSS ve ölçüm bunu kullanır. */
function rehypeMarkCodeBlocks() {
  const text = (node) =>
    node.type === 'text' ? node.value : (node.children ?? []).map(text).join('')
  return (tree) => {
    const walk = (node) => {
      if (node.type === 'element' && node.tagName === 'pre') {
        const lines = text(node).replace(/\n$/, '').split('\n')
        const cols = lines.reduce((m, l) => Math.max(m, [...l].length), 0)
        const cls = lines.length <= 25 ? ['short'] : []
        node.properties = { ...node.properties, className: cls, 'data-cols': String(cols) }
      }
      ;(node.children ?? []).forEach(walk)
    }
    walk(tree)
  }
}

const processor = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(remarkRehype)
  .use(rehypeSharedHeadingIds)
  .use(rehypeMarkCodeBlocks)
  .use(rehypeStringify)

/* ── Varlıklar ────────────────────────────────────────────────────────────── */
const b64 = (p) => readFileSync(p).toString('base64')

function fontFaces() {
  const f = (file) => b64(join(PUBLIC_DIR, 'fonts', file))
  return `
@font-face { font-family:'Roboto'; font-style:normal; font-weight:400;
  src:url(data:font/ttf;base64,${f('Roboto-Regular.ttf')}) format('truetype'); }
@font-face { font-family:'Roboto'; font-style:normal; font-weight:700;
  src:url(data:font/ttf;base64,${f('Roboto-Bold.ttf')}) format('truetype'); }
/* Kutu-çizim karakterleri (U+2500 bloğu) Roboto'da YOK; ASCII diyagramlar bu fontla çizilir. */
@font-face { font-family:'DejaVuSansMono'; font-style:normal; font-weight:400;
  src:url(data:font/ttf;base64,${f('DejaVuSansMono.ttf')}) format('truetype'); }`
}

function coverHtml(lang, version, dateStr) {
  const s = STRINGS[lang] ?? STRINGS.tr
  const logo = b64(join(PUBLIC_DIR, 'brand', 'logo-ok-512.png'))
  return `<section class="cover">
  <img src="data:image/png;base64,${logo}" alt="">
  <div class="cover-title">${s.title}</div>
  <div class="cover-subtitle">${s.subtitle}</div>
  <div class="cover-rule"></div>
  <div class="cover-meta">
    ${s.versionLabel}: ${version}<br>
    ${s.dateLabel}: ${dateStr}<br>
    ${s.langLabel}: ${s.langName}
  </div>
</section>`
}

function footerTemplate(lang) {
  const s = STRINGS[lang] ?? STRINGS.tr
  // DİKKAT: bu şablon sayfa stilini MİRAS ALMAZ ve gömülü fontlara erişemez.
  // Arial + ASCII-güvenli metin; font-size açıkça verilmezse Chromium sıfıra yakın basar.
  return `<div style="font-family:Arial,sans-serif;font-size:8px;color:#7a848f;width:100%;
    padding:0 16mm;display:flex;justify-content:space-between;">
    <span>${s.footer}</span>
    <span>${s.pageWord} <span class="pageNumber"></span> / <span class="totalPages"></span></span>
  </div>`
}

/* ── Taşan kod bloklarını sığdır ──────────────────────────────────────────── */
/**
 * Diyagramların bir kısmı 200 sütunu aşıyor. Sabit bir formül yerine tarayıcıda ölçüyoruz:
 * önce yazı boyutunu küçültüyoruz, 8px tabanında hâlâ sığmayanı yatay sayfaya alıyoruz.
 * Sarma (pre-wrap) kullanılmaz — kutu çizimini okunamaz hâle getirir.
 */
async function fitCodeBlocks(page) {
  return page.evaluate(() => {
    const MIN_PX = 8

    // DİKKAT: `overflow: visible` olan bir blokta scrollWidth === clientWidth'tir —
    // taşan içerik ölçüye girmez, yalnız dışarı boyanır. Gerçek içerik genişliği için
    // düğüm içeriği üzerinde bir Range ölçülür.
    const contentWidth = (pre) => {
      const r = document.createRange()
      r.selectNodeContents(pre)
      return r.getBoundingClientRect().width
    }

    const headingOf = (el) => {
      let n = el
      while (n) {
        if (/^H[1-4]$/.test(n.tagName)) return n.textContent.trim()
        n = n.previousElementSibling ?? n.parentElement
      }
      return '(başlıksız)'
    }

    /** Blok sığana kadar yazı boyutunu küçültür; taban MIN_PX. Sığdıysa true. */
    const shrinkToFit = (pre) => {
      const avail = pre.clientWidth
      let content = contentWidth(pre)
      if (content <= avail) return true
      const start = parseFloat(getComputedStyle(pre).fontSize)
      const target = Math.max(MIN_PX, (start * avail) / content * 0.98)
      pre.style.fontSize = `${target}px`
      content = contentWidth(pre)
      return content <= pre.clientWidth
    }

    const shrunk = []
    const widened = []
    const overflowing = []

    for (const pre of document.querySelectorAll('pre')) {
      if (contentWidth(pre) <= pre.clientWidth) continue
      shrunk.push(pre)
      if (shrinkToFit(pre)) continue

      // Portrede tabana inildi, hâlâ sığmıyor → yatay sayfaya taşı ve yeniden dene
      pre.classList.add('wide')
      pre.style.fontSize = ''
      widened.push(pre)
      if (!shrinkToFit(pre)) {
        overflowing.push({ heading: headingOf(pre), cols: pre.dataset.cols ?? '?' })
      }
    }
    return { shrunk: shrunk.length, widened: widened.length, overflowing }
  })
}

/* ── Üretim ───────────────────────────────────────────────────────────────── */
async function buildPdf(browser, source, { version, dateStr, outPath }) {
  const md = readFileSync(source.absPath, 'utf8')
  const body = String(await processor.process(md))
  const css = readFileSync(PRINT_CSS_PATH, 'utf8')

  const html = `<!doctype html><html lang="${source.lang}"><head><meta charset="utf-8">
<style>${fontFaces()}</style><style>${css}</style></head>
<body>${coverHtml(source.lang, version, dateStr)}<div class="sheet">${body}</div></body></html>`

  const page = await browser.newPage()
  try {
    await page.setContent(html, { waitUntil: 'load' })
    // Ölçüm baskı düzeninde yapılmalı; ekran düzeninde @media/@page kuralları uygulanmaz.
    await page.emulateMedia({ media: 'print' })
    await page.evaluate(() => document.fonts.ready)
    const fit = await fitCodeBlocks(page)
    for (const o of fit.overflowing) {
      console.warn(`  ! taşan diyagram (${o.cols} sütun) — bölüm: ${o.heading}`)
    }
    await page.pdf({
      path: outPath,
      preferCSSPageSize: true,
      printBackground: true,
      outline: true,
      tagged: true,
      displayHeaderFooter: true,
      headerTemplate: '<span></span>',
      footerTemplate: footerTemplate(source.lang),
    })
    return fit
  } finally {
    await page.close()
  }
}

async function main() {
  const args = process.argv.slice(2)
  const check = args.includes('--check')
  const langArg = args.find((a) => a.startsWith('--lang='))?.split('=')[1]

  const version = readFileSync(join(FRONTEND_DIR, '..', 'VERSION'), 'utf8').trim()
  const dateStr = new Date().toISOString().slice(0, 10)

  let sources = guideSources()
  if (langArg) sources = sources.filter((s) => s.lang === langArg)
  if (!sources.length) {
    console.error(`Kaynak bulunamadı${langArg ? ` (--lang=${langArg})` : ''}.`)
    process.exit(1)
  }

  const scratch = check ? mkdtempSync(join(tmpdir(), 'guide-pdf-')) : null
  const browser = await chromium.launch()
  const produced = []
  try {
    for (const source of sources) {
      const out = outputFor(source.lang)
      const outPath = check ? join(scratch, `${source.lang}.pdf`) : out.absPath
      process.stdout.write(`${source.file} → ${check ? '(kontrol)' : out.relKey}\n`)
      const fit = await buildPdf(browser, source, { version, dateStr, outPath })
      const info = inspectPdf(outPath)
      console.log(
        `  ${info.pages} sayfa · ${(info.bytes / 1024 / 1024).toFixed(2)} MB · ` +
        `${fit.shrunk} blok küçültüldü, ${fit.widened} blok yatay sayfaya alındı`
      )
      if (!info.isPdf || !info.hasEof || info.pages < 20) {
        console.error('  HATA: üretilen PDF beklenen yapıda değil.')
        process.exitCode = 1
      }
      produced.push({ source, out, outPath })
    }
  } finally {
    await browser.close()
  }

  if (check) {
    rmSync(scratch, { recursive: true, force: true })
    console.log('Kontrol modu: dosya yazılmadı.')
    return
  }

  const manifest = {
    manifestVersion: MANIFEST_VERSION,
    generatedAt: new Date().toISOString(),
    appVersion: version,
    sources: {},
    toolchain: {},
    outputs: {},
  }
  for (const s of guideSources()) manifest.sources[s.relKey] = fileEntry(s.absPath)
  for (const t of toolchainFiles()) manifest.toolchain[t.relKey] = fileEntry(t.absPath)
  for (const p of produced) {
    manifest.outputs[p.out.relKey] = { ...fileEntry(p.out.absPath), pages: inspectPdf(p.out.absPath).pages }
  }
  writeFileSync(MANIFEST_PATH, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  console.log(`manifest yazıldı → scripts/whitepaper.manifest.json`)
}

// Yan etkiler yalnız doğrudan çalıştırıldığında; test bu dosyayı import ETMEZ ama
// ileride ederse playwright'ı tetiklemesin.
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => { console.error(e); process.exit(1) })
}
