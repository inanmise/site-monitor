import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * DİLLER-ARASI BEKÇİ: etkinlik akışına (activity_log) yazılan HER eylem kodunun `act.ac.<kod>` etiketi olmalı.
 *
 * `ActivityLog.jsx` eylemi `t('act.ac.' + row.action)` ile basar; anahtar yoksa kullanıcı ham `act.ac.NOC_NOTIFIED`
 * görür. 7/24 bildirimi (NOC_NOTIFIED / NOC_RESOLVED) ve alan adı yenileme/hatırlatma olayları (RENEWAL_*,
 * EXPIRY_REMINDER) etiketsiz yayına girmişti — hiçbir test kırılmadı (i18n-parity yalnız TR↔EN dengesine bakar).
 *
 * Kaynak (backend eylem kodunu nerede yazıyorsa): `activityLog.recordLifecycle(…, "<EYLEM>", …)` çağrıları, 7/24
 * arama kaydının `activity(ev, teamId, "<EYLEM>", …)` yardımcısı ve kontrol kayıtlarının `*_CHECK` kodları
 * (ActivityLogService.recordCheck). Kırılırsa: yeni eylemin `act.ac.<kod>` anahtarını TR ve EN sözlüğüne ekleyin.
 */
const JAVA_ROOT = path.resolve(__dirname, '../../../backend/src/main/java')

function javaFiles(dir) {
  const out = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) out.push(...javaFiles(p))
    else if (e.name.endsWith('.java')) out.push(p)
  }
  return out
}

/** `callee(` ile başlayan her çağrının ilk `);`'ye kadarki metnindeki BÜYÜK_HARF dize sabitleri. */
function literalsInCalls(src, callee) {
  const out = []
  const re = new RegExp(`(?<![A-Za-z0-9_])${callee}[(]`, 'g')   // `activityLog.recordLifecycle(` de, `activity(` de
  let m
  while ((m = re.exec(src)) !== null) {
    const end = src.indexOf(');', m.index)
    const seg = src.slice(m.index, end < 0 ? undefined : end)
    for (const x of seg.matchAll(/"([A-Z][A-Z0-9_]+)"/g)) out.push(x[1])
  }
  return out
}

function backendActions() {
  const codes = new Set()
  for (const f of javaFiles(JAVA_ROOT)) {
    const src = fs.readFileSync(f, 'utf8')
    for (const c of literalsInCalls(src, 'recordLifecycle')) codes.add(c)
    for (const c of literalsInCalls(src, 'activity')) codes.add(c)
    if (path.basename(f) === 'ActivityLogService.java') for (const x of src.matchAll(/"([A-Z]+_CHECK)"/g)) codes.add(x[1])
  }
  return [...codes].sort()
}

describe('act.ac.* — backend etkinlik eylem kodlarıyla senkron', () => {
  const codes = backendActions()

  it('backend eylemleri okunabiliyor (boş/eksik liste = yol ya da çağrı sözdizimi değişmiş)', () => {
    expect(fs.existsSync(JAVA_ROOT), `bulunamadı: ${JAVA_ROOT}`).toBe(true)
    for (const must of ['CREATED', 'DELETED', 'CONFIG_CHANGED', 'SCHEDULED_CHECK', 'MANUAL_CHECK',
      'NOC_NOTIFIED', 'NOC_RESOLVED', 'NOC_CALL_LOGGED', 'NOC_CALL_DELETED']) {
      expect(codes, must).toContain(must)
    }
  })

  it('her eylem kodunun TR ve EN etiketi var (ham anahtar ekrana düşmez)', () => {
    const missing = []
    for (const c of codes) {
      for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
        const v = dict[`act.ac.${c}`]
        if (typeof v !== 'string' || !v.trim()) missing.push(`${lang}: act.ac.${c}`)
      }
    }
    expect(missing).toEqual([])
  })

  it('TR ve EN etiketleri birbirinin kopyası değil', () => {
    for (const c of codes) expect(EN[`act.ac.${c}`], c).not.toBe(TR[`act.ac.${c}`])
  })
})
