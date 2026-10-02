import UserEditor from './user-editor/UserEditor.jsx'

/**
 * Takım üye kartlarından (TeamManager / TeamMembersModal kalemi) açılan kullanıcı düzenleme girişi — 2026-10-02'den beri
 * paylaşılan `user-editor/UserEditor`ın İNCE sarmalayıcısı (kullanıcı isteği: Kullanıcılar sekmesiyle TEK düzenleyici).
 * Dış sözleşme korunur: `user` yoksa hiçbir şey çizilmez; `readOnly` → görüntüleme kipi ("Düzenle" → `onEdit`, yoksa
 * yetkiliye aynı pencerede düzenleme); `onSaved(res.data)` ve `onClose` eskisi gibi. Ek bağlam (isteğe bağlı):
 * `viewerRole` (vars. ADMIN — eski pencere yönetici gibi davranırdı), `globalAdmin`, `ownTeamId`, `currentUsername`,
 * `activeAdminCount`, `onChanged` (anında uygulanan eylemler sonrası), `onOpenDirectory`.
 *
 * <p>Takımlar penceresinin (ModalShell) ÜSTÜNDE açılır: ModalShell body'ye portal'lar, sonra eklenen kabuk aynı
 * katmanda kazanır; Escape yalnız en üstteki katmanı kapatır (Radix katman yığını).
 */
export default function UserEditModal({ user, readOnly = false, ...rest }) {
  if (!user) return null
  return <UserEditor key={user.id} mode={readOnly ? 'view' : 'edit'} user={user} {...rest} />
}
