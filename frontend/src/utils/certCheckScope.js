import { rowGrantAccess } from '../hooks/useMonitorTeamPick.js'

/**
 * Sertifika "Şimdi kontrol et" kapısı (kart, Uyarılar satırı, pencerenin Çalıştır / Sağlık yenile / Tanıla düğmeleri,
 * toplu koşu) — sunucunun `/check/{domain}` kuralının (`CertificateController.requireViewableDomain`) istemci aynası
 * (2026-10-22, hata avı FE-1). Sunucu şunlardan BİRİNİ kabul eder:
 *   - SY takımı ya da UG takımı çağıranın GÖRÜŞ kapsamında (`SessionScope.canView`: global görüntüleyici hepsi; USER üye
 *     olduğu takımlar; TEAM_ADMIN yönettiği + üyesi olduğu; müdür kendi + astlarının takımları),
 *   - SY ya da UG takımında erişim paylaşımıyla EXECUTE+ düzey.
 * 7/24 operatörlüğü bu kapıyı GENİŞLETMEZ (operatör tüm kartları görür ama yalnız kendi kapsamındakini çalıştırır).
 *
 * Satır `owner_team_ids` (SY + UG) taşıyorsa kural birebir uygulanır. Taşımıyorsa (eski sunucu, pano dışı kaynaktan açılan
 * pencere) UG takımı bilinmez → 20.165.0 kuralı: paylaşımla görülen kartta EXECUTE düzeyi, aksi hâlde düğme görünür ve
 * sunucu karar verir. Belirsizlikte düğme ASLA gizlenmez (sunucunun kabul edeceği bir eylem saklanmaz).
 *
 * @param {object} cert pano satırı (`team_id`, isteğe bağlı `owner_team_ids`)
 * @param {object} ctx `{ globalViewer, viewTeamIds (/me view_team_ids; null = bilinmiyor), memberTeamIds, manageTeamIds
 *                       (null = bilinmiyor), systemRole, grantLevels ({teamId: level}) }`
 */
export function canCheckCertRow(cert, ctx = {}) {
  const { globalViewer = false, memberTeamIds = [], grantLevels = {} } = ctx
  if (globalViewer) return true
  const owners = Array.isArray(cert?.owner_team_ids) ? cert.owner_team_ids.filter((id) => id != null) : null
  if (owners) return owners.some((id) => inCheckScope(id, ctx) !== false || EXEC_LEVELS.has(grantLevels[String(id)]))
  const ga = rowGrantAccess(cert?.team_id, { memberTeamIds, grantLevels, globalViewer })
  if (ga) return ga.check
  return true
}

const EXEC_LEVELS = new Set(['EXECUTE', 'EDIT', 'DELETE'])
const same = (a, b) => a != null && b != null && String(a) === String(b)

/**
 * Takım çağıranın görüş kapsamında mı (`SessionScope.canView`): true / false; bilinemiyorsa null.
 * `/me.view_team_ids` varsa o (oturumun kendisi). Yoksa (eski sunucu) türetilir — görüş kapsamı her rolde üyelik ∪ yönetim
 * kapsamıdır (UserService.computeViewTeamIds / computeManageTeamIds); yönetim kapsamı da bilinmiyorsa yönetici rollerinde null.
 */
export function inCheckScope(teamId, { viewTeamIds = null, memberTeamIds = [], manageTeamIds = null, systemRole = 'USER' } = {}) {
  if (teamId == null) return false
  if (Array.isArray(viewTeamIds)) return viewTeamIds.some((id) => same(id, teamId))
  if ((memberTeamIds || []).some((id) => same(id, teamId))) return true
  if (Array.isArray(manageTeamIds)) return manageTeamIds.some((id) => same(id, teamId))
  return systemRole === 'USER' ? false : null
}
