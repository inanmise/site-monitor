import IssueReportsPage from './issues/IssueReportsPage.jsx'

/**
 * "Bildirimlerim" — kullanıcının kendi sorun bildirimleri (Sorun Bildirimleri sayfasının kullanıcı kitlesi).
 * Geriye dönük giriş noktası; ekran `components/issues/IssueReportsPage` (2026-09-27 yeniden tasarım).
 */
export default function MyIssueReports() {
  return <IssueReportsPage adminAudience={false} />
}
