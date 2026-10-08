import { zipSync, strToU8 } from 'fflate'

/**
 * Bağımlılıksız, küçük Excel (.xlsx — Office Open XML) yazıcısı (2026-10-08, kullanıcı: "paylaşılan sertifikaları Excel
 * olarak dışa alabilmeliyim"). Projede zaten olan `fflate` ile paketlenir; yeni kütüphane yok (SheetJS'in npm sürümü
 * bakımsız ve güvenlik bulgulu).
 *
 * Desteklenen: birden çok sayfa, metin (inline string) ve sayı hücreleri, kalın + gri zeminli başlık satırı, sütun
 * genişlikleri, dondurulmuş başlık ve otomatik süzgeç. Formül YAZILMAZ: hücreler her zaman değer — `=…` ile başlayan bir
 * metin Excel'de formül olarak çalışmaz (CSV'deki formül enjeksiyonu riski yok).
 *
 * @typedef {{ name: string, rows: Array<Array<string|number|null|undefined>>, headerRows?: number, widths?: number[],
 *             freezeHeader?: boolean, autoFilter?: boolean }} XlsxSheet
 */

const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'

/** XML 1.0'da izin verilmeyen denetim karakterleri (sekme / satır sonu hariç) atılır, özel karakterler kaçırılır. */
export function xmlText(v) {
  return String(v)
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 0 → A, 25 → Z, 26 → AA … */
export function colName(index) {
  let n = index + 1
  let s = ''
  while (n > 0) {
    const r = (n - 1) % 26
    s = String.fromCharCode(65 + r) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

/** Excel sayfa adı kuralı: ≤ 31 karakter, `[]:*?/\` yok, boş değil, adlar benzersiz. */
export function sheetName(name, used = new Set()) {
  let base = String(name ?? '').replace(/[[\]:*?/\\]/g, ' ').trim().slice(0, 31) || 'Sheet'
  let candidate = base
  let i = 2
  while (used.has(candidate.toLocaleLowerCase('tr'))) {
    const suffix = ` (${i++})`
    candidate = base.slice(0, 31 - suffix.length) + suffix
  }
  used.add(candidate.toLocaleLowerCase('tr'))
  return candidate
}

function cellXml(ref, value, style) {
  const s = style ? ` s="${style}"` : ''
  if (value === null || value === undefined || value === '') return `<c r="${ref}"${s}/>`
  if (typeof value === 'number' && Number.isFinite(value)) return `<c r="${ref}"${s}><v>${value}</v></c>`
  return `<c r="${ref}"${s} t="inlineStr"><is><t xml:space="preserve">${xmlText(value)}</t></is></c>`
}

function sheetXml(sheet) {
  const rows = Array.isArray(sheet.rows) ? sheet.rows : []
  const headerRows = sheet.headerRows ?? 1
  const maxCols = rows.reduce((m, r) => Math.max(m, Array.isArray(r) ? r.length : 0), 0)
  const lastRef = `${colName(Math.max(0, maxCols - 1))}${Math.max(1, rows.length)}`
  const parts = [XML_HEAD,
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ',
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">']
  if (sheet.freezeHeader && headerRows > 0 && rows.length > headerRows) {
    parts.push('<sheetViews><sheetView workbookViewId="0">',
      `<pane ySplit="${headerRows}" topLeftCell="A${headerRows + 1}" activePane="bottomLeft" state="frozen"/>`,
      '</sheetView></sheetViews>')
  }
  if (Array.isArray(sheet.widths) && sheet.widths.length) {
    parts.push('<cols>')
    sheet.widths.forEach((w, i) => {
      if (Number.isFinite(w) && w > 0) parts.push(`<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`)
    })
    parts.push('</cols>')
  }
  parts.push('<sheetData>')
  rows.forEach((row, ri) => {
    const r = ri + 1
    const cells = (Array.isArray(row) ? row : []).map((v, ci) => cellXml(`${colName(ci)}${r}`, v, ri < headerRows ? 1 : 0))
    parts.push(`<row r="${r}">${cells.join('')}</row>`)
  })
  parts.push('</sheetData>')
  if (sheet.autoFilter && rows.length > headerRows && maxCols > 0) parts.push(`<autoFilter ref="A${headerRows}:${lastRef}"/>`)
  parts.push('</worksheet>')
  return { xml: parts.join(''), filterRef: sheet.autoFilter && rows.length > headerRows && maxCols > 0 ? `$A$${headerRows}:$${colName(maxCols - 1)}$${rows.length}` : null }
}

const STYLES = XML_HEAD
  + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
  + '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
  + '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
  + '<fill><patternFill patternType="solid"><fgColor rgb="FFE5E7EB"/><bgColor indexed="64"/></patternFill></fill></fills>'
  + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
  + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
  + '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
  + '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>'
  + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
  + '</styleSheet>'

/**
 * Sayfaları .xlsx baytlarına çevirir.
 * @param {XlsxSheet[]} sheets
 * @returns {Uint8Array}
 */
export function buildXlsx(sheets) {
  const list = (Array.isArray(sheets) && sheets.length ? sheets : [{ name: 'Sheet', rows: [] }])
  const used = new Set()
  const names = list.map((s) => sheetName(s.name, used))
  const built = list.map((s) => sheetXml(s))

  const files = {}
  files['[Content_Types].xml'] = strToU8(XML_HEAD
    + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    + built.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')
    + '</Types>')
  files['_rels/.rels'] = strToU8(XML_HEAD
    + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
    + '</Relationships>')
  const defined = built
    .map((b, i) => (b.filterRef ? `<definedName name="_xlnm._FilterDatabase" localSheetId="${i}" hidden="1">'${names[i].replace(/'/g, "''")}'!${b.filterRef}</definedName>` : ''))
    .join('')
  files['xl/workbook.xml'] = strToU8(XML_HEAD
    + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
    + '<sheets>' + names.map((n, i) => `<sheet name="${xmlText(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') + '</sheets>'
    + (defined ? `<definedNames>${defined}</definedNames>` : '')
    + '</workbook>')
  files['xl/_rels/workbook.xml.rels'] = strToU8(XML_HEAD
    + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + built.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')
    + `<Relationship Id="rId${built.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>`
    + '</Relationships>')
  files['xl/styles.xml'] = strToU8(STYLES)
  built.forEach((b, i) => { files[`xl/worksheets/sheet${i + 1}.xml`] = strToU8(b.xml) })
  return zipSync(files, { level: 6 })
}

export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
