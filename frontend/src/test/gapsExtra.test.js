import { describe, it, expect, vi } from 'vitest'
import {
  EXTRA_TEAM_CODES, TASK_CODES, actionTasks, approvalTasks, channelApproverGaps, channelReportTasks, forwardGaps, groupGaps,
  onCallMembership, oncallGaps,
} from '../components/gaps/gapsExtra.js'
import { channelLink, extraGapLink, extraItemLink, fixKindOf } from '../components/gaps/gapsLinks.js'
import { POOL_SIZE, classify, contextOf, loadGapSources, runPool, teamNameOf } from '../components/gaps/gapsSources.js'
import { DEADLINE, LOAD_DEADLINE_MS, collectGaps } from '../components/gaps/gapsModel.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const TEAM = { id: 4, name: 'Dijital Kanallar' }
const err = (status) => Object.defineProperty({ success: false, error: 'x' }, 'errorInfo', { value: { status } })

describe('eksikler — veri kalitesi dışı takım eksikleri', () => {
  it('varsayılan grubun bütün adresleri pasif → eksik; etkin adres sayısı bilinmiyorsa ya da adres etkinse eksik yok', () => {
    const g = (over) => ({ id: 31, team_id: 4, name: 'Nöbet listesi', active: true, is_default: true, emails: ['a@x.com', 'b@x.com'], ...over })
    expect(groupGaps(TEAM, [g({ inactive_emails: ['a@x.com', 'B@x.com'] })]).map((x) => [x.code, x.severity, x.items[0].name]))
      .toEqual([['TEAM_GROUP_ALL_PASSIVE', 'HIGH', 'Nöbet listesi']])
    expect(groupGaps(TEAM, [g({ inactive_emails: ['a@x.com'] })])).toEqual([])
    expect(groupGaps(TEAM, [g({})])).toEqual([])                                  // inactive_emails yok → bilinmiyor
    expect(groupGaps(TEAM, [g({ inactive_emails: ['a@x.com', 'b@x.com'], is_default: false })])).toEqual([])
    expect(groupGaps(TEAM, [g({ team_id: 9, inactive_emails: ['a@x.com', 'b@x.com'] })])).toEqual([])
    expect(groupGaps(TEAM, null)).toEqual([])
  })

  it('nöbet çizelgesi: yalnız AÇIK çizelgede pasif / silinmiş / e-postasız kişi; şu anki nöbetçi böyleyse önem yüksek', () => {
    const view = { enabled: true, members: [
      { user_id: 'a', name: 'Ali', active: true, has_email: true },
      { user_id: 'b', name: 'Banu', active: false, has_email: true },
      { user_id: null, name: null, active: false, missing: true },
      { user_id: 'c', name: 'Cem', active: true, has_email: false },
    ], current: { user_id: 'a', active: true, has_email: true } }
    const [gap] = oncallGaps(TEAM, view)
    expect(gap.code).toBe('TEAM_ONCALL_MEMBERS')
    expect(gap.severity).toBe('MEDIUM')
    expect(gap.items.map((i) => [i.name, i.state])).toEqual([['Banu', 'inactive'], [null, 'deleted'], ['Cem', 'no_email']])
    expect(oncallGaps(TEAM, { ...view, current: { active: false } })[0].severity).toBe('HIGH')
    expect(oncallGaps(TEAM, { ...view, enabled: false })).toEqual([])
    expect(oncallGaps(TEAM, { enabled: true, members: [{ name: 'Ali', active: true, has_email: true }] })).toEqual([])
  })

  it('alarm iletimi: yalnız takımın kendi iletimlerinden dikkat isteyenler (alıcı pasif / hedef yok / üye yok / adres yok)', () => {
    const f = (over) => ({ id: 1, owner_team_id: 4, recipient_team_name: 'Ağ Ekibi', recipient_team_active: true, enabled: true,
      state: 'active', recipient_address_count: 2, scope_kind: 'ALL', recipient_kind: 'TEAM', ...over })
    const list = [f({ id: 1, state: 'recipient_inactive' }), f({ id: 2, state: 'paused', enabled: false }), f({ id: 3, state: 'expired' }),
      f({ id: 4, state: 'active', recipient_address_count: 0 }), f({ id: 5, owner_team_id: 9, state: 'no_targets' }), f({ id: 6 })]
    const [gap] = forwardGaps(TEAM, list)
    expect(gap.items.map((i) => [i.id, i.state])).toEqual([[1, 'recipient_inactive'], [4, 'no_address']])
    expect(forwardGaps(TEAM, [f({})])).toEqual([])
  })

  it('onay akışlı kanalda onaylayıcı yok: yalnız sunucunun saydığı 0 (null / alan yok = bilinmiyor → eksik yok)', () => {
    const overview = (count) => ({ teams: [{ id: 4, ...(count === undefined ? {} : { approver_count: count }) }],
      channels: [{ id: 10, team_id: 4, name: 'Mobil', active: true, approval_required: true },
        { id: 11, team_id: 4, name: 'Web', active: true, approval_required: false },
        { id: 12, team_id: 4, name: 'Eski', active: false, approval_required: true }] })
    expect(channelApproverGaps(TEAM, overview(0))[0].items.map((i) => i.name)).toEqual(['Mobil'])
    expect(channelApproverGaps(TEAM, overview(2))).toEqual([])
    expect(channelApproverGaps(TEAM, overview(null))).toEqual([])
    expect(channelApproverGaps(TEAM, overview(undefined))).toEqual([])
  })
})

