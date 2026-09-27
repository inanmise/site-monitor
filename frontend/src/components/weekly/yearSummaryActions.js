import { buildYearSummaryCsv, buildYearSummaryHtml } from './weeklyModel.js'

/*
 * Yönetici yıl özeti eylemleri (2026-09-13, ikinci tur; 2026-09-27 başlığın "Dışa aktar" menüsüne taşındı):
 * takım × hafta matrisinin CSV'si ve kendi başına yazdırılabilir A4 yatay belgesi (buildYearSummaryHtml — uygulama
 * CSS'inden bağımsız; gizli iframe → tarayıcı "PDF olarak kaydet").
 */

/** Tarayıcıda dosya indirir (BOM'lu UTF-8 CSV — Excel Türkçe karakterleri doğru açar). */
export function downloadCsv(csv, fileName) {
  try {
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = fileName; document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* jsdom */ }
}

export function downloadYearSummaryCsv(data, t) {
  downloadCsv(buildYearSummaryCsv(data, t), `haftalik-yil-ozeti-${data?.year ?? ''}.csv`)
}

/** Gizli iframe: uygulama CSS'inden bağımsız, A4 yatay tek sayfa; yazdırma bitince kaldırılır. */
export function printYearSummary(data, t) {
  const html = buildYearSummaryHtml(data, t)
  try {
    const f = document.createElement('iframe')
    f.setAttribute('aria-hidden', 'true'); f.setAttribute('title', 'print'); f.className = 'wrc-print-frame'
    document.body.appendChild(f)
    const d = f.contentDocument; d.open(); d.write(html); d.close()
    const w = f.contentWindow
    const done = () => setTimeout(() => { try { f.remove() } catch { /* yoksay */ } }, 1500)
    w.onafterprint = done
    setTimeout(() => { try { w.focus(); w.print() } catch { done() } }, 60)
  } catch { /* jsdom */ }
}
