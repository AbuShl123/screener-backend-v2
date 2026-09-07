package dev.abu.screener_backend.exchange.spi;

import dev.abu.screener_backend.exchange.book.BookSlot;
import dev.abu.screener_backend.exchange.ingress.DepthEvent;

public interface DepthSyncStrategy {

    BookSyncContext newContext();

    void onEvent(BookSlot slot, DepthEvent event);

}
