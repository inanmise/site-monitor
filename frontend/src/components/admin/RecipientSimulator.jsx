import { useEffect, useState } from 'react'
import { RotateCw, Users } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { readUrlParam, useUrlQuerySync } from '../../hooks/useUrlQuerySync.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import { SettingsHeader } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import ScenarioForm from './whonotified/ScenarioForm.jsx'
import SimResult, { HowDecided, ResultSkeleton } from './whonotified/SimResult.jsx'
import { DEFAULT_KIND, DEFAULT_LEVEL, normId, normKind, normLevel } from './whonotified/whoNotifiedModel.js'

/** Seçim değişikliklerinin tek istekte toplanması (ör. seviye çubuğunda hızlı gezinme). */
const DEBOUNCE_MS = 200

/**
 * "Kim bilgilendirilir?" — Yönetim Paneli › Bildirim & Alarmlar altında AYRI sekme (2026-09-27; önceden Eskalasyon
 * Kişileri sayfasının içinde katlanır bir karttı). Takım + alarm seviyesi + alarm türü (+ bildirim grubu) seçilir,
 * alarm gitmeden alıcı zinciri görünür: e-posta (bildirim grubu → takım varsayılan grubu → takım adresi), eskalasyon
 * kişileri (seviye eşiği + takımsız/global düşüşü), kişi webhook'ları, kişi bazlı push kararları (alır / almaz + neden)
 * ve 7/24 ekibi notu. Push satırlarını kimin göreceğini SUNUCU söyler (`push_access`, 2026-09-28): global yönetici her
 * takım, takımı yöneten tüm üyeler, üye yalnız kendini; göremeyen neden göremediğini okur.
 * Kararları sunucu verir — gerçek gönderimle AYNI kod yolu ({@code EscalationService.simulateRecipients}).
 *
 * <p>Senaryo URL'de (`g_team`, `g_level`, `g_kind`, `g_group`; varsayılan değer yazılmaz): simülasyon bağlantıyla
 * paylaşılır, yenileme korunur. Sekme değişince AdminPanel `g_*` anahtarlarını temizler.
 *
 * @param teams          görünür takımlar (AdminPanel yükler)
 * @param isAdmin        sistem rolü ADMIN — yalnız kurulum geçişinde takım süzgecini taşımak için (push görünürlüğü sunucudan)
 * @param defaultTeamId  tek takımlı ADMIN olmayan kullanıcıda o takım (takımlar geç yüklenir → efektle uygulanır)
 * @param onNavigate     (sekmeId, g_* paramları) → AdminPanel.jump; "kimse bilgilendirilmez" durumundan kuruluma geçiş
 */