describe('eksikler — bekleyen işler', () => {
  const ov = {
    teams: [{ id: 4, can_approve: true }, { id: 5, can_approve: false }],
    channels: [
      { id: 1, team_id: 4, name: 'Açılmamış', active: true, can_report: true, period_report: null },
      { id: 2, team_id: 4, name: 'Taslak', active: true, can_report: true, period_report: { id: 20, status: 'draft' } },
      { id: 3, team_id: 4, name: 'Değişti', active: true, can_report: true, period_report: { id: 30, status: 'changed', approval: { state: 'pending' } } },
      { id: 4, team_id: 4, name: 'Gönderildi', active: true, can_report: true, period_report: { id: 40, status: 'sent' } },
      { id: 5, team_id: 4, name: 'Kuyrukta', active: true, can_report: true, period_report: { id: 50, status: 'queued' } },
      { id: 6, team_id: 4, name: 'Paylaşım', active: true, can_report: true, shared_access: true, period_report: null },
      { id: 7, team_id: 4, name: 'Pasif', active: false, can_report: true, period_report: null },
      { id: 8, team_id: 4, name: 'Okur', active: true, can_report: false, period_report: null },
      { id: 9, team_id: 5, name: 'Öteki', active: true, can_report: true, period_report: { id: 90, status: 'draft', approval: { state: 'pending' } } },
    ],
  }

  it('kanal raporu: açılmamış ya da taslak / değişmiş (gönderilmemiş); gönderilen, kuyruktaki, paylaşımla görülen, pasif, yetkisiz değil', () => {
    const [gap] = channelReportTasks(TEAM, ov)
    expect(gap.category).toBe('tasks')
    expect(gap.items.map((i) => [i.name, i.state, i.reportId])).toEqual([['Açılmamış', 'missing', null], ['Taslak', 'unsent', 20], ['Değişti', 'unsent', 30]])
  })

  it('onay bekleyen: yalnız sunucunun onaylayıcı dediği takımın onay bekleyen raporu (okunan takımlar arasında)', () => {
    const [gap] = approvalTasks([TEAM, { id: 5, name: 'Beş' }], ov)
    expect(gap.key).toBe('k:TASK_APPROVAL_PENDING')
    expect(gap.items.map((i) => [i.name, i.reportId, i.teamName])).toEqual([['Değişti', 30, 'Dijital Kanallar']])
    expect(approvalTasks([{ id: 5 }], ov)).toEqual([])                              // takım 4 okunmadı
  })

  it('gecikmiş aksiyonlar: toplam sayı kalem sayısından büyük olabilir (en çok 5 kalem okunur)', () => {
    const [gap] = actionTasks({ total: 7, items: [{ id: 1, title: 'Yedek', due_date: '2026-11-01', incident_title: 'Kesinti' }] })
    expect(gap.failing).toBe(7)
    expect(gap.more).toBe(6)
    expect(gap.items[0]).toEqual({ kind: 'action', id: 1, name: 'Yedek', due: '2026-11-01', incident: 'Kesinti' })
    expect(actionTasks({ total: 0, items: [] })).toEqual([])
    expect(actionTasks(null)).toEqual([])
  })

  it('nöbet üyeliği (önem bağlamı): kullanıcı adıyla; okunamayan / yetkisiz çizelge varsa ve başka yerde üye değilse bilinmiyor', () => {
    const v = (members, enabled = true) => ({ status: 'ok', data: { enabled, members } })
    expect(onCallMembership([v([{ username: 'AYSE' }])], 'ayse')).toBe(true)
    expect(onCallMembership([v([{ username: 'ayse' }], false)], 'ayse')).toBe(false)
    expect(onCallMembership([v([{ username: 'ali' }]), { status: 'failed' }], 'ayse')).toBeNull()
    expect(onCallMembership([{ status: 'denied' }], 'ayse')).toBeNull()
    expect(onCallMembership([{ status: 'skipped' }], 'ayse')).toBe(false)
    expect(onCallMembership([{ status: 'failed' }, v([{ username: 'ayse' }])], 'ayse')).toBe(true)
    expect(onCallMembership([], 'ayse')).toBe(false)
  })

  it('her ek kod ve iş için başlık / neden / ne yapmalı / aç metni iki dilde; durum metinleri iki dilde', () => {
    for (const c of [...EXTRA_TEAM_CODES, ...TASK_CODES]) {
      for (const part of ['title', 'why', 'fix', 'open']) {
        const k = `gaps.code.${c}.${part}`
        expect(tr[k], k).toBeTruthy()
        expect(en[k], k).toBeTruthy()
      }
    }
    for (const s of ['deleted', 'inactive', 'no_email', 'missing', 'unsent', 'recipient_inactive', 'no_targets', 'no_recipients', 'no_address']) {
      expect(tr[`gaps.state.${s}`], s).toBeTruthy()
      expect(en[`gaps.state.${s}`], s).toBeTruthy()
    }
  })
})

