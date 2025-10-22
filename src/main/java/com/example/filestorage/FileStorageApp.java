package main.java.com.example.filestorage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * FileStorageApp - Level 4 (Delete & Restore by prefix)
 *
 * Builds on Level 1..3:
 * - Level 1: add/get/move
 * - Level 2: topKByPrefix (prefix + largest)
 * - Level 3: versioning
 * - Level 4: soft-delete & restore by prefix
 */
public class FileStorageApp {

    /* --- Public snapshot returned by getFile(...) --- */
    public static class FileMetaData {
        private final String name;
        private final long sizeBytes;
        private final Instant createdAt;
        private final Instant modifiedAt;
        private final String metadata;

        public FileMetaData(String name, long sizeBytes, Instant createdAt, Instant modifiedAt, String metadata) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.createdAt = createdAt;
            this.modifiedAt = modifiedAt;
            this.metadata = metadata;
        }

        public String getName() { return name; }
        public long getSizeBytes() { return sizeBytes; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getModifiedAt() { return modifiedAt; }
        public String getMetadata() { return metadata; }

        @Override
        public String toString() {
            return String.format("FileMeta{name='%s', size=%d, created=%s, modified=%s, meta=%s}",
                    name, sizeBytes, createdAt, modifiedAt, metadata);
        }
    }

    /* --- Internal version & record --- */
    public static final class FileVersion {
        public final int versionNumber;
        public final long sizeBytes;
        public final String content;
        public final Instant timestamp;
        public final String metadata;

        public FileVersion(int versionNumber, long sizeBytes, String content, Instant timestamp, String metadata) {
            this.versionNumber = versionNumber;
            this.sizeBytes = sizeBytes;
            this.content = content;
            this.timestamp = timestamp;
            this.metadata = metadata;
        }

        @Override
        public String toString() {
            return "v" + versionNumber + " size=" + sizeBytes + " @ " + timestamp + (metadata == null ? "" : " (" + metadata + ")");
        }
    }

    private static final class FileRecord {
        private String name;
        private final List<FileVersion> versions = new ArrayList<>();
        private final Instant createdAt;
        private Instant modifiedAt;
        private boolean deleted = false; // Level 4: soft-delete flag

        public FileRecord(String name, String content, String metadata) {
            this.name = Objects.requireNonNull(name);
            Instant now = Instant.now();
            this.createdAt = now;
            this.modifiedAt = now;
            addVersionInternal(content, metadata, now);
        }

        private long computeSize(String content) {
            if (content == null) return 0L;
            return content.getBytes(StandardCharsets.UTF_8).length;
        }

        private FileVersion addVersionInternal(String content, String metadata, Instant ts) {
            int newVer = versions.size() + 1;
            long size = computeSize(content);
            FileVersion fv = new FileVersion(newVer, size, content == null ? "" : content, ts, metadata);
            versions.add(fv);
            this.modifiedAt = ts;
            return fv;
        }

        public FileVersion addVersion(String content, String metadata) {
            return addVersionInternal(content, metadata, Instant.now());
        }

        public FileVersion latestVersion() {
            if (versions.isEmpty()) return null;
            return versions.getLast();
        }

        public long latestSize() {
            FileVersion lv = latestVersion();
            return (lv == null) ? 0L : lv.sizeBytes;
        }

        public List<FileVersion> getVersions() {
            return Collections.unmodifiableList(versions);
        }

        public void setName(String newName) {
            this.name = Objects.requireNonNull(newName);
            this.modifiedAt = Instant.now();
        }

        public String getName() { return name; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getModifiedAt() { return modifiedAt; }
        public boolean isDeleted() { return deleted; }
        public void setDeleted(boolean d) { this.deleted = d; this.modifiedAt = Instant.now(); }
    }

    /* --- Storage --- */
    public static final class Storage {
        private final TreeMap<String, FileRecord> files = new TreeMap<>();

        /* ---------- Level 1 & 3: add/get/move/versioning ---------- */

        public void addFile(String name, String content, String metadata, boolean overwrite) {
            Objects.requireNonNull(name);
            FileRecord existing = files.get(name);
            if (existing != null) {
                if (!overwrite) throw new IllegalArgumentException("File already exists: " + name);
                // Overwrite semantics for versioned files: append version and (re)activate if deleted
                existing.setDeleted(false);
                existing.addVersion(content, metadata);
                files.put(name, existing);
                return;
            }
            FileRecord rec = new FileRecord(name, content, metadata);
            files.put(name, rec);
        }

