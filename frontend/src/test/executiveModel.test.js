import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  buildMonthlyCron, dailyPoints, formatValue, invalidEmails, isSummaryPayload, kpiView, localized, monthLabel,
  monthOptions, noteText, parseEmails, parseMonthlyCron, renewalSegments, verdictText, yFloor, fmtPct,
  distributionSegments, TLS_GRADE_SEGMENTS, CRYPTO_CATEGORY_SEGMENTS, CODE_LABEL_KEY,
  scopeOptions, statusCounts, STATUS_SEGMENTS, teamToForm, teamToBody, teamDirty, filterMembers, filterTeams, isTeamScope,
  TEAM_NOTE_KEY, TEAM_ID_RE,
} from '../components/executive/executiveModel.js'
import { summary, SCOPES, TEAM_DETAIL, TEAMS } from './helpers/executiveFixtures.js'

/** useT'nin saf karşılığı: eksik anahtarda anahtarın kendisi, {n} yer tutucuları doldurulur. */
const mk = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tTR = mk(TR)
const tEN = mk(EN)

describe('executiveModel — biçimleme', () => {
  it('yüzde dilde: TR "%99,95", EN "99.95%"; boş → —', () => {
    expect(formatValue(99.95, 'pct', { t: tTR, lang: 'tr' })).toBe('%99,95')
    expect(formatValue(99.95, 'pct', { t: tEN, lang: 'en' })).toBe('99.95%')
    expect(formatValue(null, 'pct', { t: tTR, lang: 'tr' })).toBe('—')
    expect(fmtPct(100, 'tr')).toBe('%100')
  })

  it('boş değerin anlamı türe göre: takımsız / gruplanmamış / atanmamış', () => {
    expect(formatValue(null, 'team', { t: tTR })).toBe('Takımsız')
    expect(formatValue(null, 'service', { t: tEN, lang: 'en' })).toBe('Ungrouped')
    expect(formatValue(null, 'tier', { t: tTR })).toBe('Atanmamış')
    expect(formatValue(2, 'tier', { t: tEN, lang: 'en' })).toBe('Tier 2')
  })

  it('puan farkı, yüzde değişim, dakika, gün, tarih, kodlu değerler', () => {
    expect(formatValue(-0.24, 'pp', { t: tTR, lang: 'tr' })).toBe('▼ 0,24 puan · geçen aya göre')
    expect(formatValue(0, 'pp', { t: tEN, lang: 'en' })).toBe('same as last month')
    expect(formatValue(50, 'pct_change', { t: tEN, lang: 'en' })).toBe('▲ 50% vs last month')
    expect(formatValue(75, 'minutes', { t: tTR })).toBe('1 sa 15 dk')
    expect(formatValue(30, 'minutes', { t: tEN })).toBe('30 min')
    expect(formatValue(1500, 'minutes', { t: tEN })).toBe('1 d 1 h')
    expect(formatValue(-3, 'days', { t: tTR })).toBe('3 gün önce doldu')
    expect(formatValue(0, 'days', { t: tEN })).toBe('today')
    expect(formatValue('2026-09-28', 'date', { t: tTR, lang: 'tr' })).toBe('28.09.2026')
    // UTC 21:30 → İstanbul ertesi gün
    expect(formatValue('2026-09-30T21:30:00', 'date', { t: tTR, lang: 'tr' })).toBe('01.10.2026')
    expect(formatValue('http', 'monitor_type', { t: tTR })).toBe(TR['nav.http'])
    expect(formatValue('ON_TIME', 'renewal_class', { t: tEN })).toBe('On time')
    expect(formatValue('NO_PLAN', 'overdue_reason', { t: tTR })).toBe('Plan yok')
    expect(formatValue('bad', 'status', { t: tTR })).toBe('Kritik')
  })

  it('başka özelliklerin kodları kendi i18n anahtarlarıyla (TLS nedeni, kripto kategori/bant, veri kalitesi kural/bant)', () => {
    expect(formatValue('TLS10_ENABLED', 'tls_reason', { t: tTR })).toBe(TR['tlsg.reason.TLS10_ENABLED.title'])
    expect(formatValue('TLS10_ENABLED', 'tls_reason', { t: tEN })).toBe(EN['tlsg.reason.TLS10_ENABLED.title'])
    expect(formatValue('LEGACY', 'crypto_category', { t: tEN })).toBe(EN['cinv.cat.LEGACY'])
    expect(formatValue('P1', 'pqc_band', { t: tTR })).toBe(TR['cinv.band.P1'])
    expect(formatValue('INV_NO_TEAM', 'dq_rule', { t: tEN })).toBe(EN['dq.rule.INV_NO_TEAM.title'])
    expect(formatValue('NEEDS_ATTENTION', 'dq_band', { t: tTR })).toBe('İyileştirilmeli')
    // bilinmeyen kod ham anahtar değil, kodun kendisi
    expect(formatValue('YENI_KURAL', 'dq_rule', { t: tEN })).toBe('YENI_KURAL')
    expect(formatValue(6.25, 'num', { t: tTR, lang: 'tr' })).toBe('6,25')
  })
})

