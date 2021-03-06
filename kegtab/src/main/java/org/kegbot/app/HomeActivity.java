/*
 * Copyright 2014 Bevbot LLC <info@bevbot.com>
 *
 * This file is part of the Kegtab package from the Kegbot project. For
 * more information on Kegtab or Kegbot, see <http://kegbot.org/>.
 *
 * Kegtab is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 2.
 *
 * Kegtab is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with Kegtab. If not, see <http://www.gnu.org/licenses/>.
 */
package org.kegbot.app;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.hoho.android.usbserial.util.HexDump;
import com.squareup.otto.Subscribe;

import org.kegbot.app.alert.AlertCore;
import org.kegbot.app.config.AppConfiguration;
import org.kegbot.app.event.ConnectivityChangedEvent;
import org.kegbot.app.event.VisibleTapsChangedEvent;
import org.kegbot.app.service.CheckinService;
import org.kegbot.app.util.SortableFragmentStatePagerAdapter;
import org.kegbot.app.util.Units;
import org.kegbot.app.util.Utils;
import org.kegbot.app.view.BadgeView;
import org.kegbot.core.KegbotCore;
import org.kegbot.proto.Models;
import org.kegbot.proto.Models.KegTap;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * The main "home screen" of the Kegtab application. It shows the status of each tap, and allows the
 * user start a pour by authenticating (if enabled in settings).
 */
public class HomeActivity extends CoreActivity {

  private static final String LOG_TAG = HomeActivity.class.getSimpleName();

  //private static final int REQUEST_PLAY_SERVICES_UPDATE = 100;
  private static final String GCM_SENDER_ID = "209039242857";

  private static final String ACTION_SHOW_TAP_EDITOR = "show_editor";
  private static final String EXTRA_METER_NAME = "meter_name";

  private static final String ALERT_ID_UNBOUND_TAPS = "unbound-taps";

  /**
   * Idle timeout which triggers "attract mode".
   *
   * @see #mAttractModeRunnable
   */
  private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);

  /**
   * Pause interval between rotated screens in "attract mode".
   *
   * @see #mAttractModeRunnable
   */
  private static final long ROTATE_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(12);

  private static final Function<KegTap, String> TAP_TO_NAME = new Function<KegTap, String>() {
    @Nullable
    @Override
    public String apply(@Nullable KegTap input) {
      return input != null ? input.getName() : "none";
    }
  };

  private KegbotCore mCore;

  private HomeFragmentsAdapter mTapStatusAdapter;
  private ViewPager mTapStatusPager;
  private AppConfiguration mConfig;


  //for dummy pour status
  private TextView mTapTitle;
  private BadgeView mPourVolumeBadge;

  //for backgrounds
  private ImageView mImageView0;
  private LinearLayout mImageView1;

  //for start button/new drinker
  private Button mStartButton;
  private Button mNewDrinkerButton;
  private LinearLayout mActivityControls;

  private static final int REQUEST_AUTHENTICATE = 1000;
  private static final int REQUEST_CREATE_DRINKER = 1001;

  /**
   * Keep track of Google Play Services error codes, and don't annoy when the same error persists.
   * (For some reason, {@link GooglePlayServicesUtil} treats absence of the apk as "user
   * recoverable").
   *
   * @see #checkPlayServices()
   */
  private int mLastShownGooglePlayServicesError = Integer.MIN_VALUE;

  private final Object mTapsLock = new Object();

  /**
   * Shadow copy of tap manager taps.
   */
  @GuardedBy("mTapsLock")
  private final List<KegTap> mTaps = Lists.newArrayList();

  /** Main thread handler for managing {@link #mAttractModeRunnable}. */
  private final Handler mAttractModeHandler = new Handler(Looper.getMainLooper());

  /**
   * Rotates through view pager when idle.
   *
   * @see #startAttractMode()
   * @see #resetAttractMode()
   * @see #cancelAttractMode()
   */
  private final Runnable mAttractModeRunnable = new Runnable() {
    @Override
    public void run() {
      rotateDisplay();
      mAttractModeHandler.postDelayed(mAttractModeRunnable, ROTATE_INTERVAL_MILLIS);
    }
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main_activity);

    final ActionBar actionBar = getActionBar();
