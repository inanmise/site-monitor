import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Java test kaynağındaki `static final String[][] AD = { { "a", null, "b" }, … };` doğruluk tablolarını okur — Java ve JS
// aynı tabloyu paylaşır, iki taraf ayrışamaz (TechErrorCalcTest.TABLE, TechErrorSummaryBlocksTest.BLOCKS_TABLE,
// TechErrorTilesTest.TILES_TABLE, TechErrorSummaryDeltaTest.DELTA_TABLE).
export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..')

/** `com.sitemonitor` altındaki bir test kaynağının metni (ör. `service/techerror/TechErrorTilesTest.java`). */
export function readJavaTest(rel) {
  return fs.readFileSync(path.join(REPO_ROOT, 'backend/src/test/java/com/sitemonitor', rel), 'utf8')
}

/** `com.sitemonitor` altındaki bir ÜRETİM kaynağının metni (ör. `service/techerror/TechErrorTemplate.java`). */
export function readJavaMain(rel) {
  return fs.readFileSync(path.join(REPO_ROOT, 'backend/src/main/java/com/sitemonitor', rel), 'utf8')
}

/** Java kaynak kaçışları: \n \t \" \\ \uXXXX. */
export function unescapeJava(s) {
  return s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t', '"': '"', '\\': '\\' }[c] ?? c))
}

/**
 * `String[][] AD = { { "a", null, "b" }, … };` → satır dizileri (hücre: dizgi ya da null). Dizgi içindeki süslü parantez satır
 * sınırı değildir; bir hücreyi oluşturan `"…" + "…"` birleştirmeleri tek hücre okunur; `//` yorumları atlanır.
 */
export function javaStringTable(src, name) {
  const start = src.indexOf(`${name} = {`)
  if (start < 0) throw new Error(`${name} Java kaynağında yok`)
  const body = src.slice(start + name.length + 4)
  const rows = []
  let row = null
  let i = 0
  let pendingJoin = false
  while (i < body.length) {
    const ch = body[i]
    if (ch === '"') {
      let j = i + 1
      let s = ''
      while (j < body.length && body[j] !== '"') {
        if (body[j] === '\\') { s += body[j] + body[j + 1]; j += 2 } else { s += body[j]; j++ }
      }
      const text = unescapeJava(s)
      if (pendingJoin && row.length) row[row.length - 1] += text
      else row.push(text)
      pendingJoin = false
      i = j + 1
    } else if (ch === '+') {
      pendingJoin = true
      i++
    } else if (ch === '/' && body[i + 1] === '/') {
      while (i < body.length && body[i] !== '\n') i++
    } else if (ch === '{') {
      row = []
      i++
    } else if (ch === '}') {
      if (row === null) break
      rows.push(row)
      row = null
      i++
    } else if (body.startsWith('null', i)) {
      row.push(null)
      i += 4
    } else {
      i++
    }
  }
  return rows
}
