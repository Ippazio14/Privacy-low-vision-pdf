package org.ippazio.privacylowvisionpdf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class FirstRunFrameLayout extends FrameLayout {
    private static final String HELP_PREFS = "help_preferences";
    private static final String PREF_INTRO_SHOWN = "intro_shown";

    private boolean introLaunchScheduled;

    public FirstRunFrameLayout(@NonNull Context context) {
        super(context);
    }

    public FirstRunFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FirstRunFrameLayout(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode() || introLaunchScheduled) {
            return;
        }

        SharedPreferences preferences = getContext()
                .getSharedPreferences(HELP_PREFS, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PREF_INTRO_SHOWN, false)) {
            return;
        }

        introLaunchScheduled = true;
        preferences.edit().putBoolean(PREF_INTRO_SHOWN, true).apply();
        post(() -> {
            if (!isAttachedToWindow()) {
                return;
            }
            Intent intent = new Intent(getContext(), HelpActivity.class);
            intent.putExtra(HelpActivity.EXTRA_FIRST_RUN, true);
            getContext().startActivity(intent);
        });
    }
}
