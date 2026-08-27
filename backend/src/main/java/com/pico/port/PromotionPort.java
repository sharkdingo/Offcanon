package com.pico.port;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface PromotionPort {
    default PromotionPlan plan(Project project, Snapshot base, Experiment experiment, Path candidate) {
        return new PromotionPlan(List.of(), Map.of(), Map.of());
    }

    PromotionResult apply(Project project, Snapshot base, Experiment experiment, Path candidate);

    default PromotionResult apply(Project project,
                                  Snapshot base,
                                  Experiment experiment,
                                  Path candidate,
                                  PromotionPlan expectedPlan) {
        return apply(project, base, experiment, candidate);
    }

    record PromotionPlan(List<String> touchedFiles,
                         Map<String, String> preimageHashes,
                         Map<String, String> postimageHashes) {
        public PromotionPlan {
            touchedFiles = List.copyOf(touchedFiles);
            preimageHashes = Map.copyOf(preimageHashes);
            postimageHashes = Map.copyOf(postimageHashes);
        }
    }

    record PromotionResult(boolean applied, List<String> changedFiles, String resultingFingerprint) {
        public PromotionResult {
            changedFiles = List.copyOf(changedFiles);
        }
    }
}
