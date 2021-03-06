package me.devsaki.hentoid.fragments.downloads;

import android.app.Dialog;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AboutMikanDialogFragment extends DialogFragment {

    public static void show(FragmentManager fragmentManager) {
        AboutMikanDialogFragment fragment = new AboutMikanDialogFragment();
        fragment.show(fragmentManager, null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        WebView webView = new WebView(requireContext());
        webView.loadUrl("file:///android_asset/about_mikan.html");
        webView.setInitialScale(95);

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle("About Mikan Search")
                .setPositiveButton(android.R.string.ok, null)
                .create();
    }
}
