package main.java.com.example.filestorage;

import java.nio.charset.StandardCharsets;
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

        public FileMetaData(String name, long sizeBytes, Instant createdAt, Instant modifiedAt, String metadata) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.createdAt = createdAt;
            this.modifiedAt = modifiedAt;
            this.metadata = metadata;
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


    /** Internal representation of a single file version */
    public static final class FileVersion {
        public final int versionNumber;
        public final long sizeBytes;
        public final String content; // simulated content (string)
        public final Instant timestamp;
        public final String metadata; // version-level metadata (e.g., hash)

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

    /** Internal file record that holds version history */
    private static final class FileRecord {
        private String name;
        private final List<FileVersion> versions = new ArrayList<>();
        Instant createdAt;
        Instant modifiedAt;
        private boolean deleted = false;

        public FileRecord(String name, String content, String metadata){
            this.name = Objects.requireNonNull(name);
            Instant now = Instant.now();
            this.createdAt = now;
            this.modifiedAt = now;
            addVersionInternal(content, metadata, now);
        }

        private long computeSize(String content){
            if (content == null) return 0L;
            return content.getBytes(StandardCharsets.UTF_8).length;
        }
        public FileVersion addVersionInternal(String content, String metadata, Instant ts){
            int newVersion = versions.size() + 1;
            long size = computeSize(content);
            FileVersion fv = new FileVersion(newVersion, size, content == null? "": null,ts,metadata );
            versions.add(fv);
            this.modifiedAt = ts;
            return fv;
        }

        public FileVersion addVersion(String content, String metadata){
            return addVersionInternal(content, metadata, Instant.now());
        }

        public FileVersion latestVersion(){
            if(versions.isEmpty()){
                return null;
            }
            return versions.getLast();
        }

        public long latestSuze(){
            FileVersion fv = latestVersion();
            return fv == null ? 0L : fv.sizeBytes;
        }

        List<FileVersion> getVersions(){
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

    /**
     * Storage core for Level 1 + Level 2.
     * Uses a TreeMap keyed by full object name (path-like string) enabling prefix subMap queries.
     */
    public final static class Storage {
        // TreeMap for lexicographic ordering -> efficient prefix subMap
        private final TreeMap<String, FileRecord> files = new TreeMap<>();


        /* ---------- Level 1: add/get/move (adapted to versioned records) ---------- */

        /**
         * Add a file (initial version). If overwrite==true and file exists, append a new version.
         * If overwrite==false and file exists -> IllegalArgumentException.
         *
         * @param name file name (non-null)
         * @param content simulated content (string). Size computed via UTF-8 bytes.
         * @param metadata optional metadata for the version (e.g., hash)
         * @param overwrite if true and file exists, create a new version; else error
         */
        public void addFile(String name, String content, String metadata, boolean overWrite) {
            Objects.requireNonNull(name, "name must not be null");

            FileRecord existing = files.get(name);
            if (existing != null) {
                if(!overWrite )throw new IllegalArgumentException("File already exists: " + name);
                // Overwrite semantics for Level 3: append new version instead of destroying history
                existing.setDeleted(false);// resurrect if needed
                existing.addVersion(content, metadata);
                files.put(name, existing);// keep mapping (name same)
            }
            FileRecord rec = new FileRecord(name, content, metadata);
            files.put(name, rec);
        }

        /** Get snapshot metadata for latest version, or null if missing */
        public FileMetaData getFile(String name) {
            FileRecord record = files.get(name);
            if(record == null) return null;
            FileVersion fv = record.latestVersion();
            if (fv == null) return null;
            return new FileMetaData(record.getName(), fv.sizeBytes, record.getCreatedAt(), record.getModifiedAt(), fv.metadata);
        }

        /** Get latest content string for a file (or null) */
        public String getContent(String name) {
            FileRecord rec = files.get(name);
            if (rec == null) return null;
            FileVersion lv = rec.latestVersion();
            return (lv == null) ? null : lv.content;
        }


        /**
         * Move/rename a file record. If destination exists and overWrite==false -> false.
         * If overWrite==true and destination exists -> destination is removed and source moved.
         *
         * Returns true on success, false on expected failure (src missing or dest exists without overwrite).
         */
        public boolean moveFile(String srcName, String destinationName, boolean overWrite) {
            Objects.requireNonNull(srcName);
            Objects.requireNonNull(destinationName);
            if (srcName.equals(destinationName)) return true;

            FileRecord src = files.get(srcName);
            if (src == null) {
                // source missing
                return false;
            }
            FileRecord dest = files.get(destinationName);
            if (dest != null && !overWrite) {
                // destination exists and caller doesn't want overwrite
                return false;
            }

            // If overwriting, remove dest first.
            if (dest != null && overWrite) {
                files.remove(destinationName);
            }

            files.remove(srcName);
            src.setName(destinationName);
            files.put(destinationName, src);
            return true;
        }

        /* ---------- Level 3: versioning APIs ---------- */

        /**
         * Append a new version for a file. If file does not exist, create it (v1).
         * Returns the new FileVersion.
         */
        public FileVersion addVersion(String name, String content, String metadata) {
            Objects.requireNonNull(name);
            FileRecord rec = files.get(name);
            if (rec == null) {
                FileRecord newRec = new FileRecord(name, content, metadata);
                files.put(name, newRec);
                return newRec.latestVersion();
            } else {
                rec.setDeleted(false);
                return rec.addVersion(content, metadata);
            }
        }

        /** Return unmodifiable list of versions for a file, or empty list if missing */
        public List<FileVersion> listVersions(String name) {
            FileRecord rec = files.get(name);
            if (rec == null) return Collections.emptyList();
            return rec.getVersions();
        }

        private static String prefixEndExclusive(String prefix) {
            return prefix + '\uffff';
        }


        /**
         * Return top k FileMetaData objects whose names start with prefix.
         * Deterministic ordering: size desc, name asc (tie-breaker).
         * <p>
         * If prefix is null or empty -> matches all files.
         * If k <= 0 -> returns empty list.
         */
        public List<FileMetaData> topKByPrefix(String prefix, int k) {
            if (k <= 0) return Collections.emptyList();

            // IMPORTANT: ensure start is never null (TreeMap.subMap does NOT accept null keys)
            String start = (prefix == null || prefix.isEmpty()) ? "" : prefix;
            String end = prefixEndExclusive(start);

            // Optional debug (remove or comment out if noisy)
            // System.out.printf("topKByPrefix: start='%s' end='%s' k=%d%n", start, end, k);

            // Get candidate view; subMap will not throw now because start != null
            NavigableMap<String, FileRecord> sub = files.subMap(start, true, end, true);
            Collection<FileRecord> candidates = sub.values();

            // PQ comparator: make the worst element according to final ordering be the root (min-heap)
            PriorityQueue<FileRecord> pq = new PriorityQueue<>(k, (a, b) -> {
               int sizeCmp = Long.compare(a.latestSuze(), b.latestSuze());
               if(sizeCmp != 0) return sizeCmp;
               return b.getName().compareTo(a.getName());
            });

            for (FileRecord f : candidates) {
                if (f.isDeleted()) continue; // skip deleted (helpful for Level 4 later). By default none are deleted.
                if (pq.size() < k) {
                    pq.offer(f);
                } else {
                    FileRecord worst = pq.peek();
                    // candidate is better if size greater OR (size equal AND name lexicographically smaller)
                    if (compareForFinalOrder(f,worst) < 0) {
                        pq.poll();
                        pq.offer(f);
                    }
                }
            }

            // final sort: size desc, name asc
            List<FileRecord> chosen = new ArrayList<>(pq);
            chosen.sort((a, b) -> {
                int c = Long.compare(b.latestSuze(), a.latestSuze()); // desc
                if (c != 0) return c;
                return a.getName().compareTo(b.getName()); // asc
            });

            // Convert to FileMetaData snapshots
            List<FileMetaData> res = new ArrayList<>(chosen.size());
            for (FileRecord r : chosen) {
                FileVersion lv = r.latestVersion();
                if (lv != null) {
                    res.add(new FileMetaData(r.getName(), lv.sizeBytes, r.getCreatedAt(), r.getModifiedAt(), lv.metadata));
                }
            }
            return res;
        }

        // Helper: compare for final desired order (negative if a should come before b)
        private static int compareForFinalOrder(FileRecord a, FileRecord b) {
            int bySizeDesc = Long.compare(b.latestSuze(), a.latestSuze());
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

        // Add initial files (v1)
        s.addFile("photos/2025/vacation.jpg", "JPEGDATA_SMALL", "sha1:aaa", false);
        s.addFile("photos/2025/party.mov", "MOVDATA_LARGE".repeat(100), "sha1:bbb", false); // large
        s.addFile("photos/2025/a_tie.jpg", "MOVDATA_LARGE".repeat(100), "sha1:ccc", false); // tie
        s.addFile("docs/resume.pdf", "PDFDATA_V1", "sha1:r1", false);

        // getFile returns snapshot of latest
        FileMetaData meta = s.getFile("docs/resume.pdf");
        System.out.println("Initial resume meta: " + meta);
        assert meta.getSizeBytes() == "PDFDATA_V1".getBytes(StandardCharsets.UTF_8).length;

        // Add a new version for resume (v2)
        s.addVersion("docs/resume.pdf", "PDFDATA_V2_WITH_MORE_CONTENT", "sha1:r2");
        List<FileVersion> versions = s.listVersions("docs/resume.pdf");
        System.out.println("Versions for resume: " + versions);
        assert versions.size() == 2;
        // getFile should reflect v2
        FileMetaData meta2 = s.getFile("docs/resume.pdf");
        System.out.println("Latest resume meta: " + meta2);
        assert Objects.requireNonNull(meta2).getSizeBytes() == versions.get(1).sizeBytes;

        // Move file (test boolean API)
        boolean moved = s.moveFile("docs/resume.pdf", "docs/2025/resume.pdf", false);
        System.out.println("Move succeeded? " + moved);
        assert moved;
        assert s.getFile("docs/resume.pdf") == null;
        assert s.getFile("docs/2025/resume.pdf") != null;

        // topK by prefix uses latest version sizes
        List<FileMetaData> top2 = s.topKByPrefix("photos/2025/", 2);
        System.out.println("Top 2 photos/2025/:");
        top2.forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        // Add version to a_tie to make it larger than party.mov and re-evaluate topK
        s.addVersion("photos/2025/a_tie.jpg", "MOVDATA_LARGE".repeat(200), "sha1:tie2");
        List<FileMetaData> topAfter = s.topKByPrefix("photos/2025/", 2);
        System.out.println("Top 2 after a_tie grew:");
        topAfter.forEach(f -> System.out.println(" - " + f.getName() + " size=" + f.getSizeBytes()));

        System.out.println("Level 3 demo finished.");
    }


}
