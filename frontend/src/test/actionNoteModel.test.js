import { describe, it, expect } from 'vitest'
import { TR, EN } from '../i18n/index.jsx'
import {
  NOTE_RULE, javaTrim, noteRuleState, isNoteValid, ACK_CHIPS, RESOLVE_CHIPS, chipsFor, applyTemplate, hasTemplate,
  contextFromAlert, contextFromIncident, levelMix, isLongMessage, focusReturnPlan, returnFocus,
} from '../components/incidents/actionNoteModel.js'
import { isNoteValid as dialogIsNoteValid, NOTE_RULE as DIALOG_RULE } from '../components/ui/Dialog.jsx'

/**
 * Gerekçe kuralı sunucunun `AlertActionNote`'uyla BİREBİR aynı olmalı — arayüz geçirip sunucu 400 verirse kullanıcı
 * yazdığı notla takılır. Vakalar `AlertActionNoteTest.java`'dakilerin aynısı + Java trim/split sınırları.
 */
describe('actionNoteRule — AlertActionNote aynası', () => {
  it('eşikler sunucuyla aynı; genel not penceresi (ui/Dialog) AYNI uygulamayı kullanır', () => {
    expect(NOTE_RULE).toEqual({ minWords: 3, minWordLen: 2, minChars: 10 })
    expect(DIALOG_RULE).toBe(NOTE_RULE)
    expect(dialogIsNoteValid).toBe(isNoteValid)
  })

  it('boş / null / yalnız boşluk reddedilir (emptyIsRejected)', () => {
    for (const v of [null, undefined, '', '     ', '\n\t ']) expect(isNoteValid(v)).toBe(false)
  })

  it('üç kelimeden az reddedilir (tooFewWordsRejected)', () => {
    expect(isNoteValid('planlı bakım')).toBe(false)
    expect(isNoteValid('yanlışalarmdı')).toBe(false)
  })

  it('geçiştiren kısa girdiler reddedilir: "a b c", "x y z q", "ok ok ok" (tokenGamingRejected)', () => {
    expect(isNoteValid('a b c')).toBe(false)
    expect(isNoteValid('x y z q')).toBe(false)
    const s = noteRuleState('ok ok ok')
    expect(s).toMatchObject({ chars: 8, words: 3, charsOk: false, wordsOk: true, valid: false })
  })

  it('9 karakter reddedilir, 10 karakter kabul edilir (eşik tam sınırda)', () => {
    expect(noteRuleState('ok ok okk')).toMatchObject({ chars: 9, valid: false })
    expect(noteRuleState('ok ok okkk')).toMatchObject({ chars: 10, words: 3, valid: true })
    // Kırpma sayılmaz: baştaki/sondaki boşluk karakter eşiğine katkı vermez
    expect(noteRuleState('  ok ok okk  ')).toMatchObject({ chars: 9, valid: false })
  })

  it('gerçek gerekçe kabul edilir (realReasonAccepted) + bilinen sınır "aaa bbb ccc" geçer', () => {
    expect(isNoteValid('planlı bakım kapsamında kapatıldı')).toBe(true)
    expect(isNoteValid('bilinen sorun takip ediliyor')).toBe(true)
    expect(isNoteValid('  düzeltme devrede doğrulandı  ')).toBe(true)
    expect(isNoteValid('aaa bbb ccc')).toBe(true)
  })

  it('Java trim: yalnız ≤ U+0020 kırpılır (kontrol karakteri kırpılır, NBSP KIRPILMAZ)', () => {
    expect(javaTrim('\u0001 abc \u0002')).toBe('abc')
    expect(javaTrim(' abc ')).toBe(' abc ')
    // JS trim burada 11 karakter derdi ve geçirirdi; Java 9 karakter görür → RED
    expect(noteRuleState('\u0001abc de fg\u0001')).toMatchObject({ chars: 9, valid: false })
  })

  it('Java split("\\\\s+"): NBSP kelime ayırıcı DEĞİL — JS \\s ile bölseydik geçerdi, sunucu reddeder', () => {
    const s = noteRuleState('aaaa bbbb cc')
    expect(s).toMatchObject({ chars: 12, words: 1, wordsOk: false, valid: false })
    // ASCII boşlukları (sekme, dikey sekme, form besleme) ayırıcıdır
    expect(noteRuleState('aaaa\tbbbb\u000bcc')).toMatchObject({ words: 3, valid: true })
  })
})

