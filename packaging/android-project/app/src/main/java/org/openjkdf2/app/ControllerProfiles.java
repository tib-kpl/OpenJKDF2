package org.openjkdf2.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
    Per-controller button remapping (Unreal-Android style).

    A, B, X, Y, L1, R1, L3 and R3 of each physical controller can send another
    of those buttons, or nothing. Profiles are keyed by Android's stable device
    descriptor (so they survive reconnections), and live in this APK's own
    preferences. Sticks, D-pad, triggers, Select, Start and Back are never
    remapped: Select + Start keeps opening the settings menu.

    The remapped keycode is what gets handed to SDL, whose gamepad layer then
    drives the game as for a controller that physically had that button.
*/
final class ControllerProfiles {
    /** translate() result for a disabled button: the event must be swallowed. */
    static final int DISABLED = -1;

    private static final String PREFS = "controller_profiles";

    private static final int[] BUTTONS = {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
    };
    private static final String[] NAMES = { "A", "B", "X", "Y", "L1", "R1", "L3", "R3" };

    private final Context context;
    private final SharedPreferences prefs;
    // (device, physical button) -> output used at press time, so a release
    // always matches its press even if the profile changed in between
    private final HashMap<Long, Integer> held = new HashMap<>();
    private int selectedDevice; // index in the connected controllers list

    ControllerProfiles(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int indexOf(int keyCode) {
        for (int i = 0; i < BUTTONS.length; i++) {
            if (BUTTONS[i] == keyCode) return i;
        }
        return -1;
    }

    private static String descriptor(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        return device == null ? null : device.getDescriptor();
    }

    private static String prefKey(String descriptor, int keyCode) {
        return descriptor + "/" + keyCode;
    }

    /** What a physical button currently sends: a keycode of BUTTONS, or DISABLED. */
    private int output(String descriptor, int keyCode) {
        if (descriptor == null) return keyCode;
        int value = prefs.getInt(prefKey(descriptor, keyCode), keyCode);
        return value == DISABLED || indexOf(value) >= 0 ? value : keyCode;
    }

    /** Keycode to hand to SDL for this event, or DISABLED to swallow it. */
    int translate(KeyEvent event) {
        int code = event.getKeyCode();
        if (indexOf(code) < 0) return code;
        long key = ((long) event.getDeviceId() << 32) | code;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Integer already = event.getRepeatCount() > 0 ? held.get(key) : null;
            if (already != null) return already;
            int out = output(descriptor(event.getDeviceId()), code);
            held.put(key, out);
            return out;
        }
        Integer out = held.remove(key);
        return out != null ? out : output(descriptor(event.getDeviceId()), code);
    }

    /** Forget pressed buttons (focus loss, menu opening): releases are then not remapped. */
    void clearHeld() {
        held.clear();
    }

    // ---- Settings menu section ----------------------------------------

    private static boolean fr(Context context) {
        return "fr".equalsIgnoreCase(context.getResources().getConfiguration().locale.getLanguage());
    }

    private String text(String french, String english) {
        return fr(context) ? french : english;
    }

    private static final class Pad {
        final String descriptor, name;
        Pad(String descriptor, String name) { this.descriptor = descriptor; this.name = name; }
    }

    /** Connected physical controllers, one entry per descriptor. */
    private static ArrayList<Pad> connected() {
        ArrayList<Pad> pads = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
                    && (sources & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) continue;
            String descriptor = device.getDescriptor();
            boolean known = false;
            for (Pad pad : pads) known |= pad.descriptor.equals(descriptor);
            if (!known) pads.add(new Pad(descriptor, device.getName()));
        }
        return pads;
    }

    /** Fills the current menu page with the controller mapping rows. */
    void buildSection(GameSettingsMenu menu) {
        menu.section(text("Manette — attribution des boutons", "Controller — button mapping"));
        ArrayList<Pad> pads = connected();
        if (pads.isEmpty()) {
            menu.note(text("Aucune manette détectée. Branchez ou connectez une manette pour modifier ses boutons.",
                           "No controller detected. Connect a controller to change its buttons."));
            return;
        }
        if (selectedDevice >= pads.size()) selectedDevice = 0;
        final Pad pad = pads.get(selectedDevice);

        String[] padNames = new String[pads.size()];
        for (int i = 0; i < padNames.length; i++) padNames[i] = pads.get(i).name;
        menu.choice(text("Manette", "Controller"), padNames, selectedDevice, 0, index -> {
            selectedDevice = index;
            menu.refresh();
        });

        // Each button can send any of the 8 buttons, or nothing
        String[] outputs = new String[BUTTONS.length + 1];
        System.arraycopy(NAMES, 0, outputs, 0, NAMES.length);
        outputs[BUTTONS.length] = text("Désactivé", "Disabled");
        for (int i = 0; i < BUTTONS.length; i++) {
            final int button = BUTTONS[i];
            int current = output(pad.descriptor, button);
            int initial = current == DISABLED ? BUTTONS.length : indexOf(current);
            menu.choice(String.format(Locale.ROOT, text("Bouton %s", "Button %s"), NAMES[i]),
                    outputs, initial, i, index -> {
                        SharedPreferences.Editor editor = prefs.edit();
                        if (index == BUTTONS.length) editor.putInt(prefKey(pad.descriptor, button), DISABLED);
                        else if (BUTTONS[index] == button) editor.remove(prefKey(pad.descriptor, button));
                        else editor.putInt(prefKey(pad.descriptor, button), BUTTONS[index]);
                        editor.commit();
                        clearHeld();
                    });
        }
        menu.note(text("Select, Start, Retour, sticks, croix directionnelle et gâchettes ne changent pas. "
                     + "Le profil est propre à chaque manette.",
                       "Select, Start, Back, sticks, D-pad and triggers are not remapped. "
                     + "Each controller has its own profile."));
        menu.button(text("Réinitialiser cette manette…", "Reset this controller…"), () ->
                menu.confirm(text("Rétablir les boutons par défaut de cette manette ?",
                                  "Restore this controller's default buttons?"),
                        text("Réinitialiser", "Reset"), () -> {
                            SharedPreferences.Editor editor = prefs.edit();
                            for (int button : BUTTONS) editor.remove(prefKey(pad.descriptor, button));
                            editor.commit();
                            clearHeld();
                            menu.refresh();
                        }));
    }
}
