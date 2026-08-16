import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { LangProvider, useT } from '../i18n/index.jsx'
import { ALERT_TYPE_META, ALERT_TYPES, alertTypeMeta, alertTypeLabel } from '../utils/alertTypeMeta.js'

/**
 * ALARM TİPİ SÖZLÜĞÜ — bu turda kapatılan işlevsel boşluğun kilidi.
 *
 * <p>AlertHistory kendi haritasını tutuyordu ve yalnız 11 tip tanıyordu; backend'de 24 (sertifika
 * tipleriyle 28) tip var. Keyword/ping/HTTP/sayfa/sentetik/alan-adı alarmları ham enum adıyla
 * görünüyor VE filtre pill'leri aynı haritadan üretildiği için hiç filtrelenemiyordu.
 *
 * <p>Buradaki asıl test {@link canonicalTypes}: yeni bir izleme türü eklenip alarm tipi
 * sözlüğe yazılmazsa bu suite KIRMIZI döner. Aksi halde aynı sessiz boşluk tekrar oluşur —
 * ekran çalışır görünür, yalnız o alarmlar adsız ve filtresiz kalır.
 */

/**
 * Backend'in kanonik listesi. Kaynak: EscalationService'teki TYPE_* sabitleri + sertifika
 * tipleri. Güncellemek için:
 *   grep -o 'TYPE_\w* = "\w*"' backend/src/main/java/com/sitemonitor/service/EscalationService.java
 */
const canonicalTypes = [
  // sertifika
  'EXPIRY', 'CHAIN_BROKEN', 'REVOKED', 'MISMATCH', 'ACCESSIBILITY',
  // dns
  'DNS_FAILURE', 'DNS_SLOW', 'DNS_UNEXPECTED', 'DNS_INCONSISTENT', 'DNS_CHANGED',
  // port
  'PORT_DOWN', 'PORT_SLOW',
  // http
  'HTTP_DOWN', 'HTTP_SSL',
  // keyword
  'KEYWORD', 'KEYWORD_SLOW', 'KEYWORD_SSL', 'KEYWORD_DOMAIN_EXPIRY',
  // ping
  'PING_DOWN',
  // alan adı
  'DOMAIN_EXPIRY', 'DOMAINMON_EXPIRY', 'DOMAINMON_UNKNOWN', 'DOMAINMON_STATUS', 'DOMAINMON_CHANGED',
  // sayfa bütünlüğü
  'PAGE_DOWN', 'PAGE_INTEGRITY',
  // sentetik
  'SCRIPTED_FAIL', 'SCRIPTED_SLOW',
]

const useTr = () => renderHook(() => useT(), {
  wrapper: ({ children }) => <LangProvider>{children}</LangProvider>,
}).result.current

describe('alertTypeMeta', () => {
  it('BACKEND\'İN TÜM alarm tipleri sözlükte var (yeni tür eklenince bu test kırılır)', () => {
    const missing = canonicalTypes.filter(t => !(t in ALERT_TYPE_META))
    expect(missing, `sözlüğe eklenmemiş tipler: ${missing.join(', ')}`).toEqual([])
    expect(ALERT_TYPES).toHaveLength(canonicalTypes.length)
  })

  it('her tipin ikonu VE rengi var — hiçbiri yarım tanımlı değil', () => {
    for (const type of ALERT_TYPES) {
      const m = ALERT_TYPE_META[type]
      expect(m.icon, `${type}: ikon yok`).toBeTruthy()
      expect(m.color, `${type}: renk yok`).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('her tipin i18n karşılığı var — ham enum adı EKRANA DÜŞMEZ', () => {
    const t = useTr()
    const untranslated = ALERT_TYPES.filter(type => alertTypeLabel(t, type) === type)
    expect(untranslated, `çevirisi olmayan tipler: ${untranslated.join(', ')}`).toEqual([])
  })

  it('BİLİNMEYEN tipte de kullanılabilir bir kimlik döner (ekran ikonsuz kalmaz)', () => {
    const m = alertTypeMeta('HENUZ_OLMAYAN_TIP')
    expect(m.icon).toBeTruthy()
    expect(m.color).toMatch(/^#[0-9a-f]{6}$/i)
    // null/undefined de çökertmemeli
    expect(alertTypeMeta(undefined).icon).toBeTruthy()
    expect(alertTypeMeta(null).color).toBeTruthy()
  })

  it('çeviri yoksa etiket HAM TİPE düşer (anahtar sızmaz)', () => {
    const t = useTr()
    // "incov.type.XYZ" gibi bir anahtar ASLA kullanıcıya gösterilmemeli
    expect(alertTypeLabel(t, 'HENUZ_OLMAYAN_TIP')).toBe('HENUZ_OLMAYAN_TIP')
    expect(alertTypeLabel(t, null)).toBe('')
    expect(alertTypeLabel(t, '')).toBe('')
  })

  it('bilinen tipler gerçekten ÇEVRİLİR (fallback her şeyi yutmuyor)', () => {
    const t = useTr()
    // Fallback dalı çok geniş olsaydı yukarıdaki testler de yeşil kalırdı; bu onu ayırır.
    expect(alertTypeLabel(t, 'SCRIPTED_FAIL')).not.toBe('SCRIPTED_FAIL')
    expect(alertTypeLabel(t, 'KEYWORD_SLOW')).not.toBe('KEYWORD_SLOW')
    expect(alertTypeLabel(t, 'EXPIRY')).not.toBe('EXPIRY')
  })
})
