import { describe, it, expect } from 'vitest'
import {
  TES_EXPORT_COLUMNS, TES_FILTERS, TES_FLAG_FILTERS, TES_PERIOD_FILTERS, TES_SORTS, TES_TABLE_MIN_PX, channelPeriodStatus, countChannels,
  exportApprovalStatus, exportFileName, exportHeader, exportReportStatus, exportRow, exportTable, filterChannels,
  filtersActive, hasExecutives, isTeamHidden, matchesFilter, normalizeFilter, normalizeSort, searchChannels, sortChannels,
  teamEmailState, teamVisibleState, teamsOf, countOrNull, missingTeamText, onlyDeletedTeams,
} from '../components/admin/techErrorSettingsModel.js'
import { teamOptions } from '../components/admin/noc/operatorTeamsModel.js'
import { TR } from '../i18n/tr.js'

/** Sözlükten t (yer tutucular {0} …) — bileşensiz saf model denemesi. */
const tr = (key, ...args) => args.reduce((s, a, i) => s.split(`{${i}}`).join(String(a)), TR[key] ?? key)

// Ayarlar → Teknik hata raporu — kanal özeti saf modeli (2026-10-17 yeniden tasarım). Sunucu satırı: id, name, active,
// team_id, team_name, team_visible, executive_count, approver_count, team_email_set, period_report.
const ch = (id, name, extra = {}) => ({
  id, name, active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 0, approver_count: 0,
  team_email_set: true, period_report: null, ...extra,
})
const ROWS = [
  ch(1, 'Mobil', { executive_count: 2, approver_count: 1, period_report: { id: 100, status: 'sent' } }),
  ch(2, 'POS', { active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, team_email_set: false }),
  ch(3, 'İnternet Şubesi', { team_id: 3, team_name: 'Bireysel Bankacılık', team_visible: false, team_email_set: false }),
  ch(4, 'ATM', { period_report: { id: 101, status: 'draft' } }),
  ch(5, 'Çağrı Merkezi', { team_id: 2, team_name: 'Ödeme Sistemleri', period_report: { id: 102, status: 'changed' } }),
]

describe('techErrorSettingsModel — durum ve bayraklar', () => {
  it('dönem durumu kanal listesi sayfasıyla aynı kural: pasif → inactive, gönderildi → sent, var ama gönderilmedi → open, yok → missing', () => {
    expect(channelPeriodStatus(ROWS[0])).toBe('sent')
    expect(channelPeriodStatus(ROWS[1])).toBe('inactive')
    expect(channelPeriodStatus(ROWS[2])).toBe('missing')
    expect(channelPeriodStatus(ROWS[3])).toBe('open')
    expect(channelPeriodStatus(ROWS[4])).toBe('open')
    expect(channelPeriodStatus(ch(9, 'x', { period_report: { status: 'queued' } }))).toBe('open')
  })

  it('bilinmeyen ≠ eksik: e-posta ve görünürlük yalnız AÇIK false ile eksik / kapalı; alan yoksa null (sayılmaz)', () => {
    expect(teamEmailState(ch(1, 'a'))).toBe(true)
    expect(teamEmailState(ch(1, 'a', { team_email_set: false }))).toBe(false)
    expect(teamEmailState(ch(1, 'a', { team_email_set: undefined }))).toBeNull()
    expect(teamEmailState({})).toBeNull()
    expect(teamVisibleState(ch(1, 'a'))).toBe(true)
    expect(teamVisibleState(ch(1, 'a', { team_visible: false }))).toBe(false)
    expect(teamVisibleState({})).toBeNull()
    expect(isTeamHidden({})).toBe(false)
    expect(isTeamHidden({ team_visible: null })).toBe(false)
    expect(hasExecutives(ch(1, 'a', { executive_count: '3' }))).toBe(true)
    expect(hasExecutives(ch(1, 'a', { executive_count: null }))).toBe(false)
    // Kısmi veri: eski sunucu satırlarında alan yoksa noEmail / hidden sayımı 0 kalır ama bu "hepsi tanımlı" demek değil —
    // arayüz rozeti "bilinmiyor" yazar (teamEmailState null)
    const counts = countChannels([{ id: 1, name: 'a', active: true }])
    expect(counts.noEmail).toBe(0)
    expect(counts.hidden).toBe(0)
    expect(teamEmailState({ id: 1, name: 'a', active: true })).toBeNull()
  })

  it('normalize: bilinmeyen süzgeç → all, bilinmeyen sıralama → name; sabit listeler', () => {
    expect(normalizeFilter('sent')).toBe('sent')
    expect(normalizeFilter('exec')).toBe('exec')
    expect(normalizeFilter('zzz')).toBe('all')
    expect(normalizeFilter(undefined)).toBe('all')
    expect(normalizeSort('team')).toBe('team')
    expect(normalizeSort('nope')).toBe('name')
    expect(TES_FILTERS).toEqual(['all', ...TES_PERIOD_FILTERS, ...TES_FLAG_FILTERS])
    expect(TES_SORTS).toEqual(['name', 'team', 'status'])
    expect(TES_TABLE_MIN_PX).toBeGreaterThan(0)
    expect(Object.isFrozen(TES_FILTERS)).toBe(true)
  })
})

