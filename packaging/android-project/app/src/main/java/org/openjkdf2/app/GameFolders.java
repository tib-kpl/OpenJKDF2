package org.openjkdf2.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;

/**
    Where each game's files live: a folder the user picked anywhere on the
    device storage (ChooserActivity), else the app's own data dir
    (<external files dir>/jk1 or /mots, filled by the install helper).

    The native side reads the picked folders from OPENJKDF2_ROOT and
    OPENJKMOTS_ROOT (see InstallHelper_GetLocalDataDir), which
    exportToEnvironment() sets in the game's process before SDL starts.
*/
final class GameFolders {
    private static final String TAG = "GameFolders";
    private static final String PREFS = "game_folders";

    private GameFolders() {}

    private static String key(boolean bMots) {
        return bMots ? "mots" : "jk";
    }

    private static String envVar(boolean bMots) {
        return bMots ? "OPENJKMOTS_ROOT" : "OPENJKDF2_ROOT";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The folder the user picked for this game, or null. */
    static String getPicked(Context context, boolean bMots) {
        return prefs(context).getString(key(bMots), null);
    }

    static void setPicked(Context context, boolean bMots, String path) {
        // commit(), not apply(): the game may start in another process right after
        prefs(context).edit().putString(key(bMots), path).commit();
    }

    static File appDataDir(Context context, boolean bMots) {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(base, bMots ? "mots" : "jk1");
    }

    /** The folder this game will run from, or null if it is not installed. */
    static File resolve(Context context, boolean bMots) {
        String picked = getPicked(context, bMots);
        if (picked != null && isGameDir(new File(picked), bMots)) {
            return new File(picked);
        }
        File appDir = appDataDir(context, bMots);
        if (appDir != null && isGameDir(appDir, bMots)) {
            return appDir;
        }
        return null;
    }

    /** Whether dir holds this game's files (any letter case, like the engine). */
    static boolean isGameDir(File dir, boolean bMots) {
        return findCaseInsensitive(dir, "resource/jk_.cd") != null
            && findCaseInsensitive(dir, bMots ? "episode/JKM.goo" : "episode/JK1.gob") != null;
    }

    static File findCaseInsensitive(File base, String relPath) {
        File cur = base;
        for (String part : relPath.split("/")) {
            String[] names = cur.list();
            if (names == null) {
                return null;
            }
            File next = null;
            for (String name : names) {
                if (name.equalsIgnoreCase(part)) {
                    next = new File(cur, name);
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return cur.exists() ? cur : null;
    }

    /** Hands the picked folders (when still valid) to the native code. */
    static void exportToEnvironment(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return; // no Os.setenv(): app data dirs only
        }
        for (boolean bMots : new boolean[] { false, true }) {
            String picked = getPicked(context, bMots);
            try {
                if (picked != null && isGameDir(new File(picked), bMots)) {
                    Os.setenv(envVar(bMots), picked, true);
                } else {
                    Os.unsetenv(envVar(bMots));
                }
            } catch (ErrnoException e) {
                Log.w(TAG, "Could not set " + envVar(bMots), e);
            }
        }
    }
}
