import { describe, it, expect } from 'vitest'
import { normaliseLink, isHttpLink, linkLabel, linkLabelParts, linkKind, linkHost } from '../components/weekly/weeklyLinks.js'
import { sectionOutline, reportIssues, sortIssues, statusCounts, statusSum, statusMismatch, normaliseContent, duplicateChannelIndexes, domainSummary } from '../components/weekly/editorModel.js'
import { thisWeekSummary, matchesThisWeekFilter, matchesSearch, isoWeekLabel } from '../components/weekly/weeklyModel.js'

// Takip bağlantısı çipleri (2026-09-27) — saf kurallar: doğrulama, etiket/tür türetme. Veri biçimi DEĞİŞMEDİ (tek URL dizesi).
describe('weeklyLinks — doğrulama', () => {
  it('http/https kabul; şemasız alan adı https ile tamamlanır; kullanıcının yazdığı biçim korunur', () => {
    expect(normaliseLink('https://jira.example.com/browse/ABC-123')).toEqual({ ok: true, url: 'https://jira.example.com/browse/ABC-123' })
    expect(normaliseLink('  http://docs.example.com  ')).toEqual({ ok: true, url: 'http://docs.example.com' })
    expect(normaliseLink('jira.example.com/pm')).toEqual({ ok: true, url: 'https://jira.example.com/pm' })
    expect(normaliseLink('//cdn.example.com/x')).toEqual({ ok: true, url: 'https://cdn.example.com/x' })
    // Kurum içi kısa ad açık şemayla geçerli
    expect(normaliseLink('http://jira/browse/X-1').ok).toBe(true)
    expect(normaliseLink('')).toEqual({ ok: true, url: '' })
  })

  it('javascript:, data:, ftp:, boşluklu ve biçimsiz girdi reddedilir (hata anahtarıyla)', () => {
    expect(normaliseLink('javascript:alert(1)')).toEqual({ ok: false, error: 'wr.link.errScheme' })
    expect(normaliseLink('JavaScript:alert(1)').ok).toBe(false)
    expect(normaliseLink('data:text/html,hi')).toEqual({ ok: false, error: 'wr.link.errScheme' })
    expect(normaliseLink('ftp://files.example.com/a')).toEqual({ ok: false, error: 'wr.link.errScheme' })
    expect(normaliseLink('https://example.com/a b')).toEqual({ ok: false, error: 'wr.link.errSpace' })
    expect(normaliseLink('sadece metin')).toEqual({ ok: false, error: 'wr.link.errSpace' })
    expect(normaliseLink('notalink')).toEqual({ ok: false, error: 'wr.link.errFormat' })
  })

  it('isHttpLink: saklanan değer tıklanabilir mi (eski şemasız değer değil)', () => {
    expect(isHttpLink('https://example.com/x')).toBe(true)
    expect(isHttpLink('jira.example.com/pm')).toBe(false)
    expect(isHttpLink('javascript:alert(1)')).toBe(false)
    expect(isHttpLink('')).toBe(false)
  })
})

describe('weeklyLinks — etiket ve tür', () => {
  it('Jira tarzı anahtar etiket olur; yoksa alan adı › son yol parçası; yol yoksa sorgu ya da alan adı', () => {
    expect(linkLabel('https://jira.example.com/browse/SY-120')).toBe('SY-120')
    expect(linkLabel('https://tracker.example.com/issues?selectedIssue=OPS-7')).toBe('OPS-7')
    expect(linkLabel('https://www.example.com/docs/runbook-odeme')).toBe('example.com › runbook-odeme')
    expect(linkLabel('https://jira.example.com/issues/?filter=101')).toBe('jira.example.com › issues')
    expect(linkLabel('https://status.example.org/?view=all')).toBe('status.example.org › view=all')
    expect(linkLabel('https://example.net')).toBe('example.net')
    expect(linkHost('https://www.example.com/a')).toBe('example.com')
    // Yalnız sayı olan son parça bir öncekiyle birleşir (anlamlı kalsın); parçalar çipte ayrı çizilir
    expect(linkLabel('https://gitlab.example.com/ops/app/-/merge_requests/42')).toBe('gitlab.example.com › merge_requests/42')
    expect(linkLabelParts('https://jira.example.com/browse/SY-9')).toEqual({ key: 'SY-9' })
    expect(linkLabelParts('https://docs.example.com/runbook')).toEqual({ host: 'docs.example.com', tail: 'runbook' })
    // Uzun parça kırpılır
    expect(linkLabel('https://example.com/' + 'a'.repeat(60)).length).toBeLessThan(45)
  })

  it('tür: ticket / repo / doc / generic (alan adı + yol deseni)', () => {
    expect(linkKind('https://jira.example.com/browse/SY-1')).toBe('ticket')
    expect(linkKind('https://itsm.example.com/incident/42')).toBe('ticket')
    expect(linkKind('https://gitlab.example.com/team/app/-/merge_requests/3')).toBe('repo')
    expect(linkKind('https://confluence.example.com/display/OPS/Runbook')).toBe('doc')
    expect(linkKind('https://example.com/files/report.pdf')).toBe('doc')
    expect(linkKind('https://www.example.com/')).toBe('generic')
  })
})

