import { useCallback, useRef, useState } from 'react'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import ChannelsView from './ChannelsView.jsx'
import ChannelView from './ChannelView.jsx'
import ReportEditor from './ReportEditor.jsx'
import {
  TAB_REPORTS, TAB_TEMPLATE, TE_CHANNEL, TE_REPORT, TE_TAB, TE_TEAM, TE_VIEW, VIEW_CHANNEL, VIEW_CHANNELS, VIEW_REPORT,
} from './techErrorModel.js'

const ID_RE = /^[1-9][0-9]{0,17}$/

function readId(key) {
  const v = readUrlParam(key, '')
  return ID_RE.test(v) ? v : null
}

function readInitial() {
  const view = readUrlParam(TE_VIEW, '')
  const channel = readId(TE_CHANNEL)
  const report = readId(TE_REPORT)
  if (view === VIEW_REPORT && report) return { view: VIEW_REPORT, channel, report }
  if (view === VIEW_CHANNEL && channel) return { view: VIEW_CHANNEL, channel, report: null }
  return { view: VIEW_CHANNELS, channel: null, report: null }
}

/**
 * KANAL TEKNİK HATA RAPORLARI (2026-10-13, kullanıcı isteği) — `?tab=channelreports`.
 *
 * <p>Üç görünüm: <b>Kanallar</b> (takımın kanalları, önceki ayın rapor durumu), <b>Kanal</b> (raporları + o kanalın
 * ŞABLONU — "X kanalının şablonunu aç" X'i, "Y kanalının şablonunu aç" Y'yi açar) ve <b>Rapor</b> (değerler, anomali
 * başlıkları, 2 kolonlu grafik yuvaları, PDF, takım e-postasına gönderim).
 *
 * <p><b>URL:</b> `te_view` (channel | report), `te_ch` kanal, `te_tab=template` şablon sekmesi, `te_rep` rapor,
 * `te_team` liste süzgeci — her görünüm bağlantıyla paylaşılabilir. Kapsamı sunucu belirler.
 */
export default function TechErrorReportsPage() {
  const [nav, setNav] = useState(readInitial)
  const [tab, setTab] = useState(() => (readUrlParam(TE_TAB, '') === TAB_TEMPLATE ? TAB_TEMPLATE : TAB_REPORTS))
  const [team, setTeam] = useState(() => readId(TE_TEAM))

  useUrlQuerySync({
    [TE_VIEW]: nav.view === VIEW_CHANNELS ? null : nav.view,
    [TE_CHANNEL]: nav.view === VIEW_CHANNELS ? null : nav.channel,
    [TE_REPORT]: nav.view === VIEW_REPORT ? nav.report : null,
    [TE_TAB]: nav.view === VIEW_CHANNEL && tab === TAB_TEMPLATE ? TAB_TEMPLATE : null,
    [TE_TEAM]: nav.view === VIEW_CHANNELS && team ? team : null,
  })

  const openChannel = useCallback((id, which = TAB_REPORTS) => {
    setTab(which === TAB_TEMPLATE ? TAB_TEMPLATE : TAB_REPORTS)
    setNav({ view: VIEW_CHANNEL, channel: String(id), report: null })
    try { window.scrollTo({ top: 0 }) } catch { /* jsdom */ }
  }, [])

  // Ay geçişiyle açılan raporda odak başlığa taşınır (ReportEditor yeniden kurulur)
  const focusTitle = useRef(false)
  const openReport = useCallback((id, channelId) => {
    setNav((n) => ({ view: VIEW_REPORT, channel: channelId != null ? String(channelId) : n.channel, report: String(id) }))
    try { window.scrollTo({ top: 0 }) } catch { /* jsdom */ }
  }, [])

  const toChannels = useCallback(() => setNav({ view: VIEW_CHANNELS, channel: null, report: null }), [])

  if (nav.view === VIEW_REPORT) {
    return (
      <ReportEditor key={nav.report} reportId={nav.report}
        onBack={(channelId) => (channelId || nav.channel ? openChannel(channelId || nav.channel) : toChannels())}
        onOpenChannels={toChannels} onOpenTemplate={(channelId) => openChannel(channelId, TAB_TEMPLATE)}
        onOpenReport={(id, channelId) => { focusTitle.current = true; openReport(id, channelId) }} focusTitleRef={focusTitle} />
    )
  }
  if (nav.view === VIEW_CHANNEL) {
    return (
      <ChannelView key={nav.channel} channelId={nav.channel} tab={tab} onTabChange={setTab}
        onBack={toChannels} onOpenReport={openReport} />
    )
  }
  return <ChannelsView team={team} onTeamChange={setTeam} onOpenChannel={openChannel} onOpenReport={openReport} />
}
