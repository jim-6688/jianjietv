package com.gathin.tvhome;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;

/**
 * The home background: a real weather photograph for today's condition and the time of day.
 *
 * The condition comes from {@link #setWeather(String)} (the same text HomeWeather reads), and day or
 * night from the sun's rise and set at config.xml's latitude and longitude (or a fixed temperate day
 * without a place). The picture is a 1920x1080 photo in drawable-nodpi, so it fills the screen with
 * no drawing cost: it is only swapped when the weather or the day/night state actually changes.
 * Off by default (config.xml, sky_intensity = 0); a theme turns it on with {@code "sky_intensity": 100}.
 */
public class SkyView extends View {

    /** Full-screen and faded with setAlpha: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Weather kinds, from the condition text HomeWeather reads ("晴", "多云", "阴", "小雨", "雪", ...).
    private static final int W_CLEAR = 0;
    private static final int W_CLOUDY = 1;
    private static final int W_OVERCAST = 2;
    private static final int W_RAIN = 3;
    private static final int W_THUNDER = 4;
    private static final int W_SNOW = 5;
    private static final int W_FOG = 6;

    /** Opacity of the whole backdrop, 0 to 255, from sky_intensity. */
    private int intensity;
    private int lastMinute = -1;
    private int weatherKind = W_CLEAR;
    /** The drawable currently shown, so a redraw on the same picture is a no-op. */
    private int shownRes;

    public SkyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@code percent} of sky_intensity: 0 hides the backdrop, 100 covers the wallpaper. */
    public void setIntensity(int percent) {
        intensity = Math.max(0, Math.min(100, percent));
        setAlpha(intensity / 100f);
        setVisibility(intensity == 0 ? GONE : VISIBLE);
        if (intensity > 0) render(true);
    }

    /** Weather from HomeWeather's condition text ("晴", "多云", "小雨", "雪"…); swaps the photo when it changes. */
    public void setWeather(@Nullable String condition) {
        int kind = kindOf(condition);
        if (kind == weatherKind) return;
        weatherKind = kind;
        if (intensity > 0 && getVisibility() == VISIBLE) render(true);
    }

    /** Picks up a new time of day; cheap and safe to call every minute. */
    public void update() {
        if (intensity == 0 || getVisibility() != VISIBLE) return;
        render(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (intensity > 0) render(true);
    }

    private static int kindOf(@Nullable String condition) {
        if (condition == null || condition.isEmpty()) return W_CLEAR;
        if (condition.contains("雷")) return W_THUNDER;
        if (condition.contains("雪") || condition.contains("冰")) return W_SNOW;
        if (condition.contains("雨")) return W_RAIN;
        if (condition.contains("雾") || condition.contains("霾") || condition.contains("沙") || condition.contains("尘")) return W_FOG;
        if (condition.contains("阴")) return W_OVERCAST;
        if (condition.contains("多云") || condition.contains("少云")) return W_CLOUDY;
        return W_CLEAR; // 晴 and anything unrecognised
    }

    /** Today's sunrise and sunset at the configured place, or null without one (fixed reference day). */
    @Nullable
    private int[] sunTimes() {
        String lat = getContext().getString(R.string.latitude);
        String lon = getContext().getString(R.string.longitude);
        if (lat.isEmpty() || lon.isEmpty()) return null;
        try {
            return Sun.riseAndSet(LocalDate.now(), Double.parseDouble(lat), Double.parseDouble(lon),
                    ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void render(boolean force) {
        Calendar now = Calendar.getInstance();
        int minute = Sky.minuteOfDay(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        if (!force && minute == lastMinute) return;
        lastMinute = minute;

        int[] sun = sunTimes();
        int rise = sun == null ? Sky.REF_SUNRISE : sun[0];
        int set = sun == null ? Sky.REF_SUNSET : sun[1];
        boolean day = minute >= rise && minute < set;

        int res = photo(day);
        if (res != shownRes) {
            shownRes = res;
            setBackgroundResource(res);
        }
    }

    /** The real photograph for this weather and time of day. Snow and fog reuse the overcast sky. */
    private int photo(boolean day) {
        switch (weatherKind) {
            case W_RAIN:
            case W_THUNDER:
                return day ? R.drawable.weather_rain_day : R.drawable.weather_rain_night;
            case W_CLOUDY:
            case W_OVERCAST:
            case W_FOG:
            case W_SNOW:
                return day ? R.drawable.weather_cloudy_day : R.drawable.weather_cloudy_night;
            default:
                return day ? R.drawable.weather_clear_day : R.drawable.weather_clear_night;
        }
    }
}
