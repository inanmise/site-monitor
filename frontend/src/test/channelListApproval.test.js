import { describe, it, expect } from 'vitest'
import {
  APPROVAL_BADGES, APPROVAL_FILTERS, CHANNEL_STATUSES, REPORT_APPROVAL_STATUS, REPORT_STATUSES, approvalBadgeKey,
  approvalCounts, approverTeamIds, channelCounts, filterChannels, filterReports, isApprovalPending, normalizeChannelStatus,
  normalizeReportStatus,
} from '../components/techerror/channelListModel.js'

// Kanal teknik hata raporları — A1 (2026-11-20): onay rozeti ve onay süzgeçleri. Akışsız kanalda raporun eski onay
// durumu yok sayılır; bilinmeyen / eksik onay verisi rozet üretmez (bilinmeyen ≠ "onay gerekmiyor"); "Onayımı bekleyen"
// yalnız sunucunun `can_approve === true` dediği takımlarda.
const flow = { approval_required: true }
const noFlow = { approval_required: false }
const rep = (id, status, state, extra = {}) => ({ id, year: 2026, month: 9, title: `R${id}`, status,
  ...(state === undefined ? {} : { approval: state === null ? null : { state } }), ...extra })
const ch = (id, team, state, extra = {}) => ({ id, name: `K${id}`, team_id: team, team_name: `T${team}`, active: true,
  approval_required: true, period_report: state === undefined ? null : { id: 100 + id, status: 'draft', approval: { state } },
  ...extra })

describe('channelListModel — onay rozeti (A1)', () => {
  it('rozet anahtarı: bekleyen / eskimiş / reddedilen; onaylı yalnız gönderilmemişken', () => {
    expect(approvalBadgeKey(rep(1, 'draft', 'pending'), flow)).toBe('pending')
    expect(approvalBadgeKey(rep(1, 'changed', 'stale'), flow)).toBe('stale')
    expect(approvalBadgeKey(rep(1, 'draft', 'rejected'), flow)).toBe('rejected')
    expect(approvalBadgeKey(rep(1, 'draft', 'approved'), flow)).toBe('approved_unsent')
    expect(approvalBadgeKey(rep(1, 'changed', 'approved'), flow)).toBe('approved_unsent')
    expect(approvalBadgeKey(rep(1, 'sent', 'approved'), flow)).toBeNull()        // gönderildi: durum rozeti yeter
    expect(approvalBadgeKey(rep(1, 'queued', 'approved'), flow)).toBeNull()      // kuyrukta: durum rozeti söyler
    expect(approvalBadgeKey(rep(1, 'sending', 'approved'), flow)).toBeNull()
    expect(approvalBadgeKey(rep(1, 'draft', 'none'), flow)).toBeNull()
    expect(APPROVAL_BADGES).toEqual(['pending', 'stale', 'rejected', 'approved_unsent'])
  })

  it('akışsız kanalda, kanal bilinmezken ya da onay verisi yok / bozukken rozet yok', () => {
    expect(approvalBadgeKey(rep(1, 'draft', 'pending'), noFlow)).toBeNull()
    expect(approvalBadgeKey(rep(1, 'draft', 'pending'), null)).toBeNull()
    expect(approvalBadgeKey(rep(1, 'draft', 'pending'), {})).toBeNull()                   // eski sunucu: alan yok
    expect(approvalBadgeKey(rep(1, 'draft', 'pending'), { approval_required: 'true' })).toBeNull()
    expect(approvalBadgeKey(rep(1, 'draft', undefined), flow)).toBeNull()                  // approval alanı yok
    expect(approvalBadgeKey(rep(1, 'draft', null), flow)).toBeNull()
    expect(approvalBadgeKey({ status: 'draft', approval: { state: 5 } }, flow)).toBeNull()
    expect(approvalBadgeKey({ status: 'draft', approval: 'pending' }, flow)).toBeNull()
    expect(approvalBadgeKey(rep(1, 'draft', 'yeni-durum'), flow)).toBeNull()
    expect(approvalBadgeKey(null, flow)).toBeNull()
  })
})

