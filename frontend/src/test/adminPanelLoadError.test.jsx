import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatTime:     (s) => s ?? '',
  api: withApiFallback({}),
}))
// LoginIssueReports izin kapisi arkasinda; izinsizken "erisim yok" ekrani cikar ve
// yukleme yolu hic calismaz. Kapiyi acik tutuyoruz ki test asil sozlesmeyi olcsun.
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ refresh: vi.fn(), canView: () => true, canEdit: () => true, canExecute: () => true }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'

import AlertThresholds from '../components/admin/AlertThresholds.jsx'
import BrandingSettings from '../components/admin/BrandingSettings.jsx'
import CertInventoryReportSettings from '../components/admin/CertInventoryReportSettings.jsx'
import EscalationContacts from '../components/admin/EscalationContacts.jsx'
import GeneralSettings from '../components/admin/GeneralSettings.jsx'
import LdapSettings from '../components/admin/LdapSettings.jsx'
import LoginIssueReports from '../components/admin/LoginIssueReports.jsx'
import RetentionSettings from '../components/admin/RetentionSettings.jsx'
import SmtpSettings from '../components/admin/SmtpSettings.jsx'
import TeamManager from '../components/admin/TeamManager.jsx'
import WeeklyAvailabilitySettings from '../components/admin/WeeklyAvailabilitySettings.jsx'

/**
 * YONETIM PANELLERINDE YUKLEME HATASI GORUNUR OLMALI.
 *
 * Denetimde 10 IZLEME sayfasinin `load` fonksiyonu try/catch'e alinmisti (E2/E3), ama ayni
 * duzeltme YONETIM panellerine tasinmamisti: 11 panelin `load`'u korumasizdi. Iki ayri
 * basarisizlik kipi uretiyordu ve ikisi de SESSIZDI:
 *
 *   1. `useState(null)` + `if (!x) return <Spinner>` olan 8 panelde  -> spinner SONSUZA kadar
 *      doner. api/client.js request() ag hatasinda {success:false} DONDURMEZ, throw eder;
 *      try/catch yokken promise reject olur ve hicbir durum guncellenmez.
 *   2. `useState([])` olan 3 panelde (AlertThresholds / EscalationContacts / TeamManager)
 *      -> YALANCI BOS DURUM. Bu ucunde `else` dali da yoktu, yani temiz bir 403 yaniti bile
 *      yutuluyordu. En agiri TeamManager: takimlar tum yetkilendirmenin temeli
 *      (viewTeamIds/manageTeamIds); "hic takim yok" goren yonetici silinmis sanip yeniden
 *      olusturursa uyelikler ve takim kapsamli alarmlar ikiye bolunur.
 *
 * Dogru kalip depoda ZATEN vardi: SystemHealth.jsx `Promise.allSettled` + `loadErrors`
 * kullaniyor (bkz. SystemHealthLoadError.test.jsx). Bu kapi ayni sozlesmeyi geri kalan
 * 11 panele tasir ve YENI eklenen bir panel korumasiz gelirse kirilir.
 *
 * Iki dal da olculuyor: REJECT (ag hatasi) ve {success:false} (403/500 gibi temiz hata).
 * Ikincisi onemli, cunku uc panelde o dal hic yazilmamisti.
 */
const PANELS = [
  { name: 'AlertThresholds',             Comp: AlertThresholds,             loader: 'getThresholds' },
  { name: 'BrandingSettings',            Comp: BrandingSettings,            loader: 'getBrandingSettings' },
  { name: 'CertInventoryReportSettings', Comp: CertInventoryReportSettings, loader: 'getCertInvReportStatus' },
  { name: 'EscalationContacts',          Comp: EscalationContacts,          loader: 'getContacts' },
  { name: 'GeneralSettings',             Comp: GeneralSettings,             loader: 'getGeneralSettings' },
  { name: 'LdapSettings',                Comp: LdapSettings,                loader: 'getLdapSettings' },
  { name: 'LoginIssueReports',           Comp: LoginIssueReports,           loader: 'getLoginIssues' },
  { name: 'RetentionSettings',           Comp: RetentionSettings,           loader: 'getRetentionOverview' },
  { name: 'SmtpSettings',                Comp: SmtpSettings,                loader: 'getSmtpSettings' },
  { name: 'TeamManager',                 Comp: TeamManager,                 loader: 'getTeams' },
  { name: 'WeeklyAvailabilitySettings',  Comp: WeeklyAvailabilitySettings,  loader: 'getWeeklyAvailStatus' },
]

describe('yonetim panelleri: yukleme hatasi gorunur olmali', () => {
  beforeEach(() => { vi.clearAllMocks() })

  for (const { name, Comp, loader } of PANELS) {
    it(`${name}: AG HATASI (reject) hata bandi cizer, sessizce yutulmaz`, async () => {
      api.admin[loader].mockRejectedValue(new Error('network down'))

      render(<Comp systemRole="ADMIN" isAdmin />)

      await waitFor(() => {
        expect(screen.getByRole('alert'), `${name}: reject sonrasi hata bandi YOK`).toBeDefined()
      })
    })

    it(`${name}: TEMIZ HATA yaniti (success:false) da hata bandi cizer`, async () => {
      api.admin[loader].mockResolvedValue({ success: false, error: 'forbidden' })

      render(<Comp systemRole="ADMIN" isAdmin />)

      await waitFor(() => {
        expect(screen.getByRole('alert'), `${name}: success:false sonrasi hata bandi YOK`).toBeDefined()
      })
    })
  }

  it('POZITIF KONTROL: basarili yuklemede hata bandi CIKMAZ (bant her zaman gorunmuyor)', async () => {
    api.admin.getThresholds.mockResolvedValue({ success: true, data: [] })

    render(<AlertThresholds />)

    await waitFor(() => expect(api.admin.getThresholds).toHaveBeenCalled())
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
