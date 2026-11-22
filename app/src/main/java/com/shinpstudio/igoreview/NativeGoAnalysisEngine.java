package com.shinpstudio.igoreview;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline ARM64 KataGo. The APK contains an upstream compiled GTP executable
 * in nativeLibraryDir and a verified official g170 neural network in assets.
 * Java only communicates via GTP; KataGo owns the actual 9x9 search.
 */
final class NativeGoAnalysisEngine implements GoAnalysisEngine {
    private static final String TAG = "NativeKataGo";
    private static final String NETWORK_SHA256 =
        "f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1";
    private static final Pattern INFO = Pattern.compile("(?:^|\\s)info\\s+move\\s+");
    private static final Pattern VALUE = Pattern.compile("(?:^|\\s)%s\\s+([^\\s]+)");
    private final Context context;
    private final AppSettings settings;
    private final BlockingQueue<String> outputLines = new LinkedBlockingQueue<>();
    private Process process;
    private OutputStreamWriter writer;
    private volatile boolean closed;
    private int commandId;

    NativeGoAnalysisEngine(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
    }

    static String sgfFor(BoardRecognizer.Position position, int toPlay) {
        if (position.stones.length != 81 ||
                (toPlay != BoardRecognizer.BLACK && toPlay != BoardRecognizer.WHITE))
            throw new IllegalArgumentException("Invalid 9x9 position");
        StringBuilder sgf = new StringBuilder("(;GM[1]FF[4]SZ[9]KM[7]RU[Chinese]PL[");
        sgf.append(toPlay == BoardRecognizer.BLACK ? "B" : "W").append("]");
        for (int color : new int[]{BoardRecognizer.BLACK, BoardRecognizer.WHITE}) {
            sgf.append(color == BoardRecognizer.BLACK ? "AB" : "AW");
            int before = sgf.length();
            for (int i = 0; i < 81; i++) {
                if (position.stones[i] == color)
                    sgf.append('[').append((char)('a' + i % 9))
                        .append((char)('a' + i / 9)).append(']');
                else if (position.stones[i] != BoardRecognizer.EMPTY
                        && position.stones[i] != BoardRecognizer.BLACK
                        && position.stones[i] != BoardRecognizer.WHITE)
                    throw new IllegalArgumentException("Invalid stone color");
            }
            if (before == sgf.length()) sgf.setLength(sgf.length() - 2);
        }
        return sgf.append(")").toString();
    }

    @Override public synchronized KataGoClient.Result analyze(
            BoardRecognizer.Position board, int toPlay) throws Exception {
        if (closed) throw new IllegalStateException("解析エンジンは終了済みです");
        ensureStarted();
        File position = new File(context.getNoBackupFilesDir(), "current-9x9.sgf");
        Files.writeString(position.toPath(), sgfFor(board, toPlay), StandardCharsets.UTF_8);
        command("loadsgf " + position.getAbsolutePath(), 12);
        command("kata-set-param maxVisits " + settings.visits(), 12);
        // This command does not play a move. It emits analysis and a final
        // 'play <move>' marker. Visit budget is capped by config maxTime.
        String result = command("kata-search_analyze " +
            (toPlay == BoardRecognizer.BLACK ? "B" : "W") +
            " 1 maxmoves " + settings.candidates(), 45);
        return parseAnalysis(result, settings.candidates());
    }