describe('techErrorSettingsModel — sayım, arama, süzgeç', () => {
  it('sayımlar: sent / open / missing yalnız aktifler; inactive pasifler; exec / noEmail / hidden tüm kanallar', () => {
    expect(countChannels(ROWS)).toEqual({ total: 5, sent: 1, open: 2, missing: 1, inactive: 1, exec: 1, noEmail: 2, hidden: 2 })
    expect(countChannels([])).toEqual({ total: 0, sent: 0, open: 0, missing: 0, inactive: 0, exec: 0, noEmail: 0, hidden: 0 })
    expect(countChannels(null).total).toBe(0)
  })

  it('arama Türkçe katlar (kanal + takım adı): "internet" → İnternet Şubesi, "odeme" → Ödeme Sistemleri kanalları', () => {
    expect(searchChannels(ROWS, { q: 'internet' }).map((c) => c.id)).toEqual([3])
    expect(searchChannels(ROWS, { q: 'ODEME' }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { q: '  ' })).toHaveLength(5)
    expect(searchChannels(ROWS, { q: 'yok-böyle' })).toEqual([])
  })

  it('takım süzgeci kimlikle (sayı ya da metin), "all" / boş / null süzgeç yok', () => {
    expect(searchChannels(ROWS, { team: 2 }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { team: '2' }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { team: 'all' })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: '' })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: null })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: 99 })).toEqual([])
  })

  it('matchesFilter: dönem durumları ve bayraklar; filterChannels üçünü VE ile birleştirir', () => {
    expect(ROWS.filter((c) => matchesFilter(c, 'sent')).map((c) => c.id)).toEqual([1])
    expect(ROWS.filter((c) => matchesFilter(c, 'open')).map((c) => c.id)).toEqual([4, 5])
    expect(ROWS.filter((c) => matchesFilter(c, 'missing')).map((c) => c.id)).toEqual([3])
    expect(ROWS.filter((c) => matchesFilter(c, 'inactive')).map((c) => c.id)).toEqual([2])
    expect(ROWS.filter((c) => matchesFilter(c, 'exec')).map((c) => c.id)).toEqual([1])
    expect(ROWS.filter((c) => matchesFilter(c, 'noEmail')).map((c) => c.id)).toEqual([2, 3])
    expect(ROWS.filter((c) => matchesFilter(c, 'hidden')).map((c) => c.id)).toEqual([2, 3])
    expect(ROWS.filter((c) => matchesFilter(c, 'all'))).toHaveLength(5)
    expect(ROWS.filter((c) => matchesFilter(c, 'garbage'))).toHaveLength(5)
    expect(filterChannels(ROWS, { team: 2, filter: 'noEmail' }).map((c) => c.id)).toEqual([2])
    expect(filterChannels(ROWS, { q: 'odeme', filter: 'open' }).map((c) => c.id)).toEqual([5])
    expect(filterChannels(ROWS, { q: 'odeme', team: 1 })).toEqual([])
    expect(filterChannels(ROWS)).toHaveLength(5)
  })

  it('filtersActive: boş arama / all takım / all süzgeç → kapalı; herhangi biri → açık', () => {
    expect(filtersActive({})).toBe(false)
    expect(filtersActive({ q: '   ', team: 'all', filter: 'all' })).toBe(false)
    expect(filtersActive({ q: 'a' })).toBe(true)
    expect(filtersActive({ team: '2' })).toBe(true)
    expect(filtersActive({ filter: 'hidden' })).toBe(true)
    expect(filtersActive({ filter: 'bilinmeyen' })).toBe(false)
  })
})

