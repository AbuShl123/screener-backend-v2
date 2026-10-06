package dev.abu.screener_backend.feed;

import java.util.List;

/** JSON framing shared by every {@link FeedChannel}. */
public final class Envelope {

    private Envelope() {}

    /** {@code {"type":"SNAPSHOT","data":[e1,e2,…]}} — entries are complete JSON values. */
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