describe('executiveModel — yeni bölümler (TLS notu, kripto, veri kalitesi)', () => {
  const s = summary()
  const sec = (k) => s.sections.find((x) => x.key === k)

  it('hükümler iki dilde, kod parametresi kendi dilinde biçimlenir', () => {
    const tls = sec('tls-grade')
    expect(verdictText(tEN, 'tls-grade', tls.verdicts[2], 'en'))
      .toBe(`The most common reason pulling grades below A: ${EN['tlsg.reason.TLS10_ENABLED.title']} (6 endpoints).`)
    expect(verdictText(tTR, 'tls-grade', tls.verdicts[0], 'tr')).toBe('Notlanan 40 uç nokta: A/A+ payı %70, C ve altı %7,5.')
    const dq = sec('data-quality')
    expect(verdictText(tEN, 'data-quality', dq.verdicts[0], 'en'))
      .toBe('Organisation data quality score 71 (Needs attention); 17 open findings.')
    expect(verdictText(tTR, 'data-quality', dq.verdicts[1], 'tr'))
      .toBe('Ay sonu görüntüsüne göre puan geçen aya kıyasla 7 puan düştü (78 → 71).')
  })

  it('göstergeler: ipucu parametreleri, ay sonu farkı çipi; ipucu yoksa boş', () => {
    const dq = sec('data-quality')
    const v = kpiView(tEN, 'data-quality', dq.kpis[1], 'en')
    expect(v.label).toBe('Month-end score')
    expect(v.delta).toBe('▼ 7 pts vs last month')
    expect(v.hint).toBe('Snapshot of 30/09/2026')
    expect(kpiView(tEN, 'data-quality', dq.kpis[0], 'en').hint).toBe('Needs attention · 17 open findings')
    expect(kpiView(tEN, 'tls-grade', sec('tls-grade').kpis[2], 'en').hint).toBeNull()
    expect(kpiView(tTR, 'crypto-readiness', sec('crypto-readiness').kpis[1], 'tr').hint).toBe('P2 5 · P3 10 · P4 13')
  })

  it('dağılım dilimleri: sabit sıra, yüzde, eksik / bozuk değer 0', () => {
    const segs = distributionSegments({ 'A+': 1, A: 3, F: 'x', B: -2 }, TLS_GRADE_SEGMENTS)
    expect(segs.map((x) => x.key)).toEqual(['A+', 'A', 'B', 'C', 'D', 'F'])
    expect(segs.map((x) => x.count)).toEqual([1, 3, 0, 0, 0, 0])
    expect(segs[1].pct).toBe(75)
    expect(segs[0].total).toBe(4)
    expect(distributionSegments(null, CRYPTO_CATEGORY_SEGMENTS)[0].total).toBe(0)
    expect(CRYPTO_CATEGORY_SEGMENTS.map((x) => x.key)).toEqual(['BROKEN', 'LEGACY', 'MODERN', 'PQC_READY', 'UNKNOWN'])
    expect(Object.keys(CODE_LABEL_KEY)).toEqual(['tls_reason', 'crypto_category', 'pqc_band', 'dq_rule', 'dq_band'])
  })
})