describe('techErrorSettingsModel — sıralama ve takımlar', () => {
  it('name: aktifler önce, Türkçe A→Z; girdi değişmez', () => {
    const input = [...ROWS]
    expect(sortChannels(input, 'name').map((c) => c.name)).toEqual(['ATM', 'Çağrı Merkezi', 'İnternet Şubesi', 'Mobil', 'POS'])
    expect(input.map((c) => c.id)).toEqual([1, 2, 3, 4, 5])
  })

  it('team: takım adı A→Z, aynı takımda aktifler önce, sonra ad', () => {
    expect(sortChannels(ROWS, 'team').map((c) => c.name)).toEqual(['İnternet Şubesi', 'ATM', 'Mobil', 'Çağrı Merkezi', 'POS'])
  })

  it('status: eksik → açık → gönderildi → pasif; aynı durumda takım e-postası eksik olan önce; sonra ad', () => {
    const rows = [...ROWS, ch(6, 'Şube', { team_email_set: false, period_report: { status: 'draft' } })]
    expect(sortChannels(rows, 'status').map((c) => c.name)).toEqual(['İnternet Şubesi', 'Şube', 'ATM', 'Çağrı Merkezi', 'Mobil', 'POS'])
    expect(sortChannels(rows, 'yok').map((c) => c.name)[0]).toBe('ATM')   // bilinmeyen → name
    expect(sortChannels(null)).toEqual([])
  })

  it('teamsOf: tekil takımlar, kimlik metin, ada göre Türkçe sıralı; takımsız satır atlanır', () => {
    expect(teamsOf([...ROWS, { id: 9, name: 'x' }])).toEqual([
      { id: '3', name: 'Bireysel Bankacılık' }, { id: '1', name: 'Dijital Kanallar' }, { id: '2', name: 'Ödeme Sistemleri' },
    ])
    expect(teamsOf([ch(1, 'a', { team_id: 7, team_name: null })])).toEqual([{ id: '7', name: '#7' }])
    expect(teamsOf(undefined)).toEqual([])
  })
})

