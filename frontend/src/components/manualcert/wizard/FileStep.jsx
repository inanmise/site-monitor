import { useId, useRef, useState } from 'react'
import { ClipboardPaste, FileText, FileUp, KeyRound, Upload, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field from '../../ui/Field.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'
import KeptLocalNote from './KeptLocalNote.jsx'
import {
  ACCEPT_ATTR, ACCEPT_EXTENSIONS, MAX_UPLOAD_MB, discouragedExtension, fileExtension, knownExtension, passwordLikely, sizeLabel,
} from '../manualCertModel.js'

/** BKS / UBER için yönerge komutu (dil bağımsız; yer tutucular büyük harf). */
const BKS_COMMAND = 'keytool -exportcert -rfc -alias ALIAS -keystore truststore.bks -storetype BKS -providerpath bcprov.jar -provider org.bouncycastle.jce.provider.BouncyCastleProvider -file cert.pem'

/**
 * Sihirbaz 1. adım — "Dosya" (2026-10-06). İki kaynak (SegmentedControl): dosya seç (sürükle-bırak alanı + gerçek dosya
 * seçici) ya da PEM/Base64 metni yapıştır. Şifre alanı .pfx/.p12/.jks/.jceks/.bks seçilince BAŞTAN, analiz "şifre gerekli /
 * yanlış" dediğinde de görünür; hata alanın altında (yalnız bildirim değil). Şifre saklanmaz — ipucu bunu söyler.
 * Yükleme öncesi uyarılar: CSR / tek başına özel anahtar uzantısı, tanınmayan uzantı, 5 MB sınırı.
 *
 * <p>2026-10-08: dosya TARAYICIDA açılır — güvence satırı (`mcert-local-hint`) bunu söyler; ayıklama yapıldıysa (İnceleme'den
 * dönüldü) "özel anahtar (N) tarayıcıda ayıklandı" notu (`mcert-kept-local`). BKS / UBER tarayıcıda açılamaz → alan hatası +
 * keytool yönergesi (`mcert-bks-help`).
 *
 * <p>Test kancaları: `mcert-dropzone` (+ `data-drag`), `mcert-file-input`, `mcert-file-chip`, `mcert-paste`, `mcert-password`.
 */
export default function FileStep({
  source, onSource, file, onFile, text, onText, password, onPassword, passwordNeeded, fe, extraction = null, issue = null,
}) {
  const t = useT()
  const inputRef = useRef(null)
  const inputId = useId()
  const [drag, setDrag] = useState(false)
  const showPassword = passwordNeeded || (source === 'file' && file && passwordLikely(file.name))

  const pick = (f) => {
    if (!f) return
    onFile(f)
    fe.clear('file')
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <SegmentedControl value={source} onChange={(v) => { onSource(v); fe.clear('file'); fe.clear('text') }} ariaLabel={t('mcert.file.sourceLabel')}
        className="w-full sm:w-auto" itemClassName="flex-1 max-sm:h-10 pointer-coarse:h-10"
        options={[
          { value: 'file', label: t('mcert.file.tabFile'), icon: FileUp },
          { value: 'text', label: t('mcert.file.tabText'), icon: ClipboardPaste },
        ]} />

      {source === 'file' ? (
        <div className="flex min-w-0 flex-col gap-2" data-field="file">
          <div data-slot="mcert-dropzone" data-drag={drag ? 'true' : undefined}
            onDragEnter={(e) => { e.preventDefault(); setDrag(true) }}
            onDragOver={(e) => { e.preventDefault(); setDrag(true) }}
            onDragLeave={() => setDrag(false)}
            onDrop={(e) => { e.preventDefault(); setDrag(false); pick(e.dataTransfer?.files?.[0]) }}
            className={cn('flex min-w-0 flex-col items-center gap-3 rounded-xl border-2 border-dashed px-4 py-6 text-center transition-colors motion-reduce:transition-none sm:py-8',
              drag ? 'border-primary bg-primary/5' : 'border-border bg-muted/20',
              fe.errors.file && 'border-destructive/60')}>
            <span aria-hidden="true" className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Upload className="size-6" />
            </span>
            <div className="flex min-w-0 flex-col gap-1">
              <p className="m-0 text-sm font-semibold">{t('mcert.file.dropTitle')}</p>
              <p className="m-0 text-xs text-muted-foreground">{t('mcert.file.dropHint', MAX_UPLOAD_MB)}</p>
            </div>
            <Button type="button" variant="outline" className="h-10" onClick={() => inputRef.current?.click()}>
              <FileUp aria-hidden="true" />{t('mcert.file.choose')}
            </Button>
            <Input ref={inputRef} id={inputId} type="file" data-slot="mcert-file-input" accept={ACCEPT_ATTR} tabIndex={-1}
              aria-label={t('mcert.file.choose')} className="sr-only"
              onChange={(e) => { pick(e.target.files?.[0]); e.target.value = '' }} />
            <p className="m-0 flex flex-wrap justify-center gap-1">
              {ACCEPT_EXTENSIONS.filter((x) => x !== '.txt').map((x) => (
                <Badge key={x} variant="outline" className="h-5 rounded-md px-1.5 font-mono text-[11px] font-normal text-muted-foreground">{x}</Badge>
              ))}
            </p>
          </div>
          {fe.errors.file && <p data-slot="field-error" className="m-0 text-xs text-destructive">{fe.errors.file}</p>}

          {file && (
            <div data-slot="mcert-file-chip" className="flex min-w-0 items-center gap-3 rounded-lg border bg-card px-3 py-2">
              <FileText aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <p className="m-0 truncate text-sm font-semibold" title={file.name}>{file.name}</p>
                <p className="m-0 text-xs text-muted-foreground tabular-nums">
                  {sizeLabel(file.size)}{fileExtension(file.name) ? ` · .${fileExtension(file.name)}` : ''}
                </p>
              </div>
              <Button type="button" variant="ghost" size="icon" className="shrink-0 text-muted-foreground"
                onClick={() => { onFile(null); fe.clear('file') }} aria-label={t('a11y.rowAction', file.name, t('mcert.file.remove'))}>
                <X aria-hidden="true" />
              </Button>
            </div>
          )}
          {file && discouragedExtension(file.name) && (
            <AlertBanner tone="warning" className="mb-0" title={t('mcert.file.discouragedTitle')}>
              {fileExtension(file.name) === 'csr' ? t('mcert.guide.dont.csr') : t('mcert.guide.dont.key')}
            </AlertBanner>
          )}
          {file && !discouragedExtension(file.name) && !knownExtension(file.name) && (
            <AlertBanner tone="info" className="mb-0">{t('mcert.file.unknownExt')}</AlertBanner>
          )}
        </div>
      ) : (
        <Field label={t('mcert.text.label')} hint={t('mcert.text.hint')} {...fe.fieldProps('text')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} data-slot="mcert-paste" aria-describedby={describedBy} aria-invalid={invalid} value={text} rows={8}
              spellCheck={false} autoComplete="off" placeholder={t('mcert.text.placeholder')}
              className="min-h-40 font-mono text-xs [overflow-wrap:anywhere] md:text-xs"
              onChange={(e) => { onText(e.target.value); fe.clear('text') }} />
          )}
        </Field>
      )}

      {issue === 'BKS' && (
        <AlertBanner tone="info" className="mb-0" title={t('mcert.extract.bksTitle')}>
          <div data-slot="mcert-bks-help" className="flex min-w-0 flex-col gap-1.5">
            <p className="m-0">{t('mcert.extract.bksHow')}</p>
            <code className="block max-w-full overflow-x-auto rounded bg-muted px-2 py-1 font-mono text-[11px] whitespace-pre-wrap [overflow-wrap:anywhere]">{BKS_COMMAND}</code>
          </div>
        </AlertBanner>
      )}

      {showPassword && (
        <Field label={t('mcert.pw.label')} hint={t('mcert.pw.hint')} {...fe.fieldProps('password')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <div className="relative flex min-w-0 items-center">
              <KeyRound aria-hidden="true" className="pointer-events-none absolute left-3 size-4 text-muted-foreground" />
              <Input id={id} type="password" data-slot="mcert-password" aria-describedby={describedBy} aria-invalid={invalid}
                value={password} maxLength={256} autoComplete="new-password" spellCheck={false} className="pl-9"
                onChange={(e) => { onPassword(e.target.value); fe.clear('password') }} />
            </div>
          )}
        </Field>
      )}

      <KeptLocalNote extraction={extraction} />
    </div>
  )
}
