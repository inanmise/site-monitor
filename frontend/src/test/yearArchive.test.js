import { describe, it, expect, vi } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import {
  ARCHIVE_MAX_RETRIES, ARCHIVE_RETRY_WAIT_MS, MISSING_FILE, abortableSleep, archiveFileName, archiveYears, buildArchiveZip,
  fallbackPdfName, fileSlug, isPdfBytes, missingReason, missingText, namedEntries, runArchive, safeEntryName, uniqueName, zipPlan,
} from '../components/techerror/yearArchive.js'
import { TR } from '../i18n/tr.js'

// Kanalın yıl arşivi — ZIP (2026-10-17, C12): saf parçalar. PDF'ler SIRAYLA (tek istek) alınır; 429'da 3 sn bekleyip en çok
// 3 kez yeniden; eksik aylar missing.txt'ye; iptal → hiçbir şey; PDF girdileri sıkıştırılmadan.
const t = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), TR[key] ?? key)
const MONTHS = ['', 'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']
const labelOf = (item) => `${MONTHS[item.month]} ${item.year}`
const pdf = (s = '') => new TextEncoder().encode(`%PDF-1.4 ${s}`)
const noSleep = () => Promise.resolve(true)

describe('yıl arşivi — adlar', () => {
  it('fileSlug sunucunun kısaltmasıyla aynı: Türkçe küçük harf + ASCII, tek tire, ≤ 40, uç tireler yok', () => {
    expect(fileSlug('İnternet Şubesi')).toBe('internet-subesi')
    expect(fileSlug('  ÇAĞRI Merkezi / Kurumsal (Kart) ')).toBe('cagri-merkezi-kurumsal-kart')
    expect(fileSlug('IŞIK ÜRÜN Âlî')).toBe('isik-urun-ali')
    expect(fileSlug('Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı')).toHaveLength(40)
    expect(fileSlug('Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı').endsWith('-')).toBe(false)
    expect(fileSlug('***')).toBe('')
    expect(fileSlug(null)).toBe('')
  })

  it('yedek PDF adı <kanal>-<yıl>-<ay>.pdf; ZIP adı <kanal>-<yıl>-teknik-hata-raporlari.zip', () => {
    expect(fallbackPdfName('Mobil Şube', 2026, 3)).toBe('mobil-sube-2026-03.pdf')
    expect(fallbackPdfName('', 2026, 11)).toBe('2026-11.pdf')
    expect(archiveFileName('Mobil Şube', 2026)).toBe('mobil-sube-2026-teknik-hata-raporlari.zip')
    expect(archiveFileName('', 2025)).toBe('2025-teknik-hata-raporlari.zip')
  })

  it('sunucu adı ZIP içinde yol açamaz; .pdf eklenir; boş → null', () => {
    expect(safeEntryName('mobil-teknik-hata-2026-09.pdf')).toBe('mobil-teknik-hata-2026-09.pdf')
    expect(safeEntryName('../../etc/x')).toBe('-..-etc-x.pdf')
    expect(safeEntryName('a\\b\u0001c')).toBe('a-bc.pdf')
    expect(safeEntryName('   ')).toBeNull()
    expect(safeEntryName(null)).toBeNull()
  })

  it('aynı ad numaralanır (büyük / küçük harf duyarsız); missing.txt adı ayrılmış', () => {
    const used = new Set()
    expect(uniqueName('a.pdf', used)).toBe('a.pdf')
    expect(uniqueName('A.pdf', used)).toBe('A-2.pdf')
    expect(uniqueName('a.pdf', used)).toBe('a-3.pdf')
    const named = namedEntries([
      { item: { year: 2026, month: 1 }, name: 'x.pdf', bytes: pdf() },
      { item: { year: 2026, month: 2 }, name: 'x.pdf', bytes: pdf() },
      { item: { year: 2026, month: 3 }, name: null, bytes: pdf() },
      { item: { year: 2026, month: 4 }, name: 'missing.txt', bytes: pdf() },
    ], 'Mobil')
    expect(named.map((e) => e.entry)).toEqual(['x.pdf', 'x-2.pdf', 'mobil-2026-03.pdf', 'missing.txt.pdf'])
  })
})

describe('yıl arşivi — plan', () => {
  const reports = [
    { id: 5, year: 2026, month: 9 }, { id: 3, year: 2026, month: 1 }, { id: 4, year: 2026, month: 3 }, { id: 4, year: 2026, month: 3 },
    { id: 9, year: 2025, month: 12 }, { id: 10, year: 2026, month: 13 }, { id: null, year: 2026, month: 2 }, { id: 11, year: 'x', month: 2 },
  ]
  it('archiveYears: raporu olan yıllar, en yeni önce, geçerli ay ve kimlikle sayılır', () => {
    expect(archiveYears(reports)).toEqual([{ year: 2026, count: 4 }, { year: 2025, count: 1 }])
    expect(archiveYears(null)).toEqual([])
  })
  it('zipPlan: o yılın raporları ay sırasıyla, kimlik tekil', () => {
    expect(zipPlan(reports, 2026)).toEqual([{ id: 3, year: 2026, month: 1 }, { id: 4, year: 2026, month: 3 }, { id: 5, year: 2026, month: 9 }])
    expect(zipPlan(reports, '2025')).toEqual([{ id: 9, year: 2025, month: 12 }])
    expect(zipPlan(reports, 2020)).toEqual([])
  })
})

