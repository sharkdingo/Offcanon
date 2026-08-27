package com.offcanon.port;

import com.offcanon.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.List;

public interface DiffPort {
    List<DiffEntry> compare(Snapshot base, Path workspace);

    record DiffEntry(String path,
                     Change change,
                     long beforeBytes,
                     long afterBytes,
                     boolean binary,
                     int additions,
                     int deletions,
                     String patch) {
        public DiffEntry(String path, Change change, long beforeBytes, long afterBytes, boolean binary) {
            this(path, change, beforeBytes, afterBytes, binary, 0, 0,
                    binary ? "Binary files differ" : "");
        }

        public enum Change { ADDED, MODIFIED, DELETED }
    }
}
