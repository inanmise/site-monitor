import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import {
  buildSheets, cellValue, channelSlug, exportFileName, exportParams, numberCell, pivotExport,
} from '../components/techerror/channelExportModel.js'

// Kanal Excel dışa aktarımı (C14, 2026-10-18) — saf model: sunucunun rapor × alan satırları → alan × ay tablosu.
const t = (k, ...a) => {
  const v = TR[k] ?? k
  return a.reduce((s, x, i) => s.replace(`{${i}}`, String(x)), v)
}

const COLUMNS = ['team_id', 'team_name', 'channel_id', 'channel_name', 'year', 'month', 'report_id', 'report_status',
  'sent_at', 'changed_after_send', 'approval_state', 'field_id', 'field_label', 'field_type', 'value', 'exempt', 'computed']

function row(o) {
  const base = { team_id: 1, team_name: 'Takım A', channel_id: 10, channel_name: 'Mobil', report_status: 'sent',
    sent_at: '2026-10-02T08:00:00Z', changed_after_send: false, approval_state: null, exempt: false, computed: false, ...o }
  return COLUMNS.map((c) => (c in base ? base[c] : null))
}

/** Ağustos: error-rate o ay SAYI, Eylül: YÜZDE (aynı kimlik, farklı tür → iki satır); muaf ve boş değerler; başka kanal. */
function data() {
  return {
    columns: COLUMNS,
    rows: [
      row({ year: 2026, month: 9, report_id: 102, field_id: 'total-tx', field_label: 'Toplam işlem (Eylül)', field_type: 'number', value: 2000 }),
      row({ year: 2026, month: 9, report_id: 102, field_id: 'error-rate', field_label: 'Teknik hata oranı', field_type: 'percent', value: 1.475, computed: true }),
      row({ year: 2026, month: 9, report_id: 102, field_id: 'outage-errors', field_label: 'Kesinti kaynaklı hata', field_type: 'number', value: null, exempt: true }),
      row({ year: 2026, month: 9, report_id: 102, field_id: 'had-outage', field_label: 'Kesinti oldu mu?', field_type: 'yesno', value: false }),
      row({ year: 2026, month: 9, report_id: 102, field_id: 'comment', field_label: 'Kısa not', field_type: 'text', value: '=1+1' }),
      row({ year: 2025, month: 12, report_id: 101, field_id: 'total-tx', field_label: 'Toplam işlem (Aralık)', field_type: 'number', value: 1000,
        report_status: 'changed', changed_after_send: true }),
      row({ year: 2025, month: 12, report_id: 101, field_id: 'error-rate', field_label: 'Hata oranı (eski)', field_type: 'number', value: 12 }),
      row({ year: 2025, month: 12, report_id: 101, field_id: 'legacy', field_label: 'Eski alan', field_type: 'number', value: 5 }),
      row({ year: 2025, month: 12, report_id: 101, field_id: 'comment', field_label: 'Kısa not', field_type: 'text', value: null }),
      row({ channel_id: 20, channel_name: 'POS', year: 2026, month: 9, report_id: 200, field_id: 'total-tx', field_type: 'number', value: 9 }),
    ],
    reports: [
      { channel_id: 10, year: 2025, month: 12, report_id: 101, report_status: 'changed', sent_at: '2026-01-05T08:00:00Z', changed_after_send: true, approval_state: null },
      { channel_id: 10, year: 2026, month: 9, report_id: 102, report_status: 'sent', sent_at: '2026-10-02T08:00:00Z', changed_after_send: false, approval_state: null },
      { channel_id: 10, year: 2026, month: 10, report_id: 103, report_status: 'draft', sent_at: null, changed_after_send: false, approval_state: null },
      { channel_id: 20, year: 2026, month: 9, report_id: 200, report_status: 'sent' },
    ],
    notes: [],
  }
}

describe('channelExportModel — parametreler', () => {
  it('tek kanal + takım, tüm durumlar, JSON; "Tüm yıllar"da yıl yok, seçili yılda o yıl', () => {
    expect(exportParams({ id: 10, team_id: 1 }, 'all')).toEqual({ channel: 10, team: 1, status: 'all', format: 'json' })
    expect(exportParams({ id: 10, team_id: 1 }, '2025')).toEqual({ channel: 10, team: 1, status: 'all', format: 'json', year: 2025 })
    expect(exportParams({ id: 10 }, 'x')).toEqual({ channel: 10, status: 'all', format: 'json' })
  })
})

describe('channelExportModel — pivot', () => {
  it('aylar eskiden yeniye (alanı olmayan rapor da ay olarak), başka kanalın satırları alınmaz', () => {
    const p = pivotExport(data(), 10)
    expect(p.months.map((m) => m.key)).toEqual(['2025-12', '2026-09', '2026-10'])
    expect(p.reports.get('2026-10').report_status).toBe('draft')
    expect([...p.cells.values()].some((m) => m.has('2026-09') && m.get('2026-09').value === 9)).toBe(false)
  })

  it('satır kimliği = alan kimliği + TÜR: aynı kimlik farklı türde iki satır; etiket en yeni aydan; sıra en yeni ayın şablonu, sonra eski alanlar', () => {
    const p = pivotExport(data(), 10)
    expect(p.fields.map((f) => f.key)).toEqual([
      'total-tx|number', 'error-rate|percent', 'outage-errors|number', 'had-outage|yesno', 'comment|text',
      'error-rate|number', 'legacy|number',
    ])
    expect(p.fields[0].label).toBe('Toplam işlem (Eylül)')                    // en yeni ayın adı
    expect(p.fields.find((f) => f.key === 'error-rate|number').label).toBe('Hata oranı (eski)')
    expect(p.cells.get('error-rate|percent').has('2025-12')).toBe(false)   // eski ayın sayısı yüzde satırına karışmaz
    expect(p.cells.get('error-rate|number').get('2025-12').value).toBe(12)
  })

  it('sütunlar sunucunun columns listesinden okunur (sıra varsayılmaz)', () => {
    const d = data()
    const perm = [...COLUMNS].reverse()
    const map = (r) => perm.map((c) => r[COLUMNS.indexOf(c)])
    const p = pivotExport({ ...d, columns: perm, rows: d.rows.map(map) }, 10)
    expect(p.fields.map((f) => f.key)).toEqual(pivotExport(d, 10).fields.map((f) => f.key))
  })

  it('bozuk / eksik yanıt boş tablo verir (çökmez)', () => {
    expect(pivotExport(null).months).toEqual([])
    expect(pivotExport({ columns: COLUMNS, rows: [null, 'x', row({ year: 'yok', month: 3 })] }).fields).toEqual([])
  })
})

