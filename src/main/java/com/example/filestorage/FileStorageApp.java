package main.java.com.example.filestorage;

import java.time.Instant;
import java.util.*;

/**
 * CloudStorageLevel2
 * Builds on Level 1 (add/get/move) and adds Level 2: topKByPrefix(prefix, k).
 * <p>
 * - Uses TreeMap for lexicographic ordering and efficient prefix submap.
 * - topKByPrefix: O(log n + m log k) using a bounded min-heap; deterministic tie-breaker (size desc, name asc).
 */
public class FileStorageApp {

    // Lightweight metadata for a stored object (same shape as Level 1)
    public static class FileMetaData {
        private String name;
        private long sizeBytes;
        private final Instant createdAt;
        private Instant modifiedAt;
        private final String metadata;

        public FileMetaData(String name, long sizeBytes, String metadata) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.metadata = metadata;
            Instant now = Instant.now();
            this.modifiedAt = now;
            this.createdAt = now;
        }

        public String getName() {
            return name;
        }

        public String getMetadata() {
            return metadata;
        }

        public Instant getModifiedAt() {
            return modifiedAt;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setName(String name) {
            this.name = name;
            this.modifiedAt = Instant.now();
        }

        public void setSize(long size) {
            this.sizeBytes = size;
            this.modifiedAt = Instant.now();
        }

        @Override
        public String toString() {
            return String.format("FileMeta{name='%s', size=%d, created=%s, modified=%s, meta=%s}",
                    name, sizeBytes, createdAt, modifiedAt, metadata);
        }
    }

    /**
     * Storage core for Level 1 + Level 2.
     * Uses a TreeMap keyed by full object name (path-like string) enabling prefix subMap queries.
     */
    public final static class Storage {
        // TreeMap for lexicographic ordering -> efficient prefix subMap
        private final TreeMap<String, FileMetaData> files = new TreeMap<>();

        public void addFile(String name, long size, String metadata, boolean overWrite) throws IllegalArgumentException {
            Objects.requireNonNull(name, "name must not be null");
            if (size < 0) {
                throw new IllegalArgumentException("Size must be >= =");
            }
            FileMetaData existing = files.get(name);
            if (existing != null && !overWrite) {
                throw new IllegalArgumentException("File already exists: " + name);
            }
            FileMetaData meta = new FileMetaData(name, size, metadata);
            files.put(name, meta);
        }

        /**
         * Retrieve FileMeta for a name. Returns null if not found.
         */

        public FileMetaData getFile(String name) {
            return files.get(name);
        }

        /**
         * Move/rename file from srcName to destName.
         * If dest exists and overwrite==false -> IllegalArgumentException.
         * If src doesn't exist -> IllegalArgumentException.
         */
        public void moveFile(String srcName, String destinationName, boolean overWrite) {
            Objects.requireNonNull(srcName);
            Objects.requireNonNull(destinationName);
            if (srcName.equals(destinationName)) {
                return;
            }
            FileMetaData src = files.get(srcName);
            if (src == null) {
                throw new IllegalArgumentException("Source file not found: " + srcName);
            }
            FileMetaData dest = files.get(destinationName);
            if (dest != null) {
                throw new IllegalArgumentException("Destination exists");
            }
            files.remove(srcName);
            src.setName(destinationName);
            files.put(destinationName, src);
        }

        private static String prefixEndExclusive(String prefix) {
            return prefix + '\uffff';
        }


