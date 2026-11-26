/**
 * Envanter formunun "Beklenen parmak izi" alanı (H paketi 2026-11-26, sertifika B2). Kural sunucuyla BİREBİR
 * (AdminController.expectedFingerprintForUpdate + CertificateService.normalizeFingerprint):
 *   - karşılaştırma ve gönderim NORMALİZE değerle: `:` / boşluk / `-` / sekme atılır, büyük harf (en çok 256 karakter);
 *   - geçerli değer = 64 onaltılık karakter (SHA-256); ham metin en çok 512 karakter;
 *   - değişen değer normalize gider, boşaltılırsa `null` (temizle); düzenlemede değişmeyen değer saklı hâliyle +
 *     `expected_fingerprint_unchanged: true` gider (yeni sunucu yok sayar, eski pod aynısını yazar);
 * Kaydın eski (biçimi bozuk) değeri DEĞİŞMEDİKÇE doğrulanmaz: başka bir alan için düzenleme engellenmez.
 */
export const FP_RAW_MAX = 512
export const FP_HEX_LEN = 64

/** Sunucunun normalizeFingerprint'i: `:` ` ` `-` sekme atılır, büyük harf; 256 karakterde durur. Boş → ''. */
export function normalizeFingerprint(raw) {
  const s = raw == null ? '' : String(raw)
  let out = ''
  for (let i = 0; i < s.length && out.length < 256; i++) {
    const c = s[i]
    if (c === ':' || c === ' ' || c === '-' || c === '\t') continue
    out += c.toUpperCase()
  }
  return out
}

/** Biçim sorunu: null (geçerli ya da boş) | 'tooLong' | 'format'. Kenar boşlukları kırpılır (sunucu da kırpar). */
export function fingerprintProblem(raw) {
  const s = raw == null ? '' : String(raw).trim()
  if (!s) return null
  if (s.length > FP_RAW_MAX) return 'tooLong'
  const n = normalizeFingerprint(s)
  if (n.length !== FP_HEX_LEN) return 'format'
  for (let i = 0; i < n.length; i++) {
    const c = n.charCodeAt(i)
    const hex = (c >= 48 && c <= 57) || (c >= 65 && c <= 70)
    if (!hex) return 'format'
  }
  return null
}

/** Formdaki değer kayıttakinden (normalize karşılaştırma) farklı mı. Boş ↔ boş aynıdır. */
export function fingerprintChanged(formValue, storedValue) {
  return normalizeFingerprint(String(formValue ?? '').trim()) !== normalizeFingerprint(String(storedValue ?? '').trim())
}

/** Bayrakla gönderilen saklı değer: boş / yalnız boşluk → null, aksi HAM değer (normalize edilmez — eski pod aynısını yazar). */
const storedOrNull = (v) => (v == null || String(v).trim() === '' ? null : v)

/**
 * Kayıt gövdesine eklenecek parça. Çağıran önce {@link fingerprintProblem}'ı denetler.
 *   - DEĞİŞTİYSE: boşken `{ expected_fingerprint: null }` (temizle), doluyken normalize değer; bayrak YOK.
 *   - değişmediyse ve DÜZENLEMEYSE (`edit: true`): saklı HAM değer + `expected_fingerprint_unchanged: true` (koordinatör
 *     kararı, inceleme bulgusu 1). Yeni sunucu bayrakta değeri yok sayar; sürüm geçişinde eski pod anahtar yoksa null yazıp
 *     saklananı SİLERDİ — saklı değeri aynen alınca korur.
 *   - değişmediyse ve OLUŞTURMAYSA: `{}` (anahtar yok; bayrak oluşturmada gönderilmez).
 */
export function expectedFingerprintPatch(formValue, storedValue, { edit = false } = {}) {
  if (!fingerprintChanged(formValue, storedValue)) {
    return edit ? { expected_fingerprint: storedOrNull(storedValue), expected_fingerprint_unchanged: true } : {}
  }
  const s = String(formValue ?? '').trim()
  return { expected_fingerprint: s ? normalizeFingerprint(s) : null }
}

/**
 * UG takımı: form UG'yi DEĞİŞTİRMEZ (salt okunur satır). Düzenlemede saklı değer + `ug_team_id_unchanged: true` gider —
 * yeni sunucu bayrakta değeri yok sayar, eski pod aynı değeri yazar (UG korunur; eskiden form her kayıtta null gönderip
 * CSV ile atanmış UG'yi siliyordu). Oluşturmada hiçbir şey gitmez.
 */
export function ugTeamPatch(storedUgTeamId, { edit = false } = {}) {
  return edit ? { ug_team_id: storedUgTeamId ?? null, ug_team_id_unchanged: true } : {}
}
