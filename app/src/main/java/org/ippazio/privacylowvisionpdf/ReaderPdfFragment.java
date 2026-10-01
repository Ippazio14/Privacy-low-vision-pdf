package org.ippazio.privacylowvisionpdf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.pdf.PdfDocument;
import androidx.pdf.PdfPoint;
import androidx.pdf.content.ExternalLink;
import androidx.pdf.view.PdfView;
import androidx.pdf.viewer.fragment.PdfViewerFragment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ReaderPdfFragment extends PdfViewerFragment {
    public static final int PDF_THEME_ORIGINAL = 0;
    public static final int PDF_THEME_DARK = 1;
    public static final int PDF_THEME_HIGH_CONTRAST = 2;

    private static final String READING_PREFS = "reading_positions";

    public interface Host {
        void onPdfZoomChanged(int percent);
        void onPdfLoaded();
        void onPdfLoadError();
    }

    private Host host;
    private PdfView pdfView;
    private int pdfTheme = PDF_THEME_ORIGINAL;
    private String readingKey;

    private final PdfView.OnViewportChangedListener viewportListener =
            (firstVisiblePage, visiblePagesCount, pageLocations, zoomLevel) -> {
                if (host != null) {
                    host.onPdfZoomChanged(Math.max(1, Math.round(zoomLevel * 100f)));
                }
            };

    private final PdfView.OnGestureStateChangedListener gestureListener = newState -> {
        if (newState == PdfView.GESTURE_STATE_IDLE) {
            saveReadingPosition();
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Host) {
            host = (Host) context;
        }
    }

    @Override
    public void onDetach() {
        host = null;
        super.onDetach();
    }

    @Override
    public void onPdfViewCreated(@NonNull PdfView view) {
        pdfView = view;
        pdfView.setFormFillingEnabled(false);
        pdfView.addOnViewportChangedListener(viewportListener);
        pdfView.addOnGestureStateChangedListener(gestureListener);
        applyPdfTheme();
    }

    @Override
    public void onLoadDocumentSuccess(@NonNull PdfDocument document) {
        super.onLoadDocumentSuccess(document);
        setToolboxVisible(false);
        readingKey = keyForUri(document.getUri());

        if (pdfView != null) {
            pdfView.post(this::restoreReadingPosition);
        }

        if (host != null) {
            host.onPdfLoaded();
            if (pdfView != null) {
                host.onPdfZoomChanged(Math.max(1, Math.round(pdfView.getZoom() * 100f)));
            }
        }
    }

    @Override
    public void onLoadDocumentError(@NonNull Throwable error) {
        super.onLoadDocumentError(error);
        if (host != null) {
            host.onPdfLoadError();
        }
    }

    @Override
    public void onRequestImmersiveMode(boolean enterImmersive) {
        // Privacy Low Vision PDF deliberately keeps its single menu button visible.
        setToolboxVisible(false);
    }

    @Override
    protected boolean onLinkClicked(@NonNull ExternalLink externalLink) {
        Uri target = externalLink.getUri();
        Context context = getContext();
        if (context == null || target == null) {
            return true;
        }

        String scheme = target.getScheme();
        boolean allowed = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "mailto".equalsIgnoreCase(scheme);

        if (!allowed) {
            Toast.makeText(context, R.string.link_not_supported, Toast.LENGTH_SHORT).show();
            return true;
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.open_link_title)
                .setMessage(context.getString(R.string.open_link_message, target.toString()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_link, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    } catch (RuntimeException error) {
                        Toast.makeText(context,
                                R.string.no_app_for_link,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();

        return true;
    }

    public void setPdfTheme(int theme) {
        pdfTheme = theme;
        applyPdfTheme();
    }

    public void zoomIn() {
        changeZoom(1.20f);
    }

    public void zoomOut() {
        changeZoom(1f / 1.20f);
    }

    private void changeZoom(float factor) {
        if (pdfView == null || getDocumentUri() == null) {
            return;
        }

        float current = pdfView.getZoom();
        float target = current * factor;
        target = Math.max(pdfView.getMinZoom(), Math.min(pdfView.getMaxZoom(), target));
        pdfView.setZoom(target);

        if (host != null) {
            host.onPdfZoomChanged(Math.max(1, Math.round(target * 100f)));
        }
    }

    private void applyPdfTheme() {
        if (pdfView == null) {
            return;
        }

        if (pdfTheme == PDF_THEME_ORIGINAL) {
            // Leave the AndroidX PdfView in its native rendering mode. Forcing a layer type here
            // interfered with text-selection gestures in the light theme on real devices.
            pdfView.invalidate();
            return;
        }

        ColorMatrix matrix;
        if (pdfTheme == PDF_THEME_DARK) {
            matrix = new ColorMatrix(new float[]{
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
            });
        } else {
            float contrast = 1.8f;
            float offset = 128f * (1f - contrast);
            float r = 0.2126f * contrast;
            float g = 0.7152f * contrast;
            float b = 0.0722f * contrast;
            matrix = new ColorMatrix(new float[]{
                    r, g, b, 0f, offset,
                    r, g, b, 0f, offset,
                    r, g, b, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
            });
        }

        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        pdfView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        pdfView.invalidate();
    }

    private void saveReadingPosition() {
        if (pdfView == null || readingKey == null || getContext() == null) {
            return;
        }

        PdfPoint center = pdfView.viewToPdfPoint(
                pdfView.getWidth() / 2f,
                pdfView.getHeight() / 2f);

        SharedPreferences.Editor editor = getContext()
                .getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(readingKey + "_zoom", pdfView.getZoom());

        if (center != null) {
            editor.putInt(readingKey + "_page", center.getPageNum())
                    .putFloat(readingKey + "_x", center.getX())
                    .putFloat(readingKey + "_y", center.getY());
        } else {
            editor.putInt(readingKey + "_page", pdfView.getFirstVisiblePage())
                    .putFloat(readingKey + "_x", 0f)
                    .putFloat(readingKey + "_y", 0f);
        }

        editor.apply();
    }

    private void restoreReadingPosition() {
        if (pdfView == null || readingKey == null || getContext() == null) {
            return;
        }

        SharedPreferences prefs = getContext()
                .getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE);
        int page = prefs.getInt(readingKey + "_page", -1);
        if (page < 0) {
            return;
        }

        float savedZoom = prefs.getFloat(readingKey + "_zoom", pdfView.getZoom());
        savedZoom = Math.max(pdfView.getMinZoom(), Math.min(pdfView.getMaxZoom(), savedZoom));
        float x = prefs.getFloat(readingKey + "_x", 0f);
        float y = prefs.getFloat(readingKey + "_y", 0f);

        try {
            pdfView.setZoom(savedZoom);
            pdfView.scrollToPosition(new PdfPoint(page, x, y));
        } catch (RuntimeException ignored) {
            try {
                pdfView.scrollToPage(page);
            } catch (RuntimeException ignoredAgain) {
                // The viewer will simply keep its default opening position.
            }
        }
    }

    @Override
    public void onPause() {
        saveReadingPosition();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (pdfView != null) {
            pdfView.removeOnViewportChangedListener(viewportListener);
            pdfView.removeOnGestureStateChangedListener(gestureListener);
            pdfView = null;
        }
        super.onDestroyView();
    }

    private static String keyForUri(@Nullable Uri uri) {
        String value = uri == null ? "" : uri.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
