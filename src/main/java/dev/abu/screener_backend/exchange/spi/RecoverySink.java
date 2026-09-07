package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.book.BookSlot;

public interface RecoverySink {

    boolean requestRecovery(BookSlot slot);

}
