import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MAX_EXTRA_CC, MAX_EXTRA_CC_INPUT, SENDLOG_KEYS, SENDLOG_KINDS, SENDLOG_KIND_KEYS, SENDLOG_ROLE_KEYS, SENDLOG_SIZE_OPTIONS,
  SENDLOG_STATUSES, SENDLOG_STATUS_KEYS, isDeliverable, normalizeSendLogKind, normalizeSendLogStatus, parseExtraCc, sendLogKindOf,
  sendLogRoleKey, sendLogStatusOf,
} from '../components/techerror/sendModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

// R18 (2026-10-18): gönderime ek Bilgi (CC) ve kanal gönderim günlüğü modeli — sunucu sınırları Java kaynağından okunur
// (iki taraf ayrışırsa kırmızı); biçim kuralı sunucu deseniyle aynı; günlüğün tür / durum / rol anahtarları sözlükte.
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const SENDER = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorReportSender.java'), 'utf8')
const LOG = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorSendLogService.java'), 'utf8')
const javaInt = (src, name) => Number(new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(src)[1].replace(/_/g, ''))

describe('ek Bilgi (CC) — sunucu aynası', () => {
  it('sınırlar Java sabitleriyle aynı; sayfa boyutları sunucunun en büyük sayfasını aşmaz', () => {
    expect(MAX_EXTRA_CC).toBe(javaInt(SENDER, 'MAX_EXTRA_CC'))
    expect(MAX_EXTRA_CC_INPUT).toBe(javaInt(SENDER, 'MAX_EXTRA_CC_INPUT'))
    const maxPage = javaInt(LOG, 'MAX_PAGE_SIZE')
    expect(SENDLOG_SIZE_OPTIONS.length).toBeGreaterThan(0)
    expect(Math.max(...SENDLOG_SIZE_OPTIONS)).toBeLessThanOrEqual(maxPage)
    expect(SENDLOG_SIZE_OPTIONS).toContain(javaInt(LOG, 'DEFAULT_PAGE_SIZE'))
  })

  it('biçim: sunucunun teslim edilebilir adres kuralı', () => {
    expect(isDeliverable('ad.soyad@kurum.com.tr')).toBe(true)
    expect(isDeliverable(' a@b.co ')).toBe(true)
    expect(isDeliverable('a@b.c')).toBe(false)              // son parça ≥ 2
    expect(isDeliverable('ab.com')).toBe(false)
    expect(isDeliverable('a b@c.com')).toBe(false)
    expect(isDeliverable(`${'a'.repeat(250)}@b.com`)).toBe(false)   // > 254
    expect(isDeliverable('')).toBe(false)
    expect(isDeliverable(null)).toBe(false)
  })

  it('çözüm: boş = ek yok; virgül / noktalı virgül / boşluk ayırır; tekrar (büyük-küçük) tek; geçersiz adres adıyla; 11 farklı adres hata', () => {
    expect(parseExtraCc('')).toEqual({ addresses: [], error: null })
    expect(parseExtraCc('  \n ')).toEqual({ addresses: [], error: null })
    expect(parseExtraCc('a@x.com; b@y.com  A@X.com,\nc@z.org')).toEqual({ addresses: ['a@x.com', 'b@y.com', 'c@z.org'], error: null })
    expect(parseExtraCc('a@x.com, bozuk')).toEqual({ addresses: [], error: { key: 'te.send.ccInvalid', params: ['bozuk'] } })
    const ten = Array.from({ length: 10 }, (_, i) => `e${i}@x.com, E${i}@x.com`).join('; ')
    expect(parseExtraCc(ten).addresses).toHaveLength(10)
    const eleven = Array.from({ length: 11 }, (_, i) => `e${i}@x.com`).join(', ')
    expect(parseExtraCc(eleven).error).toEqual({ key: 'te.send.ccTooMany', params: [10, 11] })
    expect(parseExtraCc('x'.repeat(MAX_EXTRA_CC_INPUT + 1)).error.key).toBe('te.send.ccTooLong')
    // Uzun geçersiz öğe 80 karakterde kısalır (alan altı metni taşmasın)
    expect(parseExtraCc('y'.repeat(200)).error.params[0]).toHaveLength(81)
  })
})

describe('gönderim günlüğü modeli', () => {
  it('süzgeç değerleri sunucuyla aynı; bilinmeyen = all', () => {
    for (const k of SENDLOG_KINDS.filter((x) => x !== 'all')) expect(LOG).toContain(`k.put("${k}"`)
    for (const s of SENDLOG_STATUSES.filter((x) => x !== 'all')) expect(LOG).toContain(`"${s}"`)
    expect(normalizeSendLogKind('test')).toBe('test')
    expect(normalizeSendLogKind('garip')).toBe('all')
    expect(normalizeSendLogStatus('failed')).toBe('failed')
    expect(normalizeSendLogStatus(undefined)).toBe('all')
    expect(SENDLOG_KEYS).toEqual(['te_slp', 'te_slps', 'te_slk', 'te_sls'])
  })

  it('her etiket anahtarı TR ve EN sözlükte; bilinmeyen satır değerleri ham değer değil güvenli etiket', () => {
    for (const k of [...Object.values(SENDLOG_KIND_KEYS), ...Object.values(SENDLOG_STATUS_KEYS), ...Object.values(SENDLOG_ROLE_KEYS)]) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
    expect(sendLogStatusOf({ status: 'weird' })).toBe('failed')
    expect(sendLogStatusOf({ status: 'queued' })).toBe('queued')
    expect(sendLogKindOf({ kind: 'all' })).toBe('report')
    expect(sendLogKindOf({ kind: 'approval' })).toBe('approval')
    expect(sendLogRoleKey({ role: 'NOC' })).toBe('te.slog.role.other')
    expect(sendLogRoleKey({ role: 'APPROVER_INFO' })).toBe('te.slog.role.approverInfo')
    // Sunucunun rol kümesi istemcide karşılıklı (OTHER dahil)
    for (const r of ['TEAM', 'EXECUTIVE', 'SELF', 'APPROVER', 'APPROVER_INFO', 'REQUESTER']) expect(LOG).toContain(`"${r}"`)
  })
})
