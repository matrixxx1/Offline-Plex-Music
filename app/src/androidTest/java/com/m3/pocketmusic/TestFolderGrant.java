package com.m3.pocketmusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.DocumentsContract;

/** Grants only the synthetic test provider tree to the app being tested. */
public class TestFolderGrant extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        context.grantUriPermission("com.m3.pocketmusic", DocumentsContract.buildTreeDocumentUri("com.m3.pocketmusic.test.documents", "root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
}
