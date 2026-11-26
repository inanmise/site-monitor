/**
 * Ekranda görünen ad (Nöbet Rutinleri iyimser işaretindeki "kim", 2026-11-26). App /me profilinden verir; giriş paketine
 * küçük kalsın diye ayrı modül (rutin modeli tembel parçada).
 */
export function displayNameOf(profile, username) {
  const full = String(profile?.display_name || [profile?.first_name, profile?.last_name].filter(Boolean).join(' ') || '').trim()
  return full || (username ? String(username) : null)
}
