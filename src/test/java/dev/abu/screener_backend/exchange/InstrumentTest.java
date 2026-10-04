package dev.abu.screener_backend.exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the three derived keys built by {@link Instrument#of}.
 *
 * <p>Only Binance has a spot venue today, so the cross-exchange spot case uses a hand-rolled
 * instrument (the record constructor is public) with a different {@code feedKey}.
 */
public class InstrumentTest {

    @Test
    @DisplayName("Binance ruleKey is byte-identical to the stored rule format, nativeSymbol:MARKET")
    void binanceRuleKeyMatchesStoredRuleFormat() {
        Instrument spot = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
        Instrument fut = Instrument.of(1, Venue.BINANCE_FUTURES, "1000PEPEUSDT", "1000PEPE", "USDT");

        // ClassificationRuleService.buildRuntimeRule keys rules as row.symbol + ":" + row.market,
        // and existing rows were written with Binance native symbols — these must keep matching.
        assertEquals(spot.nativeSymbol() + ":" + Market.SPOT, spot.ruleKey());
        assertEquals(fut.nativeSymbol() + ":" + Market.FUTURES, fut.ruleKey());
    }

    @Test
    @DisplayName("symbol and ruleKey come from base + quote, not the native spelling")
    void symbolAndRuleKeyIgnoreNativeSymbol() {
        Instrument inst = Instrument.of(0, Venue.BINANCE_FUTURES, "BTC_USDT", "BTC", "USDT");

        assertEquals("BTCUSDT", inst.symbol());
        assertEquals("BTCUSDT:FUTURES", inst.ruleKey());
        assertEquals("BINANCE:FUTURES:BTCUSDT", inst.feedKey());
    }

    @Test
    @DisplayName("feedKey is EXCHANGE:MARKET:SYMBOL and differs between spot and futures")
    void feedKeyFormat() {
        Instrument spot = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
        Instrument fut = Instrument.of(1, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT");

        assertEquals("BINANCE:SPOT:BTCUSDT", spot.feedKey());
        assertEquals("BINANCE:FUTURES:BTCUSDT", fut.feedKey());
        assertNotEquals(spot.ruleKey(), fut.ruleKey());
    }

    @Test
    @DisplayName("same symbol on two exchanges: different feedKey, same ruleKey")
    void crossExchangeKeys() {
        Instrument binance = Instrument.of(0, Venue.BINANCE_SPOT, "BTCUSDT", "BTC", "USDT");
        Instrument other = otherExchangeSpot(1, "BTC_USDT", "BTC", "USDT");

        assertNotEquals(binance.feedKey(), other.feedKey());
        assertEquals(binance.ruleKey(), other.ruleKey());
        assertEquals(binance.symbol(), other.symbol());
    }

    @Test
    @DisplayName("MEXC futures: normalized symbol and ruleKey, venue-specific feedKey and logName")
    void mexcFuturesKeys() {
        Instrument mexc = Instrument.of(0, Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001);
        Instrument binance = Instrument.of(1, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT");

        assertEquals("BTCUSDT", mexc.symbol());
        assertEquals(binance.ruleKey(), mexc.ruleKey());
        assertEquals("MEXC:FUTURES:BTCUSDT", mexc.feedKey());
        assertEquals("MEXC_FUTURES/BTC_USDT", mexc.logName());
        assertEquals(Exchange.MEXC, mexc.exchange());
    }

    @Test
    @DisplayName("quantityMultiplier defaults to 1.0 and is carried as given")
    void quantityMultiplier() {
        assertEquals(1.0, Instrument.of(0, Venue.BINANCE_FUTURES, "BTCUSDT", "BTC", "USDT").quantityMultiplier());
        assertEquals(0.0001, Instrument.of(1, Venue.MEXC_FUTURES, "BTC_USDT", "BTC", "USDT", 0.0001)
                .quantityMultiplier());
    }

    /**
     * Stand-in for a second exchange's spot instrument until a second exchange has a spot venue. Its
     * venue (and so its payload {@code exchange}) is still Binance; only the keys differ.
     */
    public static Instrument otherExchangeSpot(int id, String nativeSymbol, String base, String quote) {
        String symbol = base + quote;
        return new Instrument(id, Venue.BINANCE_SPOT, nativeSymbol, base, quote, 1.0,
                symbol, symbol + ":SPOT", "OTHER:SPOT:" + symbol, "OTHER_SPOT/" + nativeSymbol);
    }
}
