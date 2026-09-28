import { describe, it, expect } from 'vitest'
import {
  addDays, blacklistEntriesOf, dayDiff, eppListOf, intervalHours, locksOf, nextStepsOf, planBlockOf, rawJsonOf, rawRecordOf,
  relativeDays, timelineOf, todayKey,
} from '../components/domain/detail/domainDetailModel.js'
import { localDayKey } from '../utils/localDay.js'
import { blacklistOf } from '../components/domain/domainCardModel.js'

// Tarihler BUGÜNE göre kurulur (kayan pencereye karşı sabit tarih = zaman bombası); saat dilimi bağımsız.
const TODAY = todayKey()
const day = (n) => addDays(TODAY, n)

describe('domainDetailModel — gün aritmetiği', () => {
  it('addDays / dayDiff yerel gün anahtarında simetrik; bozuk anahtar null', () => {
    expect(dayDiff(day(212), TODAY)).toBe(212)
    expect(dayDiff(day(-3), TODAY)).toBe(-3)
    expect(addDays('2027-03-01', -1)).toBe('2027-02-28')
    expect(addDays('2028-03-01', -1)).toBe('2028-02-29')   // artık yıl
    expect(dayDiff('bozuk', TODAY)).toBeNull()
  })

  it('relativeDays: gün / ay / yıl ölçeği ve "bugün" (Intl, en-GB)', () => {
    expect(relativeDays(0, 'en-GB')).toBe('today')
    expect(relativeDays(-3, 'en-GB')).toBe('3 days ago')
    expect(relativeDays(212, 'en-GB')).toBe('in 7 months')
    expect(relativeDays(800, 'en-GB')).toBe('in 2 years')
    expect(relativeDays(-3, 'tr-TR')).toBe('3 gün önce')
    expect(relativeDays(null, 'en-GB')).toBe('')
  })

  it('intervalHours: saniye → saat (en az 1); yoksa null', () => {
    expect(intervalHours({ interval_seconds: 86400 })).toBe(24)
    expect(intervalHours({ interval_seconds: 3600 })).toBe(1)
    expect(intervalHours({})).toBeNull()
  })
})

describe('domainDetailModel — kilit matrisi (EPP → transfer/güncelleme/silme/yenileme)', () => {
  it('RDAP: transfer sunucunun hükmü; diğerleri client/server kodlarından; ton kuralları', () => {
    const locks = locksOf({
      source: 'RDAP', transfer_lock: 'BOTH',
      status_codes: ['client transfer prohibited', 'serverTransferProhibited', 'clientDeleteProhibited', 'server update prohibited'],
    })
    const by = Object.fromEntries(locks.map((l) => [l.op, l]))
    expect(locks.map((l) => l.op)).toEqual(['transfer', 'update', 'delete', 'renew'])
    expect(by.transfer).toEqual({ op: 'transfer', state: 'BOTH', tone: 'ok' })
    expect(by.update).toEqual({ op: 'update', state: 'SERVER', tone: 'ok' })
    expect(by.delete).toEqual({ op: 'delete', state: 'CLIENT', tone: 'ok' })
    expect(by.renew).toEqual({ op: 'renew', state: 'NONE', tone: 'ok' })     // yenileme serbest = olağan
  })

  it('kilit yoksa: transfer bad (alarm konusu), güncelleme/silme muted; yenileme kilidi warn', () => {
    const by = Object.fromEntries(locksOf({ transfer_lock: 'NONE', status_codes: ['clientRenewProhibited'] }).map((l) => [l.op, l]))
    expect(by.transfer.tone).toBe('bad')
    expect(by.update).toMatchObject({ state: 'NONE', tone: 'muted' })
    expect(by.delete).toMatchObject({ state: 'NONE', tone: 'muted' })
    expect(by.renew).toMatchObject({ state: 'CLIENT', tone: 'warn' })
  })

  it('transfer doğrulanamadıysa (WHOIS) türetilenler de doğrulanamadı — çelişkili tablo yok', () => {
    const locks = locksOf({ source: 'WHOIS', transfer_lock: 'UNKNOWN', status_codes: ['clientDeleteProhibited'] })
    expect(locks.every((l) => l.state === 'UNKNOWN' && l.tone === 'muted')).toBe(true)
    expect(locksOf({}).every((l) => l.state === 'UNKNOWN')).toBe(true)   // hiç veri yok
  })
})

