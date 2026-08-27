package com.offcanon.port;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.project.domain.Project;
import com.offcanon.verification.domain.VerificationResult;
import com.offcanon.verification.domain.VerificationPurpose;
import com.offcanon.workspace.domain.Snapshot;

import java.nio.file.Path;

public interface VerificationPort {
    VerificationResult verify(Project project,
                              Experiment experiment,
                              Snapshot verifiedState,
                              Path workspace,
                              VerificationPurpose purpose);
}
