/**
 * Panodaki Excel/HTML tablo verisini GFM markdown tablosuna çevirir.
 * - text/html içinde <table> varsa: DOM parse → hücreler
 * - text/plain çok satırlı TSV ise (Excel kopyası): tab ayrımı → hücreler
 * - hiçbiri değilse null döner (default paste davranışı sürer)
 */
export function clipboardToMarkdownTable(clipboardData) {
  if (!clipboardData) return null

  const html = clipboardData.getData('text/html')
  if (html && html.toLowerCase().includes('<table')) {
    try {
      const doc = new DOMParser().parseFromString(html, 'text/html')
      const table = doc.querySelector('table')
      if (table) {
        const rows = Array.from(table.querySelectorAll('tr')).map(tr =>
          Array.from(tr.querySelectorAll('th,td')).map(cell => cleanCell(cell.textContent))
        ).filter(r => r.length > 0)
        if (rows.length > 0) return rowsToMarkdown(rows)
      }
    } catch {
      // parse hatası → TSV denemesine düş
    }
  }

  // text/plain → yalnız GERÇEK ızgara (Excel/TSV) tabloya çevrilir. Stack trace / girintili düz metin
  // YANLIŞLIKLA tabloya dönmesin: en az 2 satır olmalı ve HER satır tab içermeli (exception metninde ilk
  // satır = mesaj, tab yok → tabloya çevrilmez). Ayrıca satırların çoğu girinti amaçlı tek-baştaki-tab
  // (boş ilk hücre) ise bu da ızgara değildir (yalnız "at ..." satırları kopyalansa bile yakalanır).
  const text = clipboardData.getData('text/plain')
  if (text) {
    const lines = text.replace(/\r\n?/g, '\n').split('\n').filter(l => l.trim() !== '')
    if (lines.length >= 2 && lines.every(l => l.includes('\t'))) {
      const indentRows = lines.filter(l => l.split('\t')[0].trim() === '').length
      if (indentRows <= lines.length / 2) {
        const rows = lines.map(l => l.split('\t').map(cleanCell))
        return rowsToMarkdown(rows)
      }
    }
  }

  return null
}

function cleanCell(value) {
  return String(value ?? '')
    .replace(/\|/g, '\\|')   // pipe markdown tablo ayracıdır
    .replace(/\s+/g, ' ')    // hücre içi newline'ları düzleştir
    .trim()
}

function rowsToMarkdown(rows) {
  const colCount = Math.max(...rows.map(r => r.length))
  const pad = (r) => {
    const filled = [...r]
    while (filled.length < colCount) filled.push('')
    return filled
  }
  const header = pad(rows[0])
  const body = rows.slice(1).map(pad)
  const lines = [
    '| ' + header.join(' | ') + ' |',
    '| ' + header.map(() => '---').join(' | ') + ' |',
    ...body.map(r => '| ' + r.join(' | ') + ' |'),
  ]
  return lines.join('\n') + '\n'
}
