import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  MAX_INSTRUCTIONS, NOC_TYPES, applyFilters, canEditCallList, canEditItem, configBody, coverageTone, isValidEmail, moveItem, normalizeConfig,
  openTargetOf, parseEmailInput, sortGroups, sortItems, splitEmailText, statusOf, summarize, summarizeSkipped, teamOptions, testOutcome,
  uncoveredActiveCount, unwrap, validateGroup, withNotify,
} from '../components/noc/nocModel.js'

/** 7/24 (NOC) saf modeli (2026-09-27): e-posta listesi, kapsam özeti/süzgeçleri, iyimser güncelleme, yetki. */
describe('nocModel — e-posta listesi', () => {
  it('ayırıcılar: virgül, noktalı virgül, satır sonu, boşluk; Outlook "Ad <adres>" biçimi', () => {
    expect(splitEmailText('a@example.com, b@example.com;c@example.com\nd@example.com e@example.com'))
      .toEqual(['a@example.com', 'b@example.com', 'c@example.com', 'd@example.com', 'e@example.com'])
    expect(splitEmailText('Kişi A <kisi-a@example.com>; "Kişi B" <kisi-b@example.com>'))
      .toEqual(['kisi-a@example.com', 'kisi-b@example.com'])
  })

  it('Outlook: görünen ad VİRGÜL içerse de ("Soyad, Ad" <adres>) ad parçası aday olmaz; karışık yapıştırmada sıra korunur', () => {
    const pasted = ['"Soyad A, Kişi A" <kisi-a@example.com>; Soyad B, Kişi B <kisi-b@example.com>, noc@example.com;',
      '"Kişi C" <kisi-c@example.com>'].join(String.fromCharCode(10))
    expect(splitEmailText(pasted)).toEqual(['kisi-a@example.com', 'kisi-b@example.com', 'noc@example.com', 'kisi-c@example.com'])
    const r = parseEmailInput(pasted, [])
    expect(r.invalid).toEqual([])   // "Soyad A" / "Kişi" gibi geçersiz çip YOK → Kaydet kilitlenmez
    expect(r.added).toHaveLength(4)
    // Köşeli adres yoksa eski davranış: geçersiz aday kullanıcıya gösterilir
    expect(splitEmailText('bozuk, noc@example.com')).toEqual(['bozuk', 'noc@example.com'])
  })

  it('doğrulama: geçerli / geçersiz örnekler', () => {
    for (const ok of ['noc@example.com', 'izleme.ekibi+gece@alt.example.co.uk', "o'brien@example.org"]) expect(isValidEmail(ok), ok).toBe(true)
    for (const bad of ['noc@', '@example.com', 'noc@example', 'no c@example.com', 'a..b@example.com', '.a@example.com', 'a@-x.com', 'a@example.c']) {
      expect(isValidEmail(bad), bad).toBe(false)
    }
  })

  it('parseEmailInput: küçük harfe çevirir, mevcut + girdi içi yinelenenleri sayar, geçersizleri ayırır', () => {
    const r = parseEmailInput('NOC@Example.com, izleme@example.com; noc@example.com, bozuk@, izleme@example.com, bozuk@',
      ['izleme@example.com'])
    expect(r.added).toEqual(['noc@example.com'])
    expect(r.duplicates).toBe(3)            // izleme (mevcut) ×2 + noc (girdide ikinci kez)
    expect(r.invalid).toEqual(['bozuk@'])   // geçersizler de tekil
  })

  it('validateGroup: ad zorunlu/uzunluk, bekleyen geçersiz adres, boş liste, 50 tavanı', () => {
    expect(validateGroup({ name: '', emails: ['a@example.com'], invalid: [] })).toEqual({ name: 'noc.errName' })
    // Sunucu sınırı ad ≤100 (CONTRACT "Backend sapmaları")
    expect(validateGroup({ name: 'x'.repeat(100), emails: ['a@example.com'], invalid: [] }).name).toBeUndefined()
    expect(validateGroup({ name: 'x'.repeat(101), emails: ['a@example.com'], invalid: [] }).name).toBe('noc.errNameLong')
    // Ad harf duyarsız tekil (sunucu kuralı) — TR yerel: "NOC İZLEME" = "noc izleme"
    expect(validateGroup({ name: 'NOC İZLEME', emails: ['a@example.com'], invalid: [] }, ['noc izleme']).name).toBe('noc.errNameTaken')
    expect(validateGroup({ name: 'NOC Gece', emails: ['a@example.com'], invalid: [] }, ['noc izleme']).name).toBeUndefined()
    expect(validateGroup({ name: 'A', emails: ['a@example.com'], invalid: ['x@'] }).emails).toBe('noc.errInvalidPending')
    expect(validateGroup({ name: 'A', emails: [], invalid: [] }).emails).toBe('noc.errNoEmail')
    const many = Array.from({ length: 51 }, (_, i) => `u${i}@example.com`)
    expect(validateGroup({ name: 'A', emails: many, invalid: [] }).emails).toBe('noc.errTooMany')
    expect(validateGroup({ name: 'A', emails: many.slice(0, 50), invalid: [] })).toEqual({})
  })

  it('sortGroups: varsayılan → aktif → ad; normalizeConfig eksik türü AÇIK sayar; configBody camelCase', () => {
    const g = sortGroups([{ name: 'B', active: true }, { name: 'C', active: false, is_default: true }, { name: 'A', active: false }])
    expect(g.map((x) => x.name)).toEqual(['C', 'B', 'A'])
    const c = normalizeConfig({ enabled_types: { PING: false }, min_level: 'BOGUS', call_instructions: null })
    expect(c.enabled_types.PING).toBe(false)
    expect(NOC_TYPES.filter((k) => k !== 'PING').every((k) => c.enabled_types[k])).toBe(true)
    expect(c.min_level).toBe('CRITICAL')
    expect(c.send_resolve).toBe(true)
    expect(configBody({ ...c, call_instructions: 'x'.repeat(1200) })).toMatchObject({
      minLevel: 'CRITICAL', sendResolve: true, enabledTypes: expect.objectContaining({ PING: false }),
    })
    // Talimat KESİLMEZ (sessiz kırpma kayıtlı metnin sonunu silerdi) — tavanı sunucu 400'le söyler
    expect(configBody({ ...c, call_instructions: 'x'.repeat(2500) }).callInstructions).toHaveLength(2500)
    expect(configBody({ ...c, call_instructions: 'x'.repeat(1500) }).callInstructions).toHaveLength(1500)
  })

  it('noc-limits-sync: arama talimatı tavanı sunucuyla AYNI (NocConfigService.MAX_INSTRUCTIONS)', () => {
    const java = fs.readFileSync(path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/noc/NocConfigService.java'), 'utf8')
    const m = /MAX_INSTRUCTIONS\s*=\s*([0-9]+)/.exec(java)
    expect(m, 'NocConfigService.MAX_INSTRUCTIONS okunamadı').not.toBeNull()
    expect(MAX_INSTRUCTIONS).toBe(Number(m[1]))
  })

  it('testOutcome: sent sayı / dizi / true', () => {
    expect(testOutcome({ sent: 3, failed: [] }, 3)).toEqual({ sent: 3, failed: [], disabled: false })
    expect(testOutcome({ sent: ['a', 'b'], failed: ['c'] }, 3)).toEqual({ sent: 2, failed: ['c'], disabled: false })
    expect(testOutcome({ sent: true, failed: ['c'] }, 3)).toEqual({ sent: 2, failed: ['c'], disabled: false })
    // Sunucunun gerçek biçimi: [{ email, error }]
    expect(testOutcome({ sent: 1, failed: [{ email: 'b@example.com', error: '550' }] }, 2)).toMatchObject({ sent: 1, failed: ['b@example.com'], disabled: false })
    // Genel e-posta kapalı: sunucu her adresi SKIPPED_DISABLED ile başarısız sayar
    expect(testOutcome({ sent: 0, failed: [{ email: 'a@example.com', error: 'SKIPPED_DISABLED' }, { email: 'b@example.com', error: 'SKIPPED_DISABLED' }] }).disabled).toBe(true)
  })

  it('summarizeSkipped: UNCHANGED geri alınmaz ve başarısızlık sayılmaz; bilinmeyen neden INVALID', () => {
    const s = summarizeSkipped([
      { type: 'PING', id: 1, reason: 'UNCHANGED' }, { type: 'SSL', id: 5, reason: 'FORBIDDEN' },
      { type: 'DNS', id: 3, reason: 'NOT_FOUND' }, { type: 'X', id: 9, reason: 'WAT' },
    ])
    expect([...s.rollback].sort()).toEqual(['DNS:3', 'SSL:5', 'X:9'])
    expect(s.counts).toEqual({ UNCHANGED: 1, FORBIDDEN: 1, NOT_FOUND: 1, INVALID: 1 })
    expect(s.failed).toBe(3)
  })

  it('unwrap: {success,data} zarfı, zarfsız veri, hata, 401 null', () => {
    expect(unwrap({ success: true, data: [1] })).toEqual({ ok: true, data: [1], error: null })
    expect(unwrap({ summary: {}, items: [] }).data).toEqual({ summary: {}, items: [] })
    expect(unwrap({ success: false, error: 'yasak' })).toEqual({ ok: false, data: null, error: 'yasak' })
    expect(unwrap(null).ok).toBe(false)
  })
})

const ITEMS = [
  { type: 'PING', id: 1, name: 'ping-a', target: 'a.example.com', team_id: 1, team_name: 'Takım A', active: true, noc_notify: false, covered: false, reason: 'MONITOR_OFF' },
  { type: 'HTTP', id: 2, name: 'web', target: 'https://www.example.com', team_id: 2, team_name: 'Takım B', active: true, noc_notify: true, covered: true, reason: null, group_names: ['NOC'] },
  { type: 'DNS', id: 3, name: 'dns-a', target: 'example.com', team_id: 1, team_name: 'Takım A', active: false, noc_notify: true, covered: false, reason: 'PAUSED' },
  { type: 'PORT', id: 4, name: 'db', target: 'db.example.com:5432', team_id: null, team_name: null, active: true, noc_notify: true, covered: false, reason: 'TYPE_DISABLED' },
]

describe('nocModel — kapsam', () => {
  it('statusOf + summarize: kutucuklar satırlardan; tür oranı duraklatılmışsız', () => {
    expect(ITEMS.map(statusOf)).toEqual(['not_covered', 'covered', 'paused', 'not_covered'])
    const s = summarize(ITEMS)
    expect(s).toMatchObject({ total: 4, covered: 1, not_covered: 2, paused: 1 })
    expect(s.by_type.DNS).toEqual({ total: 0, covered: 0, paused: 1 })
    expect(s.by_type.HTTP).toEqual({ total: 1, covered: 1, paused: 0 })
    expect(uncoveredActiveCount({ items: ITEMS })).toBe(2)
    expect(uncoveredActiveCount({ summary: { not_covered: 7 } })).toBe(7)
    expect(coverageTone(1, 1)).toBe('ok')
    expect(coverageTone(1, 2)).toBe('warn')
    expect(coverageTone(0, 2)).toBe('crit')
  })

  it('applyFilters: arama (ad/hedef/takım/grup), takım (takımsız dâhil), tür, neden, durum', () => {
    expect(applyFilters(ITEMS, { q: 'noc' }).map((x) => x.id)).toEqual([2])            // grup adı
    expect(applyFilters(ITEMS, { q: 'TAKIM B' }).map((x) => x.id)).toEqual([2])
    expect(applyFilters(ITEMS, { teams: ['__none__'] }).map((x) => x.id)).toEqual([4])
    expect(applyFilters(ITEMS, { types: ['PING', 'DNS'] }).map((x) => x.id)).toEqual([1, 3])
    expect(applyFilters(ITEMS, { reasons: ['TYPE_DISABLED'] }).map((x) => x.id)).toEqual([4])
    expect(applyFilters(ITEMS, { status: 'paused' }).map((x) => x.id)).toEqual([3])
  })

  it('sortItems: kapsanmayan (kullanıcının düzeltebileceği neden) önce, kapsanan en sonda; teamOptions takımsız sonda', () => {
    expect(sortItems(ITEMS).map((x) => x.id)).toEqual([1, 4, 3, 2])
    const opts = teamOptions(ITEMS, 'Takımsız')
    expect(opts.map((o) => o.value)).toEqual(['1', '2', '__none__'])
    expect(opts.find((o) => o.value === '1').count).toBe(2)
  })

  it('withNotify: açma MONITOR_OFF engelini kaldırır; tür kapalı / grup yoksa nedene döner; kapatma kapsamdan çıkarır', () => {
    expect(withNotify(ITEMS[0], true)).toMatchObject({ noc_notify: true, covered: true, reason: null })
    expect(withNotify(ITEMS[0], true, { disabledTypes: ['PING'] })).toMatchObject({ covered: false, reason: 'TYPE_DISABLED' })
    expect(withNotify(ITEMS[0], true, { activeGroups: 0 })).toMatchObject({ covered: false, reason: 'NO_ACTIVE_GROUP' })
    expect(withNotify(ITEMS[1], false)).toMatchObject({ noc_notify: false, covered: false, reason: 'MONITOR_OFF' })
  })

  it('canEditItem: can_edit kazanır; AUDIT yazamaz; USER yalnız kendi takımı; ADMIN/global her şey', () => {
    expect(canEditItem({ ...ITEMS[0], can_edit: false }, { globalAdmin: true })).toBe(false)
    expect(canEditItem(ITEMS[0], { systemRole: 'AUDIT', globalAdmin: false, myTeamIds: [1] })).toBe(false)   // üye olsa da
    expect(canEditItem(ITEMS[0], { systemRole: 'USER', myTeamIds: [1] })).toBe(true)
    expect(canEditItem(ITEMS[1], { systemRole: 'USER', myTeamIds: [1] })).toBe(false)
    expect(canEditItem(ITEMS[3], { systemRole: 'USER', myTeamIds: [1] })).toBe(false)   // takımsız
    expect(canEditItem(ITEMS[1], { systemRole: 'ADMIN' })).toBe(true)
  })

  it('openTargetOf: SSL → Pano araması, Sentetik → yalnız sekme, diğerleri ?monitor=', () => {
    expect(openTargetOf({ type: 'SSL', id: 9, target: 'www.example.com' })).toEqual({ tab: 'dashboard', params: { domain: 'www.example.com' } })
    // 443 dışı port: hedef `host:8443` Pano aramasında eşleşmez → alan adı (`name`) aranır
    expect(openTargetOf({ type: 'SSL', id: 9, name: 'www.example.com', target: 'www.example.com:8443' }))
      .toEqual({ tab: 'dashboard', params: { domain: 'www.example.com' } })
    expect(openTargetOf({ type: 'SCRIPTED', id: 9 })).toEqual({ tab: 'scripted', params: undefined })
    expect(openTargetOf({ type: 'PORT', id: 4 })).toEqual({ tab: 'port', params: { monitor: 4 } })
  })
})

describe('nocModel — arama listesi', () => {
  it('moveItem sınırda değişmez; canEditCallList: müdür (leader), TEAM_ADMIN üyesi, ADMIN', () => {
    expect(moveItem(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c'])
    expect(moveItem(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
    expect(moveItem(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(canEditCallList(5, { systemRole: 'USER', userId: 7, leaderId: 7 })).toBe(true)
    expect(canEditCallList(5, { systemRole: 'USER', userId: 7, leaderId: 8, myTeamIds: [5] })).toBe(false)
    expect(canEditCallList(5, { systemRole: 'TEAM_ADMIN', myTeamIds: [5] })).toBe(true)
    expect(canEditCallList(5, { systemRole: 'TEAM_ADMIN', myTeamIds: [6] })).toBe(false)
    // YALNIZ global yönetici her takımı düzenler; kapsamlı müdür (rol ADMIN) sunucuda yönetim kapsamıyla sınırlı
    expect(canEditCallList(5, { systemRole: 'ADMIN', globalAdmin: true })).toBe(true)
    expect(canEditCallList(5, { systemRole: 'ADMIN' })).toBe(false)
    expect(canEditCallList(5, { systemRole: 'ADMIN', myTeamIds: [5] })).toBe(true)
    expect(canEditCallList(null, { globalAdmin: true })).toBe(false)
    // Takıma elle atanmış müdür (Team.managerId) de düzenler
    expect(canEditCallList(5, { systemRole: 'USER', userId: 7, leaderId: 8, managerId: 7 })).toBe(true)
  })
})
