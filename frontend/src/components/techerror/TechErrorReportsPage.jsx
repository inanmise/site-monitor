import { useCallback, useRef, useState } from 'react'
import { flushUrlQuerySync, readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import ChannelsView from './ChannelsView.jsx'
import ChannelView from './ChannelView.jsx'
import ReportEditor from './ReportEditor.jsx'
import {
  TAB_REPORTS, TAB_TEMPLATE, TE_CHANNEL, TE_REPORT, TE_TAB, TE_TEAM, TE_VIEW, VIEW_CHANNEL, VIEW_CHANNELS, VIEW_REPORT,
} from './techErrorModel.js'
import { REPORT_LIST_KEYS } from './channelListModel.js'
import { SENDLOG_KEYS, TAB_SENDLOG } from './sendModel.js'

const ID_RE = /^[1-9][0-9]{0,17}$/

/** Kanal sekmesi: şablon, gönderim günlüğü (R18 / E22) ya da raporlar (bilinmeyen değer = raporlar). */
function tabOf(v) {
  return v === TAB_TEMPLATE || v === TAB_SENDLOG ? v : TAB_REPORTS
}

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
 * `te_team` liste süzgeci; kanal listesi `te_q` / `te_st` / `te_sort` / `te_page` / `te_ps`, kanalın rapor listesi
 * `te_rq` / `te_rst` / `te_ry` / `te_rpage` / `te_rps` (8. sürüm, 2026-10-17), gönderim günlüğü `te_tab=sendlog` +
 * `te_slp` / `te_slps` / `te_slk` / `te_sls` (R18, 2026-10-18) — her görünüm bağlantıyla paylaşılabilir.
 * Kapsamı sunucu belirler.
 */
export default function TechErrorReportsPage() {
  const [nav, setNav] = useState(readInitial)
  const [tab, setTab] = useState(() => tabOf(readUrlParam(TE_TAB, '')))
  const [team, setTeam] = useState(() => readId(TE_TEAM))

  useUrlQuerySync({
    [TE_VIEW]: nav.view === VIEW_CHANNELS ? null : nav.view,
    [TE_CHANNEL]: nav.view === VIEW_CHANNELS ? null : nav.channel,
    [TE_REPORT]: nav.view === VIEW_REPORT ? nav.report : null,
    [TE_TAB]: nav.view === VIEW_CHANNEL && tab !== TAB_REPORTS ? tab : null,
    [TE_TEAM]: nav.view === VIEW_CHANNELS && team ? team : null,
    // Kanal görünümünden ÇIKINCA (kanallar listesi) rapor listesi süzgeçleri silinir — başka bir kanala eski arama / yıl
    // taşınmasın; rapora inip kanala dönünce korunur (8. sürüm). Kanal listesinin süzgeçleri (te_q / te_st / te_sort /
    // te_page / te_ps) silinmez: kanaldan dönünce liste aynı kalır.
    ...(nav.view === VIEW_CHANNELS ? Object.fromEntries(REPORT_LIST_KEYS.map((k) => [k, null])) : {}),
    // Gönderim günlüğünün sayfa / süzgeç anahtarları (R18 / E22) yalnız o sekme açıkken yaşar (günlük kendisi yazar)
    ...(nav.view === VIEW_CHANNEL && tab === TAB_SENDLOG ? {} : Object.fromEntries(SENDLOG_KEYS.map((k) => [k, null]))),
  })

  // Görünüm değişmeden önce bekleyen (300 ms gecikmeli) adres yazımları uygulanır: aramayı yazıp hemen kanala giren kullanıcı
  // dönüşte aynı listeyi bulur; eski görünümün kapanması yazımı düşürüyordu (e2e, 8. sürüm)
  const openChannel = useCallback((id, which = TAB_REPORTS) => {
    flushUrlQuerySync()
    setTab(tabOf(which))
    setNav({ view: VIEW_CHANNEL, channel: String(id), report: null })
    try { window.scrollTo({ top: 0 }) } catch { /* jsdom */ }
  }, [])

  // Ay geçişiyle açılan raporda odak başlığa taşınır (ReportEditor yeniden kurulur)
  const focusTitle = useRef(false)
  const openReport = useCallback((id, channelId) => {
    flushUrlQuerySync()
    setNav((n) => ({ view: VIEW_REPORT, channel: channelId != null ? String(channelId) : n.channel, report: String(id) }))
    try { window.scrollTo({ top: 0 }) } catch { /* jsdom */ }
  }, [])

  const toChannels = useCallback(() => { flushUrlQuerySync(); setNav({ view: VIEW_CHANNELS, channel: null, report: null }) }, [])

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
