package com.hmartinez94.tvrelay;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.GuidedStepSupportFragment;

/**
 * Hosts the settings screens as a stack of GuidedStepSupportFragments.
 * No XML layout: GuidedStepSupportFragment.addAsRoot manages its own
 * content view directly on the activity's decor view.
 */
public class MainActivity extends FragmentActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            GuidedStepSupportFragment root = Preferences.isDisclosureAccepted(this)
                    ? new SettingsStepFragment()
                    : new DisclosureStepFragment();
            GuidedStepSupportFragment.addAsRoot(this, root, android.R.id.content);
        }
    }
}
