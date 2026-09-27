import { describe, it, expect } from 'vitest'
import { buildView, normId, normKind, normLevel, parseTeamSource } from '../components/admin/whonotified/whoNotifiedModel.js'

/**
 * "Kim bilgilendirilir?" görünüm modeli — karar SUNUCUNUNDUR; model yalnız biçimler. Burada pinlenen: tekil adres
 * sayımı (sunucunun email_total'ıyla aynı), yinelenen kişinin aynı satıra "ayrıca" notu olarak katlanması, adresi
 * olmayan kişinin ve push'ta elenenlerin gerekçeyle "bilgilendirilmeyenler"e düşmesi, URL değerlerinin süzülmesi.
 */
describe('whoNotifiedModel', () => {
  it('parseTeamSource: server label "Grup: X" → group, anything else → team address', () => {
    expect(parseTeamSource('Grup: Nöbetçi')).toEqual({ kind: 'group', groupName: 'Nöbetçi' })
    expect(parseTeamSource('Takım maili')).toEqual({ kind: 'team', groupName: null })
    expect(parseTeamSource(null)).toEqual({ kind: 'team', groupName: null })
  })

  it('URL normalisers: unknown level/kind → defaults, ids must be positive integers', () => {
    expect(normLevel('CRITICAL')).toBe('CRITICAL')
    expect(normLevel('bogus')).toBe('HIGH')
    expect(normKind('MONITOR')).toBe('MONITOR')
    expect(normKind('x')).toBe('CERT')
    expect(normId('7')).toBe('7')
    expect(normId(7)).toBe('7')
    for (const bad of ['', '0', '-3', 'abc', '1.5', null, undefined]) expect(normId(bad)).toBe('')
  })

  it('emails are unique addresses; a duplicate contact is folded into the matching row (case-insensitive)', () => {
    const v = buildView({
      team_name: 'Takım A', email_total: 2, contacts_fallback_global: false,
      team_emails: [{ email: 'Team@example.com', team: 'Takım A', source: 'Takım maili' }],
      contacts: [
        { id: 1, name: 'Ali', email: 'ali@example.com', role: 'PO', min_level: 'WARNING', email_duplicate: false },
        { id: 2, name: 'Veli', email: 'team@EXAMPLE.com', role: 'TECH', min_level: 'HIGH', email_duplicate: true },
      ],
    })
    expect(v.emails.map((e) => [e.email, e.source])).toEqual([['Team@example.com', 'team'], ['ali@example.com', 'contact']])
    expect(v.emails[0].name).toBe('Takım A')
    expect(v.emails[0].also).toEqual([{ id: 2, name: 'Veli' }])
    expect(v.counts.email).toBe(2)
    expect(v.excluded).toEqual([])
  })

  it('global fallback marks contact rows; a contact without an address is excluded with NO_EMAIL', () => {
    const v = buildView({
      email_total: 1, contacts_fallback_global: true, team_emails: [],
      contacts: [
        { id: 5, name: 'Global', email: 'g@example.com', role: 'MANAGER', min_level: 'HIGH' },
        { id: 6, name: 'No Mail', email: '  ', role: 'TECH', min_level: 'WARNING' },
      ],
    })
    expect(v.emails.map((e) => e.source)).toEqual(['globalContact'])
    expect(v.fallbackGlobal).toBe(true)
    expect(v.excluded).toEqual([{ key: 'contact:6', channel: 'email', name: 'No Mail', sub: 'TECH', reason: 'NO_EMAIL' }])
  })

  it('push: RECIPIENT rows are recipients, every other decision is excluded with its reason; camelCase tolerated', () => {
    const v = buildView({
      email_total: 0, team_emails: [], contacts: [], webhooks: [],
      push: [
        { username: 'ali', display_name: 'Ali', group: 'po', min_level: 'WARNING', decision: 'RECIPIENT' },
        { username: 'veli', displayName: 'Veli', decision: 'SKIPPED_USER_OPT_OUT' },
        { username: '-', display_name: 'Adsız', decision: 'SKIPPED_NO_ID' },
      ],
    })
    expect(v.push).toMatchObject({ available: true, total: 3 })
    expect(v.push.recipients.map((p) => p.name)).toEqual(['Ali'])
    expect(v.excluded.map((x) => [x.name, x.channel, x.reason, x.sub])).toEqual([
      ['Veli', 'push', 'SKIPPED_USER_OPT_OUT', 'veli'], ['Adsız', 'push', 'SKIPPED_NO_ID', ''],
    ])
    expect(v.nobody).toBe(false)   // bir push alıcısı yeter
  })

  it('nobody: no email, webhook or push recipient; push missing → not available (not "zero")', () => {
    const v = buildView({ email_total: 0, team_emails: [], contacts: [], webhooks: [], managers_included: false })
    expect(v.nobody).toBe(true)
    expect(v.push).toEqual({ available: false, error: null, recipients: [], total: 0 })
    expect(v.managersIncluded).toBe(false)
    expect(buildView({ push_error: 'boom' }).push.error).toBe('boom')
  })
})
