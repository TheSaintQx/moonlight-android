// Путь: app/src/main/java/com/limelight/preferences/KbLoaderPreference.java
// НОВЫЙ файл. Строка «База знаний (JSON)» в настройках: тап открывает системный
// выбор файла, статус загрузки показывается в подстроке строки.
package com.limelight.preferences;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import com.limelight.assistant.AssistantKb;

import java.lang.ref.WeakReference;

public class KbLoaderPreference extends Preference {
    // Ссылка на последний открытый экземпляр: чтобы обновить строку на экране,
    // когда AssistantKb меняет статус (в т.ч. из фонового потока отправки).
    private static WeakReference<Preference> lastInstance = new WeakReference<>(null);

    public KbLoaderPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public KbLoaderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public KbLoaderPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        lastInstance = new WeakReference<>(this);
        AssistantKb.pick(getContext());
    }

    @Override
    public CharSequence getSummary() {
        Context ctx = getContext();
        String name = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(AssistantKb.KEY_KB_NAME, "");
        String status = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(AssistantKb.KEY_KB_STATUS, "");
        if (status.isEmpty()) {
            return "Тап - выбрать файл базы, он сохранится и уйдёт на сервер";
        }
        return (name.isEmpty() ? "" : name + ": ") + status;
    }

    // Обновление строки на экране после смены статуса. Безопасно в любой момент:
    // если настройки уже закрыты, WeakReference пуста - ничего не делаем.
    public static void notifyStatusChanged() {
        Preference p = lastInstance.get();
        if (p != null) {
            try {
                p.setSummary(p.getSummary());
            } catch (Throwable ignored) {
                // настройка могла отвалиться от экрана - это не ошибка
            }
        }
    }
}
