package dev.abu.screener_backend.analysis.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunningMedianTest {

    @Test
    @DisplayName("matches sorted[n / 2] after every add, with heavy duplicates, across clear and growth")
    void matchesSortedUpperMedian() {
        Random rnd = new Random(1);
        RunningMedian m = new RunningMedian(2);
        for (int trial = 0; trial < 2_000; trial++) {
            m.clear();
            int n = 1 + rnd.nextInt(300);
            double[] seen = new double[n];
            for (int i = 0; i < n; i++) {
                double v = trial % 2 == 0 ? rnd.nextInt(5) : rnd.nextGaussian() * 1e6;
                seen[i] = v;
                m.add(v);
                double[] sorted = Arrays.copyOf(seen, i + 1);
                Arrays.sort(sorted);
                assertEquals(sorted[(i + 1) / 2], m.median(), "trial " + trial + " size " + (i + 1));
                assertEquals(i + 1, m.size());
            }
        }
    }
}
