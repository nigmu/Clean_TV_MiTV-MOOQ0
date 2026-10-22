package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Still photos from Unsplash behind Home. One saved batch is slid through while Home is in front; the next
 * batch is fetched only when this one runs out. Pausing Home stops the fetch and the slide, and drops the
 * decoder thread. Two bitmaps exist only while one photo is sliding off the last.
 */
final class PhotoBackdrop {
    private static final LinearInterpolator LINEAR = new LinearInterpolator();
    private static final long RATE_LIMIT_MS = 45 * 60_000L;
    private static final long RETRY_MS = 15 * 60_000L;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final Activity activity;
    private final FrameLayout root;
    private ImageView currentView;
    private ImageView nextView;
    private final View scrim;
    private final TextView credit;
    private final String key;
    private final boolean reducedMotion;
    private final int screenW;
    private final int screenH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable slideTask = this::onSlideDue;
    private final Runnable retryTask = () -> runWorker(this::refetchThenSlide);

    private ExecutorService executor;
    private volatile boolean running;
    private volatile HttpURLConnection inFlight;
    private UnsplashPhotos.Batch batch;
    private long retryAfter;
    /** When the last /photos/random finished, so a batch of bad files cannot ask again immediately. */
    private long lastFetchMs;
    private Bitmap shown;
    private Bitmap incoming;

    PhotoBackdrop(Activity activity, FrameLayout root, boolean reducedMotion, String key) {
        this.activity = activity;
        this.root = root;
        this.reducedMotion = reducedMotion;
        this.key = key == null ? "" : key.trim();
        currentView = root.findViewById(R.id.photoBack);
        nextView = root.findViewById(R.id.photoFront);
        scrim = root.findViewById(R.id.photoScrim);
        credit = root.findViewById(R.id.photoCredit);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        screenW = metrics.widthPixels > 0 ? metrics.widthPixels : UnsplashPhotos.WIDTH;
        screenH = metrics.heightPixels > 0 ? metrics.heightPixels : UnsplashPhotos.HEIGHT;
        if (credit != null) {
            credit.setMovementMethod(LinkMovementMethod.getInstance());
            credit.setHighlightColor(0);
            // LinkMovementMethod makes the view focusable. The credit sits under the date and must not
            // take the D-pad away from the pills and the tiles.
            credit.setFocusable(false);
            credit.setFocusableInTouchMode(false);
        }
    }

