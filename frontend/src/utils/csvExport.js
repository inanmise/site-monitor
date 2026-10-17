import { csvRows } from './csv.js'

/**
 * CSV dışa aktarım (2026-09-20, Yönetim Paneli): BOM'lu UTF-8 (Excel Türkçe karakterleri doğru açsın);
 * ayırıcı, kaçış ve formül nötrlemesi projenin ORTAK yardımcısından (utils/csv.js — csv-escape-guard kapısı).
 *
 * @param {string[]} headers   sütun başlıkları
 * @param {Array<Array<any>>} rows  hücre matrisi (dizi hücreler ", " ile birleşir)
 */
export function toCsv(headers, rows) {
  const flat = (r) => r.map(v => (Array.isArray(v) ? v.join(', ') : v))
  return '﻿' + csvRows([headers, ...rows.map(flat)]) + '\r\n'
}

/** Tarayıcıda indir — dosya adı tarih damgalı; jsdom'da (test) sessizce çıkar. */
export function downloadCsv(filename, csv) {
  try {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* indirme ortamı yok */ }
}

export function stampedName(base) {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${base}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.csv`
}

/**
 * Metin dosyası indir (2026-10-11, postmortem Markdown dışa aktarımı) — {@link downloadCsv} ile aynı yol, tür parametreli.
 * downloadCsv'nin gövdesi (karakterizasyon testi) DEĞİŞMEDİ.
 */
export function downloadText(filename, text, type = 'text/plain;charset=utf-8') {
  try {
    const blob = new Blob([text], { type })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* indirme ortamı yok */ }
}

/**
 * Hazır bir Blob'u indir (2026-10-17: kanalın yıl arşivi ZIP'i, Ayarlar kanal özeti Excel'i) — {@link downloadCsv} ile aynı
 * yol. Büyük dosyada (yıllık PDF arşivi) tarayıcı indirmeyi başlatmadan adres iptal edilmesin diye bağlantı 60 sn tutulur.
 * jsdom'da (test) sessizce çıkar. Yukarıdaki gövdeler DEĞİŞMEDİ.
 */
export function downloadBlob(filename, blob, revokeAfterMs = 60000) {
  try {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), revokeAfterMs)
  } catch { /* indirme ortamı yok */ }
}
