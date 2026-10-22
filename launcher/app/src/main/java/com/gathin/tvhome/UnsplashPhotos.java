package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Unsplash batch for the Home backdrop. Image and page URLs are the ones the API returned, with the
 * size and referral parameters added. No Android types, so the batch rules are tested on the JVM.
 */
final class UnsplashPhotos {
    /** Demo apps may ask for at most 30, and each photo's download ping counts toward 50 requests an hour. */
    static final int BATCH = 30;
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    /**
     * Hold, then a slow slide. Thirty photos at this pace, plus one list request and one download ping each,
     * stay under 50 API calls an hour even when the next batch starts as the previous one ends.
     */
    static final long HOLD_MS = 88_000L;
    static final long SLIDE_MS = 12_000L;

    private UnsplashPhotos() { }

    static boolean hasKey(String key) {
        return key != null && !key.trim().isEmpty();
    }

    /** 403 is how Unsplash reports the demo-app hourly cap; 429 is the usual rate-limit status. */
    static boolean rateLimited(int status) {
        return status == 403 || status == 429;
    }

    static String randomUrl() {
        return "https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&count=" + BATCH;
    }

    /** The API's own image URL, asked to come back as a 1920x1080 JPEG. */
    static String sized(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return null;
        String sep = imageUrl.contains("?") ? "&" : "?";
        return imageUrl + sep + "w=" + WIDTH + "&h=" + HEIGHT + "&fit=crop&fm=jpg&q=80";
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

    static double requestsPerHour() {
        long batchMs = (HOLD_MS + SLIDE_MS) * (long) BATCH;
        return (1.0 + BATCH) * 3_600_000.0 / (double) batchMs;
    }

    /** True when there is nothing left to show and the rate-limit wait, if any, has passed. */
    static boolean shouldFetch(Batch batch, long now) {
        if (batch == null || batch.photos.isEmpty()) return true;
        if (!batch.exhausted()) return false;
        return now >= batch.retryAfter;
    }

    /**
     * Photos from a /photos/random body. An error object, or a body with no usable image, is null so the
     * caller keeps the batch already on screen.
     */
    static Batch parse(String json) {
        if (json == null) return null;
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return null;
        try {
            Object value = Json.read(trimmed);
            List<Photo> photos = new ArrayList<>();
            if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    Photo photo = photo(item);
                    if (photo != null) photos.add(photo);
                    if (photos.size() == BATCH) break;
                }
            } else {
                Photo photo = photo(value);
                if (photo != null) photos.add(photo);
            }
            return photos.isEmpty() ? null : new Batch(photos, 0, 0L);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Photo photo(Object item) {
        Map<?, ?> object = item instanceof Map ? (Map<?, ?>) item : null;
        if (object == null) return null;
        String id = text(object.get("id")).trim();
        String image = sized(firstUrl(object.get("urls"), "raw", "full", "regular"));
        if (id.isEmpty() || image == null) return null;
        Object links = object.get("links");
        Object user = object.get("user");
        Object userLinks = user instanceof Map ? ((Map<?, ?>) user).get("links") : null;
        String name = user instanceof Map ? text(((Map<?, ?>) user).get("name")) : "";
        String userPage = userLinks instanceof Map ? text(((Map<?, ?>) userLinks).get("html")) : "";
        String photoPage = links instanceof Map ? text(((Map<?, ?>) links).get("html")) : "";
        String download = links instanceof Map ? text(((Map<?, ?>) links).get("download_location")) : "";
        return new Photo(id, image, name, referral(userPage), referral(photoPage), download);
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
        final String downloadLocation;
        boolean downloadTracked;

        Photo(String id, String imageUrl, String photographer, String userLink, String photoLink,
                String downloadLocation) {
            this.id = id;
            this.imageUrl = imageUrl;
            this.photographer = photographer == null ? "" : photographer;
            this.userLink = userLink;
            this.photoLink = photoLink;
            this.downloadLocation = downloadLocation == null ? "" : downloadLocation;
        }
    }

    static final class Batch {
        final List<Photo> photos;
        int index;
        /** Epoch millis before which another /photos/random would only spend the hourly cap. */
        long retryAfter;

        Batch(List<Photo> photos, int index, long retryAfter) {
            this.photos = photos;
            this.index = index;
            this.retryAfter = retryAfter;
        }

        synchronized boolean exhausted() {
            return photos.isEmpty() || index >= photos.size();
        }

        synchronized Photo current() {
            return exhausted() ? null : photos.get(index);
        }

        synchronized void advance() {
            if (index < photos.size()) index++;
        }

        /** True the first time this photo should ping links.download_location. */
        synchronized boolean markDownload(String id) {
            if (id == null) return false;
            for (Photo photo : photos) {
                if (id.equals(photo.id)) {
                    if (photo.downloadTracked) return false;
                    photo.downloadTracked = true;
                    return true;
                }
            }
            return false;
        }

        synchronized String save() {
            StringBuilder out = new StringBuilder();
            out.append(index).append('\n').append(retryAfter).append('\n');
            for (Photo photo : photos) {
                out.append(field(photo.id)).append('\t')
                        .append(field(photo.imageUrl)).append('\t')
                        .append(field(photo.photographer)).append('\t')
                        .append(field(photo.userLink)).append('\t')
                        .append(field(photo.photoLink)).append('\t')
                        .append(field(photo.downloadLocation)).append('\t')
                        .append(photo.downloadTracked ? '1' : '0').append('\n');
            }
            return out.toString();
        }

        static Batch load(String text) {
            if (text == null) return null;
            String[] lines = text.split("\n", -1);
            if (lines.length < 2) return null;
            try {
                int index = Integer.parseInt(lines[0].trim());
                long retryAfter = Long.parseLong(lines[1].trim());
                List<Photo> photos = new ArrayList<>();
                for (int i = 2; i < lines.length; i++) {
                    if (lines[i].trim().isEmpty()) continue;
                    String[] parts = lines[i].split("\t", -1);
                    if (parts.length < 7 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
                    Photo photo = new Photo(parts[0], parts[1], parts[2], emptyAsNull(parts[3]),
                            emptyAsNull(parts[4]), parts[5]);
                    photo.downloadTracked = "1".equals(parts[6].trim());
                    photos.add(photo);
                }
                if (photos.isEmpty()) return null;
                if (index < 0) index = 0;
                return new Batch(photos, index, retryAfter);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String field(String value) {
            if (value == null) return "";
            return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        private static String emptyAsNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }
    }
}