export default function RecipientSimulator({ teams = [], isAdmin = false, defaultTeamId = '', onNavigate }) {
  const t = useT()
  const [teamId, setTeamId] = useState(() => normId(readUrlParam('g_team', '')) || normId(defaultTeamId))
  const [level, setLevel] = useState(() => normLevel(readUrlParam('g_level', DEFAULT_LEVEL)))
  const [kind, setKind] = useState(() => normKind(readUrlParam('g_kind', DEFAULT_KIND)))
  const [groupId, setGroupId] = useState(() => normId(readUrlParam('g_group', '')))
  useUrlQuerySync({
    g_team: teamId || null,
    g_level: level === DEFAULT_LEVEL ? null : level,
    g_kind: kind === DEFAULT_KIND ? null : kind,
    g_group: groupId || null,
  })

  // Takımlar AdminPanel'de geç gelir: tek takımlı kullanıcının takımı yüklendiğinde seçilir (kullanıcı seçimini ezmez).
  useEffect(() => {
    const id = normId(defaultTeamId)
    if (id) setTeamId((cur) => cur || id)
  }, [defaultTeamId])

  // Elle yazılmış/eskimiş bağlantı: görünür takım listesinde olmayan takım seçili kalmasın.
  useEffect(() => {
    if (teamId && teams.length > 0 && !teams.some((tm) => String(tm.id) === teamId)) {
      setTeamId('')
      setGroupId('')
    }
  }, [teams, teamId])

  // Takımın bildirim grupları (isteğe bağlı "izlemenin grubu" seçimi).
  const [groupsState, setGroupsState] = useState({ teamId: '', list: [] })
  useEffect(() => {
    if (!teamId) return undefined
    let alive = true
    Promise.resolve(api.notificationGroups?.list?.(Number(teamId)))
      .then((res) => {
        if (!alive || !res?.success) return
        const raw = Array.isArray(res.data) ? res.data : (res.data?.items || [])
        const list = raw.filter((g) => g && g.active !== false && (g.team_id == null || String(g.team_id) === teamId))
        setGroupsState({ teamId, list })
        // Bağlantıdaki grup bu takımın değilse varsayılana dön (sunucu zaten yok sayar; ekran da yanıltmasın).
        setGroupId((cur) => (cur && !list.some((g) => String(g.id) === cur) ? '' : cur))
      })
      .catch(() => { /* grup seçici boş kalır; simülasyon takım varsayılanıyla çalışır */ })
    return () => { alive = false }
  }, [teamId])
  const groupsLoaded = groupsState.teamId === teamId
  const groups = groupsLoaded ? groupsState.list : []

  // Simülasyon — her senaryo değişikliğinde (kısa gecikmeli) + "Simüle et" ile yeniden.
  const [runKey, setRunKey] = useState(0)
  const [result, setResult] = useState({ data: null, error: null, loading: false })
  useEffect(() => {
    if (!teamId) { setResult({ data: null, error: null, loading: false }); return undefined }
    let alive = true
    setResult((r) => ({ ...r, error: null, loading: true }))
    const timer = setTimeout(() => {
      Promise.resolve(api.admin.simulateRecipients({ teamId: Number(teamId), level, kind, groupId: groupId ? Number(groupId) : null }))
        .then((res) => {
          if (!alive) return
          if (res?.success) setResult({ data: res.data, error: null, loading: false })
          else setResult({ data: null, error: res?.error || t('sim.error'), loading: false })
        })
        .catch((e) => { if (alive) setResult({ data: null, error: e?.message || t('sim.error'), loading: false }) })
    }, DEBOUNCE_MS)
    return () => { alive = false; clearTimeout(timer) }
  }, [teamId, level, kind, groupId, runKey])   // eslint-disable-line react-hooks/exhaustive-deps

  function changeTeam(v) {
    const id = normId(v)
    if (id === teamId) return
    setTeamId(id)
    setGroupId('')   // grup takıma aittir
  }
  const rerun = () => setRunKey((k) => k + 1)
  // Kuruluma geçişte takım süzgeci yalnız yöneticide taşınır (diğer rolde o süzgecin denetimi görünmüyor).
  const navParams = isAdmin && teamId ? { g_team: teamId } : undefined

  let body
  if (!teamId) {
    body = (
      <StatusBlock icon={Users} title={t('wn.pickTeamTitle')} description={t('wn.pickTeamDesc')}
        className="rounded-xl border border-dashed py-8 md:py-8" />
    )
  } else if (result.error) {
    body = (
      <AlertBanner tone="danger" role="alert" title={t('wn.errorTitle')} className="mb-0"
        actions={<Button type="button" variant="outline" size="sm" className="h-10 md:h-8" onClick={rerun}>
          <RotateCw aria-hidden="true" /> {t('wn.retry')}
        </Button>}>
        {String(result.error)}
      </AlertBanner>
    )
  } else if (!result.data) {
    body = <ResultSkeleton />
  } else {
    body = (
      <SimResult data={result.data} level={level} kind={kind} refreshing={result.loading}
        onNavigate={onNavigate} navParams={navParams} />
    )
  }

  return (
    <section data-slot="who-notified" data-testid="recipient-sim" className="mb-8 flex min-w-0 w-full flex-col gap-5">
      <SettingsHeader icon={Users} title={t('sim.title')} description={t('wn.subtitle')}
        actions={teamId ? <CopyLinkButton variant="outline" className="h-10 w-full sm:w-auto md:h-8" /> : null} />
      <ScenarioForm teams={teams} teamId={teamId} onTeamChange={changeTeam}
        level={level} onLevelChange={setLevel} kind={kind} onKindChange={setKind}
        groups={groups} groupsLoaded={groupsLoaded} groupId={groupId} onGroupChange={(v) => setGroupId(normId(v))}
        onRun={rerun} running={result.loading} />
      {body}
      <HowDecided />
    </section>
  )
}
