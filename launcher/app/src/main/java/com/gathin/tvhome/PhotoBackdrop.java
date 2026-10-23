package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Still photos from Unsplash behind Home. One loop walks the numbered PNGs already on disk; the other asks
 * for a single Nature photo and waits until that request has finished before the next. Pausing Home cancels
 * both and drops the decoder thread. The picture on screen is replaced with a cut, and the thread holds the
 * next decode only while it is preparing it.
 */
final class PhotoBackdrop {
    private final Activity activity;
    private final FrameLayout root;
    private final ImageView currentView;
    private final ImageView nextView;
    private final View scrim;
    private final TextView credit;
    private final String key;
    private final boolean enabled;
    private final long fetchMs;
    private final long displayMs;
    private final int maxImages;
    private final int hourlyCap;
    private final int width;
    private final int height;
    private final String topics;
    private final String contentFilter;
    private final List<String> rejectTopics;
    private final List<String> rejectKeywords;
    private final int screenW;
    private final int screenH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable displayTask = () -> runWorker(this::advanceDisplay);
    private final Runnable fetchTask = () -> runWorker(this::fetchOne);

    private ExecutorService executor;
    private volatile boolean running;
    private volatile HttpURLConnection inFlight;
    private volatile int shownNumber;
    private volatile Bitmap shown;

