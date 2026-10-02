package org.ippazio.privacylowvisionpdf;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.io.IOException;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ReaderPdfFragment.Host {
    private static final String PDF_FRAGMENT_TAG = "pdf_viewer";
    private static final String UI_PREFS = "ui_preferences";
    private static final String PREF_THEME = "theme";
    private static final String PREF_REFLOW_TEXT_SIZE = "reflow_text_size";

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String THEME_HIGH_CONTRAST = "high_contrast";

    private static final int REFLOW_TEXT_SIZE_DEFAULT = 26;
    private static final int REFLOW_TEXT_SIZE_MIN = 18;
    private static final int REFLOW_TEXT_SIZE_MAX = 52;
    private static final int REFLOW_TEXT_SIZE_STEP = 2;

    private static final String[] LANGUAGE_TAGS = {
            "", "en", "it", "fr", "es", "de", "hr", "ru", "uk", "ar", "zh-CN", "ja"
    };

    private static final String[] LANGUAGE_LABELS = {
            null,
            "🇬🇧 English",
            "🇮🇹 Italiano",
            "🇫🇷 Français",
            "🇪🇸 Español",
            "🇩🇪 Deutsch",
            "🇭🇷 Hrvatski",
            "🇷🇺 Русский",
            "🇺🇦 Українська",
            "🇸🇦 العربية",
            "🇨🇳 简体中文",
            "🇯🇵 日本語"
    };

    private ReaderPdfFragment pdfViewerFragment;
    private View emptyState;
    private View menuPanel;
    private View menuScrim;
    private View documentOpenRow;
    private View documentMenuControls;
    private View documentMenuActions;
    private View reflowMenuControls;
    private View searchButton;
    private View pdfContainer;
    private View themeTitle;
    private ScrollView reflowContainer;
    private TextView reflowText;
    private TextView reflowFontValue;
    private TextView zoomValue;
    private RadioGroup themeGroup;
    private Button languageButton;

    private boolean reflowMode;
    private int reflowFontSp;
    private int reflowRequestId;

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
        documentOpenRow = findViewById(R.id.document_open_row);
        documentMenuControls = findViewById(R.id.document_menu_controls);
        documentMenuActions = findViewById(R.id.document_menu_actions);
        reflowMenuControls = findViewById(R.id.reflow_menu_controls);
        searchButton = findViewById(R.id.search_button);
        pdfContainer = findViewById(R.id.pdf_container);
        themeTitle = findViewById(R.id.theme_title);
        reflowContainer = findViewById(R.id.reflow_container);
        reflowText = findViewById(R.id.reflow_text);
        reflowFontValue = findViewById(R.id.reflow_font_value);
        zoomValue = findViewById(R.id.zoom_value);
        themeGroup = findViewById(R.id.theme_group);
        languageButton = findViewById(R.id.language_button);

        Button menuButton = findViewById(R.id.menu_button);
        Button openButton = findViewById(R.id.open_document_button);
        Button zoomOutButton = findViewById(R.id.zoom_out_button);
        Button zoomInButton = findViewById(R.id.zoom_in_button);
        Button reflowButton = findViewById(R.id.reflow_button);
        Button reflowFontSmallerButton = findViewById(R.id.reflow_font_smaller_button);
        Button reflowFontLargerButton = findViewById(R.id.reflow_font_larger_button);
        Button exitReflowButton = findViewById(R.id.exit_reflow_button);
        Button closeProgramButton = findViewById(R.id.close_program_button);

        pdfViewerFragment = (ReaderPdfFragment) getSupportFragmentManager()
                .findFragmentByTag(PDF_FRAGMENT_TAG);

        reflowFontSp = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getInt(PREF_REFLOW_TEXT_SIZE, REFLOW_TEXT_SIZE_DEFAULT);
        reflowFontSp = Math.max(REFLOW_TEXT_SIZE_MIN, Math.min(REFLOW_TEXT_SIZE_MAX, reflowFontSp));
        applyReflowFontSize();
        reflowFontSmallerButton.setContentDescription(getString(R.string.font_size) + " −");
        reflowFontLargerButton.setContentDescription(getString(R.string.font_size) + " +");

        menuButton.setOnClickListener(v -> toggleMenu());
        menuScrim.setOnClickListener(v -> closeMenu());

        openButton.setOnClickListener(v -> {
            closeMenu();
            openPdfLauncher.launch(new String[]{"application/pdf"});
        });

        searchButton.setOnClickListener(v -> {
            if (!hasOpenDocument() || reflowMode) {
                return;
            }

            boolean activateSearch = !pdfViewerFragment.isTextSearchActive();
            closeMenu();

            if (!activateSearch) {
                pdfViewerFragment.setTextSearchActive(false);
                return;
            }

            // Wait until the menu has been removed from the current layout pass before showing
            // AndroidX's search bar. On first use, activating it in the same frame as closing the
            // menu can leave the search view temporarily laid out around mid-screen.
            pdfContainer.postOnAnimation(() ->
                    pdfContainer.postOnAnimation(() -> {
                        if (hasOpenDocument() && !reflowMode
                                && !pdfViewerFragment.isTextSearchActive()) {
                            pdfViewerFragment.setTextSearchActive(true);
                        }
                    }));
        });

        zoomOutButton.setOnClickListener(v -> {
            if (hasOpenDocument() && !reflowMode) {
                pdfViewerFragment.zoomOut();
            }
        });

        zoomInButton.setOnClickListener(v -> {
            if (hasOpenDocument() && !reflowMode) {
                pdfViewerFragment.zoomIn();
            }
        });

        reflowButton.setOnClickListener(v -> enterReflowMode());
        reflowFontSmallerButton.setOnClickListener(v -> changeReflowFontSize(-REFLOW_TEXT_SIZE_STEP));
        reflowFontLargerButton.setOnClickListener(v -> changeReflowFontSize(REFLOW_TEXT_SIZE_STEP));
        exitReflowButton.setOnClickListener(v -> exitReflowMode());

        closeProgramButton.setOnClickListener(v -> closeProgram());
        languageButton.setOnClickListener(v -> showLanguageChooser());

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
        updateLanguageButton();

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
            setTheme(R.style.Theme_PrivacyLowVisionPdf);
        } else if (THEME_LIGHT.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            setTheme(R.style.Theme_PrivacyLowVisionPdf);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            setTheme(R.style.Theme_PrivacyLowVisionPdf);
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

        if (reflowMode) {
            emptyState.setVisibility(View.GONE);
            pdfContainer.setVisibility(View.GONE);
            reflowContainer.setVisibility(View.VISIBLE);
            documentOpenRow.setVisibility(View.GONE);
            searchButton.setVisibility(View.GONE);
            documentMenuControls.setVisibility(View.GONE);
            documentMenuActions.setVisibility(View.GONE);
            themeTitle.setVisibility(View.GONE);
            themeGroup.setVisibility(View.GONE);
            languageButton.setVisibility(View.GONE);
            reflowMenuControls.setVisibility(View.VISIBLE);
            return;
        }

        reflowContainer.setVisibility(View.GONE);
        pdfContainer.setVisibility(View.VISIBLE);
        emptyState.setVisibility(open ? View.GONE : View.VISIBLE);
        documentOpenRow.setVisibility(View.VISIBLE);
        searchButton.setVisibility(open ? View.VISIBLE : View.GONE);
        documentMenuControls.setVisibility(open ? View.VISIBLE : View.GONE);
        documentMenuActions.setVisibility(open ? View.VISIBLE : View.GONE);
        themeTitle.setVisibility(View.VISIBLE);
        themeGroup.setVisibility(View.VISIBLE);
        languageButton.setVisibility(View.VISIBLE);
        reflowMenuControls.setVisibility(View.GONE);
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
        if (reflowMode) {
            exitReflowMode();
        }
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

    private void enterReflowMode() {
        if (!hasOpenDocument() || pdfViewerFragment == null) {
            return;
        }

        if (pdfViewerFragment.isTextSearchActive()) {
            pdfViewerFragment.setTextSearchActive(false);
        }

        closeMenu();
        reflowMode = true;
        int requestId = ++reflowRequestId;
        reflowText.setText(R.string.reflow_loading);
        reflowContainer.scrollTo(0, 0);
        updateUiForDocumentState();

        pdfViewerFragment.extractReflowText(new ReaderPdfFragment.ReflowTextCallback() {
            @Override
            public void onTextReady(@androidx.annotation.NonNull String text) {
                runOnUiThread(() -> {
                    if (!reflowMode || requestId != reflowRequestId
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    reflowText.setText(text.isEmpty() ? getString(R.string.reflow_no_text) : text);
                    reflowContainer.scrollTo(0, 0);
                });
            }

            @Override
            public void onError(@androidx.annotation.NonNull Throwable error) {
                runOnUiThread(() -> {
                    if (!reflowMode || requestId != reflowRequestId
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    reflowText.setText(R.string.reflow_no_text);
                    reflowContainer.scrollTo(0, 0);
                });
            }
        });
    }

    private void exitReflowMode() {
        if (!reflowMode) {
            return;
        }

        reflowMode = false;
        reflowRequestId++;
        if (pdfViewerFragment != null) {
            pdfViewerFragment.cancelReflowExtraction();
        }
        reflowText.setText("");
        updateUiForDocumentState();
        closeMenu();
    }

    private void changeReflowFontSize(int delta) {
        int newSize = Math.max(
                REFLOW_TEXT_SIZE_MIN,
                Math.min(REFLOW_TEXT_SIZE_MAX, reflowFontSp + delta));
        if (newSize == reflowFontSp) {
            return;
        }

        reflowFontSp = newSize;
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_REFLOW_TEXT_SIZE, reflowFontSp)
                .apply();
        applyReflowFontSize();
    }

    private void applyReflowFontSize() {
        if (reflowText != null) {
            reflowText.setTextSize(TypedValue.COMPLEX_UNIT_SP, reflowFontSp);
        }
        if (reflowFontValue != null) {
            reflowFontValue.setText(reflowFontSp + " sp");
        }
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

    private void showLanguageChooser() {
        String[] items = new String[LANGUAGE_TAGS.length];
        items[0] = "🌐 " + getString(R.string.language_system);
        for (int i = 1; i < items.length; i++) {
            items[i] = LANGUAGE_LABELS[i];
        }

        int selected = selectedLanguageIndex();
        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(items, selected, (dialog, which) -> {
                    dialog.dismiss();
                    closeMenu();

                    LocaleListCompat locales = which == 0
                            ? LocaleListCompat.getEmptyLocaleList()
                            : LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[which]);
                    AppCompatDelegate.setApplicationLocales(locales);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int selectedLanguageIndex() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) {
            return 0;
        }
        return languageIndexForLocale(locales.get(0));
    }

    private void updateLanguageButton() {
        LocaleListCompat selected = AppCompatDelegate.getApplicationLocales();
        Locale locale;
        if (selected.isEmpty()) {
            locale = getResources().getConfiguration().getLocales().get(0);
        } else {
            locale = selected.get(0);
        }

        int index = languageIndexForLocale(locale);
        if (index > 0) {
            languageButton.setText(LANGUAGE_LABELS[index]);
        } else {
            languageButton.setText("🌐 " + getString(R.string.language_system));
        }
    }

    private int languageIndexForLocale(Locale locale) {
        if (locale == null) {
            return 0;
        }

        String language = locale.getLanguage();
        for (int i = 1; i < LANGUAGE_TAGS.length; i++) {
            Locale supported = Locale.forLanguageTag(LANGUAGE_TAGS[i]);
            if (!supported.getLanguage().equalsIgnoreCase(language)) {
                continue;
            }

            if ("zh".equalsIgnoreCase(language)) {
                String script = locale.getScript();
                String country = locale.getCountry();
                if ("Hans".equalsIgnoreCase(script)
                        || country.isEmpty()
                        || "CN".equalsIgnoreCase(country)
                        || "SG".equalsIgnoreCase(country)) {
                    return i;
                }
                continue;
            }

            return i;
        }
        return 0;
    }

    private void closeProgram() {
        closeMenu();
        if (pdfViewerFragment != null) {
            pdfViewerFragment.cancelReflowExtraction();
        }
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
        } else if (THEME_SYSTEM.equals(theme) && isNightModeActive()) {
            pdfViewerFragment.setPdfTheme(ReaderPdfFragment.PDF_THEME_DARK);
        } else {
            pdfViewerFragment.setPdfTheme(ReaderPdfFragment.PDF_THEME_ORIGINAL);
        }
    }

    private boolean isNightModeActive() {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
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
        if (reflowMode) {
            exitReflowMode();
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
