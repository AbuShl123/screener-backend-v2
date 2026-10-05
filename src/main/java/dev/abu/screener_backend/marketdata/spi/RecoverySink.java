package dev.abu.screener_backend.marketdata.spi;

import dev.abu.screener_backend.marketdata.core.book.BookSlot;

public interface RecoverySink {

    boolean requestRecovery(BookSlot slot);

}
