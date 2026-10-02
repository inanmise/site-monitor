import { useMemo, useState } from 'react'
import { CalendarClock, CheckCircle2, Hourglass, LogIn, MonitorSmartphone, Timer } from 'lucide-react'
import { FixedLangProvider, loadLanguage, useLanguage, useT } from '../../../i18n/index.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import MaintenanceLoginCard from '../../maintenance/MaintenanceLoginCard.jsx'
import { MaintenanceAnnounceStrip, MaintenanceEndedStrip, MaintenanceWarningStrip } from '../../maintenance/MaintenanceStrips.jsx'
import { MaintenanceDialogBody } from '../../maintenance/MaintenanceCountdownDialog.jsx'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { SettingsSection } from '../SettingsControls.jsx'

/**
 * Kullanıcı ekranı ÖNİZLEMESİ (2026-10-02, onaylı zenginleştirme a) — giriş ekranı uyarısı, duyuru şeridi, uyarı şeridi,
 * son 60 sn geri sayım penceresi ve bakım bitince görünen "tamamlandı" (bitiş) şeridi GERÇEK bileşenlerle (aynı metin/ton/yerleşim), yayına almadan; TR/EN geçişi arayüz dilini
 * değiştirmez ({@link FixedLangProvider}). Kaynak: en yakın bakım (yoksa örnek pencere: yarın 22:00–23:00) ve onun
 * mesajları. Önizleme hiçbir istek atmaz, hiçbir şey göndermez.
 *
 * Test kancaları: `data-slot="sysmaint-preview"` (`data-lang`, `data-tab`), `sysmaint-preview-stage`.
 */
function sampleBlock(current, serverNowMs) {
  if (current?.start_at) return { ...current, state: 'announced' }
  const base = new Date((Number.isFinite(serverNowMs) ? serverNowMs : Date.now()) + 24 * 3_600_000)
  // İstanbul 22:00 = UTC 19:00 (sabit UTC+3)
  const day = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate(), 19, 0, 0))
  return {
    state: 'announced', id: 0, revision: 1, warn_minutes: 10, announce_hours: 24,
    start_at: day.toISOString(), end_at: new Date(day.getTime() + 60 * 60_000).toISOString(),
    message_tr: null, message_en: null, contact: null,
  }
}

export default function SysMaintPreview({ current, serverNowMs }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [lang, setLang] = useState(uiLang === 'en' ? 'en' : 'tr')
  const [loading, setLoading] = useState(false)
  const [tab, setTab] = useState('login')
  const block = useMemo(() => sampleBlock(current, serverNowMs), [current, serverNowMs])

  async function changeLang(next) {
    if (next === 'en') {
      setLoading(true)
      try { await loadLanguage('en') } catch { /* sözlük inmedi: TR yedeğiyle çizilir */ }
      setLoading(false)
    }
    setLang(next)
  }

  const tabs = [
    { id: 'login', icon: LogIn, label: t('sysmaint.preview.login') },
    { id: 'announce', icon: CalendarClock, label: t('sysmaint.preview.announce') },
    { id: 'warning', icon: Hourglass, label: t('sysmaint.preview.warning') },
    { id: 'dialog', icon: Timer, label: t('sysmaint.preview.dialog') },
    { id: 'ended', icon: CheckCircle2, label: t('sysmaint.preview.ended') },
  ]

  return (
    <SettingsSection title={<span className="inline-flex items-center gap-2"><MonitorSmartphone aria-hidden="true" className="size-4" />{t('sysmaint.preview.title')}</span>}
      description={t('sysmaint.preview.desc')} contentClassName="flex min-w-0 flex-col gap-3">
      <div data-slot="sysmaint-preview" data-lang={lang} data-tab={tab} className="flex min-w-0 flex-col gap-3">
        <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-3">
          <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div className="w-full min-w-0 overflow-x-auto sm:w-auto">
              <TabsList variant="line" aria-label={t('sysmaint.preview.title')} className="h-auto w-max min-w-full justify-start gap-1 p-0 pb-1">
                {tabs.map(({ id, icon: Icon, label }) => (
                  <TabsTrigger key={id} value={id} className="min-h-10 gap-1.5 whitespace-nowrap px-2.5">
                    <Icon aria-hidden="true" className="size-4" />{label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </div>
            <div className="flex items-center gap-2 self-end sm:self-auto">
              {loading && <Spinner size={14} inline label={t('nav.langLoading')} />}
              <SegmentedControl value={lang} onChange={changeLang} ariaLabel={t('sysmaint.preview.lang')}
                options={[{ value: 'tr', label: 'TR' }, { value: 'en', label: 'EN' }]} />
            </div>
          </div>
          <FixedLangProvider lang={lang}>
            <div data-slot="sysmaint-preview-stage" aria-label={t('sysmaint.preview.stage')} role="group"
              className="min-w-0 overflow-hidden rounded-lg border bg-muted/40 p-3 sm:p-4">
              <TabsContent value="login" className="mx-auto w-full max-w-md">
                <MaintenanceLoginCard status={{ ...block, state: 'active' }} />
              </TabsContent>
              <TabsContent value="announce" className="min-w-0">
                <div className="overflow-hidden rounded-md border bg-background">
                  <MaintenanceAnnounceStrip block={block} onDismiss={() => {}} />
                </div>
              </TabsContent>
              <TabsContent value="warning" className="min-w-0">
                <div className="overflow-hidden rounded-md border bg-background">
                  <MaintenanceWarningStrip block={block} secondsLeft={(block.warn_minutes || 10) * 60} onDismiss={() => {}} />
                </div>
              </TabsContent>
              <TabsContent value="dialog" className="mx-auto w-full max-w-lg">
                <div className="grid min-w-0 gap-4 rounded-lg border bg-background p-4 shadow-sm sm:p-6">
                  <MaintenanceDialogBody preview mode="final" seconds={42} block={block} />
                </div>
              </TabsContent>
              <TabsContent value="ended" className="min-w-0">
                <div className="overflow-hidden rounded-md border bg-background">
                  <MaintenanceEndedStrip block={{ ...block, state: 'ended' }} onDismiss={() => {}} />
                </div>
              </TabsContent>
            </div>
          </FixedLangProvider>
        </Tabs>
      </div>
    </SettingsSection>
  )
}