describe('techErrorSettingsModel — dışa aktarma (E22)', () => {
  it('başlık sütun sırasıyla; satır ekrandaki metinler, sayılar sayı', () => {
    expect(TES_EXPORT_COLUMNS).toHaveLength(9)
    expect(exportHeader(tr)).toEqual(['Kanal', 'Takım', 'Aktif', 'Modül takıma açık', 'Takım e-postası', 'Yönetici alıcı sayısı',
      'PO onaylayıcı sayısı', 'Dönem raporu durumu', 'Onay durumu'])
    const mobil = ch(1, 'Mobil', { executive_count: 2, approver_count: 1,
      period_report: { id: 100, status: 'draft', last_send_status: 'failed', approval: { state: 'pending' } } })
    expect(exportRow(mobil, tr)).toEqual(['Mobil', 'Dijital Kanallar', 'Evet', 'Evet', 'Tanımlı', 2, 1,
      'Taslak · Son gönderim denemesi başarısız', 'Onay bekliyor'])
    const pos = ROWS[1]
    expect(exportRow(pos, tr)).toEqual(['POS', 'Ödeme Sistemleri', 'Hayır', 'Hayır', 'Eksik — rapor gönderilemez', 0, 0,
      'Dönem raporu yok', '—'])
  })

  it('bilinmeyen alan "Bilinmiyor" — boş ya da "sorun yok" değil', () => {
    const odd = { id: 7, name: 'Eski', team_id: 4, period_report: { id: 1, status: null } }
    expect(exportRow(odd, tr)).toEqual(['Eski', '#4', 'Bilinmiyor', 'Bilinmiyor', 'Bilinmiyor', 'Bilinmiyor', 'Bilinmiyor',
      'Başlanmadı', 'Bilinmiyor'])
    expect(exportApprovalStatus({ period_report: { approval: { state: 'garip' } } }, tr)).toBe('Bilinmiyor')
    expect(exportApprovalStatus({ period_report: { approval: { state: 'stale' } } }, tr)).toMatch(/yeniden onaya/)
    expect(exportReportStatus({ period_report: { status: 'sent' } }, tr)).toBe('Gönderildi')
    expect(exportRow({}, tr).slice(0, 2)).toEqual(['—', '—'])
  })

  it('İngilizce sütun adları sayım sütunlarını sayı olarak adlandırır (kişi adı listesi gibi okunmasın)', async () => {
    const { EN } = await import('../i18n/en.js')
    const en = (key) => EN[key] ?? key
    expect(exportHeader(en).slice(5, 7)).toEqual(['Executive recipient count', 'PO approver count'])
  })

  it('onay durumu ekranla aynı: onay akışı YOKSA (yönetici alıcısı 0) "—", "Onaya gönderilmedi" değil; sayı bilinmiyorsa "Bilinmiyor"', () => {
    const pr = (state) => ({ id: 1, status: 'draft', ...(state === undefined ? {} : { approval: { state } }) })
    // Akış yok: sunucu yine state "none" yollar, ekran rozet çizmez → "—"
    expect(exportApprovalStatus({ executive_count: 0, period_report: pr('none') }, tr)).toBe('—')
    expect(exportApprovalStatus({ executive_count: '0', period_report: pr('none') }, tr)).toBe('—')
    expect(exportApprovalStatus({ executive_count: 0, period_report: pr(undefined) }, tr)).toBe('—')
    // Akış var, henüz gönderilmedi
    expect(exportApprovalStatus({ executive_count: 2, period_report: pr('none') }, tr)).toBe('Onaya gönderilmedi')
    // Alıcı sayısı bilinmiyor → akışın olup olmadığı da bilinmiyor
    expect(exportApprovalStatus({ period_report: pr('none') }, tr)).toBe('Bilinmiyor')
    expect(exportApprovalStatus({ executive_count: null, period_report: pr('none') }, tr)).toBe('Bilinmiyor')
    expect(exportApprovalStatus({ executive_count: 'x', period_report: pr('none') }, tr)).toBe('Bilinmiyor')
    expect(exportApprovalStatus({ executive_count: 2, period_report: pr(undefined) }, tr)).toBe('Bilinmiyor')
    // Süren / sonuçlanmış onay ekranda rozetle görünür → metni yazılır (alıcılar sonradan kaldırılmış olsa da)
    expect(exportApprovalStatus({ executive_count: 0, period_report: pr('pending') }, tr)).toBe('Onay bekliyor')
    expect(exportApprovalStatus({ executive_count: 3, period_report: pr('approved') }, tr)).toBe('Onaylandı')
    // Rapor yok → uygulanmaz
    expect(exportApprovalStatus({ executive_count: 2, period_report: null }, tr)).toBe('—')
    expect(exportRow(ch(9, 'Akışsız', { period_report: pr('none') }), tr)[8]).toBe('—')
  })

  it('exportTable verilen SIRAYI korur (çağıran süzülmüş + sıralanmış tam listeyi verir)', () => {
    const list = sortChannels(filterChannels(ROWS, { filter: 'open' }), 'name')
    const { header, rows } = exportTable(list, tr)
    expect(header[0]).toBe('Kanal')
    expect(rows.map((r) => r[0])).toEqual(['ATM', 'Çağrı Merkezi'])
    expect(exportTable(null, tr).rows).toEqual([])
  })

  it('dosya adı dönemle; dönem yoksa bugünün tarihi', () => {
    expect(exportFileName({ year: 2026, month: 9 }, 'csv')).toBe('teknik-hata-raporu-kanallar-2026-09.csv')
    expect(exportFileName({ year: 2026, month: 12 }, 'xlsx')).toBe('teknik-hata-raporu-kanallar-2026-12.xlsx')
    expect(exportFileName(null, 'csv', new Date(2026, 9, 7))).toBe('teknik-hata-raporu-kanallar-2026-10-07.csv')
  })
})

