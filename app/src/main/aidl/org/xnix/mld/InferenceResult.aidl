package org.xnix.mld;

parcelable InferenceResult {
    byte[] output;
    long latencyMicros;
    int acceleratorUsed;   // ACCEL_* actually executed on
}
