package it.hero3.remote;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import static org.junit.Assert.*;

public class PreviewCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void cachePersistsAcrossInstancesAndSeparatesChangedFiles() throws Exception {
        File directory = temporary.newFolder(); PreviewCache cache = new PreviewCache(directory, 100);
        cache.store("/videos/photo.JPG|date1", out -> out.write(new byte[]{1, 2, 3}));
        PreviewCache reopened = new PreviewCache(directory, 100);
        assertNotNull(reopened.find("/videos/photo.JPG|date1"));
        assertNull(reopened.find("/videos/photo.JPG|date2"));
        reopened.remove("/videos/photo.JPG|date1"); assertNull(reopened.find("/videos/photo.JPG|date1"));
    }
    @Test public void boundedCacheEvictsOldestAndIncompleteWritesAreNotVisible() throws Exception {
        File directory = temporary.newFolder(); PreviewCache cache = new PreviewCache(directory, 10);
        cache.store("old", out -> out.write(new byte[6])); cache.find("old").setLastModified(1);
        cache.store("new", out -> out.write(new byte[6]));
        assertNull(cache.find("old")); assertNotNull(cache.find("new"));
        try { cache.store("broken", out -> { out.write(1); throw new IOException("failed"); }); fail("Expected failure"); }
        catch (IOException expected) { assertNull(cache.find("broken")); }
    }
}