describe('domainDetailModel — EPP listesi', () => {
  it('TÜM kodlar, kritik → uyarı → bilgi; aynı tonda kaynağın sırası; etiket tek biçim', () => {
    const list = eppListOf(['clientTransferProhibited', 'autoRenewPeriod', 'serverHold', 'ok', 'redemption period', 'pendingRenew',
      'clientDeleteProhibited', 'clientUpdateProhibited'])
    expect(list.map((c) => c.key)).toEqual(['serverhold', 'redemptionperiod', 'autorenewperiod', 'pendingrenew',
      'clienttransferprohibited', 'ok', 'clientdeleteprohibited', 'clientupdateprohibited'])
    expect(list.map((c) => c.tone)).toEqual(['bad', 'bad', 'warn', 'warn', 'info', 'info', 'info', 'info'])
    expect(list[0]).toMatchObject({ code: 'serverHold', label: 'server hold' })
    expect(list).toHaveLength(8)   // "+N" yok — detay hepsini gösterir
  })
})

describe('domainDetailModel — zaman çizelgesi', () => {
  const m = {
    registration_date: day(-3000), last_changed: day(-153), expiry_date: day(212), days_remaining: 212, warning_days: 30,
    renewal_planned_at: day(190), renewal_overdue: false,
  }

  it('tarih sırası: kayıt → son güncelleme → bugün → uyarı eşiği → plan → bitiş; durum + gün farkı', () => {
    const items = timelineOf(m)
    expect(items.map((i) => i.kind)).toEqual(['registered', 'updated', 'today', 'warning', 'plan', 'expiry'])
    expect(items.map((i) => i.state)).toEqual(['past', 'past', 'today', 'future', 'future', 'future'])
    const by = Object.fromEntries(items.map((i) => [i.kind, i]))
    expect(by.warning.key).toBe(day(182))            // bitiş − warning_days
    expect(by.warning.diff).toBe(182)
    expect(by.plan).toMatchObject({ tone: 'plan', overdue: false, before: 22 })
    expect(by.expiry).toMatchObject({ key: day(212), diff: 212, tone: 'ok' })
  })

  it('bitiş/eşik "kaç gün" bilgisi SUNUCUNUN kalan gününden (kahraman sayıyla aynı)', () => {
    const by = Object.fromEntries(timelineOf({ ...m, days_remaining: 211 }).map((i) => [i.kind, i]))
    expect(by.expiry.diff).toBe(211)
    expect(by.warning.diff).toBe(181)
  })

  it('RDAP zaman damgası YEREL güne yerleşir (slice ile UTC günü alınmaz)', () => {
    const stamp = '2027-03-15T23:59:59Z'
    const exp = timelineOf({ expiry_date: stamp }).find((i) => i.kind === 'expiry')
    expect(exp.key).toBe(localDayKey(stamp))
    expect(exp.iso).toBe(stamp)
  })

  it('gecikmiş plan geçmişte ve kırmızı; dolmuş kayıtta uyarı eşiği çizilmez; tarihsiz kayıtta yalnız "bugün"', () => {
    const overdue = timelineOf({ ...m, expiry_date: day(-2), days_remaining: -2, renewal_planned_at: day(-10), renewal_overdue: true })
    const by = Object.fromEntries(overdue.map((i) => [i.kind, i]))
    expect(by.plan).toMatchObject({ state: 'past', tone: 'bad', overdue: true })
    expect(by.warning).toBeUndefined()
    expect(by.expiry).toMatchObject({ state: 'past', tone: 'expired' })
    expect(timelineOf({}).map((i) => i.kind)).toEqual(['today'])
  })

  it('son güncelleme kayıt günüyle aynıysa ikinci kez yazılmaz', () => {
    expect(timelineOf({ registration_date: day(-10), last_changed: day(-10) }).map((i) => i.kind)).toEqual(['registered', 'today'])
  })
})