describe('hazır gerekçeler', () => {
  it('sahiplen ve çöz FARKLI çip kümeleri; her şablon kuralı TEK BAŞINA sağlar (TR + EN)', () => {
    expect(chipsFor('ack')).toBe(ACK_CHIPS)
    expect(chipsFor('resolve')).toBe(RESOLVE_CHIPS)
    const texts = (list, dict) => list.map((c) => dict[c.text])
    expect(texts(ACK_CHIPS, TR)).not.toEqual(texts(RESOLVE_CHIPS, TR))
    for (const dict of [TR, EN]) {
      for (const c of [...ACK_CHIPS, ...RESOLVE_CHIPS]) {
        expect(dict[c.label], `${c.label} sözlükte`).toBeTruthy()
        expect(isNoteValid(dict[c.text]), `${c.text} → "${dict[c.text]}"`).toBe(true)
      }
    }
    // Sahiplenmede mevcut alh.note.chip1..4, çözmede chip5 (düzeltme doğrulandı) kullanılır
    expect(ACK_CHIPS.map((c) => c.text)).toEqual(expect.arrayContaining(['alh.note.chip1', 'alh.note.chip2', 'alh.note.chip3', 'alh.note.chip4']))
    expect(RESOLVE_CHIPS.map((c) => c.text)).toContain('alh.note.chip5')
  })

  it('applyTemplate: boşa yazar, doluya noktalamayla ekler, varsa ÇIKARIR (geri al); tekrar eklemez', () => {
    const a = 'Bilinen sorun, takip ediliyor'
    const b = 'Planlı bakım kapsamında'
    expect(applyTemplate('', a)).toBe(a)
    expect(applyTemplate('  ', a)).toBe(a)
    expect(applyTemplate('kendi notum', a)).toBe(`kendi notum. ${a}`)
    expect(applyTemplate('kendi notum.', a)).toBe(`kendi notum. ${a}`)
    const both = applyTemplate(a, b)
    expect(both).toBe(`${a}. ${b}`)
    expect(hasTemplate(both, a) && hasTemplate(both, b)).toBe(true)
    expect(applyTemplate(both, a)).toBe(b)          // ilkini çıkar
    expect(applyTemplate(both, b)).toBe(a)          // sonuncuyu çıkar (ayraç da gider)
    expect(applyTemplate(a, a)).toBe('')            // tek şablon → boş
    expect(applyTemplate(`x. ${a}. y`, a)).toBe('x. y')
  })
})

