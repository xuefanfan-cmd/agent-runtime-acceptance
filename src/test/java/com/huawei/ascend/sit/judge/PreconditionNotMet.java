package com.huawei.ascend.sit.judge;

import com.huawei.ascend.sit.judge.EvidenceChecks.Reason;

/**
 * A precondition or activation the scenario needs did not hold, so this execution cannot conclude anything about
 * the product. Distinct from a product violation: the record says FAIL with this reason, and the fix belongs to
 * the deployment, the stub or the test.
 */
public final class PreconditionNotMet extends RuntimeException {
    private final Reason reason;

    public PreconditionNotMet(Reason reason, String requirement, String evidenceRef) {
        super("[" + reason.code() + "] " + requirement + " (evidence " + evidenceRef + ")");
        if (reason != Reason.CONDITIONAL_NOT_TRIGGERED && reason != Reason.INFRA_FAILURE) {
            throw new IllegalArgumentException("precondition failures are conditional_not_triggered or infra_failure");
        }
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