describe('channelListModel — onay süzgeçleri (A1)', () => {
  const TEAMS = [
    { id: 1, name: 'T1', can_approve: true },
    { id: 2, name: 'T2', can_approve: false },
    { id: 3, name: 'T3', can_approve: null },       // bilinmiyor → sayılmaz
    { id: 4, name: 'T4' },                          // eski sunucu
  ]
  const CHANNELS = [
    ch(1, 1, 'pending'),
    ch(2, 2, 'pending'),
    ch(3, 3, 'pending'),
    ch(4, 1, 'approved'),
    ch(5, 1, 'stale'),                                       // eskimiş istek onay beklemez (eksikler penceresiyle aynı)
    ch(6, 1, 'pending', { active: false }),                 // pasif kanal sayılmaz
    ch(7, 1, 'pending', { approval_required: false }),      // akışı kalkmış kanal sayılmaz
    ch(8, 4, undefined),                                     // dönem raporu yok
    ch(9, 2, 'pending', { approval_required: undefined }), // eski sunucu
  ]

  it('onaylayıcı takımlar yalnız can_approve === true; kimlikler metin', () => {
    const set = approverTeamIds(TEAMS)
    expect([...set]).toEqual(['1'])
    expect(approverTeamIds(null).size).toBe(0)
    expect(approverTeamIds([null, { id: 7, can_approve: 'true' }]).size).toBe(0)
  })

  it('isApprovalPending: etkin + onay akışlı + dönem raporu bekliyor', () => {
    expect(CHANNELS.filter(isApprovalPending).map((c) => c.id)).toEqual([1, 2, 3])
    expect(isApprovalPending(null)).toBe(false)
  })

  it('sayımlar: bekleyen, benim onayımı bekleyen, akışlı kanal; bilinmeyen takım "benim" sayılmaz', () => {
    const mine = approverTeamIds(TEAMS)
    expect(approvalCounts(CHANNELS, mine)).toEqual({ pending: 3, mine: 1, flow: 7 })
    expect(approvalCounts(CHANNELS)).toEqual({ pending: 3, mine: 0, flow: 7 })
    expect(approvalCounts([], mine)).toEqual({ pending: 0, mine: 0, flow: 0 })
  })

  it('süzgeç: approval_pending tüm bekleyenler, my_approval yalnız onaylayıcı takımlar; aramayla birleşir', () => {
    const mine = approverTeamIds(TEAMS)
    expect(filterChannels(CHANNELS, { status: 'approval_pending' }).map((c) => c.id)).toEqual([1, 2, 3])
    expect(filterChannels(CHANNELS, { status: 'my_approval', approverTeams: mine }).map((c) => c.id)).toEqual([1])
    expect(filterChannels(CHANNELS, { status: 'my_approval' })).toEqual([])          // takım bilinmiyor → boş, "hepsi" değil
    expect(filterChannels(CHANNELS, { status: 'approval_pending', q: 'K2' }).map((c) => c.id)).toEqual([2])
    expect(normalizeChannelStatus('approval_pending')).toBe('approval_pending')
    expect(normalizeChannelStatus('my_approval')).toBe('my_approval')
    expect(APPROVAL_FILTERS.every((f) => CHANNEL_STATUSES.includes(f))).toBe(true)
  })

  it('"Açık" KPI anlamı değişmez: onay bekleyen taslak hâlâ "open" sayılır', () => {
    expect(channelCounts(CHANNELS.filter((c) => c.active)).open).toBe(7)
    expect(filterChannels(CHANNELS, { status: 'open' }).map((c) => c.id)).toEqual([1, 2, 3, 4, 5, 7, 9])
  })
})

describe('channelListModel — rapor listesi onay süzgeci (A1)', () => {
  const REPORTS = [rep(1, 'draft', 'pending'), rep(2, 'sent', 'approved'), rep(3, 'changed', 'stale'), rep(4, 'draft', 'none')]
  const label = () => 'Eylül 2026'

  it('approval_pending yalnız onay akışlı kanalda bekleyen raporlar; akışsız / bilinmeyen kanalda hiçbir şey', () => {
    expect(filterReports(REPORTS, { status: REPORT_APPROVAL_STATUS, channel: flow }, label).map((r) => r.id)).toEqual([1])
    expect(filterReports(REPORTS, { status: REPORT_APPROVAL_STATUS, channel: noFlow }, label)).toEqual([])
    expect(filterReports(REPORTS, { status: REPORT_APPROVAL_STATUS }, label)).toEqual([])
    expect(filterReports(REPORTS, { status: 'draft', channel: flow }, label).map((r) => r.id)).toEqual([1, 4])
  })

  it('URL değeri kabul edilir ama durum listesine (sayımlara) girmez', () => {
    expect(normalizeReportStatus('approval_pending')).toBe('approval_pending')
    expect(normalizeReportStatus('approval')).toBe('all')
    expect(REPORT_STATUSES).not.toContain('approval_pending')
  })
})
