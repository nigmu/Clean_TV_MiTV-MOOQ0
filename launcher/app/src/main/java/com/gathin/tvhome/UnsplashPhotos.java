package com.gathin.tvhome;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One Unsplash photo for the Home backdrop. The random request is a single counted call: no {@code count}
 * parameter, and {@code download_location} is never requested. Image URLs are the ones the API returned, with
 * the size and PNG parameters added. No Android types, so the rules are tested on the JVM.
 */
final class UnsplashPhotos {
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    /** A 1080p PNG is larger than the old JPEG cap. Anything past this is discarded, not decoded. */
    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    static final long HOUR_MS = 3_600_000L;

    private UnsplashPhotos() { }

    static boolean hasKey(String key) {
        return key != null && !key.trim().isEmpty();
    }

    /** 403 is how Unsplash reports the demo-app hourly cap; 429 is the usual rate-limit status. */
    static boolean rateLimited(int status) {
        return status == 403 || status == 429;
    }

    /**
     * One photo. {@code topics} is a comma-separated list of topic ids (Nature is {@code 6sMVjTLSkeQ}).
     * There is no {@code count} parameter: the body is one object, and the call counts as one.
     */
    static String randomUrl(String topics, String contentFilter) {
        StringBuilder url = new StringBuilder("https://api.unsplash.com/photos/random?");
        String encodedTopics = encodeTopics(topics);
        if (!encodedTopics.isEmpty()) url.append("topics=").append(encodedTopics).append('&');
        String filter = contentFilter == null || contentFilter.trim().isEmpty() ? "high" : contentFilter.trim();
        url.append("orientation=landscape&content_filter=").append(encode(filter));
        return url.toString();
    }

