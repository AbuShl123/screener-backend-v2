package dev.abu.screener_backend.analysis.cluster;

import java.util.Arrays;

/**
 * Running upper median ({@code sorted[n / 2]}) of a growing set of doubles, in O(log n) per add
 * and O(1) per read: two primitive binary heaps, the smaller half in a max-heap, the larger half
 * in a min-heap whose root is the median. No allocation except when a heap grows past any size
 * seen before. Single-thread use.
 *
 * <p>Replaces binary insertion into a sorted array, which is O(n) per add and so O(n²) for the
 * long uniform runs of a thick book.
 */
final class RunningMedian {

    /** Max-heap: the {@code floor(n / 2)} smallest values. */
    private double[] low;
    /** Min-heap: the {@code ceil(n / 2)} largest values; {@code high[0]} is the median. */
    private double[] high;
    private int lowSize;
    private int highSize;

    RunningMedian(int initialCapacity) {
        low = new double[initialCapacity];
        high = new double[initialCapacity];
    }

    void clear() {
        lowSize = 0;
        highSize = 0;
    }

    int size() {
        return lowSize + highSize;
    }

    /** The upper median; undefined while empty. */
    double median() {
        return high[0];
    }

    void add(double value) {
        if (highSize == 0 || value >= high[0]) {
            pushHigh(value);
        } else {
            pushLow(value);
        }
        if (highSize > lowSize + 1) {
            pushLow(popHigh());
        } else if (lowSize > highSize) {
            pushHigh(popLow());
        }
    }

    // ---- min-heap (high) ----

    private void pushHigh(double v) {
        if (highSize == high.length) high = Arrays.copyOf(high, high.length * 2);
        int i = highSize++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (high[parent] <= v) break;
            high[i] = high[parent];
            i = parent;
        }
        high[i] = v;
    }

    private double popHigh() {
        double top = high[0];
        double last = high[--highSize];
        int i = 0;
        int half = highSize >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            if (child + 1 < highSize && high[child + 1] < high[child]) child++;
            if (last <= high[child]) break;
            high[i] = high[child];
            i = child;
        }
        if (highSize > 0) high[i] = last;
        return top;
    }

    // ---- max-heap (low) ----

    private void pushLow(double v) {
        if (lowSize == low.length) low = Arrays.copyOf(low, low.length * 2);
        int i = lowSize++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (low[parent] >= v) break;
            low[i] = low[parent];
            i = parent;
        }
        low[i] = v;
    }

    private double popLow() {
        double top = low[0];
        double last = low[--lowSize];
        int i = 0;
        int half = lowSize >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            if (child + 1 < lowSize && low[child + 1] > low[child]) child++;
            if (last >= low[child]) break;
            low[i] = low[child];
            i = child;
        }
        if (lowSize > 0) low[i] = last;
        return top;
    }
}
