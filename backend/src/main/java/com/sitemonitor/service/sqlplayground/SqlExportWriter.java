package com.sitemonitor.service.sqlplayground;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sitemonitor.service.SqlPlaygroundService;
import com.sitemonitor.util.Csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SQL Playground dışa aktarım yazıcısı (2026-11-13, C3) — satırları bir akışa (geçici dosya) tek tek yazar; bellekte sonuç
 * tutmaz. CSV: BOM + başlık + {@link Csv#row} (formül koruması). JSON:
 * {@code {"success":true,"data":{"columns":[…],"rows":[[…]],"row_count":n,"truncated":b,"max_rows":m,"masked_columns":[…]}}}
 * — satırlar DİZİ (aynı adlı iki kolon kaybolmasın), değerler düz metin / sayı / mantıksal (zaman damgaları metin).
 *
 * <p>Yazılan karakter {@link #MAX_CHARS}'ı aşınca okuma durur ({@link #charLimitHit()}) — dosya sınırsız büyümez.
 */
public final class SqlExportWriter implements SqlPlaygroundService.RowSink, AutoCloseable {

    /** Dışa aktarım dosyasının üst sınırı (karakter ≈ bayt). */
    public static final long MAX_CHARS = 256L * 1024 * 1024;

    private final boolean json;
    private final CountingWriter out;
    private JsonGenerator gen;
    private long charLimit = MAX_CHARS;
    private boolean limitHit;
    private int rows;

    public SqlExportWriter(String format, OutputStream os) throws IOException {
        this.json = "json".equals(format);
        this.out = new CountingWriter(new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 64 * 1024));
        if (json) {
            gen = new JsonFactory().createGenerator(out);
            gen.writeStartObject();
            gen.writeBooleanField("success", true);
            gen.writeFieldName("data");
            gen.writeStartObject();
        } else {
            out.write('﻿');
        }
    }

    /** Testler. */
    void setCharLimit(long limit) { this.charLimit = limit; }

    @Override
    public void columns(String[] labels) throws IOException {
        if (json) {
            gen.writeArrayFieldStart("columns");
            for (String l : labels) gen.writeString(l);
            gen.writeEndArray();
            gen.writeArrayFieldStart("rows");
        } else {
            out.write(Csv.row((Object[]) labels));
        }
    }

    @Override
    public boolean row(Object[] values) throws IOException {
        if (json) {
            gen.writeStartArray();
            for (Object v : values) writeJson(v);
            gen.writeEndArray();
            gen.flush();
        } else {
            Object[] cells = new Object[values.length];
            for (int i = 0; i < values.length; i++) cells[i] = text(values[i]);
            out.write(Csv.row(cells));
        }
        rows++;
        if (out.count() > charLimit) {
            limitHit = true;
            return false;
        }
        return true;
    }

    /** JSON kuyruğunu yazar (CSV'de no-op). */
    public void finish(int rowCount, boolean truncated, int maxRows, List<String> masked) throws IOException {
        if (json) {
            if (gen.getOutputContext().inArray()) gen.writeEndArray();
            else {
                gen.writeArrayFieldStart("columns");
                gen.writeEndArray();
                gen.writeArrayFieldStart("rows");
                gen.writeEndArray();
            }
            gen.writeNumberField("row_count", rowCount);
            gen.writeBooleanField("truncated", truncated);
            gen.writeNumberField("max_rows", maxRows);
            gen.writeArrayFieldStart("masked_columns");
            for (String m : masked) gen.writeString(m);
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
            gen.flush();
        }
        out.flush();
    }

    public boolean charLimitHit() { return limitHit; }

    public int rows() { return rows; }

    @Override
    public void close() throws IOException {
        if (gen != null) {
            try { gen.close(); } catch (IOException ignored) { }
        }
        out.close();
    }

    private void writeJson(Object v) throws IOException {
        if (v == null) gen.writeNull();
        else if (v instanceof Boolean b) gen.writeBoolean(b);
        else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) gen.writeNumber(((Number) v).longValue());
        else if (v instanceof BigDecimal d) gen.writeNumber(d);
        else if (v instanceof BigInteger d) gen.writeNumber(d);
        else if (v instanceof Double d && !d.isNaN() && !d.isInfinite()) gen.writeNumber(d);
        else if (v instanceof Float f && !f.isNaN() && !f.isInfinite()) gen.writeNumber(f);
        else gen.writeString(text(v));
    }

    /** Değerin metin karşılığı: ikili veri boyutuyla, zaman damgaları ISO'ya yakın {@code toString}. */
    static String text(Object v) {
        if (v == null) return null;
        if (v instanceof byte[] b) return "[binary " + b.length + " bytes]";
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.sql.Array a) {
            try {
                Object arr = a.getArray();
                if (arr instanceof Object[] objs) return java.util.Arrays.toString(objs);
                return String.valueOf(arr);
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

    /** Yazılan karakteri sayan sarmalayıcı. */
    static final class CountingWriter extends Writer {
        private final Writer w;
        private long count;

        CountingWriter(Writer w) { this.w = w; }

        long count() { return count; }

        @Override public void write(char[] cbuf, int off, int len) throws IOException { w.write(cbuf, off, len); count += len; }
        @Override public void write(int c) throws IOException { w.write(c); count++; }
        @Override public void write(String s, int off, int len) throws IOException { w.write(s, off, len); count += len; }
        @Override public void flush() throws IOException { w.flush(); }
        @Override public void close() throws IOException { w.close(); }
    }
}
