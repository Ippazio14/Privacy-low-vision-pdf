package org.ippazio.privacylowvisionpdf;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
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
    private static final String STATE_REFLOW_MODE = "state_reflow_mode";
    private static final String STATE_REFLOW_PAGE = "state_reflow_page";

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String THEME_HIGH_CONTRAST = "high_contrast";

    private static final int REFLOW_TEXT_SIZE_DEFAULT = 26;
    private static final int REFLOW_TEXT_SIZE_MIN = 18;
    private static final int REFLOW_TEXT_SIZE_MAX = 52;
    private static final int REFLOW_TEXT_SIZE_STEP = 2;
    private static final int REFLOW_PREFETCH_PAGES = 2;

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
    private View documentMenuControls;
    private View documentMenuActions;
    private View searchButton;
    private View pdfContainer;
    private View themeTitle;
    private ReflowListView reflowList;
    private RadioGroup themeGroup;
    private Button languageButton;
    private Button zoomOutButton;
    private Button zoomInButton;
    private CheckBox reflowCheckBox;

    private final SparseArray<String> reflowPageText = new SparseArray<>();
    private final SparseBooleanArray reflowPageLoading = new SparseBooleanArray();
    private ReflowPageAdapter reflowAdapter;

    private boolean reflowMode;
    private int reflowFontSp;
    private int reflowRequestId;
    private int reflowPageCount;
    private boolean restoreReflowAfterLoad;
    private int restoreReflowPage;

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
        searchButton = findViewById(R.id.search_button);
        pdfContainer = findViewById(R.id.pdf_container);
        themeTitle = findViewById(R.id.theme_title);
        reflowList = findViewById(R.id.reflow_list);
        themeGroup = findViewById(R.id.theme_group);
        languageButton = findViewById(R.id.language_button);
        reflowCheckBox = findViewById(R.id.reflow_checkbox);
        zoomOutButton = findViewById(R.id.zoom_out_button);
        zoomInButton = findViewById(R.id.zoom_in_button);

        Button menuButton = findViewById(R.id.menu_button);
        Button openButton = findViewById(R.id.open_document_button);
        Button exitButton = findViewById(R.id.exit_program_button);

        pdfViewerFragment = (ReaderPdfFragment) getSupportFragmentManager()
                .findFragmentByTag(PDF_FRAGMENT_TAG);

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_REFLOW_MODE, false)) {
            restoreReflowAfterLoad = true;
            restoreReflowPage = savedInstanceState.getInt(STATE_REFLOW_PAGE, 0);
        }

        reflowAdapter = new ReflowPageAdapter();
        reflowList.setAdapter(reflowAdapter);
        reflowList.setItemsCanFocus(true);
        reflowList.setOnScaleStepListener(direction ->
                changeReflowFontSize(direction * REFLOW_TEXT_SIZE_STEP));
        reflowList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(
                    AbsListView view,
                    int firstVisibleItem,
                    int visibleItemCount,
                    int totalItemCount) {
                if (!reflowMode || visibleItemCount <= 0) {
                    return;
                }
                requestReflowRange(
                        firstVisibleItem - REFLOW_PREFETCH_PAGES,
                        firstVisibleItem + visibleItemCount - 1 + REFLOW_PREFETCH_PAGES);
            }
        });

        reflowFontSp = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getInt(PREF_REFLOW_TEXT_SIZE, REFLOW_TEXT_SIZE_DEFAULT);
        reflowFontSp = Math.max(
                REFLOW_TEXT_SIZE_MIN,
                Math.min(REFLOW_TEXT_SIZE_MAX, reflowFontSp));

        menuButton.setOnClickListener(v -> toggleMenu());
        menuScrim.setOnClickListener(v -> closeMenu());

        openButton.setOnClickListener(v -> {
            closeMenu();
            openPdfLauncher.launch(new String[]{"application/pdf"});
        });

        exitButton.setOnClickListener(v -> showCloseConfirmation());

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

            pdfContainer.postOnAnimation(() ->
                    pdfContainer.postOnAnimation(() -> {
                        if (hasOpenDocument() && !reflowMode
                                && !pdfViewerFragment.isTextSearchActive()) {
                            pdfViewerFragment.setTextSearchActive(true);
                        }
                    }));
        });

        zoomOutButton.setOnClickListener(v -> changeUnifiedZoom(-1));
        zoomInButton.setOnClickListener(v -> changeUnifiedZoom(1));

        reflowCheckBox.setOnClickListener(v -> {
            if (reflowCheckBox.isChecked()) {
                enterReflowMode();
                if (!reflowMode) {
                    reflowCheckBox.setChecked(false);
                }
            } else {
                exitReflowMode();
            }
        });

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

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (reflowMode) {
            outState.putBoolean(STATE_REFLOW_MODE, true);
            outState.putInt(
                    STATE_REFLOW_PAGE,
                    Math.max(0, reflowList.getFirstVisiblePosition()));
        }
        super.onSaveInstanceState(outState);
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

        emptyState.setVisibility(open ? View.GONE : View.VISIBLE);
        pdfContainer.setVisibility(reflowMode ? View.GONE : View.VISIBLE);
        reflowList.setVisibility(reflowMode ? View.VISIBLE : View.GONE);

        searchButton.setVisibility(open && !reflowMode ? View.VISIBLE : View.GONE);
        documentMenuActions.setVisibility(open ? View.VISIBLE : View.GONE);
        documentMenuControls.setVisibility(open ? View.VISIBLE : View.GONE);

        themeTitle.setVisibility(View.VISIBLE);
        themeGroup.setVisibility(View.VISIBLE);
        languageButton.setVisibility(View.VISIBLE);

        reflowCheckBox.setEnabled(open);
        reflowCheckBox.setChecked(reflowMode);

        if (reflowMode) {
            zoomOutButton.setContentDescription(getString(R.string.font_size) + " −");
            zoomInButton.setContentDescription(getString(R.string.font_size) + " +");
        } else {
            zoomOutButton.setContentDescription(getString(R.string.zoom_out));
            zoomInButton.setContentDescription(getString(R.string.zoom_in));
        }
    }

    private void changeUnifiedZoom(int direction) {
        if (!hasOpenDocument()) {
            return;
        }

        if (reflowMode) {
            changeReflowFontSize(direction * REFLOW_TEXT_SIZE_STEP);
        } else if (pdfViewerFragment != null) {
            if (direction < 0) {
                pdfViewerFragment.zoomOut();
            } else {
                pdfViewerFragment.zoomIn();
            }
        }
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
        boolean reopenInReflow = reflowMode;
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
                    if (reopenInReflow && reflowMode) {
                        exitReflowMode();
                    }
                    restoreReflowAfterLoad = reopenInReflow;
                    restoreReflowPage = 0;
                    ensurePdfViewer();
                    pdfViewerFragment.setDocumentUri(uri);
                    emptyState.setVisibility(View.GONE);
                    updateUiForDocumentState();
                } catch (RuntimeException error) {
                    restoreReflowAfterLoad = false;
                    showDocumentRejected(R.string.unreadable_document_message);
                }
            });
        }, "pdf-check").start();
    }

    private void enterReflowMode() {
        int page = pdfViewerFragment == null ? 0 : pdfViewerFragment.getCurrentPdfPage();
        enterReflowModeAtPage(page);
    }

    private void enterReflowModeAtPage(int preferredPage) {
        if (!hasOpenDocument() || pdfViewerFragment == null) {
            return;
        }

        int pageCount = pdfViewerFragment.getReflowPageCount();
        if (pageCount <= 0) {
            return;
        }

        if (pdfViewerFragment.isTextSearchActive()) {
            pdfViewerFragment.setTextSearchActive(false);
        }

        reflowMode = true;
        reflowRequestId++;
        reflowPageCount = pageCount;
        reflowPageText.clear();
        reflowPageLoading.clear();
        reflowAdapter.notifyDataSetChanged();
        updateUiForDocumentState();

        int startPage = Math.max(0, Math.min(reflowPageCount - 1, preferredPage));
        requestReflowRange(
                startPage - REFLOW_PREFETCH_PAGES,
                startPage + REFLOW_PREFETCH_PAGES);
        reflowList.setSelection(startPage);
    }

    private void requestReflowRange(int fromPage, int toPage) {
        if (!reflowMode || pdfViewerFragment == null || reflowPageCount <= 0) {
            return;
        }

        int from = Math.max(0, fromPage);
        int to = Math.min(reflowPageCount - 1, toPage);
        for (int page = from; page <= to; page++) {
            requestReflowPage(page);
        }
    }

    private void requestReflowPage(int pageNumber) {
        if (!reflowMode
                || pdfViewerFragment == null
                || pageNumber < 0
                || pageNumber >= reflowPageCount
                || reflowPageText.get(pageNumber) != null
                || reflowPageLoading.get(pageNumber)) {
            return;
        }

        int requestId = reflowRequestId;
        reflowPageLoading.put(pageNumber, true);

        pdfViewerFragment.extractReflowPageText(
                pageNumber,
                new ReaderPdfFragment.ReflowTextCallback() {
                    @Override
                    public void onTextReady(@NonNull String text) {
                        runOnUiThread(() -> {
                            if (!reflowMode
                                    || requestId != reflowRequestId
                                    || isFinishing()
                                    || isDestroyed()) {
                                return;
                            }
                            reflowPageLoading.delete(pageNumber);
                            reflowPageText.put(pageNumber, text);
                            stripRepeatedEdgeTextAround(pageNumber);
                            reflowAdapter.notifyDataSetChanged();
                        });
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        runOnUiThread(() -> {
                            if (!reflowMode
                                    || requestId != reflowRequestId
                                    || isFinishing()
                                    || isDestroyed()) {
                                return;
                            }
                            reflowPageLoading.delete(pageNumber);
                            reflowPageText.put(pageNumber, "");
                            reflowAdapter.notifyDataSetChanged();
                        });
                    }
                });
    }

    private void stripRepeatedEdgeTextAround(int pageNumber) {
        int from = Math.max(0, pageNumber - REFLOW_PREFETCH_PAGES);
        int to = Math.min(reflowPageCount - 1, pageNumber + REFLOW_PREFETCH_PAGES);

        for (int first = from; first <= to; first++) {
            String firstText = reflowPageText.get(first);
            if (firstText == null || firstText.isEmpty()) {
                continue;
            }

            for (int second = first + 1; second <= to; second++) {
                if (second - first > REFLOW_PREFETCH_PAGES) {
                    break;
                }
                String secondText = reflowPageText.get(second);
                if (secondText == null || secondText.isEmpty()) {
                    continue;
                }

                String firstHeader = firstParagraph(firstText);
                String secondHeader = firstParagraph(secondText);
                if (hasMultipleParagraphs(firstText)
                        && hasMultipleParagraphs(secondText)
                        && isRepeatedRunningText(firstHeader, secondHeader)) {
                    reflowPageText.put(first, removeFirstParagraph(firstText));
                    reflowPageText.put(second, removeFirstParagraph(secondText));
                    firstText = reflowPageText.get(first);
                    secondText = reflowPageText.get(second);
                }

                String firstFooter = lastParagraph(firstText);
                String secondFooter = lastParagraph(secondText);
                if (hasMultipleParagraphs(firstText)
                        && hasMultipleParagraphs(secondText)
                        && isRepeatedRunningText(firstFooter, secondFooter)) {
                    reflowPageText.put(first, removeLastParagraph(firstText));
                    reflowPageText.put(second, removeLastParagraph(secondText));
                    firstText = reflowPageText.get(first);
                }
            }
        }
    }

    private boolean hasMultipleParagraphs(String text) {
        return text.contains("\n\n");
    }

    private boolean isRepeatedRunningText(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }

        String normalizedFirst = normalizeRunningText(first);
        String normalizedSecond = normalizeRunningText(second);
        if (!normalizedFirst.equals(normalizedSecond)) {
            return false;
        }

        int words = normalizedFirst.split("\\s+").length;
        return normalizedFirst.length() <= 90 && words <= 12;
    }

    private String normalizeRunningText(String text) {
        return text.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String firstParagraph(String text) {
        int separator = text.indexOf("\n\n");
        return (separator < 0 ? text : text.substring(0, separator)).trim();
    }

    private String lastParagraph(String text) {
        int separator = text.lastIndexOf("\n\n");
        return (separator < 0 ? text : text.substring(separator + 2)).trim();
    }

    private String removeFirstParagraph(String text) {
        int separator = text.indexOf("\n\n");
        return separator < 0 ? "" : text.substring(separator + 2).trim();
    }

    private String removeLastParagraph(String text) {
        int separator = text.lastIndexOf("\n\n");
        return separator < 0 ? "" : text.substring(0, separator).trim();
    }

    private void exitReflowMode() {
        if (!reflowMode) {
            reflowCheckBox.setChecked(false);
            return;
        }

        reflowMode = false;
        reflowRequestId++;
        reflowPageCount = 0;
        reflowPageText.clear();
        reflowPageLoading.clear();
        if (pdfViewerFragment != null) {
            pdfViewerFragment.cancelReflowExtraction();
        }
        reflowAdapter.notifyDataSetChanged();
        updateUiForDocumentState();
    }

    private void changeReflowFontSize(int delta) {
        int newSize = Math.max(
                REFLOW_TEXT_SIZE_MIN,
                Math.min(REFLOW_TEXT_SIZE_MAX, reflowFontSp + delta));
        if (newSize == reflowFontSp) {
            return;
        }

        int firstVisible = -1;
        int topOffset = 0;
        if (reflowMode) {
            firstVisible = reflowList.getFirstVisiblePosition();
            View firstChild = reflowList.getChildAt(0);
            if (firstChild != null) {
                topOffset = firstChild.getTop();
            }
        }

        reflowFontSp = newSize;
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_REFLOW_TEXT_SIZE, reflowFontSp)
                .apply();
        reflowAdapter.notifyDataSetChanged();

        if (reflowMode && firstVisible >= 0) {
            final int restorePosition = firstVisible;
            final int restoreOffset = topOffset;
            reflowList.post(() ->
                    reflowList.setSelectionFromTop(restorePosition, restoreOffset));
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

    private void showCloseConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.close_program) + "?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.close_program, (dialog, which) -> closeProgram())
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
    }

    @Override
    public void onPdfLoaded() {
        applyPdfThemeToViewer();
        if (restoreReflowAfterLoad) {
            int page = restoreReflowPage;
            restoreReflowAfterLoad = false;
            restoreReflowPage = 0;
            enterReflowModeAtPage(page);
        } else {
            updateUiForDocumentState();
        }
    }

    @Override
    public void onPdfLoadError() {
        restoreReflowAfterLoad = false;
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

    private final class ReflowPageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return reflowMode ? reflowPageCount : 0;
        }

        @Override
        public Object getItem(int position) {
            return reflowPageText.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textView;
            if (convertView instanceof TextView) {
                textView = (TextView) convertView;
            } else {
                textView = new TextView(MainActivity.this);
                textView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                textView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                textView.setTextIsSelectable(true);
                textView.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
                textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
                textView.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
                textView.setLineSpacing(dp(8), 1f);
            }

            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, reflowFontSp);
            textView.setPadding(dp(28), dp(10), dp(28), dp(18));

            String pageText = reflowPageText.get(position);
            if (pageText == null) {
                textView.setMinHeight(dp(180));
                textView.setText(R.string.reflow_loading);
                requestReflowPage(position);
            } else {
                textView.setMinHeight(pageText.isEmpty() ? dp(1) : 0);
                textView.setText(pageText);
            }
            return textView;
        }
    }

    private enum DocumentCheck {
        OK,
        PROTECTED,
        UNREADABLE
    }
}