        public FileMetaData getFile(String name) {
            FileRecord rec = files.get(name);
            if (rec == null) return null;
            FileVersion lv = rec.latestVersion();
            if (lv == null) return null;
            return new FileMetaData(rec.getName(), lv.sizeBytes, rec.getCreatedAt(), rec.getModifiedAt(), lv.metadata);
        }

        public String getContent(String name) {
            FileRecord rec = files.get(name);
            if (rec == null) return null;
            FileVersion lv = rec.latestVersion();
            return (lv == null) ? null : lv.content;
        }

        /**
         * Move file. Returns true on success, false on expected failure (src missing or dest exists and overwrite==false).
         */
        public boolean moveFile(String srcName, String destinationName, boolean overwrite) {
            Objects.requireNonNull(srcName);
            Objects.requireNonNull(destinationName);
            if (srcName.equals(destinationName)) return true;

            FileRecord src = files.get(srcName);
            if (src == null) return false;

            FileRecord dest = files.get(destinationName);
            if (dest != null && !overwrite) return false;
            if (dest != null && overwrite) files.remove(destinationName);

            files.remove(srcName);
            src.setName(destinationName);
            files.put(destinationName, src);
            return true;
        }

        public FileVersion addVersion(String name, String content, String metadata) {
            Objects.requireNonNull(name);
            FileRecord rec = files.get(name);
            if (rec == null) {
                FileRecord r = new FileRecord(name, content, metadata);
                files.put(name, r);
                return r.latestVersion();
            } else {
                rec.setDeleted(false);
                return rec.addVersion(content, metadata);
            }
        }

        public List<FileVersion> listVersions(String name) {
            FileRecord rec = files.get(name);
            if (rec == null) return Collections.emptyList();
            return rec.getVersions();
        }

        /* ---------- Level 2 (updated): top-K excludes deleted ---------- */

        private static String prefixEndExclusive(String prefix) {
            return prefix + '\uffff';
        }

        public List<FileMetaData> topKByPrefix(String prefix, int k) {
            if (k <= 0) return Collections.emptyList();
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);

            NavigableMap<String, FileRecord> sub = files.subMap(start, true, end, true);
            Collection<FileRecord> candidates = sub.values();

            PriorityQueue<FileRecord> pq = new PriorityQueue<>(Math.max(1, k), (a, b) -> {
                int sizeCmp = Long.compare(a.latestSize(), b.latestSize()); // ascending -> worst at top
                if (sizeCmp != 0) return sizeCmp;
                return b.getName().compareTo(a.getName()); // tie: larger name considered worse in heap
            });

            for (FileRecord rec : candidates) {
                if (rec.isDeleted()) continue; // skip soft-deleted files
                if (pq.size() < k) {
                    pq.offer(rec);
                } else {
                    FileRecord worst = pq.peek();
                    if (compareFinalOrder(rec, worst) < 0) {
                        pq.poll();
                        pq.offer(rec);
                    }
                }
            }

            List<FileRecord> chosen = new ArrayList<>(pq);
            chosen.sort((a, b) -> {
                int c = Long.compare(b.latestSize(), a.latestSize()); // desc
                if (c != 0) return c;
                return a.getName().compareTo(b.getName()); // asc
            });

            List<FileMetaData> res = new ArrayList<>(chosen.size());
            for (FileRecord r : chosen) {
                FileVersion lv = r.latestVersion();
                res.add(new FileMetaData(r.getName(), lv.sizeBytes, r.getCreatedAt(), r.getModifiedAt(), lv.metadata));
            }
            return res;
        }

        private static int compareFinalOrder(FileRecord a, FileRecord b) {
            int bySizeDesc = Long.compare(b.latestSize(), a.latestSize());
            if (bySizeDesc != 0) return bySizeDesc;
            return a.getName().compareTo(b.getName());
        }

        /* ---------- Level 4: delete & restore by prefix ---------- */

