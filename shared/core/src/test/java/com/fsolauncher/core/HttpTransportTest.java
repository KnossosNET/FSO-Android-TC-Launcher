package com.fsolauncher.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class HttpTransportTest {
    private static final HttpTransport.Progress NOOP=(m,d,t)->{};
    private static class Response extends HttpURLConnection {
        final int code; final byte[] data; final Map<String,String> headers = new HashMap<>();
        Response(int code, byte[] data) throws Exception {super(new URL("https://example.test/archive"));this.code=code;this.data=data;}
        public int getResponseCode(){return code;}
        public String getHeaderField(String name){return headers.get(name);}
        public InputStream getInputStream(){return new ByteArrayInputStream(data);}
        public void disconnect(){}
        public boolean usingProxy(){return false;}
        public void connect(){}
    }
    private Models.Archive archive(Path temp,byte[] data) throws Exception {
        Path raw=temp.resolve("original");Files.write(raw,data);
        Models.Archive a=new Models.Archive();a.filename="data.7z";a.filesize=data.length;
        a.checksum=Arrays.asList("sha256",SafeFiles.sha256(raw));
        a.urls=Arrays.asList("https://one.example/data","https://two.example/data");
        return a;
    }
    @Test public void resumesPartialArchiveAndVerifiesFullHash() throws Exception {
        Path root=Files.createTempDirectory("http-resume");
        try {
            Models.Archive a=archive(root,"abcdef".getBytes());
            Files.write(root.resolve(a.checksum.get(1)+".part"),"abc".getBytes());
            Response response=new Response(206,"def".getBytes());response.headers.put("Content-Range","bytes 3-5/6");
            Path complete=new HttpTransport(NOOP,url->response).archive(a,root);
            assertEquals("bytes=3-",response.getRequestProperty("Range"));
            assertEquals("abcdef",new String(Files.readAllBytes(complete)));
        }finally{SafeFiles.deleteTree(root);}
    }
    @Test public void serverIgnoringRangeRestartsSafely() throws Exception {
        Path root=Files.createTempDirectory("http-restart");
        try {
            Models.Archive a=archive(root,"abcdef".getBytes());
            Files.write(root.resolve(a.checksum.get(1)+".part"),"abc".getBytes());
            Path complete=new HttpTransport(NOOP,url->newResponse(200,"abcdef")).archive(a,root);
            assertEquals(6,Files.size(complete));
        }finally{SafeFiles.deleteTree(root);}
    }
    private static Response newResponse(int code,String data) throws IOException {
        try{return new Response(code,data.getBytes());}catch(Exception e){throw new IOException(e);}
    }
    @Test public void corruptMirrorDoesNotActivateArchiveAndNextMirrorCanRecover() throws Exception {
        Path root=Files.createTempDirectory("http-mirror");
        try {
            Models.Archive a=archive(root,"abcdef".getBytes());
            Path complete=new HttpTransport(NOOP,url->newResponse(200,url.getHost().startsWith("one")?"xxxxxx":"abcdef")).archive(a,root);
            assertEquals("abcdef",new String(Files.readAllBytes(complete)));
        }finally{SafeFiles.deleteTree(root);}
    }
    @Test public void invalidResumeRangeFallsBackWithoutAppendingWrongBytes() throws Exception {
        Path root=Files.createTempDirectory("http-range");
        try {
            Models.Archive a=archive(root,"abcdef".getBytes());
            Files.write(root.resolve(a.checksum.get(1)+".part"),"abc".getBytes());
            Path complete=new HttpTransport(NOOP,url->{
                Response r=newResponse(url.getHost().startsWith("one")?206:200,"abcdef");
                r.headers.put("Content-Range","bytes 0-5/6");return r;
            }).archive(a,root);
            assertEquals("abcdef",new String(Files.readAllBytes(complete)));
        }finally{SafeFiles.deleteTree(root);}
    }
    @Test public void httpUrlsAreRefusedBeforeOpeningConnection() throws Exception {
        Path root=Files.createTempDirectory("http-security");
        try{
            Models.Archive a=archive(root,"abcdef".getBytes());a.urls=Collections.singletonList("http://example.test/archive");
            try{new HttpTransport(NOOP,url->{throw new AssertionError("Insecure connection was attempted");}).archive(a,root);fail();}
            catch(IOException expected){}
        }finally{SafeFiles.deleteTree(root);}
    }
}
