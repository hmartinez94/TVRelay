package com.hmartinez94.tvrelay;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.leanback.app.GuidedStepSupportFragment;

/** Hosts SearchStepFragment - reachable from Settings, or from the Watch Now overlay button. */
public class SearchActivity extends FragmentActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, new SearchStepFragment(), android.R.id.content);
        }
    }
}
