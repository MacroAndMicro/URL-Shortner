package com.Url_Shortener.shortcode;

/**
 * Base62 encoding of non-negative longs using 0-9, A-Z, a-z.
 * A Snowflake ID encodes to 10-11 characters.
 */
public final class Base62 {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot encode a negative value: " + value);
        }
        if (value == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(11);
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % BASE)));
            value /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Cannot decode an empty string");
        }
        long value = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int digit = ALPHABET.indexOf(encoded.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Not a base62 character: " + encoded.charAt(i));
            }
            value = Math.addExact(Math.multiplyExact(value, BASE), digit);
        }
        return value;
    }
}
