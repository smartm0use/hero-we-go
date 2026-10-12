package it.hero3.remote;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackStatusTest {
    @Test public void fullBufferHidesMessageAndStallsCanReturnToBuffering() {
        PlaybackStatus state = new PlaybackStatus(); state.start(0); state.buffering(42);
        state.sample(500, 0, 500); assertEquals("Buffering… 42%", state.text());
        state.buffering(100); state.sample(1000, 0, 1000);
        assertEquals("", state.text());
        state.sample(1500, 1, 1500); state.sample(2000, 2, 2000);
        assertEquals("", state.text());
        state.sample(2500, 3, 2500); assertEquals("", state.text());
        state.sample(5000, 3, 5000); assertEquals("Buffering…", state.text());
    }
    @Test public void frozenVideoIsDetectedEvenWhenAudioClockMoves() {
        PlaybackStatus state = new PlaybackStatus(); state.start(0);
        state.sample(0, 0, 0); state.sample(500, 1, 500); state.sample(1000, 2, 1000); state.sample(1500, 3, 1500);
        assertEquals("", state.text());
        state.sample(4000, 3, 4000); assertEquals("Buffering…", state.text());
        state.buffering(63); state.sample(4500, 3, 4500); assertEquals("Buffering… 63%", state.text());
        state.stop(); assertEquals("", state.text());
    }
    @Test public void isolatedProgressDoesNotFlashTheMessageAndClockFallbackWorks() {
        PlaybackStatus state = new PlaybackStatus(); state.start(0); state.buffering(25);
        state.sample(0, -1, 0); state.sample(500, -1, 500); state.sample(1000, -1, 500);
        assertEquals("Buffering… 25%", state.text());
        state.sample(1500, -1, 1000); state.sample(2000, -1, 1500); state.sample(2500, -1, 2000);
        assertEquals("", state.text());
    }
}