        /**
         * Return top k FileMeta objects whose names start with prefix.
         * Deterministic ordering: size desc, name asc (tie-breaker).
         * <p>
         * If prefix is null or empty -> matches all files.
         * If k <= 0 -> returns empty list.
         */
        public List<FileMetaData> topKByPrefix(String prefix, int k) {
            if (k <= 0) {
                return Collections.emptyList();
            }
            String start = (prefix == null || prefix.isEmpty()) ? "" : null;
            String end = prefixEndExclusive(start);

            // Get candidate view; subMap is O(log n) to locate range.
            Collection<FileMetaData> candidates = files.subMap(start, true, end, true).values();

            // Min-heap comparator: smallest size at top; for equal sizes, larger name lexicographically later
            // We want the heap to evict the smallest size, and where sizes tie, evict the lexicographically largest name
            PriorityQueue<FileMetaData> pq = new PriorityQueue<>(k, (a, b) -> {
                int cmp = Long.compare(a.getSizeBytes(), b.getSizeBytes());
                if (cmp != 0) {
                    return cmp;
                }
                return b.getName().compareTo(a.getName());
            });

            for (FileMetaData f : candidates) {
                if (pq.size() < k) {
                    pq.offer(f);
                } else {
                    // if f is better than worstInTop per final ordering, replace
                    FileMetaData worstInTop = pq.peek();
                    if (compareForFinalOrder(f, worstInTop) < 0) {
                        pq.poll();
                        pq.offer(f);
                    }
                }
            }
            // Extract and sort final results by size desc, name asc
            List<FileMetaData> result = new ArrayList<>(pq);
            result.sort((a, b) -> {
                int c = Long.compare(b.getSizeBytes(), a.getSizeBytes());
                if (c != 0) {
                    return c;
                }
                return a.getName().compareTo(b.getName());
            });
            return result;
        }

        // Helper: compare for final desired order (negative if a should come before b)
        private static int compareForFinalOrder(FileMetaData a, FileMetaData b) {
            int bySizeDesc = Long.compare(b.getSizeBytes(), a.getSizeBytes());
            if (bySizeDesc != 0) return bySizeDesc;
            return a.getName().compareTo(b.getName());
        }

        /* ---------- Helpers for tests/debug ---------- */

        public List<String> listNamesByPrefix(String prefix) {
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);
            return new ArrayList<>(files.subMap(start, true, end, true).keySet());
        }

        public List<String> listAllNames() {
            return new ArrayList<>(files.keySet());
        }


    }


    public static void main(String[] args) {
        Storage s = new Storage();

        // Add new files
        s.addFile("photos/vacation.jpg", 5_000_000L, "sha1:aaaa", false);
        s.addFile("photos/party.mov", 50_000_000L, "sha1:big", false);
        s.addFile("photos/a_tie.jpg", 50_000_000L, "sha1:tie", false);
        s.addFile("docs/resume.pdf", 200_000L, "sha1:bbbb", false);

        // Retrieve
        FileMetaData p = s.getFile("photos/vacation.jpg");
        System.out.println("Got: " + p);
        assert p != null && p.getSizeBytes() == 5_000_000L;

        // Try duplicate add without overwrite -> should throw
        boolean threw = false;
        try {
            s.addFile("docs/resume.pdf", 210_000L, null, false);
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        assert threw;

        // Overwrite allowed
        s.addFile("docs/resume.pdf", 210_000L, "updated", true);
        FileMetaData updated = s.getFile("docs/resume.pdf");
        System.out.println("Updated: " + updated);
        assert updated != null && updated.getSizeBytes() == 210_000L;

        // Move file (overwrite=false) -> should fail if dest exists
        s.addFile("docs/2025/resume.pdf", 10L, null, false);
        boolean moveThrew = false;
        try {
            s.moveFile("docs/resume.pdf", "docs/2025/resume.pdf", false);
        } catch (IllegalArgumentException ex) {
            moveThrew = true;
        }
        assert moveThrew;

        // Move with overwrite=true
        s.moveFile("docs/resume.pdf", "docs/2025/resume.pdf", true);
        assert s.getFile("docs/resume.pdf") == null;
        assert s.getFile("docs/2025/resume.pdf") != null;

        // List names
        System.out.println("All files: " + s.listAllNames());

        // Level 2: top-K by prefix
        List<FileMetaData> top2 = s.topKByPrefix("photos", 2); // note: prefix "photos" matches "photos/..."
        System.out.println("Top2 for 'photos':");
        top2.forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        // Tie-breaker deterministic test: a_tie.jpg (name asc gets priority)
        List<FileMetaData> top3 = s.topKByPrefix("photos", 3);
        System.out.println("Top3 for 'photos':");
        top3.forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        System.out.println("Level 2 demo finished.");
    }


}
