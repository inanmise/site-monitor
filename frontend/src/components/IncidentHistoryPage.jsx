/**
 * SRE Olay & Hata Geçmişi — ince giriş noktası. App.jsx bu yolu tembel yükler (`lazy(() => import('./components/IncidentHistoryPage'))`);
 * sayfanın kendisi ve parçaları `components/incidenthistory/` altında (2026-09-28 shadcn yeniden tasarımı):
 * model (URL/süzgeç/doğrulama), başlık + KPI, süzgeç araç çubuğu (telefonda Sheet), liste (tablo | kart), ayrıntı Sheet'i,
 * beş bölümlü form penceresi ve günlük trend.
 */
export { default } from './incidenthistory/IncidentHistoryPage.jsx'
