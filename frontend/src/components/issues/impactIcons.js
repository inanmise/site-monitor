import { LogIn, FileX, Snail, ListX, SaveOff, BellOff, BellRing, Lock, Smartphone, FileDown, Sparkles, Ellipsis } from 'lucide-react'

/**
 * "Ne yaşıyorsunuz?" etki kodlarının ikonları (2026-09-28) — bildirim formundaki seçim çipleri ile Sorun Bildirimleri
 * listesi/ayrıntısındaki rozetler AYNI ikonu kullanır. Kodlar: issuesModel.IMPACTS.
 */
export const IMPACT_ICONS = {
  LOGIN: LogIn,
  PAGE_NOT_LOADING: FileX,
  SLOW: Snail,
  WRONG_DATA: ListX,
  SAVE_ERROR: SaveOff,
  NO_ALERTS: BellOff,
  FALSE_ALERTS: BellRing,
  ACCESS: Lock,
  MOBILE: Smartphone,
  REPORT_EXPORT: FileDown,
  FEATURE_REQUEST: Sparkles,
  OTHER: Ellipsis,
}
