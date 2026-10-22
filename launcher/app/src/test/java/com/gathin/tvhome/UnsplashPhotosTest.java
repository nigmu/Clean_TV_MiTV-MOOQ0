package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Unsplash batch: hotlinked URLs, one download ping, and a pace that stays under 50 API calls an hour. */
public class UnsplashPhotosTest {
    private static final String RAW = "https://images.unsplash.com/photo-abc?ixid=1";
    private static final String JSON = "[{"
            + "\"id\":\"abc\","
            + "\"urls\":{\"raw\":\"" + RAW + "\"},"
            + "\"links\":{\"html\":\"https://unsplash.com/photos/abc\","
            + "\"download_location\":\"https://api.unsplash.com/photos/abc/download?ixid=1\"},"
            + "\"user\":{\"name\":\"Ada Lovelace\",\"links\":{\"html\":\"https://unsplash.com/@ada\"}}"
            + "}]";

    @Test public void randomRequestIsOneLandscapeBatch() {
        String url = UnsplashPhotos.randomUrl();
        assertTrue(url.startsWith("https://api.unsplash.com/photos/random?"));
        assertTrue(url.contains("orientation=landscape"));
        assertTrue(url.contains("content_filter=high"));
        assertTrue(url.contains("count=" + UnsplashPhotos.BATCH));
        assertTrue(UnsplashPhotos.BATCH <= 30);
    }

    @Test public void sizedImageKeepsTheApiUrlAndFitsThePanel() {
        String sized = UnsplashPhotos.sized(RAW);
        assertTrue(sized.startsWith(RAW + "&"));
        assertTrue(sized.contains("w=1920"));
        assertTrue(sized.contains("h=1080"));
        assertTrue(sized.contains("fit=crop"));
        assertTrue(UnsplashPhotos.sized("https://images.unsplash.com/photo-abc")
                .startsWith("https://images.unsplash.com/photo-abc?w=1920"));
        assertNull(UnsplashPhotos.sized(null));
        assertNull(UnsplashPhotos.sized(""));
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

    @Test public void parseReadsThePhotoTheApiReturned() {
        UnsplashPhotos.Batch batch = UnsplashPhotos.parse(JSON);
        assertEquals(1, batch.photos.size());
        UnsplashPhotos.Photo photo = batch.photos.get(0);
        assertEquals("abc", photo.id);
        assertTrue(photo.imageUrl.startsWith(RAW + "&"));
        assertTrue(photo.imageUrl.contains("w=1920"));
        assertEquals("Ada Lovelace", photo.photographer);
        assertEquals("https://unsplash.com/@ada?utm_source=tvhome&utm_medium=referral", photo.userLink);
        assertEquals("https://unsplash.com/photos/abc?utm_source=tvhome&utm_medium=referral", photo.photoLink);
        assertEquals("https://api.unsplash.com/photos/abc/download?ixid=1", photo.downloadLocation);
        assertFalse(photo.downloadTracked);
        assertEquals("Ada Lovelace  ·  Unsplash", UnsplashPhotos.credit(photo));
    }

    @Test public void parseSkipsAPhotoWithNoImageAndRejectsAnErrorBody() {
        UnsplashPhotos.Batch batch = UnsplashPhotos.parse("[{"
                + "\"id\":\"gone\",\"user\":{\"name\":\"Nobody\"}},"
                + "{\"id\":\"ok\",\"urls\":{\"raw\":\"https://images.unsplash.com/photo-ok\"},"
                + "\"links\":{\"html\":\"https://unsplash.com/photos/ok\","
                + "\"download_location\":\"https://api.unsplash.com/photos/ok/download\"},"
                + "\"user\":{\"name\":\"\",\"links\":{\"html\":\"https://unsplash.com/@ok\"}}}]");
        assertEquals(1, batch.photos.size());
        assertEquals("ok", batch.photos.get(0).id);
        assertEquals("Unsplash", UnsplashPhotos.credit(batch.photos.get(0)));
        assertNull(UnsplashPhotos.parse("{\"errors\":[\"Rate Limit Exceeded\"]}"));
        assertNull(UnsplashPhotos.parse("not json"));
        assertNull(UnsplashPhotos.parse(null));
    }

    @Test public void batchRefetchesOnlyWhenItRunsOut() {
        UnsplashPhotos.Batch batch = UnsplashPhotos.parse(JSON);
        assertFalse(batch.exhausted());
        assertEquals("abc", batch.current().id);
        assertFalse(UnsplashPhotos.shouldFetch(batch, 1_000L));
        batch.advance();
        assertTrue(batch.exhausted());
        assertNull(batch.current());
        assertTrue(UnsplashPhotos.shouldFetch(batch, 1_000L));
        batch.retryAfter = 5_000L;
        assertFalse(UnsplashPhotos.shouldFetch(batch, 4_000L));
        assertTrue(UnsplashPhotos.shouldFetch(batch, 5_000L));
        assertTrue(UnsplashPhotos.shouldFetch(null, 1_000L));
    }

    @Test public void downloadLocationIsMarkedOnce() {
        UnsplashPhotos.Batch batch = UnsplashPhotos.parse(JSON);
        assertTrue(batch.markDownload("abc"));
        assertFalse(batch.markDownload("abc"));
        assertFalse(batch.markDownload("missing"));
    }

    @Test public void savedBatchRoundTrips() {
        UnsplashPhotos.Batch batch = UnsplashPhotos.parse(JSON);
        batch.advance();
        batch.retryAfter = 42L;
        batch.photos.get(0).downloadTracked = true;
        UnsplashPhotos.Batch loaded = UnsplashPhotos.Batch.load(batch.save());
        assertEquals(1, loaded.index);
        assertEquals(42L, loaded.retryAfter);
        assertEquals("abc", loaded.photos.get(0).id);
        assertEquals(batch.photos.get(0).imageUrl, loaded.photos.get(0).imageUrl);
        assertTrue(loaded.photos.get(0).downloadTracked);
        assertEquals(batch.photos.get(0).downloadLocation, loaded.photos.get(0).downloadLocation);
        assertNull(UnsplashPhotos.Batch.load(null));
        assertNull(UnsplashPhotos.Batch.load("nope"));
    }

    @Test public void missingKeyDoesNotFetch() {
        assertFalse(UnsplashPhotos.hasKey(null));
        assertFalse(UnsplashPhotos.hasKey(""));
        assertFalse(UnsplashPhotos.hasKey("   "));
        assertTrue(UnsplashPhotos.hasKey("abc"));
    }

    @Test public void rateLimitKeepsTheBatch() {
        assertTrue(UnsplashPhotos.rateLimited(403));
        assertTrue(UnsplashPhotos.rateLimited(429));
        assertFalse(UnsplashPhotos.rateLimited(200));
        assertFalse(UnsplashPhotos.rateLimited(500));
    }

    @Test public void fullBatchStaysUnderTheHourlyLimit() {
        assertTrue(UnsplashPhotos.SLIDE_MS >= 8_000L);
        assertTrue(UnsplashPhotos.requestsPerHour() < 50.0);
    }
}