describe('editorModel — ana hat ve gönderim kontrol listesi', () => {
  const EMPTY = { item1: { urgent: 0, high: 0, medium: 0, low: 0, notes_md: '', tracking_url: '' }, item2: { open_incidents: 0, notes_md: '' }, item3: { notes_md: '' }, item4: { channels: [] } }
  it('boş rapor: dört bölüm boş; sayı / not / bağlantı bölümü doldurur', () => {
    expect(sectionOutline(EMPTY).map((s) => s.filled)).toEqual([false, false, false, false])
    const c = { ...EMPTY, item1: { ...EMPTY.item1, high: 2 }, item2: { ...EMPTY.item2, postmortems_url: 'https://example.com/pm' },
      item3: { notes_md: 'toplantı' }, item4: { channels: [{ id: 'a', name: 'Web', notes_md: '  ' }] } }
    expect(sectionOutline(c).map((s) => s.filled)).toEqual([true, true, true, false])
  })

  it('kontrol listesi: boş bölüm, geçersiz (şemasız) bağlantı, adsız kanal — bölüm sırasına göre, önce hatalar', () => {
    const c = { ...EMPTY, item1: { ...EMPTY.item1, notes_md: 'x' }, item2: { ...EMPTY.item2, incidents_url: 'jira.example.com/pm' },
      item4: { channels: [{ id: 'k', name: ' ', notes_md: 'dolu' }] } }
    const got = sortIssues(reportIssues(c)).map((i) => `${i.section}:${i.kind}`)
    expect(got).toEqual(['item2:link', 'item3:empty', 'item4:channelName'])
    expect(reportIssues(null)).toEqual([])
    // Madde 4 (2026-09-27): yinelenen domain adı (büyük/küçük harf + Türkçe İ duyarsız) — ilki hariç işaretlenir
    const dup = reportIssues({ ...c, item4: { channels: [{ id: 'a', name: 'İnternet', notes_md: 'x' }, { id: 'b', name: 'internet ', notes_md: '' }] } })
      .filter((i) => i.kind === 'channelDuplicate')
    expect(dup).toEqual([expect.objectContaining({ section: 'item4', index: 1, name: 'internet' })])
    expect(duplicateChannelIndexes([{ name: 'A' }, { name: 'a' }, { name: '' }, { name: 'B' }])).toEqual(new Set([1]))
    expect(domainSummary([{ notes_md: 'x' }, { notes_md: ' ' }, {}])).toEqual({ total: 3, withUpdate: 1, without: 2 })
  })
})