    void start() {
        if (running || currentView == null) return;
        running = true;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "unsplash");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        if (shown != null && batch != null && !batch.exhausted()) {
            scheduleSlide();
            runWorker(() -> downloadRest(0));
            return;
        }
        runWorker(this::boot);
    }

    /** Home left the screen: no request, no slide, no decoder thread. The photo already up stays. */
    void stop() {
        running = false;
        main.removeCallbacks(slideTask);
        main.removeCallbacks(retryTask);
        if (currentView != null) currentView.animate().cancel();
        if (nextView != null) nextView.animate().cancel();
        if (incoming != null) {
            if (nextView != null) nextView.setImageBitmap(null);
            Bitmap extra = incoming;
            incoming = null;
            extra.recycle();
        }
        if (currentView != null) currentView.setTranslationX(0);
        if (nextView != null) nextView.setTranslationX(0);
        if (batch != null) showCredit(batch.current());
        HttpURLConnection conn = inFlight;
        if (conn != null) conn.disconnect();
        ExecutorService exec = executor;
        executor = null;
        if (exec != null) exec.shutdownNow();
    }

    void close() {
        stop();
        if (shown != null) {
            if (currentView != null) currentView.setImageBitmap(null);
            shown.recycle();
            shown = null;
        }
    }

    private void boot() {
        batch = readBatch();
        if (batch != null) retryAfter = Math.max(retryAfter, batch.retryAfter);
        long now = System.currentTimeMillis();
        if (UnsplashPhotos.hasKey(key) && now >= retryAfter && UnsplashPhotos.shouldFetch(batch, now)) {
            UnsplashPhotos.Batch fresh = fetchRandom();
            if (!running) return;
            if (fresh != null) {
                deleteUnused(fresh);
                batch = fresh;
                persist();
            } else if (batch == null || batch.exhausted()) {
                main.post(this::scheduleRetry);
            }
        }
        if (!running || batch == null || batch.photos.isEmpty()) return;
        if (batch.exhausted()) {
            main.post(this::scheduleRetry);
            return;
        }
        int start = batch.index;
        for (int i = start; i < batch.photos.size(); i++) {
            if (!running) return;
            if (!ensure(batch.photos.get(i))) continue;
            Bitmap bitmap = decode(imageFile(batch.photos.get(i)));
            if (bitmap == null) continue;
            int index = i;
            main.post(() -> showStill(bitmap, index));
            downloadRest(i + 1);
            return;
        }
        main.post(this::scheduleRetry);
    }

    private void downloadRest(int from) {
        if (batch == null) return;
        for (int i = Math.max(0, from); i < batch.photos.size(); i++) {
            if (!running) return;
            ensure(batch.photos.get(i));
        }
    }

    private void onSlideDue() {
        if (!running || batch == null) return;
        int next = batch.index + 1;
        if (next >= batch.photos.size()) {
            runWorker(this::refetchThenSlide);
            return;
        }
        runWorker(() -> prepare(next));
    }

    private void prepare(int index) {
        if (!running || batch == null || index < 0 || index >= batch.photos.size()) return;
        UnsplashPhotos.Photo photo = batch.photos.get(index);
        if (!ensure(photo)) {
            main.post(() -> skip(index));
            return;
        }
        Bitmap bitmap = decode(imageFile(photo));
        if (!running) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        if (bitmap == null) {
            imageFile(photo).delete();
            main.post(() -> skip(index));
            return;
        }
        main.post(() -> beginSlide(bitmap, index));
    }

    private void skip(int index) {
        if (!running || batch == null) return;
        if (index + 1 >= batch.photos.size()) {
            // The files in this batch cannot be shown. Wait, instead of asking the API again in a loop.
            retryAfter = Math.max(retryAfter, System.currentTimeMillis() + RETRY_MS);
            batch.retryAfter = Math.max(batch.retryAfter, retryAfter);
            persist();
            main.post(this::scheduleRetry);
            return;
        }
        runWorker(() -> prepare(index + 1));
    }

    private void refetchThenSlide() {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (!UnsplashPhotos.hasKey(key)) return;
        if (now < waitUntil() || (lastFetchMs != 0 && now - lastFetchMs < RETRY_MS)) {
            main.post(this::scheduleRetry);
            return;
        }
        UnsplashPhotos.Batch fresh = fetchRandom();
        if (!running) return;
        if (fresh == null) {
            if (retryAfter <= System.currentTimeMillis()) {
                retryAfter = System.currentTimeMillis() + RETRY_MS;
                if (batch != null) batch.retryAfter = retryAfter;
                persist();
            }
            main.post(this::scheduleRetry);
            return;
        }
        deleteUnused(fresh);
        batch = fresh;
        persist();
        prepare(0);
    }

    private void showStill(Bitmap bitmap, int index) {
        if (!running || batch == null || index < 0 || index >= batch.photos.size()) {
            bitmap.recycle();
            return;
        }
        batch.index = index;
        currentView.setTranslationX(0);
        currentView.setImageBitmap(bitmap);
        Bitmap previous = shown;
        shown = bitmap;
        releaseLater(previous);
        reveal();
        showCredit(batch.photos.get(index));
        ping(batch.photos.get(index));
        persist();
        scheduleSlide();
    }

    private void beginSlide(Bitmap bitmap, int index) {
        if (!running || bitmap == null || nextView == null) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        int width = root.getWidth() > 0 ? root.getWidth() : screenW;
        incoming = bitmap;
        nextView.setTranslationX(width);
        nextView.setImageBitmap(bitmap);
        if (batch != null && index >= 0 && index < batch.photos.size()) showCredit(batch.photos.get(index));
        long duration = reducedMotion ? 90L : UnsplashPhotos.SLIDE_MS;
        currentView.animate().translationX(-width).setDuration(duration).setInterpolator(LINEAR).start();
        nextView.animate().translationX(0).setDuration(duration).setInterpolator(LINEAR)
                .withEndAction(() -> finishSlide(index)).start();
    }

    private void finishSlide(int index) {
        if (!running || batch == null) return;
        currentView.animate().cancel();
        nextView.animate().cancel();
        ImageView arrived = nextView;
        nextView = currentView;
        currentView = arrived;
        nextView.setImageBitmap(null);
        nextView.setTranslationX(0);
        currentView.setTranslationX(0);
        Bitmap previous = shown;
        shown = incoming;
        incoming = null;
        releaseLater(previous);
        if (index >= 0 && index < batch.photos.size()) batch.index = index;
        reveal();
        showCredit(batch.current());
        ping(batch.current());
        persist();
        if (batch.exhausted()) runWorker(this::refetchThenSlide);
        else scheduleSlide();
    }

    private void reveal() {
        scrim.setVisibility(View.VISIBLE);
        // The photo covers the root colour. Leaving that fill in would be a second full-screen draw.
        root.setBackground(null);
    }

    private void scheduleSlide() {
        main.removeCallbacks(slideTask);
        if (!running || batch == null || batch.exhausted()) return;
        main.postDelayed(slideTask, UnsplashPhotos.HOLD_MS);
    }

    private void scheduleRetry() {
        if (!running) return;
        long wait = waitUntil() - System.currentTimeMillis();
        if (wait < 5_000L) wait = RETRY_MS;
        main.removeCallbacks(retryTask);
        main.postDelayed(retryTask, wait);
    }

    private long waitUntil() {
        long until = retryAfter;
        if (batch != null) until = Math.max(until, batch.retryAfter);
        return until;
    }

    private void ping(UnsplashPhotos.Photo photo) {
        if (photo == null || !UnsplashPhotos.hasKey(key)) return;
        if (photo.downloadLocation == null || !photo.downloadLocation.startsWith("https://")) return;
        if (System.currentTimeMillis() < waitUntil()) return;
        runWorker(() -> {
            if (!running || batch == null) return;
            if (!batch.markDownload(photo.id)) return;
            int status = getStatus(photo.downloadLocation);
            if (status >= 200 && status < 300) {
                persist();
                return;
            }
            photo.downloadTracked = false;
            if (UnsplashPhotos.rateLimited(status)) noteRateLimit();
            else if (status == 404) {
                photo.downloadTracked = true;
                persist();
            }
        });
    }

    private void noteRateLimit() {
        retryAfter = System.currentTimeMillis() + RATE_LIMIT_MS;
        if (batch != null) batch.retryAfter = retryAfter;
        persist();
    }

    private UnsplashPhotos.Batch fetchRandom() {
        HttpURLConnection conn = null;
        try {
            conn = open(UnsplashPhotos.randomUrl(), true);
            int status = conn.getResponseCode();
            lastFetchMs = System.currentTimeMillis();
            if (status != 200) {
                Log.w("TvHome.Photos", "random photos failed: " + status);
                if (UnsplashPhotos.rateLimited(status)) noteRateLimit();
                return null;
            }
            return UnsplashPhotos.parse(readLimited(conn.getInputStream(), 1_048_576));
        } catch (Exception e) {
            lastFetchMs = System.currentTimeMillis();
            if (running) Log.w("TvHome.Photos", "random photos failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return null;
        } finally {
            done(conn);
        }
    }

    private int getStatus(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, true);
            int status = conn.getResponseCode();
            InputStream body = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (body != null) readLimited(body, 64 * 1024);
            return status;
        } catch (Exception e) {
            return 0;
        } finally {
            done(conn);
        }
    }

    private boolean ensure(UnsplashPhotos.Photo photo) {
        File dest = imageFile(photo);
        if (dest.isFile() && dest.length() > 0) return true;
        return download(photo, dest);
    }

    private boolean download(UnsplashPhotos.Photo photo, File dest) {
        if (!running || photo.imageUrl == null || !photo.imageUrl.startsWith("https://")) return false;
        File folder = dest.getParentFile();
        if (folder != null) folder.mkdirs();
        File part = new File(folder, dest.getName() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = open(photo.imageUrl, false);
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (!running || Thread.currentThread().isInterrupted()) return false;
                    total += n;
                    if (total > MAX_IMAGE_BYTES) return false;
                    out.write(buf, 0, n);
                }
            }
            if (!running) return false;
            if (dest.exists() && !dest.delete()) return false;
            return part.renameTo(dest);
        } catch (Exception e) {
            return false;
        } finally {
            done(conn);
            if (!dest.isFile()) part.delete();
        }
    }

    private HttpURLConnection open(String url, boolean api) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(api ? 15_000 : 30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "tvhome");
        if (api) {
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Version", "v1");
            conn.setRequestProperty("Authorization", "Client-ID " + key);
        }
        inFlight = conn;
        return conn;
    }

    private void done(HttpURLConnection conn) {
        if (conn != null) conn.disconnect();
        if (inFlight == conn) inFlight = null;
    }

    private static String readLimited(InputStream in, int max) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buf)) >= 0 && total < max) {
            int take = Math.min(n, max - total);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Same decode as a theme wallpaper: RGB_565, about 4 MB at 1080p, then one hardware bitmap. */
    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth;
            int height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    private void showCredit(UnsplashPhotos.Photo photo) {
        if (credit == null) return;
        String line = UnsplashPhotos.credit(photo);
        if (photo == null || line.isEmpty()) {
            credit.setVisibility(View.GONE);
            return;
        }
        SpannableString span = new SpannableString(line);
        String name = photo.photographer == null ? "" : photo.photographer.trim();
        if (!name.isEmpty() && line.length() > name.length() && https(photo.userLink)) {
            span.setSpan(new QuietLink(photo.userLink), 0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int mark = line.lastIndexOf("Unsplash");
        if (mark >= 0 && https(photo.photoLink)) {
            span.setSpan(new QuietLink(photo.photoLink), mark, mark + "Unsplash".length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        credit.setText(span);
        credit.setContentDescription(line);
        credit.setVisibility(View.VISIBLE);
        credit.setFocusable(false);
    }

    private static boolean https(String url) {
        return url != null && url.startsWith("https://");
    }

    private void releaseLater(Bitmap bitmap) {
        if (bitmap == null || bitmap == shown || bitmap == incoming) return;
        main.postDelayed(bitmap::recycle, 500);
    }

    private void persist() {
        UnsplashPhotos.Batch snapshot = batch;
        if (snapshot == null) return;
        runWorker(() -> writeBatch(snapshot));
    }

    private void writeBatch(UnsplashPhotos.Batch snapshot) {
        try {
            File file = batchFile();
            File folder = file.getParentFile();
            if (folder != null) folder.mkdirs();
            String text = snapshot.save();
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w("TvHome.Photos", "could not save the batch");
        }
    }

    private UnsplashPhotos.Batch readBatch() {
        File file = batchFile();
        if (!file.isFile()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return UnsplashPhotos.Batch.load(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteUnused(UnsplashPhotos.Batch fresh) {
        File folder = imageDir();
        File[] files = folder.listFiles();
        if (files == null) return;
        HashSet<String> keep = new HashSet<>();
        for (UnsplashPhotos.Photo photo : fresh.photos) keep.add(fileName(photo.id));
        for (File file : files) {
            if (!keep.contains(file.getName())) file.delete();
        }
    }

    private File imageFile(UnsplashPhotos.Photo photo) {
        return new File(imageDir(), fileName(photo.id));
    }

    private File imageDir() {
        return new File(activity.getFilesDir(), "unsplash/images");
    }

    private File batchFile() {
        return new File(activity.getFilesDir(), "unsplash/batch.txt");
    }

    private static String fileName(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.isEmpty()) cleaned = "photo";
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        return cleaned + ".jpg";
    }

    private void runWorker(Runnable job) {
        ExecutorService exec = executor;
        if (!running || exec == null) return;
        try {
            exec.execute(job);
        } catch (RejectedExecutionException ignored) {
            // stop() already shut the thread down
        }
    }

    private final class QuietLink extends ClickableSpan {
        private final String url;

        QuietLink(String url) {
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
                // a TV with no browser still shows the name
            }
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setColor(0xE6FFFFFF);
            paint.setUnderlineText(false);
        }
    }
}
