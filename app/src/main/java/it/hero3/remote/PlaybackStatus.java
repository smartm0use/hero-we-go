package it.hero3.remote;

import java.util.Locale;

final class PlaybackStatus {
    private boolean requested, waiting;
    private Float percentage;
    private long lastProgress, progressSince = -1;
    private long lastFrames = -1, lastPosition = -1;
    void start(long now) {
        requested = true; waiting = true; percentage = null;
        lastProgress = now; progressSince = -1; lastFrames = -1; lastPosition = -1;
    }
    void stop() { requested = false; waiting = false; percentage = null; }
    void buffering(float value) {
        if (!requested || !Float.isFinite(value)) return;
        if (value < 100) { waiting = true; progressSince = -1; }
        percentage = Math.max(0, Math.min(100, value));
        if (value >= 100) waiting = false;
        // 100% describes the input buffer, not a guarantee that frames are being displayed.
    }
    void sample(long now, long displayedFrames, long position) {
        if (!requested) return;
        boolean progress = displayedFrames >= 0
            ? lastFrames >= 0 && displayedFrames > lastFrames
            : lastPosition >= 0 && position > lastPosition;
        lastFrames = displayedFrames; lastPosition = position;
        if (progress) {
            lastProgress = now;
            if (progressSince < 0) progressSince = now;
            if (waiting && now - progressSince >= 1000) { waiting = false; percentage = null; }
        } else {
            progressSince = -1;
            if (now - lastProgress >= 2500 && !waiting) { waiting = true; percentage = null; }
        }
    }
    String text() {
        if (!requested || !waiting) return "";
        return percentage == null ? "Buffering…" : String.format(Locale.ITALY, "Buffering… %.0f%%", percentage);
    }
}