//    if (actionBar != null) {
//      actionBar.hide();
//    }

    synchronized (mTapsLock) {
      mTaps.clear();
    }
    mTapStatusAdapter = new HomeFragmentsAdapter(getFragmentManager());

    mTapStatusPager = (ViewPager) findViewById(R.id.tap_status_pager);
    mTapStatusPager.setAdapter(mTapStatusAdapter);
    mTapStatusPager.setOffscreenPageLimit(8); // >8 Tap systems are rare


    //for dummy pour status
    mTapTitle = (TextView) findViewById(R.id.tapTitle);
    mTapTitle.setText("Current Pour");
    mPourVolumeBadge = (BadgeView) findViewById(R.id.pourStatsBadge1);
    mPourVolumeBadge.setBadgeValue("0");
    mPourVolumeBadge.setBadgeCaption("Current mL Poured");


    //for backgrounds
    mImageView0 = (ImageView) findViewById(R.id.imageView0);
    mImageView1 = (LinearLayout) findViewById(R.id.imageView1);

    //for start button/new drinker
    mStartButton = (Button) findViewById(R.id.pourStartButton);
    mNewDrinkerButton = (Button) findViewById(R.id.newDrinkerButton);
    mActivityControls = (LinearLayout) findViewById(R.id.mainActivityControls);

    overridePendingTransition(R.anim.image_fade_in, R.anim.image_fade_in);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
  }

  @Override
  protected void onStart() {
    super.onStart();
    mCore = KegbotCore.getInstance(this);
    mConfig = mCore.getConfiguration();
    maybeShowTapWarnings();

    //for dummy pour status
    final Pair<String, String> qty = Units.localizeWithoutScaling(
        mCore.getConfiguration(), 0.0);
    mPourVolumeBadge.setBadgeValue(qty.first);
    mPourVolumeBadge.setBadgeCaption("Current " + Units.capitalizeUnits(qty.second) + " Poured");

    mImageView0.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        final ActionBar actionBar = getActionBar();
        if (actionBar.isShowing()) {
          actionBar.hide();
          mConfig.setShowActionBar(false);
        } else {
          actionBar.show();
          mConfig.setShowActionBar(true);
        }
      }
    });

    //for start button
    final Context thisContext = this;
    mStartButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (mConfig.useAccounts()) {
          final Intent intent = KegtabCommon.getAuthDrinkerActivityIntent(thisContext);
          startActivityForResult(intent, REQUEST_AUTHENTICATE);
        } else {
          mCore.getFlowManager().activateUserAmbiguousTap("");
        }

      }
    });

    mNewDrinkerButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        final Intent intent = KegtabCommon.getCreateDrinkerActivityIntent(thisContext);
        startActivityForResult(intent, REQUEST_CREATE_DRINKER);
      }
    });

    mTapStatusPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
      @Override
      public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

      }

      @Override
      public void onPageSelected(int position) {

        //for progress bar
        if (mTaps.size() > 0) {
          final KegTap tap = mTaps.get(position);
          if (tap.hasCurrentKeg()) {
            final Models.Keg keg = tap.getCurrentKeg();
            double remainml = keg.getRemainingVolumeMl();
            double totalml = keg.getFullVolumeMl();
            double percent = (remainml) / (totalml) * 100;

            final ProgressBar mTapProgress = (ProgressBar) findViewById(R.id.tapProgress);
            mTapProgress.setMax((int) totalml);
            mTapProgress.setProgress((int) remainml);

            final TextView mTapPercentage = (TextView) findViewById(R.id.tapPercentage);
            mTapPercentage.setText(String.format("%.2f", percent) + "%");
          }
        }

        //for backgrounds
        switch (position) {
          case 0:
            mImageView0.setImageResource(R.drawable.e1);
            mImageView1.setBackgroundResource(R.drawable.e2);
            break;
          case 1:
            mImageView0.setImageResource(R.drawable.a1);
            mImageView1.setBackgroundResource(R.drawable.a2);
            break;
          case 2:
            mImageView0.setImageResource(R.drawable.b1);
            mImageView1.setBackgroundResource(R.drawable.b2);
            break;
          case 3:
            mImageView0.setImageResource(R.drawable.c1);
            mImageView1.setBackgroundResource(R.drawable.c2);
            break;
          case 4:
            mImageView0.setImageResource(R.drawable.d1);
            mImageView1.setBackgroundResource(R.drawable.d2);
            break;
          default:
            mImageView0.setImageResource(R.drawable.e1);
            mImageView1.setBackgroundResource(R.drawable.e2);
            break;
        }
      }

      @Override
      public void onPageScrollStateChanged(int state) {

      }
    });
  }

  @Override
  protected void onResume() {
    Log.d(LOG_TAG, "onResume");
    super.onResume();
    mCore.getBus().register(this);
    mCore.getHardwareManager().refreshSoon();
    startAttractMode();


    boolean showControls = false;
    if (mConfig.getAllowManualLogin()) {
      mStartButton.setVisibility(View.VISIBLE);
      showControls = true;
    } else {
      mStartButton.setVisibility(View.GONE);
    }

    if (mConfig.getAllowRegistration() && mConfig.useAccounts()) {
      mNewDrinkerButton.setVisibility(View.VISIBLE);
      showControls = true;
    } else {
      mNewDrinkerButton.setVisibility(View.GONE);
    }

    if (showControls && mConfig.getRunCore()) {
      mActivityControls.setVisibility(View.VISIBLE);
    } else {
      mActivityControls.setVisibility(View.GONE);
    }


    if (checkPlayServices()) {
      doGcmRegistration();
    }
  }

  @Override
  protected void onPause() {
    Log.d(LOG_TAG, "onPause");
    mCore.getBus().unregister(this);
    cancelAttractMode();
    super.onPause();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    final int itemId = item.getItemId();
    switch (itemId) {
      case R.id.settings:
        SettingsActivity.startSettingsActivity(this);
        return true;
      case R.id.manageTaps:
        TapListActivity.startActivity(this);
        return true;
      case R.id.bugreport:
        BugreportActivity.startBugreportActivity(this);
        return true;
      case android.R.id.home:
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    Log.d(LOG_TAG, "onNewIntent: Got intent: " + intent);

    if (intent.hasExtra(NfcAdapter.EXTRA_TAG)) {
      Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
      byte[] id = tag.getId();
      if (id != null && id.length > 0) {
        String tagId = HexDump.toHexString(id).toLowerCase(Locale.US);
        Log.d(LOG_TAG, "Read NFC tag with id: " + tagId);
        // TODO: use tag technology as part of id?
        AuthenticatingActivity.startAndAuthenticate(this, "nfc", tagId);
      }
    }
  }

  @Subscribe
  public void onVisibleTapListUpdate(VisibleTapsChangedEvent event) {
    assert(Looper.myLooper() == Looper.getMainLooper());
    Log.d(LOG_TAG, "Got tap list change event: " + event + " taps=" + event.getTaps().size());

    final List<KegTap> newTapList = event.getTaps();
    synchronized (mTapsLock) {
      if (newTapList.equals(mTaps)) {
        Log.d(LOG_TAG, "Tap list unchanged.");
        return;
      }

      mTaps.clear();
      mTaps.addAll(newTapList);
      mTapStatusAdapter.notifyDataSetChanged();
    }

    //for progress bar
    if (mTaps.size() > 0) {
      final KegTap tap = mTaps.get(mTapStatusPager.getCurrentItem());
      if (tap.hasCurrentKeg()) {
        final Models.Keg keg = tap.getCurrentKeg();
        double remainml = keg.getRemainingVolumeMl();
        double totalml = keg.getFullVolumeMl();
        double percent = (remainml) / (totalml) * 100;

        final ProgressBar mTapProgress = (ProgressBar) findViewById(R.id.tapProgress);
        mTapProgress.setMax((int) totalml);
        mTapProgress.setProgress((int) remainml);

        final TextView mTapPercentage = (TextView) findViewById(R.id.tapPercentage);
        mTapPercentage.setText(String.format("%.2f", percent) + "%");
      }
    }

    maybeShowTapWarnings();
  }

  private void maybeShowTapWarnings() {
    final List<KegTap> unboundTaps = Lists.newArrayList();
    synchronized (mTapsLock) {
      for (final KegTap tap : mTaps) {
        if (!tap.hasMeter()) {
          unboundTaps.add(tap);
        }
      }
    }

    if (unboundTaps.isEmpty()) {
      mCore.getAlertCore().cancelAlert(ALERT_ID_UNBOUND_TAPS);
      return;
    }

    final String message;
    final List<String> tapNames = Lists.transform(unboundTaps, TAP_TO_NAME);
    if (tapNames.size() == 1) {
      message = getString(R.string.alert_unbound_single_tap_description,
          tapNames.get(0));
    } else {
      final String listStr = Joiner.on(", ").join(tapNames.subList(0, tapNames.size() - 2));
      message = getString(R.string.alert_unbound_multiple_taps_description, listStr,
          tapNames.get(tapNames.size() - 1));
    }

    mCore.getAlertCore().postAlert(AlertCore.newBuilder(getString(R.string.alert_unbound_title))
        .setId(ALERT_ID_UNBOUND_TAPS)
        .setAction(new Runnable() {
          @Override
          public void run() {
            TapListActivity.startActivity(getApplicationContext());
          }
        })
        .setActionName(getString(R.string.alert_unbound_action_name))
        .setDescription(message)
        .severityWarning()
        .build());
  }

  @Subscribe
  public void onConnectivityChangedEvent(ConnectivityChangedEvent event) {
    updateConnectivityAlert(event.isConnected());
  }

  /**
   * Shows the tap editor for the given tap, prompting for the manager pin if necessary.
   *
   * @param context
   * @param meterName
   */
  static void showTapEditor(Context context, String meterName) {
    final Intent editorIntent = new Intent(context, HomeActivity.class);
    editorIntent.setAction(ACTION_SHOW_TAP_EDITOR);
    editorIntent.putExtra(EXTRA_METER_NAME, meterName);
    editorIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    PinActivity.startThroughPinActivity(context, editorIntent);
  }

  @Override
  public void onUserInteraction() {
    resetAttractMode();
  }

  private void rotateDisplay() {
    final int count = mTapStatusAdapter.getCount();
    if (count <= 1) {
      return;
    }
    final int nextItem = (mTapStatusPager.getCurrentItem() + 1) % mTapStatusAdapter.getCount();
    mTapStatusPager.setCurrentItem(nextItem);
  }

  private void startAttractMode() {
    cancelAttractMode();
    if (mConfig.getEnableAttractMode()) {
      mAttractModeHandler.postDelayed(mAttractModeRunnable, IDLE_TIMEOUT_MILLIS);
    }
  }

  private void resetAttractMode() {
    cancelAttractMode();
    startAttractMode();
  }

  private void cancelAttractMode() {
    mAttractModeHandler.removeCallbacks(mAttractModeRunnable);
  }

  private void doGcmRegistration() {
    final int versionCode = Utils.getOwnPackageInfo(getApplicationContext()).versionCode;
    final int registeredVersionCode = mConfig.getGcmRegistrationAppVersion();
    final String currentRegId = mConfig.getGcmRegistrationId();

    // Fast path: reuse saved id.
    if (versionCode == registeredVersionCode && !Strings.isNullOrEmpty(currentRegId)) {
      return;
    }

    // Destroy stale regid, if any.
    mConfig.setGcmRegistrationId("");

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        Log.d(LOG_TAG, "Registering for GCM ...");
        final GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(HomeActivity.this);
        final String gcmId;
        try {
          gcmId = gcm.register(GCM_SENDER_ID);
        } catch (IOException e) {
          Log.w(LOG_TAG, "GCM registration failed.", e);
          return null;
        }
        mConfig.setGcmRegistrationId(gcmId);
        mConfig.setGcmRegistrationAppVersion(versionCode);
        CheckinService.requestImmediateCheckin(getApplicationContext());
        Log.d(LOG_TAG, "GCM registration success, id=" + gcmId);

        return null;
      }
    }.execute(null, null, null);
  }

  private boolean checkPlayServices() {
    int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
    if (resultCode != ConnectionResult.SUCCESS) {
      if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
        Log.i(LOG_TAG, "GCM error: " + resultCode);
        if (resultCode != mLastShownGooglePlayServicesError) {
          Log.w(LOG_TAG, GooglePlayServicesUtil.getErrorString(resultCode));
          //GooglePlayServicesUtil.getErrorDialog(
          //    resultCode, this, REQUEST_PLAY_SERVICES_UPDATE).show();
          mLastShownGooglePlayServicesError = resultCode;
        }
      }
      return false;
    }
    return true;
  }

  /**
   * Shows a TapStatusFragment for each tap, plus a SystemStatusFragment.
   */
  public class HomeFragmentsAdapter extends SortableFragmentStatePagerAdapter {
    public HomeFragmentsAdapter(FragmentManager fm) {
      super(fm);
    }

    @Override
    public long getItemId(int position) {
      if (position < mTaps.size()) {
        return mTaps.get(position).getId();
      } else if (position == mTaps.size()) {
        return -1;
      }
      throw new IndexOutOfBoundsException("Position out of bounds: " + position);
    }

    @Override
    public Fragment getItem(int index) {
      Log.d(LOG_TAG, "getItem: " + index);
      synchronized (mTapsLock) {
        if (index < mTaps.size()) {
          final KegTap tap = mTaps.get(index);
          TapStatusFragment frag = TapStatusFragment.forTap(mTaps.get(index));
          return frag;
        } else if (index == mTaps.size()) {
          SystemStatusFragment frag = new SystemStatusFragment();
          return frag;
        } else {
          Log.wtf(LOG_TAG, "Trying to get fragment " + index + ", current size " + mTaps.size());
          return null;
        }
      }
    }

    @Override
    public int getItemPosition(Object object) {
      Log.d(LOG_TAG, "getItemPosition: " + object);

      synchronized (mTapsLock) {
        if (object instanceof SystemStatusFragment) {
          Log.d(LOG_TAG, "  position=" + mTaps.size());
          return mTaps.size();
        }

        if (object instanceof TapStatusFragment) {
          final int tapId = ((TapStatusFragment) object).getTapId();
          int position = 0;
          for (final KegTap tap : mTaps) {
            if (tap.getId() == tapId) {
              Log.d(LOG_TAG, "  position=" + position);
              return position;
            }
            position++;
          }
        }
      }

      Log.d(LOG_TAG, "  position=NONE");
      return POSITION_NONE;
    }

    @Override
    public int getCount() {
      synchronized (mTapsLock) {
        return mTaps.size();
      }
    }

    @Override
    public float getPageWidth(int position) {
      return 1.0f;
    }
  }


