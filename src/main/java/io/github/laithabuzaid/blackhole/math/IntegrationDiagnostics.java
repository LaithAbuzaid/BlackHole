package io.github.laithabuzaid.blackhole.math;

/**
 * Reusable diagnostic record populated during numerical integration without per-step allocation.
 */
public final class IntegrationDiagnostics {
    public int acceptedSteps;
    public int rejectedSteps;
    public int functionEvaluations;
    public double maxErrorRatio;
    public double maxConstraintDrift;
    public double minStepUsed;
    public double maxStepUsed;
    public boolean completed;

    public void reset() {
        acceptedSteps = 0;
        rejectedSteps = 0;
        functionEvaluations = 0;
        maxErrorRatio = 0.0;
        maxConstraintDrift = 0.0;
        minStepUsed = Double.POSITIVE_INFINITY;
        maxStepUsed = 0.0;
        completed = false;
    }

    public void recordAcceptedStep(double absStep, double errorRatio) {
        acceptedSteps++;
        if (absStep < minStepUsed) minStepUsed = absStep;
        if (absStep > maxStepUsed) maxStepUsed = absStep;
        if (errorRatio > maxErrorRatio) maxErrorRatio = errorRatio;
    }

    public void recordConstraintDrift(double absDrift) {
        if (absDrift > maxConstraintDrift) {
            maxConstraintDrift = absDrift;
        }
    }
}
