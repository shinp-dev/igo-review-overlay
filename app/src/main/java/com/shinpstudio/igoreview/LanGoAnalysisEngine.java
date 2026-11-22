package com.shinpstudio.igoreview;

/**
 * Temporary transport while a real offline 9x9 native backend is integrated.
 * This is NOT on-device KataGo.
 */
final class LanGoAnalysisEngine implements GoAnalysisEngine {
    private final AppSettings settings;
    LanGoAnalysisEngine(AppSettings settings) { this.settings = settings; }

    @Override public KataGoClient.Result analyze(BoardRecognizer.Position board, int sideToPlay)
            throws Exception {
        return KataGoClient.analyze(settings, board, sideToPlay);
    }
}
