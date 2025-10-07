package com.example.filestorage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileAppTest {
    @Test
    void registerAndRetrieveMetadata() {
        FileApp app = new FileApp();
        app.register("foo.txt", 123L);
        var meta = app.getMeta("foo.txt");
        assertNotNull(meta, "Metadata should be stored");
        assertEquals("foo.txt", meta.name());
        assertEquals(123L, meta.sizeBytes());
        assertNotNull(meta.createdAt());
    }
}

