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
// 2026-10-18 (R22): kanal analizi — çizgi grafiği (boş ay boşluk, tek değer nokta), ay × kanal tablosu / kanal kartları,
// dönem ve alan seçimi URL'de; uzun kanal / takım adları sarar; hücre o ayın raporunu açar.
// 2026-10-18: anomali konuları — kart çipleri, canlı pay, rapordaki giriş metni; takvim + saat (ertesi gün), Kesinti ID,
// müşteri etkisi, konu görseli (kaydedilmemiş konu önce kaydedilir); "konuyu rapora alma"; şablonda "Anomali alanları" paneli.
// İnceleme (2026-10-18): İngilizce arayüz geçişi, Kaydet → zorunlu alan → düzelt, klavyeyle Evet / Hayır, kayıt 409 iken görsel yüklenmez.
// 2026-11-20: kanal notları — "Notlar" sekmesi (yaz, yazma alanına görsel yapıştır, önizle, kaydet, yenile), dış adresli görsele
// istek gitmez; rapor düzenleyicisinde ve Görsel paneli penceresinde (520 px) salt okunur, kapalı başlayan not kartı.
// 2026-11-20 (A, hızlı kazanımlar): onay rozeti ve "Onayımı bekleyen" süzgeci, e-posta önizlemesinden PO kararı, konu
// silmede "Geri al", Ctrl+S ile kayıt, Alt+↓/↑ bölüm gezintisi, çakışma uyarısında son kaydeden + değişiklik geçmişi.
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
// Ekrandan görsel alma (2026-10-18): sahte ekran paylaşımı = renk bloklu bir tuvalin akışı (captureStream). Bloklar
// 200 × 150 kaynak pikseli; renk sırası (bx + by × 3) % 6 — video çözümünün renk dönüşümüne dayanıklı orta tonlar.
const CAP_W = 1600
const CAP_H = 900
const CAP_BW = 200
const CAP_BH = 150
const CAP_COLORS = [[200, 60, 60], [60, 160, 80], [50, 90, 200], [230, 200, 60], [150, 80, 180], [40, 170, 170]]
async function mockDisplayCapture(target) {
  await target.addInitScript(({ W, H, BW, BH, COLORS }) => {
    window.__capCalls = 0
    window.__capStopped = false
    const md = navigator.mediaDevices || {}
    if (!navigator.mediaDevices) Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: md })
    md.getDisplayMedia = async () => {
      window.__capCalls++
      const c = document.createElement('canvas')
      c.width = W
      c.height = H
      const ctx = c.getContext('2d')
      const paint = () => {
        for (let by = 0; by * BH < H; by++) {
          for (let bx = 0; bx * BW < W; bx++) {
            const [r, g, b] = COLORS[(bx + by * 3) % COLORS.length]
            ctx.fillStyle = `rgb(${r}, ${g}, ${b})`
            ctx.fillRect(bx * BW, by * BH, BW, BH)
          }
        }
      }
      paint()
      const stream = c.captureStream(10)
      // Durağan tuval yeni kare üretmez: akış canlı kalsın diye aynı içerik yeniden boyanır
      const timer = setInterval(paint, 100)
      for (const tr of stream.getTracks()) {
        const stop = tr.stop.bind(tr)
        tr.stop = () => { window.__capStopped = true; clearInterval(timer); stop() }
      }
      return stream
    }
  }, { W: CAP_W, H: CAP_H, BW: CAP_BW, BH: CAP_BH, COLORS: CAP_COLORS })
}
// Canlı görüntü + kaydırma (2026-11-19): sahte SEKME paylaşımı. Paylaşılan "sayfa" 1600 × 4500'lük bloklu bir şerittir;
// akışın tuvali (1600 × 900) şeridin `__capScrollY`den başlayan bölümünü gösterir. Sahte CaptureController gerçek
// tarayıcının yaptığını yapar (Chromium 159 deneyiyle aynı): forwardWheel(öğe) o öğedeki GERÇEK tekerlek olaylarını
// "paylaşılan sekmeye" iletir (şeridi kaydırır), forwardWheel(null) iletimi keser; izin `prompt` iken çağrı, test
// `__capAnswer('granted' | 'denied' | 'dismiss')` diyene dek BEKLER (tarayıcının izin sorusu).
async function mockLiveTabCapture(target, { perm = 'prompt' } = {}) {
  await target.addInitScript(({ W, H, BW, BH, COLORS, perm0 }) => {
    window.__capCalls = 0
    window.__capStopped = false
    window.__capScrollY = 0
    window.__capPerm = perm0
    window.__capWheelCalls = []
    window.__capPending = null
    const PAGE_H = H * 5
    let ctx = null
    const paint = () => {
      if (!ctx) return
      const top = window.__capScrollY
      for (let by = Math.floor(top / BH); by * BH < top + H; by++) {
        for (let bx = 0; bx * BW < W; bx++) {
          const [r, g, b] = COLORS[(bx + by * 3) % COLORS.length]
          ctx.fillStyle = `rgb(${r}, ${g}, ${b})`
          ctx.fillRect(bx * BW, by * BH - top, BW, BH)
        }
      }
    }
    window.__capScrollTo = (y) => {
      window.__capScrollY = Math.max(0, Math.min(PAGE_H - H, Math.round(y)))
      paint()
    }
    const md = navigator.mediaDevices || {}
    if (!navigator.mediaDevices) Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: md })
    md.getDisplayMedia = async () => {
      window.__capCalls++
      const c = document.createElement('canvas')
      c.width = W
      c.height = H
      ctx = c.getContext('2d')
      paint()
      const stream = c.captureStream(10)
      const timer = setInterval(paint, 100)
      for (const tr of stream.getTracks()) {
        const stop = tr.stop.bind(tr)
        tr.stop = () => { window.__capStopped = true; clearInterval(timer); stop() }
        const settings = tr.getSettings.bind(tr)
        tr.getSettings = () => ({ ...settings(), displaySurface: 'browser' })
      }
      return stream
    }
    let wheelEl = null
    const onWheel = (e) => { if (e.isTrusted) window.__capScrollTo(window.__capScrollY + e.deltaY) }
    const attach = (el) => {
      if (wheelEl) wheelEl.removeEventListener('wheel', onWheel)
      wheelEl = el
      if (el) el.addEventListener('wheel', onWheel)
    }
    const notAllowed = () => Object.assign(new Error('No permission.'), { name: 'NotAllowedError' })
    window.CaptureController = class {
      setFocusBehavior() {}
      forwardWheel(el) {
        window.__capWheelCalls.push(el ? 'el' : 'null')
        if (el == null) { attach(null); return Promise.resolve() }
        if (window.__capPerm === 'granted') { attach(el); return Promise.resolve() }
        if (window.__capPerm === 'denied') return Promise.reject(notAllowed())
        return new Promise((resolve, reject) => {
          window.__capPending = (answer) => {
            window.__capPending = null
            if (answer === 'granted') { window.__capPerm = 'granted'; attach(el); resolve(); return }
            if (answer === 'denied') window.__capPerm = 'denied'
            reject(notAllowed())
          }
        })
      }
    }
    window.__capAnswer = (answer) => {
      if (!window.__capPending) return false
      window.__capPending(answer)
      return true
    }
    const perms = navigator.permissions
    if (perms && typeof perms.query === 'function') {
      const query = perms.query.bind(perms)
      perms.query = (d) => (d && d.name === 'captured-surface-control' ? Promise.resolve({ state: window.__capPerm }) : query(d))
    }
  }, { W: CAP_W, H: CAP_H, BW: CAP_BW, BH: CAP_BH, COLORS: CAP_COLORS, perm0: perm })
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
    updated_at: '2026-10-02T07:00:00Z', last_sent_at: null, version: 4, title: '', updated_by_name: 'Ayşegül Nur Karaosmanoğlu',
    template_version: 3 },
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
// Kısaltılmış sayı + sıfır işareti (2026-10-18): kısaltmayla girilmiş toplam işlem ("4,4G" — units), sıfır kota hatası, sıfır
// müşterili anomali konusu (inceleme 2026-10-18: işaretsiz); özet kutucukları toplam + kota; geçen ay "4350M" (harften önce
// gruplama yok — "4.350M" düzenleyicide 4,35 milyon okunurdu)
const NUMDISP_TEMPLATE = {
  ...CALC_TEMPLATE,
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['{kanal} toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.',
      'Ay içindeki teknik hata adedi {f:error-count} olarak gerçekleşmiştir.'],
      tiles: ['total-tx', 'quota-count', 'error-count'] },
    ...CALC_TEMPLATE.sections,
    { id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
      description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı',
      errors_label: 'Bu konudan kaynaklı teknik hata adedi' },
  ],
}
const NUMDISP_REPORT = {
  ...CALC_REPORT, template: NUMDISP_TEMPLATE,
  values: { fields: { 'total-tx': 4400000000, 'error-count': 526806, 'unmapped-count': 3340, 'quota-count': 0 },
    issues: { issues: [{ id: 'i-a', topic: 'Kart Limiti Sorgulama Servisi ve Mobil Uygulama Giriş Akışı', title: '', title_auto: true,
      description: '', customers: 0, errors: 681600 }] }, texts: {},
    units: { 'total-tx': 9, 'issues.issues.i-a.errors': 3 } },
  previous: { id: 90, year: 2026, month: 8, fields: { 'total-tx': 4350000000 }, units: { 'total-tx': 6 } },
}
// Anomali konuları (2026-10-18): ne zaman, adet + otomatik pay, kesinti + ID, müşteri etkisi, konu görselleri; gerçek uzunlukta ad
const LONG_TOPIC = 'Kart Limiti Sorgulama ve Taksitlendirme Servisi Zaman Aşımı (Mobil / İnternet Şubesi Ortak Altyapı)'
const ANOMALY_ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi',
  ask_time: true, time_label: 'Ne zaman?', ask_outage: true, outage_label: 'Kesinti kaynaklı mı?', outage_id_label: 'Kesinti ID',
  ask_impact: true, impact_label: 'Müşteri etkisi var mı?', no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count',
}
const ANOMALY_TEMPLATE = {
  v: 1, lang: 'tr', title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Verileri', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
    ] },
    ANOMALY_ISSUES,
  ],
}
const ANOMALY_REPORT = {
  ...REPORT, template: ANOMALY_TEMPLATE, trend: undefined,
  values: { fields: { 'total-tx': 125430112, 'error-count': 50000 }, issues: { issues: [{ id: 'k1', topic: LONG_TOPIC, title: '',
    title_auto: true, description: 'Servis yanıt süresi uzadı; **önbellek** yenilendi.', date: '2026-09-12', start: '14:00', end: '15:30',
    errors: 1700, outage: true, outage_id: 'INC-2026-0915-MOBIL-LIMIT-SORGU-ZAMAN-ASIMI', impact: true, customers: 320 }] }, texts: {} },
}
let reportOverride = null
// Ekrandan görsel alma (2026-10-18): yüklenen görseller raporun okumasına girer (sunucu gibi) — Görsel paneli ile düzenleyici
// arasındaki eşitleme gerçek yeniden okumayla sınanır. Yalnız açan test açar.
let persistUploads = false
let storedImages = []
let templateOverride = null
let overviewOverride = null     // kanal listesi (8. sürüm: 60 kanal, sayfalama)
let channelPuts = []            // kanal sırası (R14): PUT /channels/<id> gövdeleri — {id, ...body}
let orderPuts = []              // toplu kanal sırası (R14 inceleme): PUT /teams/<id>/channel-order — {team, ids}
let reportsOverride = null      // kanalın rapor listesi (8. sürüm: 30 rapor)

// Kanal notları (2026-11-20): kanal 10'un notu sahte sunucuda TUTULUR — kayıt / görsel yüklemeden sonraki okuma gerçektir
// (sayfa yenilenince kaydedilen not ve görseli gelir). Yalnız açan test `notesOn` yapar: kanal satırları not bayraklarını,
// rapor yanıtı `channel_notes` özetini o zaman taşır (öteki testlerde anahtar yok → rapor kartı hiç çizilmez).
let notesOn = false
let noteCanEdit = true
let noteDoc = null              // { version, body, updated_at, updated_by_name, created_at } | null (henüz not yok)
let noteImages = []             // { id, file_name, content_type, width, height, size_bytes, uploaded_at }
let noteImageBytes = new Map()  // kimlik → PNG baytları (GET /channel-notes/images/<kimlik>)
let noteVersions = []           // { version, body, saved_at, saved_by_name, char_count, image_count } — en yenisi başta
let noteSaves = []              // PUT gövdeleri
let noteUploads = []            // POST /channel-notes/10/images gövdeleri (Buffer)
let noteGets = 0                // GET /channel-notes/10 sayısı (kapalı kart istek atmaz)
const NOTE_LIMITS = { max_chars: 60000, max_images: 40, max_image_bytes: 5242880, max_total_bytes: 41943040, unlinked_keep_days: 30,
  max_versions: 20 }
// Sunucunun başvuru kuralı (te-note-img:<1–18 rakam>, ardından rakam gelmez) — test verisi kısa, burada regex yeter
const noteRefIds = (body) => [...new Set([...String(body || '').matchAll(/te-note-img:(\d{1,18})(?!\d)/g)].map((m) => String(Number(m[1]))))]
function noteGetBody() {
  const body = noteDoc?.body ?? ''
  const refs = new Set(noteRefIds(body))
  const images = noteImages.map((i) => ({ ...i, linked: refs.has(String(i.id)) }))
  return { channel_id: 10, exists: !!noteDoc, version: noteDoc?.version ?? 0, body, char_count: body.length,
    updated_at: noteDoc?.updated_at ?? null, updated_by_name: noteDoc?.updated_by_name ?? null, created_at: noteDoc?.created_at ?? null,
    images, image_bytes: images.reduce((n, i) => n + i.size_bytes, 0), limits: NOTE_LIMITS, can_edit: noteCanEdit, shared_access: false }
}
// Rapor / rapor listesi yanıtındaki özet (`channel_notes`)
function noteSummary() {
  const body = noteDoc?.body ?? ''
  return { readable: true, exists: !!noteDoc, empty: body.trim() === '', updated_at: noteDoc?.updated_at ?? null,
    updated_by_name: noteDoc?.updated_by_name ?? null, image_count: noteRefIds(body).length }
}
const noteChannel = (c) => (notesOn ? { ...c, can_read_notes: true, can_edit_notes: noteCanEdit } : c)
/** Çok parçalı gövdedeki PNG'nin kendi baytları (imza … IEND + CRC) — yüklenen görsel aynen geri sunulur. */
function pngBytes(body) {
  const i = body.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47]))
  if (i < 0) return null
  const end = body.indexOf(Buffer.from('IEND', 'latin1'), i)
  return end < 0 ? null : body.subarray(i, end + 8)
}
// Notun gerçekçi içeriği: başlık, uzun adres, tablo, görev listesi, kod — ve çizilmemesi gereken UZAK görsel
const NOTE_REMOTE_HOST = 'example.com'
const NOTE_TEXT = [
  '# Veriyi nereden alıyoruz',
  '',
  `Rakamlar Qlik "Kanal Teknik Hataları" panosundan alınır: https://qlik.ornek-kurum-bilgi-teknolojileri.example.org/sense/app/3f2a9c1e-7b1d-4c55-9a10-kanal-teknik-hatalari/sheet/aylik-ozet-ve-gunluk-dagilim`,
  '',
  '1. Panoda **dönem** süzgecini raporun ayına getirin.',
  '2. "Teknik hata adedi" kutucuğunu okuyun.',
  '',
  '- [x] Map yapılmamış hatalar ayrı sayfada',
  '- [ ] Kota hataları için ikinci pano',
  '',
  '| Alan | Kaynak | Not |',
  '| --- | --- | --- |',
  '| Toplam işlem adedi | Qlik — Aylık Özet | Ayın son günü 23:59 itibarıyla |',
  '| Teknik hata adedi | Qlik — Teknik Hatalar | Kesinti kaynaklılar dahil |',
  '',
  `![dış kaynak](https://${NOTE_REMOTE_HOST}/a.png)`,
  '',
  '`SELECT COUNT(*) FROM teknik_hatalar WHERE donem = :ay`',
  '',
].join('\n')
/**
 * Dış sunuculara (örnek alan adları) giden istekleri toplar ve KESER: not içindeki dış adresli görsel için <img> çizilmemeli,
 * yani tek bir istek bile düşmemeli. Kesildiği için uygulama yanlışlıkla istese de makineden bir şey çıkmaz; istek olayı
 * yine de kaydedilir. `target`: sayfa ya da bağlam (açılır pencere de izlenir).
 */
