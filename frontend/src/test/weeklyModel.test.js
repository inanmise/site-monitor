import { describe, it, expect } from 'vitest'
import {
  effectiveStatus, statusFacets, approvalQueue, filterByStatus, sortReports, scoreBand, countdown, delta,
  parsePrevContent, buildYearCsv, toUrlMapping,
} from '../components/weekly/weeklyModel.js'

// Haftalık Raporlar modeli (2026-09-13): saf kurallar.
const R = (o) => ({ id: 1, team_id: 5, report_year: 2026, week_no: 37, status: 'DRAFT', ...o })
const t = (k) => k

describe('weeklyModel — durum, facet, kuyruk', () => {
  it('APPROVED + sent_at → SENT; facet sayaçları; onay kuyruğu rol/takım kapsamlı', () => {
    const list = [R({ id: 1, status: 'APPROVED', sent_at: 'x' }), R({ id: 2, status: 'APPROVED' }), R({ id: 3, status: 'PENDING_APPROVAL' }),
      R({ id: 4, status: 'PENDING_APPROVAL', team_id: 9 }), R({ id: 5, status: 'REJECTED' }), R({ id: 6 })]
    expect(effectiveStatus(list[0])).toBe('SENT'); expect(effectiveStatus(list[1])).toBe('APPROVED')
    expect(statusFacets(list)).toEqual({ all: 6, DRAFT: 1, PENDING_APPROVAL: 2, APPROVED: 1, SENT: 1, REJECTED: 1 })
    expect(approvalQueue(list, { isAdmin: true, teamId: null }).map((r) => r.id)).toEqual([3, 4])
    expect(approvalQueue(list, { isAdmin: false, teamId: 5 }).map((r) => r.id)).toEqual([3])
    expect(approvalQueue(list, { isAudit: true })).toEqual([])
    expect(filterByStatus(list, 'SENT').map((r) => r.id)).toEqual([1])
    expect(filterByStatus(list, 'MINE', { isAdmin: false, teamId: 9 }).map((r) => r.id)).toEqual([4])
    expect(filterByStatus(list, '')).toHaveLength(6)
  })
  it('sıralama: hafta (varsayılan desc), takım adı, durum sırası, skor (null en sona), güncelleme', () => {
    const list = [R({ id: 1, week_no: 10, score: 50, status: 'APPROVED', updated_at: '2026-03-01' }), R({ id: 2, week_no: 12, score: null, status: 'DRAFT', updated_at: '2026-03-20', team_id: 1 }),
      R({ id: 3, week_no: 11, score: 90, status: 'REJECTED', updated_at: '2026-03-10' })]
    expect(sortReports(list, 'week|desc').map((r) => r.id)).toEqual([2, 3, 1])
    expect(sortReports(list, 'score|desc').map((r) => r.id)).toEqual([3, 1, 2])
    expect(sortReports(list, 'status|asc').map((r) => r.id)).toEqual([3, 2, 1])
    expect(sortReports(list, 'updated|desc').map((r) => r.id)).toEqual([2, 3, 1])
    expect(sortReports(list, 'team|asc', (id) => (id === 1 ? 'Alfa' : 'Beta')).map((r) => r.id)[0]).toBe(2)
    expect(scoreBand(80)).toBe('green'); expect(scoreBand(60)).toBe('amber'); expect(scoreBand(59)).toBe('red'); expect(scoreBand(null)).toBeNull()
  })
})

describe('weeklyModel — geri sayım, Δ, önceki içerik, CSV, URL', () => {
  it('countdown: kalan/geçen gün-saat-dakika; bozuk tarih null', () => {
    const now = Date.parse('2026-09-10T10:00:00Z')
    expect(countdown('2026-09-11T12:30:00', now)).toMatchObject({ past: false, d: 1, h: 2, m: 30 })
    expect(countdown('2026-09-09T09:00:00', now)).toMatchObject({ past: true, d: 1, h: 1 })
    expect(countdown('bozuk', now)).toBeNull()
    expect(delta(3, 1)).toBe(2); expect(delta('4', 4)).toBe(0); expect(delta(null, 1)).toBeNull()
  })
  it('parsePrevContent güvenli; buildYearCsv başlık + satır; toUrlMapping yalnız varsayılan-dışını yazar', () => {
    expect(parsePrevContent('{bozuk')).toBeNull()
    expect(parsePrevContent('{"item1":{"total":2},"item4":{"channels":[{"id":"c-1"}]}}')).toMatchObject({ item1: { total: 2 }, channels: [{ id: 'c-1' }] })
    const csv = buildYearCsv([R({ week_no: 3, score: 77, status: 'APPROVED', sent_at: '2026-01-20T10:00:00', created_by: 'A, B' })], () => 'Takım A', t)
    const lines = csv.split('\r\n')
    expect(lines[0]).toBe('wr.colWeek,wr.team,wr.statusCol,wr.colScore,wr.colCreated,wr.colUpdated,wr.colApproved,wr.colSent')
    expect(lines[1]).toBe('2026-W03,Takım A,SENT,77,"A, B",,,2026-01-20T10:00:00')
    expect(toUrlMapping({ selectedId: null, selTeamId: '', year: 2026, weekFilter: null, statusChip: '', sort: 'week|desc', currentYear: 2026 }))
      .toEqual({ w_id: null, w_team: null, w_year: null, w_week: null, w_st: null, w_sort: null })
    expect(toUrlMapping({ selectedId: 9, selTeamId: '5', year: 2025, weekFilter: 12, statusChip: 'MINE', sort: 'score|desc', currentYear: 2026 }))
      .toEqual({ w_id: 9, w_team: '5', w_year: 2025, w_week: 12, w_st: 'MINE', w_sort: 'score|desc' })
  })
})
