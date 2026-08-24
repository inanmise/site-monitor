import { describe, it, expect } from 'vitest'
import { buildScriptSourceOptions } from '../utils/scriptSourceOptions.js'
import { CATEGORY_ORDER } from '../utils/templateCategories.js'

// i18n yerine kimlik fonksiyonu: anahtarin KENDISI etiket olur → gruplari anahtardan dogrulariz.
const t = (k) => k

const builtin = (id, category, name) =>
  ({ id, select_token: `b${id}`, name, scope: 'general', builtin: true, category })

describe('Script kaynagi secenekleri — yerlesikler KATEGORIYE gore dallanir', () => {
  // Yerlesik katalog 100 sablon (10 kategori x 10). Hepsi tek bir "Yerlesik sablonlar" basligi
  // altina dokulunce bir script'in HANGI kategoriden geldigi hic gorunmuyordu ve liste 100 satir
  // uzunlugundaydi (kullanici bildirimi 2026-08-24).

  it('her yerlesik kendi KATEGORI dalina duser, tek yigin baslik ALTINDA degil', () => {
    const opts = buildScriptSourceOptions({
      templates: [builtin(1, 'identity', 'Login'), builtin(2, 'availability', 'Anasayfa')], t,
    })

    const byLabel = Object.fromEntries(opts.filter(o => o.value).map(o => [o.label, o.group]))
    expect(byLabel['Login']).toBe('tpl.cat.identity')
    expect(byLabel['Anasayfa']).toBe('tpl.cat.availability')
    expect(Object.values(byLabel)).not.toContain('scripted.srcGroupTemplates')
  })

  it('kategori sirasi CATEGORY_ORDER — alfabetik DEGIL (izlemeye baslama yolu)', () => {
    // 'availability' alfabetik olarak once gelir ama sira anlamli: once ayakta mi, sonra
    // girilebiliyor mu... Ters sirada verip cikti sirasinin KAYNAKTAN geldigini kanitliyoruz.
    const reversed = [...CATEGORY_ORDER].reverse().map((c, i) => builtin(i + 1, c, `S-${c}`))
    const opts = buildScriptSourceOptions({ templates: reversed, t })

    const groupsInOrder = []
    for (const o of opts) if (o.group && o.group !== groupsInOrder.at(-1)) groupsInOrder.push(o.group)
    expect(groupsInOrder).toEqual(CATEGORY_ORDER.map(c => 'tpl.cat.' + c))
  })

  it('AYNI kategorinin satirlari ARDISIK — bitisiklik sozlesmesi (baslik iki kez basilmaz)', () => {
    const opts = buildScriptSourceOptions({
      templates: [builtin(1, 'identity', 'A'), builtin(2, 'search', 'B'), builtin(3, 'identity', 'C')], t,
    })

    const seen = []
    for (const o of opts) if (o.group && o.group !== seen.at(-1)) seen.push(o.group)
    expect(seen.length).toBe(new Set(seen).size)   // hicbir baslik tekrarlamiyor
  })

  it('KAPSAM kaybolmuyor: kayitli/takim/genel kendi basligini korur', () => {
    const opts = buildScriptSourceOptions({
      savedScripts: [{ id: 7, name: 'kendi-scriptim' }],
      templates: [
        { id: 1, select_token: 't1', name: 'Takim sablonu', scope: 'team', category: 'identity' },
        { id: 2, select_token: 'g1', name: 'Genel sablon', scope: 'general', builtin: false, category: 'identity' },
        builtin(3, 'identity', 'Yerlesik'),
      ],
      savedSourceId: 7, t,
    })
    const g = Object.fromEntries(opts.filter(o => o.value).map(o => [o.label.split(' ')[0], o.group]))

    expect(g['kendi-scriptim']).toBe('scripted.srcGroupSaved')
    expect(g['Takim']).toBe('scripted.srcGroupMyTeam')
    expect(g['Genel']).toBe('scripted.srcGroupGeneral')
    expect(g['Yerlesik']).toBe('tpl.cat.identity')   // yalniz yerlesik kategoriye dallanir
  })

  it('BILINMEYEN kategori sessizce DUSMEZ — eski baslik altinda toplanir', () => {
    // Katalog buyurse ve CATEGORY_ORDER guncellenmezse sablon secilemez hale gelirdi.
    const opts = buildScriptSourceOptions({
      templates: [builtin(1, 'yeni-kategori', 'Kayip'), builtin(2, null, 'Kategorisiz')], t,
    })

    const labels = opts.filter(o => o.group === 'scripted.srcGroupTemplates').map(o => o.label)
    expect(labels).toEqual(['Kayip', 'Kategorisiz'])
  })
})
