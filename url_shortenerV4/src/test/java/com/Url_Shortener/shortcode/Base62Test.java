package com.Url_Shortener.shortcode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

    @Test
    void encodesKnownValues() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
        assertThat(Base62.encode(Long.MAX_VALUE)).isEqualTo("AzL8n0Y58m7");
    }

    @Test
    void decodeReversesEncode() {
        for (long value : new long[]{0, 1, 61, 62, 3843, 123_456_789_012L, Long.MAX_VALUE}) {
            assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("ab-c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
    }
}
