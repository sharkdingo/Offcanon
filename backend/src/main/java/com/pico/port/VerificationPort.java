package com.pico.port;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.verification.domain.VerificationResult;
import com.pico.workspace.domain.Snapshot;

public interface VerificationPort {
    VerificationResult verify(Project project, Experiment experiment, Snapshot snapshot);
}
