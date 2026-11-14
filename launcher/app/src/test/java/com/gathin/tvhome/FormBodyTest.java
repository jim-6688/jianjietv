package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 手机网页提交上来的请求体：换源表单（urlencoded）和 py 上传（multipart）。
 * 这里按浏览器真实发出来的字节走一遍，中文名字和 CRLF 都不能走样。
 */
public class FormBodyTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void urlEncodedKeepsChineseAndTheUrl() {
        Map<String, String> form = FormBody.urlEncoded(bytes(
                "pick=2&name=%E9%A5%AD%E6%80%BB&url=http%3A%2F%2Fexample.com%2Ftv%3Fa%3D1"));
        assertEquals("2", form.get("pick"));
        assertEquals("饭总", form.get("name"));
        assertEquals("http://example.com/tv?a=1", form.get("url"));
    }

    @Test
    public void urlEncodedSurvivesEmptyAndMissingValues() {
        Map<String, String> form = FormBody.urlEncoded(bytes("name=&url=&pick=0&broken"));
        assertEquals("", form.get("name"));
        assertEquals("0", form.get("pick"));
        assertEquals("", form.get("broken"));
    }

    @Test
    public void multipartGivesBackTheFileWithItsChineseName() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\n");
        write(body, "Content-Disposition: form-data; name=\"file\"; filename=\"中文源.py\"\r\n");
        write(body, "Content-Type: application/octet-stream\r\n\r\n");
        write(body, "print('hi')\r\nline2");
        write(body, "\r\n--" + boundary + "--\r\n");

        FormBody.FilePart part = FormBody.multipartFile(body.toByteArray(),
                "multipart/form-data; boundary=" + boundary);
        assertNotNull(part);
        assertEquals("中文源.py", part.name);
        assertEquals("print('hi')\r\nline2", new String(part.data, StandardCharsets.UTF_8));
    }

    @Test
    public void multipartAcceptsAQuotedBoundary() {
        String boundary = "abc123";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\n");
        write(body, "Content-Disposition: form-data; name=\"file\"; filename=\"a.py\"\r\n\r\n");
        write(body, "x = 1\r\n--" + boundary + "--\r\n");

        FormBody.FilePart part = FormBody.multipartFile(body.toByteArray(),
                "multipart/form-data; boundary=\"" + boundary + "\"");
        assertNotNull(part);
        assertEquals("a.py", part.name);
        assertArrayEquals(bytes("x = 1"), part.data);
    }

    @Test
    public void multipartWithoutAFileIsNull() {
        assertNull(FormBody.multipartFile(bytes("nothing"), "multipart/form-data; boundary=x"));
        assertNull(FormBody.multipartFile(bytes("a=1"), "application/x-www-form-urlencoded"));
        assertNull(FormBody.multipartFile(new byte[0], "multipart/form-data; boundary=x"));
    }

    @Test
    public void percentEncodedFileNameIsDecoded() {
        assertEquals("中文.py", FormBody.decodeName("%E4%B8%AD%E6%96%87.py"));
        assertEquals("plain.py", FormBody.decodeName("plain.py"));
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] data = bytes(text);
        out.write(data, 0, data.length);
    }
}
