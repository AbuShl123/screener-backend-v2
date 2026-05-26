package dev.abu.screener_backend.ws;

import dev.abu.screener_backend.auth.AuthenticatedUser;
import dev.abu.screener_backend.auth.JwtService;
import dev.abu.screener_backend.feed.OrderBookBroadcaster;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@ServerEndpoint(value = "/ws", configurator = CustomSpringConfigurator.class)
public class ScreenerWebSocketEndpoint {

    @Autowired
    private OrderBookBroadcaster broadcaster;

    @Autowired
    private JwtService jwtService;

    @OnOpen
    public void onOpen(Session session) {
        List<String> tokens = session.getRequestParameterMap().get("token");
        if (tokens == null || tokens.isEmpty()) {
            closeUnauthorized(session, "Missing token");
            return;
        }
        AuthenticatedUser user = jwtService.validateAndExtract(tokens.get(0));
        if (user == null) {
            closeUnauthorized(session, "Invalid or expired token");
            return;
        }

        UserWebSocketSession userSession = new UserWebSocketSession(session, user.userId());
        session.getUserProperties().put("session", userSession);
        userSession.startSendLoop();
        broadcaster.addSession(userSession);
        log.debug("WebSocket opened: {} user={}", session.getId(), user.userId());
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
        log.debug("Unknown message from session {}: {}", session.getId(), trimmed);
    }

    private UserWebSocketSession getSession(Session session) {
        return (UserWebSocketSession) session.getUserProperties().get("session");
    }

    private void closeUnauthorized(Session session, String reason) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (IOException e) {
            log.debug("Error closing unauthorized session {}: {}", session.getId(), e.getMessage());
        }
    }
}
