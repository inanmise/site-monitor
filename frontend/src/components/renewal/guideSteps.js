import { ClipboardCheck, Download, FileBadge, Globe, Hourglass, KeyRound, ListChecks, Server, ShieldCheck, ShoppingCart } from 'lucide-react'
import { SAFE_LINK_SCHEMES, safeHref } from '../../utils/safeHref.js'

/**
 * Sertifika Değişim Rehberi — veri katmanı. Metinler i18n'de (`guide.step.<id>.*`, `guide.flow.<id>.*`,
 * `guide.cmd.<key>`, `guide.plat.<id>.note`); komutlar dile bağlı olmadığı için burada. Örnek adlar yer tutucudur
 * (`example.com`) — kurumsal ad/alan YOK.
 *
 * 2026-09-27 yeniden düzen (kullanıcı: "sertifikaları genelde DigiCert gibi CA'lardan SATIN alıyoruz; elle üretim
 * nadir, sayfanın ana konusu olmasın"): sayfa artık Kaynaklar → CA üzerinden alma akışı (CA_FLOW) → platform notları
 * (GUIDE_PLATFORMS) → Gelişmiş: kendi anahtar/CSR'ın (GUIDE_STEPS, katlanır, en altta) sırasıyla.
 *
 * GUIDE_STEPS adım alanları (Gelişmiş bölümün elle yolu):
 *  - id           kalıcı kimlik (tamamlandı işaretleri localStorage'da bu kimlikle tutulur — değiştirme)
 *  - icon         lucide ikon
 *  - commands     [{ key, code }] — key → `guide.cmd.<key>` başlığı
 *  - seePlatforms true → adım platform sekmelerini kendisi çizmez; "Platformunuza yükleme" bölümüne götürür
 *  - callouts     [{ tone, key }] — `guide.step.<id>.<key>Title` / `<key>Text` (ui/AlertBanner)
 *  - related      [{ tab, labelKey }] — ilgili ekrana geçiş (utils/navigate)
 */
export const GUIDE_STEPS = [
  {
    id: 'scope', icon: ListChecks,
    commands: [
      { key: 'inspect', code: 'openssl s_client -connect example.com:443 -servername example.com </dev/null 2>/dev/null | openssl x509 -noout -subject -issuer -dates -ext subjectAltName' },
    ],
    related: [{ tab: 'renewal', labelKey: 'nav.renewal' }, { tab: 'forecast', labelKey: 'nav.forecast' }],
  },
  {
    id: 'csr', icon: KeyRound,
    commands: [
      { key: 'csr', code: 'openssl req -new -newkey rsa:2048 -nodes -keyout example.com.key -out example.com.csr -subj "/CN=example.com" -addext "subjectAltName=DNS:example.com,DNS:www.example.com"' },
    ],
    callouts: [{ tone: 'warning', key: 'warn' }],
  },
  {
    id: 'issue', icon: FileBadge,
    commands: [
      { key: 'match', code: 'openssl x509 -noout -pubkey -in example.com.crt | openssl sha256\nopenssl pkey -pubout -in example.com.key | openssl sha256' },
      { key: 'chain', code: 'cat example.com.crt intermediate.crt > fullchain.pem' },
    ],
    callouts: [{ tone: 'info', key: 'tip' }],
  },
  {
    // Platform sekmeleri artık "Platformunuza yükleme" bölümünde (GUIDE_PLATFORMS) — burada o bölüme geçiş düğmesi
    id: 'deploy', icon: Server, seePlatforms: true,
    callouts: [{ tone: 'warning', key: 'warn' }],
    related: [{ tab: 'maintenance', labelKey: 'nav.maintenance' }],
  },
  {
    id: 'verify', icon: ShieldCheck,
    commands: [
      { key: 'verify', code: 'openssl s_client -connect example.com:443 -servername example.com </dev/null 2>/dev/null | openssl x509 -noout -issuer -enddate' },
      { key: 'verifyChain', code: 'openssl s_client -connect example.com:443 -servername example.com -verify_return_error </dev/null' },
    ],
    related: [{ tab: 'all', labelKey: 'nav.all' }, { tab: 'renewal', labelKey: 'nav.renewal' }],
  },
  {
    id: 'close', icon: ClipboardCheck,
    callouts: [{ tone: 'info', key: 'tip' }],
    related: [{ tab: 'domains', labelKey: 'nav.domains' }],
  },
]

