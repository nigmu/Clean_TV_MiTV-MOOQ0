package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One Unsplash photo: a single random URL, a PNG, numbered files, and a cap that can say no. */
public class UnsplashPhotosTest {
    private static final String RAW = "https://images.unsplash.com/photo-abc?ixid=1";
    private static final String OBJECT = "{"
            + "\"id\":\"abc\","
            + "\"urls\":{\"raw\":\"" + RAW + "\"},"
            + "\"links\":{\"html\":\"https://unsplash.com/photos/abc\"},"
            + "\"user\":{\"name\":\"Ada Lovelace\",\"links\":{\"html\":\"https://unsplash.com/@ada\"}}"
            + "}";
    private static final String NATURE = "{"
            + "\"id\":\"n1\","
            + "\"alt_description\":\"A quiet beach\","
            + "\"description\":\"Waves at dusk\","
            + "\"topic_submissions\":{\"nature\":{\"status\":\"approved\"},\"wallpapers\":{\"status\":\"approved\"}},"
            + "\"urls\":{\"raw\":\"https://images.unsplash.com/photo-n1\"},"
            + "\"user\":{\"name\":\"Ada Lovelace\"}"
            + "}";

    @Test public void randomRequestIsOnePhoto() {
        String url = UnsplashPhotos.randomUrl("6sMVjTLSkeQ", "high");
        assertTrue(url.startsWith("https://api.unsplash.com/photos/random?"));
        assertTrue(url.contains("topics=6sMVjTLSkeQ"));
        assertTrue(url.contains("orientation=landscape"));
        assertTrue(url.contains("content_filter=high"));
        assertFalse(url.contains("count="));
    }

    @Test public void sizedImageIsAPngCroppedToThePanel() {
        String sized = UnsplashPhotos.sized(RAW);
        assertTrue(sized.startsWith(RAW + "&"));
        assertTrue(sized.contains("w=1920"));
        assertTrue(sized.contains("h=1080"));
        assertTrue(sized.contains("fit=crop"));
        assertTrue(sized.contains("fm=png"));
        assertFalse(sized.contains("fm=jpg"));
        assertTrue(UnsplashPhotos.sized("https://images.unsplash.com/photo-abc")
                .startsWith("https://images.unsplash.com/photo-abc?w=1920"));
        assertTrue(UnsplashPhotos.imageHost(sized));
        assertNull(UnsplashPhotos.sized(null));
        assertNull(UnsplashPhotos.sized(""));
    }

    @Test public void fileNamesUseAtLeastTwoDigits() {
        assertEquals("01.png", UnsplashPhotos.fileName(1));
        assertEquals("02.png", UnsplashPhotos.fileName(2));
        assertEquals("10.png", UnsplashPhotos.fileName(10));
        assertEquals("51.png", UnsplashPhotos.fileName(51));
        assertEquals("100.png", UnsplashPhotos.fileName(100));
        assertEquals(1, UnsplashPhotos.fileNumber("01.png"));
        assertEquals(51, UnsplashPhotos.fileNumber("51.png"));
        assertEquals(100, UnsplashPhotos.fileNumber("100.png"));
        assertEquals(0, UnsplashPhotos.fileNumber("abc.jpg"));
        assertEquals(1, UnsplashPhotos.nextFileNumber(0));
        assertEquals(52, UnsplashPhotos.nextFileNumber(51));
        assertEquals(101, UnsplashPhotos.nextFileNumber(100));
    }

    @Test public void savingPastTheCapDeletesThatManyBack() {
        assertEquals(0, UnsplashPhotos.evictNumber(50, 50));
        assertEquals(1, UnsplashPhotos.evictNumber(51, 50));
        assertEquals(2, UnsplashPhotos.evictNumber(52, 50));
        assertEquals("01.png", UnsplashPhotos.fileName(UnsplashPhotos.evictNumber(51, 50)));
        assertEquals("02.png", UnsplashPhotos.fileName(UnsplashPhotos.evictNumber(52, 50)));
        assertEquals("50.png", UnsplashPhotos.fileName(UnsplashPhotos.evictNumber(100, 50)));
    }

    @Test public void displayWalksInNumericOrderAndWraps() {
        int[] files = {51, 2, 10};
        assertEquals(2, UnsplashPhotos.nextShown(0, files));
        assertEquals(10, UnsplashPhotos.nextShown(2, files));
        assertEquals(51, UnsplashPhotos.nextShown(10, files));
        assertEquals(2, UnsplashPhotos.nextShown(51, files));
        assertEquals(0, UnsplashPhotos.nextShown(1, new int[0]));
    }

    @Test public void referralUsesTheReturnedPage() {
        assertEquals("https://unsplash.com/@ada?utm_source=tvhome&utm_medium=referral",
                UnsplashPhotos.referral("https://unsplash.com/@ada"));
        assertEquals("https://unsplash.com/photos/abc?ixid=1&utm_source=tvhome&utm_medium=referral",
                UnsplashPhotos.referral("https://unsplash.com/photos/abc?ixid=1"));
        assertEquals("https://unsplash.com/@ada?utm_source=tvhome&utm_medium=referral",
                UnsplashPhotos.referral("https://unsplash.com/@ada?utm_source=tvhome&utm_medium=referral"));
        assertNull(UnsplashPhotos.referral(null));
    }