    PhotoBackdrop(Activity activity, FrameLayout root, Config cfg, String key) {
        this.activity = activity;
        this.root = root;
        this.key = key == null ? "" : key.trim();
        this.enabled = cfg.unsplashEnabled;
        this.fetchMs = cfg.unsplashFetchSeconds * 1000L;
        this.displayMs = cfg.unsplashDisplaySeconds * 1000L;
        this.maxImages = cfg.unsplashMaxImages;
        this.hourlyCap = cfg.unsplashHourlyCap;
        this.width = cfg.unsplashWidth;
        this.height = cfg.unsplashHeight;
        this.topics = UnsplashPhotos.joinTopics(cfg.unsplashTopics);
        this.contentFilter = cfg.unsplashContentFilter;
        this.rejectTopics = cfg.unsplashRejectTopics;
        this.rejectKeywords = cfg.unsplashRejectKeywords;
        currentView = root.findViewById(R.id.photoBack);
        nextView = root.findViewById(R.id.photoFront);
        scrim = root.findViewById(R.id.photoScrim);
        credit = root.findViewById(R.id.photoCredit);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        screenW = metrics.widthPixels > 0 ? metrics.widthPixels : width;
        screenH = metrics.heightPixels > 0 ? metrics.heightPixels : height;
        if (nextView != null) {
            nextView.setImageBitmap(null);
            nextView.setTranslationX(0);
        }
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
        if (running || currentView == null || !enabled) return;
        running = true;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "unsplash");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        runWorker(this::boot);
    }

    /** Home left the screen: no request, no swap, no decoder thread. The photo already up stays. */
    void stop() {
        running = false;
        main.removeCallbacks(displayTask);
        main.removeCallbacks(fetchTask);
        if (currentView != null) currentView.animate().cancel();
        if (nextView != null) nextView.animate().cancel();
        if (currentView != null) currentView.setTranslationX(0);
        if (nextView != null) nextView.setTranslationX(0);
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
            Bitmap gone = shown;
            shown = null;
            gone.recycle();
        }
    }

    private void boot() {
        migrate();
        if (!running) return;
        if (shown == null) advanceDisplay();
        else main.post(this::scheduleDisplay);
        if (UnsplashPhotos.hasKey(key)) main.post(this::armFetch);
    }

    /** The old batch of id-named JPEGs is not a numbered set. Drop it once; deleting it again is a no-op. */
    private void migrate() {
        new File(activity.getFilesDir(), "unsplash/batch.txt").delete();
        File[] files = imageDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.US);
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".part")) file.delete();
        }
    }

    private void armFetch() {
        if (!running || !UnsplashPhotos.hasKey(key)) return;
        runWorker(() -> {
            long now = System.currentTimeMillis();
            UnsplashPhotos.State state = UnsplashPhotos.State.roll(readState(), now);
            long delay = UnsplashPhotos.nextDelay(online(), state, hourlyCap, System.currentTimeMillis(), fetchMs);
            main.post(() -> scheduleFetch(delay));
        });
    }

    private void advanceDisplay() {
        if (!running) return;
        int[] numbers = listNumbers();
        if (numbers.length == 0) return;
        int cursor = shownNumber;
        for (int i = 0; i < numbers.length; i++) {
            if (!running) return;
            int next = UnsplashPhotos.nextShown(cursor, numbers);
            if (next <= 0) return;
            if (next == shownNumber && shown != null) {
                main.post(this::scheduleDisplay);
                return;
            }
            Bitmap bitmap = decode(new File(imageDir(), UnsplashPhotos.fileName(next)));
            if (!running) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            if (bitmap != null) {
                int number = next;
                UnsplashPhotos.Photo credit = creditFor(number);
                main.post(() -> cutTo(bitmap, number, credit));
                return;
            }
            cursor = next;
        }
        main.post(this::scheduleDisplay);
    }

    private void cutTo(Bitmap bitmap, int number, UnsplashPhotos.Photo credit) {
        if (!running || currentView == null || bitmap == null) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        shownNumber = number;
        currentView.setTranslationX(0);
        currentView.setImageBitmap(bitmap);
        if (nextView != null) nextView.setImageBitmap(null);
        Bitmap previous = shown;
        shown = bitmap;
        if (previous != null && previous != shown) previous.recycle();
        reveal();
        showCredit(credit);
        scheduleDisplay();
    }

    private void fetchOne() {
        if (!running || !UnsplashPhotos.hasKey(key)) return;
        long now = System.currentTimeMillis();
        UnsplashPhotos.State state = UnsplashPhotos.State.roll(readState(), now);
        now = System.currentTimeMillis();
        boolean connected = online();
        if (!UnsplashPhotos.shouldFetch(connected, state.remaining, state.calls, hourlyCap, now, state.waitUntilMs)) {
            long delay = UnsplashPhotos.nextDelay(connected, state, hourlyCap, now, fetchMs);
            if (delay < 1L) delay = fetchMs;
            long wait = delay;
            main.post(() -> scheduleFetch(wait));
            return;
        }
        Result result = requestRandom();
        if (!running && result.status <= 0) return;
        now = System.currentTimeMillis();
        int savedNumber = state.lastSaved;
        boolean saved = false;
        if (result.status == 200 && result.photo != null
                && !UnsplashPhotos.rejected(result.photo, rejectTopics, rejectKeywords)) {
            int number = nextSaveNumber(state);
            saved = download(result.photo, number);
            if (saved) savedNumber = number;
        }
        UnsplashPhotos.State next = UnsplashPhotos.State.record(state, now, result.status, result.remaining,
                result.reset, hourlyCap, savedNumber);
        writeState(next);
        if (!running) return;
        long delay = next.waitUntilMs > now ? next.waitUntilMs - now : fetchMs;
        boolean showNow = saved && shown == null;
        main.post(() -> {
            if (showNow) runWorker(this::advanceDisplay);
            scheduleFetch(delay);
        });
    }

    private void reveal() {
        scrim.setVisibility(View.VISIBLE);
        // The photo covers the root colour. Leaving that fill in would be a second full-screen draw.
        root.setBackground(null);
    }

    private void scheduleDisplay() {
        if (!running) return;
        main.removeCallbacks(displayTask);
        main.postDelayed(displayTask, displayMs);
    }

    private void scheduleFetch(long delay) {
        if (!running || !UnsplashPhotos.hasKey(key)) return;
        main.removeCallbacks(fetchTask);
        main.postDelayed(fetchTask, Math.max(0L, delay));
    }

    /** A network that can actually carry the request. No such network: leave the API alone. */
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private Result requestRandom() {
        HttpURLConnection conn = null;
        int status = 0;
        String remaining = null;
        String reset = null;
        try {
            conn = open(UnsplashPhotos.randomUrl(topics, contentFilter), true);
            status = conn.getResponseCode();
            remaining = header(conn, "X-Ratelimit-Remaining");
            reset = header(conn, "X-Ratelimit-Reset");
            if (status != 200) {
                Log.w("TvHome.Photos", "random photo failed: " + status);
                return new Result(status, remaining, reset, null);
            }
            UnsplashPhotos.Photo photo = UnsplashPhotos.parse(
                    readLimited(conn.getInputStream(), 1_048_576), width, height);
            return new Result(status, remaining, reset, photo);
        } catch (Exception e) {
            if (running) Log.w("TvHome.Photos", "random photo failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return new Result(status, remaining, reset, null);
        } finally {
            done(conn);
        }
    }

    private boolean download(UnsplashPhotos.Photo photo, int number) {
        if (!running || photo == null || !UnsplashPhotos.imageHost(photo.imageUrl)) return false;
        File dest = new File(imageDir(), UnsplashPhotos.fileName(number));
        File folder = dest.getParentFile();
        if (folder != null) folder.mkdirs();
        File part = new File(folder, dest.getName() + ".part");
        HttpURLConnection conn = null;
        boolean ok = false;
        try {
            conn = open(photo.imageUrl, false);
            if (conn.getResponseCode() != 200) return false;
            long length = conn.getContentLengthLong();
            if (length > UnsplashPhotos.MAX_IMAGE_BYTES) return false;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (!running || Thread.currentThread().isInterrupted()) return false;
                    total += n;
                    if (total > UnsplashPhotos.MAX_IMAGE_BYTES) return false;
                    out.write(buf, 0, n);
                }
            }
            if (!running) return false;
            if (dest.exists() && !dest.delete()) return false;
            ok = part.renameTo(dest);
            if (!ok) return false;
            writeCredit(number, photo);
            int drop = UnsplashPhotos.evictNumber(number, maxImages);
            if (drop > 0) {
                new File(imageDir(), UnsplashPhotos.fileName(drop)).delete();
                creditFile(drop).delete();
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            done(conn);
            if (!ok) part.delete();
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

    private static String header(HttpURLConnection conn, String name) {
        String value = conn.getHeaderField(name);
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
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
            int srcW = options.outWidth;
            int srcH = options.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;
            int sample = WallpaperSize.sampleSize(srcW, srcH, screenW, screenH);
            if (!WallpaperSize.accepted(srcW / sample, srcH / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(srcW / sample, srcH / sample, screenW, screenH);
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

    private int nextSaveNumber(UnsplashPhotos.State state) {
        int last = state == null ? 0 : state.lastSaved;
        int onDisk = 0;
        for (int n : listNumbers()) if (n > onDisk) onDisk = n;
        if (onDisk > last) last = onDisk;
        return UnsplashPhotos.nextFileNumber(last);
    }

    private int[] listNumbers() {
        File[] files = imageDir().listFiles();
        if (files == null || files.length == 0) return new int[0];
        int[] found = new int[files.length];
        int count = 0;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) continue;
            int number = UnsplashPhotos.fileNumber(file.getName());
            if (number > 0) found[count++] = number;
        }
        int[] out = new int[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    private void writeCredit(int number, UnsplashPhotos.Photo photo) {
        try {
            File file = creditFile(number);
            File folder = file.getParentFile();
            if (folder != null) folder.mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(UnsplashPhotos.creditLine(photo).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w("TvHome.Photos", "could not save the credit");
        }
    }

    private UnsplashPhotos.Photo creditFor(int number) {
        File file = creditFile(number);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(0, nl);
            return UnsplashPhotos.readCredit(text);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeState(UnsplashPhotos.State state) {
        if (state == null) return;
        try {
            File file = stateFile();
            File folder = file.getParentFile();
            if (folder != null) folder.mkdirs();
            File part = new File(folder, "state.txt.part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(state.save().getBytes(StandardCharsets.UTF_8));
            }
            if (file.exists() && !file.delete()) {
                part.delete();
                return;
            }
            if (!part.renameTo(file)) part.delete();
        } catch (Exception e) {
            Log.w("TvHome.Photos", "could not save the photo counter");
        }
    }

    private UnsplashPhotos.State readState() {
        File file = stateFile();
        if (!file.isFile()) return UnsplashPhotos.State.EMPTY;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return UnsplashPhotos.State.load(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return UnsplashPhotos.State.EMPTY;
        }
    }

    private File imageDir() {
        return new File(activity.getFilesDir(), "unsplash/images");
    }

    private File creditFile(int number) {
        return new File(new File(activity.getFilesDir(), "unsplash/credits"),
                String.format(Locale.US, "%02d.txt", number));
    }

    private File stateFile() {
        return new File(activity.getFilesDir(), "unsplash/state.txt");
    }

    private void runWorker(Runnable job) {
        ExecutorService exec = executor;
        if (!running || exec == null) return;
        try {
            exec.execute(() -> {
                try {
                    job.run();
                } catch (Throwable t) {
                    Log.w("TvHome.Photos", "photo task failed: " + t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // stop() already shut the thread down
        }
    }

    private static final class Result {
        final int status;
        final String remaining;
        final String reset;
        final UnsplashPhotos.Photo photo;

        Result(int status, String remaining, String reset, UnsplashPhotos.Photo photo) {
            this.status = status;
            this.remaining = remaining;
            this.reset = reset;
            this.photo = photo;
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
