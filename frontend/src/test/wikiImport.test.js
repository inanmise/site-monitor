import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import {
  WIKI_CAPS, WIKI_MAX_INPUT, boldHeadingText, cutTitle, everyWeekday, extractBareUrls, extractMarkdownLinks, findWeekday,
  hostPath, inferFrequency, parseWikiImport,
} from '../components/oncallroutine/wikiImport.js'
import { ID_RE } from '../components/oncallroutine/templateEditorModel.js'
import { fold } from '../utils/keywordFold.js'

/**
 * Nöbet rutini WİKİ İÇE AKTARIMI (2026-11-26, §7): wiki sayfasının markdown ya da HTML kopyası şablon bölümlerine çevrilir.
 * Başlık (`#`, kalın satır, girintisiz düz satır + madde) = bölüm; en sığ madde = görev; derin madde = bağlantı ya da açıklama
 * satırı. Örnek sayfa (`fixtures/oncallRoutineWiki.md|.html`) iki biçimde de AYNI sonucu verir: 5 bölüm, 21 görev, 17 bağlantı,
 * 1 açıklama satırı; http(s) dışı bağlantı düşer; haftalık / aylık bölümlerde sıklık çıkarılır.
 */
const MD = readFileSync(path.resolve(__dirname, 'fixtures/oncallRoutineWiki.md'), 'utf8')
const HTML = readFileSync(path.resolve(__dirname, 'fixtures/oncallRoutineWiki.html'), 'utf8')

const shape = (sections) => sections.map((s) => ({
  title: s.title,
  tasks: s.tasks.map((t) => ({
    title: t.title, description: t.description, links: t.links, frequency: t.frequency, weekday: t.weekday,
    monthly_rule: t.monthly_rule, active: t.active, required: t.required,
  })),
}))

describe('örnek wiki sayfası — markdown', () => {
  const r = parseWikiImport({ text: MD }, { lang: 'tr' })

  it('5 bölüm, 21 görev, 17 bağlantı, 1 açıklama satırı', () => {
    expect(r.error).toBeNull()
    expect(r.sections.map((s) => s.title)).toEqual([
      'Gün başlangıcı', 'Gün içi', 'Düzenli Gelen Mail İçerik kontrolleri', 'Haftalık periyotta', 'Aylık Geçiş',
    ])
    expect(r.sections.map((s) => s.tasks.length)).toEqual([8, 2, 7, 3, 1])
    expect(r.stats).toMatchObject({ sections: 5, tasks: 21, links: 17, description_lines: 1, dropped_links: 1 })
    // "# Nöbet Rutini" (görevsiz başlık) + açıklama paragrafı atlanır
    expect(r.stats.skipped_lines).toBe(2)
    expect(r.stats.capped).toEqual({ sections: 0, tasks: 0, links: 0, active: 0, description: 0 })
  })

  it('bağlantı etiketleri: markdown etiketi, kalan metin, yoksa ana makine + yol', () => {
    const cluster = r.sections[0].tasks[0]
    expect(cluster.title).toBe('Cluster Monitoring')
    expect(cluster.links).toHaveLength(6)
    expect(cluster.links[0]).toEqual({ label: 'Grafana küme genel bakış', url: 'https://grafana.example.internal/d/cluster-overview' })
    expect(cluster.links[4]).toEqual({
      label: 'console.example.internal/k8s/cluster/projects', url: 'https://console.example.internal/k8s/cluster/projects',
    })
    expect(cluster.links[5]).toEqual({ label: 'Kapasite panosu', url: 'https://capacity.example.internal/dashboard' })
  })

  it('bağlantısız derin madde açıklama satırı olur', () => {
    const t = r.sections[0].tasks[3]
    expect(t.title).toBe('Açık olay ve problem kayıtlarının kontrol edilmesi ve kapatılması')
    expect(t.links).toHaveLength(2)
    expect(t.description).toBe('• İlgili kayıtların mail takibi')
  })

  it('mailto bağlantısı düşer, görev kalır', () => {
    const t = r.sections[2].tasks[1]
    expect(t.title).toBe('Kapasite raporu maili')
    expect(t.links).toEqual([])
  })

  it('sıklık: haftalık bölüm WEEKLY (Her Cuma → 5, gün yok → 5, Pazartesi → 1), aylık bölüm MONTHLY ilk iş günü', () => {
    expect(r.sections[0].tasks.every((t) => t.frequency === 'DAILY')).toBe(true)
    expect(r.sections[3].tasks.map((t) => [t.frequency, t.weekday])).toEqual([['WEEKLY', 5], ['WEEKLY', 5], ['WEEKLY', 1]])
    expect(r.sections[4].tasks[0]).toMatchObject({ frequency: 'MONTHLY', monthly_rule: 'FIRST_WORKDAY', weekday: null })
  })

  it('kimlikler geçerli, benzersiz ve verilen kimliklerle çakışmaz', () => {
    const taken = new Set(['s-taken', 't-taken'])
    const again = parseWikiImport({ text: MD }, { taken })
    const ids = again.sections.flatMap((s) => [s.id, ...s.tasks.map((t) => t.id)])
    expect(ids.every((id) => ID_RE.test(id))).toBe(true)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).not.toContain('s-taken')
    // Çağıranın kümesi değişmez
    expect([...taken]).toEqual(['s-taken', 't-taken'])
  })

  it('görevler zorunlu ve etkin; sinyal / saat aralığı yok', () => {
    for (const t of r.sections.flatMap((s) => s.tasks)) {
      expect(t).toMatchObject({ required: true, active: true, signals: [], window_from: null, window_to: null })
    }
  })
})

