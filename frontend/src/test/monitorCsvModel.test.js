import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  CSV_MAX_BYTES, CSV_MAX_ROWS, MONITOR_CSV_TYPES, changedText, classifyImportResponse, decodeCsvBytes, fileProblem, filterRows,
  isCsvType, pausedRows, previewCounts, resultCounts, rowsNeedingSecrets, secretLabel,
} from '../components/monitoring/csv/monitorCsvModel.js'

const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/monitorcsv')
const javaSrc = (f) => fs.readFileSync(path.join(JAVA, f), 'utf8')

describe('izleme CSV modeli — sunucuyla sözleşme', () => {
  it('tür listesi = MonitorCsvSpec.TYPES (aynı sıra)', () => {
    const m = /TYPES\s*=\s*List\.of\(([^)]*)\)/.exec(javaSrc('MonitorCsvSpec.java'))
    expect(m).toBeTruthy()
    const java = [...m[1].matchAll(/"([a-z]+)"/g)].map((x) => x[1])
    expect(MONITOR_CSV_TYPES).toEqual(java)
    expect(isCsvType('http')).toBe(true)
    expect(isCsvType('uptime')).toBe(false)
  })

  it('istemci sınırları = sunucu sınırları (2000 satır, 5 MB)', () => {
    const parser = javaSrc('MonitorCsvParser.java')
    expect(parser).toMatch(new RegExp(`MAX_ROWS\\s*=\\s*${CSV_MAX_ROWS}\\b`))
    expect(parser).toMatch(/MAX_CHARS\s*=\s*5\s*\*\s*1024\s*\*\s*1024/)
    expect(CSV_MAX_BYTES).toBe(5 * 1024 * 1024)
  })

  it('eylem / durum adları = sunucu sabitleri', () => {
    const svc = javaSrc('MonitorCsvService.java')
    for (const a of ['create', 'update', 'unchanged', 'error']) expect(svc).toContain(`"${a}"`)
    for (const s of ['done', 'skipped', 'failed']) expect(svc).toContain(`"${s}"`)
  })
})

describe('dosya denetimi', () => {
  const f = (name, size, type = '') => ({ name, size, type })
  it('uygun CSV / TXT / türsüz uzantısız', () => {
    expect(fileProblem(f('a.csv', 10, 'text/csv'))).toBeNull()
    expect(fileProblem(f('A.CSV', 10, 'application/vnd.ms-excel'))).toBeNull()
    expect(fileProblem(f('a.txt', 10, 'text/plain'))).toBeNull()
    expect(fileProblem(f('export', 10, 'text/csv'))).toBeNull()
  })
  it('boş, büyük, CSV olmayan', () => {
    expect(fileProblem(null)).toBe('empty')
    expect(fileProblem(f('a.csv', 0))).toBe('empty')
    expect(fileProblem(f('a.csv', CSV_MAX_BYTES + 1))).toBe('tooLarge')
    expect(fileProblem(f('logo.png', 10, 'image/png'))).toBe('notCsv')
    expect(fileProblem(f('rapor.pdf', 10, 'application/pdf'))).toBe('notCsv')
  })
})

describe('sayılar ve süzgeç', () => {
  const rows = [
    { row: 2, action: 'create', status: 'done' },
    { row: 3, action: 'update', status: 'done' },
    { row: 4, action: 'unchanged', status: 'skipped' },
    { row: 5, action: 'error', status: 'failed' },
    { row: 6, action: '???', status: null },
  ]
  it('önizleme: bilinmeyen eylem hata sayılır, uygulanabilir = oluştur + güncelle', () => {
    expect(previewCounts(rows)).toEqual({ create: 1, update: 1, unchanged: 1, error: 2, total: 5, actionable: 2 })
    expect(previewCounts(null).actionable).toBe(0)
  })
  it('sonuç: durumsuz satır başarı sayılmaz', () => {
    expect(resultCounts(rows)).toEqual({ done: 2, created: 1, updated: 1, skipped: 1, failed: 2, total: 5 })
  })
  it('süzgeç: önizlemede eylem, sonuçta durum', () => {
    expect(filterRows(rows, 'all').length).toBe(5)
    expect(filterRows(rows, 'error').map((r) => r.row)).toEqual([5, 6])
    expect(filterRows(rows, 'update').map((r) => r.row)).toEqual([3])
    expect(filterRows(rows, 'failed', 'result').map((r) => r.row)).toEqual([5, 6])
    expect(filterRows(rows, 'skipped', 'result').map((r) => r.row)).toEqual([4])
  })
})

