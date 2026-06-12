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

  const text = clipboardData.getData('text/plain')
  if (text && text.includes('\t')) {
    const lines = text.replace(/\r\n?/g, '\n').split('\n').filter(l => l.trim() !== '')
    if (lines.length >= 2 || (lines.length === 1 && lines[0].includes('\t'))) {
      const rows = lines.map(l => l.split('\t').map(cleanCell))
      return rowsToMarkdown(rows)
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
