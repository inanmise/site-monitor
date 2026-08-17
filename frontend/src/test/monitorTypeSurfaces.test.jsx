import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { MONITOR_GUIDES } from '../components/monitorGuides.js'

/**
 * YENİ İZLEME TÜRÜ EKLEYENİ DURDURAN KAPI — arayüz yüzeyleri.
 *
 * <p>Yeni bir izleme türü eklendiğinde checker ve CRUD yazılıyor, sayfa açılıyor, her şey
 * çalışıyor gibi görünüyor; sonra türe özel bir LİSTE atlanıyor. Atlanan liste hata vermiyor,
 * yalnızca o tür oradan yok oluyor. Bu daha önce EN AZ İKİ KEZ oldu ve ikisi de kodda yorumla
 * kayıtlı:
 *
 *   • MaintenanceWindowsPage.MON_TYPES'ta `scripted` yoktu → planlı bakımda sentetik monitörler
 *     susturulamıyordu; tek çare "tüm monitörler" bayrağıyla her şeyi birden susturmaktı.
 *   • ActivityLog.TYPES'ta `SCRIPTED` yoktu → satırlar gri "?" rozetiyle çıkıyor ve tür filtresi
 *     çipi hiç üretilmiyordu.
 *
 * <p>Ayrıca backend tarafında `page` (Sayfa Bütünlüğü) haftalık göstergelerde hiç sayılmıyordu
 * (2026-08-16'da düzeltildi) — arayüz onu bekliyordu ama veri hiç gelmiyordu.
 *
 * <p>Kanonik liste ELLE YAZILMAZ: backend {@code MonitorTypeCatalog.ORDER}'dan ayrıştırılır.
 * Elle yazılsaydı koruma, korumayı kuran kişinin dikkatine bağlı olurdu — tam da kapatmaya
 * çalıştığı hata sınıfı. Backend ikizi: {@code MonitorTypeCatalogTest}.
 */

const FRONTEND_SRC = resolve(__dirname, '..')
const CATALOG = resolve(__dirname,
  '../../../backend/src/main/java/com/sitemonitor/service/MonitorTypeCatalog.java')

function read(relPath) {
  const full = resolve(FRONTEND_SRC, relPath)
  expect(existsSync(full), `beklenen dosya yok: ${relPath}`).toBe(true)
  return readFileSync(full, 'utf8')
}

/** Kanonik izleme türleri — backend katalogunun ORDER listesinden. */
function canonicalTypes() {
  const src = readFileSync(CATALOG, 'utf8')
  const at = src.indexOf('ORDER =')
  expect(at, 'MonitorTypeCatalog okundu ama ORDER bulunamadı — yol/regex bozulmuş').toBeGreaterThan(-1)
  const start = src.indexOf('List.of(', at)
  const end = src.indexOf(')', start)
  const types = [...src.slice(start, end).matchAll(/"(\w+)"/g)].map(m => m[1])
  expect(types.length, 'ORDER bulundu ama içinden tür çıkmadı — regex bozulmuş').toBeGreaterThan(0)
  return types
}

const ALL_TYPES = canonicalTypes()
/** Sertifika izlemesinin kendi sayfası/sekmesi yok (Sertifikalar grubundaki "Durum İzleme"). */
const MONITOR_TYPES = ALL_TYPES.filter(t => t !== 'cert')

/** Kaynaktan bir blok kesip içindeki tırnaklı değerleri toplar; boş çıkarsa test patlar. */
function valuesIn(src, startMarker, endMarker, pattern, label) {
  const from = src.indexOf(startMarker)
  expect(from, `${label}: "${startMarker}" bulunamadı — dosya değişmiş`).toBeGreaterThan(-1)
  const to = src.indexOf(endMarker, from)
  expect(to, `${label}: blok sonu bulunamadı`).toBeGreaterThan(from)
  const found = [...src.slice(from, to).matchAll(pattern)].map(m => m[1])
  expect(found.length, `${label}: blok bulundu ama içinden değer çıkmadı — regex bozulmuş`).toBeGreaterThan(0)
  return found
}

