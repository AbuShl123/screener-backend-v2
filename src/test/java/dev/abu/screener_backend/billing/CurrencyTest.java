package dev.abu.screener_backend.billing;

import dev.abu.screener_backend.error.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Currency} (E10): per-currency decimal places, case-insensitive resolution with
 * rejection of badly-formatted/unsupported codes, and {@code requireScale} accepting amounts within the
 * currency's decimals (trailing zeros tolerated) while rejecting over-scale amounts.
 */
class CurrencyTest {

    @Test
    void decimalsPerCurrency() {
        assertEquals(2, Currency.UZS.decimals());
        assertEquals(2, Currency.USD.decimals());
        assertEquals(8, Currency.BTC.decimals());
        assertEquals(18, Currency.ETH.decimals());
    }

    @Test
    void ofNormalisesCaseAndWhitespace() {
        assertSame(Currency.UZS, Currency.of("uzs"));
        assertSame(Currency.UZS, Currency.of("  UZS  "));
        assertSame(Currency.ETH, Currency.of("eth"));
    }

    @Test
    void ofRejectsBadFormatAndUnsupported() {
        assertThrows(ApiException.class, () -> Currency.of(null));
        assertThrows(ApiException.class, () -> Currency.of(""));
        assertThrows(ApiException.class, () -> Currency.of("US"));    // too short
        assertThrows(ApiException.class, () -> Currency.of("US1"));   // not all letters
        assertThrows(ApiException.class, () -> Currency.of("EUR"));   // well-formed but unsupported
    }

    @Test
    void requireScaleAcceptsWithinDecimalsToleratingTrailingZeros() {
        assertDoesNotThrow(() -> Currency.UZS.requireScale(new BigDecimal("19.99")));
        assertDoesNotThrow(() -> Currency.UZS.requireScale(new BigDecimal("19.9")));
        assertDoesNotThrow(() -> Currency.UZS.requireScale(new BigDecimal("19.900"))); // significant scale 1
        assertDoesNotThrow(() -> Currency.UZS.requireScale(new BigDecimal("50000")));
        assertDoesNotThrow(() -> Currency.ETH.requireScale(new BigDecimal("0.000000000000000001"))); // 18 dp
    }

    @Test
    void requireScaleRejectsOverScale() {
        assertThrows(ApiException.class, () -> Currency.UZS.requireScale(new BigDecimal("480.888")));
        assertThrows(ApiException.class, () -> Currency.USD.requireScale(new BigDecimal("19.999")));
        assertThrows(ApiException.class, () -> Currency.BTC.requireScale(new BigDecimal("0.000000001"))); // 9 dp > 8
    }
}
