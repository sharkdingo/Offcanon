package com.offcanon.verification.domain;

public enum VerificationPurpose {
    EXPERIMENT_RESULT("VERIFICATION"),
    PROMOTION_CANDIDATE("PROMOTION_VERIFICATION");

    private final String evidenceKind;

    VerificationPurpose(String evidenceKind) {
        this.evidenceKind = evidenceKind;
    }

    public String evidenceKind() {
        return evidenceKind;
    }
}
