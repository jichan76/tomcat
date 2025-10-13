package com.example.attendance.servlet;

import com.google.gson.JsonObject;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@WebServlet("/ChangePasswordServlet")
public class ChangePasswordServlet extends HttpServlet {

    private static final Semaphore GUARD = new Semaphore(8, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS = 8000;
    private static final int BCRYPT_COST = 10;

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean ok = false;
        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) { response.setStatus(503); response.getWriter().write("{\"result\":\"busy\"}"); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            try (PrintWriter out = response.getWriter()) {

                final String mode        = trimOrNull(request.getParameter("mode"));   // ★ "session" 이면 세션 기반
                final String newPassword = request.getParameter("newPassword");

                if (newPassword == null || newPassword.isEmpty()) {
                    response.setStatus(400);
                    out.print(error("새 비밀번호가 없습니다."));
                    return;
                }
                if (!newPassword.matches("^[a-zA-Z0-9!@#$%^*]{8,20}$") || !hasAtLeastTwoKinds(newPassword)) {
                    response.setStatus(400);
                    out.print(error("비밀번호 규칙을 지켜주세요. (8~20자, 영문/숫자/특수 포함, 최소 2종 조합)"));
                    return;
                }

                // ★ 사용자 식별 분기
                String userId;
                String email = null;

                if ("session".equalsIgnoreCase(mode)) {
                    HttpSession s = request.getSession(false);
                    if (s == null || s.getAttribute("USER_ID") == null) {
                        response.setStatus(401);
                        out.print(error("세션이 만료되었습니다. 다시 로그인하세요."));
                        return;
                    }
                    userId = String.valueOf(s.getAttribute("USER_ID"));
                } else {
                    // 기존: 비밀번호 찾기(RESET) 흐름 – id+email 필요
                    userId = trimOrNull(request.getParameter("id"));
                    email  = trimOrNull(request.getParameter("email"));
                    if (userId == null || email == null) {
                        response.setStatus(400);
                        out.print(error("아이디/이메일이 없습니다."));
                        return;
                    }
                }

                // === DB 처리 ===
                try (Connection conn = getConnection()) {
                    conn.setAutoCommit(false);

                    // 현재 비밀번호 조회 (분기)
                    String q = ("session".equalsIgnoreCase(mode))
                            ? "SELECT PASSWORD FROM USERS WHERE TRIM(USER_ID)=?"
                            : "SELECT PASSWORD FROM USERS WHERE TRIM(USER_ID)=? AND LOWER(EMAIL)=LOWER(?)";

                    String currentPw = null;
                    int matchCnt = 0;
                    try (PreparedStatement ps = conn.prepareStatement(q)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        ps.setString(1, userId);
                        if (!"session".equalsIgnoreCase(mode)) ps.setString(2, email);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) { currentPw = rs.getString(1); matchCnt++; }
                        }
                    }
                    if (matchCnt != 1) {
                        conn.rollback();
                        out.print(fail(matchCnt == 0 ? "사용자를 찾을 수 없습니다." : "중복된 사용자 데이터가 있습니다."));
                        return;
                    }

                    // 기존 비번 동일 방지
                    boolean isHashed   = currentPw != null && (currentPw.startsWith("$2a$") || currentPw.startsWith("$2b$") || currentPw.startsWith("$2y$"));
                    boolean sameAsOld  = isHashed ? BCrypt.checkpw(newPassword, currentPw) : newPassword.equals(currentPw);
                    if (sameAsOld) {
                        conn.rollback();
                        out.print(fail("기존 비밀번호와 동일합니다."));
                        return;
                    }

                    final String hashed = BCrypt.hashpw(newPassword, BCrypt.gensalt(BCRYPT_COST));

                    // 업데이트 (분기)
                    String u = ("session".equalsIgnoreCase(mode))
                            ? "UPDATE USERS SET PASSWORD=?, is_first_login=0 WHERE TRIM(USER_ID)=?"
                            : "UPDATE USERS SET PASSWORD=?, is_first_login=0 WHERE TRIM(USER_ID)=? AND LOWER(EMAIL)=LOWER(?)";

                    int updated;
                    try (PreparedStatement ps = conn.prepareStatement(u)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        ps.setString(1, hashed);
                        ps.setString(2, userId);
                        if (!"session".equalsIgnoreCase(mode)) ps.setString(3, email);
                        updated = ps.executeUpdate();
                    }

                    if (updated == 1) {
                        conn.commit();
                        JsonObject okResp = new JsonObject();
                        okResp.addProperty("result", "success");
                        okResp.addProperty("message", "비밀번호가 성공적으로 변경되었습니다.");
                        okResp.addProperty("is_first_login", 0);
                        out.print(okResp.toString());
                    } else {
                        conn.rollback();
                        out.print(fail("사용자를 찾을 수 없습니다."));
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                response.setStatus(500);
                try (PrintWriter out = response.getWriter()) {
                    out.print(error("서버 오류: " + safeMsg(e.getMessage())));
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); response.setStatus(503);
        } finally {
            if (ok) GUARD.release();
        }
    }

    // --- 유틸 ---
    private static String trimOrNull(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private static boolean hasAtLeastTwoKinds(String s) {
        boolean hasLetter = s.matches(".*[a-zA-Z].*");
        boolean hasDigit  = s.matches(".*\\d.*");
        boolean hasSpec   = s.matches(".*[!@#$%^*].*");
        int kinds = (hasLetter?1:0) + (hasDigit?1:0) + (hasSpec?1:0);
        return kinds >= 2;
    }
    private static String error(String msg) { JsonObject o = new JsonObject(); o.addProperty("result","error"); o.addProperty("message", msg); return o.toString(); }
    private static String fail(String msg)  { JsonObject o = new JsonObject(); o.addProperty("result","fail");  o.addProperty("message", msg); return o.toString(); }
    private static String safeMsg(String s) { if (s == null) return ""; return s.replace("\"","\\\""); }
}
