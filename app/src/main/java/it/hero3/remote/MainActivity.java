package it.hero3.remote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final CameraApi api = new CameraApi();
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService pictures = Executors.newFixedThreadPool(2);
    private final ExecutorService transfers = Executors.newSingleThreadExecutor();
    private final ExecutorService photoPreviews = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, Bitmap> imageCache = new android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<ImageView, String> pendingImages = new LinkedHashMap<>();
    private final Map<String, String> imageVersions = new ConcurrentHashMap<>();
    private PreviewCache diskImages;
    private boolean galleryOnline, galleryLoaded;
    private boolean photoPreviewOpen;
    private Dialog photoDialog;
    private PreviewUi photoUi;
    private float photoAspect = 16f / 9f;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private ConnectivityManager networks;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network wifiNetwork;
    private int wifiGeneration;
    private LinearLayout galleryPanel, controlsPanel, cards, accessBox, commandBox;
    private TextView galleryBadge, commandBadge, message, battery, capture, galleryCount, accessError;
    private EditText passwordField;
    private Button galleryTab, controlsTab, authenticate, shutter, videoMode, photoMode, preview;
    private ImageButton refresh;
    private final Map<String, Button> filterButtons = new LinkedHashMap<>();
    private int sortOrder = 2;
    private List<CameraApi.Entry> files = new ArrayList<>();
    private String filter = "all", password = "";
    private boolean controlsReady, busy, galleryBusy, resumed, controlsVisible, statusReading;
    private volatile boolean destroyed;
    private CameraApi.Status status;
    private long lastStatusRead;
    private int pictureGeneration;
    private CameraApi.Entry pendingDownload;
    private volatile boolean downloadCancelled;
    private volatile HttpURLConnection downloadConnection;
    private boolean downloading;
    private static final int SAVE_FILE = 10;
    private static final int PLAY_VIDEO = 11;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (resumed && controlsVisible && controlsReady && !busy && System.currentTimeMillis() - lastStatusRead >= (status != null && status.recording() ? 5000 : 15000)) readStatus(false);
            main.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        diskImages = new PreviewCache(new File(getCacheDir(), "previews"), 64L * 1024 * 1024);
        bindWifi();
        LinearLayout root = column(); root.setPadding(dp(16), dp(16), dp(16), dp(16)); root.setBackgroundColor(0xff101419);
        root.setOnApplyWindowInsetsListener((v, insets) -> { root.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(), dp(16), dp(16) + insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 24); title.setContentDescription(getString(R.string.app_name) + " · " + getString(R.string.app_description)); heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = iconButton(R.drawable.wifi, "Impostazioni Wi-Fi"); heading.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))); root.addView(heading);
        settings.setOnLongClickListener(v -> { CrashDiagnostics.share(this); return true; });
        LinearLayout badges = row(); badges.setGravity(Gravity.CENTER_VERTICAL);
        galleryBadge = text("Galleria · Offline", 12); commandBadge = text("Comandi · Non autenticati", 12);
        galleryBadge.setSingleLine(true); commandBadge.setSingleLine(true);
        addHalf(badges, galleryBadge); addHalf(badges, commandBadge); root.addView(badges);
        LinearLayout tabs = row(); galleryTab = button("▧ Galleria"); controlsTab = button("◎ Comandi"); addHalf(tabs, galleryTab); addHalf(tabs, controlsTab); root.addView(tabs);
        ScrollView scroll = new ScrollView(this); LinearLayout content = column(); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisibleImages());
        galleryPanel = panel(); controlsPanel = column(); content.addView(galleryPanel); content.addView(controlsPanel);
        createGallery(); createControls();
        message = text("Connetti il telefono al Wi-Fi GoPro. Non servono PC o server.", 13); root.addView(message);
        setContentView(root);
        galleryTab.setOnClickListener(v -> chooseTab(false)); controlsTab.setOnClickListener(v -> chooseTab(true));
        chooseTab(false); main.post(poll); loadGallery();
    }
    private void bindWifi() {
        networks = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                main.post(() -> {
                    if (destroyed) return;
                    if (network.equals(wifiNetwork)) return;
                    wifiNetwork = network; wifiGeneration++; networks.bindProcessToNetwork(network);
                    if (galleryBadge != null) loadGallery();
                });
            }
            @Override public void onLost(Network network) {
                main.post(() -> {
                    if (destroyed || !network.equals(wifiNetwork)) return;
                    wifiNetwork = null; wifiGeneration++; networks.bindProcessToNetwork(null);
                    if (galleryBadge == null) return;
                    showDisconnectedGallery();
                });
            }
        };
        networks.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), wifiCallback);
        for (Network network : networks.getAllNetworks()) {
            NetworkCapabilities caps = networks.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { wifiNetwork = network; networks.bindProcessToNetwork(network); break; }
        }
    }
    private void createGallery() {
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("I tuoi contenuti", 21), new LinearLayout.LayoutParams(0, -2, 1));
        refresh = iconButton(android.R.drawable.ic_popup_sync, "Aggiorna galleria"); heading.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48))); galleryPanel.addView(heading); refresh.setOnClickListener(v -> loadGallery());
        LinearLayout filters = row();
        for (String kind : List.of("all", "video", "photo")) {
            Button button = button(kind.equals("all") ? "Tutti" : kind.equals("video") ? "Video" : "Foto"); addHalf(filters, button);
            filterButtons.put(kind, button);
            button.setOnClickListener(v -> { filter = kind; updateFilters(); renderGallery(); });
        }
        galleryPanel.addView(filters); updateFilters();
        Spinner order = new Spinner(this);
        order.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Nome · A → Z", "Nome · Z → A", "Data · Più recenti", "Data · Meno recenti"}));
        order.setSelection(sortOrder);
        order.setContentDescription("Ordina contenuti"); galleryPanel.addView(order);
        order.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { sortOrder = position; if (cards != null) renderGallery(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        galleryCount = text("Camera non collegata", 12); galleryPanel.addView(galleryCount);
        cards = column(); galleryPanel.addView(cards);
    }
    private void createControls() {
        accessBox = panel(); commandBox = panel(); controlsPanel.addView(accessBox); controlsPanel.addView(commandBox);
        commandBox.setVisibility(View.GONE);
        accessBox.addView(text("Accesso ai comandi", 21));
        accessBox.addView(text("La password Wi-Fi serve all’API legacy, non alla galleria. Resta in memoria fino alla chiusura dell’app.", 13));
        passwordField = new EditText(this); passwordField.setTextColor(0xfff4f7fb); passwordField.setHintTextColor(0xffacb9ca); passwordField.setHint("Password Wi-Fi della camera");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); passwordField.setSaveEnabled(false); accessBox.addView(passwordField);
        authenticate = button("Abilita comandi"); accessBox.addView(authenticate);
        accessError = text("", 13); accessError.setTextColor(0xffff8c91); accessError.setVisibility(View.GONE); accessError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); accessBox.addView(accessError);
        authenticate.setOnClickListener(v -> {
            if (busy || statusReading) return;
            password = passwordField.getText().toString();
            if (password.isEmpty()) { accessFailure("Inserisci la password Wi-Fi della camera."); return; }
            accessError.setVisibility(View.GONE);
            controlsReady = false; readStatus(true);
        });
        battery = text("Batteria · Abilita i comandi", 14); commandBox.addView(battery);
        LinearLayout modes = row(); videoMode = button("Video"); photoMode = button("Foto"); addHalf(modes, videoMode); addHalf(modes, photoMode); commandBox.addView(modes);
        videoMode.setOnClickListener(v -> command("video")); photoMode.setOnClickListener(v -> command("photo"));
        capture = text("Comandi non autenticati", 20); commandBox.addView(capture);
        shutter = button("● Registra / Scatta"); shutter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffdf343f)); commandBox.addView(shutter);
        shutter.setOnClickListener(v -> command(status != null && status.mode() == 0 && status.recording() ? "stop" : "start"));
        preview = button("▶ Anteprima live con VLC"); commandBox.addView(preview);
        preview.setOnClickListener(v -> {
            if (busy || !controlsReady) return;
            busy = true; updateControls(); say("Attivazione del live originale…");
            work.execute(() -> {
                try { api.preview(password); ui(() -> { busy = false; updateControls(); if (resumed && controlsVisible) openPlayer(CameraApi.LIVE, "GoPro · Live originale"); }); }
                catch (Exception e) { ui(() -> { busy = false; updateControls(); say(error(e)); }); }
            });
        });
        // commandBox.addView(text("Player VLC nativo: nessun limite artificiale a 8 fps. L’audio viene riprodotto se presente nel flusso della camera. I tasti hardware regolano il volume.", 13));
        updateControls();
    }
    private void chooseTab(boolean controls) {
        controlsVisible = controls; controlsPanel.setVisibility(controls ? View.VISIBLE : View.GONE); galleryPanel.setVisibility(controls ? View.GONE : View.VISIBLE);
        galleryTab.setAlpha(controls ? .55f : 1f); controlsTab.setAlpha(controls ? 1f : .55f);
        if (controls && controlsReady && !busy && System.currentTimeMillis() - lastStatusRead >= 15000) readStatus(false);
        if (!controls) cards.post(this::loadVisibleImages);
    }
    private void readStatus(boolean authenticating) {
        if (busy || statusReading) return;
        statusReading = true; if (authenticating) busy = true; lastStatusRead = System.currentTimeMillis();
        if (authenticating) updateControls();
        final String credentials = password;
        work.execute(() -> {
            try { CameraApi.Status value = api.status(credentials); ui(() -> { statusReading = false; if (busy && !authenticating) return; status = value; controlsReady = true; if (authenticating) busy = false; accessError.setVisibility(View.GONE); updateControls(); if (authenticating) say(""); }); }
            catch (Exception e) { ui(() -> { statusReading = false; if (busy && !authenticating) return; if (authenticating) busy = false; accessFailure(error(e)); }); }
        });
    }
    private void command(String action) {
        if (!controlsReady || busy) return;
        final int modeBefore = status == null ? -1 : status.mode();
        busy = true; updateControls();
        if (action.equals("start")) { shutter.setText(modeBefore == 1 ? "Scatto in corso…" : "Avvio registrazione…"); }
        else if (action.equals("stop")) shutter.setText("Arresto registrazione…");
        work.execute(() -> {
            try {
                api.command(action, password);
                CameraApi.Status value = api.confirmCommand(action, modeBefore, password);
                ui(() -> { status = value; busy = false; lastStatusRead = System.currentTimeMillis(); updateControls(); say("Comando eseguito."); });
            } catch (Exception e) { ui(() -> { busy = false; accessFailure("Impossibile confermare lo stato dopo il comando: " + error(e) + " Verifica la camera prima di riprovare."); }); }
        });
    }
    private void updateControls() {
        if (shutter == null) return;
        accessBox.setVisibility(controlsReady ? View.GONE : View.VISIBLE);
        commandBox.setVisibility(controlsReady ? View.VISIBLE : View.GONE);
        commandBadge.setText(controlsReady ? "Comandi · Online" : "Comandi · Non autenticati"); commandBadge.setTextColor(controlsReady ? 0xff8de8b8 : 0xffacb9ca);
        boolean enabled = controlsReady && !busy;
        preview.setEnabled(enabled); shutter.setEnabled(enabled && status != null && status.mode() <= 1);
        videoMode.setEnabled(enabled && (status == null || !status.recording())); photoMode.setEnabled(videoMode.isEnabled()); authenticate.setEnabled(!busy);
        if (status == null || !controlsReady) return;
        battery.setText(status.battery() == null ? "Batteria · Non comunicata dal firmware" : "Batteria · " + (status.percent() ? status.battery() + "%" : "Livello " + status.battery()));
        boolean photo = status.mode() == 1;
        markMode(videoMode, status.mode() == 0);
        markMode(photoMode, photo);
        shutter.setText(photo ? "◎ Scatta foto" : status.recording() ? "■ Interrompi registrazione" : "● Avvia registrazione");
        capture.setText(photo ? "Modalità Foto" : (status.recording() ? "Registrazione · " : "Modalità Video · ") + String.format(Locale.ITALY, "%02d:%02d", status.elapsed() / 60, status.elapsed() % 60));
    }
    private void markMode(Button button, boolean selected) {
        button.setSelected(selected);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? 0xff244f6b : 0xff293340));
        button.setTextColor(selected ? 0xffa1dfff : 0xffacb9ca);
        button.setContentDescription(button.getText() + (selected ? ", modalità attiva" : ", modalità non attiva"));
    }
    private void loadGallery() {
        if (galleryBusy || destroyed) return;
        if (!hasWifi()) { showDisconnectedGallery(); return; }
        final int networkGeneration = wifiGeneration;
        galleryBusy = true; refresh.setEnabled(false); refresh.setAlpha(.4f); refresh.setContentDescription("Lettura galleria in corso");
        if (!galleryOnline) { galleryLoaded = false; galleryBadge.setText("Galleria · Verifica…"); renderGallery(); }
        work.execute(() -> {
            try { List<CameraApi.Entry> entries = api.gallery(); ui(() -> { if (networkGeneration != wifiGeneration || !hasWifi()) return; updateImageVersions(entries); files = entries; galleryOnline = true; galleryLoaded = true; galleryBadge.setText("Galleria · Online"); galleryBadge.setTextColor(0xff8de8b8); renderGallery(); say(""); }); }
            catch (Exception e) { ui(() -> { if (networkGeneration != wifiGeneration || !hasWifi()) return; galleryOnline = false; galleryLoaded = true; galleryBadge.setText("Galleria · Offline"); galleryBadge.setTextColor(0xffacb9ca); renderGallery(); say(error(e)); }); }
            finally { ui(() -> { galleryBusy = false; refresh.setEnabled(true); refresh.setAlpha(1f); refresh.setContentDescription("Aggiorna galleria"); if (!galleryOnline) renderGallery(); if (networkGeneration != wifiGeneration && hasWifi()) loadGallery(); }); }
        });
    }
    private boolean hasWifi() {
        if (wifiNetwork == null) return false;
        NetworkCapabilities caps = networks.getNetworkCapabilities(wifiNetwork);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    private void showDisconnectedGallery() {
        galleryOnline = false; galleryLoaded = true;
        galleryBadge.setText("Galleria · Offline"); galleryBadge.setTextColor(0xffacb9ca);
        renderGallery();
        accessFailure(getString(R.string.wifi_disconnected));
        say("");
    }
    private void renderGallery() {
        ++pictureGeneration; cards.removeAllViews(); pendingImages.clear();
        if (!galleryOnline) {
            boolean checking = galleryBusy && hasWifi();
            galleryCount.setText(checking ? "Verifica collegamento…" : "Camera non collegata");
            TextView connectionMessage = text(checking ? "Wi-Fi collegato. Verifica della GoPro in corso…" : getString(R.string.wifi_disconnected), 13);
            if (!checking) connectionMessage.setTextColor(0xffff8c91);
            connectionMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            cards.addView(connectionMessage);
            return;
        }
        int videos = 0;
        for (CameraApi.Entry file : files) if (file.video()) videos++;
        galleryCount.setText(String.format(Locale.ITALY, "%d elementi trovati (%d video e %d foto)", files.size(), videos, files.size() - videos));
        LinearLayout line = null; int count = 0;
        List<CameraApi.Entry> sorted = new ArrayList<>(files); sorted.sort(CameraApi.ordering(sortOrder));
        for (CameraApi.Entry file : sorted) {
            if (filter.equals("video") && !file.video() || filter.equals("photo") && file.video()) continue;
            if (count++ % 2 == 0) { line = row(); cards.addView(line); }
            LinearLayout card = panel(); line.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackgroundColor(0xff22384c);
            image.setContentDescription(file.name()); card.addView(image, new LinearLayout.LayoutParams(-1, dp(110)));
            TextView name = text((file.video() ? "▷ " : "") + file.name(), 12); card.addView(name);
            if (sortOrder >= 2) card.addView(text(file.modified() == null ? "Data non disponibile" : new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(new Date(file.modified())), 11));
            View.OnClickListener open = v -> { if (file.video()) openPlayer(file); else openPhoto(file); };
            card.setOnClickListener(open); image.setOnClickListener(open); name.setOnClickListener(open); card.setContentDescription("Apri " + file.name());
            String path = file.video() ? file.path().replaceFirst("(?i)\\.mp4$", ".THM") : file.path();
            Bitmap cached = imageCache.get(imageKey(path));
            if (cached != null) image.setImageBitmap(cached);
            else pendingImages.put(image, path);
        }
        if (count == 0) cards.addView(text("Nessun contenuto per questo filtro. Aggiorna dopo aver scattato o registrato.", 15));
        cards.post(this::loadVisibleImages);
    }
    private void loadVisibleImages() {
        if (!resumed || controlsVisible || downloading || photoPreviewOpen || destroyed) return;
        Iterator<Map.Entry<ImageView, String>> iterator = pendingImages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ImageView, String> entry = iterator.next(); ImageView image = entry.getKey();
            if (!image.getGlobalVisibleRect(new Rect())) continue;
            String path = entry.getValue(); final int generation = pictureGeneration; iterator.remove();
            Bitmap cached = imageCache.get(imageKey(path));
            if (cached != null) image.setImageBitmap(cached);
            else loadImage(path, bitmap -> { if (generation == pictureGeneration) image.setImageBitmap(bitmap); }, failure -> {
                if (failure.equals("deferred") && generation == pictureGeneration) pendingImages.put(image, path);
            }, pictures);
        }
    }
    private void updateFilters() {
        for (Map.Entry<String, Button> entry : filterButtons.entrySet()) {
            boolean selected = entry.getKey().equals(filter);
            Button button = entry.getValue(); button.setSelected(selected);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? 0xff244f6b : 0xff293340));
            button.setTextColor(selected ? 0xffa1dfff : 0xffacb9ca);
            button.setContentDescription(button.getText() + (selected ? ", filtro selezionato" : ", filtro non selezionato"));
        }
    }
    private interface BitmapResult { void receive(Bitmap bitmap); }
    private String imageKey(String path) { return path + "|" + imageVersions.getOrDefault(path, "unknown"); }
    private void updateImageVersions(List<CameraApi.Entry> entries) {
        for (CameraApi.Entry entry : entries) {
            String path = entry.video() ? entry.path().replaceFirst("(?i)\\.mp4$", ".THM") : entry.path();
            String version = entry.modified() == null ? "unknown" : entry.modified().toString();
            String previous = imageVersions.put(path, version);
            if (previous != null && !previous.equals(version)) imageCache.remove(path + "|" + previous);
        }
    }
    private void loadImage(String path, BitmapResult result) {
        Bitmap cached = imageCache.get(imageKey(path));
        if (cached != null) { ui(() -> result.receive(cached)); return; }
        loadImage(path, result, failure -> {}, pictures);
    }
    private void loadImage(String path, BitmapResult result, java.util.function.Consumer<String> failure, ExecutorService executor) {
        if (destroyed || pictures.isShutdown()) return;
        final String key = imageKey(path);
        executor.execute(() -> {
            if (executor == pictures && (!resumed || controlsVisible || downloading || photoPreviewOpen)) { ui(() -> failure.accept("deferred")); return; }
            try {
                // Recheck here: an earlier queued request may have populated the cache.
                Bitmap memory = imageCache.get(key);
                if (memory != null) { ui(() -> result.receive(memory)); return; }
                File cachedFile = diskImages.find(key);
                if (cachedFile != null) {
                    Bitmap cached = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
                    if (cached != null) { imageCache.put(key, cached); ui(() -> result.receive(cached)); return; }
                    diskImages.remove(key);
                }
                HttpURLConnection connection = CameraApi.open(CameraApi.MEDIA + path); activeConnections.add(connection);
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int size;
                    while ((size = input.read(chunk)) != -1) { output.write(chunk, 0, size); if (output.size() > 25 * 1024 * 1024) throw new IOException("Foto troppo grande per l’anteprima."); }
                    bytes = output.toByteArray();
                } finally { activeConnections.remove(connection); connection.disconnect(); }
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                options.inSampleSize = 1; while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false; Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new IOException("La risposta non contiene un’immagine JPEG leggibile.");
                imageCache.put(key, bitmap);
                try { diskImages.store(key, out -> { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw new IOException("Conversione cache fallita."); }); }
                catch (IOException ignored) { /* A cache failure must not hide a successfully loaded photo. */ }
                ui(() -> result.receive(bitmap));
            } catch (OutOfMemoryError exhausted) {
                ui(() -> failure.accept("Memoria insufficiente per l’anteprima foto. Puoi scaricare l’originale."));
            } catch (Exception e) { ui(() -> failure.accept("Impossibile aprire la foto: " + error(e))); }
        });
    }
    private void openPhoto(CameraApi.Entry file) {
        photoPreviewOpen = true;
        Dialog dialog = new Dialog(this);
        photoDialog = dialog; photoAspect = 16f / 9f;
        dialog.setOnDismissListener(ignored -> { photoPreviewOpen = false; photoDialog = null; photoUi = null; cards.post(this::loadVisibleImages); });
        PreviewUi preview = new PreviewUi(this, file.name(), () -> { dialog.dismiss(); chooseDownload(file); }, dialog::dismiss);
        photoUi = preview;
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.content.addView(image, new FrameLayout.LayoutParams(-1, -1));
        preview.status.setText("Caricamento foto…");
        dialog.setContentView(preview.root); dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(PreviewUi.width(this), ViewGroup.LayoutParams.WRAP_CONTENT);
        BitmapResult show = bitmap -> {
            if (!dialog.isShowing()) return;
            image.setImageBitmap(bitmap); preview.status.setText(""); preview.status.setVisibility(View.INVISIBLE);
            photoAspect = (float) bitmap.getWidth() / bitmap.getHeight(); resizePhoto();
        };
        Bitmap cached = imageCache.get(imageKey(file.path()));
        if (cached != null) show.receive(cached);
        else loadImage(file.path(), show, error -> { if (dialog.isShowing()) preview.status.setText(error); }, photoPreviews);
    }
    private void openPlayer(String url, String title) { startActivity(new Intent(this, PlayerActivity.class).putExtra("url", url).putExtra("title", title)); }
    private void openPlayer(CameraApi.Entry file) {
        pendingDownload = file;
        startActivityForResult(new Intent(this, PlayerActivity.class).putExtra("url", CameraApi.MEDIA + file.path()).putExtra("title", file.name()).putExtra("download", true), PLAY_VIDEO);
    }
    private void chooseDownload(CameraApi.Entry file) {
        if (downloading) { say("Un download è già in corso. Attendi o annullalo prima di iniziarne un altro."); return; }
        pendingDownload = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(file.video() ? "video/mp4" : "image/jpeg").putExtra(Intent.EXTRA_TITLE, file.name());
        startActivityForResult(intent, SAVE_FILE);
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PLAY_VIDEO) {
            CameraApi.Entry file = pendingDownload; pendingDownload = null;
            if (result == RESULT_OK && file != null) chooseDownload(file);
            return;
        }
        if (request != SAVE_FILE || result != RESULT_OK || data == null || data.getData() == null || pendingDownload == null) return;
        CameraApi.Entry file = pendingDownload; pendingDownload = null; Uri destination = data.getData();
        downloading = true; downloadCancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout box = column(); box.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView progressText = text("Connessione alla GoPro…", 14); box.addView(progressText);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setMax(1000); box.addView(progress);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Download · " + file.name()).setView(box).setNegativeButton("Annulla", null).setCancelable(false).create(); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            downloadCancelled = true; HttpURLConnection connection = downloadConnection; if (connection != null) connection.disconnect();
            progressText.setText("Annullamento…"); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        });
        say("Download in corso. Tieni l’app aperta e collegata al Wi-Fi.");
        transfers.execute(() -> {
            HttpURLConnection connection = null;
            try {
                long started = System.currentTimeMillis();
                connection = CameraApi.open(CameraApi.MEDIA + file.path()); activeConnections.add(connection);
                downloadConnection = connection;
                connection.setReadTimeout(30000);
                long total = connection.getContentLengthLong();
                ui(() -> { progress.setIndeterminate(total <= 0); progressText.setText("Trasferimento avviato…"); });
                try (InputStream input = connection.getInputStream(); OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("Destinazione non disponibile.");
                    DownloadTransfer.copy(input, output, total, () -> downloadCancelled, bytes -> {
                        double speed = bytes / Math.max(1d, (System.currentTimeMillis() - started) / 1000d);
                        ui(() -> {
                            if (total > 0) progress.setProgress((int) Math.min(1000, bytes * 1000 / total));
                            progressText.setText(total > 0
                                ? String.format(Locale.ITALY, "%.1f / %.1f MB · %.0f%%\n%.0f KB/s", bytes / 1000000d, total / 1000000d, bytes * 100d / total, speed / 1000d)
                                : String.format(Locale.ITALY, "%.1f MB trasferiti · dimensione totale non comunicata\n%.0f KB/s", bytes / 1000000d, speed / 1000d));
                        });
                    });
                }
                ui(() -> { progress.setIndeterminate(false); progress.setProgress(1000); progressText.setText("Download completato.\nSalvato: " + file.name()); say("Salvato: " + file.name()); });
            } catch (Exception e) {
                String failure = downloadCancelled ? "Download annullato. Il file può essere parziale." : "Download non completato: " + error(e) + "\nIl file può essere parziale. La camera potrebbe non consentire il trasferimento mentre registra.";
                ui(() -> { progress.setIndeterminate(false); progressText.setText(failure); say(failure); });
            } finally {
                if (connection != null) { activeConnections.remove(connection); connection.disconnect(); }
                downloadConnection = null;
                ui(() -> { downloading = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE); close.setText("Chiudi"); close.setEnabled(true); close.setOnClickListener(v -> { dialog.dismiss(); cards.post(this::loadVisibleImages); }); });
            }
        });
    }
    private void ui(Runnable action) { main.post(() -> { if (!destroyed) action.run(); }); }
    private void say(String text) { message.setText(text); message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE); }
    private void accessFailure(String text) {
        controlsReady = false;
        accessError.setText(text); accessError.setVisibility(View.VISIBLE);
        updateControls();
    }
    private String error(Exception e) { return e.getMessage() == null ? "Camera non raggiungibile. Verifica il Wi-Fi." : e.getMessage(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private LinearLayout panel() {
        LinearLayout view = column(); view.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff1a2028); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), 0xff323d4a); view.setBackground(bg); return view;
    }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xfff4f7fb); view.setPadding(0, dp(6), 0, dp(6)); return view; }
    private Button button(String label) { Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(48)); return view; }
    private ImageButton iconButton(int icon, String description) {
        ImageButton view = new ImageButton(this); view.setImageResource(icon); view.setContentDescription(description);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(0xff91d8ff)); view.setBackgroundColor(Color.TRANSPARENT); view.setPadding(dp(10), dp(10), dp(10), dp(10)); return view;
    }
    private void addHalf(LinearLayout row, View child) { row.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private void resizePhoto() {
        if (photoDialog == null || !photoDialog.isShowing() || photoUi == null) return;
        photoDialog.getWindow().setLayout(PreviewUi.width(this), ViewGroup.LayoutParams.WRAP_CONTENT);
        ViewGroup.LayoutParams size = photoUi.content.getLayoutParams(); size.height = PreviewUi.height(this, photoAspect); photoUi.content.setLayoutParams(size);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Existing views reflow without rebuilding the gallery or issuing another index request.
        cards.post(() -> { resizePhoto(); loadVisibleImages(); });
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (cards != null) cards.post(this::loadVisibleImages); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; downloadCancelled = true; pictureGeneration++; main.removeCallbacksAndMessages(null); work.shutdownNow(); pictures.shutdownNow(); photoPreviews.shutdownNow(); transfers.shutdownNow(); imageCache.evictAll();
        for (HttpURLConnection connection : activeConnections) connection.disconnect();
        if (networks != null) { networks.unregisterNetworkCallback(wifiCallback); networks.bindProcessToNetwork(null); }
        super.onDestroy();
    }
}