describe('örnek wiki sayfası — HTML (Azure DevOps kopyası)', () => {
  const html = parseWikiImport({ html: HTML, text: MD }, { lang: 'tr' })
  const md = parseWikiImport({ text: MD }, { lang: 'tr' })

  it('HTML önceliklidir ve markdown ile aynı yapıyı verir', () => {
    expect(html.error).toBeNull()
    expect(shape(html.sections)).toEqual(shape(md.sections))
    expect(html.stats).toMatchObject({ sections: 5, tasks: 21, links: 17, description_lines: 1, dropped_links: 1 })
  })

  it('başlıksız h1, paragraf, tablo ve görsel atlanır; betik çalışmaz ve sayılmaz', () => {
    expect(html.stats.skipped_lines).toBe(4)
    expect(globalThis.__wikiImportScriptRan).toBeUndefined()
  })

  it('kalın paragraf (<p><strong>) bölüm başlığıdır', () => {
    expect(html.sections[1].title).toBe('Gün içi')
    expect(html.sections[1].tasks).toHaveLength(2)
  })
})

describe('markdown kuralları', () => {
  const titles = (r) => r.sections.map((s) => s.title)

  it('girintisiz düz satır + madde = bölüm; girintili düz satır başlık değildir', () => {
    const r = parseWikiImport({ text: 'Gün başlangıcı\n- A\n- B\n  Girintili satır\n- C' })
    expect(titles(r)).toEqual(['Gün başlangıcı'])
    expect(r.sections[0].tasks.map((t) => t.title)).toEqual(['A', 'B', 'C'])
    expect(r.stats.skipped_lines).toBe(1)
  })

  it('başlıktan önceki maddeler "Genel" / "General" bölümüne düşer', () => {
    expect(titles(parseWikiImport({ text: '- A\n## X\n- B' }, { lang: 'tr' }))).toEqual(['Genel', 'X'])
    expect(titles(parseWikiImport({ text: '- A' }, { lang: 'en' }))).toEqual(['General'])
  })

  it('kalın satır (sonunda iki nokta ile) bölüm başlığıdır', () => {
    expect(titles(parseWikiImport({ text: '**Gün içi:**\n- A' }))).toEqual(['Gün içi'])
    expect(titles(parseWikiImport({ text: '__Akşam__\n- A' }))).toEqual(['Akşam'])
  })

  it('numaralı ve onay kutulu maddeler görev olur; satır içi işaretler temizlenir', () => {
    const r = parseWikiImport({ text: '## X\n1. **Kalın** görev\n2) [ ] `kod` görevi\n- [x] Bitti' })
    expect(r.sections[0].tasks.map((t) => t.title)).toEqual(['Kalın görev', 'kod görevi', 'Bitti'])
  })

  it('http(s) dışı ve kullanıcı bilgili bağlantılar düşer, sayılır', () => {
    const r = parseWikiImport({ text: '## X\n- A\n  - [ftp](ftp://files.example.internal/a)\n  - [gizli](https://u:p@h.example.internal/)\n  - https://ok.example.internal/x' })
    expect(r.sections[0].tasks[0].links).toEqual([{ label: 'ok.example.internal/x', url: 'https://ok.example.internal/x' }])
    expect(r.stats.dropped_links).toBe(2)
  })

  it('aynı adres bir görevde bir kez', () => {
    const r = parseWikiImport({ text: '## X\n- A\n  - https://a.example.internal/\n  - [Tekrar](https://a.example.internal/)' })
    expect(r.sections[0].tasks[0].links).toHaveLength(1)
  })

  it('başlıksız madde bağlantısından görev adı çıkar', () => {
    const r = parseWikiImport({ text: '## X\n- [Grafana](https://g.example.internal/d/1)' })
    expect(r.sections[0].tasks[0]).toMatchObject({ title: 'Grafana', links: [{ label: 'Grafana', url: 'https://g.example.internal/d/1' }] })
  })

  it('görevsiz başlık bölüm olmaz ve atlanan satır sayılır', () => {
    const r = parseWikiImport({ text: '## Boş\n## Dolu\n- A' })
    expect(titles(r)).toEqual(['Dolu'])
    expect(r.stats.skipped_lines).toBe(1)
  })

  it('çok uzun satır atlanır', () => {
    const r = parseWikiImport({ text: `## X\n- ${'a'.repeat(5000)}\n- B` })
    expect(r.sections[0].tasks.map((t) => t.title)).toEqual(['B'])
    expect(r.stats.skipped_lines).toBe(1)
  })
})

