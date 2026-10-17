import { describe, it, expect } from 'vitest'
import {
  CHANNEL_SORTS, CHANNEL_STATUSES, REPORT_LIST_KEYS, REPORT_STATUSES, channelCounts, channelPeriodStatus, filterChannels,
  filterReports, normalizeChannelSort, normalizeChannelStatus, normalizeReportStatus, normalizeReportYear, reportCounts,
  reportStatusKey, reportYears, sortChannels,
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
    expect(CHANNEL_SORTS).toEqual(['name', 'status', 'team'])
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
