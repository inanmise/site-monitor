import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import IssueReportsPage from '../issues/IssueReportsPage.jsx'

/**
 * Sorun Bildirimleri sekmesi (`?tab=login-issues`) — HERKESE açık (2026-09-26). Yönetici izni
 * (`issues.login-reports` görüntüle) varsa tam triyaj ekranı + "Bildirimlerim" görünümü; yoksa kullanıcının kendi
 * bildirimleri. Durum değiştirme `edit`, kalıcı silme AYRI ve hassas `issues.login-reports.purge` (execute) yetkisi
 * ister — yetkisi olmayana düğme hiç çizilmez. Ekranın tamamı `components/issues/*` (2026-09-27 yeniden tasarım).
 *
 * `scopedAdmin` (kapsamlı müdür — AD ADMIN, `global_admin=false`; 2026-09-28): ADMIN rolü matriste bu izni taşısa da
 * sunucu yönetici uçlarını `SessionScope.requireNotScopedAdmin` ile 403'e çeker (bildirimler sistem geneli, takımsız
 * kişisel veri). Ekran bu yüzden müdüre kullanıcı görünümünü açar — kendi bildirimleri tam çalışır.
 */
export default function LoginIssueReports({ scopedAdmin = false }) {
  const { canView, canEdit, canExecute } = usePermissions()
  const adminAudience = !scopedAdmin && canView('issues.login-reports')
  return (
    <IssueReportsPage adminAudience={adminAudience}
      canEdit={adminAudience && canEdit('issues.login-reports')}
      canPurge={adminAudience && canExecute('issues.login-reports.purge')} />
  )
}
