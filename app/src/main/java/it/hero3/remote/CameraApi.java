package it.hero3.remote;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.regex.*;

public final class CameraApi {
    public static final String CAMERA = "http://10.5.5.9";
    public static final String MEDIA = CAMERA + ":8080";
    public static final String LIVE = MEDIA + "/live/amba.m3u8";
    public record Entry(String path, String name, boolean folder, boolean video, Long modified) {}
    public record Status(int mode, boolean recording, int elapsed, Integer battery, boolean percent) {}
    public static final class CameraException extends IOException {
        public final int code;
        CameraException(int code) { super(code == 403 ? "Password errata (403)." : code == 410 ? "Comando rifiutato dalla camera (410)." : "GoPro: HTTP " + code); this.code = code; }
    }
    private final String camera, media;
    private Integer battery;
    private boolean percent;
    private long batteryCheck;
    public CameraApi() { this(CAMERA, MEDIA); }
    CameraApi(String camera, String media) { this.camera = camera; this.media = media; }

    public static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false); connection.setRequestProperty("Accept-Encoding", "identity");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) { connection.disconnect(); throw new CameraException(code); }
        return connection;
    }
    private byte[] get(String address) throws IOException {
        HttpURLConnection connection = open(address);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) { output.write(buffer, 0, size); if (output.size() > 8 * 1024 * 1024) throw new IOException("Risposta della camera troppo grande."); }
            return output.toByteArray();
        }
        finally { connection.disconnect(); }
    }
    static String token(String password) {
        try { return URLEncoder.encode(password, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    static String commandPath(String action, String password) {
        String path, value;
        switch (action) {
            case "start": path = "/bacpac/SH"; value = "01"; break;
            case "stop": path = "/bacpac/SH"; value = "00"; break;
            case "video": path = "/camera/CM"; value = "00"; break;
            case "photo": path = "/camera/CM"; value = "01"; break;
            case "preview": path = "/camera/PV"; value = "02"; break;
            default: throw new IllegalArgumentException("Comando non valido");
        }
        return path + "?t=" + token(password) + "&p=%" + value;
    }
    public void command(String action, String password) throws IOException { get(camera + commandPath(action, password)); }
    public void preview(String password) throws IOException {
        try { command("preview", password); }
        catch (CameraException e) { if (e.code != 410) throw e; }
        String playlist = new String(get(media + "/live/amba.m3u8"), StandardCharsets.UTF_8);
        if (!playlist.trim().startsWith("#EXTM3U")) throw new IOException("La camera non restituisce una playlist HLS valida.");
    }
    public synchronized Status status(String password) throws IOException {
        byte[] bytes = get(camera + "/camera/sx?t=" + token(password));
        if (bytes.length < 30) throw new IOException("Stato camera incompleto.");
        if (bytes.length > 57) { battery = bytes[57] & 255; percent = false; }
        else if (System.currentTimeMillis() - batteryCheck >= 60000) {
            batteryCheck = System.currentTimeMillis();
            try {
                byte[] legacy = get(camera + "/camera/se?t=" + token(password));
                if (legacy.length >= 30 && legacy[1] == bytes[1] && (legacy[19] & 255) <= 100) { battery = legacy[19] & 255; percent = true; }
            } catch (IOException ignored) {}
        }
        return new Status(bytes[1] & 255, bytes[29] != 0, ((bytes[13] & 255) << 8) | (bytes[14] & 255), battery, percent);
    }
    public Status confirmCommand(String action, int previousMode, String password) throws IOException {
        // SH may return before the camera changes state or finishes processing a photo.
        long deadline = System.currentTimeMillis() + 12000;
        IOException lastError = null;
        do {
            try {
                Status value = status(password);
                boolean confirmed = switch (action) {
                    case "video" -> value.mode() == 0;
                    case "photo" -> value.mode() == 1;
                    case "start" -> previousMode == 1 ? !value.recording() : value.recording();
                    case "stop" -> !value.recording();
                    default -> true;
                };
                if (confirmed) return value;
            } catch (CameraException e) {
                if (e.code == 403) throw e;
                lastError = e;
            } catch (IOException e) { lastError = e; }
            try { Thread.sleep(350); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Verifica interrotta.", e); }
        } while (System.currentTimeMillis() < deadline);
        throw new IOException("Stato non confermato entro 12 secondi" + (lastError == null ? "." : ": " + lastError.getMessage()));
    }
    static List<Entry> parseIndex(String html, String directory, String origin) {
        URI base = URI.create(origin + directory);
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Element anchor : Jsoup.parse(html).select("a[href]")) {
            try {
                URI uri = base.resolve(anchor.attr("href")).normalize();
                if (!Objects.equals(uri.getScheme(), base.getScheme()) || !Objects.equals(uri.getRawAuthority(), base.getRawAuthority()) || uri.getRawQuery() != null || uri.getRawFragment() != null) continue;
                String path = uri.getRawPath(), decoded = uri.getPath();
                if (!path.startsWith(directory) || decoded.contains("\\") || decoded.matches(".*[\\x00-\\x1f].*") || Arrays.asList(decoded.split("/")).contains("..")) continue;
                String relative = path.substring(directory.length());
                boolean folder = relative.endsWith("/");
                if (folder) relative = relative.substring(0, relative.length() - 1);
                if (relative.isEmpty() || relative.contains("/")) continue;
                String name = URLDecoder.decode(relative.replace("+", "%2B"), "UTF-8");
                boolean video = name.toLowerCase(Locale.ROOT).endsWith(".mp4");
                if (!folder && !video && !name.toLowerCase(Locale.ROOT).endsWith(".jpg")) continue;
                Element row = anchor.closest("tr");
                String metadata = row != null ? row.text() : anchor.nextSibling() == null ? "" : anchor.nextSibling().toString();
                entries.put(path, new Entry(path, name, folder, video, parseDate(metadata)));
            } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {}
        }
        return new ArrayList<>(entries.values());
    }
    static Long parseDate(String metadata) {
        String[][] formats = {
            {"\\b\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2})?\\b", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"},
            {"\\b\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}(?::\\d{2})?\\b", "dd-MMM-yyyy HH:mm:ss", "dd-MMM-yyyy HH:mm"},
            {"\\b\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}(?::\\d{2})?\\b", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm"}
        };
        for (String[] format : formats) {
            Matcher match = Pattern.compile(format[0]).matcher(metadata);
            if (!match.find()) continue;
            String value = match.group().replace('T', ' ');
            for (int i = 1; i < format.length; i++) {
                SimpleDateFormat parser = new SimpleDateFormat(format[i], Locale.ENGLISH); parser.setLenient(false);
                ParsePosition position = new ParsePosition(0); Date date = parser.parse(value, position);
                if (date != null && position.getIndex() == value.length()) return date.getTime();
            }
        }
        return null;
    }
    static Comparator<Entry> ordering(int choice) {
        Comparator<Entry> names = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::path);
        if (choice < 2) return choice == 0 ? names : names.reversed();
        Comparator<Long> dates = choice == 2 ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(Entry::modified, Comparator.nullsLast(dates)).thenComparing(names);
    }
    public List<Entry> gallery() throws IOException {
        List<Entry> empty = null; IOException failure = null;
        for (String root : List.of("/videos/", "/DCIM/")) {
            try {
                List<Entry> files = new ArrayList<>(); Queue<String> queue = new ArrayDeque<>(); Set<String> seen = new HashSet<>(); queue.add(root);
                while (!queue.isEmpty()) {
                    String directory = queue.remove(); if (!seen.add(directory)) continue;
                    if (seen.size() > 100) throw new IOException("Indice troppo grande.");
                    for (Entry entry : parseIndex(new String(get(media + directory), StandardCharsets.UTF_8), directory, media)) {
                        if (entry.folder()) queue.add(entry.path()); else files.add(entry);
                    }
                }
                files.sort(Comparator.comparing(Entry::name).reversed());
                if (!files.isEmpty()) return files; empty = files;
            } catch (IOException e) { failure = e; }
        }
        if (empty != null) return empty;
        throw failure == null ? new IOException("Camera non raggiungibile.") : failure;
    }
}
