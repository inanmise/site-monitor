package com.sitemonitor.service.monitorcsv;

import java.util.ArrayList;
import java.util.List;

/**
 * İzleme CSV içe aktarımının ayrıştırıcısı (2026-11-19) — RFC 4180, DOĞRUSAL, sınırlı.
 *
 * <p><b>Kurallar.</b> Tırnaklı alan {@code "…"} (içinde {@code ""} = bir tırnak, virgül, noktalı virgül ve satır sonu serbest);
 * satır sonu CRLF, LF ya da tek CR; baştaki UTF-8 BOM atılır; tamamen boş satırlar (her hücre boş) sayılmaz. Ayırıcı başlık
 * satırından seçilir: tırnak dışında {@code ;} sayısı {@code ,} sayısından fazlaysa noktalı virgül (Türkçe Excel CSV'yi böyle
 * kaydeder), aksi halde virgül. Tırnaksız alan içindeki tırnak harf olarak alınır (hoşgörü); kapanan tırnaktan sonra ayırıcı
 * ya da satır sonu dışında karakter → hata.
 *
 * <p><b>Sınırlar (kullanıcı kuralı: sınırsız döngü yok).</b> Metin ≤ {@link #MAX_CHARS}, veri satırı ≤ {@link #MAX_ROWS},
 * sütun ≤ {@link #MAX_COLUMNS}, hücre ≤ {@link #MAX_CELL_CHARS} karakter. Her karakter bir kez okunur; aşılan sınır, kaçıncı
 * satırda olduğunu söyleyen {@link CsvFormatException} ile durur (geri kalan okunmaz).
 */
public final class MonitorCsvParser {

    /** İçe aktarılan metnin üst sınırı (karakter) — denetleyici gövdeyi okumadan önce de bayt olarak sınırlar. */
    public static final int MAX_CHARS = 5 * 1024 * 1024;
    /** Başlık dışındaki satır sınırı. */
    public static final int MAX_ROWS = 2000;
    /** Sütun sınırı (başlık ve her satır). */
    public static final int MAX_COLUMNS = 200;
    /** Hücre sınırı (karakter) — k6 betiği ve SQL çok satırlı tek hücre olabilir. */
    public static final int MAX_CELL_CHARS = 64 * 1024;

    private MonitorCsvParser() { }

    /** Ayrıştırma hatası — {@code line} metindeki fiziksel satır (1'den), {@code reason} kalıcı kod. */
    public static final class CsvFormatException extends Exception {
        private final int line;
        private final String reason;

        public CsvFormatException(String reason, int line) {
            super(reason + " @" + line);
            this.reason = reason;
            this.line = line;
        }

        public int line() { return line; }
        public String reason() { return reason; }
    }

    /** Bir kayıt: hücreler + kaydın başladığı fiziksel satır (çok satırlı hücrede kayıt birden fazla satır kaplar). */
    public record Record(int line, List<String> cells) {
        /** Her hücre boş mu (yalnız boşluk dahil). */
        public boolean blank() {
            for (String c : cells) if (c != null && !c.isBlank()) return false;
            return true;
        }
    }

    /** Sonuç: başlık + veri satırları + seçilen ayırıcı. */
    public record Parsed(Record header, List<Record> rows, char delimiter) { }

    /** Hata kodları (kalıcı — arayüz {@code mcsv.parse.<kod>} metnini çizer). */
    public static final String E_EMPTY = "empty";
    public static final String E_TOO_LARGE = "too_large";
    public static final String E_TOO_MANY_ROWS = "too_many_rows";
    public static final String E_TOO_MANY_COLUMNS = "too_many_columns";
    public static final String E_CELL_TOO_LONG = "cell_too_long";
    public static final String E_UNCLOSED_QUOTE = "unclosed_quote";
    public static final String E_BAD_QUOTE = "bad_quote";

    public static Parsed parse(String text) throws CsvFormatException {
        if (text == null) throw new CsvFormatException(E_EMPTY, 1);
        if (text.length() > MAX_CHARS) throw new CsvFormatException(E_TOO_LARGE, 1);
        int start = 0;
        if (!text.isEmpty() && text.charAt(0) == '﻿') start = 1;
        char delim = detectDelimiter(text, start);

        List<Record> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int line = 1;
        int recordLine = 1;
        boolean quoted = false;      // şu an tırnaklı alanın İÇİNDE miyiz
        boolean wasQuoted = false;   // bu alan tırnakla başladı ve kapandı
        boolean fieldStart = true;
        int dataRows = 0;
        int n = text.length();
        int i = start;
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { append(cell, '"', line); i += 2; continue; }
                    quoted = false;
                    wasQuoted = true;
                    i++;
                    continue;
                }
                if (c == '\r') {
                    // tırnak içindeki satır sonu hücreye LF olarak girer (CRLF / tek CR)
                    if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    append(cell, '\n', line);
                    line++;
                    i++;
                    continue;
                }
                if (c == '\n') line++;
                append(cell, c, line);
                i++;
                continue;
            }
            if (c == '"' && fieldStart) {
                quoted = true;
                fieldStart = false;
                i++;
                continue;
            }
            if (c == delim) {
                endCell(cells, cell, line);
                wasQuoted = false;
                fieldStart = true;
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                endCell(cells, cell, line);
                Record r = new Record(recordLine, List.copyOf(cells));
                if (!r.blank()) {
                    if (!records.isEmpty() && ++dataRows > MAX_ROWS) throw new CsvFormatException(E_TOO_MANY_ROWS, recordLine);
                    records.add(r);
                }
                cells.clear();
                wasQuoted = false;
                fieldStart = true;
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                i++;
                line++;
                recordLine = line;
                continue;
            }
            if (wasQuoted) throw new CsvFormatException(E_BAD_QUOTE, line);
            fieldStart = false;
            append(cell, c, line);
            i++;
        }
        if (quoted) throw new CsvFormatException(E_UNCLOSED_QUOTE, recordLine);
        if (!fieldStart || cell.length() > 0 || !cells.isEmpty()) {
            endCell(cells, cell, line);
            Record r = new Record(recordLine, List.copyOf(cells));
            if (!r.blank()) {
                if (!records.isEmpty() && ++dataRows > MAX_ROWS) throw new CsvFormatException(E_TOO_MANY_ROWS, recordLine);
                records.add(r);
            }
        }
        if (records.isEmpty()) throw new CsvFormatException(E_EMPTY, 1);
        return new Parsed(records.get(0), List.copyOf(records.subList(1, records.size())), delim);
    }

    private static void append(StringBuilder cell, char c, int line) throws CsvFormatException {
        if (cell.length() >= MAX_CELL_CHARS) throw new CsvFormatException(E_CELL_TOO_LONG, line);
        cell.append(c);
    }

    private static void endCell(List<String> cells, StringBuilder cell, int line) throws CsvFormatException {
        if (cells.size() >= MAX_COLUMNS) throw new CsvFormatException(E_TOO_MANY_COLUMNS, line);
        cells.add(cell.toString());
        cell.setLength(0);
    }

    /** İlk fiziksel satırın tırnak dışındaki ayırıcı sayımı: {@code ;} fazlaysa noktalı virgül, değilse virgül. */
    static char detectDelimiter(String text, int start) {
        int commas = 0;
        int semis = 0;
        boolean q = false;
        int n = text.length();
        for (int i = start; i < n; i++) {
            char c = text.charAt(i);
            if (c == '"') { q = !q; continue; }
            if (q) continue;
            if (c == '\r' || c == '\n') break;
            if (c == ',') commas++;
            else if (c == ';') semis++;
        }
        return semis > commas ? ';' : ',';
    }
}
