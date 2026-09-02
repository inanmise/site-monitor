import { describe, it, expect } from 'vitest'
import {
  eventClass, eventLabel, parseDetail, fmtValue, actionSentence,
} from '../components/admin/audit/auditFormat.js'
import { TR } from '../i18n/index.jsx'

/** Gerçek TR sözlüğüyle çeviri — etiket kuralının sözlükle GERÇEKTEN buluştuğunu doğrular. */
const tr = (k) => (TR[k] ?? k)
/** Çevirisi olmayan dil taklidi — kural yedeğinin devreye girdiği hâl. */
const raw = (k) => k

describe('auditFormat.eventClass', () => {
  it('ENGELLENEN erişim kendi sınıfında — nötr olay gibi görünmez', () => {
    // Eski eşleme yalnız soneke bakıyordu: ACCESS_DENIED "ev-other"a düşüyordu.
    expect(eventClass('ACCESS_DENIED')).toBe('ev-denied')
    expect(eventClass('AUTH_REQUIRED')).toBe('ev-denied')
    expect(eventClass('CHANGE_LOG_DENIED')).toBe('ev-denied')
  })

  it('ad-hoc test, dışa aktarım ve sistem olayları ayrı sınıflarda', () => {
    expect(eventClass('MONITOR_TEST')).toBe('ev-test')
    expect(eventClass('SMTP_TEST')).toBe('ev-test')
    expect(eventClass('AUDIT_EXPORT')).toBe('ev-export')
    expect(eventClass('SYSTEM_STARTUP')).toBe('ev-system')
    expect(eventClass('RETENTION_RUN_MANUAL')).toBe('ev-system')
  })

  it('klasik yaşam döngüsü sınıfları korunur', () => {
    expect(eventClass('LOGIN')).toBe('ev-login')
    expect(eventClass('LOGIN_FAILED')).toBe('ev-failed')
    expect(eventClass('LOGOUT')).toBe('ev-logout')
    expect(eventClass('USER_DELETE')).toBe('ev-delete')
    expect(eventClass('USER_CREATE')).toBe('ev-create')
    expect(eventClass('USER_UPDATE')).toBe('ev-edit')
    expect(eventClass('SOMETHING_ELSE')).toBe('ev-other')
    expect(eventClass(null)).toBe('ev-other')
  })

  it('silme SONEKİ, önekten bağımsız yakalanır (DOMAIN_BULK_PURGE)', () => {
    expect(eventClass('DOMAIN_BULK_PURGE')).toBe('ev-delete')
  })
})

describe('auditFormat.eventLabel', () => {
  it('özel türler sözlükten gelir', () => {
    expect(eventLabel('LOGIN_FAILED', tr)).toBe('Giriş başarısız')
    expect(eventLabel('SQL_EXECUTE', tr)).toBe('SQL çalıştırıldı')
    expect(eventLabel('MAINTENANCE_QUICK', tr)).toBe('Hızlı bakım başlatıldı')
  })

  it('kural: NESNE + EYLEM (162 tür için sözlük tutmaya gerek yok)', () => {
    expect(eventLabel('USER_DELETE', tr)).toBe('Kullanıcı silindi')
    expect(eventLabel('TEAM_UPDATE', tr)).toBe('Takım güncellendi')
    expect(eventLabel('MAINTENANCE_PAUSE', tr)).toBe('Bakım penceresi duraklatıldı')
    expect(eventLabel('GUIDE_LINK_CREATE', tr)).toBe('Kılavuz bağlantısı oluşturuldu')
  })

  it('EN UZUN önek kazanır (USER_PUSH_SETTINGS ≠ Kullanıcı)', () => {
    expect(eventLabel('USER_PUSH_SETTINGS', tr)).toBe('Kişi bildirimi kaydedildi')
    expect(eventLabel('CERT_INVENTORY_REPORT_RUN', tr)).toBe('Sertifika envanter raporu çalıştırıldı')
  })

  it('çeviri yoksa YARIM çeviri basmaz, okunur yedeğe düşer', () => {
    // "audit.noun.user silindi" ham koddan daha kötü okunur; kural ancak İKİ parça da
    // çevrildiğinde kullanılır.
    expect(eventLabel('USER_DELETE', raw)).toBe('User Delete')
    expect(eventLabel('BRAND_NEW_THING', tr)).toBe('Brand New Thing')
  })

  it('boş tür tire döner', () => {
    expect(eventLabel(null, tr)).toBe('—')
    expect(eventLabel('', tr)).toBe('—')
  })

  it('KALİTE ÇITASI: hiçbir tür ekranda ham i18n anahtarı göstermez', () => {
    // Kartlarda yaşanan hata buydu: anahtar bulunamayınca `useT` anahtarın KENDİSİNİ
    // döndürüyor ve ekranda "audit.total_24h" yazıyordu.
    const types = [
      'LOGIN', 'LOGIN_FAILED', 'LOGOUT', 'ACCOUNT_LOCKED', 'ACCESS_DENIED', 'AUTH_REQUIRED',
      'USER_CREATE', 'USER_UPDATE', 'USER_DELETE', 'USER_UNLOCK', 'USER_ROLE_UNLOCK',
      'TEAM_CREATE', 'TEAM_UPDATE', 'TEAM_DELETE', 'PERMISSION_UPDATE', 'PERMISSION_RESET',
      'MONITOR_CREATE', 'MONITOR_UPDATE', 'MONITOR_DELETE', 'MONITOR_TRIGGER', 'MONITOR_TEST',
      'MAINTENANCE_CREATE', 'MAINTENANCE_UPDATE', 'MAINTENANCE_DELETE', 'MAINTENANCE_PAUSE',
      'MAINTENANCE_RESUME', 'MAINTENANCE_QUICK', 'SQL_EXECUTE', 'AUDIT_EXPORT',
      'SMTP_SETTINGS_SAVE', 'LDAP_TEST', 'STORM_SETTINGS_SAVE', 'GENERAL_SETTINGS_SAVE',
      'BRANDING_SAVE', 'USER_PUSH_SETTINGS', 'USER_PUSH_SCOPES', 'RETENTION_POLICY_CHANGE',
      'WEEKLY_REPORT_APPROVE', 'WEEKLY_REPORT_REJECT', 'GUIDE_LINK_DELETE', 'TEMPLATE_PROMOTE',
      'NOTIFICATION_GROUP_DEFAULT', 'INCIDENT_COMMENT_ADD', 'CERT_NOTE_ADD', 'CA_PINNED',
      'SYSTEM_STARTUP', 'SCHEDULER_LOCK_RELEASE', 'SCRIPTED_DRAFT_DELETE', 'ALERT_RENOTIFY',
    ]
    const leaking = types.filter(t => /audit\.(noun|verbSuffix|ev)\./.test(eventLabel(t, tr)))
    expect(leaking, `bu türlerde ham anahtar sızıyor: ${leaking.join(', ')}`).toEqual([])
  })
})

