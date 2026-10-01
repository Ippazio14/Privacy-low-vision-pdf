package org.ippazio.privacylowvisionpdf;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements ReaderPdfFragment.Host {
    private static final String PDF_FRAGMENT_TAG = "pdf_viewer";
    private static final String UI_PREFS = "ui_preferences";
    private static final String PREF_THEME = "theme";

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String THEME_HIGH_CONTRAST = "high_contrast";

    private ReaderPdfFragment pdfViewerFragment;
    private View emptyState;
    private View menuPanel;
    private View menuScrim;
    private View documentMenuControls;
    private View documentMenuActions;
    private TextView zoomValue;
    private RadioGroup themeGroup;

    private final ActivityResultLauncher<String[]> openPdfLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    openPdf(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedThemeBeforeCreate();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyState = findViewById(R.id.empty_state);
        menuPanel = findViewById(R.id.menu_panel);
        menuScrim = findViewById(R.id.menu_scrim);
        documentMenuControls = findViewById(R.id.document_menu_controls);
        documentMenuActions = findViewById(R.id.document_menu_actions);
        zoomValue = findViewById(R.id.zoom_value);
        themeGroup = findViewById(R.id.theme_group);

        Button menuButton = findViewById(R.id.menu_button);
        Button openButton = findViewById(R.id.open_document_button);
        Button searchButton = findViewById(R.id.search_button);
        Button zoomOutButton = findViewById(R.id.zoom_out_button);
        Button zoomInButton = findViewById(R.id.zoom_in_button);
        Button reflowButton = findViewById(R.id.reflow_button);
        Button closeProgramButton = findViewById(R.id.close_program_button);

        pdfViewerFragment = (ReaderPdfFragment) getSupportFragmentManager()
                .findFragmentByTag(PDF_FRAGMENT_TAG);

        menuButton.setOnClickListener(v -> toggleMenu());
        menuScrim.setOnClickListener(v -> closeMenu());

        openButton.setOnClickListener(v -> {
            closeMenu();
            openPdfLauncher.launch(new String[]{"application/pdf"});
        });

        searchButton.setOnClickListener(v -> {
            if (!hasOpenDocument()) {
                return;
            }
            closeMenu();
            pdfViewerFragment.setTextSearchActive(
                    !pdfViewerFragment.isTextSearchActive());
        });

        zoomOutButton.setOnClickListener(v -> {
            if (hasOpenDocument()) {
                pdfViewerFragment.zoomOut();
            }
        });

        zoomInButton.setOnClickListener(v -> {
            if (hasOpenDocument()) {
                pdfViewerFragment.zoomIn();
            }
        });

        reflowButton.setOnClickListener(v ->
                Toast.makeText(this, R.string.reflow_not_ready, Toast.LENGTH_SHORT).show());

        closeProgramButton.setOnClickListener(v -> closeProgram());

        selectSavedThemeRadio();
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String newTheme = themeFromRadioId(checkedId);
            SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            String oldTheme = prefs.getString(PREF_THEME, THEME_SYSTEM);
            if (!newTheme.equals(oldTheme)) {
                prefs.edit().putString(PREF_THEME, newTheme).apply();
                recreate();
            }
        });

        configureMenuWidth();
        updateUiForDocumentState();
        applyPdfThemeToViewer();

        Intent launchIntent = getIntent();
        if (savedInstanceState == null
                && launchIntent != null
                && Intent.ACTION_VIEW.equals(launchIntent.getAction())
                && launchIntent.getData() != null) {
            openPdf(launchIntent.getData());
        }
    }

    private void applySavedThemeBeforeCreate() {
        String theme = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_THEME, THEME_SYSTEM);

        if (THEME_HIGH_CONTRAST.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            setTheme(R.style.Theme_PrivacyLowVisionPdf_HighContrast);
        } else if (THEME_DARK.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (THEME_LIGHT.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private void selectSavedThemeRadio() {
        String theme = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_THEME, THEME_SYSTEM);

        if (THEME_LIGHT.equals(theme)) {
            themeGroup.check(R.id.theme_light);
        } else if (THEME_DARK.equals(theme)) {
            themeGroup.check(R.id.theme_dark);
        } else if (THEME_HIGH_CONTRAST.equals(theme)) {
            themeGroup.check(R.id.theme_high_contrast);
        } else {
            themeGroup.check(R.id.theme_system);
        }
    }

    private String themeFromRadioId(int checkedId) {
        if (checkedId == R.id.theme_light) {
            return THEME_LIGHT;
        }
        if (checkedId == R.id.theme_dark) {
            return THEME_DARK;
        }
        if (checkedId == R.id.theme_high_contrast) {
            return THEME_HIGH_CONTRAST;
        }
        return THEME_SYSTEM;
    }

    private void configureMenuWidth() {
        menuPanel.post(() -> {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int margin = dp(24);
            int maximum = dp(360);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
            params.width = Math.max(dp(240), Math.min(maximum, screenWidth - margin));
            menuPanel.setLayoutParams(params);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleMenu() {
        if (menuPanel.getVisibility() == View.VISIBLE) {
            closeMenu();
        } else {
            menuScrim.setVisibility(View.VISIBLE);
            menuPanel.setVisibility(View.VISIBLE);
        }
    }

    private void closeMenu() {
        menuPanel.setVisibility(View.GONE);
        menuScrim.setVisibility(View.GONE);
    }

    private boolean hasOpenDocument() {
        return pdfViewerFragment != null && pdfViewerFragment.getDocumentUri() != null;
    }

    private void updateUiForDocumentState() {
        boolean open = hasOpenDocument();
        emptyState.setVisibility(open ? View.GONE : View.VISIBLE);
        documentMenuControls.setVisibility(open ? View.VISIBLE : View.GONE);
        documentMenuActions.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    private void ensurePdfViewer() {
        if (pdfViewerFragment != null) {
            return;
        }

        pdfViewerFragment = new ReaderPdfFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.pdf_container, pdfViewerFragment, PDF_FRAGMENT_TAG)
                .commitNow();

        pdfViewerFragment.setToolboxVisible(false);
        applyPdfThemeToViewer();
    }

    private void openPdf(Uri uri) {
        closeMenu();

        new Thread(() -> {
            DocumentCheck check = checkDocument(uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (check == DocumentCheck.PROTECTED) {
                    showDocumentRejected(R.string.protected_document_message);
                    return;
                }
                if (check == DocumentCheck.UNREADABLE) {
                    showDocumentRejected(R.string.unreadable_document_message);
                    return;
                }

                try {
                    ensurePdfViewer();
                    pdfViewerFragment.setDocumentUri(uri);
                    emptyState.setVisibility(View.GONE);
                    updateUiForDocumentState();
                } catch (RuntimeException error) {
                    showDocumentRejected(R.string.unreadable_document_message);
                }
            });
        }, "pdf-check").start();
    }

    private DocumentCheck checkDocument(Uri uri) {
        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        try {
            descriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) {
                return DocumentCheck.UNREADABLE;
            }

            renderer = new PdfRenderer(descriptor);
            descriptor = null;
            renderer.close();
            renderer = null;
            return DocumentCheck.OK;
        } catch (SecurityException error) {
            return DocumentCheck.PROTECTED;
        } catch (IOException | IllegalArgumentException error) {
            return DocumentCheck.UNREADABLE;
        } catch (RuntimeException error) {
            return DocumentCheck.UNREADABLE;
        } finally {
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void showDocumentRejected(int messageRes) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cannot_open_document)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void closeProgram() {
        closeMenu();
        finishAndRemoveTask();
    }

    private void applyPdfThemeToViewer() {
        if (pdfViewerFragment == null) {
            return;
        }

        String theme = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_THEME, THEME_SYSTEM);
        if (THEME_DARK.equals(theme)) {
            pdfViewerFragment.setPdfTheme(ReaderPdfFragment.PDF_THEME_DARK);
        } else if (THEME_HIGH_CONTRAST.equals(theme)) {
            pdfViewerFragment.setPdfTheme(ReaderPdfFragment.PDF_THEME_HIGH_CONTRAST);
        } else {
            pdfViewerFragment.setPdfTheme(ReaderPdfFragment.PDF_THEME_ORIGINAL);
        }
    }

    @Override
    public void onPdfZoomChanged(int percent) {
        zoomValue.setText(getString(R.string.zoom_percent, percent));
    }

    @Override
    public void onPdfLoaded() {
        updateUiForDocumentState();
        applyPdfThemeToViewer();
    }

    @Override
    public void onPdfLoadError() {
        showDocumentRejected(R.string.unreadable_document_message);
    }

    @Override
    public void onBackPressed() {
        if (menuPanel != null && menuPanel.getVisibility() == View.VISIBLE) {
            closeMenu();
            return;
        }
        super.onBackPressed();
    }

    private enum DocumentCheck {
        OK,
        PROTECTED,
        UNREADABLE
    }
}
