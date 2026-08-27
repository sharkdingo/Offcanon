package com.pico.port;

import com.pico.experiment.domain.Experiment;
import com.pico.project.domain.Project;
import com.pico.verification.domain.VerificationResult;
import com.pico.verification.domain.VerificationPurpose;
import com.pico.workspace.domain.Snapshot;

import java.nio.file.Path;

public interface VerificationPort {
    VerificationResult verify(Project project,
                              Experiment experiment,
                              Snapshot verifiedState,
                              Path workspace,
                              VerificationPurpose purpose);
}
