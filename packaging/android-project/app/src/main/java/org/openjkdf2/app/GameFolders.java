package org.openjkdf2.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

/**
    Where each game's files live: the folder whose path the user gave
    (SetupActivity), anywhere on the device storage. There is no app-private
    copy of the game: the game runs from that folder only.

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

    // Same lists as aRequiredAssets / aRequiredAssetsMots in src/Main/InstallHelper.c:
    // a folder the engine would reject must be refused here, not at game start.
    private static final String[] JK_FILES = {
        "episode/JK1.gob", "episode/JK1CTF.gob", "episode/JK1MP.gob",
        "resource/Res1hi.gob", "resource/Res2.gob", "resource/jk_.cd",
    };
    private static final String[] MOTS_FILES = {
        "episode/JKM_KFY.goo", "episode/JKM_MP.goo", "episode/JKM_SABER.goo", "episode/JKM.goo",
        "resource/Jkmres.goo", "resource/JKMsndLO.goo", "resource/jk_.cd",
    };

    /** The required game files dir lacks (any letter case, like the engine); empty if complete. */
    static ArrayList<String> missingFiles(File dir, boolean bMots) {
        ArrayList<String> missing = new ArrayList<>();
        for (String rel : bMots ? MOTS_FILES : JK_FILES) {
            if (findCaseInsensitive(dir, rel) == null) {
                missing.add(rel);
            }
        }
        return missing;
    }

    /** Whether dir holds all of this game's files. */
    static boolean isGameDir(File dir, boolean bMots) {
        return missingFiles(dir, bMots).isEmpty();
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
