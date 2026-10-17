// Kanal teknik hata raporları (2026-10-13). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280: kanal kartları
// sığar ve uzun adlar sarar; "Şablonu aç" TIKLANAN kanalın şablonunu açar; şablon kaydı okunan sürümle gider; rapor
// düzenleyicide Türkçe sayı çözülür ve sürümle kaydedilir, grafik yuvaları 768+ ekranda iki kolon (yarım yan yana, tam iki
// kolon), telefonda tek kolon; görsel yükleme ve gönderim onayı. API mock'lu (bildirim yok, e-posta gönderilmez).
// 2026-10-14: yönetici özeti (rakamlarla canlı, kalın), grup satırlı tablo (geniş kapta tablo, telefonda kart; yatay
// taşma yok), açıklamada madde / kalın araç çubuğu ve önizleme; şablonda özet cümlesi ve tablo düzenleme.
// 2026-10-15 (Faz C): otomatik trend grafikleri sığar, çubuk etiketleri üst üste binmez, ilk ay / Ocak altında yıl;
// büyük görsel tarayıcıda yuva ölçüsüne küçültülüp yüklenir (gerçek canvas), PDF yüklenmez.
// 2026-10-16: bölümler katlanır (kapalı başlar, açılan hatırlanır, "Tümünü aç"); rapor 30 sn'de bir ve sekme gizlenince
// otomatik kaydedilir (`auto: true`, sessiz; hatalı alan saklı değerinde gider). Akordeonla ilgisi olmayan testler
// bölümleri açık tohumlar (`seedOpenSections`).
// 2026-10-17 (8. sürüm): kanal listesi — dönem kartları süzgeç, arama / sıralama, geniş kapta tablo, telefonda kart, sayfalama
// (60 kanal → 50 + 10); kanalın rapor listesi durum / yıl / arama süzgeçleri ve sayfalama (30 → 25 + 5); URL'de taşınır.
import { test, expect } from '@playwright/test'
import zlib from 'node:zlib'
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_CHANNEL = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')

// Büyük ekran görüntüsü yerine geçen PNG (çizgili, RGB) — küçültme gerçek tarayıcı canvas'ında denenir
const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function stripedPng(w, h) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
  const raw = Buffer.alloc((w * 3 + 1) * h)
  for (let y = 0; y < h; y++) {
    const o = y * (w * 3 + 1)
    for (let x = 0; x < w; x++) {
      const on = (Math.floor(x / 40) + Math.floor(y / 40)) % 2 === 0
      raw[o + 1 + x * 3] = on ? 20 : 240; raw[o + 2 + x * 3] = on ? 90 : 240; raw[o + 3 + x * 3] = on ? 200 : 240
    }
  }
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
// Çok parçalı yükleme gövdesindeki PNG'yi çözer (8 bit RGB / RGBA, taramasız; beş satır süzgeci) — tarayıcının küçülttüğü
// görselin PİKSELLERİ doğrulanır (2026-10-16: şerit / önbellek / alan ortalaması gerçek tuvalde)
function decodePng(body) {
  const i = body.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47]))
  if (i < 0) return null
  let o = i + 8
  let w = 0, h = 0, ct = -1
  const idat = []
  for (let guard = 0; guard < 100000 && o + 8 <= body.length; guard++) {
    const len = body.readUInt32BE(o)
    const type = body.toString('ascii', o + 4, o + 8)
    const data = body.subarray(o + 8, o + 8 + len)
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]
      if (data[8] !== 8 || data[12] !== 0) return null
    } else if (type === 'IDAT') idat.push(data)
    else if (type === 'IEND') break
    o += 12 + len
  }
  const bpp = ct === 6 ? 4 : ct === 2 ? 3 : 0
  if (!bpp) return null
  const raw = zlib.inflateSync(Buffer.concat(idat))
  const stride = w * bpp
  const out = Buffer.alloc(stride * h)
  let prev = Buffer.alloc(stride)
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)]
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    const cur = out.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0
      const b = prev[x]
      const c = x >= bpp ? prev[x - bpp] : 0
      let v = line[x]
      if (f === 1) v += a
      else if (f === 2) v += b
      else if (f === 3) v += (a + b) >> 1
      else if (f === 4) {
        const p = a + b - c
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c)
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c
      }
      cur[x] = v & 0xff
    }
    prev = cur
  }
  return { w, h, px: (x, y) => Array.from(out.subarray((y * w + x) * bpp, (y * w + x) * bpp + 3)) }
}
// Çok parçalı yükleme gövdesindeki PNG'nin boyutu (IHDR)
function pngSize(body) {
  const i = body.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47]))
  if (i < 0) return null
  return { w: body.readUInt32BE(i + 16), h: body.readUInt32BE(i + 20), bytes: body.length }
}

const TEMPLATE = {
  v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      '{kanal} Kanalından yapılan toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.',
      'SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} ile {f:sla-above?hedefin üzerinde|hedefin altında} gerçekleşmiştir.',
    ] },
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Özeti', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', hint: 'İlgili ay/yıl verilerine göre' },
      { id: 'had-outage', label: 'Dönemde kesinti oldu mu?', type: 'yesno', hint: "“Hayır” işaretlenirse kesinti alanları istenmez, PDF'e girmez" },
      { id: 'error-rate-excl', label: 'Kesinti günleri hariç teknik hata oranı', type: 'percent', skip_if_no: 'had-outage' },
      { id: 'outage-errors', label: 'Kesinti kaynaklı teknik hata adedi', type: 'number', skip_if_no: 'had-outage' },
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'tx-increase', label: 'Önceki aya göre artış var mı?', type: 'yesno' },
      { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent' },
      { id: 'sla-above', label: 'SLA hedefin üstünde mi?', type: 'yesno' },
    ] },
    { id: 'issues', kind: 'issues', title: 'Teknik hataların anomali olarak arttığı konular', item_title: '{konu} Konusu ile yaşanan problem',
      description_label: 'Ne zaman, nerede, nasıl gerçekleşti?', customers_label: 'Etkilenen müşteri sayısı',
      errors_label: 'Bu sorundan kaynaklı teknik hata adedi' },
    { id: 'unmapped', kind: 'metrics', title: 'Map Yapılmamış Hata Adedi', fields: [
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'unmapped-note', label: 'Açıklama', type: 'textarea' },
    ] },
    { id: 'sla', kind: 'table', title: 'SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'success-tx', label: 'Başarılı işlem adedi', type: 'number' },
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'failed-tx', label: 'Teknik başarısız işlem adedi (kesinti günleri dahil)', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik (kesintisiz)', type: 'percent' },
      { id: 'volume-share', label: 'Toplam işlem hacmi payı', type: 'percent' },
    ], rows: [
      { id: 'total', label: '{kanal} Kanalı Toplam', group: true },
      { id: 'sla-critical', label: 'SLA dahil ve kritik fonksiyonlar (müşteri işlemleri)' },
      { id: 'sla-noncritical', label: 'SLA dahil ve kritik olmayan' },
      { id: 'out-of-sla', label: 'SLA kapsamında olmayan' },
    ] },
    { id: 'trend-note', kind: 'text', title: 'Grafikler hakkında', text: 'Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.' },
    { id: 'auto-trend', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)',
      series: ['error-rate', 'total-tx', 'unmapped-count', 'outage-errors'] },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [
      { id: 'monthly-tx', label: '{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri', width: 'half' },
      { id: 'tech-errors', label: '{kanal} Kanalı Teknik Hata Grafiği', width: 'half' },
      { id: 'daily-errors', label: '{kanal} Kanalı Teknik Hata (Günlük)', width: 'full' },
    ] },
  ],
}

function channel(id, name) {
  return {
    id, team_id: 5, team_name: LONG_TEAM, name, description: 'Aylık teknik hata raporu üst yönetime e-posta ile iletilir.',
    active: true, sort_order: null, template_version: 3, lang: 'tr', section_count: 5, field_count: 8, slot_count: 3,
    can_edit: true, can_delete: true, report_count: 4,
    period_report: id === 10 ? { id: 100, status: 'draft', year: 2026, month: 9, last_send_status: null } : null,
  }
}

const REPORT = {
  report: { id: 100, channel_id: 10, team_id: 5, year: 2026, month: 9, status: 'draft', send_state: null, last_send_status: null,
    updated_at: '2026-10-02T07:00:00Z', last_sent_at: null, version: 4, title: '', updated_by_name: 'Ayşegül Nur Karaosmanoğlu' },
  channel: channel(10, LONG_CHANNEL), template: TEMPLATE,
  values: { fields: { 'error-rate': 0.42 }, issues: { issues: [{ id: 'i-1', topic: 'Kart Limiti Sorgulama Servisi', title: '', title_auto: true,
    description: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı; servis yeniden başlatıldı.',
    customers: 1200, errors: 3400 }] }, texts: {} },
  images: [], previous: { id: 90, year: 2026, month: 8, fields: { 'error-rate': 0.5, 'total-tx': 1000000 } },
  can_edit: true, can_delete: true, team_email_set: true, template_update: { differs: false, lost_values: 0, lost_images: 0 },
  neighbors: { prev: { id: 90, year: 2026, month: 8 }, next: null },
  // Faz C: kanalın önceki raporlarına girilmiş rakamlar (Ekim 2025 … Eylül 2026; bu ay düzenleyicideki taslaktan)
  trend: {
    months: Array.from({ length: 12 }, (_, i) => (i < 3 ? { year: 2025, month: 10 + i } : { year: 2026, month: i - 2 })),
    values: {
      'error-rate': [0.61, 0.58, 0.55, 0.52, 0.6, 0.49, 0.47, 0.51, 0.45, 0.48, 0.5, 0.42],
      'total-tx': [98.2e6, 101.4e6, 110e6, 120e6, 115.6e6, 118e6, 121.3e6, 119e6, 123e6, 124.9e6, 125.43e6, null],
      'unmapped-count': [1200, 1540, 1810, 2400, 1999, 2650, 3010, 2875, 3120, 2990, 3340, null],
      'outage-errors': [null, null, null, null, null, null, null, null, null, null, 412, null],
    },
    min_points: 2,
  },
}

// Otomatik oranlar (2026-10-16): kullanıcı adetleri girer, oranlar hesaplanır; adet girişinin altında türeyen oran
const CALC_TEMPLATE = {
  v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Verileri', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'quota-count', label: 'Kota hatası adedi', type: 'number' },
    ] },
    { id: 'rates', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Oranları (otomatik)', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
      { id: 'unmapped-rate', label: 'Map yapılmamış hataların teknik hatalara oranı', type: 'percent',
        calc: { kind: 'ratio', num: 'unmapped-count', den: 'error-count' } },
      { id: 'quota-rate', label: 'Kota hatalarının teknik hatalara oranı', type: 'percent',
        calc: { kind: 'ratio', num: 'quota-count', den: 'error-count' } },
      { id: 'tx-increase', label: 'Önceki aya göre artış var mı?', type: 'yesno', calc: { kind: 'increase', of: 'total-tx' } },
      { id: 'tx-increase-pct', label: 'Önceki aya göre değişim oranı', type: 'percent', calc: { kind: 'change', of: 'total-tx' } },
    ] },
  ],
}
const CALC_REPORT = {
  ...REPORT, template: CALC_TEMPLATE, trend: undefined,
  values: { fields: { 'total-tx': 125430112, 'error-count': 526806, 'unmapped-count': 3340, 'quota-count': 1250 }, issues: {}, texts: {} },
  previous: { id: 90, year: 2026, month: 8, fields: { 'total-tx': 124900000 } },
}
let reportOverride = null
let templateOverride = null
let overviewOverride = null     // kanal listesi (8. sürüm: 60 kanal, sayfalama)
let channelPuts = []            // kanal sırası (R14): PUT /channels/<id> gövdeleri — {id, ...body}
let orderPuts = []              // toplu kanal sırası (R14 inceleme): PUT /teams/<id>/channel-order — {team, ids}
let reportsOverride = null      // kanalın rapor listesi (8. sürüm: 30 rapor)

// Özet kartına gömülü bölüm (2026-10-17): map yapılmamış hata adedi + açıklama yönetici özeti kartında girilir; açıklama
// ilgili cümlenin altında, hiçbir cümlenin başvurmadığı evet/hayır kendi satırında
const EMBED_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: [
      '{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.',
      'Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.',
      'Kapanış cümlesi.',
    ] },
    { id: 'unmapped', kind: 'metrics', title: 'Map Yapılmamış Hatalar', host: 'exec', fields: [
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'unmapped-new', label: 'Yeni hata kodu var mı?', type: 'yesno' },
      { id: 'unmapped-note', label: 'Map yapılmamış hatalar hakkında açıklama', type: 'textarea' },
    ] },
    TEMPLATE.sections[1],
    TEMPLATE.sections[2],
  ],
}
const EMBED_REPORT = { ...REPORT, template: EMBED_TEMPLATE, trend: undefined, values: { fields: { 'error-rate': 0.42 }, issues: {}, texts: {} } }

// SLA hedefi + renkli kutucuklar + fark cümleleri (2026-10-17, 4b): SLA oranında hedef %99,95 (yüksek iyi), "SLA hedefin
// üstünde mi?" hedefle otomatik; özetin başında 3 kutucuk; {d:} / {t:} / {dt:} cümleleri; geçen ayın raporu var
const SLA_TEMPLATE = {
  ...TEMPLATE,
  sections: [
    { ...TEMPLATE.sections[0], tiles: ['error-rate', 'total-tx', 'sla-rate'], lines: [
      TEMPLATE.sections[0].lines[0],
      'Önceki aya göre toplam işlem adedi {d:total-tx} değişmiştir.',
      'SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} (hedef {t:sla-rate}, fark {dt:sla-rate}) ile '
        + '{f:sla-above?hedef başarıyla tutturulmuştur|hedefin altında kalmıştır}.',
    ] },
    { ...TEMPLATE.sections[1], fields: TEMPLATE.sections[1].fields.map((f) => (f.id === 'sla-rate' ? { ...f, target: 99.95, better: 'higher' }
      : f.id === 'sla-above' ? { ...f, calc: { kind: 'compare', of: 'sla-rate' } } : f)) },
    ...TEMPLATE.sections.slice(2),
  ],
}
const SLA_REPORT = {
  ...REPORT, template: SLA_TEMPLATE, trend: undefined,
  values: { fields: { 'error-rate': 0.42, 'total-tx': 1000, 'sla-rate': 99.9 }, issues: {}, texts: {} },
  previous: { id: 90, year: 2026, month: 8, fields: { 'error-rate': 0.6, 'total-tx': 800 } },
}