        /**
         * Soft-delete all files whose names start with prefix. Returns list of names that were marked deleted.
         * Deleted records remain in the map so they can be restored and keep version history.
         */
        public List<String> deleteByPrefix(String prefix) {
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);
            List<String> deletedNames = new ArrayList<>();
            // iterate over a snapshot of entries to avoid concurrent modification via view
            for (Map.Entry<String, FileRecord> e : new ArrayList<>(files.subMap(start, true, end, true).entrySet())) {
                FileRecord rec = e.getValue();
                if (!rec.isDeleted()) {
                    rec.setDeleted(true);
                    deletedNames.add(e.getKey());
                }
            }
            return deletedNames;
        }

        /**
         * Restore soft-deleted files whose names start with prefix.
         * If overwrite==false and a non-deleted file exists under same name (rare, see notes), skip restore.
         * If overwrite==true, restore regardless (in practice this simply unmarks the deleted flag).
         * Returns list of names restored.
         */
        public List<String> restoreByPrefix(String prefix, boolean overwrite) {
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);
            List<String> restored = new ArrayList<>();
            for (Map.Entry<String, FileRecord> e : new ArrayList<>(files.subMap(start, true, end, true).entrySet())) {
                FileRecord rec = e.getValue();
                if (rec.isDeleted()) {
                    // In our design deleted records remain in the map keyed by their name.
                    // If an external new active record were created with the same name, the record under this key would typically have been reactivated.
                    // So here, simply unmark deleted (or skip if not allowed).
                    if (!overwrite) {
                        // if the record is still marked deleted we can restore it; overwrite flag is mostly for API symmetry
                        rec.setDeleted(false);
                        restored.add(e.getKey());
                    } else {
                        rec.setDeleted(false);
                        restored.add(e.getKey());
                    }
                }
            }
            return restored;
        }

        /* ---------- Helpers ---------- */

        /**
         * List file names by prefix. If includeDeleted==false, deleted files are excluded.
         */
        public List<String> listNamesByPrefix(String prefix, boolean includeDeleted) {
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, FileRecord> e : files.subMap(start, true, end, true).entrySet()) {
                if (includeDeleted || !e.getValue().isDeleted()) out.add(e.getKey());
            }
            return out;
        }

        public List<String> listAllNames() {
            return new ArrayList<>(files.keySet());
        }
    }

    /* ---------- Demo / assertions for Levels 1..4 ---------- */
    public static void main(String[] args) {
        Storage s = new Storage();

        System.out.println("=== LEVEL 1: add/get/move (sanity) ===");
        s.addFile("photos/2025/vacation.jpg", "JPEGDATA_SMALL", "sha1:aaa", false);
        s.addFile("photos/2025/party.mov", "MOVDATA_LARGE".repeat(100), "sha1:bbb", false);
        s.addFile("photos/2025/a_tie.jpg", "MOVDATA_LARGE".repeat(100), "sha1:ccc", false);
        s.addFile("docs/resume.pdf", "PDFDATA_V1", "sha1:r1", false);
        System.out.println("Initial names: " + s.listAllNames());

        System.out.println("\n=== LEVEL 3: versioning demo (quick check) ===");
        s.addVersion("docs/resume.pdf", "PDFDATA_V2_WITH_MORE_CONTENT", "sha1:r2");
        List<FileVersion> versions = s.listVersions("docs/resume.pdf");
        System.out.println("Versions for resume: " + versions);

        System.out.println("\n=== LEVEL 2: top-K (pre-delete) ===");
        List<FileMetaData> topPre = s.topKByPrefix("photos/2025/", 2);
        topPre.forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        System.out.println("\n=== LEVEL 4: delete by prefix (soft) ===");
        List<String> deleted = s.deleteByPrefix("photos/2025/");
        System.out.println("Deleted names: " + deleted);
        System.out.println("Names by prefix (exclude deleted): " + s.listNamesByPrefix("photos/2025/", false));
        System.out.println("Top-K after delete (should be empty): " + s.topKByPrefix("photos/2025/", 2));

        System.out.println("\n=== LEVEL 4: restore by prefix ===");
        List<String> restored = s.restoreByPrefix("photos/2025/", false);
        System.out.println("Restored names: " + restored);
        System.out.println("Names by prefix (exclude deleted): " + s.listNamesByPrefix("photos/2025/", false));
        System.out.println("Top-K after restore:");
        s.topKByPrefix("photos/2025/", 2).forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        System.out.println("\nLevel 4 demo finished.");
    }
}