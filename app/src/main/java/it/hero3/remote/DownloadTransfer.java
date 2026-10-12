package it.hero3.remote;

import java.io.*;
import java.util.function.BooleanSupplier;

final class DownloadTransfer {
    interface Progress { void update(long received); }
    static long copy(InputStream input, OutputStream output, long total, BooleanSupplier cancelled, Progress progress) throws IOException {
        byte[] buffer = new byte[65536]; long received = 0, lastUpdate = 0; int size;
        while (true) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download annullato.");
            size = input.read(buffer); if (size == -1) break;
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download annullato.");
            output.write(buffer, 0, size); received += size;
            long now = System.currentTimeMillis();
            if (now - lastUpdate >= 250) { lastUpdate = now; progress.update(received); }
        }
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Download annullato.");
        if (received == 0) throw new IOException("La camera non ha inviato dati per questo file.");
        if (total > 0 && received != total) throw new IOException("Trasferimento incompleto: " + received + " di " + total + " byte.");
        output.flush(); progress.update(received); return received;
    }
}
