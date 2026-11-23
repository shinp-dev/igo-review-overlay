package com.shinpstudio.igoreview;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BoardRecognizerTest {
    private static final int CELL = 34;
    private static final int LEFT = 25;
    private static final int TOP = 40;
    private static final int GRID_SPAN = 8 * CELL;
    private static final int WIDTH = 322;
    private static final int HEIGHT = 352;
    private static final int STRIDE = (WIDTH + 6) * 4;
    private static final int BLACK_POINT = 19;
    private static final int WHITE_POINT = 80;
    private static final int BLACK = BoardRecognizer.BLACK;
    private static final int WHITE = BoardRecognizer.WHITE;

    @Test public void readsAllPointsWithGridLinesAndCalibrationOutline() {
        int[] expected = new int[81];
        expected[BLACK_POINT] = BLACK;
        expected[WHITE_POINT] = WHITE;
        for (int last : new int[]{BLACK_POINT, WHITE_POINT}) {
            BoardRecognizer.Position position = read(board(last));
            assertArrayEquals(expected, position.stones);
            assertEquals(last, position.last);
            assertEquals(last == BLACK_POINT ? WHITE : BLACK, position.turn);
        }
    }

    @Test public void rejectsCalibrationWithoutRoomForOuterSamples() {
        BoardRecognizer.RecognitionException error = assertThrows(BoardRecognizer.RecognitionException.class,
            () -> BoardRecognizer.read(board(-1), STRIDE, 4, WIDTH, HEIGHT, 0, TOP, GRID_SPAN, TOP + GRID_SPAN));
        assertTrue(error.detail.contains("sampling margins"));
    }

    @Test public void reportsTheIntersectionAndVotesWhenColorsAreUnrecognized() {
        ByteBuffer image = board(-1);
        for (int y = TOP - 12; y <= TOP + 12; y++) {
            for (int x = LEFT - 12; x <= LEFT + 12; x++) {
                putRgb(image, x, y, 0x0077ff);
            }
        }
        BoardRecognizer.RecognitionException error =
            assertThrows(BoardRecognizer.RecognitionException.class, () -> read(image));
        assertEquals("column=1, row=1, votes(empty/black/white/unknown)=[0, 0, 0, 8]", error.detail);
    }

    private static BoardRecognizer.Position read(ByteBuffer image) {
        return BoardRecognizer.read(image, STRIDE, 4, WIDTH, HEIGHT,
            LEFT, TOP, LEFT + GRID_SPAN, TOP + GRID_SPAN);
    }

    private static ByteBuffer board(int last) {
        ByteBuffer image = ByteBuffer.allocate(STRIDE * HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int col = x - LEFT;
                int row = y - TOP;
                boolean horizontal = col >= 0 && col <= GRID_SPAN
                    && row >= 0 && row <= GRID_SPAN && row % CELL == 0;
                boolean vertical = row >= 0 && row <= GRID_SPAN
                    && col >= 0 && col <= GRID_SPAN && col % CELL == 0;
                int color = (x % 2 == 0) ? 0xffe5bf : 0xfde7bb;
                if (horizontal || vertical) color = 0x493323;
                color = stone(color, col, row, BLACK_POINT, last, 0x000000, 0xfdfdfd);
                color = stone(color, col, row, WHITE_POINT, last, 0xfdfdfd, 0x000000);
                boolean outline = (horizontal && (row == 0 || row == GRID_SPAN))
                    || (vertical && (col == 0 || col == GRID_SPAN));
                if (outline) color = 0x79a0a4;
                putRgb(image, x, y, color);
            }
        }
        return image;
    }

    private static int stone(int background, int x, int y, int point, int last, int color, int ring) {
        double radius = Math.hypot(x - (point % 9) * CELL, y - (point / 9) * CELL);
        if (radius > CELL * .46) return background;
        if (point == last && Math.abs(radius - CELL * .24) <= 1.25) return ring;
        return color;
    }

    private static void putRgb(ByteBuffer image, int x, int y, int color) {
        int offset = y * STRIDE + x * 4;
        image.put(offset, (byte) (color >> 16));
        image.put(offset + 1, (byte) (color >> 8));
        image.put(offset + 2, (byte) color);
    }
}
