package com.example.attendance.filter;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;

//@WebFilter("/*")
public class GzipFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // gzip 지원하는지 확인
        String ae = request.getHeader("Accept-Encoding");
        boolean supportsGzip = (ae != null && ae.contains("gzip"));

        // 이미 커밋/인코딩 지정이면 패스
        if (!supportsGzip || response.isCommitted() || response.getHeader("Content-Encoding") != null) {
            chain.doFilter(req, res);
            return;
        }

        GzipResponseWrapper wrapped = new GzipResponseWrapper(response);
        try {
            chain.doFilter(req, wrapped);
        } finally {
            wrapped.finish(); // 반드시 닫기
        }
    }

    static class GzipResponseWrapper extends HttpServletResponseWrapper {
        private GzipServletOutputStream gzipOut;
        private PrintWriter writer;
        private boolean headersSet = false;

        GzipResponseWrapper(HttpServletResponse resp) {
            super(resp);
        }

        private void ensureHeaders() {
            if (!headersSet) {
                // 압축 헤더 + Vary
                super.addHeader("Content-Encoding", "gzip");
                super.addHeader("Vary", "Accept-Encoding");
                // Content-Length는 컨테이너가 알아서 chunked로 처리 → 건드리지 않음
                headersSet = true;
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) throw new IllegalStateException("getWriter() already called");
            ensureHeaders();
            if (gzipOut == null) {
                gzipOut = new GzipServletOutputStream(super.getOutputStream());
            }
            return gzipOut;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (gzipOut != null) throw new IllegalStateException("getOutputStream() already called");
            ensureHeaders();
            if (writer == null) {
                gzipOut = new GzipServletOutputStream(super.getOutputStream());
                writer = new PrintWriter(new OutputStreamWriter(gzipOut, getCharacterEncoding()), false);
            }
            return writer;
        }

        @Override public void setContentLength(int len) { /* no-op: gzip 시 모름 */ }
        @Override public void setContentLengthLong(long len) { /* no-op */ }

        void finish() throws IOException {
            // writer/stream 둘 중 실제 사용한 쪽을 "close()" 까지 호출
            if (writer != null) {
                writer.flush();
                writer.close();      // <-- 중요
            } else if (gzipOut != null) {
                gzipOut.finish();
                gzipOut.close();     // <-- 중요
            }
        }
    }

    static class GzipServletOutputStream extends ServletOutputStream {
        private final GZIPOutputStream gzip;
        GzipServletOutputStream(OutputStream out) throws IOException {
            this.gzip = new GZIPOutputStream(out, true);
        }
        @Override public void write(int b) throws IOException { gzip.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { gzip.write(b, off, len); }
        @Override public void flush() throws IOException { gzip.flush(); }
        void finish() throws IOException { gzip.finish(); }
        @Override public void close() throws IOException { gzip.close(); } // <-- 반드시 구현
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener writeListener) {}
    }
}