const NOTE_REMOTE_RE = /(^|\.)example\.(com|org)$/
async function trackRemote(target) {
  const seen = []
  target.on('request', (r) => {
    try { if (NOTE_REMOTE_RE.test(new URL(r.url()).hostname)) seen.push(r.url()) } catch { /* ayrıştırılamayan adres */ }
  })
  await target.route((u) => NOTE_REMOTE_RE.test(new URL(u).hostname), (route) => route.abort())
  return seen
}
/** Görsel gerçekten yüklendi mi (tembel yüklenen görsel önce görünür alana getirilir): doğal genişliği. */
async function loadedWidth(img) {
  await img.scrollIntoViewIfNeeded()
  return img.evaluate((el) => (el.complete ? el.naturalWidth : 0))
}

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
const VERSIONS = { current_version: 3, max_versions: 100, unknown_version_reports: 1, unlisted_version_reports: 0, versions: [
  { version: 3, current: true, recorded: true, saved_at: '2026-10-03T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 5, field_count: 8, slot_count: 3,
    report_count: 2 },
  { version: 2, current: false, recorded: true, saved_at: '2026-10-02T08:00:00Z', saved_by_name: LONG_PERSON, section_count: 4, field_count: 9, slot_count: 3,
    report_count: 0, source_kind: 'library', source_name: LONG_TEAM },
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
async function seedOpenSections(page, { reports = [100], channels = [10, 20], topics = true } = {}) {
  // Katlanır anomali konuları (2026-10-18) da kapalı başlar (`sm.te.open.ri.<rapor>`): konu akordeonuyla ilgisi olmayan testler
  // konuları da açık tohumlar; akordeonu sınayan test `topics: false` ile yalnız bölümleri açar
  await page.addInitScript(({ owner, reports: r, channels: c, topics: tp }) => {
    try {
      localStorage.setItem('sm.storage.owner', owner)
      for (const id of r) {
        localStorage.setItem(`sm.te.open.r.${id}`, JSON.stringify({ all: true }))
        if (tp) localStorage.setItem(`sm.te.open.ri.${id}`, JSON.stringify({ all: true }))
      }
      for (const id of c) localStorage.setItem(`sm.te.open.t.${id}`, JSON.stringify({ all: true }))
    } catch { /* yoksay */ }
  }, { owner: ME_USER, reports, channels, topics })
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
// Uzun alıcı listesi: gerçek uzunlukta kurumsal adresler. notification_logs.recipient_email VARCHAR(255) — sunucu listeyi
// NotificationLog.fitList ile " … +N" ekiyle saklar, TechErrorSendLogService.splitFitted eki ayırır: yanıt yalnız kolona
// sığan TAM adresler + `to_more` (inceleme 2026-10-18: mock sunucunun GERÇEK şeklini taşır — 9 adresin hepsi değil)
const SENDLOG_LONG_TO = Array.from({ length: 9 }, (_, i) => `dijital.kanallar.uygulama.gelistirme.${i}@ornek-kurum-bilgi-teknolojileri.example.com`)
function serverFitted(list, max = 255) {
  const items = []
  let len = 0
  for (let i = 0; i < list.length; i++) {
    const suffix = i + 1 < list.length ? ` … +${list.length - i - 1}` : ''
    const next = len + (len === 0 ? 0 : 2) + list[i].length + suffix.length
    if (next > max) break
    len += (len === 0 ? 0 : 2) + list[i].length
    items.push(list[i])
  }
  return { items, more: list.length - items.length }
}
const SENDLOG_FITTED_TO = serverFitted(SENDLOG_LONG_TO)
const SENDLOG_ROWS = [
  { id: 3, at: '2026-10-13T08:00:00Z', kind: 'report', trigger: 'TECH_ERROR_REPORT', role: 'TEAM', status: 'sent', reason: null,
    report_id: 100, year: 2026, month: 9, to: SENDLOG_FITTED_TO.items, to_more: SENDLOG_FITTED_TO.more,
    cc: ['ek.bilgi.adresi@ornek-kurum-bilgi-teknolojileri.example.com'], cc_more: 0, to_count: 9, cc_count: 1 },
  // Ham durum metni (posta sunucusunun iletisi) yanıtta YOK — yalnız sebep kodu
  { id: 2, at: '2026-10-12T08:00:00Z', kind: 'test', trigger: 'TECH_ERROR_REPORT_TEST', role: 'SELF', status: 'failed', reason: 'SMTP_FAILED',
    report_id: 100, year: 2026, month: 9, to: ['ayse.yilmaz@example.com'], to_more: 0, cc: [], cc_more: 0, to_count: 1, cc_count: 0 },
  { id: 1, at: '2026-10-11T08:00:00Z', kind: 'approval', trigger: 'TECH_ERROR_APPROVAL', role: 'APPROVER', status: 'queued', reason: 'QUEUED',
    report_id: null, year: null, month: null, to: ['mehmet.kaya@example.com'], to_more: 0, cc: [], cc_more: 0, to_count: 1, cc_count: 0 },
]
let analyticsCalls = []         // Kanal analizi istekleri (2026-10-18, R22): URLSearchParams

// Kanal analizi (2026-10-18, R22) — sunucu yanıtı isteğin ay sayısı ve alanlarıyla kurulur. Uzun kanal / takım adları,
// boşluklu seri (iki ay boş), yalnız TEK değeri olan kanal (nokta), gönderilmemiş ay.
const LONG_CHANNEL_2 = 'Kurumsal İnternet Şubesi ve Nakit Yönetimi Ortak Kanalı (Kurumsal Bankacılık / Dijital Kanallar)'
const LONG_INACTIVE_CHANNEL = 'Eski Bireysel Mobil Bankacılık ve Kart Ödemeleri Ortak Kanalı (Dijital Kanallar / Kapatıldı Ağustos 2026)'
const AN_FIELDS = {
  'error-rate': { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' },
  'total-tx': { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
  'sla-rate': { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent' },
}
function analyticsBody(params) {
  const n = [6, 12, 24].includes(Number(params.get('months'))) ? Number(params.get('months')) : 12
  const months = Array.from({ length: n }, (_, i) => {
    const d = new Date(Date.UTC(2026, 8 - (n - 1) + i, 1))
    return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 }
  })
  const ids = (params.get('fields') || 'error-rate,total-tx').split(',').filter((id) => AN_FIELDS[id])
  const last = n - 1
  const pts = (kind) => ({
    'error-rate': months.map((_, i) => (kind === 'lone' ? (i === last - 4 ? 0.9 : null) : kind === 'gaps' && (i === last - 7 || i === last - 6) ? null : 0.4 + i / 100)),
    'total-tx': months.map((_, i) => (kind === 'lone' ? (i === last - 4 ? 4200 : null) : 1_100_000 + i * 12_500)),
    'sla-rate': months.map(() => null),
  })
  const reps = (base, unsent) => months.map((_, i) => ({ id: i === last && base === 700 ? 100 : base + i, sent: i !== unsent }))
  // Pasif kanal (2026-10-18): yalnız eski aylarda raporu var (pasife alınmadan önce), aktiflerin ardında, `active: false`
  const pastIdx = (i) => i <= last - 3 && i >= last - 5
  const ch = (id, name, teamId, teamName, kind, base, active = true) => ({
    id, name, team_id: teamId, team_name: teamName, active,
    points: Object.fromEntries(ids.map((f) => [f, kind === 'past' ? pts('full')[f].map((v, i) => (pastIdx(i) ? v : null)) : pts(kind)[f]])),
    reports: kind === 'lone' ? months.map((_, i) => (i === last - 4 ? { id: base, sent: true } : null))
      : kind === 'past' ? months.map((_, i) => (pastIdx(i) ? { id: base + i, sent: true } : null)) : reps(base, last),
  })
  return {
    team: params.get('team') || 'all', months_requested: n, months, end: { year: 2026, month: 9 },
    fields: ids.map((id) => AN_FIELDS[id]),
    available_fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number', channels: 3 },
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', channels: 3 },
      { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', type: 'percent', channels: 2 },
    ],
    channels: [
      ch(10, LONG_CHANNEL, 5, LONG_TEAM, 'gaps', 700),
      ch(20, 'POS', 5, LONG_TEAM, 'full', 800),
      ch(30, LONG_CHANNEL_2, 6, 'Kurumsal Bankacılık Dijital Kanallar ve Nakit Yönetimi Uygulama Geliştirme Ekibi', 'lone', 900),
      ch(40, LONG_INACTIVE_CHANNEL, 5, LONG_TEAM, 'past', 950, false),
    ],
    // 24 ay (inceleme 2026-10-18): sığmayan pasif kanallar AYRI işaret — aylar tam, `truncated` yalnız aylar için
    channel_count: n === 24 ? 6 : 4, truncated: false, channels_truncated: n === 24, inactive_omitted: n === 24 ? 2 : 0,
    teams: [{ id: 5, name: LONG_TEAM }, { id: 6, name: 'Kurumsal Bankacılık Dijital Kanallar ve Nakit Yönetimi Uygulama Geliştirme Ekibi' }],
    month_choices: [6, 12, 24], max_fields: 4,
  }
}
let libraryEntries = []         // takım şablon kütüphanesi (2026-10-18, D17): GET /teams/5/templates satırları
let libraryWrites = []          // POST / PUT / DELETE /teams/5/templates — {method, id, body}
let channelCreates = []         // POST /channels gövdeleri (yeni kanal penceresi)
const libEntry = (id, name, extra = {}) => ({
  id, team_id: 5, name, is_default: false, lang: 'tr', section_count: 5, field_count: 8, slot_count: 3,
  created_at: '2026-10-10T08:00:00Z', created_by_name: LONG_PERSON, updated_at: '2026-10-17T08:00:00Z', updated_by_name: LONG_PERSON,
  version: 1, can_edit: true, ...extra,
})
const LONG_LIB = 'Dijital Kanallar Bireysel Bankacılık Mobil ve İnternet Şubesi Ortak Aylık Teknik Hata Şablonu (Takım Varsayılanı 2026)'
async function mock(page, { openSections = true, openTopics = true, target = page } = {}) {
  persistUploads = false; storedImages = []
  savedTemplate = null; savedReport = null; autoSaves = []; uploads = []; uploadSizes = []; uploadBodies = []; sends = 0
  testMails = 0; approvalRequests = 0; decisions = []; channelPuts = []; orderPuts = []; previewBodies = []; sendBodies = []
  sendLogCalls = []; analyticsCalls = []
  reportOverride = null
  templateOverride = null
  overviewOverride = null
  reportsOverride = null
  libraryEntries = []; libraryWrites = []; channelCreates = []
  notesOn = false; noteCanEdit = true; noteDoc = null; noteImages = []; noteImageBytes = new Map(); noteVersions = []
  noteSaves = []; noteUploads = []; noteGets = 0
  // target: sayfa (varsayılan) ya da bağlam — Görsel paneli testi açılır pencereyi de aynı sahte sunucuya bağlar
  await target.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  if (openSections) await seedOpenSections(target, { topics: openTopics })
  await target.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    if (/^\/api\/tech-error-reports\/images\/\d+$/.test(p)) {
      await route.fulfill({ status: 200, contentType: 'image/png', body: PNG })
      return
    }
    // Kanal notu görseli (2026-11-20): yüklenen baytlar aynen sunulur; bilinmeyen kimlik kodlu 404
    if (/^\/api\/tech-error-reports\/channel-notes\/images\/\d+$/.test(p)) {
      const bytes = noteImageBytes.get(Number(p.split('/').pop()))
      if (bytes) {
        await route.fulfill({ status: 200, contentType: 'image/png',
          headers: { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' }, body: bytes })
      } else {
        await route.fulfill({ status: 404, contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'NOTE_IMAGE_NOT_FOUND', error: 'Görsel bulunamadı ya da artık yok.' }) })
      }
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
    else if (p === '/api/tech-error-reports/channel-notes/10' && req.method() === 'GET') {
      noteGets++
      body = { success: true, data: noteGetBody() }
    } else if (p === '/api/tech-error-reports/channel-notes/10' && req.method() === 'PUT') {
      // Sunucu gibi: okunan sürümle kaydedilir (uyuşmazsa 409 + kim / ne zaman), her kayıt bir sürüm satırı bırakır
      const put = JSON.parse(req.postData() || '{}')
      noteSaves.push(put)
      if (put.version !== (noteDoc?.version ?? 0)) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'CONCURRENT_MODIFICATION',
          error: 'Bu not siz düzenlerken başka biri tarafından kaydedildi. En son hâli yükleyip değişikliğinizi yeniden uygulayın.',
          current_version: noteDoc?.version ?? 0, updated_by_name: noteDoc?.updated_by_name ?? null, updated_at: noteDoc?.updated_at ?? null }) })
        return
      }
      const text = String(put.body ?? '')
      noteDoc = { version: (noteDoc?.version ?? 0) + 1, body: text, updated_at: '2026-11-20T08:30:00Z', updated_by_name: LONG_PERSON,
        created_at: noteDoc?.created_at ?? '2026-11-20T08:30:00Z' }
      noteVersions = [{ version: noteDoc.version, body: text, saved_at: noteDoc.updated_at, saved_by_name: LONG_PERSON, char_count: text.length,
        image_count: noteRefIds(text).length }, ...noteVersions]
      body = { success: true, data: { ...noteGetBody(), changed: true } }
    } else if (p === '/api/tech-error-reports/channel-notes/10/images' && req.method() === 'POST') {
      const buf = req.postDataBuffer() || Buffer.alloc(0)
      noteUploads.push(buf)
      const size = pngSize(buf)
      const id = 700 + noteUploads.length
      const bytes = pngBytes(buf) || PNG
      noteImageBytes.set(id, bytes)
      const stored = { id, file_name: /filename="([^"]+)"/.exec(buf.toString('latin1'))?.[1] || `gorsel-${id}.png`, content_type: 'image/png',
        width: size?.w ?? 1, height: size?.h ?? 1, size_bytes: bytes.length, uploaded_at: '2026-11-20T08:20:00Z' }
      noteImages = [stored, ...noteImages]
      body = { success: true, data: { image: { ...stored, linked: false }, ref: `te-note-img:${id}` } }
    } else if (/^\/api\/tech-error-reports\/channel-notes\/10\/images\/\d+$/.test(p) && req.method() === 'DELETE') {
      // Yalnız KAYITLI metinde kullanılmayan görsel silinir
      const id = Number(p.split('/').pop())
      if (noteRefIds(noteDoc?.body).includes(String(id))) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'NOTE_IMAGE_IN_USE',
          error: 'Bu görsel kayıtlı notta kullanılıyor. Önce metinden çıkarıp notu kaydedin, sonra silin.' }) })
        return
      }
      noteImages = noteImages.filter((i) => i.id !== id)
      noteImageBytes.delete(id)
      body = { success: true, data: { deleted: true } }
    } else if (p === '/api/tech-error-reports/channel-notes/10/versions') {
      body = { success: true, data: { max_versions: NOTE_LIMITS.max_versions,
        // Liste gövdesizdir (sözleşme): metin yalnız tek sürüm okumasında gelir
        versions: noteVersions.map((v) => ({ version: v.version, saved_at: v.saved_at, saved_by_name: v.saved_by_name,
          char_count: v.char_count, image_count: v.image_count })) } }
    } else if (/^\/api\/tech-error-reports\/channel-notes\/10\/versions\/\d+$/.test(p)) {
      const v = noteVersions.find((x) => x.version === Number(p.split('/').pop()))
      if (!v) {
        await route.fulfill({ status: 404, contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'NOTE_VERSION_NOT_FOUND', error: 'Bu sürüm artık tutulmuyor.' }) })
        return
      }
      body = { success: true, data: { version: v.version, body: v.body, saved_at: v.saved_at, saved_by_name: v.saved_by_name } }
    } else if (p === '/api/tech-error-reports/overview') {
      body = { success: true, data: { period: { year: 2026, month: 9 }, can_create: true,
        teams: [{ id: 5, name: LONG_TEAM, active: true, has_email: true, can_edit: true, can_manage: false, library_read: true }],
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
    } else if (p === '/api/tech-error-reports/analytics') {
      const params = new URL(req.url()).searchParams
      analyticsCalls.push(params)
      body = { success: true, data: analyticsBody(params) }
    } else if (/^\/api\/tech-error-reports\/teams\/5\/templates(\/\d+)?$/.test(p)) {
      // Takım şablon kütüphanesi (D17): liste gövdesiz, tek kayıt gövdeli; yazımlar kaydedilir ve listeye işlenir
      const m = /templates\/(\d+)$/.exec(p)
      const id = m ? Number(m[1]) : null
      const method = req.method()
      const put = req.postData() ? JSON.parse(req.postData()) : null
      if (method === 'GET' && id == null) {
        body = { success: true, data: { team_id: 5, team_name: LONG_TEAM, max: 20, can_edit: true, templates: libraryEntries } }
      } else if (method === 'GET') {
        const e = libraryEntries.find((x) => x.id === id)
        body = e ? { success: true, data: { ...e, template: { ...TEMPLATE, title: `Kütüphane: ${e.name} {kanal} {ay} {yil}` } } }
          : { success: false, code: 'NOT_FOUND', error: 'Kütüphane şablonu bulunamadı.' }
      } else if (method === 'POST') {
        libraryWrites.push({ method, id: null, body: put })
        if (put?.default) libraryEntries.forEach((x) => { x.is_default = false })
        const e = libEntry(900 + libraryWrites.length, put?.name, { is_default: !!put?.default, version: 0 })
        libraryEntries.push(e)
        body = { success: true, data: e }
      } else if (method === 'PUT') {
        libraryWrites.push({ method, id, body: put })
        const e = libraryEntries.find((x) => x.id === id)
        if (put && 'default' in put && put.default) libraryEntries.forEach((x) => { x.is_default = false })
        if (e) Object.assign(e, put?.name ? { name: put.name } : {}, put && 'default' in put ? { is_default: !!put.default } : {}, { version: e.version + 1 })
        body = { success: true, data: { ...(e || { id }), changed: true } }
      } else if (method === 'DELETE') {
        libraryWrites.push({ method, id, body: null })
        libraryEntries = libraryEntries.filter((x) => x.id !== id)
        body = { success: true, data: { deleted: true } }
      }
    } else if (p === '/api/tech-error-reports/channels' && req.method() === 'POST') {
      const created = JSON.parse(req.postData() || '{}')
      channelCreates.push(created)
      // Yeni pod gibi: gerçekten kullanılan şablon kaynağını onaylar (yoksa istemci "uygulanmadı" uyarısı gösterir)
      body = { success: true, data: { ...channel(20, 'ATM'), template_source: created.library_template_id != null
        ? { kind: 'library', library_template_id: created.library_template_id } : { kind: created.copy_from != null ? 'copy' : 'default' } } }
    } else if (p === '/api/tech-error-reports/reports/100/history') {
      body = { success: true, data: HISTORY }
    } else if (p === '/api/tech-error-reports/channels/10/template/versions') {
      body = { success: true, data: VERSIONS }
    } else if (/^\/api\/tech-error-reports\/channels\/10\/template\/versions\/[23]$/.test(p)) {
      const v = Number(p.split('/').pop())
      body = { success: true, data: { version: v, saved_at: '2026-10-0' + v + 'T08:00:00Z', saved_by_name: LONG_PERSON,
        template: v === 2 ? TEMPLATE_V2 : TEMPLATE, current: v === 3, report_count: v === 3 ? 2 : 0,
        source_kind: v === 2 ? 'library' : null, source_name: v === 2 ? LONG_TEAM : null,
        reports: v === 3 ? [{ id: 100, year: 2026, month: 9, title: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026` },
          { id: 80, year: 2025, month: 12, title: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Aralık 2025` }] : [] } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/template$/.test(p)) {
      const id = Number(p.split('/')[4])
      if (req.method() === 'PUT') {
        savedTemplate = { id, ...JSON.parse(req.postData() || '{}') }
        body = { success: true, data: { template: savedTemplate.template, version: 4 } }
      } else body = { success: true, data: { channel: channel(id, id === 10 ? LONG_CHANNEL : 'POS'), template: templateOverride || TEMPLATE, version: 3 } }
    } else if (/^\/api\/tech-error-reports\/channels\/(10|20)\/reports$/.test(p)) {
      const id = Number(p.split('/')[4])
      body = { success: true, data: { channel: noteChannel(channel(id, id === 10 ? LONG_CHANNEL : 'POS')), default_period: { year: 2026, month: 9 },
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
      } else {
        const base = reportOverride || REPORT
        const data = persistUploads ? { ...base, images: storedImages } : base
        // Kanal notları açıksa (yalnız o testler) rapor yanıtı kanalın not bayraklarını ve not özetini de taşır
        body = { success: true, data: notesOn ? { ...data, channel: noteChannel(data.channel), channel_notes: noteSummary() } : data }
      }
    } else if (/^\/api\/tech-error-reports\/reports\/100\/images\/[^/]+$/.test(p) && req.method() === 'POST') {
      const slotId = decodeURIComponent(p.split('/').pop())
      uploads.push(req.headers()['content-type'] || '')
      uploadSizes.push(pngSize(req.postDataBuffer() || Buffer.alloc(0)))
      uploadBodies.push(req.postDataBuffer() || Buffer.alloc(0))
      body = { success: true, data: { id: 500 + uploads.length, slot_id: slotId, content_type: 'image/png', file_name: `${slotId}.png`,
        width: 1, height: 1, size_bytes: PNG.length } }
      if (persistUploads) storedImages = [...storedImages.filter((i) => i.slot_id !== slotId), body.data]
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
      // Çok pod R2a (MP-41): önceki gönderim 421 kuyruğunda — `kuyruk@` adresiyle tetiklenir (409 SEND_QUEUED, hiçbir şey gönderilmez)
      if (sent?.extra_cc && sent.extra_cc.includes('kuyruk@')) {
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'SEND_QUEUED',
          error: 'Bu raporun önceki gönderimi posta sunucusunun yeniden deneme kuyruğunda bekliyor; ikinci bir kopya gitmesin diye rapor yeniden gönderilmedi. Birkaç dakika sonra gönderim sonucunu kontrol edin; gerekirse o zaman yeniden gönderin.' }) })
        return
      }
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

// Olay kaydından konu (2026-11-20, CANLI bağ): `mock()`tan SONRA kaydedilir — Playwright son kaydedilen yolu önce dener; olay
// uçları dışındaki her istek `fallback()` ile ana sahte sunucuya düşer. Rapor okuması `incident_links` taşır; izin anlık
// görüntüsü `incidents.view` içerir. Sunucu gibi: içe alma / güncelleme sürümü bir artırır, yanıt rapor ayrıntısının şeklidir.
const INC_LONG = 'Ödeme API ve Kart Doğrulama Servislerinde Bağlantı Havuzu Tükenmesi (Mobil / İnternet Şubesi Ortak Altyapı)'
let inc = null
const incLink = (iid, id, extra = {}) => ({ section_id: 'issues', issue_id: iid, incident_id: id, state: 'ok',
  title: id === 9 ? INC_LONG : 'Kart doğrulama gecikmesi', status: 'RESOLVED', occurred_at: '2026-09-12T11:00:00', pinned: [], notes: [],
  synced_at: '2026-10-01T08:00:00Z', incident_updated_at: '2026-09-30T08:00:00Z', ...extra })
const incCandidate = (id, title, extra = {}) => ({ id, title, occurred_at: '2026-09-12T11:00:00', resolved_at: '2026-09-12T12:30:00',
  status: 'RESOLVED', severity: 'CRITICAL', channel: LONG_CHANNEL, tech_error_count: 1250, outage_related: true, affected_customers: 320,
  imported: false, ...extra })
const incTopic = (id) => ({ id: `i-${id}`, topic: id === 9 ? INC_LONG : 'Kart doğrulama gecikmesi', title: '', title_auto: true,
  description: '**Kök neden**\nVeritabanı bağlantı havuzu doldu.', date: '2026-09-12', start: '14:00', end: '15:30', errors: 1250,
  outage: true, outage_id: 'INC-2026-0915', impact: true, customers: 320 })
function incDetail() {
  return { success: true, data: { ...inc.report, incident_links: inc.links } }
}
async function mockIncidents(page) {
  inc = { report: JSON.parse(JSON.stringify(ANOMALY_REPORT)), links: [], candidateCalls: [], previews: [], imports: [], refreshes: [],
    unlinks: [], syncs: 0, syncTo: null }
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    if (p === '/api/me/permissions') {
      return json({ success: true, data: { 'tech_error_reports.read': { view: true }, 'tech_error_reports.crud': { edit: true },
        'incidents.view': { view: true } } })
    }
    if (p === '/api/tech-error-reports/reports/100' && req.method() === 'GET') return json(incDetail())
    if (p === '/api/tech-error-reports/reports/100/incident-candidates') {
      inc.candidateCalls.push(url.searchParams)
      const items = [incCandidate(9, INC_LONG), incCandidate(11, 'Kart doğrulama gecikmesi', { outage_related: false, affected_customers: null }),
        incCandidate(12, 'Ay başında çözülen ve bu rapora daha önce alınmış olay', { imported: true })]
      // Sunucu gibi: istenen boyut (yoksa 20), 'yalnız ilk 2000 olayda arandı' bayrağı (notun sığdığı sınanır)
      return json({ success: true, data: { items, total: items.length, page: 0, size: Number(url.searchParams.get('size') || 20),
        period: { year: 2026, month: 9 }, truncated: true } })
    }
    if (p === '/api/tech-error-reports/reports/100/incident-imports/preview') {
      const b = JSON.parse(req.postData() || '{}')
      inc.previews.push(b)
      return json({ success: true, data: { proposals: (b.incident_ids || []).map((id) => ({ incident_id: id, title: incTopic(id).topic,
        fields: { topic: incTopic(id).topic, date: '2026-09-12', start: '14:00', end: '15:30', end_next_day: false, errors: 1250,
          outage: id === 9, outage_id: id === 9 ? 'INC-2026-0915' : null, impact: true, customers: 320 },
        description_preview: incTopic(id).description, notes: id === 11 ? ['outage_id_missing'] : (b.images ? ['image_skipped'] : []), hidden: false,
        // Sunucunun görsel özeti (TechErrorIncidentImportService.imagesDto): istenmediyse boş
        images: b.images && id === 9 ? { copy: 1, skipped: [{ reason: 'too_large', image_id: 41 }], remaining: 1 } : { copy: 0, skipped: [] } })) } })
    }
    if (p === '/api/tech-error-reports/reports/100/incident-imports' && req.method() === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      inc.imports.push(b)
      for (const id of b.incident_ids || []) {
        inc.report.values.issues.issues.push(incTopic(id))
        inc.links.push(incLink(`i-${id}`, id))
      }
      inc.report.report = { ...inc.report.report, version: (b.version ?? 4) + 1 }
      return json({ ...incDetail(), data: { ...incDetail().data,
        imported: (b.incident_ids || []).map((id) => ({ incident_id: id, issue_id: `i-${id}`, notes: [] })) } })
    }
    if (p === '/api/tech-error-reports/reports/100/incident-links/sync' && req.method() === 'POST') {
      inc.syncs++
      if (inc.syncTo) inc.links = inc.syncTo
      inc.report.report = { ...inc.report.report, version: inc.report.report.version + 1 }
      return json(incDetail())
    }
    const refresh = /^\/api\/tech-error-reports\/reports\/100\/incident-links\/issues\/([^/]+)\/refresh$/.exec(p)
    if (refresh && req.method() === 'POST') {
      const b = JSON.parse(req.postData() || '{}')
      inc.refreshes.push({ iid: decodeURIComponent(refresh[1]), ...b })
      inc.links = inc.links.map((l) => (l.issue_id === decodeURIComponent(refresh[1])
        ? { ...l, state: 'ok', pinned: b.unpin === 'all' ? [] : l.pinned.filter((g) => !(b.unpin || []).includes(g)) } : l))
      inc.report.report = { ...inc.report.report, version: (b.version ?? inc.report.report.version) + 1 }
      return json(incDetail())
    }
    const unlink = /^\/api\/tech-error-reports\/reports\/100\/incident-links\/issues\/([^/]+)$/.exec(p)
    if (unlink && req.method() === 'DELETE') {
      inc.unlinks.push(decodeURIComponent(unlink[1]))
      inc.links = inc.links.filter((l) => l.issue_id !== decodeURIComponent(unlink[1]))
      return json({ success: true, data: { incident_links: inc.links } })
    }
    return route.fallback()
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
    // Anahtar (Switch): görünür 18 px; dokunmatikte ::after ile ortalanmış 40×40 vuruş alanı taşır (shadcn/switch.jsx) — o kutu ölçülür
    if (b.getAttribute('role') === 'switch') {
      const sa = getComputedStyle(b, '::after')
      // Yalnız GERÇEK bir ::after vuruş alanı sayılır (genel daldaki gibi: içerik var ve mutlak konumlu); yoksa düğmenin kendisi ölçülür
      if (sa.content !== 'none' && sa.position === 'absolute') {
        if (!(parseFloat(sa.height) >= 39.5 && parseFloat(sa.width) >= 39.5)) out.push(`switch ${sa.width}×${sa.height}`)
        continue
      }
    }
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

/** Kanal analizi hücre bağlantıları (te-an-cell düğmeleri): dokunmatikte GENİŞLİK de ≥ 40 px ("—" tek karakter). */
function narrowCells(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  let measured = 0
  for (const b of root.querySelectorAll('button[data-slot="te-an-cell"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    measured++
    if (r.width < 39.5 || r.height < 39.5) out.push(`"${(b.textContent || '').trim().slice(0, 20)}" ${Math.round(r.width)}×${Math.round(r.height)}`)
  }
  return measured ? out : ['NO_CELLS']
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

      // "Yeni kanal" penceresi (kullanıcı isteği 2026-10-17): şablon kaynağı shadcn seçim kartları — 4 seçenek (2026-10-18:
      // "Takım kütüphanesinden" eklendi; bu senaryoda kütüphane boş → kart seçilemez), sığar, 40 px
      await page.locator('[data-slot="te-new-channel"]').click()
      const dlg = page.locator('[data-slot="te-channel-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="te-source-options"] [role="radio"]')).toHaveCount(4)
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
      await page.evaluate(() => Promise.all(document.getAnimations().filter((a) => a.effect?.getComputedTiming().endTime !== Infinity).map((a) => a.finished.catch(() => null))))
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

    test('kısaltılmış sayı ve sıfır işareti (2026-10-18): "4.4G" yazılır, kaydedilir, yeniden açılınca "4,4G" + tam sayı altında; 0 adet yanında yeşil tik; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      reportOverride = NUMDISP_REPORT
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const total = editor.getByLabel('Toplam işlem adedi', { exact: true })
      // Saklı üs → giriş kutusu kısaltmasıyla (arayüz dilinde), altında tam sayı
      await expect(total).toHaveValue(/^(4,4G|4\.4G)$/, { timeout: 20_000 })
      await expect(editor.locator('[data-field="values.total-tx"] [data-slot="te-number-expanded"]')).toContainText(/4[.,]400[.,]000[.,]000/)
      // Geçen ay ipucu geçen ayın kendi yazımıyla, harften önce gruplamasız (inceleme 2026-10-18)
      const prevHint = editor.locator('[data-field="values.total-tx"] [data-slot="te-prev"]')
      await expect(prevHint).toContainText('4350M')
      await expect(prevHint).not.toContainText(/4[.,]350M/)
      // Anomali adedi de kısaltmasıyla
      await expect(editor.getByLabel('Bu konudan kaynaklı teknik hata adedi', { exact: true })).toHaveValue(/^(681,6k|681\.6k)$/)
      // Sıfır adet: kota hatası yanında yeşil tik (ekran okuyucuya metin); dolu adette ve anomali konusunun adedinde yok
      await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-zero-ok"]')).toBeVisible()
      await expect(editor.locator('[data-field="values.unmapped-count"] [data-slot="te-zero-ok"]')).toHaveCount(0)
      await expect(editor.getByLabel('Etkilenen müşteri sayısı', { exact: true })).toHaveValue('0')
      await expect(editor.locator('[data-slot="te-issue"] [data-slot="te-zero-ok"]')).toHaveCount(0)
      await expect(editor.getByLabel('Kota hatası adedi', { exact: true })).toHaveAccessibleDescription(/(Sıfır — sorun yok|Zero — no issues)/)
      // Kutucuklar: kısaltmalı değer + kısa fark, sıfır kutucuğunda tik
      const tiles = editor.locator('[data-slot="te-tile"]')
      await expect(tiles.filter({ hasText: 'Toplam işlem adedi' })).toContainText('4,4G')
      await expect(tiles.filter({ hasText: 'Toplam işlem adedi' })).toContainText('+50M')
      await expect(tiles.filter({ hasText: 'Kota hatası adedi' }).locator('[data-slot="te-zero-ok"]')).toBeVisible()
      // Özet önizlemesi PDF'teki gibi
      await expect(editor.locator('[data-slot="te-sum-text"]')).toContainText('4,4G')
      // İşaret yazıya binmez: kutunun içinde, metnin sağında
      const zeroBox = await editor.locator('[data-field="values.quota-count"] [data-slot="te-zero-ok"]').boundingBox()
      const inputBox = await editor.getByLabel('Kota hatası adedi', { exact: true }).boundingBox()
      expect(zeroBox.x + zeroBox.width).toBeLessThanOrEqual(inputBox.x + inputBox.width + 0.5)
      expect(zeroBox.y).toBeGreaterThanOrEqual(inputBox.y - 0.5)
      expect(zeroBox.y + zeroBox.height).toBeLessThanOrEqual(inputBox.y + inputBox.height + 0.5)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kısaltma)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (kısaltma)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (kısaltma)').toEqual([])
      // Kutucuk değeri + tik kutucuğa sığar
      for (const tile of await tiles.all()) {
        const tb = await tile.boundingBox()
        const vb = await tile.locator('[data-slot="te-tile-value"]').boundingBox()
        expect(vb.x + vb.width).toBeLessThanOrEqual(tb.x + tb.width + 0.5)
      }
      // İnceleme 2026-10-18: geçersiz yazımda ("0x") işaret hatanın yanında kalmaz; "0" ile geri gelir
      const quota = editor.getByLabel('Kota hatası adedi', { exact: true })
      await quota.fill('0x')
      await expect(editor.locator('[data-field="values.quota-count"] [role="alert"]')).toBeVisible()
      await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-zero-ok"]')).toHaveCount(0)
      await quota.fill('0')
      await expect(editor.locator('[data-field="values.quota-count"] [data-slot="te-zero-ok"]')).toBeVisible()
      // Yalnız oran tabanı (toplam işlem — hata oranının paydası) 0 iken işaretsiz; hata adedi (oranın payı) 0 iken işaretli
      await total.fill('0')
      await editor.getByLabel('Ay içindeki teknik hata adedi', { exact: true }).fill('0')
      await expect(editor.locator('[data-field="values.error-count"] [data-slot="te-zero-ok"]')).toBeVisible()
      await expect(editor.locator('[data-field="values.total-tx"] [data-slot="te-zero-ok"]')).toHaveCount(0)
      await expect(tiles.filter({ hasText: 'Toplam işlem adedi' }).locator('[data-slot="te-zero-ok"]')).toHaveCount(0)
      await editor.getByLabel('Ay içindeki teknik hata adedi', { exact: true }).fill('526806')
      // "4.4G" yazılır (iki dilde de ondalık: sonekten önceki tek ayraç) ve map adedi "1,25M"; kayıt üsleri gönderir
      await total.fill('4.4G')
      await editor.getByLabel('Map yapılmamış hata adedi', { exact: true }).fill('1,25M')
      await expect(editor.locator('[data-field="values.unmapped-count"] [data-slot="te-number-expanded"]')).toContainText(/1[.,]250[.,]000/)
      // Kullanıcı isteği (2026-10-18): "teknik hata adedine 4,4G … yazdığımda … yönetici özetinde gözükmesini" — yazılır yazılmaz
      // özet cümlesi ve kutucuğu "4,4G" (PDF / e-posta aynı kural: TechErrorReportPdfWriterTest.technicalErrorCountTypedAbbreviatedReachesExecutiveSummary)
      await editor.getByLabel('Ay içindeki teknik hata adedi', { exact: true }).fill('4,4G')
      const sumText = editor.locator('[data-slot="te-sum-text"]')
      await expect(sumText).toContainText('Ay içindeki teknik hata adedi 4,4G olarak gerçekleşmiştir.')
      await expect(sumText).not.toContainText(/4[.,]400[.,]000[.,]000/)
      const errorTile = tiles.filter({ hasText: 'Ay içindeki teknik hata adedi' }).locator('[data-slot="te-tile-value"]')
      await expect(errorTile).toContainText('4,4G')
      await expect(errorTile).not.toContainText(/4[.,]400[.,]000[.,]000/)
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.units?.['unmapped-count']).toBe(6)
      expect(savedReport.values.fields).toMatchObject({ 'total-tx': 4400000000, 'error-count': 4400000000, 'unmapped-count': 1250000 })
      expect(savedReport.values.units).toEqual({ 'total-tx': 9, 'error-count': 9, 'unmapped-count': 6, 'issues.issues.i-a.errors': 3 })
      // Yeniden aç: sunucunun sakladığı belge → kutular kısaltmasıyla
      reportOverride = { ...NUMDISP_REPORT, values: savedReport.values, report: { ...NUMDISP_REPORT.report, version: savedReport.version + 1 } }
      await page.reload()
      await expect(editor.getByLabel('Toplam işlem adedi', { exact: true })).toHaveValue(/^(4,4G|4\.4G)$/, { timeout: 20_000 })
      await expect(editor.getByLabel('Ay içindeki teknik hata adedi', { exact: true })).toHaveValue(/^(4,4G|4\.4G)$/)
      await expect(editor.locator('[data-slot="te-sum-text"]')).toContainText('Ay içindeki teknik hata adedi 4,4G olarak gerçekleşmiştir.')
      await expect(editor.getByLabel('Map yapılmamış hata adedi', { exact: true })).toHaveValue(/^(1,25M|1\.25M)$/)
      await expect(page.locator('[data-slot="te-save"]')).toBeDisabled()                // sahte "kaydedilmemiş değişiklik" yok
      // Tam yazılan sayı tam kalır: üs düşer
      await editor.getByLabel('Map yapılmamış hata adedi', { exact: true }).fill('1250000')
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.units?.['unmapped-count'] ?? 'yok').toBe('yok')
      await page.screenshot({ path: `test-results/channel-reports-numdisp-${vp.name}.png`, fullPage: true })
    })

    test('anomali konusu (2026-10-18): kart çipleri, canlı pay, rapordaki metin; yeni konu — takvim + saat, kesinti ID, müşteri, görsel; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(150_000)
      await mock(page)
      // Arayüz Türkçe (varsayılan İngilizce): metinler ve sayı yazımı TR — rapor metni zaten şablon dilinde (TR)
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
      reportOverride = JSON.parse(JSON.stringify(ANOMALY_REPORT))
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const first = editor.locator('[data-slot="te-issue"][data-issue="k1"]')
      await expect(first.locator('[data-slot="te-issue-title"]')).toContainText(`${LONG_TOPIC}: Hata Artışı`, { timeout: 20_000 })
      // Çipler: zaman, adet + pay, kesinti + ID, müşteri
      const chips = first.locator('[data-slot="te-issue-chips"]')
      await expect(chips).toContainText(/12 Eylül 2026 · 14:00–15:30|12 September 2026 · 14:00–15:30/)
      await expect(chips).toContainText(/%3,4|3\.4%/)
      await expect(chips).toContainText('INC-2026-0915-MOBIL-LIMIT-SORGU-ZAMAN-ASIMI')
      await expect(first.locator('[data-slot="te-issue-share-value"]')).toHaveText(/^(%3,4|3\.4%)$/)
      await expect(first.locator('[data-slot="te-issue-lead"]')).toContainText(
        '12 Eylül 2026, 14:00–15:30 arasında 1.700 teknik hata alındı; bu, dönemdeki tüm teknik hataların %3,4 kadarıdır.')
      await expect(first.locator('[data-slot="te-issue-lead"]')).toContainText('Müşteri etkisi: 320 müşteri.')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (anomali)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (anomali)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (anomali)').toEqual([])
      // Çipler kartın içinde kalır (uzun ID sarar)
      const cardBox = await first.boundingBox()
      for (const b of await chips.locator('[data-slot="badge"]').all()) {
        const bb = await b.boundingBox()
        expect(bb.x + bb.width, 'çip kartın içinde').toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
      }

      // Yeni konu: ad, adet → canlı pay
      await editor.locator('[data-slot="te-add-issue"]').click()
      const card = editor.locator('[data-slot="te-issue"]').nth(1)
      await card.getByLabel('Konu', { exact: true }).fill('Fatura Ödeme')
      await card.getByLabel('Bu konudan kaynaklı teknik hata adedi', { exact: true }).fill('1.250')
      await expect(card.locator('[data-slot="te-issue-share-value"]')).toHaveText(/^(%2,5|2\.5%)$/)
      // Tarih takvimden (raporun ayı), saatler, gece yarısını geçen aralık
      await card.locator('[data-slot="te-issue-when"] [data-slot="date-picker-trigger"]').click()
      const dayKey = await page.evaluate(() => new Date(2026, 8, 30).toLocaleDateString())
      await page.locator(`[data-day="${dayKey}"]`).click()
      await card.getByLabel('Başlangıç saati').fill('23:30')
      await card.getByLabel('Bitiş saati').fill('01:15')
      await expect(card.getByText(/Bitiş saati başlangıçtan sonra olmalı/)).toBeVisible()
      await card.getByRole('checkbox', { name: 'Ertesi gün bitti' }).click()
      await expect(card.getByText(/Bitiş saati başlangıçtan sonra olmalı/)).toHaveCount(0)
      // Kesinti Evet → ID açılır (odakta); müşteri etkisi Evet → sayı
      await card.locator('[data-slot="te-issue-outage"]').getByRole('radio', { name: 'Evet' }).click()
      const oid = card.getByLabel('Kesinti ID', { exact: true })
      await expect(oid).toBeFocused()
      await oid.fill('INC-2026-0930')
      await card.locator('[data-slot="te-issue-impact"]').getByRole('radio', { name: 'Evet' }).click()
      await card.getByLabel('Etkilenen müşteri sayısı', { exact: true }).fill('45')
      await expect(card.locator('[data-slot="te-issue-lead"]')).toContainText(
        '30 Eylül 2026, 23:30 – ertesi gün 01:15 arasında 1.250 teknik hata alındı; bu, dönemdeki tüm teknik hataların %2,5 kadarıdır.')
      await expect(card.locator('[data-slot="te-issue-lead"]')).toContainText('Kesinti kaynaklıdır (Kesinti ID: INC-2026-0930). Müşteri etkisi: 45 müşteri.')
      // Görsel: kaydedilmemiş konu → önce rapor kaydedilir, sonra konu yuvasına yüklenir
      const id = await card.getAttribute('data-issue')
      await card.locator(`[data-slot-id="iss:issues:${id}:1"] input[type="file"]`).setInputFiles({ name: 'saatlik-hata.png', mimeType: 'image/png', buffer: stripedPng(400, 200) })
      await expect.poll(() => uploads.length).toBe(1)
      expect(savedReport, 'yüklemeden önce kayıt').not.toBeNull()
      const sentIssue = savedReport.values.issues.issues.find((x) => x.id === id)
      expect(sentIssue).toMatchObject({ date: '2026-09-30', start: '23:30', end: '01:15', end_next_day: true, outage: true,
        outage_id: 'INC-2026-0930', impact: true, customers: 45, errors: 1250 })
      expect(sentIssue).not.toHaveProperty('share')
      await expect(card.getByLabel('Görsel 1 açıklaması (isteğe bağlı)')).toBeVisible()
      await card.getByLabel('Görsel 1 açıklaması (isteğe bağlı)').fill('Saatlik teknik hata adedi')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (yeni konu)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (yeni konu)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (yeni konu)').toEqual([])
      // Telefonda saatler yan yana, tarih tam satır; geniş ekranda üçü bir satırda
      const dateBox = await card.locator('[data-field$=":date"]').boundingBox()
      const startBox = await card.locator('[data-field$=":start"]').boundingBox()
      const endBox = await card.locator('[data-field$=":end"]').boundingBox()
      expect(Math.abs(startBox.y - endBox.y), 'başlangıç / bitiş aynı satırda').toBeLessThanOrEqual(2)
      if (vp.width < 640) expect(startBox.y, 'telefonda tarih üstte').toBeGreaterThan(dateBox.y + dateBox.height - 1)
      else expect(Math.abs(dateBox.y - startBox.y), 'geniş ekranda tek satır').toBeLessThanOrEqual(2)
      // Kısaltma (2026-10-18): konu adedi "1,25M" yazılır — giriş metni ve çip "1,25M" der, pay SAYIYLA (1.250.000 ÷ 50.000.000
      // = %2,5; "1,25" ile hesaplansaydı ~%0); kayıtta sayı tam, üs ayrı (issues.<bölüm>.<konu>.errors); konu adedine sıfır işareti yok
      await editor.getByLabel('Ay içindeki teknik hata adedi', { exact: true }).fill('50M')
      await card.getByLabel('Bu konudan kaynaklı teknik hata adedi', { exact: true }).fill('1,25M')
      await expect(card.locator('[data-slot="te-issue-share-value"]')).toHaveText('%2,5')
      await expect(card.locator('[data-slot="te-issue-lead"]')).toContainText(
        '30 Eylül 2026, 23:30 – ertesi gün 01:15 arasında 1,25M teknik hata alındı; bu, dönemdeki tüm teknik hataların %2,5 kadarıdır.')
      await expect(card.locator('[data-slot="te-issue-lead"]')).not.toContainText('1.250.000')
      await expect(card.locator('[data-slot="te-issue-chips"]')).toContainText('1,25M')
      await expect(card.locator('[data-slot="te-zero-ok"]')).toHaveCount(0)
      await page.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.units?.[`issues.issues.${id}.errors`]).toBe(6)
      expect(savedReport.values.issues.issues.find((x) => x.id === id).errors).toBe(1250000)
      expect(savedReport.values.fields['error-count']).toBe(50000000)
      await page.screenshot({ path: `test-results/channel-reports-anomaly-${vp.name}.png` })
    })

    test('olay kaydından konu (2026-11-20): pencere sığar, seç → önizle → al; bağlı konu çubuğu; açılışta tek eşitleme; elle değişen alan ve "Olaydaki değere dön"; bağı kopar', async ({ page }) => {
      test.setTimeout(180_000)
      await mock(page)
      await mockIncidents(page)
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const importBtn = editor.locator('[data-slot="te-inc-import"]')
      await expect(importBtn).toBeVisible({ timeout: 20_000 })
      await expect(importBtn).toHaveText(/Olay kaydından al/)

      // Pencere: aday listesi (uzun başlık sarar), "bu raporda" olan seçilemez; telefonda kart, genişte tablo
      await importBtn.click()
      const dlg = page.getByRole('dialog', { name: 'Olay kaydından konu al' })
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="te-inc-row"]')).toHaveCount(3)
      expect(inc.candidateCalls[0].get('page')).toBe('0')
      await expect(dlg.locator('[data-slot="te-inc-list"]')).toHaveAttribute('data-layout', vp.width < 640 ? 'cards' : /table|cards/)
      await expect(dlg.locator('[data-inc-check="12"]')).toBeDisabled()
      await expect(dlg.locator('[data-slot="te-inc-row"][data-incident="12"] [data-slot="te-inc-imported"]')).toHaveText('Bu raporda')
      await expect(dlg.locator('[data-slot="te-inc-truncated"]')).toContainText('yalnız ilk 2.000 olay kaydında arandı')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (olay penceresi)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-inc-dialog"]'), 'kırpılan metin (olay penceresi)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="dialog"]'), 'küçük hedef (olay penceresi)').toEqual([])
      const dbox = await dlg.boundingBox()
      expect(dbox.x + dbox.width, 'pencere ekranın içinde').toBeLessThanOrEqual(vp.width + 1)

      await dlg.locator('[data-inc-check="9"]').click()
      await dlg.locator('[data-inc-check="11"]').click()
      await expect(dlg.locator('[data-slot="te-inc-selected"]')).toHaveText('2 olay seçildi')
      await dlg.locator('[data-slot="te-inc-next"]').click()
      await expect(dlg.locator('[data-slot="te-inc-proposal"]')).toHaveCount(2)
      expect(inc.previews[0]).toEqual({ section_id: 'issues', incident_ids: [9, 11], parts: ['rca', 'impact', 'resolution'], images: true })
      await expect(dlg.locator('[data-slot="te-inc-proposal"][data-incident="11"] [data-note="outage_id_missing"]'))
        .toContainText('Kesinti ID girilmemiş')
      await expect(dlg.locator('[data-slot="te-inc-proposal"][data-incident="11"] [data-slot="te-inc-pv-row"][data-group="outage"]'))
        .toHaveAttribute('data-filled', 'true')
      const img9 = dlg.locator('[data-slot="te-inc-proposal"][data-incident="9"] [data-slot="te-inc-pv-images"]')
      await expect(img9).toContainText('1 görsel kopyalanacak')
      await expect(img9.locator('[data-slot="te-inc-pv-img-skip"]')).toHaveText('Görsel #41: Görsel çok büyük (en çok 5 MB, 6000 piksel).')
      await expect(img9.locator('[data-slot="te-inc-pv-img-remaining"]')).toContainText('1 görsel daha alınmayacak')
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (önizleme)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-inc-dialog"]'), 'kırpılan metin (önizleme)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="dialog"]'), 'küçük hedef (önizleme)').toEqual([])
      await dlg.locator('[data-slot="te-inc-import-confirm"]').click()
      await expect(dlg).toBeHidden()
      expect(inc.imports[0]).toEqual({ version: 4, section_id: 'issues', incident_ids: [9, 11], parts: ['rca', 'impact', 'resolution'], images: true })

      // Yeni konular açık, bağlı; ilk konunun "Konu" alanı odakta
      const bar9 = editor.locator('[data-slot="te-issue-incident"][data-incident="9"]')
      await expect(bar9).toBeVisible()
      await expect(bar9.locator('[data-slot="te-issue-incident-state"]')).toHaveText(/Olayı izliyor/)
      await expect(bar9.locator('[data-slot="te-issue-incident-source"]')).toContainText(INC_LONG)
      await expect(editor.locator('#te-iss-issues-i-9-topic')).toBeFocused()
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (bağlı konu)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (bağlı konu)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (bağlı konu)').toEqual([])

      // Olay değişti + kullanıcı "Konu"yu elle değiştirmiş: yeniden açılışta TEK eşitleme (taslak temiz) → bağ eşit, iğne kalır
      inc.links = inc.links.map((l) => (l.issue_id === 'i-9' ? { ...l, state: 'stale', pinned: ['topic'] } : l))
      inc.syncTo = inc.links.map((l) => (l.issue_id === 'i-9' ? { ...l, state: 'ok', notes: ['time_unknown'] } : l))
      await page.reload()
      await expect(bar9.locator('[data-slot="te-issue-incident-state"]')).toHaveText(/Olayı izliyor/, { timeout: 20_000 })
      expect(inc.syncs).toBe(1)
      await expect(bar9.locator('[data-slot="te-issue-incident-notes"] [data-note="time_unknown"]')).toBeVisible()
      const card9 = editor.locator('[data-slot="te-issue"][data-issue="i-9"]')
      const marker = card9.locator('[data-slot="te-issue-topic"] [data-slot="te-issue-pinned"][data-group="topic"]')
      await expect(marker).toContainText('Konu elle değiştirildi')
      await expect(bar9.locator('[data-slot="te-issue-incident-pinned"]')).toContainText('1 alan elle değiştirildi')
      await page.waitForTimeout(300)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (iğneli konu)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (iğneli konu)').toEqual([])
      await page.waitForTimeout(500)
      expect(inc.syncs, 'eşitleme döngüsü yok').toBe(1)

      // "Olaydaki değere dön": yalnız o grup, okunan sürümle
      await marker.getByRole('button', { name: 'Olaydaki değere dön: Konu' }).click()
      await expect(marker).toHaveCount(0)
      expect(inc.refreshes).toHaveLength(1)
      expect(inc.refreshes[0]).toMatchObject({ iid: 'i-9', unpin: ['topic'] })
      expect(inc.refreshes[0].version).toEqual(expect.any(Number))

      // Bağı kopar: onay → DELETE; konu kalır, kaynak çubuğu gider
      await card9.getByRole('button', { name: 'Olay #9 — Olay kaydı işlemleri' }).click()
      await page.getByRole('menuitem', { name: /Bağı kopar/ }).click()
      const confirm = page.getByRole('dialog', { name: 'Olay kaydıyla bağ koparılsın mı?' })
      await confirm.getByRole('button', { name: 'Bağı kopar' }).click()
      await expect(bar9).toHaveCount(0)
      expect(inc.unlinks).toEqual(['i-9'])
      await expect(editor.locator('#te-iss-issues-i-9-topic')).toHaveValue(INC_LONG)
      await expect(editor.locator('[data-slot="te-issue-incident"][data-incident="11"]')).toBeVisible()

      // Olay başka bir takıma taşındı (ya da okuma izni yok): sunucu `hidden` gönderir, başlık / durum yok. Uzun kaynak metni
      // sarar, rozet sığar; olaydan değer alan eylem (Güncelle) çizilmez; açılışta eşitleme yok (bayat bağ yok)
      inc.links = inc.links.map((l) => (l.issue_id === 'i-11' ? { ...l, state: 'hidden', title: null, status: null, occurred_at: null } : l))
      inc.syncTo = null
      const syncsBefore = inc.syncs
      await page.reload()
      const bar11 = editor.locator('[data-slot="te-issue-incident"][data-incident="11"]')
      await expect(bar11).toHaveAttribute('data-state', 'hidden', { timeout: 20_000 })
      await expect(bar11.locator('[data-slot="te-issue-incident-source"]'))
        .toHaveText('Olay kaydı görüntülenemiyor (erişiminiz yok ya da başka bir takıma taşındı)')
      await expect(bar11.locator('[data-slot="te-issue-incident-state"]')).toHaveText(/Görüntülenemiyor/)
      await expect(bar11.locator('[data-slot="te-issue-incident-hint"]')).toContainText('başka bir takıma taşındı')
      await expect(bar11.locator('[data-slot="te-issue-incident-update"]')).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(inc.syncs, 'gizli bağ eşitleme tetiklemez').toBe(syncsBefore)
      expect(await page.evaluate(overflowX), 'yatay taşma (gizli bağ)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (gizli bağ)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (gizli bağ)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-incident-${vp.name}.png` })
    })

    test('konu akordiyonu (2026-10-18): var olan konular kapalı, çipler başlıkta; tıklama + klavye; yeni konu açık ve odakta; Kaydet geçersiz alanlı kapalı konuyu açar; tüm konuları aç / kapat; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(150_000)
      // Bölümler açık (kullanıcı daha önce açmış), konular KAPALI — akordeonun kendisi sınanıyor
      await mock(page, { openTopics: false })
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
      reportOverride = JSON.parse(JSON.stringify(ANOMALY_REPORT))
      reportOverride.values.issues.issues.push({ id: 'k2', topic: 'Fatura Ödeme ve Otomatik Talimat Servisi (Kurumsal / Bireysel Müşteriler)',
        title: '', title_auto: true, description: '', errors: 420, outage: false, impact: false })
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const issues = editor.locator('[data-slot="te-issues"]')
      const first = editor.locator('[data-slot="te-issue"][data-issue="k1"]')
      const second = editor.locator('[data-slot="te-issue"][data-issue="k2"]')
      const toggle = (card) => card.locator('[data-slot="te-issue-toggle"]')
      await expect(first).toHaveAttribute('data-open', 'false', { timeout: 20_000 })
      await expect(second).toHaveAttribute('data-open', 'false')
      // Kapalı konu hikâyesini başlıkta söyler: başlık + zaman, adet + pay, kesinti + ID, müşteri
      await expect(toggle(first)).toHaveAttribute('aria-expanded', 'false')
      await expect(first.locator('[data-slot="te-issue-title"]')).toContainText(`1. ${LONG_TOPIC}: Hata Artışı`)
      const chips = first.locator('[data-slot="te-issue-chips"]')
      await expect(chips).toContainText(/12 Eylül 2026 · 14:00–15:30/)
      await expect(chips).toContainText('INC-2026-0915-MOBIL-LIMIT-SORGU-ZAMAN-ASIMI')
      await expect(chips).toContainText('320 müşteri')
      await expect(second.locator('[data-slot="te-issue-chips"]')).toContainText('Kesinti değil')
      await expect(first.locator('[data-slot="te-issue-content"]')).toBeHidden()
      await expect(first.getByLabel('Konu', { exact: true })).toBeHidden()
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kapalı konular)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (kapalı konular)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (kapalı konular)').toEqual([])
      // Başlık satırının tamamı hedef: düğme kartın genişliğinin büyük kısmı; çipler kartın içinde; taşı / sil düğmenin dışında
      const cardBox = await first.boundingBox()
      const tBox = await toggle(first).boundingBox()
      expect(tBox.width, 'aç / kapat satırı geniş').toBeGreaterThan(cardBox.width * 0.55)
      for (const b of await chips.locator('[data-slot="badge"]').all()) {
        const bb = await b.boundingBox()
        expect(bb.x + bb.width, 'çip kartın içinde').toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
      }
      // Taşı / sil düğmenin DIŞINDA: geniş kartta sağında, dar kartta (telefon / tablet) alt satırda, sağa yaslı
      const removeBox = await first.locator('[data-slot="te-issue-remove"]').boundingBox()
      const besideOrBelow = removeBox.x >= tBox.x + tBox.width - 1 || removeBox.y >= tBox.y + tBox.height - 1
      expect(besideOrBelow, 'sil düğmesi aç / kapat düğmesinin dışında').toBe(true)
      expect(removeBox.x + removeBox.width, 'sil düğmesi kartın içinde').toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)
      if (vp.width >= 1280) expect(Math.abs(removeBox.y - tBox.y), 'geniş ekranda tek satır').toBeLessThanOrEqual(4)

      // Tıklama açar; içerik görünür; açık konu bu rapor için hatırlanır
      await toggle(first).click()
      await expect(first).toHaveAttribute('data-open', 'true')
      await expect(first.getByLabel('Konu', { exact: true })).toBeVisible()
      // Klavye: odaktaki düğmede Enter açar, Boşluk kapatır
      await toggle(second).focus()
      await page.keyboard.press('Enter')
      await expect(second).toHaveAttribute('data-open', 'true')
      await page.keyboard.press('Space')
      await expect(second).toHaveAttribute('data-open', 'false')
      await expect.poll(() => page.evaluate(() => localStorage.getItem('sm.te.open.ri.100'))).toBe(JSON.stringify({ ids: ['issues:k1'] }))

      // Yeni konu açık gelir, odak Konu alanında; öteki konuların durumu değişmez
      await editor.locator('[data-slot="te-add-issue"]').click()
      const fresh = editor.locator('[data-slot="te-issue"]').nth(2)
      await expect(fresh).toHaveAttribute('data-open', 'true')
      await expect(fresh.getByLabel('Konu', { exact: true })).toBeFocused()
      await expect(second).toHaveAttribute('data-open', 'false')
      await fresh.getByLabel('Konu', { exact: true }).fill('Mobil Giriş')

      // Geçersiz alanlı konu kapatılınca rozet; Kaydet konuyu açar, alanın altında ileti, kayıt GİTMEZ
      await first.getByLabel('Kesinti ID', { exact: true }).fill('')
      await toggle(first).click()
      await expect(first).toHaveAttribute('data-open', 'false')
      await expect(first.locator('[data-slot="te-issue-invalid"]')).toContainText('1 alan düzeltilmeli')
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (rozet)').toEqual([])
      await page.locator('[data-slot="te-save"]').click()
      await expect(first).toHaveAttribute('data-open', 'true')
      await expect(first.locator('[data-field="issue:issues:k1:outage_id"] [role="alert"]')).toBeVisible()
      await expect(first.locator('[data-slot="te-issue-invalid"]')).toHaveCount(0)
      expect(savedReport, 'geçersiz alanla kayıt gitmez').toBeNull()
      await first.getByLabel('Kesinti ID', { exact: true }).fill('INC-2026-0915')

      // Tüm konuları aç / kapat: yalnız konular; bölüm açık kalır
      const all = issues.locator('[data-slot="te-issues-all"]')
      await expect(all).toHaveText(/Tüm konuları aç/)
      await all.click()
      for (const c of [first, second, fresh]) await expect(c).toHaveAttribute('data-open', 'true')
      await expect(all).toHaveText(/Tüm konuları kapat/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (açık konular)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (açık konular)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (açık konular)').toEqual([])
      await all.click()
      for (const c of [first, second, fresh]) await expect(c).toHaveAttribute('data-open', 'false')
      await expect(issues).toHaveAttribute('data-open', 'true')
      await page.screenshot({ path: `test-results/channel-reports-topic-accordion-${vp.name}.png`, fullPage: true })
    })

    test('anomali (2026-10-18): "konuyu rapora alma" notu; şablonda "Anomali alanları" paneli — anahtarlar, pay alanı, seçim kartları; sığar, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
      const hide = { ...ANOMALY_TEMPLATE, sections: [ANOMALY_TEMPLATE.sections[0], { ...ANOMALY_ISSUES, no_impact_display: 'hide_topic' }] }
      reportOverride = { ...JSON.parse(JSON.stringify(ANOMALY_REPORT)), template: hide }
      reportOverride.values.issues.issues[0].impact = false
      delete reportOverride.values.issues.issues[0].customers
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const first = page.locator('[data-slot="te-issue"][data-issue="k1"]')
      await expect(first).toHaveAttribute('data-hidden', 'true', { timeout: 20_000 })
      await expect(first.locator('[data-slot="te-issue-hidden-note"]')).toContainText('Bu konu rapora girmeyecek')
      await expect(first.locator('[data-slot="te-issue-chips"]')).toContainText('Rapora girmeyecek')
      await expect(page.locator('[data-slot="te-issues-hidden-note"]')).toContainText('1 konu müşteri etkisi olmadığı için')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (gizli konu)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (gizli konu)').toEqual([])

      templateOverride = hide
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      const panel = page.locator('[data-slot="te-tpl-anomaly"]')
      await expect(panel).toBeVisible({ timeout: 20_000 })
      await expect(panel.locator('[data-slot="te-tpl-anomaly-ratio-of"]')).toHaveValue('error-count')
      await expect(panel.getByRole('radio', { name: /Konuyu rapora alma/ })).toBeChecked()
      await panel.getByRole('switch', { name: 'Kesinti kaynaklı mı?' }).click()
      await expect(panel.locator('[data-slot="te-tpl-anomaly-outage"]')).toHaveAttribute('data-on', 'false')
      await panel.getByRole('switch', { name: 'Kesinti kaynaklı mı?' }).click()
      await panel.locator('[data-slot="te-tpl-anomaly-outage"]').getByLabel('ID alanının adı').fill('Olay / değişiklik kaydı numarası')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (şablon anomali)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-tpl-anomaly"]'), 'kırpılan metin (şablon anomali)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-tpl-anomaly"]'), 'küçük hedef (şablon anomali)').toEqual([])
      await page.getByRole('button', { name: 'Şablonu kaydet' }).click()
      await expect.poll(() => savedTemplate?.template?.sections?.find((x) => x.kind === 'issues')?.outage_id_label).toBe('Olay / değişiklik kaydı numarası')
      expect(savedTemplate.template.sections.find((x) => x.kind === 'issues')).toMatchObject({ ask_outage: true, no_impact_display: 'hide_topic', ratio_of: 'error-count' })
      await page.screenshot({ path: `test-results/channel-reports-anomaly-template-${vp.name}.png` })
    })

    test('anomali — inceleme 2026-10-18: İngilizce arayüz (tekil / çoğul, etiketler), Kaydet → zorunlu alan hatası → düzelt, klavyeyle Evet / Hayır, kayıt 409 iken görsel yüklenmez; sığar, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(150_000)
      await mock(page)
      // Arayüz İngilizce (ürün varsayılanı), şablon Türkçe: çipler arayüz dilinde ("1,700 errors"), rapor metni şablon dilinde
      reportOverride = JSON.parse(JSON.stringify(ANOMALY_REPORT))
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const first = editor.locator('[data-slot="te-issue"][data-issue="k1"]')
      await expect(first.locator('[data-slot="te-issue-chips"]')).toContainText('1,700 errors · 3.4%', { timeout: 20_000 })
      await expect(first.locator('[data-slot="te-issue-chips"]')).toContainText('320 customers')
      await expect(first.locator('[data-slot="te-issue-lead"]')).toContainText('1.700 teknik hata alındı')
      await expect(first.getByLabel('Start time', { exact: true })).toHaveValue('14:00')

      // Yeni konu; kesinti KLAVYEYLE Evet → Kesinti ID odakta; boşken Kaydet → hata alanın altında, kayıt gitmez
      await editor.locator('[data-slot="te-add-issue"]').click()
      const card = editor.locator('[data-slot="te-issue"]').nth(1)
      await card.getByLabel('Subject', { exact: true }).fill('Bill Payment')
      await card.getByLabel('Bu konudan kaynaklı teknik hata adedi', { exact: true }).fill('1')
      await expect(card.locator('[data-slot="te-issue-chips"]')).toContainText('1 error · ')
      const outage = card.locator('[data-slot="te-issue-outage"]')
      await outage.getByRole('radio', { name: 'Yes' }).focus()
      await page.keyboard.press('Space')
      const oid = card.getByLabel('Kesinti ID', { exact: true })
      await expect(oid).toBeFocused()
      await expect(card.getByRole('checkbox', { name: 'Ended the next day' })).toBeDisabled()
      await editor.locator('[data-slot="te-save"]').click()
      const required = card.getByText("Enter the outage ID: it's required for a topic caused by an outage.")
      await expect(required).toBeVisible()
      await expect(oid).toHaveAttribute('aria-invalid', 'true')
      expect(savedReport, 'zorunlu alan boşken kayıt gitmez').toBeNull()
      await oid.focus()
      await page.keyboard.type('INC-2026-1001')
      await expect(required).toHaveCount(0)
      await editor.locator('[data-slot="te-save"]').click()
      await expect.poll(() => savedReport?.values?.issues?.issues?.length).toBe(2)
      const sent = savedReport.values.issues.issues[1]
      expect(sent).toMatchObject({ outage: true, outage_id: 'INC-2026-1001', errors: 1 })
      // Klavyeyle Hayır: ok tuşu sonraki seçeneğe, boşluk seçer → ID gizlenir
      await outage.getByRole('radio', { name: 'Yes' }).focus()
      await page.keyboard.press('ArrowRight')
      await expect(outage.getByRole('radio', { name: 'No' })).toBeFocused()
      await page.keyboard.press('Space')
      await expect(card.getByLabel('Kesinti ID', { exact: true })).toHaveCount(0)
      await expect(card.locator('[data-slot="te-issue-lead"]')).toContainText('Kesinti kaynaklı değildir.')

      // Kaydedilmemiş konunun görseli: önceki kayıt 409 → görsel YÜKLENMEZ, yuva nedenini söyler, çakışma uyarısı görünür
      let conflictOnce = true
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/reports/100', async (route) => {
        const req = route.request()
        const body = req.method() === 'PUT' ? JSON.parse(req.postData() || '{}') : null
        if (body && body.auto !== true && conflictOnce) {
          conflictOnce = false
          await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false,
            code: 'CONCURRENT_MODIFICATION', error: 'The report was changed by someone else.' }) })
          return
        }
        await route.fallback()
      })
      await editor.locator('[data-slot="te-add-issue"]').click()
      const third = editor.locator('[data-slot="te-issue"]').nth(2)
      await third.getByLabel('Subject', { exact: true }).fill('Card Limit')
      const id = await third.getAttribute('data-issue')
      const slot = third.locator(`[data-slot-id="iss:issues:${id}:1"]`)
      await slot.locator('input[type="file"]').setInputFiles({ name: 'hata.png', mimeType: 'image/png', buffer: stripedPng(400, 200) })
      await expect(slot.locator('[data-slot="te-image-error"]')).toContainText('someone else saved the report while you were editing it')
      expect(uploads, 'çakışmada görsel gitmez').toHaveLength(0)
      await expect(page.getByText('The report changed while you were editing')).toBeVisible()
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (EN anomali)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-issues"]'), 'kırpılan metin (EN anomali)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-issues"]'), 'küçük hedef (EN anomali)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-anomaly-en-${vp.name}.png` })
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
      // 2026-10-18: kısaltmayla yazılan sayı özette de kısaltmasıyla (PDF / e-posta ile aynı)
      await expect(summary).toContainText(`${LONG_CHANNEL} Kanalından yapılan toplam işlem adedi 1,25M olarak gerçekleşmiştir.`)
      await expect(summary.locator('strong', { hasText: '1,25M' })).toBeVisible()
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
      expect(savedReport.values.units).toEqual({ 'total-tx': 6 })                       // 2026-10-18: kısaltma saklanır
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

    test('ekrandan görsel alma: yapıştır; ekrandan al + kırp (gerçek tuval); Görsel paneli + iki yönlü eşitleme; sığar', async ({ page, context }) => {
      test.setTimeout(180_000)
      await mockDisplayCapture(context)
      await mock(page, { target: context })
      persistUploads = true
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const paste = editor.locator('[data-slot-id="monthly-tx"]')
      await expect(paste).toBeVisible({ timeout: 20_000 })

      // 1) Yapıştırma (Ctrl/⌘+V'nin olayı): yuvanın yükleme yolundan, pano adıyla, ölçü aynen
      await paste.evaluate((el, b64) => {
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const dt = new DataTransfer()
        dt.items.add(new File([bytes], 'image.png', { type: 'image/png' }))
        el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
      }, stripedPng(300, 200).toString('base64'))
      await expect.poll(() => uploads.length, { timeout: 15_000 }).toBe(1)
      expect(uploadSizes[0]).toMatchObject({ w: 300, h: 200 })
      expect(uploadBodies[0].toString('latin1')).toMatch(/filename="monthly-tx-pano-\d{8}-\d{6}\.png"/)
      await expect(paste).toHaveAttribute('data-has-image', 'true')

      // Yuva eylemleri (2026-11-20, kullanıcı isteği: "görsel yükle, Ekrandan al, panodan yapıştır her bir panelde eşit
      // olmalı, aynı sırada olmalı, farklılık olmamalı"): HER yuvada (yarım / tam, dolu / boş) aynı düğmeler, aynı sırada,
      // eşit genişlikte ve AYNI dizilişte — kap yeterince genişse tek satırda eşit karolar, çok darsa (768 px'te yarım
      // yuva) alt alta; yarım ve tam yuvada diziliş ve düğme genişliği aynı. "Diğer işlemler" menüsü yok. Eylemler
      // yuvanın içinde kalır, sözcükler bölünmez / kesilmez.
      // "Kaldır" (2026-11-20, kullanıcı bildirimi: "alta kayıyor, diğer düğmelerle aynı hizada kalmıyor"): AYNI ızgaranın
      // SON hücresi — öteki düğmelerle aynı satırda, aynı boyda. O sütun boş yuvada da AYRILMIŞTIR; bu yüzden dolu ve boş
      // yuvada ana düğmelerin yeri ve genişliği aynıdır (yükleme sonrası düğmeler kaymaz / daralmaz).
      const actions = paste.locator('[data-slot="te-image-actions"]')
      const expectedActions = vp.width < 768 ? ['te-image-upload', 'te-image-paste'] : ['te-image-upload', 'te-image-snip', 'te-image-paste']
      await expect(paste.getByRole('button', { name: /^(Diğer işlemler|More actions): / })).toHaveCount(0)
      await expect(paste.locator('[data-slot="te-image-remove"]')).toBeVisible()
      await page.waitForTimeout(200)
      const seen = {}
      for (const id of ['monthly-tx', 'tech-errors', 'daily-errors']) {   // dolu yarım · boş yarım · boş tam
        const filled = id === 'monthly-tx'
        const main = editor.locator(`[data-slot-id="${id}"] [data-slot="te-image-main-actions"] > button`)
        const info = await main.evaluateAll((els) => els.map((b) => {
          const r = b.getBoundingClientRect()
          // Etiketin en uzun sözcüğü düğmeye sığıyor mu (harf harf bölünme = sığmıyor)
          const label = [...b.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join(' ').trim()
          const probe = document.createElement('span')
          probe.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font:' + getComputedStyle(b).font
          document.body.appendChild(probe)
          let longest = 0
          for (const w of label.split(/\s+/)) { probe.textContent = w; longest = Math.max(longest, probe.getBoundingClientRect().width) }
          probe.remove()
          const cs = getComputedStyle(b)
          const room = r.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - parseFloat(cs.borderLeftWidth) - parseFloat(cs.borderRightWidth)
          return { slot: b.getAttribute('data-slot'), x: r.x, y: r.y, w: r.width, h: r.height, longest, room, col: cs.flexDirection === 'column' }
        }))
        expect(info.map((b) => b.slot), `eylem sırası (${id})`).toEqual(filled ? [...expectedActions, 'te-image-remove'] : expectedActions)
        const row = info.every((b) => Math.abs(b.y - info[0].y) <= 1)
        const stack = info.every((b) => Math.abs(b.x - info[0].x) <= 1)
        expect(row || stack, `tek satır ya da alt alta (${id})`).toBe(true)
        for (let i = 0; i < info.length; i++) {
          expect(Math.abs(info[i].w - info[0].w), `eşit genişlik (${id})`).toBeLessThanOrEqual(1)
          if (row) expect(Math.abs(info[i].h - info[0].h), `eşit yükseklik (${id})`).toBeLessThanOrEqual(1)
          if (i > 0 && row) expect(info[i].x, `soldan sağa sıra (${id})`).toBeGreaterThan(info[i - 1].x)
          if (i > 0 && !row) expect(info[i].y, `yukarıdan aşağı sıra (${id})`).toBeGreaterThan(info[i - 1].y)
          if (vp.touch) expect(info[i].h, `düğme yüksekliği ≥ 40 px (${id})`).toBeGreaterThanOrEqual(39.5)
          // Karoda simge üstte; satırda (alt alta dizilişte) simge solda. Sözcük düğmeye sığar (harf harf bölünmez)
          expect(info[i].col, `karo = tek satır dizilişi (${id})`).toBe(row && info.length > 1)
          const iconRoom = info[i].col ? 0 : 22
          expect(info[i].longest, `en uzun sözcük sığar (${id} · ${info[i].slot})`).toBeLessThanOrEqual(info[i].room - iconRoom + 1)
        }
        seen[id] = { row, w: info[0].w, x0: info[0].x - (await editor.locator(`[data-slot-id="${id}"] [data-slot="te-image-main-actions"]`).boundingBox()).x }
      }
      // Yarım ve tam yuvada, DOLU ve BOŞ yuvada AYNI diziliş ve AYNI düğme genişliği (tam yuvada eylem kabı yarım yuvanın
      // iç genişliğine iner; "Kaldır" sütunu boş yuvada da ayrılmıştır — yükleme sonrası ana düğmeler daralmaz / kaymaz)
      for (const id of ['tech-errors', 'daily-errors']) {
        expect(seen[id].row, `diziliş aynı (${id})`).toBe(seen['monthly-tx'].row)
        expect(Math.abs(seen[id].w - seen['monthly-tx'].w), `düğme genişliği aynı (${id})`).toBeLessThanOrEqual(2)
        expect(Math.abs(seen[id].x0 - seen['monthly-tx'].x0), `ilk düğmenin yeri aynı (${id})`).toBeLessThanOrEqual(1)
      }
      if (vp.width >= 1280 || vp.width < 768) expect(seen['monthly-tx'].row, 'geniş kapta tek satır').toBe(true)
      // "Kaldır" öteki düğmelerle AYNI hizada: aynı ızgaranın son hücresi; tek satır dizilişinde aynı satırda (aynı üst
      // kenar, aynı yükseklik), alt alta dizilişte aynı sol kenar ve aynı genişlikte
      const removeBox = await paste.locator('[data-slot="te-image-remove"]').boundingBox()
      const uploadBox = await paste.locator('[data-slot="te-image-upload"]').boundingBox()
      expect(await paste.locator('[data-slot="te-image-main-actions"] > button').last().getAttribute('data-slot'), '"Kaldır" ızgaranın son düğmesi').toBe('te-image-remove')
      expect(Math.abs(removeBox.width - uploadBox.width), '"Kaldır" öteki düğmelerle aynı genişlikte').toBeLessThanOrEqual(1)
      if (seen['monthly-tx'].row) {
        expect(Math.abs(removeBox.y - uploadBox.y), '"Kaldır" öteki düğmelerle aynı satırda').toBeLessThanOrEqual(1)
        expect(Math.abs(removeBox.height - uploadBox.height), '"Kaldır" aynı yükseklikte').toBeLessThanOrEqual(1)
        expect(removeBox.x, '"Kaldır" satırın sonunda').toBeGreaterThan(uploadBox.x)
      } else {
        expect(Math.abs(removeBox.x - uploadBox.x), '"Kaldır" aynı sol kenarda').toBeLessThanOrEqual(1)
      }
      const slotBox = await paste.boundingBox()
      for (const b of await actions.locator('button').all()) {
        if (!(await b.isVisible())) continue
        const r = await b.boundingBox()
        expect(r.x, 'eylem yuvanın içinde (sol)').toBeGreaterThanOrEqual(slotBox.x - 1)
        expect(r.x + r.width, 'eylem yuvanın içinde (sağ)').toBeLessThanOrEqual(slotBox.x + slotBox.width + 1)
      }
      expect(await page.evaluate(clipped, '[data-slot-id="monthly-tx"]'), 'kırpılan metin (yuva eylemleri)').toEqual([])
      expect(await page.evaluate(clipped, '[data-slot-id="daily-errors"]'), 'kırpılan metin (tam yuva eylemleri)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot-id="monthly-tx"]'), 'küçük hedef (yuva)').toEqual([])
      // Klavye: eylemler sekme sırasında da aynı sırada; dolu yuvada sonuncu "Kaldır"
      await paste.locator('[data-slot="te-image-upload"]').focus()
      for (const next of [...expectedActions.slice(1), 'te-image-remove']) {
        await page.keyboard.press('Tab')
        await expect(paste.locator(`[data-slot="${next}"]`)).toBeFocused()
      }
      await paste.screenshot({ path: `test-results/te-cap-slot-actions-${vp.name}.png` })
      await editor.locator('[data-slot-id="daily-errors"]').screenshot({ path: `test-results/te-cap-slot-actions-full-${vp.name}.png` })

      if (vp.width < 768) {
        // Telefonda ekrandan alma ve panel yok (bozuk düğme bırakılmaz); yapıştırma yolu ve "Panodan yapıştır" düğmesi var
        await expect(editor.locator('[data-slot="te-image-snip"]')).toHaveCount(0)
        await expect(editor.locator('[data-slot="te-images-panel-open"]')).toHaveCount(0)
        await page.waitForTimeout(200)
        expect(await page.evaluate(overflowX), 'yatay taşma (telefon, görsel)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (telefon, görsel)').toEqual([])
        return
      }
      await expect(paste.locator('[data-slot="te-image-snip"]')).toBeVisible()

      // 2) Ekrandan al: ilk basış paylaşımı başlatır (sahte getDisplayMedia = renk bloklu tuvalin akışı), kırpma penceresi
      const snipSlot = editor.locator('[data-slot-id="tech-errors"]')
      await snipSlot.locator('[data-slot="te-image-snip"]').click()
      const dlg = page.locator('[data-slot="te-cap-dialog"]')
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      await expect(editor.locator('[data-slot="te-cap-chip"]').first()).toContainText(/Ekran paylaşımı açık|Screen sharing on/)
      const stage = dlg.locator('[data-slot="te-cap-stage"]')
      await expect(stage).toHaveAttribute('data-source', `${CAP_W}x${CAP_H}`)
      await expect(dlg.locator('[data-slot="alert"]')).toHaveCount(0)   // bloklu kare "boş" sayılmaz
      await page.waitForTimeout(300)
      const db = await dlg.boundingBox()
      expect(db.x, 'pencere solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(db.y + db.height, 'pencere altta taşmaz').toBeLessThanOrEqual(vp.height + 1)
      expect(db.x + db.width, 'pencere sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-cap-dialog"]'), 'kırpılan metin (kırpma)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-cap-dialog"]'), 'küçük hedef (kırpma)').toEqual([])
      // Tutamaklar ≥ 24 px (dokunmatikte 40)
      const handle = await dlg.locator('[data-handle="se"]').boundingBox()
      expect(handle.width).toBeGreaterThanOrEqual(vp.touch ? 39.5 : 23.5)
      // Sürükleyerek bölge seç (tamamı seçiliyken sürükleme YENİ seçim)
      const sb = await stage.boundingBox()
      await page.mouse.move(sb.x + sb.width * 0.2, sb.y + sb.height * 0.3)
      await page.mouse.down()
      await page.mouse.move(sb.x + sb.width * 0.55, sb.y + sb.height * 0.5, { steps: 6 })
      await page.mouse.move(sb.x + sb.width * 0.7, sb.y + sb.height * 0.8, { steps: 6 })
      await page.mouse.up()
      const [rx, ry, rw, rh] = (await stage.getAttribute('data-rect')).split(',').map(Number)
      expect(rw).toBeGreaterThan(CAP_W * 0.4)
      expect(rh).toBeGreaterThan(CAP_H * 0.4)
      await expect(dlg.locator('[data-slot="te-cap-size"]')).toContainText(`${rw} × ${rh}`)
      await page.screenshot({ path: `test-results/te-cap-dialog-${vp.name}.png` })
      await dlg.locator('[data-slot="te-cap-confirm"]').click()
      await expect.poll(() => uploads.length, { timeout: 20_000 }).toBe(2)
      await expect(dlg).toHaveCount(0)
      expect(uploadBodies[1].toString('latin1')).toMatch(/filename="tech-errors-ekran-\d{8}-\d{6}\.png"/)
      // Yüklenen PNG = seçilen bölge, piksel piksel (ölçek yok): ölçü aynı; blok merkezlerinin rengi kaynaktaki bloğun rengi
      // (video çözümü renk dönüşümü yapar — küçük pay)
      const img = decodePng(uploadBodies[1])
      expect(img, 'yüklenen PNG çözülür').not.toBeNull()
      expect([img.w, img.h]).toEqual([rw, rh])
      let checked = 0
      for (let by = 0; by * CAP_BH < CAP_H; by++) {
        for (let bx = 0; bx * CAP_BW < CAP_W; bx++) {
          const cx = bx * CAP_BW + CAP_BW / 2
          const cy = by * CAP_BH + CAP_BH / 2
          if (cx < rx + 4 || cy < ry + 4 || cx >= rx + rw - 4 || cy >= ry + rh - 4) continue
          const got = img.px(cx - rx, cy - ry)
          const want = CAP_COLORS[(bx + by * 3) % CAP_COLORS.length]
          got.forEach((v, k) => expect(Math.abs(v - want[k]), `blok ${bx},${by} kanal ${k}: ${got} ≠ ${want}`).toBeLessThanOrEqual(14))
          checked++
        }
      }
      expect(checked, 'denetlenen blok').toBeGreaterThanOrEqual(4)
      await expect(snipSlot).toHaveAttribute('data-has-image', 'true')
      // İkinci yuva seçici AÇMADAN o anki kareyi alır
      await editor.locator('[data-slot-id="daily-errors"] [data-slot="te-image-snip"]').click()
      await expect(dlg).toBeVisible()
      expect(await page.evaluate(() => window.__capCalls)).toBe(1)
      await dlg.locator('[data-slot="te-cap-cancel"]').click()
      await expect(dlg).toHaveCount(0)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (görsel + çip)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (görsel + çip)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report"]'), 'küçük hedef (görsel + çip)').toEqual([])
      await editor.locator('[data-slot="te-images"]').first().screenshot({ path: `test-results/te-cap-section-${vp.name}.png` })
      // "Görsel paneli" ilk görsel bölümünün başlık satırında (bölüm kapalıyken de görünür): başlığın içinde ve kartın sınırında
      const head = await editor.locator('[data-slot="te-images"]').first().locator('[data-slot="card-header"]').boundingBox()
      const panelBtn = await editor.locator('[data-slot="te-images-panel-open"]').boundingBox()
      expect(panelBtn.y).toBeGreaterThanOrEqual(head.y - 1)
      expect(panelBtn.y + panelBtn.height).toBeLessThanOrEqual(head.y + head.height + 1)
      expect(panelBtn.x + panelBtn.width).toBeLessThanOrEqual(head.x + head.width + 1)
      await editor.locator('[data-slot="te-images"]').first().locator('[data-slot="card-header"]')
        .screenshot({ path: `test-results/te-cap-header-${vp.name}.png` })
      // Durdur: akışın izleri kapanır, çip kalkar
      await editor.locator('[data-slot="te-cap-stop"]').first().click()
      await expect(editor.locator('[data-slot="te-cap-chip"]')).toHaveCount(0)
      expect(await page.evaluate(() => window.__capStopped)).toBe(true)

      // 3) Görsel paneli: ayrı pencere, yalnız görsel yuvaları (kenar çubuğu yok); panelde yükleme düzenleyiciye yansır
      const [panel] = await Promise.all([
        context.waitForEvent('page'),
        editor.locator('[data-slot="te-images-panel-open"]').click(),
      ])
      await panel.setViewportSize({ width: 520, height: 900 })
      await expect(panel.locator('[data-slot="te-panel"][data-state="ready"]')).toBeVisible({ timeout: 20_000 })
      expect(new URL(panel.url()).searchParams.get('te_view')).toBe('images')
      await expect(panel.locator('[data-slot="te-image-slot"]')).toHaveCount(3)
      await expect(panel.locator('[data-slot="sidebar"]')).toHaveCount(0)
      await expect(panel.locator('[data-slot-id="monthly-tx"]')).toHaveAttribute('data-has-image', 'true')
      await expect(panel.locator('[data-slot="te-image-snip"]').first()).toBeVisible()
      await expect(panel).toHaveTitle(/Görsel paneli|Image panel/)
      await panel.locator('[data-slot-id="daily-errors"] input[type="file"]').setInputFiles({ name: 'gunluk.png', mimeType: 'image/png',
        buffer: stripedPng(400, 200) })
      await expect.poll(() => uploads.length, { timeout: 15_000 }).toBe(3)
      await expect(editor.locator('[data-slot-id="daily-errors"]')).toHaveAttribute('data-has-image', 'true', { timeout: 10_000 })
      await panel.waitForTimeout(300)
      expect(await panel.evaluate(overflowX), 'yatay taşma (panel 520)').toBeLessThanOrEqual(1)
      expect(await panel.evaluate(clipped, '[data-slot="te-panel"]'), 'kırpılan metin (panel)').toEqual([])
      if (vp.touch) expect(await panel.evaluate(smallTargets, '[data-slot="te-panel"]'), 'küçük hedef (panel)').toEqual([])
      await panel.screenshot({ path: `test-results/te-images-panel-${vp.name}.png` })

      // 4) "Ana pencerede aç" (inceleme 2026-10-18): ana pencere başka görünümdeyken rapor UYGULAMA İÇİNDE açılır — sayfa
      // yeniden yüklenmez (pencere işareti korunur; eskiden location.assign tam yükleme yapıyordu)
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:apply-view', { detail: { tab: 'channelreports', params: {} } })))
      await expect.poll(() => new URL(page.url()).searchParams.get('te_view')).toBeNull()
      await page.evaluate(() => { window.__noReload = 'kanal-raporlari' })
      // Ana pencerede açık pencere varken rapor arkada açılmaz: panel ne yapılacağını söyler, ana pencerenin adresi aynı
      await page.locator('[data-slot="te-new-channel"]').click()
      await expect(page.locator('[data-slot="te-channel-dialog"]')).toBeVisible()
      await panel.locator('[data-slot="te-panel-main"]').click()
      await expect(panel.getByText(/Rapor ana pencerede açılmadı|The report wasn't opened in the main window/)).toBeVisible({ timeout: 10_000 })
      expect(new URL(page.url()).searchParams.get('te_view')).toBeNull()
      expect(await page.evaluate(() => window.__noReload)).toBe('kanal-raporlari')
      await page.keyboard.press('Escape')
      await expect(page.locator('[data-slot="te-channel-dialog"]')).toHaveCount(0)
      await panel.locator('[data-slot="te-panel-main"]').click()
      try {
        await expect.poll(() => new URL(page.url()).searchParams.get('te_rep'), { timeout: 10_000 }).toBe('100')
      } catch (e) {
        // Tanı (2026-11-16 CI'da bir kez): ana pencere isteği reddetti mi (açık pencere / kaydedilmemiş iş), panel ne dedi
        const main = await page.evaluate(() => ({
          url: location.search,
          dialogs: [...document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"]')]
            .filter((d) => !d.closest('[data-radix-popper-content-wrapper]'))
            .map((d) => `${d.getAttribute('data-slot') || d.getAttribute('aria-label') || d.tagName}:${d.getAttribute('data-state')}`),
          active: document.activeElement?.outerHTML.slice(0, 120),
          toasts: [...document.querySelectorAll('[data-sonner-toast]')].map((x) => x.textContent.slice(0, 120)),
        })).catch((err) => ({ evaluateFailed: String(err?.message).slice(0, 120) }))
        const panelToasts = await panel.evaluate(() => [...document.querySelectorAll('[data-sonner-toast]')]
          .map((x) => x.textContent.slice(0, 120))).catch(() => null)
        throw new Error([e.message, `ANA: ${JSON.stringify(main)}`, `PANEL: ${JSON.stringify(panelToasts)}`].join('\n'))
      }
      expect(new URL(page.url()).searchParams.get('te_view')).toBe('report')
      expect(await page.evaluate(() => window.__noReload), 'sayfa yeniden yüklenmedi').toBe('kanal-raporlari')
      await expect(editor.locator('[data-slot-id="daily-errors"]')).toHaveAttribute('data-has-image', 'true', { timeout: 10_000 })
      await panel.close()
    })

    // Canlı görüntü + kaydırma (2026-11-19, kullanıcı isteği: "paylaşılan ekranda aşağı yukarı gidebilmeliyim, istediğim
    // noktayı seçip alabilmeliyim"): sekme paylaşımında kırpma penceresi CANLI açılır; izin kullanıcının tıklamasıyla
    // sorulur; tekerlek paylaşılan sekmeyi kaydırır; seçim başlayınca görüntü donar ve yüklenen görsel DONAN karedir.
    test('ekrandan al — canlı görüntü + kaydırma (sekme paylaşımı): izin, tekerlek, dondur, canlıya dön; yüklenen = donan kare; sığar', async ({ page, context }) => {
      test.skip(vp.width < 768, 'telefonda ekrandan alma yok (yukarıdaki testte doğrulanır)')
      test.setTimeout(150_000)
      await mockLiveTabCapture(context)
      await mock(page, { target: context })
      persistUploads = true
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      const slot = editor.locator('[data-slot-id="tech-errors"]')
      await expect(slot).toBeVisible({ timeout: 20_000 })
      await slot.locator('[data-slot="te-image-snip"]').click()
      const dlg = page.locator('[data-slot="te-cap-dialog"]')
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      const stage = dlg.locator('[data-slot="te-cap-stage"]')
      const bar = dlg.locator('[data-slot="te-cap-live-bar"]')
      const status = dlg.locator('[data-slot="te-cap-live-status"]')
      const badge = dlg.locator('[data-slot="te-cap-live-badge"]')
      const wheelCalls = () => page.evaluate(() => window.__capWheelCalls.slice())
      // Penceredeki tuvalin (karenin kendisi) bir pikseli
      const pixel = (x, y) => page.evaluate(([px, py]) => {
        const c = document.querySelector('[data-slot="te-cap-frame"]')
        const d = c.getContext('2d').getImageData(px, py, 1, 1).data
        return [d[0], d[1], d[2]]
      }, [x, y])
      const near = (got, want) => got.every((v, k) => Math.abs(v - want[k]) <= 14)
      // Şerit `top` kadar kaymışken karenin (x, y) pikselinin beklenen rengi
      const colorAt = (x, y, top) => CAP_COLORS[(Math.floor(x / CAP_BW) + Math.floor((y + top) / CAP_BH) * 3) % CAP_COLORS.length]
      const fits = async (what) => {
        await page.waitForTimeout(250)
        const db = await dlg.boundingBox()
        expect(db.x, `pencere solda taşmaz (${what})`).toBeGreaterThanOrEqual(-1)
        expect(db.y + db.height, `pencere altta taşmaz (${what})`).toBeLessThanOrEqual(vp.height + 1)
        expect(db.x + db.width, `pencere sağda taşmaz (${what})`).toBeLessThanOrEqual(vp.width + 1)
        expect(await page.evaluate(overflowX), `yatay taşma (${what})`).toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-cap-dialog"]'), `kırpılan metin (${what})`).toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-cap-dialog"]'), `küçük hedef (${what})`).toEqual([])
        // Sahne görünür kalır (çubuk onu pencereden itmez)
        const box = await stage.boundingBox()
        expect(box.height, `sahne görünür (${what})`).toBeGreaterThan(120)
        expect(box.y + box.height, `sahne pencerenin içinde (${what})`).toBeLessThanOrEqual(db.y + db.height + 1)
      }

      // 1) Canlı açılır. İzin henüz sorulmadı: tarayıcıya çağrı YAPILMAZ, ne isteneceği yazar, düğme önerilir
      await expect(stage).toHaveAttribute('data-live', 'true')
      await expect(badge).toContainText(/Canlı|Live/)
      await expect(bar).toHaveAttribute('data-scroll', 'ask')
      await expect(status).toContainText(/bir kez izin ister|asks once for permission/)
      expect((await wheelCalls()).filter((c) => c === 'el')).toHaveLength(0)
      await expect(dlg.locator('[data-slot="te-cap-retake"]')).toHaveCount(0)
      await expect(dlg.locator('[data-slot="alert"]')).toHaveCount(0)
      await fits('izin sorulacak')
      await page.screenshot({ path: `test-results/te-cap-live-ask-${vp.name}.png` })
      // Canlı çizim gerçek video hattından: paylaşılan sayfa değişince penceredeki tuval de değişir
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 0)), { timeout: 8000 }).toBe(true)
      await page.evaluate(() => window.__capScrollTo(150))
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 150)), { timeout: 8000 }).toBe(true)
      await page.evaluate(() => window.__capScrollTo(0))
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 0)), { timeout: 8000 }).toBe(true)

      // 2) "Kaydırmayı etkinleştir": tarayıcının izin sorusu (yanıt gelene dek bekler); kapatılırsa yeniden denenebilir
      const enable = dlg.locator('[data-slot="te-cap-scroll-enable"]')
      await enable.click()
      await expect(bar).toHaveAttribute('data-scroll', 'asking')
      await expect(status).toContainText(/İzin ver|Allow/)
      await expect(enable).toHaveCount(0)
      expect(await page.evaluate(() => window.__capAnswer('dismiss'))).toBe(true)
      await expect(bar).toHaveAttribute('data-scroll', 'failed')
      await fits('izin sorusu kapatıldı')
      await enable.click()
      await expect(bar).toHaveAttribute('data-scroll', 'asking')
      expect(await page.evaluate(() => window.__capAnswer('granted'))).toBe(true)
      await expect(bar).toHaveAttribute('data-scroll', 'on')
      await expect(status).toContainText(/Fare tekerleği ya da dokunmatik yüzey .* paylaşılan sekmeyi buradan kaydırır|The mouse wheel or the touchpad .* scrolls the shared tab from here/)
      await fits('kaydırma açık')

      // 3) Tekerlek sahnenin üstünde: paylaşılan sekme kayar (pencerenin kendisi kaymaz), görüntü canlı güncellenir
      const sb = await stage.boundingBox()
      const pageY = await page.evaluate(() => window.scrollY)
      await page.mouse.move(sb.x + sb.width / 2, sb.y + sb.height / 2)
      await page.mouse.wheel(0, 150)
      await expect.poll(() => page.evaluate(() => window.__capScrollY), { timeout: 5000 }).toBe(150)
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 150)), { timeout: 8000 }).toBe(true)
      expect(await page.evaluate(() => window.scrollY), 'arkadaki sayfa kaymaz').toBe(pageY)
      const sb2 = await stage.boundingBox()
      expect(Math.abs(sb2.y - sb.y), 'sahne yerinde').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/te-cap-live-on-${vp.name}.png` })

      // 3b) Faresiz kullanım (2026-11-20, kullanıcı isteği: "faresi olmayan da aşağı yukarı gidebilmeli"): dokunmatik yüzey
      //     küçük adımlı GERÇEK tekerlek olayı üretir — iletilir. Ok / sayfa tuşları paylaşılan sekmeyi buradan kaydıramaz
      //     (tarayıcı kısıtı; gerçek Chromium 159'da ölçüldü: betikle üretilen tekerlek olayı da iletilmez): görüntü DONMAZ,
      //     seçim oynamaz, arkadaki sayfa kaymaz; klavye ipucu baştan görünür ve tuşa basılınca vurgulanır.
      const keysHint = dlg.locator('[data-slot="te-cap-keys-hint"]')
      await expect(keysHint).toBeVisible()
      await expect(keysHint).toContainText('Ctrl+Tab')
      await expect(keysHint).not.toHaveAttribute('data-flash', 'true')
      for (let i = 0; i < 5; i++) await page.mouse.wheel(0, 10)
      await expect.poll(() => page.evaluate(() => window.__capScrollY), { timeout: 5000 }).toBe(200)
      for (let i = 0; i < 5; i++) await page.mouse.wheel(0, -10)
      await expect.poll(() => page.evaluate(() => window.__capScrollY), { timeout: 5000 }).toBe(150)
      const fullRect = await stage.getAttribute('data-rect')
      await stage.focus()
      for (const key of ['ArrowDown', 'PageDown', 'ArrowUp', 'Space']) await page.keyboard.press(key)
      await expect(keysHint).toHaveAttribute('data-flash', 'true')
      await expect(dlg.locator('[data-slot="te-cap-keys-live"]'))
        .toContainText(/Ok tuşları paylaşılan sekmeyi buradan kaydıramaz|The arrow keys cannot scroll the shared tab from here/)
      await expect(stage).toHaveAttribute('data-live', 'true')
      await expect(badge).toContainText(/Canlı|Live/)
      expect(await stage.getAttribute('data-rect'), 'canlıyken oklar seçimi oynatmaz').toBe(fullRect)
      expect(await page.evaluate(() => window.__capScrollY), 'tuşlar paylaşılan sekmeyi kaydırmaz').toBe(150)
      expect(await page.evaluate(() => window.scrollY), 'tuşlar arkadaki sayfayı kaydırmaz').toBe(pageY)
      await fits('klavye ipucu vurgulu')
      await page.screenshot({ path: `test-results/te-cap-live-keys-${vp.name}.png` })
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 150)), { timeout: 8000 }).toBe(true)

      // 4) Sürükleyerek seçim: görüntü donar, tekerlek iletimi kesilir
      await page.mouse.move(sb.x + sb.width * 0.2, sb.y + sb.height * 0.3)
      await page.mouse.down()
      await page.mouse.move(sb.x + sb.width * 0.55, sb.y + sb.height * 0.5, { steps: 6 })
      await page.mouse.move(sb.x + sb.width * 0.7, sb.y + sb.height * 0.8, { steps: 6 })
      await page.mouse.up()
      await expect(badge).toContainText(/Donduruldu|Frozen/)
      await expect(stage).not.toHaveAttribute('data-live', 'true')
      await expect(dlg.locator('[data-slot="te-cap-pad"]')).toHaveCount(0)
      await expect.poll(async () => (await wheelCalls()).at(-1)).toBe('null')
      const [rx, ry, rw, rh] = (await stage.getAttribute('data-rect')).split(',').map(Number)
      expect(rw).toBeGreaterThan(CAP_W * 0.4)
      expect(rh).toBeGreaterThan(CAP_H * 0.4)
      // Donduktan sonra paylaşılan sayfa değişse de penceredeki kare DEĞİŞMEZ; tekerlek artık kaydırmaz
      await page.evaluate(() => window.__capScrollTo(600))
      await page.mouse.move(sb.x + sb.width / 2, sb.y + sb.height / 2)
      await page.mouse.wheel(0, 150)
      await page.waitForTimeout(700)
      expect(await page.evaluate(() => window.__capScrollY), 'donukken tekerlek iletilmez').toBe(600)
      expect(near(await pixel(100, 75), colorAt(100, 75, 150)), 'donan kare değişmez').toBe(true)
      await fits('donduruldu')
      await page.screenshot({ path: `test-results/te-cap-live-frozen-${vp.name}.png` })

      // 5) Yükle: PNG = DONAN karenin seçilen bölgesi (kaydırma 150 iken görülen pikseller), ölçek yok
      await dlg.locator('[data-slot="te-cap-confirm"]').click()
      await expect.poll(() => uploads.length, { timeout: 20_000 }).toBe(1)
      await expect(dlg).toHaveCount(0)
      const img = decodePng(uploadBodies[0])
      expect(img, 'yüklenen PNG çözülür').not.toBeNull()
      expect([img.w, img.h]).toEqual([rw, rh])
      let checked = 0
      for (let cy = 75; cy < CAP_H; cy += CAP_BH) {
        for (let cx = 100; cx < CAP_W; cx += CAP_BW) {
          if (cx < rx + 4 || cy < ry + 4 || cx >= rx + rw - 4 || cy >= ry + rh - 4) continue
          const got = img.px(cx - rx, cy - ry)
          const want = colorAt(cx, cy, 150)
          expect(near(got, want), `piksel ${cx},${cy}: ${got} ≠ ${want} (donan kare, kaydırma 150)`).toBe(true)
          checked++
        }
      }
      expect(checked, 'denetlenen blok').toBeGreaterThanOrEqual(4)
      await expect(slot).toHaveAttribute('data-has-image', 'true')

      // 6) İkinci yuva: seçici yeniden açılmaz, izin zaten var → soru / düğme yok, kaydırma hemen açık
      await editor.locator('[data-slot-id="daily-errors"] [data-slot="te-image-snip"]').click()
      await expect(dlg).toBeVisible()
      expect(await page.evaluate(() => window.__capCalls)).toBe(1)
      await expect(stage).toHaveAttribute('data-live', 'true')
      await expect(bar).toHaveAttribute('data-scroll', 'on')
      await expect(enable).toHaveCount(0)
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 600)), { timeout: 8000 }).toBe(true)
      // "Dondur" → "Canlıya dön": yeniden canlı, tekerlek yeniden kaydırır
      await dlg.locator('[data-slot="te-cap-freeze"]').click()
      await expect(badge).toContainText(/Donduruldu|Frozen/)
      await dlg.locator('[data-slot="te-cap-resume"]').click()
      await expect(stage).toHaveAttribute('data-live', 'true')
      await expect(bar).toHaveAttribute('data-scroll', 'on')
      const sb3 = await stage.boundingBox()
      await page.mouse.move(sb3.x + sb3.width / 2, sb3.y + sb3.height / 2)
      await page.mouse.wheel(0, 150)
      await expect.poll(() => page.evaluate(() => window.__capScrollY), { timeout: 5000 }).toBe(750)
      await expect.poll(async () => near(await pixel(100, 75), colorAt(100, 75, 750)), { timeout: 8000 }).toBe(true)
      await dlg.locator('[data-slot="te-cap-cancel"]').click()
      await expect(dlg).toHaveCount(0)
      await expect.poll(async () => (await wheelCalls()).at(-1)).toBe('null')
      expect(uploads.length, 'Vazgeç yüklemez').toBe(1)
      // Durdur: akış kapanır
      await editor.locator('[data-slot="te-cap-stop"]').first().click()
      await expect(editor.locator('[data-slot="te-cap-chip"]')).toHaveCount(0)
      expect(await page.evaluate(() => window.__capStopped)).toBe(true)

      // 7) Görsel paneli (520 px'lik açılır pencere): kendi paylaşım oturumu, izin yeniden "sorulacak". Canlı çubuk, izin
      //    düğmesi ve sahne dar pencereye de sığar (inceleme 2026-11-19)
      const [panel] = await Promise.all([
        context.waitForEvent('page'),
        editor.locator('[data-slot="te-images-panel-open"]').click(),
      ])
      await panel.setViewportSize({ width: 520, height: 900 })
      await expect(panel.locator('[data-slot="te-panel"][data-state="ready"]')).toBeVisible({ timeout: 20_000 })
      await panel.locator('[data-slot-id="daily-errors"] [data-slot="te-image-snip"]').click()
      const pdlg = panel.locator('[data-slot="te-cap-dialog"]')
      await expect(pdlg).toBeVisible({ timeout: 15_000 })
      const pstage = pdlg.locator('[data-slot="te-cap-stage"]')
      const pbar = pdlg.locator('[data-slot="te-cap-live-bar"]')
      const panelFits = async (what) => {
        await panel.waitForTimeout(250)
        const db = await pdlg.boundingBox()
        expect(db.x, `panel penceresi solda taşmaz (${what})`).toBeGreaterThanOrEqual(-1)
        expect(db.x + db.width, `panel penceresi sağda taşmaz (${what})`).toBeLessThanOrEqual(520 + 1)
        expect(db.y + db.height, `panel penceresi altta taşmaz (${what})`).toBeLessThanOrEqual(900 + 1)
        expect(await panel.evaluate(overflowX), `yatay taşma (panel, ${what})`).toBeLessThanOrEqual(1)
        expect(await panel.evaluate(clipped, '[data-slot="te-cap-dialog"]'), `kırpılan metin (panel, ${what})`).toEqual([])
        if (vp.touch) expect(await panel.evaluate(smallTargets, '[data-slot="te-cap-dialog"]'), `küçük hedef (panel, ${what})`).toEqual([])
        const box = await pstage.boundingBox()
        expect(box.height, `sahne görünür (panel, ${what})`).toBeGreaterThan(120)
        expect(box.y + box.height, `sahne pencerenin içinde (panel, ${what})`).toBeLessThanOrEqual(db.y + db.height + 1)
        // Çubuktaki düğmeler pencerenin içinde kalır
        for (const b of await pbar.locator('button').all()) {
          const bb = await b.boundingBox()
          expect(bb.x, `çubuk düğmesi solda (${what})`).toBeGreaterThanOrEqual(db.x - 1)
          expect(bb.x + bb.width, `çubuk düğmesi sağda (${what})`).toBeLessThanOrEqual(db.x + db.width + 1)
        }
      }
      await expect(pstage).toHaveAttribute('data-live', 'true')
      await expect(pbar).toHaveAttribute('data-scroll', 'ask')
      // İzin sorulmadan tarayıcıya hiçbir çağrı gitmez ("durdur" dahil)
      expect(await panel.evaluate(() => window.__capWheelCalls.slice())).toEqual([])
      await panelFits('izin sorulacak')
      await panel.screenshot({ path: `test-results/te-cap-live-panel-ask-${vp.name}.png` })
      await pdlg.locator('[data-slot="te-cap-scroll-enable"]').click()
      await expect(pbar).toHaveAttribute('data-scroll', 'asking')
      expect(await panel.evaluate(() => window.__capAnswer('granted'))).toBe(true)
      await expect(pbar).toHaveAttribute('data-scroll', 'on')
      await panelFits('kaydırma açık')
      const pb = await pstage.boundingBox()
      await panel.mouse.move(pb.x + pb.width / 2, pb.y + pb.height / 2)
      await panel.mouse.wheel(0, 150)
      await expect.poll(() => panel.evaluate(() => window.__capScrollY), { timeout: 5000 }).toBe(150)
      await pdlg.locator('[data-slot="te-cap-freeze"]').click()
      await expect(pdlg.locator('[data-slot="te-cap-live-badge"]')).toContainText(/Donduruldu|Frozen/)
      await panelFits('donduruldu')
      await panel.screenshot({ path: `test-results/te-cap-live-panel-frozen-${vp.name}.png` })
      await pdlg.locator('[data-slot="te-cap-cancel"]').click()
      await expect(pdlg).toHaveCount(0)
      await panel.close()
    })

    test('takım şablon kütüphanesi (D17) + raporun şablon sürümü (D19): yönetim penceresi, yeni kanal kaynağı, kütüphaneye kaydet, varsayılana dön; sığar, kesilmez', async ({ page }) => {
      test.setTimeout(180_000)
      await mock(page)
      libraryEntries = [libEntry(501, LONG_LIB, { is_default: true }), libEntry(502, 'POS kanalları şablonu')]

      // D19 — sürüm penceresi: "bu sürümle N rapor", sürümü bilinmeyen raporlar, ilk sürümün kaynağı, dönem listesi
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      await page.locator('[data-slot="te-versions-open"]').click({ timeout: 20_000 })
      const vdlg = page.locator('[data-slot="te-versions"]')
      await expect(vdlg.locator('[data-slot="te-version-report-count"]')).toHaveCount(2)
      await expect(vdlg.locator('[data-slot="te-version-report-count"]').first()).toHaveText(/Bu sürümle 2 rapor|Reports with this version: 2/)
      await expect(vdlg.locator('[data-slot="te-version-report-count"]').nth(1)).toHaveText(/Bu sürümle bilinen rapor yok|No known reports with this version/)   // bilinmeyen 1 rapor var → "bilinen"
      await expect(vdlg.locator('[data-slot="te-versions-unknown"]')).toBeVisible()
      if (vp.width >= 768) await expect(vdlg.locator('[data-slot="te-version-report"]')).toHaveCount(2)
      await page.waitForTimeout(250)
      expect(await page.evaluate(clipped, '[data-slot="te-versions"]'), 'kırpılan metin (sürümler)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-versions"]'), 'küçük hedef (sürümler)').toEqual([])
      await page.keyboard.press('Escape')
      await expect(vdlg).toHaveCount(0)

      // D19 — rapor başlığında şablon sürümü çipi ("v0" / "vnull" yok)
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      await expect(page.locator('[data-slot="te-report-tpl-version"]')).toHaveText(/Şablon v3|Template v3/, { timeout: 20_000 })
      expect(await page.locator('[data-slot="te-report"]').innerText()).not.toMatch(/v0\b|vnull|vundefined/)

      // D17 — kütüphane yönetim penceresi (kanal listesinden)
      await page.goto('/?tab=channelreports')
      await page.locator('[data-slot="te-library-open"]').click({ timeout: 20_000 })
      const mgr = page.locator('[data-slot="te-lib-manager"]')
      await expect(mgr.locator('[data-slot="te-lib-entry"]')).toHaveCount(2)
      await expect(mgr.locator('[data-slot="te-lib-list"]')).toHaveAttribute('data-layout', vp.width < 700 ? 'cards' : 'table')
      await expect(mgr.locator('[data-slot="te-lib-entry"]').first()).toContainText(LONG_LIB)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kütüphane)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-lib-manager"]'), 'kırpılan metin (kütüphane)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-lib-manager"]'), 'küçük hedef (kütüphane)').toEqual([])
      const mbox = await mgr.boundingBox()
      expect(mbox.x, 'pencere solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(mbox.x + mbox.width, 'pencere sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      await page.screenshot({ path: `test-results/channel-reports-library-manager-${vp.name}.png`, fullPage: false })
      // "Takım varsayılanı yap" → yalnız default + okunan sürüm gider
      await mgr.locator('[data-slot="te-lib-entry"][data-entry="502"]').getByRole('button', { name: /(İşlemler|Actions)/ }).click()
      await page.getByRole('menuitem', { name: /(Takım varsayılanı yap|Make team default): POS kanalları şablonu/ }).click()
      await expect.poll(() => libraryWrites.length).toBe(1)
      expect(libraryWrites[0]).toEqual({ method: 'PUT', id: 502, body: { default: true, version: 1 } })
      await expect(mgr.locator('[data-slot="te-lib-entry"][data-default="true"]')).toContainText('POS kanalları şablonu')
      // İnceleme 2026-10-18: liste yeniden okunurken yerinde kalır; odak satırın menü tetiğine döner (gövdeye düşmez)
      await expect(mgr.locator('[data-slot="te-lib-refreshing"]')).toHaveAttribute('data-state', 'off')
      await expect(mgr.locator('[data-slot="te-lib-entry"][data-entry="502"] [data-kebab]')).toBeFocused()
      // Silme onayı kaydın adını söyler
      await mgr.locator('[data-slot="te-lib-entry"][data-entry="501"]').getByRole('button', { name: /(İşlemler|Actions)/ }).click()
      await page.getByRole('menuitem', { name: /^(Sil|Delete): / }).click()
      const confirm = page.getByRole('dialog', { name: /Kütüphane şablonu silinsin mi|Delete the library template/ })
      await expect(confirm).toContainText(LONG_LIB)
      await confirm.getByRole('button', { name: /^(Sil|Delete)$/ }).click()
      await expect.poll(() => libraryWrites.length).toBe(2)
      expect(libraryWrites[1]).toEqual({ method: 'DELETE', id: 501, body: null })
      await expect(mgr.locator('[data-slot="te-lib-entry"]')).toHaveCount(1)
      // Silinen satırın yerine kalan satırın menü tetiği odakta
      await expect(mgr.locator('[data-slot="te-lib-entry"][data-entry="502"] [data-kebab]')).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(mgr).toHaveCount(0)

      // D17 — yeni kanal: takımın varsayılanı (POS kanalları şablonu) ilk kaynak
      await page.locator('[data-slot="te-new-channel"]').click()
      const dlg = page.locator('[data-slot="te-channel-dialog"]')
      await expect(dlg.locator('[data-source="library"] [role="radio"]')).toHaveAttribute('data-state', 'checked')
      await expect(dlg.locator('[data-slot="te-source-library"]')).toContainText('POS kanalları şablonu')
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, '[data-slot="te-channel-dialog"]'), 'kırpılan metin (yeni kanal / kütüphane)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel-dialog"]'), 'küçük hedef (yeni kanal / kütüphane)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-library-new-channel-${vp.name}.png`, fullPage: false })
      await dlg.getByLabel(/Kanal adı|Channel name/).fill('ATM')
      await dlg.locator('[data-slot="te-channel-save"]').click()
      await expect.poll(() => channelCreates.length).toBe(1)
      expect(channelCreates[0]).toMatchObject({ team_id: 5, name: 'ATM', library_template_id: 502 })
      expect(channelCreates[0].lang).toBeUndefined()
      // Sunucu kaynağı onayladı → "uygulanmadı" uyarısı yok
      await expect(page.getByText(/uyguladığını doğrulamadı|didn't confirm that it applied/)).toHaveCount(0)

      // D17 — şablon düzenleyicisinden kütüphaneye kaydet (kanal açılınca şablonu açılır)
      await expect(page.locator('[data-slot="te-template-editor"]')).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-tpl-save-library"]').click()
      const save = page.locator('[data-slot="te-lib-save"]')
      await expect(save.locator('[data-slot="te-lib-count"]')).toBeVisible()
      await page.waitForTimeout(250)
      expect(await page.evaluate(clipped, '[data-slot="te-lib-save"]'), 'kırpılan metin (kütüphaneye kaydet)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-lib-save"]'), 'küçük hedef (kütüphaneye kaydet)').toEqual([])
      await page.screenshot({ path: `test-results/channel-reports-library-save-${vp.name}.png`, fullPage: false })
      await save.locator('[data-slot="te-lib-name"]').fill('Kart ve POS kanalları için uzun adlı ortak aylık teknik hata şablonu')
      await save.locator('[data-slot="te-lib-make-default"]').click()
      await save.locator('[data-slot="te-lib-save-submit"]').click()
      await expect.poll(() => libraryWrites.length).toBe(3)
      expect(libraryWrites[2].method).toBe('POST')
      expect(libraryWrites[2].body).toMatchObject({ name: 'Kart ve POS kanalları için uzun adlı ortak aylık teknik hata şablonu', default: true })
      expect(Array.isArray(libraryWrites[2].body.template.sections)).toBe(true)
      await expect(save).toHaveCount(0)

      // D17 — "Varsayılana dön": sistem varsayılanları + kütüphane (varsayılan önce); seçilen kayıt taslağa yüklenir
      await page.locator('[data-slot="te-tpl-reset"]').click()
      const menu = page.locator('[data-slot="te-tpl-reset-menu"]')
      await expect(menu.locator('[data-slot="te-tpl-reset-lib"]')).toHaveCount(2)
      await expect(menu.locator('[data-slot="te-tpl-reset-system"]')).toHaveCount(2)
      await page.waitForTimeout(250)
      expect(await page.evaluate(clipped, '[data-slot="te-tpl-reset-menu"]'), 'kırpılan metin (varsayılana dön)').toEqual([])
      if (vp.touch) {
        const short = await page.evaluate(() => [...document.querySelectorAll('[data-slot="te-tpl-reset-menu"] [role="menuitem"]')]
          .filter((n) => n.getAttribute('aria-disabled') !== 'true' && n.getBoundingClientRect().height < 39.5).map((n) => n.textContent.slice(0, 30)))
        expect(short, 'küçük hedef (varsayılana dön)').toEqual([])
      }
      const box = await menu.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      await page.screenshot({ path: `test-results/channel-reports-library-reset-${vp.name}.png`, fullPage: false })
      await menu.locator('[data-slot="te-tpl-reset-lib"]').filter({ hasText: 'POS kanalları şablonu' }).click()
      const rc = page.getByRole('dialog', { name: /Kütüphane şablonu yüklensin mi|Load the library template/ })
      await rc.getByRole('button', { name: /Şablonu yükle|Load the template/ }).click()
      await expect(page.getByLabel(/Report title|Rapor başlığı/)).toHaveValue(/^Kütüphane: POS kanalları şablonu/)
      expect(libraryWrites).toHaveLength(3)                         // taslağa yüklendi, hiçbir şey kaydedilmedi
      await page.screenshot({ path: `test-results/channel-reports-library-${vp.name}.png`, fullPage: false })
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
      await expect(years).toHaveCount(2, { timeout: 20_000 })      // gezinmeden sonraki ilk denetim: yükte 5 sn yetmeyebilir
      await expect(years.first()).toHaveAttribute('data-year', '2026')
      expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(0)

      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
      await page.locator('[data-slot="te-versions-open"]').click({ timeout: 20_000 })
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
      await expect(editor).toBeVisible({ timeout: 20_000 })      // soğuk açılış (ilk test, tembel parçalar) — diğer testler gibi
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
      await expect(editor).toBeVisible({ timeout: 20_000 })      // yeniden yüklemeden sonraki ilk denetim (yükte 5 sn yetmeyebilir)
      await expect(card).toHaveAttribute('data-state', 'pending', { timeout: 20_000 })
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

    test("Excel'e aktar (C14, 2026-10-18): düğme rapor araç çubuğuna sığar; tek kanal + tüm durumlar + JSON ister; gerçek indirme beklenen adla (.xlsx); yıl süzgeci yılı taşır", async ({ page }) => {
      await mock(page)
      const COLS = ['team_id', 'team_name', 'channel_id', 'channel_name', 'year', 'month', 'report_id', 'report_status',
        'sent_at', 'changed_after_send', 'approval_state', 'field_id', 'field_label', 'field_type', 'value', 'exempt', 'computed']
      const r = (y, m, id, field, label, type, value) => [5, LONG_TEAM, 10, LONG_CHANNEL, y, m, id, 'sent', '2026-10-02T08:00:00Z',
        false, null, field, label, type, value, false, type === 'percent']
      const exportUrls = []
      // Genel /api taklidinden SONRA kaydedilir — Playwright son kaydı önce dener
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/export', async (route) => {
        exportUrls.push(route.request().url())
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
          columns: COLS, notes: [],
          rows: [r(2025, 12, 80, 'total-tx', 'Toplam işlem adedi', 'number', 1000), r(2026, 9, 100, 'total-tx', 'Toplam işlem adedi', 'number', 2000),
            r(2026, 9, 100, 'error-rate', 'Teknik hata oranı', 'percent', 1.475)],
          reports: [{ channel_id: 10, year: 2025, month: 12, report_id: 80, report_status: 'sent', sent_at: '2026-01-05T08:00:00Z' },
            { channel_id: 10, year: 2026, month: 9, report_id: 100, report_status: 'draft', sent_at: null }] } }) })
      })
      await page.goto('/?tab=channelreports&te_view=channel&te_ch=10')
      const btn = page.locator('[data-slot="te-channel-xlsx"]')
      await expect(btn).toBeVisible({ timeout: 20_000 })
      await page.waitForTimeout(200)
      const box = await btn.boundingBox()
      expect(box.x, 'düğme ekranın solunda taşmaz').toBeGreaterThanOrEqual(0)
      expect(box.x + box.width, 'düğme ekrana sığar').toBeLessThanOrEqual(vp.width)
      if (vp.touch) expect(box.height, 'dokunma hedefi').toBeGreaterThanOrEqual(39.5)
      expect(await page.evaluate(overflowX), 'yatay taşma (rapor araç çubuğu)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report-toolbar"]'), 'kırpılan metin (araç çubuğu)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-report-toolbar"]'), 'küçük hedef (araç çubuğu)').toEqual([])

      const SLUG = 'bireysel-internet-subesi-ve-mobil-bankacilik-ortak-kanali'
      const [download] = await Promise.all([page.waitForEvent('download'), btn.click()])
      expect(download.suggestedFilename()).toBe(`${SLUG}-2025-2026-degerler.xlsx`)
      const chunks = []
      for await (const c of await download.createReadStream()) chunks.push(c)
      const file = Buffer.concat(chunks)
      expect(file.subarray(0, 2).toString('latin1'), 'gerçek .xlsx (ZIP)').toBe('PK')
      const first = new URL(exportUrls[0]).searchParams
      expect(Object.fromEntries(first)).toEqual({ team: '5', channel: '10', status: 'all', format: 'json' })

      await page.locator('[data-slot="te-ryear"]').selectOption('2025')
      const [second] = await Promise.all([page.waitForEvent('download'), btn.click()])
      expect(second.suggestedFilename()).toBe(`${SLUG}-2025-degerler.xlsx`)
      expect(new URL(exportUrls[1]).searchParams.get('year')).toBe('2025')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (indirme sonrası)').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/channel-reports-xlsx-${vp.name}.png`, fullPage: false })
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

    test('e-posta önizlemesi (inceleme 2026-10-18): 20 Kime + 20 Bilgi + üç şerit — gövde kayar, başlık ve alt düğmeler pencerede görünür kalır', async ({ page }) => {
      await mock(page)
      const many = (who) => Array.from({ length: 20 }, (_, i) => `${who}.${i}.cok-uzun-bir-adres@dijital-kanallar-uygulama-gelistirme.example.com`)
      // Genel /api taklidinden SONRA kaydedilir — Playwright son kaydı önce dener
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/reports/100/mail-preview', (route) => route.fulfill({
        status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
          subject: `${LONG_CHANNEL} Kanalı Teknik Hata Mesajları - Eylül 2026`, delivery: 'EXECUTIVE',
          to: many('yonetici'), cc: many('takim'), to_count: 20, cc_count: 20, to_dropped: 3, cc_dropped: 4,
          recipients_hidden: false, problem: 'EXEC_RECIPIENTS_MISSING', pdf_too_large: false,
          file_name: 'mobil-teknik-hata-2026-09.pdf', images_embedded: 1, images_skipped: 0, pdf_bytes: 123456, max_mail_mb: 15,
          provenance: { created_by: 'Ayşe Yılmaz', created_at: '2026-09-03T11:05:00Z', updated_by: 'Ali Veli', updated_at: '2026-09-05T06:12:00Z',
            approval_state: 'pending', approved_by: null, approved_at: null },
          html: '<!doctype html><html><body style="margin:0;font-family:Arial"><div data-slot="te-mail-band" style="background:#0f172a;color:#fff;padding:24px">TEKNİK HATA RAPORU</div></body></html>',
          text: 'metin' } }),
      }))
      await page.goto('/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
      await expect(page.locator('[data-slot="te-report"]')).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-mail-preview-btn"]').click()
      const dlg = page.locator('[data-slot="te-mail-preview"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="te-mail-to"]')).toContainText('yonetici.19.')
      await expect(dlg.locator('[data-slot="te-mail-dropped"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="te-mail-cc-dropped"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="te-mail-problem"]')).toBeVisible()
      await page.waitForTimeout(300)
      const box = await dlg.boundingBox()
      expect(box.y, 'pencere üstte taşmaz').toBeGreaterThanOrEqual(-1)
      expect(box.y + box.height, 'pencere altta taşmaz').toBeLessThanOrEqual(vp.height + 1)
      // Alt çubuk (Kapat + Bana test gönder) pencerenin İÇİNDE ve ekranda — kaydırmadan erişilir
      for (const sel of ['[data-slot="te-mail-test"]']) {
        const b = await dlg.locator(sel).boundingBox()
        expect(b, `${sel} çizildi`).not.toBeNull()
        expect(b.y + b.height, `${sel} pencerenin içinde`).toBeLessThanOrEqual(box.y + box.height + 1)
        expect(b.y + b.height, `${sel} ekranda`).toBeLessThanOrEqual(vp.height + 1)
      }
      const close = dlg.locator('[data-slot="dialog-footer"]').getByRole('button', { name: /^(Kapat|Close)$/ })
      await expect(close).toBeInViewport()
      const cb = await close.boundingBox()
      expect(cb.y + cb.height, 'Kapat pencerenin içinde').toBeLessThanOrEqual(box.y + box.height + 1)
      await expect(dlg.getByRole('heading', { name: /E-posta önizlemesi|E-mail preview/ })).toBeInViewport()
      // Gövde kayar: sona kaydırılınca önizleme çerçevesi görünür, alt çubuk yerinde
      const body = dlg.locator('[data-slot="te-mail-body"]')
      if (vp.width < 768) {
        expect(await body.evaluate((el) => el.scrollHeight > el.clientHeight + 1), 'telefonda gövde kaydırılabilir').toBe(true)
      }
      await body.evaluate((el) => { el.scrollTop = el.scrollHeight })
      await expect(dlg.locator('[data-slot="te-mail-frame"]')).toBeInViewport()
      await expect(close).toBeInViewport()
      expect(await page.evaluate(clipped, '[data-slot="te-mail-preview"]'), 'kırpılan metin (önizleme, çok alıcı)').toEqual([])
      expect(await page.evaluate(overflowX), 'yatay taşma (önizleme, çok alıcı)').toBeLessThanOrEqual(1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-mail-preview"]'), 'küçük hedef (önizleme, çok alıcı)').toEqual([])
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

    test('çok pod R2a (MP-41, inceleme REP-2): önceki gönderim posta kuyruğunda (409 SEND_QUEUED) — pencere AÇIK, "sonuç burada görünecek" bandı, ek Bilgi korunur, "Gönder" KİLİTLİ; kuyruk teslim edilince pencere kendiliğinden kapanır, ikinci gönderim YOK; sığar, kesilmez, dokunma hedefleri', async ({ page }) => {
      // Yeniden temel (2026-10-23, inceleme REP-2): eskiden bant "yeniden deneyebilirsiniz" deyip "Gönder"i açık bırakıyordu ve
      // ikinci tıklama gidiyordu — kuyruk teslim edilince sunucu onu geçirir, ekip / yöneticiler ikinci kopyayı alırdı
      await mock(page)
      const queuedRow = { status: 'queued', send_state: 'QUEUED', last_attempt_at: '2026-10-18T07:00:05Z', send_count: 0 }
      reportOverride = { ...REPORT, report: { ...REPORT.report, ...queuedRow } }
      await page.goto('/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
      await expect(page.locator('[data-slot="te-report"]')).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-send"]').click()
      const dlg = page.locator('[data-slot="te-send-dialog"]')
      await expect(dlg).toBeVisible()
      const cc = dlg.locator('[data-slot="te-send-extra-cc"]')
      await cc.fill('kuyruk@example.com')
      await dlg.getByRole('button', { name: /^(Gönder|Send)$/ }).click()
      const failure = dlg.locator('[data-slot="te-send-failure"]')
      await expect(failure).toBeVisible()
      await expect(failure).toHaveAttribute('data-kind', 'queued')
      await expect(failure).toContainText(/Önceki gönderim hâlâ posta kuyruğunda|The previous send is still in the mail queue/)
      await expect(failure).toContainText(/sonuç birkaç dakika içinde burada görünür|the result will appear here/)
      await expect(failure).not.toContainText(/yeniden “Gönder”e basın|press “Send” again/)
      await expect(cc).toHaveValue('kuyruk@example.com')
      await expect(cc).not.toHaveAttribute('aria-invalid', 'true')
      const send = dlg.getByRole('button', { name: /^(Gönder|Send)$/ })
      await expect(send).toBeDisabled()
      await expect(dlg.locator('[data-slot="te-send-confirm"][data-locked="true"]')).toHaveCount(1)
      expect(sends, 'hiçbir şey gönderilmedi').toBe(0)
      await page.waitForTimeout(200)
      const box = await dlg.boundingBox()
      expect(box.x + box.width, 'pencere ekrana sığar').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-send-dialog"]'), 'kırpılan metin (kuyruk bandı)').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="te-send-dialog"]'), 'küçük hedef (kuyruk bandı)').toEqual([])
      }
      expect(await page.evaluate(overflowX), 'yatay taşma yok').toBeLessThanOrEqual(0)
      await page.screenshot({ path: `test-results/channel-reports-send-queued-${vp.name}.png`, fullPage: false })
      // Kuyruk teslim edildi (aynı deneme damgası, SENT): yoklama (10 sn) okur — pencere kapanır, "gönderildi" bildirimi
      reportOverride = { ...REPORT, report: { ...REPORT.report, ...queuedRow, status: 'sent', send_state: 'SENT',
        last_sent_at: '2026-10-18T07:03:00Z', send_count: 1 } }
      await expect(dlg).toHaveCount(0, { timeout: 30_000 })
      await expect(page.getByText(/Gönderim tamamlandı: rapor gönderildi|The send has completed: the report was sent/)).toBeVisible()
      expect(sends, 'ikinci gönderim yok').toBe(0)
      expect(sendBodies).toHaveLength(1)
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
      // Kolona sığan adresler kırpılmadan sarılır, sığmayanlar sayıyla ("+N adres daha" — "tam yazıldı" denmez); tablo kabı kaymaz
      expect(SENDLOG_FITTED_TO.items.length, 'gerçek sığdırma: liste kısalır').toBeLessThan(SENDLOG_LONG_TO.length)
      expect(SENDLOG_FITTED_TO.more).toBeGreaterThan(0)
      const first = log.locator('[data-slot="te-sendlog-row"]').first()
      const recipients = first.locator('[data-slot="te-sendlog-recipients"]')
      await expect(recipients).toContainText(SENDLOG_FITTED_TO.items[SENDLOG_FITTED_TO.items.length - 1])
      await expect(recipients).not.toContainText(SENDLOG_LONG_TO[SENDLOG_LONG_TO.length - 1])
      await expect(first.locator('[data-slot="te-sendlog-to-more"]')).toContainText(new RegExp(`\\+${SENDLOG_FITTED_TO.more} (adres daha|more address)`))
      await expect(first.locator('[data-slot="te-sendlog-cc"]')).toContainText('ek.bilgi.adresi@')
      // Sebep dilinde yazılır; ham posta sunucusu iletisi (550 / FAILED / QUEUED_RETRY) hiçbir yerde yok
      await expect(log.locator('[data-slot="te-sendlog-reason"][data-reason="SMTP_FAILED"]')).toBeVisible()
      await expect(log.locator('[data-slot="te-sendlog-reason"][data-reason="QUEUED"]')).toBeVisible()
      await expect(log).not.toContainText(/550|FAILED|QUEUED_RETRY|Kayıtlı durum|Recorded status/)
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

    test('analiz (2026-10-18, R22): kanallardan açılır; çizgi grafiği (boş ay boşluk, tek değer nokta), tablo / kart, alan + dönem seçimi; uzun adlar sarar; sığar, dokunma hedefleri', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=channelreports')
      const open = page.locator('[data-slot="te-analytics-open"]')
      await expect(open).toBeVisible({ timeout: 20_000 })
      await open.click()
      const view = page.locator('[data-slot="te-analytics"]')
      await expect(view).toBeVisible()
      await expect.poll(() => new URL(page.url()).searchParams.get('te_view')).toBe('analytics')
      await expect.poll(() => analyticsCalls.length).toBeGreaterThan(0)
      expect(analyticsCalls[0].get('months')).toBe('12')
      expect(analyticsCalls[0].get('fields')).toBeNull()

      const cards = view.locator('[data-slot="te-an-field"]')
      await expect(cards).toHaveCount(2)
      await expect(view.locator('[data-slot="te-an-inactive-cut"]')).toHaveCount(0)
      await expect(view.locator('[data-slot="te-an-truncated"]')).toHaveCount(0)
      const rate = view.locator('[data-slot="te-an-field"][data-field="error-rate"]')
      // Grafik gerçekten çizildi: kanal başına bir çizgi; yalnız tek değeri olan kanal NOKTA alır (boş ay boşluk, 0 değil)
      await expect(rate.locator('.recharts-line-curve').first()).toBeVisible()
      expect(await rate.locator('.recharts-line-curve').count(), 'çizgi sayısı').toBeGreaterThanOrEqual(2)
      await expect(rate.locator('[data-slot="te-an-dot"]')).toHaveCount(1)
      await expect(rate.locator('[data-slot="te-an-legend-item"]')).toHaveCount(4)
      await expect(rate.locator('[data-slot="te-an-legend"]')).toContainText(LONG_CHANNEL)
      // Pasif kanal (2026-10-18): aktiflerin ARDINDA, uzun adının yanında "Pasif" rozeti (anahtarda, tabloda / kartta)
      const inactiveLegend = rate.locator('[data-slot="te-an-legend-item"][data-inactive="true"]')
      await expect(inactiveLegend).toHaveCount(1)
      await expect(rate.locator('[data-slot="te-an-legend-item"]').last()).toHaveAttribute('data-inactive', 'true')
      await expect(inactiveLegend).toContainText(LONG_INACTIVE_CHANNEL)
      await expect(inactiveLegend.locator('[data-slot="te-an-inactive"]')).toBeVisible()
      await expect(inactiveLegend.locator('[data-slot="te-an-inactive"]')).toHaveText(/^(Pasif|Inactive)$/)
      // Kap genişliği: geniş ekranda ay × kanal tablosu, dar ekranda kanal kartları
      const wrap = rate.locator('[data-slot="te-an-table-wrap"]')
      await expect(wrap).toHaveAttribute('data-layout', vp.width >= 1280 ? 'table' : 'cards')
      let inactiveHolder
      if (vp.width >= 1280) {
        await expect(rate.locator('[data-slot="te-an-row"]')).toHaveCount(12)
        await expect(rate.locator('[data-slot="te-an-row"]').first()).toHaveAttribute('data-month', '2026-9')
        inactiveHolder = rate.locator('[data-slot="te-an-table"] th[data-inactive="true"]')
        await expect(rate.locator('[data-slot="te-an-table"] th[data-channel]').last()).toHaveAttribute('data-channel', '40')
      } else {
        await expect(rate.locator('[data-slot="te-an-card"]')).toHaveCount(4)
        await expect(rate.locator('[data-slot="te-an-card"]').first()).toContainText(LONG_CHANNEL)
        inactiveHolder = rate.locator('[data-slot="te-an-card"][data-inactive="true"]')
        await expect(rate.locator('[data-slot="te-an-card"]').last()).toHaveAttribute('data-inactive', 'true')
      }
      await expect(inactiveHolder).toHaveCount(1)
      await expect(inactiveHolder).toContainText(LONG_INACTIVE_CHANNEL)
      await expect(inactiveHolder.locator('[data-slot="te-an-inactive"]')).toBeVisible()
      // Pasif kanalın geçmiş ayı rapor bağlantısı; rozet kabının içinde kalır (taşmaz, kırpılmaz)
      await expect(rate.locator('[data-slot="te-an-cell"][data-report="956"]')).toHaveCount(1)
      for (const holder of [inactiveLegend, inactiveHolder]) {
        const box = await holder.boundingBox()
        const badge = await holder.locator('[data-slot="te-an-inactive"]').boundingBox()
        expect(badge.x, 'rozet solda taşmaz').toBeGreaterThanOrEqual(box.x - 1)
        expect(badge.x + badge.width, 'rozet sağda taşmaz').toBeLessThanOrEqual(box.x + box.width + 1)
        const el = holder.locator('[data-slot="te-an-inactive"]')
        expect(await el.evaluate((n) => n.scrollWidth <= n.clientWidth + 1), 'rozet metni kırpılmaz').toBe(true)
      }
      // Boş ay "—"; gönderilmemiş ay soluk (adında "gönderilmedi")
      const unsent = rate.locator('[data-slot="te-an-cell"][data-report="100"]')
      await expect(unsent).toHaveAttribute('data-state', 'unsent')
      await expect(unsent).toHaveAccessibleName(/gönderilmedi|not sent/)
      await expect(rate.locator('[data-slot="te-an-cell"][data-state="none"]').first()).toContainText('—')

      // Y ekseni VERİYE göre (inceleme 2026-10-18): oranlar %0,4–%0,9 — eksen 0'dan başlamaz
      const domain = (await rate.locator('[data-slot="te-an-chart"]').getAttribute('data-y-domain')).split(',').map(Number)
      expect(domain[0], 'Y ekseni alt sınırı').toBeGreaterThan(0)
      expect(domain[1]).toBeGreaterThanOrEqual(0.9)

      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-analytics"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="te-analytics"]'), 'küçük hedef').toEqual([])
        // Hücre bağlantıları ("—" tek karakter de) GENİŞLİKTE de en az 40 px (inceleme 2026-10-18)
        expect(await page.evaluate(narrowCells, '[data-slot="te-analytics"]'), 'dar hücre bağlantısı').toEqual([])
      }

      // Dönem: 24 ay → yeni istek ve URL
      await view.getByRole('button', { name: /^(24 ay|24 months)$/ }).click()
      await expect.poll(() => analyticsCalls.at(-1)?.get('months')).toBe('24')
      await expect.poll(() => new URL(page.url()).searchParams.get('te_an_months')).toBe('24')
      await expect(rate.locator('[data-slot="te-an-dot"]')).toHaveCount(1)
      // Sığmayan pasif kanallar: ayrı, sayılı not; aylar kesilmedi → "yalnız son N ay" şeridi YOK (inceleme 2026-10-18)
      const inactiveCut = view.locator('[data-slot="te-an-inactive-cut"]')
      await expect(inactiveCut).toBeVisible()
      await expect(inactiveCut).toContainText(/(sonundaki 2 pasif kanal|left out \(2\))/)
      await expect(view.locator('[data-slot="te-an-truncated"]')).toHaveCount(0)

      // Alan seçici: SLA oranı eklenir → üçüncü kart, değer yoksa "değer girilmemiş" (grafik / 0 değil)
      await view.locator('[data-slot="te-an-field-picker"]').click()
      const list = page.locator('[data-slot="te-an-field-list"]')
      await expect(list).toBeVisible()
      await list.getByRole('option', { name: /Kritik fonksiyonların SLA oranı/ }).click()
      await expect.poll(() => analyticsCalls.at(-1)?.get('fields')).toBe('error-rate,total-tx,sla-rate')
      await page.keyboard.press('Escape')
      await expect(cards).toHaveCount(3)
      const sla = view.locator('[data-slot="te-an-field"][data-field="sla-rate"]')
      await expect(sla.locator('[data-slot="te-an-novalues"]')).toBeVisible()
      await expect(sla.locator('.recharts-line-curve')).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (üç alan, 24 ay)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-analytics"]'), 'kırpılan metin (üç alan)').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="te-analytics"]'), 'küçük hedef (üç alan)').toEqual([])
        expect(await page.evaluate(narrowCells, '[data-slot="te-analytics"]'), 'dar hücre bağlantısı (üç alan)').toEqual([])
      }
      // Araç çubuğu: Yenile (fresh=1, veri yerinde) ve süzgeç varsayılanda değilken "Varsayılana dön"
      await view.locator('[data-slot="te-an-refresh"]').click()
      await expect.poll(() => analyticsCalls.at(-1)?.get('fresh')).toBe('1')
      await expect(cards).toHaveCount(3)
      await expect(view.locator('[data-slot="te-an-reset"]')).toBeVisible()

      // Hücre o ayın raporunu açar; "Analize dön" analizi aynı süzgeçlerle açar (inceleme 2026-10-18)
      await rate.locator('[data-slot="te-an-cell"][data-report="100"]').click()
      await expect(page.locator('[data-slot="te-report"]')).toBeVisible()
      await expect.poll(() => new URL(page.url()).searchParams.get('te_rep')).toBe('100')
      expect(new URL(page.url()).searchParams.get('te_view')).toBe('report')
      expect(new URL(page.url()).searchParams.get('te_from')).toBe('analytics')
      const backBtn = page.locator('[data-slot="te-back"]')
      await expect(backBtn).toContainText(/Analize dön|Back to the analysis/)
      const callsBeforeBack = analyticsCalls.length
      await backBtn.click()
      await expect(view).toBeVisible()
      await expect.poll(() => new URL(page.url()).searchParams.get('te_view')).toBe('analytics')
      expect(new URL(page.url()).searchParams.get('te_an_months')).toBe('24')
      expect(new URL(page.url()).searchParams.get('te_from')).toBeNull()
      // Dönüşün ilk okuması taze (inceleme 2026-10-18, 2. tur): düzeltilen değer / silinen rapor bellekten eski gelmesin
      await expect.poll(() => analyticsCalls.length).toBeGreaterThan(callsBeforeBack)
      expect(analyticsCalls[callsBeforeBack].get('fresh')).toBe('1')
      await expect(cards).toHaveCount(3)
    })

    test.describe('kanal notları (2026-11-20)', () => {
      // Kanal notları (2026-11-20): "Notlar" sekmesi — boş durum → "Not yaz" → yazma alanına görsel yapıştır (tarayıcıda gerçek
      // hazırlık + yükleme) → önizle → kaydet → sayfayı yenile: kaydedilen not görseliyle gelir. Dış adresli görsel için <img>
      // çizilmez ve o sunucuya TEK istek gitmez. Her adımda taşma / kırpılma yok, dokunmatikte hedefler ≥ 40 px.
      test('kanal notları: Notlar sekmesi — yaz, görsel yapıştır, kaydet, yenile; dış görsel istenmez; sığar', async ({ page }) => {
        test.setTimeout(150_000)
        await mock(page)
        notesOn = true
        const remote = await trackRemote(page)
        await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=notes')
        const tab = page.locator('[data-slot="te-notes-tab"]')
        await expect(tab).toHaveAttribute('data-state', 'empty', { timeout: 20_000 })
        await expect(page.locator('[data-slot="te-tab-notes"]')).toHaveAttribute('data-state', 'active')
        await expect(tab.locator('[data-slot="te-note-guide"]')).toBeVisible()
        await page.waitForTimeout(200)
        expect(await page.evaluate(overflowX), 'yatay taşma (boş not)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-notes-tab"]'), 'kırpılan metin (boş not)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-notes-tab"]'), 'küçük hedef (boş not)').toEqual([])

        // 1) Düzenleyici: gizli bilgi uyarısı kalıcı; temizken Kaydet kapalı
        await tab.locator('[data-slot="te-note-write"]').click()
        const editor = page.locator('[data-slot="te-note-editor"]')
        await expect(editor).toBeVisible({ timeout: 20_000 })
        const ta = editor.locator('[data-slot="te-note-write-pane"] textarea')
        await expect(ta).toBeVisible({ timeout: 20_000 })
        await expect(editor.locator('[data-slot="te-note-secrets-hint"]')).toContainText(/Parola, belirteç|passwords, tokens/)
        const save = editor.locator('[data-slot="te-note-save"]')
        await expect(save).toBeDisabled()
        await ta.fill(NOTE_TEXT)
        await expect(editor).toHaveAttribute('data-dirty', 'true')
        await expect(save).toBeEnabled()

        // 2) Yazma alanına yapıştırma (Ctrl/⌘+V'nin olayı): görsel pano adıyla, ölçüsü aynen yüklenir; başvurusu imlece
        // (metnin sonuna) KENDİ satırında eklenir; görsel listesinde "kullanılmıyor" görünmez (taslakta kullanılıyor)
        await ta.evaluate((el, b64) => {
          el.focus()
          el.setSelectionRange(el.value.length, el.value.length)
          const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
          const dt = new DataTransfer()
          dt.items.add(new File([bytes], 'image.png', { type: 'image/png' }))
          el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
        }, stripedPng(300, 200).toString('base64'))
        await expect.poll(() => noteUploads.length, { timeout: 15_000 }).toBe(1)
        expect(pngSize(noteUploads[0])).toMatchObject({ w: 300, h: 200 })
        expect(noteUploads[0].toString('latin1')).toMatch(/filename="kanal-notu-pano-\d{8}-\d{6}\.png"/)
        await expect(ta).toHaveValue(/\n!\[kanal-notu-pano-\d{8}-\d{6}\]\(te-note-img:701\)\n$/)
        const rows = editor.locator('[data-slot="te-note-image-row"]')
        await expect(rows).toHaveCount(1)
        await expect(rows.first()).toHaveAttribute('data-unused', 'false')
        await expect(rows.first().locator('[data-slot="te-note-image-delete"]')).toHaveCount(0)
        // Yuva eylemleri rapor görsel yuvalarıyla aynı düğmeler, aynı sırada (telefonda "Ekrandan al" yok)
        const mainActions = await editor.locator('[data-slot="te-image-main-actions"] > button').evaluateAll((els) => els.map((b) => b.getAttribute('data-slot')))
        expect(mainActions.slice(0, 1)).toEqual(['te-image-upload'])
        expect(mainActions.filter((s) => !['te-image-upload', 'te-image-snip', 'te-image-paste'].includes(s)), 'yabancı eylem').toEqual([])
        if (vp.width < 768) expect(mainActions).not.toContain('te-image-snip')
        await page.waitForTimeout(200)
        expect(await page.evaluate(overflowX), 'yatay taşma (düzenleyici)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-notes-tab"]'), 'kırpılan metin (düzenleyici)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-notes-tab"]'), 'küçük hedef (düzenleyici)').toEqual([])
        await page.screenshot({ path: `test-results/te-notes-editor-${vp.name}.png` })

        // 3) Önizleme = notun kilitli görüntüleyicisi: kendi görseli çizilir, dış adres için <img> YOK (bilgi kutusu)
        await editor.getByRole('button', { name: /^(Önizleme|Preview)$/ }).click()
        const preview = editor.locator('[data-slot="te-note-preview"]')
        await expect(preview.locator('[data-slot="te-note-view"]')).toBeVisible({ timeout: 20_000 })
        const pvImg = preview.locator('img[data-slot="te-note-img"]')
        await expect(pvImg).toHaveCount(1)
        await expect.poll(() => loadedWidth(pvImg), { timeout: 15_000 }).toBe(300)
        await expect(preview.locator('img')).toHaveCount(1)
        await expect(preview.locator('[data-slot="te-note-img-blocked"]')).toHaveCount(1)
        await expect(preview.locator('[data-slot="te-note-table"]')).toBeVisible()
        await page.waitForTimeout(200)
        expect(await page.evaluate(overflowX), 'yatay taşma (önizleme)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-notes-tab"]'), 'kırpılan metin (önizleme)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-notes-tab"]'), 'küçük hedef (önizleme)').toEqual([])

        // 4) Kaydet: okunan sürümle (not yokken 0) gider; düzenleyici temiz kalır
        await editor.getByRole('button', { name: /^(Yaz|Write)$/ }).click()
        await save.click()
        await expect.poll(() => noteSaves.length, { timeout: 10_000 }).toBe(1)
        expect(noteSaves[0].version).toBe(0)
        expect(noteSaves[0].body).toMatch(/\]\(te-note-img:701\)/)
        expect(noteSaves[0].body).toContain(`https://${NOTE_REMOTE_HOST}/a.png`)
        await expect(editor).toHaveAttribute('data-dirty', 'false')
        await expect(editor.locator('[data-slot="te-note-status"]')).toHaveText(/Tüm değişiklikler kaydedildi|All changes saved/)
        await expect(save).toBeDisabled()
        await editor.locator('[data-slot="te-note-close"]').click()
        await expect(tab).toHaveAttribute('data-state', 'read')
        await expect(tab.locator('[data-slot="te-note-meta"]')).toContainText(LONG_PERSON)

        // 5) Sayfa yenilenir: kaydedilen not sunucudan gelir — başlık, tablo, kendi görseli (yüklenen baytlarla), dış görsel yok
        await page.reload()
        await expect(tab).toHaveAttribute('data-state', 'read', { timeout: 20_000 })
        const read = tab.locator('[data-slot="te-note-read"]')
        await expect(read.getByRole('heading', { level: 3, name: 'Veriyi nereden alıyoruz' })).toBeVisible({ timeout: 20_000 })
        const img = read.locator('img[data-slot="te-note-img"]')
        await expect(img).toHaveCount(1)
        await expect.poll(() => loadedWidth(img), { timeout: 15_000 }).toBe(300)
        expect(new URL(await img.getAttribute('src'), page.url()).pathname).toBe('/api/tech-error-reports/channel-notes/images/701')
        await expect(read.locator('img')).toHaveCount(1)
        await expect(read.locator('[data-slot="te-note-img-blocked"]')).toContainText(/Dış kaynaklı görsel gösterilmez|External images aren.t shown/)
        await expect(read.locator('[data-slot="te-note-table"]')).toBeVisible()
        await expect(read.getByRole('link', { name: /qlik\.ornek-kurum/ })).toHaveAttribute('rel', 'noopener noreferrer')
        await expect(tab.locator('[data-slot="te-note-edit"]')).toBeVisible()
        await page.waitForTimeout(300)
        expect(await page.evaluate(overflowX), 'yatay taşma (okuma)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-notes-tab"]'), 'kırpılan metin (okuma)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-notes-tab"]'), 'küçük hedef (okuma)').toEqual([])
        // Kanal başlığı ve sekme şeridi de (dört sekme) sığar
        expect(await page.evaluate(clipped, '[data-slot="te-channel"]'), 'kırpılan metin (kanal sayfası)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-channel"]'), 'küçük hedef (kanal sayfası)').toEqual([])
        await page.screenshot({ path: `test-results/te-notes-read-${vp.name}.png` })

        // Görsel büyütülür ve kapanır (odak görsele döner)
        await read.locator('[data-slot="te-note-img-open"]').click()
        const zoomBox = page.locator('[data-slot="te-image-lightbox"]')
        await expect(zoomBox).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(zoomBox).toHaveCount(0)

        expect(remote, 'dış sunucuya giden istek').toEqual([])
      })

      // Kaydedilmemiş notla başka sekmeye tıklamak BİR KEZ sorar: "Sayfada kal" deyince soru penceresi odağı sekmeye geri
      // verir — eskiden (otomatik etkinleştirmede) bu odak sekmeyi yeniden seçip soruyu döngüye sokuyordu (inceleme 2026-11-20)
      test('kanal notları: kaydedilmemiş notta sekme değişimi bir kez sorar; "Sayfada kal" döngüye girmez', async ({ page }) => {
        test.setTimeout(90_000)
        await mock(page)
        notesOn = true
        noteDoc = { version: 2, body: '# Veriyi nereden alıyoruz\n\nQlik panosu.', updated_at: '2026-11-18T08:30:00Z',
          updated_by_name: LONG_PERSON, created_at: '2026-11-01T08:00:00Z' }
        await page.goto('/?tab=channelreports&te_view=channel&te_ch=10&te_tab=notes')
        const tab = page.locator('[data-slot="te-notes-tab"]')
        await expect(tab).toHaveAttribute('data-state', 'read', { timeout: 20_000 })
        await tab.locator('[data-slot="te-note-edit"]').click()
        const editor = page.locator('[data-slot="te-note-editor"]')
        const ta = editor.locator('[data-slot="te-note-write-pane"] textarea')
        await expect(ta).toBeVisible({ timeout: 20_000 })
        await ta.fill('# Veriyi nereden alıyoruz\n\nQlik panosu — kaydedilmedi.')
        await expect(editor).toHaveAttribute('data-dirty', 'true')

        const reportsTab = page.locator('[data-slot="te-tab-reports"]')
        const dialog = page.getByRole('dialog').filter({ hasText: /Kaydedilmemiş değişiklikler var|You have unsaved changes/ })
        await reportsTab.click()
        await expect(dialog).toHaveCount(1)
        await dialog.getByRole('button', { name: /^(Sayfada kal|Stay on the page)$/ }).click()
        await expect(dialog).toHaveCount(0)
        // Odak sekmeye döndükten sonra soru yeniden AÇILMAZ; not sekmesi ve taslak yerinde
        await page.waitForTimeout(600)
        await expect(dialog).toHaveCount(0)
        await expect(page.locator('[data-slot="te-tab-notes"]')).toHaveAttribute('data-state', 'active')
        await expect(ta).toHaveValue('# Veriyi nereden alıyoruz\n\nQlik panosu — kaydedilmedi.')

        await reportsTab.click()
        await expect(dialog).toHaveCount(1)
        await dialog.getByRole('button', { name: /^(Kaydetmeden ayrıl|Leave without saving)$/ }).click()
        await expect.poll(() => new URL(page.url()).searchParams.get('te_tab')).not.toBe('notes')
        await expect(dialog).toHaveCount(0)
      })

      // Kanal notları kartı: rapor düzenleyicisinin başında ve Görsel paneli penceresinin (520 px) tepesinde SALT OKUNUR.
      // Kapalı başlar ve kapalıyken notu okumaz; açılınca not görseliyle çizilir; dış görsel istenmez; "Notu düzenle" yalnız
      // rapor düzenleyicisinde vardır ve kanalın Notlar sekmesine götürür.
      test('kanal notları: rapor düzenleyicisinde ve Görsel panelinde (520 px) salt okunur kart; sığar', async ({ page, context }) => {
        test.setTimeout(150_000)
        await mock(page, { target: context })
        notesOn = true
        const shot = stripedPng(300, 200)
        noteImageBytes.set(701, shot)
        noteImages = [{ id: 701, file_name: 'qlik-aylik-ozet-panosu-ekran-goruntusu.png', content_type: 'image/png', width: 300, height: 200,
          size_bytes: shot.length, uploaded_at: '2026-11-18T08:20:00Z' }]
        noteDoc = { version: 3, body: `${NOTE_TEXT}\n![Qlik aylık özet panosu](te-note-img:701)\n`, updated_at: '2026-11-18T08:30:00Z',
          updated_by_name: LONG_PERSON, created_at: '2026-11-01T08:00:00Z' }
        const remote = await trackRemote(context)

        await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
        const editor = page.locator('[data-slot="te-report"]')
        const card = editor.locator('[data-slot="te-notes-card"]')
        await expect(card).toBeVisible({ timeout: 20_000 })
        const toggle = card.locator('[data-slot="te-section-toggle"]')
        // Rapor bölümleri açık tohumlanmış olsa da kart kendi kaydını tutar: kapalı başlar ve notu OKUMAZ
        await expect(toggle).toHaveAttribute('aria-expanded', 'false')
        await expect(card).toHaveAttribute('data-kind', 'note')
        expect(noteGets, 'kapalı kart not okumaz').toBe(0)
        // Kart ilk rapor bölümünün üstünde
        const cardTop = await card.boundingBox()
        const firstSection = await editor.locator('[data-section]:not([data-slot="te-notes-card"])').first().boundingBox()
        expect(cardTop.y + cardTop.height).toBeLessThanOrEqual(firstSection.y + 1)

        await toggle.click()
        await expect(card).toHaveAttribute('data-note-state', 'note', { timeout: 20_000 })
        expect(noteGets).toBe(1)
        const view = card.locator('[data-slot="te-note-view"]')
        // Kart başlığı h3 olduğundan notun başlıkları h4'ten başlar
        await expect(view.getByRole('heading', { level: 4, name: 'Veriyi nereden alıyoruz' })).toBeVisible()
        const img = view.locator('img[data-slot="te-note-img"]')
        await expect(img).toHaveCount(1)
        await expect.poll(() => loadedWidth(img), { timeout: 15_000 }).toBe(300)
        await expect(view.locator('img')).toHaveCount(1)
        await expect(view.locator('[data-slot="te-note-img-blocked"]')).toHaveCount(1)
        await expect(card.locator('[data-slot="te-notes-card-meta"]')).toContainText(LONG_PERSON)
        // Salt okunur: yazma alanı / yükleme yok; düzenleme yalnız Notlar sekmesine götüren bağlantıdır
        await expect(card.locator('textarea, input[type="file"], [data-slot="te-image-upload"]')).toHaveCount(0)
        await expect(card.locator('[data-slot="te-notes-card-edit"]')).toBeVisible()
        await page.waitForTimeout(300)
        expect(await page.evaluate(overflowX), 'yatay taşma (rapor kartı)').toBeLessThanOrEqual(1)
        expect(await page.evaluate(clipped, '[data-slot="te-notes-card"]'), 'kırpılan metin (rapor kartı)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-notes-card"]'), 'küçük hedef (rapor kartı)').toEqual([])
        await card.screenshot({ path: `test-results/te-notes-card-${vp.name}.png` })

        // Görsel paneli penceresi (520 px): kart görsel yuvalarının ÜSTÜNDE, düzenleme bağlantısı YOK. Açık / kapalı durumu
        // rapor başına hatırlandığından (az önce açıldı) kart açık gelir ve notu okur
        const panel = await context.newPage()
        await panel.setViewportSize({ width: 520, height: 900 })
        await panel.goto('/?tab=channelreports&te_view=images&te_rep=100')
        await expect(panel.locator('[data-slot="te-panel"][data-state="ready"]')).toBeVisible({ timeout: 20_000 })
        const pCard = panel.locator('[data-slot="te-notes-card"]')
        await expect(pCard).toBeVisible()
        await expect(pCard.locator('[data-slot="te-section-toggle"]')).toHaveAttribute('aria-expanded', 'true')
        await expect(pCard).toHaveAttribute('data-note-state', 'note', { timeout: 20_000 })
        const pImg = pCard.locator('img[data-slot="te-note-img"]')
        await expect(pImg).toHaveCount(1)
        await expect.poll(() => loadedWidth(pImg), { timeout: 15_000 }).toBe(300)
        await expect(pCard.locator('img')).toHaveCount(1)
        await expect(pCard.locator('[data-slot="te-note-img-blocked"]')).toHaveCount(1)
        await expect(pCard.locator('[data-slot="te-notes-card-edit"], [data-slot="te-notes-card-write"], textarea')).toHaveCount(0)
        const pBox = await pCard.boundingBox()
        const slotBox = await panel.locator('[data-slot="te-image-slot"]').first().boundingBox()
        expect(pBox.y + pBox.height).toBeLessThanOrEqual(slotBox.y + 1)
        await panel.waitForTimeout(300)
        expect(await panel.evaluate(overflowX), 'yatay taşma (panel 520)').toBeLessThanOrEqual(1)
        expect(await panel.evaluate(clipped, '[data-slot="te-panel"]'), 'kırpılan metin (panel)').toEqual([])
        if (vp.touch) expect(await panel.evaluate(smallTargets, '[data-slot="te-panel"]'), 'küçük hedef (panel)').toEqual([])
        await panel.screenshot({ path: `test-results/te-notes-panel-${vp.name}.png` })
        // Kart kapatılabilir (panelde yer açmak için) ve görsel yuvaları yerinde kalır
        await pCard.locator('[data-slot="te-section-toggle"]').click()
        await expect(pCard.locator('[data-slot="te-section-toggle"]')).toHaveAttribute('aria-expanded', 'false')
        await expect(panel.locator('[data-slot="te-image-slot"]').first()).toBeVisible()
        await panel.close()

        expect(remote, 'dış sunucuya giden istek').toEqual([])

        // "Notu düzenle": rapor temizken sorulmadan kanalın Notlar sekmesine götürür (not okuma kipinde açılır)
        await card.locator('[data-slot="te-notes-card-edit"]').click()
        await expect.poll(() => new URL(page.url()).searchParams.get('te_tab')).toBe('notes')
        expect(new URL(page.url()).searchParams.get('te_view')).toBe('channel')
        expect(new URL(page.url()).searchParams.get('te_ch')).toBe('10')
        const tab = page.locator('[data-slot="te-notes-tab"]')
        await expect(tab).toHaveAttribute('data-state', 'read', { timeout: 20_000 })
        await expect(tab.locator('img[data-slot="te-note-img"]')).toHaveCount(1)
        await expect(tab.locator('[data-slot="te-note-edit"]')).toBeVisible()
      })
    })

    test('hızlı kazanımlar (A, 2026-11-20): onay rozeti + "Onayımı bekleyen", önizlemeden PO kararı, konu geri alma, Ctrl+S ve çakışma uyarısı; sığar, kesilmez', async ({ page }) => {
      test.setTimeout(180_000)
      await mock(page)
      // Girişten sonraki "eksikler" penceresi bu testin uzun beklemelerinde açılıp önizlemeyi örtmesin (dönem raporu taslak
      // olduğu için görev eksiği vardır) — yalnız bu oturum için gösterilmiş sayılır
      await page.addInitScript(() => { try { sessionStorage.setItem('sm.gaps.prompted', 'demo') } catch { /* yoksay */ } })
      // A1 — kanal listesi: onay akışlı kanalın dönem raporu PO onayı bekliyor; kullanıcı bu takımın onaylayıcısı
      const flowCh = { ...channel(10, LONG_CHANNEL), approval_required: true, executive_count: 2,
        period_report: { id: 100, status: 'draft', year: 2026, month: 9, last_send_status: null, approval: { state: 'pending' } } }
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/overview', async (route) => {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
          period: { year: 2026, month: 9 }, can_create: true,
          teams: [{ id: 5, name: LONG_TEAM, active: true, has_email: true, can_edit: true, can_manage: false, library_read: true, can_approve: true }],
          channels: [flowCh, channel(20, 'POS')] } }) })
      })
      await page.goto('/?tab=channelreports')
      const items = page.locator('[data-slot="te-channel-card"], [data-slot="te-channel-row"]')
      await expect(items).toHaveCount(2, { timeout: 20_000 })
      const badge = page.locator('[data-slot="te-approval-badge"]')
      await expect(badge).toHaveCount(1)
      await expect(badge).toHaveAttribute('data-approval', 'pending')
      await expect(page.locator('[data-slot="stat-item"][data-key="approval_pending"]')).toBeVisible()
      const mine = page.locator('[data-slot="stat-item"][data-key="my_approval"]')
      await expect(mine).toBeVisible()
      await mine.click()
      await expect(items).toHaveCount(1)
      await expect.poll(() => new URL(page.url()).searchParams.get('te_st')).toBe('my_approval')
      await expect(page.locator('[data-slot="te-order-status"]')).toHaveText(/1/)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (onay süzgeci)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-channel-list"]'), 'kırpılan metin (onay rozeti)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="stats-panel"]'), 'küçük hedef (kutucuklar)').toEqual([])
      await page.screenshot({ path: `test-results/te-quickwins-list-${vp.name}.png`, fullPage: true })

      // A2 — PO e-posta önizlemesinden reddeder: karar penceresi önizlemenin üstünde, gerekçe zorunlu, kayıttan sonra önizleme kapanır
      const detail = { required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
        approvers: [{ name: LONG_PERSON, has_email: true }], requested_by_name: LONG_PERSON, decided_by_name: null,
        // İsteyen PO (10 dk'dan eski bekleyen istek): beş düğme birden — Kapat, test, Reddet, Onayla, isteği yeniden gönder
        can_request: true, resend: true, is_approver: true, can_decide: true, can_send_executive: false }
      reportOverride = { ...REPORT, channel: { ...REPORT.channel, approval_required: true, executive_count: 2 },
        report: { ...REPORT.report, approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' } }, approval_detail: detail }
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="te-mail-preview-btn"]').click()
      const reject = page.locator('[data-slot="te-mail-reject"]')
      const approve = page.locator('[data-slot="te-mail-approve"]')
      await expect(approve).toBeVisible({ timeout: 20_000 })
      const reRequest = page.locator('[data-slot="te-mail-request-approval"]')
      await expect(reRequest).toBeVisible()
      // Beş düğmeli alt çubuk (inceleme 2026-11-20): satıra sığmazsa sarar — hiçbiri pencerenin / ekranın dışına taşmaz
      const pvDlg = page.getByRole('dialog', { name: /E-posta önizlemesi|E-mail preview/ })
      const pvBox = await pvDlg.boundingBox()
      const footerBtns = pvDlg.locator('[data-slot="dialog-footer"] button')
      await expect(footerBtns).toHaveCount(5)
      for (let i = 0; i < 5; i++) {
        const box = await footerBtns.nth(i).boundingBox()
        expect(box.x, `alt çubuk düğmesi ${i} solda taşmaz`).toBeGreaterThanOrEqual(pvBox.x - 1)
        expect(box.x + box.width, `alt çubuk düğmesi ${i} pencerede`).toBeLessThanOrEqual(pvBox.x + pvBox.width + 1)
        expect(box.x + box.width, `alt çubuk düğmesi ${i} ekranda`).toBeLessThanOrEqual(vp.width + 1)
        expect(box.y + box.height, `alt çubuk düğmesi ${i} ekranda`).toBeLessThanOrEqual(vp.height + 1)
        if (vp.touch) expect(box.height, `alt çubuk düğmesi ${i} ≥ 40 px`).toBeGreaterThanOrEqual(39.5)
      }
      expect(await page.evaluate(clipped, '[data-slot="dialog-footer"]'), 'kırpılan düğme metni (alt çubuk)').toEqual([])
      await reject.click()
      const dd = page.locator('[data-slot="te-approval-dialog"]')
      await expect(dd).toBeVisible()
      const ddBox = await dd.boundingBox()
      expect(ddBox.x + ddBox.width, 'karar penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      await dd.locator('[data-slot="te-approval-confirm"]').click()
      await expect(dd.locator('textarea')).toHaveAttribute('aria-invalid', 'true')
      expect(decisions, 'gerekçesiz ret gitmez').toHaveLength(0)
      await dd.locator('textarea').fill('Rakamlar eksik; SLA tablosunu tamamlayın.')
      await dd.locator('[data-slot="te-approval-confirm"]').click()
      await expect.poll(() => decisions.length).toBe(1)
      expect(decisions[0]).toEqual({ decision: 'reject', note: 'Rakamlar eksik; SLA tablosunu tamamlayın.' })
      await expect(dd).toBeHidden()
      await expect(approve).toHaveCount(0)                                // önizleme kapandı

      // A3 — görselsiz konu onaysız silinir, "Geri al" geri getirir; bildirim sığar, düğmesi dokunmatikte 40 px
      reportOverride = ANOMALY_REPORT
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const topicCard = page.locator('[data-slot="te-issue"]')
      await expect(topicCard).toHaveCount(1, { timeout: 20_000 })
      await topicCard.locator('[data-slot="te-issue-remove"]').click()
      await expect(topicCard).toHaveCount(0)
      const undo = page.locator('[data-slot="toast-action"]')
      await expect(undo).toBeVisible()
      const ub = await undo.boundingBox()
      if (vp.touch) expect(ub.height, '"Geri al" ≥ 40 px').toBeGreaterThanOrEqual(39.5)
      const toastBox = await page.locator('[data-sonner-toast]').filter({ has: undo }).boundingBox()
      expect(toastBox.x, 'bildirim solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(toastBox.x + toastBox.width, 'bildirim sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-sonner-toaster]'), 'kırpılan metin (geri al bildirimi)').toEqual([])
      await undo.click()
      await expect(topicCard).toHaveCount(1)
      await expect(topicCard).toHaveAttribute('data-issue', 'k1')
      await expect(topicCard.getByText(LONG_TOPIC).first()).toBeVisible()

      // A4 + A5 — Ctrl+S kaydeder (tarayıcının "sayfayı kaydet"i açılmaz); başkası kaydetmişse uyarı kim / ne zaman + geçmiş düğmesi
      reportOverride = null
      let reportGets = 0
      let puts = 0
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/reports/100', async (route) => {
        const req = route.request()
        if (req.method() === 'PUT') {
          puts++
          await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false,
            code: 'CONCURRENT_MODIFICATION', error: 'Rapor siz düzenlerken başka biri tarafından kaydedildi.' }) })
          return
        }
        reportGets++
        if (reportGets === 1) { await route.fallback(); return }
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { ...REPORT,
          report: { ...REPORT.report, version: 6, updated_at: '2026-10-13T09:00:00Z', updated_by_name: LONG_PERSON },
          values: { ...REPORT.values, fields: { 'error-rate': 0.9 } } } }) })
      })
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const total = editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })
      await expect(total).toBeVisible({ timeout: 20_000 })
      // Yarım yazılmış sayı (inceleme 2026-11-20): Ctrl+S alanı bırakır, hata alanın altında görünür, istek GİTMEZ, odak alanda
      await total.fill('1.250.0')
      await page.keyboard.press('Control+s')
      await expect(total).toHaveAttribute('aria-invalid', 'true')
      await expect(total).toBeFocused()
      await page.waitForTimeout(400)
      expect(puts, 'yarım sayı kaydedilmez').toBe(0)
      await total.fill('1250000')
      await page.keyboard.press('Control+s')
      const conflict = page.locator('[data-slot="te-conflict"]')
      await expect(conflict).toBeVisible({ timeout: 20_000 })
      await expect(conflict).toHaveAttribute('data-known', 'true')
      await expect(conflict.locator('[data-slot="te-conflict-who"]')).toContainText(LONG_PERSON)
      await expect(total).toHaveValue(/1[.,]?250[.,]?000/)              // taslak korunur
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (çakışma)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-conflict"]'), 'kırpılan metin (çakışma)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-conflict"]'), 'küçük hedef (çakışma)').toEqual([])
      await conflict.locator('[data-slot="te-conflict-history"]').click()
      await expect(page.locator('[data-slot="te-history"]')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(page.locator('[data-slot="te-history"]')).toBeHidden()

      // A4 — Alt+↓ / Alt+↑ bölüm başlıkları arasında odak taşır, bölüm açıp kapamaz (klavyeli cihaz)
      if (!vp.touch) {
        const toggles = editor.locator('[data-slot="te-section-toggle"]')
        const before = await editor.locator('[data-section][data-open]').evaluateAll((els) => els.map((e) => e.getAttribute('data-open')))
        await toggles.nth(0).focus()
        await page.keyboard.press('Alt+ArrowDown')
        await expect(toggles.nth(1)).toBeFocused()
        await page.keyboard.press('Alt+ArrowUp')
        await expect(toggles.nth(0)).toBeFocused()
        const after = await editor.locator('[data-section][data-open]').evaluateAll((els) => els.map((e) => e.getAttribute('data-open')))
        expect(after).toEqual(before)
      }
      await page.screenshot({ path: `test-results/te-quickwins-editor-${vp.name}.png` })
    })

    test('hazırlık rehberi (B, 2026-11-20): çip + panel + "Git", bölüm rozeti, birim sorusu, gönderimde özet, onay kilidi, şablonda silinecek adlar; sığar, kesilmez', async ({ page }) => {
      test.setTimeout(180_000)
      await mock(page, { openSections: false })
      await page.addInitScript(() => {
        try { sessionStorage.setItem('sm.gaps.prompted', 'demo'); localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ }
      })
      const dialogWith = (text) => page.locator('[role="alertdialog"], [role="dialog"]').filter({ hasText: text })
      // Açılma canlandırmaları bitsin (kutular son yerinde ölçülür)
      const settle = (loc) => loc.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => null))))

      // B1 — çip: görsel oranı + boş alan + uyarı (özete girmeyen 2 cümle); kapalı bölüm başlığında rozet
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const editor = page.locator('[data-slot="te-report"]')
      await expect(editor).toBeVisible({ timeout: 20_000 })
      const chip = page.locator('[data-slot="te-ready-chip"]')
      await expect(chip).toHaveText('Görsel 0/3 · 8 boş alan · 2 uyarı')
      await expect(chip).toHaveAttribute('data-tone', 'warn')
      await expect(editor.locator('[data-section="summary"] [data-slot="te-section-ready"]')).toHaveText('7 eksik')
      await expect(editor.locator('[data-section="charts"] [data-slot="te-section-ready"]')).toHaveText('3 eksik')
      await expect(editor.locator('[data-section="exec"] [data-slot="te-section-ready"]')).toHaveText('2 eksik')
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (hazırlık çipi)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-report"]'), 'kırpılan metin (çip / rozetler)').toEqual([])
      const chipBox = await chip.boundingBox()
      expect(chipBox.x + chipBox.width, 'çip ekranda').toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) expect(chipBox.height, 'çip ≥ 40 px').toBeGreaterThanOrEqual(39.5)

      // Panel: bölüme göre gruplu, sığar; "Git" bölümü açıp alana odaklanır
      await chip.click()
      const sheet = page.locator('[data-slot="te-ready-sheet"]')
      await expect(sheet).toBeVisible()
      await expect(sheet.locator('[data-slot="te-ready-group"]')).toHaveCount(4)
      // Kayarak açılma canlandırması bitsin (ölçüm son yerinde yapılır)
      await settle(sheet)
      const sb = await sheet.boundingBox()
      expect(sb.x, 'panel solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(sb.x + sb.width, 'panel sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-ready-sheet"]'), 'kırpılan metin (panel)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-ready-sheet"]'), 'küçük hedef (panel)').toEqual([])
      await page.screenshot({ path: `test-results/te-readiness-sheet-${vp.name}.png` })
      await sheet.getByRole('button', { name: 'Git: Toplam işlem adedi boş' }).click()
      await expect(sheet).toBeHidden()
      const total = editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })
      await expect(total).toBeFocused()
      await expect(editor.locator('[data-section="summary"] [data-slot="te-section-ready"]')).toHaveCount(0)

      // B2 — geçen ay 1.000.000, bu ay 1.000: alanın altında birim sorusu (engellemez), çipte uyarı
      await total.fill('1000')
      const scale = editor.locator('[data-field="values.total-tx"] [data-slot="te-scale-warn"]')
      await expect(scale).toHaveText('Geçen ay 1.000.000 idi, bu ay 1.000 — birim doğru mu?')
      await expect(chip).toHaveText('Görsel 0/3 · 7 boş alan · 2 uyarı')
      expect(await page.evaluate(clipped, '[data-field="values.total-tx"]'), 'kırpılan metin (birim sorusu)').toEqual([])
      await total.fill('1250000')
      await expect(scale).toHaveCount(0)

      // Gönderim penceresi eksikleri söyler, "Gönder" açık kalır (önce kaydedilmemiş değişiklik kaydedilir)
      await page.locator('[data-slot="te-report-actions"] [data-slot="te-send"]').click()
      const saveFirst = dialogWith('Önce kaydedilsin mi?')
      await expect(saveFirst).toBeVisible()
      await saveFirst.getByRole('button', { name: 'Kaydet ve devam et' }).click()
      const sendDlg = page.locator('[data-slot="te-send-dialog"]')
      await expect(sendDlg).toBeVisible({ timeout: 20_000 })
      await expect(sendDlg.locator('[data-slot="te-send-readiness"]')).toContainText('— yine de gönderebilirsiniz.')
      await expect(sendDlg.getByRole('button', { name: 'Gönder', exact: true })).toBeEnabled()
      await settle(sendDlg)
      const sdb = await sendDlg.boundingBox()
      expect(sdb.x + sdb.width, 'gönderim penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-send-dialog"]'), 'kırpılan metin (gönderim özeti)').toEqual([])
      await page.keyboard.press('Escape')
      await expect(sendDlg).toBeHidden()

      // B3 — PO onayı bekleyen rapor kilitli açılır; kilit onayla açılır
      reportOverride = { ...REPORT, channel: { ...REPORT.channel, approval_required: true, executive_count: 2 },
        report: { ...REPORT.report, approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' } },
        approval_detail: { required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
          approvers: [{ name: LONG_PERSON, has_email: true }], requested_by_name: LONG_PERSON, decided_by_name: null,
          can_request: false, is_approver: false, can_decide: false, can_send_executive: false } }
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      const lockBand = page.locator('[data-slot="te-approval-lock"]')
      await expect(lockBand).toBeVisible({ timeout: 20_000 })
      await expect(lockBand).toHaveAttribute('data-state', 'pending')
      await expect(page.locator('[data-slot="te-save-status"]')).toHaveText('Onay sürecinde — kilitli')
      await expect(page.locator('[data-slot="te-save"]')).toHaveCount(0)
      await page.waitForTimeout(300)
      expect(await page.evaluate(overflowX), 'yatay taşma (kilit bandı)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="te-approval-lock"]'), 'kırpılan metin (kilit bandı)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-approval-lock"]'), 'küçük hedef (kilit bandı)').toEqual([])
      await expect(editor.locator('[data-field="values.total-tx"] input')).toBeDisabled()
      await page.screenshot({ path: `test-results/te-readiness-lock-${vp.name}.png` })
      await lockBand.locator('[data-slot="te-lock-unlock"]').click()
      const unlockDlg = dialogWith('Düzenleme kilidi açılsın mı?')
      await expect(unlockDlg).toBeVisible()
      await unlockDlg.getByRole('button', { name: 'Düzenlemeyi aç' }).click()
      await expect(lockBand).toHaveCount(0)
      // Özet bölümü "Git" ile açılmıştı (kişisel kayıtta hatırlanır); kapalıysa açılır
      const sumCard = editor.locator('[data-section="summary"]')
      if ((await sumCard.getAttribute('data-open')) !== 'true') await sumCard.locator('[data-slot="te-section-toggle"]').click()
      const total2 = sumCard.getByLabel('Toplam işlem adedi', { exact: true })
      await expect(total2).toBeVisible()
      await expect(total2).toBeEnabled()
      await expect(page.locator('[data-slot="te-save"]')).toBeVisible()

      // B4 — "Şablonun son hâlini uygula" onayı silinecek alan / görsel ADLARINI listeler (kaydırılır, adlar sarar)
      reportOverride = { ...REPORT, template_update: { differs: true, lost_values: 3, lost_images: 2,
        lost_fields: [
          { id: 'sla-rate', label: 'Kritik fonksiyonların SLA oranı', section: `${LONG_CHANNEL} Kanalı — Eylül 2026 Özeti`, kind: 'field', count: 1 },
          { id: 'i-1', label: `${LONG_TOPIC} Konusu ile yaşanan problem`, section: 'Teknik hataların anomali olarak arttığı konular', kind: 'topic', count: 2 },
        ],
        lost_slots: [{ id: 'daily-errors', label: `${LONG_CHANNEL} Kanalı Teknik Hata (Günlük)`, section: 'Grafikler', kind: 'slot' }],
        lost_fields_more: 0, lost_slots_more: 1 } }
      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      await expect(editor).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: 'Şablonun son hâlini uygula' }).click()
      const applyDlg = dialogWith('Şablonun son hâli uygulansın mı?')
      await expect(applyDlg).toBeVisible()
      const loss = applyDlg.locator('[data-slot="te-tpl-loss"]')
      await expect(loss).toBeVisible()
      await expect(loss.locator('[data-slot="te-tpl-loss-fields-item"]')).toHaveCount(2)
      await expect(loss.locator('[data-slot="te-tpl-loss-slots-item"]')).toHaveCount(1)
      await expect(loss.locator('[data-slot="te-tpl-loss-slots-more"]')).toHaveText('ve 1 tane daha')
      await settle(applyDlg)
      const ab = await applyDlg.boundingBox()
      expect(ab.x, 'onay penceresi solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(ab.x + ab.width, 'onay penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(ab.y + ab.height, 'onay penceresi ekranda').toBeLessThanOrEqual(vp.height + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-tpl-loss"]'), 'kırpılan metin (silinecekler)').toEqual([])
      const lb = await loss.boundingBox()
      expect(lb.x + lb.width, 'liste pencerede').toBeLessThanOrEqual(ab.x + ab.width + 1)
      await page.screenshot({ path: `test-results/te-readiness-tplloss-${vp.name}.png` })
      await applyDlg.getByRole('button', { name: 'Vazgeç' }).click()
      await expect(applyDlg).toBeHidden()
    })

    test('geçen aydan ve toplu doldurma (C, 2026-11-20): toplu bırakma, geçen ayın görseli + büyük görünüm, sıralı doldur, Excel\'den tabloya, geçen aydan getir; sığar, kesilmez', async ({ page }) => {
      test.setTimeout(180_000)
      await mock(page)
      persistUploads = true
      await page.addInitScript(() => {
        try { sessionStorage.setItem('sm.gaps.prompted', 'demo'); localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ }
      })
      // Geçen ayın raporu (Ağustos 2026): yalnız isteyince okunur — sayılır
      let prevReads = 0
      const PREV_TEXT = 'Ağustos ayında grafikler yeni raporlama aracından alındı; eğilim sayfa 3’teki tabloda ayrıntılı açıklandı.'
      const PREV_NOTE = 'Map yapılmamış 12 hata kodu sahibi ekibe iletildi; Ekim sonuna kadar eşlenecek.'
      await page.route((u) => new URL(u).pathname === '/api/tech-error-reports/reports/90', async (route) => {
        prevReads++
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
          ...REPORT, report: { ...REPORT.report, id: 90, year: 2026, month: 8, status: 'sent', version: 7 },
          values: { fields: { 'error-rate': 0.5, 'total-tx': 1000000, 'unmapped-note': PREV_NOTE },
            texts: { 'trend-note': PREV_TEXT },
            issues: { issues: [{ id: 'p-1', topic: LONG_TOPIC, title: '', title_auto: true,
              description: 'Servis yanıt süresi uzadı; önbellek yenilendi.', customers: 320, errors: 1700 }] } },
          images: [{ id: 61, slot_id: 'monthly-tx', file_name: 'agustos-aylik-islem.png', width: 1600, height: 700, size_bytes: 90000 }],
        } }) })
      })
      const settle = (loc) => loc.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => null))))
      const pngB64 = stripedPng(300, 200).toString('base64')
      const editor = page.locator('[data-slot="te-report"]')
      const slot = (id) => editor.locator(`[data-slot-id="${id}"]`)

      await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
      await expect(slot('monthly-tx')).toBeVisible({ timeout: 20_000 })
      expect(prevReads, 'geçen ayın raporu istenmeden okunmaz').toBe(0)

      // ── C4 — bölüme bırakılan dosyalar yuvalara eşlenir (adındaki yuva kimliği, yuva adı), sırayla yüklenir ────────────
      await editor.locator('[data-slot="te-images"][data-section="charts"]').evaluate((card, b64) => {
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
        const dt = new DataTransfer()
        dt.items.add(new File([bytes], 'tech-errors-pano-20261103-101500.png', { type: 'image/png' }))
        dt.items.add(new File([bytes], 'Ay Bazlı Toplam İşlem Adetleri - Eylül.png', { type: 'image/png' }))
        dt.items.add(new File([new Uint8Array([1, 2, 3])], 'notlar.txt', { type: 'text/plain' }))
        const opts = { dataTransfer: dt, bubbles: true, cancelable: true }
        card.dispatchEvent(new DragEvent('dragover', opts))
        card.dispatchEvent(new DragEvent('drop', opts))
      }, pngB64)
      const bulk = page.locator('[data-slot="te-bulk"]')
      await expect(bulk).toBeVisible()
      await expect(bulk.locator('[data-slot="te-bulk-row"]')).toHaveCount(3)
      await expect(bulk.locator('[data-slot="te-bulk-row"][data-index="0"]')).toHaveAttribute('data-target', 'tech-errors')
      await expect(bulk.locator('[data-slot="te-bulk-row"][data-index="1"]')).toHaveAttribute('data-target', 'monthly-tx')
      await expect(bulk.locator('[data-slot="te-bulk-row"][data-index="2"]')).toHaveAttribute('data-how', 'notImage')
      const bulkDlg = page.getByRole('dialog', { name: 'Görselleri yuvalara eşleyin' })
      await settle(bulkDlg)
      const bb = await bulkDlg.boundingBox()
      expect(bb.x, 'eşleme penceresi solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(bb.x + bb.width, 'eşleme penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-bulk"]'), 'kırpılan metin (toplu bırakma)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="dialog"]'), 'küçük hedef (toplu bırakma)').toEqual([])
      await page.screenshot({ path: `test-results/te-c-bulk-${vp.name}.png` })
      // Kilit çakışması (inceleme 2026-11-20): "Aylık işlem" yuvasına yükleme ilk kez 503 LOCK_CONFLICT alır ve o arada öteki
      // pencere o yuvaya görsel koymuştur — "değiştirilsin mi?" onayı ÇALIŞAN toplu bırakma penceresinin ÜSTÜNDE açılır ve
      // gerçek tarayıcıda tıklanabilir (Playwright'ın tıklama denetimi örtülü düğmeye tıklamaz)
      let conflictOnce = true
      await page.route((u) => /\/api\/tech-error-reports\/reports\/100\/images\/monthly-tx$/.test(new URL(u).pathname), async (route) => {
        if (route.request().method() !== 'POST' || !conflictOnce) return route.fallback()
        conflictOnce = false
        storedImages = [...storedImages.filter((i) => i.slot_id !== 'monthly-tx'),
          { id: 777, slot_id: 'monthly-tx', content_type: 'image/png', file_name: 'diger-pencere.png', width: 1, height: 1, size_bytes: 100 }]
        await route.fulfill({ status: 503, contentType: 'application/json', headers: { 'Retry-After': '1' },
          body: JSON.stringify({ success: false, code: 'LOCK_CONFLICT', error: 'Rapor şu an başka bir işlemle güncelleniyor.', retry_after_seconds: 1 }) })
      })
      await bulkDlg.getByRole('button', { name: 'Yükle (2)' }).click()
      const otherConfirm = page.locator('[role="alertdialog"], [role="dialog"]').filter({ hasText: 'Görsel değiştirilsin mi?' })
      await expect(otherConfirm).toBeVisible({ timeout: 20_000 })
      await expect(bulk).toHaveAttribute('data-phase', 'running')
      await settle(otherConfirm)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="alert-dialog-content"]'), 'küçük hedef (değiştirme onayı)').toEqual([])
      await page.screenshot({ path: `test-results/te-c-lockconfirm-${vp.name}.png` })
      await otherConfirm.getByRole('button', { name: 'Değiştir', exact: true }).click()
      await expect(otherConfirm).toBeHidden()
      await expect(bulk).toHaveAttribute('data-phase', 'done', { timeout: 20_000 })
      expect(uploads).toHaveLength(2)
      await expect(bulk).toContainText('2 yüklendi, 1 atlandı, 0 yüklenemedi.')
      await bulkDlg.locator('[data-slot="te-bulk-close"]').click()
      await expect(bulk).toBeHidden()
      await expect(slot('monthly-tx')).toHaveAttribute('data-has-image', 'true')
      await expect(slot('tech-errors')).toHaveAttribute('data-has-image', 'true')
      await expect(slot('daily-errors')).toHaveAttribute('data-has-image', 'false')

      // ── C3 — "Geçen ay": bir kez okunur, aynı yuvanın görseli; büyük görünümde "Bu ay | Geçen ay" ─────────────────────
      await slot('monthly-tx').locator('[data-slot="te-image-prev-toggle"]').click()
      await expect(slot('monthly-tx').locator('[data-slot="te-image-prev-img"]')).toBeVisible()
      await slot('tech-errors').locator('[data-slot="te-image-prev-toggle"]').click()
      await expect(slot('tech-errors').locator('[data-slot="te-image-prev-none"]')).toHaveText('Ağustos 2026 raporunda bu yuvada görsel yoktu.')
      expect(prevReads, 'yuvalar AYNI okumayı paylaşır').toBe(1)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (geçen ay görünümü)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-section="charts"]'), 'kırpılan metin (geçen ay görünümü)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-section="charts"] [data-slot-id="monthly-tx"]'), 'küçük hedef (yuva)').toEqual([])
      await slot('monthly-tx').locator('[data-slot="te-image-zoom"]').click()
      const box = page.locator('[data-slot="te-image-lightbox"]')
      await expect(box).toBeVisible()
      const view = box.locator('[data-slot="te-image-lightbox-view"]')
      await expect(view).toHaveAttribute('data-month', 'current')
      await box.locator('[data-slot="te-image-lightbox-month"]').getByRole('radio', { name: 'Geçen ay' }).click()
      await expect(view).toHaveAttribute('data-month', 'prev')
      await expect(view.locator('img')).toHaveAttribute('src', /\/images\/61$/)
      await settle(box)
      const lb = await box.boundingBox()
      expect(lb.x + lb.width, 'büyük görünüm ekranda').toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-image-lightbox"]'), 'küçük hedef (büyük görünüm)').toEqual([])
      await page.screenshot({ path: `test-results/te-c-prev-${vp.name}.png` })
      await page.keyboard.press('Escape')
      await expect(box).toBeHidden()

      // ── C2 — "Sıralı doldur" (masaüstü / tablet): üç pencere düzeyi yapıştırma ardışık BOŞ yuvalara ──────────────────
      if (vp.width >= 768) {
        storedImages = []
        uploads = []
        await page.goto('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
        await expect(slot('monthly-tx')).toHaveAttribute('data-has-image', 'false', { timeout: 20_000 })
        const seq = editor.locator('[data-slot="te-seqfill"]')
        await expect(seq).toHaveAttribute('data-enabled', 'false')
        await seq.getByRole('switch', { name: 'Sıralı doldur' }).click()
        await expect(seq).toHaveAttribute('data-enabled', 'true')
        const next = seq.locator('[data-slot="te-seqfill-next"]')
        await expect(next).toHaveAttribute('data-next', 'monthly-tx')
        const windowPaste = () => page.evaluate((b64) => {
          const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
          const dt = new DataTransfer()
          dt.items.add(new File([bytes], 'image.png', { type: 'image/png' }))
          document.body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
        }, pngB64)
        for (const [i, id, after] of [[1, 'monthly-tx', 'tech-errors'], [2, 'tech-errors', 'daily-errors'], [3, 'daily-errors', '']]) {
          await windowPaste()
          await expect.poll(() => uploads.length, { timeout: 15_000 }).toBe(i)
          await expect(slot(id)).toHaveAttribute('data-has-image', 'true')
          await expect(next).toHaveAttribute('data-next', after)
          expect(uploadBodies[uploadBodies.length - 1].toString('latin1')).toMatch(new RegExp(`filename="${id}-pano-\\d{8}-\\d{6}\\.png"`))
        }
        await expect(next).toHaveText('Boş yuva kalmadı — bütün yuvalar dolu.')
        // Dolu yuvaya ASLA yüklenmez
        await windowPaste()
        await expect(seq.locator('[data-slot="te-seqfill-msg"]')).toContainText('boş yuva kalmadı')
        await page.waitForTimeout(300)
        expect(uploads).toHaveLength(3)
        expect(await page.evaluate(clipped, '[data-slot="te-seqfill"]'), 'kırpılan metin (sıralı doldur)').toEqual([])
        if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="te-seqfill"]'), 'küçük hedef (sıralı doldur)').toEqual([])
        await page.screenshot({ path: `test-results/te-c-seqfill-${vp.name}.png` })
      }

      // ── C5 — Excel'den tabloya: odaklı hücreden satır satır; geçersiz değer yazımdaki hatayı verir; dışarıdakiler sayılır ─
      const pasteText = (sel, text) => page.locator(sel).evaluate((el, x) => {
        el.focus()
        const dt = new DataTransfer()
        dt.setData('text/plain', x)
        el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
      }, text)
      await pasteText('#te-c-sla-total-functions', '12\t1.250.000\r\n7\tabc\r\n')
      await expect(page.locator('#te-c-sla-total-functions')).toHaveValue('12')
      await expect(page.locator('#te-c-sla-total-success-tx')).toHaveValue('1.250.000')
      await expect(page.locator('#te-c-sla-sla-critical-functions')).toHaveValue('7')
      await expect(page.locator('#te-c-sla-sla-critical-success-tx')).toHaveValue('abc')
      await expect(page.locator('#te-c-sla-sla-critical-success-tx')).toHaveAttribute('aria-invalid', 'true')
      const tnote = editor.locator('[data-section="sla"] [data-slot="te-table-paste-note"]')
      await expect(tnote).toHaveText('4 hücre yapıştırıldı.')
      await pasteText('#te-c-sla-out-of-sla-volume-share', '1\t2\n3\n')
      await expect(page.locator('#te-c-sla-out-of-sla-volume-share')).toHaveValue('1')
      await expect(tnote).toHaveText('1 hücre yapıştırıldı. 2 değer tablonun dışında kaldı ve alınmadı.')
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (tablo yapıştırma)').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-section="sla"]'), 'kırpılan metin (tablo yapıştırma)').toEqual([])

      // ── C1 — "Geçen aydan getir": boş metin / not ve seçilen konu taslağa; sayı, tarih, görsel gelmez ──────────────────
      await page.getByRole('button', { name: 'Geçen aydan getir' }).click()
      const carry = page.locator('[data-slot="te-carry"]')
      await expect(carry).toHaveAttribute('data-state', 'ok')
      expect(prevReads, 'aynı düzenleyicide geçen ayın okuması paylaşılır').toBeLessThanOrEqual(2)
      const carryDlg = page.getByRole('dialog', { name: 'Geçen aydan getir' })
      await expect(carryDlg.getByRole('checkbox', { name: 'Grafikler hakkında' })).toBeChecked()
      await expect(carryDlg.getByRole('checkbox', { name: 'Açıklama' })).toBeChecked()
      const topicBox = carryDlg.getByRole('checkbox', { name: `${LONG_TOPIC} Konusu ile yaşanan problem` })
      await expect(topicBox).not.toBeChecked()
      await settle(carryDlg)
      const cb = await carryDlg.boundingBox()
      expect(cb.x, 'getir penceresi solda taşmaz').toBeGreaterThanOrEqual(-1)
      expect(cb.x + cb.width, 'getir penceresi sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="te-carry"]'), 'kırpılan metin (geçen aydan getir)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="dialog"]'), 'küçük hedef (geçen aydan getir)').toEqual([])
      await page.screenshot({ path: `test-results/te-c-carry-${vp.name}.png` })
      await topicBox.click()
      await carryDlg.getByRole('button', { name: 'Getir (3)' }).click()
      await expect(carryDlg).toBeHidden()
      await expect(page.locator('#te-t-trend-note')).toHaveValue(PREV_TEXT)
      await expect(editor.locator('[data-field="values.unmapped-note"] textarea')).toHaveValue(PREV_NOTE)
      // Sayı getirilmedi (bu ayın boş toplam işlem adedi boş kaldı)
      await expect(editor.locator('[data-section="summary"]').getByLabel('Toplam işlem adedi', { exact: true })).toHaveValue('')
      const cards = editor.locator('[data-slot="te-issue"]')
      await expect(cards).toHaveCount(2)
      const added = cards.nth(1)
      await expect(added).not.toHaveAttribute('data-issue', 'p-1')
      await expect(added.getByLabel('Konu', { exact: true })).toHaveValue(LONG_TOPIC)
      await expect(page.getByText(/Ağustos 2026 raporundan getirildi: 2 metin \/ not, 1 konu\./)).toBeVisible()
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), 'yatay taşma (getirildikten sonra)').toBeLessThanOrEqual(1)
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