    private void ensureStarted() throws Exception {
        if (process != null && process.isAlive()) return;
        stopProcess();
        File root = new File(context.getNoBackupFilesDir(), "katago");
        if (!root.exists() && !root.mkdirs())
            throw new IllegalStateException("KataGo作業フォルダを作成できません");
        File config = new File(root, "gtp.cfg");
        File network = new File(root, "model.bin.gz");
        copyAsset("katago/gtp.cfg", config);
        if (!network.isFile() || !NETWORK_SHA256.equals(sha256(network))) {
            File staging = new File(root, "model.partial");
            copyAsset("katago/model.bin.gz", staging);
            if (!NETWORK_SHA256.equals(sha256(staging))) {
                staging.delete();
                throw new IllegalStateException("KataGoモデルのSHA-256検証に失敗しました");
            }
            if (network.exists() && !network.delete())
                throw new IllegalStateException("古いKataGoモデルを更新できません");
            if (!staging.renameTo(network))
                throw new IllegalStateException("KataGoモデルを保存できません");
        }
        File nativeExecutable = new File(context.getApplicationInfo().nativeLibraryDir, "libkatago.so");
        if (!nativeExecutable.isFile())
            throw new IllegalStateException("KataGo本体がAPKにありません。ネイティブ同梱版APKを使用してください");
        String selectedHash = settings.customModelHash();
        if(!selectedHash.isEmpty()){
            File selected = new File(root,"custom-model.bin.gz");
            if(!selected.isFile() || !selectedHash.equals(sha256(selected)))
                throw new IllegalStateException("取り込んだモデルが見つからないか破損しています");
            network = selected;
        }
        ProcessBuilder builder = new ProcessBuilder(nativeExecutable.getAbsolutePath(),
            "gtp", "-config", config.getAbsolutePath(), "-model", network.getAbsolutePath());
        builder.directory(root);
        builder.redirectErrorStream(false);
        process = builder.start();
        writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Process running = process;
        Thread stdout = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) outputLines.offer(line);
            } catch (Exception e) { Log.w(TAG, "KataGo stdout failed", e); }
            finally { outputLines.offer("!ENGINE_EXIT!"); }
        }, "katago-gtp-reader");
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    running.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) Log.d(TAG, line);
            } catch (Exception ignored) { }
        }, "katago-stderr-reader");
        stderr.setDaemon(true);
        stderr.start();
        try {
            command("boardsize 9", 90); // Model loading can be slow on first start.
            command("kata-set-rules chinese", 12);
            command("komi 7", 12);
        } catch (Exception e) {
            stopProcess();
            throw e;
        }
    }

    private void copyAsset(String asset, File output) throws Exception {
        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    /**
     * Exactly one command is in-flight. A reader thread handles GTP stdout
     * and lets this worker fail fast on death, cancellation, or a timeout.
     */
    private String command(String command, int timeoutSeconds) throws Exception {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("解析がキャンセルされました");
        if (process == null || !process.isAlive())
            throw new IllegalStateException("KataGoが終了しました");
        outputLines.clear();
        int id = ++commandId;
        writer.write(id + " " + command + "\n");
        writer.flush();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        boolean receivedHeader = false, failed = false;
        StringBuilder answer = new StringBuilder();
        try {
            while (true) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("KataGo解析が時間切れです");
                String line = outputLines.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null || "!ENGINE_EXIT!".equals(line))
                    throw new IllegalStateException("KataGoプロセスが異常終了しました");
                if (!receivedHeader) {
                    if (line.startsWith("=" + id) || line.startsWith("?" + id)) {
                        receivedHeader = true;
                        failed = line.startsWith("?" + id);
                        String suffix = line.substring(1 + String.valueOf(id).length());
                        answer.append(suffix.trim()).append('\n');
                    }
                    continue;
                }
                if (line.isEmpty()) {
                    if (failed) throw new IllegalStateException("KataGo GTP: " + answer.toString().trim());
                    return answer.toString();
                }
                answer.append(line).append('\n');
            }
        } catch (InterruptedException | RuntimeException error) {
            stopProcess();
            throw error;
        }
    }

    private static double number(String part, String key, double fallback) {
        Matcher m = Pattern.compile(String.format(
            java.util.Locale.ROOT, VALUE.pattern(), Pattern.quote(key))).matcher(part);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    static KataGoClient.Result parseAnalysis(String response, int count) {
        // KataGo emits one or more 'info move ...' sections. Later updates
        // replace earlier sections for the same move. 'pass' has no grid label.
        Matcher segments = INFO.matcher(response);
        List<int[]> ranges = new ArrayList<>();
        while (segments.find()) ranges.add(new int[]{segments.start(), segments.end()});
        Map<Integer, ParsedMove> best = new HashMap<>();
        for (int i = 0; i < ranges.size(); i++) {
            String chunk = response.substring(ranges.get(i)[1],
                i + 1 < ranges.size() ? ranges.get(i + 1)[0] : response.length());
            String[] tokens = chunk.trim().split("\\s+");
            if (tokens.length == 0) continue;
            int index = KataGoClient.parseCoordinate(tokens[0]);
            if (index < 0) continue;
            int visits = (int) number(chunk, "visits", 0);
            double winrate = number(chunk, "winrate", Double.NaN);
            double score = number(chunk, "scoreLead", Double.NaN);
            int order = (int) number(chunk, "order", 9999);
            if (!Double.isFinite(winrate) || !Double.isFinite(score)) continue;
            best.put(index, new ParsedMove(index, visits, winrate, score, order));
        }
        List<ParsedMove> sorted = new ArrayList<>(best.values());
        sorted.sort(Comparator.comparingInt(a -> a.order));
        List<KataGoClient.Candidate> candidates = new ArrayList<>();
        for (ParsedMove p : sorted) {
            if (candidates.size() >= count) break;
            candidates.add(new KataGoClient.Candidate(p.index, p.visits, p.winrate, p.score));
        }
        if (candidates.isEmpty())
            throw new IllegalStateException("KataGoから候補手が返りませんでした");
        KataGoClient.Candidate top = candidates.get(0);
        // GTP move-info is per candidate; for now top candidate is a PROXY
        // for root values, NOT exact weighted rootInfo from JSON analysis API.
        return new KataGoClient.Result(candidates, top.winrate, top.score);
    }

    private static final class ParsedMove {
        final int index, visits, order;
        final double winrate, score;
        ParsedMove(int index, int visits, double winrate, double score, int order) {
            this.index = index; this.visits = visits; this.winrate = winrate;
            this.score = score; this.order = order;
        }
    }

    private void stopProcess() {
        Process old = process;
        process = null; writer = null;
        outputLines.clear();
        if (old != null) {
            old.destroy();
            try { if (!old.waitFor(2, TimeUnit.SECONDS)) old.destroyForcibly(); }
            catch (InterruptedException e) { old.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }

    @Override public synchronized void close() {
        closed = true; stopProcess();
    }
}
