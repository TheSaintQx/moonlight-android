// Путь: app/src/main/java/com/limelight/preferences/AssistantStatusPreference.java
// НОВЫЙ файл. Строка в настройках, которая показывает статус последней отправки кадра.
package com.limelight.preferences;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import com.limelight.assistant.AssistantSettings;

public class AssistantStatusPreference extends Preference {
    public AssistantStatusPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public AssistantStatusPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AssistantStatusPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    public CharSequence getSummary() {
        return PreferenceManager.getDefaultSharedPreferences(getContext())
                .getString(AssistantSettings.KEY_STATUS, "ещё не отправляли: запустите игру");
    }
}
