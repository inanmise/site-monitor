import { CheckCircle2 } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import WarningList from './WarningList.jsx'
import { Card } from '@/components/shadcn/card'

/**
 * Sihirbaz 4. adım — sonuç (2026-10-06): tek kayıt ("takibe alındı · sürüm 1"), toplu ("N sertifika takibe alındı" +
 * liste) ya da yeni sürüm ("sürüm N kaydedildi, önceki M" + anahtar / konu / SAN değişim uyarıları). Ağdaki sertifikalarla
 * aynı değerlendirmenin hemen koştuğu ve diğer envanter alanlarının sonradan düzenlenebileceği söylenir.
 * Test kancası: `data-slot="mcert-result"` + `data-kind`.
 */
export default function ResultStep({ result }) {
  const t = useT()
  const d = result?.data || {}
  const created = Array.isArray(d.created) ? d.created : []
  return (
    <Card data-slot="mcert-result" data-kind={result?.kind} className="items-center gap-3 rounded-[10px] px-4 py-6 text-center shadow-none">
      <CheckCircle2 aria-hidden="true" className="size-10 text-success" />
      <h3 className="m-0 text-lg font-semibold">
        {result?.kind === 'batch' ? t('mcert.result.batchTitle', created.length)
          : result?.kind === 'renewed' ? t('mcert.result.renewTitle', d.version ?? '—')
            : t('mcert.result.createTitle')}
      </h3>
      <p className="m-0 max-w-prose text-sm text-muted-foreground">
        {result?.kind === 'batch' ? t('mcert.result.batchBody')
          : result?.kind === 'renewed' ? t('mcert.result.renewBody', result?.domain || '—', d.previous_version ?? '—')
            : t('mcert.result.createBody', d.domain || result?.domain || '—', d.version ?? 1)}
      </p>
      {result?.kind === 'batch' && created.length > 0 && (
        <ul className="m-0 flex max-w-full list-none flex-wrap justify-center gap-1.5 p-0">
          {created.map((c) => (
            <li key={c.inventory_id ?? c.domain} className="max-w-full rounded-md border bg-muted/30 px-2 py-0.5 font-mono text-xs break-all">{c.domain}</li>
          ))}
        </ul>
      )}
      {result?.kind === 'renewed' && <WarningList warnings={d.warnings} className="w-full text-left" label={t('mcert.result.changes')} />}
      <p className="m-0 max-w-prose text-xs text-muted-foreground">{t('mcert.result.note')}</p>
    </Card>
  )
}
