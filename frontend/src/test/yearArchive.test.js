import { describe, it, expect, vi } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import {
  ARCHIVE_MAX_RETRIES, ARCHIVE_REQUEST_TIMEOUT_MS, ARCHIVE_RETRY_WAIT_MS, MISSING_FILE, abortableSleep, archiveFileName,
  archiveYears, createArchiveZip, entryNamer, fallbackPdfName, fileSlug, isPdfBytes, missingReason, missingText, requestSignal,
  runArchive, safeEntryName, uniqueName, zipPlan,
} from '../components/techerror/yearArchive.js'
import { TR } from '../i18n/tr.js'

// Kanalın yıl arşivi — ZIP (2026-10-17, C12): saf parçalar. PDF'ler SIRAYLA (tek istek) alınır; 429'da 3 sn bekleyip en çok
// 3 kez yeniden; eksik aylar missing.txt'ye; iptal → hiçbir şey; PDF girdileri sıkıştırılmadan. İnceleme (aynı gün): her
// istek 5 dk ile sınırlı (zaman aşımı → eksik, sıradakine geçilir), ilk 401 / 403'te durulur (kalanlar "denenmedi"), ZIP
// AKIŞLA yazılır (her PDF gelir gelmez; baytlar sonuçta tutulmaz).
const t = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), TR[key] ?? key)
const MONTHS = ['', 'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']
const labelOf = (item) => `${MONTHS[item.month]} ${item.year}`
const pdf = (s = '') => new TextEncoder().encode(`%PDF-1.4 ${s}`)
const noSleep = () => Promise.resolve(true)
const unzipBlob = async (blob) => unzipSync(new Uint8Array(await blob.arrayBuffer()))

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

  it('aynı ad numaralanır (büyük / küçük harf duyarsız); missing.txt adı ayrılmış; adlar geliş sırasıyla verilir', () => {
    const used = new Set()
    expect(uniqueName('a.pdf', used)).toBe('a.pdf')
    expect(uniqueName('A.pdf', used)).toBe('A-2.pdf')
    expect(uniqueName('a.pdf', used)).toBe('a-3.pdf')
    const nameOf = entryNamer('Mobil')
    expect([
      nameOf({ year: 2026, month: 1 }, 'x.pdf'),
      nameOf({ year: 2026, month: 2 }, 'x.pdf'),
      nameOf({ year: 2026, month: 3 }, null),
      nameOf({ year: 2026, month: 4 }, 'missing.txt'),
    ]).toEqual(['x.pdf', 'x-2.pdf', 'mobil-2026-03.pdf', 'missing.txt.pdf'])
    // Her arşiv kendi ad kümesiyle başlar
    expect(entryNamer('Mobil')({ year: 2026, month: 1 }, 'x.pdf')).toBe('x.pdf')
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
    expect(res.stopped).toBeNull()
    expect(res.missing).toEqual([])
    expect(res.files.map((f) => f.name)).toEqual(['r-1.pdf', 'r-2.pdf', 'r-3.pdf'])
    expect(strFromU8(res.files[1].bytes)).toBe('%PDF-1.4 2')
    expect(progress).toEqual(['1/3', '2/3', '3/3'])
  })

  it('onFile: her PDF gelir gelmez (sonraki istekten ÖNCE) verilir; sonuçta bayt tutulmaz, onFile dönüşü `entry`', async () => {
    const log = []
    const fetchPdf = vi.fn(async (item) => { log.push(`fetch ${item.id}`); return { success: true, bytes: pdf(String(item.id)), name: null } })
    const onFile = vi.fn((f) => { log.push(`file ${f.item.id} ${strFromU8(f.bytes)}`); return `e-${f.item.id}.pdf` })
    const res = await runArchive(plan, { fetchPdf, onFile })
    expect(log).toEqual(['fetch 1', 'file 1 %PDF-1.4 1', 'fetch 2', 'file 2 %PDF-1.4 2', 'fetch 3', 'file 3 %PDF-1.4 3'])
    expect(res.files).toEqual([
      { item: plan[0], name: null, entry: 'e-1.pdf' }, { item: plan[1], name: null, entry: 'e-2.pdf' }, { item: plan[2], name: null, entry: 'e-3.pdf' },
    ])
    expect(res.files.some((f) => 'bytes' in f)).toBe(false)
  })

  it('onFile fırlatırsa (ör. bellek yetmedi) koşu fırlatır — sessiz yarım arşiv yok', async () => {
    const fetchPdf = vi.fn(async () => ({ success: true, bytes: pdf() }))
    const boom = new RangeError('Array buffer allocation failed')
    await expect(runArchive(plan, { fetchPdf, onFile: () => { throw boom } })).rejects.toBe(boom)
    expect(fetchPdf).toHaveBeenCalledTimes(1)
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

  it.each([401, 403])('%i: ilk yanıtta DURULUR — kalan aylar istenmez, "denenmedi" diye eksik; stopped = durum', async (status) => {
    const plan4 = [...plan, { id: 4, year: 2026, month: 4 }]
    const fetchPdf = vi.fn()
      .mockResolvedValueOnce({ success: true, bytes: pdf('1') })
      .mockResolvedValueOnce({ success: false, status, error: 'Sunucu iletisi.' })
      .mockResolvedValue({ success: true, bytes: pdf('x') })
    const res = await runArchive(plan4, { fetchPdf, sleep: noSleep })
    expect(fetchPdf).toHaveBeenCalledTimes(2)
    expect(res.aborted).toBe(false)
    expect(res.stopped).toBe(status)
    expect(res.files.map((f) => f.item.id)).toEqual([1])
    expect(res.missing).toEqual([
      { item: plan4[1], reason: 'auth', status, error: 'Sunucu iletisi.' },
      { item: plan4[2], reason: 'skipped', status, error: null },
      { item: plan4[3], reason: 'skipped', status, error: null },
    ])
  })

  it('her istek 5 dk ile sınırlı: asılı istek (sinyali yok saysa da) zaman aşımına düşer, ay eksik, sıradakine geçilir', async () => {
    vi.useFakeTimers()
    try {
      const signals = []
      const fetchPdf = vi.fn((item, signal) => {
        signals.push(signal)
        if (item.id === 2) return new Promise(() => {})            // hiç dönmeyen istek
        return Promise.resolve({ success: true, bytes: pdf(String(item.id)) })
      })
      const run = runArchive(plan, { fetchPdf, sleep: noSleep })
      await vi.advanceTimersByTimeAsync(0)
      expect(fetchPdf).toHaveBeenCalledTimes(2)
      await vi.advanceTimersByTimeAsync(ARCHIVE_REQUEST_TIMEOUT_MS - 1)
      expect(fetchPdf).toHaveBeenCalledTimes(2)                  // süre dolmadan sıradaki istenmez
      expect(signals[1].aborted).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      const res = await run
      expect(ARCHIVE_REQUEST_TIMEOUT_MS).toBe(5 * 60 * 1000)
      expect(signals[1].aborted).toBe(true)                       // istek de kesildi
      expect(signals[0].aborted).toBe(false)                      // biten isteğin zamanlayıcısı bırakıldı
      expect(res.aborted).toBe(false)
      expect(res.files.map((f) => f.item.id)).toEqual([1, 3])
      expect(res.missing).toEqual([{ item: plan[1], reason: 'timeout', status: 0, error: null }])
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('zaman aşımında istemcinin "iptal" yanıtı iptal sayılmaz (zaman aşımı); kullanıcının iptali sayılır', async () => {
    vi.useFakeTimers()
    try {
      // fetchTechErrorPdf gibi: sinyal kesilince { aborted: true } döner
      const fetchPdf = vi.fn((item, signal) => new Promise((resolve) => {
        signal.addEventListener('abort', () => resolve({ success: false, status: 0, aborted: true }), { once: true })
      }))
      const run = runArchive(plan.slice(0, 1), { fetchPdf })
      await vi.advanceTimersByTimeAsync(ARCHIVE_REQUEST_TIMEOUT_MS)
      const res = await run
      expect(res.aborted).toBe(false)
      expect(res.missing.map((m) => m.reason)).toEqual(['timeout'])

      const ctrl = new AbortController()
      const run2 = runArchive(plan, { fetchPdf, signal: ctrl.signal })
      await vi.advanceTimersByTimeAsync(1000)
      ctrl.abort()
      expect(await run2).toEqual({ aborted: true, files: [], missing: [] })
      expect(fetchPdf).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('iptal: süren istek beklenmeden durur, kalanlar istenmez, sonuç boş (hiçbir şey indirilmez)', async () => {
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
    // Sinyali yok sayan asılı istek de iptali bekletmez
    const c3 = new AbortController()
    const run3 = runArchive(plan, { fetchPdf: () => new Promise(() => {}), signal: c3.signal })
    c3.abort()
    expect((await run3).aborted).toBe(true)
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

describe('yıl arşivi — istek sinyali (kullanıcı iptali + süre)', () => {
  // AbortSignal.any olan ve olmayan (elle bağlı) yol aynı davranır
  const variants = [['AbortSignal.any', undefined], ['elle bağlı', { any: null }]]

  it.each(variants)('%s: üst iptal isteği keser; süre dolmaz', async (_name, opts) => {
    vi.useFakeTimers()
    try {
      const parent = new AbortController()
      const req = requestSignal(parent.signal, 1000, opts)
      expect(req.signal.aborted).toBe(false)
      parent.abort()
      expect(req.signal.aborted).toBe(true)
      expect(await req.ended).toMatchObject({ aborted: true })
      expect(req.timedOut()).toBe(false)
      req.done()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it.each(variants)('%s: süre dolunca kesilir ve timedOut; done() sonra zamanlayıcı ve dinleyici kalmaz', async (_name, opts) => {
    vi.useFakeTimers()
    try {
      const parent = new AbortController()
      const req = requestSignal(parent.signal, 1000, opts)
      await vi.advanceTimersByTimeAsync(999)
      expect(req.signal.aborted).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(req.signal.aborted).toBe(true)
      expect(req.timedOut()).toBe(true)
      expect(parent.signal.aborted).toBe(false)                  // kullanıcının sinyaline dokunulmaz
      req.done()
      // Bitmiş istekten sonra: süre dolmaz, üst iptal bitmiş isteği "zaman aşımı" yapmaz
      const req2 = requestSignal(parent.signal, 1000, opts)
      req2.done()
      await vi.advanceTimersByTimeAsync(5000)
      expect(req2.signal.aborted).toBe(false)
      expect(req2.timedOut()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('üst sinyal yoksa yalnız süre; baştan iptal edilmiş üst → hemen kesik', async () => {
    const req = requestSignal(null, 1000)
    expect(req.signal.aborted).toBe(false)
    req.done()
    const c = new AbortController()
    c.abort()
    for (const opts of [undefined, { any: null }]) {
      const r = requestSignal(c.signal, 1000, opts)
      expect(r.signal.aborted).toBe(true)
      expect(await r.ended).toMatchObject({ aborted: true })
      r.done()
    }
  })
})

describe('yıl arşivi — missing.txt ve akış ZIP', () => {
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

  it('zaman aşımı, oturum düştü (401), erişim yok (403) ve bu yüzden denenmeyen aylar ayrı ayrı söylenir', () => {
    const item = { id: 1, year: 2026, month: 1 }
    expect(missingReason({ item, reason: 'timeout', status: 0 }, t))
      .toBe('Zaman aşımı: PDF 5 dakika içinde gelmedi; raporu açıp “PDF indir” ile tek başına deneyin.')
    expect(missingReason({ item, reason: 'auth', status: 401, error: 'Oturum yok' }, t)).toBe('Oturum sona erdi; yeniden girip tekrar deneyin.')
    expect(missingReason({ item, reason: 'auth', status: 403, error: 'Bu raporu görme izniniz yok.' }, t)).toBe('Bu raporu görme izniniz yok.')
    expect(missingReason({ item, reason: 'auth', status: 403, error: null }, t)).toBe(TR['te.zip.reason.forbidden'])
    expect(missingReason({ item, reason: 'skipped', status: 401 }, t)).toBe(TR['te.zip.reason.skippedSession'])
    expect(missingReason({ item, reason: 'skipped', status: 403 }, t)).toBe(TR['te.zip.reason.skippedForbidden'])
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

  it("akış ZIP: PDF'ler bayt bayt aynı, sıkıştırılmadan (stored); adlar sunucudan / yedek; varsa missing.txt sonda", async () => {
    const w = await createArchiveZip('Mobil')
    expect(w.addPdf({ year: 2026, month: 1 }, 'mobil-2026-01.pdf', pdf('ocak'))).toBe('mobil-2026-01.pdf')
    expect(w.addPdf({ year: 2026, month: 2 }, null, pdf('şubat'))).toBe('mobil-2026-02.pdf')
    expect(w.addPdf({ year: 2026, month: 3 }, 'mobil-2026-01.pdf', pdf('mart'))).toBe('mobil-2026-01-2.pdf')
    const blob = w.finish('eksik')
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.type).toBe('application/zip')
    const bytes = new Uint8Array(await blob.arrayBuffer())
    const files = unzipSync(bytes)
    expect(Object.keys(files)).toEqual(['mobil-2026-01.pdf', 'mobil-2026-02.pdf', 'mobil-2026-01-2.pdf', MISSING_FILE])
    expect(strFromU8(files['mobil-2026-02.pdf'])).toBe('%PDF-1.4 şubat')
    expect(strFromU8(files['mobil-2026-01-2.pdf'])).toBe('%PDF-1.4 mart')
    expect(strFromU8(files[MISSING_FILE])).toBe('eksik')
    // stored: yerel başlıkta sıkıştırma yöntemi 0
    expect(bytes[8] | (bytes[9] << 8)).toBe(0)
    const w2 = await createArchiveZip('Mobil')
    w2.addPdf({ year: 2026, month: 1 }, null, pdf())
    expect(Object.keys(await unzipBlob(w2.finish(null)))).toEqual(['mobil-2026-01.pdf'])
  })

  it('PDF baytları eklenir eklenmez arşive KOPYALANIR (çağıran bırakabilir — üç kopya birikmez)', async () => {
    const w = await createArchiveZip('Mobil')
    const b = pdf('ocak')
    w.addPdf({ year: 2026, month: 1 }, null, b)
    b.fill(0)                                                       // çağıranın tamponu sonradan değişse de arşiv aynı
    expect(strFromU8((await unzipBlob(w.finish(null)))['mobil-2026-01.pdf'])).toBe('%PDF-1.4 ocak')
  })

  it('abort yarım arşivi bırakır (sonra yazılamaz); paketleyici yüklenemezse PDF istenmeden fırlatır', async () => {
    const w = await createArchiveZip('Mobil')
    w.addPdf({ year: 2026, month: 1 }, null, pdf())
    w.abort()
    w.abort()                                                       // ikinci kez zararsız
    expect(() => w.addPdf({ year: 2026, month: 2 }, null, pdf())).toThrow()
    const stale = new TypeError('Failed to fetch dynamically imported module: https://x/assets/fflate-abc.js')
    await expect(createArchiveZip('Mobil', { load: () => Promise.reject(stale) })).rejects.toBe(stale)
  })
})