describe('domainDetailModel — kara liste, plan, sonraki adımlar, ham veri', () => {
  it('kara liste kanıtı `;` ayraçlı `liste=hedef`; bilinen listelere sabit kaldırma sayfası', () => {
    expect(blacklistEntriesOf('zen.spamhaus.org=192.0.2.10 → 127.0.0.2; bl.spamcop.net=192.0.2.10 → 127.0.0.2; dnsbl.example.org=example.com'))
      .toEqual([
        { list: 'zen.spamhaus.org', target: '192.0.2.10 → 127.0.0.2', delist: 'https://check.spamhaus.org/' },
        { list: 'bl.spamcop.net', target: '192.0.2.10 → 127.0.0.2', delist: 'https://www.spamcop.net/bl.shtml' },
        { list: 'dnsbl.example.org', target: 'example.com', delist: null },
      ])
    expect(blacklistEntriesOf(null)).toEqual([])
  })

  it('kart modeli blacklistOf da `;` ayraçlı kanıtı liste liste sayar (başlık çipi "Listede (3 liste)")', () => {
    const bl = blacklistOf({ blacklist_status: 'LISTED', blacklist_detail: 'zen.spamhaus.org=192.0.2.1 → 127.0.0.2; bl.spamcop.net=192.0.2.1 → 127.0.0.2; dnsbl.example.org=example.com' })
    expect(bl.lists).toEqual(['zen.spamhaus.org=192.0.2.1 → 127.0.0.2', 'bl.spamcop.net=192.0.2.1 → 127.0.0.2', 'dnsbl.example.org=example.com'])
  })

  it('plan bloğu: plan (bitişten kaç gün önce) · kısayol (eşik içinde) · sakin teklif · yetkisiz null', () => {
    const base = { expiry_date: day(20), days_remaining: 20, warning_days: 30 }
    expect(planBlockOf({ ...base, renewal_planned_at: day(5), renewal_planned_by: 'Kişi A' }, true))
      .toMatchObject({ kind: 'plan', state: 'planned', by: 'Kişi A', before: 15 })
    expect(planBlockOf(base, true)).toEqual({ kind: 'cta' })
    expect(planBlockOf({ ...base, days_remaining: 200, expiry_date: day(200) }, true)).toEqual({ kind: 'offer' })
    expect(planBlockOf(base, false)).toBeNull()
    expect(planBlockOf({ days_remaining: null }, true)).toBeNull()   // bitiş bilinmiyor: neyin planlandığı belli değil
  })

  it('sonraki adımlar: hiç kontrol / hata / tarih yok; .tr notu; Tanıla yetkiye göre', () => {
    expect(nextStepsOf({ domain: 'example.com' })).toEqual(['never'])
    expect(nextStepsOf({ domain: 'example.com.tr', checked_at: 'x', error: 'timeout' }, { canDiagnose: true }))
      .toEqual(['spelling', 'retry', 'tr', 'diagnose'])
    expect(nextStepsOf({ domain: 'example.com', checked_at: 'x' })).toEqual(['nodata', 'diagnoseAdmin'])
  })

  it('ham kayıt yalnız kayıt alanlarını sabit sırayla taşır (takım/bildirim ayarları dışarıda)', () => {
    const reg = { team_id: 3, notification_group_id: 9, domain: 'example.com', registrar: 'Example Registrar Ltd.', source: 'RDAP', status_codes: ['ok'] }
    expect(Object.keys(rawRecordOf(reg))).toEqual(['domain', 'source', 'registrar', 'status_codes'])
    const json = rawJsonOf(reg)
    expect(JSON.parse(json)).toEqual({ domain: 'example.com', source: 'RDAP', registrar: 'Example Registrar Ltd.', status_codes: ['ok'] })
    expect(json).not.toMatch(/team_id|notification_group_id/)
  })
})
