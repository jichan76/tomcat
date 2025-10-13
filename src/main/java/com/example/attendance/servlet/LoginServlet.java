package com.example.attendance.servlet;

import com.google.gson.JsonObject;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;                 // [ADDED]
import java.util.concurrent.Semaphore;       // [ADDED]
import java.util.concurrent.TimeUnit;        // [ADDED]
import java.util.regex.Matcher;              // [ADDED]
import java.util.regex.Pattern;              // [ADDED]

@WebServlet("/LoginServlet")
public class LoginServlet extends HttpServlet {

    // ----- 튜닝 파라미터 -----
    private static final int MAX_CONCURRENT_ALL    = 24;  // [ADDED]
    private static final int MAX_CONCURRENT_BCRYPT = 4;   // [ADDED]
    private static final int ACQUIRE_TIMEOUT_MS    = 300; // [ADDED]
    private static final int SQL_TIMEOUT_SEC       = 8;   // [ADDED]
    private static final int DB_CONNECT_TO_MS      = 8000;// [ADDED]
    private static final int TARGET_BCRYPT_COST    = 10;  // [ADDED] 재해시 목표 cost

    private static final Semaphore GUARD_ALL    = new Semaphore(MAX_CONCURRENT_ALL, true);   // [ADDED]
    private static final Semaphore GUARD_BCRYPT = new Semaphore(MAX_CONCURRENT_BCRYPT, true);// [ADDED]
    private static final Pattern COST_PAT = Pattern.compile("\\$2[aby]\\$(\\d{2})\\$");       // [ADDED]

