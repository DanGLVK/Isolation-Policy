package io.github.mhmrdd.isolationpolicy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class MainActivity extends Activity {

    private static final long FILTER_DEBOUNCE_MS = 60L;
    private static final long TEST_BIND_TIMEOUT_MS = 6000L;

    private TextView mStatus;
    private EditText mSearch;
    private RecyclerView mList;
    private ProgressBar mProgress;
    private Button mApply;

    private final Set<String> mDenied = new TreeSet<String>();
    private final Adapter mAdapter = new Adapter();
    private int mEligibleCount = 0;
    private String mTestHeadline = "isolated service : binding ...";
    private String mTestDetail = "";
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final HandlerThread mBgThread = new HandlerThread("isolpolicy-bg");
    private Handler mBg;
    private String mFilter = "";
    private final Runnable mApplyFilter = new Runnable() {
        @Override public void run() { mAdapter.setFilter(mFilter); }
    };
    private ServiceConnection mTestConnection;
    private Runnable mTestTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());

        mBgThread.start();
        mBg = new Handler(mBgThread.getLooper());

        loadDenied();
        refreshStatus();
        mBg.post(new ScanRunnable());
        runTest();
    }

    @Override
    protected void onDestroy() {
        cancelPendingTest();
        mBgThread.quitSafely();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs() {
        try {
            return getSharedPreferences(Constants.PREFS_NAME, MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        }
    }

    private void loadDenied() {
        mDenied.clear();
        Set<String> stored = prefs().getStringSet(Constants.KEY_DENIED, null);
        if (stored != null) mDenied.addAll(stored);
        mDenied.add(Constants.APPLICATION_ID);
    }

    private void saveDenied() {
        Set<String> out = new HashSet<String>(mDenied);
        out.add(Constants.APPLICATION_ID);
        prefs().edit().putStringSet(Constants.KEY_DENIED, out).apply();
        if (BuildConfig.DEBUG) {
            android.util.Log.i(Constants.TAG, "wrote policy " + Constants.PREFS_NAME + " size=" + out.size());
        }
    }

    private class ScanRunnable implements Runnable {
        @Override
        public void run() {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            List<Item> built = new ArrayList<Item>(all.size());
            int eligible = 0;
            String selfPkg = Constants.APPLICATION_ID;
            for (ApplicationInfo ai : all) {
                boolean isSelf = selfPkg.equals(ai.packageName);
                boolean isSystem = ai.uid < 10000
                        || ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0);
                Item.Kind k = isSelf ? Item.Kind.SELF
                        : isSystem ? Item.Kind.SYSTEM
                        : Item.Kind.USER;
                String label;
                try { label = ai.loadLabel(pm).toString(); } catch (Throwable t) { label = ai.packageName; }
                Drawable icon = null;
                try { icon = ai.loadIcon(pm); } catch (Throwable ignored) {}
                built.add(new Item(ai.packageName, label, icon, k));
                if (k == Item.Kind.USER) eligible++;
            }
            Collections.sort(built, ITEM_COMPARATOR);
            final int eligibleCount = eligible;
            mUi.post(new ApplyScanResult(built, eligibleCount));
        }
    }

    private class ApplyScanResult implements Runnable {
        private final List<Item> mResult;
        private final int mEligible;
        ApplyScanResult(List<Item> r, int e) { this.mResult = r; this.mEligible = e; }
        @Override
        public void run() {
            mEligibleCount = mEligible;
            mAdapter.setItems(mResult);
            refreshStatus();
            if (mProgress != null) mProgress.setVisibility(View.GONE);
            if (mList != null) mList.setVisibility(View.VISIBLE);
        }
    }

    private void rescan() {
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        if (mList != null) mList.setVisibility(View.GONE);
        mAdapter.setItems(Collections.<Item>emptyList());
        mEligibleCount = 0;
        refreshStatus();
        mBg.post(new ScanRunnable());
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("module           : ").append(ModuleStatus.isActive() ? "active" : "disabled").append('\n');
        sb.append(mTestHeadline).append('\n');
        if (!mTestDetail.isEmpty()) sb.append(mTestDetail).append('\n');
        sb.append("eligible: ").append(mEligibleCount)
                .append("    selected: ").append(Math.max(mDenied.size() - 1, 0));
        mStatus.setText(sb.toString());
    }

    private void runTest() {
        mTestHeadline = "isolated service : binding ...";
        mTestDetail = "";
        refreshStatus();
        cancelPendingTest();
        final ServiceConnection conn = new ServiceConnection() {
            private boolean done;
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (done) return;
                done = true;
                deliverTestResult(service);
                cancelPendingTest();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
            @Override
            public void onNullBinding(ComponentName name) {
                if (done) return;
                done = true;
                mTestHeadline = "isolated service : not bound";
                mTestDetail = "reason : null binding";
                refreshStatus();
                cancelPendingTest();
            }
        };
        mTestConnection = conn;
        Intent intent = new Intent(this, IsolationPolicyService.class);
        boolean bound;
        try {
            bound = bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                    "isolationpolicy", getMainExecutor(), conn);
        } catch (Throwable t) {
            bound = false;
            mTestHeadline = "isolated service : not bound";
            mTestDetail = "reason : bind threw " + t.getClass().getSimpleName();
            refreshStatus();
        }
        if (!bound) {
            mTestConnection = null;
            mTestHeadline = "isolated service : not bound";
            mTestDetail = "reason : bindIsolatedService returned false";
            refreshStatus();
            return;
        }
        mTestTimeout = new Runnable() {
            @Override
            public void run() {
                if (mTestConnection != conn) return;
                try { unbindService(conn); } catch (Throwable ignored) {}
                mTestConnection = null;
                mTestTimeout = null;
                mTestHeadline = "isolated service : not bound";
                mTestDetail = "reason : connection timed out";
                refreshStatus();
            }
        };
        mUi.postDelayed(mTestTimeout, TEST_BIND_TIMEOUT_MS);
    }

    private void cancelPendingTest() {
        if (mTestTimeout != null) {
            mUi.removeCallbacks(mTestTimeout);
            mTestTimeout = null;
        }
        if (mTestConnection != null) {
            try { unbindService(mTestConnection); } catch (Throwable ignored) {}
            mTestConnection = null;
        }
    }

    private void deliverTestResult(IBinder service) {
        if (service == null) {
            mTestHeadline = "isolated service : not bound";
            mTestDetail = "reason : null service";
            refreshStatus();
            return;
        }
        try {
            IIsolationPolicyService server = IIsolationPolicyService.Stub.asInterface(service);
            String report = server.getReport();
            mTestHeadline = "isolated service : bound";
            mTestDetail = report != null ? report : "";
        } catch (RemoteException e) {
            mTestHeadline = "isolated service : transact failed";
            mTestDetail = "reason : " + e;
        }
        refreshStatus();
    }

    private View buildLayout() {
        int gray = Color.parseColor("#FF1E1E1E");
        int faint = Color.parseColor("#FF2C2C2C");
        int textC = Color.parseColor("#FFE8E8E8");
        int sub = Color.parseColor("#FFB0B0B0");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(gray);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        mStatus = new TextView(this);
        mStatus.setTypeface(Typeface.MONOSPACE);
        mStatus.setTextColor(textC);
        mStatus.setTextSize(13f);
        mStatus.setBackgroundColor(faint);
        mStatus.setPadding(dp(10), dp(8), dp(10), dp(8));
        mStatus.setTextIsSelectable(true);
        root.addView(mStatus, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);

        mSearch = new EditText(this);
        mSearch.setHint("filter by name or package");
        mSearch.setTextColor(textC);
        mSearch.setHintTextColor(sub);
        mSearch.setTextSize(13f);
        mSearch.setSingleLine(true);
        mSearch.setBackgroundColor(faint);
        mSearch.setPadding(dp(10), dp(8), dp(10), dp(8));
        mSearch.addTextChangedListener(new SearchWatcher());
        searchRow.addView(mSearch, new LinearLayout.LayoutParams(0, WRAP, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setTextSize(13f);
        refresh.setOnClickListener(new RefreshClick());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(WRAP, WRAP);
        rp.leftMargin = dp(6);
        searchRow.addView(refresh, rp);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
        sp.topMargin = dp(8);
        root.addView(searchRow, sp);

        FrameLayout listArea = new FrameLayout(this);
        listArea.setBackgroundColor(faint);
        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setAdapter(mAdapter);
        mList.setHasFixedSize(true);
        mList.setVisibility(View.GONE);
        listArea.addView(mList, new FrameLayout.LayoutParams(MATCH, MATCH));
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        mProgress.setIndeterminate(true);
        mProgress.setIndeterminateTintList(ColorStateList.valueOf(Color.parseColor("#FF03A9F4")));
        FrameLayout.LayoutParams pgp = new FrameLayout.LayoutParams(WRAP, WRAP);
        pgp.gravity = Gravity.CENTER;
        listArea.addView(mProgress, pgp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(listArea, lp);

        mApply = new Button(this);
        mApply.setText("Apply changes");
        mApply.setOnClickListener(new ApplyClick());
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(MATCH, WRAP);
        brp.topMargin = dp(8);
        root.addView(mApply, brp);
        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final class Item {
        enum Kind { USER, SYSTEM, SELF }
        final String pkg;
        final String label;
        final String labelLower;
        final String pkgLower;
        final Drawable icon;
        final Kind kind;
        Item(String pkg, String label, Drawable icon, Kind kind) {
            this.pkg = pkg;
            this.label = label;
            this.labelLower = label.toLowerCase();
            this.pkgLower = pkg.toLowerCase();
            this.icon = icon;
            this.kind = kind;
        }
    }

    private final java.util.Comparator<Item> ITEM_COMPARATOR = new java.util.Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            int oa = order(a);
            int ob = order(b);
            if (oa != ob) return oa - ob;
            int byLabel = a.labelLower.compareTo(b.labelLower);
            if (byLabel != 0) return byLabel;
            return a.pkg.compareTo(b.pkg);
        }

        private int order(Item it) {
            if (it.kind == Item.Kind.SELF) return 0;
            if (it.kind == Item.Kind.SYSTEM) return 3;
            return mDenied.contains(it.pkg) ? 1 : 2;
        }
    };

    private class Adapter extends RecyclerView.Adapter<RowVH> {
        private final List<Item> mAll = new ArrayList<Item>();
        private final List<Item> mShown = new ArrayList<Item>();
        private String mAdapterFilter = "";

        void setItems(List<Item> items) {
            mAll.clear();
            mAll.addAll(items);
            recompute();
        }

        void setFilter(String f) {
            mAdapterFilter = f == null ? "" : f.toLowerCase();
            recompute();
        }

        private void recompute() {
            mShown.clear();
            if (mAdapterFilter.isEmpty()) {
                mShown.addAll(mAll);
            } else {
                for (Item it : mAll) {
                    if (it.labelLower.contains(mAdapterFilter) || it.pkgLower.contains(mAdapterFilter)) {
                        mShown.add(it);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public RowVH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RowVH(buildRowView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RowVH holder, int position) {
            holder.bind(mShown.get(position));
        }

        @Override
        public int getItemCount() { return mShown.size(); }
    }

    private static View buildRowView(Context ctx) {
        int activeC = Color.parseColor("#FFE8E8E8");
        int dimC = Color.parseColor("#FF707070");
        int subC = Color.parseColor("#FFA0A0A0");

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        row.setPadding(pad, (int) (6 * ctx.getResources().getDisplayMetrics().density),
                pad, (int) (6 * ctx.getResources().getDisplayMetrics().density));
        row.setLayoutParams(new RecyclerView.LayoutParams(MATCH, WRAP));

        ImageView icon = new ImageView(ctx);
        icon.setId(android.R.id.icon);
        int iconSize = (int) (40 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.rightMargin = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        row.addView(icon, ip);

        LinearLayout vbox = new LinearLayout(ctx);
        vbox.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(ctx);
        label.setId(android.R.id.text1);
        label.setTextSize(15f);
        label.setTextColor(activeC);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        TextView pkg = new TextView(ctx);
        pkg.setId(android.R.id.text2);
        pkg.setTextSize(12f);
        pkg.setTextColor(subC);
        pkg.setTypeface(Typeface.MONOSPACE);
        vbox.addView(label);
        vbox.addView(pkg);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        row.addView(vbox, vp);

        CheckBox cb = new CheckBox(ctx);
        cb.setId(android.R.id.checkbox);
        row.addView(cb);

        return row;
    }

    private class RowVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox cb;
        Item bound;

        RowVH(View v) {
            super(v);
            icon = v.findViewById(android.R.id.icon);
            label = v.findViewById(android.R.id.text1);
            pkg = v.findViewById(android.R.id.text2);
            cb = v.findViewById(android.R.id.checkbox);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean on) {
                    if (bound == null || bound.kind != Item.Kind.USER) return;
                    if (on) mDenied.add(bound.pkg); else mDenied.remove(bound.pkg);
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (cb.isEnabled()) cb.setChecked(!cb.isChecked());
                }
            });
        }

        void bind(Item it) {
            bound = it;
            int active = Color.parseColor("#FFE8E8E8");
            int dim = Color.parseColor("#FF707070");
            int sub = Color.parseColor("#FFA0A0A0");
            icon.setImageDrawable(it.icon);
            String prefix = it.kind == Item.Kind.SELF ? "[self] "
                    : it.kind == Item.Kind.SYSTEM ? "[oem] "
                    : "";
            label.setText(prefix + it.label);
            label.setTextColor(it.kind == Item.Kind.USER ? active : dim);
            pkg.setText(it.pkg);
            pkg.setTextColor(it.kind == Item.Kind.USER ? sub : dim);
            cb.setOnCheckedChangeListener(null);
            if (it.kind == Item.Kind.SELF) {
                cb.setChecked(true);
                cb.setEnabled(false);
            } else if (it.kind == Item.Kind.SYSTEM) {
                cb.setChecked(false);
                cb.setEnabled(false);
            } else {
                cb.setChecked(mDenied.contains(it.pkg));
                cb.setEnabled(true);
            }
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean on) {
                    if (bound == null || bound.kind != Item.Kind.USER) return;
                    if (on) mDenied.add(bound.pkg); else mDenied.remove(bound.pkg);
                }
            });
        }
    }

    private class ApplyClick implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            saveDenied();
            refreshStatus();
        }
    }

    private class SearchWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void afterTextChanged(Editable s) {}
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mFilter = s.toString();
            mUi.removeCallbacks(mApplyFilter);
            mUi.postDelayed(mApplyFilter, FILTER_DEBOUNCE_MS);
        }
    }

    private class RefreshClick implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            rescan();
        }
    }
}