describe('bağlam dönüştürücüler', () => {
  it('Alarm Geçmişi satırı → ortak bağlam (takım dizini adı, push özeti, e-posta/7-24 sayıları)', () => {
    const c = contextFromAlert({
      id: 5, domain: 'api.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', team_id: 3, created_at: '2026-09-28T08:00:00',
      message: 'HTTP 503', acknowledged: true, acknowledged_by: 'kisi.a', acknowledged_at: '2026-09-28T08:05:00',
      email_sent_count: 4, email_failed_count: 1, noc_call_count: 2,
    }, { teamName: 'Takım A', push: { sent: 3, failed: 0, skipped: 1 } })
    expect(c).toMatchObject({
      id: 5, kind: 'alert', title: 'api.example.com', alertType: 'HTTP_DOWN', level: 'CRITICAL', teamId: 3, teamName: 'Takım A',
      openedAt: '2026-09-28T08:00:00', message: 'HTTP 503', acknowledged: true, ackBy: 'kisi.a',
      mail: { sent: 4, failed: 1 }, push: { sent: 3, failed: 0 }, nocCalls: 2,
    })
    // Sunucu döndürmediyse sayı UYDURULMAZ; takımsız sertifika alarmı envanterin SY takımına düşer
    const bare = contextFromAlert({ id: 6, domain: 'x.example.com', sy_team_id: 9, sy_team_name: 'SY Takım' })
    expect(bare).toMatchObject({ mail: null, push: null, nocCalls: 0, teamId: 9, teamName: 'SY Takım', acknowledged: false })
  })

  it('Olaylar satırı → ortak bağlam (izleme adı başlık, farklıysa alan adı alt satır; sayılar yok)', () => {
    const c = contextFromIncident({
      id: 1, monitor: { name: 'Ödeme API', type: 'http' }, domain: 'pay.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH',
      team_id: 2, team_name: 'Takım A', started_at: '2026-09-28T07:00:00', message: 'timeout', acknowledged: false,
    })
    expect(c).toMatchObject({ kind: 'incident', title: 'Ödeme API', subtitle: 'pay.example.com', openedAt: '2026-09-28T07:00:00', mail: null, push: null })
    expect(contextFromIncident({ id: 2, domain: 'a.example.com' })).toMatchObject({ title: 'a.example.com', subtitle: null })
  })

  it('KENDİ olay satırı Alarm Geçmişi adlarıyla sahip + e-posta/push/7-24 taşır → aynı bağlam (push satırın push_summary\'sinden)', () => {
    const c = contextFromIncident({
      id: 7, monitor: { name: 'Ödeme API', type: 'http' }, domain: 'pay.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
      started_at: '2026-09-28T07:00:00', acknowledged: true, acknowledged_by: 'kisi.a', acknowledged_at: '2026-09-28T07:05:00',
      email_sent_count: 4, email_failed_count: 1, push_summary: { sent: 2, failed: 1, skipped: 1, other: 0 }, noc_call_count: 2,
      can_manage: true,
    })
    expect(c).toMatchObject({ ackBy: 'kisi.a', ackAt: '2026-09-28T07:05:00', mail: { sent: 4, failed: 1 }, push: { sent: 2, failed: 1 }, nocCalls: 2 })
    // Başka ekibin olayı: sunucu bu alanları hiç yazmaz → satırlar çizilmez (sıfır uydurulmaz)
    expect(contextFromIncident({ id: 8, domain: 'b.example.com', acknowledged: true, can_manage: false }))
      .toMatchObject({ ackBy: null, ackAt: null, mail: null, push: null, nocCalls: 0 })
    // Sıfır sayılar da sunucudan geldiyse korunur (Alarm Geçmişi gibi "E-posta: 0")
    expect(contextFromIncident({ id: 9, domain: 'c.example.com', email_sent_count: 0, email_failed_count: 0 }).mail).toEqual({ sent: 0, failed: 0 })
  })

  it('levelMix: KRİTİK → YÜKSEK → UYARI sırası, sayılar doğru', () => {
    expect(levelMix([{ level: 'WARNING' }, { level: 'CRITICAL' }, { level: 'WARNING' }, { level: 'HIGH' }, { level: 'critical' }]))
      .toEqual([{ level: 'CRITICAL', count: 2 }, { level: 'HIGH', count: 1 }, { level: 'WARNING', count: 2 }])
  })

  it('isLongMessage: uzun ya da çok satırlı', () => {
    expect(isLongMessage('HTTP 503')).toBe(false)
    expect(isLongMessage('x'.repeat(91))).toBe(true)
    expect(isLongMessage('a\nb')).toBe(true)
  })
})

describe('odak iadesi planı', () => {
  it('menü öğesinden açıldıysa menünün TETİĞİ; tetik kalkarsa aynı eylem satırının ilk düğmesi', () => {
    document.body.innerHTML = `
      <div data-alert-id="1"><div data-alert-actions="">
        <button id="ack">Onayla</button><button id="res">Çöz</button>
        <button id="kebab" aria-controls="m1">…</button>
      </div></div>
      <div role="menu" id="m1"><div role="menuitem" id="item" tabindex="-1">Onayla</div></div>`
    document.getElementById('item').focus()
    const viaMenu = focusReturnPlan(document.activeElement)
    expect(viaMenu.trigger.id).toBe('kebab')

    document.getElementById('ack').focus()
    const plan = focusReturnPlan(document.activeElement)
    expect(plan.trigger.id).toBe('ack')
    document.getElementById('ack').remove()          // sahiplenince "Onayla" kaybolur
    document.body.focus()
    expect(returnFocus(plan)).toBe(true)
    expect(document.activeElement.id).toBe('res')
    document.body.innerHTML = ''
  })
})
