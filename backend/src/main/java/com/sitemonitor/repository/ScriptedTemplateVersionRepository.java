package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Şablon sürüm geçmişi — {@code ScriptedScriptVersionRepository}'nin birebir eşi.
 * Burada türetilmiş adlar yeterli: sorgular basit ve kapsam kuralı içermiyor.
 */
public interface ScriptedTemplateVersionRepository extends JpaRepository<ScriptedTemplateVersion, Long> {

    /** Zaman çizelgesi: en yeni sürüm üstte. */
    List<ScriptedTemplateVersion> findByTemplateIdOrderBySequenceNoDesc(Long templateId);

    /** Bir sonraki {@code sequenceNo} ve sürüm etiketi bundan türetilir. */
    Optional<ScriptedTemplateVersion> findTopByTemplateIdOrderBySequenceNoDesc(Long templateId);

    /**
     * Şablon kalıcı silindiğinde geçmişini de temizlemek için (soft delete'te DOKUNULMAZ).
     * {@code @Transactional} ŞART — türetilmiş silmeye Spring Data kendiliğinden tx sarmaz ve
     * {@code open-in-view=false} olduğu için çağrı tx'siz düşer (bkz. {@code ScriptedDraftRepository}).
     */
    @Transactional
    int deleteByTemplateId(Long templateId);
}
