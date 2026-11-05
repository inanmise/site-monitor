import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import {
  ATTENTION_STATES, DEFAULT_LIMITS, FIELD_MAP, FORWARD_STATES, FORWARD_TYPES, TYPE_LABEL_KEYS, createPayload, daysLeft, deriveState,
  emptyForm, expiringSoon, filterForwards, filterMemberOptions, filterMonitorOptions, findMonitorForRef, formErrors, formOf,
  hasNewRecipients, memberOptions, monitorKey, monitorLabel, needsAttention, normalizeForward, normalizeLimits, normalizeMonitorOptions,
  ownerTeamOptions, parseNewRef, payloadOf, plural, recipientSummary, resultNotes, scopeChips, scopeSummary, sortForwards,
  summarize, todayIstanbul, typeLabel, updatePayload,
} from '../components/admin/forwards/alertForwardModel.js'

/**
 * Alarm iletimi modeli (2026-11-05): tür sırası Java `MonitorTypeCatalog.ORDER` ile AYNI (kaynak okunur), satır
 * normalleştirme (adres sayısı null = okunamadı, 0 değil), durum / dikkat / özet, Türkçe katlamalı süzgeç, sıralama,
 * gövdeler (yeni: tam; düzenleme: YALNIZ değişen, değişiklik yoksa boş), istemci doğrulaması, derin bağ ayrıştırma.
 */
const NOW = Date.parse('2026-11-05T09:00:00Z')
const tOf = (dict) => (k, ...a) => String(dict[k] ?? k).replace(/\{(\d)\}/g, (_, i) => String(a[Number(i)]))
const t = tOf(TR)
const tEn = tOf(EN)

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const raw = (over) => ({
  id: 1, version: 3, owner_team_id: 10, owner_team_name: 'Ödeme Sistemleri', owner_team_active: true,
  scope_kind: 'TYPES', scope_types: ['ping', 'http'], scope_monitors: [],
  recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true,
  recipient_members: [], recipient_address_count: 2, enabled: true, expires_on: null, state: 'active', note: 'Gece vardiyası',
  created_at: '2026-11-01T08:00:00', created_by: 'yonetici', updated_at: '2026-11-01T08:00:00', updated_by: 'yonetici', can_edit: true,
  ...over,
})

