import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
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
 * BACKEND'İN KANONİK LİSTESİ — elle YAZILMAZ, kaynaktan OKUNUR.
 *
 * <p>Bu listenin elle tutulduğu ilk sürümde şöyle bir açık vardı: yeni bir izleme türü ekleyen
 * kişi {@code EscalationService}'e {@code TYPE_YENI} yazar, sözlüğü günceller mi bilinmez —
 * ama test listesini de güncellemesi gerektiği için test yine yeşil kalabilirdi. Yani koruma,
 * korumayı kuran kişinin dikkatine bağlıydı.
 *
 * <p>Artık liste Java kaynağından ayrıştırılıyor: {@code TYPE_X = "X"} satırları. Yeni bir alarm
 * tipi eklendiği anda bu suite, sözlükte VE i18n'de karşılığı olana kadar KIRMIZI kalır.
 *
 * <p>Bilinen bağ: dosya yolu. Taşınırsa test yüksek sesle kırılır (sessizce atlamaz) ve yol
 * güncellenir — kabul edilebilir bir takas.
 */
function canonicalTypesFromBackend() {
  const src = readFileSync(
    resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/EscalationService.java'),
    'utf8')
  const types = [...src.matchAll(/TYPE_\w+\s*=\s*"(\w+)"/g)].map(m => m[1])
  if (types.length === 0) throw new Error('EscalationService okundu ama TYPE_* bulunamadı — regex/yol bozulmuş')
  return [...new Set(types)]
}

/**
 * Sertifika alarm tipleri — Java kaynağındaki {@code CERT_ALERT_TYPES} kümesinden AYRIŞTIRILIR.
 *
 * Burada dört tip ELLE yazılıydı ve yorumu "EscalationService'te sabit DEĞİL" diyordu; o not
 * bayatlamıştı. `CERT_ALERT_TYPES` mevcut ve altı tip içeriyor: dördü düz literal, ikisi
 * `TYPE_*` sabiti (sabit olanlar zaten yukarıdaki otomatik türetmeye giriyordu). Donmuş liste
 * yüzünden kümeye eklenen yeni bir sertifika tipi bu kapıya HİÇ görünmezdi.
 */
function certTypesFromBackend(all) {
  const src = readFileSync(
    resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/EscalationService.java'),
    'utf8')
  const block = /CERT_ALERT_TYPES\s*=\s*Set\.of\(([\s\S]*?)\);/.exec(src)
  if (!block) throw new Error('CERT_ALERT_TYPES bulunamadı — regex/yol bozulmuş')
  const literals = [...block[1].matchAll(/"(\w+)"/g)].map(m => m[1])
  // Sabitle verilenler (TYPE_HOSTNAME_MISMATCH gibi) zaten TYPE_* türetmesinde var;
  // kesişimi almak yerine ikisini birleştirmek yeterli.
  const consts = [...block[1].matchAll(/TYPE_(\w+)/g)]
    .map(m => all.find(t => t === m[1]) ?? m[1])
  const out = [...new Set([...literals, ...consts])]
  if (out.length < 4) throw new Error('CERT_ALERT_TYPES ayrıştırıldı ama beklenenden az tip çıktı')
  return out
}

const backendTypes = canonicalTypesFromBackend()
const canonicalTypes = [...new Set([...backendTypes, ...certTypesFromBackend(backendTypes)])]

// renderHook'un kanonik deseni: callback React'in kendi render bağlamında çağrılıyor,
// lint bunu göremediği için yanlış alarm veriyor (kural yalnız bu satırda susturuluyor).
// eslint-disable-next-line react-hooks/rules-of-hooks
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
