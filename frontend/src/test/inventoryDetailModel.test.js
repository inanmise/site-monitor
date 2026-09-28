import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  splitLinks, safeHttpUrl, siteUrl, splitEmail, missingFields, splitFlags, inventoryDeepLinkParams, daysFromNow, hasRenewalInfo,
  intervalLabelKey, tlsModeLabelKey, contactsTone, notificationGroupView,
} from '../components/inventory/inventoryDetailModel.js'

/**
 * Envanter detay modeli (2026-09-28 yeniden tasarım) — bağlantı güvenliği ve eksik alan çıkarımı saf fonksiyonlarda;
 * burada tek başına pinlenir (bileşen testleri çizimi, bunlar kuralı sınar).
 */
describe('safeHttpUrl — yalnız http(s)', () => {
  it('http/https kabul, diğer şemalar (javascript:, data:, vbscript:, ftp:) null', () => {
    expect(safeHttpUrl('https://wiki.example.com/a?b=1')).toBe('https://wiki.example.com/a?b=1')
    expect(safeHttpUrl('http://intra.example.com')).toBe('http://intra.example.com/')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('  JaVaScRiPt:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,<b>x</b>')).toBeNull()
    expect(safeHttpUrl('vbscript:msgbox(1)')).toBeNull()
    expect(safeHttpUrl('ftp://files.example.com')).toBeNull()
    expect(safeHttpUrl('/relative/path')).toBeNull()
    expect(safeHttpUrl('')).toBeNull()
  })
})

describe('splitLinks — serbest metinde güvenli bağlantılar', () => {
  it('bağlantısız metin TEK metin parçası döner (metin düğümü bölünmez)', () => {
    expect(splitLinks('Satın Alma Ekibi')).toEqual([{ type: 'text', value: 'Satın Alma Ekibi' }])
  })

  it('http(s) adresi bağlantı olur; cümle sonu noktalama metinde kalır', () => {
    expect(splitLinks('Runbook: https://wiki.example.com/runbook. Bitti')).toEqual([
      { type: 'text', value: 'Runbook: ' },
      { type: 'url', value: 'https://wiki.example.com/runbook', href: 'https://wiki.example.com/runbook' },
      { type: 'text', value: '. Bitti' },
    ])
  })

  it('javascript: ve şemasız www. adresi ASLA bağlantı olmaz', () => {
    const parts = splitLinks('bak javascript:alert(1) ve www.example.com')
    expect(parts.every((p) => p.type === 'text')).toBe(true)
  })

  it('birden çok adres sırayla ayrılır', () => {
    const urls = splitLinks('a http://a.example.com b (https://b.example.com)').filter((p) => p.type === 'url').map((p) => p.href)
    expect(urls).toEqual(['http://a.example.com/', 'https://b.example.com/'])
  })
})

describe('siteUrl — kaydın sitesi', () => {
  it('443 portsuz, diğer port adreste; joker alan adı için bağlantı YOK', () => {
    expect(siteUrl({ domain: 'a.example.com', port: 443 })).toBe('https://a.example.com/')
    expect(siteUrl({ domain: 'a.example.com', port: 8443 })).toBe('https://a.example.com:8443/')
    expect(siteUrl({ domain: '*.example.com', port: 443 })).toBeNull()
    expect(siteUrl({ domain: 'kötü alan', port: 443 })).toBeNull()
    expect(siteUrl({ domain: '' })).toBeNull()
  })
})

describe('splitEmail', () => {
  it('ad + adres ayrılır; adres yoksa null', () => {
    expect(splitEmail('Ad Soyad - ad.soyad@example.com')).toEqual({ before: 'Ad Soyad - ', addr: 'ad.soyad@example.com', after: '' })
    expect(splitEmail('Ad Soyad (izinde)')).toBeNull()
    expect(splitEmail('')).toBeNull()
  })
})

describe('missingFields — hijyen bandıyla aynı kodlar', () => {
  it('takım / kritiklik / sorumlu / platform eksikleri sırayla', () => {
    expect(missingFields({ team_id: null, tier: null, platform: null })).toEqual(['no_team', 'no_tier', 'no_contacts', 'no_platform'])
    expect(missingFields({ team_id: 1, tier: 2, platform: 'IIS', app_dev_contact: 'dev@example.com' })).toEqual([])
    expect(missingFields({ team_id: 1, tier: 2, platform: 'IIS', app_dev_contact: '   ' })).toEqual(['no_contacts'])
  })
})

describe('splitFlags — 13 bayrak iki listeye TAM dağılır', () => {
  it('açık + kapalı = 13, sıra INVENTORY_FLAGS', () => {
    const { on, off } = splitFlags({ netscaler: true, action_required: true })
    expect(on.map((f) => f.key)).toEqual(['action_required', 'netscaler'])
    expect(on.length + off.length).toBe(13)
  })
})

describe('inventoryDeepLinkParams', () => {
  it('kendi kaydı: yalnız domain; salt okunur (başka takım) kayıt: tüm takımlar kapsamı', () => {
    expect(inventoryDeepLinkParams({ domain: 'a.example.com', can_manage: true })).toEqual({ domain: 'a.example.com' })
    expect(inventoryDeepLinkParams({ domain: 'b.example.com', can_manage: false })).toEqual({ domain: 'b.example.com', i_scope: 'all' })
  })
})

