package it.hero3.remote;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.WindowManager;
import android.widget.*;
import org.videolan.libvlc.*;
import java.util.ArrayList;

public final class PlayerActivity extends Activity {
    private LibVLC vlc;
    private MediaPlayer player;
    private TextView status;
    // private Button pause, audio;
    // private boolean muted;
    private int generation;
    private FrameLayout videoFrame;
    private SurfaceView surface;
    private float videoAspect = 16f / 9f;
    private Button playOverlay;
    private boolean live;
    private boolean playRequested;
    private Media playbackMedia;
    private final PlaybackStatus playbackStatus = new PlaybackStatus();
    private boolean released, failed;
    private final Runnable watchPlayback = new Runnable() {
        @Override public void run() {
            if (player == null || released || failed) return;
            if (playRequested) {
                try {
                    // Some firmware/player combinations do not expose stats while opening.
                    org.videolan.libvlc.interfaces.IMedia.Stats stats = null;
                    try { stats = playbackMedia.getStats(); } catch (RuntimeException ignored) {}
                    playbackStatus.sample(android.os.SystemClock.elapsedRealtime(), stats == null ? -1 : Integer.toUnsignedLong(stats.displayedPictures), player.getTime());
                    showStatus(playbackStatus.text());
                } catch (RuntimeException error) { playerFailure("Lettura stato VLC", error); return; }
            }
            status.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams window = getWindow().getAttributes(); window.dimAmount = .65f; getWindow().setAttributes(window);
        live = !getIntent().getBooleanExtra("download", false);
        PreviewUi preview = new PreviewUi(this, getIntent().getStringExtra("title"), live ? null : () -> { setResult(RESULT_OK); finish(); }, this::finish);
        LinearLayout root = preview.root;
        videoFrame = preview.content;
        surface = new SurfaceView(this); videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        playOverlay = new Button(this); playOverlay.setText("▶"); playOverlay.setTextSize(26); playOverlay.setContentDescription("Riproduci video");
        playOverlay.setTextColor(Color.WHITE); playOverlay.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xaa203347));
        videoFrame.addView(playOverlay, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        playOverlay.setVisibility(live ? View.GONE : View.VISIBLE);
        videoFrame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        status = preview.status; showStatus("");
        // Controls retained for future reactivation, hidden as requested for both video and live.
        // LinearLayout buttons = new LinearLayout(this); root.addView(buttons);
        // pause = new Button(this); pause.setText("Pausa"); buttons.addView(pause, new LinearLayout.LayoutParams(0, -2, 1));
        // audio = new Button(this); audio.setText("Audio attivo"); buttons.addView(audio, new LinearLayout.LayoutParams(0, -2, 1));
        setContentView(root);
        try {
        int caching = live ? 1500 : 500;
        vlc = new LibVLC(this, new ArrayList<>(java.util.List.of("--network-caching=" + caching, "--no-video-title-show", "--avcodec-hw=none", "--stats")));
        player = new MediaPlayer(vlc);
        player.getVLCVout().setVideoView(surface);
        player.getVLCVout().attachViews((output, width, height, visibleWidth, visibleHeight, sarNum, sarDen) -> runOnUiThread(() -> {
            if (released || failed || player == null || visibleWidth <= 0 || visibleHeight <= 0) return;
            videoAspect = (float) visibleWidth / visibleHeight * (sarNum > 0 && sarDen > 0 ? (float) sarNum / sarDen : 1f);
            resizeWindow();
        }));
        player.setEventListener(event -> runOnUiThread(() -> {
            if (player == null || released || failed) return;
            if (event.type == MediaPlayer.Event.Playing) {
                playOverlay.setText("Ⅱ"); playOverlay.setContentDescription("Metti in pausa il video");
                // pause.setText("Pausa");
            } else if (event.type == MediaPlayer.Event.Buffering && playRequested) { playbackStatus.buffering(event.getBuffering()); showStatus(playbackStatus.text()); }
            else if (event.type == MediaPlayer.Event.Paused) { playRequested = false; playbackStatus.stop(); generation++; playOverlay.setText("▶"); playOverlay.setContentDescription("Riprendi video"); showStatus(""); }
            else if (event.type == MediaPlayer.Event.EncounteredError) { playRequested = false; playbackStatus.stop(); playOverlay.setText("▶"); showStatus("VLC non riesce a riprodurre il flusso. Verifica Wi-Fi e stato della camera."); }
            else if (event.type == MediaPlayer.Event.EndReached) { playRequested = false; playbackStatus.stop(); generation++; playOverlay.setText("▶"); showStatus(""); /* pause.setText("Riproduci"); */ }
        }));
        playbackMedia = new Media(vlc, Uri.parse(getIntent().getStringExtra("url")));
        playbackMedia.setHWDecoderEnabled(false, false); playbackMedia.addOption(":network-caching=" + caching);
        player.setMedia(playbackMedia);
        if (live) surface.post(this::startPlayback);
        playOverlay.setOnClickListener(v -> {
            if (player == null || released || failed) return;
            try {
                if (playRequested) { playRequested = false; playbackStatus.stop(); player.pause(); playOverlay.setText("▶"); showStatus(""); }
                else startPlayback();
            } catch (RuntimeException error) { playerFailure("Pausa VLC", error); }
        });
        // pause.setOnClickListener(v -> { if (player.isPlaying()) { player.pause(); pause.setText("Riproduci"); } else player.play(); });
        // audio.setOnClickListener(v -> { muted = !muted; player.setVolume(muted ? 0 : 100); audio.setText(muted ? "Audio disattivato" : "Audio attivo"); });
        root.post(this::resizeWindow);
        status.postDelayed(watchPlayback, 1000);
        } catch (RuntimeException | LinkageError error) { playerFailure("Avvio VLC", error); }
    }
    private void showStatus(String text) {
        if (!status.getText().toString().equals(text)) status.setText(text);
        int visibility = text.isEmpty() ? View.INVISIBLE : View.VISIBLE;
        if (status.getVisibility() != visibility) status.setVisibility(visibility);
    }
    private void startPlayback() {
        if (released || failed || player == null) return;
        playRequested = true; playbackStatus.start(android.os.SystemClock.elapsedRealtime());
        playOverlay.setText("Ⅱ"); showStatus(playbackStatus.text());
        try { player.play(); } catch (RuntimeException error) { playerFailure("Riproduzione VLC", error); }
    }
    private void playerFailure(String stage, Throwable error) {
        failed = true; playRequested = false; playbackStatus.stop();
        CrashDiagnostics.record(this, stage, error);
        status.removeCallbacks(watchPlayback); playOverlay.setEnabled(false);
        showStatus("Errore del player VLC. Chiudi l’anteprima e riprova. Se ricapita, tieni premuta l’icona Wi-Fi per condividere la diagnostica.");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        videoFrame.post(this::resizeWindow);
    }
    private void resizeWindow() {
        if (player == null || released || failed) return;
        int width = PreviewUi.width(this);
        ViewGroup.LayoutParams frame = videoFrame.getLayoutParams(); frame.height = PreviewUi.height(this, videoAspect); videoFrame.setLayoutParams(frame);
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT); getWindow().setGravity(Gravity.CENTER);
        videoFrame.post(this::fitVideo);
    }
    private void fitVideo() {
        if (player == null || released || failed || videoFrame.getWidth() <= 0 || videoFrame.getHeight() <= 0) return;
        int width = videoFrame.getWidth(), height = videoFrame.getHeight();
        if ((float) width / height > videoAspect) width = Math.round(height * videoAspect); else height = Math.round(width / videoAspect);
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (size.width != width || size.height != height) { size.width = width; size.height = height; size.gravity = Gravity.CENTER; surface.setLayoutParams(size); }
        try { player.getVLCVout().setWindowSize(width, height); }
        catch (RuntimeException error) { playerFailure("Dimensionamento VLC", error); }
    }
    @Override protected void onStop() {
        super.onStop(); playRequested = false; playbackStatus.stop();
        if (player != null && !released) try { player.pause(); } catch (RuntimeException error) { CrashDiagnostics.record(this, "Sospensione VLC", error); }
    }
    @Override protected void onDestroy() {
        generation++;
        released = true;
        status.removeCallbacks(watchPlayback);
        if (player != null) {
            MediaPlayer closing = player; player = null;
            try { closing.setEventListener(null); closing.stop(); closing.getVLCVout().detachViews(); }
            catch (RuntimeException error) { CrashDiagnostics.record(this, "Arresto VLC", error); }
            finally { try { closing.release(); } catch (RuntimeException error) { CrashDiagnostics.record(this, "Rilascio VLC", error); } }
        }
        if (playbackMedia != null) { try { playbackMedia.release(); } catch (RuntimeException error) { CrashDiagnostics.record(this, "Rilascio media", error); } playbackMedia = null; }
        if (vlc != null) try { vlc.release(); } catch (RuntimeException error) { CrashDiagnostics.record(this, "Rilascio LibVLC", error); }
        super.onDestroy();
    }
}
