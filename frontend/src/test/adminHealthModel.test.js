import { describe, it, expect } from 'vitest'
import { buildHealthSignals, escalationGapFrom, HEALTH_SEVERE } from '../components/admin/adminHealthModel.js'

describe('adminHealthModel — buildHealthSignals', () => {
  it('ağır sinyaller önce; kullanıcı uyarıları TEK USER_ATTENTION kutucuğunda toplanır; eskalasyon boşluğu ağır', () => {
    const overview = { dormant_days: 90, warnings: [
      { code: 'USER_LOCKED', count: 1 }, { code: 'USER_DORMANT', count: 2 }, { code: 'USER_NEVER_LOGGED_IN', count: 3 },
      { code: 'TEAM_NO_LEADER', count: 1 }, { code: 'SINGLE_ADMIN', count: 1 }, { code: 'TEAM_NO_EMAIL', count: 1 },
    ] }
    const sigs = buildHealthSignals(overview, 2)
    expect(sigs.map(s => s.code)).toEqual(['SINGLE_ADMIN', 'ESCALATION_GAP', 'TEAM_NO_EMAIL', 'TEAM_NO_LEADER', 'USER_ATTENTION'])
    const attn = sigs.find(s => s.code === 'USER_ATTENTION')
    // Birden çok tür → süzgeçsiz sekme (sayı ile tıklama hedefi tutarlı), kırılım kutucukta (2026-11-13 hata taraması)
    expect(attn).toMatchObject({ count: 6, tab: 'users', severe: false, params: {} })
    expect(attn.parts).toEqual([{ key: 'locked', count: 1 }, { key: 'never', count: 3 }, { key: 'dormant', count: 2 }])
    expect(sigs.find(s => s.code === 'ESCALATION_GAP')).toMatchObject({ count: 2, tab: 'contacts', severe: true })
    expect([...HEALTH_SEVERE]).toContain('ESCALATION_GAP')
  })

  it('deep-link paramları: SINGLE_ADMIN→users g_role ADMIN; GROUP→notifyGroups; CONTACT→contacts; TEAM→teams', () => {
    const sigs = buildHealthSignals({ warnings: [
      { code: 'SINGLE_ADMIN', count: 1 }, { code: 'GROUP_NO_EMAILS', count: 1 }, { code: 'CONTACT_INACTIVE', count: 2 }, { code: 'TEAM_NO_EMAIL', count: 1 },
    ] }, null)
    const by = Object.fromEntries(sigs.map(s => [s.code, s]))
    expect(by.SINGLE_ADMIN).toMatchObject({ tab: 'users', params: { g_role: 'ADMIN' }, severe: true })
    expect(by.GROUP_NO_EMAILS).toMatchObject({ tab: 'notifyGroups', severe: false })
    expect(by.CONTACT_INACTIVE).toMatchObject({ tab: 'contacts', severe: false })
    expect(by.TEAM_NO_EMAIL).toMatchObject({ tab: 'teams', severe: true })
  })

  it('escalationGap null (bilinmiyor) → ESCALATION_GAP kutucuğu YOK', () => {
    expect(buildHealthSignals({ warnings: [{ code: 'TEAM_NO_LEADER', count: 1 }] }, null).map(s => s.code)).toEqual(['TEAM_NO_LEADER'])
    expect(buildHealthSignals({ warnings: [] }, 0).map(s => s.code)).toEqual([])   // gap 0 → kutucuk yok
  })

  it('NO_ADMIN (count 0) gösterilir ve SINGLE_ADMIN yerine geçer', () => {
    const sigs = buildHealthSignals({ warnings: [{ code: 'NO_ADMIN', count: 0 }, { code: 'SINGLE_ADMIN', count: 1 }] }, null)
    expect(sigs[0]).toMatchObject({ code: 'NO_ADMIN', severe: true })
    expect(sigs.some(s => s.code === 'SINGLE_ADMIN')).toBe(false)
  })

  it('USER_ATTENTION tek tür: o türün süzgeci (kilitli → g_status)', () => {
    expect(buildHealthSignals({ warnings: [{ code: 'USER_LOCKED', count: 2 }] }, null)
      .find(s => s.code === 'USER_ATTENTION').params).toEqual({ g_status: 'locked' })
  })

  it('ESCALATION_GAP en az N: { count, atLeast } kutucuğu atLeast taşır', () => {
    const s = buildHealthSignals({ warnings: [] }, { count: 2, atLeast: true }).find(x => x.code === 'ESCALATION_GAP')
    expect(s).toMatchObject({ count: 2, atLeast: true, severe: true })
  })

  it('USER_ATTENTION param önceliği: kilitli yoksa atıl (dormant_days), o da yoksa hiç-giriş', () => {
    expect(buildHealthSignals({ dormant_days: 120, warnings: [{ code: 'USER_DORMANT', count: 2 }] }, null)
      .find(s => s.code === 'USER_ATTENTION').params).toEqual({ g_dormant: '120' })
    expect(buildHealthSignals({ warnings: [{ code: 'USER_NEVER_LOGGED_IN', count: 1 }] }, null)
      .find(s => s.code === 'USER_ATTENTION').params).toEqual({ g_dormant: 'never' })
  })

  it('uyarı yoksa boş dizi', () => {
    expect(buildHealthSignals({ warnings: [] }, null)).toEqual([])
    expect(buildHealthSignals(null, null)).toEqual([])
  })
})

describe('adminHealthModel — escalationGapFrom', () => {
  it('contacts okunamadı → null; statsKnown false → null; aksi → gap sayısı', () => {
    expect(escalationGapFrom({ summary: { gap: 3, statsKnown: true } }, false)).toBeNull()
    expect(escalationGapFrom({ summary: { gap: 3, statsKnown: false } }, true)).toBeNull()
    expect(escalationGapFrom({ summary: { gap: 3, statsKnown: true } }, true)).toBe(3)
    expect(escalationGapFrom({ summary: { gap: 0, statsKnown: true } }, true)).toBe(0)
    expect(escalationGapFrom(null, true)).toBeNull()
  })
  it('2026-11-13 hata taraması: kapsamı bilinmeyen takım varsa boşluk yok → null (yeşil değil); kesin boşluk varsa "en az N"', () => {
    expect(escalationGapFrom({ summary: { gap: 0, unknown: 2, statsKnown: true } }, true)).toBeNull()
    expect(escalationGapFrom({ summary: { gap: 3, unknown: 1, statsKnown: true } }, true)).toEqual({ count: 3, atLeast: true })
  })
})