describe('yıl arşivi — sıralı koşucu', () => {
  const plan = [{ id: 1, year: 2026, month: 1 }, { id: 2, year: 2026, month: 2 }, { id: 3, year: 2026, month: 3 }]

  it('istekler SIRAYLA: bir PDF bitmeden sonraki istenmez; sunucu adı taşınır', async () => {
    const log = []
    let inFlight = 0
    const fetchPdf = vi.fn(async (item) => {
      inFlight++
      log.push(`start ${item.id} (${inFlight})`)
      await new Promise((r) => setTimeout(r, 5))
      inFlight--
      log.push(`end ${item.id}`)
      return { success: true, blob: new Blob([pdf(String(item.id))]), name: `r-${item.id}.pdf` }
    })
    const progress = []
    const res = await runArchive(plan, { fetchPdf, onProgress: (p) => progress.push(`${p.index}/${p.total}`) })
    expect(log).toEqual(['start 1 (1)', 'end 1', 'start 2 (1)', 'end 2', 'start 3 (1)', 'end 3'])
    expect(res.aborted).toBe(false)
    expect(res.missing).toEqual([])
    expect(res.files.map((f) => f.name)).toEqual(['r-1.pdf', 'r-2.pdf', 'r-3.pdf'])
    expect(strFromU8(res.files[1].bytes)).toBe('%PDF-1.4 2')
    expect(progress).toEqual(['1/3', '2/3', '3/3'])
  })

  it('429 → 3 sn bekleyip aynı raporu yeniden ister; sonra başarı', async () => {
    const sleep = vi.fn(noSleep)
    const fetchPdf = vi.fn()
      .mockResolvedValueOnce({ success: true, bytes: pdf('1') })
      .mockResolvedValueOnce({ success: false, status: 429, error: 'meşgul' })
      .mockResolvedValueOnce({ success: false, status: 429, error: 'meşgul' })
      .mockResolvedValueOnce({ success: true, bytes: pdf('2') })
      .mockResolvedValueOnce({ success: true, bytes: pdf('3') })
    const waits = []
    const res = await runArchive(plan, { fetchPdf, sleep, onProgress: (p) => { if (p.waiting) waits.push(`${p.index}:${p.attempt}`) } })
    expect(fetchPdf.mock.calls.map((c) => c[0].id)).toEqual([1, 2, 2, 2, 3])
    expect(sleep).toHaveBeenCalledTimes(2)
    expect(sleep.mock.calls[0][0]).toBe(ARCHIVE_RETRY_WAIT_MS)
    expect(ARCHIVE_RETRY_WAIT_MS).toBe(3000)
    expect(waits).toEqual(['2:2', '2:3'])
    expect(res.files).toHaveLength(3)
    expect(res.missing).toEqual([])
  })

  it('429 en çok 3 kez yeniden denenir; tükenirse ay "meşgul" diye eksik, sıradaki rapora geçilir', async () => {
    const fetchPdf = vi.fn(async (item) => (item.id === 2 ? { success: false, status: 429 } : { success: true, bytes: pdf() }))
    const sleep = vi.fn(noSleep)
    const res = await runArchive(plan, { fetchPdf, sleep })
    expect(ARCHIVE_MAX_RETRIES).toBe(3)
    expect(fetchPdf.mock.calls.filter((c) => c[0].id === 2)).toHaveLength(4)
    expect(sleep).toHaveBeenCalledTimes(3)
    expect(res.files.map((f) => f.item.id)).toEqual([1, 3])
    expect(res.missing).toEqual([{ item: plan[1], reason: 'busy', status: 429, error: null }])
  })

  it('başka hata yeniden denenmez; PDF olmayan yanıt ve fırlatan istek eksik sayılır', async () => {
    const fetchPdf = vi.fn()
      .mockResolvedValueOnce({ success: false, status: 404, error: 'Rapor bulunamadı.' })
      .mockResolvedValueOnce({ success: true, bytes: new TextEncoder().encode('<html>proxy</html>') })
      .mockRejectedValueOnce(new Error('kopuk'))
    const res = await runArchive(plan, { fetchPdf, sleep: noSleep })
    expect(fetchPdf).toHaveBeenCalledTimes(3)
    expect(res.files).toEqual([])
    expect(res.missing.map((m) => [m.item.id, m.reason, m.status])).toEqual([[1, 'failed', 404], [2, 'notPdf', 0], [3, 'failed', 0]])
  })

  it('iptal: istek dönünce durur, kalanlar istenmez, sonuç boş (hiçbir şey indirilmez)', async () => {
    const ctrl = new AbortController()
    const fetchPdf = vi.fn(async (item) => {
      if (item.id === 2) ctrl.abort()
      return { success: true, bytes: pdf() }
    })
    const res = await runArchive(plan, { fetchPdf, signal: ctrl.signal })
    expect(res).toEqual({ aborted: true, files: [], missing: [] })
    expect(fetchPdf).toHaveBeenCalledTimes(2)
    // İstek iptalle reddedilirse de iptal sayılır
    const c2 = new AbortController()
    const res2 = await runArchive(plan, { fetchPdf: async () => { c2.abort(); return { success: false, aborted: true } }, signal: c2.signal })
    expect(res2.aborted).toBe(true)
  })

  it('iptal 429 beklemesini keser', async () => {
    vi.useFakeTimers()
    try {
      const ctrl = new AbortController()
      const fetchPdf = vi.fn(async () => ({ success: false, status: 429 }))
      const run = runArchive(plan, { fetchPdf, signal: ctrl.signal })
      await vi.advanceTimersByTimeAsync(1000)
      ctrl.abort()
      const res = await run
      expect(res.aborted).toBe(true)
      expect(fetchPdf).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('abortableSleep: süre dolunca true, iptalde false, baştan iptalse hemen false', async () => {
    vi.useFakeTimers()
    try {
      const p = abortableSleep(3000)
      await vi.advanceTimersByTimeAsync(3000)
      expect(await p).toBe(true)
      const c = new AbortController()
      const q = abortableSleep(3000, c.signal)
      c.abort()
      expect(await q).toBe(false)
      expect(await abortableSleep(10, c.signal)).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('isPdfBytes yalnız %PDF- imzası', () => {
    expect(isPdfBytes(pdf())).toBe(true)
    expect(isPdfBytes(new TextEncoder().encode('{"success":true}'))).toBe(false)
    expect(isPdfBytes(new Uint8Array(0))).toBe(false)
    expect(isPdfBytes(null)).toBe(false)
  })
})

describe('yıl arşivi — missing.txt ve ZIP', () => {
  const missing = [
    { item: { id: 4, year: 2026, month: 3 }, reason: 'busy', status: 429, error: null },
    { item: { id: 5, year: 2026, month: 4 }, reason: 'failed', status: 500, error: 'PDF üretilemedi; yeniden deneyin.' },
    { item: { id: 6, year: 2026, month: 5 }, reason: 'failed', status: 0, error: 'Failed to fetch' },
    { item: { id: 7, year: 2026, month: 6 }, reason: 'failed', status: 502, error: null },
    { item: { id: 8, year: 2026, month: 7 }, reason: 'notPdf', status: 0, error: null },
  ]

  it('eksik ayların nedeni arayüz dilinde; ağ hatasında tarayıcının teknik metni yazılmaz', () => {
    expect(missingReason(missing[0], t)).toBe('Sunucu 4 denemenin hepsinde başka PDF\'ler hazırlıyordu (meşgul).')
    expect(missingReason(missing[1], t)).toBe('PDF üretilemedi; yeniden deneyin.')
    expect(missingReason(missing[2], t)).toBe(TR['te.zip.reason.network'])
    expect(missingReason(missing[3], t)).toMatch(/HTTP 502/)
    expect(missingReason(missing[4], t)).toBe(TR['te.zip.reason.notPdf'])
  })

  it('missing.txt: BOM, başlık (kanal, yıl), her eksik ay bir satır, öneri; CRLF', () => {
    const text = missingText(missing.slice(0, 2), { t, channelName: 'Mobil', year: 2026, labelOf })
    expect(text.charCodeAt(0)).toBe(0xfeff)
    const lines = text.slice(1).split('\r\n')
    expect(lines[0]).toBe('Bu arşive eklenemeyen raporlar — Mobil, 2026')
    expect(lines[2]).toBe('- Mart 2026 — Sunucu 4 denemenin hepsinde başka PDF\'ler hazırlıyordu (meşgul).')
    expect(lines[3]).toBe('- Nisan 2026 — PDF üretilemedi; yeniden deneyin.')
    expect(lines[5]).toBe(TR['te.zip.missingAdvice'])
  })

  it("ZIP: PDF'ler bayt bayt aynı, sıkıştırılmadan (level 0); varsa missing.txt", async () => {
    const entries = [{ entry: 'mobil-2026-01.pdf', bytes: pdf('ocak') }, { entry: 'mobil-2026-02.pdf', bytes: pdf('şubat') }]
    const bytes = await buildArchiveZip(entries, 'eksik')
    const files = unzipSync(bytes)
    expect(Object.keys(files).sort()).toEqual(['missing.txt', 'mobil-2026-01.pdf', 'mobil-2026-02.pdf'])
    expect(strFromU8(files['mobil-2026-02.pdf'])).toBe('%PDF-1.4 şubat')
    expect(strFromU8(files[MISSING_FILE])).toBe('eksik')
    // level 0: yerel başlıkta sıkıştırma yöntemi 0 (stored)
    expect(bytes[8] | (bytes[9] << 8)).toBe(0)
    const noMissing = unzipSync(await buildArchiveZip(entries, null))
    expect(Object.keys(noMissing)).not.toContain('missing.txt')
  })
})