describe('executiveModel — metin (i18n + sunucu metnine düşüş)', () => {
  const s = summary()

  it('hüküm i18n anahtarından, biçimli parametrelerle (EN)', () => {
    const v = s.sections[0].verdicts[0]
    expect(verdictText(tEN, 'availability', v, 'en')).toBe('Organisation availability 99.95% — the 99.9% target was met.')
    expect(verdictText(tTR, 'availability', v, 'tr')).toBe('Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.')
  })

  it('bilinmeyen bölüm (yeni sağlayıcı) sunucunun Türkçe metnine düşer — ham anahtar görünmez', () => {
    const fut = s.sections.find((x) => x.key === 'future-section')
    expect(verdictText(tEN, fut.key, fut.verdicts[0], 'en')).toBe(fut.verdicts[0].text)
    expect(localized(tEN, 'exec.unknown.title', 'Sunucu metni')).toBe('Sunucu metni')
  })

  it('doldurulamayan yer tutucu kalmaz: parametresiz gelen ipucu sunucu metnine düşer', () => {
    const k = { code: 'on_time_pct', label: 'Zamanında yenileme', value: 40, format: 'pct', tone: 'warn',
      hint: 'Hedef: seviye bazlı yenileme süresi', hint_params: [] }
    expect(kpiView(tEN, 'renewals', k, 'en').hint).toBe('Hedef: seviye bazlı yenileme süresi')
    const withParam = { ...k, hint_params: [30] }
    expect(kpiView(tEN, 'renewals', withParam, 'en').hint).toBe('Target: at least 30 days before expiry')
  })

  it('gösterge görünümü: etiket, değer, fark çipi', () => {
    const k = s.sections[0].kpis[0]
    const v = kpiView(tTR, 'availability', k, 'tr')
    expect(v.label).toBe('Kurum erişilebilirliği')
    expect(v.value).toBe('%99,95')
    expect(v.delta).toBe('▼ 0,02 puan · geçen aya göre')
    expect(v.hint).toBe('Hedef %99,9')
  })

  it('not: bölüme özgü anahtar yoksa ortak exec.note.<kod>', () => {
    expect(noteText(tEN, 'tls-grade', { code: 'ERROR', text: 'x', params: [] }, 'en')).toBe(EN['exec.note.ERROR'])
  })
})

describe('executiveModel — yardımcılar', () => {
  it('yük doğrulaması', () => {
    expect(isSummaryPayload(summary())).toBe(true)
    expect(isSummaryPayload([])).toBe(false)
    expect(isSummaryPayload({ month: '2026-09' })).toBe(false)
  })

  it('ay etiketi ve seçenekleri (sunucu listesi yoksa bu ay + 12, İstanbul takvimi)', () => {
    expect(monthLabel('2026-09', 'tr')).toBe('Eylül 2026')
    expect(monthLabel('2026-09', 'en')).toBe('September 2026')
    const opts = monthOptions([], 'tr', new Date('2026-09-30T21:30:00Z'))   // İstanbul'da 1 Ekim
    expect(opts).toHaveLength(13)
    expect(opts[0]).toMatchObject({ value: '2026-10', current: true })
    expect(opts[12].value).toBe('2025-10')
  })

  it('cron ↔ gün/saat; kalıp dışı ifade özel kalır', () => {
    expect(parseMonthlyCron('0 0 9 1 * *')).toEqual({ custom: false, day: 1, time: '09:00' })
    expect(parseMonthlyCron('0 30 14 15 * *')).toEqual({ custom: false, day: 15, time: '14:30' })
    expect(parseMonthlyCron('0 0 10 * * FRIL').custom).toBe(true)
    expect(parseMonthlyCron('0 0 9 31 * *').custom).toBe(true)
    expect(buildMonthlyCron(15, '14:30')).toBe('0 30 14 15 * *')
    expect(buildMonthlyCron(40, '09:00')).toBe('0 0 9 28 * *')
  })

  it('adresler: tekil (harf duyarsız), geçersizler ayrı', () => {
    expect(parseEmails('a@x.com; A@X.com  b@y.org,')).toEqual(['a@x.com', 'b@y.org'])
    expect(invalidEmails('a@x.com, kotu, c@d')).toEqual(['kotu', 'c@d'])
  })

  it('yenileme dilimleri: sabit sıra, yüzde; günlük seri ve y tabanı hedefin altında', () => {
    const segs = renewalSegments({ ON_TIME: 2, LATE: 1, LAST_MINUTE: 1, AFTER_EXPIRY: 1 })
    expect(segs.map((x) => x.key)).toEqual(['ON_TIME', 'LATE', 'LAST_MINUTE', 'AFTER_EXPIRY'])
    expect(segs[0].pct).toBe(40)
    expect(segs[0].total).toBe(5)
    const pts = dailyPoints(summary().sections[0].data.daily, 'tr')
    expect(pts).toHaveLength(30)
    expect(pts[0]).toMatchObject({ label: '1', full: '01.09.2026' })
    const floor = yFloor(pts, 99.9)
    expect(floor).toBeLessThan(99.4)
    expect(floor).toBeGreaterThan(98)
  })
})

