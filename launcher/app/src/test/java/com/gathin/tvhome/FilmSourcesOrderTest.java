package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Uploaded .py sources outrank the built-in list; order()/pyLabel() are pure Java, so no Android mocks. */
public class FilmSourcesOrderTest {

    private static String[] source(String name, String url) {
        return new String[]{name, url};
    }

    @Test
    public void uploadedPyComesFirst() {
        List<String[]> py = Collections.singletonList(source("本机 py · my", "http://tv/py/my.py"));
        List<String[]> builtIn = Arrays.asList(
                source("饭总主接口", "http://a/tv"), source("肥猫主接口", "http://b/tv"));
        List<String[]> custom = Collections.singletonList(source("自定义", "http://c/tv"));
        List<String[]> all = FilmSources.order(py, builtIn, custom);
        assertEquals(4, all.size());
        assertEquals("http://tv/py/my.py", all.get(0)[1]);
        assertEquals("http://a/tv", all.get(1)[1]);
        assertEquals("http://b/tv", all.get(2)[1]);
        assertEquals("http://c/tv", all.get(3)[1]);
    }

    @Test
    public void duplicateUrlsAreDroppedKeepingTheHighestPriority() {
        List<String[]> py = Collections.singletonList(source("本机 py · same", "http://same/tv"));
        List<String[]> builtIn = Collections.singletonList(source("内置同名地址", "http://same/tv"));
        List<String[]> custom = Collections.singletonList(source("自定义", "http://other/tv"));
        List<String[]> all = FilmSources.order(py, builtIn, custom);
        assertEquals(2, all.size());
        assertEquals("本机 py · same", all.get(0)[0]);
    }

    @Test
    public void emptyPyKeepsTheOldBuiltInOrder() {
        List<String[]> builtIn = Arrays.asList(source("a", "http://a"), source("b", "http://b"));
        List<String[]> all = FilmSources.order(new ArrayList<>(), builtIn, new ArrayList<>());
        assertEquals(2, all.size());
        assertEquals("a", all.get(0)[0]);
        assertEquals("b", all.get(1)[0]);
    }

    @Test
    public void labelStripsThePyExtension() {
        assertEquals("本机 py · abc", FilmSources.pyLabel("abc.py"));
        assertEquals("本机 py · abc", FilmSources.pyLabel("abc.PY"));
        assertEquals("本机 py · abc", FilmSources.pyLabel("abc"));
        assertEquals("本机 py · source", FilmSources.pyLabel(null));
    }
}
