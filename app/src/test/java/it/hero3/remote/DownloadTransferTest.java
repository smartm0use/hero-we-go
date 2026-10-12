package it.hero3.remote;

import org.junit.Test;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class DownloadTransferTest {
    @Test public void completeTransferReportsBytesAndPreservesContent() throws Exception {
        byte[] source = new byte[150000]; for (int i = 0; i < source.length; i++) source[i] = (byte) i;
        ByteArrayOutputStream target = new ByteArrayOutputStream(); List<Long> updates = new ArrayList<>();
        assertEquals(source.length, DownloadTransfer.copy(new ByteArrayInputStream(source), target, source.length, () -> false, updates::add));
        assertArrayEquals(source, target.toByteArray()); assertEquals(Long.valueOf(source.length), updates.get(updates.size() - 1));
    }
    @Test public void truncatedAndEmptyTransfersDoNotReportSuccess() throws Exception {
        for (byte[] source : new byte[][]{new byte[0], new byte[3]}) {
            try { DownloadTransfer.copy(new ByteArrayInputStream(source), new ByteArrayOutputStream(), 10, () -> false, count -> {}); fail("Expected incomplete download"); }
            catch (IOException expected) {}
        }
    }
    @Test public void cancellationStopsCopyingAndUnknownLengthIsSupported() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try { DownloadTransfer.copy(new ByteArrayInputStream(new byte[3]), target, -1, () -> true, count -> {}); fail("Expected cancellation"); }
        catch (InterruptedIOException expected) { assertEquals(0, target.size()); }
        assertEquals(3, DownloadTransfer.copy(new ByteArrayInputStream(new byte[3]), target, -1, () -> false, count -> {}));
    }
}
