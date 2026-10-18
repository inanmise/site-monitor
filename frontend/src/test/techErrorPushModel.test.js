import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  PUSH_FIELDS, PUSH_GROUPS, applyPush, changedPushFields, isPushDirty, normalizePush, pushState, toPushForm,
} from '../components/admin/techErrorPushModel.js'

// Teknik hata raporu push bildirimleri kartının saf modeli (2026-10-18). Alan listesi Java kaynağıyla aynı sırada
// (TechErrorSettingsController.PUSH_FIELDS); bilinmeyen ≠ açık (okunamayan blok değer uydurmaz); gövde YALNIZ değişen anahtarlar.
const CONTROLLER = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/controller/TechErrorSettingsController.java')

describe('techErrorPushModel', () => {
  it('alanlar Java ucundaki PUSH_FIELDS ile aynı ve aynı sırada; gruplar her alanı bir kez kapsar', () => {
    const src = fs.readFileSync(CONTROLLER, 'utf8')
    const java = [...src.matchAll(/PUSH_FIELDS\.put\("([a-z_]+)"/g)].map((m) => m[1])
    expect(java.length).toBeGreaterThan(0)
    expect([...PUSH_FIELDS]).toEqual(java)
    expect(PUSH_GROUPS.flatMap((g) => g.fields).sort()).toEqual([...PUSH_FIELDS].sort())
  })

  it('normalizePush: eksik alan sunucu varsayılanı gibi AÇIK, false kapalı; push_enabled bilinmiyorsa null; blok yoksa null', () => {
    const n = normalizePush({ decision: false, push_enabled: false, read_only: true })
    expect(n.decision).toBe(false)
    expect(n.approval_request).toBe(true)
    expect(n.period_reminder).toBe(true)
    expect(n.push_enabled).toBe(false)
    expect(n.read_only).toBe(true)
    expect(normalizePush({}).push_enabled).toBeNull()
    expect(normalizePush(null)).toBeNull()
    expect(normalizePush([])).toBeNull()
    expect(normalizePush('x')).toBeNull()
  })

  it('pushState: eski pod (404 / 405) "desteklenmiyor"; başarısız okuma önceki değerleri korur, uydurmaz; beklenmeyen biçim → kart yok', () => {
    const ok = pushState({ success: true, data: { failed: false } })
    expect(ok.status).toBe('ok')
    expect(ok.data.failed).toBe(false)
    expect(pushState({ success: false, status: 404 }, ok)).toEqual({ status: 'unsupported', data: ok.data, error: null })
    expect(pushState({ success: false, status: 405 }).status).toBe('unsupported')
    expect(pushState({ success: false, status: 500, error: 'Sunucu hatası: istek kimliği 1' }, ok))
      .toEqual({ status: 'failed', data: ok.data, error: 'Sunucu hatası: istek kimliği 1' })
    expect(pushState(null)).toEqual({ status: 'failed', data: null, error: null })
    expect(pushState({ success: true, data: [] })).toBeNull()
    expect(pushState({ success: true, data: [] }, ok).status).toBe('failed')
  })

  it('changedPushFields: yalnız değişen anahtarlar (boolean); değişiklik yoksa boş — istek gitmez; applyPush yanıt yokken gövdeyi uygular', () => {
    const saved = normalizePush({ decision: false, push_enabled: true })
    const form = toPushForm(saved)
    expect(changedPushFields(saved, form)).toEqual({})
    expect(isPushDirty(saved, form)).toBe(false)
    const next = { ...form, decision: true, failed: false }
    expect(changedPushFields(saved, next)).toEqual({ decision: true, failed: false })
    expect(isPushDirty(saved, next)).toBe(true)
    expect(changedPushFields(null, form)).toEqual({})
    const applied = applyPush(saved, { decision: true })
    expect(applied.decision).toBe(true)
    expect(applied.push_enabled).toBe(true)
    expect(toPushForm(null)).toEqual(Object.fromEntries(PUSH_FIELDS.map((k) => [k, true])))
  })
})
