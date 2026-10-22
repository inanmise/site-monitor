import { describe, it, expect } from 'vitest'
import { sendStatusClass, sendOutcome, historyBadge, sendStatusText } from '../components/admin/reportSendStatus.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Rapor e-postası gönderim sonucu (20.166 bugfix, UIA-C2 / K9). Eskiden `success:true` her durumda "gönderildi" sayılıyor,
 * SMTP bozukken yeşil bantta ham "FAILED: …", e-posta kanalı kapalıyken "test gönderildi" yazılıyordu.
 * Kural (CLAUDE.md checklist #8): SKIPPED ≠ FAILED ≠ SENT; kuyruk (QUEUED) teslim değildir.
 */
const t = (k) => EN[k] ?? k

describe('reportSendStatus — sınıflandırma', () => {
  it.each([
    ['SENT', 'sent'], ['sent', 'sent'], ['QUEUED_RETRY 2/5', 'queued'], ['SKIPPED_DISABLED', 'skipped'],
    ['SKIPPED: pasif kullanıcı', 'skipped'], ['DISABLED', 'skipped'], ['NO_RECIPIENT', 'noRecipient'],
    ['FAILED: Connection refused', 'failed'], ['ALREADY_SENT', 'skipped'], ['', 'unknown'], [null, 'unknown'],
  ])('%s → %s', (raw, cls) => {
    expect(sendStatusClass(raw)).toBe(cls)
  })
})

describe('reportSendStatus — yanıt sonucu (eski + K9 sunucu)', () => {
  it('eski sunucu: success:true ama durum SKIPPED → atlandı (başarı DEĞİL)', () => {
    const o = sendOutcome({ success: true, data: { status: 'SKIPPED_DISABLED' } }, t)
    expect(o).toMatchObject({ cls: 'skipped', tone: 'warning', raw: 'SKIPPED_DISABLED' })
    expect(o.text).toBe(EN['rsend.status.skipped'])
  })
  it('K9 sunucu: success:false + message SKIPPED → yine atlandı (başarısız değil)', () => {
    expect(sendOutcome({ success: false, message: 'SKIPPED: pasif kullanıcı' }, t).cls).toBe('skipped')
  })
  it('FAILED → başarısız, ham SMTP metni yalnız raw', () => {
    const o = sendOutcome({ success: true, data: { status: 'FAILED: 535 auth' } }, t)
    expect(o.cls).toBe('failed')
    expect(o.text).not.toContain('535')
    expect(o.raw).toBe('FAILED: 535 auth')
  })
  it('durum yok: success → gönderildi; doğrulama hatası (400 error) → başarısız, sunucu metni', () => {
    expect(sendOutcome({ success: true, data: {} }, t).cls).toBe('sent')
    const o = sendOutcome({ success: false, error: 'Geçerli bir e-posta adresi gerekli' }, t)
    expect(o).toMatchObject({ cls: 'failed', text: 'Geçerli bir e-posta adresi gerekli' })
  })
  it('K9: data.outcome varsa ham durumdan ÖNCE gelir (eski sunucuda yok → durumdan)', () => {
    expect(sendOutcome({ success: true, data: { status: 'QUEUED_RETRY 1/5', outcome: 'QUEUED' } }, t).cls).toBe('queued')
    expect(sendOutcome({ success: true, data: { status: 'SENT', outcome: 'NO_RECIPIENT' } }, t).cls).toBe('noRecipient')
    expect(sendOutcome({ success: true, data: { status: 'SKIPPED_DISABLED', outcome: 'SKIPPED' } }, t)).toMatchObject({ cls: 'skipped', raw: 'SKIPPED_DISABLED' })
  })
  it('kuyruk bilgi tonudur', () => {
    expect(sendOutcome({ success: true, data: { status: 'QUEUED_RETRY' } }, t).tone).toBe('info')
  })
})

describe('reportSendStatus — arşiv rozeti ve metinler', () => {
  it('rozet etiketleri iki dilde var; bilinmeyen sınıf ham kodu gösterir', () => {
    for (const st of ['SENT', 'QUEUED', 'SKIPPED', 'NO_RECIPIENT', 'FAILED']) {
      expect(historyBadge(st, (k) => EN[k] ?? k).label).not.toMatch(/^rsend\./)
      expect(historyBadge(st, (k) => TR[k] ?? k).label).not.toMatch(/^rsend\./)
    }
    expect(historyBadge('SENT', t)).toMatchObject({ tone: 'success', cls: 'sent' })
    expect(historyBadge('FAILED', t)).toMatchObject({ tone: 'danger', cls: 'failed' })
  })
  it('her sınıfın açıklayıcı metni iki dilde', () => {
    for (const cls of ['sent', 'queued', 'skipped', 'noRecipient', 'failed', 'unknown']) {
      expect(sendStatusText(cls, (k) => EN[k] ?? k)).not.toMatch(/^rsend\./)
      expect(sendStatusText(cls, (k) => TR[k] ?? k)).not.toMatch(/^rsend\./)
    }
  })
})
