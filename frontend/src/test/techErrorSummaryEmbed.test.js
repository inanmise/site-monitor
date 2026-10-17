import { describe, it, expect } from 'vitest'
import {
  embeddedSections, errorCountsBySection, hostOf, reportErrorSection, sanitizeHosts, templateDiff, templateErrors,
  templateErrorSection, withoutHost,
} from '../components/techerror/techErrorModel.js'
import { diffText } from '../components/techerror/TemplateVersions.jsx'

// Özet kartına gömülü bölümler (2026-10-17): `host` kuralı, temizleme, hata eşlemesi ve sürüm farkı — sunucudaki
// TechErrorTemplate.hostOf / embeddedSections ile aynı kural.
const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['Map {f:un-count}.'] },
    { id: 'un', kind: 'metrics', title: 'Map Yapılmamış Hatalar', host: 'exec', fields: [
      { id: 'un-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'un-note', label: 'Açıklama', type: 'textarea' },
    ] },
    { id: 'base', kind: 'metrics', title: 'Veriler', fields: [{ id: 'total', label: 'Toplam', type: 'number' }] },
    { id: 'img', kind: 'images', title: 'Görseller', slots: [{ id: 'a', label: 'A', width: 'half' }] },
  ],
}
const withSection = (tpl, id, fn) => ({ ...tpl, sections: tpl.sections.map((s) => (s.id === id ? fn(s) : s)) })

describe('hostOf / embeddedSections', () => {
  it('gösterge bölümü aynı şablondaki bir özete bağlıysa ev sahibi odur; özet olmayan hedef, bozuk kimlik, gösterge olmayan bölüm → null', () => {
    expect(hostOf(TPL, TPL.sections[1]).id).toBe('exec')
    expect(hostOf(TPL, TPL.sections[2])).toBeNull()
    expect(hostOf(TPL, { ...TPL.sections[1], host: 'base' })).toBeNull()
    expect(hostOf(TPL, { ...TPL.sections[1], host: 'yok' })).toBeNull()
    expect(hostOf(TPL, { ...TPL.sections[1], host: 'Büyük Harf!' })).toBeNull()
    expect(hostOf(TPL, { ...TPL.sections[1], host: 7 })).toBeNull()
    expect(hostOf(TPL, { ...TPL.sections[3], host: 'exec' })).toBeNull()
    expect(hostOf(null, TPL.sections[1])).toBeNull()
    expect(hostOf(TPL, null)).toBeNull()
    expect(embeddedSections(TPL, 'exec').map((s) => s.id)).toEqual(['un'])
    expect(embeddedSections(TPL, 'base')).toEqual([])
    expect(embeddedSections(TPL, null)).toEqual([])
    expect(embeddedSections(null, 'exec')).toEqual([])
  })
})

describe('withoutHost / sanitizeHosts', () => {
  it('withoutHost anahtarı kaldırır, diğer her şeyi korur', () => {
    const s = withoutHost(TPL.sections[1])
    expect(s).not.toHaveProperty('host')
    expect(s.fields).toBe(TPL.sections[1].fields)
    expect(withoutHost(null)).toEqual({})
  })

  it('bu değişiklikle silinen özetin bağları kalkar; önceden sarkan bağ kalır (düzenleyici uyarır); boş bağ anahtarsız; değişiklik yoksa aynı nesne', () => {
    const noSummary = { ...TPL, sections: TPL.sections.filter((s) => s.id !== 'exec') }
    const cleaned = sanitizeHosts(noSummary, TPL)
    expect(cleaned.sections.find((s) => s.id === 'un')).not.toHaveProperty('host')
    // Önceden de sarkıyordu → dokunulmaz (prev'de de özet değil)
    const dangling = withSection(TPL, 'un', (s) => ({ ...s, host: 'yok' }))
    expect(sanitizeHosts(dangling, dangling)).toBe(dangling)
    // prev verilmezse şablonda özete çıkmayan HER bağ kalkar
    expect(sanitizeHosts(dangling).sections.find((s) => s.id === 'un')).not.toHaveProperty('host')
    // Özetin türü değişti (artık özet değil) → bağ kalkar
    const retyped = withSection(TPL, 'exec', (s) => ({ id: 'exec', kind: 'text', title: 'Metin', text: '' }))
    expect(sanitizeHosts(retyped, TPL).sections.find((s) => s.id === 'un')).not.toHaveProperty('host')
    // Gömülü bölümün türü değişti → gösterge olmayan bölümde host kalmaz
    const unRetyped = withSection(TPL, 'un', (s) => ({ ...s, kind: 'images', slots: [] }))
    expect(sanitizeHosts(unRetyped, TPL).sections.find((s) => s.id === 'un')).not.toHaveProperty('host')
    // Boş / null bağ anahtarsız
    expect(sanitizeHosts(withSection(TPL, 'un', (s) => ({ ...s, host: '' })), TPL).sections[1]).not.toHaveProperty('host')
    expect(sanitizeHosts(withSection(TPL, 'un', (s) => ({ ...s, host: null })), TPL).sections[1]).not.toHaveProperty('host')
    // Değişiklik yok → aynı nesne
    expect(sanitizeHosts(TPL, TPL)).toBe(TPL)
    expect(sanitizeHosts(null)).toBeNull()
  })
})

