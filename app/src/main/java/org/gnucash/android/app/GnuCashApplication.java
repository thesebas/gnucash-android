/*
 * Copyright (c) 2013 - 2014 Ngewi Fet <ngewif@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gnucash.android.app;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;
import com.crashlytics.android.Crashlytics;

import org.gnucash.android.BuildConfig;
import org.gnucash.android.R;
import org.gnucash.android.db.AccountsDbAdapter;
import org.gnucash.android.db.DatabaseHelper;
import org.gnucash.android.db.ScheduledActionDbAdapter;
import org.gnucash.android.db.SplitsDbAdapter;
import org.gnucash.android.db.TransactionsDbAdapter;
import org.gnucash.android.service.SchedulerService;

import java.util.Currency;
import java.util.Locale;

/**
 * An {@link Application} subclass for retrieving static context
 * @author Ngewi Fet <ngewif@gmail.com>
 *
 */
public class GnuCashApplication extends Application{

    /**
     * Lifetime of passcode session
     */
    public static final long SESSION_TIMEOUT = 5 * 1000;

    /**
     * Init time of passcode session
     */
    public static long PASSCODE_SESSION_INIT_TIME = 0l;

    private static Context context;

    private static DatabaseHelper mDbHelper;

    private static SQLiteDatabase mDb;

    private static AccountsDbAdapter mAccountsDbAdapter;

    private static TransactionsDbAdapter mTransactionsDbAdapter;

    private static SplitsDbAdapter mSplitsDbAdapter;

    private static ScheduledActionDbAdapter mScheduledActionDbAdapter;

    @Override
    public void onCreate(){
        super.onCreate();
        GnuCashApplication.context = getApplicationContext();
        //TODO: in production, only start logging if user gave consent

        if (BuildConfig.USE_CRASHLYTICS)
            Crashlytics.start(this);

        mDbHelper = new DatabaseHelper(getApplicationContext());
        try {
            mDb = mDbHelper.getWritableDatabase();
        } catch (SQLException e) {
            Crashlytics.logException(e);
            Log.e(getClass().getName(), "Error getting database: " + e.getMessage());
            mDb = mDbHelper.getReadableDatabase();
        }
        mSplitsDbAdapter = new SplitsDbAdapter(mDb);
        mTransactionsDbAdapter = new TransactionsDbAdapter(mDb, mSplitsDbAdapter);
        mAccountsDbAdapter = new AccountsDbAdapter(mDb, mTransactionsDbAdapter);
        mScheduledActionDbAdapter = new ScheduledActionDbAdapter(mDb);
    }

    public static AccountsDbAdapter getAccountsDbAdapter() {
        return mAccountsDbAdapter;
    }

    public static TransactionsDbAdapter getTransactionDbAdapter() {
        return mTransactionsDbAdapter;
    }

    public static SplitsDbAdapter getSplitsDbAdapter() {
        return mSplitsDbAdapter;
    }

    public static ScheduledActionDbAdapter getScheduledEventDbAdapter(){
        return mScheduledActionDbAdapter;
    }

    /**
     * Returns the application context
     * @return Application {@link Context} object
     */
    public static Context getAppContext() {
        return GnuCashApplication.context;
    }

    /**
     * Checks if crashlytics is enabled
     * @return {@code true} if crashlytics is enabled, {@code false} otherwise
     */
    public static boolean isCrashlyticsEnabled(){
        final Context context = getAppContext();
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.key_enable_crashlytics), false);
    }

    /**
     * Returns <code>true</code> if double entry is enabled in the app settings, <code>false</code> otherwise.
     * If the value is not set, the default value can be specified in the parameters.
     * @return <code>true</code> if double entry is enabled, <code>false</code> otherwise
     */
    public static boolean isDoubleEntryEnabled(){
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPrefs.getBoolean(context.getString(R.string.key_use_double_entry), false);
    }

    /**
     * Returns <code>true</code> if setting is enabled to save opening balances after deleting transactions,
     * <code>false</code> otherwise.
     * @param defaultValue Default value to return if double entry is not explicitly set
     * @return <code>true</code> if opening balances should be saved, <code>false</code> otherwise
     */
    public static boolean shouldSaveOpeningBalances(boolean defaultValue){
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPrefs.getBoolean(context.getString(R.string.key_save_opening_balances), defaultValue);
    }

    /**
     * Returns the default currency code for the application. <br/>
     * What value is actually returned is determined in this order of priority:<ul>
     *     <li>User currency preference (manually set be user in the app)</li>
     *     <li>Default currency for the device locale</li>
     *     <li>United States Dollars</li>
     * </ul>
     *
     * @return Default currency code string for the application
     */
    public static String getDefaultCurrencyCode(){
        Locale locale = getDefaultLocale();

        String currencyCode = "USD"; //start with USD as the default
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try { //there are some strange locales out there
            currencyCode = Currency.getInstance(locale).getCurrencyCode();
        } catch (Throwable e) {
            Crashlytics.logException(e);
            Log.e(context.getString(R.string.app_name), "" + e.getMessage());
        } finally {
            currencyCode = prefs.getString(context.getString(R.string.key_default_currency), currencyCode);
        }
        return currencyCode;
    }

    /**
     * Returns the default locale which is used for currencies, while handling special cases for
     * locales which are not supported for currency such as en_GB
     * @return The default locale for this device
     */
    public static Locale getDefaultLocale() {
        Locale locale = Locale.getDefault();
        //sometimes the locale en_UK is returned which causes a crash with Currency
        if (locale.getCountry().equals("UK")) {
            locale = new Locale(locale.getLanguage(), "GB");
        }

        //for unsupported locale es_LG
        if (locale.getCountry().equals("LG")){
            locale = new Locale(locale.getLanguage(), "ES");
        }

        if (locale.getCountry().equals("en")){
            locale = Locale.US;
        }
        return locale;
    }

    /**
     * Starts the service for scheduled events and schedules an alarm to call the service twice daily.
     * <p>If the alarm already exists, this method does nothing. If not, the alarm will be created
     * Hence, there is no harm in calling the method repeatedly</p>
     * @param context Application context
     */
    public static void startScheduledActionExecutionService(Context context){
        Intent alarmIntent = new Intent(context, SchedulerService.class);
        PendingIntent pendingIntent = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_NO_CREATE);
        if (pendingIntent != null)
            return;
        else
            pendingIntent = PendingIntent.getService(context, 0, alarmIntent, 0);

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_HALF_DAY,
                pendingIntent);

        context.startService(alarmIntent); //run the service the first time
    }
}