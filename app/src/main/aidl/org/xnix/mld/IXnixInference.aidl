package org.xnix.mld;

import org.xnix.mld.ModelDescriptor;
import org.xnix.mld.InferenceResult;

interface IXnixInference {
    const int ACCEL_AUTO = 0;   // NPU -> GPU -> CPU
    const int ACCEL_NPU  = 1;
    const int ACCEL_GPU  = 2;
    const int ACCEL_CPU  = 3;

    // ---- classic LiteRT CompiledModel (tensor) ----
    long loadModelByPath(in String path, int accelPref);
    long loadModelByFd(in ParcelFileDescriptor modelFd, int accelPref);
    InferenceResult run(long session, in byte[] input);
    int activeAccelerator(long session);
    void unload(long session);

    // ---- generative LiteRT-LM (.litertlm SLM/LLM) ----
    // Load a .litertlm model; backend: ACCEL_GPU (default) or ACCEL_CPU
    // ACCEL_NPU/AUTO select the Tensor TPU LLM path (needs an NPU-compiled .litertlm).
    long loadLlmByPath(in String path, int accelPref);
    long loadLlmByFd(in ParcelFileDescriptor modelFd, int accelPref);

    // Blocking single-turn generation: prompt in, full response text out.
    String generate(long llmSession, in String prompt, int maxTokens);

    // Release an LLM session.
    void unloadLlm(long llmSession);
}