describe('alertForwardModel', () => {
  it('tür sırası Java MonitorTypeCatalog.ORDER ile AYNI; her türün etiketi iki dilde var', () => {
    const src = readJavaMain('service/MonitorTypeCatalog.java')
    const m = src.match(/ORDER\s*=\s*List\.of\(([^)]*)\)/)
    expect([...m[1].matchAll(/"([a-z]+)"/g)].map((x) => x[1])).toEqual([...FORWARD_TYPES])
    for (const ty of FORWARD_TYPES) {
      expect(TR[TYPE_LABEL_KEYS[ty]], ty).toBeTruthy()
      expect(EN[TYPE_LABEL_KEYS[ty]], ty).toBeTruthy()
    }
    expect(typeLabel('cert', t)).toBe(TR['fwd.type.cert'])
    expect(typeLabel('bogus', tEn)).toBe('Unknown type (bogus)')
  })

  it('her durumun ve dikkat açıklamasının etiketi iki dilde var', () => {
    for (const s of FORWARD_STATES) { expect(TR[`fwd.state.${s}`]).toBeTruthy(); expect(EN[`fwd.state.${s}`]).toBeTruthy() }
    for (const s of ATTENTION_STATES) { expect(TR[`fwd.attention.${s}`]).toBeTruthy(); expect(EN[`fwd.attention.${s}`]).toBeTruthy() }
  })

  it('normalizeForward: alanlar, tür sırası, adres sayısı null = okunamadı, bilinmeyen kapsam / alıcı null', () => {
    const f = normalizeForward(raw())
    expect(f).toMatchObject({ id: 1, version: 3, ownerTeamId: 10, scopeKind: 'TYPES', scopeTypes: ['http', 'ping'],
      recipientKind: 'TEAM', recipientAddressCount: 2, enabled: true, state: 'active', canEdit: true, note: 'Gece vardiyası' })
    expect(normalizeForward(raw({ recipient_address_count: null })).recipientAddressCount).toBeNull()
    expect(normalizeForward(raw({ recipient_address_count: 0 })).recipientAddressCount).toBe(0)
    const bad = normalizeForward({ scope_kind: 'X', recipient_kind: 'Y' })
    expect(bad.scopeKind).toBeNull()
    expect(bad.recipientKind).toBeNull()
    expect(bad.canEdit).toBe(false)
    const mem = normalizeForward(raw({ recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 'u-1', display_name: 'Ayşe', active: false, in_team: true }] }))
    expect(mem.recipientMembers).toEqual([{ userId: 'u-1', displayName: 'Ayşe', active: false, inTeam: true }])
  })

  it('durum: sunucu durumu esas; yoksa aynı öncelikle türetilir (duraklatıldı > süresi doldu > pasif > izleme yok > alıcı yok)', () => {
    expect(normalizeForward(raw({ state: 'no_targets' })).state).toBe('no_targets')
    const d = (over) => normalizeForward(raw({ state: undefined, ...over })).state
    expect(d({ enabled: false, recipient_team_active: false })).toBe('paused')
    expect(deriveState(normalizeForward(raw({ state: 'active', expires_on: '2026-11-04' })), NOW)).toBe('expired')
    expect(deriveState(normalizeForward(raw({ state: 'active', expires_on: '2026-11-05' })), NOW)).toBe('active')   // bugün son gün
    expect(d({ recipient_team_active: false })).toBe('recipient_inactive')
    expect(d({ scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 1, missing: true }] })).toBe('no_targets')
    expect(d({ scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 1, missing: true }, { type: 'ping', id: 2, name: 'x' }] })).toBe('active')
    expect(d({ recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 1, active: false, in_team: true }, { user_id: 2, active: true, in_team: false }] }))
      .toBe('no_recipients')
    expect(d({ recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 1, active: true, in_team: true }] })).toBe('active')
  })

  it('dikkat: sorunlu durumlar + etkin ama adres 0; adres sayısı okunamadıysa (null) dikkat SAYILMAZ', () => {
    expect(needsAttention(normalizeForward(raw({ state: 'recipient_inactive' })))).toBe(true)
    expect(needsAttention(normalizeForward(raw({ recipient_address_count: 0 })))).toBe(true)
    expect(needsAttention(normalizeForward(raw({ recipient_address_count: null })))).toBe(false)
    expect(needsAttention(normalizeForward(raw({ state: 'paused', recipient_address_count: 0 })))).toBe(false)
    const list = [raw(), raw({ id: 2, state: 'paused' }), raw({ id: 3, state: 'expired' }), raw({ id: 4, state: 'no_recipients' }),
      raw({ id: 5, recipient_address_count: 0 })].map(normalizeForward)
    expect(summarize(list)).toEqual({ total: 5, active: 2, paused: 1, expired: 1, attention: 2 })
  })

  it('izleme durumu üç değerli: missing true = silinmiş, null = dizin okunamadı ("bilinmiyor" — silinmiş DENMEZ, iletim "izleme kalmadı" sayılmaz)', () => {
    const f = normalizeForward(raw({ state: undefined, scope_kind: 'MONITORS', scope_types: [], scope_monitors: [
      { type: 'http', id: 1, name: null, missing: null }, { type: 'ping', id: 2, name: 'Çekirdek', missing: null },
      { type: 'port', id: 3, name: null, missing: true }] }))
    expect(f.scopeMonitors.map((m) => m.missing)).toEqual([null, null, true])
    expect(f.state).toBe('active')
    const chips = scopeChips(f, tEn)
    expect(chips.map((c) => c.label)).toEqual([`Monitor status unavailable (${EN['nav.http']})`, 'Çekirdek', `Deleted monitor (${EN['nav.port']})`])
    expect(chips.map((c) => [c.missing, c.unknown])).toEqual([[false, true], [false, true], [true, false]])
    expect(monitorLabel({ type: 'http', id: 9, missing: false }, tEn)).toBe('http:9')
    // Hepsi bilinmiyorsa da "izleme kalmadı" değil
    expect(normalizeForward(raw({ state: undefined, scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 1, missing: null }] })).state).toBe('active')
  })

  it('kalan gün İstanbul gününe göre; yakında bitiyor yalnız etkin satırda', () => {
    expect(todayIstanbul(Date.parse('2026-11-05T22:30:00Z'))).toBe('2026-11-06')   // UTC+3 gece yarısını geçti
    expect(daysLeft('2026-11-05', NOW)).toBe(0)
    expect(daysLeft('2026-11-12', NOW)).toBe(7)
    expect(daysLeft(null, NOW)).toBeNull()
    expect(expiringSoon(normalizeForward(raw({ expires_on: '2026-11-08' })), NOW)).toBe(true)
    expect(expiringSoon(normalizeForward(raw({ expires_on: '2026-12-08' })), NOW)).toBe(false)
    expect(expiringSoon(normalizeForward(raw({ expires_on: '2026-11-08', state: 'paused' })), NOW)).toBe(false)
  })

  it('süzgeç: Türkçe katlama (İ / ı / aksan), sahip takım, kapsam, alıcı türü, durum; arama üye / izleme / tür / notta', () => {
    const list = [
      raw({ id: 1, owner_team_name: 'İnternet Şubesi', note: 'gece' }),
      raw({ id: 2, owner_team_id: 11, owner_team_name: 'Kart', note: null, scope_kind: 'MONITORS', scope_types: [],
        scope_monitors: [{ type: 'http', id: 5, name: 'Ödeme Ağ Geçidi', target: 'https://odeme.example.com' }] }),
      raw({ id: 3, owner_team_id: 11, owner_team_name: 'Kart', note: null, recipient_kind: 'MEMBERS', state: 'paused',
        recipient_members: [{ user_id: 'u-9', display_name: 'Çağrı Işık (Ağ Operasyon / Uzman)', active: true, in_team: true }] }),
    ].map(normalizeForward)
    const ids = (q) => filterForwards(list, q, t).map((f) => f.id)
    expect(ids({ q: 'internet sube' })).toEqual([1])
    expect(ids({ q: 'odeme ag' })).toEqual([2])
    expect(ids({ q: 'cagri isik' })).toEqual([3])
    expect(ids({ q: 'GECE' })).toEqual([1])
    expect(ids({ q: 'ping izleme' })).toEqual([1, 3])   // tür adı (TR "Ping İzleme")
    expect(ids({ team: '11' })).toEqual([2, 3])
    expect(ids({ scope: 'monitors' })).toEqual([2])
    expect(ids({ rcpt: 'members' })).toEqual([3])
    expect(ids({ state: 'paused' })).toEqual([3])
    expect(ids({ state: 'attention' })).toEqual([])
  })

  it('sıralama sahip takım → alıcı takım → kimlik (Türkçe harmanlama); sahip seçenekleri yönetilen boş takımı da içerir', () => {
    const list = [raw({ id: 3, owner_team_name: 'Çek' }), raw({ id: 1, owner_team_name: 'Zirve' }), raw({ id: 2, owner_team_name: 'Cari' })]
      .map(normalizeForward)
    expect(sortForwards(list).map((f) => f.id)).toEqual([2, 3, 1])
    expect(ownerTeamOptions(list.slice(0, 1), [{ id: 99, name: 'Ağ' }]).map(([id]) => id)).toEqual(['99', '10'])
  })

  it('kapsam çipleri / özetleri: ALL tek çip, TYPES tür adları, MONITORS ad ya da "silinmiş", duraklatılmış işaretli', () => {
    expect(scopeChips(normalizeForward(raw({ scope_kind: 'ALL', scope_types: [] })), t).map((c) => c.label)).toEqual([TR['fwd.scope.allShort']])
    expect(scopeSummary(normalizeForward(raw()), t)).toBe(`${TR['nav.http']}, ${TR['nav.ping']}`)
    const mf = normalizeForward(raw({ scope_kind: 'MONITORS', scope_types: [], scope_monitors: [
      { type: 'http', id: 1, name: 'Ana sayfa', active: false }, { type: 'cert', id: 40, name: null, missing: true }] }))
    const chips = scopeChips(mf, t)
    expect(chips[0]).toMatchObject({ label: 'Ana sayfa', paused: true, missing: false })
    expect(chips[1]).toMatchObject({ label: `Silinmiş izleme (${TR['fwd.type.cert']})`, missing: true })
    expect(scopeSummary(mf, tEn)).toBe('2 monitors')
    expect(scopeSummary(normalizeForward(raw({ scope_kind: 'MONITORS', scope_monitors: [{ type: 'http', id: 1, name: 'a' }] })), tEn)).toBe('1 monitor')
    expect(recipientSummary(normalizeForward(raw()), tEn)).toBe(`${LONG_TEAM} — whole team`)
    expect(recipientSummary(normalizeForward(raw({ recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 1 }] })), tEn)).toBe(`${LONG_TEAM} — 1 member`)
    expect(plural(tEn, 'fwd.daysLeft', 3)).toBe('3 days left')
  })

  it('yeni gövde: tüm alanlar; görünmeyen kapsam / alıcı alanı boş; not kırpılır; bitiş / not / erişim yalnız doluysa', () => {
    const f = { ...emptyForm(10), scopeKind: 'TYPES', scopeTypes: ['ping', 'http', 'ping'], recipientTeamId: 20, note: '  gece  ' }
    expect(createPayload(f)).toEqual({ owner_team_id: 10, scope_kind: 'TYPES', scope_types: ['http', 'ping'], scope_monitors: [],
      recipient_kind: 'TEAM', recipient_team_id: 20, recipient_user_ids: [], note: 'gece' })
    const m = { ...emptyForm(10), scopeKind: 'MONITORS', scopeMonitors: [{ type: 'http', id: 5 }, { type: 'cert', id: 40 }],
      recipientKind: 'MEMBERS', recipientTeamId: 20, recipientUserIds: ['3f2a-uuid', 7], expiresOn: '2026-12-31', grantView: true }
    expect(createPayload(m)).toEqual({ owner_team_id: 10, scope_kind: 'MONITORS', scope_types: [],
      scope_monitors: [{ type: 'cert', id: 40 }, { type: 'http', id: 5 }], recipient_kind: 'MEMBERS', recipient_team_id: 20,
      recipient_user_ids: ['3f2a-uuid', 7], expires_on: '2026-12-31', grant_view: true })
  })

  it('düzenleme gövdesi: YALNIZ değişen alanlar (+ sürüm); değişiklik yoksa boş — sıra farkı değişiklik sayılmaz', () => {
    const orig = normalizeForward(raw({ scope_types: ['http', 'ping'] }))
    expect(updatePayload(orig, formOf(orig))).toEqual({})
    expect(updatePayload(orig, { ...formOf(orig), scopeTypes: ['ping', 'http'] })).toEqual({})
    expect(updatePayload(orig, { ...formOf(orig), enabled: false })).toEqual({ enabled: false, version: 3 })
    expect(updatePayload(orig, { ...formOf(orig), note: '' })).toEqual({ note: '', version: 3 })
    expect(updatePayload(orig, { ...formOf(orig), expiresOn: '2027-01-01' })).toEqual({ expires_on: '2027-01-01', version: 3 })
    // Kapsam türü değişince yeni kapsamın alanı da gider
    expect(updatePayload(orig, { ...formOf(orig), scopeKind: 'ALL' })).toEqual({ scope_kind: 'ALL', scope_types: [], version: 3 })
    expect(updatePayload(orig, { ...formOf(orig), scopeKind: 'MONITORS', scopeMonitors: [{ type: 'http', id: 5 }] }))
      .toEqual({ scope_kind: 'MONITORS', scope_types: [], scope_monitors: [{ type: 'http', id: 5 }], version: 3 })
    // Alıcı türü değişince üyeler ve takım birlikte; erişim kutusu yalnız yeni alıcıda
    const toMembers = { ...formOf(orig), recipientKind: 'MEMBERS', recipientUserIds: ['u-1'], grantView: true }
    expect(updatePayload(orig, toMembers)).toEqual({ recipient_kind: 'MEMBERS', recipient_user_ids: ['u-1'], recipient_team_id: 20,
      grant_view: true, version: 3 })
    expect(updatePayload(orig, { ...formOf(orig), note: 'x', grantView: true })).toEqual({ note: 'x', version: 3 })
    // Sürüm bilinmiyorsa eklenmez
    expect(updatePayload(normalizeForward(raw({ version: undefined })), { ...formOf(orig), note: 'y' })).toEqual({ note: 'y' })
  })

  it('yeni alıcı: takım / tür değişti ya da listeye eklenen üye var; çıkarmak yeni alıcı değildir', () => {
    const orig = normalizeForward(raw({ recipient_kind: 'MEMBERS', recipient_members: [{ user_id: 'a' }, { user_id: 'b' }] }))
    expect(hasNewRecipients(orig, formOf(orig))).toBe(false)
    expect(hasNewRecipients(orig, { ...formOf(orig), recipientUserIds: ['a'] })).toBe(false)
    expect(hasNewRecipients(orig, { ...formOf(orig), recipientUserIds: ['a', 'c'] })).toBe(true)
    expect(hasNewRecipients(orig, { ...formOf(orig), recipientTeamId: 21 })).toBe(true)
    expect(hasNewRecipients(null, emptyForm())).toBe(true)
  })

  it('form doğrulaması: zorunlular, aynı takım, sınırlar; düzenlemede değişmeyen kapsam yeniden doğrulanmaz', () => {
    const e = formErrors({ ...emptyForm(null), scopeKind: 'TYPES' }, { isNew: true })
    expect(Object.keys(e).sort()).toEqual(['ownerTeamId', 'recipientTeamId', 'scopeTypes'])
    expect(formErrors({ ...emptyForm(10), recipientTeamId: 10 }).recipientTeamId).toEqual(['fwd.err.sameTeam'])
    const many = Array.from({ length: 201 }, (_, i) => ({ type: 'http', id: i }))
    expect(formErrors({ ...emptyForm(10), recipientTeamId: 20, scopeKind: 'MONITORS', scopeMonitors: many }).scopeMonitors)
      .toEqual(['fwd.err.monitorsMax', DEFAULT_LIMITS.monitors])
    expect(formErrors({ ...emptyForm(10), recipientTeamId: 20, recipientKind: 'MEMBERS' }).recipientUserIds).toEqual(['fwd.err.membersRequired'])
    expect(formErrors({ ...emptyForm(10), recipientTeamId: 20, note: 'x'.repeat(501) }).note).toEqual(['fwd.err.note', 500])
    // Düzenleme: kayıtlı 201 izleme (eski sınır) — kapsam değişmediyse hata yok
    const orig = normalizeForward(raw({ scope_kind: 'MONITORS', scope_monitors: many }))
    expect(formErrors(formOf(orig), { isNew: false, original: orig })).toEqual({})
    expect(formErrors({ ...formOf(orig), scopeMonitors: many.slice(0, 200).concat([{ type: 'ping', id: 900 }, { type: 'ping', id: 901 }]) },
      { isNew: false, original: orig }).scopeMonitors).toBeTruthy()
    for (const keys of Object.values(e)) { expect(TR[keys[0]]).toBeTruthy(); expect(EN[keys[0]]).toBeTruthy() }
  })

  it('sunucu alan adları form alanlarına eşlenir (sözleşmedeki her alan)', () => {
    for (const k of ['owner_team_id', 'scope_kind', 'scope_types', 'scope_monitors', 'recipient_kind', 'recipient_team_id',
      'recipient_user_ids', 'expires_on', 'note']) expect(FIELD_MAP[k], k).toBeTruthy()
  })

  it('derin bağ: "<tür>:<id>", "cert:<alanadı>"; bilinmeyen tür / boş / bozuk → null; listede tür + kimlik ya da alan adı', () => {
    expect(parseNewRef('http:12')).toEqual({ type: 'http', id: 12, domain: null })
    expect(parseNewRef('cert:Ornek.Example.com')).toEqual({ type: 'cert', id: null, domain: 'ornek.example.com' })
    expect(parseNewRef('cert:40')).toEqual({ type: 'cert', id: 40, domain: null })
    expect(parseNewRef('scripted:abc')).toEqual({ type: 'scripted', id: 'abc', domain: null })
    for (const bad of [null, '', 'http', 'http:', ':12', 'bogus:1', 'x'.repeat(301)]) expect(parseNewRef(bad), String(bad)).toBeNull()
    const items = normalizeMonitorOptions([{ type: 'http', id: 12, name: 'Ana' }, { type: 'cert', id: 40, name: 'ornek.example.com' }])
    expect(findMonitorForRef(items, parseNewRef('http:12'))?.name).toBe('Ana')
    expect(findMonitorForRef(items, parseNewRef('cert:ORNEK.example.com'))?.id).toBe(40)
    expect(findMonitorForRef(items, parseNewRef('ping:12'))).toBeNull()
  })

  it('izleme / üye seçenekleri: tür sırası + ad; süzgeç tür + Türkçe katlama; yalnız AKTİF üyeler, ada göre', () => {
    const items = normalizeMonitorOptions([{ type: 'ping', id: 1, name: 'Çekirdek' }, { type: 'http', id: 2, name: 'Zeta', target: 'https://ödeme.example' },
      { type: 'http', id: 3, name: 'Alfa' }, { type: 'http', id: null, name: 'kimliksiz' }])
    expect(items.map(monitorKey)).toEqual(['http:3', 'http:2', 'ping:1'])
    expect(filterMonitorOptions(items, { type: 'ping' }).map(monitorKey)).toEqual(['ping:1'])
    expect(filterMonitorOptions(items, { q: 'odeme' }).map(monitorKey)).toEqual(['http:2'])
    const mem = memberOptions([{ id: 'b', display_name: 'Zeynep', active: true, email: 'z@x' }, { id: 'a', display_name: 'Ahmet', active: true },
      { id: 'c', display_name: 'Pasif Kişi', active: false }])
    expect(mem.map((m) => m.userId)).toEqual(['a', 'b'])
    expect(filterMemberOptions(mem, 'zeyn').map((m) => m.userId)).toEqual(['b'])
  })

  it('yanıt notları: bilgi sayıları, hiç bilgi gitmediyse uyarı, erişim (verildi / zaten vardı / verilemedi — sunucu metni öncelikli)', () => {
    expect(resultNotes({ info: { emails: 2, push: 1 } }, tEn)).toEqual({ ok: ['Recipients were notified — e-mail: 2, push: 1.'], warn: [] })
    expect(resultNotes({ info: { emails: 0, push: 0 } }, tEn).warn).toHaveLength(1)
    expect(resultNotes({ grant: { requested: true, created: 1, existing: 2, failed: 0 } }, tEn).ok)
      .toEqual(['View access given to 1 recipient.', '2 recipients already had view access.'])
    expect(resultNotes({ grant: { requested: true, created: 0, existing: 0, failed: 1, message: 'Tavan dolu.' } }, tEn).warn).toEqual(['Tavan dolu.'])
    expect(resultNotes({ grant: { requested: false } }, tEn)).toEqual({ ok: [], warn: [] })
    expect(payloadOf({ success: true, data: { items: [1] } })).toEqual({ items: [1] })
    expect(normalizeLimits({ per_team: 5, members: 0 })).toEqual({ perTeam: 5, members: 50, monitors: 200, note: 500 })
  })
})
