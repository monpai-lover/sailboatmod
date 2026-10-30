package com.monpai.sailboatmod.client.ui.market;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pinyin initials of Chinese text, so the market search finds 钻石 by typing "zs".
 *
 * <p>{@code pinyin-initials.txt} holds one letter per code point from U+4E00 to U+9FA5: the first letter of the
 * character's most common reading, "_" when it has none. It was generated with pypinyin 0.53.0
 * ({@code Style.FIRST_LETTER}); the JDK's Chinese collator is not pinyin-ordered, so it cannot derive this at run
 * time.</p>
 */
public final class PinyinInitials {
    private static final char FIRST = '\u4e00';
    private static final String TABLE = load();

    private PinyinInitials() {
    }

    /**
     * Lower-case initials of the Chinese characters in {@code text}, in order; everything else is skipped, so
     * {@code "下界合金锭"} gives {@code "xjhjd"} and an English name gives {@code ""}.
     */
    public static String of(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int index = text.charAt(i) - FIRST;
            if (index >= 0 && index < TABLE.length() && TABLE.charAt(index) != '_') {
                out.append(Character.toLowerCase(TABLE.charAt(index)));
            }
        }
        return out.toString();
    }

    private static String load() {
        try (InputStream in = PinyinInitials.class.getResourceAsStream("pinyin-initials.txt")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
