package com.offcanon.port;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.project.domain.Project;
import com.offcanon.workspace.domain.Snapshot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface PromotionPort {
    PromotionPlan plan(Project project, Snapshot base, Experiment experiment, Path candidate);

    PromotionResult apply(Project project,
                          Snapshot base,
                          Experiment experiment,
                          Path candidate,
                          PromotionPlan expectedPlan);

    record PromotionPlan(List<String> touchedFiles,
                         Map<String, String> preimageHashes,
                         Map<String, String> postimageHashes) {
        public PromotionPlan {
            touchedFiles = List.copyOf(touchedFiles);
            preimageHashes = Map.copyOf(preimageHashes);
            postimageHashes = Map.copyOf(postimageHashes);
        }
    }

    record PromotionResult(boolean applied, List<String> changedFiles) {
        public PromotionResult {
            changedFiles = List.copyOf(changedFiles);
        }
    }
}