describe('channelExportModel — hücreler', () => {
  it('muaf → "muaf"; boş → boş; sayı / yüzde SAYI (en çok 6 ondalık); evet/hayır metin; metin aynen (Excel formül çalıştırmaz)', () => {
    expect(cellValue({ value: null, exempt: true }, 'number', t)).toBe('muaf')
    expect(cellValue({ value: null, exempt: false }, 'number', t)).toBeNull()
    expect(cellValue(undefined, 'number', t)).toBeNull()
    expect(cellValue({ value: 1.475, exempt: false }, 'percent', t)).toBe(1.475)
    expect(cellValue({ value: 0.1 + 0.2, exempt: false }, 'percent', t)).toBe(0.3)
    expect(cellValue({ value: 2000, exempt: false }, 'number', t)).toBe(2000)
    expect(cellValue({ value: true, exempt: false }, 'yesno', t)).toBe('Evet')
    expect(cellValue({ value: false, exempt: false }, 'yesno', t)).toBe('Hayır')
    expect(cellValue({ value: '=1+1', exempt: false }, 'text', t)).toBe('=1+1')
    expect(numberCell('12.5')).toBe(12.5)
    expect(numberCell('abc')).toBeNull()
    expect(numberCell(123456789012345)).toBe(123456789012345)
  })
})

describe('channelExportModel — sayfalar ve dosya adı', () => {
  it('"Değerler": Alan | Tür | aylar; "Raporlar": dönem, durum, gönderim tarihi, değişti; onay sütunu yalnız onay akışında', () => {
    const sheets = buildSheets(pivotExport(data(), 10), { t, fmtDate: (iso) => `T:${iso}` })
    expect(sheets.map((s) => s.name)).toEqual(['Değerler', 'Raporlar'])
    const [head, first, rate] = sheets[0].rows
    expect(head).toEqual(['Alan', 'Tür', 'Aralık 2025', 'Eylül 2026', 'Ekim 2026'])
    expect(first).toEqual(['Toplam işlem (Eylül)', 'Sayı', 1000, 2000, null])
    expect(rate).toEqual(['Teknik hata oranı', 'Yüzde', null, 1.475, null])
    expect(sheets[0].rows[3]).toEqual(['Kesinti kaynaklı hata', 'Sayı', null, 'muaf', null])
    expect(sheets[0].rows[4]).toEqual(['Kesinti oldu mu?', 'Evet / Hayır', null, 'Hayır', null])
    expect(sheets[0].freezeHeader).toBe(true)
    expect(sheets[1].rows[0]).toEqual(['Dönem', 'Durum', 'Gönderim tarihi', 'Gönderimden sonra değişti'])
    expect(sheets[1].rows[1]).toEqual(['Aralık 2025', 'Gönderimden sonra değişti', 'T:2026-01-05T08:00:00Z', 'Evet'])
    expect(sheets[1].rows[3]).toEqual(['Ekim 2026', 'Taslak', '', 'Hayır'])

    const d = data()
    d.reports[1].approval_state = 'approved'
    const withFlow = buildSheets(pivotExport(d, 10), { t })
    expect(withFlow[1].rows[0].at(-1)).toBe('Onay durumu')
    expect(withFlow[1].rows[2].at(-1)).toBe('Onaylandı')
    expect(withFlow[1].rows[1].at(-1)).toBe('')
  })

  it('dosya adı <kanal>-<yıl>-degerler.xlsx: Türkçe katlanır; tüm yıllarda veri aralığı ya da tek yıl; ad boşsa "kanal"', () => {
    const months = pivotExport(data(), 10).months
    expect(exportFileName('İnternet Şubesi', '2026', months)).toBe('internet-subesi-2026-degerler.xlsx')
    expect(exportFileName('Mobil', 'all', months)).toBe('mobil-2025-2026-degerler.xlsx')
    expect(exportFileName('Mobil', 'all', [{ year: 2026 }])).toBe('mobil-2026-degerler.xlsx')
    expect(exportFileName('Mobil', 'all', [])).toBe('mobil-tum-yillar-degerler.xlsx')
    expect(channelSlug('  --Çağrı / Merkezi--  ')).toBe('cagri-merkezi')
    expect(channelSlug('***')).toBe('kanal')
    expect(channelSlug('a'.repeat(200))).toHaveLength(60)
    // Uzun ad sözcük sınırında kesilir (yarım sözcük kalmaz)
    expect(channelSlug('Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'))
      .toBe('bireysel-internet-subesi-ve-mobil-bankacilik-ortak-kanali')
    expect(channelSlug('-'.repeat(50000) + 'x')).toBe('x')                  // uzun ayraç dizisi doğrusal katlanır
  })
})