describe('editorModel — Madde 1 durum dağılımı (2026-09-27)', () => {
  it('statusCounts: dört anahtar; eksik/bozuk/negatif → 0, sayısal metin kabul, tavan 100000', () => {
    expect(statusCounts({})).toEqual({ working: 0, planned: 0, on_hold: 0, done: 0 })
    expect(statusCounts({ status_counts: 'x' })).toEqual({ working: 0, planned: 0, on_hold: 0, done: 0 })
    expect(statusCounts({ status_counts: { working: '5', planned: -2, on_hold: 'abc', done: 1e9, extra: 4 } }))
      .toEqual({ working: 5, planned: 0, on_hold: 0, done: 100000 })
    expect(statusSum({ working: 2, planned: 1, on_hold: 0, done: 3 })).toBe(6)
  })

  it('normaliseContent: status_counts her zaman nesne, eski status_text korunur, girdi değişmez', () => {
    const legacy = { item1: { total: 2, urgent: 2, status_text: 'Planlandı' }, item3: { notes_md: '' } }
    const n = normaliseContent(legacy)
    expect(n.item1.status_counts).toEqual({ working: 0, planned: 0, on_hold: 0, done: 0 })
    expect(n.item1.status_text).toBe('Planlandı')
    expect(legacy.item1.status_counts).toBeUndefined()
    expect(normaliseContent({ version: 1 }).item1.status_counts.working).toBe(0)
    expect(normaliseContent(null)).toBeNull()
  })

  it('tutarsızlık: dağılım toplamı ≠ önem toplamı → kontrol listesinde uyarı; ikisi de 0 ya da eşitse yok', () => {
    expect(statusMismatch({ urgent: 1, high: 2, status_counts: { working: 2, done: 3 } })).toEqual({ sum: 5, total: 3 })
    expect(statusMismatch({ urgent: 1, high: 2, status_counts: { working: 2, done: 1 } })).toBeNull()
    expect(statusMismatch({})).toBeNull()
    const issues = reportIssues({ item1: { high: 4, status_counts: { working: 1 } }, item2: {}, item3: { notes_md: 'x' }, item4: { channels: [{ id: 'a', name: 'A', notes_md: 'y' }] } })
    expect(issues.map((i) => i.kind)).toEqual(['empty', 'statusMismatch'])
    expect(issues.find((i) => i.kind === 'statusMismatch')).toMatchObject({ section: 'item1', sum: 1, total: 4 })
    // Dağılım girilmişse bölüm dolu sayılır
    expect(sectionOutline({ item1: { status_counts: { done: 2 } } })[0].filled).toBe(true)
  })
})

describe('weeklyModel — bu hafta özeti, arama', () => {
  const data = { due_at: '2000-01-01T00:00:00', teams: [
    { status: 'APPROVED' }, { status: 'PENDING_APPROVAL' }, { status: 'DRAFT' }, { status: 'REJECTED' }, { status: 'MISSING' },
  ] }
  it('gönderilen / süren / eksik; son giriş geçtiyse geciken = süren + eksik', () => {
    const s = thisWeekSummary(data, Date.parse('2026-01-01T00:00:00Z'))
    expect(s).toMatchObject({ total: 5, submitted: 2, progress: 2, missing: 1, overdue: 3, past: true })
    const f = thisWeekSummary({ ...data, due_at: '2999-01-01T00:00:00' }, Date.parse('2026-01-01T00:00:00Z'))
    expect(f.overdue).toBe(0)
    expect(data.teams.filter((x) => matchesThisWeekFilter(x, 'overdue', true)).length).toBe(3)
    expect(data.teams.filter((x) => matchesThisWeekFilter(x, 'overdue', false)).length).toBe(0)
    expect(data.teams.filter((x) => matchesThisWeekFilter(x, 'submitted', true)).length).toBe(2)
  })

  it('arama: ISO etiket, W-no, tarih aralığı, takım, kişi — Türkçe İ duyarsız', () => {
    const r = { report_year: 2026, week_no: 7, created_by: 'Ekip Üyesi İki', updated_by: null }
    expect(isoWeekLabel(2026, 7)).toBe('2026-W07')
    expect(matchesSearch(r, '2026-w07')).toBe(true)
    expect(matchesSearch(r, 'w7')).toBe(true)
    expect(matchesSearch(r, 'iki')).toBe(true)
    expect(matchesSearch(r, 'takım a', { teamName: 'Takım A' })).toBe(true)
    expect(matchesSearch(r, 'şubat', { weekText: '9–15 Şubat 2026' })).toBe(true)
    expect(matchesSearch(r, 'yok')).toBe(false)
    expect(matchesSearch(r, '   ')).toBe(true)
  })
})
