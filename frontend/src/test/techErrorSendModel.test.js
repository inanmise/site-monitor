import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MAX_EXTRA_CC, MAX_EXTRA_CC_INPUT, SENDLOG_KEYS, SENDLOG_KINDS, SENDLOG_KIND_KEYS, SENDLOG_ROLE_KEYS, SENDLOG_SIZE_OPTIONS,
  SENDLOG_STATUSES, SENDLOG_STATUS_KEYS, SENDLOG_REASON_KEYS, isDeliverable, normalizeSendLogKind, normalizeSendLogStatus, parseExtraCc,
  sendFailureKind, sendLogKindOf, sendLogMore, sendLogReasonKey, sendLogRoleKey, sendLogStatusOf, unsureSendOutcome,
  SEND_POLL_MS, SEND_POLL_MAX_MS, SEND_STATUS_FIELDS, sendPollState, withSendStatus,
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

  it('sebep kodları (inceleme 2026-10-18): istemci kümesi ve sırası Java REASON_CODES ile aynı; her metin TR ve EN, açıklayıcı; bilinmeyen kod → null', () => {
    const m = /REASON_CODES\s*=\s*List\.of\(([^)]*)\)/.exec(LOG)
    expect(m, 'REASON_CODES Java kaynağında').not.toBeNull()
    const javaCodes = [...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1])
    expect(javaCodes.length).toBeGreaterThan(5)
    expect(Object.keys(SENDLOG_REASON_KEYS)).toEqual(javaCodes)
    for (const [code, key] of Object.entries(SENDLOG_REASON_KEYS)) {
      expect(key).toBe(`te.slog.reason.${code}`)
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
      expect(TR[key].length, key).toBeGreaterThanOrEqual(25)
      expect(EN[key].length, key).toBeGreaterThanOrEqual(25)
    }
    expect(sendLogReasonKey({ reason: 'SMTP_FAILED' })).toBe('te.slog.reason.SMTP_FAILED')
    expect(sendLogReasonKey({ reason: null })).toBeNull()
    expect(sendLogReasonKey({ reason: 'toString' })).toBeNull()
    expect(sendLogReasonKey({ reason: 'YENI_KOD' })).toBeNull()
    expect(sendLogReasonKey(undefined)).toBeNull()
    // Ham durum metni artık istemciye gelmiyor: eski anahtar sözlükte yok
    expect(TR['te.slog.statusText']).toBeUndefined()
    expect(EN['te.slog.statusText']).toBeUndefined()
  })

  it('sığmayan adres sayısı: to_more / cc_more; sayı değilse 0', () => {
    expect(sendLogMore({ to_more: 7 }, 'to')).toBe(7)
    expect(sendLogMore({ cc_more: 2 }, 'cc')).toBe(2)
    expect(sendLogMore({ to_more: '3' }, 'to')).toBe(3)
    expect(sendLogMore({ to_more: -1 }, 'to')).toBe(0)
    expect(sendLogMore({ to_more: 'x' }, 'to')).toBe(0)
    expect(sendLogMore({}, 'cc')).toBe(0)
    expect(sendLogMore(null, 'to')).toBe(0)
  })
})

