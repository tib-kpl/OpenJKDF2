package org.openjkdf2.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * Touch-only entry point for game settings overlay.
 * Controllers open the same menu with Select + Start.
 */
final class AndroidSettingsGear {
    private AndroidSettingsGear() {}

    /**
     * Adds the touch-only entry point over the game.
     * @param activity The game activity
     * @param open Runnable to open the settings menu
     * @param bMots Whether this is the MoTS flavor (for color theming)
     * @return The gear view
     */
    static View install(Activity activity, Runnable open, boolean bMots) {
        final float density = activity.getResources().getDisplayMetrics().density;
        View gear = new View(activity) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f, unit = Math.min(cx, cy);
                paint.setStyle(Paint.Style.FILL);
                // Semi-transparent dark background circle
                paint.setColor(0x66000000);
                canvas.drawCircle(cx, cy, unit * .85f, paint);
                // Gear teeth color - adapted to game
                int gearColor = bMots ? 0xffdd5533 : 0xff4466aa; // MoTS: orange-red, JK: blue
                paint.setColor(gearColor);
                for (int tooth = 0; tooth < 8; tooth++) {
                    canvas.save();
                    canvas.rotate(tooth * 45, cx, cy);
                    canvas.drawRect(cx - unit * .10f, cy - unit * .55f, cx + unit * .10f, cy - unit * .30f, paint);
                    canvas.restore();
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(unit * .13f);
                canvas.drawCircle(cx, cy, unit * .34f, paint);
                canvas.drawCircle(cx, cy, unit * .10f, paint);
            }
        };
        String lang = Locale.getDefault().getLanguage();
        gear.setContentDescription("fr".equals(lang)
                ? "Réglages du jeu" : "Game settings");
        // Touch only: a focusable view takes controller input away from SDL.
        gear.setFocusable(false);
        gear.setFocusableInTouchMode(false);
        gear.setOnClickListener(v -> open.run());
        gear.setAlpha(.45f);
        int size = (int) (40 * density + .5f);
        activity.addContentView(gear, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.RIGHT));
        // Refresh even when controller is plugged in mid-game.
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (gear.hasWindowFocus()) updateVisibility(gear, activity);
                gear.postDelayed(this, 750);
            }
        };
        gear.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { view.removeCallbacks(refresh); view.post(refresh); }
            @Override public void onViewDetachedFromWindow(View view) { view.removeCallbacks(refresh); }
        });
        // addContentView may have already attached the view before the listener was added.
        gear.removeCallbacks(refresh);
        gear.post(refresh);
        raise(gear);
        return gear;
    }

    /** Keeps the gear above other overlays. */
    static void raise(View gear) {
        if (gear == null) return;
        updateVisibility(gear, gear.getContext() instanceof Activity ? (Activity) gear.getContext() : null);
        gear.bringToFront();
        if (Build.VERSION.SDK_INT >= 21) gear.setElevation(20000f);
    }

    private static void updateVisibility(View gear, Activity activity) {
        if (activity == null) return;
        boolean touch = activity.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        gear.setVisibility(touch && !isControllerConnected(activity) && !hasDesktopInput(activity)
                ? View.VISIBLE : View.GONE);
    }

    private static boolean isControllerConnected(Activity activity) {
        int[] ids = android.view.InputDevice.getDeviceIds();
        for (int id : ids) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                    || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDesktopInput(Activity activity) {
        if (Build.VERSION.SDK_INT < 29) return false;
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null || device.isVirtual() || !device.isExternal()) continue;
            int sources = device.getSources();
            if ((sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                    || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) continue;
            String name = device.getName() == null ? "" : device.getName().toLowerCase(Locale.US);
            if (name.contains("virtual mouse") || name.contains("virtual keyboard") || name.contains("virtual pointer")) continue;
            if (device.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    || (sources & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE) return true;
        }
        return false;
    }
}