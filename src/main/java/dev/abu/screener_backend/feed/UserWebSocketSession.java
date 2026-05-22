package dev.abu.screener_backend.feed;

import lombok.Getter;

/**
 * Stub — no real WebSocket session yet. sendData() is a no-op placeholder for Phase 5.
 * Status is volatile because the Phase-5 WebSocket receive callback (a different thread)
 * will set it back to NEED_SNAPSHOT on a SNAPSHOT_REQUEST from the client.
 */
public class UserWebSocketSession {

    public enum Status { NEED_SNAPSHOT, READY }

    @Getter
    private volatile Status status = Status.NEED_SNAPSHOT;

    // Accessed only by the sender thread — no volatile or atomic needed.
    // Reset to 0 when status returns to NEED_SNAPSHOT; first sendData() makes it 1.
    private int seqNumber = 0;

    public void setStatus(Status status) {
        if (status == Status.NEED_SNAPSHOT) seqNumber = 0;
        this.status = status;
    }

    /** Increments seq, then transmits. No-op stub until Phase 5 wires a real session. */
    public void sendData(String json) {
        // Phase 5: replace with session.sendMessage(json)
    }

    /** Returns the next seq number (pre-increment). Called by the broadcaster before serialization. */
    public int getAndIncrementSeq() {
        return ++seqNumber;
    }
}
