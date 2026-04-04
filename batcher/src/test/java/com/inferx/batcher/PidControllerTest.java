package com.inferx.batcher;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PidControllerTest {

    @Test
    void outputClampedToRange() {
        var pid = new PidController(100, 0, 0, 1.0, 64.0);
        // Huge error should clamp to max
        assertThat(pid.compute(1.0, 0.0)).isLessThanOrEqualTo(64.0);
        // Negative error should clamp to min
        assertThat(pid.compute(0.0, 1.0)).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void proportionalResponse() {
        var pid = new PidController(10.0, 0, 0, 0, 100);
        double output = pid.compute(50, 30); // error = 20
        assertThat(output).isGreaterThan(0);
    }

    @Test
    void zeroErrorProducesZeroOutput() {
        var pid = new PidController(10.0, 0, 0, -100, 100);
        double output = pid.compute(50, 50); // error = 0
        assertThat(output).isCloseTo(0.0, within(0.01));
    }

    @Test
    void integralAccumulatesOverTime() throws InterruptedException {
        var pid = new PidController(0, 10.0, 0, -100, 100);
        // Repeated positive error should increase output via integral
        double first = pid.compute(1.0, 0.0);
        Thread.sleep(10);
        double second = pid.compute(1.0, 0.0);
        // Integral accumulates, so second should be larger
        assertThat(second).isGreaterThanOrEqualTo(first);
    }

    @Test
    void resetClearsState() {
        var pid = new PidController(10, 10, 10, 0, 100);
        pid.compute(100, 0);
        pid.compute(100, 0);
        pid.reset();
        // After reset, integral should be zero — output should be similar to first call
        double afterReset = pid.compute(50, 50);
        assertThat(afterReset).isCloseTo(0.0, within(1.0));
    }

    @Test
    void rejectsInvalidRange() {
        assertThatThrownBy(() -> new PidController(1, 0, 0, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convergesToSetpoint() throws InterruptedException {
        var pid = new PidController(2.0, 0.5, 0.3, 1, 64);
        double measured = 0.5;
        double setpoint = 0.85;

        for (int i = 0; i < 20; i++) {
            double output = pid.compute(setpoint, measured);
            // Simulate system response: measured moves towards output direction
            measured += (setpoint - measured) * 0.2;
            Thread.sleep(5);
        }

        // After 20 iterations, measured should be close to setpoint
        assertThat(measured).isCloseTo(setpoint, within(0.1));
    }
}
