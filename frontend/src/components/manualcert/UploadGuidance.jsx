import { useState } from 'react'
import {
  Ban, CircleHelp, FileArchive, FileCode, FileKey2, FileLock2, FileStack, FileText, History, KeyRound, ListChecks,
  ShieldCheck, ClipboardPaste, Workflow,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { MAX_UPLOAD_MB } from './manualCertModel.js'

/** Kullanıcının aç/kapa tercihi (yalnız bu tarayıcıda; depolama yoksa her açılışta açık). */
const STORAGE_KEY = 'sm.mcert.guide'

function readOpen() {
  try { return localStorage.getItem(STORAGE_KEY) === 'open' } catch { return false }   // varsayılan KAPALI
}
function writeOpen(open) {
  try { localStorage.setItem(STORAGE_KEY, open ? 'open' : 'closed') } catch { /* depolama yok */ }
}

/**
 * Biçim tablosu — her satır: biçim adları, genelde ne içerdiği, şifre gerekir mi, özel anahtar içerir mi. Tablo DEĞİL
 * satır listesi: telefonda (360 px) yatay kaydırma olmadan okunur.
 */
const FORMATS = [
  { id: 'pem', Icon: FileText, names: '.pem · .crt · .cer', pw: 'no', key: 'sometimes' },
  { id: 'der', Icon: FileCode, names: '.der · .cer', pw: 'no', key: 'no' },
  { id: 'p7b', Icon: FileStack, names: '.p7b · .p7c', pw: 'no', key: 'no' },
  { id: 'pfx', Icon: FileLock2, names: '.pfx · .p12', pw: 'yes', key: 'yes' },
  { id: 'jks', Icon: FileKey2, names: '.jks · .jceks', pw: 'often', key: 'keystore' },
  { id: 'zip', Icon: FileArchive, names: '.zip', pw: 'depends', key: 'depends' },
  { id: 'text', Icon: ClipboardPaste, names: 'PEM', pw: 'no', key: 'no' },
]

const CHIP = 'h-auto min-h-5 gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold whitespace-normal'
const PW_TONE = {
  yes: 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  often: 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  depends: 'border-border bg-muted text-muted-foreground',
  no: 'border-border bg-muted text-muted-foreground',
}
const KEY_TONE = {
  yes: 'border-violet-500/35 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300',
  keystore: 'border-violet-500/35 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300',
  sometimes: 'border-border bg-muted text-muted-foreground',
  depends: 'border-border bg-muted text-muted-foreground',
  no: 'border-border bg-muted text-muted-foreground',
}

function Section({ icon: Icon, title, children, className, slot }) {
  return (
    <section data-slot={slot} className={cn('flex min-w-0 flex-col gap-2', className)}>
      <h3 className="m-0 flex items-center gap-2 text-sm font-semibold">
        <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />{title}
      </h3>
      {children}
    </section>
  )
}

/**
 * "Hangi dosyayı yüklemeliyim?" — yükleme sayfasının ve boş durumun rehberi (2026-10-06). Kullanıcının süreci: iç talep
 * ile CSR → harici CA → `xxxx.pem` → zincir tamamlama → PFX/JKS → OpenShift'te keystore/truststore. Site Monitor bu
 * sürece KATILMAZ, yalnız bitişi izler; rehber hangi aşamada hangi dosyanın yükleneceğini, neyin yüklenmeyeceğini (CSR,
 * tek başına özel anahtar), gizliliği ve yenilemeyi anlatır.
 *
 * <p>Katlanır şerit (ui/CollapsibleSection); VARSAYILAN KAPALI (2026-10-07, kullanıcı isteği — liste boşken de), kullanıcı
 * açarsa tercihi tarayıcıda hatırlanır. Test kancası `data-slot="mcert-guide"`.
 */
export default function UploadGuidance({ className }) {
  const t = useT()
  const [open, setOpen] = useState(readOpen)
  const onOpenChange = (next) => { setOpen(next); writeOpen(next) }

  return (
    <CollapsibleSection data-slot="mcert-guide" open={open} onOpenChange={onOpenChange}
      icon={CircleHelp} label={t('mcert.guide.title')} hint={t('mcert.guide.hint')}
      className={cn('mb-4', className)} triggerClassName="mb-2">
      <Card data-slot="mcert-guide-body" className="gap-5 rounded-[10px] px-4 py-4 shadow-none sm:px-5">
        <p className="m-0 text-sm text-muted-foreground">{t('mcert.guide.intro')}</p>

        <Section icon={Workflow} title={t('mcert.guide.whenTitle')} slot="mcert-guide-when">
          <ol className="m-0 flex list-none flex-col gap-1.5 p-0">
            {[1, 2, 3, 4, 5].map((n) => {
              const upload = n === 3 || n === 4
              return (
                <li key={n} data-step={n} data-upload={upload ? 'true' : undefined}
                  className={cn('flex min-w-0 items-start gap-2.5 rounded-md px-2 py-1.5 text-sm', upload && 'bg-success/10 dark:bg-success/15')}>
                  <span aria-hidden="true"
                    className={cn('mt-px flex size-5 shrink-0 items-center justify-center rounded-full text-[11px] font-bold',
                      upload ? 'bg-success text-white' : 'bg-muted text-muted-foreground')}>{n}</span>
                  <span className="min-w-0 flex-1">
                    {t(`mcert.guide.step${n}`)}
                    {upload && (
                      <Badge variant="outline" className={cn(CHIP, 'ml-1.5 border-success/40 bg-transparent text-success align-middle')}>
                        {t('mcert.guide.uploadHere')}
                      </Badge>
                    )}
                  </span>
                </li>
              )
            })}
          </ol>
        </Section>

        <Section icon={ListChecks} title={t('mcert.guide.formatsTitle')} slot="mcert-guide-formats">
          <p className="m-0 text-xs text-muted-foreground">{t('mcert.guide.formatsHint', MAX_UPLOAD_MB)}</p>
          <ul className="m-0 grid list-none grid-cols-1 gap-2 p-0 lg:grid-cols-2">
            {FORMATS.map(({ id, Icon, names, pw, key }) => (
              <li key={id} data-format={id} className="flex min-w-0 items-start gap-3 rounded-lg border bg-muted/20 px-3 py-2.5">
                <Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <p className="m-0 flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <span className="text-sm font-semibold">{t(`mcert.fmt.${id}.name`)}</span>
                    <span className="font-mono text-xs text-muted-foreground">{names}</span>
                  </p>
                  <p className="m-0 text-xs text-muted-foreground">{t(`mcert.fmt.${id}.what`)}</p>
                  <span className="flex flex-wrap gap-1.5">
                    <Badge variant="outline" data-slot="mcert-fmt-pw" data-value={pw} className={cn(CHIP, PW_TONE[pw])}>
                      <KeyRound aria-hidden="true" className="size-3" />{t(`mcert.fmt.pw.${pw}`)}
                    </Badge>
                    <Badge variant="outline" data-slot="mcert-fmt-key" data-value={key} className={cn(CHIP, KEY_TONE[key])}>
                      <FileLock2 aria-hidden="true" className="size-3" />{t(`mcert.fmt.key.${key}`)}
                    </Badge>
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </Section>

        <div className="grid min-w-0 grid-cols-1 gap-5 lg:grid-cols-2">
          <Section icon={Ban} title={t('mcert.guide.dontTitle')} slot="mcert-guide-dont">
            <ul className="m-0 flex list-none flex-col gap-1.5 p-0 text-sm">
              {['csr', 'key', 'csrZip'].map((k) => (
                <li key={k} className="flex min-w-0 items-start gap-2">
                  <Ban aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-destructive" />
                  <span className="min-w-0">{t(`mcert.guide.dont.${k}`)}</span>
                </li>
              ))}
            </ul>
          </Section>
          <Section icon={ShieldCheck} title={t('mcert.guide.privacyTitle')} slot="mcert-guide-privacy">
            <p className="m-0 text-sm">{t('mcert.guide.privacy')}</p>
          </Section>
          <Section icon={History} title={t('mcert.guide.renewTitle')} slot="mcert-guide-renew">
            <p className="m-0 text-sm">{t('mcert.guide.renew')}</p>
          </Section>
          <Section icon={ShieldCheck} title={t('mcert.guide.trackTitle')} slot="mcert-guide-track">
            <p className="m-0 text-sm">{t('mcert.guide.track')}</p>
          </Section>
        </div>
      </Card>
    </CollapsibleSection>
  )
}