    static String joinTopics(List<String> topics) {
        if (topics == null || topics.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String topic : topics) {
            if (topic == null) continue;
            String id = topic.trim();
            if (id.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    /** The API's own image URL, asked to come back as a PNG cropped to the panel. */
    static String sized(String imageUrl) {
        return sized(imageUrl, WIDTH, HEIGHT);
    }

    static String sized(String imageUrl, int width, int height) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return null;
        int w = width > 0 ? width : WIDTH;
        int h = height > 0 ? height : HEIGHT;
        String sep = imageUrl.contains("?") ? "&" : "?";
        return imageUrl + sep + "w=" + w + "&h=" + h + "&fit=crop&fm=png";
    }

    /** File bytes are fetched only from Unsplash's image host. That download is not an API call. */
    static boolean imageHost(String url) {
        return url != null && url.startsWith("https://images.unsplash.com/");
    }

    static String referral(String pageUrl) {
        if (pageUrl == null || pageUrl.trim().isEmpty()) return null;
        if (pageUrl.contains("utm_source=")) return pageUrl;
        String sep = pageUrl.contains("?") ? "&" : "?";
        return pageUrl + sep + "utm_source=tvhome&utm_medium=referral";
    }

    /** "Name  ·  Unsplash", or just Unsplash when the photographer name is missing. */
    static String credit(Photo photo) {
        if (photo == null) return "";
        String name = photo.photographer == null ? "" : photo.photographer.trim();
        if (name.isEmpty()) return "Unsplash";
        return name + "  \u00B7  Unsplash";
    }

    /** {@code 1} is {@code 01.png}, {@code 51} is {@code 51.png}, {@code 100} is {@code 100.png}. */
    static String fileName(int number) {
        return String.format(Locale.US, "%02d.png", number);
    }

    /** The integer in {@code 01.png}, or 0 when the name is not a numbered PNG. */
    static int fileNumber(String name) {
        if (name == null || !name.endsWith(".png")) return 0;
        int digits = name.length() - 4;
        if (digits < 1) return 0;
        int value = 0;
        for (int i = 0; i < digits; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return 0;
            value = value * 10 + (c - '0');
            if (value > 1_000_000) return 0;
        }
        return value;
    }

    /** The next file number. A counter that has already moved past 1 stays there; only a fresh folder starts at 1. */
    static int nextFileNumber(int lastSaved) {
        return lastSaved < 1 ? 1 : lastSaved + 1;
    }

    /**
     * The file number removed when {@code saved} is written, or 0 while the folder is still within
     * {@code maxImages}. Saving 51 with a cap of 50 removes 1 ({@code 01.png}).
     */
    static int evictNumber(int saved, int maxImages) {
        if (maxImages < 1 || saved <= maxImages) return 0;
        return saved - maxImages;
    }

    /**
     * The next PNG to put on screen: the lowest number above {@code shown}, or the lowest number when
     * {@code shown} is already the highest. An empty folder returns 0.
     */
    static int nextShown(int shown, int[] numbers) {
        if (numbers == null || numbers.length == 0) return 0;
        int lowest = 0;
        int higher = 0;
        for (int n : numbers) {
            if (n <= 0) continue;
            if (lowest == 0 || n < lowest) lowest = n;
            if (n > shown && (higher == 0 || n < higher)) higher = n;
        }
        if (lowest == 0) return 0;
        return higher != 0 ? higher : lowest;
    }

    /**
     * Whether another /photos/random is allowed. Offline never calls. A remaining count of 0 does not call
     * until {@code waitUntil} (the reset, or an hour) has passed. With no remaining header, the local count
     * has to stay under {@code hourlyCap}.
     */
    static boolean shouldFetch(boolean online, String remaining, int callsThisHour, int hourlyCap,
            long now, long waitUntil) {
        if (!online) return false;
        if (waitUntil > now) return false;
        Integer left = parseCount(remaining);
        if (left != null) {
            if (left > 0) return true;
            return waitUntil > 0L && now >= waitUntil;
        }
        int cap = hourlyCap > 0 ? hourlyCap : 50;
        return callsThisHour < cap;
    }

    /**
     * Milliseconds before the next attempt. A skip (offline, or the cap) waits out the block; a finished
     * fetch waits the display-independent interval stored on the state.
     */
    static long nextDelay(boolean online, State state, int hourlyCap, long now, long fetchMs) {
        State current = state == null ? State.EMPTY : state;
        long interval = fetchMs > 0L ? fetchMs : 15_000L;
        if (current.waitUntilMs > now) return current.waitUntilMs - now;
        if (!online) return interval;
        if (!shouldFetch(true, current.remaining, current.calls, hourlyCap, now, current.waitUntilMs)) {
            if (current.windowStartMs > 0L) {
                long end = current.windowStartMs + HOUR_MS;
                if (end > now) return end - now;
            }
            return HOUR_MS;
        }
        if (current.fetchedAtMs > 0L && now - current.fetchedAtMs < interval) {
            return interval - (now - current.fetchedAtMs);
        }
        return 0L;
    }

    /**
     * True when {@code topic_submissions} keys hit the topic list, or {@code alt_description} / {@code description}
     * contain a keyword. Empty lists reject nothing. The caller still counts the request.
     */
    static boolean rejected(Photo photo, List<String> topics, List<String> keywords) {
        if (photo == null) return false;
        if (topics != null) {
            for (String topic : topics) {
                if (topic == null) continue;
                String want = topic.trim();
                if (want.isEmpty()) continue;
                for (String key : photo.topics) {
                    if (want.equals(key)) return true;
                }
            }
        }
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null) continue;
                String want = keyword.trim();
                if (want.isEmpty()) continue;
                if (contains(photo.altDescription, want) || contains(photo.description, want)) return true;
            }
        }
        return false;
    }

    /**
     * One photo from a /photos/random body. An error object, or a body with no usable image, is null.
     * An array (the old {@code count} shape) still yields only the first usable photo.
     */
    static Photo parse(String json) {
        return parse(json, WIDTH, HEIGHT);
    }

    static Photo parse(String json, int width, int height) {
        if (json == null) return null;
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return null;
        try {
            Object value = Json.read(trimmed);
            if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    Photo photo = photo(item, width, height);
                    if (photo != null) return photo;
                }
                return null;
            }
            return photo(value, width, height);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One line beside the PNG: photographer, profile, photo page. Tabs in the text are flattened. */
    static String creditLine(Photo photo) {
        if (photo == null) return "";
        return field(photo.photographer) + "\t" + field(photo.userLink) + "\t" + field(photo.photoLink);
    }

    static Photo readCredit(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split("\t", -1);
        String name = parts.length > 0 ? parts[0] : "";
        String user = parts.length > 1 ? parts[1] : "";
        String page = parts.length > 2 ? parts[2] : "";
        if (name.isEmpty() && user.isEmpty() && page.isEmpty()) return null;
        return new Photo("", "", name, emptyAsNull(user), emptyAsNull(page), "", "", Collections.emptyList());
    }

    private static Photo photo(Object item, int width, int height) {
        Map<?, ?> object = item instanceof Map ? (Map<?, ?>) item : null;
        if (object == null) return null;
        String id = text(object.get("id")).trim();
        String image = sized(firstUrl(object.get("urls"), "raw", "full", "regular"), width, height);
        if (id.isEmpty() || image == null) return null;
        Object links = object.get("links");
        Object user = object.get("user");
        Object userLinks = user instanceof Map ? ((Map<?, ?>) user).get("links") : null;
        String name = user instanceof Map ? text(((Map<?, ?>) user).get("name")) : "";
        String userPage = userLinks instanceof Map ? text(((Map<?, ?>) userLinks).get("html")) : "";
        String photoPage = links instanceof Map ? text(((Map<?, ?>) links).get("html")) : "";
        return new Photo(id, image, name, referral(userPage), referral(photoPage),
                text(object.get("alt_description")), text(object.get("description")),
                topicKeys(object.get("topic_submissions")));
    }

    private static List<String> topicKeys(Object submissions) {
        if (!(submissions instanceof Map)) return Collections.emptyList();
        List<String> keys = new ArrayList<>();
        for (Object key : ((Map<?, ?>) submissions).keySet()) {
            if (key == null) continue;
            String name = String.valueOf(key).trim();
            if (!name.isEmpty()) keys.add(name);
        }
        return keys;
    }

    private static String firstUrl(Object urls, String... names) {
        if (!(urls instanceof Map)) return "";
        Map<?, ?> object = (Map<?, ?>) urls;
        for (String name : names) {
            String url = text(object.get(name)).trim();
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static boolean contains(String text, String keyword) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) return false;
        return text.toLowerCase(Locale.US).contains(keyword.toLowerCase(Locale.US));
    }

    private static String encodeTopics(String topics) {
        if (topics == null || topics.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String part : topics.split(",")) {
            String id = part.trim();
            if (id.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(encode(id));
        }
        return out.toString();
    }

    /** The Charset overload of URLEncoder.encode arrived in API 33. This set is API 29. */
    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String field(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String emptyAsNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Integer parseCount(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) return null;
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The handful of JSON forms /photos/random uses. Anything else is a bad body, not a photo. */
    private static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object read(String text) {
            Json json = new Json(text);
            Object value = json.value();
            json.skip();
            if (json.at != text.length()) throw new IllegalArgumentException();
            return value;
        }

        private Object value() {
            skip();
            if (at >= text.length()) throw new IllegalArgumentException();
            char c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' || c == 'f' || c == 'n') return literal();
            return number();
        }

        private Map<String, Object> object() {
            at++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (at < text.length() && text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (at < text.length()) {
                skip();
                String key = string();
                skip();
                if (at >= text.length() || text.charAt(at) != ':') throw new IllegalArgumentException();
                at++;
                map.put(key, value());
                skip();
                if (at >= text.length()) throw new IllegalArgumentException();
                char c = text.charAt(at++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException();
            }
            throw new IllegalArgumentException();
        }

        private List<Object> array() {
            at++;
            List<Object> list = new ArrayList<>();
            skip();
            if (at < text.length() && text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (at < text.length()) {
                list.add(value());
                skip();
                if (at >= text.length()) throw new IllegalArgumentException();
                char c = text.charAt(at++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException();
            }
            throw new IllegalArgumentException();
        }

        private String string() {
            if (at >= text.length() || text.charAt(at) != '"') throw new IllegalArgumentException();
            at++;
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) throw new IllegalArgumentException();
                char esc = text.charAt(at++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(esc);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (at + 4 > text.length()) throw new IllegalArgumentException();
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default:
                        throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }

        private Object literal() {
            if (text.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            throw new IllegalArgumentException();
        }

        private Object number() {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if ((c < '0' || c > '9') && c != '-' && c != '+' && c != '.' && c != 'e' && c != 'E') break;
                at++;
            }
            if (start == at) throw new IllegalArgumentException();
            return text.substring(start, at);
        }

        private void skip() {
            while (at < text.length() && text.charAt(at) <= ' ') at++;
        }
    }

    static final class Photo {
        final String id;
        final String imageUrl;
        final String photographer;
        final String userLink;
        final String photoLink;
        final String altDescription;
        final String description;
        /** Keys of {@code topic_submissions}, as the API spelled them. */
        final List<String> topics;

        Photo(String id, String imageUrl, String photographer, String userLink, String photoLink,
                String altDescription, String description, List<String> topics) {
            this.id = id == null ? "" : id;
            this.imageUrl = imageUrl;
            this.photographer = photographer == null ? "" : photographer;
            this.userLink = userLink;
            this.photoLink = photoLink;
            this.altDescription = altDescription == null ? "" : altDescription;
            this.description = description == null ? "" : description;
            if (topics == null || topics.isEmpty()) this.topics = Collections.emptyList();
            else this.topics = Collections.unmodifiableList(new ArrayList<>(topics));
        }
    }

    /**
     * {@code unsplash/state.txt}. The first line is the last saved file number and only moves forward.
     * The rest remembers the hourly window so a restart does not spend the cap again.
     * A missing {@code X-Ratelimit-Remaining} is stored as {@code -}.
     */
    static final class State {
        static final State EMPTY = new State(0, 0, 0L, 0L, 0L, null);

        final int lastSaved;
        final int calls;
        final long windowStartMs;
        final long waitUntilMs;
        final long fetchedAtMs;
        /** Last {@code X-Ratelimit-Remaining}, or null when that response had no such header. */
        final String remaining;

        State(int lastSaved, int calls, long windowStartMs, long waitUntilMs, long fetchedAtMs, String remaining) {
            this.lastSaved = Math.max(0, lastSaved);
            this.calls = Math.max(0, calls);
            this.windowStartMs = Math.max(0L, windowStartMs);
            this.waitUntilMs = Math.max(0L, waitUntilMs);
            this.fetchedAtMs = Math.max(0L, fetchedAtMs);
            this.remaining = remaining;
        }

        /** Drop a local window that has already ended. A stored remaining header is left as it is. */
        static State roll(State state, long now) {
            State current = state == null ? EMPTY : state;
            if (current.remaining != null) return current;
            boolean hourGone = current.windowStartMs > 0L && now - current.windowStartMs >= HOUR_MS;
            boolean waitGone = current.waitUntilMs > 0L && now >= current.waitUntilMs;
            if (!hourGone && !waitGone) return current;
            return new State(current.lastSaved, 0, 0L, 0L, current.fetchedAtMs, null);
        }

        /**
         * Account for one finished request. {@code status} 0 is a connection failure: the cap is unchanged.
         * {@code savedNumber} is the file just written, or the previous number when nothing was saved.
         * Remaining 0, 403 and 429 all wait until {@code resetHeader} or an hour from now.
         */
        static State record(State prev, long now, int status, String remainingHeader, String resetHeader,
                int hourlyCap, int savedNumber) {
            State prior = prev == null ? EMPTY : prev;
            int last = savedNumber > prior.lastSaved ? savedNumber : prior.lastSaved;
            if (status <= 0) {
                long wait = prior.waitUntilMs > now ? prior.waitUntilMs : 0L;
                return new State(last, prior.calls, prior.windowStartMs, wait, now, prior.remaining);
            }
            Integer left = parseCount(remainingHeader);
            if (left != null) {
                long wait = (left <= 0 || rateLimited(status)) ? resetAt(now, resetHeader) : 0L;
                return new State(last, 0, 0L, wait, now, Integer.toString(left));
            }
            long window = prior.windowStartMs;
            int calls = prior.calls;
            if (window <= 0L || now - window >= HOUR_MS) {
                window = now;
                calls = 1;
            } else {
                calls++;
            }
            int cap = hourlyCap > 0 ? hourlyCap : 50;
            long wait = 0L;
            if (rateLimited(status) || calls >= cap) {
                String reset = resetHeader == null ? "" : resetHeader.trim();
                // No remaining header: the hour is measured from the first call in this window.
                // A reset header on 403/429 still wins, the same wait as a remaining count of 0.
                wait = reset.isEmpty() ? window + HOUR_MS : resetAt(now, reset);
            }
            return new State(last, calls, window, wait, now, null);
        }

        String save() {
            return lastSaved + "\n" + calls + "\n" + windowStartMs + "\n" + waitUntilMs + "\n"
                    + fetchedAtMs + "\n" + (remaining == null ? "-" : remaining) + "\n";
        }

        static State load(String text) {
            if (text == null) return EMPTY;
            String[] lines = text.split("\n", -1);
            if (lines.length < 1 || lines[0].trim().isEmpty()) return EMPTY;
            try {
                int last = Integer.parseInt(lines[0].trim());
                int calls = lines.length > 1 && !lines[1].trim().isEmpty() ? Integer.parseInt(lines[1].trim()) : 0;
                long window = lines.length > 2 && !lines[2].trim().isEmpty() ? Long.parseLong(lines[2].trim()) : 0L;
                long wait = lines.length > 3 && !lines[3].trim().isEmpty() ? Long.parseLong(lines[3].trim()) : 0L;
                long fetched = lines.length > 4 && !lines[4].trim().isEmpty() ? Long.parseLong(lines[4].trim()) : 0L;
                String remaining = null;
                if (lines.length > 5) {
                    String raw = lines[5].trim();
                    if (!raw.isEmpty() && !"-".equals(raw)) remaining = raw;
                }
                return new State(last, calls, window, wait, fetched, remaining);
            } catch (NumberFormatException e) {
                return EMPTY;
            }
        }

        private static long resetAt(long now, String resetHeader) {
            String raw = resetHeader == null ? null : resetHeader.trim();
            if (raw != null && !raw.isEmpty()) {
                try {
                    long value = Long.parseLong(raw);
                    long ms = value > 1_000_000_000_000L ? value : value * 1000L;
                    if (ms > now) return ms;
                } catch (NumberFormatException ignored) {
                    // an unreadable reset still waits an hour
                }
            }
            return now + HOUR_MS;
        }
    }
}
