package io.github.drompincen.javaclawv1.runtime.agent.llm;

import java.util.List;

public record ScenarioReport(
        String scenarioName,
        int totalSteps,
        int passedSteps,
        List<StepReport> stepReports
) {
    public boolean allPassed() {
        return passedSteps == totalSteps;
    }

    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("Scenario: ").append(scenarioName != null ? scenarioName : "unnamed").append("\n");
        sb.append("========================================\n");

        for (int i = 0; i < stepReports.size(); i++) {
            StepReport sr = stepReports.get(i);
            sb.append("Step ").append(i + 1).append("/").append(totalSteps)
                    .append(": ").append(sr.stepName() != null ? sr.stepName() : "unnamed").append("\n");

            for (AssertionResult ar : sr.results()) {
                String tag = ar.passed() ? "PASS" : "FAIL";
                sb.append("  [").append(tag).append("] ").append(ar.name())
                        .append(": expected=").append(ar.expected())
                        .append(", actual=").append(ar.actual()).append("\n");
            }

            long passCount = sr.results().stream().filter(AssertionResult::passed).count();
            String stepResult = sr.allPassed() ? "PASS" : "FAIL";
            sb.append("  STEP: ").append(stepResult)
                    .append(" (").append(passCount).append("/").append(sr.results().size()).append(")\n");
        }

        sb.append("========================================\n");
        String result = allPassed() ? "PASS" : "FAIL";
        sb.append("RESULT: ").append(result)
                .append(" (").append(passedSteps).append("/").append(totalSteps).append(" steps passed)\n");
        sb.append("========================================");
        return sb.toString();
    }

    public record StepReport(
            String stepName,
            boolean allPassed,
            List<AssertionResult> results
    ) {}
}