//  @Override
//  public void onActivityResult(int requestCode, int resultCode, Intent data) {
//    switch (requestCode) {
//      case REQUEST_AUTHENTICATE:
//        Log.d(TAG, "Got authentication result.");
//        if (resultCode == Activity.RESULT_OK && data != null) {
//          final String username =
//                  data.getStringExtra(KegtabCommon.ACTIVITY_AUTH_DRINKER_RESULT_EXTRA_USERNAME);
//          if (!Strings.isNullOrEmpty(username)) {
//            AuthenticatingActivity.startAndAuthenticate(getActivity(), username);
//          }
//        }
//        break;
//      case REQUEST_CREATE_DRINKER:
//        Log.d(TAG, "Got registration result.");
//        if (resultCode == Activity.RESULT_OK && data != null) {
//          final String username =
//                  data.getStringExtra(KegtabCommon.ACTIVITY_CREATE_DRINKER_RESULT_EXTRA_USERNAME);
//          if (!Strings.isNullOrEmpty(username)) {
//            Log.d(TAG, "Authenticating newly-created user.");
//            AuthenticatingActivity.startAndAuthenticate(getActivity(), username);
//          }
//        }
//        break;
//      default:
//        super.onActivityResult(requestCode, resultCode, data);
//    }
//  }

}
