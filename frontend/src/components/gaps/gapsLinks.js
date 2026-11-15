// Eksikler penceresi — veri kalitesi DIŞI eksiklerin ve bekleyen işlerin düzeltme / açma bağlantıları (2026-11-15). SAF.
// Veri kalitesi kurallarının bağlantıları dataQualityModel.linkFor'dadır; burada yalnız gapsExtra / gapsProfile kodları.
// Bağlantı = { tab, params } (navigateTo / applyTabView biçimi). Hedef sekmeler var olan URL anahtarlarını okur.

const str = (v) => (v == null ? null : String(v))

/** Kişisel push tercihleri kartı (Etkinliklerim) — kart `ma_sec=push` ile görünür kaydırılır. */
export const PUSH_PREFS_LINK = Object.freeze({ tab: 'myactivity', params: { ma_sec: 'push' } })

/** Olay aksiyonları — bana atanmış, gecikmiş (Olay & Hata Geçmişi → Aksiyonlar). */
export const MY_OVERDUE_ACTIONS_LINK = Object.freeze({ tab: 'incident-history', params: { ih_view: 'actions', ih_amine: '1', ih_aover: '1' } })

/** Kanal teknik hata raporu bağlantısı: rapor varsa rapor, yoksa kanal. */
export function channelLink(channelId, reportId = null) {
  if (channelId == null) return null
  return reportId != null
    ? { tab: 'channelreports', params: { te_view: 'report', te_ch: str(channelId), te_rep: str(reportId) } }
    : { tab: 'channelreports', params: { te_view: 'channel', te_ch: str(channelId) } }
}

/** Kalemin bağlantısı (yoksa null). `gap` eksiğin kendisi (takım kimliği / adı için). */
export function extraItemLink(gap, item) {
  if (!gap || !item) return null
  switch (gap.code) {
    case 'TEAM_GROUP_ALL_PASSIVE': return gap.teamId == null ? null : { tab: 'admin', params: { g_tab: 'notifyGroups', g_team: str(gap.teamId) } }
    case 'TEAM_FORWARD_BROKEN': return gap.teamId == null ? null : { tab: 'admin', params: { g_tab: 'forwards', g_fteam: str(gap.teamId), g_fstate: 'attention' } }
    case 'TEAM_CHANNEL_NO_APPROVER': return channelLink(item.id)
    case 'TASK_CHANNEL_REPORT': return channelLink(item.id, item.state === 'unsent' ? item.reportId : null)
    case 'TASK_APPROVAL_PENDING': return channelLink(item.id, item.reportId)
    case 'TASK_ACTIONS_OVERDUE': return MY_OVERDUE_ACTIONS_LINK
    default: return null
  }
}

/** Eksiğin TOPLU bağlantısı (kalem yoksa ya da gösterilmeyen kalem varsa "hepsini aç"); yoksa null. */
export function extraGapLink(gap) {
  if (!gap) return null
  switch (gap.code) {
    case 'TEAM_GROUP_ALL_PASSIVE':
    case 'TEAM_FORWARD_BROKEN': return extraItemLink(gap, {})
    case 'TEAM_ONCALL_MEMBERS': return gap.teamName ? { tab: 'admin', params: { g_tab: 'teams', g_q: gap.teamName } } : { tab: 'admin', params: { g_tab: 'teams' } }
    case 'TEAM_CHANNEL_NO_APPROVER':
    case 'TASK_CHANNEL_REPORT': return gap.teamId == null ? null : { tab: 'channelreports', params: { te_team: str(gap.teamId) } }
    case 'TASK_APPROVAL_PENDING': return { tab: 'channelreports', params: {} }
    case 'TASK_ACTIONS_OVERDUE': return MY_OVERDUE_ACTIONS_LINK
    default: return null
  }
}

/** Pencereden düzeltilebilen eksik → düzeltme türü (yoksa null). */
export function fixKindOf(code) {
  if (code === 'TEAM_NO_ESCALATION') return 'contact'
  if (code === 'TEAM_NO_NOTIFY_ADDRESS' || code === 'TEAM_GROUP_ALL_PASSIVE') return 'group'
  if (code === 'TEAM_ONCALL_MEMBERS') return 'oncall'
  return null
}
