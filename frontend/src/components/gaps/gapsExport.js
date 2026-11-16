// Eksikler penceresi — dışa aktarma (2026-11-15, D paketi): "Listeyi kopyala" (düz metin) ve Excel. TEMBEL modül — pencere
// yalnız tıklamada import() ile alır (Excel yazıcısı açılış paketine girmez; lazyChunks / manualCertExtractLazy kapıları).
// Görünen (gizlenmemiş) eksikler, ekrandaki sırayla; kalemler pencerede gösterilenlerle aynı (kural başına en çok 5 + "N kayıt
// daha"). Kişi verisi: yalnız pencerede zaten görünen adlar (kayıt / takım adları).

import { copyText } from '../../utils/copyText.js'
import { dateLocale } from '../../i18n/dateLocale.js'
import { downloadBlob } from '../../utils/csvExport.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { GAP_CATEGORIES } from './gapsModel.js'
import { gapItemLine, gapTextKeys, uncheckedLines } from './gapsText.js'

function stamp(at) {
  const d = new Date(Number.isFinite(at) ? at : Date.now())
  try {
    return new Intl.DateTimeFormat(dateLocale(), { timeZone: 'Europe/Istanbul', dateStyle: 'short', timeStyle: 'short' }).format(d)
  } catch {
    return d.toISOString()
  }
}

/**
 * Satırlar: [kategori, önem, eksik, takım, kayıt sayısı, kayıt, ayrıntı, neden önemli, ne yapmalı]. Liste TAM değilse
 * (okunamayan kaynak — 2026-11-17) sonuna "Denetlenemedi" satırları eklenir: boş tablo "eksik yok" diye okunmasın.
 */
export function gapRows(gaps, t, opts = {}) {
  const rows = []
  for (const c of GAP_CATEGORIES) {
    for (const g of gaps?.byCategory?.[c] || []) {
      const k = gapTextKeys(g)
      const base = [t(`gaps.cat.${c}`), t(`dq.severity.${g.severity}`), t(k.title), g.teamName || '', g.category === 'profile' ? '' : g.failing,
      ]
      const why = t(k.why)
      const fix = k.fix ? t(k.fix) : ''
      const items = Array.isArray(g.items) ? g.items : []
      if (items.length === 0) rows.push([...base, '', '', why, fix])
      items.forEach((it, i) => {
        const line = gapItemLine(g, it, t)
        rows.push([...base, line.name, line.sub || '', i === 0 ? why : '', i === 0 ? fix : ''])
      })
      if (g.more > 0) rows.push([...base, t('gaps.export.more', g.more), '', '', ''])
    }
  }
  for (const line of uncheckedLines(gaps, t, opts)) rows.push([t('gaps.export.uncheckedCat'), '', line, '', '', '', '', '', ''])
  return rows
}

/**
 * Düz metin (panoya): başlık, kategoriler, eksikler ve kalemleri. Okunamayan kaynak varsa (2026-11-17) "Denetlenemeyenler"
 * bölümü eklenir ve hiç eksik yoksa "tamamlanacak bir şey yok" YAZILMAZ (bilinmeyen ≠ eksik yok).
 */
export function gapsText(gaps, t, { at = null, dq = 'yes', moreTeams = 0 } = {}) {
  const lines = [`${t('gaps.title')} — ${stamp(at)}`]
  for (const c of GAP_CATEGORIES) {
    const list = gaps?.byCategory?.[c] || []
    if (list.length === 0) continue
    lines.push('', `[${t(`gaps.cat.${c}`)}]`)
    for (const g of list) {
      const k = gapTextKeys(g)
      const team = g.teamName ? ` — ${g.teamName}` : ''
      const count = g.category === 'profile' ? '' : ` (${t('gaps.failing', g.failing)})`
      lines.push(`- (${t(`dq.severity.${g.severity}`)}) ${t(k.title)}${team}${count}`)
      for (const it of Array.isArray(g.items) ? g.items : []) {
        const line = gapItemLine(g, it, t)
        lines.push(`    • ${line.name}${line.sub ? ` — ${line.sub}` : ''}`)
      }
      if (g.more > 0) lines.push(`    • ${t('gaps.export.more', g.more)}`)
    }
  }
  const unchecked = uncheckedLines(gaps, t, { dq, moreTeams })
  if (lines.length === 1) lines.push('', t(unchecked.length > 0 ? 'gaps.export.emptyIncomplete' : 'gaps.export.empty'))
  if (unchecked.length > 0) {
    lines.push('', `[${t('gaps.export.uncheckedTitle')}]`)
    for (const line of unchecked) lines.push(`- ${line}`)
  }
  return lines.join('\n')
}

/** Panoya kopyalar; başarıda true. */
export async function copyGaps(gaps, t, opts = {}) {
  return copyText(gapsText(gaps, t, opts))
}

/** Excel olarak indirir ("Eksikler" sayfası); indirme başlatılamadıysa false. */
export function downloadGapsXlsx(gaps, t, { at = null, dq = 'yes', moreTeams = 0 } = {}) {
  const header = ['gaps.export.col.category', 'gaps.export.col.severity', 'gaps.export.col.gap', 'gaps.export.col.team',
    'gaps.export.col.count', 'gaps.export.col.item', 'gaps.export.col.detail', 'gaps.export.col.why', 'gaps.export.col.fix'].map((k) => t(k))
  const bytes = buildXlsx([{ name: t('gaps.export.sheet'), headerRows: 1, rows: [header, ...gapRows(gaps, t, { dq, moreTeams })],
    widths: [16, 10, 36, 22, 10, 32, 30, 44, 44] }])
  const d = new Date(Number.isFinite(at) ? at : Date.now())
  const day = d.toISOString().slice(0, 10)
  return downloadBlob(`${t('gaps.export.file')}-${day}.xlsx`, new Blob([bytes], { type: XLSX_MIME })) !== false
}
