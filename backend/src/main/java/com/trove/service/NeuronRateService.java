package com.trove.service;

/** Service contract for NeuronRateService. */
public interface NeuronRateService {
    double neuronsFor(String model, long promptTokens, long completionTokens);
}
