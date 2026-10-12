package com.saipramod.actiongraph;

import com.saipramod.actiongraph.eval.EvalRunner;
import com.saipramod.actiongraph.extract.RuleBasedExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CI gate on extraction quality and on the anti-hallucination verifier. */
class EvalGateTest {

    @Test
    void extractionAndVerificationMeetTheBar() throws Exception {
        var r = EvalRunner.run(new RuleBasedExtractor(), 100, 42);
        assertThat(r.synthetic().overall().f1()).isGreaterThanOrEqualTo(0.95);
        assertThat(r.blindSet().overall().f1()).isGreaterThanOrEqualTo(0.85);
        assertThat(r.synthetic().autoAcceptPrecision()).isGreaterThanOrEqualTo(0.95);
        assertThat(r.verifier().caught()).isEqualTo(1.0);
        assertThat(r.verifier().falseRejects()).isZero();
    }
}