describe('gönderim hatası — pencere açık kalsın mı (inceleme 2026-10-18)', () => {
  it('yeniden denenebilir: yalnız 429 (PDF sırası / kişi başı sınır) — gönderim talebi alınmadan reddedildi → retry', () => {
    expect(sendFailureKind({ success: false, status: 429, code: 'HTTP_429' })).toBe('retry')
    expect(sendFailureKind({ success: false, status: 429, code: 'RATE_LIMITED' })).toBe('retry')
    expect(sendFailureKind({ success: false, status: 429, code: 'PDF_BUSY' })).toBe('retry')
    expect(sendFailureKind({ success: false, errorInfo: { status: 429 } })).toBe('retry')
  })
  it('sonucu bilinmeyen: ağ hatası / zaman aşımı (durum 0, ApiError dahil) → unsure', () => {
    expect(sendFailureKind({ success: false, status: 0, code: 'NETWORK_ERROR' })).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 0, code: 'REQUEST_TIMEOUT' })).toBe('unsure')
    expect(sendFailureKind(Object.assign(new Error('Sunucuya ulaşılamadı.'), { status: 0, code: 'OFFLINE' }))).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 0 })).toBe('unsure')
  })
  it('inceleme 2026-10-18 (3. tur): 409 SEND_IN_PROGRESS (gönderim O AN sürüyor — çoğu kez yanıtı kaybolan ilk istek) ve 503 (posta gitmiş, sonuç yazılamamış olabilir; pod ölürken ağ geçidi) "yeniden dene" DEĞİL → unsure', () => {
    expect(sendFailureKind({ success: false, status: 409, code: 'SEND_IN_PROGRESS' })).toBe('unsure')
    expect(sendFailureKind({ success: false, errorInfo: { status: 409, code: 'SEND_IN_PROGRESS' } })).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 503, code: 'DB_UNAVAILABLE' })).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 503, code: 'HTTP_503' })).toBe('unsure')      // ağ geçidinin HTML sayfası
    expect(sendFailureKind({ success: false, status: 503 })).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 504, code: 'HTTP_504' })).toBe('unsure')
    expect(sendFailureKind({ success: false, status: 502, code: 'HTTP_502' })).toBe('unsure')
    // Posta sunucusunun kesin reddi (502 MAIL_FAILED) kesin sonuçtur — bilinmeyen değil
    expect(sendFailureKind({ success: false, status: 502, code: 'MAIL_FAILED' })).toBeNull()
    // Sunucu kodları gerçekten var (Java kaynağı) — ad değişirse istemci sessizce "kesin hata" saymasın
    const handler = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/config/GlobalExceptionHandler.java'), 'utf8')
    expect(SENDER).toMatch(/CodedConflictException\("SEND_IN_PROGRESS"/)
    expect(handler).toMatch(/respond\(503, "DB_UNAVAILABLE"/)
  })
  it('kesin sonuç: başarı ve kalıcı retler pencereyi kapatır (null)', () => {
    expect(sendFailureKind({ success: true, data: {} })).toBeNull()
    for (const [status, code] of [[409, 'MAIL_DISABLED'], [409, 'TEAM_EMAIL_MISSING'], [409, 'APPROVAL_REQUIRED'], [502, 'MAIL_FAILED'],
      [403, 'FORBIDDEN'], [404, 'NOT_FOUND'], [400, 'VALIDATION_FAILED']]) {
      expect(sendFailureKind({ success: false, status, code })).toBeNull()
    }
    expect(sendFailureKind(null)).toBeNull()
    expect(sendFailureKind(new TypeError('x is not a function'))).toBeNull()
  })
  it('inceleme 2026-10-18 (4. tur): MAIL_FAILED dışındaki HER 5xx belirsiz — posta servise verildikten sonra düşen 500 de "gitmiş olabilir" (yeniden okuma ucuz)', () => {
    for (const [status, code] of [[500, 'INTERNAL_ERROR'], [500, undefined], [500, 'HTTP_500'], [501, 'HTTP_501'], [507, 'X'],
      [502, undefined], [599, 'WHATEVER']]) {
      expect(sendFailureKind({ success: false, status, ...(code ? { code } : {}) })).toBe('unsure')
    }
    expect(sendFailureKind({ success: false, errorInfo: { status: 500, code: 'INTERNAL_ERROR' } })).toBe('unsure')
    // Tek istisna: posta sunucusunun kesin reddi
    expect(sendFailureKind({ success: false, status: 502, code: 'MAIL_FAILED' })).toBeNull()
    expect(sendFailureKind({ success: false, errorInfo: { status: 502, code: 'MAIL_FAILED' } })).toBeNull()
    // 4xx kesin retler değişmedi
    expect(sendFailureKind({ success: false, status: 409, code: 'MAIL_DISABLED' })).toBeNull()
    expect(sendFailureKind({ success: false, status: 499 })).toBeNull()
  })
})

