package it.hero3.remote;

import org.junit.Test;
import java.util.List;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.Dispatcher;
import okio.Buffer;
import static org.junit.Assert.*;

public class CameraApiTest {
    @Test public void legacyParametersAreBinaryAndPasswordIsEncoded() {
        assertEquals("/bacpac/SH?t=a%26b%20%2B&p=%01", CameraApi.commandPath("start", "a&b +"));
        assertEquals("/camera/PV?t=secret&p=%02", CameraApi.commandPath("preview", "secret"));
    }
    @Test public void indexFiltersSidecarsParentsAndExternalLinks() {
        List<CameraApi.Entry> entries = CameraApi.parseIndex("<a href='../'>Parent</a><a href='100GOPRO/'>Folder</a><a href='GOPR0001.MP4'>video</a><a href='photo%20one.JPG'>photo</a><a href='GOPR0001.LRV'>low</a><a href='GOPR0001.THM'>thumb</a><a href='http://evil.test/videos/bad.JPG'>external</a><a href='%2e%2e/secret.JPG'>encoded traversal</a>", "/videos/", "http://10.5.5.9:8080");
        assertEquals(3, entries.size()); assertTrue(entries.get(0).folder()); assertTrue(entries.get(1).video()); assertEquals("photo one.JPG", entries.get(2).name());
    }
    @Test public void datesAreReadFromCherokeeRowsAndUnknownDatesSortLast() {
        List<CameraApi.Entry> entries = CameraApi.parseIndex("<table><tr><td><a href='B.JPG'>B</a></td><td>2026-10-09 18:30:10</td></tr><tr><td><a href='A.MP4'>A</a></td><td>08-Oct-2026 12:15</td></tr><tr><td><a href='C.JPG'>C</a></td><td>-</td></tr></table>", "/videos/", "http://10.5.5.9:8080");
        assertNotNull(entries.get(0).modified()); assertNotNull(entries.get(1).modified()); assertNull(entries.get(2).modified());
        entries.sort(CameraApi.ordering(2)); assertEquals("B.JPG", entries.get(0).name()); assertEquals("C.JPG", entries.get(2).name());
        entries.sort(CameraApi.ordering(3)); assertEquals("A.MP4", entries.get(0).name()); assertEquals("C.JPG", entries.get(2).name());
        entries.sort(CameraApi.ordering(0)); assertEquals("A.MP4", entries.get(0).name());
        assertNull(CameraApi.parseDate("2026-02-31 12:00"));
    }
    @Test public void simulatedCameraHandlesGalleryStatusAndPreviewRefusal() throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
          @Override public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath().split("\\?")[0]; byte[] data; int code = 200;
            switch (path) {
                case "/videos/": data = "<a href='100GOPRO/'>folder</a>".getBytes(); break;
                case "/videos/100GOPRO/": data = "<a href='GOPR001.JPG'>photo</a><a href='GOPR002.MP4'>video</a><a href='GOPR002.LRV'>low</a>".getBytes(); break;
                case "/camera/sx": data = new byte[30]; data[29] = 1; break;
                case "/camera/se": data = new byte[30]; data[19] = 75; break;
                case "/camera/PV": data = "refused".getBytes(); code = 410; break;
                case "/live/amba.m3u8": data = "#EXTM3U\n#EXTINF:1,\nsegment.ts\n".getBytes(); break;
                case "/bacpac/SH": data = "ok".getBytes(); break;
                default: data = "missing".getBytes(); code = 404;
            }
            return new MockResponse().setResponseCode(code).setBody(new Buffer().write(data));
          }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getPort();
            CameraApi api = new CameraApi(origin, origin);
            assertEquals(2, api.gallery().size());
            CameraApi.Status status = api.status("secret");
            assertTrue(status.recording()); assertEquals(Integer.valueOf(75), status.battery()); assertTrue(status.percent());
            api.preview("secret"); api.command("start", "secret");
        } finally { server.shutdown(); }
    }
    @Test public void recordingConfirmationWaitsForCameraAndRetriesTransientPhotoErrors() throws Exception {
        MockWebServer server = new MockWebServer(); server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getPort(); CameraApi api = new CameraApi(origin, origin);
            byte[] idle = new byte[60], recording = new byte[60]; recording[29] = 1;
            server.enqueue(new MockResponse().setBody(new Buffer().write(idle)));
            server.enqueue(new MockResponse().setBody(new Buffer().write(recording)));
            assertTrue(api.confirmCommand("start", 0, "secret").recording());
            server.enqueue(new MockResponse().setResponseCode(410));
            byte[] photo = new byte[60]; photo[1] = 1;
            server.enqueue(new MockResponse().setBody(new Buffer().write(photo)));
            assertFalse(api.confirmCommand("start", 1, "secret").recording());
            server.enqueue(new MockResponse().setResponseCode(403));
            try { api.confirmCommand("stop", 0, "wrong"); fail("Expected password error"); }
            catch (CameraApi.CameraException error) { assertEquals(403, error.code); }
        } finally { server.shutdown(); }
    }
}