describe('techErrorSettingsModel — inceleme 2026-10-18', () => {
  it('countOrNull: bilinmeyen sayı null (0 değil — "PO yok" demez); negatif / metin bilinmiyor', () => {
    expect(countOrNull(3)).toBe(3)
    expect(countOrNull('2')).toBe(2)
    expect(countOrNull(0)).toBe(0)
    for (const v of [undefined, null, '', 'x', -1, NaN]) expect(countOrNull(v), String(v)).toBeNull()
  })

  it('missingTeamText + teamOptions: rehber okunduysa seçili ama rehberde olmayan takım "Silinmiş takım", okunamadıysa "Takım adı okunamadı" — asla çıplak "#99"', () => {
    expect(missingTeamText(99, true, tr)).toBe('Silinmiş takım')
    expect(missingTeamText(99, false, tr)).toBe('Takım adı okunamadı')
    const opts = teamOptions([{ id: 1, name: 'Dijital' }], [1, 99], 'pasif', (id) => missingTeamText(id, true, tr))
    expect(opts.map((o) => o.label)).toEqual(['Dijital', 'Silinmiş takım'])
    expect(opts.find((o) => o.value === 99)).toMatchObject({ missing: true })
    expect(opts.map((o) => o.label).join(' ')).not.toContain('#99')
  })

  it('inceleme 2026-10-18: birden fazla eksik takım numaralanır (kimliğe göre artan; kimlik gösterilmez); tek eksik numarasız', () => {
    expect(missingTeamText(99, true, tr, 2, 3)).toBe('Silinmiş takım 2')
    expect(missingTeamText(99, false, tr, 1, 2)).toBe('Adı okunamayan takım 1')
    expect(missingTeamText(99, true, tr, 1, 1)).toBe('Silinmiş takım')
    const label = (id, i, n) => missingTeamText(id, true, tr, i, n)
    const opts = teamOptions([{ id: 1, name: 'Dijital' }], [99, 1, 42, 7], 'pasif', label)
    expect(opts.map((o) => o.label)).toEqual(['Dijital', 'Silinmiş takım 1', 'Silinmiş takım 2', 'Silinmiş takım 3'])
    expect(opts.filter((o) => o.missing).map((o) => o.value)).toEqual([7, 42, 99])
    expect(opts.map((o) => o.label).join(' ')).not.toMatch(/#\d/)
    // Eski tek argümanlı etiket işlevi de çalışır
    expect(teamOptions([], [5], '', (id) => `X${id}`).map((o) => o.label)).toEqual(['X5'])
  })

  it('inceleme 2026-10-18: onlyDeletedTeams — kayıtlı liste YALNIZ rehberde olmayan takımlardan oluşuyorsa true; rehber okunmadıysa / liste boşsa / biri varsa false', () => {
    const dir = [{ id: 1, name: 'Dijital' }, { id: 2, name: 'Ödeme' }]
    expect(onlyDeletedTeams([98, 99], dir)).toBe(true)
    expect(onlyDeletedTeams([1, 99], dir)).toBe(false)
    expect(onlyDeletedTeams([], dir)).toBe(false)
    expect(onlyDeletedTeams([99], [])).toBe(false)
    expect(onlyDeletedTeams([99], null)).toBe(false)
    expect(onlyDeletedTeams(['2'], dir)).toBe(false)
  })
})
