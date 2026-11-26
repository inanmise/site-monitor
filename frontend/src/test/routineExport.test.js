import { describe, it, expect, vi, beforeEach } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'

// Nöbet Rutinleri — Excel dışa aktarımı (2026-11-26): altı sayfa (arayüz dilinde adlar), sayılar SAYI hücresi, bilinmeyen
// değer BOŞ (asla 0), durum / önem / dönem / sıklık kodları çevrilir — tanınmayan kod boş kalır (ham kod yazılmaz),
// İstanbul saatli damgalar, dosya adı, gerçek .xlsx (fflate ile açılır), sonuç kodları (oturum bitti, iptal, sunucu kodu,
// ağ, indirme başlamadı).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ oncallRoutines: { exportRows: vi.fn() } }) }))
const downloadBlob = vi.hoisted(() => vi.fn(() => true))
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadBlob }))

import { api } from '../api/client'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { XLSX_MIME } from '../utils/xlsxWriter.js'
import {
  buildRoutineSheets, exportRoutineXlsx, num, routineExportFileName, routineFileSlug, stamp,
} from '../components/oncallroutine/routineExport.js'

const exportRows = () => api.oncallRoutines.exportRows

/** Anahtarı (ve argümanları) aynen döndüren çevirici — yapı testleri sözlükten bağımsız. */
const keyT = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
/** Gerçek sözlükten çevirici; eksik anahtar ⟦…⟧ ile işaretlenir (testte yakalanır). */
const dictT = (dict) => (k, ...a) => String(dict[k] ?? `⟦${k}⟧`).replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)] ?? ''))

const RAW_CODES = ['YENI_KOD', 'WHATEVER', 'QUARTER', 'CRITICAL', 'YEARLY', 'X']

