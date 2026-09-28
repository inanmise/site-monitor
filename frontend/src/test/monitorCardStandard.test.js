import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const COMPONENTS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'components')

/**
 * KART GÖRÜNÜMÜ STANDARDI — dokuz izleme türü AYNI biçimde listelenir.
 *
 * DNS ve Port satır (tablo) tabanlıydı, diğer yedi tür karttı. Aynı ürün, aynı soru ("bu izleme
 * ne durumda?") iki farklı düzende cevaplanıyordu: kullanıcı bir ekranda göz gezdirip diğerinde
 * sütun okuyordu. Faz C'de ikisi de `upt-card` desenine alındı.
 *
 * Kural yalnız "kart var mı" değil, paylaşılan parçaların KULLANILDIĞIdır: kendi meta/eylem
 * bloğunu yeniden yazan bir sayfa, standardın sessizce kaymasının ilk adımıdır.
 */
const MONITOR_PAGES = [
  'PingMonitorPage', 'DnsMonitorPage', 'DomainMonitorPage', 'HttpMonitorPage',
  'KeywordMonitorPage', 'PageMonitorPage', 'PageSpeedMonitorPage', 'PortMonitorPage',
  'ScriptedMonitorPage',
]

const read = (p) => fs.readFileSync(path.join(COMPONENTS, `${p}.jsx`), 'utf8')

/**
 * Kartı AYRI bileşene taşınmış sayfalar (2026-09-27, Ping kartı yeniden tasarımı → `ping/PingMonitorCard`).
 * Kural gevşemez, YER değiştirir: kart kuralları sayfa + kart modülünün BİRLEŞİK kaynağında aranır; sayfanın o kart
 * bileşenini gerçekten çizmesi ayrıca şarttır (eşleme bayatlarsa kural sessizce başka dosyada "yeşil" kalmasın).
 */
const CARD_MODULES = {
  PingMonitorPage: ['ping/PingMonitorCard'],
  PageSpeedMonitorPage: ['pagespeed/PageSpeedMonitorCard'],
  DnsMonitorPage: ['dns/DnsMonitorCard'],
  ScriptedMonitorPage: ['scripted/ScriptedMonitorCard'],
  PageMonitorPage: ['page/PageMonitorCard'],
  KeywordMonitorPage: ['keyword/KeywordMonitorCard'],
  HttpMonitorPage: ['http/HttpMonitorCard'],
  PortMonitorPage: ['port/PortMonitorCard'],
  DomainMonitorPage: ['domain/DomainMonitorCard'],
}
const readCard = (p) => [read(p), ...(CARD_MODULES[p] || []).map(read)].join('\n')

