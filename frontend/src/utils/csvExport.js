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
