package com.Url_Shortener.shortcode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortCodeTest {

    @Test
    void customCodesAreStoredInLowerCase() {
        assertThat(ShortCode.normalizeCustom("My-Portfolio")).isEqualTo("my-portfolio");
        assertThat(ShortCode.normalizeCustom("  shop2026 ")).isEqualTo("shop2026");
    }

    @Test
    void customCodesAreFourToThirtyCharacters() {
        assertThat(ShortCode.normalizeCustom("abcd")).isEqualTo("abcd");
        assertThat(ShortCode.normalizeCustom("a".repeat(30))).hasSize(30);
        assertThatThrownBy(() -> ShortCode.normalizeCustom("abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4 to 30");
        assertThatThrownBy(() -> ShortCode.normalizeCustom("a".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShortCode.normalizeCustom(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCodesUseLettersDigitsAndInnerHyphens() {
        for (String bad : new String[]{"-abcd", "abcd-", "ab_cd", "ab cd", "ab.cd", "abçd"}) {
            assertThatThrownBy(() -> ShortCode.normalizeCustom(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reservedWordsAreRefusedInAnyCase() {
        assertThatThrownBy(() -> ShortCode.normalizeCustom("Admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> ShortCode.normalizeCustom("LOGIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyValidCustomCodeIsAcceptedByTheRedirectRoute() {
        assertThat(ShortCode.isShortCodePath("/" + ShortCode.normalizeCustom("My-Link-2026"))).isTrue();
        assertThat(ShortCode.isShortCodePath("/" + "a".repeat(30))).isTrue();
    }
}