describe('sırlar ve metinler', () => {
  const t = (k, ...a) => `${k}${a.length ? `(${a.join(',')})` : ''}`
  it('sır kodları okunur ada', () => {
    expect(secretLabel(t, 'basic_auth_pass')).toBe('mcsv.secret.basic_auth_pass')
    expect(secretLabel(t, 'custom_headers')).toBe('mcsv.secret.custom_headers')
    expect(secretLabel(t, 'env:TOKEN')).toBe('mcsv.secret.env(TOKEN)')
    expect(secretLabel(t, 'other')).toBe('other')
  })
  it('sır gereken satırlar: önizlemede uygulanacaklar, sonuçta uygulananlar', () => {
    const rows = [
      { action: 'create', status: 'done', secrets: ['basic_auth_pass'] },
      { action: 'error', status: 'failed', secrets: ['basic_auth_pass'] },
      { action: 'update', status: 'failed', secrets: ['env:X'] },
      { action: 'unchanged', status: 'skipped', secrets: [] },
    ]
    expect(rowsNeedingSecrets(rows).length).toBe(2)
    expect(rowsNeedingSecrets(rows, 'result').length).toBe(1)
  })
  it('değişen sütunlar 12 sonrası "+N"', () => {
    const many = Array.from({ length: 15 }, (_, i) => `c${i}`)
    expect(changedText(['a', 'b'], (n) => `+${n}`)).toBe('a, b')
    expect(changedText(many, (n) => `+${n}`)).toBe(`${many.slice(0, 12).join(', ')} +3`)
  })
})

describe('yanıt sınıfı', () => {
  it('veri / eski sunucu / yanıtsız / hata', () => {
    expect(classifyImportResponse({ success: true, data: { rows: [] } })).toBe('ok')
    expect(classifyImportResponse({ success: false, status: 404, code: 'NOT_FOUND' })).toBe('unsupported')
    expect(classifyImportResponse({ success: false, status: 405 })).toBe('unsupported')
    expect(classifyImportResponse({ success: false, status: 0, code: 'REQUEST_TIMEOUT' })).toBe('unsure')
    expect(classifyImportResponse({ success: false, status: 504 })).toBe('unsure')
    expect(classifyImportResponse(null)).toBe('unsure')
    expect(classifyImportResponse({ success: false, status: 400, code: 'VALIDATION_FAILED' })).toBe('error')
    expect(classifyImportResponse({ success: false, status: 429, code: 'RATE_LIMITED' })).toBe('error')
    expect(classifyImportResponse({ success: false, status: 413, code: 'PAYLOAD_TOO_LARGE' })).toBe('error')
  })
})

describe('bağımsız inceleme 2026-11-19', () => {
  it('gerçek koşumda her 5xx "sonuç bilinmiyor"; önizlemede yalnız 502-504; 409 CSV_PLAN_CHANGED ayrı sınıf', () => {
    expect(classifyImportResponse({ success: false, status: 500, code: 'INTERNAL_ERROR' }, { applying: true })).toBe('unsure')
    expect(classifyImportResponse({ success: false, status: 500, code: 'INTERNAL_ERROR' })).toBe('error')
    expect(classifyImportResponse({ success: false, status: 409, code: 'CSV_PLAN_CHANGED' }, { applying: true })).toBe('planChanged')
    expect(classifyImportResponse({ success: false, status: 409, code: 'CONCURRENT_MODIFICATION' }, { applying: true })).toBe('error')
  })

  it('kodlama: UTF-8 (BOM ile ya da BOM olmadan), UTF-16 LE / BE BOM, geçersiz UTF-8 → Windows-1254 (Türkçe harfler doğru)', () => {
    const utf8 = new TextEncoder().encode('name,team\nİstanbul Şube,Takım Ğ\n')
    expect(decodeCsvBytes(utf8)).toEqual({ text: 'name,team\nİstanbul Şube,Takım Ğ\n', encoding: 'utf-8' })
    const bom = new Uint8Array([0xEF, 0xBB, 0xBF, ...new TextEncoder().encode('a,b')])
    expect(decodeCsvBytes(bom)).toEqual({ text: 'a,b', encoding: 'utf-8' })
    // Windows-1254: İ=DD, Ş=DE, Ğ=D0, ı=FD, ş=FE, ğ=F0, ö=F6, ü=FC, ç=E7
    const cp1254 = new Uint8Array([0xDD, 0x73, 0x74, 0x61, 0x6E, 0x62, 0x75, 0x6C, 0x2C, 0xDE, 0xFE, 0xD0, 0xF0, 0xFD])
    expect(decodeCsvBytes(cp1254)).toEqual({ text: 'İstanbul,ŞşĞğı', encoding: 'windows-1254' })
    const le = new Uint8Array([0xFF, 0xFE, 0x30, 0x01, 0x2C, 0x00])   // "İ,"
    expect(decodeCsvBytes(le)).toEqual({ text: 'İ,', encoding: 'utf-16le' })
    const be = new Uint8Array([0xFE, 0xFF, 0x01, 0x30, 0x00, 0x2C])
    expect(decodeCsvBytes(be)).toEqual({ text: 'İ,', encoding: 'utf-16be' })
    expect(decodeCsvBytes(new ArrayBuffer(0))).toEqual({ text: '', encoding: 'utf-8' })
  })

  it('duraklatılmış satırlar: önizlemede oluşturulacaklar, sonuçta yalnız uygulananlar', () => {
    const rows = [
      { action: 'create', paused: true, status: 'done' },
      { action: 'create', paused: true, status: 'failed' },
      { action: 'create', paused: false, status: 'done' },
      { action: 'update', paused: true, status: 'done' },
    ]
    expect(pausedRows(rows).length).toBe(2)
    expect(pausedRows(rows, 'result').length).toBe(1)
    expect(pausedRows(null)).toEqual([])
  })
})
