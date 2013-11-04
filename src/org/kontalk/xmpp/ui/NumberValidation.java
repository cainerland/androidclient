/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.xmpp.ui;

import java.io.IOException;
import java.net.SocketException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.kontalk.xmpp.BuildConfig;
import org.kontalk.xmpp.Kontalk;
import org.kontalk.xmpp.R;
import org.kontalk.xmpp.authenticator.Authenticator;
import org.kontalk.xmpp.client.EndpointServer;
import org.kontalk.xmpp.client.NumberValidator;
import org.kontalk.xmpp.client.NumberValidator.NumberValidatorListener;
import org.kontalk.xmpp.crypto.PersonalKey;
import org.kontalk.xmpp.service.KeyPairGeneratorService;
import org.kontalk.xmpp.sync.SyncAdapter;
import org.kontalk.xmpp.ui.CountryCodesAdapter.CountryCode;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.MenuItemCompat;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.NumberParseException.ErrorType;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;


/** Number validation activity. */
public class NumberValidation extends AccountAuthenticatorActionBarActivity
        implements NumberValidatorListener {
    private static final String TAG = NumberValidation.class.getSimpleName();

    public static final int REQUEST_MANUAL_VALIDATION = 771;
    public static final int REQUEST_VALIDATION_CODE = 772;

    public static final String ACTION_LOGIN = "org.kontalk.sync.LOGIN";

    public static final String PARAM_AUTHTOKEN_TYPE = "org.kontalk.authtokenType";
    public static final String PARAM_FROM_INTERNAL = "org.kontalk.internal";

    public static final String PARAM_AUTHTOKEN = "org.kontalk.authtoken";
    public static final String PARAM_PUBLICKEY = "org.kontalk.publickey";
    public static final String PARAM_PRIVATEKEY = "org.kontalk.privatekey";

    private AccountManager mAccountManager;
    private Spinner mCountryCode;
    private EditText mPhone;
    private Button mValidateButton;
    private Button mInsertCode;
    private ProgressDialog mProgress;
    private CharSequence mProgressMessage;
    private NumberValidator mValidator;
    private Handler mHandler;

    private String mAuthtoken;
    private String mAuthtokenType;
    private String mPhoneNumber;

    private PersonalKey mKey;
    private LocalBroadcastManager lbm;

    private boolean mFromInternal;
    /** Runnable for delaying initial manual sync starter. */
    private Runnable mSyncStart;
    private boolean mSyncing;

    private KeyGeneratedReceiver mKeyReceiver;

    private static final class RetainData {
        NumberValidator validator;
        CharSequence progressMessage;
        String phoneNumber;
        PersonalKey key;
        boolean syncing;
    }

    private interface PersonalKeyRunnable {
        public void run(PersonalKey key);
    }

    private final static class KeyGeneratedReceiver extends BroadcastReceiver {
        private final Handler handler;
        private final PersonalKeyRunnable action;

        public KeyGeneratedReceiver(Handler handler, PersonalKeyRunnable action) {
            this.handler = handler;
            this.action = action;
        }

        @Override
        public void onReceive(Context context, final Intent intent) {
            if (KeyPairGeneratorService.ACTION_GENERATE.equals(intent.getAction())) {
                // we can stop the service now
                context.stopService(new Intent(context, KeyPairGeneratorService.class));

                handler.post(new Runnable() {
                    public void run() {
                        PersonalKey key = intent.getParcelableExtra(KeyPairGeneratorService.EXTRA_KEY);
                        action.run(key);
                    }
                });
            }
        }

    }

    /**
     * Compatibility method for {@link PhoneNumberUtil#getSupportedRegions()}.
     * This was introduced because crappy Honeycomb has an old version of
     * libphonenumber, therefore Dalvik will insist on we using it.
     * In case getSupportedRegions doesn't exist, getSupportedCountries will be
     * used.
     */
    @SuppressWarnings("unchecked")
    private Set<String> getSupportedRegions(PhoneNumberUtil util) {
        try {
            return (Set<String>) util.getClass()
                .getMethod("getSupportedRegions")
                .invoke(util);
        }
        catch (NoSuchMethodException e) {
            try {
                return (Set<String>) util.getClass()
                    .getMethod("getSupportedCountries")
                    .invoke(util);
            }
            catch (Exception helpme) {
                // ignored
            }
        }
        catch (Exception e) {
            // ignored
        }

        return new HashSet<String>();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.number_validation);

        mAccountManager = AccountManager.get(this);
        mHandler = new Handler();

        lbm = LocalBroadcastManager.getInstance(getApplicationContext());

        final Intent intent = getIntent();
        mAuthtokenType = intent.getStringExtra(PARAM_AUTHTOKEN_TYPE);
        mFromInternal = intent.getBooleanExtra(PARAM_FROM_INTERNAL, false);

        mCountryCode = (Spinner) findViewById(R.id.phone_cc);
        mPhone = (EditText) findViewById(R.id.phone_number);
        mValidateButton = (Button) findViewById(R.id.button_validate);
        mInsertCode = (Button) findViewById(R.id.button_validation_code);

        // populate country codes
        final CountryCodesAdapter ccList = new CountryCodesAdapter(this, R.layout.country_item, R.layout.country_dropdown_item);
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        Set<String> ccSet = getSupportedRegions(util);
        for (String cc : ccSet)
            ccList.add(cc);

        ccList.sort(new Comparator<CountryCodesAdapter.CountryCode>() {
            public int compare(CountryCodesAdapter.CountryCode lhs, CountryCodesAdapter.CountryCode rhs) {
                return lhs.regionName.compareTo(rhs.regionName);
            }
        });
        mCountryCode.setAdapter(ccList);
        mCountryCode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ccList.setSelected(position);
            }
            public void onNothingSelected(AdapterView<?> parent) {
                // TODO Auto-generated method stub
            }
        });

        // FIXME this doesn't consider creation because of configuration change
        PhoneNumber myNum = NumberValidator.getMyNumber(this);
        if (myNum != null) {
            mPhone.setText(String.valueOf(myNum.getNationalNumber()));
            Log.d(TAG, "selecting country " + util.getRegionCodeForNumber(myNum));
            CountryCode cc = new CountryCode();
            cc.regionCode = util.getRegionCodeForNumber(myNum);
            cc.countryCode = myNum.getCountryCode();
            mCountryCode.setSelection(ccList.getPositionForId(cc));
        }
        else {
            final TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            final String regionCode = tm.getSimCountryIso().toUpperCase(Locale.US);
            CountryCode cc = new CountryCode();
            cc.regionCode = regionCode;
            cc.countryCode = util.getCountryCodeForRegion(regionCode);
            mCountryCode.setSelection(ccList.getPositionForId(cc));
        }

        // configuration change??
        RetainData data = (RetainData) getLastCustomNonConfigurationInstance();
        if (data != null) {
            synchronized (this) {
                // sync starter was queued, we can exit
                if (data.syncing) {
                    delayedSync();
                }

                mPhoneNumber = data.phoneNumber;
                mValidator = data.validator;
                mKey = data.key;
                if (mValidator != null)
                    mValidator.setListener(this);
            }
            if (data.progressMessage != null) {
                setProgressMessage(data.progressMessage, true);
            }
        }

        if (savedInstanceState != null) {
            mPhoneNumber = savedInstanceState.getString("phoneNumber");
            mKey = savedInstanceState.getParcelable("key");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("phoneNumber", mPhoneNumber);
        state.putParcelable("key", mKey);
    }

    /** Returning the validator thread. */
    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        RetainData data = new RetainData();
        data.validator = mValidator;
        data.phoneNumber = mPhoneNumber;
        data.key = mKey;
        if (mProgress != null) data.progressMessage = mProgressMessage;
        data.syncing = mSyncing;
        return data;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.number_validation_menu, menu);
        MenuItem item = menu.findItem(R.id.menu_settings);
        MenuItemCompat.setShowAsAction(item, MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_settings: {
                Intent intent = new Intent(this, BootstrapPreferences.class);
                startActivityIfNeeded(intent, -1);
                break;
            }
            default:
                return true;
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (mKey == null) {
            PersonalKeyRunnable action = new PersonalKeyRunnable() {
                public void run(PersonalKey key) {
                    mKey = key;
                    if (mValidator != null)
                        // this will release the waiting lock
                        mValidator.setKey(mKey);
                }
            };

            mKeyReceiver = new KeyGeneratedReceiver(mHandler, action);

            IntentFilter filter = new IntentFilter(KeyPairGeneratorService.ACTION_GENERATE);
            lbm.registerReceiver(mKeyReceiver, filter);

            // TODO i18n
            Toast.makeText(this, "Generating keypair in the background.",
                Toast.LENGTH_LONG).show();

            Intent i = new Intent(this, KeyPairGeneratorService.class);
            i.setAction(KeyPairGeneratorService.ACTION_GENERATE);
            startService(i);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        keepScreenOn(false);

        if (mKeyReceiver != null)
            lbm.unregisterReceiver(mKeyReceiver);

        if (mProgress != null) {
            if (isFinishing())
                mProgress.cancel();
            else
                mProgress.dismiss();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        keepScreenOn(false);
        if (mProgress != null)
            mProgress.cancel();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MANUAL_VALIDATION && resultCode == RESULT_OK) {
            finishLogin(data.getStringExtra(PARAM_AUTHTOKEN), data.getByteArrayExtra(PARAM_PRIVATEKEY), data.getByteArrayExtra(PARAM_PUBLICKEY));
        }
    }

    private void keepScreenOn(boolean active) {
        if (active)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Starts the validation activity. */
    public static void startValidation(Context context) {
        Intent i = new Intent(context, NumberValidation.class);
        i.putExtra(PARAM_FROM_INTERNAL, true);
        context.startActivity(i);
    }

    private void enableControls(boolean enabled) {
        mValidateButton.setEnabled(enabled);
        mInsertCode.setEnabled(enabled);
        mCountryCode.setEnabled(enabled);
        mPhone.setEnabled(enabled);
    }

    private void error(int title, int message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(android.R.string.ok, null)
            .show();
    }

    private boolean checkInput() {
        mPhoneNumber = null;
        String phoneStr = null;

        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        CountryCode cc = (CountryCode) mCountryCode.getSelectedItem();
        if (!BuildConfig.DEBUG) {
            PhoneNumber phone;
            try {
                phone = util.parse(mPhone.getText().toString(), cc.regionCode);
                if (!util.isValidNumberForRegion(phone, cc.regionCode)) {
                    throw new NumberParseException(ErrorType.INVALID_COUNTRY_CODE, "invalid number for region " + cc.regionCode);
                }
            }
            catch (NumberParseException e1) {
                error(R.string.title_invalid_number, R.string.msg_invalid_number);
                return false;
            }

            // check phone number format
            if (phone != null) {
                phoneStr = util.format(phone, PhoneNumberFormat.E164);
                if (!PhoneNumberUtils.isWellFormedSmsAddress(phoneStr)) {
                    Log.i(TAG, "not a well formed SMS address");
                }
            }
        }
        else {
            phoneStr = String.format(Locale.US, "+%d%s", cc.countryCode, mPhone.getText().toString());
        }

        // phone is null - invalid number
        if (phoneStr == null) {
            Toast.makeText(this, R.string.warn_invalid_number, Toast.LENGTH_SHORT)
                .show();
            return false;
        }

        Log.v(TAG, "Using phone number to register: " + phoneStr);
        mPhoneNumber = phoneStr;
        return true;
    }

    private void startValidation() {
        enableControls(false);

        if (!checkInput()) {
            enableControls(true);
        }
        else {
            // start async request
            Log.d(TAG, "phone number checked, sending validation request");
            startProgress();

            // key generation finished, start immediately
            EndpointServer server = MessagingPreferences.getEndpointServer(this);
            mValidator = new NumberValidator(this, server, mPhoneNumber, mKey);
            mValidator.setListener(this);
            mValidator.start();
        }
    }

    /**
     * Begins validation of the phone number.
     * Also used by the view definition as the {@link OnClickListener}.
     * @param v not used
     */
    public void validatePhone(View v) {
        keepScreenOn(true);
        startValidation();
    }

    /**
     * Opens manual validation window immediately.
     * Also used by the view definition as the {@link OnClickListener}.
     * @param v not used
     */
    public void validateCode(View v) {
        if (checkInput())
            startValidationCode(REQUEST_VALIDATION_CODE);
    }

    /** No search here. */
    @Override
    public boolean onSearchRequested() {
        return false;
    }

    public void startProgress() {
        startProgress(null);
    }

    private void startProgress(CharSequence message) {
        if (mProgress == null) {
            mProgress = new NonSearchableProgressDialog(this);
            mProgress.setIndeterminate(true);
            mProgress.setCanceledOnTouchOutside(false);
            setProgressMessage(message != null ? message : getText(R.string.msg_validating_phone));
            mProgress.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    keepScreenOn(false);
                    Toast.makeText(NumberValidation.this, R.string.msg_validation_canceled, Toast.LENGTH_LONG).show();
                    abort();
                }
            });
            mProgress.setOnDismissListener(new OnDismissListener() {
                public void onDismiss(DialogInterface dialog) {
                    // remove sync starter
                    if (mSyncStart != null) {
                        mHandler.removeCallbacks(mSyncStart);
                        mSyncStart = null;
                    }
                }
            });
        }
        mProgress.show();
    }

    public void abortProgress() {
        if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
    }

    public void abortProgress(boolean enableControls) {
        abortProgress();
        enableControls(enableControls);
    }

    public void abort() {
        abort(false);
    }

    public void abort(boolean ending) {
        if (!ending) {
            abortProgress(true);
        }

        if (mValidator != null) {
            mValidator.shutdown();
            mValidator = null;
        }
    }

    private void setProgressMessage(CharSequence message) {
        setProgressMessage(message, false);
    }

    private void setProgressMessage(CharSequence message, boolean create) {
        if (mProgress == null && create) {
            startProgress(message);
        }

        if (mProgress != null) {
            mProgressMessage = message;
            mProgress.setMessage(message);
        }
    }

    private void delayedSync() {
        mSyncing = true;
        mSyncStart = new Runnable() {
            public void run() {
                // start has been requested
                mSyncStart = null;

                // start sync
                SyncAdapter.requestSync(NumberValidation.this, true);

                // if we have been called internally, start ConversationList
                if (mFromInternal)
                    startActivity(new Intent(getApplicationContext(), ConversationList.class));

                Toast.makeText(getApplicationContext(), R.string.msg_authenticated, Toast.LENGTH_LONG).show();

                // end this
                abortProgress();
                finish();
            }
        };

        /*
         * This is a workaround for API level... I don't know since when :D
         * Seems that requesting sync too soon after account creation has no
         * effect. We delay sync by some time to give it time to settle.
         */
        mHandler.postDelayed(mSyncStart, 2000);
    }

    /** @deprecated {@link CodeValidation} handles this now. */
    @Override
    @Deprecated
    public void onAuthTokenReceived(NumberValidator v, CharSequence token, byte[] privateKey, byte[] publicKey) {
    }

    @Override
    public void onAuthTokenFailed(NumberValidator v, int reason) {
        Log.e(TAG, "authentication token request failed (" + reason + ")");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                keepScreenOn(false);
                Toast.makeText(NumberValidation.this,
                        R.string.err_authentication_failed,
                        Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    protected void finishLogin(String token, byte[] privateKeyData, byte[] publicKeyData) {
        Log.v(TAG, "finishing login");

        // update public key
        try {
            mKey.update(publicKeyData);
        }
        catch (IOException e) {
            Log.v(TAG, "error decoding public key", e);
            // TODO what now??
        }

        if (mProgress == null)
            startProgress();
        mProgress.setCancelable(false);
        setProgressMessage(getString(R.string.msg_initializing));

        final Account account = new Account(mPhoneNumber, Authenticator.ACCOUNT_TYPE);
        mAuthtoken = token;

        // the password is actually the auth token
        mAccountManager.addAccountExplicitly(account, mAuthtoken, null);
        mAccountManager.setUserData(account, Authenticator.DATA_PRIVATEKEY, Base64.encodeToString(privateKeyData, Base64.NO_WRAP));
        mAccountManager.setUserData(account, Authenticator.DATA_PUBLICKEY, Base64.encodeToString(publicKeyData, Base64.NO_WRAP));
        // Set contacts sync for this account.
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true);
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);

        // send back result
        final Intent intent = new Intent();
        intent.putExtra(AccountManager.KEY_ACCOUNT_NAME, mPhoneNumber);
        intent.putExtra(AccountManager.KEY_ACCOUNT_TYPE, Authenticator.ACCOUNT_TYPE);
        if (mAuthtokenType != null
            && mAuthtokenType.equals(Authenticator.AUTHTOKEN_TYPE)) {
            intent.putExtra(AccountManager.KEY_AUTHTOKEN, mAuthtoken);
        }
        setAccountAuthenticatorResult(intent.getExtras());
        setResult(RESULT_OK, intent);

        // ok enable services
        Kontalk.setServicesEnabled(this, true);

        // manual sync starter
        delayedSync();
    }

    @Override
    public void onError(NumberValidator v, final Throwable e) {
        Log.e(TAG, "validation error.", e);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                keepScreenOn(false);
                int msgId;
                if (e instanceof SocketException)
                    msgId = R.string.err_validation_network_error;
                else
                    msgId = R.string.err_validation_error;
                Toast.makeText(NumberValidation.this, msgId, Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    @Override
    public void onServerCheckFailed(NumberValidator v) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NumberValidation.this, R.string.err_validation_server_not_supported, Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    @Override
    public void onValidationFailed(NumberValidator v, int reason) {
        Log.e(TAG, "phone number validation failed (" + reason + ")");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(NumberValidation.this, R.string.err_validation_failed, Toast.LENGTH_LONG).show();
                abort();
            }
        });
    }

    @Override
    public void onValidationRequested(NumberValidator v) {
        Log.d(TAG, "validation has been requested, requesting validation code to user");
        proceedManual();
    }

    /** Proceeds to the next step in manual validation. */
    private void proceedManual() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                abortProgress(true);
                startValidationCode(REQUEST_MANUAL_VALIDATION);
            }
        });
    }

    private void startValidationCode(int requestCode) {
        Intent i = new Intent(NumberValidation.this, CodeValidation.class);
        i.putExtra("requestCode", requestCode);
        i.putExtra("phone", mPhoneNumber);
        i.putExtra(KeyPairGeneratorService.EXTRA_KEY, mKey);
        startActivityForResult(i, REQUEST_MANUAL_VALIDATION);
    }
}