describe('sıklık çıkarımı (Türkçe katlama)', () => {
  it.each([
    ['Gün başlangıcı', 'Kontrol', { frequency: 'DAILY', weekday: null }],
    ['HAFTALIK KONTROLLER', 'Kontrol', { frequency: 'WEEKLY', weekday: 5 }],
    ['Haftalık', 'Çarşamba toplantısı', { frequency: 'WEEKLY', weekday: 3 }],
    ['Haftalık — Salı', 'Kontrol', { frequency: 'WEEKLY', weekday: 2 }],
    ['Weekly checks', 'Review on thursday', { frequency: 'WEEKLY', weekday: 4 }],
    ['Gün içi', 'Rapor (her Pazartesi)', { frequency: 'WEEKLY', weekday: 1 }],
    ['Daily', 'Send report every Friday', { frequency: 'WEEKLY', weekday: 5 }],
    ['AYLIK GEÇİŞ', 'Devir', { frequency: 'MONTHLY', weekday: null }],
    ['Monthly', 'Devir', { frequency: 'MONTHLY', weekday: null }],
    ['Saylık', 'Kontrol', { frequency: 'DAILY', weekday: null }],
  ])('%s / %s', (section, task, want) => {
    expect(inferFrequency(section, task)).toMatchObject(want)
  })

  it('hafta günü yalnız sözcük başında (Cumartesi ≠ Cuma, "pazartesi" > "pazar")', () => {
    expect(findWeekday(fold('cumartesi nöbeti'))).toBe(6)
    expect(findWeekday(fold('pazartesi'))).toBe(1)
    expect(findWeekday(fold('pazar günü'))).toBe(7)
    expect(findWeekday(fold('ekcuma'))).toBeNull()
    expect(everyWeekday(fold('her cuma'))).toBe(5)
    expect(everyWeekday(fold('herhangi bir cuma'))).toBeNull()
  })
})