describe('sonucu bilinmeyen gönderimden sonra yeniden okunan rapor (inceleme 2026-10-18, 3. tur)', () => {
  const draft = { status: 'draft', send_state: null, last_sent_at: null, last_attempt_at: null, send_count: 0 }
  const sentOnce = { status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-01T08:00:00Z', last_attempt_at: '2026-10-01T08:00:00Z', send_count: 1 }

  it('okunamadı (null / nesne değil) → unknown: gönder kilitli kalır', () => {
    expect(unsureSendOutcome(draft, null)).toBe('unknown')
    expect(unsureSendOutcome(draft, undefined)).toBe('unknown')
    expect(unsureSendOutcome(draft, 'x')).toBe('unknown')
  })
  it('süren gönderim (taze talep) → sending; kuyrukta (durum queued ya da daha önce gönderilmiş raporda send_state QUEUED) → queued', () => {
    expect(unsureSendOutcome(draft, { ...draft, status: 'sending', send_state: 'SENDING' })).toBe('sending')
    expect(unsureSendOutcome(draft, { ...draft, status: 'queued', send_state: 'QUEUED' })).toBe('queued')
    expect(unsureSendOutcome(sentOnce, { ...sentOnce, status: 'sent', send_state: 'QUEUED' })).toBe('queued')
  })
  it('gönderim gitmiş: son gönderim zamanı değişti ya da gönderim sayısı arttı → sent (yeniden gönderimde de)', () => {
    expect(unsureSendOutcome(draft, { ...draft, status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-18T07:00:00Z', send_count: 1 })).toBe('sent')
    expect(unsureSendOutcome(sentOnce, { ...sentOnce, last_sent_at: '2026-10-18T07:00:00Z', send_count: 2 })).toBe('sent')
    // Zaman aynı biçimde yazılmamış olsa da sayaç artışı yeter
    expect(unsureSendOutcome(sentOnce, { ...sentOnce, send_count: 2 })).toBe('sent')
    // Önceki satır bilinmiyor ve rapor gönderilmiş → çift gönderimden kaçınılır
    expect(unsureSendOutcome(null, sentOnce)).toBe('sent')
  })
  it('açıkça gitmemiş: son gönderim aynı, süren / kuyrukta gönderim yok (deneme başarısız bittiyse de) → notSent', () => {
    expect(unsureSendOutcome(draft, draft)).toBe('notSent')
    expect(unsureSendOutcome(draft, { ...draft, send_state: 'FAILED', last_attempt_at: '2026-10-18T07:00:00Z' })).toBe('notSent')
    expect(unsureSendOutcome(sentOnce, { ...sentOnce, status: 'changed' })).toBe('notSent')
    // 10 dakikadan eski (bayat) talep — bu denemenin değil
    expect(unsureSendOutcome(sentOnce, { ...sentOnce, status: 'sent', send_state: 'SENDING' })).toBe('notSent')
    // Sayaç yoksa (eski sunucu) yalnız zaman karşılaştırılır
    expect(unsureSendOutcome({ status: 'draft' }, { status: 'draft' })).toBe('notSent')
  })

  describe('inceleme 2026-10-18 (4. tur): tıklamadan ÖNCE kuyrukta bekleyen gönderim — eski kuyruk bu gönderimin işareti değil', () => {
    const queuedBefore = { status: 'sent', send_state: 'QUEUED', last_sent_at: '2026-10-01T08:00:00Z', last_attempt_at: '2026-10-17T09:00:00Z', send_count: 1 }
    const queuedDraft = { status: 'queued', send_state: 'QUEUED', last_sent_at: null, last_attempt_at: '2026-10-17T09:00:00Z', send_count: 0 }
    // [ad, önceki, okunan, beklenen]
    const TABLE = [
      // Bu gönderim sunucuya hiç ulaşmadı, eski kuyruk değişmedi → 'queued' DEĞİL
      ['eski kuyruk değişmedi (gönderilmiş rapor)', queuedBefore, { ...queuedBefore }, 'notSent'],
      ['eski kuyruk değişmedi (hiç gönderilmemiş rapor)', queuedDraft, { ...queuedDraft }, 'notSent'],
      // Okuma eski kuyruğu sonuçlandırdı (confirmQueued: last_sent_at + send_count, last_attempt_at AYNI) → 'sent' DEĞİL
      ['okuma eski kuyruğu teslim etti', queuedBefore,
        { ...queuedBefore, status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-17T09:00:00Z', send_count: 2 }, 'notSent'],
      ['okuma eski kuyruğu teslim etti (taslak)', queuedDraft,
        { ...queuedDraft, status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-17T09:00:00Z', send_count: 1 }, 'notSent'],
      // Okuma eski kuyruğu başarısız buldu (failQueued) → bu gönderim yine olmamış
      ['okuma eski kuyruğu başarısız buldu', queuedBefore, { ...queuedBefore, send_state: 'FAILED' }, 'notSent'],
      // Bu gönderim sonuçlandı: last_attempt_at damgalandı → sonuç send_state'ten
      ['bu gönderim gitti (finishSent)', queuedBefore,
        { ...queuedBefore, send_state: 'SENT', last_sent_at: '2026-10-18T07:00:00Z', last_attempt_at: '2026-10-18T07:00:01Z', send_count: 3 }, 'sent'],
      ['bu gönderim kuyruğa alındı (finishQueued)', queuedBefore, { ...queuedBefore, last_attempt_at: '2026-10-18T07:00:01Z' }, 'queued'],
      ['bu gönderim kuyruğa alındı, okuma teslim etti', queuedDraft,
        { ...queuedDraft, status: 'sent', send_state: 'SENT', last_sent_at: '2026-10-18T07:00:00Z', last_attempt_at: '2026-10-18T07:00:01Z', send_count: 1 }, 'sent'],
      // Gönderimin içinde eski kuyruk teslim edildi, sonra BU deneme başarısız (finishNotSent) → 'sent' DEĞİL
      ['eski kuyruk teslim + bu deneme başarısız', queuedBefore,
        { ...queuedBefore, send_state: 'FAILED', last_sent_at: '2026-10-17T09:00:00Z', last_attempt_at: '2026-10-18T07:00:01Z', send_count: 2 }, 'notSent'],
      ['bu deneme atlandı (finishNotSent SKIPPED)', queuedBefore, { ...queuedBefore, send_state: 'SKIPPED', last_attempt_at: '2026-10-18T07:00:01Z' }, 'notSent'],
      // Süren gönderim her zaman önce
      ['süren gönderim', queuedBefore, { ...queuedBefore, status: 'sending', send_state: 'SENDING' }, 'sending'],
    ]
    it.each(TABLE)('%s', (_name, before, after, expected) => {
      expect(unsureSendOutcome(before, after)).toBe(expected)
    })
    it('sunucu kuralı gerçekten böyle: finishSent / finishQueued / finishNotSent last_attempt_at damgalar, confirmQueued / failQueued / releaseClaim damgalamaz; rapor satırı alanı taşır', () => {
      const repo = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/repository/TechErrorReportRepository.java'), 'utf8')
      const queryOf = (method) => {
        const i = repo.indexOf(` ${method}(`)
        expect(i).toBeGreaterThan(0)
        const q = repo.lastIndexOf('@Query(', i)
        return repo.slice(q, i)
      }
      for (const m of ['finishSent', 'finishQueued', 'finishNotSent']) expect(queryOf(m)).toMatch(/r\.lastAttemptAt = :at/)
      for (const m of ['confirmQueued', 'failQueued', 'releaseClaim']) expect(queryOf(m)).not.toMatch(/lastAttemptAt/)
      const ctrl = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/controller/TechErrorReportController.java'), 'utf8')
      expect(ctrl).toContain('m.put("last_attempt_at", iso(r.getLastAttemptAt()));')
    })
  })
})

describe('süren / kuyruktaki gönderimin yoklaması (inceleme 2026-10-18, 4. tur)', () => {
  it('sınırlar: 10 sn arayla, en çok 10 dk', () => {
    expect(SEND_POLL_MS).toBe(10_000)
    expect(SEND_POLL_MAX_MS).toBe(10 * 60_000)
  })
  it('durum: sürüyor / kuyrukta → pending; SENT → sent; FAILED / SKIPPED → notSent; bayat talep / durum yok → unknown; okunamadı → null', () => {
    expect(sendPollState({ status: 'sending', send_state: 'SENDING' })).toBe('pending')
    expect(sendPollState({ status: 'queued', send_state: 'QUEUED' })).toBe('pending')
    expect(sendPollState({ status: 'sent', send_state: 'QUEUED' })).toBe('pending')
    expect(sendPollState({ status: 'sent', send_state: 'SENT' })).toBe('sent')
    expect(sendPollState({ status: 'changed', send_state: 'SENT' })).toBe('sent')
    expect(sendPollState({ status: 'draft', send_state: 'FAILED' })).toBe('notSent')
    expect(sendPollState({ status: 'sent', send_state: 'SKIPPED' })).toBe('notSent')
    expect(sendPollState({ status: 'sent', send_state: 'SENDING' })).toBe('unknown')
    expect(sendPollState({ status: 'draft', send_state: null })).toBe('unknown')
    expect(sendPollState(null)).toBeNull()
    expect(sendPollState('x')).toBeNull()
  })
  it('yalnız gönderim alanları işlenir: sürüm, başlık, onay ve yanıtta olmayan alanlar değişmez', () => {
    const cur = { id: 100, version: 7, title: 'Eylül', status: 'sending', send_state: 'SENDING', send_count: 1, approval: { state: 'approved' }, last_sent_to: 'a@x.com' }
    const fresh = { id: 100, version: 9, title: 'BAŞKA', status: 'sent', send_state: 'SENT', send_count: 2, last_sent_at: '2026-10-18T07:00:00Z', approval: { state: 'stale' } }
    const out = withSendStatus(cur, fresh)
    expect(out).toEqual({ ...cur, status: 'sent', send_state: 'SENT', send_count: 2, last_sent_at: '2026-10-18T07:00:00Z' })
    expect(out.version).toBe(7)
    expect(cur.status).toBe('sending')                              // giriş değişmez
    expect(SEND_STATUS_FIELDS).not.toContain('version')
    expect(withSendStatus(cur, null)).toBe(cur)
  })
})
