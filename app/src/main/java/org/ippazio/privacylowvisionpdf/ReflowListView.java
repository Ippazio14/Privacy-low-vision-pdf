package org.ippazio.privacylowvisionpdf;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Reflow list that justifies only the visible text blocks. Keeping justification here means
 * changing the font size still relayouts only the small visible page window, not the whole PDF.
 */
public class ReflowListView extends ListView {
    public ReflowListView(@NonNull Context context) {
        super(context);
    }

    public ReflowListView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ReflowListView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TextView) {
                TextView text = (TextView) child;
                if (text.getJustificationMode() != Layout.JUSTIFICATION_MODE_INTER_WORD) {
                    text.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
                }
            }
        }
    }
}
