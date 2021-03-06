package net.yeputons.spbau.spring2016.torrent.client;

import net.yeputons.spbau.spring2016.torrent.FileDescription;
import net.yeputons.spbau.spring2016.torrent.FirmTorrentConnection;
import net.yeputons.spbau.spring2016.torrent.protocol.FileEntry;
import net.yeputons.spbau.spring2016.torrent.protocol.GetRequest;
import net.yeputons.spbau.spring2016.torrent.protocol.StatRequest;
import net.yeputons.spbau.spring2016.torrent.protocol.UpdateRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TorrentSeederTest {
    private static final long RANDOM_SEED = 123456789L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final FileDescription file = new FileDescription(new FileEntry(1, "file1.txt", 100), 35);

    private ClientState state;
    private byte[] fileData;

    @Before
    public void initState() throws IOException {
        state = new ClientState(folder.getRoot().toPath());
        state.getFiles().put(1, file);

        // CHECKSTYLE.OFF: MagicNumber
        RandomAccessFile file = state.getFile(1);
        file.seek(0);
        assertEquals(100, file.length());
        // CHECKSTYLE.ON: MagicNumber

        fileData = new byte[(int) file.length()];
        new Random(RANDOM_SEED).nextBytes(fileData);
        file.write(fileData);
        file.getFD().sync();

        state.getFileDescription(1).getDownloaded().set(1);
    }

    @Test
    public void testUpdateTracker() throws IOException, InterruptedException {
        FirmTorrentConnection tracker = mock(FirmTorrentConnection.class);
        // CHECKSTYLE.OFF: MagicNumber
        TorrentSeeder seeder = new TorrentSeeder(tracker, state, 100);
        // CHECKSTYLE.ON: MagicNumber

        AtomicInteger updateRequestId = new AtomicInteger();
        Semaphore waitingForRequest = new Semaphore(1);
        waitingForRequest.acquire();
        when(tracker.makeRequest(new UpdateRequest(anyInt(), Arrays.asList(1)))).thenAnswer((r) -> {
            int id = updateRequestId.incrementAndGet();
            waitingForRequest.release();
            // CHECKSTYLE.OFF: MagicNumber
            if (id == 2) {
            // CHECKSTYLE.ON: MagicNumber
                throw new IOException("Test exception");
            }
            return true;
        });

        seeder.start();
        waitingForRequest.acquire(); // 1
        waitingForRequest.acquire(); // 2
        waitingForRequest.acquire(); // 3

        // CHECKSTYLE.OFF: MagicNumber
        state.getFiles().put(2, new FileDescription(new FileEntry(2, "file2.txt", 50), 35));
        // CHECKSTYLE.ON: MagicNumber
        when(tracker.makeRequest(new UpdateRequest(anyInt(), Arrays.asList(1, 2)))).thenAnswer((r) -> {
            int id = updateRequestId.incrementAndGet();
            waitingForRequest.release();
            // CHECKSTYLE.OFF: MagicNumber
            if (id == 5) {
            // CHECKSTYLE.ON: MagicNumber
                throw new IOException("Test exception");
            }
            return true;
        });
        waitingForRequest.acquire(); // 4
        waitingForRequest.acquire(); // 5
        waitingForRequest.acquire(); // 6

        seeder.shutdown();
        seeder.join();
    }

    @Test
    public void testStat() throws IOException, InterruptedException {
        TorrentSeeder seeder = createSeeder();

        try (FirmTorrentConnection connection = new FirmTorrentConnection(seeder.getAddress())) {
            assertEquals(Arrays.asList(1), connection.makeRequest(new StatRequest(1)));
            state.getFileDescription(1).getDownloaded().set(2);
            assertEquals(Arrays.asList(1, 2), connection.makeRequest(new StatRequest(1)));
        }

        seeder.shutdown();
        seeder.join();
    }

    @Test
    public void testGet() throws IOException, InterruptedException {
        TorrentSeeder seeder = createSeeder();

        try (FirmTorrentConnection connection = new FirmTorrentConnection(seeder.getAddress())) {
            // CHECKSTYLE.OFF: MagicNumber
            ByteBuffer result1 = connection.makeRequest(new GetRequest(1, 1, file.getPartSize(1)));
            byte[] expected1 = new byte[35];
            System.arraycopy(fileData, 35, expected1, 0, 35);
            assertArrayEquals(expected1, result1.array());

            state.getFileDescription(1).getDownloaded().set(2);

            ByteBuffer result2 = connection.makeRequest(new GetRequest(1, 2, file.getPartSize(2)));
            byte[] expected2 = new byte[30];
            System.arraycopy(fileData, 70, expected2, 0, 30);
            assertArrayEquals(expected2, result2.array());
            // CHECKSTYLE.ON: MagicNumber
        }

        seeder.shutdown();
        seeder.join();
    }

    private TorrentSeeder createSeeder() throws IOException {
        FirmTorrentConnection tracker = mock(FirmTorrentConnection.class);
        when(tracker.makeRequest(any(UpdateRequest.class))).thenReturn(true);
        TorrentSeeder seeder = new TorrentSeeder(tracker, state);
        seeder.start();
        return seeder;
    }
}