/**
 * Platform kurulum notları — "Platformunuza yükleme" bölümünün sekmeleri (label ürün adıdır, çevrilmez; not
 * `guide.plat.<id>.note`). `match`: bu platforma ait kaynak kategorilerini bulur (sekmede "NetScaler kaynakları (2)"
 * düğmesi); kategori adları yöneticinin serbest metnidir, bu yüzden gevşek eşleşme.
 */
export const GUIDE_PLATFORMS = [
  { id: 'netscaler', label: 'NetScaler', match: /netscaler|citrix/i, commands: [
    { key: 'nsUpdate', code: 'update ssl certKey example.com -cert example.com.crt -key example.com.key' },
    { key: 'nsLink', code: 'link ssl certKey example.com intermediate-ca\nsave ns config' },
  ] },
  { id: 'iis', label: 'IIS (Windows)', match: /(^|[^a-z])iis([^a-z]|$)|windows/i, commands: [
    { key: 'pfx', code: 'openssl pkcs12 -export -out example.com.pfx -inkey example.com.key -in example.com.crt -certfile intermediate.crt' },
    { key: 'pfxImport', code: 'Import-PfxCertificate -FilePath .\\example.com.pfx -CertStoreLocation Cert:\\LocalMachine\\My -Password (Read-Host -AsSecureString)' },
  ] },
  { id: 'k8s', label: 'Kubernetes / OpenShift', match: /kubernetes|openshift|k8s|ingress/i, commands: [
    { key: 'k8sSecret', code: 'kubectl create secret tls example-tls --cert=fullchain.pem --key=example.com.key -n example --dry-run=client -o yaml | kubectl apply -f -' },
    { key: 'ocSecret', code: 'oc create secret tls example-tls --cert=fullchain.pem --key=example.com.key -n example --dry-run=client -o yaml | oc apply -f -' },
  ] },
  { id: 'linux', label: 'Nginx / Apache', match: /nginx|apache|linux/i, commands: [
    { key: 'nginx', code: 'sudo nginx -t && sudo systemctl reload nginx' },
    { key: 'apache', code: 'sudo apachectl configtest && sudo systemctl reload apache2' },
  ] },
]

export const GUIDE_PLATFORM_IDS = GUIDE_PLATFORMS.map((p) => p.id)

/**
 * CA üzerinden satın alma / yenileme — sayfanın kısa akışı (kısa satırlar, uzun metin YOK). Metin
 * `guide.flow.<id>.title` / `.text` (+ `hint: true` ise `.hint`, ui/HintPopover). `go`: adımın bağlandığı yer —
 * 'ca' (Kaynaklar'da CA portalı kategorisi), 'platforms' (platform notları bölümü), 'verify' (Tüm Sertifikalar +
 * Yenileme Önerileri).
 */
export const CA_FLOW = [
  { id: 'order', icon: ShoppingCart, hint: true, go: 'ca' },
  { id: 'dcv', icon: Globe, hint: true },
  { id: 'issue', icon: Hourglass, hint: true },
  { id: 'download', icon: Download, hint: true },
  { id: 'install', icon: Server, go: 'platforms' },
  { id: 'verify', icon: ShieldCheck, go: 'verify' },
]

/**
 * Kaynak kategorisinin türü — sıralama için: CA portalları ÖNCE, sonra platformlar (ve diğerleri), araçlar SONDA.
 * Kategori adı serbest metin (TR/EN, büyük/küçük harf karışık) → gevşek eşleşme; "ca" yalnız harf dışı sınırla
 * ("Local" / "Araçlar" eşleşmez).
 * @returns {'ca'|'platform'|'tools'}
 */
