package org.ippazio.privacylowvisionpdf;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

public class HelpButton extends MaterialButton {
    public HelpButton(@NonNull Context context) {
        super(context);
        init();
    }

    public HelpButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HelpButton(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(v ->
                getContext().startActivity(new Intent(getContext(), HelpActivity.class)));
    }
}
