import { describe, it, expect } from 'vitest'
import {
  CHANNEL_SORTS, CHANNEL_STATUSES, REPORT_LIST_KEYS, REPORT_STATUSES, applyReorderPlan, channelCounts, channelPeriodStatus,
  filterChannels, filterReports, inverseReorderPlan, normalizeChannelSort, normalizeChannelStatus, normalizeReportStatus,
  normalizeReportYear, reorderPlan, reportCounts, reportStatusKey, reportYears, sortChannels,
} from '../components/techerror/channelListModel.js'
import { PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

// Kanal teknik hata raporları — liste modeli (2026-10-17, 8. sürüm): dönem durumu, süzgeç (Türkçe katlama), sıralama,
// sayımlar; rapor listesi süzgeci (durum / yıl / arama), yıl listesi. Saf: girdi değişmez.
const ch = (id, name, extra = {}) => ({ id, name, team_id: 1, team_name: 'Dijital Kanallar', active: true, description: null,
  period_report: null, ...extra })
const CHANNELS = [
  ch(1, 'Mobil', { period_report: { id: 100, status: 'sent' } }),
  ch(2, 'POS', { period_report: { id: 101, status: 'draft' } }),
  ch(3, 'İnternet Şubesi', { description: 'Bireysel müşteriler' }),
  ch(4, 'ATM', { active: false, period_report: { id: 102, status: 'sent' } }),
  ch(5, 'Çağrı Merkezi', { team_id: 2, team_name: 'Ağ Operasyon', period_report: { id: 103, status: 'changed' } }),
]

describe('channelListModel — kanallar', () => {
  it('dönem durumu: pasif kanal inactive; gönderildi sent; açık rapor open; rapor yok missing', () => {
    expect(channelPeriodStatus(CHANNELS[0])).toBe('sent')
    expect(channelPeriodStatus(CHANNELS[1])).toBe('open')
    expect(channelPeriodStatus(CHANNELS[2])).toBe('missing')
    expect(channelPeriodStatus(CHANNELS[3])).toBe('inactive')     // gönderilmiş olsa da pasif
    expect(channelPeriodStatus(CHANNELS[4])).toBe('open')
    expect(channelPeriodStatus(null)).toBe('inactive')
  })

  it('sayımlar: aktif kanal / gönderildi / açık / eksik / pasif / toplam', () => {
    expect(channelCounts(CHANNELS)).toEqual({ channels: 4, sent: 1, open: 2, missing: 1, inactive: 1, total: 5 })
    expect(channelCounts([])).toEqual({ channels: 0, sent: 0, open: 0, missing: 0, inactive: 0, total: 0 })
  })

  it('arama Türkçe katlar (internet → İnternet Şubesi), takım adı ve açıklamada da; durum süzgeci; URL dışı değer "all"', () => {
    expect(filterChannels(CHANNELS, { q: 'internet' }).map((c) => c.id)).toEqual([3])
    expect(filterChannels(CHANNELS, { q: 'INTERNET SUBESI' }).map((c) => c.id)).toEqual([3])
    expect(filterChannels(CHANNELS, { q: 'ag operasyon' }).map((c) => c.id)).toEqual([5])
    expect(filterChannels(CHANNELS, { q: 'bireysel' }).map((c) => c.id)).toEqual([3])
    expect(filterChannels(CHANNELS, { status: 'open' }).map((c) => c.id)).toEqual([2, 5])
    expect(filterChannels(CHANNELS, { status: 'missing' }).map((c) => c.id)).toEqual([3])
    expect(filterChannels(CHANNELS, { status: 'inactive' }).map((c) => c.id)).toEqual([4])
    expect(filterChannels(CHANNELS, { q: 'pos', status: 'sent' })).toEqual([])
    expect(filterChannels(CHANNELS, { status: 'bilinmeyen' })).toHaveLength(5)
    expect(normalizeChannelStatus('x')).toBe('all')
    expect(normalizeChannelStatus('sent')).toBe('sent')
    expect(CHANNEL_STATUSES[0]).toBe('all')
  })

  it('sıralama: ada göre aktifler önce (Türkçe harf sırası); duruma göre eksik → açık → gönderildi → pasif; takıma göre; girdi değişmez', () => {
    const before = CHANNELS.map((c) => c.id)
    expect(sortChannels(CHANNELS, 'name').map((c) => c.name)).toEqual(['Çağrı Merkezi', 'İnternet Şubesi', 'Mobil', 'POS', 'ATM'])
    expect(sortChannels(CHANNELS, 'status').map((c) => c.id)).toEqual([3, 5, 2, 1, 4])
    expect(sortChannels(CHANNELS, 'team').map((c) => c.id)).toEqual([5, 3, 1, 2, 4])
    expect(sortChannels(CHANNELS, 'yok').map((c) => c.name)[0]).toBe('Çağrı Merkezi')
    expect(CHANNELS.map((c) => c.id)).toEqual(before)
    expect(normalizeChannelSort('team')).toBe('team')
    expect(normalizeChannelSort('')).toBe('name')
    expect(CHANNEL_SORTS).toEqual(['name', 'status', 'team', 'order'])
  })
})

// Kanal sırası (R14, 2026-10-17): "Sıra" sıralaması takım A→Z → sort_order artan (boş en sonda) → ad; taşıma planı aynı
// takımın komşusuyla yer değiştirir, sırası olmayan takım önce ardışık numaralanır, başka takım hiç dokunulmaz.
const oc = (id, name, team, sort_order, extra = {}) => ch(id, name, { team_id: team, team_name: team === 1 ? 'Dijital Kanallar' : 'Ağ Operasyon', sort_order, ...extra })
describe('channelListModel — kanal sırası', () => {
  it('"order": takım adına göre gruplar, takım içinde sort_order artan, boş olanlar sonda ad sırasında; pasif ayrımı yok; girdi değişmez', () => {
    const list = [
      oc(1, 'Mobil', 1, 2), oc(2, 'POS', 1, null), oc(3, 'İnternet Şubesi', 1, 1), oc(4, 'ATM', 1, null, { active: false }),
      oc(5, 'Çağrı Merkezi', 2, 5), oc(6, 'Bayi', 2, null),
    ]
    const before = list.map((c) => c.id)
    // "Ağ Operasyon" < "Dijital Kanallar"; takım 2: 5 (sıra 5) → 6 (boş); takım 1: 3 (1) → 1 (2) → boşlar ad sırası: ATM, POS
    expect(sortChannels(list, 'order').map((c) => c.id)).toEqual([5, 6, 3, 1, 4, 2])
    expect(list.map((c) => c.id)).toEqual(before)
    // Sayı olarak gelen metin sıra da sayı gibi okunur; 0 geçerli bir sıradır (boş değil)
    expect(sortChannels([oc(1, 'B', 1, '3'), oc(2, 'A', 1, 0), oc(3, 'C', 1, null)], 'order').map((c) => c.id)).toEqual([2, 1, 3])
    expect(normalizeChannelSort('order')).toBe('order')
  })

  it('plan — iki kanalın da farklı sırası varsa yalnız ikisi yer değiştirir (2 PUT); ucunda boş plan; başka takım komşu sayılmaz', () => {
    const ordered = sortChannels([oc(1, 'A', 1, 1), oc(2, 'B', 1, 2), oc(3, 'C', 1, 3), oc(9, 'Z', 2, 1)], 'order')
    // Sıra: takım 2 (Ağ Operasyon) önce → [9, 1, 2, 3]
    expect(ordered.map((c) => c.id)).toEqual([9, 1, 2, 3])
    expect(reorderPlan(ordered, 1, 2, 1)).toEqual([{ id: 2, sort_order: 3 }, { id: 3, sort_order: 2 }])      // B aşağı ↔ C
    expect(reorderPlan(ordered, 1, 2, -1)).toEqual([{ id: 2, sort_order: 1 }, { id: 1, sort_order: 2 }])     // B yukarı ↔ A
    expect(reorderPlan(ordered, 1, 1, -1)).toEqual([])          // A takımın ilki: yukarı yok (önündeki 9 başka takım)
    expect(reorderPlan(ordered, 1, 3, 1)).toEqual([])           // C takımın sonu
    expect(reorderPlan(ordered, 2, 0, 1)).toEqual([])           // Z takımının tek kanalı: aşağısı başka takım
    expect(reorderPlan(ordered, 2, 1, 1)).toEqual([])           // konum takıma ait değil
    expect(reorderPlan(ordered, 1, 7, 1)).toEqual([])           // konum yok
    expect(reorderPlan(ordered, 1, 2, 0)).toEqual([])           // yön yok
    expect(reorderPlan(null, 1, 0, 1)).toEqual([])
  })

  it('plan — komşunun sırası yoksa ya da sıralar eşitse takımın kanalları taşınmış dizilişte 1…n numaralanır; değeri zaten doğru olana PUT yok; süzgeçle gizli kanal da komşudur', () => {
    const ordered = sortChannels([oc(1, 'A', 1, null), oc(2, 'B', 1, null), oc(3, 'C', 1, null), oc(9, 'Z', 2, null)], 'order')
    expect(ordered.map((c) => c.id)).toEqual([9, 1, 2, 3])
    // A aşağı: [B, A, C] → B=1, A=2, C=3 — üçü de boştu, üçüne PUT; Z'ye (başka takım) dokunulmaz
    expect(reorderPlan(ordered, 1, 1, 1)).toEqual([{ id: 2, sort_order: 1 }, { id: 1, sort_order: 2 }, { id: 3, sort_order: 3 }])
    // Kısmen numaralı takım: A=1, B boş, C boş → B aşağı: [A, C, B] → A=1 (aynı, PUT yok), C=2, B=3
    const mixed = sortChannels([oc(1, 'A', 1, 1), oc(2, 'B', 1, null), oc(3, 'C', 1, null)], 'order')
    expect(reorderPlan(mixed, 1, 1, 1)).toEqual([{ id: 3, sort_order: 2 }, { id: 2, sort_order: 3 }])
    // Eşit sıralar (ikisi de 4): yer değiştirme anlamsız → numaralama
    const equal = sortChannels([oc(1, 'A', 1, 4), oc(2, 'B', 1, 4)], 'order')
    expect(reorderPlan(equal, 1, 0, 1)).toEqual([{ id: 2, sort_order: 1 }, { id: 1, sort_order: 2 }])
    // Plan uygulama ve geri alma: yalnız plandaki kanallar değişir, diğerleri aynı nesne; geri alma eski değerleri (null dâhil) döndürür
    const plan = reorderPlan(ordered, 1, 1, 1)
    const applied = applyReorderPlan(ordered, plan)
    expect(applied.map((c) => c.sort_order)).toEqual([null, 2, 1, 3])
    expect(applied[0]).toBe(ordered[0])
    expect(sortChannels(applied, 'order').map((c) => c.id)).toEqual([9, 2, 1, 3])
    const undo = inverseReorderPlan(ordered, plan)
    expect(undo).toEqual([{ id: 2, sort_order: null }, { id: 1, sort_order: null }, { id: 3, sort_order: null }])
    expect(applyReorderPlan(applied, undo).map((c) => c.sort_order)).toEqual([null, null, null, null])
    expect(applyReorderPlan(ordered, [])).toBe(ordered)
  })
})

const rp = (id, year, month, status, extra = {}) => ({ id, year, month, status, title: `Mobil Kanalı Teknik Hata Mesajları - ${month}/${year}`, ...extra })
const REPORTS = [
  rp(1, 2026, 9, 'draft'), rp(2, 2026, 8, 'sent'), rp(3, 2026, 7, 'changed'), rp(4, 2025, 12, 'queued'), rp(5, 2025, 11, 'sending'),
  rp(6, 2024, 1, 'sent', { title: 'Eski rapor' }),
]
const label = (r) => ({ 9: 'Eylül', 8: 'Ağustos', 7: 'Temmuz', 12: 'Aralık', 11: 'Kasım', 1: 'Ocak' }[r.month] + ' ' + r.year)

describe('channelListModel — raporlar', () => {
  it('durum anahtarı: queued / sending → queued; sent, changed aynen; diğerleri draft', () => {
    expect(REPORTS.map(reportStatusKey)).toEqual(['draft', 'sent', 'changed', 'queued', 'queued', 'sent'])
    expect(reportStatusKey({ status: null })).toBe('draft')
    expect(REPORT_STATUSES).toEqual(['all', 'draft', 'changed', 'sent', 'queued'])
    expect(normalizeReportStatus('sending')).toBe('all')
    expect(normalizeReportStatus('queued')).toBe('queued')
  })

  it('sayımlar ve yıllar (en yeni önce)', () => {
    expect(reportCounts(REPORTS)).toEqual({ total: 6, sent: 2, open: 2, queued: 2 })
    expect(reportYears(REPORTS)).toEqual([2026, 2025, 2024])
    expect(reportYears([{ year: null }, { year: 'x' }])).toEqual([])
  })

  it('süzgeç: arama başlıkta ve dönem etiketinde (Türkçe katlama), durum, yıl; yıl listede yoksa "all"', () => {
    expect(filterReports(REPORTS, { q: 'eylul' }, label).map((r) => r.id)).toEqual([1])
    expect(filterReports(REPORTS, { q: 'ESKİ' }, label).map((r) => r.id)).toEqual([6])
    expect(filterReports(REPORTS, { status: 'queued' }, label).map((r) => r.id)).toEqual([4, 5])
    expect(filterReports(REPORTS, { year: '2025' }, label).map((r) => r.id)).toEqual([4, 5])
    expect(filterReports(REPORTS, { year: 2024, status: 'sent' }, label).map((r) => r.id)).toEqual([6])
    expect(filterReports(REPORTS, { year: 'all' }, label)).toHaveLength(6)
    expect(filterReports(REPORTS, {}).map((r) => r.id)).toEqual([1, 2, 3, 4, 5, 6])    // etiket fonksiyonu yoksa yalnız başlık
    expect(normalizeReportYear('2025', [2026, 2025])).toBe('2025')
    expect(normalizeReportYear('2019', [2026, 2025])).toBe('all')
    expect(normalizeReportYear('abc', [2026])).toBe('all')
  })

  it('URL anahtarları te_ önekli (sekme değişiminde temizlenir); rapor listesi anahtarları beş tane', () => {
    expect(PAGE_STATE_PREFIXES).toContain('te_')
    expect(REPORT_LIST_KEYS).toEqual(['te_rq', 'te_rst', 'te_ry', 'te_rpage', 'te_rps'])
    for (const k of REPORT_LIST_KEYS) expect(k.startsWith('te_')).toBe(true)
  })
})