describe('auditFormat.parseDetail', () => {
  it('changes → diff, detail → JSON nesnesi', () => {
    const r = parseDetail({ changes: '{"a":{"from":1,"to":2}}', detail: '{"host":"x"}' })
    expect(r.changes).toEqual([['a', { from: 1, to: 2 }]])
    expect(r.detailObj).toEqual({ host: 'x' })
    expect(r.detailText).toBeNull()
  })

  it('DÜZ METİN detail metin olarak döner — sessizce düşmez', () => {
    // Regresyon: eski kod parseDiff(changes || detail) diyordu; düz metinde JSON.parse
    // patlayıp null dönüyor ve ayrıntı ekranda HİÇ görünmüyordu.
    const r = parseDetail({ changes: null, detail: 'test → ops@example.com' })
    expect(r.detailText).toBe('test → ops@example.com')
    expect(r.changes).toBeNull()
    expect(r.detailObj).toBeNull()
  })

  it('detail ASLA diff sanılmaz (changes yokken bile)', () => {
    const r = parseDetail({ changes: null, detail: '{"a":{"from":1,"to":2}}' })
    expect(r.changes, 'detail diff olarak yorumlanmamalı').toBeNull()
    expect(r.detailObj).toEqual({ a: { from: 1, to: 2 } })
  })

  it('boş/bozuk girdiler güvenli', () => {
    expect(parseDetail({})).toEqual({ changes: null, detailObj: null, detailText: null })
    expect(parseDetail(null)).toEqual({ changes: null, detailObj: null, detailText: null })
    expect(parseDetail({ changes: '{bozuk', detail: '   ' }).detailText).toBeNull()
    expect(parseDetail({ detail: '[]' }).detailObj, 'boş dizi ayrıntı sayılmaz').toBeNull()
  })
})

describe('auditFormat.fmtValue', () => {
  it('nesne ve dizi JSON olarak yazılır ([object Object] değil)', () => {
    expect(fmtValue({ a: 1 })).toBe('{"a":1}')
    expect(fmtValue(['a', 'b'])).toBe('["a","b"]')
  })

  it('boş değerler tire', () => {
    expect(fmtValue(null)).toBe('—')
    expect(fmtValue(undefined)).toBe('—')
    expect(fmtValue('')).toBe('—')
    expect(fmtValue(0), 'sıfır boş DEĞİLDİR').toBe('0')
    expect(fmtValue(false)).toBe('false')
  })
})

describe('auditFormat.actionSentence', () => {
  it('aktör · fiil · kaynak — ilk iki değişiklikle', () => {
    const row = { actor: 'alice', event_type: 'MONITOR_UPDATE', resource_type: 'PORT_MONITOR', resource_id: '7' }
    const diff = [['warningDays', { from: 30, to: 15 }], ['notifyEmail', { from: true, to: false }]]
    const s = actionSentence(row, tr, diff)
    expect(s).toContain('alice')
    expect(s).toContain('PORT_MONITOR:7')
    expect(s).toContain('warningDays: 30 → 15')
  })

  it('ikiden fazla değişiklikte kalanı sayar', () => {
    const diff = [['a', {}], ['b', {}], ['c', {}], ['d', {}]]
    expect(actionSentence({ actor: 'x', event_type: 'USER_UPDATE' }, tr, diff)).toContain('(+2)')
  })

  it('diff yoksa hata sebebine düşer; aktörsüz satırda sistem aktörü yazar', () => {
    const s = actionSentence({ event_type: 'LOGIN_FAILED', failure_reason: 'BAD_PASSWORD: hatalı parola' }, tr, null)
    expect(s).toContain('BAD_PASSWORD')
    expect(s).toContain(tr('audit.systemActor'))
  })
})
