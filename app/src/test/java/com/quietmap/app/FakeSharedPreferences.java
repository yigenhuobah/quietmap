package com.quietmap.app;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 纯 JVM 的 SharedPreferences 替身：只实现 StatusProvider.applyReport 用到的
 * 路径，并记录 commit/apply 次数，用来验证「崩溃路径同步落盘」的语义。
 */
final class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> mValues = new HashMap<>();
    private boolean mCommitResult = true;
    private boolean mThrowOnCommit;
    private int mCommitCount;
    private int mApplyCount;

    void setCommitResult(boolean result) {
        mCommitResult = result;
    }

    void setThrowOnCommit(boolean throwOnCommit) {
        mThrowOnCommit = throwOnCommit;
    }

    Map<String, Object> values() {
        return mValues;
    }

    int commitCount() {
        return mCommitCount;
    }

    int applyCount() {
        return mApplyCount;
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(mValues);
    }

    @Override
    public String getString(String key, String defValue) {
        Object value = mValues.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = mValues.get(key);
        return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object value = mValues.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object value = mValues.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object value = mValues.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object value = mValues.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public boolean contains(String key) {
        return mValues.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    private final class FakeEditor implements Editor {

        private final Map<String, Object> mPending = new HashMap<>();
        private final Set<String> mRemoved = new HashSet<>();
        private boolean mClear;

        @Override
        public Editor putString(String key, String value) {
            mPending.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            mPending.put(key, values == null ? null : new HashSet<>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            mPending.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            mPending.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            mPending.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            mPending.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            mRemoved.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            mClear = true;
            return this;
        }

        @Override
        public boolean commit() {
            mCommitCount++;
            if (mThrowOnCommit) {
                throw new IllegalStateException("commit boom");
            }
            if (!mCommitResult) {
                return false;
            }
            applyPending();
            return true;
        }

        @Override
        public void apply() {
            mApplyCount++;
            if (mThrowOnCommit) {
                throw new IllegalStateException("apply boom");
            }
            applyPending();
        }

        private void applyPending() {
            if (mClear) {
                mValues.clear();
            }
            for (String key : mRemoved) {
                mValues.remove(key);
            }
            mValues.putAll(mPending);
        }
    }
}