const CA_CATEGORY_RE = /(^|[^\p{L}])(ca|pki)([^\p{L}]|$)|certificate authorit|sertifika otorite|digicert|sectigo|globalsign|entrust|geotrust|thawte/iu
const TOOLS_CATEGORY_RE = /araç|arac|tool|yardımcı|yardimci|utilit|diğer|diger|other|misc/iu
export function categoryKind(name) {
  const s = String(name || '')
  if (CA_CATEGORY_RE.test(s)) return 'ca'
  if (TOOLS_CATEGORY_RE.test(s)) return 'tools'
  return 'platform'
}

const KIND_RANK = { ca: 0, platform: 1, tools: 2 }

/**
 * Bağlantıları kategoriye gruplar; kategori sırası CA → platform → araçlar, aynı türde sunucu sırası (sort_order)
 * korunur (kararlı sıralama). Kimlik (`guide-cat-<i>`) yeni sıradaki konumdur — eski çapa biçimi korunur.
 * @returns {{ id: string, name: string, kind: string, items: object[], count: number }[]}
 */
export function groupLinks(links) {
  const order = []; const map = new Map()
  for (const link of links || []) {
    const cat = String(link.category || '').trim() || '—'
    if (!map.has(cat)) { map.set(cat, []); order.push(cat) }
    map.get(cat).push(link)
  }
  return order
    .map((name, i) => ({ name, i, kind: categoryKind(name) }))
    .sort((a, b) => (KIND_RANK[a.kind] - KIND_RANK[b.kind]) || (a.i - b.i))
    .map((c, i) => ({ id: `guide-cat-${i}`, name: c.name, kind: c.kind, items: map.get(c.name), count: map.get(c.name).length }))
}

/** localStorage anahtarları — yalnız bu tarayıcının kolaylığı (paylaşılmaz, güvenilir durum değil). */
export const CHECKLIST_KEY = 'renewal-guide-checklist'
export const PLATFORM_KEY = 'renewal-guide-platform'

export function readChecklist() {
  try {
    const raw = JSON.parse(localStorage.getItem(CHECKLIST_KEY) || '[]')
    const ids = new Set(GUIDE_STEPS.map((s) => s.id))
    return Array.isArray(raw) ? raw.filter((x) => ids.has(x)) : []
  } catch { return [] }
}

export function writeChecklist(list) {
  try { localStorage.setItem(CHECKLIST_KEY, JSON.stringify(list)) } catch { /* depolama yok: işaretler bu oturumda kalır */ }
}

export function readPlatform() {
  try { const v = localStorage.getItem(PLATFORM_KEY); return GUIDE_PLATFORM_IDS.includes(v) ? v : GUIDE_PLATFORM_IDS[0] } catch { return GUIDE_PLATFORM_IDS[0] }
}

export function writePlatform(v) {
  try { localStorage.setItem(PLATFORM_KEY, v) } catch { /* yoksay */ }
}

/**
 * Bağlantı adresini tıklanabilir hâle getirir: şemalı adres olduğu gibi, `//x` → https, UNC yolu (`\\sunucu\paylaşım`)
 * → `file://sunucu/paylaşım`, çıplak ad → https. UNC tarayıcıda çoğu zaman açılmaz; kartta ayrıca kopyala düğmesi var.
 */
export function normalizeUrl(raw) {
  if (!raw) return '#'
  const s = String(raw).trim()
  if (isUncPath(s)) return 'file:' + s.replace(/\\/g, '/')
  if (/^[a-z][a-z0-9+.-]*:/i.test(s)) return s   // şemalı (http, https, mailto, ftp, file, …)
  if (s.startsWith('//')) return 'https:' + s     // şemasız
  return 'https://' + s.replace(/^\/+/, '')
}