describe('eksikler — bağlantılar', () => {
  it('kalem ve toplu bağlantılar var olan URL anahtarlarıyla; düzeltme türü', () => {
    expect(channelLink(10)).toEqual({ tab: 'channelreports', params: { te_view: 'channel', te_ch: '10' } })
    expect(channelLink(10, 20)).toEqual({ tab: 'channelreports', params: { te_view: 'report', te_ch: '10', te_rep: '20' } })
    expect(extraItemLink({ code: 'TASK_CHANNEL_REPORT' }, { id: 10, state: 'missing', reportId: null }).params.te_view).toBe('channel')
    expect(extraItemLink({ code: 'TASK_CHANNEL_REPORT' }, { id: 10, state: 'unsent', reportId: 20 }).params.te_rep).toBe('20')
    expect(extraItemLink({ code: 'TEAM_FORWARD_BROKEN', teamId: 4 }, {})).toEqual({ tab: 'admin', params: { g_tab: 'forwards', g_fteam: '4', g_fstate: 'attention' } })
    expect(extraGapLink({ code: 'TEAM_ONCALL_MEMBERS', teamName: 'Dijital' })).toEqual({ tab: 'admin', params: { g_tab: 'teams', g_q: 'Dijital' } })
    expect(extraGapLink({ code: 'TASK_ACTIONS_OVERDUE' }).params).toEqual({ ih_view: 'actions', ih_amine: '1', ih_aover: '1' })
    expect(extraGapLink({ code: 'BILINMEYEN' })).toBeNull()
    expect([fixKindOf('TEAM_NO_ESCALATION'), fixKindOf('TEAM_NO_NOTIFY_ADDRESS'), fixKindOf('TEAM_GROUP_ALL_PASSIVE'), fixKindOf('TEAM_ONCALL_MEMBERS'), fixKindOf('MON_NO_GROUP')])
      .toEqual(['contact', 'group', 'group', 'oncall', null])
  })
})