describe('takım kapsamı ve alıcı modeli (2026-10-10)', () => {
  const t = mk(TR)

  it('kapsam seçenekleri: kurum (görebiliyorsa) + takımlar "Takımlar" grubunda; kurum yoksa grup yok; kimlik metin', () => {
    expect(scopeOptions(SCOPES, t)).toEqual([
      { value: 'org', label: 'Kurum geneli' },
      { value: '5', label: 'Ödeme Ağ Geçidi Takımı', group: 'Takımlar' },
      { value: '6', label: 'Ağ Operasyon', group: 'Takımlar' },
    ])
    expect(scopeOptions({ org: false, teams: [{ id: 7, name: '' }] }, t)).toEqual([{ value: '7', label: '#7', group: undefined }])
    expect(scopeOptions(null, t)).toEqual([])
    expect(isTeamScope({ scope: { kind: 'team', team_id: 5 } })).toBe(true)
    expect(isTeamScope({ scope: { kind: 'org' } })).toBe(false)
    expect(isTeamScope(summary())).toBe(false)
    expect(TEAM_ID_RE.test('12')).toBe(true)
    expect(TEAM_ID_RE.test('1;DROP')).toBe(false)
  })

  it('bölüm durumu sayımı: bilinmeyen durum "veri yok"; dilim sırası kötüden iyiye', () => {
    expect(statusCounts([{ status: 'ok' }, { status: 'critical' }, { status: 'ok' }, { status: 'garip' }, {}]))
      .toEqual({ critical: 1, error: 0, attention: 0, ok: 2, no_data: 2 })
    expect(STATUS_SEGMENTS.map((s) => s.key)).toEqual(['critical', 'error', 'attention', 'ok', 'no_data'])
  })

  it('takım formu ↔ gövde: seçili üyeler liste sırasıyla, opak kimlikler METİN, ek adresler tekil + virgüllü', () => {
    const f = teamToForm(TEAM_DETAIL)
    expect(f).toEqual({ enabled: true, includeManager: true, includeTeamAdmins: true, userIds: ['u-201'], extra: '' })
    const body = teamToBody({ ...f, userIds: ['u-203', 'u-201', 'yabanci'], extra: 'a@x.com; A@X.com b@x.com' }, TEAM_DETAIL.members)
    expect(body).toEqual({ enabled: true, include_manager: true, include_team_admins: true,
      user_ids: ['u-201', 'u-203', 'yabanci'], extra_emails: 'a@x.com, b@x.com' })
    expect(teamDirty(f, TEAM_DETAIL)).toBe(false)
    expect(teamDirty({ ...f, userIds: ['u-201', 'u-202'] }, TEAM_DETAIL)).toBe(true)
    expect(teamDirty({ ...f, extra: ' ' }, TEAM_DETAIL)).toBe(false)          // boşluk değişiklik sayılmaz
    expect(teamDirty(f, null)).toBe(false)
    // varsayılanlar: alanlar yoksa müdür seçenekleri AÇIK, özet KAPALI
    expect(teamToForm({})).toEqual({ enabled: false, includeManager: true, includeTeamAdmins: true, userIds: [], extra: '' })
  })

  it('süzgeçler: üye ad/unvan/adres, takım adı — Türkçe büyük/küçük harf duyarsız', () => {
    expect(filterMembers(TEAM_DETAIL.members, 'AYŞE').map((m) => m.user_id)).toEqual(['u-201'])
    expect(filterMembers(TEAM_DETAIL.members, 'stajyer').map((m) => m.user_id)).toEqual(['u-203'])
    expect(filterMembers(TEAM_DETAIL.members, 'mehmet@').map((m) => m.user_id)).toEqual(['u-202'])
    expect(filterMembers(TEAM_DETAIL.members, '  ')).toHaveLength(3)
    expect(filterTeams(TEAMS.teams, 'ödeme').map((x) => x.team_id)).toEqual([5])
    expect(filterTeams(TEAMS.teams, 'AĞ')).toHaveLength(2)
  })

  it('uyarı kodlarının metni iki dilde var', () => {
    for (const key of Object.values(TEAM_NOTE_KEY)) {
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
  })
})
