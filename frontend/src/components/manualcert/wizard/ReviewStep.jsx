import { useId } from 'react'
import { FileSearch, FileWarning, KeyRound, Layers } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import EntryCard from './EntryCard.jsx'
import WarningList from './WarningList.jsx'
import { MAX_BATCH, entryTitle, looksLikeTruststore, sizeLabel } from '../manualCertModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'

/** Dokunmatikte 16 px radyo / kutunun dokunma alanı ::after ile 40 px (görünüm aynı). */
const TOUCH_AREA = 'relative pointer-coarse:after:absolute pointer-coarse:after:-inset-3'

/** CSR yüklendiyse: ne olduğu + CSR'nin içeriği + yapılacak şey (CA'nın döndüğü sertifikayı yükle). */
function CsrCard({ csr }) {
  const t = useT()
  const san = Array.isArray(csr.san) ? csr.san : []
  return (
    <div data-slot="mcert-csr">
      <AlertBanner tone="warning" icon={FileWarning} className="mb-0" title={t('mcert.csr.title')}>
        <p className="m-0">{t('mcert.csr.body')}</p>
        <dl className="m-0 mt-2 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
          <dt className="font-semibold">{t('mcert.csr.cn')}</dt><dd className="m-0 font-mono break-all">{csr.cn || '—'}</dd>
          {csr.subject_dn && (<><dt className="font-semibold">{t('mcert.entry.subject')}</dt><dd className="m-0 font-mono break-all">{csr.subject_dn}</dd></>)}
          {san.length > 0 && (<><dt className="font-semibold">{t('mcert.csr.san')}</dt><dd className="m-0 font-mono break-all">{san.join(', ')}</dd></>)}
          <dt className="font-semibold">{t('mcert.entry.key')}</dt>
          <dd className="m-0">{[csr.key_alg, csr.key_size].filter(Boolean).join(' ') || '—'}{csr.signature_algorithm ? ` · ${csr.signature_algorithm}` : ''}</dd>
        </dl>
        <p className="m-0 mt-2 font-semibold">{t('mcert.csr.next')}</p>
      </AlertBanner>
    </div>
  )
}

/**
 * Sihirbaz 2. adım — "İnceleme" (2026-10-06). Dosya özeti (biçim · ad · boyut), dosya düzeyi uyarılar, CSR açıklama
 * kartı, girdiler. Tek girdi seçimi radyo (RadioGroup); birden çok girdi varsa — yeni kayıt kipinde — "birden çok seç"
 * (truststore: her CA sertifikası ayrı kayıt, en çok 20) kutularla. Girdi yoksa açıklamalı boş durum.
 */
export default function ReviewStep({
  analysis, multi, onMulti, selected, onSelect, selectedSet, onToggle, renewMode, renewTargetId, onOpenCert, onRenewTarget, onFixPassword,
}) {
  const t = useT()
  const uid = useId()
  const entries = Array.isArray(analysis?.entries) ? analysis.entries : []
  const canMulti = !renewMode && entries.length > 1
  const full = selectedSet.size >= MAX_BATCH

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div data-slot="mcert-file-summary" className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
        {analysis?.format && <Badge variant="secondary" data-slot="mcert-format" className="font-mono">{analysis.format}</Badge>}
        {analysis?.file_name && <span className="min-w-0 truncate font-semibold" title={analysis.file_name}>{analysis.file_name}</span>}
        {analysis?.size_bytes != null && <span className="text-xs text-muted-foreground tabular-nums">{sizeLabel(analysis.size_bytes)}</span>}
        <span className="text-xs text-muted-foreground">· {t('mcert.review.found', entries.length)}</span>
      </div>

      <WarningList warnings={analysis?.warnings} label={t('mcert.review.fileWarnings')} />
      {/* JKS/JCEKS/BKS yanlış şifre: sertifikalar yine okundu — devam edilebilir ya da şifre düzeltilip yeniden analiz edilir */}
      {onFixPassword && (analysis?.warnings || []).some((w) => w?.code === 'PASSWORD_WRONG' || w?.code === 'PASSWORD_REQUIRED') && (
        <div data-slot="mcert-fix-password" className="flex min-w-0 flex-col gap-1.5 sm:flex-row sm:items-center">
          <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:h-10" onClick={onFixPassword}>
            <KeyRound aria-hidden="true" />{t('mcert.review.fixPassword')}
          </Button>
          <span className="text-xs text-muted-foreground">{t('mcert.review.passwordOptional')}</span>
        </div>
      )}
      {analysis?.csr && <CsrCard csr={analysis.csr} />}

      {entries.length === 0 ? (
        <StatusBlock tone="neutral" icon={FileSearch} className="rounded-[10px] border border-dashed py-8"
          title={t('mcert.review.noneTitle')} description={t('mcert.review.noneBody')} />
      ) : (
        <>
          {canMulti && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <SegmentedControl value={multi ? 'multi' : 'single'} onChange={(v) => onMulti(v === 'multi')} ariaLabel={t('mcert.review.modeLabel')}
                className="w-full sm:w-auto" itemClassName="flex-1 max-sm:h-10 pointer-coarse:h-10"
                options={[
                  { value: 'single', label: t('mcert.review.single') },
                  { value: 'multi', label: t('mcert.review.multi'), icon: Layers },
                ]} />
              <p className="m-0 text-xs text-muted-foreground">
                {multi ? t('mcert.review.multiHint', MAX_BATCH) : looksLikeTruststore(analysis) ? t('mcert.review.truststoreHint') : t('mcert.review.singleHint')}
              </p>
            </div>
          )}

          {multi ? (
            <ul aria-label={t('mcert.review.entries')} className="m-0 flex list-none flex-col gap-3 p-0">
              {entries.map((e, i) => {
                const titleId = `${uid}-t${i}`
                const on = selectedSet.has(e.ref)
                return (
                  <li key={e.ref}>
                    <EntryCard entry={e} selected={on} titleId={titleId} onOpenCert={onOpenCert}
                      control={(
                        <Checkbox checked={on} disabled={!on && full} onCheckedChange={() => onToggle(e.ref)} className={`size-5 ${TOUCH_AREA}`}
                          aria-label={t('a11y.rowAction', entryTitle(e), t('mcert.review.pick'))} />
                      )} />
                  </li>
                )
              })}
            </ul>
          ) : (
            <RadioGroup value={selected ?? ''} onValueChange={onSelect} aria-label={t('mcert.review.entries')} className="gap-3">
              {entries.map((e, i) => {
                const titleId = `${uid}-t${i}`
                return (
                  <EntryCard key={e.ref} entry={e} selected={selected === e.ref} titleId={titleId} onOpenCert={onOpenCert}
                    onRenewTarget={renewMode ? undefined : (target) => onRenewTarget(e.ref, target)} renewTargetId={renewTargetId}
                    control={entries.length > 1
                      ? <RadioGroupItem value={e.ref} aria-labelledby={titleId} className={`size-5 ${TOUCH_AREA}`} />
                      : null} />
                )
              })}
            </RadioGroup>
          )}
          {multi && <p role="status" className="m-0 text-xs text-muted-foreground tabular-nums">{t('mcert.review.selectedCount', selectedSet.size, MAX_BATCH)}</p>}
        </>
      )}
    </div>
  )
}