describe('İzleme türü yüzeyleri — yeni tür eklenince hepsi güncellenmeli', () => {
  it('Kanonik liste okunabiliyor ve beklenen türleri içeriyor', () => {
    // Bu iddia listenin BOYUTUNU dondurmuyor; yalnız ayrıştırmanın çalıştığını ve bilinen
    // türlerin orada olduğunu doğruluyor. Yeni tür eklendiğinde bu test DEĞİŞMEDEN geçer,
    // aşağıdakiler ise eksik yüzeyi gösterir.
    expect(ALL_TYPES).toContain('cert')
    expect(ALL_TYPES).toContain('page')
    expect(ALL_TYPES).toContain('scripted')
    expect(MONITOR_TYPES).not.toContain('cert')
  })

  it('Sidebar "İzleme" grubu her izleme türünü içerir', () => {
    // Anasayfadan erişilen ana giriş noktası. Eksik tür = kullanıcı o sayfaya hiç ulaşamaz.
    const ids = valuesIn(read('components/Nav.jsx'),
      "labelKey: 'nav.groupMonitoring'", '],', /id: '([\w-]+)'/g, 'Nav İzleme grubu')
    expect(ids).toEqual(expect.arrayContaining(MONITOR_TYPES))
  })

  it('VALID_TABS her izleme türünü kabul eder (derin bağlantı ?tab=)', () => {
    // Eksik tür: maildeki/paylaşılan bağlantı sessizce dashboard'a düşer.
    const tabs = valuesIn(read('App.jsx'),
      'const VALID_TABS = new Set([', '])', /'([\w-]+)'/g, 'VALID_TABS')
    expect(tabs).toEqual(expect.arrayContaining(MONITOR_TYPES))
  })

  it('Her izleme türünün sayfası var ve iki standart yardım bileşenini kullanıyor', () => {
    // Proje standardı: MonitorHowBox (nasıl/nereden) + MonitorGuideButton (form nasıl doldurulur).
    for (const type of MONITOR_TYPES) {
      const file = `components/${type[0].toUpperCase()}${type.slice(1)}MonitorPage.jsx`
      const src = read(file)
      expect(src, `${file}: MonitorHowBox yok`).toContain('MonitorHowBox')
      expect(src, `${file}: MonitorGuideButton yok`).toContain('MonitorGuideButton')
    }
  })

  it('monitorGuides her izleme türü için TR+EN rehber taşır', () => {
    for (const type of MONITOR_TYPES) {
      expect(MONITOR_GUIDES, `rehber yok: ${type}`).toHaveProperty(type)
      expect(MONITOR_GUIDES[type].tr, `${type}: TR rehberi boş`).toBeTruthy()
      expect(MONITOR_GUIDES[type].en, `${type}: EN rehberi boş`).toBeTruthy()
    }
  })

  it('Bakım penceresi hedef seçici her türü listeler (sertifika dahil)', () => {
    // Eksik tür: o türün monitörleri planlı bakımda SUSTURULAMAZ. `scripted` burada
    // eksikti ve tek çare "tüm monitörler" bayrağıyla her şeyi birden susturmaktı.
    const types = valuesIn(read('components/MaintenanceWindowsPage.jsx'),
      'const MON_TYPES = [', '\n]', /\['(\w+)'/g, 'MON_TYPES')
    expect(types).toEqual(expect.arrayContaining(ALL_TYPES))
  })

  it('Etkinlik günlüğü tür rozetleri her türü tanır', () => {
    // Eksik tür: satırlar gri "?" rozetiyle çıkar ve tür filtresi çipi hiç üretilmez.
    const keys = valuesIn(read('components/ActivityLog.jsx'),
      'const TYPES = [', '\n]', /key: '(\w+)'/g, 'ActivityLog TYPES')
    const upper = ALL_TYPES.map(t => t.toUpperCase())
    expect(keys).toEqual(expect.arrayContaining(upper))
  })

  it('Haftalık izleme şeridi sırası katalogla BİREBİR aynı', () => {
    // Sıra ayrışırsa aynı rapor iki yerde farklı okunur; eksik tür ise satırı hiç çizilmez.
    const order = valuesIn(read('components/WeeklyMonitoringStrip.jsx'),
      'const ORDER = [', ']', /'(\w+)'/g, 'WeeklyMonitoringStrip ORDER')
    expect(order).toEqual(ALL_TYPES)
  })
})
