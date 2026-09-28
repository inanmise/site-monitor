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

  it('contact rows are always the team’s own; a contact without an address is excluded with NO_EMAIL', () => {
    // 2026-09-28: "global" yedek yolu kalktı — eski sunucu bayrağı gelse bile satır "globalContact" olarak işaretlenmez.
    const v = buildView({
      email_total: 1, contacts_fallback_global: true, team_emails: [],
      contacts: [
        { id: 5, name: 'Müdür', email: 'm@example.com', role: 'MANAGER', min_level: 'HIGH', team_id: 7 },
        { id: 6, name: 'No Mail', email: '  ', role: 'TECH', min_level: 'WARNING', team_id: 7 },
      ],
    })
    expect(v.emails.map((e) => e.source)).toEqual(['contact'])
    expect(v).not.toHaveProperty('fallbackGlobal')
    expect(v.teamContacts).toBe('ok')
    expect(v.excluded).toEqual([{ key: 'contact:6', channel: 'email', name: 'No Mail', sub: 'TECH', reason: 'NO_EMAIL' }])
  })

  it('teamContacts: none set up vs. none at this level vs. ok (server flags team_contacts_missing / _defined)', () => {
    const base = { email_total: 1, team_emails: [{ email: 'team@example.com', team: 'Takım A', source: 'Takım maili' }], contacts: [] }
    expect(buildView({ ...base, team_contacts_missing: true, team_contacts_defined: false }).teamContacts).toBe('none')
    expect(buildView({ ...base, team_contacts_missing: true, team_contacts_defined: true }).teamContacts).toBe('noneAtLevel')
    expect(buildView({ ...base, team_contacts_missing: false, team_contacts_defined: true }).teamContacts).toBe('ok')
    expect(buildView(base).teamContacts).toBe('ok')   // bayraksız (eski sunucu) → uyarı yok
  })

  it('owners: SY + UG each with its own state; without the server list a single SY entry from the flags', () => {
    const v = buildView({ team_id: 7, team_name: 'Takım A', team_contacts_missing: true, team_contacts_defined: true,
      owners: [
        { team_id: 7, role: 'SY', team_name: 'Takım A', contacts_missing: true, contacts_defined: true },
        { team_id: 9, role: 'UG', team_name: 'Takım B', contacts_missing: true, contacts_defined: false },
      ] })
    expect(v.owners).toEqual([
      { teamId: 7, role: 'SY', teamName: 'Takım A', state: 'noneAtLevel' },
      { teamId: 9, role: 'UG', teamName: 'Takım B', state: 'none' },
    ])
    expect(buildView({ team_id: 7, team_name: 'Takım A', team_contacts_missing: false }).owners)
      .toEqual([{ teamId: 7, role: 'SY', teamName: 'Takım A', state: 'ok' }])
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
    // 2026-09-28: push görünümü kişi bazlı karar alanlarıyla genişledi (rows / nonRecipients / access / settings / channel)
    // — anlam aynı: veri yoksa "mevcut değil", sıfır değil; erişim NONE, satır yok.
    expect(v.push).toMatchObject({ available: false, error: null, recipients: [], total: 0,
      rows: [], nonRecipients: [], access: 'NONE', settings: 'NONE', self: null })
    expect(v.managersIncluded).toBe(false)
    expect(buildView({ push_error: 'boom' }).push.error).toBe('boom')
  })
})
