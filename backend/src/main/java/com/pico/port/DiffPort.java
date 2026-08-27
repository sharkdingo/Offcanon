package com.pico.port;

import com.pico.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.List;

public interface DiffPort {
    List<DiffEntry> compare(Snapshot base, Path workspace);

    record DiffEntry(String path, Change change, long beforeBytes, long afterBytes, boolean binary) {
        public enum Change { ADDED, MODIFIED, DELETED }
    }
}
