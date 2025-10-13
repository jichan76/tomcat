package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import org.mindrot.jbcrypt.BCrypt;
import com.google.gson.JsonObject;
import java.util.Properties;               // [ADDED]
import java.util.concurrent.Semaphore;     // [ADDED]
import java.util.concurrent.TimeUnit;      // [ADDED]

@WebServlet("/adminChangePassword")
public class AdminChangePasswordServlet extends HttpServlet {

    // [ADDED]
    private static final Semaphore GUARD = new Semaphore(8, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;
    private static final int BCRYPT_COST           = 10; // [ADDED]

    private Connection getConnection() throws Exception { // [CHANGED]
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean ok = false; // [ADDED]
        try { // [ADDED]
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) { response.setStatus(503); response.getWriter().write("{\"result\":\"busy\"}"); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            PrintWriter out = response.getWriter();
            JsonObject jsonResponse = new JsonObject();

            String userId = request.getParameter("userId");
            String tempPassword = request.getParameter("tempPassword");
            String role = request.getParameter("role");

            if (userId == null || tempPassword == null || role == null
                    || userId.trim().isEmpty() || tempPassword.trim().isEmpty() || role.trim().isEmpty()) {
                jsonResponse.addProperty("result", "fail");
                jsonResponse.addProperty("message", "파라미터 누락");
                out.print(jsonResponse.toString());
                return;
            }

            if (!role.equals("student") && !role.equals("professor")) {
                jsonResponse.addProperty("result", "fail");
                jsonResponse.addProperty("message", "역할(role) 값이 잘못되었습니다.");
                out.print(jsonResponse.toString());
                return;
            }

            try (Connection conn = getConnection()) {
                String getPwSql = "SELECT password FROM users WHERE user_id = ?";
                String currentHashedPw = null;
                try (PreparedStatement pwStmt = conn.prepareStatement(getPwSql)) {
                    pwStmt.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                    pwStmt.setString(1, userId);
                    try (ResultSet rs = pwStmt.executeQuery()) {
                        if (rs.next()) currentHashedPw = rs.getString(1);
                    }
                }
                if (currentHashedPw == null) {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "존재하지 않는 사용자입니다.");
                    out.print(jsonResponse.toString());
                    return;
                }
                if (org.mindrot.jbcrypt.BCrypt.checkpw(tempPassword, currentHashedPw)) {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "기존에 사용하던 비밀번호로는 변경할 수 없습니다.");
                    out.print(jsonResponse.toString());
                    return;
                }

                String hashedPassword = BCrypt.hashpw(tempPassword, BCrypt.gensalt(BCRYPT_COST)); // [CHANGED]

                String checkSql = "SELECT COUNT(*) FROM users WHERE user_id = ?";
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                    checkStmt.setString(1, userId);
                    ResultSet rs = checkStmt.executeQuery();
                    if (rs.next() && rs.getInt(1) == 0) {
                        jsonResponse.addProperty("result", "fail");
                        jsonResponse.addProperty("message", "존재하지 않는 사용자입니다.");
                        out.print(jsonResponse.toString());
                        return;
                    }
                }

                String updateSql = "UPDATE users SET password = ?, role = ? WHERE user_id = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                    pstmt.setQueryTimeout(SQL_TIMEOUT_SEC); // [ADDED]
                    pstmt.setString(1, hashedPassword);
                    pstmt.setString(2, role);
                    pstmt.setString(3, userId);

                    int affectedRows = pstmt.executeUpdate();
                    if (affectedRows > 0) {
                        jsonResponse.addProperty("result", "success");
                        jsonResponse.addProperty("message", "비밀번호/역할이 변경되었습니다.");
                    } else {
                        jsonResponse.addProperty("result", "fail");
                        jsonResponse.addProperty("message", "업데이트 실패 (row 없음)");
                    }
                }
            } catch (Exception e) {
                jsonResponse.addProperty("result", "error");
                jsonResponse.addProperty("message", "서버 오류: " + e.getMessage());
                e.printStackTrace();
            }

            out.print(jsonResponse.toString());

        } catch (InterruptedException ie) { // [ADDED]
            Thread.currentThread().interrupt(); response.setStatus(503);
        } finally { // [ADDED]
            if (ok) GUARD.release();
        }
    }
}
