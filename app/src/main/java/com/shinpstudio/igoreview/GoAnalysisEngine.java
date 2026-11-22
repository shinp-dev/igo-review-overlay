package com.shinpstudio.igoreview;

/**
 * 9x9-only analysis contract, independent of JNI or network transport.
 *
 * Values are always relative to the requested side to play. Implementations
 * MUST be serialized on a worker thread, never the Android main thread.
 * Do not produce fabricated values if the model is not initialized.
 */
interface GoAnalysisEngine extends AutoCloseable {
    @Override default void close() {}
    KataGoClient.Result analyze(BoardRecognizer.Position board, int sideToPlay) throws Exception;
}
