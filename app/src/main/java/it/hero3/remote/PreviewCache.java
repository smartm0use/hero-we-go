package it.hero3.remote;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded local preview cache; originals are never treated as downloaded files. */
final class PreviewCache {
    private final File directory;
    private final long maxBytes;
    PreviewCache(File directory, long maxBytes) { this.directory = directory; this.maxBytes = maxBytes; }
    private File file(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(); for (byte value : hash) name.append(String.format(Locale.ROOT, "%02x", value & 255));
            return new File(directory, name + ".jpg");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    synchronized File find(String key) {
        File file = file(key);
        if (!file.isFile()) return null;
        file.setLastModified(System.currentTimeMillis()); return file;
    }
    synchronized void remove(String key) { file(key).delete(); }
    interface Writer { void write(OutputStream out) throws IOException; }
    synchronized void store(String key, Writer writer) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache non disponibile.");
        File target = file(key), temporary = new File(directory, target.getName() + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(temporary)) { writer.write(out); }
            if (!temporary.renameTo(target)) throw new IOException("Salvataggio cache fallito.");
        } finally { temporary.delete(); }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        long total = 0; for (File cached : files) total += cached.length();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File cached : files) { if (total <= maxBytes) break; long size = cached.length(); if (cached.delete()) total -= size; }
    }
}
