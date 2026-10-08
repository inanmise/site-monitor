/**
 * ModalShell `className`'ine eklenen ortak yerleşim sınıfları (2026-10-09).
 *
 * `PHONE_FULLSCREEN` — telefonda (< 640 px) pencere TAM EKRAN: köşe/kenar yok, 100dvh, sol üstten başlar,
 * kenar payı 16 px, alt boşluk güvenli alanı gözetir. Geniş ekranda (sm+) HİÇBİR şey değişmez (yalnız `max-sm:`).
 * Pencere yüksekliği etkin sekmeden bağımsız olur → sekme değişince telefonda zıplamaz.
 *
 * Önceden aynı dize beş dosyada elle kopyalanıyordu (IncidentFormModal, TeamMembersModal, UserEditor,
 * OverviewKpiDialog, issues/report/ReportParts); onlar bunu içe aktarır. `max-sm:h-dvh`/`max-w-full` kullanan
 * kardeş kopyalar (DiagnosticsModal, tanılama pencereleri, UploadWizard) ve yapışkan başlıklı
 * `DETAIL_PHONE_FULLSCREEN` (Alan adı detayı) bilinçli olarak ayrı kaldı — dize aynı değil.
 */
export const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-[100dvh] max-sm:max-h-none max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:p-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'
