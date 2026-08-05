/**
 * Rename storage göçü (CertMonitor → Site Monitor): eski localStorage/sessionStorage
 * anahtarlarını yeni adlara TEK SEFERLİK taşır — kullanıcı dil/tema/beni-hatırla/sayfa-boyutu
 * tercihlerini ve oturum bayrağını kaybetmesin.
 *
 * main.jsx'te createRoot().render()'dan ÖNCE senkron çağrılır: ThemeProvider/LangProvider
 * initializer'ları localStorage'ı render anında okur — göç geç kalırsa tercihler varsayılana döner.
 *
 * Idempotent: yeni anahtar zaten doluysa eski değer ÜZERİNE YAZILMAZ (yeni tercih kazanır);
 * eski anahtar her durumda silinir. Storage erişilemezse (Safari private vb.) sessizce atlanır.
 */

// Bire-bir taşınan localStorage anahtarları (eski → yeni)  // geriye-uyum: eski anahtar adları
const LOCAL_KEY_MAP = {
  'cert-monitor-remembered-user': 'site-monitor-remembered-user',
  'cert-monitor-lang': 'site-monitor-lang',
  'cert-monitor-theme': 'site-monitor-theme',
}

// Öneki taşınan localStorage anahtarları (dinamik son ek: cm.pageSize.<listKey> vb.)
const LOCAL_PREFIX_MAP = {
  'cm.pageSize.': 'sm.pageSize.',
  'cm.banner.': 'sm.banner.',
}

// sessionStorage anahtarları
const SESSION_KEY_MAP = {
  'cm.session.active': 'sm.session.active',
}

function moveKey(storage, oldKey, newKey) {
  const val = storage.getItem(oldKey)
  if (val === null) return
  if (storage.getItem(newKey) === null) storage.setItem(newKey, val)
  storage.removeItem(oldKey)
}

export function migrateStorageKeys() {
  try {
    for (const [oldKey, newKey] of Object.entries(LOCAL_KEY_MAP)) {
      moveKey(localStorage, oldKey, newKey)
    }
    // Önek göçü: anahtar listesi iterasyon sırasında değişir → önce topla, sonra taşı.
    const prefixed = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      for (const oldPrefix of Object.keys(LOCAL_PREFIX_MAP)) {
        if (k && k.startsWith(oldPrefix)) prefixed.push([k, oldPrefix])
      }
    }
    for (const [k, oldPrefix] of prefixed) {
      moveKey(localStorage, k, LOCAL_PREFIX_MAP[oldPrefix] + k.slice(oldPrefix.length))
    }
  } catch { /* storage erişilemez — göç atlanır */ }
  try {
    for (const [oldKey, newKey] of Object.entries(SESSION_KEY_MAP)) {
      moveKey(sessionStorage, oldKey, newKey)
    }
  } catch { /* sessionStorage erişilemez */ }
}
