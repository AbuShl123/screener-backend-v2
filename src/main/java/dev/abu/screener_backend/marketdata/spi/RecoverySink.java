package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.slot.InstrumentSlot;

public interface RecoverySink {

    boolean requestRecovery(InstrumentSlot slot);

}
