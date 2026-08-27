package com.pico.port;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.List;

public interface PromotionPort {
    PromotionResult apply(Project project, Snapshot base, Experiment experiment, Path candidate);

    record PromotionResult(boolean applied, List<String> changedFiles, String resultingFingerprint) {
        public PromotionResult {
            changedFiles = List.copyOf(changedFiles);
        }
    }
}
