package com.shivansps.fsowrapper.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import com.fsolauncher.core.TouchInputState;
import com.fsolauncher.core.TouchLayout;
import com.shivansps.fsowrapper.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the entire configurable HUD from its packaged JSON layout. */
@SuppressLint("ViewConstructor")
public final class TouchOverlay extends ViewGroup {
    private final TouchLayout profile;
    private final TouchInputState input;
    private final Runnable toggleKeyboard;
    private final Map<View, TouchLayout.Element> elements = new LinkedHashMap<>();
    private final Map<Button, TouchLayout.Action> buttons = new LinkedHashMap<>();
    private final Map<Button, int[]> heldButtons = new LinkedHashMap<>();
    private final List<RadialActionView> radials = new ArrayList<>();
    private final List<Runnable> pulses = new ArrayList<>();
    private RadialDpadView joystick;
    private final Button modeButton;
    private int mode;

    public TouchOverlay(Context context, TouchLayout profile, TouchInputState input, Runnable toggleKeyboard) {
        super(context);
        this.profile = profile;
        this.input = input;
        this.toggleKeyboard = toggleKeyboard;
        this.mode = profile.initialHudMode;
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setMotionEventSplittingEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setTag("overlay_root");

        for (TouchLayout.Element element : profile.elements) {
            View view = createElement(element);
            view.setId(View.generateViewId());
            view.setTag(element.id);
            if (element.description != null) view.setContentDescription(element.description);
            elements.put(view, element);
            addView(view, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        // This control intentionally lives outside the JSON and every visibility group.
        modeButton = new Button(context, null, 0, R.style.Widget_FSOWrapper_HudButton);
        modeButton.setId(View.generateViewId());
        modeButton.setTag("btnToggle");
        modeButton.setText("≡");
        modeButton.setTextSize(18);
        modeButton.setMinWidth(dp(38));
        modeButton.setOnClickListener(view -> {
            releaseInputs();
            mode = (mode + 1) % 4;
            applyMode();
        });
        addView(modeButton, new LayoutParams(dp(38), dp(28)));
        applyMode();
        refreshAppearance();
    }

    private View createElement(TouchLayout.Element element) {
        switch (element.type) {
            case "buttonBar": return createBar(element);
            case "button": return createButton(element.action);
            case "radial": return createRadial(element);
            case "joystick": return createJoystick(element);
            default: throw new IllegalArgumentException("Unknown element type: " + element.type);
        }
    }

    private View createBar(TouchLayout.Element element) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(("end".equals(element.align) ? Gravity.END : Gravity.START) | Gravity.TOP);
        row.setMotionEventSplittingEnabled(true);
        for (TouchLayout.Action action : element.buttons) {
            Button button = createButton(action);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, dp(action.heightDp));
            params.leftMargin = dp(2);
            params.rightMargin = dp(2);
            row.addView(button, params);
        }
        scroll.addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return scroll;
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button createButton(TouchLayout.Action source) {
        TouchLayout.Action action = profile.resolve(source, true);
        Button button = new Button(getContext(), null, 0, R.style.Widget_FSOWrapper_HudButton);
        button.setId(View.generateViewId());
        button.setTag(action.id);
        button.setText(action.label);
        button.setTextSize(action.textSp);
        button.setContentDescription(description(action));
        button.setMinWidth(dp(action.minWidthDp));
        buttons.put(button, action);
        if ("keyboard".equals(action.command)) {
            button.setOnClickListener(view -> toggleKeyboard.run());
        } else {
            button.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!heldButtons.containsKey(button)) {
                            heldButtons.put(button, action.codes);
                            input.press(action.codes);
                        }
                        button.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        int[] codes = heldButtons.remove(button);
                        if (codes != null) input.release(codes);
                        button.setPressed(false);
                        return true;
                    default:
                        return false;
                }
            });
            // Accessibility click actions generate a short, balanced key press.
            button.setOnClickListener(view -> {
                input.press(action.codes);
                Runnable pulse = new Runnable() {
                    @Override public void run() {
                        input.release(action.codes);
                        pulses.remove(this);
                    }
                };
                pulses.add(pulse);
                postDelayed(pulse, 45);
            });
        }
        return button;
    }

    private View createRadial(TouchLayout.Element element) {
        RadialActionView wheel = new RadialActionView(getContext());
        wheel.setActions(radialActions(element.center), radialActions(element.outer));
        wheel.setCenterWeights(weights(element.center));
        wheel.setOuterWeights(weights(element.outer));
        wheel.setInnerRadiusRatio(element.innerRadiusRatio);
        wheel.setFirstOuterActionAngle(element.firstOuterActionAngle);
        wheel.setLabelScale(element.labelScale);
        wheel.setOnActionListener((released, pressed) -> {
            List<int[]> up = new ArrayList<>();
            List<int[]> down = new ArrayList<>();
            for (RadialActionView.Action action : released) up.add(action.getKeyCodes());
            for (RadialActionView.Action action : pressed) down.add(action.getKeyCodes());
            input.transition(up, down);
        });
        radials.add(wheel);
        return wheel;
    }

    private List<RadialActionView.Action> radialActions(List<TouchLayout.Action> actions) {
        List<RadialActionView.Action> result = new ArrayList<>();
        for (TouchLayout.Action source : actions) {
            TouchLayout.Action action = profile.resolve(source, false);
            result.add(new RadialActionView.Action(action.label, description(action), action.codes));
        }
        return result;
    }

    private static float[] weights(List<TouchLayout.Action> actions) {
        float[] result = new float[actions.size()];
        for (int i = 0; i < actions.size(); i++) result[i] = actions.get(i).weight;
        return result;
    }

    private View createJoystick(TouchLayout.Element element) {
        joystick = new RadialDpadView(getContext());
        joystick.setDeadzone(element.deadzone);
        joystick.setRingEnabled(element.ringAction != null);
        if (element.ringAction != null) {
            TouchLayout.Action ring = profile.resolve(element.ringAction, false);
            joystick.setRingLabel(ring.label);
            joystick.setOnRingActionListener(pressed -> {
                if (pressed) input.press(ring.codes);
                else input.release(ring.codes);
            });
        }
        return joystick;
    }

    private static String description(TouchLayout.Action action) {
        return action.description == null ? action.label.replace('\n', ' ') : action.description;
    }

    private void applyMode() {
        for (Map.Entry<View, TouchLayout.Element> entry : elements.entrySet()) {
            entry.getKey().setVisibility(TouchLayout.visible(entry.getValue().modes, mode) ? VISIBLE : GONE);
        }
        for (Map.Entry<Button, TouchLayout.Action> entry : buttons.entrySet()) {
            int[] modes = entry.getValue().modes;
            if (modes != null) {
                TouchLayout.Element element = elements.get(entry.getKey());
                boolean parentVisible = element == null || TouchLayout.visible(element.modes, mode);
                entry.getKey().setVisibility(parentVisible && TouchLayout.visible(modes, mode) ? VISIBLE : GONE);
            }
        }
        modeButton.setVisibility(VISIBLE);
        modeButton.setContentDescription("Show or hide touch control groups. Mode " + mode);
        requestLayout();
    }

    public void releaseInputs() {
        for (Runnable pulse : pulses) removeCallbacks(pulse);
        pulses.clear();
        for (RadialActionView radial : radials) radial.releaseAllActions();
        if (joystick != null) joystick.releaseAllControls();
        for (Map.Entry<Button, int[]> entry : heldButtons.entrySet()) {
            input.release(entry.getValue());
            entry.getKey().setPressed(false);
        }
        heldButtons.clear();
        input.releaseAll();
    }

    public void refreshAppearance() {
        for (Button button : buttons.keySet()) HudStyle.applyTo(button);
        HudStyle.applyTo(modeButton);
        for (RadialActionView radial : radials) radial.invalidate();
        if (joystick != null) joystick.invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int contentWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int contentHeight = Math.max(0, height - getPaddingTop() - getPaddingBottom());
        for (Map.Entry<View, TouchLayout.Element> entry : elements.entrySet()) {
            TouchLayout.Bounds bounds = TouchLayout.bounds(entry.getValue(), contentWidth, contentHeight,
                getResources().getDisplayMetrics().density);
            entry.getKey().measure(MeasureSpec.makeMeasureSpec(bounds.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bounds.height, MeasureSpec.EXACTLY));
        }
        modeButton.measure(MeasureSpec.makeMeasureSpec(Math.min(contentWidth, dp(38)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Math.min(contentHeight, dp(28)), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        int height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        for (Map.Entry<View, TouchLayout.Element> entry : elements.entrySet()) {
            TouchLayout.Bounds bounds = TouchLayout.bounds(entry.getValue(), width, height,
                getResources().getDisplayMetrics().density);
            int x = getPaddingLeft() + bounds.x;
            int y = getPaddingTop() + bounds.y;
            entry.getKey().layout(x, y, x + bounds.width, y + bounds.height);
        }
        int x = getPaddingLeft() + Math.round((width - modeButton.getMeasuredWidth()) * .5f);
        int y = getPaddingTop();
        modeButton.layout(x, y, x + modeButton.getMeasuredWidth(), y + modeButton.getMeasuredHeight());
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && input != null) releaseInputs();
    }

    @Override protected void onDetachedFromWindow() {
        releaseInputs();
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
