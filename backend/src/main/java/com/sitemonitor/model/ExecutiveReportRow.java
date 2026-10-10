package com.sitemonitor.model;

/**
 * Aylık yönetici özeti gönderim kaydının ORTAK yüzü (2026-10-10) — kurum geneli ({@link ExecutiveSummaryReport}) ve takım
 * ({@link ExecutiveSummaryTeamReport}) kayıtları aynı "tam bir kez" talep / bitirme mantığından geçer. Yalnız arayüz:
 * JPA eşlemesi her varlıkta ayrı kalır (kalıtım yok, mevcut tablo eşlemesi değişmez); yöntemleri Lombok {@code @Data}
 * üretir.
 */
public interface ExecutiveReportRow {

    /** Gönderim sürüyor (talep alındı). */
    String SENDING = "SENDING";
    /** Tüm dilimler gönderildi (ya da kuyruğa alındı). */
    String SENT = "SENT";
    /** Dilimlerin bir kısmı gitti, bir kısmı başarısız. */
    String PARTIAL = "PARTIAL";
    /** Hiçbir dilim gitmedi — tavan dolana dek saatlik telafi yeniden dener. */
    String FAILED = "FAILED";
    /** Alıcı yok. */
    String NO_RECIPIENT = "NO_RECIPIENT";
    /** Zamanlanmış koşu geldi ama özet kapalı — gönderilmedi, iz bırakıldı. */
    String SKIPPED_DISABLED = "SKIPPED_DISABLED";
    /** E-posta kanalı kapalı (SMTP susturulmuş) — gönderilmedi. */
    String SKIPPED_MAIL_OFF = "SKIPPED_MAIL_OFF";

    Long getId();

    Integer getReportYear();
    void setReportYear(Integer v);

    Integer getReportMonth();
    void setReportMonth(Integer v);

    String getStatus();
    void setStatus(String v);

    String getTriggerKind();
    void setTriggerKind(String v);

    Integer getAttempts();
    void setAttempts(Integer v);

    Integer getRecipientCount();
    void setRecipientCount(Integer v);

    Integer getChunkCount();
    void setChunkCount(Integer v);

    String getDetail();
    void setDetail(String v);

    String getSubject();
    void setSubject(String v);

    String getSummaryStatus();
    void setSummaryStatus(String v);

    String getSummaryJson();
    void setSummaryJson(String v);

    String getActor();
    void setActor(String v);

    String getClaimedAt();
    void setClaimedAt(String v);

    String getSentAt();
    void setSentAt(String v);

    String getCreatedAt();
    void setCreatedAt(String v);
}