describe('templateErrors / hata eşlemesi', () => {
  it('sarkan bağ şablon hatasıdır (kayıt engellenir); geçerli bağ değil', () => {
    expect(templateErrors(TPL)).not.toHaveProperty('section:un')
    const dangling = withSection(TPL, 'un', (s) => ({ ...s, host: 'yok' }))
    expect(templateErrors(dangling)['section:un']).toBe('te.err.hostMissing')
    expect(templateErrorSection('section:un', dangling)).toBe('un')      // şablon düzenleyicide bölümün KENDİ kartı
  })

  it('raporda gömülü alanın hatası özetin kartına sayılır (kapalı kartta sayı, kayıtta açılır); ayrı bölümün alanı kendine', () => {
    expect(reportErrorSection('values.un-count', TPL)).toBe('exec')
    expect(reportErrorSection('values.un-note', TPL)).toBe('exec')
    expect(reportErrorSection('values.total', TPL)).toBe('base')
    expect(reportErrorSection('text.exec', TPL)).toBe('exec')
    const counts = errorCountsBySection({ 'values.un-count': 'x', 'values.un-note': 'y', 'values.total': 'z', 'values.yok': 'w' },
      (k) => reportErrorSection(k, TPL))
    expect(counts.get('exec')).toBe(2)
    expect(counts.get('base')).toBe(1)
    expect(counts.has('un')).toBe(false)
    // Bağ sarkıyorsa alan kendi bölümünde sayılır (kartı da oradadır)
    const dangling = withSection(TPL, 'un', (s) => ({ ...s, host: 'yok' }))
    expect(reportErrorSection('values.un-count', dangling)).toBe('un')
  })
})

describe('templateDiff — gösterim yeri', () => {
  it('ayrı bölüm ↔ özet kartı tek "host" satırı (önce / sonra özetin başlığı, ayrı bölüm null, okunamayan ""); metni okunur', () => {
    const separate = withSection(TPL, 'un', (s) => withoutHost(s))
    const toHosted = templateDiff(separate, TPL).filter((d) => d.op === 'host')
    expect(toHosted).toEqual([{ group: 'section', op: 'host', label: 'Map Yapılmamış Hatalar', before: null, after: 'Yönetici Özeti' }])
    const toSeparate = templateDiff(TPL, separate).filter((d) => d.op === 'host')
    expect(toSeparate).toEqual([{ group: 'section', op: 'host', label: 'Map Yapılmamış Hatalar', before: 'Yönetici Özeti', after: null }])
    const dangling = withSection(TPL, 'un', (s) => ({ ...s, host: 'yok' }))
    expect(templateDiff(TPL, dangling).filter((d) => d.op === 'host')[0]).toMatchObject({ before: 'Yönetici Özeti', after: '' })
    expect(templateDiff(TPL, { ...TPL }).filter((d) => d.op === 'host')).toEqual([])
    const t = (k, ...a) => `${k}${a.length ? `(${a.join(',')})` : ''}`
    const r = (x) => x
    expect(diffText({ group: 'section', op: 'host', label: 'Map', before: null, after: 'Yönetici Özeti' }, t, r))
      .toBe('Map — te.ver.host: te.tpl.hostSeparate → te.tpl.hostIn(Yönetici Özeti)')
    expect(diffText({ group: 'section', op: 'host', label: 'Map', before: '', after: null }, t, r))
      .toBe('Map — te.ver.host: te.tpl.hostIn(—) → te.tpl.hostSeparate')
  })
})
