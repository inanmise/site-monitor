import { Image, FileCode, Link2, Frame, Type, ShieldAlert, ExternalLink } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/shadcn/badge'
import { ISSUE_TONE, issueReasonKey } from './pageIssueModel.js'

const RES_ICON = { IMG: Image, CSS: FileCode, JS: FileCode, LINK: Link2, IFRAME: Frame, FONT: Type, FAVICON: Image }

/**
 * Sayfa bütünlüğü SORUNLU KAYNAK listesi — kompakt, yeniden kullanılır (E1 kontrol-geçmişi "Bozulmuş" ayrıntısı +
 * E2 alarm detayı). Her kaynak için: tür ikonu (+3P), kaynak URL'i, bulunduğu sayfa (source_page, güvenli link),
 * sorun türü rozeti, HTTP durumu/süre, ve E4 "neden / ne yapmalı". Kullanıcının #3 isteğini birebir karşılar:
 * HANGİ kaynak, NASIL bir dönüşten/hatadan, HANGİ sayfada.
 *
 * <p>Güvenlik: uzaktaki görsel/çerez render EDİLMEZ (yalnız metin). source_page yalnız http(s) ise linklenir
 * (javascript:/data: şema XSS'i engellenir). mweb: dar ekranda tek sütun yığılır, uzun URL'ler sarılır.
 *
 * @param {Array}  issues  page_resource_issues satırları (bir kontrole ait)
 * @param {number} max     en çok kaç satır çizilir (varsayılan 50); gerisi "+N daha" olarak sayılır
 */
export default function PageIssueList({ issues, max = 50, className }) {
  const t = useT()
  if (!Array.isArray(issues) || issues.length === 0) return null
  const shown = issues.slice(0, max)
  return (
    <div data-slot="page-issue-list" className={cn('flex min-w-0 flex-col gap-2', className)}>
      {shown.map((r, i) => {
        // "… +N daha" özeti (E2 bağlam-ayrıştırması): kaynak satırı değil, nötr bir sayaç satırı.
        if (r.more) return <div key={`m${i}`} data-slot="page-issue-more" className="text-xs text-muted-foreground">{r.more}</div>
        const RI = RES_ICON[r.resource_type] || Link2
        const reason = issueReasonKey(r)
        const linkable = r.source_page && r.source_page !== r.resource_url && /^https?:\/\//i.test(r.source_page)
        return (
          <div key={`${r.id || ''}#${i}`} data-slot="page-issue-row"
            className="min-w-0 rounded-md border bg-card px-3 py-2 text-xs">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span className="flex items-center gap-1 font-medium text-muted-foreground">
                <RI size={13} aria-hidden="true" />{r.resource_type || '—'}
                {/* 3P yalnız AÇIKÇA üçüncü-taraf (first_party === false); bilinmiyorsa (undefined) rozet yok */}
                {r.first_party === false && <span className="text-muted-foreground" title={t('page.thirdParty')}>·3P</span>}
              </span>
              <Badge variant="outline" className={cn('rounded-sm font-semibold', ISSUE_TONE[r.issue_type] || ISSUE_TONE.BROKEN)}>
                {r.issue_type === 'MIXED_CONTENT' && <ShieldAlert size={11} aria-hidden="true" className="mr-0.5 inline align-[-2px]" />}
                {t(`page.issue_${r.issue_type}`)}
              </Badge>
              {r.http_status != null && (
                <Badge variant="outline" className="rounded-sm font-mono text-muted-foreground">HTTP {r.http_status}</Badge>
              )}
              {r.duration_ms != null && <span className="text-muted-foreground">{r.duration_ms}ms</span>}
            </div>
            <div className="mt-1 break-all font-medium">{r.resource_url || '—'}</div>
            {r.source_page && r.source_page !== r.resource_url && (
              <div className="mt-0.5 break-all text-muted-foreground">
                {t('page.foundOn')}:{' '}
                {linkable
                  ? <a href={r.source_page} target="_blank" rel="noreferrer"
                      className="inline-flex items-center gap-0.5 text-inherit underline-offset-2 hover:underline">
                      {r.source_page}<ExternalLink size={10} aria-hidden="true" />
                    </a>
                  : r.source_page}
              </div>
            )}
            {/* E4: neden + ne yapmalı (insan-okur) */}
            <div className="mt-1.5 flex flex-col gap-0.5 border-t pt-1.5">
              <span><span className="font-semibold text-muted-foreground">{t('page.issueWhy')}:</span> {t(`page.issueReason.${reason}.why`)}</span>
              <span className="text-muted-foreground"><span className="font-semibold">{t('page.issueFix')}:</span> {t(`page.issueReason.${reason}.fix`)}</span>
            </div>
          </div>
        )
      })}
      {issues.length > max && (
        <div className="text-xs text-muted-foreground">{t('page.issueMore', issues.length - max)}</div>
      )}
    </div>
  )
}
