import { describe, it, expect } from 'vitest'
import { collectGaps, gapsIncomplete, uncheckedCategories } from '../components/gaps/gapsModel.js'
import { uncheckedLines } from '../components/gaps/gapsText.js'
import { gapRows, gapsText } from '../components/gaps/gapsExport.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Eksikler penceresi — TAM denetlenemeyen kategoriler ve dışa aktarma (2026-11-17, inceleme): bir kaynak okunamadığında
 * kategori ve dışa aktarma "tamamlanacak bir şey yok" diyordu. Bilinmeyen ≠ eksik yok.
 */
const tOf = (dict) => (k, ...a) => {
  const v = dict[k]
  if (v == null) throw new Error(`eksik anahtar: ${k}`)
  return v.replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)]))
}
const t = tOf(TR)
const PROFILE = { email: 'ayse@kurum.com.tr', phone: '05551112233' }
const ok = (data) => ({ status: 'ok', data })
const DQ_EMPTY = ok({ team: { id: 4, name: 'Dijital Kanallar' }, rules: [] })
const base = (over = {}) => ({
  profile: PROFILE, prefs: { opt_out: false, min_level: 'WARNING' }, prefsStatus: 'ok',
  teams: [{ id: 4, name: 'Dijital Kanallar', dq: DQ_EMPTY, oncall: ok({ enabled: false, members: [] }) }],
  groups: ok([]), forwards: ok([]), channels: ok({ teams: [], channels: [] }), actions: ok({ items: [], total: 0 }),
  ...over,
})

describe('uncheckedCategories / gapsIncomplete', () => {
  it('her şey okunduysa hiçbir kategori eksik denetlenmiş değildir', () => {
    const g = collectGaps(base())
    expect(g.total).toBe(0)
    expect([...uncheckedCategories(g, { dq: 'yes' })]).toEqual([])
    expect(gapsIncomplete(g, { dq: 'yes' })).toBe(false)
  })

  it('kaynak → kategori: bildirim grupları okunamazsa YALNIZ takım; veri kalitesi okunamazsa takım + izlemeler + envanter', () => {
    const groupsFailed = collectGaps(base({ groups: { status: 'failed' } }))
    expect([...uncheckedCategories(groupsFailed, { dq: 'yes' })]).toEqual(['team'])
    const dqFailed = collectGaps(base({ teams: [{ id: 4, name: 'X', dq: { status: 'failed' }, oncall: ok({ enabled: false }) }] }))
    expect([...uncheckedCategories(dqFailed, { dq: 'yes' })].sort()).toEqual(['inventory', 'monitors', 'team'])
    const prefsFailed = collectGaps(base({ prefs: null, prefsStatus: 'failed' }))
    expect([...uncheckedCategories(prefsFailed, { dq: 'yes' })]).toEqual(['profile'])
    const actionsUnsupported = collectGaps(base({ actions: { status: 'unsupported' } }))
    expect([...uncheckedCategories(actionsUnsupported, { dq: 'yes' })]).toEqual(['tasks'])
  })

  it('yetki bilinmiyor ya da denetlenmeyen takım varsa takım / izleme / envanter tam değil; profil alanları gelmediyse profil', () => {
    const g = collectGaps(base())
    expect([...uncheckedCategories(g, { dq: 'unknown' })].sort()).toEqual(['inventory', 'monitors', 'team'])
    expect([...uncheckedCategories(g, { dq: 'yes', moreTeams: 3 })].sort()).toEqual(['inventory', 'monitors', 'team'])
    const noKeys = collectGaps(base({ profile: { username: 'ayse' } }))
    expect(uncheckedCategories(noKeys, { dq: 'yes' }).has('profile')).toBe(true)
    expect([...uncheckedCategories(null)]).toHaveLength(5)
  })
})

describe('dışa aktarma — okunamayan kaynak "eksik yok" yazmaz', () => {
  it('metin: hiç eksik yok ama kaynak okunamadı → "kesin değil" + Denetlenemeyenler bölümü (adıyla)', () => {
    const g = collectGaps(base({ groups: { status: 'failed' }, teams: [{ id: 4, name: 'Dijital Kanallar', dq: { status: 'failed' }, oncall: { status: 'failed' } }] }))
    const text = gapsText(g, t, { at: 0, dq: 'yes' })
    expect(text).not.toContain(TR['gaps.export.empty'])
    expect(text).toContain(TR['gaps.export.emptyIncomplete'])
    expect(text).toContain(`[${TR['gaps.export.uncheckedTitle']}]`)
    expect(text).toContain('Takım verisi okunamadı: Dijital Kanallar')
    expect(text).toContain('Okunamadı: Bildirim grupları')
    expect(text).toContain('Okunamadı: Nöbet çizelgesi (Dijital Kanallar)')
    expect(text).not.toMatch(/undefined|gaps\./)
  })

  it('metin: her şey okunduysa eskisi gibi "Tamamlanması gereken bir şey yok." ve Denetlenemeyenler bölümü yok', () => {
    const text = gapsText(collectGaps(base()), t, { at: 0, dq: 'yes' })
    expect(text).toContain(TR['gaps.export.empty'])
    expect(text).not.toContain(TR['gaps.export.uncheckedTitle'])
  })

  it('Excel satırları: okunamayanlar "Denetlenemedi" kategorisiyle eklenir (boş tablo "eksik yok" okunmaz); yetki / takım / iş notları', () => {
    const g = collectGaps(base({ actions: { status: 'unsupported' } }))
    const rows = gapRows(g, t, { dq: 'unknown', moreTeams: 1 })
    const notes = rows.filter((r) => r[0] === TR['gaps.export.uncheckedCat']).map((r) => r[2])
    expect(notes).toEqual([
      'Bu sürümde yapılamadı: Olay aksiyonları',
      TR['gaps.export.unchecked.permUnknown'],
      TR['gaps.export.unchecked.moreTeams.one'],
    ])
    expect(gapRows(collectGaps(base()), t, { dq: 'yes' })).toEqual([])
  })

  it('İngilizce: çoğul / tekil ve bilinmeyen eksik okunamazsa tek satır', () => {
    const tEn = tOf(EN)
    expect(uncheckedLines(collectGaps(base()), tEn, { dq: 'yes', moreTeams: 2 })).toEqual(['2 more teams were not checked.'])
    expect(uncheckedLines(null, tEn)).toEqual([EN['gaps.export.unchecked.all']])
    expect(uncheckedLines(collectGaps(base({ profile: { username: 'x' } })), tEn, { dq: 'no' }))
      .toEqual([EN['gaps.export.unchecked.profileUnknown'], EN['gaps.export.unchecked.noPermission']])
  })
})
