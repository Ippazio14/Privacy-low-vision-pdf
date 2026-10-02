package org.ippazio.privacylowvisionpdf;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class HelpActivity extends AppCompatActivity {
    public static final String EXTRA_FIRST_RUN = "first_run";

    private static final String UI_PREFS = "ui_preferences";
    private static final String PREF_THEME = "theme";
    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String THEME_HIGH_CONTRAST = "high_contrast";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedThemeBeforeCreate();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        boolean firstRun = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false);

        Button closeButton = findViewById(R.id.help_close_button);
        closeButton.setText(firstRun ? R.string.continue_label : R.string.close);
        closeButton.setOnClickListener(v -> finish());
    }

    private void applySavedThemeBeforeCreate() {
        SharedPreferences preferences = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String theme = preferences.getString(PREF_THEME, THEME_SYSTEM);

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
}
