import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * DİLLER-ARASI BEKÇİ: iki dilli bir alana dokunan her yüzey dil seçimi YAPMAK ZORUNDA.
 *
 * <p><b>Neden var (2026-08-22, kullanıcının bildirdiği hata).</b> Arayüz İngilizceyken Şablonlar
 * kartında İngilizce ad okunuyor, "Görüntüle" penceresi açılınca Türkçe metin geliyordu. Sebep:
 * kart listesi {@code pickLang} kullanıyordu, pencere kullanmıyordu — aynı veriyi iki yüzey iki
 * farklı kuralla gösteriyordu. Üstelik İngilizce alanlar katlanmış bir bloğun içinde durduğu için
 * çeviri hiç görünmüyordu.
 *
 * <p><b>Neden testler yakalamadı.</b> Şablon testlerinin hepsi {@code lang="tr"} ile koşuyordu;
 * İngilizce yol hiç çizilmemişti. Ayrıca bu bir çökme değil — ekran çalışır, sadece yanlış dilde.
 * Böyle hatalar yalnızca ya birinin gözüne çarparsa ya da bir kural onları kovalarsa bulunur.
 *
 * <p><b>Kural.</b> Alan çiftleri backend entity'sinden ({@code private String xEn;}) türetilir,
 * yani yeni bir iki dilli kolon eklenince kural kendiliğinden onu da kapsar. Üretim kodundaki bir
 * dosya çiftin İNGİLİZCE üyesine dokunuyorsa, {@code pickLang} ile dil seçimi yapmalı ya da
 * aşağıdaki listeye GEREKÇESİYLE eklenmelidir.
 *
 * <p><b>Sınır (dürüstçe):</b> bu kural yalnız iki dilli alanın adını GEÇEN dosyaları görür. Yalnız
 * asıl alanı ({@code tpl.description}) basıp {@code _en} üyesinden hiç söz etmeyen yeni bir yüzey
 * gözden kaçabilir. O boşluğu davranış testleri kapatıyor: her şablon yüzeyi İngilizce dilde de
 * çiziliyor ({@code ScriptedTemplateEditor.test.jsx}, {@code ScriptedTemplatesTab.test.jsx},
 * {@code ScriptedTemplateInfo.test.jsx}).
 */
const SRC = path.resolve(__dirname, '..')
const ENTITY = path.resolve(
  __dirname, '../../../backend/src/main/java/com/sitemonitor/model/ScriptedTemplate.java',
)

/** Dil seçimini TANIMLAYAN modül ile sözlük ve testler kuralın dışındadır. */
const EXEMPT = [
  path.join('utils', 'scriptSourceOptions.js'),   // pickLang'in kendisi burada tanımlı
  path.join('i18n', 'index.jsx'),                 // 'tpl.nameEn' ETİKET anahtarı, veri değil
  `test${path.sep}`,                              // testler kasten ham alanla çalışır
]

/**
 * Bilinçli istisnalar. Buraya bir satır eklemek "bu dosya iki dilli veriyi gösterMİYOR" taahhüdüdür
 * (ör. yalnız payload kuruyor). Gerekçesiz satır, yukarıdaki hatanın kapısını yeniden açar.
 */
const ALLOW = new Map([
  // (şimdilik boş — her üretim dosyası pickLang kullanıyor)
])

/** `private String whenToUseEn;` → 'whenToUse' */
function bilingualBases() {
  const src = fs.readFileSync(ENTITY, 'utf8')
  const out = []
  const re = /private\s+String\s+(\w+)En\s*;/g
  let m
  while ((m = re.exec(src)) !== null) out.push(m[1])
  return out
}

/** camelCase → snake_case (API yanıtı snake, form state camel — ikisi de aranmalı) */
const snake = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()

/** Blok ve satır yorumlarını atar — kural KODA baksın, açıklamaya değil. */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
}

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(p, acc)
    else if (/\.jsx?$/.test(entry.name)) acc.push(p)
  }
  return acc
}

describe('iki dilli alanlar — dil seçimi zorunlu', () => {
  const bases = bilingualBases()

  it('backend entity okunabiliyor (boş liste = kural hiçbir şeyi korumaz)', () => {
    expect(fs.existsSync(ENTITY), `bulunamadı: ${ENTITY}`).toBe(true)
    expect(bases).toContain('name')
    expect(bases.length).toBeGreaterThanOrEqual(3)
  })

  it('iki dilli alana dokunan üretim dosyaları pickLang kullanıyor', () => {
    const enTokens = bases.flatMap(b => [`${b}En`, `${snake(b)}_en`])
    const offenders = []

    for (const file of walk(SRC)) {
      const rel = path.relative(SRC, file)
      if (EXEMPT.some(x => rel.includes(x))) continue

      // YORUMLAR ÇIKARILIR: ilk sürümde çıkarılmıyordu ve javadoc'ta "pickLang" geçen bir dosya
      // kuralı sağlıyor sayılıyordu — mutasyon testi kapıyı tam bu yüzden delip geçti.
      const text = stripComments(fs.readFileSync(file, 'utf8'))
      if (!enTokens.some(tok => text.includes(tok))) continue      // iki dilli veriye dokunmuyor
      // ÇAĞRI aranıyor, sözcük değil: import edilip kullanılmayan bir helper hiçbir şey düzeltmez.
      if (/\bpickLang\s*\(|\btemplateLabel\s*\(/.test(text)) continue
      if (ALLOW.has(rel)) continue

      offenders.push(rel)
    }

    expect(offenders).toEqual([])
  })

  it('kuralın kendisi çalışıyor: bilinen dosyalar gerçekten taranıyor', () => {
    // Tarayıcı bozulursa (yanlış yol, değişmiş uzantı) test sessizce YEŞİL kalırdı.
    const scanned = walk(SRC).map(f => path.relative(SRC, f))
    expect(scanned).toContain(path.join('components', 'scripted', 'ScriptedTemplateEditor.jsx'))
    expect(scanned.length).toBeGreaterThan(100)
  })
})