    static { // [ADDED]
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            DriverManager.setLoginTimeout(DB_CONNECT_TO_MS/1000);
        } catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }

    private Connection getConnection() throws SQLException { // [CHANGED]
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TO_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_CONNECT_TO_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_CONNECT_TO_MS));
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean acquired = false; // [ADDED]
        try { // [ADDED]
            acquired = GUARD_ALL.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) { response.setStatus(503); response.getWriter().write("{\"result\":\"busy\"}"); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            JsonObject json = new JsonObject();
            try (PrintWriter out = response.getWriter()) {

                String userId = trimOrNull(request.getParameter("id"));
                String password = request.getParameter("password");
                String androidId = trimOrNull(request.getParameter("android_id"));

                if (userId == null || password == null || password.isEmpty()) {
                    response.setStatus(400);
                    json.addProperty("result", "error");
                    json.addProperty("message", "Missing id or password");
                    out.print(json.toString());
                    return;
                }

                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT user_id, password, android_id, name, role, is_first_login, is_initial_admin, department " +
                                     "FROM users WHERE TRIM(user_id) = TRIM(?)")) {

                    ps.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                    ps.setString(1, userId);
                    String selectedUserId, dbPassword, dbAndroidId, name, roleRaw, department;
                    String role = "";
                    int isFirstLogin, isInitialAdmin;

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            json.addProperty("result", "fail");
                            json.addProperty("message", "사용자 없음");
                            out.print(json.toString());
                            return;
                        }
                        selectedUserId  = rs.getString("user_id");     // ★ 추가
                        dbPassword     = rs.getString("password");
                        dbAndroidId    = rs.getString("android_id");
                        name           = rs.getString("name");
                        roleRaw        = rs.getString("role");
                        role           = roleRaw == null ? "" : roleRaw.toLowerCase();
                        isFirstLogin   = rs.getInt("is_first_login");
                        isInitialAdmin = rs.getInt("is_initial_admin");
                        department      = rs.getString("department");  // ★ 추가
                    }

                    // 1) 비밀번호 검증 (BCrypt 병렬폭주 방지)
                    boolean passwordMatched;
                    if (dbPassword != null &&
                            (dbPassword.startsWith("$2a$") || dbPassword.startsWith("$2b$") || dbPassword.startsWith("$2y$"))) {

                        boolean got = GUARD_BCRYPT.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS); // [ADDED]
                        if (!got) { response.setStatus(503); out.print("{\"result\":\"busy\"}"); return; } // [ADDED]
                        try {
                            passwordMatched = BCrypt.checkpw(password, dbPassword);
                        } finally {
                            GUARD_BCRYPT.release(); // [ADDED]
                        }

                        // 성공했고 work factor가 높으면 즉시 재해시(다음 로그인부터 빠름)  // [ADDED]
                        if (passwordMatched) {
                            int cost = extractCost(dbPassword);
                            if (cost > TARGET_BCRYPT_COST) {
                                String rehash = BCrypt.hashpw(password, BCrypt.gensalt(TARGET_BCRYPT_COST));
                                try (PreparedStatement ups = conn.prepareStatement(
                                        "UPDATE users SET password = ?, is_initial_admin = 0 WHERE user_id = ?")) {
                                    ups.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                                    ups.setString(1, rehash);
                                    ups.setString(2, userId);
                                    ups.executeUpdate();
                                } catch (Exception ignore) {}
                            }
                        }
                    } else {
                        // 평문 허용: 초기 관리자만
                        passwordMatched = "admin".equals(role) && isInitialAdmin == 1 && password.equals(dbPassword);
                        if (passwordMatched) {
                            String hashed = BCrypt.hashpw(password, BCrypt.gensalt(TARGET_BCRYPT_COST)); // [CHANGED] cost 10
                            try (PreparedStatement ups = conn.prepareStatement(
                                    "UPDATE users SET password = ?, is_initial_admin = 0 WHERE user_id = ?")) {
                                ups.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                                ups.setString(1, hashed);
                                ups.setString(2, userId);
                                ups.executeUpdate();
                                isInitialAdmin = 0;
                            }
                        }
                    }

                    if (!passwordMatched) {
                        json.addProperty("result", "fail");
                        json.addProperty("message", "비밀번호 틀림");
                        out.print(json.toString());
                        return;
                    }

                    // 2) Android ID 정책 (쿼리들에 타임아웃 부여)
                    if ("student".equals(role)) {
                        if (androidId == null || androidId.isEmpty()) {
                            response.setStatus(400);
                            json.addProperty("result", "fail");
                            json.addProperty("message", "학생은 안드로이드 기기 등록이 필요합니다");
                            out.print(json.toString());
                            return;
                        }
                        if (dbAndroidId == null || dbAndroidId.isEmpty()) {
                            try (PreparedStatement ups = conn.prepareStatement(
                                    "UPDATE users SET android_id = ? WHERE user_id = ?")) {
                                ups.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                                ups.setString(1, androidId);
                                ups.setString(2, userId);
                                ups.executeUpdate();
                            }
                        } else if (!dbAndroidId.equals(androidId)) {
                            json.addProperty("result", "fail");
                            json.addProperty("message", "등록된 기기와 다릅니다");
                            out.print(json.toString());
                            return;
                        }
                    } else {
                        if (dbAndroidId != null && !dbAndroidId.isEmpty()
                                && androidId != null && !androidId.isEmpty()
                                && !dbAndroidId.equals(androidId)) {
                            json.addProperty("result", "fail");
                            json.addProperty("message", "등록된 기기와 다릅니다");
                            out.print(json.toString());
                            return;
                        }
                    }

                    // 3) 성공 응답
                    json.addProperty("result", "success");
                    json.addProperty("role", role);
                    json.addProperty("name", name);
                    json.addProperty("user_id", selectedUserId);
                    json.addProperty("department", department == null ? "" : department); // ★ 학과
                    json.addProperty("is_first_login", isFirstLogin);
                    json.addProperty("is_initial_admin", isInitialAdmin);
                    HttpSession session = request.getSession(true);   // ★ JSESSIONID 발급
                    session.setAttribute("USER_ID", selectedUserId);  // ★ ChangePasswordServlet과 동일 키
                    session.setMaxInactiveInterval(30 * 60);          // (선택) 30분
                    out.print(json.toString());
                }
            } catch (Exception e) {
                response.setStatus(500);
                try (PrintWriter out = response.getWriter()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("result", "error");
                    err.addProperty("message", safe(e.getMessage()));
                    out.print(err.toString());
                }
                e.printStackTrace();
            }
        } catch (InterruptedException ie) { // [ADDED]
            Thread.currentThread().interrupt(); response.setStatus(503);
        } finally { // [ADDED]
            if (acquired) GUARD_ALL.release();
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
    private static int extractCost(String hash) { // [ADDED]
        try { Matcher m = COST_PAT.matcher(hash); if (m.find()) return Integer.parseInt(m.group(1)); }
        catch (Exception ignore) {}
        return TARGET_BCRYPT_COST;
    }
    private static String safe(String s) { return s == null ? "" : s.replace("\"","\\\""); } // [ADDED]
}
