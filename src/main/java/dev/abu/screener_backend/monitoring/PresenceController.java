package dev.abu.screener_backend.monitoring;

import dev.abu.screener_backend.analysis.UserFeedRegistry;
import dev.abu.screener_backend.analysis.UserFeedRegistry.UserPresence;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Live presence endpoint — answers "who is connected to the screener right now".
 *
 * <p>Reads the in-memory session registry ({@link UserFeedRegistry}); no database access and no
 * persistence. This reflects the instant the request is served only — it carries no history. For
 * connection frequency / historical usage a separate persisted module would be required.
 *
 * <p>Requires a valid Bearer JWT (any authenticated user) — there is no {@code ADMIN} role yet, so
 * access is intentionally open to all logged-in users for now.
 */
@RestController
@RequestMapping("/api/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final UserFeedRegistry userFeedRegistry;

    /**
     * Returns the set of currently-connected users with their open session counts.
     *
     * @return online user count, total session count, and the per-user breakdown
     */
    @GetMapping
    public PresenceResponse getPresence() {
        List<UserPresence> users = userFeedRegistry.presenceSnapshot();
        int totalSessions = users.stream().mapToInt(UserPresence::sessions).sum();
        List<UserPresence> sorted = users.stream()
                .sorted(Comparator.comparingInt(UserPresence::sessions).reversed())
                .toList();
        return new PresenceResponse(sorted.size(), totalSessions, sorted);
    }

    /**
     * Response body for {@code GET /api/presence}.
     *
     * @param onlineUsers   number of distinct connected users
     * @param totalSessions total open WebSocket sessions across all users
     * @param users         per-user breakdown, sorted by session count descending
     */
    public record PresenceResponse(
            int onlineUsers,
            int totalSessions,
            List<UserPresence> users
    ) {}
}
