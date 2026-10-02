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

            String text = normalizeContinuousText(block);
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

            List<RectF> sourceBounds = textContent.getBounds();
            List<RectF> lineBounds = sourceBounds == null
                    ? Collections.emptyList()
                    : new ArrayList<>(sourceBounds);
            RectF union = unionBounds(lineBounds);
            Block block = new Block(
                    index,
                    rawText,
                    union,
                    !lineBounds.isEmpty(),
                    lineBounds);

            if (looksLikeTextualGrid(rawText)) {
                block.omit = true;
            }

            blocks.add(block);
            index++;
        }

        blocks.sort(Comparator.comparingInt(block -> block.originalIndex));
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

            if (similarRows >= 2) {
                mark[i] = true;
            }
        }

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
                    if (Math.abs(printed - physical) <= 5) {
                        block.omit = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if (ROMAN_PAGE_NUMBER.matcher(text).matches()) {
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
    private static String normalizeContinuousText(@NonNull Block block) {
        List<String> sourceLines = buildSourceLines(block);
        StringBuilder output = new StringBuilder();

        boolean paragraphBreakPending = false;
        boolean previousWasList = false;
        String previousLine = null;
        RectF previousRect = null;
        int previousLineLength = 0;
        int boundsIndex = 0;

        float typicalHeight = medianRectHeight(block.lineBounds);
        float typicalGap = medianLineGap(block.lineBounds, typicalHeight);
        float leftEdge = block.box.left;
        float blockWidth = Math.max(1f, block.box.width());
        int typicalLineLength = medianLineLength(sourceLines);

        for (String rawLine : sourceLines) {
            int leadingWhitespace = leadingWhitespaceCount(rawLine);
            String line = normalizeInlineWhitespace(rawLine);
            if (line.isEmpty()) {
                paragraphBreakPending = output.length() > 0;
                previousWasList = false;
                continue;
            }

            RectF currentRect = boundsIndex < block.lineBounds.size()
                    ? block.lineBounds.get(boundsIndex)
                    : null;
            boundsIndex++;

            boolean listLine = LIST_PREFIX.matcher(line).matches();
            boolean geometryParagraph = shouldStartParagraphFromGeometry(
                    previousLine,
                    line,
                    previousRect,
                    currentRect,
                    leftEdge,
                    blockWidth,
                    typicalHeight,
                    typicalGap);
            boolean textParagraph = shouldStartParagraphFromText(
                    previousLine,
                    line,
                    previousLineLength,
                    leadingWhitespace,
                    typicalLineLength);
            boolean paragraphBreak = paragraphBreakPending
                    || listLine
                    || previousWasList
                    || geometryParagraph
                    || textParagraph;

            if (output.length() == 0) {
                output.append(line);
            } else if (paragraphBreak) {
                appendParagraphBreak(output);
                output.append(line);
            } else if (shouldJoinHyphenated(output, line)) {
                output.deleteCharAt(output.length() - 1);
                output.append(line);
            } else {
                output.append(' ').append(line);
            }

            paragraphBreakPending = false;
            previousWasList = listLine;
            previousLine = line;
            previousLineLength = line.length();
            previousRect = currentRect;
        }

        return output.toString().trim();
    }

    @NonNull
    private static List<String> buildSourceLines(@NonNull Block block) {
        String normalized = normalizeNewlines(block.rawText);
        List<String> lines = new ArrayList<>();

        if (normalized.indexOf('\n') >= 0 || block.lineBounds.size() <= 1) {
            String[] split = normalized.split("\\n", -1);
            Collections.addAll(lines, split);
            return lines;
        }

        return approximateLinesFromBounds(normalized, block.lineBounds);
    }

    @NonNull
    private static List<String> approximateLinesFromBounds(
            @NonNull String text,
            @NonNull List<RectF> bounds) {
        String compact = normalizeInlineWhitespace(text);
        List<String> lines = new ArrayList<>();
        if (compact.isEmpty() || bounds.size() <= 1) {
            lines.add(compact);
            return lines;
        }

        int cursor = 0;
        for (int i = 0; i < bounds.size() - 1 && cursor < compact.length(); i++) {
            float remainingWidth = 0f;
            for (int j = i; j < bounds.size(); j++) {
                RectF rect = bounds.get(j);
                remainingWidth += rect == null ? 1f : Math.max(1f, rect.width());
            }

            RectF current = bounds.get(i);
            float currentWidth = current == null ? 1f : Math.max(1f, current.width());
            int remainingCharacters = compact.length() - cursor;
            int targetCharacters = Math.max(
                    1,
                    Math.round(remainingCharacters * (currentWidth / remainingWidth)));
            int ideal = Math.min(compact.length() - 1, cursor + targetCharacters);
            int breakAt = nearestWhitespace(compact, cursor, ideal);

            if (breakAt <= cursor || breakAt >= compact.length()) {
                break;
            }

            lines.add(compact.substring(cursor, breakAt).trim());
            cursor = breakAt;
            while (cursor < compact.length() && Character.isWhitespace(compact.charAt(cursor))) {
                cursor++;
            }
        }

        if (cursor < compact.length()) {
            lines.add(compact.substring(cursor).trim());
        }

        if (lines.isEmpty()) {
            lines.add(compact);
        }
        return lines;
    }

    private static int nearestWhitespace(
            @NonNull String text,
            int start,
            int ideal) {
        int remaining = text.length() - start;
        int searchRadius = Math.max(8, Math.min(40, remaining / 5));

        for (int distance = 0; distance <= searchRadius; distance++) {
            int left = ideal - distance;
            if (left > start && left < text.length() && Character.isWhitespace(text.charAt(left))) {
                return left;
            }

            int right = ideal + distance;
            if (right > start && right < text.length() && Character.isWhitespace(text.charAt(right))) {
                return right;
            }
        }

        for (int i = ideal; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return text.length();
    }

    private static boolean shouldStartParagraphFromGeometry(
            @Nullable String previousLine,
            @NonNull String currentLine,
            @Nullable RectF previousRect,
            @Nullable RectF currentRect,
            float leftEdge,
            float blockWidth,
            float typicalHeight,
            float typicalGap) {
        if (previousLine == null || previousRect == null || currentRect == null) {
            return false;
        }

        float lineHeight = typicalHeight > 0f
                ? typicalHeight
                : Math.max(previousRect.height(), currentRect.height());
        if (lineHeight <= 0f) {
            lineHeight = 10f;
        }

        float verticalGap = currentRect.top - previousRect.bottom;
        float largeGapThreshold = typicalGap > 0f
                ? Math.max(lineHeight * 0.35f, typicalGap * 1.45f + 0.5f)
                : lineHeight * 0.45f;
        if (verticalGap > largeGapThreshold) {
            return true;
        }

        float indentThreshold = Math.max(6f, lineHeight * 0.50f);
        boolean currentIndented = currentRect.left - leftEdge > indentThreshold;
        boolean previousAtBodyEdge = previousRect.left - leftEdge <= indentThreshold * 0.60f;
        if (currentIndented && previousAtBodyEdge) {
            return true;
        }

        boolean previousLineShort = previousRect.width() < blockWidth * 0.88f;
        boolean currentAtBodyEdge = currentRect.left - leftEdge <= indentThreshold;
        return previousLineShort
                && currentAtBodyEdge
                && endsParagraphPunctuation(previousLine)
                && looksLikeParagraphStart(currentLine);
    }

    private static boolean shouldStartParagraphFromText(
            @Nullable String previousLine,
            @NonNull String currentLine,
            int previousLineLength,
            int currentLeadingWhitespace,
            int typicalLineLength) {
        if (previousLine == null || previousLine.isEmpty()) {
            return false;
        }

        if (currentLeadingWhitespace >= 2 && looksLikeParagraphStart(currentLine)) {
            return true;
        }

        if (!endsParagraphPunctuation(previousLine) || !looksLikeParagraphStart(currentLine)) {
            return false;
        }

        if (typicalLineLength <= 0) {
            return previousLineLength <= 80;
        }

        return previousLineLength <= Math.max(24, Math.round(typicalLineLength * 0.90f));
    }

    private static boolean looksLikeParagraphStart(@NonNull String line) {
        if (line.isEmpty()) {
            return false;
        }

        int index = 0;
        while (index < line.length()) {
            int codePoint = line.codePointAt(index);
            if (Character.isLetterOrDigit(codePoint)) {
                return Character.isUpperCase(codePoint)
                        || Character.isTitleCase(codePoint)
                        || Character.isDigit(codePoint);
            }

            if (codePoint == '“'
                    || codePoint == '"'
                    || codePoint == '«'
                    || codePoint == '‘'
                    || codePoint == '\''
                    || codePoint == '('
                    || codePoint == '[') {
                index += Character.charCount(codePoint);
                continue;
            }
            return false;
        }
        return false;
    }

    private static boolean endsParagraphPunctuation(@NonNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.'
                || last == '!'
                || last == '?'
                || last == ':'
                || last == ';'
                || last == '。'
                || last == '！'
                || last == '？'
                || last == '…'
                || last == '”'
                || last == '»';
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
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u2028', '\n')
                .replace("\u2029", "\n\n")
                .replace('\u0085', '\n')
                .replace('\f', '\n');
    }

    private static int leadingWhitespaceCount(@NonNull String line) {
        int count = 0;
        while (count < line.length()) {
            char value = line.charAt(count);
            if (value != ' ' && value != '\t') {
                break;
            }
            count++;
        }
        return count;
    }

    private static int medianLineLength(@NonNull List<String> lines) {
        List<Integer> lengths = new ArrayList<>();
        for (String line : lines) {
            String normalized = normalizeInlineWhitespace(line);
            if (normalized.length() >= 8) {
                lengths.add(normalized.length());
            }
        }
        if (lengths.isEmpty()) {
            return 0;
        }
        Collections.sort(lengths);
        return lengths.get(lengths.size() / 2);
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

    private static float medianRectHeight(@NonNull List<RectF> bounds) {
        List<Float> heights = new ArrayList<>();
        for (RectF rect : bounds) {
            if (rect != null && rect.height() > 0f) {
                heights.add(rect.height());
            }
        }
        if (heights.isEmpty()) {
            return 0f;
        }
        Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    private static float medianLineGap(
            @NonNull List<RectF> bounds,
            float typicalHeight) {
        if (bounds.size() < 2) {
            return 0f;
        }

        List<Float> gaps = new ArrayList<>();
        float maximumUsefulGap = typicalHeight > 0f ? typicalHeight * 1.5f : 30f;
        for (int i = 1; i < bounds.size(); i++) {
            RectF previous = bounds.get(i - 1);
            RectF current = bounds.get(i);
            if (previous == null || current == null) {
                continue;
            }
            float gap = current.top - previous.bottom;
            if (gap >= 0f && gap <= maximumUsefulGap) {
                gaps.add(gap);
            }
        }

        if (gaps.isEmpty()) {
            return 0f;
        }
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
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
        final List<RectF> lineBounds;
        boolean omit;

        Block(
                int originalIndex,
                String rawText,
                RectF box,
                boolean hasBounds,
                List<RectF> lineBounds) {
            this.originalIndex = originalIndex;
            this.rawText = rawText;
            this.box = box;
            this.hasBounds = hasBounds;
            this.lineBounds = lineBounds;
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
