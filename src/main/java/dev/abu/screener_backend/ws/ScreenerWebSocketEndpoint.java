package dev.abu.screener_backend.ws;

import dev.abu.screener_backend.feed.OrderBookBroadcaster;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ServerEndpoint(value = "/ws", configurator = CustomSpringConfigurator.class)
public class ScreenerWebSocketEndpoint {

    // Injected once into the singleton bean. All @OnXxx handlers share this reference safely.
    @Autowired
    private OrderBookBroadcaster broadcaster;

    @OnOpen
    public void onOpen(Session session) {
        UserWebSocketSession userSession = new UserWebSocketSession(session);
        session.getUserProperties().put("session", userSession);
        userSession.startSendLoop();
        broadcaster.addSession(userSession);
        log.debug("WebSocket opened: {}", session.getId());
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;
        userSession.disconnect();
        broadcaster.removeSession(userSession);
        log.debug("WebSocket closed: {} reason={}", session.getId(), reason.getCloseCode());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("WebSocket error on session {}: {}", session.getId(), error.getMessage());
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;
        userSession.disconnect();
        broadcaster.removeSession(userSession);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        UserWebSocketSession userSession = getSession(session);
        if (userSession == null) return;

        String trimmed = message.trim();
        if ("SNAPSHOT_REQUEST".equals(trimmed)) {
            userSession.setStatus(UserWebSocketSession.Status.NEED_SNAPSHOT);
            log.debug("SNAPSHOT_REQUEST from session {}", session.getId());
            return;
        }
        // Future: parse JSON {"type":"..."} and dispatch on the type field
        log.debug("Unknown message from session {}: {}", session.getId(), trimmed);
    }

    private UserWebSocketSession getSession(Session session) {
        return (UserWebSocketSession) session.getUserProperties().get("session");
    }
}
