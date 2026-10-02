package org.ippazio.privacylowvisionpdf;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.pdf.PdfDocument;
import androidx.pdf.content.PdfPageTextContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class ReflowPostProcessor {
    private static final Pattern MULTI_COLUMN_SPACING =
            Pattern.compile("\\S(?:\\t+| {2,})\\S");
    private static final Pattern LIST_PREFIX =
            Pattern.compile("^(?:[•◦▪‣–—-]|\\d{1,3}[.)]|[A-Za-z][.)])\\s+.+");
    private static final Pattern ARABIC_PAGE_NUMBER =
            Pattern.compile("^\\d{1,4}$");
    private static final Pattern ROMAN_PAGE_NUMBER =
            Pattern.compile("(?i)^[ivxlcdm]{1,8}$");

    private ReflowPostProcessor() {
    }

    @NonNull
    static String processPage(
            @Nullable PdfDocument.PdfPageContent pageContent,
            int pageNumber) {
        if (pageContent == null) {
            return "";
        }

        List<Block> blocks = collectBlocks(pageContent);
        if (blocks.isEmpty()) {
            return "";
        }

        PageGeometry geometry = PageGeometry.from(blocks);
        markObviousStructuredRegions(blocks, geometry);
        markLikelyPageNumbers(blocks, geometry, pageNumber);

        StringBuilder output = new StringBuilder();
        for (Block block : blocks) {
            if (block.omit) {
                continue;
            }

            String text = normalizeContinuousText(block.rawText);
            if (text.isEmpty()) {
                continue;
            }

            if (output.length() > 0) {
                appendParagraphBreak(output);
            }
            output.append(text);
        }

        return output.toString().trim();
    }

    @NonNull
    private static List<Block> collectBlocks(
            @NonNull PdfDocument.PdfPageContent pageContent) {
        List<Block> blocks = new ArrayList<>();
        int index = 0;

        for (PdfPageTextContent textContent : pageContent.getTextContents()) {
            String rawText = normalizeNewlines(textContent.getText()).trim();
            if (rawText.isEmpty()) {
                index++;
                continue;
            }

            List<RectF> bounds = textContent.getBounds();
            RectF union = unionBounds(bounds);
            Block block = new Block(index, rawText, union, bounds != null && !bounds.isEmpty());

            // Some PDF producers flatten a whole table row or table into one text object. In that
            // case geometry between separate objects cannot reveal the grid, but repeated wide
            // spacing on several short lines is still a strong signal of structured content.
            if (looksLikeTextualGrid(rawText)) {
                block.omit = true;
            }

            blocks.add(block);
            index++;
        }

        // AndroidX describes PdfPageTextContent as text in viewing order. Preserve that order.
        // Bounds are used only to identify obviously structured regions, not to invent a new order.
        Collections.sort(blocks, Comparator.comparingInt(block -> block.originalIndex));
        return blocks;
    }

    private static void markObviousStructuredRegions(
            @NonNull List<Block> blocks,
            @NonNull PageGeometry geometry) {
        if (!geometry.hasUsefulBounds || geometry.width <= 0f) {
            return;
        }

        List<Block> candidates = new ArrayList<>();
        for (Block block : blocks) {
            if (block.omit || !block.hasBounds) {
                continue;
            }

            String compact = block.rawText.replace('\n', ' ').trim();
            if (compact.length() > 90 || wordCount(compact) > 12) {
                continue;
            }

            // A narrow short block can be a table cell. Long narrow blocks are much more likely
            // to be ordinary newspaper or magazine columns and are deliberately not candidates.
            if (block.box.width() <= geometry.width * 0.62f) {
                candidates.add(block);
            }
        }

        if (candidates.size() < 6) {
            return;
        }

        float typicalHeight = medianHeight(candidates);
        float rowTolerance = Math.max(5f, typicalHeight * 0.65f);

        candidates.sort(Comparator
                .comparingDouble((Block block) -> block.box.centerY())
                .thenComparingDouble(block -> block.box.left));

        List<Row> rows = new ArrayList<>();
        for (Block block : candidates) {
            Row target = null;
            if (!rows.isEmpty()) {
                Row last = rows.get(rows.size() - 1);
                if (Math.abs(last.centerY - block.box.centerY()) <= rowTolerance) {
                    target = last;
                }
            }

            if (target == null) {
                target = new Row(block.box.centerY());
                rows.add(target);
            }
            target.blocks.add(block);
            target.recalculateCenter();
        }

        List<Row> gridRows = new ArrayList<>();
        for (Row row : rows) {
            if (row.blocks.size() >= 2 && row.blocks.size() <= 8) {
                row.blocks.sort(Comparator.comparingDouble(block -> block.box.left));
                gridRows.add(row);
            }
        }

        if (gridRows.size() < 3) {
            return;
        }

        float columnTolerance = Math.max(12f, geometry.width * 0.045f);
        boolean[] mark = new boolean[gridRows.size()];

        for (int i = 0; i < gridRows.size(); i++) {
            int similarRows = 0;
            for (int j = 0; j < gridRows.size(); j++) {
                if (i == j) {
                    continue;
                }
                if (commonColumnAnchors(gridRows.get(i), gridRows.get(j), columnTolerance) >= 2) {
                    similarRows++;
                }
            }

            // Requiring two other matching rows means at least three rows share the same grid.
            if (similarRows >= 2) {
                mark[i] = true;
            }
        }

        // Expand from each confident row to the other rows that share its column anchors.
        for (int i = 0; i < gridRows.size(); i++) {
            if (!mark[i]) {
                continue;
            }
            for (int j = 0; j < gridRows.size(); j++) {
                if (commonColumnAnchors(gridRows.get(i), gridRows.get(j), columnTolerance) >= 2) {
                    mark[j] = true;
                }
            }
        }

        int markedRows = 0;
        for (boolean value : mark) {
            if (value) {
                markedRows++;
            }
        }
        if (markedRows < 3) {
            return;
        }

        for (int i = 0; i < gridRows.size(); i++) {
            if (!mark[i]) {
                continue;
            }
            for (Block block : gridRows.get(i).blocks) {
                block.omit = true;
            }
        }
    }

    private static void markLikelyPageNumbers(
            @NonNull List<Block> blocks,
            @NonNull PageGeometry geometry,
            int pageNumber) {
        if (!geometry.hasUsefulBounds || geometry.height <= 0f || geometry.width <= 0f) {
            return;
        }

        float bottomBand = geometry.maxBottom - geometry.height * 0.08f;
        for (Block block : blocks) {
            if (block.omit || !block.hasBounds || block.box.bottom < bottomBand) {
                continue;
            }

            String text = block.rawText.trim();
            if (text.indexOf('\n') >= 0 || block.box.width() > geometry.width * 0.25f) {
                continue;
            }

            if (ARABIC_PAGE_NUMBER.matcher(text).matches()) {
                try {
                    int printed = Integer.parseInt(text);
                    int physical = pageNumber + 1;
                    // Covers ordinary pagination while tolerating a small front-matter offset.
                    if (Math.abs(printed - physical) <= 5) {
                        block.omit = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if (ROMAN_PAGE_NUMBER.matcher(text).matches()) {
                // Roman numerals are common in front matter. Only remove them in the extreme
                // bottom band and when they are isolated and narrow.
                block.omit = true;
            }
        }
    }

    private static int commonColumnAnchors(
            @NonNull Row first,
            @NonNull Row second,
            float tolerance) {
        int matches = 0;
        boolean[] used = new boolean[second.blocks.size()];

        for (Block a : first.blocks) {
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            for (int j = 0; j < second.blocks.size(); j++) {
                if (used[j]) {
                    continue;
                }
                float distance = Math.abs(a.box.left - second.blocks.get(j).box.left);
                if (distance <= tolerance && distance < bestDistance) {
                    best = j;
                    bestDistance = distance;
                }
            }
            if (best >= 0) {
                used[best] = true;
                matches++;
            }
        }

        return matches;
    }

    private static boolean looksLikeTextualGrid(@NonNull String rawText) {
        String[] lines = normalizeNewlines(rawText).split("\\n");
        int meaningful = 0;
        int aligned = 0;
        int totalLength = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() < 3) {
                continue;
            }
            meaningful++;
            totalLength += line.length();
            if (MULTI_COLUMN_SPACING.matcher(line).find()) {
                aligned++;
            }
        }

        if (meaningful < 3 || aligned < 3) {
            return false;
        }

        float averageLength = (float) totalLength / meaningful;
        return averageLength <= 120f && aligned >= Math.ceil(meaningful * 0.60f);
    }

    @NonNull
    private static String normalizeContinuousText(@Nullable String rawText) {
        if (rawText == null) {
            return "";
        }

        String[] rawLines = normalizeNewlines(rawText).split("\\n", -1);
        StringBuilder output = new StringBuilder();
        boolean paragraphBreakPending = false;
        boolean previousWasList = false;

        for (String rawLine : rawLines) {
            String line = normalizeInlineWhitespace(rawLine);
            if (line.isEmpty()) {
                paragraphBreakPending = output.length() > 0;
                previousWasList = false;
                continue;
            }

            boolean listLine = LIST_PREFIX.matcher(line).matches();

            if (output.length() == 0) {
                output.append(line);
            } else if (shouldJoinHyphenated(output, line)) {
                output.deleteCharAt(output.length() - 1);
                output.append(line);
            } else if (paragraphBreakPending || listLine || previousWasList) {
                appendParagraphBreak(output);
                output.append(line);
            } else {
                output.append(' ').append(line);
            }

            paragraphBreakPending = false;
            previousWasList = listLine;
        }

        return output.toString().trim();
    }

    private static boolean shouldJoinHyphenated(
            @NonNull StringBuilder output,
            @NonNull String nextLine) {
        if (output.length() < 2 || nextLine.isEmpty()) {
            return false;
        }

        char hyphen = output.charAt(output.length() - 1);
        if (hyphen != '-' && hyphen != '\u2010' && hyphen != '\u00AD') {
            return false;
        }

        char before = output.charAt(output.length() - 2);
        if (!Character.isLetter(before)) {
            return false;
        }

        int firstCodePoint = nextLine.codePointAt(0);
        return Character.isLetter(firstCodePoint) && Character.isLowerCase(firstCodePoint);
    }

    private static void appendParagraphBreak(@NonNull StringBuilder output) {
        while (output.length() > 0
                && Character.isWhitespace(output.charAt(output.length() - 1))) {
            output.deleteCharAt(output.length() - 1);
        }
        if (output.length() > 0) {
            output.append("\n\n");
        }
    }

    @NonNull
    private static String normalizeInlineWhitespace(@NonNull String line) {
        return line.replace('\t', ' ')
                .replaceAll("[\\p{Zs} ]+", " ")
                .trim();
    }

    @NonNull
    private static String normalizeNewlines(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int wordCount(@NonNull String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private static float medianHeight(@NonNull List<Block> blocks) {
        List<Float> heights = new ArrayList<>();
        for (Block block : blocks) {
            if (block.hasBounds && block.box.height() > 0f) {
                heights.add(block.box.height());
            }
        }
        if (heights.isEmpty()) {
            return 10f;
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    @NonNull
    private static RectF unionBounds(@Nullable List<RectF> bounds) {
        RectF union = new RectF();
        if (bounds == null || bounds.isEmpty()) {
            return union;
        }

        boolean initialized = false;
        for (RectF rect : bounds) {
            if (rect == null) {
                continue;
            }
            if (!initialized) {
                union.set(rect);
                initialized = true;
            } else {
                union.union(rect);
            }
        }
        return union;
    }

    private static final class Block {
        final int originalIndex;
        final String rawText;
        final RectF box;
        final boolean hasBounds;
        boolean omit;

        Block(int originalIndex, String rawText, RectF box, boolean hasBounds) {
            this.originalIndex = originalIndex;
            this.rawText = rawText;
            this.box = box;
            this.hasBounds = hasBounds;
        }
    }

    private static final class Row {
        final List<Block> blocks = new ArrayList<>();
        float centerY;

        Row(float centerY) {
            this.centerY = centerY;
        }

        void recalculateCenter() {
            float total = 0f;
            for (Block block : blocks) {
                total += block.box.centerY();
            }
            centerY = blocks.isEmpty() ? centerY : total / blocks.size();
        }
    }

    private static final class PageGeometry {
        final boolean hasUsefulBounds;
        final float minLeft;
        final float maxRight;
        final float minTop;
        final float maxBottom;
        final float width;
        final float height;

        PageGeometry(
                boolean hasUsefulBounds,
                float minLeft,
                float maxRight,
                float minTop,
                float maxBottom) {
            this.hasUsefulBounds = hasUsefulBounds;
            this.minLeft = minLeft;
            this.maxRight = maxRight;
            this.minTop = minTop;
            this.maxBottom = maxBottom;
            this.width = Math.max(0f, maxRight - minLeft);
            this.height = Math.max(0f, maxBottom - minTop);
        }

        @NonNull
        static PageGeometry from(@NonNull List<Block> blocks) {
            float minLeft = Float.MAX_VALUE;
            float maxRight = -Float.MAX_VALUE;
            float minTop = Float.MAX_VALUE;
            float maxBottom = -Float.MAX_VALUE;
            boolean found = false;

            for (Block block : blocks) {
                if (!block.hasBounds) {
                    continue;
                }
                found = true;
                minLeft = Math.min(minLeft, block.box.left);
                maxRight = Math.max(maxRight, block.box.right);
                minTop = Math.min(minTop, block.box.top);
                maxBottom = Math.max(maxBottom, block.box.bottom);
            }

            if (!found) {
                return new PageGeometry(false, 0f, 0f, 0f, 0f);
            }
            return new PageGeometry(true, minLeft, maxRight, minTop, maxBottom);
        }
    }
}
