#!/usr/bin/env node
// OWASP Dependency-Check sonucunu sınıflandırır ve kapıyı verir — .github/workflows/dependency-check.yml çağırır;
// CI'da `node --test scripts/__tests__/ci-gates.test.mjs` sınar (2026-11-17, inceleme bulgusu).
//
// Neden ayrı betik: sınıflandırma iş akışının içinde satır içi kabuktu ve sınanamıyordu. 2026-11-16 düzeni "NVD
// güncellenemedi ama önbellekteki ESKİ veriyle tarama temiz bitti" durumunu `clean` sayıyordu → iş YEŞİL kalıyordu
// (özette yalnız bir uyarı satırı). Bayat veriyle temiz sonuç yeni CVE'leri görmez; artık ayrı sonuç `stale_clean` ve
// KIRMIZI (önbellek de kaydedilmez).
//
// Sonuçlar (sıra önemli — güncelleme hatası bulgudan ÖNCE denetlenir):
//   clean          — güncelleme başarılı, CVSS ≥ 7 bulgu yok                    → yeşil, önbellek kaydedilir
//   findings       — CVSS ≥ 7 bulgu (güncelleme düştüyse stale=true)            → kırmızı
//   stale_clean    — güncelleme düştü, eski veriyle tarama bulgusuz bitti       → kırmızı (temiz SAYILMAZ)
//   update_failed  — güncelleme düştü ve tarama da bitmedi (veri yok vb.)       → kırmızı
//   error          — başka bir hata (derleme, eklenti)                          → kırmızı
//
// Kullanım:  node scripts/depcheck-classify.mjs --log <günlük> --rc <maven çıkış kodu>   → stdout: result= / stale= / db_ok=
//            node scripts/depcheck-classify.mjs --gate <result>                         → açıklama + çıkış kodu (0 yalnız clean)
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

/**
 * NVD (ya da başka bir önbellekli veri kaynağı) güncellemesinin düştüğünü gösteren günlük satırları. Dependency-Check
 * çekirdeği güncelleme hatasında "Unable to update 1 or more Cached Web DataSource, using local Data instead" der ve
 * yerel veriyle sürer; veri hiç yoksa NoDataException ile durur.
 */
export const UPDATE_FAILURE_PATTERNS = [
  /Error updating the NVD Data/,
  /NoDataException/,
  /Unable to continue dependency-check analysis/,
  /Unable to update 1 or more Cached Web DataSource/i,
]

/** Eklentinin CVSS eşiği aşıldığında yazdığı satır (BaseDependencyCheckMojo). */
export const FINDINGS_PATTERN = /identified with vulnerabilities that have a CVSS/

/** Tüm olası sonuçlar; yalnız `clean` kapıdan geçer. */
export const RESULTS = ['clean', 'findings', 'stale_clean', 'update_failed', 'error']

/**
 * @param {{ log: string, rc: number }} input  Maven günlüğü ve çıkış kodu
 * @returns {{ result: string, stale: boolean, dbOk: boolean }}  dbOk = NVD veritabanı önbelleğe kaydedilebilir mi
 */
export function classify({ log, rc }) {
  const text = typeof log === 'string' ? log : ''
  const code = Number.isInteger(rc) ? rc : 1
  const updateFailed = UPDATE_FAILURE_PATTERNS.some((re) => re.test(text))
  const findings = FINDINGS_PATTERN.test(text)
  if (updateFailed) {
    // Bayat veri asla önbelleğe yazılmaz.
    if (findings) return { result: 'findings', stale: true, dbOk: false }
    if (code === 0) return { result: 'stale_clean', stale: true, dbOk: false }
    return { result: 'update_failed', stale: false, dbOk: false }
  }
  if (code === 0) return { result: 'clean', stale: false, dbOk: true }
  if (findings) return { result: 'findings', stale: false, dbOk: true }
  return { result: 'error', stale: false, dbOk: false }
}

/**
 * Kapı kararı. Bilinmeyen / boş sonuç geçmez (adım hiç koşmadıysa da iş kırmızı).
 * @returns {{ pass: boolean, message: string }}  message: GitHub ek açıklaması (pass=false ise ::error:: ile basılır)
 */
export function gate(result) {
  switch (result) {
    case 'clean':
      return { pass: true, message: 'OWASP Dependency-Check temiz.' }
    case 'findings':
      return { pass: false, message: 'CVSS ≥ 7 bağımlılık bulgusu (ayrıntı: özet ve dependency-check-report)' }
    case 'stale_clean':
      return {
        pass: false,
        message: 'NVD verisi güncellenemedi; tarama önbellekteki ESKİ veriyle bulgusuz bitti — yeni CVE\'ler görülmemiş '
          + 'olabilir, temiz SAYILMAZ (NVD_API_KEY tanımlayın / yeniden koşun)',
      }
    case 'update_failed':
      return { pass: false, message: 'NVD verisi güncellenemedi; tarama yapılamadı (NVD_API_KEY tanımlayın / yeniden deneyin)' }
    case 'error':
      return { pass: false, message: 'OWASP Dependency-Check beklenmeyen hatayla bitti (adım günlüğüne bakın)' }
    default:
      return { pass: false, message: `OWASP Dependency-Check sonucu yok ya da tanınmıyor (${result || 'yok'})` }
  }
}

/** Özet satırı (Markdown) — iş akışının "Summary" adımı yazar. */
export function summaryLine(result) {
  switch (result) {
    case 'clean': return '**Sonuç: temiz** — CVSS ≥ 7 bulgu yok.'
    case 'findings': return '**Sonuç: ⛔ CVSS ≥ 7 bulgu var** — ayrıntı dependency-check-report artifact\'ında.'
    case 'stale_clean':
      return '**Sonuç: ⚠ bayat veriyle temiz — GEÇMEZ** — NVD güncellenemedi; tarama önbellekteki eski veriyle bulgusuz '
        + 'bitti, yeni CVE\'ler eksik olabilir.'
    case 'update_failed': return '**Sonuç: ⚠ NVD verisi güncellenemedi** — tarama yapılamadı (rapor yok ya da eksik).'
    default: return '**Sonuç: ⚠ beklenmeyen hata** — adım günlüğüne bakın.'
  }
}

function arg(argv, name) {
  const i = argv.indexOf(name)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : undefined
}

export function main(argv = process.argv.slice(2), out = process.stdout) {
  if (argv.includes('--gate')) {
    const g = gate(arg(argv, '--gate'))
    out.write(g.pass ? `${g.message}\n` : `::error::${g.message}\n`)
    return g.pass ? 0 : 1
  }
  if (argv.includes('--summary')) {
    out.write(`${summaryLine(arg(argv, '--summary'))}\n`)
    return 0
  }
  const logPath = arg(argv, '--log')
  const rcRaw = arg(argv, '--rc')
  let log = ''
  try { log = logPath ? readFileSync(logPath, 'utf8') : '' } catch { log = '' }
  const rc = /^-?\d+$/.test(rcRaw ?? '') ? Number(rcRaw) : 1
  const c = classify({ log, rc })
  out.write(`result=${c.result}\nstale=${c.stale}\ndb_ok=${c.dbOk}\n`)
  return 0
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exitCode = main()
}
