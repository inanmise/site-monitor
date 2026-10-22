import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { triggerKey, triggerText, KNOWN_TRIGGER_CODES } from '../components/admin/logTriggers.js'
import { TRIGGERS as PUSH_TRIGGERS } from '../components/admin/push/pushLogModel.js'
import { REPO_ROOT } from './helpers/javaTable.js'

/**
 * GÖNDERİM GÜNLÜĞÜ TETİKLEYİCİ KAPISI (20.166 bugfix, UIA-14 / UIA-C4). SMTP logu 9, push logu ~20 kodu tanıyordu; fırtına,
 * 7/24, bekçi, yönetici özeti, kanal raporu, TLS notu düşüşü … iki dilde de HAM kod basılıyordu ve TLS_GRADE_DROP push
 * süzgecinde yoktu.
 *
 * Kural: sunucunun yazdığı her tetikleyici sabiti (`static final String …TRIGGER… = "X"`), her `setTrigger("X")` literali ve
 * her `enqueueTeamNotice*(team, "X", …)` literali `logTriggers.js` sözlüğünde bir TR + EN etikete çözülür. Bilerek dışarıda
 * bırakılanlar aşağıda gerekçeli; liste yalnız küçülür.
 */
const EXEMPT = {
  TRIGGER: 'AlertStormMember.JOIN_TRIGGER — fırtına üyeliğinin katılma TÜRÜ, gönderim günlüğüne yazılmaz',
  SCHEDULED: 'TlsProfile.TRIGGER_SCHEDULED — TLS profil taramasının tetik türü, gönderim günlüğüne yazılmaz',
}

function javaFiles(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) javaFiles(p, acc)
    else if (e.name.endsWith('.java')) acc.push(p)
  }
  return acc
}

function serverTriggers() {
  const out = new Set()
  for (const f of javaFiles(path.join(REPO_ROOT, 'backend/src/main/java/com/sitemonitor'))) {
    const src = fs.readFileSync(f, 'utf8')
    for (const m of src.matchAll(/static\s+final\s+String\s+[A-Z0-9_]*TRIGGER[A-Z0-9_]*\s*=\s*"([A-Z][A-Z0-9_]*)"/g)) out.add(m[1])
    for (const m of src.matchAll(/\.setTrigger\(\s*"([A-Z][A-Z0-9_]*)"\s*\)/g)) out.add(m[1])
    for (const m of src.matchAll(/\benqueueTeamNotice\w*\(\s*[^,()]+(?:\([^()]*\))?\s*,\s*"([A-Z][A-Z0-9_]*)"/g)) out.add(m[1])
  }
  return [...out].sort()
}

describe('gönderim günlüğü tetikleyici etiketleri', () => {
  const codes = serverTriggers()

  it('sunucu kaynağı okundu (en az 30 tetikleyici)', () => {
    expect(codes.length).toBeGreaterThan(30)
    expect(codes).toEqual(expect.arrayContaining(['TLS_GRADE_DROP', 'WATCHDOG', 'STORM_INITIAL', 'NOC_OPEN']))
  })

  it('her sunucu tetikleyicisi sözlükte (ya da gerekçeli istisna)', () => {
    const missing = codes.filter((c) => !EXEMPT[c] && !triggerKey(c, 'smtp') && !triggerKey(c, 'push'))
    expect(missing, `etiketsiz tetikleyiciler: ${missing.join(', ')}`).toEqual([])
  })

  it.each([['TR', TR], ['EN', EN]])('%s: bilinen her kodun anahtarı sözlükte var (ham anahtar basılmaz)', (_l, dict) => {
    for (const kind of ['smtp', 'push']) {
      for (const code of KNOWN_TRIGGER_CODES) {
        const k = triggerKey(code, kind)
        expect(dict[k], `${kind}:${code} → ${k}`).toBeTruthy()
      }
    }
  })

  it('istisnalar canlı: sunucu hâlâ yazıyor (yoksa listeden çıkarın)', () => {
    for (const code of Object.keys(EXEMPT)) expect(codes, code).toContain(code)
  })

  it('push süzgeç listesi TLS_GRADE_DROP içerir; bilinmeyen kod ham, DEPLOYMENT_* öneki etiketli', () => {
    expect(PUSH_TRIGGERS).toContain('TLS_GRADE_DROP')
    const t = (k) => EN[k] ?? k
    expect(triggerText('NO_SUCH_CODE', t)).toBe('NO_SUCH_CODE')
    expect(triggerText('DEPLOYMENT_RESTART', t)).toBe(EN['logtrig.DEPLOYMENT'])
    expect(triggerText(null, t)).toBe('—')
    // aynı kod iki kanalda farklı anlam: SMTP ESCALATION = eskalasyon e-postası, push ESCALATION = seviye yükseldi
    expect(triggerText('ESCALATION', t, 'smtp')).toBe(EN['health.triggerEscalation'])
    expect(triggerText('ESCALATION', t, 'push')).toBe(EN['userpush.trigger.ESCALATION'])
  })
})