describe('izleme listesi kart standardı', () => {
  it('dokuz sayfa da KART ızgarası çizer', () => {
    const missing = MONITOR_PAGES.filter(p => !/className="upt-grid"/.test(read(p)))
    expect(missing).toEqual([])
  })

  it('hiçbir izleme sayfası listeyi TABLO olarak çizmez', () => {
    // Not: detay modallarındaki tablolar (kontrol geçmişi vb.) ayrı bileşenlerde; burada
    // taranan yalnız sayfa dosyalarının kendisi.
    const offenders = MONITOR_PAGES.filter(p => /<table[\s>]/.test(readCard(p)))
    expect(offenders, 'liste hâlâ tablo olarak çiziliyor').toEqual([])
  })

  it('kart üstbilgisi ve eylemleri PAYLAŞILAN bileşenlerden gelir', () => {
    const missing = []
    for (const p of MONITOR_PAGES) {
      const src = readCard(p)
      if (!/<MonitorCardMeta/.test(src)) missing.push(`${p} → MonitorCardMeta`)
      if (!/<MonitorCardActions/.test(src)) missing.push(`${p} → MonitorCardActions`)
    }
    expect(missing).toEqual([])
  })

  it('ayrı kart modülü olan sayfa o kartı GERÇEKTEN çizer ve kart paylaşılan MonitorCard ailesinden kurulur', () => {
    const missing = []
    for (const [p, mods] of Object.entries(CARD_MODULES)) {
      for (const mod of mods) {
        const name = path.basename(mod)
        if (!new RegExp(`<${name}\\b`).test(read(p))) missing.push(`${p} → <${name}> çizilmiyor`)
        if (!/<MonitorCard\b/.test(read(mod))) missing.push(`${mod} → MonitorCard (paylaşılan kart kabı)`)
      }
    }
    expect(Object.keys(CARD_MODULES).every((p) => MONITOR_PAGES.includes(p)), 'eşleme yalnız izleme sayfalarını anar').toBe(true)
    expect(missing).toEqual([])
  })

  /**
   * TOPLU İŞLEM + HIZLI SÜRDÜR STANDARDI (2026-09-26 kullanıcı bildirimi: "Alan Adı sayfasında kartlar sol üstten
   * seçilemiyor, toplu sürdür/duraklat diğer sayfalardaki gibi çalışmıyor — tüm izleme ekranlarında AYNI standart").
   * Her izleme sayfası: sol üst seçim kutusu (CARD_CHECK) + BulkActionBar (duraklat/sürdür/takım/grup/sil) +
   * duraklatılmış kartta ve detayda hızlı Sürdür (useMonitorResume → MonitorCardActions/MonitorModalActions `onResume`).
   *
   * UptimePage KAPSAM DIŞI (bilinçli): bir izleme türü değil, envanter alan adlarından türetilmiş salt okunur bir
   * ÖZET görünümü — satırın izleme kimliği yok (anahtar alan adı), `active` alanı yok ve güncellenecek bir izleme
   * kaydı/ucu yok (GET /monitoring/uptime/overview). Toplu işlem ya da Sürdür uygulanacak nesne bulunmaz.
   */
  it('dokuz sayfa da toplu seçim + BulkActionBar + hızlı Sürdür + kart eylemleri taşır', () => {
    const REQUIRED = [
      ['BulkActionBar', /<BulkActionBar\b/],
      ['CARD_CHECK (sol üst seçim kutusu)', /<Checkbox className=\{CARD_CHECK\}/],
      ['useMonitorResume', /\buseMonitorResume\(api\.monitoring\.update\w+Monitor\b/],
      // Öznitelikler ok fonksiyonu (`=>`) taşıdığı için `[^>]*` etiketi erken keser → kendini kapatan bloğa kadar tara.
      ['kart Sürdür (MonitorCardActions onResume)', /<MonitorCardActions\b(?:(?!\/>)[\s\S])*?\bonResume=\{/],
      ['MonitorCardActions', /<MonitorCardActions\b/],
    ]
    const scanned = []
    const missing = []
    for (const p of MONITOR_PAGES) {
      const src = read(p)
      scanned.push(p)
      for (const [label, rx] of REQUIRED) if (!rx.test(src)) missing.push(`${p} → ${label}`)
    }
    expect(scanned, 'tarama vakum değil: dokuz izleme sayfasının hepsi okundu').toHaveLength(9)
    expect(missing).toEqual([])
  })

  it('detay penceresi de duraklatılmış izlemede Sürdür sunar (MonitorModalActions / DnsDetailModal onResume)', () => {
    const missing = MONITOR_PAGES.filter((p) => !/\bonResume=\{[^}]*resume\(/.test(read(p)))
    expect(missing).toEqual([])
  })

  /**
   * DEĞİŞİKLİKLER SEKMESİ STANDARDI (2026-09-27, kullanıcı isteği: "hangi izleme değiştiyse kartına tıklayınca
   * yapılan değişiklikleri görsün"). Her izleme türünün detay penceresi: `ChangeHistoryTab` (Değişiklikler sekmesi),
   * `DetailTabs countsFor` (sekme sayaçları: değişiklik/not/açık alarm) ve `?monitor=<id>&mtab=changes` derin
   * bağlantısı (İzleme Değişiklikleri konsolu böyle bağlar; sayfalar `useDeepLinkTab`, DNS modalı `readUrlParam`).
   * DNS sayfası detay penceresini DnsDetailModal'a devreder — sekme orada bağlanır.
   */
  it('dokuz izleme türü de DEĞİŞİKLİKLER sekmesini bağlar (ChangeHistoryTab + countsFor + mtab derin bağlantısı)', () => {
    const missing = []
    const detailSource = (p) => (p === 'DnsMonitorPage' ? read('DnsDetailModal') : read(p))
    for (const p of MONITOR_PAGES) {
      const src = detailSource(p)
      if (!/<TabsContent value="changes">[\s\S]{0,600}?<ChangeHistoryTab\b/.test(src)) missing.push(`${p} → Değişiklikler sekmesi (TabsContent + ChangeHistoryTab)`)
      if (!/<DetailTabs\b[\s\S]{0,300}?\bcountsFor=\{\{[^}]*\bkind:\s*'[a-z]+'/.test(src)) missing.push(`${p} → DetailTabs countsFor (sekme sayaçları)`)
    }
    for (const p of MONITOR_PAGES.filter((p) => p !== 'DnsMonitorPage')) {
      const src = read(p)
      if (!/\buseDeepLinkTab\(/.test(src) || !/setDetailTab\(deepLinkTab\(\)\)/.test(src)) missing.push(`${p} → mtab derin bağlantısı (useDeepLinkTab → openDetail)`)
    }
    if (!/readUrlParam\('mtab'/.test(read('DnsDetailModal'))) missing.push('DnsDetailModal → mtab derin bağlantısı')
    expect(missing).toEqual([])
  })

  /**
   * SAYFA BAŞLIĞI STANDARDI (2026-09-27, kullanıcı isteği: "Yenile / Şimdi Kontrol Et / Nasıl doldurulur / Yeni Monitör —
   * shadcn ile profesyonel, her sayfada benzer, mobil uyumlu"). Dokuz izleme sayfası + Uptime başlığını
   * `monitoring/MonitorPageHeader`'dan alır (ui/PageHeader: ikon, meta çipleri, eylem sırası, telefonda "Diğer" menüsü).
   * El yapımı başlık satırı (eski `.upt-header` / `.dns-header`) geri gelirse standart sessizce kayar.
   */
  it('dokuz izleme sayfası + Uptime başlığı ortak MonitorPageHeader\'dan gelir (el yapımı başlık yok)', () => {
    const PAGES = [...MONITOR_PAGES, 'UptimePage']
    const missing = []
    for (const p of PAGES) {
      const src = read(p)
      if (!/import MonitorPageHeader from '\.\/monitoring\/MonitorPageHeader\.jsx'/.test(src)) missing.push(`${p} → import`)
      if (!/<MonitorPageHeader\b[^>]*\btype="[a-z]+"/.test(src)) missing.push(`${p} → <MonitorPageHeader type="…">`)
      if (/className="(upt-header|upt-header-right|upt-title|upt-subtitle|upt-last-check|dns-header|dns-title)"/.test(src)) missing.push(`${p} → el yapımı başlık sınıfı`)
    }
    expect(PAGES, 'tarama vakum değil: on sayfa okundu').toHaveLength(10)
    expect(missing).toEqual([])
  })

  /**
   * KAYIT SONRASI İLK / TAZE KONTROL STANDARDI (2026-09-28, kullanıcı bildirimi: "Test et → başarılı → Kaydet; açılan
   * kartta veriler yansımıyor, boş bir görünüm oluyor"). Dokuz sayfa da kaydın ardından kartın KENDİ "Şimdi kontrol et"
   * yolunu paylaşılan karardan (`utils/checkAfterSave` → shouldCheckAfterSave('<tür>')) geçirerek çağırır ve kartına
   * `running={isRunning(m.id)}` verir; kart modülü onu MonitorCard'a iletir (hiç sonucu yokken "İlk kontrol yapılıyor…").
   * Bir sayfa bu halkalardan birini atlarsa o türde kart yine kayıttan sonra boş kalır — derleme ve diğer testler yeşil.
   */
  it('dokuz sayfa kayıttan sonra ilk / taze kontrolü paylaşılan karardan başlatır ve kart `running` alır', () => {
    const KIND = {
      PingMonitorPage: 'ping', DnsMonitorPage: 'dns', DomainMonitorPage: 'domain', HttpMonitorPage: 'http', KeywordMonitorPage: 'keyword',
      PageMonitorPage: 'page', PageSpeedMonitorPage: 'pagespeed', PortMonitorPage: 'port', ScriptedMonitorPage: 'scripted',
    }
    const missing = []
    for (const p of MONITOR_PAGES) {
      const src = read(p)
      const kind = KIND[p]
      if (!/import \{[^}]*\bshouldCheckAfterSave\b[^}]*\} from '\.\.\/utils\/checkAfterSave\.js'/.test(src)) missing.push(`${p} → import checkAfterSave`)
      if (!new RegExp(`shouldCheckAfterSave\\('${kind}',`).test(src)) missing.push(`${p} → shouldCheckAfterSave('${kind}', …)`)
      // Kararın ardından GERÇEKTEN başlatılır: ya paylaşılan ateşle-unut ya da (Sentetik) doğrulama bandı yolu.
      if (!/startCheckAfterSave\(checkNow,/.test(src)) missing.push(`${p} → startCheckAfterSave(checkNow, …)`)
      const card = path.basename(CARD_MODULES[p][0])
      if (!new RegExp(`<${card}\\b[^>]*?\\brunning=\\{isRunning\\(m\\.id\\)\\}`).test(src)) missing.push(`${p} → <${card} running={isRunning(m.id)}>`)
      if (!/<MonitorCard\b[^>]*\brunning=\{running\}/.test(read(CARD_MODULES[p][0]))) missing.push(`${CARD_MODULES[p][0]} → <MonitorCard running={running}>`)
    }
    expect(Object.keys(KIND).sort(), 'tür eşlemesi dokuz sayfanın tamamı').toEqual([...MONITOR_PAGES].sort())
    expect(missing).toEqual([])
  })

  /**
   * 7/24 GÖSTERGESİ STANDARDI (2026-09-28, kullanıcı isteği: "kartların üzerinde küçük bir tasarımla, izleme 7/24 ekibine
   * iletiliyorsa ve açıksa veya kapalı ise her izleme için bu bilgi gözükmeli"). Dokuz kart MonitorCard'a
   * `noc={{ type: '<TÜR>', monitor: m, rowLabel, canEdit }}` verir; göstergeyi (noc/NocStatus) paylaşılan MonitorCardTop
   * sağ grubun BAŞINA kendisi çizer → dokuz türde aynı yer, iki yoğunlukta da. Kart dosyası göstergeyi elle yerleştirmez
   * (eski yalnız-Zengin NocBadge'i böyle kaymıştı: dört kartta meta satırında, beşinde Kompakt'ta hiç). Sayfa kartı
   * `canEdit={canManageRow(m)}` ile çizer (düzenleme bağlantısı kart eylemleriyle AYNI kapı) ve detay penceresi aynı
   * göstergeyi taşır (`noc={{ type: '<TÜR>', … }}`) — kart ile pencere ayrışmaz. Genel Bakış sertifika kartı da aynı
   * bileşeni sağ grubun başında çizer (tür SSL); Uyarılar listesi meta satırında, sertifika penceresi başlığında (2026-09-28).
   * Çizim sözleşmesi (iki yoğunluk, üç durum, detay açmaz) nocStatus.cards.test.jsx + warningsNocStatus.test.jsx.
   */
  it('dokuz kart + sertifika kartı 7/24 göstergesini paylaşılan yerden çizer; sayfa canEdit, detay penceresi noc verir', () => {
    const TYPE = {
      PingMonitorPage: 'PING', DnsMonitorPage: 'DNS', DomainMonitorPage: 'DOMAIN', HttpMonitorPage: 'HTTP', KeywordMonitorPage: 'KEYWORD',
      PageMonitorPage: 'PAGE', PageSpeedMonitorPage: 'PAGESPEED', PortMonitorPage: 'PORT', ScriptedMonitorPage: 'SCRIPTED',
    }
    const missing = []
    const shared = read('monitoring/MonitorCard')
    if (!/export function MonitorCardTop\b[\s\S]*?<NocStatus\b[^>]*\bcompact=\{density === 'compact'\}/.test(shared)) {
      missing.push('monitoring/MonitorCard → MonitorCardTop <NocStatus … compact> çizmiyor')
    }
    for (const p of MONITOR_PAGES) {
      const mod = CARD_MODULES[p][0]
      const cardName = path.basename(mod)
      const card = read(mod)
      if (!new RegExp(`<MonitorCard\\b[^>]*?\\bnoc=\\{\\{ type: '${TYPE[p]}', monitor: m\\b[^}]*\\bcanEdit\\b`).test(card)) {
        missing.push(`${mod} → <MonitorCard noc={{ type: '${TYPE[p]}', monitor: m, …, canEdit }}>`)
      }
      if (!/<MonitorCardTop\b/.test(card)) missing.push(`${mod} → MonitorCardTop (göstergenin yeri)`)
      if (/\bNocBadge\b|<NocStatus\b/.test(card)) missing.push(`${mod} → göstergeyi elle yerleştiriyor (MonitorCardTop çizer)`)
      const src = read(p)
      if (!new RegExp(`<${cardName}\\b[^>]*?\\bcanEdit=\\{canManageRow\\(m\\)\\}`).test(src)) missing.push(`${p} → <${cardName} canEdit={canManageRow(m)}>`)
      const detail = p === 'DnsMonitorPage' ? read('DnsDetailModal') : src
      if (!new RegExp(`<MonitorDetailModal\\b[^>]*?\\bnoc=\\{\\{ type: '${TYPE[p]}'`).test(detail)) missing.push(`${p} → <MonitorDetailModal noc={{ type: '${TYPE[p]}', … }}>`)
      if (/\bnocNotify=\{/.test(detail)) missing.push(`${p} → eski nocNotify rozeti`)
    }
    if (!/<NocStatus type="SSL" monitor=\{cert\}/.test(read('CertificateCard'))) missing.push('CertificateCard → <NocStatus type="SSL" monitor={cert} …>')
    // 2026-09-28: Uyarılar listesi (tablo + dikkat kartı, meta satırı) ve sertifika penceresinin başlığı da AYNI göstergeyi çizer
    const attention = read('../pages/warnings/AttentionList')
    if (!/function MetaLine\b[\s\S]*?<NocStatus type="SSL" monitor=\{row\}/.test(attention)) missing.push('pages/warnings/AttentionList → MetaLine <NocStatus type="SSL" monitor={row} …>')
    const certModal = read('CertificateModal')
    if (!/<NocStatus type="SSL" monitor=\{noc\}/.test(certModal) || !/headerExtra=\{<>\{nocIndicator\}/.test(certModal)) {
      missing.push('CertificateModal → başlıkta <NocStatus type="SSL" monitor={noc} …> (headerExtra, eylemlerin solunda)')
    }
    expect(Object.keys(TYPE).sort(), 'tür eşlemesi dokuz sayfanın tamamı').toEqual([...MONITOR_PAGES].sort())
    expect(fs.existsSync(path.join(COMPONENTS, 'noc/forms/NocBadge.jsx')), 'eski yalnız-Zengin rozeti geri geldi').toBe(false)
    expect(missing).toEqual([])
  })

  it('durum sınıfı paylaşılan sözlükten seçilir (uydurma sınıf = tarayıcı varsayılanı)', () => {
    // 'warn' senaryo izlemesine ozel MESRU dorduncu durum (kismi gecis) ve App.css:2892'de
    // TANIMLI. Listeye alinmasi, uydurma bir sinifin gozden kacmasina izin vermez.
    const allowed = ['upt-card--up', 'upt-card--down', 'upt-card--unknown', 'upt-card--warn']
    const offenders = []
    for (const p of MONITOR_PAGES) {
      const used = [...read(p).matchAll(/'(upt-card--[a-z]+)'/g)].map(m => m[1])
      for (const cls of used) if (!allowed.includes(cls)) offenders.push(`${p} → ${cls}`)
    }
    expect(offenders).toEqual([])
  })
})