describe('sınırlar', () => {
  it('30 bölümden fazlası kesilir, sayılır', () => {
    const text = Array.from({ length: 35 }, (_, i) => `## B${i}\n- G${i}`).join('\n')
    const r = parseWikiImport({ text })
    expect(r.sections).toHaveLength(WIKI_CAPS.sections)
    expect(r.stats.capped).toMatchObject({ sections: 5, tasks: 5 })
  })

  it('bölüm başına 100 görev; 200 üstü etkin değil', () => {
    const sec = (n, k) => `## S${n}\n${Array.from({ length: k }, (_, i) => `- G${n}-${i}`).join('\n')}`
    const r = parseWikiImport({ text: [sec(1, 110), sec(2, 80), sec(3, 80)].join('\n') })
    expect(r.sections.map((s) => s.tasks.length)).toEqual([100, 80, 80])
    expect(r.stats.capped.tasks).toBe(10)
    const all = r.sections.flatMap((s) => s.tasks)
    expect(all.filter((t) => t.active)).toHaveLength(200)
    expect(r.stats.capped.active).toBe(60)
  })

  it('uzun görev adı sözcük sınırında "…" ile kesilir', () => {
    const long = Array.from({ length: 60 }, (_, i) => `sözcük${i}`).join(' ')
    const r = parseWikiImport({ text: `## X\n- ${long}` })
    const t = r.sections[0].tasks[0].title
    expect(t.length).toBeLessThanOrEqual(WIKI_CAPS.title)
    expect(t.endsWith('…')).toBe(true)
    expect(r.stats.truncated_titles).toBe(1)
  })

  it('bağlantı görev başına 20', () => {
    const subs = Array.from({ length: 25 }, (_, i) => `  - https://l${i}.example.internal/`).join('\n')
    const r = parseWikiImport({ text: `## X\n- A\n${subs}` })
    expect(r.sections[0].tasks[0].links).toHaveLength(20)
    expect(r.stats.capped.links).toBe(5)
  })

  it('1 MB üstü girdi TOO_LARGE, ayrıştırılmaz', () => {
    const r = parseWikiImport({ text: 'a'.repeat(WIKI_MAX_INPUT + 1) })
    expect(r.error).toBe('TOO_LARGE')
    expect(r.sections).toEqual([])
  })

  it('boş girdi boş sonuç', () => {
    expect(parseWikiImport({ text: '   ' })).toMatchObject({ sections: [], error: null })
    expect(parseWikiImport()).toMatchObject({ sections: [], error: null })
  })

  it('kötü niyetli girdi doğrusal biter', () => {
    const t0 = Date.now()
    parseWikiImport({ text: `## X\n- ${'['.repeat(3500)}\n- ${'*'.repeat(3500)}\n- ${'http'.repeat(800)}` })
    expect(extractMarkdownLinks('['.repeat(200_000)).links).toEqual([])
    expect(extractBareUrls('http'.repeat(100_000)).urls).toEqual([])
    expect(boldHeadingText(`**${'*'.repeat(100_000)}`)).toBeTruthy()
    expect(Date.now() - t0).toBeLessThan(3000)
  })
})

describe('yardımcılar', () => {
  it('extractMarkdownLinks metni etikete indirger', () => {
    expect(extractMarkdownLinks('Bak [Grafana](https://g.example.internal/x "başlık") ve [yalın]')).toEqual({
      text: 'Bak Grafana ve [yalın]', links: [{ label: 'Grafana', url: 'https://g.example.internal/x' }],
    })
  })

  it('extractBareUrls sondaki noktalamayı bırakır', () => {
    expect(extractBareUrls('Panel: https://a.example.internal/x, sonra')).toEqual({
      urls: ['https://a.example.internal/x'], rest: 'Panel: , sonra',
    })
    expect(extractBareUrls('xhttps://a.example.internal').urls).toEqual([])
  })

  it('boldHeadingText', () => {
    expect(boldHeadingText('**Başlık**')).toBe('Başlık')
    expect(boldHeadingText('**Başlık**:  ')).toBe('Başlık')
    expect(boldHeadingText('**Kalın** sonrası metin')).toBeNull()
    expect(boldHeadingText('****')).toBeNull()
  })

  it('cutTitle sözcük sınırında keser', () => {
    let cut = 0
    expect(cutTitle('kısa', 10, () => { cut += 1 })).toBe('kısa')
    expect(cutTitle('bir iki üç dört beş', 12, () => { cut += 1 })).toBe('bir iki üç…')
    expect(cut).toBe(1)
  })

  it('hostPath sorguyu atar, çözer', () => {
    expect(hostPath('https://a.example.internal/yol/%C3%A7?token=gizli#x')).toBe('a.example.internal/yol/ç')
    expect(hostPath('https://a.example.internal/')).toBe('a.example.internal')
    expect(hostPath('bozuk')).toBe('')
  })
})
