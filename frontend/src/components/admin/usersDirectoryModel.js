// Kullanıcılar dizini (2026-11-12) — saf, test edilebilir yardımcılar: UI süzgeç durumu → API parametreleri, sıralama
// geçişi, etkin-süzgeç kontrolü. UserManager bu modülü kullanır; sunucu tarafı eşlemesi AdminController.searchUsers ile
// birebir (findDirectory). Burada setter/JSX YOK — çipler bileşende kalır (setter gerektirir).

export const SEARCH_FIELDS = ['all', 'name', 'username', 'email', 'phone', 'employee']
export const STATUS_VALUES = ['active', 'passive', 'locked', 'mustchange']
export const SOURCE_VALUES = ['LOCAL', 'LDAP']
export const TRISTATE_VALUES = ['yes', 'no']           // iletişim / NOC / çevrimiçi süzgeçleri
export const SORT_FIELDS = ['username', 'lastLogin', 'created', 'role', 'status']

// Sütun başlığına tıklayınca varsayılan yön: isim/rol artan; durum/son-giriş/oluşturulma azalan (en ilgili önce).
const DEFAULT_DIR = { username: 'asc', role: 'asc', status: 'desc', lastLogin: 'desc', created: 'desc' }

/** Başlık tıklaması → yeni { sort, dir }: aynı alan ise yön döner, değilse o alanın varsayılan yönü. */
export function nextSort(cur, field) {
  const s = cur || {}
  if (s.sort === field) return { sort: field, dir: s.dir === 'asc' ? 'desc' : 'asc' }
  return { sort: field, dir: DEFAULT_DIR[field] || 'asc' }
}

/** Yalnızca desteklenen sıralama alanını kabul et (bilinmeyen → 'username'). */
export function safeSort(field) {
  return SORT_FIELDS.includes(field) ? field : 'username'
}

/** UI süzgeç durumu → API parametre nesnesi (page/size çağıran tarafında eklenir). Varsayılanlar atlanır (temiz URL):
 *  field 'all', sort 'username' + dir 'asc', boş/false değerler api/client.searchUsers tarafından zaten düşürülür. */
export function buildSearchParams(f = {}) {
  const sortField = safeSort(f.sort || 'username')
  const dir = f.dir === 'desc' ? 'desc' : 'asc'
  const isDefaultSort = sortField === 'username' && dir === 'asc'
  return {
    q: (f.q || '').trim(),
    field: f.field && f.field !== 'all' && SEARCH_FIELDS.includes(f.field) ? f.field : '',
    systemRole: f.role || '',
    orgRole: f.orgRole || '',
    teamId: f.team || '',
    dormantDays: f.dormant && f.dormant !== 'never' ? Number(f.dormant) : '',
    neverLoggedIn: f.dormant === 'never',
    status: STATUS_VALUES.includes(f.status) ? f.status : '',
    source: SOURCE_VALUES.includes(f.source) ? f.source : '',
    hasPhone: TRISTATE_VALUES.includes(f.phone) ? f.phone : '',
    hasEmail: TRISTATE_VALUES.includes(f.email) ? f.email : '',
    codeLoginReady: !!f.otp,   // tel-üzeri ad kimlik-benzeri olmasın (OTel sorgu-gizleme kapısı)
    noc: TRISTATE_VALUES.includes(f.noc) ? f.noc : '',
    online: TRISTATE_VALUES.includes(f.online) ? f.online : '',
    loginFrom: f.loginFrom || '',
    loginTo: f.loginTo || '',
    createdFrom: f.createdFrom || '',
    createdTo: f.createdTo || '',
    // username+desc'i sunucuya iletebilmek için sort'u gönder; aksi hâlde varsayılanı atla
    sort: isDefaultSort ? '' : sortField,
    dir: dir === 'desc' ? 'desc' : '',
  }
}

/** Panel/çip "etkin süzgeç" kontrolü — serbest metin (q) ve alan seçimi SAYILMAZ (bunlar arama modifikasyonu). */
export function hasActiveDirectoryFilter(f = {}) {
  return !!(f.role || f.orgRole || f.team || f.dormant || f.status || f.source ||
    f.phone || f.email || f.otp || f.noc || f.online ||
    f.loginFrom || f.loginTo || f.createdFrom || f.createdTo)
}

/** Boş süzgeç durumu (clearFilters için) — q ve field dahil sıfırlanır; sıralama varsayılana döner. */
export const EMPTY_FILTERS = {
  q: '', field: 'all', role: '', orgRole: '', team: '', dormant: '',
  status: '', source: '', phone: '', email: '', otp: false, noc: '', online: '',
  loginFrom: '', loginTo: '', createdFrom: '', createdTo: '', sort: 'username', dir: 'asc',
}

// ── Kayıtlı görünümler (2026-11-12, B7) — görünüm = süzgeç durumunun kararlı anlık görüntüsü (string/bool); SavedViewsMenu
//    bunu kişisel tercihlerde (savedViews.admin-users) saklar ve onApply ile geri yükler. Geçici durum (onlineIds vb.) girmez.
const VIEW_KEYS = Object.keys(EMPTY_FILTERS)

/**
 * Geçerli süzgeç durumundan saklanabilir görünüm anlık görüntüsü (yalnız tanımlı süzgeç anahtarları). Sunucu boş değeri
 * saklamaz ve her değeri METNE çevirir (UserPreferencesService.normalizeParams): boş anahtar yazılmaz, `otp` yalnız açıkken
 * '1' olarak yazılır — yoksa kayıttan dönen `"false"` metni okurken açık sayılıyor ve "etkin görünüm" işareti hiç
 * eşleşmiyordu (2026-11-13 inceleme).
 */
export function viewFromFilters(f = {}) {
  const out = {}
  for (const k of VIEW_KEYS) {
    if (k === 'otp') { if (f.otp === true) out.otp = '1'; continue }
    const v = f[k] == null ? '' : String(f[k])
    if (v !== '') out[k] = v
  }
  return out
}

/** Saklanmış `otp` değeri: yeni biçim '1', eski kayıtlar boolean ya da sunucunun yazdığı "true"/"false" metni. */
function otpOn(x) {
  return x === true || x === 1 || x === '1' || x === 'true'
}

/** Saklanan görünüm → tam, DOĞRULANMIŞ süzgeç durumu (eksik/bozuk değerler güvenli varsayılana döner; bilinmeyen anahtar atlanır). */
export function filtersFromView(v = {}) {
  const s = v && typeof v === 'object' ? v : {}
  const tri = (x) => (TRISTATE_VALUES.includes(x) ? x : '')
  const str = (x) => (typeof x === 'string' ? x : '')
  return {
    ...EMPTY_FILTERS,
    q: str(s.q),
    field: SEARCH_FIELDS.includes(s.field) ? s.field : 'all',
    role: str(s.role),
    orgRole: str(s.orgRole),
    team: str(s.team),
    dormant: str(s.dormant),
    status: STATUS_VALUES.includes(s.status) ? s.status : '',
    source: SOURCE_VALUES.includes(s.source) ? s.source : '',
    phone: tri(s.phone),
    email: tri(s.email),
    otp: otpOn(s.otp),
    noc: tri(s.noc),
    online: tri(s.online),
    loginFrom: str(s.loginFrom),
    loginTo: str(s.loginTo),
    createdFrom: str(s.createdFrom),
    createdTo: str(s.createdTo),
    sort: safeSort(s.sort),
    dir: s.dir === 'desc' ? 'desc' : 'asc',
  }
}
