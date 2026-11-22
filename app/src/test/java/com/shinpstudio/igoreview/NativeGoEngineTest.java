package com.shinpstudio.igoreview;
import static org.junit.Assert.*;
import org.junit.Test;

public class NativeGoEngineTest {
    @Test public void sgfEmptyBlack() {
        int[] grid = new int[81];
        BoardRecognizer.Position p = new BoardRecognizer.Position(grid, -1, BoardRecognizer.BLACK);
        assertEquals("(;GM[1]FF[4]SZ[9]KM[7]RU[Chinese]PL[B])",
            NativeGoAnalysisEngine.sgfFor(p, BoardRecognizer.BLACK));
    }
    @Test public void sgfCoordinatesAndWhiteTurn() {
        int[] grid = new int[81];
        grid[0] = BoardRecognizer.BLACK;
        grid[80] = BoardRecognizer.WHITE;
        BoardRecognizer.Position p = new BoardRecognizer.Position(grid, 80, BoardRecognizer.BLACK);
        String actual = NativeGoAnalysisEngine.sgfFor(p, BoardRecognizer.WHITE);
        assertTrue(actual.contains("PL[W]"));
        assertTrue(actual.contains("AB[aa]"));
        assertTrue(actual.contains("AW[ii]"));
    }
    @Test public void gtPInfoParsing() {
        String lines = "info move A9 visits 90 winrate 0.75 scoreLead 4.1 order 0 pv A9 " +
            "info move J1 visits 6 winrate 0.42 scoreLead -3.3 order 1 pv J1\nplay A9\n";
        KataGoClient.Result result = NativeGoAnalysisEngine.parseAnalysis(lines, 2);
        assertEquals(2, result.moves.size());
        assertEquals(0, result.moves.get(0).index);
        assertEquals(80, result.moves.get(1).index);
        assertEquals(0.75, result.winrate, 0.0001);
    }
}