describe('eksikler — kaynak yükleyici', () => {
  it('havuz: aynı anda en çok POOL_SIZE görev; sıra korunur; atan görev "failed" olur', async () => {
    let running = 0
    let peak = 0
    const task = (v, ms) => async () => { running++; peak = Math.max(peak, running); await new Promise((r) => setTimeout(r, ms)); running--; return v }
    const out = await runPool([task(1, 5), task(2, 1), task(3, 3), task(4, 1), task(5, 2), task(6, 1), async () => { throw new Error('x') }])
    expect(out).toEqual([1, 2, 3, 4, 5, 6, { status: 'failed' }])
    expect(peak).toBeLessThanOrEqual(POOL_SIZE)
    expect(await runPool([])).toEqual([])
  })

  it('yanıt sınıflama: başarı / 403 yetki yok / 404 uygulanamaz ya da bu sürümde yok / 405 / süre doldu / diğer okunamadı', () => {
    expect(classify({ success: true, data: [1] })).toEqual({ status: 'ok', data: [1] })
    expect(classify(err(403))).toEqual({ status: 'denied' })
    expect(classify(err(404))).toEqual({ status: 'unsupported' })
    expect(classify(err(404), { notFound: 'skipped' })).toEqual({ status: 'skipped' })
    expect(classify(err(405))).toEqual({ status: 'unsupported' })
    expect(classify(err(500))).toEqual({ status: 'failed' })
    expect(classify(DEADLINE)).toEqual({ status: 'failed' })
    expect(classify(null)).toEqual({ status: 'failed' })
  })

  function fakeApi(over = {}) {
    return {
      getMe: vi.fn(async () => ({ username: 'ayse', email: 'a@b.c', phone: '05551112233', team_ids: [4], team_names: ['Dijital Kanallar'], manage_team_ids: [], noc_operator: false })),
      getLoginMethods: vi.fn(async () => ({ success: true, otp_push: true, push_requires_phone: true })),
      me: { getPushPreferences: vi.fn(async () => ({ success: true, opt_out: false })) },
      dataQuality: { team: vi.fn(async () => ({ success: true, data: { team: { id: 4, name: 'Dijital Kanallar' }, rules: [] } })) },
      oncall: { get: vi.fn(async () => ({ success: true, data: { enabled: true, members: [{ username: 'ayse', active: true, has_email: true }] } })) },
      notificationGroups: { list: vi.fn(async () => ({ success: true, data: { groups: [] } })) },
      alertForwards: { list: vi.fn(async () => ({ success: true, data: { items: [] } })) },
      techErrors: { overview: vi.fn(async () => ({ success: true, data: { teams: [], channels: [] } })) },
      incidents: { actions: vi.fn(async () => ({ success: true, data: { items: [], total: 0 } })) },
      teams: { directory: vi.fn(async () => ({ success: true, data: [] })) },
      ...over,
    }
  }
  const ALL = { dq: 'yes', groups: 'yes', oncall: 'yes', forwards: 'yes', channels: 'yes', actions: 'yes' }

  it('yetkisi olmayan denetim HİÇ istenmez (denied); yetkiler bilinmiyorsa istenmez (off); takım dizini yalnız gerekince', async () => {
    const api = fakeApi()
    const src = await loadGapSources({ api, can: { ...ALL, groups: 'no', actions: 'unknown' }, globalAdmin: false })
    expect(api.notificationGroups.list).not.toHaveBeenCalled()
    expect(api.incidents.actions).not.toHaveBeenCalled()
    expect(src.groups).toEqual({ status: 'denied' })
    expect(src.actions).toEqual({ status: 'off' })
    expect(api.teams.directory).not.toHaveBeenCalled()                   // profil eksiği yok, kalan takım yok
    expect(api.dataQuality.team).toHaveBeenCalledWith(4, false, { timeoutMs: 20000 })
    expect(src.teams.map((t) => [t.id, t.name, t.dq.status, t.oncall.status])).toEqual([[4, 'Dijital Kanallar', 'ok', 'ok']])
    expect(api.incidents.actions).not.toHaveBeenCalled()
    // önem bağlamı: nöbetçi + kodla girişte telefon zorunlu
    expect(contextOf(src, 'ayse')).toEqual({ onCall: true, noc: false, otpPhone: true, otpEmail: false })
    const g = collectGaps({ profile: src.me, ctx: contextOf(src, 'ayse'), prefs: src.prefs.data, prefsStatus: src.prefs.status, teams: src.teams })
    expect(g.total).toBe(0)
  })

  it('profilde iletişim eksiği varsa (yönetici değil) takım dizini okunur; kalan takım varsa da', async () => {
    const api = fakeApi({ getMe: vi.fn(async () => ({ username: 'ayse', email: null, phone: '', team_ids: [4] })) })
    await loadGapSources({ api, can: ALL, globalAdmin: false })
    expect(api.teams.directory).toHaveBeenCalledTimes(1)
    const api2 = fakeApi({ getMe: vi.fn(async () => ({ username: 'ayse', email: null, phone: '', team_ids: [4] })) })
    await loadGapSources({ api: api2, can: ALL, globalAdmin: true })
    expect(api2.teams.directory).not.toHaveBeenCalled()
    const many = [...Array(12)].map((_, i) => i + 1)
    const api3 = fakeApi({ getMe: vi.fn(async () => ({ username: 'ayse', email: 'a@b.c', phone: '05551112233', team_ids: many })) })
    const src = await loadGapSources({ api: api3, can: ALL, globalAdmin: true })
    expect(api3.teams.directory).toHaveBeenCalledTimes(1)
    expect(src.autoTeamIds).toEqual(many.slice(0, 10))
    expect(src.restTeamIds).toEqual([11, 12])
    expect(api3.dataQuality.team).toHaveBeenCalledTimes(10)
  })

  it('takılan istek üst sürede "okunamadı" olur — süresiz bekleme yok', async () => {
    vi.useFakeTimers()
    try {
      const api = fakeApi({ oncall: { get: vi.fn(() => new Promise(() => {})) } })
      const p = loadGapSources({ api, can: ALL })
      await vi.advanceTimersByTimeAsync(LOAD_DEADLINE_MS + 10)
      const src = await p
      expect(src.teams[0].oncall).toEqual({ status: 'failed' })
      expect(src.teams[0].dq.status).toBe('ok')
    } finally {
      vi.useRealTimers()
    }
  })

  it('yarıda bırakma: oturum değiştiyse sonuç atılır; takım adı /me ya da dizinden', async () => {
    const src = await loadGapSources({ api: fakeApi(), can: ALL, isCurrent: () => false })
    expect(src).toBeNull()
    expect(teamNameOf({ team_ids: [4, 5], team_names: ['A', 'B'] }, 5, null)).toBe('B')
    expect(teamNameOf(null, 7, { status: 'ok', data: [{ id: 7, name: 'Yedi' }] })).toBe('Yedi')
    expect(teamNameOf(null, 8, { status: 'failed' })).toBeNull()
  })
})
