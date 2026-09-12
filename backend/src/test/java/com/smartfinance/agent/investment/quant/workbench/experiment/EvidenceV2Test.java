package com.smartfinance.agent.investment.quant.workbench.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceV2Test {
    Map<String,Object> evaluate(String controls,String data,String source,String... reasons) {
        return new EvidenceQualityEvaluator().evaluate(controls,data,source,List.of(0,1,2,3,4),5,5,List.of(),List.of(reasons));
    }
    @ParameterizedTest @ValueSource(strings={"CONTROL_VARIABLE_VIOLATION","EXPERIMENT_ENVIRONMENT_CHANGED","EXPERIMENT_TASK_LINK_INVALID","EXPERIMENT_INVARIANT_MISMATCH"})
    void explicitControlReasonsFailControls(String code) {
        var result=evaluate("PASS","PASS","COMPLETE",code);
        assertThat(result).containsEntry("level","INVALID").containsEntry("controlIntegrity","FAIL").containsEntry("dataConsistency","PASS");
        assertThat((Map<String,String>)result.get("reasonClassifications")).containsEntry(code,"CONTROL_INTEGRITY");
    }
    @ParameterizedTest @ValueSource(strings={"EXPERIMENT_DATA_INCONSISTENCY","SNAPSHOT_CORRUPTED","SNAPSHOT_FORMAT_UNSUPPORTED"})
    void dataFailuresDoNotFailControlAxis(String code) {
        assertThat(evaluate("PASS","PASS","COMPLETE",code)).containsEntry("level","INVALID").containsEntry("controlIntegrity","PASS").containsEntry("dataConsistency","FAIL");
    }
    @ParameterizedTest @ValueSource(strings={"CORPORATE_ACTIONS_NOT_VERIFIED","CORPORATE_ACTION_UNSUPPORTED"})
    void corporateActionsWarnRatherThanInvalidate(String code) {
        assertThat(evaluate("PASS","PASS","COMPLETE",code)).containsEntry("level","LOW").containsEntry("dataConsistency","WARN");
    }
    @Test void partialControlAndSourceAreLowAndMissingSourceIsInsufficient() {
        assertThat(evaluate("PARTIAL","PASS","COMPLETE")).containsEntry("level","LOW");
        assertThat(evaluate("PASS","PASS","PARTIAL")).containsEntry("level","LOW");
        assertThat(evaluate("PASS","PASS","INSUFFICIENT")).containsEntry("level","INSUFFICIENT");
        assertThat(evaluate("FAIL","PASS","INSUFFICIENT")).containsEntry("level","INVALID");
    }
    @Test void unknownRawReasonIsPreservedWithMediumEvidence() {
        var result=evaluate("PASS","PASS","COMPLETE","UNRECOGNIZED_ENGINE_REASON");assertThat(result).containsEntry("level","MEDIUM");
        assertThat((Map<String,String>)result.get("reasonClassifications")).containsEntry("UNRECOGNIZED_ENGINE_REASON","OTHER");
    }
}