    @Test public void parseReadsTheOnePhotoTheApiReturned() {
        UnsplashPhotos.Photo photo = UnsplashPhotos.parse(OBJECT);
        assertEquals("abc", photo.id);
        assertTrue(photo.imageUrl.startsWith(RAW + "&"));
        assertTrue(photo.imageUrl.contains("w=1920"));
        assertTrue(photo.imageUrl.contains("fm=png"));
        assertEquals("Ada Lovelace", photo.photographer);
        assertEquals("https://unsplash.com/@ada?utm_source=tvhome&utm_medium=referral", photo.userLink);
        assertEquals("https://unsplash.com/photos/abc?utm_source=tvhome&utm_medium=referral", photo.photoLink);
        assertEquals("Ada Lovelace  ·  Unsplash", UnsplashPhotos.credit(photo));
        UnsplashPhotos.Photo first = UnsplashPhotos.parse("[" + OBJECT + "," + OBJECT.replace("abc", "def") + "]");
        assertEquals("abc", first.id);
    }

    @Test public void parseSkipsAPhotoWithNoImageAndRejectsAnErrorBody() {
        UnsplashPhotos.Photo photo = UnsplashPhotos.parse("[{"
                + "\"id\":\"gone\",\"user\":{\"name\":\"Nobody\"}},"
                + "{\"id\":\"ok\",\"urls\":{\"raw\":\"https://images.unsplash.com/photo-ok\"},"
                + "\"links\":{\"html\":\"https://unsplash.com/photos/ok\"},"
                + "\"user\":{\"name\":\"\",\"links\":{\"html\":\"https://unsplash.com/@ok\"}}}]");
        assertEquals("ok", photo.id);
        assertEquals("Unsplash", UnsplashPhotos.credit(photo));
        assertNull(UnsplashPhotos.parse("{\"errors\":[\"Rate Limit Exceeded\"]}"));
        assertNull(UnsplashPhotos.parse("not json"));
        assertNull(UnsplashPhotos.parse(null));
    }

    @Test public void rejectMatchesTopicKeysAndDescription() {
        UnsplashPhotos.Photo photo = UnsplashPhotos.parse(NATURE);
        assertFalse(UnsplashPhotos.rejected(photo, Collections.emptyList(), Collections.emptyList()));
        assertTrue(UnsplashPhotos.rejected(photo, Arrays.asList("nature"), Collections.emptyList()));
        assertFalse(UnsplashPhotos.rejected(photo, Arrays.asList("people"), Collections.emptyList()));
        assertTrue(UnsplashPhotos.rejected(photo, Collections.emptyList(), Arrays.asList("beach")));
        assertTrue(UnsplashPhotos.rejected(photo, Collections.emptyList(), Arrays.asList("WAVES")));
        assertFalse(UnsplashPhotos.rejected(photo, Collections.emptyList(), Arrays.asList("city")));
        assertFalse(UnsplashPhotos.rejected(null, Arrays.asList("nature"), Arrays.asList("beach")));
    }

    @Test public void remainingZeroDoesNotFetch() {
        assertFalse(UnsplashPhotos.shouldFetch(true, "0", 0, 50, 5_000L, 0L));
        assertFalse(UnsplashPhotos.shouldFetch(true, "0", 0, 50, 1_000L, 5_000L));
        assertTrue(UnsplashPhotos.shouldFetch(true, "12", 50, 50, 5_000L, 0L));
        assertFalse(UnsplashPhotos.shouldFetch(false, "12", 0, 50, 5_000L, 0L));
        assertFalse(UnsplashPhotos.shouldFetch(true, null, 50, 50, 5_000L, 0L));
        assertTrue(UnsplashPhotos.shouldFetch(true, null, 49, 50, 5_000L, 0L));
        UnsplashPhotos.State blocked = UnsplashPhotos.State.record(
                UnsplashPhotos.State.EMPTY, 5_000L, 200, "0", null, 50, 0);
        assertEquals("0", blocked.remaining);
        assertFalse(UnsplashPhotos.shouldFetch(true, blocked.remaining, blocked.calls, 50,
                5_000L, blocked.waitUntilMs));
        assertTrue(blocked.waitUntilMs > 5_000L);
    }

    @Test public void counterFileRemembersTheLastNumber() {
        UnsplashPhotos.State state = new UnsplashPhotos.State(51, 0, 0L, 0L, 0L, null);
        UnsplashPhotos.State loaded = UnsplashPhotos.State.load(state.save());
        assertEquals(51, loaded.lastSaved);
        assertEquals(52, UnsplashPhotos.nextFileNumber(loaded.lastSaved));
        assertNull(loaded.remaining);
        assertEquals(0, UnsplashPhotos.State.load(null).lastSaved);
        assertEquals(0, UnsplashPhotos.State.load("nope").lastSaved);
    }

    @Test public void missingKeyDoesNotFetch() {
        assertFalse(UnsplashPhotos.hasKey(null));
        assertFalse(UnsplashPhotos.hasKey(""));
        assertFalse(UnsplashPhotos.hasKey("   "));
        assertTrue(UnsplashPhotos.hasKey("abc"));
    }

    @Test public void rateLimitedStatuses() {
        assertTrue(UnsplashPhotos.rateLimited(403));
        assertTrue(UnsplashPhotos.rateLimited(429));
        assertFalse(UnsplashPhotos.rateLimited(200));
        assertFalse(UnsplashPhotos.rateLimited(500));
        UnsplashPhotos.State blocked = UnsplashPhotos.State.record(
                UnsplashPhotos.State.EMPTY, 1_000L, 429, null, null, 50, 0);
        assertTrue(blocked.waitUntilMs > 1_000L);
        assertFalse(UnsplashPhotos.shouldFetch(true, blocked.remaining, blocked.calls, 50,
                1_000L, blocked.waitUntilMs));
    }
}