const DATA = Object.freeze({
  from: '2026-10-28', to: '2026-11-26', truncated: false, max_rows: 20000,
  days: [
    { date: '2026-11-25', workday: true, holiday_name: null, status: 'COMPLETED', required: 18, done: 17, skipped: 1,
      problems_opened: 0, responsible_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', completed_at: '2026-11-25T14:05:00Z', completed_late: false },
    { date: '2026-11-26', workday: true, status: 'YENI_KOD', required: null, done: 'x' },
    { date: 'çöp', status: 'COMPLETED' },
    null,
  ],
  items: [
    { date: '2026-11-25', period_kind: 'DAY', period_key: '2026-11-25', section_title: 'Sabah kontrolleri', task_title: 'Panoyu denetle',
      state: 'DONE', state_by_name: 'Ayşe Yılmaz', state_at: '2026-11-25T06:30:00Z', skip_reason: null, note: 'Sorun yok' },
    { date: '2026-11-25', period_kind: 'QUARTER', period_key: 'Q4', task_title: 'Çeyrek kontrolü', state: 'WHATEVER' },
  ],
  problems: [
    { id: 41, day: '2026-11-25', severity: 'HIGH', status: 'OPEN', section_title: 'Sabah kontrolleri', task_title: 'Panoyu denetle',
      note: 'Grafana panosu boş', resolution_note: null, created_by_name: 'Ayşe Yılmaz', created_at: '2026-11-25T06:31:00Z',
      resolved_by_name: null, resolved_at: null, age_days: 1, incident: { id: 7, title: 'Pano kesintisi' } },
    { id: 42, severity: 'CRITICAL', status: 'X', incident: { id: 9, hidden: true } },
  ],
  stats: {
    workdays: 21,
    days: { completed: 15, partial: 2, incomplete: 1, missed: 2, open: 1, none: 0 },
    on_time_pct: 93.6,
    tasks: [
      { title: 'Panoyu denetle', section_title: 'Sabah kontrolleri', frequency: 'DAILY', occurrences: 21, done: 20, skipped: 1,
        missed: 0, pending: 0, done_pct: 95.2, problems: 1, last_done_by_name: 'Ayşe Yılmaz', last_done_at: '2026-11-25T06:30:00Z',
        median_done_time: '09:15' },
      { title: 'Yıllık gözden geçirme', frequency: 'YEARLY', occurrences: null, done_pct: null },
    ],
    people: [
      { name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', days_responsible: 11, days_completed: 10, days_completed_on_time: 9,
        items_done: 190, problems_opened: 3 },
    ],
    problems: { opened: 5, resolved: 3, open_now: 2, by_severity: { LOW: 1, MEDIUM: 2, HIGH: 2 }, median_resolve_hours: 6.5 },
  },
})

const OPTS = { teamName: 'Dijital Kanallar SY', from: '2026-10-28', to: '2026-11-26' }
const sheetsWith = (t, data = DATA) => buildRoutineSheets(data, { t, ...OPTS })
const byName = (sheets, key) => sheets.find((s) => s.name === key)
/** Özet sayfasında etiketin değeri. */
const summaryValue = (sheets, label) => byName(sheets, 'or.xl.sheet.summary').rows.find((r) => r[0] === label)?.[1]

beforeEach(() => {
  vi.clearAllMocks()
  downloadBlob.mockReturnValue(true)
})

describe('yardımcılar', () => {
  it('sayı hücresi: sonlu sayı ya da sayı metni → sayı; bilinmeyen → boş (asla 0)', () => {
    expect(num(12)).toBe(12)
    expect(num('12')).toBe(12)
    expect(num(0)).toBe(0)
    expect(num(93.6)).toBe(93.6)
    for (const v of [null, undefined, '', 'a', Number.NaN, Infinity]) expect(num(v)).toBeNull()
  })

  it('damga İstanbul saatiyle "yyyy-MM-dd HH:mm"; dilimsiz = UTC; okunamayan boş', () => {
    expect(stamp('2026-11-25T06:30:00Z')).toBe('2026-11-25 09:30')
    expect(stamp('2026-11-25T06:30:00')).toBe('2026-11-25 09:30')
    expect(stamp('2026-11-25T23:30:00Z')).toBe('2026-11-26 02:30')   // gün sınırı
    expect(stamp('çöp')).toBeNull()
    expect(stamp(null)).toBeNull()
  })

  it('dosya adı: Türkçe harfler sadeleşir, ≤ 40 karakter, boşsa "takim"', () => {
    expect(routineFileSlug('Dijital Kanallar SY')).toBe('dijital-kanallar-sy')
    expect(routineFileSlug('Çağrı Merkezi – İş Sürekliliği')).toBe('cagri-merkezi-is-surekliligi')
    expect(routineFileSlug('ÖDEME SİSTEMLERİ')).toBe('odeme-sistemleri')
    for (const v of ['', null, undefined, '!!!', '   ']) expect(routineFileSlug(v)).toBe('takim')
    const long = routineFileSlug('Kurumsal Bankacılık Dijital Kanallar Servis Yönetimi Ekibi (İstanbul)')
    expect(long.length).toBeLessThanOrEqual(40)
    expect(long).not.toMatch(/-$/)
    expect(routineExportFileName('Dijital Kanallar SY', '2026-10-28', '2026-11-26'))
      .toBe('nobet-rutini-dijital-kanallar-sy-2026-10-28-2026-11-26.xlsx')
    expect(routineExportFileName('X', 'çöp', null)).toBe('nobet-rutini-x-baslangic-bitis.xlsx')
  })
})

describe('sayfalar', () => {
  it('altı sayfa, sabit sıra; başlık satırı dondurulur; özet dışındakiler süzgeçli', () => {
    const sheets = sheetsWith(keyT)
    expect(sheets.map((s) => s.name)).toEqual([
      'or.xl.sheet.summary', 'or.xl.sheet.days', 'or.xl.sheet.items', 'or.xl.sheet.problems',
      'or.xl.sheet.taskStats', 'or.xl.sheet.peopleStats',
    ])
    for (const s of sheets) expect(s.freezeHeader).toBe(true)
    expect(sheets[0].autoFilter).toBe(false)
    for (const s of sheets.slice(1)) expect(s.autoFilter).toBe(true)
    // Satırsız sayfada süzgeç yok
    const empty = sheetsWith(keyT, { ...DATA, problems: [] })
    expect(byName(empty, 'or.xl.sheet.problems').autoFilter).toBe(false)
  })

  it('sayfa adları arayüz dilinde (TR / EN)', () => {
    expect(sheetsWith(dictT(TR)).map((s) => s.name))
      .toEqual(['Özet', 'Günler', 'Görevler', 'Sorunlar', 'Görev istatistiği', 'Kişi istatistiği'])
    expect(sheetsWith(dictT(EN)).map((s) => s.name))
      .toEqual(['Summary', 'Days', 'Tasks', 'Problems', 'Task statistics', 'People statistics'])
  })

  it('Günler: durum çevrilir, tanınmayan kod ve bilinmeyen sayı boş; geçersiz tarihli satır atlanır', () => {
    const rows = byName(sheetsWith(keyT), 'or.xl.sheet.days').rows
    expect(rows).toHaveLength(3)   // başlık + 2 geçerli gün
    expect(rows[0]).toHaveLength(11)
    expect(rows[1]).toEqual(['2026-11-25', 'or.xl.yes', null, 'or.status.COMPLETED', 18, 17, 1, 0,
      'Ayşe Yılmaz (Dijital Kanallar / Uzman)', '2026-11-25 17:05', 'or.xl.no'])
    expect(rows[2][3]).toBeNull()      // YENI_KOD → boş
    expect(rows[2][4]).toBeNull()      // required null → boş (0 değil)
    expect(rows[2][5]).toBeNull()      // 'x' → boş
    expect(rows[2][10]).toBeNull()     // geç tamamlandı bilinmiyor → boş
  })

  it('Görevler (madde satırları): dönem ve durum çevrilir; damga İstanbul', () => {
    const rows = byName(sheetsWith(keyT), 'or.xl.sheet.items').rows
    expect(rows[1]).toEqual(['2026-11-25', 'or.xl.kind.DAY', '2026-11-25', 'Sabah kontrolleri', 'Panoyu denetle',
      'or.xl.state.DONE', 'Ayşe Yılmaz', '2026-11-25 09:30', null, 'Sorun yok'])
    expect(rows[2][1]).toBeNull()      // QUARTER tanınmaz
    expect(rows[2][5]).toBeNull()      // WHATEVER tanınmaz
  })

  it('Sorunlar: önem / durum çevrilir; olay "#id başlık"; gizli olay yalnız numara metniyle', () => {
    const rows = byName(sheetsWith(keyT), 'or.xl.sheet.problems').rows
    expect(rows[0]).toHaveLength(14)
    expect(rows[1][0]).toBe(41)
    expect(rows[1].slice(1, 4)).toEqual(['2026-11-25', 'or.stats.sev.HIGH', 'or.xl.pstatus.OPEN'])
    expect(rows[1][9]).toBe('2026-11-25 09:31')
    expect(rows[1][11]).toBeNull()     // çözülmedi
    expect(rows[1][12]).toBe(1)
    expect(rows[1][13]).toBe('#7 Pano kesintisi')
    expect(rows[2][2]).toBeNull()      // CRITICAL tanınmaz
    expect(rows[2][3]).toBeNull()
    expect(rows[2][13]).toBe('or.xl.incidentHidden(9)')
  })

  it('Görev ve kişi istatistiği: sayılar SAYI hücresi; bilinmeyen sıklık / yüzde boş', () => {
    const sheets = sheetsWith(keyT)
    const task = byName(sheets, 'or.xl.sheet.taskStats').rows
    expect(task[1]).toEqual(['Panoyu denetle', 'Sabah kontrolleri', 'or.freq.DAILY', 21, 20, 1, 0, 0, 95.2, 1,
      'Ayşe Yılmaz', '2026-11-25 09:30', '09:15'])
    for (const i of [3, 4, 5, 6, 7, 8, 9]) expect(typeof task[1][i]).toBe('number')
    expect(task[2][2]).toBeNull()      // YEARLY tanınmaz
    expect(task[2][3]).toBeNull()
    expect(task[2][8]).toBeNull()      // bilinmeyen yüzde boş — asla 0
    const people = byName(sheets, 'or.xl.sheet.peopleStats').rows
    expect(people[1]).toEqual(['Ayşe Yılmaz (Dijital Kanallar / Uzman)', 11, 10, 9, 190, 3])
  })

  it('Özet: takım, aralık, gün sayaçları, zamanında %, önem dağılımı, satır sayısı, kısaltma', () => {
    const sheets = sheetsWith(keyT)
    expect(summaryValue(sheets, 'or.xl.sum.team')).toBe('Dijital Kanallar SY')
    expect(summaryValue(sheets, 'or.xl.sum.from')).toBe('2026-10-28')
    expect(summaryValue(sheets, 'or.xl.sum.to')).toBe('2026-11-26')
    expect(summaryValue(sheets, 'or.xl.sum.workdays')).toBe(21)
    expect(summaryValue(sheets, 'or.status.COMPLETED')).toBe(15)
    expect(summaryValue(sheets, 'or.status.MISSED')).toBe(2)
    expect(summaryValue(sheets, 'or.xl.sum.onTimePct')).toBe(93.6)
    expect(summaryValue(sheets, 'or.xl.sum.problemsBySeverity(or.stats.sev.HIGH)')).toBe(2)
    expect(summaryValue(sheets, 'or.xl.sum.medianResolveHours')).toBe(6.5)
    expect(summaryValue(sheets, 'or.xl.sum.items')).toBe(2)
    expect(summaryValue(sheets, 'or.xl.sum.truncated')).toBe('or.xl.no')
    expect(summaryValue(sheetsWith(keyT, { ...DATA, truncated: true }), 'or.xl.sum.truncated')).toBe('or.xl.truncatedYes(20000)')
    // Bilinmeyen istatistik → boş hücre
    const blank = sheetsWith(keyT, { ...DATA, stats: { on_time_pct: null } })
    expect(summaryValue(blank, 'or.xl.sum.onTimePct')).toBeNull()
    expect(summaryValue(blank, 'or.xl.sum.workdays')).toBeNull()
  })

  it('gerçek sözlüklerle hiçbir hücrede eksik anahtar, ham kod ya da "or." anahtarı yok', () => {
    for (const dict of [TR, EN]) {
      for (const s of sheetsWith(dictT(dict))) {
        for (const row of s.rows) {
          for (const cell of row) {
            if (typeof cell !== 'string') continue
            expect(cell, s.name).not.toMatch(/⟦|^or\./)
            expect(RAW_CODES, `${s.name}: ${cell}`).not.toContain(cell)
          }
        }
      }
    }
    // Çevrilmiş durum sözlüğün kendi metni
    const days = sheetsWith(dictT(TR))[1].rows
    expect(days[1][3]).toBe(TR['or.status.COMPLETED'])
  })
})

describe('exportRoutineXlsx', () => {
  it('veriyi okur, gerçek .xlsx kurar ve sunucunun aralığıyla adlandırıp indirir', async () => {
    exportRows().mockResolvedValue({ success: true, data: DATA })
    const ctrl = new AbortController()
    const out = await exportRoutineXlsx({ teamId: 12, teamName: 'Dijital Kanallar SY', from: '2026-10-01', to: '2026-11-26',
      t: dictT(TR), lang: 'tr', signal: ctrl.signal })
    expect(exportRows()).toHaveBeenCalledWith(12, { from: '2026-10-01', to: '2026-11-26', signal: ctrl.signal })
    // Ad sunucunun döndürdüğü aralıkla (data.from = 2026-10-28)
    expect(out).toMatchObject({ ok: true, name: 'nobet-rutini-dijital-kanallar-sy-2026-10-28-2026-11-26.xlsx', truncated: false })
    expect(downloadBlob).toHaveBeenCalledTimes(1)
    const [name, blob] = downloadBlob.mock.calls[0]
    expect(name).toBe(out.name)
    expect(blob.type).toBe(XLSX_MIME)

    const files = unzipSync(new Uint8Array(await blob.arrayBuffer()))
    const workbook = strFromU8(files['xl/workbook.xml'])
    const names = [...workbook.matchAll(/<sheet name="([^"]+)"/g)].map((m) => m[1])
    expect(names).toEqual(['Özet', 'Günler', 'Görevler', 'Sorunlar', 'Görev istatistiği', 'Kişi istatistiği'])
    const daysXml = strFromU8(files['xl/worksheets/sheet2.xml'])
    expect(daysXml).toContain('<v>18</v>')                 // sayı hücresi (metin değil)
    expect(daysXml).not.toContain('YENI_KOD')              // ham kod yazılmaz
    const peopleXml = strFromU8(files['xl/worksheets/sheet6.xml'])
    expect(peopleXml).toContain('<v>190</v>')
  })

  it('EN arayüzde sayfa adları İngilizce', async () => {
    exportRows().mockResolvedValue({ success: true, data: DATA })
    await exportRoutineXlsx({ teamId: 12, teamName: 'Digital Channels', from: '2026-10-28', to: '2026-11-26', t: dictT(EN), lang: 'en' })
    const blob = downloadBlob.mock.calls[0][1]
    const workbook = strFromU8(unzipSync(new Uint8Array(await blob.arrayBuffer()))['xl/workbook.xml'])
    expect([...workbook.matchAll(/<sheet name="([^"]+)"/g)].map((m) => m[1]))
      .toEqual(['Summary', 'Days', 'Tasks', 'Problems', 'Task statistics', 'People statistics'])
  })

  it('kısaltılmış dışa aktarım sonuçta söylenir', async () => {
    exportRows().mockResolvedValue({ success: true, data: { ...DATA, truncated: true } })
    const out = await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT })
    expect(out.ok).toBe(true)
    expect(out.truncated).toBe(true)
  })

  it('oturum bitti (null) → SESSION_ENDED; indirme yok', async () => {
    exportRows().mockResolvedValue(null)
    const out = await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT })
    expect(out).toEqual({ ok: false, code: 'SESSION_ENDED', res: null })
    expect(downloadBlob).not.toHaveBeenCalled()
  })

  it('sunucu reddi kodu aynen döner (EXPORT_TOO_LARGE, EXPORT_BUSY)', async () => {
    const big = { success: false, status: 400, code: 'EXPORT_TOO_LARGE', field: 'from', error: 'Çok büyük.' }
    exportRows().mockResolvedValueOnce(big)
    expect(await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2025-11-27', to: '2026-11-26', t: keyT }))
      .toEqual({ ok: false, code: 'EXPORT_TOO_LARGE', res: big })
    const busy = { success: false, status: 429, code: 'EXPORT_BUSY', retry_after_seconds: 5 }
    exportRows().mockResolvedValueOnce(busy)
    expect(await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT }))
      .toEqual({ ok: false, code: 'EXPORT_BUSY', res: busy })
    expect(downloadBlob).not.toHaveBeenCalled()
  })

  it('ağ hatası (istek reddedildi) → kendi kodu ya da NETWORK_ERROR', async () => {
    const apiErr = Object.assign(new Error('Sunucuya ulaşılamadı.'), { name: 'ApiError', code: 'OFFLINE' })
    exportRows().mockRejectedValueOnce(apiErr)
    expect((await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT })).code).toBe('OFFLINE')
    exportRows().mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const out = await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT })
    expect(out.ok).toBe(false)
    expect(out.code).toBe('NETWORK_ERROR')
  })

  it('yanıt gelirken iptal edildiyse CANCELLED; dosya kurulmaz', async () => {
    const ctrl = new AbortController()
    exportRows().mockImplementationOnce(async () => { ctrl.abort(); return { success: true, data: DATA } })
    const out = await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT, signal: ctrl.signal })
    expect(out.code).toBe('CANCELLED')
    expect(downloadBlob).not.toHaveBeenCalled()
  })

  it('tarayıcı indirmeyi başlatamazsa DOWNLOAD_FAILED (başarı denmez)', async () => {
    exportRows().mockResolvedValue({ success: true, data: DATA })
    downloadBlob.mockReturnValueOnce(false)
    const out = await exportRoutineXlsx({ teamId: 1, teamName: 'A', from: '2026-10-28', to: '2026-11-26', t: keyT })
    expect(out).toMatchObject({ ok: false, code: 'DOWNLOAD_FAILED', name: 'nobet-rutini-a-2026-10-28-2026-11-26.xlsx' })
  })
})
