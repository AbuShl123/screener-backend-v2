package dev.abu.screener_backend.feed;

import dev.abu.screener_backend.marketdata.Instrument;

import java.util.List;

/**
 * JSON framing shared by every {@link FeedChannel}. Every message except {@code SNAPSHOT} is
 * {@code {"type":…,"exchange":…,"market":…,"symbol":…,"data":…}}; only {@code data} differs
 * between types. Type names are each channel's own constants — this class doesn't list them.
 */
public final class Envelope {

    private Envelope() {}

    /**
     * Appends {@code {"type":"<type>","exchange":"…","market":"…","symbol":"…","data":} to
     * {@code sb}; the caller writes {@code data}, then {@code '}'}. {@code symbol} is the normalized
     * {@code BASEQUOTE} form, matching the rule API.
     */
    public static void head(StringBuilder sb, String type, Instrument inst) {
        sb.append("{\"type\":\"").append(type).append('"');
        sb.append(",\"exchange\":\"").append(inst.exchange().name()).append('"');
        sb.append(",\"market\":\"").append(inst.market().name()).append('"');
        sb.append(",\"symbol\":\"").append(inst.symbol()).append('"');
        sb.append(",\"data\":");
    }

    /**
     * {@code {"type":"SNAPSHOT","data":[e1,e2,…]}}. Each entry is a complete message, exactly as the
     * client would receive it live.
     */
    public static String snapshot(List<String> entries) {
        int size = 32;
        for (String e : entries) size += e.length() + 1;
        StringBuilder sb = new StringBuilder(size);
        sb.append("{\"type\":\"SNAPSHOT\",\"data\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }
}