export const isUncPath = (s) => /^\\\\[^\\]/.test(String(s || '').trim())

/**
 * Kartın TIKLANABİLİR adresi — şema beyaz listesiyle (2026-09-27 regresyon BD1). `normalizeUrl` şemalı HER değeri
 * aynen döndürür; `javascript:` / `data:` / `vbscript:` bu yüzden href'e yazılıyordu. İzinli: http, https, mailto ve
 * `file:` (UNC ağ klasörü kartları bilinçli olarak bu biçimde çizilir; betik çalıştıramaz). Başka şema → null: kart
 * başlığı düz metin kalır, adres kopyala düğmesinde durur.
 */
export const GUIDE_LINK_SCHEMES = [...SAFE_LINK_SCHEMES, 'file:']
export function guideHref(raw) {
  return safeHref(normalizeUrl(raw), GUIDE_LINK_SCHEMES)
}

/**
 * Bağlantı türü + hedef (önizleme satırı için): web (http/https), unc (ağ yolu ya da file:), mail (mailto:), other.
 * @returns {{ kind: 'web'|'unc'|'mail'|'other', host: string }}
 */
export function linkKind(raw) {
  const s = String(raw || '').trim()
  if (!s) return { kind: 'other', host: '' }
  if (isUncPath(s)) return { kind: 'unc', host: s.slice(2).split('\\')[0] }
  try {
    const u = new URL(normalizeUrl(s))
    if (u.protocol === 'http:' || u.protocol === 'https:') return { kind: 'web', host: u.hostname }
    if (u.protocol === 'mailto:') return { kind: 'mail', host: u.pathname }
    if (u.protocol === 'file:') return { kind: 'unc', host: u.hostname }
    return { kind: 'other', host: u.hostname || u.protocol.replace(/:$/, '') }
  } catch { return { kind: 'other', host: '' } }
}

/**
 * Kartta gösterilen kısa adres: web → `ana-bilgisayar/yol` (şema ve sondaki `/` yok), mailto → adres, ağ yolu ve
 * diğerleri olduğu gibi. Tam adres kopyala düğmesinde ve `title`'da kalır.
 */
export function displayUrl(raw) {
  const s = String(raw || '').trim()
  if (!s || isUncPath(s)) return s
  try {
    const u = new URL(normalizeUrl(s))
    if (u.protocol === 'http:' || u.protocol === 'https:') {
      const shown = (u.host + u.pathname + u.search + u.hash).replace(/\/$/, '')
      try { return decodeURI(shown) } catch { return shown }   // URL yolu yüzde-kodlar (ü → %C3%BC); okunur hâli göster
    }
    if (u.protocol === 'mailto:') return decodeURIComponent(u.pathname)
  } catch { /* olduğu gibi */ }
  return s
}

/**
 * Kaynak formu doğrulaması — alan → hata kodu (bileşen i18n'e çevirir). Adres: boşluk içeremez; UNC yolu ya da
 * `new URL(normalizeUrl(s))` ile çözülen ve (http/https ise) noktalı/localhost ana bilgisayarı olan bir adres.
 * @returns {{ title?: 'required', url?: 'required'|'invalid', category?: 'required' }}
 */
export function validateLinkForm({ title, url, category }) {
  const errors = {}
  if (!String(title || '').trim()) errors.title = 'required'
  if (!String(category || '').trim()) errors.category = 'required'
  const s = String(url || '').trim()
  if (!s) errors.url = 'required'
  else if (/\s/.test(s)) errors.url = 'invalid'
  else if (!isUncPath(s)) {
    try {
      const u = new URL(normalizeUrl(s))
      const web = u.protocol === 'http:' || u.protocol === 'https:'
      if (web && !(u.hostname.includes('.') || u.hostname === 'localhost')) errors.url = 'invalid'
    } catch { errors.url = 'invalid' }
  }
  return errors
}