// Faz B (2026-10-14): değişiklik geçmişi ve şablon sürümleri — gerçek uzunlukta adlar / değerler
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Demirtaş (Dijital Kanallar / Uygulama Geliştirme Yöneticisi)'
const HISTORY = {
  has_more: false, recorded_since: '2026-10-01T08:00:00Z',
  report: { created_at: '2026-10-01T08:00:00Z', created_by_name: LONG_PERSON, updated_at: '2026-10-13T09:00:00Z', updated_by_name: LONG_PERSON },
  events: [
    { id: 9, kind: 'SEND', at: '2026-10-13T10:00:00Z', actor_name: LONG_PERSON, system: false,
      detail: { state: 'SENT', recipient_count: 3, recipients: ['dijital-kanallar-uygulama-gelistirme@example.com', 'kanal-yonetimi-raporlama@example.com', 'ust-yonetim@example.com'] } },
    { id: 8, kind: 'VALUES', at: '2026-10-13T09:00:00Z', actor_name: LONG_PERSON, system: false, version: 5,
      detail: { changes: [
        { section: `${LONG_CHANNEL} Kanalı — Eylül 2026 Özeti`, label: 'Kesinti günleri hariç teknik hata oranı (kesintilerin olduğu günler çıkarıldığında)', type: 'percent', op: 'change', before: '%0,4235', after: '%0,5112' },
        { section: 'Teknik hataların anomali olarak arttığı konular', label: 'Kart Limiti Sorgulama Servisi Konusu ile yaşanan problem · Ne zaman, nerede, nasıl gerçekleşti?', type: 'textarea', op: 'change',
          before: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı.', after: '12 Eylül 09:40–11:15 arasında mobil uygulamada limit sorgusu zaman aşımına uğradı; servis yeniden başlatıldı ve kalıcı çözüm için kapasite artırımı planlandı.', after_length: 640 },
        { section: 'SLA Erişilebilirlik Tablosu', label: `${LONG_CHANNEL} Kanalı Toplam — Teknik başarısız işlem adedi`, type: 'table', op: 'set', before: null, after: '125.430.112' },
      ] } },
    { id: 7, kind: 'IMAGE_ADD', at: '2026-10-12T08:00:00Z', actor_name: LONG_PERSON, system: false,
      detail: { slot: `${LONG_CHANNEL} Kanalı Ay Bazlı Toplam İşlem Adetleri`, file_name: 'aylik-toplam-islem-adetleri-grafigi-2026-eylul-son-surum.png', width: 1600, height: 900, size_bytes: 412000 } },
    { id: 1, kind: 'CREATE', at: '2026-10-01T08:00:00Z', actor_name: LONG_PERSON, system: false, detail: { template_version: 3 } },
  ],
}
const VERSIONS = { current_version: 3, max_versions: 100, versions: [
  { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 5, field_count: 8, slot_count: 3 },
  { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 4, field_count: 9, slot_count: 3 },
] }
const TEMPLATE_V2 = { ...TEMPLATE, title: 'Eski rapor başlığı: {kanal} Kanalı Aylık Teknik Hata ve Erişilebilirlik Değerlendirmesi - {ay} {yil}',
  sections: TEMPLATE.sections.map((x, i) => (i === TEMPLATE.sections.findIndex((y) => y.kind === 'metrics')
    ? { ...x, fields: [...x.fields, { id: 'eski-alan', label: 'Kaldırılan uzun alan adı: kota ve map yapılmamış hataların toplam içindeki payı', type: 'percent' }] } : x)) }

// Oturum sahibi (/api/me) — kişisel kayıtlar (açık bölümler, taslak yedekleri) bu ada bağlı
const ME_USER = 'demo'

/**
 * Katlanır bölümler (2026-10-16): rapor ve şablon bölümleri KAPALI başlar, açılanlar kişisel kayıtta hatırlanır
 * (`sm.te.open.r.<rapor>` / `sm.te.open.t.<kanal>`). Akordeonla ilgisi olmayan testler bölümleri daha önce açmış bir
 * kullanıcıyı taklit eder: bu spec'in raporu ve iki kanal şablonu "hepsi açık". Kayıt sahibi de yazılır — yoksa uygulama
 * girişte sahibi bilinmeyen kişisel kayıtları siler (`claimPersonalStorage`) ve tohum kaybolur. Her gezinmede yeniden
 * yazılır (yeniden yüklemede de hepsi açık).
 */
async function seedOpenSections(page, { reports = [100], channels = [10, 20] } = {}) {
  await page.addInitScript(({ owner, reports: r, channels: c }) => {
    try {
      localStorage.setItem('sm.storage.owner', owner)
      for (const id of r) localStorage.setItem(`sm.te.open.r.${id}`, JSON.stringify({ all: true }))
      for (const id of c) localStorage.setItem(`sm.te.open.t.${id}`, JSON.stringify({ all: true }))
    } catch { /* yoksay */ }
  }, { owner: ME_USER, reports, channels })
}

let savedTemplate = null
let savedReport = null          // son ELLE kayıt (Kaydet) — `autoBefore`: ondan önce kaç otomatik kayıt gitti
let autoSaves = []              // otomatik kayıtlar (gövdede `auto: true`, 2026-10-16)
let uploads = []
let uploadSizes = []
let uploadBodies = []
let sends = 0
let testMails = 0               // "Bana test gönder" (R13): rapor gönderimi DEĞİL — ayrı sayılır
let approvalRequests = 0        // PO onayı (2026-10-17, S6)
let decisions = []
let previewBodies = []          // şablon önizleme PDF'i (R19): gönderilen taslaklar
let sendBodies = []             // R18 / C13: gönderim gövdeleri (ek Bilgi yoksa gövde yok → null)
let sendLogCalls = []           // R18 / E22: gönderim günlüğü istekleri (sorgu dizgeleri)
// Uzun alıcı listesi (sararak TAM yazılmalı): gerçek uzunlukta kurumsal adresler
const SENDLOG_LONG_TO = Array.from({ length: 9 }, (_, i) => `dijital.kanallar.uygulama.gelistirme.${i}@ornek-kurum-bilgi-teknolojileri.example.com`)
const SENDLOG_ROWS = [
  { id: 3, at: '2026-10-13T08:00:00Z', kind: 'report', trigger: 'TECH_ERROR_REPORT', role: 'TEAM', status: 'sent', status_text: 'SENT',
    report_id: 100, year: 2026, month: 9, to: SENDLOG_LONG_TO, cc: ['ek.bilgi.adresi@ornek-kurum-bilgi-teknolojileri.example.com'], to_count: 9, cc_count: 1 },
  { id: 2, at: '2026-10-12T08:00:00Z', kind: 'test', trigger: 'TECH_ERROR_REPORT_TEST', role: 'SELF', status: 'failed',
    status_text: 'FAILED: 550 5.1.1 alıcı posta kutusu bulunamadı — kurumsal ağ geçidi iletiyi reddetti', report_id: 100, year: 2026, month: 9,
    to: ['ayse.yilmaz@example.com'], cc: [], to_count: 1, cc_count: 0 },
  { id: 1, at: '2026-10-11T08:00:00Z', kind: 'approval', trigger: 'TECH_ERROR_APPROVAL', role: 'APPROVER', status: 'queued',
    status_text: 'QUEUED_RETRY: 421', report_id: null, year: null, month: null, to: ['mehmet.kaya@example.com'], cc: [], to_count: 1, cc_count: 0 },
]
async function mock(page, { openSections = true } = {}) {
  savedTemplate = null; savedReport = null; autoSaves = []; uploads = []; uploadSizes = []; uploadBodies = []; sends = 0
  testMails = 0; approvalRequests = 0; decisions = []; channelPuts = []; orderPuts = []; previewBodies = []; sendBodies = []
  sendLogCalls = []
  reportOverride = null
  templateOverride = null
  overviewOverride = null
  reportsOverride = null
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  if (openSections) await seedOpenSections(page)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    if (p === '/api/tech-error-reports/images/501') {
      await route.fulfill({ status: 200, contentType: 'image/png', body: PNG })
      return
    }
    if (/^\/api\/tech-error-reports\/channels\/(10|20)\/template\/preview-pdf$/.test(p) && req.method() === 'POST') {
      previewBodies.push({ id: Number(p.split('/')[4]), ...JSON.parse(req.postData() || '{}') })
      await route.fulfill({ status: 200, contentType: 'application/pdf',
        headers: { 'Content-Disposition': 'inline; filename="mobil-sablon-onizleme.pdf"', 'Cache-Control': 'no-store' },
        body: '%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n' })
      return
    }
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: ME_USER, system_role: 'USER', global_admin: false, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'tech_error_reports.read': { view: true }, 'tech_error_reports.crud': { edit: true } } }
    else if (p === '/api/tech-error-reports/overview') {
      body = { success: true, data: { period: { year: 2026, month: 9 }, can_create: true,
        teams: [{ id: 5, name: LONG_TEAM, active: true, has_email: true, can_edit: true, can_manage: false }],
        channels: overviewOverride || [channel(10, LONG_CHANNEL), channel(20, 'POS')] } }
    } else if (/^\/api\/tech-error-reports\/channels\/\d+$/.test(p) && req.method() === 'PUT') {
      // Kanal sırası (R14): gövde kaydedilir, overviewOverride'daki kanal güncellenir — yeniden yükleme yeni sırayı getirir
      const id = Number(p.split('/').pop())
      const put = JSON.parse(req.postData() || '{}')
      channelPuts.push({ id, ...put })
      const ch = (overviewOverride || []).find((c) => c.id === id)
      if (ch && 'sort_order' in put) ch.sort_order = put.sort_order
      body = { success: true, data: { ...(ch || { id }), ...put } }
    } else if (/^\/api\/tech-error-reports\/teams\/\d+\/channel-order$/.test(p) && req.method() === 'PUT') {
      // Toplu kanal sırası (R14 inceleme 2026-10-17): takımın tüm dizilişi tek istekte; sunucu gibi 1…n yazar
      const team = Number(p.split('/')[4])
      const { ids = [] } = JSON.parse(req.postData() || '{}')
      orderPuts.push({ team, ids })
      ids.forEach((id, i) => { const c = (overviewOverride || []).find((x) => x.id === id); if (c) c.sort_order = i + 1 })
      body = { success: true, data: { team_id: team, changed: ids.length, channels: ids.map((id, i) => ({ id, sort_order: i + 1 })) } }
    } else if (p === '/api/tech-error-reports/reports/100/history') {
      body = { success: true, data: HISTORY }
    } else if (p === '/api/tech-error-reports/channels/10/template/versions') {
      body = { success: true, data: VERSIONS }
    } else if (/^\/api\/tech-error-reports\/channels\/10\/template\/versions\/[23]$/.test(p)) {
      const v = Number(p.split('/').pop())
      body = { success: true, data: { version: v, saved_at: '2026-10-0' + v + 'T08:00:00Z', saved_by_name: LONG_PERSON,
        template: v === 2 ? TEMPLATE_V2 : TEMPLATE, current: v === 3 } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/template$/.test(p)) {
      const id = Number(p.split('/')[4])
      if (req.method() === 'PUT') {
        savedTemplate = { id, ...JSON.parse(req.postData() || '{}') }
        body = { success: true, data: { template: savedTemplate.template, version: 4 } }
      } else body = { success: true, data: { channel: channel(id, id === 10 ? LONG_CHANNEL : 'POS'), template: templateOverride || TEMPLATE, version: 3 } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/reports$/.test(p)) {
      const id = Number(p.split('/')[4])
      body = { success: true, data: { channel: channel(id, id === 10 ? LONG_CHANNEL : 'POS'), default_period: { year: 2026, month: 9 },
        reports: reportsOverride || [{ id: 100, year: 2026, month: 9, status: 'draft', title: `${id === 10 ? LONG_CHANNEL : 'POS'} Kanalı Teknik Hata Mesajları - Eylül 2026`,
          updated_at: '2026-10-02T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: true, image_count: 0 },
        { id: 80, year: 2025, month: 12, status: 'sent', title: `${id === 10 ? LONG_CHANNEL : 'POS'} Kanalı Teknik Hata Mesajları - Aralık 2025`,
          updated_at: '2026-01-05T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: false, image_count: 2,
          last_sent_at: '2026-01-05T08:00:00Z' }] } }
    } else if (p === '/api/tech-error-reports/reports/100') {
      if (req.method() === 'PUT') {
        // Otomatik kayıt 30 sn'de bir de gider (yavaş makinede elle kayıttan önce araya girebilir): ayrı tutulur, her
        // kayıt okunan sürümü bir artırır — elle kaydın sürümü araya giren otomatik kayıtların sürümünü taşır
        const put = JSON.parse(req.postData() || '{}')
        if (put.auto) autoSaves.push(put)
        else savedReport = { ...put, autoBefore: autoSaves.length }
        body = { success: true, data: { version: (put.version ?? 4) + 1, changed: true, updated_at: '2026-10-13T09:00:00Z', values: put.values } }
      } else body = { success: true, data: reportOverride || REPORT }
    } else if (/^\/api\/tech-error-reports\/reports\/100\/images\/[a-z0-9-]+$/.test(p) && req.method() === 'POST') {
      const slotId = p.split('/').pop()
      uploads.push(req.headers()['content-type'] || '')
      uploadSizes.push(pngSize(req.postDataBuffer() || Buffer.alloc(0)))
      uploadBodies.push(req.postDataBuffer() || Buffer.alloc(0))
      body = { success: true, data: { id: 500 + uploads.length, slot_id: slotId, content_type: 'image/png', file_name: `${slotId}.png`,
        width: 1, height: 1, size_bytes: PNG.length } }
    } else if (p === '/api/tech-error-reports/reports/100/mail-preview') {
      body = { success: true, data: { subject: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026`, delivery: 'TEAM',
        to: ['dijital-kanallar@example.com'], cc: [], to_count: 1, cc_count: 0, recipients_hidden: false, problem: null,
        file_name: 'mobil-teknik-hata-2026-09.pdf', images_embedded: 1, images_skipped: 0,
        provenance: { created_by: 'Ayşe Yılmaz', created_at: '2026-09-03T11:05:00Z', updated_by: 'Ali Veli', updated_at: '2026-09-05T06:12:00Z',
          approval_state: null, approved_by: null, approved_at: null },
        html: '<!doctype html><html><body style="margin:0;font-family:Arial"><div data-slot="te-mail-band" style="background:#0f172a;color:#fff;padding:24px">TEKNİK HATA RAPORU · EYLÜL 2026</div><p style="padding:16px">Yönetici Özeti — önizleme gövdesi</p></body></html>',
        text: 'metin' } }
    } else if (p === '/api/tech-error-reports/reports/100/mail-preview/test' && req.method() === 'POST') {
      testMails++
      body = { success: true, data: { sent_to: 'ayse.yilmaz@example.com', status: 'SENT' } }
    } else if (p === '/api/tech-error-reports/reports/100/send') {
      const sent = req.postData() ? JSON.parse(req.postData()) : null
      sendBodies.push(sent)
      // R18 / C13: sunucunun ek Bilgi alan hatası (Bilgi toplamı 20'yi aşıyor) — `reddet@` adresiyle tetiklenir
      if (sent?.extra_cc && sent.extra_cc.includes('reddet@')) {
        await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'VALIDATION_FAILED',
          field: 'extra_cc', fields: { extra_cc: 'x' },
          error: 'Bilgi (CC) listesi en çok 20 adres olabilir: takımın Bilgi listesinde 19 adres var, 2 ek adresle 21 oluyor. Ek adresleri 1 adrese indirin.' }) })
        return
      }
      sends++
      body = { success: true, data: { state: 'SENT', to: ['dijital-kanallar@example.com'], cc: sent?.extra_cc ? sent.extra_cc.split(', ') : [],
        subject: 'x', pdf_bytes: 1000 } }
    } else if (p === '/api/tech-error-reports/channels/10/send-log') {
      sendLogCalls.push(new URL(req.url()).search)
      body = { success: true, data: { rows: SENDLOG_ROWS, total: SENDLOG_ROWS.length, page: 0, size: 25, total_pages: 1, kind: 'all',
        status: 'all', recipients_hidden: false } }
    } else if (p === '/api/tech-error-reports/reports/100/approval/request') {
      approvalRequests++
      body = { success: true, data: { state: 'pending', approvers: [{ name: 'Mehmet Kaya', has_email: true }], mailed: 1, failed: 0,
        mail_problem: null, approval: { state: 'pending' } } }
    } else if (p === '/api/tech-error-reports/reports/100/approval/decide') {
      decisions.push(JSON.parse(req.postData() || '{}'))
      body = { success: true, data: { state: 'approved', send_state: 'SENT', sent_to: ['ceo@example.com'], approval: { state: 'approved' } } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea')) {
    const r = b.getBoundingClientRect()
    // Onay kutusu 16 px görünür ama dokunmatikte ::after ile 40 px vuruş alanı taşır (shadcn/checkbox.jsx proje eki)
    if (r.width === 0 || r.height === 0 || b.disabled || b.type === 'file' || b.getAttribute('role') === 'checkbox') continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    // Radyo (seçim kartı): vuruş alanı kartın tamamı — kapsayan label ölçülür (team-access e2e ile aynı kural)
    let h = r.height
    if (b.getAttribute('role') === 'radio' || b.type === 'radio') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height ?? 0)
    // Takım rozeti gibi küçük görünen hedefler dokunmatikte ::after ile dikeyde büyür (TEAM_BADGE_TOUCH, ~42 px) — o alan sayılır
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0, bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  // Ekran okuyucuya özel metin (sr-only) kasıtlı olarak 1 px'tir — kırpılma sayılmaz. Kopyalama düğmeli satırlar
  // (te-copy-row, 2026-10-16) çocuklu olduğu ve içindeki metin satır içi olduğu için kabın kendisi ölçülür.
  return [...root.querySelectorAll('span, p, li, h2, h3, label, button, [data-slot="te-copy-row"]')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only'))
    .filter((n) => (n.childElementCount === 0 || n.dataset.slot === 'te-copy-row') && n.textContent.trim().length > 20
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

/**
 * Takım rozetinin GENİŞLETİLMİŞ vuruş alanı (rozet kutusu ± `::after` taşması, TEAM_BADGE_TOUCH) kanal adı düğmesinin
 * kutusuyla kesişmesin (R14 inceleme 2026-10-17: 6 px aralıkta rozetin 10 px'lik `::after`'ı ada biniyordu — dokunulan ad
 * değil takım penceresi açılırdı). Kesişen satırların kanal adlarını döner; 0,5 px altı yuvarlama sayılmaz.
 */
function badgeHitOverlaps(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const item of root.querySelectorAll('[data-slot="te-channel-row"], [data-slot="te-channel-card"]')) {
    const name = item.querySelector('[data-slot="te-channel-name"]')
    const badge = item.querySelector('[data-slot="team-badge"]')
    if (!name || !badge) continue
    const n = name.getBoundingClientRect()
    const b = badge.getBoundingClientRect()
    const af = getComputedStyle(badge, '::after')
    let top = b.top, bottom = b.bottom, left = b.left, right = b.right
    if (af.position === 'absolute' && af.content !== 'none') {
      top += Math.min(0, parseFloat(af.top) || 0)
      bottom -= Math.min(0, parseFloat(af.bottom) || 0)
      left += Math.min(0, parseFloat(af.left) || 0)
      right -= Math.min(0, parseFloat(af.right) || 0)
    }
    const overlapY = Math.min(bottom, n.bottom) - Math.max(top, n.top)
    const overlapX = Math.min(right, n.right) - Math.max(left, n.left)
    if (overlapY > 0.5 && overlapX > 0.5) out.push(`${name.textContent.trim().slice(0, 30)} ${Math.round(overlapY)}px`)
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Kanal teknik hata raporları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kanallar sığar; "Şablonu aç" tıklanan kanalın şablonunu açar; şablon sürümle kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports')
      // 8. sürüm: geniş kapta (≥ 960 px) tablo satırı, dar kapta kart — ikisi de aynı eylem yuvalarını taşır
      const cards = page.locator('[data-slot="te-channel-card"], [data-slot="te-channel-row"]')
      await expect(cards).toHaveCount(2, { timeout: 20_000 })
      await expect(page.locator('[data-slot="te-channel-list"]')).toHaveAttribute('data-layout', vp.width >= 1280 ? 'table' : 'cards')
      await expect(cards.first()).toContainText(LONG_CHANNEL)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channels"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel-list"]'), 'küçük hedef').toEqual([])

      await cards.nth(1).locator('[data-slot="te-open-template"]').click()   // POS
      await expect(page.locator('[data-slot="te-template-editor"]')).toBeVisible()
      await expect(page.locator('[data-slot="te-channel-title"]')).toHaveText('POS')
      await expect.poll(() => new URL(page.url()).searchParams.get('te_ch')).toBe('20')

      await page.locator('[data-slot="te-back"]').click()
      await cards.first().locator('[data-slot="te-open-template"]').click()   // uzun adlı kanal
      await expect(page.locator('[data-slot="te-channel-title"]')).toHaveText(LONG_CHANNEL)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_ch')).toBe('10')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon)').toEqual([])

      // Yönetici özeti cümlesi ve tablo düzenleyicisi de sığar; cümleye alan eklenir, tabloya grup satırı eklenir
      const sum = page.locator('[data-slot="te-tpl-section"][data-kind="summary"]')
      const firstLine = sum.locator('textarea').first()
      await firstLine.fill('Oran')
      await sum.locator('[data-slot="te-tpl-insert-field"]').first().click()
      await page.getByRole('menuitem', { name: /Kritik fonksiyonların SLA oranı/ }).click()
      await expect(firstLine).toHaveValue('Oran {f:sla-rate}')
      await expect(sum.locator('[data-slot="te-tpl-line-preview"]').first()).toContainText('[Kritik fonksiyonların SLA oranı]')
      const table = page.locator('[data-slot="te-tpl-section"][data-kind="table"]')
      await table.getByRole('button', { name: /Satır ekle|Add row/ }).click()
      await table.locator('[data-slot="te-tpl-row"]').last().getByRole('checkbox').click()
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon özet/tablo)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon özet/tablo)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon özet/tablo)').toEqual([])

      const slots = page.locator('[data-slot="te-tpl-section"][data-kind="images"]')
      await slots.getByRole('button', { name: /Görsel yuvası ekle|Add image slot/ }).click()
      await page.locator('[data-slot="te-tpl-save"]').click()
      await expect.poll(() => savedTemplate?.version).toBe(3)
      expect(savedTemplate.id).toBe(10)
      expect(savedTemplate.template.sections.find((s) => s.id === 'charts').slots).toHaveLength(4)
      expect(savedTemplate.template.sections.find((s) => s.id === 'exec').lines[0]).toBe('Oran {f:sla-rate}')
      expect(savedTemplate.template.sections.find((s) => s.id === 'sla').rows.at(-1)).toMatchObject({ group: true })
      expect(savedTemplate.template.sections.find((s) => s.id === 'auto-trend').series)
        .toEqual(['error-rate', 'total-tx', 'unmapped-count', 'outage-errors'])

      // R19: "Örnek PDF" düzenleyicideki GÜNCEL taslağı (kaydedilmemiş başlık dahil) gönderir, PDF tıklamada açılan sekmede
      // gösterilir; düğme hazırlanırken kilitli, sonra yine açık; araç çubuğu sığar, dokunma hedefleri ≥ 40 px
      await page.locator('#te-tpl-title').fill('Önizleme başlığı {kanal} {ay}')
      const popupP = page.waitForEvent('popup', { timeout: 15_000 }).catch(() => null)
      await page.locator('[data-slot="te-tpl-preview-pdf"]').click()
      await expect.poll(() => previewBodies.length).toBe(1)
      expect(previewBodies[0].id).toBe(10)
      expect(previewBodies[0].template.title).toBe('Önizleme başlığı {kanal} {ay}')
      expect(previewBodies[0].template.sections.find((s) => s.id === 'charts').slots).toHaveLength(4)
      const popup = await popupP
      expect(popup, 'örnek PDF sekmesi').not.toBeNull()
      await popup?.close().catch(() => {})
      await expect(page.locator('[data-slot="te-tpl-preview-pdf"]')).toBeEnabled()
      expect(await page.evaluate(overflowX), 'yatay taşma (örnek PDF araç çubuğu)').toBeLessThanOrEqual(1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-tpl-toolbar"]'), 'küçük hedef (araç çubuğu)').toEqual([])

      // Bölüm kartı yeniden tasarımı (2026-10-17): TÜM bölümler açıkken tek çerçeve + başlık ayracı; öge kartları yumuşak —
      // yatay taşma, kırpılan metin, küçük dokunma hedefi yok (tohum bölümleri açık getirir; kapalı kaldıysa "Tümünü aç")
      const tplAll = page.locator('[data-slot="te-tpl-toolbar"] [data-slot="te-sections-all"]')
      if (/Tümünü aç|Expand all/.test(await tplAll.innerText())) await tplAll.click()
      await expect(page.locator('[data-slot="te-tpl-section"][data-open="false"]')).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon, hepsi açık)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon, hepsi açık)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon, hepsi açık)').toEqual([])
      // Kullanıcı geri bildirimi (2026-10-17): açılan bölümün çevresindeki köşeli çizgi = tarayıcının varsayılan fieldset
      // kenarlığı. Düzenleme kilidi fieldset'leri çerçevesiz ve boşluksuz olmalı.
      const fieldsetFrames = await page.evaluate(() => [...document.querySelectorAll('[data-slot="te-template-editor"] fieldset')]
        .map((f) => { const s = getComputedStyle(f); return `${s.borderTopWidth}/${s.paddingTop}/${s.marginLeft}` })
        .filter((v) => v !== '0px/0px/0px'))
      expect(fieldsetFrames, 'fieldset kenarlığı / boşluğu (şablon)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-template-${vp.name}.png` })
      // Tam sayfa görüntüsü EN SONDA (dokunmatik öykünmeyi sıfırlar)
      await page.screenshot({ path: `test-results/te-sections-template-${vp.name}.png`, fullPage: true })
    })

    test('liste yeniden tasarımı (2026-10-17, 8. sürüm): dönem kartı süzgeç, arama, sıralama, sayfalama; kanalda rapor süzgeçleri + sayfalama; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(120_000)
      const many = []
      for (let i = 1; i <= 60; i++) {
        const extra = i <= 20 ? { period_report: { id: 1000 + i, status: 'sent' } }
          : i <= 40 ? { period_report: { id: 1000 + i, status: 'draft' } } : i <= 55 ? {} : { active: false }
        // sort_order = i: "Sıra" sıralamasında Kanal 01, 02, … (R14) — taşıma iki PUT'luk yer değiştirme olur
        many.push({ ...channel(i, i === 10 ? LONG_CHANNEL : `Kanal ${String(i).padStart(2, '0')}`), period_report: null, sort_order: i, ...extra })
      }
      const months = ['', 'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']
      const reps = []
      for (let i = 0, y = 2026, m = 9; i < 30; i++) {
        const status = y === 2026 ? (m % 2 ? 'draft' : 'changed') : y === 2025 ? 'sent' : 'queued'
        reps.push({ id: 900 - i, year: y, month: m, status, title: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - ${months[m]} ${y}`,
          updated_at: `${y}-${String(m).padStart(2, '0')}-20T07:00:00Z`, updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: status !== 'sent',
          last_sent_at: status === 'sent' ? `${y}-${String(m).padStart(2, '0')}-25T08:00:00Z` : null })
        m--
        if (m === 0) { m = 12; y-- }
      }
      await mock(page)
      overviewOverride = many
      reportsOverride = reps
      await page.goto('/?tab=channelreports')
      const rows = page.locator('[data-slot="te-channel-card"], [data-slot="te-channel-row"]')
      await expect(rows).toHaveCount(50, { timeout: 20_000 })        // page ön ayarı 50
      await expect(page.locator('[data-slot="te-count"]')).toHaveText(/60 .*60/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (liste)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channels"]'), 'kırpılan metin (liste)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channels"]'), 'küçük hedef (liste)').toEqual([])
      // Dokunmatikte takım rozetinin ::after vuruş alanı kanal adı düğmesine binmez (ad ↔ takım satırı 12 px)
      if (vp.touch) expect(await page.evaluate(badgeHitOverlaps, '[data-slot="te-channels"]'), 'rozet vuruş alanı adla çakışıyor').toEqual([])

      // "Yeni kanal" penceresi (kullanıcı isteği 2026-10-17): şablon kaynağı shadcn seçim kartları — 3 seçenek, sığar, 40 px
      await page.locator('[data-slot="te-new-channel"]').click()
      const dlg = page.locator('[data-slot="te-channel-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="te-source-options"] [role="radio"]')).toHaveCount(3)
      await expect(dlg.locator('[data-slot="te-source-options"] [role="radio"][data-state="checked"]')).toHaveCount(1)
      await dlg.locator('[data-slot="te-source-options"] [data-source="copy"]').click()
      await expect(dlg.locator('[data-slot="te-source-copy"]')).toBeVisible()
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, '[data-slot="te-channel-dialog"]'), 'kırpılan metin (yeni kanal)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel-dialog"]'), 'küçük hedef (yeni kanal)').toEqual([])
      const dlgBox = await dlg.boundingBox()
      expect(dlgBox.width, 'pencere ekrana sığar').toBeLessThanOrEqual(vp.width)
      await page.screenshot({ path: `test-results/channel-reports-new-channel-${vp.name}.png`, fullPage: false })
      await page.keyboard.press('Escape')
      await expect(dlg).toHaveCount(0)

      // Dönem özeti kartı süzgeçtir: başlanmamış 15 → yalnız onlar; URL te_st
      await page.locator('[data-slot="stat-item"][data-key="missing"]').click()
      const diag = () => page.evaluate(() => ({ url: location.search, q: document.querySelector('[data-slot="te-search"]')?.value,
        pressed: [...document.querySelectorAll('[data-slot="stat-item"]')].map((b) => `${b.dataset.key}:${b.getAttribute('aria-pressed')}`).join(' ') }))
      await expect(page.locator('[data-slot="te-count"]'), JSON.stringify(await diag())).toHaveText(/15 .*60/)
      await expect(rows).toHaveCount(15)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_st')).toBe('missing')
      await expect(page.locator('[data-slot="stat-item"][data-key="missing"]')).toHaveAttribute('aria-pressed', 'true')
      await page.locator('[data-slot="te-clear-filters"]').click()
      await expect(rows).toHaveCount(50)

      // Arama Türkçe katlar: uzun adlı kanal (10) "bireysel internet" ile bulunur
      await page.locator('[data-slot="te-search"]').fill('bireysel internet')
      await expect(rows).toHaveCount(1)
      await expect(rows.first()).toContainText(LONG_CHANNEL)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_q')).toBe('bireysel internet')
      await page.locator('[data-slot="te-clear-filters"]').click()

      // Sıralama duruma göre: ilk satır başlanmamış (Kanal 41); sayfalama: ikinci sayfa 10 satır, URL te_page=2
      await page.locator('[data-slot="te-sort"]').selectOption('status')
      await expect(rows.first()).toContainText('Kanal 41')
      await page.getByRole('button', { name: /^(Sonraki|Next)$/ }).first().click()
      await expect(rows).toHaveCount(10)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_page')).toBe('2')
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (2. sayfa)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channels"]'), 'kırpılan metin (2. sayfa)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channels"]'), 'küçük hedef (2. sayfa)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-list-${vp.name}.png`, fullPage: false })

      // Kanal sırası (R14, 2026-10-17): "Sıra" sıralaması → ipucu, sayfa 1'e döner (Kanal 01 ilk); satır menüsünde
      // "Yukarı / Aşağı taşı" (takımın ilkinde yukarı devre dışı); "Aşağı taşı" takımın TÜM dizilişini TEK istekle gönderir
      // (toplu uç, inceleme 2026-10-17 — kanal başına PUT yok), liste yeni sırayı gösterir ve canlı bölge söyler; sığar,
      // kesilmez, dokunma hedefleri
      await page.locator('[data-slot="te-sort"]').selectOption('order')
      await expect(page.locator('[data-slot="te-sort-hint"]')).toBeVisible()
      await expect.poll(() => new URL(page.url()).searchParams.get('te_sort')).toBe('order')
      await expect(rows).toHaveCount(50)
      await expect(rows.nth(0)).toContainText('Kanal 01')
      await expect(rows.nth(1)).toContainText('Kanal 02')
      const menuOf = (row) => row.getByRole('button', { name: /İşlemler|Kanal işlemleri|Actions|Channel actions/ })
      await menuOf(rows.nth(0)).click()
      // Arayüz dili e2e'de EN (varsayılan) — erişilebilir adlar iki dilde de kabul edilir
      const upFirst = page.getByRole('menuitem', { name: /^(Yukarı taşı|Move up): Kanal 01$/ })
      await expect(upFirst).toBeVisible()
      await expect(upFirst).toHaveAttribute('aria-disabled', 'true')
      await expect(page.getByRole('menuitem', { name: /^(Aşağı taşı|Move down): Kanal 01$/ })).not.toHaveAttribute('aria-disabled', 'true')
      await page.waitForTimeout(150)
      expect(await page.evaluate(clipped, '[role="menu"]'), 'kırpılan metin (sıra menüsü)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="menu"]'), 'küçük hedef (sıra menüsü)').toEqual([])
      await page.keyboard.press('Escape')
      await expect(page.getByRole('menu')).toHaveCount(0)
      await menuOf(rows.nth(1)).click()
      await page.getByRole('menuitem', { name: /^(Aşağı taşı|Move down): Kanal 02$/ }).click()
      await expect.poll(() => orderPuts.length).toBe(1)
      expect(orderPuts[0].team).toBe(5)
      expect(orderPuts[0].ids).toHaveLength(60)                                         // takımın tüm kanalları
      expect(orderPuts[0].ids.slice(0, 4)).toEqual([1, 3, 2, 4])
      expect(channelPuts).toEqual([])                                                   // kanal başına PUT yok
      await expect(rows.nth(1)).toContainText('Kanal 03')
      await expect(rows.nth(2)).toContainText('Kanal 02')
      await expect(page.locator('[data-slot="te-order-status"]')).toHaveText(/Kanal 02/)
      await expect(rows).toHaveCount(50)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (sıra)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channels"]'), 'kırpılan metin (sıra)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channels"]'), 'küçük hedef (sıra)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-order-${vp.name}.png`, fullPage: false })

      // Kanal 10'u aç (sayfa 1, ad sırası): rapor listesi 30 → 25 + 5; durum süzgeci; yıl süzgeci; arama
      await page.locator('[data-slot="te-sort"]').selectOption('name')
      await page.locator('[data-slot="te-search"]').fill('bireysel internet')
      await rows.first().locator('[data-slot="te-channel-name"]').click()
      await expect(page.locator('[data-slot="te-channel-title"]')).toHaveText(LONG_CHANNEL)
      await expect(page.locator('[data-slot="te-channel-stats"]')).toContainText('30')
      const rrows = page.locator('[data-slot="te-report-row"]')
      await expect(rrows).toHaveCount(25)
      await expect(page.locator('[data-slot="te-rcount"]')).toHaveText(/30 .*30/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kanal)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channel"]'), 'kırpılan metin (kanal)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel"]'), 'küçük hedef (kanal)').toEqual([])
      await page.locator('[data-slot="te-rstatus"]').selectOption('sent')
      await expect(rrows).toHaveCount(12)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_rst')).toBe('sent')
      await page.locator('[data-slot="te-rstatus"]').selectOption('all')
      await page.locator('[data-slot="te-ryear"]').selectOption('2024')
      await expect(rrows).toHaveCount(9)
      await expect(page.locator('[data-slot="te-report-year"]')).toHaveCount(1)
      await page.locator('[data-slot="te-rclear"]').click()
      await expect(rrows).toHaveCount(25)
      await page.getByRole('button', { name: /^(Sonraki|Next)$/ }).first().click()
      await expect(rrows).toHaveCount(5)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_rpage')).toBe('2')
      await page.locator('[data-slot="te-rsearch"]').fill('nisan 2024')
      await expect(rrows).toHaveCount(1)

      // Kanallara dön: rapor süzgeçleri adresten düşer, liste araması korunur (aynı tek sonuç)
      await page.locator('[data-slot="te-back"]').click()
      await expect(rows).toHaveCount(1)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_rq')).toBeNull()
      expect(new URL(page.url()).searchParams.get('te_q')).toBe('bireysel internet')
      await page.screenshot({ path: `test-results/channel-reports-list-back-${vp.name}.png`, fullPage: false })
    })

    test('başka kanaldan şablon al (2026-10-17, D16): pencere sığar, diğer kanal seçilir, taslak olarak yüklenir ve bu kanalın sürümüyle kaydedilir', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      // Kaynak kanalın (POS) şablonu farklı: İngilizce, başka başlık — mock'tan SONRA kaydedilen yol önceliklidir
      const POS_TPL = { ...TEMPLATE, lang: 'en', title: 'POS Channel Technical Error Messages - {month} {year}' }
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/channels/20/template', (route) => route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { channel: channel(20, 'POS'), template: POS_TPL, version: 9 } }) }))
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      await expect(page.locator('[data-slot="te-template-editor"]')).toBeVisible({ timeout: 20_000 })
      const open = page.locator('[data-slot="te-tpl-import"]')
      await expect(open).toBeVisible()
      if (vp.touch) expect((await open.boundingBox()).height, 'aç düğmesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      await open.click()
      const dlg = page.locator('[data-slot="te-tpl-import-dialog"]')
      await expect(dlg).toBeVisible()
      const picker = dlg.getByRole('combobox')
      await expect(picker).toBeVisible()
      await page.waitForTimeout(300)
      const box = await dlg.boundingBox()
      expect(box.x, 'pencere solda ekrana sığar').toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, 'pencere sağda ekrana sığar').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon al)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-tpl-import-dialog"]'), 'kırpılan metin (şablon al)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-tpl-import-dialog"]'), 'küçük hedef (şablon al)').toEqual([])

      // Liste: yalnız DİĞER kanal (bu kanal — uzun adlı 10 — yok); uzun takım adı seçenekte sarar
      await picker.dispatchEvent('mousedown')
      const options = page.getByRole('option')
      await expect(options).toHaveCount(1)
      await expect(options.first()).toContainText('POS')
      await expect(options.first()).toContainText(LONG_TEAM)
      await options.first().dispatchEvent('mousedown')
      await expect(dlg.locator('[data-slot="te-tpl-import-meta"]')).toBeVisible()
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, '[data-slot="te-tpl-import-dialog"]'), 'kırpılan metin (seçimden sonra)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-tpl-import-dialog"]'), 'küçük hedef (seçimden sonra)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-template-import-${vp.name}.png`, fullPage: false })

      await dlg.locator('[data-slot="te-tpl-import-load"]').click()
      await expect(dlg).toHaveCount(0)
      await expect(page.locator('#te-tpl-title')).toHaveValue(POS_TPL.title)
      await expect(page.locator('#te-tpl-lang')).toHaveValue('en')
      const save = page.locator('[data-slot="te-tpl-save"]')
      await expect(save).toBeEnabled()
      await save.click()
      await expect.poll(() => savedTemplate?.version).toBe(3)       // bu kanalın okunan sürümü — kaynağınki (9) değil
      expect(savedTemplate.id).toBe(10)
      expect(savedTemplate.template.title).toBe(POS_TPL.title)
      expect(savedTemplate.template.lang).toBe('en')
    })

    test('yıl arşivi (2026-10-17, C12): menü görünür ve sığar; seçilen yılın PDF\'leri tek ZIP olarak iner', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      const pdfCalls = []
      await page.route((u) => /^\/api\/tech-error-reports\/reports\/\d+\/pdf$/.test(new URL(u).pathname), (route) => {
        const id = new URL(route.request().url()).pathname.split('/')[4]
        pdfCalls.push(id)
        return route.fulfill({ status: 200, contentType: 'application/pdf', body: `%PDF-1.4 rapor ${id}`,
          headers: { 'Content-Disposition': `attachment; filename="kanal-teknik-hata-${id}.pdf"` } })
      })
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10')
      await expect(page.locator('[data-slot="te-report-row"]')).toHaveCount(2, { timeout: 20_000 })
      const trigger = page.locator('[data-slot="te-year-zip"]')
      await expect(trigger).toBeVisible()
      if (vp.touch) expect((await trigger.boundingBox()).height, 'ZIP düğmesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (kanal, ZIP)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channel"]'), 'kırpılan metin (kanal, ZIP)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel"]'), 'küçük hedef (kanal, ZIP)').toEqual([])

      await trigger.click()
      const menu = page.locator('[data-slot="te-year-zip-menu"]')
      await expect(menu).toBeVisible()
      const items = menu.locator('[data-slot="te-year-zip-item"]')
      await expect(items).toHaveCount(2)
      await expect(items.first()).toHaveAttribute('data-year', '2026')
      // Radix menü açılırken zoom-in-95 animasyonu oynar: ölçüm animasyon bitince (ölçek 1) alınır
      await menu.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => null))))
      const mb = await menu.boundingBox()
      expect(mb.x, 'menü solda sığar').toBeGreaterThanOrEqual(-1)
      expect(mb.x + mb.width, 'menü sağda sığar').toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) {
        for (const h of await items.evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height))) {
          expect(h, 'menü öğesi dokunma hedefi').toBeGreaterThanOrEqual(39.5)
        }
      }
      await page.screenshot({ path: `test-results/channel-reports-year-zip-${vp.name}.png`, fullPage: false })

      const [zip] = await Promise.all([page.waitForEvent('download'), items.nth(1).click()])   // 2025 (1 rapor)
      expect(zip.suggestedFilename()).toMatch(/^[a-z0-9-]+-2025-teknik-hata-raporlari\.zip$/)
      const files = unzipSync(new Uint8Array(readFileSync(await zip.path())))
      expect(Object.keys(files)).toEqual(['kanal-teknik-hata-80.pdf'])
      expect(strFromU8(files['kanal-teknik-hata-80.pdf'])).toBe('%PDF-1.4 rapor 80')
      expect(pdfCalls).toEqual(['80'])
      await expect(page.locator('[data-slot="te-year-zip-status"]')).toHaveText('')
      await expect(trigger).toBeEnabled()
    })

    test('özet kartına gömülü bölümler (2026-10-17): adet ve açıklama özet kartında, açıklama cümlenin altında; şablonda gösterim yeri', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      reportOverride = EMBED_REPORT
      templateOverride = EMBED_TEMPLATE
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const card = editor.locator('[data-slot="te-summary"]')
      const emb = card.locator('[data-slot="te-sum-embedded"][data-section="unmapped"]')
      await expect(emb).toBeVisible({ timeout: 20_000 })
      await expect(editor.locator('[data-slot="te-metrics"][data-section="unmapped"]')).toHaveCount(0)
      // Gömülü kart özet kartının içinde
      const cb = await card.boundingBox()
      const eb = await emb.boundingBox()
      expect(eb.x, 'gömülü kart solda içeride').toBeGreaterThanOrEqual(cb.x - 1)
      expect(eb.x + eb.width, 'gömülü kart sağda içeride').toBeLessThanOrEqual(cb.x + cb.width + 1)
      // Arayüz dili e2e'de EN (varsayılan): "1.250" EN'de 1,25 okunur — belirsiz olmayan yazım; özet şablon dilinde (TR) biçimler
      await emb.getByLabel('Map yapılmamış hata adedi', { exact: true }).fill('1250')
      await expect(card.locator('[data-slot="te-sum-text"]')).toContainText('Map yapılmamış hata adedi 1.250 olarak kaydedilmiştir.')
      await emb.getByLabel('Map yapılmamış hatalar hakkında açıklama', { exact: true }).fill('Yeni **kodlar** eşlenmedi')
      await expect(card.locator('[data-slot="te-sum-note"][data-note-field="unmapped-note"]')).toContainText('Yeni kodlar eşlenmedi')
      // Açıklama cümlenin altında, kapanış cümlesinden önce
      const parts = card.locator('[data-slot="te-sum-text"] > *')
      await expect(parts).toHaveCount(3)
      expect(await parts.evaluateAll((els) => els.map((e) => e.dataset.slot))).toEqual(['te-sum-part', 'te-sum-note', 'te-sum-part'])
      // Hiçbir cümlenin başvurmadığı evet/hayır kendi satırında (kalın değil)
      await emb.getByLabel('Yeni hata kodu var mı?', { exact: true }).getByRole('radio', { name: /^(Evet|Yes)$/ }).click()
      await expect(card.locator('[data-slot="te-sum-text"]')).toContainText('Yeni hata kodu var mı? Evet')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (gömülü)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (gömülü)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (gömülü)').toEqual([])
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.fields?.['unmapped-count']).toBe(1250)
      expect(savedReport.values.fields).toMatchObject({ 'unmapped-new': true, 'unmapped-note': 'Yeni **kodlar** eşlenmedi' })

      // Şablon düzenleyici: "Gösterim yeri" seçici, rozet, önizlemede özetin altında; "Ayrı bölüm olarak" → host kayda gitmez
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      const tplCard = page.locator('[data-slot="te-tpl-section"][data-section="unmapped"]')
      await expect(tplCard.locator('[data-slot="te-tpl-section-hosted"]')).toBeVisible({ timeout: 20_000 })
      const sel = tplCard.locator('[data-slot="te-tpl-host"] select')
      await expect(sel).toHaveValue('exec')
      await expect(page.locator('[data-slot="te-tpl-preview-embedded"][data-section="unmapped"]')).toBeVisible()
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon gömülü)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon gömülü)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon gömülü)').toEqual([])
      await sel.selectOption('')
      await expect(tplCard.locator('[data-slot="te-tpl-section-hosted"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="te-tpl-preview-section"][data-section="unmapped"]')).toBeVisible()
      await page.locator('[data-slot="te-tpl-save"]').click()
      await expect.poll(() => savedTemplate?.version).toBe(3)
      expect(savedTemplate.template.sections.find((s) => s.id === 'unmapped')).not.toHaveProperty('host')
      await page.screenshot({ path: `test-results/channel-reports-embed-${vp.name}.png` })
    })

    test('SLA hedefi ve renkli kutucuklar (2026-10-17, 4b): kutucuklar özetin başında (ton, fark, hedef), hedef karşılaştırması otomatik, raporun hedefi kaydedilir; şablonda hedef / yön / kutucuk seçimi', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      reportOverride = SLA_REPORT
      templateOverride = SLA_TEMPLATE
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const card = editor.locator('[data-slot="te-summary"]')
      const tiles = card.locator('[data-slot="te-tiles"] [data-slot="te-tile"]')
      await expect(tiles).toHaveCount(3, { timeout: 20_000 })
      // Oran %0,42 < geçen ay %0,6 → iyi (yüzde varsayılanı düşük-iyi); adet yönsüz → nötr, ok + fark; SLA hedefin altında → kötü
      await expect(card.locator('[data-slot="te-tile"][data-tile="error-rate"]')).toHaveAttribute('data-tone', 'good')
      await expect(card.locator('[data-slot="te-tile"][data-tile="total-tx"]')).toHaveAttribute('data-tone', 'neutral')
      await expect(card.locator('[data-slot="te-tile"][data-tile="total-tx"]')).toContainText('+200 (+%25)')
      const sla = card.locator('[data-slot="te-tile"][data-tile="sla-rate"]')
      await expect(sla).toHaveAttribute('data-tone', 'bad')
      await expect(sla).toContainText(/(hedef|target) %99,95/)
      // Kutucuklar özet kartının içinde; geniş ekranda yan yana, telefonda alt alta; metin kesilmez
      const cb = await card.boundingBox()
      const boxes = await tiles.evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height } }))
      for (const b of boxes) {
        expect(b.x, 'kutucuk solda içeride').toBeGreaterThanOrEqual(cb.x - 1)
        expect(b.x + b.w, 'kutucuk sağda içeride').toBeLessThanOrEqual(cb.x + cb.width + 1)
      }
      if (vp.width >= 1024) expect(boxes[0].y, 'üç kutucuk yan yana').toBe(boxes[2].y)
      else if (vp.width < 640) expect(boxes[1].y, 'telefonda alt alta').toBeGreaterThan(boxes[0].y + boxes[0].h - 1)
      // Hedef karşılaştırması sorulmaz: çıktı Hayır; SLA cümlesi hedef ve puan farkıyla; fark cümlesi geçen aydan
      await expect(editor.getByLabel('SLA hedefin üstünde mi?', { exact: true })).toHaveText(/^(Hayır|No)$/)
      await expect(card.locator('[data-slot="te-sum-text"]')).toContainText('SLA oranı %99,9 (hedef %99,95, fark −0,05 puan) ile hedefin altında kalmıştır.')
      await expect(card.locator('[data-slot="te-sum-text"]')).toContainText('Önceki aya göre toplam işlem adedi +200 (+%25) değişmiştir.')
      // Raporun o aya özel hedefi (arayüz dili EN: ondalık nokta): karşılaştırma, kutucuk ve cümle yeni hedefi kullanır
      const target = editor.getByLabel(/^(Bu ayın hedefi|This month's target)/)
      await target.fill('99.8')
      await expect(editor.getByLabel('SLA hedefin üstünde mi?', { exact: true })).toHaveText(/^(Evet|Yes)$/)
      await expect(sla).toHaveAttribute('data-tone', 'good')
      await expect(card.locator('[data-slot="te-sum-text"]')).toContainText('(hedef %99,8, fark +0,1 puan) ile hedef başarıyla tutturulmuştur.')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kutucuklar)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (kutucuklar)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (kutucuklar)').toEqual([])
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.targets?.['sla-rate']).toBe(99.8)
      expect(savedReport.values.fields).not.toHaveProperty('sla-above')          // hesaplanan cevap istemciden gitmez

      // Şablon düzenleyici: hedef + yön yüzde alanında, yalnız yön adet alanında; karşılaştırma hesabı; kutucuk listesi
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      const tplEditor = page.locator('[data-slot="te-template-editor"]')
      const slaField = tplEditor.locator('[data-slot="te-tpl-field"][data-field="field:sla-rate"]')
      await expect(slaField.locator('[data-slot="te-tpl-target"] input')).toHaveValue(/99[.,]95/, { timeout: 20_000 })
      await expect(slaField.locator('[data-slot="te-tpl-better"]')).toHaveValue('higher')
      const totalField = tplEditor.locator('[data-slot="te-tpl-field"][data-field="field:total-tx"]')
      await expect(totalField.locator('[data-slot="te-tpl-target"] input')).toHaveCount(0)
      await expect(totalField.locator('[data-slot="te-tpl-better"]')).toHaveValue('')
      await expect(tplEditor.locator('[data-slot="te-tpl-field"][data-field="field:sla-above"] [data-slot="te-tpl-calc"] select').first()).toHaveValue('compare')
      const tilesBox = tplEditor.locator('[data-slot="te-tpl-tiles"]')
      await expect(tilesBox.locator('[data-slot="te-tpl-tiles-count"]')).toHaveText(/3 \/ 6/)
      await tilesBox.locator('[data-slot="te-tpl-tile-pick"]').selectOption('error-rate-excl')
      await tilesBox.locator('[data-slot="te-tpl-tile-add"]').click()
      await expect(tilesBox.locator('[data-slot="te-tpl-tiles-count"]')).toHaveText(/4 \/ 6/)
      await expect(tilesBox.locator('[data-slot="te-tpl-tile"]')).toHaveCount(4)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon hedef)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon hedef)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon hedef)').toEqual([])
      await page.locator('[data-slot="te-tpl-save"]').click()
      await expect.poll(() => savedTemplate?.version).toBe(3)
      expect(savedTemplate.template.sections[0].tiles).toEqual(['error-rate', 'total-tx', 'sla-rate', 'error-rate-excl'])
      expect(savedTemplate.template.sections[1].fields.find((f) => f.id === 'sla-rate')).toMatchObject({ target: 99.95, better: 'higher' })
      await page.screenshot({ path: `test-results/channel-reports-sla-tiles-${vp.name}.png` })
    })

    test('otomatik oranlar (2026-10-16): oranlar adetlerden; adet girişinin altında yüzde; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      await mock(page)
      reportOverride = CALC_REPORT
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor.getByLabel('Teknik hata oranı', { exact: true })).toHaveText(/^(%0,42|0\.42%)$/, { timeout: 20_000 })
      await expect(editor.getByLabel('Önceki aya göre artış var mı?', { exact: true })).toHaveText(/^(Evet|Yes)$/)
      await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-calc-derived"]'))
        .toContainText(/Kota hatalarının teknik hatalara oranı: (%0,24|0\.24%)/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (oranlar)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (oranlar)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (oranlar)').toEqual([])
      // Hesap kutusu kartın içinde (uzun ad sararsa bile taşmaz)
      const box = await editor.locator('[data-field="values.unmapped-rate"] [data-slot="te-calc"]').boundingBox()
      const card = await editor.locator('[data-slot="te-metrics"][data-section="rates"]').boundingBox()
      expect(box.x + box.width).toBeLessThanOrEqual(card.x + card.width + 1)
      // Adet değişince oran anında güncellenir
      await editor.getByLabel('Kota hatası adedi', { exact: true }).fill('2500')
      await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-calc-derived"]')).toContainText(/%0,47|0\.47%/)
      await expect(editor.getByLabel('Kota hatalarının teknik hatalara oranı', { exact: true })).toHaveText(/^(%0,47|0\.47%)$/)
      await page.screenshot({ path: `test-results/channel-reports-calc-${vp.name}.png` })
    })

    test('rapor: sayı çözülür ve sürümle kaydedilir; grafik ızgarası; görsel yükleme; gönderim onayı', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor.locator('[data-slot="te-report-title"]')).toContainText(`${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026`, { timeout: 20_000 })
      await expect(editor.locator('[data-slot="te-issue-title"]')).toContainText('Kart Limiti Sorgulama Servisi Konusu ile yaşanan problem')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (rapor)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (rapor)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (rapor)').toEqual([])

      // A4 (2026-10-17): yapışkan eylem çubuğu (telefonda 2–3 satıra sarılır) son bölüm kartını ÖRTMEZ — kabın alt boşluğu
      // çubuğun GERÇEK yüksekliğini izler (useElementHeight); sayfanın en altında kart çubuğun üstünde biter
      const actionBar = page.locator('[data-slot="te-report-actions"]')
      await actionBar.evaluate((el) => el.scrollIntoView({ block: 'end' }))
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
      await page.waitForTimeout(300)
      const lastSection = editor.locator(':scope > [data-slot^="te-"]:has([data-slot="te-section-toggle"])').last()
      const lastBox = await lastSection.boundingBox()
      const barBox = await actionBar.boundingBox()
      expect(lastBox, 'son bölüm kartı ölçülür').not.toBeNull()
      expect(barBox.height, 'eylem çubuğu ölçülür').toBeGreaterThan(0)
      expect(lastBox.y + lastBox.height, 'son bölüm kartı eylem çubuğunun üstünde biter (örtülmez)').toBeLessThanOrEqual(barBox.y + 1)
      const padBottom = await editor.evaluate((el) => parseFloat(getComputedStyle(el).paddingBottom))
      expect(padBottom, 'alt boşluk = çubuk yüksekliği + 16 (sabit pb-24 değil)').toBeGreaterThanOrEqual(barBox.height + 15)
      expect(padBottom).toBeLessThanOrEqual(barBox.height + 17)
      await page.evaluate(() => window.scrollTo(0, 0))

      // Grafik ızgarası: 768+ yarımlar yan yana, tam iki kolon; telefonda alt alta
      const a = await editor.locator('[data-slot-id="monthly-tx"]').boundingBox()
      const b = await editor.locator('[data-slot-id="tech-errors"]').boundingBox()
      const full = await editor.locator('[data-slot-id="daily-errors"]').boundingBox()
      if (vp.width >= 768) {
        expect(Math.abs(a.y - b.y), 'yarım yuvalar aynı satırda').toBeLessThanOrEqual(2)
        expect(full.width, 'tam yuva iki kolon').toBeGreaterThan(a.width * 1.8)
      } else {
        expect(b.y, 'telefonda alt alta').toBeGreaterThan(a.y + a.height - 1)
        expect(Math.abs(full.width - a.width)).toBeLessThanOrEqual(2)
      }

      // 2026-10-14: "Dönemde kesinti oldu mu?" → "Hayır" kesinti alanlarını muaf eder (gizli + not), "Evet" geri getirir
      await expect(editor.getByLabel('Kesinti günleri hariç teknik hata oranı', { exact: true })).toBeVisible()
      const outage = editor.getByLabel('Dönemde kesinti oldu mu?', { exact: true })
      await outage.getByRole('radio', { name: /^(Hayır|No)$/ }).click()
      await expect(editor.getByLabel('Kesinti günleri hariç teknik hata oranı', { exact: true })).toHaveCount(0)
      await expect(editor.getByLabel('Kesinti kaynaklı teknik hata adedi', { exact: true })).toHaveCount(0)
      await expect(editor.locator('[data-slot="te-exempt-note"]')).toBeVisible()
      expect(await page.evaluate(overflowX), 'yatay taşma (muaf)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (muaf)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (muaf)').toEqual([])

      // Kısaltmalı giriş (2026-10-16): "1,25M" yazıldığı gibi kalır, altında anlaşılan tam sayı; kayda 1.250.000 gider
      const totalInput = editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })
      await totalInput.fill('1,25M')
      await expect(totalInput).toHaveValue('1,25M')
      await expect(editor.locator('[data-field="values.total-tx"] [data-slot="te-number-expanded"]')).toContainText(/= (1\.250\.000|1,250,000)/)

      // Yönetici özeti: girilen rakamla canlı yazılır, rakam kalın; bekleyen cümle neyi beklediğini söyler
      const summary = editor.locator('[data-slot="te-sum-text"]')
      await expect(summary).toContainText(`${LONG_CHANNEL} Kanalından yapılan toplam işlem adedi 1.250.000 olarak gerçekleşmiştir.`)
      await expect(summary.locator('strong', { hasText: '1.250.000' })).toBeVisible()
      await expect(editor.locator('[data-slot="te-sum-pending"]')).toContainText(/Kritik fonksiyonların SLA oranı/)

      // Tablo: geniş kapta gerçek tablo, dar kapta kart; ikisinde de yatay sayfa taşması yok
      const tableBlock = editor.locator('[data-slot="te-table"] [data-layout]')
      const layout = await tableBlock.getAttribute('data-layout')
      if (vp.width < 768) expect(layout, 'telefonda kart').toBe('cards')
      if (vp.width >= 1280) expect(layout, 'geniş ekranda tablo').toBe('table')
      await editor.getByLabel(`${LONG_CHANNEL} Kanalı Toplam — Fonksiyon adedi`).or(
        editor.locator('[data-row="total"]').getByLabel('Fonksiyon adedi', { exact: true })).fill('1250')
      await editor.getByLabel(`${LONG_CHANNEL} Kanalı Toplam — Erişilebilirlik (kesintisiz)`).or(
        editor.locator('[data-row="total"]').getByLabel('Erişilebilirlik (kesintisiz)', { exact: true })).fill('99,5')

      // Açıklama: madde + kalın araç çubuğu, önizleme madde işaretini gösterir
      const note = editor.getByLabel('Açıklama', { exact: true })
      await note.fill('Limit sorgusu yavaşladı')
      const noteBox = editor.locator('[data-field="values.unmapped-note"] [data-slot="te-rich-input"]')
      await noteBox.locator('[data-tool="bullet"]').click()
      await expect(note).toHaveValue('- Limit sorgusu yavaşladı')
      await noteBox.locator('[data-tool="preview"]').click()
      await expect(noteBox.locator('[data-slot="te-rich-item"]')).toContainText('Limit sorgusu yavaşladı')

      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (özet/tablo)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (özet/tablo)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (özet/tablo)').toEqual([])

      await page.locator('[data-slot="te-save"]').click()
      // Okunan sürümle (araya otomatik kayıt girdiyse onun döndürdüğü sürümle) gider; elle kayıtta `auto` yok
      await expect.poll(() => savedReport && savedReport.version - savedReport.autoBefore).toBe(4)
      expect(savedReport).not.toHaveProperty('auto')
      expect(savedReport.values.fields).toMatchObject({ 'error-rate': 0.42, 'total-tx': 1250000, 'had-outage': false,
        'unmapped-note': '- Limit sorgusu yavaşladı' })
      expect(savedReport.values.fields).not.toHaveProperty('error-rate-excl')
      expect(savedReport.values.tables).toEqual({ sla: { total: { functions: 1250, availability: 99.5 } } })
      await expect(page.locator('[data-slot="te-report-actions"]')).toContainText(/Tüm değişiklikler kaydedildi|All changes saved/)

      // Yuvaya sığan PNG (en az 16 px — sunucu kuralı) olduğu gibi yüklenir
      await editor.locator('[data-slot-id="monthly-tx"] input[type="file"]').setInputFiles({ name: 'aylik-islem.png', mimeType: 'image/png',
        buffer: stripedPng(400, 200) })
      await expect.poll(() => uploads.length).toBe(1)
      expect(uploads[0]).toContain('multipart/form-data')
      await expect(editor.locator('[data-slot-id="monthly-tx"] img')).toBeVisible()
      // Görsel büyütme (2026-10-16): tıklayınca pencere açılır, ekrana sığar, düğmeler dokunmatikte ≥ 40 px, × kapatır
      await editor.locator('[data-slot-id="monthly-tx"] [data-slot="te-image-zoom"]').click()
      const zoomBox = page.locator('[data-slot="te-image-lightbox"]')
      await expect(zoomBox).toBeVisible()
      await page.waitForTimeout(300)
      const zb = await zoomBox.boundingBox()
      expect(zb.x, 'pencere solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(zb.y, 'pencere üstte taşmaz').toBeGreaterThanOrEqual(-1)
      expect(zb.x + zb.width, 'pencere sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(zb.y + zb.height, 'pencere altta taşmaz').toBeLessThanOrEqual(vp.height + 1)
      if (vp.width < 640) expect(zb.width, 'telefonda tam ekran (sağda boşluk yok)').toBeGreaterThanOrEqual(vp.width - 1)
      // "Sığdır": görsel görünüm alanının içinde (uzun adda da altı taşmaz)
      const viewBox = await zoomBox.locator('[data-slot="te-image-lightbox-view"]').boundingBox()
      const imgBox = await zoomBox.locator('[data-slot="te-image-lightbox-img"]').boundingBox()
      expect(imgBox.y + imgBox.height, 'görsel görünümün içinde').toBeLessThanOrEqual(viewBox.y + viewBox.height + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-image-lightbox"]'), 'kırpılan metin (büyütme)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-image-lightbox"]'), 'küçük hedef (büyütme)').toEqual([])
      await zoomBox.locator('[data-slot="te-image-lightbox-zoom"] [role="radio"]').nth(2).click()
      await expect(zoomBox.locator('[data-slot="te-image-lightbox-view"]')).toHaveAttribute('data-zoom', '200')
      await zoomBox.locator('[data-slot="te-image-lightbox-close"]').click()
      await expect(zoomBox).toHaveCount(0)
      // Görsel yuvanın genişliğini doldurur (kullanıcı kararı 2026-10-16): içeride / dışarıda farklı ölçü yok
      await expect.poll(async () => {
        const img = await editor.locator('[data-slot-id="monthly-tx"] [data-slot="te-image"]').boundingBox()
        const head = await editor.locator('[data-slot-id="monthly-tx"] > div').first().boundingBox()
        return img && head ? Math.abs(img.width - head.width) : 999
      }).toBeLessThanOrEqual(1)

      await page.locator('[data-slot="te-send"]').click()
      const dlg = page.getByRole('dialog', { name: /Rapor takıma gönderilsin mi\?|Send the report to the team\?/ })
      await expect(dlg).toBeVisible()
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect.poll(() => sends).toBe(1)

      // Bölüm kartı yeniden tasarımı (2026-10-17): TÜM bölümler açıkken tek çerçeve + başlık ayracı — yatay taşma, kırpılan
      // metin, küçük dokunma hedefi yok (tohum bölümleri açık getirir; kapalı kaldıysa gezinme satırındaki "Tümünü aç")
      const allBtn = editor.locator('[data-slot="te-report-nav"] [data-slot="te-sections-all"]')
      if (/Tümünü aç|Expand all/.test(await allBtn.innerText())) await allBtn.click()
      await expect(editor.locator('[data-section][data-open="false"]')).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (rapor, hepsi açık)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (rapor, hepsi açık)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (rapor, hepsi açık)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-report-${vp.name}.png` })
      // Tam sayfa görüntüsü EN SONDA (dokunmatik öykünmeyi sıfırlar)
      await page.screenshot({ path: `test-results/te-sections-report-${vp.name}.png`, fullPage: true })
    })
  
    test('Faz C: otomatik trend sığar, etiketler binmez; büyük görsel tarayıcıda küçültülür, PDF yüklenmez', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const block = editor.locator('[data-slot="te-trend"]')
      await expect(block).toBeVisible({ timeout: 20_000 })
      const charts = block.locator('[data-slot="te-trend-chart"]')
      await expect(charts).toHaveCount(4)
      await expect(block.locator('[data-field="error-rate"]')).toHaveAttribute('data-points', '12')
      await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-points', '11')
      await expect(block.locator('[data-field="total-tx"] figcaption')).toHaveText('Toplam işlem adedi (Son 12 Ay)')
      await expect(block.locator('[data-field="outage-errors"] [data-slot="te-trend-note"]'))
        .toContainText(/en az iki ayın rakamı|at least two months/)
      // Çubuklar çizildi; ay adları 12, ilk ayın ve Ocak'ın altında yıl
      await expect(block.locator('[data-field="total-tx"] .recharts-bar-rectangle')).toHaveCount(11, { timeout: 10_000 })
      // Ay adları: genişse 12, dar grafikte bir atlanarak (raporun ayı hep görünür); yıl görünen ilk ayda ve yıl değişince
      const ticks = block.locator('[data-field="total-tx"] [data-slot="te-trend-tick"]')
      await expect.poll(() => ticks.count()).toBeGreaterThanOrEqual(6)
      const tickTexts = await ticks.allTextContents()
      expect(tickTexts.at(-1)).toBe('Eyl')
      expect(tickTexts[0]).toMatch(/^(Eki|Kas)\s*2025$/)
      expect(tickTexts.filter((x) => /2026$/.test(x))).toHaveLength(1)
      if (vp.width >= 1280) expect(tickTexts).toHaveLength(12)
      await page.waitForTimeout(300)
      // Çubuk etiketleri komşusuyla çakışmaz ve grafiğin dışına taşmaz (telefonda yalnız bu ayınki yazılır)
      const labelProblems = await page.evaluate(() => {
        const out = []
        for (const fig of document.querySelectorAll('[data-slot="te-trend-chart"]')) {
          const svg = fig.querySelector('svg.recharts-surface')
          if (!svg) continue
          const s = svg.getBoundingClientRect()
          // Çubuk etiketleri ve ay adları ayrı ayrı: komşusuyla çakışmaz, grafiğin dışına taşmaz
          for (const sel of ['.recharts-label-list text', '[data-slot="te-trend-tick"]']) {
            const boxes = [...fig.querySelectorAll(sel)].filter((n) => n.textContent.trim())
              .map((n) => ({ t: n.textContent, r: n.getBoundingClientRect() })).sort((a, b) => a.r.left - b.r.left)
            for (let i = 0; i < boxes.length; i++) {
              const { t, r } = boxes[i]
              if (r.left < s.left - 1 || r.right > s.right + 1) out.push(`${fig.dataset.field}: ${t} dışarıda`)
              if (i && boxes[i - 1].r.right > r.left - 2) out.push(`${fig.dataset.field}: ${boxes[i - 1].t} / ${t} çakışıyor ${Math.round(boxes[i - 1].r.left)}-${Math.round(boxes[i - 1].r.right)} ${Math.round(r.left)}-${Math.round(r.right)}`)
            }
          }
        }
        return out
      })
      expect(labelProblems, 'çubuk etiketleri').toEqual([])
      if (vp.width < 768) await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-labels', 'current')
      for (const fig of await charts.all()) {
        const r = await fig.boundingBox()
        const b = await block.boundingBox()
        expect(r.x, 'grafik kartın içinde').toBeGreaterThanOrEqual(b.x - 1)
        expect(r.x + r.width, 'grafik kartın içinde').toBeLessThanOrEqual(b.x + b.width + 1)
      }
      expect(await page.evaluate(overflowX), 'yatay taşma (trend)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-trend"]'), 'kırpılan metin (trend)').toEqual([])

      // Bu ayın çubuğu taslaktan: "Toplam işlem" rakamı girilince 12. nokta (raporun ayı) eklenir
      await editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true }).fill('126.000.000')
      await expect(block.locator('[data-field="total-tx"]')).toHaveAttribute('data-points', '12')

      // Görsel (2026-10-16: çözünürlük korunur): sınır içindeki 3000 × 1500 PNG OLDUĞU GİBİ yüklenir — küçültme / not yok
      const big = stripedPng(3000, 1500)
      const slot = editor.locator('[data-slot-id="monthly-tx"]')
      await slot.locator('input[type="file"]').setInputFiles({ name: 'buyuk-ekran-goruntusu.png', mimeType: 'image/png', buffer: big })
      await expect.poll(() => uploads.length, { timeout: 15_000 }).toBe(1)
      expect(uploadSizes[0], 'yüklenen PNG aynı ölçüde').toMatchObject({ w: 3000, h: 1500, bytes: expect.any(Number) })
      await expect(slot.locator('[data-slot="te-image-note"]')).toHaveCount(0)
      // Sunucu sınırını (6000 px kenar) aşan görsel GERÇEK tuvalde alan ortalamasıyla sınıra iner, KAYIPSIZ PNG kalır
      const huge = stripedPng(7200, 1800)
      const wide = editor.locator('[data-slot-id="daily-errors"]')
      await wide.locator('input[type="file"]').setInputFiles({ name: 'cok-genis.png', mimeType: 'image/png', buffer: huge })
      await expect.poll(() => uploads.length, { timeout: 30_000 }).toBe(2)
      expect(uploadSizes[1], 'sınıra inen PNG (JPEG değil)').toMatchObject({ w: 6000, h: 1500 })
      // Pikseller: 40 px'lik bloklar 6000 / 7200 = 5 / 6 ölçekte. Tek bloğun içindeki hedef piksel bloğun rengini AYNEN
      // taşır (şerit sınırını aşan satırlar dahil — 64 satırlık şerit önbelleği); sınırdaki piksel alan ortalamasının tam
      // değeri: x = 33 → kaynak [39,6; 40,8) = 0,4 "açık" + 0,8 "koyu" blok
      const img = decodePng(uploadBodies[1])
      expect(img, 'yüklenen PNG çözülür').not.toBeNull()
      const ON = [20, 90, 200]
      const OFF = [240, 240, 240]
      expect(img.px(10, 10)).toEqual(ON)
      expect(img.px(40, 10)).toEqual(OFF)
      expect(img.px(10, 60)).toEqual(OFF)
      expect(img.px(10, 1000)).toEqual(ON)
      expect(img.px(5990, 1490)).toEqual(OFF)
      const edge = img.px(33, 10)
      const want = [167, 190, 227]
      edge.forEach((v, k) => expect(Math.abs(v - want[k]), `sınır pikseli ${k}`).toBeLessThanOrEqual(1))
      await expect(wide.locator('[data-slot="te-image-note"]')).toContainText('7200 × 1800 → 6000 × 1500')

      // PDF yuvaya yüklenmez; ne yapılacağı yuvanın altında
      const other = editor.locator('[data-slot-id="tech-errors"]')
      await other.locator('input[type="file"]').setInputFiles({ name: 'rapor.pdf', mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7\n%rapor') })
      await expect(other.locator('[data-slot="te-image-error"]')).toContainText(/PDF dosyası görsel yuvasına yüklenemez|A PDF file can't go/)
      expect(uploads.length).toBe(2)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (görsel)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (trend/görsel)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma (görsel)').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/channel-reports-trend-${vp.name}.png` })
      await block.screenshot({ path: `test-results/channel-reports-trend-block-${vp.name}.png` })
    })

    test('Faz B: geçmiş paneli ve şablon sürümleri sığar, uzun adlar kesilmez; yıllara göre liste; ay geçişi', async ({ page }) => {
      // İlk yükleme kardeş testlerle aynı süreyi alır (yüklü makinede dev sunucusu modülleri yavaş verir)
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      await expect(page.locator('[data-slot="te-report-title"]')).toBeVisible({ timeout: 20_000 })
      await expect(page.locator('[data-slot="te-nav-prev"]')).toContainText(/August 2026|Ağustos 2026/)
      await page.locator('[data-slot="te-history-open"]').click()
      const sheet = page.locator('[data-slot="te-history"]')
      await expect(sheet.locator('[data-kind="VALUES"]')).toBeVisible()
      await expect(sheet.locator('[data-kind="CREATE"]')).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="te-history"]')).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-history"]')).toEqual([])
      // Panel sağdan kayarak açılır: kenar, canlandırma bitince ölçülür (taşıyorsa yoklama yine düşer)
      await expect.poll(async () => { const b = await sheet.boundingBox(); return Math.round(b.x + b.width) }, { timeout: 4000 })
        .toBeLessThanOrEqual(vp.width + 1)
      expect((await sheet.boundingBox()).x).toBeGreaterThanOrEqual(-1)
      // Değişiklik satırı kendi kabından taşmaz (uzun ad + uzun değer sarar)
      const overflowing = await page.evaluate(() => [...document.querySelectorAll('[data-slot="te-history-change"]')]
        .filter((n) => n.scrollWidth > n.clientWidth + 1).length)
      expect(overflowing).toBe(0)
      await page.keyboard.press('Escape')
      await expect(sheet).toHaveCount(0)
      // Kapanınca odak açan düğmeye döner (inceleme 2026-10-14: sayfanın başına düşüyordu)
      await expect(page.locator('[data-slot="te-history-open"]')).toBeFocused()

      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10')
      const years = page.locator('[data-slot="te-report-year"]')
      await expect(years).toHaveCount(2)
      await expect(years.first()).toHaveAttribute('data-year', '2026')
      expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(0)

      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      await page.locator('[data-slot="te-versions-open"]').click()
      const dlg = page.locator('[data-slot="te-versions"]')
      await expect(dlg.locator('[data-slot="te-version-item"]')).toHaveCount(2)
      await dlg.locator('[data-slot="te-version-item"][data-version="2"]').click()
      await expect(dlg.locator('[data-slot="te-version-load"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="te-version-outline"]')).toBeVisible()
      if (vp.width < 768) {
        // Dar ekran: ayrıntıya geçince odak geri düğmesinde; bölme sonuna kaydırılsa da geri düğmesi görünür (yapışkan)
        const back = dlg.locator('[data-slot="te-versions-back"]')
        await expect(back).toBeFocused()
        const inside = await page.evaluate(() => {
          const pane = document.querySelector('[data-slot="te-version-detail"]')
          pane.scrollTop = pane.scrollHeight
          const p = pane.getBoundingClientRect()
          const b = document.querySelector('[data-slot="te-versions-back"]').getBoundingClientRect()
          return b.top >= p.top - 1 && b.bottom <= p.bottom + 1
        })
        expect(inside).toBe(true)
      }
      expect(await page.evaluate(clipped, '[data-slot="te-versions"]')).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-versions"]')).toEqual([])
      await expect.poll(async () => { const b = await dlg.boundingBox(); return Math.round(b.x + b.width) }, { timeout: 4000 })
        .toBeLessThanOrEqual(vp.width + 1)
      expect((await dlg.boundingBox()).x).toBeGreaterThanOrEqual(-1)
      // Sürüm 3'ün farkı (sürüm 2'ye göre): kaldırılan uzun adlı alan
      // Dar ekranda (md altı) liste ve ayrıntı ayrı görünümler: önce listeye dönülür
      const pick = async (v) => {
        if (vp.width < 768) await dlg.getByRole('button', { name: /Back to the version list|Sürüm listesine dön/ }).click()
        await dlg.locator(`[data-slot="te-version-item"][data-version="${v}"]`).click()
      }
      await pick(3)
      await expect(dlg.locator('[data-slot="te-version-diff"]')).toContainText('Kaldırılan uzun alan adı')
      await pick(2)
      await dlg.locator('[data-slot="te-version-load"]').click()
      await expect(dlg).toHaveCount(0)
      await expect(page.locator('[data-slot="te-versions-open"]')).toBeFocused()
      await expect(page.getByLabel(/Report title|Rapor başlığı/)).toHaveValue(/^Eski rapor başlığı/)
    })

    test('PO onayı (2026-10-17, S6): onay akışlı kanalda "Onaya gönder" + onay kartı; PO onaylar; sığar, kesilmez', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      const execChannel = { ...REPORT.channel, approval_required: true, executive_count: 2, executive_emails: 'ceo@example.com, cto@example.com' }
      const detail = { required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
        approvers: [{ name: LONG_PERSON, has_email: true }], requested_by_name: null, decided_by_name: null,
        can_request: true, is_approver: false, can_decide: false, can_send_executive: false }
      reportOverride = { ...REPORT, channel: execChannel, report: { ...REPORT.report, approval: { state: 'none', raw_state: null, stale: false } }, approval_detail: detail }
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor).toBeVisible()
      const card = page.locator('[data-slot="te-approval"]')
      await expect(card).toHaveAttribute('data-state', 'none')
      await expect(page.locator('[data-slot="te-send"]')).toHaveCount(0)
      expect(await page.evaluate(overflowX), 'yatay taşma (onaysız)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-approval"]'), 'kırpılan metin (onay kartı)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-approval"]'), 'küçük hedef (onay kartı)').toEqual([])
      await page.locator('[data-slot="te-report-actions"] [data-slot="te-approval-request-bar"]').click()
      const confirm = page.getByRole('dialog', { name: /Onaya gönder|Send for approval/ })
      await expect(confirm).toBeVisible()
      await confirm.getByRole('button', { name: /Onaya gönder|Send for approval/ }).click()
      await expect.poll(() => approvalRequests).toBe(1)
      expect(sends, 'onay isteği gönderim değildir').toBe(0)

      // PO görünümü: bekleyen istek, Onayla / Reddet; onay gerekçesiz, karar ucuna gider
      reportOverride = { ...reportOverride,
        report: { ...REPORT.report, approval: { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-13T09:00:00Z' } },
        approval_detail: { ...detail, requested_by_name: LONG_PERSON, can_request: false, is_approver: true, can_decide: true } }
      await page.reload()
      await expect(card).toHaveAttribute('data-state', 'pending')
      expect(await page.evaluate(clipped, '[data-slot="te-approval"]'), 'kırpılan metin (bekleyen)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma (bekleyen)').toBeLessThanOrEqual(1)
      await card.locator('[data-slot="te-approval-approve"]').click()
      const dlg = page.locator('[data-slot="te-approval-dialog"]')
      await expect(dlg).toBeVisible()
      const db = await dlg.boundingBox()
      expect(db.x + db.width, 'onay penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      await dlg.locator('textarea').fill('Uygun')
      await dlg.locator('[data-slot="te-approval-confirm"]').click()
      await expect.poll(() => decisions.length).toBe(1)
      expect(decisions[0]).toEqual({ decision: 'approve', note: 'Uygun' })
      // Pencere kapanıp bildirim yerleşince (kayma animasyonu geçici taşma sayılmasın) sayfa yine taşmaz
      await expect(dlg).toBeHidden()
      await page.waitForTimeout(800)
      expect(await page.evaluate(overflowX), 'yatay taşma (karar sonrası)').toBeLessThanOrEqual(1)
    })

    test('e-posta önizlemesi (2026-10-17, 10. sürüm): konu / alıcı / ek, gövde kumsandıkta, masaüstü ve telefon genişliği, pencereden gönder; sığar, dokunma hedefleri', async ({ page }) => {
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
      await expect(page.locator('[data-slot="te-report"]')).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-mail-preview-btn"]').click()
      const dlg = page.locator('[data-slot="te-mail-preview"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="te-mail-subject"]')).toContainText('Eylül 2026')
      await expect(dlg.locator('[data-slot="te-mail-to"]')).toContainText('dijital-kanallar@example.com')
      await expect(dlg.locator('[data-slot="te-mail-created"]')).toContainText('Ayşe Yılmaz')
      await expect(dlg.locator('[data-slot="te-mail-updated"]')).toContainText('Ali Veli')
      const frame = dlg.locator('[data-slot="te-mail-frame"]')
      await expect(frame).toBeVisible()
      await expect(frame.contentFrame().locator('[data-slot="te-mail-band"]')).toContainText('TEKNİK HATA RAPORU')
      await page.waitForTimeout(300)
      expect(await page.evaluate(clipped, '[data-slot="te-mail-preview"]'), 'kırpılan metin (önizleme)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-mail-preview"]'), 'küçük hedef (önizleme)').toEqual([])
      const box = await dlg.boundingBox()
      expect(box.width, 'pencere ekrana sığar').toBeLessThanOrEqual(vp.width)
      // Telefon genişliği: iframe 400 px (ekrandan dar değilse)
      await dlg.getByRole('button', { name: /^(Telefon|Phone)$/ }).click()
      await expect(frame).toHaveAttribute('data-width', 'phone')
      const fw = (await frame.boundingBox()).width
      expect(fw).toBeLessThanOrEqual(401)
      await page.screenshot({ path: `test-results/channel-reports-mail-preview-${vp.name}.png`, fullPage: false })
      // "Bana test gönder" (R13 / B5): yalnız bana gider — ayrı uç, toast sunucunun adresiyle; pencere açık kalır, rapor
      // gönderimi ÇAĞRILMAZ; düğme dokunmatikte ≥ 40 px (yukarıdaki smallTargets ölçümü pencereyi kapsar)
      const testBtn = dlg.locator('[data-slot="te-mail-test"]')
      await expect(testBtn).toBeVisible()
      await expect(testBtn).toContainText(/Bana test gönder|Send me a test/)
      await testBtn.click()
      await expect(page.getByText(/Test postası ayse\.yilmaz@example\.com adresine gönderildi|Test mail sent to ayse\.yilmaz@example\.com/)).toBeVisible()
      await expect.poll(() => testMails).toBe(1)
      expect(sends, 'test postası rapor gönderimi değildir').toBe(0)
      await expect(dlg).toBeVisible()
      await expect(testBtn).toBeEnabled()
      // Pencereden gönder: pencere kapanır, mevcut onay sorusu açılır, onaylanınca gönderim gider
      await dlg.locator('[data-slot="te-mail-send"]').click()
      await expect(dlg).toHaveCount(0)
      const confirm = page.getByRole('dialog', { name: /Rapor takıma gönderilsin mi|Send the report to the team/ })
      await expect(confirm).toBeVisible()
      await confirm.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect.poll(() => sends).toBe(1)
    })

    test('R18: gönderim penceresinde ek Bilgi (CC) — istemci ve sunucu alan hatası alanın altında, gövdede yalnız extra_cc; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
      await expect(page.locator('[data-slot="te-report"]')).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-send"]').click()
      const dlg = page.locator('[data-slot="te-send-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.getByRole('heading', { name: /Rapor takıma gönderilsin mi\?|Send the report to the team\?/ })).toBeVisible()
      const cc = dlg.locator('[data-slot="te-send-extra-cc"]')
      await expect(cc).toBeVisible()
      await expect(dlg).toContainText(/en çok 10 adres|at most 10 addresses/)
      const box = await dlg.boundingBox()
      expect(box.x, 'pencere solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, 'pencere ekrana sığar').toBeLessThanOrEqual(vp.width + 1)
      // İstemci denetimi: geçersiz adres alanın altında, istek gitmez
      await cc.fill('ek@example.com, bozuk-adres')
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect(dlg).toContainText(/bozuk-adres/)
      await expect(cc).toHaveAttribute('aria-invalid', 'true')
      expect(sendBodies, 'geçersiz giriş gönderilmez').toHaveLength(0)
      // Sunucunun alan hatası (Bilgi toplamı 20'yi aşıyor): pencere açık, metin alanın altında, yazılan korunur
      await cc.fill('reddet@example.com, iki@example.com')
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect(dlg).toContainText('Bilgi (CC) listesi en çok 20 adres olabilir')
      await expect(dlg).toBeVisible()
      await expect(cc).toHaveValue('reddet@example.com, iki@example.com')
      expect(sends).toBe(0)
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, '[data-slot="te-send-dialog"]'), 'kırpılan metin (gönderim penceresi)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-send-dialog"]'), 'küçük hedef (gönderim penceresi)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-send-dialog-${vp.name}.png`, fullPage: false })
      // Geçerli adresler: gövdede yalnız tekilleşmiş extra_cc
      await cc.fill('ek@example.com; iki@example.com EK@example.com')
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      await expect.poll(() => sends).toBe(1)
      expect(sendBodies[sendBodies.length - 1]).toEqual({ extra_cc: 'ek@example.com, iki@example.com' })
      await expect(dlg).toHaveCount(0)
      await expect(page.getByText(/Bilgi \(CC\): ek@example\.com, iki@example\.com|CC: ek@example\.com, iki@example\.com/)).toBeVisible()
      expect(await page.evaluate(overflowX), 'yatay taşma yok').toBeLessThanOrEqual(0)
    })

    test('R18: kanal gönderim günlüğü — sekme, tablo (1280) / kart (390), uzun alıcı listesi sararak tam, durum metni; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      await mock(page)
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10')
      const tab = page.locator('[data-slot="te-tab-sendlog"]')
      await expect(tab).toBeVisible({ timeout: 20_000 })
      await tab.click()
      const log = page.locator('[data-slot="te-sendlog"]')
      await expect(log).toBeVisible()
      await expect(log.locator('[data-slot="te-sendlog-row"]')).toHaveCount(3)
      await expect.poll(() => page.evaluate(() => window.location.search)).toContain('te_tab=sendlog')
      expect(sendLogCalls[0]).toContain('page=0')
      expect(sendLogCalls[0]).toContain('size=25')
      if (vp.width >= 1280) await expect(log).toHaveAttribute('data-layout', 'table')
      if (vp.width < 640) await expect(log).toHaveAttribute('data-layout', 'cards')
      // Uzun alıcı listesi kırpılmadan TAM ve sarılır; tablo kabı yatayda kaymaz
      const first = log.locator('[data-slot="te-sendlog-row"]').first()
      await expect(first.locator('[data-slot="te-sendlog-recipients"]')).toContainText(SENDLOG_LONG_TO[SENDLOG_LONG_TO.length - 1])
      await expect(first.locator('[data-slot="te-sendlog-cc"]')).toContainText('ek.bilgi.adresi@')
      await expect(log.locator('[data-slot="te-sendlog-status-text"]').first()).toContainText('550')
      await page.waitForTimeout(300)
      expect(await page.evaluate(clipped, '[data-slot="te-sendlog"]'), 'kırpılan metin (gönderim günlüğü)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma yok').toBeLessThanOrEqual(0)
      const table = log.locator('[data-slot="te-sendlog-table"]')
      if (await table.count()) {
        expect(await table.evaluate((n) => n.scrollWidth - n.clientWidth), 'tablo kabı yatayda kaymaz').toBeLessThanOrEqual(1)
      }
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="te-sendlog"]'), 'küçük hedef (gönderim günlüğü)').toEqual([])
        const tabBox = await tab.boundingBox()
        expect(tabBox.height, 'sekme dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      }
      // Kanal sekmeleri sığar (üç sekme, telefonda sarılır)
      const tabsList = page.locator('[data-slot="tabs-list"]').first()
      const tl = await tabsList.boundingBox()
      expect(tl.x + tl.width, 'sekme listesi ekrana sığar').toBeLessThanOrEqual(vp.width + 1)
      // Süzgeç: tür değişince istek kind ile gider
      await log.locator('[data-slot="te-slog-kind"]').selectOption('test')
      await expect.poll(() => sendLogCalls[sendLogCalls.length - 1]).toContain('kind=test')
      await page.screenshot({ path: `test-results/channel-reports-send-log-${vp.name}.png`, fullPage: false })
    })

    test('katlanır bölümler + otomatik kayıt', async ({ page }) => {
      test.setTimeout(120_000)
      // Tohum YOK: kullanıcı raporu ilk kez açıyor
      await mock(page, { openSections: false })
      const url = '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100'
      await page.goto(url)
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor.locator('[data-slot="te-report-title"]')).toBeVisible({ timeout: 20_000 })
      const cards = editor.locator('[data-section][data-open]')
      const sectionCount = TEMPLATE.sections.length

      // (1) Her bölüm kapalı başlar; içerik gizli (alanlara yazılamaz)
      await expect(cards).toHaveCount(sectionCount)
      await expect(editor.locator('[data-section][data-open="true"]')).toHaveCount(0)
      const contents = editor.locator('[data-slot="te-section-content"]')
      await expect(contents).toHaveCount(sectionCount)
      for (const c of await contents.all()) await expect(c).toBeHidden()
      const total = editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })
      await expect(total).toBeHidden()

      // (2) Başlıklar: tam ad (uzun ad sarar, kesilmez), dokunmatikte ≥ 40 px hedef, kartın içinde; yatay taşma yok
      const summaryTitle = `${LONG_CHANNEL} Kanalı — Eylül 2026 Özeti`
      const summaryToggle = editor.getByRole('button', { name: summaryTitle, exact: true })
      await expect(summaryToggle).toHaveText(summaryTitle)
      await expect(summaryToggle).toHaveAttribute('aria-expanded', 'false')
      await page.waitForTimeout(200)
      const headerProblems = ({ root, touch }) => {
        const out = []
        for (const card of document.querySelectorAll(`${root} [data-section][data-open]`)) {
          const id = card.dataset.section
          const t = card.querySelector('[data-slot="te-section-toggle"]')
          if (!t) { out.push(`${id}: aç / kapat düğmesi yok`); continue }
          const row = t.closest('[data-slot="te-copy-row"]')
          const cr = card.getBoundingClientRect()
          const r = t.getBoundingClientRect()
          if (touch && r.height < 39.5) out.push(`${id}: dokunma hedefi ${Math.round(r.height)} px`)
          if (row && row.scrollWidth > row.clientWidth + 1) out.push(`${id}: başlık satırı taşıyor`)
          if (card.scrollWidth > card.clientWidth + 1) out.push(`${id}: kart taşıyor`)
          for (const rr of t.getClientRects()) {
            if (rr.left < cr.left - 1 || rr.right > cr.right + 1) out.push(`${id}: başlık kartın dışında`)
          }
          if (getComputedStyle(t).textOverflow === 'ellipsis') out.push(`${id}: başlık kesiliyor`)
        }
        return out
      }
      expect(await page.evaluate(headerProblems, { root: '[data-slot="te-report"]', touch: vp.touch }),
        'bölüm başlıkları (kapalı)').toEqual([])
      if (vp.width < 768) {
        // Telefonda uzun kanal adlı başlık birden çok satıra SARAR (tek satıra sığıp kesilmez). Düğme satır içi yazılsa da
        // tarayıcı onu inline-block çizer: satır sayısı = (yükseklik − dikey dolgu) ÷ satır yüksekliği
        const lines = await summaryToggle.evaluate((n) => {
          const cs = getComputedStyle(n)
          const h = n.getBoundingClientRect().height - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom)
          return Math.round(h / parseFloat(cs.lineHeight))
        })
        expect(lines, 'uzun başlık sarar').toBeGreaterThan(1)
      }
      expect(await page.evaluate(overflowX), 'yatay taşma (kapalı bölümler)').toBeLessThanOrEqual(1)

      // (3) Bir bölüm açılır, yeniden yüklemede açık kalır (yalnız o)
      await summaryToggle.click()
      await expect(editor.locator('[data-section="summary"]')).toHaveAttribute('data-open', 'true')
      await expect(summaryToggle).toHaveAttribute('aria-expanded', 'true')
      await expect(total).toBeVisible()
      await expect(editor.locator('[data-section][data-open="true"]')).toHaveCount(1)
      expect(JSON.parse(await page.evaluate(() => localStorage.getItem('sm.te.open.r.100')))).toEqual({ ids: ['summary'] })
      await page.reload()
      await expect(editor.locator('[data-slot="te-report-title"]')).toBeVisible({ timeout: 20_000 })
      await expect(editor.locator('[data-section="summary"]')).toHaveAttribute('data-open', 'true')
      await expect(editor.locator('[data-section][data-open="true"]')).toHaveCount(1)
      await expect(total).toBeVisible()
      await expect(editor.locator('[data-section="exec"]')).toHaveAttribute('data-open', 'false')

      // "Tümünü aç" (rapor gezinme satırında) hepsini açar; düğme "Tümünü kapat" olur; taşma / kırpılma / küçük hedef yok
      const all = editor.locator('[data-slot="te-report-nav"] [data-slot="te-sections-all"]')
      await expect(all).toHaveText(/Tümünü aç|Expand all/)
      await all.click()
      await expect(editor.locator('[data-section][data-open="true"]')).toHaveCount(sectionCount)
      await expect(all).toHaveText(/Tümünü kapat|Collapse all/)
      for (const c of await contents.all()) await expect(c).toBeVisible()
      expect(JSON.parse(await page.evaluate(() => localStorage.getItem('sm.te.open.r.100')))).toEqual({ all: true })
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (hepsi açık)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (hepsi açık)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (hepsi açık)').toEqual([])

      // (4) Otomatik kayıt: sekme gizlenince (visibilitychange → hidden) kaydedilmemiş değişiklik sessizce kaydedilir
      const status = page.locator('[data-slot="te-save-status"]')
      const hideTab = () => page.evaluate(() => {
        Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
        document.dispatchEvent(new Event('visibilitychange'))
        delete document.visibilityState                     // yeniden görünür (prototipin değeri)
      })
      await expect(status).toHaveText(/^(Tüm değişiklikler kaydedildi|All changes saved)$/)
      await total.fill('1250000')
      await expect(status).toHaveText(/Kaydedilmemiş değişiklikler var · 30 saniye içinde otomatik kaydedilir|There are unsaved changes · saved automatically within 30 seconds/)
      expect(autoSaves, 'yazarken kayıt yok').toHaveLength(0)
      await hideTab()
      await expect.poll(() => autoSaves.length).toBe(1)
      expect(autoSaves[0]).toMatchObject({ auto: true, version: 4, values: { fields: { 'error-rate': 0.42, 'total-tx': 1250000 } } })
      await expect(status).toHaveText(/(Tüm değişiklikler kaydedildi · otomatik kayıt|All changes saved · saved automatically at) \d/)
      await expect(status).toHaveAttribute('data-auto', 'ok')
      expect(savedReport, 'elle kayıt yok').toBeNull()
      // Sessiz: "Rapor kaydedildi." bildirimi çıkmaz
      await page.waitForTimeout(300)
      await expect(page.getByText(/^(Rapor kaydedildi\.|Report saved\.)$/)).toHaveCount(0)

      // Hatalı alan otomatik kaydı DURDURMAZ: diğer alanlar kaydedilir, hatalı alan saklı (eski) değeriyle gider
      const outage = editor.locator('[data-section="summary"]').getByLabel('Kesinti kaynaklı teknik hata adedi', { exact: true })
      await outage.fill('412')
      await outage.fill('412x')
      await expect(outage).toHaveAttribute('aria-invalid', 'true')
      await editor.locator('[data-section="unmapped"]').getByLabel('Map yapılmamış hata adedi', { exact: true }).fill('3340')
      await expect(status).toContainText(/hatalı alanlar dışındakiler otomatik kaydedilir|everything except the invalid fields is saved automatically/)
      // Hatalı alanlı bölüm kapatılınca başlığında kaç alanın düzeltilmesi gerektiği görünür
      await summaryToggle.click()
      await expect(editor.locator('[data-section="summary"]')).toHaveAttribute('data-open', 'false')
      await expect(editor.locator('[data-section="summary"] [data-slot="te-section-invalid"]')).toHaveText(/^(1 alan düzeltilmeli|1 field\(s\) need fixing)$/)
      expect(await page.evaluate(overflowX), 'yatay taşma (hatalı bölüm kapalı)').toBeLessThanOrEqual(1)
      await hideTab()
      await expect.poll(() => autoSaves.length).toBe(2)
      expect(autoSaves[1]).toMatchObject({ auto: true, version: 5, values: { fields: { 'total-tx': 1250000, 'unmapped-count': 3340 } } })
      expect(autoSaves[1].values.fields, 'hatalı alan saklı değerinde (kayıtlı değil)').not.toHaveProperty('outage-errors')
      await expect(status).toHaveAttribute('data-auto', 'ok')
      await expect(status).toContainText(/hatalı alanlar dışındakiler|everything except the invalid fields/)

      // Düzeltilen alan bir sonraki otomatik kayıtla gider
      await summaryToggle.click()
      await expect(editor.locator('[data-section="summary"] [data-slot="te-section-invalid"]')).toHaveCount(0)
      await outage.fill('412')
      await expect(outage).not.toHaveAttribute('aria-invalid', 'true')
      await hideTab()
      await expect.poll(() => autoSaves.length).toBe(3)
      expect(autoSaves[2]).toMatchObject({ auto: true, version: 6, values: { fields: { 'outage-errors': 412, 'unmapped-count': 3340 } } })
      await expect(status).toHaveText(/(Tüm değişiklikler kaydedildi · otomatik kayıt|All changes saved · saved automatically at) \d/)

      // Elle "Kaydet": gövdede `auto` yok, son otomatik kaydın sürümüyle gider, bildirim çıkar
      await editor.locator('[data-section="unmapped"]').getByLabel('Map yapılmamış hata adedi', { exact: true }).fill('3341')
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.version).toBe(7)
      expect(savedReport).not.toHaveProperty('auto')
      expect(savedReport.values.fields).toMatchObject({ 'unmapped-count': 3341, 'outage-errors': 412 })
      await expect(page.getByText(/^(Rapor kaydedildi\.|Report saved\.)$/).first()).toBeVisible()
      await expect(status).toHaveText(/^(Tüm değişiklikler kaydedildi|All changes saved)$/)
      expect(autoSaves).toHaveLength(3)

      // (5) Şablon düzenleyicisi: bölümler kapalı başlar, araç çubuğundaki "Tümünü aç" hepsini açar
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      const tplEditor = page.locator('[data-slot="te-template-editor"]')
      const tplCards = tplEditor.locator('[data-slot="te-tpl-section"]')
      await expect(tplCards).toHaveCount(sectionCount, { timeout: 20_000 })
      await expect(tplEditor.locator('[data-slot="te-tpl-section"][data-open="true"]')).toHaveCount(0)
      const sumLine = tplEditor.locator('[data-slot="te-tpl-section"][data-kind="summary"] textarea').first()
      await expect(sumLine).toBeHidden()
      await page.waitForTimeout(200)
      expect(await page.evaluate(headerProblems, { root: '[data-slot="te-template-editor"]', touch: vp.touch }),
        'şablon bölüm başlıkları (kapalı)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon, kapalı)').toBeLessThanOrEqual(1)
      const tplAll = tplEditor.locator('[data-slot="te-tpl-toolbar"] [data-slot="te-sections-all"]')
      await expect(tplAll).toHaveText(/Tümünü aç|Expand all/)
      await tplAll.click()
      await expect(tplEditor.locator('[data-slot="te-tpl-section"][data-open="true"]')).toHaveCount(sectionCount)
      await expect(tplAll).toHaveText(/Tümünü kapat|Collapse all/)
      await expect(sumLine).toBeVisible()
      expect(JSON.parse(await page.evaluate(() => localStorage.getItem('sm.te.open.t.10')))).toEqual({ all: true })
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon, hepsi açık)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-template-editor"]'), 'kırpılan metin (şablon, hepsi açık)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-template-editor"]'), 'küçük hedef (şablon, hepsi açık)').toEqual([])
      // Tam sayfa görüntüsü EN SONDA (dokunmatik öykünmeyi sıfırlar)
      await page.screenshot({ path: `test-results/channel-reports-sections-${vp.name}.png`, fullPage: true })
    })

    if (!vp.touch) {
      // 30 saniyelik aralık (sahte saat): sekme görünürken de kaydedilmemiş değişiklik kendiliğinden kaydedilir
      test('otomatik kayıt 30 saniyede bir (sahte saat)', async ({ page }) => {
        await mock(page)
        await page.clock.install()
        await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
        const editor = page.locator('[data-slot="te-report"]')
        const total = editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })
        await expect(total).toBeVisible({ timeout: 20_000 })
        await total.fill('1250000')
        const status = page.locator('[data-slot="te-save-status"]')
        await expect(status).toContainText(/30 saniye içinde otomatik kaydedilir|saved automatically within 30 seconds/)
        expect(autoSaves).toHaveLength(0)
        await page.clock.runFor(31_000)
        await expect.poll(() => autoSaves.length).toBe(1)
        expect(autoSaves[0]).toMatchObject({ auto: true, version: 4, values: { fields: { 'total-tx': 1250000 } } })
        await expect(status).toHaveText(/(Tüm değişiklikler kaydedildi · otomatik kayıt|All changes saved · saved automatically at) \d/)
        expect(savedReport).toBeNull()
        // Değişiklik yokken aralık bir şey göndermez
        await page.clock.runFor(31_000)
        await page.waitForTimeout(300)
        expect(autoSaves).toHaveLength(1)
      })
    }
})
}
