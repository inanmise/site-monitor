package com.certmonitor.util;

import java.security.SecureRandom;

/**
 * Generates temporary passwords for the admin auto-reset flow.
 *
 * 10 characters long, guaranteed to contain at least one uppercase, one
 * lowercase and one digit so the password meets the strength-meter "good"
 * level and the server-side 6-10 / character-class validation. Visually
 * ambiguous characters (I, l, O, 0, 1) are excluded so users do not
 * mistype them when copying from email.
 */
public final class PasswordGenerator {
    private static final SecureRandom RNG = new SecureRandom();

    private static final String UPPER  = "ABCDEFGHJKLMNPQRSTUVWXYZ";   // no I, O
    private static final String LOWER  = "abcdefghijkmnpqrstuvwxyz";   // no l, o
    private static final String DIGITS = "23456789";                   // no 0, 1
    private static final String ALL    = UPPER + LOWER + DIGITS;

    private PasswordGenerator() {}

    public static String generate() {
        char[] buf = new char[10];
        buf[0] = UPPER.charAt(RNG.nextInt(UPPER.length()));
        buf[1] = LOWER.charAt(RNG.nextInt(LOWER.length()));
        buf[2] = DIGITS.charAt(RNG.nextInt(DIGITS.length()));
        for (int i = 3; i < buf.length; i++) {
            buf[i] = ALL.charAt(RNG.nextInt(ALL.length()));
        }
        // Fisher-Yates shuffle so the first three positions are not predictable.
        for (int i = buf.length - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            char t = buf[i]; buf[i] = buf[j]; buf[j] = t;
        }
        return new String(buf);
    }
}
