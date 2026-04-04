package com.inferx.batcher;

/**
 * PID (Proportional-Integral-Derivative) controller for adaptive batch sizing.
 *
 * Controls batch size by observing the error between target batch fill rate
 * and actual fill rate. Adjusts batch size to maintain optimal GPU utilization
 * without missing SLO deadlines.
 *
 * Output is clamped to [minOutput, maxOutput].
 */
public final class PidController {

    private final double kp; // proportional gain
    private final double ki; // integral gain
    private final double kd; // derivative gain
    private final double minOutput;
    private final double maxOutput;

    private double integral;
    private double previousError;
    private long previousTimeNanos;

    /**
     * @param kp        proportional gain — reacts to current error
     * @param ki        integral gain — corrects accumulated error
     * @param kd        derivative gain — dampens oscillation
     * @param minOutput minimum output value (e.g., min batch size)
     * @param maxOutput maximum output value (e.g., max batch size)
     */
    public PidController(double kp, double ki, double kd, double minOutput, double maxOutput) {
        if (minOutput > maxOutput) {
            throw new IllegalArgumentException("minOutput must be <= maxOutput");
        }
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
        this.minOutput = minOutput;
        this.maxOutput = maxOutput;
        this.integral = 0;
        this.previousError = 0;
        this.previousTimeNanos = System.nanoTime();
    }

    /**
     * Compute the next control output given the current error.
     *
     * @param setpoint the target value (e.g., target fill rate 0.85)
     * @param measured the current measured value (e.g., actual fill rate 0.60)
     * @return the control output (e.g., new batch size)
     */
    public double compute(double setpoint, double measured) {
        long now = System.nanoTime();
        double dt = (now - previousTimeNanos) / 1_000_000_000.0; // seconds
        if (dt <= 0) dt = 0.001; // avoid division by zero

        double error = setpoint - measured;

        // Proportional term
        double p = kp * error;

        // Integral term (accumulated error over time) with anti-windup
        integral += error * dt;
        integral = clamp(integral, minOutput / Math.max(ki, 0.001), maxOutput / Math.max(ki, 0.001));
        double i = ki * integral;

        // Derivative term (rate of change of error)
        double derivative = (error - previousError) / dt;
        double d = kd * derivative;

        previousError = error;
        previousTimeNanos = now;

        return clamp(p + i + d, minOutput, maxOutput);
    }

    /**
     * Reset the controller state (e.g., after a configuration change).
     */
    public void reset() {
        integral = 0;
        previousError = 0;
        previousTimeNanos = System.nanoTime();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