describe('küçük yardımcılar', () => {
  it('daysFromNow: yalnız tarih ve UTC damga; bozuk → null', () => {
    // UTC öğlen: UTC−11…UTC+11 arasındaki her saat diliminde yerel gün 28 Eylül (CI UTC, geliştirici İstanbul).
    const now = Date.parse('2026-09-28T12:00:00Z')
    expect(daysFromNow('2026-10-08', now)).toBe(10)   // 28 Eylül → 8 Ekim = 10 takvim günü (eskiden 9 — bir gün eksik)
    expect(daysFromNow('2026-09-20T12:00:00', now)).toBe(-8)
    expect(daysFromNow('bozuk', now)).toBeNull()
    expect(daysFromNow('2026-13-01', now)).toBeNull()
    expect(daysFromNow(null, now)).toBeNull()
  })

  it('hasRenewalInfo / etiket anahtarları / sorumlu tonu', () => {
    expect(hasRenewalInfo({})).toBe(false)
    expect(hasRenewalInfo({ domain_expiry: '2027-01-01' })).toBe(true)
    expect(intervalLabelKey(24)).toBe('inv.interval24h')
    expect(intervalLabelKey(null)).toBe('inv.intervalInherit')
    expect(tlsModeLabelKey('browser')).toBe('inv.tlsModeBrowser')
    expect(tlsModeLabelKey('')).toBe('inv.tlsModeInherit')
    expect([contactsTone(0), contactsTone(2), contactsTone(4)]).toEqual(['bad', 'warn', 'ok'])
  })
})

/**
 * Ek 3/1 (2026-09-28): yalnız tarih değer (yenileme planı, alan adı bitişi) YEREL takvim günüyle sayılır. Saat sahte
 * (`vi.setSystemTime`) ve YEREL kurucuyla kurulur; tarihler de o yerel günden türetilir → çalıştıran makinenin saat
 * diliminden (CI UTC / İstanbul) bağımsız, takvim ilerleyince eskimez.
 */
describe('daysFromNow — yerel takvim günü (gün sınırı / saat dilimi)', () => {
  const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const shift = (base, n) => { const d = new Date(base); d.setDate(d.getDate() + n); return ymd(d) }
  afterEach(() => { vi.useRealTimers() })

  it.each([
    ['gün başı 00:05', [2026, 8, 28, 0, 5]],
    ['öğlen 12:00', [2026, 8, 28, 12, 0]],
    ['gün sonu 23:59', [2026, 8, 28, 23, 59]],
  ])('%s: bugün 0, yarın 1, dün −1, 10 gün sonra 10 — saatten bağımsız', (_l, parts) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    const now = new Date(...parts)
    vi.setSystemTime(now)
    expect(daysFromNow(ymd(now))).toBe(0)
    expect(daysFromNow(shift(now, 1))).toBe(1)
    expect(daysFromNow(shift(now, -1))).toBe(-1)
    expect(daysFromNow(shift(now, 10))).toBe(10)
    expect(daysFromNow(shift(now, 30))).toBe(30)   // ≤ 30 eşiğinde (amber ton / "≤ 30 gün" süzgeci) kayma yok
  })

  it('ay ve yıl sınırı: 31 Aralık 23:30 → 1 Ocak yarın, 28 Şubat → 1 Mart 1 gün', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 11, 31, 23, 30))
    expect(daysFromNow('2027-01-01')).toBe(1)
    expect(daysFromNow('2026-12-31')).toBe(0)
    vi.setSystemTime(new Date(2027, 1, 28, 9, 0))
    expect(daysFromNow('2027-03-01')).toBe(1)
  })

  it('zaman damgası (UTC, `Z`siz) eskisi gibi kalan TAM gün — yalnız tarih kuralı ona uygulanmaz', () => {
    const now = Date.parse('2026-09-28T12:00:00Z')
    expect(daysFromNow('2026-09-29T11:00:00', now)).toBe(0)    // 23 saat kaldı
    expect(daysFromNow('2026-09-29T13:00:00Z', now)).toBe(1)
    expect(daysFromNow('2026-09-28T15:00:00+03:00', now)).toBe(0)
  })
})

describe('notificationGroupView — bildirim grubu satırı (2026-09-28)', () => {
  it('grup yok → takım varsayılanı; ad çözüldü → ad; kimlik var ama ad yok → bulunamadı', () => {
    expect(notificationGroupView({ notification_group_id: null })).toEqual({ kind: 'default', id: null, name: null })
    expect(notificationGroupView({})).toMatchObject({ kind: 'default' })
    expect(notificationGroupView({ notification_group_id: 7, notification_group_name: ' Nöbet A ' })).toEqual({ kind: 'named', id: 7, name: 'Nöbet A' })
    expect(notificationGroupView({ notification_group_id: 7 })).toEqual({ kind: 'missing', id: 7, name: null })
    expect(notificationGroupView({ notification_group_id: 7, notification_group_name: '' })).toMatchObject({ kind: 'missing' })
  })
})
