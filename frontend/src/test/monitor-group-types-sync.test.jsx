import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'
import { TYPE_LABEL } from '../components/admin/MonitorGroups.jsx'

/**
 * İZLEME GRUPLARI TÜR ROZETİ ↔ backend'in ürettiği türler.
 *
 * `permission-labels-sync` ve `settings-labels-sync` ile AYNI sınıf, üçüncü yüzey: etiket
 * `t(TYPE_LABEL[g.type])` ile DİNAMİK kuruluyor, dolayısıyla haritada olmayan bir tür statik
 * i18n kapılarının hiçbirine takılmıyor. `pagespeed` tam olarak böyle kaçmıştı — backend on tür
 * üretirken harita dokuzunu tanıyordu ve o satırda çevrilmemiş ham `pagespeed` görünüyordu.
 * Arama kutusu da ham dizeyle eşleştiği için Türkçe "hız" yazan kullanıcı satırı bulamıyordu.
 *
 * Kapı backend'i tek doğruluk kaynağı kabul eder: yeni bir izleme türü eklendiği gün burası
 * kırılır ve etiket unutulmaz.
 */
const SERVICE = path.resolve(
  __dirname, '../../../backend/src/main/java/com/sitemonitor/service/MonitoringGroupService.java')

/** Servisin ürettiği tür literalleri (küçük harfli, tek kelime). */
function backendTypes() {
  const src = fs.readFileSync(SERVICE, 'utf8')
  return [...new Set([...src.matchAll(/"([a-z]+)"/g)].map(m => m[1]))]
    .filter(t => ['cert', 'http', 'ping', 'port', 'dns', 'keyword', 'domain',
      'page', 'scripted', 'pagespeed'].includes(t))
}

describe('İzleme Grupları tür etiketleri ↔ backend', () => {
  it('kaynak gerçekten okunuyor (kapı boşa çalışmıyor)', () => {
    expect(backendTypes().length).toBeGreaterThanOrEqual(9)
  })

  it('backend’in ürettiği her türün bir etiket anahtarı var', () => {
    const missing = backendTypes().filter(t => !TYPE_LABEL[t])
    expect(missing, 'haritada olmayan tür ekranda HAM olarak görünür').toEqual([])
  })

  it('her etiket anahtarının TR ve EN karşılığı var', () => {
    const missing = []
    for (const key of Object.values(TYPE_LABEL)) {
      if (!TR[key]) missing.push(`TR ${key}`)
      if (!EN[key]) missing.push(`EN ${key}`)
    }
    expect(missing).toEqual([])
  })
})
