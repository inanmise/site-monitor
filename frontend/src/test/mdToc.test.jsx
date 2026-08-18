import { describe, it, expect } from 'vitest'
import { slugify, parseToc, duplicateSlugs } from '../utils/mdToc.js'

/**
 * Kılavuz TOC yardımcısı — HelpPage ve PDF üreticisi AYNI çapaları üretmek zorunda.
 * Buradaki davranış değişirse uygulamadaki içindekiler ile PDF'teki bağlantılar ayrışır.
 */
describe('slugify', () => {
  it('Türkçe harfleri ASCII karşılıklarına indirger', () => {
    expect(slugify('Yönetici Özeti')).toBe('yonetici-ozeti')
    expect(slugify('Sertifika Kontrol Akışı')).toBe('sertifika-kontrol-akisi')
    expect(slugify('Bakım Pencereleri')).toBe('bakim-pencereleri')
    expect(slugify('Güvenlik Modeli')).toBe('guvenlik-modeli')
  })

  it('numaralı başlıkta noktalama düşer, boşluk tireye döner', () => {
    expect(slugify('1. Yönetici Özeti')).toBe('1-yonetici-ozeti')
    expect(slugify('4. Mimari ve Teknoloji Yığını')).toBe('4-mimari-ve-teknoloji-yigini')
  })

  it('kılavuzdaki elle yazılmış İçindekiler çapalarıyla uyuşur', () => {
    // whitepaper.md İçindekiler bloğu bu slug'lara link veriyor — bozulursa TOC kırılır.
    expect(slugify('10. İzleme Türleri')).toBe('10-izleme-turleri')
    expect(slugify('21. Terimler Sözlüğü')).toBe('21-terimler-sozlugu')
  })
})

describe('parseToc', () => {
  it('###e kadar okur, #### hariç tutulur', () => {
    const md = '# Bir\n## İki\n### Üç\n#### Dört\n'
    expect(parseToc(md).map(i => i.text)).toEqual(['Bir', 'İki', 'Üç'])
    expect(parseToc(md).map(i => i.level)).toEqual([1, 2, 3])
  })

  it('başlıktan kalın ve backtick işaretlerini soyar', () => {
    const md = '## **Kalın** ve `kod`\n'
    expect(parseToc(md)[0].text).toBe('Kalın ve kod')
  })

  it('kod çiti içindeki # satırını TOC girdisi saymaz', () => {
    // Regresyon: yaml/shell örneklerindeki yorum satırları hayalet TOC girdisi üretiyordu.
    const md = [
      '## Gerçek Başlık',
      '',
      '```yaml',
      '# Yalnızca /tmp yazılabilir (emptyDir)',
      'readOnlyRootFilesystem: true',
      '```',
      '',
      '## İkinci Başlık',
    ].join('\n')
    expect(parseToc(md).map(i => i.text)).toEqual(['Gerçek Başlık', 'İkinci Başlık'])
  })

  it('~~~ çitini de tanır ve farklı işaretle kapanmaz', () => {
    const md = '~~~\n# çit içi\n```\n# hâlâ çit içi\n~~~\n## Dışarısı\n'
    expect(parseToc(md).map(i => i.text)).toEqual(['Dışarısı'])
  })

  it('kapanmamış çitten sonrasını başlık saymaz', () => {
    const md = '## Önce\n```\n# sonsuza dek çit içi\n## bu da\n'
    expect(parseToc(md).map(i => i.text)).toEqual(['Önce'])
  })

  it('boş/geçersiz girdide patlamaz', () => {
    expect(parseToc('')).toEqual([])
    expect(parseToc(null)).toEqual([])
    expect(parseToc(undefined)).toEqual([])
  })

  it('her girdide level, text ve slug id döner', () => {
    expect(parseToc('## Bakım Pencereleri\n')).toEqual([
      { level: 2, text: 'Bakım Pencereleri', id: 'bakim-pencereleri' },
    ])
  })
})

describe('duplicateSlugs', () => {
  it('çakışma yoksa boş dizi döner', () => {
    expect(duplicateSlugs('# Bir\n## İki\n')).toEqual([])
  })

  it('aynı slug altında toplanan başlıkları bildirir', () => {
    expect(duplicateSlugs('## Ayarlar\n### Ayarlar\n')).toEqual(['ayarlar'])
  })

  it('farklı yazımlar aynı slug üretiyorsa yakalar', () => {
    // "Bakım" ve "Bakim" aynı slug'a iner — TOC bağlantısı yanlış hedefe gider.
    expect(duplicateSlugs('## Bakım\n## Bakim\n')).toEqual(['bakim'])
  })
})
