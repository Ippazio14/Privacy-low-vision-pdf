package org.ippazio.privacylowvisionpdf;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Reflow list that keeps justification local to the visible page window and converts a two-finger
 * pinch into discrete text-size steps.
 */
public class ReflowListView extends ListView {
    public interface OnScaleStepListener {
        void onScaleStep(int direction);
    }

    private final ScaleGestureDetector scaleGestureDetector;
    private OnScaleStepListener scaleStepListener;
    private float accumulatedScale = 1f;

    public ReflowListView(@NonNull Context context) {
        super(context);
        scaleGestureDetector = createScaleDetector(context);
    }

    public ReflowListView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scaleGestureDetector = createScaleDetector(context);
    }

    public ReflowListView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scaleGestureDetector = createScaleDetector(context);
    }

    public void setOnScaleStepListener(@Nullable OnScaleStepListener listener) {
        scaleStepListener = listener;
    }

    @NonNull
    private ScaleGestureDetector createScaleDetector(@NonNull Context context) {
        return new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        accumulatedScale = 1f;
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        accumulatedScale *= detector.getScaleFactor();

                        if (accumulatedScale >= 1.12f) {
                            if (scaleStepListener != null) {
                                scaleStepListener.onScaleStep(1);
                            }
                            accumulatedScale = 1f;
                        } else if (accumulatedScale <= 0.89f) {
                            if (scaleStepListener != null) {
                                scaleStepListener.onScaleStep(-1);
                            }
                            accumulatedScale = 1f;
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                        accumulatedScale = 1f;
                    }
                });
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
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
