package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base for FEAT-112 classes whose scenarios share one deployment form. The repository's managed-stack lifecycle
 * launches the DA once per class; this base adds the model double, readiness and the deployment handle.
 * Scenarios that need their own configuration, restarts or two processes start a {@link GateDeployment} per test.
 */
@Feat112Integration
public abstract class DeployedGateCase extends BaseManagedStackTest {
    private GateDeployment.Prepared prepared;
    private GateDeployment deployment;

    /** The deployment form every scenario of this class rests on. */
    protected abstract DeploymentForm form();

    @Override
    protected final SutStack.Builder buildStack(TestConfig config) {
        try {
            prepared = GateDeployment.prepare(config, form(), GateDeployment.AGENT);
        } catch (Exception e) {
            throw new IllegalStateException("FEAT-112 deployment " + form().name() + " could not be prepared: " + e.getMessage(), e);
        }
        return prepared.builder();
    }

    @BeforeAll
    final void attachDeployment() throws Exception {
        deployment = GateDeployment.attach(prepared, stack, false);
    }

    protected final GateDeployment deployment() {
        return deployment;
    }

    @AfterAll
    final void closeModelDouble() throws Exception {
        if (prepared != null) prepared.close();
    }
}
